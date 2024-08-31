package com.asaas.domain.job

import com.asaas.annotation.JobByServer
import com.asaas.domain.server.ServerConfig
import com.asaas.environment.AsaasEnvironment
import com.asaas.infrastructure.ServerConfigStatus
import com.asaas.job.JobEnvironment
import com.asaas.jobs.server.ServerCheckInJob
import com.asaas.log.AsaasLogger
import com.asaas.utils.ApplicationMonitoringUtils
import com.asaas.utils.CustomDateUtils
import grails.plugins.quartz.GrailsJobClassConstants
import grails.plugins.quartz.JobDescriptor
import org.aspectj.lang.ProceedingJoinPoint

class JobExecutionService {

    static transactional = false

    def featureFlagService
    def jobConfigService
    def jobManagerService

    public void executeIfPossible(ProceedingJoinPoint joinPoint) {
        final Class jobClass = joinPoint.target.class
        final String jobName = jobClass.simpleName

        if (jobName == "ServerCheckInJob" && featureFlagService.isServerCheckInJobTriggerLogEnabled()) {
            AsaasLogger.info("JobExecutionService.executeIfPossible >>> Validando execução checkIn server: ${ServerConfig.getCurrentServerName()}")
        }

        JobConfig jobConfig = JobConfig.query([job: jobName]).get()
        if (!jobConfig) jobConfig = jobConfigService.save(jobName)
        if (!jobConfig) return

        Boolean currentServerCanExecuteJob = checkCurrentServerCanExecuteJob(jobClass, jobConfig)
        if (!currentServerCanExecuteJob) {
            ApplicationMonitoringUtils.dropActiveSpan()
            return
        }

        execute(joinPoint, jobConfig)
    }

    public void disableJobsNotAllowedInCurrentEnvironment() {
        List<JobDescriptor> jobList = jobManagerService.getJobs(JobConfig.INACTIVE_BY_ENVIRONMENT_JOB_GROUP_NAME)
        for (JobDescriptor jobDescriptor : jobList) {
            jobManagerService.removeJob(jobDescriptor.group, jobDescriptor.name)
        }
    }

    public void disableJobsThatNeverRunInCurrentServer() {
        if (!AsaasEnvironment.canRunJobs()) return

        final ServerConfig currentServer = ServerConfig.getCurrentServerConfig()
        final List<String> skipJobsList = [ServerCheckInJob].collect { it.simpleName.toString() }

        try {
            Boolean canDisableJobsOnCurrentServer = JobEnvironment.SERVERS_TO_DISABLE_UNTRIGGERED_JOBS.contains(currentServer.name)
            if (!canDisableJobsOnCurrentServer) return

            List<String> jobThatRunOnlyOnOtherServersList = JobConfig.query([column: "job", "mainServerId[ne]": currentServer.id, "fallbackServerId[ne]": currentServer.id]).list()
            List<JobDescriptor> jobList = jobManagerService.getJobs(GrailsJobClassConstants.DEFAULT_GROUP)
            for (JobDescriptor job : jobList) {
                String jobClassSimpleName = getClass().getClassLoader().loadClass(job.getName()).simpleName
                if (skipJobsList.contains(jobClassSimpleName)) continue

                Boolean jobRunsOnlyOnOtherServers = jobThatRunOnlyOnOtherServersList.contains(jobClassSimpleName)
                if (jobRunsOnlyOnOtherServers) jobManagerService.removeJob(job.group, job.name)
            }
        } catch (Exception exception) {
            AsaasLogger.error("JobExecutionService.disableJobsThatNeverRunInCurrentServer >> Erro ao desabilitar jobs no servidor [${currentServer?.name}]", exception)
        }
    }

    public Boolean checkCurrentServerCanExecuteJob(Class jobClass, JobConfig jobConfig) {
        if (jobConfig.stopped) return false

        Boolean runOnAllServers = jobClass.getMethod("execute").getAnnotation(JobByServer).runOnAllServers()
        if (runOnAllServers) return true

        Boolean isValidDateToExecute = !jobConfig.nextExecution || jobConfig.nextExecution <= new Date()
        if (!isValidDateToExecute) return false

        if (AsaasEnvironment.isDevelopment()) return true

        if (jobConfig.executing) {
            Boolean mustIgnoreExecutingFlag = jobConfig.executingOnServer == ServerConfig.getCurrentServerName()
            if (!mustIgnoreExecutingFlag) return false

            AsaasLogger.warn("JobExecutionService.checkCurrentServerCanExecuteJob >> Ignorando executing da job [${jobConfig.job}] no servidor [${ServerConfig.getCurrentServerName()}]")
        }

        Map currentServerConfigData = ServerConfig.currentServer([columnList: ["id", "status"]]).get()
        if (!currentServerConfigData) {
            AsaasLogger.warn("JobConfigService.checkCurrentServerCanExecuteJob >> ServerConfig não encontrado")
            return false
        }

        if ((currentServerConfigData.status as ServerConfigStatus).isUnavailable()) return false

        Boolean isMainServer = jobConfig.mainServerId == currentServerConfigData.id
        if (isMainServer) return true

        Boolean isFallbackServer = jobConfig.fallbackServerId == currentServerConfigData.id
        if (!isFallbackServer) return false

        Boolean isMainServerRunning = ServerConfig.query([id: jobConfig.mainServerId, status: ServerConfigStatus.RUNNING, exists: true]).get().asBoolean()
        if (isMainServerRunning) return false

        if (isFallbackServer) AsaasLogger.info("JobExecutionService.checkCurrentServerCanExecuteJob >> Executando job [${jobConfig.job}] no servidor de fallback [${ServerConfig.getCurrentServerName()}]")

        return true
    }

    private void execute(ProceedingJoinPoint joinPoint, JobConfig jobConfig) {
        Boolean isMoveToLowLowRecurrenceAPM = false

        if (joinPoint.target.hasProperty("isLowRecurrence")) {
            isMoveToLowLowRecurrenceAPM = Boolean.valueOf(joinPoint.target.isLowRecurrence)
        }

        ApplicationMonitoringUtils.updateJobSpan(jobConfig.job, isMoveToLowLowRecurrenceAPM)

        jobConfigService.setAsExecuting(jobConfig, ServerConfig.getCurrentServerName())

        if (featureFlagService.isJobExecutionHistoryLogEnabled()) {
            AsaasLogger.info("JobExecutionService.execute >>> Executando Job ${jobConfig.job} - ${CustomDateUtils.fromDate(jobConfig.startDate, "yyyy-MM-dd'T'HH:mm:ss.SSS")}")
        }

        try {
            joinPoint.proceed()
        } catch (Throwable throwable) {
            AsaasLogger.error("JobExecutionService.execute >> Ocorreu um erro não tratado no Job [${jobConfig.job}].", throwable)
        }

        jobConfigService.setAsFinished(jobConfig)

        if (featureFlagService.isJobExecutionHistoryLogEnabled()) {
            AsaasLogger.info("JobExecutionService.execute >>> Finalizado Job ${jobConfig.job} - ${CustomDateUtils.fromDate(jobConfig.finishDate, "yyyy-MM-dd'T'HH:mm:ss.SSS")}")
        }
    }
}

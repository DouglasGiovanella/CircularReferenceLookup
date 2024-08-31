package com.asaas.domain.job

import com.asaas.domain.server.ServerConfig
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class JobPausingService {

    def jobConfigService
    def jobManagerService

    public void stopTriggeringJobs() {
        AsaasLogger.info("DEPLOY >> Pausando triggers de Jobs do servidor [${ServerConfig.getCurrentServerName()}]")
        jobManagerService.pauseAll()
    }

    public void awaitForJobsToPauseOrInterruptIfTimedOut(Integer timeoutInMinutes) {
        if (!timeoutInMinutes) throw new RuntimeException("Informe um tempo limite para pausar as jobs")

        Boolean allJobsArePaused = awaitForJobsToPauseGracefully(timeoutInMinutes)
        if (!allJobsArePaused) {
            interruptRunningJobs(timeoutInMinutes)
            AsaasLogger.info("DEPLOY >> Prosseguindo com o deploy sem pausar todos os jobs no servidor [${ServerConfig.getCurrentServerName()}]")
        } else {
            AsaasLogger.info("DEPLOY >> Jobs parados no servidor [${ServerConfig.getCurrentServerName()}]")
        }
    }

    private Boolean awaitForJobsToPauseGracefully(Integer timeoutInMinutes) {
        final Date startTime = new Date()
        while (checkIfJobsAreRunning()) {
            Integer elapsedTimeInMinutes = CustomDateUtils.calculateDifferenceInMinutes(startTime, new Date())
            if (elapsedTimeInMinutes >= timeoutInMinutes) break

            sleep(2000)
            AsaasLogger.info("DEPLOY >> ${jobManagerService.getRunningJobs().size()} job(s) ainda rodando no servidor [${ServerConfig.getCurrentServerName()}]: ${listRunningJobsNames().join(", ")}")
        }

        Boolean jobsArePaused = !checkIfJobsAreRunning()
        return jobsArePaused
    }

    private void interruptRunningJobs(Integer timeoutInMinutes) {
        for (String jobName : listRunningJobsNames()) {
            Utils.withNewTransactionAndRollbackOnError({
                JobConfig jobConfig = JobConfig.query([job: jobName]).get()
                if (!jobConfig.executing) return

                preventExecutionOnFallbackServer(jobConfig)
                jobConfigService.setAsFinished(jobConfig)

                AsaasLogger.warn("DEPLOY >> Job não pausou dentro do tempo limite de [${timeoutInMinutes} minutos] no servidor [${ServerConfig.getCurrentServerName()}]:${jobName}")
            }, [logErrorMessage: "JobPausingService.interruptRunningJobs >> Erro inesperado ao interromper a job [${jobName}]"])
        }
    }

    private void preventExecutionOnFallbackServer(JobConfig jobConfig) {
        jobConfig.fallbackServer = null
        jobConfig.save(failOnError: true, flush: true)
    }

    private Boolean checkIfJobsAreRunning() {
        return jobManagerService.getRunningJobs().size() > 0
    }

    private List<String> listRunningJobsNames() {
        return jobManagerService.getRunningJobs().collect {
            return it.jobDetail.key.name.split("\\.").last()
        }
    }
}

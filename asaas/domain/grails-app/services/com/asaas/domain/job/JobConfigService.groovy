package com.asaas.domain.job

import com.asaas.domain.server.ServerConfig
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException
import org.quartz.JobKey
import org.quartz.Trigger

@Transactional
class JobConfigService {

    def grailsApplication
    def quartzScheduler

    public void sleepUntilNextExecutionFromTomorrowWhenFinished(Closure<Boolean> closure) {
        final String fullClassName = closure.owner.class.name
        final Date endOfThisDay = CustomDateUtils.setTimeToEndOfDay(new Date())
        final Date nextTriggerExecutionAfterToday = getNextJobTriggerExecutionAfterDate(fullClassName, endOfThisDay)

        setNextExecutionWhenFinished(nextTriggerExecutionAfterToday, closure)
    }

    public void setNextExecutionToNextDayWhenFinished(Closure<Boolean> closure) {
        setNextExecutionWhenFinished(CustomDateUtils.sumDays(new Date(), 1), closure)
    }

    public void setNextExecutionToNextBusinessDayWhenFinished(Closure<Boolean> closure) {
        setNextExecutionWhenFinished(CustomDateUtils.addBusinessDays(new Date().clearTime(), 1), closure)
    }

    public void setNextExecutionWhenFinished(Date nextExecutionDate, Closure<Boolean> closure) {
        def result = closure.call()
        if (!validateJobResult(result)) return
        if (result) return

        final String jobName = getJobName(closure)

        Utils.withNewTransactionAndRollbackOnError({
            setNextExecutionTo(jobName, nextExecutionDate)
        }, [logErrorMessage: "JobConfigService.setNextExecutionWhenFinished >> Falha ao interromper job ${jobName} de forma temporária"])
    }

    public void stopPermanentlyWhenFinished(Closure<Boolean> closure) {
        def result = closure.call()
        if (!validateJobResult(result)) return
        if (result) return

        final String jobName = getJobName(closure)

        Utils.withNewTransactionAndRollbackOnError({
            setJobAsStopped(jobName)
            AsaasLogger.warn("O job ${jobName} finalizou todo o seu processamento e pode ser removido da codebase.")
        }, [logErrorMessage: "JobConfigService.stopPermanentlyWhenFinished >> Falha ao interromper job ${jobName} de forma permanente"])
    }

    public void setNextExecutionTo(String jobName, Date date) {
        JobConfig jobConfig = JobConfig.query(job: jobName).get()
        jobConfig.nextExecution = date
        jobConfig.save()
    }

    public JobConfig save(String jobName) {
        final String defaultServer = grailsApplication.config.asaas.jobs.server.default
        Boolean currentServerGonnaExecuteTheJob = defaultServer == ServerConfig.getCurrentServerName()
        if (!currentServerGonnaExecuteTheJob) return

        JobConfig validatedDomain = validateSave(jobName)
        if (validatedDomain.hasErrors()) throw new ValidationException("Erro ao salvar configurações da job [${jobName}]", validatedDomain.errors)

        final String defaultFallbackServer = grailsApplication.config.asaas.jobs.server.defaultFallbackServer
        AsaasLogger.info("Creating JobConfig for job [${jobName}]")

        JobConfig jobConfig = new JobConfig()
        jobConfig.job = jobName
        jobConfig.mainServer = ServerConfig.query([name: defaultServer]).get()
        jobConfig.fallbackServer = ServerConfig.query([name: defaultFallbackServer]).get()
        jobConfig.dateCreated = new Date()
        jobConfig.save(failOnError: true)

        return jobConfig
    }

    public Integer deleteAllByJobName(List<String> jobNameList) {
        return JobConfig.where { "in"("job", jobNameList) }
            .deleteAll()
            .toInteger()
    }

    public void setAsExecuting(JobConfig jobConfig, String serverName) {
        try {
            jobConfig.startDate = new Date()
            jobConfig.executing = true
            jobConfig.executingOnServer = serverName
            jobConfig.save(failOnError: true, flush: true)
        } catch (Exception exception) {
            AsaasLogger.error("JobConfigService.setAsExecuting >>> Falha ao definir Job como executando: ${jobConfig.job}", exception)
            throw exception
        }
    }

    public void setAsFinished(JobConfig jobConfig) {
        try {
            jobConfig.finishDate = new Date()
            jobConfig.executing = false
            jobConfig.executingOnServer = null
            jobConfig.save(failOnError: true, flush: true)
        } catch (Exception exception) {
            AsaasLogger.error("JobConfigService.setAsFinished >>> Falha ao definir Job como finalizado: ${jobConfig.job}", exception)
            throw exception
        }
    }

    public void changeServer(JobConfig jobConfig, ServerConfig mainServer, ServerConfig fallbackServer) {
        try {
            validateChangeServer(jobConfig, mainServer, fallbackServer)

            User user = UserUtils.getCurrentUser()
            AsaasLogger.warn("Usuário [${user.username}] mudou servidor da job [${jobConfig.job}] para [${mainServer.name}] e fallback para [${fallbackServer?.name}]")

            jobConfig.mainServer = mainServer
            jobConfig.fallbackServer = fallbackServer
            jobConfig.save(failOnError: true)
        } catch (Exception exception) {
            Boolean isBusinessException = exception instanceof BusinessException
            if (!isBusinessException) AsaasLogger.error("JobConfigService.changeServer >> Falha ao mudar job de servidor [job: ${jobConfig.job}, mainServer: ${mainServer}, fallbackServer: ${fallbackServer}]", exception)

            throw exception
        }
    }

    private void setJobAsStopped(String jobName) {
        JobConfig jobConfig = JobConfig.query(job: jobName).get()
        jobConfig.stopped = true
        jobConfig.save(failOnError: true)
    }

    private JobConfig validateSave(String jobName) {
        JobConfig validatedDomain = new JobConfig()

        if (!jobName) return DomainUtils.addError(validatedDomain, "Informe o nome da job")

        Boolean jobNameIsAlreadyUsed = JobConfig.query([exists: true, job: jobName]).get().asBoolean()
        if (jobNameIsAlreadyUsed) DomainUtils.addError(validatedDomain, "Job [${jobName}] já registrado")

        return validatedDomain
    }

    private Boolean validateJobResult(def result) {
        if (!(result instanceof Boolean)) throw new RuntimeException("O retorno do método executado no job deve ser do tipo Boolean.")

        return true
    }

    private void validateChangeServer(JobConfig jobConfig, ServerConfig mainServer, ServerConfig fallbackServer) {
        if (!jobConfig) throw new BusinessException("Informe a job a ser alterada")

        if (!mainServer) throw new BusinessException("Informe um servidor principal")

        if (mainServer.id == fallbackServer?.id) throw new BusinessException("O servidor principal e o de fallback não podem ser iguais")

        final String serverThatMustNotHaveFallback = "jobs-03"
        if (mainServer.name == serverThatMustNotHaveFallback && fallbackServer) throw new BusinessException("O servidor ${serverThatMustNotHaveFallback} não pode ter um servidor de fallback")
    }

    private Date getNextJobTriggerExecutionAfterDate(String jobName, Date fromDate) {
        final String jobGroup = "GRAILS_JOBS"
        JobKey jobKey = JobKey.jobKey(jobName, jobGroup)
        List<Trigger> triggers = quartzScheduler.getTriggersOfJob(jobKey)
        Date nextExecution

        for (Trigger trigger : triggers) {
            Date nextExecutionDateAfterLimitDate = trigger.getFireTimeAfter(fromDate)

            if (!nextExecution || nextExecutionDateAfterLimitDate < nextExecution) {
                nextExecution = nextExecutionDateAfterLimitDate
            }
        }

        return nextExecution
    }

    private String getJobName(Closure<Boolean> closure) {
        return closure.owner.class.simpleName
    }
}

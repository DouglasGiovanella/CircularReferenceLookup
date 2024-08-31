package com.asaas.service.server

import com.asaas.domain.job.JobConfig
import com.asaas.domain.server.ServerConfig
import com.asaas.infrastructure.ServerConfigStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ServerConfigService {

    private static final Integer LIMIT_OF_SERVER_INACTIVITY_TIME_IN_MINUTES = 3

    def jobConfigService
    def jobManagerService
    def jobPausingService
    def workerManagerService

    public void checkIn(ServerConfig serverConfig) {
        serverConfig.lastCheckIn = new Date()
        serverConfig.save(failOnError: true)

        setAsRunning(serverConfig)
    }

    public void shutdownInactiveServersIfNecessary() {
        List<Long> inactiveServerConfigIdList = ServerConfig.query([
            column: "id",
            "lastCheckIn[le]": CustomDateUtils.sumMinutes(new Date(), LIMIT_OF_SERVER_INACTIVITY_TIME_IN_MINUTES * -1),
            status: ServerConfigStatus.RUNNING
        ]).list()

        for (Long serverConfigId : inactiveServerConfigIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                forceShutdown(ServerConfig.lock(serverConfigId))
            }, [logErrorMessage: "ServerConfigService.shutdownInactiveServersIfNecessary >> Falha ao forçar desligamento do servidor [${serverConfigId}]"])
        }
    }

    public void shutdownCurrentServer() {
        final String currentServerName = ServerConfig.getCurrentServerName()
        final Integer limitInMinutesToTryPausingJobs = 2

        jobPausingService.stopTriggeringJobs()
        workerManagerService.stopTriggeringWorkers()
        sleep(2000)

        Utils.withNewTransactionAndRollbackOnError({
            ServerConfig serverConfig = ServerConfig.query([name: currentServerName]).get()
            serverConfig.lock()
            if (!serverConfig) serverConfig = save(currentServerName)

            setAsUnavailable(serverConfig)
        }, [onError: { Exception exception -> throw exception }])

        workerManagerService.awaitForWorkersToPauseOrInterruptIfTimedOut(limitInMinutesToTryPausingJobs)
        jobPausingService.awaitForJobsToPauseOrInterruptIfTimedOut(limitInMinutesToTryPausingJobs)
    }

    public void wakeUpCurrentServer() {
        final String currentServerName = ServerConfig.getCurrentServerName()

        AsaasLogger.info("ServerConfigService.wakeUpCurrentServer >> Iniciando/Resumindo Jobs do servidor [${currentServerName}]")

        Utils.withNewTransactionAndRollbackOnError({
            ServerConfig serverConfig = ServerConfig.query([name: currentServerName]).get()
            if (!serverConfig) serverConfig = save(currentServerName)

            checkIn(serverConfig)
        }, [onError: { Exception exception -> throw exception }])

        jobManagerService.resumeAll()

        AsaasLogger.info("ServerConfigService.wakeUpCurrentServer >> Jobs iniciados no servidor [${currentServerName}]")
    }

    public ServerConfig save(String serverName) {
        ServerConfig validatedDomain = validateSave(serverName)
        if (validatedDomain.hasErrors()) throw new ValidationException("Falha ao salvar configurações do servidor [${serverName}]", validatedDomain.errors)

        ServerConfig serverConfig = new ServerConfig()
        serverConfig.name = serverName
        serverConfig.status = ServerConfigStatus.RUNNING
        serverConfig.save(failOnError: true)

        return serverConfig
    }

    private ServerConfig validateSave(String serverName) {
        ServerConfig validatedDomain = new ServerConfig()

        if (!serverName) DomainUtils.addError(validatedDomain, "Informe o nome do servidor")

        return validatedDomain
    }

    private void setAsRunning(ServerConfig serverConfig) {
        if (serverConfig.status.isRunning()) return

        serverConfig.status = ServerConfigStatus.RUNNING
        serverConfig.save(failOnError: true)
    }

    private void setAsUnavailable(ServerConfig serverConfig) {
        serverConfig.status = ServerConfigStatus.UNAVAILABLE
        serverConfig.save(failOnError: true)
    }

    private void forceShutdown(ServerConfig serverConfig) {
        if (serverConfig.status.isUnavailable()) return

        AsaasLogger.warn("ServerConfigService.forceShutdown >> Forçando desligamento do servidor [${serverConfig.name}] após [${LIMIT_OF_SERVER_INACTIVITY_TIME_IN_MINUTES} minutos] de inatividade")
        setAsUnavailable(serverConfig)

        List<JobConfig> runningJobConfigList = JobConfig.query([mainServerId: serverConfig.id, executing: true]).list()
        for (JobConfig jobConfig : runningJobConfigList) {
            jobConfigService.setAsFinished(jobConfig)
        }
        AsaasLogger.warn("ServerConfigService.forceShutdown >> Jobs interrompidas abruptamente:\n-${runningJobConfigList*.job.join("\n-")}")
    }
}

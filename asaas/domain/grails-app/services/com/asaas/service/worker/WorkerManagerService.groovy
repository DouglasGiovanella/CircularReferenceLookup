package com.asaas.service.worker

import com.asaas.domain.server.ServerConfig
import com.asaas.domain.worker.WorkerConfig
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.threadpooling.WorkerManager
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class WorkerManagerService {

    public void initialize() {
        if (WorkerManager.instance.initialized) return

        if (!AsaasEnvironment.isDevelopment()) {
            List<String> listRunningWorkersNames = WorkerConfig.active([column: "worker", "interruptedServerName[isNull]": true]).list()
            if (listRunningWorkersNames) {
                AsaasLogger.info("WorkerManagerService.initialize >> Workers ainda rodando em outro servidor " + listRunningWorkersNames.join(", "))
                return
            }
        }

        WorkerManager.instance.initialize()
    }

    public void shutdown() {
        AsaasLogger.info("WorkerManagerService.shutdown >> Parando Workers do servidor [${ServerConfig.getCurrentServerName()}]")
        WorkerManager.instance.shutdown()
    }

    public void stopTriggeringWorkers() {
        if (!WorkerManager.instance.initialized) return

        AsaasLogger.info("DEPLOY >> Parando Workers do servidor [${ServerConfig.getCurrentServerName()}]")
        WorkerManager.instance.stopTriggeringWorkers()
    }

    public void awaitForWorkersToPauseOrInterruptIfTimedOut(Integer timeoutInMinutes) {
        if (!WorkerManager.instance.initialized) return

        if (!timeoutInMinutes) throw new RuntimeException("Informe um tempo limite para pausar as workers")

        Boolean allWorkersArePaused = awaitForWorkersToPauseGracefully(timeoutInMinutes)
        if (!allWorkersArePaused) {
            AsaasLogger.warn("DEPLOY >> Workers não pausaram dentro do tempo limite de [${timeoutInMinutes} minutos] no servidor [${ServerConfig.getCurrentServerName()}]:\n-${listRunningWorkersInCurrentServer().join("\n-")}")
            AsaasLogger.info("DEPLOY >> Prosseguindo com o deploy sem aguardar todos os worker do servidor [${ServerConfig.getCurrentServerName()}]")
            disableFallbackServerForRunningWorkers()
        } else {
            AsaasLogger.info("DEPLOY >> Workers parados no servidor [${ServerConfig.getCurrentServerName()}]")
        }

        WorkerManager.instance.shutdown()
    }

    public void onServerShutdownFailed() {
        List<WorkerConfig> workersInterruptedList = WorkerConfig.query([interruptedServerName: ServerConfig.getCurrentServerName()]).list()
        for (WorkerConfig workerConfig : workersInterruptedList) {
            workerConfig.interruptedServerName = null
            workerConfig.save(failOnError: true)
        }

        WorkerManager.instance.shutdown()
    }

    private Boolean awaitForWorkersToPauseGracefully(Integer timeoutInMinutes) {
        if (!WorkerManager.instance.initialized) return true

        final Date startTime = new Date()
        while (hasWorkerRunningInCurrentServer()) {
            Integer elapsedTimeInMinutes = CustomDateUtils.calculateDifferenceInMinutes(startTime, new Date())
            if (elapsedTimeInMinutes >= timeoutInMinutes) break

            sleep(2000)
            AsaasLogger.info("DEPLOY >> ${listRunningWorkersInCurrentServer().size()} worker(s) ainda rodando no servidor [${ServerConfig.getCurrentServerName()}]: ${listRunningWorkersInCurrentServer().join(", ")}")
        }

        Boolean allWorkersArePaused = !hasWorkerRunningInCurrentServer()
        return allWorkersArePaused
    }

    private Boolean hasWorkerRunningInCurrentServer() {
        return listRunningWorkersInCurrentServer().size() > 0
    }

    private List<String> listRunningWorkersInCurrentServer() {
        return Utils.withNewTransactionAndRollbackOnError({
            return WorkerConfig.active([column: "worker", currentServer: ServerConfig.getCurrentServerName()]).list()
        })
    }

    private void disableFallbackServerForRunningWorkers() {
        Utils.withNewTransactionAndRollbackOnError({
            List<WorkerConfig> workerConfigList = WorkerConfig.active([currentServer: ServerConfig.getCurrentServerName()]).list()

            for (WorkerConfig workerConfig : workerConfigList) {
                workerConfig.interruptedServerName = ServerConfig.getCurrentServerName()
                workerConfig.save(failOnError: true)
            }
        }, [onError: { Exception exception ->
            AsaasLogger.error("${this.getClass().getSimpleName()}.disableFallbackServerForRunningWorkers >> Erro ao tentar interromper worker", exception)
            throw exception
        }, ignoreStackTrace: true])
    }
}

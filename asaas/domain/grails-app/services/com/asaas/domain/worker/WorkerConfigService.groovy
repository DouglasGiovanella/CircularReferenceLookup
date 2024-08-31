package com.asaas.domain.worker

import com.asaas.domain.server.ServerConfig
import com.asaas.log.AsaasLogger
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class WorkerConfigService {

    public WorkerConfig save(String workerName) {
        AsaasLogger.info("WorkerConfigService.saveIfNecessary >> Criando workerConfig para: [${workerName}]")

        WorkerConfig workerConfig = new WorkerConfig()
        workerConfig.worker = workerName
        workerConfig.save(failOnError: true)

        return workerConfig
    }

    public void setAsRunning(String workerName) {
        WorkerConfig workerConfig = WorkerConfig.query([worker: workerName]).get()
        workerConfig.running = true
        workerConfig.save(failOnError: true)
    }

    public void setAsFinished(String workerName) {
        WorkerConfig workerConfig = WorkerConfig.query([worker: workerName]).get()
        workerConfig.running = false
        workerConfig.save(failOnError: true)
    }

    public void processShutdown(String workerName) {
        WorkerConfig workerConfig = WorkerConfig.query([worker: workerName]).get()
        workerConfig.currentServer = null
        workerConfig.interruptedServerName = null
        workerConfig.running = false
        workerConfig.save(failOnError: true)

        AsaasLogger.info("WorkerConfigService.processShutdown >> Finalizado ${workerName}")
    }

    public AsaasError processStart(String workerName) {
        WorkerConfig workerConfig = WorkerConfig.query([worker: workerName]).get()
        if (!workerConfig) workerConfig = save(workerName)

        if (workerConfig.interruptedServerName) {
            String currentServerName = ServerConfig.getCurrentServerName()

            Boolean isRunningOnFallbackServer = workerConfig.interruptedServerName != currentServerName
            if (isRunningOnFallbackServer) {
                AsaasLogger.info("WorkerConfigService.processStart >> Ignorando inicio do worker [${workerName}] pois não parou no servidor principal")
                return new AsaasError("worker.interrupted.previous.server")
            }

            AsaasLogger.info("WorkerConfigService.processStart >> Permitindo inicio do worker [${workerName}] após interrupção anterior no servidor atual")
            workerConfig.interruptedServerName = null
            workerConfig.running = false
        }

        workerConfig.currentServer = ServerConfig.getCurrentServerName()
        workerConfig.save(failOnError: true)
        return null
    }
}

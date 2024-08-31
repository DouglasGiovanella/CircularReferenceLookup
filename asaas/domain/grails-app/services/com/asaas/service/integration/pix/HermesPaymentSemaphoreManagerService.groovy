package com.asaas.service.integration.pix

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.semaphore.save.HermesSavePaymentSemaphoreRequestDTO
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

import static grails.async.Promises.task

@Transactional
class HermesPaymentSemaphoreManagerService {

    public void saveAsync(Long paymentId) {
        task { save(paymentId) }
    }

    private void save(Long paymentId) {
        if (!AsaasEnvironment.isProduction()) return

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/paymentSemaphore", new HermesSavePaymentSemaphoreRequestDTO(paymentId).properties, null)

        if (!hermesManager.isSuccessful()) AsaasLogger.error("HermesPaymentSemaphoreManagerService.save() -> Erro ao salvar Semáforo para a cobrança [payment.id: ${paymentId}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}]")
    }

}

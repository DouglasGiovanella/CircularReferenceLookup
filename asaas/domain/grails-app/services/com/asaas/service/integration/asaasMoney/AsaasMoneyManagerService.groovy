package com.asaas.service.integration.asaasMoney

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.asaasMoney.api.AsaasMoneyManager
import com.asaas.integration.asaasMoney.dto.AsaasMoneyCreatePaymentStatusChangeRequestDTO
import com.asaas.integration.asaasMoney.dto.AsaasMoneyCreatePixTransactionStatusChangeRequestDTO
import com.asaas.integration.asaasMoney.dto.AsaasMoneyUserEnableRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.pix.PixTransactionStatus
import grails.transaction.Transactional

@Transactional
class AsaasMoneyManagerService {

    public void updatePixStatus(String id, String status) {
        AsaasMoneyManager asaasMoneyManager = new AsaasMoneyManager()
        asaasMoneyManager.post("asaas/pix-payment-status-change", new AsaasMoneyCreatePixTransactionStatusChangeRequestDTO(id, PixTransactionStatus.convert(status)).properties)

        if (!asaasMoneyManager.isSuccessful()) {
            AsaasLogger.error("AsaasMoneyManagerService.updatePixStatus >> Erro ao disparar request de atualização de status do PixTransaction ${id} para o status ${status} : ${asaasMoneyManager.getErrorMessage()}")
            throw new RuntimeException(asaasMoneyManager.getErrorMessage())
        }
    }

    public void updatePaymentStatus(String paymentId, String installmentId, String status) {
        AsaasMoneyManager asaasMoneyManager = new AsaasMoneyManager()
        asaasMoneyManager.post("asaas/payment-status-change", new AsaasMoneyCreatePaymentStatusChangeRequestDTO(paymentId, installmentId, PaymentStatus.convert(status)).properties)

        if (!asaasMoneyManager.isSuccessful()) {
            AsaasLogger.error("AsaasMoneyManagerService.updatePaymentStatus >> Erro ao disparar request de atualização de status do Payment ${paymentId} para o status ${status} : ${asaasMoneyManager.getErrorMessage()}")
            throw new RuntimeException(asaasMoneyManager.getErrorMessage())
        }
    }

    public void enableUser(Long userId, String username) {
        if (!AsaasMoneyManager.isAvailable() && !AsaasEnvironment.isProduction()) return
        AsaasMoneyUserEnableRequestDTO requestDTO = new AsaasMoneyUserEnableRequestDTO(userId, username)

        AsaasMoneyManager moneyManager = new AsaasMoneyManager()
        moneyManager.post("asaas/user/enable", requestDTO.properties)

        if (!moneyManager.isSuccessful()) {
            AsaasLogger.error("AsaasMoneyService.enableUser >> Erro ao habilitar usuário [userId: ${userId}]")
            throw new RuntimeException(moneyManager.getErrorMessage())
        }
    }
    public void disableUser(Long userId) {
        if (!AsaasMoneyManager.isAvailable() && !AsaasEnvironment.isProduction()) return
        AsaasMoneyManager moneyManager = new AsaasMoneyManager()
        moneyManager.put("asaas/user/disable/${userId}", null)

        if (!moneyManager.isSuccessful()) {
            AsaasLogger.error("AsaasMoneyService.disableUser >> Erro ao desabilitar usuário [userId: ${userId}]")
            throw new RuntimeException(moneyManager.getErrorMessage())
        }
    }
}

package com.asaas.service.asaascard

import com.asaas.asaascard.AsaasCardType
import com.asaas.asaascard.sandbox.UpdateTypeAdapter
import com.asaas.domain.asaascard.AsaasCardAgreement
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils

import grails.transaction.Transactional

@Transactional
class AsaasCardSandboxService {

    def asaasCardAgreementService
    def asaasCardService
    def bifrostSandboxManagerService

    public void savePurchase(Long asaasCardId) {
        bifrostSandboxManagerService.savePurchase(asaasCardId)
    }

    public void savePurchaseInInstallments(Long asaasCardId) {
        bifrostSandboxManagerService.savePurchaseInInstallments(asaasCardId)
    }

    public void saveWithdrawal(Long asaasCardId) {
        bifrostSandboxManagerService.saveWithdrawal(asaasCardId)
    }

    public void saveRefund(String asaasCardId, Long transactionId) {
        bifrostSandboxManagerService.saveRefund(asaasCardId, transactionId)
    }

    public void updateType(UpdateTypeAdapter updateTypeAdapter) {
        AsaasCardType newType = bifrostSandboxManagerService.getNewCardType(updateTypeAdapter.asaasCard)

        asaasCardService.updateType(updateTypeAdapter.asaasCard, newType)
        saveCreditCardAgreementIfNecessary(updateTypeAdapter)

        bifrostSandboxManagerService.updateCardType(updateTypeAdapter.asaasCard)
    }

    private void saveCreditCardAgreementIfNecessary(UpdateTypeAdapter updateTypeAdapter) {
        if (!updateTypeAdapter.asaasCard.type.isCreditEnabled()) return

        Map asaasCardAgreementParams = [remoteIp: updateTypeAdapter.remoteIp, userAgent: updateTypeAdapter.userAgent, headers: updateTypeAdapter.headers]
        AsaasCardAgreement agreement = asaasCardAgreementService.saveCreditCardAgreementIfNecessary(updateTypeAdapter.asaasCard.customer, updateTypeAdapter.asaasCard.brand, asaasCardAgreementParams, UserUtils.getCurrentUser())
        if (agreement.hasErrors()) {
            AsaasLogger.error("AsaasCardSandboxService.updateTypeAsaas() -> Erro ao salvar o aceite de contrato do cartão. [customerId: ${updateTypeAdapter.customer.id}, asaasCardId: ${updateTypeAdapter.asaasCard.id}]")
            throw new BusinessException("Erro ao salvar o aceite de contrato do cartão.")
        }
    }
}

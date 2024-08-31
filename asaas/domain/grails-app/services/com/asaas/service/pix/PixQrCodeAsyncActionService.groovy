package com.asaas.service.pix

import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.customer.CustomerPixConfig
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixAsaasQrCode
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixQrCodeAsyncActionService {

    def asyncActionService
    def hermesQrCodeManagerService
    def pixAsaasQrCodeService
    def pixQrCodeService

    public void updateDynamicQrCodeIfNecessary(Payment payment) {
        BusinessValidation validatedBusiness = pixQrCodeService.validateCanBeGenerated(payment)
        if (!validatedBusiness.isValid()) return
        if (!CustomerPixConfig.customerCanReceivePaymentWithPixOwnKey(payment.provider)) return

        asyncActionService.saveHermesPixQrCodeUpdate(payment.id)
    }

    public void deleteDynamicQrCodeIfNecessary(Payment payment) {
        if (CustomerPixConfig.query([column: "addressKeyCreated", customer: payment.provider]).get().asBoolean()) {
            asyncActionService.saveHermesPixQrCodeDelete(payment.id)
        }

        PixAsaasQrCode pixAsaasQrCode = PixAsaasQrCode.dynamicWithAsaasAddressKeyAvailableForPayment([payment: payment, customer: payment.provider]).get()
        if (pixAsaasQrCode) {
            pixAsaasQrCodeService.setAsAwaitingSynchronization(pixAsaasQrCode)
        }
    }

    public void restoreDynamicQrCodeIfNecessary(Payment payment) {
        BusinessValidation validatedBusiness = pixQrCodeService.validateCanBeGenerated(payment)
        if (!validatedBusiness.isValid()) return
        if (!CustomerPixConfig.customerCanReceivePaymentWithPixOwnKey(payment.provider)) return

        asyncActionService.saveHermesPixQrCodeRestore(payment.id)
    }

    public void processUpdateDynamicQrCode() {
        List<Map> asyncActionList = asyncActionService.listPendingHermesPixQrCodeUpdate()
        processAsyncAction(asyncActionList)
    }

    public void processDeleteDynamicQrCode() {
        List<Map> asyncActionList = asyncActionService.listPendingHermesPixQrCodeDelete()
        processAsyncAction(asyncActionList)
    }

    public void processRestoreDynamicQrCode() {
        List<Map> asyncActionList = asyncActionService.listPendingHermesPixQrCodeRestore()
        processAsyncAction(asyncActionList)
    }

    private void processAsyncAction(List<Map> asyncActionList) {
        for (Map asyncActionData : asyncActionList) {
            Utils.withNewTransactionAndRollbackOnError({
                AsyncAction asyncAction = AsyncAction.get(asyncActionData.asyncActionId)
                Payment payment = Payment.lock(asyncAction.getDataAsMap().paymentId)

                if (payment.status.hasBeenConfirmed()) {
                    asyncActionService.setAsCancelled(asyncAction)
                    return
                }

                Map response = [:]
                if (asyncAction.type.isHermesPixQrCodeUpdate()) {
                    response = hermesQrCodeManagerService.updateDynamicQrCodeIfNecessary(payment)
                } else if (asyncAction.type.isHermesPixQrCodeDelete()) {
                    response = hermesQrCodeManagerService.deleteDynamicQrCodeIfNecessary(payment)
                } else if (asyncAction.type.isHermesPixQrCodeRestore()) {
                    response = hermesQrCodeManagerService.restoreDynamicQrCodeIfNecessary(payment)
                }

                if (response.withoutExternalResponse) {
                    throw new RuntimeException("Ocorreu um timeout ao processar ${asyncAction.type} de uma Cobrança [payment.id: ${payment.id}]")
                }

                if (!response.success) {
                    throw new RuntimeException("Ocorreu um erro ao processar ${asyncAction.type} de uma Cobrança [payment.id: ${payment.id}]")
                }

                asyncActionService.delete(asyncAction.id)
            }, [ignoreStackTrace: true, onError: { Exception e ->
                AsaasLogger.warn("PixQrCodeService.processAsyncAction() -> Falha de processamento, enviando para reprocessamento se for possível [id: ${asyncActionData.asyncActionId}]", e)

                Utils.withNewTransactionAndRollbackOnError({
                    AsyncAction asyncAction = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                    if (asyncAction.status.isCancelled()) {
                        AsaasLogger.error("PixQrCodeService.processAsyncAction() -> Falha de processamento, quantidade máxima de tentativas atingida AsyncAction [id: ${asyncAction.id}, type: ${asyncAction.type}]")
                    }
                }, [logErrorMessage: "PixQrCodeService.processAsyncAction() -> Falha ao enviar AsyncAction [id: ${asyncActionData.asyncActionId}] para reprocessamento"])
            }])
        }
    }
}

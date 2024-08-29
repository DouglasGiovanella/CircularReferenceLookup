package com.asaas.service.asaasmoney

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixTransaction
import com.asaas.exception.BusinessException
import com.asaas.pix.adapter.qrcode.QrCodeAdapter
import com.asaas.pix.vo.transaction.PixQrCodeDebitVO
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AsaasMoneyPixPaymentService {

    def asaasMoneyManagerService
    def asyncActionService
    def pixDebitService
    def pixQrCodeService

    public PixTransaction save(Map fields, String payerCustomerPublicId) {
        if (!payerCustomerPublicId) throw new BusinessException("Pagador não informado.")

        Customer customer = Customer.query([publicId: payerCustomerPublicId]).get()
        if (!customer) throw new BusinessException("O pagador não foi encontrado.")

        QrCodeAdapter qrCodeAdapter = pixQrCodeService.decode(fields.qrCode.payload, customer)
        if (!qrCodeAdapter) throw new BusinessException("Dados inválidos.")

        return pixDebitService.saveQrCodeDebit(customer, new PixQrCodeDebitVO(customer, fields, qrCodeAdapter, null), fields.authorizationData)
    }

    public void createStatusChangeAsyncAction(PixTransaction pixTransaction) {
        if (!pixTransaction.type.isDebit() && !pixTransaction.type.isDebitRefund()) return

        Boolean hasAsaasMoneyTransactionInfo = AsaasMoneyTransactionInfo.query([exists: true, destinationPixTransaction: pixTransaction]).get().asBoolean()
        if (!hasAsaasMoneyTransactionInfo) return

        AsyncActionType asyncActionType = AsyncActionType.ASAAS_MONEY_PIX_STATUS_CHANGE
        Map asyncActionData = [id: pixTransaction.publicId, status: pixTransaction.status]

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    public void processStatusChangeAsync() {
        for (Map asyncActionData : asyncActionService.listPendingAsaasMoneyPixStatusChange()) {
            Utils.withNewTransactionAndRollbackOnError({
                asaasMoneyManagerService.updatePixStatus(asyncActionData.id, asyncActionData.status)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AsaasMoneyPixPaymentService.processAsyncAction >> Erro ao processar asyncAction do PIX [${asyncActionData.id}] para o Asaas Money. ID: [${asyncActionData.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        }
    }
}

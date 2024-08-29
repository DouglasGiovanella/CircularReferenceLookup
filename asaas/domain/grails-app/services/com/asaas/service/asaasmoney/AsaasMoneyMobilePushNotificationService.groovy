package com.asaas.service.asaasmoney

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.mobilepushnotification.MobilePushNotificationAction
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AsaasMoneyMobilePushNotificationService {

    def mobilePushNotificationService
    def asyncActionService

    public void save(Map params, String receiverCustomerPublicId) {
        Customer customer = Customer.query([publicId: receiverCustomerPublicId]).get()
        if (!customer) throw new BusinessException("O pagador não foi encontrado.")

        saveForCustomer(params, customer)
    }

    public void onPaymentCreated(String cpfCnpj, Long paymentId) {
        Boolean hasCustomer = Customer.notDisabledAccounts([exists: true, cpfCnpj: cpfCnpj]).get().asBoolean()
        if (!hasCustomer) return

        AsyncActionType asyncActionType = AsyncActionType.ASAAS_MONEY_PAYMENT_CREATED_FOR_CUSTOMER
        Map asyncActionData = [cpfCnpj: cpfCnpj, paymentId: paymentId]

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    public void processPaymentCreatedAsync() {
        for (Map asyncActionData : asyncActionService.listPendingAsaasMoneyPaymentCreatedForCustomer()) {
            Utils.withNewTransactionAndRollbackOnError({
                Payment payment = Payment.pendingOrOverdue([id: Long.valueOf(asyncActionData.paymentId)]).get()
                if (payment) {
                    List<Customer> customerList = Customer.notDisabledAccounts([cpfCnpj: asyncActionData.cpfCnpj]).list()
                    for (Customer customer : customerList) {
                        saveForCustomer([action: MobilePushNotificationAction.ASAAS_MONEY_PAYMENT_CREATED_FOR_CUSTOMER, asaasPaymentPublicId: payment.publicId, deepLinkPath: "invoices?id=${payment.publicId}"], customer)
                    }
                }
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "AsaasMoneyMobilePushNotificationService.processPaymentCreatedAsyncAction >> Erro ao processar asyncAction de notificação de pagamento criado [${asyncActionData.id}] para o Asaas Money. ID: [${asyncActionData.asyncActionId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        }
    }

    private void saveForCustomer(Map params, Customer customer) {
        MobilePushNotificationAction action = MobilePushNotificationAction.convert(params.action as String)
        if (!action.isAsaasMoney()) throw new BusinessException("Essa não é uma notificação Asaas Money")

        Map dataMap = [:]
        dataMap.action = action
        if (params.containsKey("paymentPublicId")) dataMap.paymentPublicId = params.paymentPublicId
        if (params.containsKey("asaasPaymentPublicId")) dataMap.asaasPaymentPublicId = params.asaasPaymentPublicId
        if (params.containsKey("deepLinkPath")) dataMap.deeplink = "app://money.asaas.com/${params.deepLinkPath}"

        if (params.containsKey("paymentValue")) params.paymentValue = FormUtils.formatCurrencyWithMonetarySymbol(params.paymentValue)
        if (params.containsKey("cashBackValue")) params.cashbackValue = FormUtils.formatCurrencyWithMonetarySymbol(params.cashbackValue)

        mobilePushNotificationService.notifyAsaasMoney(dataMap, customer, [params.paymentValue, params.cash])
    }
}

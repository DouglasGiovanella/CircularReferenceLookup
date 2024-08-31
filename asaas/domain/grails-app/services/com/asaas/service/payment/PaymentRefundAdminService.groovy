package com.asaas.service.payment

import com.asaas.domain.customer.Customer
import com.asaas.domain.user.User
import com.asaas.domain.payment.Payment
import com.asaas.exception.CustomerNotFoundException
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PaymentRefundAdminService {

    def asyncActionService
    def installmentService
    def messageService
    def paymentRefundService

    public void saveRefundCustomerCreditCardPaymentsInBatchAsyncAction(Long customerId, Long asaasUserId) {
        Boolean customerExists = Customer.query([exists: true, id: customerId]).get().asBoolean()
        if (!customerExists) throw new CustomerNotFoundException("Cliente não encontrado.")

        Map asyncActionData = [:]
        asyncActionData.customerId = customerId
        asyncActionData.asaasUserId = asaasUserId
        asyncActionService.saveRefundCustomerCreditCardPaymentsInBatch(asyncActionData)
    }

    public void refundCustomerCreditCardPaymentsInBatch() {
        for (Map asyncActionData : asyncActionService.listRefundCustomerCreditCardPaymentsInBatch()) {
            Customer customer = Customer.read(asyncActionData.customerId)
            User asaasUser = User.read(asyncActionData.asaasUserId)
            List<Long> paymentIdList = Payment.creditCardConfirmed([column: "id", customer: customer]).list()

            Utils.forEachWithFlushSession(paymentIdList, 10, { Long paymentId ->
                Utils.withNewTransactionAndRollbackOnError({
                    Payment payment = Payment.get(paymentId)
                    if (!payment.isConfirmedOrReceived()) return

                    if (payment.canBeIndividuallyRefunded()) {
                        paymentRefundService.refund(payment, [refundOnAcquirer: true])
                    } else {
                        installmentService.refundCreditCard(payment.installment.id, true, [:])
                    }
                }, [logErrorMessage: "PaymentRefundAdminService.refundCustomerCreditCardPaymentsInBatch >> Falha ao executar chargeback da cobrança, paymentId: [${paymentId}] - customerId: [${asyncActionData.customerId}]"])
            })

            asyncActionService.delete(asyncActionData.asyncActionId)
            messageService.notifyAboutRefundCustomerCreditCardPaymentsInBatch(customer, asaasUser)
        }
    }

    public BusinessValidation canRefundCustomerCreditCardPaymentsInBatch(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!customer.suspectedOfFraud) {
            businessValidation.addError("paymentRefundRule.customerNotSuspectedOfFraud")
            return businessValidation
        }

        if (!customer.accountRejected()) {
            businessValidation.addError("paymentRefundRule.accountNotRejected")
            return businessValidation
        }

        return businessValidation
    }
}

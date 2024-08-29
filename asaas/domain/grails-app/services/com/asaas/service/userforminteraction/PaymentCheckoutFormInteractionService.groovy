package com.asaas.service.userforminteraction

import com.asaas.billinginfo.BillingType
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.userforminteraction.InstallmentUserFormInteraction
import com.asaas.domain.userforminteraction.PaymentUserFormInteraction
import com.asaas.domain.userforminteraction.SubscriptionUserFormInteraction
import com.asaas.domain.userforminteraction.UserFormInteraction
import com.asaas.userFormInteraction.vo.UserFormInteractionInfoVO
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PaymentCheckoutFormInteractionService {

    def userFormInteractionService

    public void saveUserFormInteractionIfNecessary(BillingType billingType, String domainInstanceClass, Long domainInstanceId, List<UserFormInteractionInfoVO> userFormInteractionInfoVOList) {
        if (!userFormInteractionInfoVOList) return
        if (!billingType.isCreditCard() && !billingType.isDebitCard()) return

        Utils.withNewTransactionAndRollbackOnError({
            List<UserFormInteraction> userFormInteractionList = userFormInteractionService.saveList(userFormInteractionInfoVOList)

            if (domainInstanceClass == Payment.class.simpleName) {
                saveUserFormInteractionForPaymentIfNecessary(Payment.load(domainInstanceId), userFormInteractionList)
            } else if (domainInstanceClass == Subscription.class.simpleName) {
                saveUserFormInteractionForSubscriptionIfNecessary(Subscription.load(domainInstanceId), userFormInteractionList)
            } else if (domainInstanceClass == Installment.class.simpleName) {
                saveUserFormInteractionForInstallmentIfNecessary(Installment.load(domainInstanceId), userFormInteractionList)
            }
        }, [logLockAsWarning: true,
            logErrorMessage: "PaymentCheckoutFormInteractionService.saveUserFormInteractionIfNecessary >>>  Erro ao registrar interações de usuário. [className: ${domainInstanceClass}, id: ${domainInstanceId}]"])
    }

    private void saveUserFormInteractionForPaymentIfNecessary(Payment payment, List<UserFormInteraction> userFormInteractionList) {
        for (UserFormInteraction userFormInteraction : userFormInteractionList) {
            PaymentUserFormInteraction paymentUserFormInteraction = new PaymentUserFormInteraction()
            paymentUserFormInteraction.paymentId = payment.id
            paymentUserFormInteraction.userFormInteraction = userFormInteraction
            paymentUserFormInteraction.save(failOnError: true)
        }
    }

    private void saveUserFormInteractionForSubscriptionIfNecessary(Subscription subscription, List<UserFormInteraction> userFormInteractionList) {
        for (UserFormInteraction userFormInteraction : userFormInteractionList) {
            SubscriptionUserFormInteraction subscriptionUserFormInteraction = new SubscriptionUserFormInteraction()
            subscriptionUserFormInteraction.subscriptionId = subscription.id
            subscriptionUserFormInteraction.userFormInteraction = userFormInteraction
            subscriptionUserFormInteraction.save(failOnError: true)
        }
    }

    private void saveUserFormInteractionForInstallmentIfNecessary(Installment installment, List<UserFormInteraction> userFormInteractionList) {
        for (UserFormInteraction userFormInteraction : userFormInteractionList) {
            InstallmentUserFormInteraction installmentUserFormInteraction = new InstallmentUserFormInteraction()
            installmentUserFormInteraction.installmentId = installment.id
            installmentUserFormInteraction.userFormInteraction = userFormInteraction
            installmentUserFormInteraction.save(failOnError: true)
        }
    }
}

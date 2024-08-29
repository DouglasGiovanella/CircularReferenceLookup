package com.asaas.service.riskanalysis

import com.asaas.creditcard.CreditCardTransactionOriginInterface
import com.asaas.domain.auditlog.AuditLogEvent
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.creditcard.CreditCardTransactionOrigin
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentOriginRequesterInfo
import com.asaas.domain.paymentcampaign.PaymentCampaign
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class RiskAnalysisPaymentActionService {

    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService

    public void createRiskAnalysisForPaymentsCreatedAndPaidOnTheSameIp() {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.PAYMENT_CREATED_AND_PAID_ON_THE_SAME_IP
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        Calendar startDate = CustomDateUtils.getInstanceOfCalendar()
        startDate.add(Calendar.MINUTE, -30)

        List<Long> creditCardTransactionOriginIdList = CreditCardTransactionOrigin.query([column: "id", "dateCreated[gt]": startDate.getTime(), "originInterface[in]": CreditCardTransactionOriginInterface.eligibleForPaymentCreatedAndPaidOnTheSameIpRiskAnalysis(), order: "asc"]).list()

        if (!creditCardTransactionOriginIdList) return

        final BigDecimal minValueToAnalysis = 500.00
        final Integer minTransactionCountToAnalysis = 3
        final Integer maxCountOfTransactionsToList = 4

        Utils.forEachWithFlushSession(creditCardTransactionOriginIdList, 20, { Long creditCardTransactionOriginId ->
            Utils.withNewTransactionAndRollbackOnError({
                String creatorRemoteIp
                def domainInstance
                Payment payment
                Customer customer

                CreditCardTransactionOrigin creditCardTransactionOrigin = CreditCardTransactionOrigin.get(creditCardTransactionOriginId)

                if (creditCardTransactionOrigin.installment) {
                    domainInstance = creditCardTransactionOrigin.installment
                    payment = domainInstance.payments[0]
                } else {
                    domainInstance = creditCardTransactionOrigin.payment
                    payment = domainInstance
                }
                customer = domainInstance.getProvider()

                if (domainInstance.value < minValueToAnalysis) {
                    List<Long> creditCardTransactionInfoIdList = CreditCardTransactionInfo.firstInstallment([exists: true, "dateCreated[le]": creditCardTransactionOrigin.dateCreated, paymentProvider: customer]).list(max: maxCountOfTransactionsToList)
                    if (creditCardTransactionInfoIdList.size() <= minTransactionCountToAnalysis) return
                }

                if (creditCardTransactionOrigin.originInterface.isInvoicePaymentCampaign()) {
                    domainInstance = PaymentCampaign.getPaymentCampaignFromPayment(payment)
                }

                if (!domainInstance) return

                if (domainInstance instanceof PaymentCampaign) {
                    creatorRemoteIp = AuditLogEvent.query([column: "remoteIp", eventName: 'INSERT', className: domainInstance.class.simpleName, persistedObjectId: domainInstance.id.toString()]).get()
                } else {
                    creatorRemoteIp = PaymentOriginRequesterInfo.query([column: "remoteIp", paymentId: payment.id]).get()
                }

                if (creatorRemoteIp == creditCardTransactionOrigin.remoteIp) riskAnalysisRequestService.save(customer, riskAnalysisReason, [domainObject: payment])
            }, [logErrorMessage: "RiskAnalysisRequestService.createRiskAnalysisForPaymentsCreatedAndPaidOnTheSameIp -> Erro ao criar analise do creditCardTransactionOriginId [${creditCardTransactionOriginId}]"])
        })
    }
}

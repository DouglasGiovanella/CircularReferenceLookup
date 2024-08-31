package com.asaas.service.paymentinfo

import com.asaas.billinginfo.BillingType
import com.asaas.customer.PersonType
import com.asaas.domain.abtest.AbTestVariant
import com.asaas.domain.customer.Customer
import com.asaas.domain.paymentinfo.PaymentAnticipableInfo
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlement
import com.asaas.domain.transfer.Transfer
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.payment.PaymentStatus
import com.asaas.paymentinfo.PaymentAnticipableInfoStatus
import com.asaas.paymentinfo.PaymentNonAnticipableReason
import com.asaas.receivableanticipation.validator.ReceivableAnticipationNonAnticipableReasonVO
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidationClosures
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentAnticipableInfoMonitoringService {

    def receivableAnticipationValidationCacheService
    def receivableAnticipationValidationService
    def asyncActionService
    def grailsApplication

    public List<Long> setBankSlipAsNotAnticipableWhereDueDateNotInAllowedTime() {
        final PaymentNonAnticipableReason nonAnticipableReason = PaymentNonAnticipableReason.BANK_SLIP_MINIMUM_DAYS_TO_BE_ANTICIPABLE

        List<Long> paymentIdList = PaymentAnticipableInfo.query([
            "column": "payment.id",
            "anticipated": false,
            "status": PaymentAnticipableInfoStatus.ANALYZED,
            "nonAnticipableReason[ne]": nonAnticipableReason,
            "billingType": BillingType.BOLETO,
            "paymentStatus": PaymentStatus.PENDING,
            "dueDate[ge]": new Date().clearTime(),
            "dueDate[lt]": ReceivableAnticipation.getMinimumDateAllowed(),
            "disableSort": true
        ]).list(max: 2500)

        receivableAnticipationValidationService.setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(nonAnticipableReason, [ReceivableAnticipation.MINIMUM_DAYS_TO_BE_ANTICIPABLE.toString()]))

        return paymentIdList
    }


    public List<Long> updatePixAsNotAnticipableWhereDueDateNotInAllowedTime() {
        final PaymentNonAnticipableReason nonAnticipableReason = PaymentNonAnticipableReason.BANK_SLIP_AND_PIX_MINIMUM_DAYS_TO_BE_ANTICIPABLE

        String pixAbTestName = grailsApplication.config.asaas.abtests.pixAnticipation.name
        String pixVariantValue = grailsApplication.config.asaas.abtests.variantB
        Long abTestVariantId = AbTestVariant.query(column: "id", abTestName: pixAbTestName, value: pixVariantValue).get()
        if (!abTestVariantId) {
            if (AsaasEnvironment.isProduction()) throw new BusinessException("Variante não encontrada para o Test A/B")
            return
        }

        List<Long> paymentIdList = PaymentAnticipableInfo.query([
            "column": "payment.id",
            "anticipated": false,
            "status": PaymentAnticipableInfoStatus.ANALYZED,
            "nonAnticipableReason[ne]": nonAnticipableReason,
            "billingType": BillingType.PIX,
            "paymentStatus": PaymentStatus.PENDING,
            "dueDate[ge]": new Date().clearTime(),
            "dueDate[lt]": ReceivableAnticipation.getMinimumDateAllowed(),
            "abTestVariantChoice[exists]": abTestVariantId,
            "disableSort": true
        ]).list(max: 2500)

        receivableAnticipationValidationService.setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(nonAnticipableReason, [ReceivableAnticipation.MINIMUM_DAYS_TO_BE_ANTICIPABLE.toString()]))

        return paymentIdList
    }

    public List<Long> setCreditCardAsNotAnticipableWhereCreditDateNotInAllowedTime() {
        final PaymentNonAnticipableReason nonAnticipableReason = PaymentNonAnticipableReason.CREDIT_CARD_PAYMENT_NOT_IN_ALLOWED_TIME

        List<Long> paymentIdList = PaymentAnticipableInfo.query([
            "column": "payment.id",
            "anticipated": false,
            "status": PaymentAnticipableInfoStatus.ANALYZED,
            "nonAnticipableReason[ne]": nonAnticipableReason,
            "billingType": BillingType.MUNDIPAGG_CIELO,
            "paymentStatus": PaymentStatus.CONFIRMED,
            "paymentCreditDate[ge]": new Date().clearTime(),
            "paymentCreditDate[lt]": ReceivableAnticipation.getMinimumDateAllowed(),
            "disableSort": true
        ]).list(max: 2000)

        receivableAnticipationValidationService.setAsNotAnticipableAndSchedulable(paymentIdList, new ReceivableAnticipationNonAnticipableReasonVO(nonAnticipableReason, [ReceivableAnticipation.MINIMUM_DAYS_TO_BE_ANTICIPABLE.toString()]))

        return paymentIdList
    }

    public void createAsyncActionToSetPaymentsFromCustomersWithOverdueSettlementsAsNotAnticipable() {
        List<Long> customerIdList = listCustomersWithOverdueSettlements()
        for (Long customerId : customerIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                asyncActionService.saveSetPaymentsFromCustomerWithOverdueSettlementsAsNotAnticipable(customerId)
            }, [logErrorMessage: "PaymentAnticipableInfoMonitoringService.createAsyncActionToSetPaymentsFromCustomersWithOverdueSettlementsAsNotAnticipable >> Erro ao salvar AsyncAction para atualização de cobranças de cliente com antecipação vencida para não antecipáveis. customerId: [${customerId}]"])
        }
    }

    public Boolean processCustomersReachingFirstTransferPeriodToAnticipate() {
        List<Map> customerInfoMapList = []
        customerInfoMapList += listInfoFromCustomersReachingFirstTransferPeriodToAnticipate(ReceivableAnticipation.REQUIRED_DAYS_AFTER_TRANSFER_TO_NATURAL_PERSON_ANTICIPATE_BANKSLIP, PersonType.FISICA, BillingType.BOLETO)
        customerInfoMapList += listInfoFromCustomersReachingFirstTransferPeriodToAnticipate(ReceivableAnticipation.REQUIRED_DAYS_AFTER_TRANSFER_TO_NATURAL_PERSON_ANTICIPATE_CREDITCARD, PersonType.FISICA, BillingType.MUNDIPAGG_CIELO)
        customerInfoMapList += listInfoFromCustomersReachingFirstTransferPeriodToAnticipate(ReceivableAnticipation.REQUIRED_DAYS_AFTER_TRANSFER_TO_LEGAL_PERSON_ANTICIPATE_BANKSLIP, PersonType.JURIDICA, BillingType.BOLETO)

        Boolean hasError = false
        for (Map customerInfoMap : customerInfoMapList) {
            receivableAnticipationValidationService.notifyOnFirstTransferReachesMinimumPeriodToAnticipate(Customer.read(customerInfoMap.customerId), customerInfoMap.billingType)

            Utils.withNewTransactionAndRollbackOnError({
                receivableAnticipationValidationService.onCustomerChange(Customer.read(customerInfoMap.customerId))
                receivableAnticipationValidationCacheService.evictIsFirstUse(customerInfoMap.customerId)
            }, [logErrorMessage: "PaymentAnticipableInfoMonitoringService.processCustomersReachingFirstTransferPeriodToAnticipate >> Erro ao processar o cliente [id: ${customerInfoMap.customerId}]",
                onError: { hasError = true }])
        }

        return hasError
    }

    private List<Long> listCustomersWithOverdueSettlements() {
        final Date limitDateAnticipationAwaitingCredit = ReceivableAnticipationValidationClosures.getLimitDateCustomerWithPartnerSettlementAwaitingCredit()
        Map search = [
            distinct: "partnerAcquisition.customer.id",
            status: ReceivableAnticipationPartnerSettlementStatus.AWAITING_CREDIT,
            disableSort: true,
            "debitDate[ge]": limitDateAnticipationAwaitingCredit.clearTime(),
            "debitDate[le]": CustomDateUtils.setTimeToEndOfDay(limitDateAnticipationAwaitingCredit)
        ]

        return ReceivableAnticipationPartnerSettlement.query(search).list()
    }

    private List<Map> listInfoFromCustomersReachingFirstTransferPeriodToAnticipate(Integer numberOfDays, PersonType personType, BillingType billingType) {
        final Date startDate = CustomDateUtils.sumDays(new Date(), numberOfDays * -1).clearTime()
        final Date finishDate = CustomDateUtils.setTimeToEndOfDay(startDate)

        List<Long> customerIdListWithTransferOnDate = Transfer.confirmedToAnticipation([
            "distinct": "customer.id",
            "transferDate[ge]": startDate,
            "transferDate[le]": finishDate,
            "customerPersonType": personType,
            "disableSort": true
        ]).list()
        if (!customerIdListWithTransferOnDate) return []

        Set<Long> customerIdListWithTranferBeforeDate = Transfer.confirmedToAnticipation([
            "distinct": "customer.id",
            "customerId[in]": customerIdListWithTransferOnDate,
            "transferDate[le]": startDate,
            "disableSort": true
        ]).list().toSet()

        return customerIdListWithTransferOnDate
            .findAll { Long customerId -> !customerIdListWithTranferBeforeDate.contains(customerId) }
            .collect({ Long customerId -> return [customerId: customerId, billingType: billingType] })
    }
}

package com.asaas.service.customer

import com.asaas.billinginfo.BillingType
import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.customer.CustomerQueryExecutor
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.payment.PartialPayment
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationCompromisedItem
import com.asaas.payment.PaymentStatus
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CustomerFinancialStatisticsService {

    def customerDocumentFileProxyService

    static final Integer LIMIT_OF_MONTHS_TO_CALCULATE_AVERAGE_MONTHLY_TPV = 3
    static final Integer LIMIT_OF_DAYS_TO_CALCULATE_AVERAGE_MONTHLY_TPV = LIMIT_OF_MONTHS_TO_CALCULATE_AVERAGE_MONTHLY_TPV * 30

    public BigDecimal calculateAverageMonthlyTpv(Customer customer, List<BillingType> billingTypeList) {
        Date startDate = CustomDateUtils.sumDays(CustomDateUtils.getYesterday(), -LIMIT_OF_DAYS_TO_CALCULATE_AVERAGE_MONTHLY_TPV)
        Date endDate = new Date().clearTime()

        Map extraSearchParams = [:]
        if (billingTypeList) extraSearchParams.billingTypeList = billingTypeList

        BigDecimal confirmedValueOnPeriod = sumConfirmedValueOnPeriod(customer.id, startDate, endDate, extraSearchParams)
        BigDecimal receivedValueOnPeriod = sumReceivedValueOnPeriod(customer.id, startDate, endDate, extraSearchParams)

        Map partialPaymentSearch = [customer: customer, "dateCreated[ge]": startDate, "dateCreated[le]": endDate]
        if (billingTypeList) partialPaymentSearch."billingType[in]" = billingTypeList
        BigDecimal partialPaymentSummedValue = PartialPayment.sumValue(partialPaymentSearch).get()

        BigDecimal tpvOnPeriod = confirmedValueOnPeriod + receivedValueOnPeriod + partialPaymentSummedValue
        return tpvOnPeriod / LIMIT_OF_MONTHS_TO_CALCULATE_AVERAGE_MONTHLY_TPV
    }

    public BusinessValidation canCalculateAverageMonthlyTpv(Customer customer, List<BillingType> billingTypeList) {
        BusinessValidation businessValidation = new BusinessValidation()
        Date startDate = CustomDateUtils.sumDays(CustomDateUtils.getYesterday(), -LIMIT_OF_DAYS_TO_CALCULATE_AVERAGE_MONTHLY_TPV)
        Date endDate = new Date().clearTime()

        Boolean hasReceivedPaymentBeforeAnalyzedPeriod = Payment.received([exists: true, customer: customer, "paymentDate[le]": startDate]).get().asBoolean()
        if (!hasReceivedPaymentBeforeAnalyzedPeriod) {
            businessValidation.addError("customerFinancialStatistics.payment.firstReceivedInLastDays", [LIMIT_OF_DAYS_TO_CALCULATE_AVERAGE_MONTHLY_TPV])
            return businessValidation
        }

        Boolean hasReceivedPaymentOnAnalyzedPeriod = Payment.received([exists: true, customer: customer, "paymentDate[ge]": startDate, "paymentDate[le]": endDate, billingTypeList: billingTypeList]).get().asBoolean()
        if (!hasReceivedPaymentOnAnalyzedPeriod && billingTypeList) {
            String message = billingTypeList.collect { Utils.getMessageProperty("billingType.${it.toString()}") }.join(", ")
            businessValidation.addError("customerFinancialStatistics.payment.billingTypeNotReceivedInLastDays", [message, LIMIT_OF_DAYS_TO_CALCULATE_AVERAGE_MONTHLY_TPV])
            return businessValidation
        }

        if (!hasReceivedPaymentOnAnalyzedPeriod) {
            businessValidation.addError("customerFinancialStatistics.payment.notReceivedInLastDays", [LIMIT_OF_DAYS_TO_CALCULATE_AVERAGE_MONTHLY_TPV])
            return businessValidation
        }

        return businessValidation
    }

    public Map buildCustomerStatisticsInfo(Customer customer) {
        Map customerStatistics = [:]

        customerStatistics.firstCreditTransferDate = findFirstCreditTransferDate(customer)
        customerStatistics.numberOfSentInvoices = customerDocumentFileProxyService.count(customer.id, [typeList: [CustomerDocumentType.INVOICE], customer: customer])
        customerStatistics.numberOfCreatedPayments = countCreatedPayments(customer, null)
        customerStatistics.numberOfCreatedBankSlipPayments = countCreatedPayments(customer, BillingType.BOLETO)
        customerStatistics.numberOfBankSlipReceivedPayments = countReceivedPayments(customer, BillingType.BOLETO)
        customerStatistics.numberOfCreditCardReceivedPayments = countReceivedPayments(customer, BillingType.MUNDIPAGG_CIELO)
        customerStatistics.maxBankSlipPaymentsInMonth = CustomerQueryExecutor.getMaxPaymentsInMonth(customer.id, BillingType.BOLETO)
        customerStatistics.maxCreditCardPaymentsInMonth = CustomerQueryExecutor.getMaxPaymentsInMonth(customer.id, BillingType.MUNDIPAGG_CIELO)
        customerStatistics.averageMonthlyValue = calculateAverageMonthlyValue(customer)
        customerStatistics.numberOfCreatedCreditCardPayments = countCreatedPayments(customer, BillingType.MUNDIPAGG_CIELO)

        return customerStatistics
    }

    public BigDecimal calculateNotPaidPaymentsPercentageIndex(Customer customer, List<BillingType> billingTypeList, Boolean shouldAddReceivedInCash) {
        final Integer monthsToCalculatePaymentsPerStatusPercentageIndex = 3

        Date todayMinusThreeMonths = CustomDateUtils.addMonths(new Date().clearTime(), monthsToCalculatePaymentsPerStatusPercentageIndex * -1)

        Map search = [customer: customer, "dueDate[ge]": todayMinusThreeMonths, deleted: true]
        if (billingTypeList) search.billingTypeList = billingTypeList

        Long totalConfirmedPaymentsOnLastMonthsCount = Payment.query(search + [statusList: PaymentStatus.hasBeenConfirmedStatusList()]).count()

        List<PaymentStatus> paymentStatusList = [PaymentStatus.DUNNING_REQUESTED, PaymentStatus.OVERDUE]
        if (shouldAddReceivedInCash) paymentStatusList.add(PaymentStatus.RECEIVED_IN_CASH)

        Long paymentsPerStatusOnLastMonthsCount = Payment.query(search + [statusList: paymentStatusList]).count()

        Long totalPayments = paymentsPerStatusOnLastMonthsCount + totalConfirmedPaymentsOnLastMonthsCount
        if (!totalPayments) return 0

        return (paymentsPerStatusOnLastMonthsCount / totalPayments) * 100
    }

    public BigDecimal calculateBankSlipAndPixAnticipationTotalCompromisedOverCustomerLimitIndex(ReceivableAnticipation anticipation) {
        BigDecimal bankSlipAnticipationLimit = CustomerReceivableAnticipationConfig.query([column: "bankSlipAnticipationLimit", customerId: anticipation.customer.id]).get()
        if (!bankSlipAnticipationLimit) return BigDecimal.ZERO

        BigDecimal bankSlipAndPixAnticipationTotalCompromisedValue = ReceivableAnticipationCompromisedItem.sumValue([
            customer: anticipation.customer,
            "customerAccount.cpfCnpj": anticipation.customerAccount.cpfCnpj,
            "billingType[in]": [BillingType.BOLETO, BillingType.PIX],
            "receivableAnticipationStatus[ne]": ReceivableAnticipationStatus.SCHEDULED
        ]).get()

        return (bankSlipAndPixAnticipationTotalCompromisedValue / bankSlipAnticipationLimit) * 100
    }

    private Date findFirstCreditTransferDate(Customer customer) {
        List<CreditTransferRequest> creditTransferRequestList = CreditTransferRequest.executeQuery("from CreditTransferRequest ctr where ctr.provider = :provider and ctr.status = :confirmedStatus and ctr.deleted = false order by ctr.confirmedDate", [provider: customer, confirmedStatus: CreditTransferRequestStatus.CONFIRMED])

        return creditTransferRequestList[0]?.confirmedDate ?: null
    }

    private Integer countCreatedPayments(Customer customer, BillingType billingType) {
        Map search = [customer: customer]

        if (billingType) search << [billingType: billingType]

        return Payment.query(search).count()
    }

    private Integer countReceivedPayments(Customer customer, BillingType billingType) {
        return Payment.query([customer: customer, status: PaymentStatus.RECEIVED, billingType: billingType]).count()
    }

    private BigDecimal sumConfirmedValueOnPeriod(Long customerId, Date fromDate, Date endDate, Map extraSearch) {
        return Payment.sumValue(extraSearch + [
            customerId: customerId,
            status: PaymentStatus.CONFIRMED,
            "confirmedDate[ge]": fromDate,
            "confirmedDate[le]": endDate
        ]).get() as BigDecimal
    }

    private BigDecimal sumReceivedValueOnPeriod(Long customerId, Date fromDate, Date endDate, Map extraSearch) {
        return Payment.sumValue(extraSearch + [
            customerId: customerId,
            status: PaymentStatus.RECEIVED,
            "paymentDate[ge]": fromDate,
            "paymentDate[le]": endDate
        ]).get() as BigDecimal
    }

    private Double calculateAverageMonthlyValue(Customer customer) {
        Double totalValue = Payment.executeQuery("select sum(p.value) from Payment p where p.provider = :provider and p.deleted = false and p.status = :status", [provider: customer, status: PaymentStatus.RECEIVED])[0]

        Date firstPaymentDate = Payment.executeQuery("select min(p.paymentDate) from Payment p where p.provider = :provider and p.deleted = false and p.status = :status", [provider: customer, status: PaymentStatus.RECEIVED])[0]

        if (!firstPaymentDate) return 0

        Integer differenceInMonths = CustomDateUtils.calculateDifferenceInMonthsIgnoringDays(CustomDateUtils.truncate(firstPaymentDate, Calendar.MONTH), CustomDateUtils.truncate(new Date(), Calendar.MONTH))

        if (!differenceInMonths) return totalValue

        return totalValue / differenceInMonths
    }
}

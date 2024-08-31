package com.asaas.service.api

import com.asaas.api.ApiFinanceParser
import com.asaas.checkout.CheckoutValidator
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.domain.split.PaymentSplit
import com.asaas.payment.PaymentStatus
import com.asaas.split.PaymentSplitStatus
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiFinanceService extends ApiBaseService {

    def accountBalanceReportService
    def apiResponseBuilderService
    def paymentService

    def getCurrentBalance(params) {
        return apiResponseBuilderService.buildSuccess([totalBalance: FinancialTransaction.getCustomerBalance(getProviderInstance(params))])
    }

    def getDetailedBalance(params) {
        Customer customer = getProviderInstance(params)

        Map responseMap = [:]
        responseMap.waitingPaymentValue = paymentService.calculateNetValueByStatus(customer, PaymentStatus.PENDING)
        responseMap.confirmedValue = paymentService.calculateNetValueByStatus(customer, PaymentStatus.CONFIRMED)
        responseMap.balance = FinancialTransaction.getCustomerBalance(customer)

        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        responseMap.transferAllowed = checkoutValidator.customerCanViewCheckout()

        return responseMap
    }

    public Map getPaymentStatistics(Map params) {
        Customer customer = getProviderInstance(params)

        Map query = ApiFinanceParser.parsePaymentStatisticsFilters(customer, params)
        query.customer = customer
        query.timeout = 30

        Map responseMap = [:]
        responseMap.quantity = Payment.query(query).count()
        responseMap.value = Payment.sumValue(query).get()
        responseMap.netValue = Payment.sumNetValue(query).get()

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    public Map getSplitStatistics(Map params) {
        Customer customer = getProviderInstance(params)

        Map splitMap = [:]

        List<PaymentSplitStatus> paymentSplitStatusList = PaymentSplitStatus.getAllowedToCreditStatusList()
        splitMap.income = PaymentSplit.sumTotalValue([destinationCustomer: customer, "status[in]": paymentSplitStatusList]).get()
        splitMap.outcome = PaymentSplit.sumTotalValue([originCustomer: customer, "status[in]": paymentSplitStatusList]).get()

        return apiResponseBuilderService.buildSuccess(splitMap)
    }

    public Map downloadAccountBalanceReport(Map params) {
        Customer customer = getProviderInstance(params)
        Integer year = params.year.toInteger()

        byte[] reportFile = accountBalanceReportService.buildReportFile(customer, year)
        String fileName = "InformeDeSaldoEmConta${year}.pdf"

        return apiResponseBuilderService.buildFile(reportFile, fileName)
    }

    public Map sendByEmail(Map params) {
        Customer customer = getProviderInstance(params)
        Integer year = Utils.toInteger(params.year)

        accountBalanceReportService.requestReportFile(customer, year)

        return apiResponseBuilderService.buildSuccess([:])
    }
}

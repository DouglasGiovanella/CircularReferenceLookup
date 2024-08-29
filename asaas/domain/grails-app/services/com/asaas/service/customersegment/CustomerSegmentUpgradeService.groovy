package com.asaas.service.customersegment

import com.asaas.customer.CustomerSegment
import com.asaas.domain.customer.Customer
import com.asaas.domain.monthlycustomerconfirmedpaymentsummary.MonthlyCustomerConfirmedPaymentSummary
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerSegmentUpgradeService {

    final static Long CORPORATE_MIN_PAYMENT_COUNT = 400
    final static BigDecimal CORPORATE_MIN_PAYMENT_SUM = 200000

    def customerSegmentService

    public List<Long> upgradeAutomatically() {
        List<Long> customerIdList = []

        customerIdList += upgradeCustomerListToCorporate()
        customerIdList += upgradeCustomerListToBusiness()

        return customerIdList
    }

    private List<Long> upgradeCustomerListToCorporate() {
        List<Long> customerIdList = listCustomerIdWithoutAccountOwnerForCorporateSegment()

        if (!customerIdList) customerIdList = listChildAccountIdForCorporateSegment()

        final Integer batchSize = 50
        final Integer flushEvery = 50
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerIdList, batchSize, flushEvery, { Long customerId ->
            Customer customer = Customer.get(customerId)
            customerSegmentService.changeCustomerSegmentAndUpdateAccountManager(customer, CustomerSegment.CORPORATE, true)
        }, [logErrorMessage: "Erro no upgrade para Corporativo ", appendBatchToLogErrorMessage: true])

        return customerIdList
    }

    private List<Long> upgradeCustomerListToBusiness() {
        List<Long> customerIdList = listCustomerIdWithoutAccountOwnerForBusinessSegment()

        if (!customerIdList) customerIdList = listChildAccountIdForBusinessSegment()

        final Integer batchSize = 50
        final Integer flushEvery = 50
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerIdList, batchSize, flushEvery, { Long customerId ->
            Customer customer = Customer.get(customerId)
            customerSegmentService.changeCustomerSegmentAndUpdateAccountManager(customer, CustomerSegment.BUSINESS, true)
        }, [logErrorMessage: "Erro no upgrade para Negócios ", appendBatchToLogErrorMessage: true])

        return customerIdList
    }

    private List<Long> listCustomerIdWithoutAccountOwnerForCorporateSegment() {
        Map search = buildDefaultQueryToCorporateUpgrade()
        search."customerAccountOwner[isNull]" = true

        final Integer maxItemsPerCycle = 500
        List<Long> customerIdList = MonthlyCustomerConfirmedPaymentSummary.readyToCorporateSegment(search, CORPORATE_MIN_PAYMENT_COUNT, CORPORATE_MIN_PAYMENT_SUM).list(maxItemsPerCycle)

        return customerIdList
    }

    private List<Long> listChildAccountIdForCorporateSegment() {
        Map defaultQuery = buildDefaultQueryToCorporateUpgrade()
        defaultQuery."customerAccountOwner[isNotNull]" = true
        final Integer maxItemsPerCycle = 500

        Map search = [:]
        search."customerAccountOwnerSegment[in]" = [CustomerSegment.SMALL, CustomerSegment.BUSINESS]
        List<Long> customerIdList = MonthlyCustomerConfirmedPaymentSummary.readyToCorporateSegment(defaultQuery + search, CORPORATE_MIN_PAYMENT_COUNT, CORPORATE_MIN_PAYMENT_SUM).list(maxItemsPerCycle)
        if (customerIdList) return customerIdList

        search = [:]
        search."disableAccountOwnerManagerAndSegmentParameter[exists]" = true
        return MonthlyCustomerConfirmedPaymentSummary.readyToCorporateSegment(defaultQuery + search, CORPORATE_MIN_PAYMENT_COUNT, CORPORATE_MIN_PAYMENT_SUM).list(maxItemsPerCycle)
    }

    private List<Long> listCustomerIdWithoutAccountOwnerForBusinessSegment() {
        Map search = buildDefaultQueryToBusinessUpgrade()
        search."customerAccountOwner[isNull]" = true

        final Integer maxItemsPerCycle = 500
        List<Long> customerIdList = MonthlyCustomerConfirmedPaymentSummary.query(search).list(maxItemsPerCycle)

        return customerIdList
    }

    private List<Long> listChildAccountIdForBusinessSegment() {
        Map defaultQuery = buildDefaultQueryToBusinessUpgrade()
        defaultQuery."customerAccountOwner[isNotNull]" = true
        final Integer maxItemsPerCycle = 500

        Map search = [:]
        search."customerAccountOwnerSegment[in]" = [CustomerSegment.SMALL, CustomerSegment.BUSINESS]
        List<Long> customerIdList = MonthlyCustomerConfirmedPaymentSummary.query(defaultQuery + search).list(maxItemsPerCycle)
        if (customerIdList) return customerIdList

        search = [:]
        search."disableAccountOwnerManagerAndSegmentParameter[exists]" = true
        return MonthlyCustomerConfirmedPaymentSummary.query(defaultQuery + search).list(maxItemsPerCycle)
    }

    private Map buildDefaultQueryToCorporateUpgrade() {
        Map search = customerSegmentService.buildDefaultSearchToUpgradeOrDowngradeSegment()
        search."customerSegment[in]" = [CustomerSegment.SMALL, CustomerSegment.BUSINESS]

        return search
    }

    private Map buildDefaultQueryToBusinessUpgrade() {
        Map search = customerSegmentService.buildDefaultSearchToUpgradeOrDowngradeSegment()
        search.customerSegment = CustomerSegment.SMALL
        search."totalValue[ge]" = customerSegmentService.BUSINESS_MIN_PAYMENT_SUM

        return search
    }
}

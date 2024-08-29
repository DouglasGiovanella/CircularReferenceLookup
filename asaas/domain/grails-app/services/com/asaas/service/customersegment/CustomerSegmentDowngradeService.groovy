package com.asaas.service.customersegment

import com.asaas.customer.CustomerSegment
import com.asaas.domain.customer.Customer
import com.asaas.domain.monthlycustomerconfirmedpaymentsummary.MonthlyCustomerConfirmedPaymentSummary
import com.asaas.stage.StageCode
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerSegmentDowngradeService {

    def customerSegmentService

    public List<Long> downgradeAutomatically() {
        List<Long> customerIdList = listCustomerIdWithoutAccountOwnerForSmallSegment()

        if (!customerIdList) customerIdList = listChildAccountIdForSmallSegment()

        final Integer batchSize = 50
        final Integer flushEvery = 50
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerIdList, batchSize, flushEvery, { Long customerId ->
            Customer customer = Customer.get(customerId)
            customerSegmentService.changeCustomerSegmentAndUpdateAccountManager(customer, CustomerSegment.SMALL, true)
        }, [logErrorMessage: "Erro no downgrade para Empreendedor ", appendBatchToLogErrorMessage: true])

        return customerIdList
    }

    private List<Long> listCustomerIdWithoutAccountOwnerForSmallSegment() {
        Map search = buildDefaultSearch()
        search."customerAccountOwner[isNull]" = true

        Date threeMonthsAgo = CustomDateUtils.addMonths(new Date().clearTime(), -3)
        final Integer maxItemsPerCycle = 500

        List<Long> customerIdList = MonthlyCustomerConfirmedPaymentSummary.readyToDowngradeToSmallSegment(search, threeMonthsAgo, StageCode.CONVERTED).list(maxItemsPerCycle)
        return customerIdList
    }

    private List<Long> listChildAccountIdForSmallSegment() {
        Map defaultSearch = buildDefaultSearch()
        defaultSearch."customerAccountOwner[isNotNull]" = true

        Date threeMonthsAgo = CustomDateUtils.addMonths(new Date().clearTime(), -3)
        final Integer maxItemsPerCycle = 500

        Map search = [:]
        search."customerAccountOwnerSegment[in]" = [CustomerSegment.SMALL, CustomerSegment.BUSINESS]
        List<Long> customerIdList = MonthlyCustomerConfirmedPaymentSummary.readyToDowngradeToSmallSegment(defaultSearch + search, threeMonthsAgo, StageCode.CONVERTED).list(maxItemsPerCycle)
        if (customerIdList) return customerIdList

        search = [:]
        search."disableAccountOwnerManagerAndSegmentParameter[exists]" = true
        return MonthlyCustomerConfirmedPaymentSummary.readyToDowngradeToSmallSegment(defaultSearch + search, threeMonthsAgo, StageCode.CONVERTED).list(maxItemsPerCycle)
    }

    private Map buildDefaultSearch() {
        Map search = customerSegmentService.buildDefaultSearchToUpgradeOrDowngradeSegment()
        search.customerSegment = CustomerSegment.BUSINESS
        search."totalValue[lt]" = customerSegmentService.BUSINESS_MIN_PAYMENT_SUM

        return search
    }
}

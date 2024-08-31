package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiMobileUtils
import com.asaas.billinginfo.ChargeType
import com.asaas.paymentstatistic.PaymentDateFilterType
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.user.UserUtils

import grails.transaction.Transactional

@Transactional
class ApiDashboardService extends ApiBaseService {

    def apiResponseBuilderService
    def dashboardStatisticsService
    def systemMessageService

    def show(params) {
        Customer customer = getProviderInstance(params)

        Map fields = parseRequestParams(params)

        Date startDate = dashboardStatisticsService.getStartDate(fields, customer)
        Date finishDate = dashboardStatisticsService.getFinishDate(fields)

        Map responseMap = [:]

        if (ApiMobileUtils.isMobileAppRequest()) {
            if (UserUtils.getCurrentUser().hasFinancialModulePermission()) {
                responseMap.currentBalance = FinancialTransaction.getCustomerBalance(customer.id)
            }

            responseMap.dashboardStatistics = dashboardStatisticsService.getStatistics(customer.id, startDate, finishDate, fields.chargeType, fields.paymentDateFilterType, fields.receivedInCash, false)
        } else {
            responseMap.currentBalance = FinancialTransaction.getCustomerBalance(customer.id)
            responseMap << dashboardStatisticsService.getStatistics(customer.id, startDate, finishDate, fields.chargeType, fields.paymentDateFilterType, fields.receivedInCash, true)
        }

        return apiResponseBuilderService.buildSuccess(responseMap)
    }

    private Map parseRequestParams(Map params) {
        Map fields = [:]

        if (params.containsKey("period")) {
            fields.period = params.period
        } else {
            fields.period = 'thisYear'
        }

        if (params.containsKey("startDate")) {
            fields.startDate = ApiBaseParser.parseDate(params.startDate)
        }

        if (params.containsKey("finishDate")) {
            fields.finishDate = ApiBaseParser.parseDate(params.finishDate)
        }

        if (params.containsKey("receivedInCash")) {
            fields.receivedInCash = Boolean.valueOf(params.receivedInCash)
        }

        if (params.containsKey("chargeType")) {
            fields.chargeType = ChargeType.convert(params.chargeType)
        }

        if (params.dateType == "PAYMENT") {
            fields.paymentDateFilterType = PaymentDateFilterType.PAYMENT_DATE
        } else if (params.dateType == "CREATED") {
            fields.paymentDateFilterType = PaymentDateFilterType.CREATED_DATE
        } else {
            fields.paymentDateFilterType = PaymentDateFilterType.convert(params.dateType) ?: PaymentDateFilterType.DUE_DATE
        }

        return fields
    }
}

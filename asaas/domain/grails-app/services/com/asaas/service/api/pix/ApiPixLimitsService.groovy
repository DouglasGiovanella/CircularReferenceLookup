package com.asaas.service.api.pix

import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.pix.ApiPixLimitsParser
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestPeriod
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestScope
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestType
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

@Transactional
class ApiPixLimitsService extends ApiBaseService {

    def apiResponseBuilderService
    def pixTransactionCheckoutLimitChangeRequestService
    def pixTransactionCheckoutLimitInitialNightlyHourChangeRequestService
    def pixTransactionCheckoutLimitValuesChangeRequestService

    public Map getLimitsInfo(Map params) {
        Customer customer = getProviderInstance(params)

        Map limitsInfo = [:]
        limitsInfo.accountLimit = CustomerConfig.getCustomerDailyCheckoutLimit(customer)
        limitsInfo.daytimePeriodStart = PixTransactionCheckoutLimit.INITIAL_DAYTIME_PERIOD

        Closure<Boolean> hasPendingRequest = { PixTransactionCheckoutLimitChangeRequestScope scope, PixTransactionCheckoutLimitChangeRequestPeriod period ->
            pixTransactionCheckoutLimitChangeRequestService.hasPending(customer, scope, period, null, null)
        }

        Map limitsByPeriod = [:]
        limitsByPeriod.daytimePeriodLimit = PixTransactionCheckoutLimit.calculateDaytimeLimit(customer)
        limitsByPeriod.nightlyPeriodLimit = PixTransactionCheckoutLimit.getNightlyLimit(customer)
        limitsByPeriod.daytimeTransactionLimit = PixTransactionCheckoutLimit.calculateDaytimeLimitPerTransaction(customer)
        limitsByPeriod.nightlyTransactionLimit = PixTransactionCheckoutLimit.calculateNightlyLimitPerTransaction(customer)
        limitsByPeriod.hasPendingDaytimePeriodRequest = hasPendingRequest(PixTransactionCheckoutLimitChangeRequestScope.GENERAL, PixTransactionCheckoutLimitChangeRequestPeriod.DAYTIME)
        limitsByPeriod.hasPendingNightlyPeriodRequest = hasPendingRequest(PixTransactionCheckoutLimitChangeRequestScope.GENERAL, PixTransactionCheckoutLimitChangeRequestPeriod.NIGHTLY)
        limitsInfo.limitsByPeriod = limitsByPeriod

        Map qrCodeCashLimits = [:]
        qrCodeCashLimits.maximumDaytimePeriodLimit = PixTransactionCheckoutLimit.MAXIMUM_DAYTIME_CASH_VALUE_LIMIT_PER_PERIOD_AND_TRANSACTION
        qrCodeCashLimits.maximumNightlyPeriodLimit = PixTransactionCheckoutLimit.MAXIMUM_NIGHTLY_CASH_VALUE_LIMIT_PER_PERIOD_AND_TRANSACTION
        qrCodeCashLimits.daytimePeriodLimit = PixTransactionCheckoutLimit.calculateCashValueDaytimeLimit(customer)
        qrCodeCashLimits.daytimeTransactionLimit = PixTransactionCheckoutLimit.calculateCashValueDaytimePerTransaction(customer)
        qrCodeCashLimits.nightlyPeriodLimit = PixTransactionCheckoutLimit.calculateCashValueNightlyLimit(customer)
        qrCodeCashLimits.nightlyTransactionLimit = PixTransactionCheckoutLimit.calculateCashValueNightlyLimitPerTransaction(customer)
        qrCodeCashLimits.hasPendingDaytimePeriodRequest = hasPendingRequest(PixTransactionCheckoutLimitChangeRequestScope.CASH_VALUE, PixTransactionCheckoutLimitChangeRequestPeriod.DAYTIME)
        qrCodeCashLimits.hasPendingNightlyPeriodRequest = hasPendingRequest(PixTransactionCheckoutLimitChangeRequestScope.CASH_VALUE, PixTransactionCheckoutLimitChangeRequestPeriod.NIGHTLY)
        limitsInfo.qrCodeCashLimits = qrCodeCashLimits

        Map nightlyPeriod = [:]
        nightlyPeriod.isDefaultNightlyPeriodStart = PixTransactionCheckoutLimit.getInitialNightlyHourConfig(customer) == PixTransactionCheckoutLimit.INITIAL_NIGHTLY_PERIOD
        nightlyPeriod.defaultNightlyPeriodStart = PixTransactionCheckoutLimit.INITIAL_NIGHTLY_PERIOD
        nightlyPeriod.alternativeNightlyPeriodStart = PixTransactionCheckoutLimit.VALID_TIMES_FOR_NIGHTLY_PERIOD[1]

        Map queryParams = [
            exists: true,
            customer: customer,
            type: PixTransactionCheckoutLimitChangeRequestType.CHANGE_NIGHTLY_HOUR,
            period: PixTransactionCheckoutLimitChangeRequestPeriod.NIGHTLY
        ]

        nightlyPeriod.hasPendingNightlyPeriodRequest = PixTransactionCheckoutLimitChangeRequest.requested(queryParams).get().asBoolean()
        limitsInfo.nightlyPeriod = nightlyPeriod

        return apiResponseBuilderService.buildSuccess(limitsInfo)
    }

    public Map saveNightlyPeriod(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixLimitsParser.parseRequestParams(params)

        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = pixTransactionCheckoutLimitInitialNightlyHourChangeRequestService.save(customer, fields.initialNightlyHour, fields)

        if (pixTransactionCheckoutLimitChangeRequest.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(pixTransactionCheckoutLimitChangeRequest)
        }

        return apiResponseBuilderService.buildSuccess([:])
    }

    public Map saveTransactionsLimit(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixLimitsParser.parseRequestParams(params)

        List<PixTransactionCheckoutLimitChangeRequest> pixTransactionCheckoutLimitChangeRequest = pixTransactionCheckoutLimitValuesChangeRequestService.saveGroup(customer, fields)

        if (pixTransactionCheckoutLimitChangeRequest[0].hasErrors()) {
            return apiResponseBuilderService.buildErrorList(pixTransactionCheckoutLimitChangeRequest[0])
        }

        return apiResponseBuilderService.buildSuccess([:])
    }

    public Map requestToken(Map params) {
        Customer customer = getProviderInstance(params)

        Map fields = ApiPixLimitsParser.parseRequestParams(params)

        CriticalActionGroup criticalActionGroup = pixTransactionCheckoutLimitChangeRequestService.requestAuthorizationToken(customer, fields)

        if (criticalActionGroup.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(criticalActionGroup)
        }

        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildGroupResponseItem(criticalActionGroup))
    }
}

package com.asaas.service.pix.limit

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestLimitType
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestPeriod
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestRisk
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestScope
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestType
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class PixTransactionCheckoutLimitValuesChangeRequestService {

    def customerCheckoutLimitService
    def pixTransactionCheckoutLimitChangeRequestService

    public List<PixTransactionCheckoutLimitChangeRequest> saveGroup(Customer customer, Map groupInfo) {
        PixTransactionCheckoutLimitChangeRequest validatePixTransactionCheckoutLimitChangeRequest = validateGroup(customer, groupInfo)
        if (validatePixTransactionCheckoutLimitChangeRequest.hasErrors()) return [validatePixTransactionCheckoutLimitChangeRequest]

        validatePixTransactionCheckoutLimitChangeRequest = pixTransactionCheckoutLimitChangeRequestService.validateCriticalActionAuthorizationToken(customer, groupInfo)
        if (validatePixTransactionCheckoutLimitChangeRequest.hasErrors()) return [validatePixTransactionCheckoutLimitChangeRequest]

        List<PixTransactionCheckoutLimitChangeRequest> pixTransactionCheckoutLimitChangeRequestList = []

        if (groupInfo.limitPerTransaction != null) {
            pixTransactionCheckoutLimitChangeRequestList.add(save(
                customer,
                groupInfo.scope,
                groupInfo.period,
                PixTransactionCheckoutLimitChangeRequestLimitType.PER_TRANSACTION,
                groupInfo.limitPerTransaction))
        }

        if (groupInfo.limitPerPeriod != null) {
            pixTransactionCheckoutLimitChangeRequestList.add(save(
                customer,
                groupInfo.scope,
                groupInfo.period,
                PixTransactionCheckoutLimitChangeRequestLimitType.PER_PERIOD,
                groupInfo.limitPerPeriod))
        }

        return pixTransactionCheckoutLimitChangeRequestList
    }

    public BigDecimal calculateCurrentLimit(Customer customer, PixTransactionCheckoutLimitChangeRequestScope scope, PixTransactionCheckoutLimitChangeRequestPeriod period, PixTransactionCheckoutLimitChangeRequestLimitType limitType) {
        BigDecimal previousLimit

        if (limitType.isPerPeriod()) {
            if (period.isDaytime()) {
                if (scope.isGeneral()) {
                    previousLimit = PixTransactionCheckoutLimit.calculateDaytimeLimit(customer)
                } else if (scope.isCashValue()) {
                    previousLimit = PixTransactionCheckoutLimit.calculateCashValueDaytimeLimit(customer)
                }
            } else if (period.isNightly()) {
                if (scope.isGeneral()) {
                    previousLimit = PixTransactionCheckoutLimit.getNightlyLimit(customer)
                } else if (scope.isCashValue()) {
                    previousLimit = PixTransactionCheckoutLimit.calculateCashValueNightlyLimit(customer)
                }
            }
        } else {
            if (period.isDaytime()) {
                if (scope.isGeneral()) {
                    previousLimit = PixTransactionCheckoutLimit.calculateDaytimeLimitPerTransaction(customer)
                } else if (scope.isCashValue()) {
                    previousLimit = PixTransactionCheckoutLimit.calculateCashValueDaytimePerTransaction(customer)
                }
            } else if (period.isNightly()) {
                if (scope.isGeneral()) {
                    previousLimit = PixTransactionCheckoutLimit.calculateNightlyLimitPerTransaction(customer)
                } else if (scope.isCashValue()) {
                    previousLimit = PixTransactionCheckoutLimit.calculateCashValueNightlyLimitPerTransaction(customer)
                }
            }
        }

        return previousLimit
    }

    private PixTransactionCheckoutLimitChangeRequest save(Customer customer, PixTransactionCheckoutLimitChangeRequestScope scope, PixTransactionCheckoutLimitChangeRequestPeriod period, PixTransactionCheckoutLimitChangeRequestLimitType limitType, BigDecimal value) {
        Map requestInfo = [:]
        requestInfo.previousLimit = calculateCurrentLimit(customer, scope, period, limitType)
        requestInfo.requestedLimit = value
        requestInfo.limitType = limitType
        requestInfo.type = PixTransactionCheckoutLimitChangeRequestType.CHANGE_LIMIT
        requestInfo.scope = scope
        requestInfo.period = period
        requestInfo.risk = estimateRequestRisk(customer, requestInfo.previousLimit, requestInfo.requestedLimit)

        return pixTransactionCheckoutLimitChangeRequestService.save(customer, requestInfo)
    }

    private PixTransactionCheckoutLimitChangeRequest validateGroup(Customer customer, Map groupInfo) {
        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = new PixTransactionCheckoutLimitChangeRequest()

        AsaasError asaasError = PixTransactionCheckoutLimit.changeCanBeRequested(customer)
        if (asaasError) return DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, asaasError.getMessage())

        if (groupInfo.limitPerPeriod == null && groupInfo.limitPerTransaction == null) return DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, "Informe qual limite deverá ser alterado.")

        if (groupInfo.limitPerPeriod != null) {
            pixTransactionCheckoutLimitChangeRequest = validate(customer, groupInfo.scope, groupInfo.period, PixTransactionCheckoutLimitChangeRequestLimitType.PER_PERIOD, groupInfo.limitPerPeriod)
            if (pixTransactionCheckoutLimitChangeRequest.hasErrors()) return pixTransactionCheckoutLimitChangeRequest
        }

        if (groupInfo.limitPerTransaction != null) {
            pixTransactionCheckoutLimitChangeRequest = validate(customer, groupInfo.scope, groupInfo.period, PixTransactionCheckoutLimitChangeRequestLimitType.PER_TRANSACTION, groupInfo.limitPerTransaction)
            if (pixTransactionCheckoutLimitChangeRequest.hasErrors()) return pixTransactionCheckoutLimitChangeRequest
        }

        return validateIfLimitPerTransactionIsLessThanPerPeriod(customer, groupInfo)
    }

    private PixTransactionCheckoutLimitChangeRequest validateIfLimitPerTransactionIsLessThanPerPeriod(Customer customer, Map groupInfo) {
        BigDecimal limitPerTransaction = groupInfo.limitPerTransaction != null ? groupInfo.limitPerTransaction : calculateCurrentLimit(customer, groupInfo.scope, groupInfo.period, PixTransactionCheckoutLimitChangeRequestLimitType.PER_TRANSACTION)
        BigDecimal limitPerPeriod = groupInfo.limitPerPeriod != null ? groupInfo.limitPerPeriod : calculateCurrentLimit(customer, groupInfo.scope, groupInfo.period, PixTransactionCheckoutLimitChangeRequestLimitType.PER_PERIOD)

        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = new PixTransactionCheckoutLimitChangeRequest()
        if (limitPerTransaction > limitPerPeriod) return DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, Utils.getMessageProperty("pixTransactionCheckoutLimitChangeRequest.validate.limitPerTransactionMustBeLessThanPerPeriod"))

        return pixTransactionCheckoutLimitChangeRequest
    }

    private PixTransactionCheckoutLimitChangeRequest validate(Customer customer, PixTransactionCheckoutLimitChangeRequestScope scope, PixTransactionCheckoutLimitChangeRequestPeriod period, PixTransactionCheckoutLimitChangeRequestLimitType limitType, BigDecimal value) {
        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = new PixTransactionCheckoutLimitChangeRequest()

        if (!scope) return DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, "Informe se a alteração deverá ser aplicada no Pix ou no Pix Saque e Pix Troco.")
        if (!period) return DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, "Informe qual o período deve ser aplicada a alteração de limite.")
        if (value < 0) return DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, "Informe um valor maior ou igual a zero.")

        BigDecimal currentLimit = calculateCurrentLimit(customer, scope, period, limitType)
        if (value == currentLimit) return DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, "Informe um valor diferente do limite atual.")

        if (scope.isGeneral() && limitType.isPerPeriod()) {
            Boolean isNecessaryIncreaseCustomerDailyLimit = value > CustomerConfig.getCustomerDailyCheckoutLimit(customer)
            if (isNecessaryIncreaseCustomerDailyLimit) {
                CustomerConfig validatedCustomerConfig = customerCheckoutLimitService.validateDailyCheckoutLimit(customer, value)
                if (validatedCustomerConfig.hasErrors()) return DomainUtils.copyAllErrorsFromObject(validatedCustomerConfig, pixTransactionCheckoutLimitChangeRequest)
            }
        } else if (scope.isCashValue()) {
            BigDecimal maximumCashValueLimit

            if (period.isDaytime()) {
                maximumCashValueLimit = PixTransactionCheckoutLimit.MAXIMUM_DAYTIME_CASH_VALUE_LIMIT_PER_PERIOD_AND_TRANSACTION
            } else if (period.isNightly()) {
                maximumCashValueLimit = PixTransactionCheckoutLimit.MAXIMUM_NIGHTLY_CASH_VALUE_LIMIT_PER_PERIOD_AND_TRANSACTION
            }

            if (value > maximumCashValueLimit) {
                String periodMessage = Utils.getEnumLabel(period)
                String formattedMaximumCashValueLimit = FormUtils.formatCurrencyWithMonetarySymbol(maximumCashValueLimit)

                return DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, "O limite máximo para Pix Saque e Pix Troco ${periodMessage} é ${formattedMaximumCashValueLimit}.")
            }

            BigDecimal currentLimitOnGeneralScope = calculateCurrentLimit(customer, PixTransactionCheckoutLimitChangeRequestScope.GENERAL, period, limitType)
            if (value > currentLimitOnGeneralScope) return DomainUtils.addError(pixTransactionCheckoutLimitChangeRequest, Utils.getMessageProperty("pixTransactionCheckoutLimitChangeRequest.validate.cashValueLimitMustBeLessThanGeneral"))
        }

        return pixTransactionCheckoutLimitChangeRequest
    }

    private PixTransactionCheckoutLimitChangeRequestRisk estimateRequestRisk(Customer customer, BigDecimal previousLimit, BigDecimal requestedLimit) {
        if (requestedLimit < previousLimit) {
            return PixTransactionCheckoutLimitChangeRequestRisk.LOW
        }

        if (requestedLimit > previousLimit && requestedLimit < CustomerConfig.getCustomerDailyCheckoutLimit(customer)) {
            return PixTransactionCheckoutLimitChangeRequestRisk.MEDIUM
        }

        if (customer.suspectedOfFraud) {
            return PixTransactionCheckoutLimitChangeRequestRisk.HIGH
        }

        return PixTransactionCheckoutLimitChangeRequestRisk.HIGH
    }
}

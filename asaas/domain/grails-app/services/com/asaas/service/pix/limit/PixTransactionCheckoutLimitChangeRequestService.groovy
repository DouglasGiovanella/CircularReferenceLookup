package com.asaas.service.pix.limit

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestLimitType
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestPeriod
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestRisk
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestScope
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestStatus
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestType
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class PixTransactionCheckoutLimitChangeRequestService {

    def analysisInteractionService
    def customerAlertNotificationService
    def customerCheckoutLimitService
    def customerInteractionService
    def customerMessageService
    def criticalActionService
    def mobilePushNotificationService
    def pixTransactionCheckoutLimitService

    @Deprecated
    public PixTransactionCheckoutLimitChangeRequest save(Customer customer, BigDecimal value) {
        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = new PixTransactionCheckoutLimitChangeRequest()
        pixTransactionCheckoutLimitChangeRequest.customer = customer
        pixTransactionCheckoutLimitChangeRequest.user = UserUtils.getCurrentUser()
        pixTransactionCheckoutLimitChangeRequest.requestedLimit = value
        pixTransactionCheckoutLimitChangeRequest.previousLimit = PixTransactionCheckoutLimit.getNightlyLimit(customer)
        pixTransactionCheckoutLimitChangeRequest.scope = PixTransactionCheckoutLimitChangeRequestScope.GENERAL
        pixTransactionCheckoutLimitChangeRequest.period = PixTransactionCheckoutLimitChangeRequestPeriod.NIGHTLY
        pixTransactionCheckoutLimitChangeRequest.limitType = PixTransactionCheckoutLimitChangeRequestLimitType.PER_PERIOD
        pixTransactionCheckoutLimitChangeRequest.type = PixTransactionCheckoutLimitChangeRequestType.CHANGE_LIMIT
        pixTransactionCheckoutLimitChangeRequest.risk = PixTransactionCheckoutLimitChangeRequestRisk.HIGH
        pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)

        saveCustomerInteraction(customer, value)
        pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)

        return pixTransactionCheckoutLimitChangeRequest
    }

    public PixTransactionCheckoutLimitChangeRequest setAsAutomaticallyApproved(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        pixTransactionCheckoutLimitChangeRequest.status = PixTransactionCheckoutLimitChangeRequestStatus.AUTOMATICALLY_APPROVED
        pixTransactionCheckoutLimitChangeRequest.analysisDate = new Date()
        return pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)
    }

    public PixTransactionCheckoutLimitChangeRequest cancel(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        BusinessValidation businessValidation = pixTransactionCheckoutLimitChangeRequest.canBeCancelled()
        if (!businessValidation.isValid()) return DomainUtils.copyAllErrorsFromBusinessValidation(businessValidation, pixTransactionCheckoutLimitChangeRequest)

        return setAsCancelled(pixTransactionCheckoutLimitChangeRequest)
    }

    public PixTransactionCheckoutLimitChangeRequest setAsError(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        pixTransactionCheckoutLimitChangeRequest.status = PixTransactionCheckoutLimitChangeRequestStatus.ERROR
        return pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)
    }

    public CriticalActionGroup requestAuthorizationToken(Customer customer, Map params) {
        String hash = buildCriticalActionHash(customer, buildChangeRequestParams(params))
        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.PIX_SAVE_TRANSACTION_CHECKOUT_LIMIT_CHANGE_REQUEST, hash)
    }

    public PixTransactionCheckoutLimitChangeRequest applyChangeRequestAndApproveAutomatically(PixTransactionCheckoutLimitChangeRequest changeRequest) {
        onApproved(changeRequest)
        return setAsAutomaticallyApproved(changeRequest)
    }

    public Boolean hasPending(Customer customer, PixTransactionCheckoutLimitChangeRequestScope scope, PixTransactionCheckoutLimitChangeRequestPeriod period, PixTransactionCheckoutLimitChangeRequestLimitType limitType, PixTransactionCheckoutLimitChangeRequestType type) {
        Map searchParameters = [
            exists: true,
            customer: customer,
            scope: scope,
            period: period
        ]

        if (limitType) {
            searchParameters.limitType = limitType
        }

        if (type) {
            searchParameters."type" = type
        }

        return PixTransactionCheckoutLimitChangeRequest.requested(searchParameters).get().asBoolean()
    }

    public PixTransactionCheckoutLimitChangeRequest validateCriticalActionAuthorizationToken(Customer customer, Map groupInfo) {
        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequestValidatedToken = new PixTransactionCheckoutLimitChangeRequest()

        String hash = buildCriticalActionHash(customer, buildChangeRequestParams(groupInfo))
        BusinessValidation businessValidation = criticalActionService.authorizeSynchronous(customer, groupInfo.tokenParams.groupId, groupInfo.tokenParams.authorizationToken, CriticalActionType.PIX_SAVE_TRANSACTION_CHECKOUT_LIMIT_CHANGE_REQUEST, null, hash)

        if (!businessValidation.isValid()) DomainUtils.addError(pixTransactionCheckoutLimitChangeRequestValidatedToken, businessValidation.getFirstErrorMessage())
        return pixTransactionCheckoutLimitChangeRequestValidatedToken
    }

    public PixTransactionCheckoutLimitChangeRequest save(Customer customer, Map requestInfo) {
        cancelPendingIfExists(customer, requestInfo)

        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = new PixTransactionCheckoutLimitChangeRequest()
        pixTransactionCheckoutLimitChangeRequest.customer = customer
        pixTransactionCheckoutLimitChangeRequest.user = UserUtils.getCurrentUser()
        pixTransactionCheckoutLimitChangeRequest.previousLimit = requestInfo.previousLimit
        pixTransactionCheckoutLimitChangeRequest.requestedLimit = requestInfo.requestedLimit
        pixTransactionCheckoutLimitChangeRequest.scope = requestInfo.scope
        pixTransactionCheckoutLimitChangeRequest.period = requestInfo.period
        pixTransactionCheckoutLimitChangeRequest.limitType = requestInfo.limitType
        pixTransactionCheckoutLimitChangeRequest.type = requestInfo.type
        pixTransactionCheckoutLimitChangeRequest.risk = requestInfo.risk
        pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)

        saveRequestedCustomerInteraction(pixTransactionCheckoutLimitChangeRequest)
        sendEmailForRequestIfNecessary(pixTransactionCheckoutLimitChangeRequest)

        return pixTransactionCheckoutLimitChangeRequest
    }

    public PixTransactionCheckoutLimitChangeRequest awaitingApproval(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        BusinessValidation businessValidation = validateAnalysis(pixTransactionCheckoutLimitChangeRequest, PixTransactionCheckoutLimitChangeRequestStatus.AWAITING_APPROVAL, null)
        if (!businessValidation.isValid()) {
            return DomainUtils.copyAllErrorsFromBusinessValidation(businessValidation, pixTransactionCheckoutLimitChangeRequest)
        }

        pixTransactionCheckoutLimitChangeRequest.status = PixTransactionCheckoutLimitChangeRequestStatus.AWAITING_APPROVAL
        pixTransactionCheckoutLimitChangeRequest.analysisDate = new Date()
        pixTransactionCheckoutLimitChangeRequest.analyst = UserUtils.getCurrentUser()

        return pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)
    }

    public void processAwaitingApproval() {
        final Integer flushEvery = 50
        final Integer maxQueryResults = 500

        List<Long> checkoutLimitChangeRequestIdList = PixTransactionCheckoutLimitChangeRequest.waitingApproval([column: "id"]).list(max: maxQueryResults)

        Utils.forEachWithFlushSession(checkoutLimitChangeRequestIdList, flushEvery, { Long checkoutLimitChangeRequestId ->
            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = PixTransactionCheckoutLimitChangeRequest.get(checkoutLimitChangeRequestId)
                pixTransactionCheckoutLimitChangeRequest = approve(pixTransactionCheckoutLimitChangeRequest)

                if (pixTransactionCheckoutLimitChangeRequest.hasErrors()) {
                    AsaasLogger.error("PixTransactionCheckoutLimitChangeRequestService.processAwaitingApproval() -> Erro ao aprovar a solicitação de alteração do limite Pix [pixTransactionCheckoutLimitChangeRequestId: ${checkoutLimitChangeRequestId}, errorMessage: ${DomainUtils.getFirstValidationMessage(pixTransactionCheckoutLimitChangeRequest)}]")
                }
            }, [logErrorMessage: "PixTransactionCheckoutLimitChangeRequestService.processAwaitingApproval() -> Erro interno ao processar a aprovação de alteração do limite Pix [pixTransactionCheckoutLimitChangeRequestId: ${checkoutLimitChangeRequestId}]"])
        })
    }

    public PixTransactionCheckoutLimitChangeRequest awaitingDenial(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest, String deniedReason) {
        BusinessValidation businessValidation = validateAnalysis(pixTransactionCheckoutLimitChangeRequest, PixTransactionCheckoutLimitChangeRequestStatus.AWAITING_DENIAL, deniedReason)
        if (!businessValidation.isValid()) {
            return DomainUtils.copyAllErrorsFromBusinessValidation(businessValidation, pixTransactionCheckoutLimitChangeRequest)
        }

        pixTransactionCheckoutLimitChangeRequest.status = PixTransactionCheckoutLimitChangeRequestStatus.AWAITING_DENIAL
        pixTransactionCheckoutLimitChangeRequest.deniedReason = deniedReason
        pixTransactionCheckoutLimitChangeRequest.analysisDate = new Date()
        pixTransactionCheckoutLimitChangeRequest.analyst = UserUtils.getCurrentUser()

        return pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)
    }

    public void processAwaitingDenial() {
        final Integer flushEvery = 50
        final Integer maxQueryResults = 500

        List<Long> checkoutLimitChangeRequestIdList = PixTransactionCheckoutLimitChangeRequest.waitingDenial([column: "id"]).list(max: maxQueryResults)

        Utils.forEachWithFlushSession(checkoutLimitChangeRequestIdList, flushEvery, { Long checkoutLimitChangeRequestId ->
            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = PixTransactionCheckoutLimitChangeRequest.get(checkoutLimitChangeRequestId)
                pixTransactionCheckoutLimitChangeRequest = deny(pixTransactionCheckoutLimitChangeRequest)

                if (pixTransactionCheckoutLimitChangeRequest.hasErrors()) {
                    AsaasLogger.error("PixTransactionCheckoutLimitChangeRequestService.processAwaitingDenial() -> Erro ao reprovar a solicitação de alteração do limite Pix [pixTransactionCheckoutLimitChangeRequestId: ${checkoutLimitChangeRequestId}, errorMessage: ${DomainUtils.getFirstValidationMessage(pixTransactionCheckoutLimitChangeRequest)}]")
                }
            }, [logErrorMessage: "PixTransactionCheckoutLimitChangeRequestService.processAwaitingDenial() -> Erro interno ao processar a reprovação de alteração do limite Pix [pixTransactionCheckoutLimitChangeRequestId: ${checkoutLimitChangeRequestId}]"])
        })
    }

    public PixTransactionCheckoutLimitChangeRequest denyAutomatically(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest, String deniedReason) {
        BusinessValidation businessValidation = validateAnalysis(pixTransactionCheckoutLimitChangeRequest, PixTransactionCheckoutLimitChangeRequestStatus.AUTOMATICALLY_DENIED, deniedReason)
        if (!businessValidation.isValid()) {
            return DomainUtils.copyAllErrorsFromBusinessValidation(businessValidation, pixTransactionCheckoutLimitChangeRequest)
        }

        onDenied(pixTransactionCheckoutLimitChangeRequest)
        return setAsAutomaticallyDenied(pixTransactionCheckoutLimitChangeRequest, deniedReason)
    }

    private PixTransactionCheckoutLimitChangeRequest approve(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        onApproved(pixTransactionCheckoutLimitChangeRequest)

        pixTransactionCheckoutLimitChangeRequest.status = PixTransactionCheckoutLimitChangeRequestStatus.APPROVED
        finish(pixTransactionCheckoutLimitChangeRequest)

        return pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)
    }

    private PixTransactionCheckoutLimitChangeRequest deny(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        onDenied(pixTransactionCheckoutLimitChangeRequest)

        pixTransactionCheckoutLimitChangeRequest.status = PixTransactionCheckoutLimitChangeRequestStatus.DENIED
        finish(pixTransactionCheckoutLimitChangeRequest)

        return pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)
    }

    private PixTransactionCheckoutLimitChangeRequest setAsCancelled(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        pixTransactionCheckoutLimitChangeRequest.status = PixTransactionCheckoutLimitChangeRequestStatus.CANCELLED
        return pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)
    }

    private void onDenied(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        saveDeniedCustomerInteraction(pixTransactionCheckoutLimitChangeRequest)
        customerAlertNotificationService.notifyCustomerPixTransactionCheckoutLimitValuesChangeRequestDenied(pixTransactionCheckoutLimitChangeRequest)
        mobilePushNotificationService.notifyCustomerPixTransactionCheckoutLimitValuesChangeRequestDenied(pixTransactionCheckoutLimitChangeRequest)
        customerMessageService.notifyPixTransactionCheckoutLimitChangeRequestDenied(pixTransactionCheckoutLimitChangeRequest)
    }

    private PixTransactionCheckoutLimitChangeRequest setAsAutomaticallyDenied(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest, String deniedReason) {
        pixTransactionCheckoutLimitChangeRequest.status = PixTransactionCheckoutLimitChangeRequestStatus.AUTOMATICALLY_DENIED
        pixTransactionCheckoutLimitChangeRequest.deniedReason = deniedReason
        pixTransactionCheckoutLimitChangeRequest.analysisDate = new Date()
        return pixTransactionCheckoutLimitChangeRequest.save(failOnError: true)
    }

    private void applyChangeRequest(PixTransactionCheckoutLimitChangeRequest changeRequest) {
        if (changeRequest.type.isChangeLimit() && changeRequest.scope.isGeneral() && changeRequest.limitType.isPerPeriod()) {
            Boolean isNecessaryIncreaseCustomerDailyLimit = changeRequest.requestedLimit > CustomerConfig.getCustomerDailyCheckoutLimit(changeRequest.customer)
            if (isNecessaryIncreaseCustomerDailyLimit) {
                customerCheckoutLimitService.setDailyLimit(changeRequest.customer, changeRequest.requestedLimit, "limite atualizado via limites Pix")
            }
        }

        if (changeRequest.type.isChangeNightlyHour()) {
            pixTransactionCheckoutLimitService.applyChangeRequestOnNightlyHour(changeRequest.customer, changeRequest.requestedLimit.toInteger())
            return
        }

        if (changeRequest.isDaytimeLimitPerPeriodOnGeneralScope()) {
            pixTransactionCheckoutLimitService.saveDaytimeLimit(changeRequest.customer, changeRequest.requestedLimit)
            return
        }

        if (changeRequest.isDaytimeLimitPerTransactionOnGeneralScope()) {
            pixTransactionCheckoutLimitService.saveDaytimeLimitPerTransaction(changeRequest.customer, changeRequest.requestedLimit)
            return
        }

        if (changeRequest.isNightlyLimitPerPeriodOnGeneralScope()) {
            pixTransactionCheckoutLimitService.saveNightlyLimit(changeRequest.customer, changeRequest.requestedLimit)
            return
        }

        if (changeRequest.isNightlyLimitPerTransactionOnGeneralScope()) {
            pixTransactionCheckoutLimitService.saveNightlyLimitPerTransaction(changeRequest.customer, changeRequest.requestedLimit)
            return
        }

        if (changeRequest.isDaytimeLimitPerPeriodOnCashValueScope()) {
            pixTransactionCheckoutLimitService.saveCashValueDaytimeLimit(changeRequest.customer, changeRequest.requestedLimit)
            return
        }

        if (changeRequest.isDaytimeLimitPerTransactionOnCashValueScope()) {
            pixTransactionCheckoutLimitService.saveCashValueDaytimeLimitPerTransaction(changeRequest.customer, changeRequest.requestedLimit)
            return
        }

        if (changeRequest.isNightlyLimitPerPeriodOnCashValueScope()) {
            pixTransactionCheckoutLimitService.saveCashValueNightlyLimit(changeRequest.customer, changeRequest.requestedLimit)
            return
        }

        if (changeRequest.isNightlyLimitPerTransactionOnCashValueScope()) {
            pixTransactionCheckoutLimitService.saveCashValueNightlyLimitPerTransaction(changeRequest.customer, changeRequest.requestedLimit)
            return
        }
    }

    private Map buildChangeRequestParams(Map params) {
        Map fields = [:]

        if (params.scope) fields.scope = PixTransactionCheckoutLimitChangeRequestScope.convert(params.scope)
        if (params.period) fields.period = PixTransactionCheckoutLimitChangeRequestPeriod.convert(params.period)
        if (params.limitPerPeriod) fields.limitPerPeriod = Utils.toBigDecimal(params.limitPerPeriod)
        if (params.limitPerTransaction) fields.limitPerTransaction = Utils.toBigDecimal(params.limitPerTransaction)
        if (params.initialNightlyHour) fields.initialNightlyHour = Utils.toInteger(params.initialNightlyHour)

        return fields
    }

    private String buildCriticalActionHash(Customer customer, Map params) {
        String operation = customer.id.toString()

        if (params.scope) operation += params.scope.toString()
        if (params.period) operation += params.period.toString()
        if (params.limitPerPeriod) operation += params.limitPerPeriod.toString()
        if (params.limitPerTransaction) operation += params.limitPerTransaction.toString()
        if (params.initialNightlyHour) operation += params.initialNightlyHour.toString()

        return operation.encodeAsMD5()
    }

    @Deprecated
    private void saveCustomerInteraction(Customer customer, BigDecimal requestedValue) {
        BigDecimal oldCheckoutLimitValueOutOfBusinessHour = PixTransactionCheckoutLimit.getNightlyLimit(customer)

        String description = "Solicitação de alteração no limite Pix em horário especial:\n"
        description += "Solicitado aumento no limite Pix em horário especial de ${FormUtils.formatCurrencyWithMonetarySymbol(oldCheckoutLimitValueOutOfBusinessHour)} para ${FormUtils.formatCurrencyWithMonetarySymbol(requestedValue)}\n"

        customerInteractionService.save(customer, description)
    }

    private void cancelPendingIfExists(Customer customer, Map changeRequestInfo) {
        Map queryParams = [
            customer: customer,
            type: changeRequestInfo.type,
            period: changeRequestInfo.period
        ]

        if (changeRequestInfo.scope) queryParams.scope = changeRequestInfo.scope
        if (changeRequestInfo.limitType) queryParams.limitType = changeRequestInfo.limitType

        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = PixTransactionCheckoutLimitChangeRequest.requested(queryParams).get()

        if (pixTransactionCheckoutLimitChangeRequest) setAsCancelled(pixTransactionCheckoutLimitChangeRequest)
    }

    private BusinessValidation validateAnalysis(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest, PixTransactionCheckoutLimitChangeRequestStatus status, String deniedReason) {
        BusinessValidation businessValidation = pixTransactionCheckoutLimitChangeRequest.canBeAnalyzed()
        if (!businessValidation.isValid()) return businessValidation

        if (status.isEquivalentToDenied()) {
            if (!deniedReason) {
                businessValidation.addError("pixTransactionCheckoutLimitChangeRequest.validate.deniedReasonMustBeInformed")
                return businessValidation
            }

            if (!pixTransactionCheckoutLimitChangeRequest.type.isChangeLimit()) {
                businessValidation.addError("pixTransactionCheckoutLimitChangeRequest.validate.deny.invalidType")
                return businessValidation
            }
        }

        return businessValidation
    }

    private void onApproved(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        applyChangeRequest(pixTransactionCheckoutLimitChangeRequest)
        saveApprovedCustomerInteraction(pixTransactionCheckoutLimitChangeRequest)
        notifyApprovedIfNecessary(pixTransactionCheckoutLimitChangeRequest)
        sendEmailForApproveIfNecessary(pixTransactionCheckoutLimitChangeRequest)
    }

    private void notifyApprovedIfNecessary(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        if (pixTransactionCheckoutLimitChangeRequest.type.isChangeLimit()) {
            Boolean isLimitIncrease = pixTransactionCheckoutLimitChangeRequest.requestedLimit > pixTransactionCheckoutLimitChangeRequest.previousLimit
            if (isLimitIncrease) {
                customerAlertNotificationService.notifyCustomerPixTransactionCheckoutLimitValuesChangeRequestApproved(pixTransactionCheckoutLimitChangeRequest)
                mobilePushNotificationService.notifyCustomerPixTransactionCheckoutLimitValuesChangeRequestApproved(pixTransactionCheckoutLimitChangeRequest)
            }
        } else {
            customerAlertNotificationService.notifyCustomerPixTransactionCheckoutLimitInitialNightlyHourChangeRequestApproved(pixTransactionCheckoutLimitChangeRequest)
            mobilePushNotificationService.notifyCustomerPixTransactionCheckoutLimitInitialNightlyHourChangeRequestApproved(pixTransactionCheckoutLimitChangeRequest)
        }
    }

    private void saveRequestedCustomerInteraction(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        BigDecimal previousLimit = pixTransactionCheckoutLimitChangeRequest.previousLimit
        BigDecimal requestedLimit = pixTransactionCheckoutLimitChangeRequest.requestedLimit

        String description
        if (pixTransactionCheckoutLimitChangeRequest.type.isChangeLimit()) {
            description = "Solicitação de alteração nos ${Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.scope)}:\n"
            description += "Solicitado alteração no limite ${Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.period)} ${Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.limitType).toLowerCase()} de ${FormUtils.formatCurrencyWithMonetarySymbol(previousLimit)} para ${FormUtils.formatCurrencyWithMonetarySymbol(requestedLimit)}\n"
        } else {
            description = "Solicitação de alteração no horário Pix:\n"
            description += "Solicitado alteração no início do horário noturno para ${(Integer) requestedLimit}h"
        }

        customerInteractionService.save(pixTransactionCheckoutLimitChangeRequest.customer, description)
    }

    private void sendEmailForRequestIfNecessary(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        Boolean isLimitReduction = pixTransactionCheckoutLimitChangeRequest.type.isChangeLimit() && pixTransactionCheckoutLimitChangeRequest.requestedLimit < pixTransactionCheckoutLimitChangeRequest.previousLimit
        if (isLimitReduction) return

        customerMessageService.notifyPixTransactionCheckoutLimitChangeRequestRequested(pixTransactionCheckoutLimitChangeRequest)
    }

    private void sendEmailForApproveIfNecessary(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        Boolean isLimitReduction = pixTransactionCheckoutLimitChangeRequest.type.isChangeLimit() && pixTransactionCheckoutLimitChangeRequest.requestedLimit < pixTransactionCheckoutLimitChangeRequest.previousLimit
        if (isLimitReduction) return

        customerMessageService.notifyPixTransactionCheckoutLimitChangeRequestApproved(pixTransactionCheckoutLimitChangeRequest)
    }

    private void saveApprovedCustomerInteraction(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        BigDecimal previousLimit = pixTransactionCheckoutLimitChangeRequest.previousLimit
        BigDecimal requestedLimit = pixTransactionCheckoutLimitChangeRequest.requestedLimit

        String description
        if (pixTransactionCheckoutLimitChangeRequest.type.isChangeLimit()) {
            description = "Solicitação de alteração nos ${Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.scope)} aprovada:\n"
            description += "Limite ${Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.period)} ${Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.limitType).toLowerCase()} alterado de ${FormUtils.formatCurrencyWithMonetarySymbol(previousLimit)} para ${FormUtils.formatCurrencyWithMonetarySymbol(requestedLimit)}\n"
        } else {
            description = "Solicitação de alteração no horário Pix aprovado:\n"
            description += "O ínicio do horário noturno foi alterado para ${(Integer) requestedLimit}h\n"
        }

        customerInteractionService.save(pixTransactionCheckoutLimitChangeRequest.customer, description)
    }

    private void saveDeniedCustomerInteraction(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        BigDecimal previousLimit = pixTransactionCheckoutLimitChangeRequest.previousLimit
        BigDecimal requestedLimit = pixTransactionCheckoutLimitChangeRequest.requestedLimit

        String description = "Solicitação de alteração nos ${Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.scope)} reprovada:\n"
        description += "Limite ${Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.period)} ${Utils.getEnumLabel(pixTransactionCheckoutLimitChangeRequest.limitType).toLowerCase()} reprovado de ${FormUtils.formatCurrencyWithMonetarySymbol(previousLimit)} para ${FormUtils.formatCurrencyWithMonetarySymbol(requestedLimit)}\n"

        customerInteractionService.save(pixTransactionCheckoutLimitChangeRequest.customer, description)
    }

    private void finish(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        analysisInteractionService.createForPixTransactionCheckoutLimitChangeRequest(pixTransactionCheckoutLimitChangeRequest.analyst, AnalysisInteractionType.FINISH, pixTransactionCheckoutLimitChangeRequest)
    }
}

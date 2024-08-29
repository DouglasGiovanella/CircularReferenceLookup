package com.asaas.service.pix.limit

import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimit
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimitChangeRequest
import com.asaas.pix.PixTransactionBankAccountInfoCheckoutLimitChangeRequestRisk
import com.asaas.pix.PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixTransactionBankAccountInfoCheckoutLimitChangeRequestService {

    def criticalActionService
    def customerAlertNotificationService
    def customerMessageService
    def pixTransactionBankAccountInfoCheckoutLimitService

    public PixTransactionBankAccountInfoCheckoutLimitChangeRequest save(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal requestedDailyLimit, Map tokenParams) {
        PixTransactionBankAccountInfoCheckoutLimit validatedPixTransactionBankAccountInfoCheckoutLimit = pixTransactionBankAccountInfoCheckoutLimitService.validate(bankAccountInfo, requestedDailyLimit)
        if (validatedPixTransactionBankAccountInfoCheckoutLimit.hasErrors()) return DomainUtils.copyAllErrorsFromObject(validatedPixTransactionBankAccountInfoCheckoutLimit, new PixTransactionBankAccountInfoCheckoutLimitChangeRequest())

        PixTransactionBankAccountInfoCheckoutLimitChangeRequest validatedPixTransactionBankAccountInfoCheckoutLimitChangeRequest = validateCriticalActionAuthorizationToken(customer, bankAccountInfo.id, requestedDailyLimit, tokenParams)
        if (validatedPixTransactionBankAccountInfoCheckoutLimitChangeRequest.hasErrors()) return validatedPixTransactionBankAccountInfoCheckoutLimitChangeRequest

        cancelRequestedIfExists(bankAccountInfo)

        PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest = new PixTransactionBankAccountInfoCheckoutLimitChangeRequest()
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.customer = customer
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.user = UserUtils.getCurrentUser()
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.bankAccountInfo = bankAccountInfo
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.previousLimit = PixTransactionBankAccountInfoCheckoutLimit.query([column: "dailyLimit", bankAccountInfo: bankAccountInfo]).get()
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.requestedValue = requestedDailyLimit
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.status = PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.REQUESTED
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.risk = estimateRisk(customer, bankAccountInfo, requestedDailyLimit)
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.save(failOnError: true)

        customerMessageService.notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestRequested(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)

        return pixTransactionBankAccountInfoCheckoutLimitChangeRequest
    }

    public CriticalActionGroup requestAuthorizationToken(Customer customer, Long bankAccountInfoId, BigDecimal dailyLimit) {
        String hash = buildCriticalActionHash(customer, bankAccountInfoId, dailyLimit)
        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.PIX_SAVE_TRANSACTION_BANK_ACCOUNT_INFO_CHECKOUT_LIMIT_CHANGE_REQUEST, hash)
    }

    public PixTransactionBankAccountInfoCheckoutLimitChangeRequest cancel(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        if (!pixTransactionBankAccountInfoCheckoutLimitChangeRequest.status.isRequested()) {
            return DomainUtils.addError(pixTransactionBankAccountInfoCheckoutLimitChangeRequest, "Apenas solicitações pendentes podem ser canceladas")
        }

        customerMessageService.notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestCancelled(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
        return setAsCancelled(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
    }

    public PixTransactionBankAccountInfoCheckoutLimitChangeRequest approveAutomatically(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit = pixTransactionBankAccountInfoCheckoutLimitService.save(pixTransactionBankAccountInfoCheckoutLimitChangeRequest.bankAccountInfo, pixTransactionBankAccountInfoCheckoutLimitChangeRequest.requestedValue)
        if (pixTransactionBankAccountInfoCheckoutLimit.hasErrors()) return DomainUtils.copyAllErrorsFromObject(pixTransactionBankAccountInfoCheckoutLimit, pixTransactionBankAccountInfoCheckoutLimitChangeRequest)

        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.status = PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.AUTOMATICALLY_APPROVED
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.analysisDate = new Date()
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.save(failOnError: true)

        onApprove(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)

        return pixTransactionBankAccountInfoCheckoutLimitChangeRequest
    }

    public PixTransactionBankAccountInfoCheckoutLimitChangeRequest approve(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequestValidated = validateAnalysis(pixTransactionBankAccountInfoCheckoutLimitChangeRequest, null, PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.APPROVED)
        if (pixTransactionBankAccountInfoCheckoutLimitChangeRequestValidated.hasErrors()) return pixTransactionBankAccountInfoCheckoutLimitChangeRequestValidated

        PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit = pixTransactionBankAccountInfoCheckoutLimitService.save(pixTransactionBankAccountInfoCheckoutLimitChangeRequest.bankAccountInfo, pixTransactionBankAccountInfoCheckoutLimitChangeRequest.requestedValue)
        if (pixTransactionBankAccountInfoCheckoutLimit.hasErrors()) return DomainUtils.copyAllErrorsFromObject(pixTransactionBankAccountInfoCheckoutLimit, pixTransactionBankAccountInfoCheckoutLimitChangeRequest)

        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.status = PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.APPROVED
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.analysisDate = new Date()
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.analyst = UserUtils.getCurrentUser()
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.save(failOnError: true)

        onApprove(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)

        return pixTransactionBankAccountInfoCheckoutLimitChangeRequest
    }

    public PixTransactionBankAccountInfoCheckoutLimitChangeRequest deny(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest, String deniedReason) {
        PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequestValidated = validateAnalysis(pixTransactionBankAccountInfoCheckoutLimitChangeRequest, deniedReason, PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.DENIED)
        if (pixTransactionBankAccountInfoCheckoutLimitChangeRequestValidated.hasErrors()) return pixTransactionBankAccountInfoCheckoutLimitChangeRequestValidated

        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.status = PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.DENIED
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.analysisDate = new Date()
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.analyst = UserUtils.getCurrentUser()
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.deniedReason = deniedReason
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.save(failOnError: true)

        onDeny(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)

        return pixTransactionBankAccountInfoCheckoutLimitChangeRequest
    }

    public PixTransactionBankAccountInfoCheckoutLimitChangeRequest denyAutomatically(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest, String deniedReason) {
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.status = PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.AUTOMATICALLY_DENIED
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.analysisDate = new Date()
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.deniedReason = deniedReason
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.save(failOnError: true)

        onDeny(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)

        return pixTransactionBankAccountInfoCheckoutLimitChangeRequest
    }

    public PixTransactionBankAccountInfoCheckoutLimitChangeRequest setAsError(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.status = PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.ERROR
        return pixTransactionBankAccountInfoCheckoutLimitChangeRequest.save(failOnError: true)
    }

    public void cancelRequestedIfExists(BankAccountInfo bankAccountInfo) {
        PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest = PixTransactionBankAccountInfoCheckoutLimitChangeRequest.query([bankAccountInfo: bankAccountInfo, status: PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.REQUESTED]).get()
        if (pixTransactionBankAccountInfoCheckoutLimitChangeRequest) cancel(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
    }

    private void onApprove(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        customerAlertNotificationService.notifyCustomerPixTransactionBankAccountInfoCheckoutLimitChangeRequestApproved(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
        customerMessageService.notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestApproved(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
    }

    private void onDeny(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        customerAlertNotificationService.notifyCustomerPixTransactionBankAccountInfoCheckoutLimitChangeRequestDenied(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
        customerMessageService.notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestDenied(pixTransactionBankAccountInfoCheckoutLimitChangeRequest)
    }

    private PixTransactionBankAccountInfoCheckoutLimitChangeRequest validateAnalysis(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest, String deniedReason, PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus status) {
        if (!pixTransactionBankAccountInfoCheckoutLimitChangeRequest.status.isRequested()) {
            return DomainUtils.addError(pixTransactionBankAccountInfoCheckoutLimitChangeRequest, "Apenas solicitações pendentes podem ser analisadas")
        }

        if (status.isEquivalentToDenied() && !deniedReason) {
            return DomainUtils.addError(pixTransactionBankAccountInfoCheckoutLimitChangeRequest, "Solicitações negadas devem ser justificadas")
        }

        return pixTransactionBankAccountInfoCheckoutLimitChangeRequest
    }

    private PixTransactionBankAccountInfoCheckoutLimitChangeRequestRisk estimateRisk(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal requestedDailyLimit) {
        BigDecimal currentBankAccountInfoDailyLimit = PixTransactionBankAccountInfoCheckoutLimit.query([column: "dailyLimit", bankAccountInfo: bankAccountInfo]).get()
        Boolean isLimitReduction = currentBankAccountInfoDailyLimit != null && requestedDailyLimit < currentBankAccountInfoDailyLimit
        if (isLimitReduction) {
            return PixTransactionBankAccountInfoCheckoutLimitChangeRequestRisk.LOW
        }

        if (customer.suspectedOfFraud) {
            return PixTransactionBankAccountInfoCheckoutLimitChangeRequestRisk.HIGH
        }

        BigDecimal customerDailyCheckoutLimit = CustomerConfig.getCustomerDailyCheckoutLimit(customer)
        Boolean isLimitIncreaseToThirdPartyAccount = requestedDailyLimit > customerDailyCheckoutLimit && bankAccountInfo.getThirdPartyAccount()
        if (isLimitIncreaseToThirdPartyAccount) {
            return PixTransactionBankAccountInfoCheckoutLimitChangeRequestRisk.HIGH
        }

        return PixTransactionBankAccountInfoCheckoutLimitChangeRequestRisk.MEDIUM
    }

    private PixTransactionBankAccountInfoCheckoutLimitChangeRequest setAsCancelled(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.status = PixTransactionBankAccountInfoCheckoutLimitChangeRequestStatus.CANCELLED
        pixTransactionBankAccountInfoCheckoutLimitChangeRequest.save(failOnError: true)
    }

    private String buildCriticalActionHash(Customer customer, Long bankAccountInfoId, BigDecimal dailyLimit) {
        String operation = new StringBuilder(customer.id.toString())
            .append(bankAccountInfoId.toString())
            .append(dailyLimit.toString())
            .toString()

        return operation.encodeAsMD5()
    }

    private PixTransactionBankAccountInfoCheckoutLimitChangeRequest validateCriticalActionAuthorizationToken(Customer customer, Long bankAccountInfoId, BigDecimal dailyLimit, Map tokenParams) {
        PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequestValidatedToken = new PixTransactionBankAccountInfoCheckoutLimitChangeRequest()

        String hash = buildCriticalActionHash(customer, bankAccountInfoId, dailyLimit)
        BusinessValidation businessValidation = criticalActionService.authorizeSynchronous(customer, tokenParams.groupId, tokenParams.authorizationToken, CriticalActionType.PIX_SAVE_TRANSACTION_BANK_ACCOUNT_INFO_CHECKOUT_LIMIT_CHANGE_REQUEST, null, hash)

        if (!businessValidation.isValid()) DomainUtils.addError(pixTransactionBankAccountInfoCheckoutLimitChangeRequestValidatedToken, businessValidation.getFirstErrorMessage())
        return pixTransactionBankAccountInfoCheckoutLimitChangeRequestValidatedToken
    }
}

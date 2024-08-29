package com.asaas.service.customer

import com.asaas.asyncaction.AsyncActionType
import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.customer.CustomerDisabledReasonStatus
import com.asaas.customer.CustomerEventName
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerStatus
import com.asaas.customer.DisabledReason
import com.asaas.domain.asaascard.AsaasCardRecharge
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.bankaccountinfo.BankAccountInfoUpdateRequest
import com.asaas.domain.bill.Bill
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerDisabledReason
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.debit.Debit
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardAccountDisableRestrictionsAdapter
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.PixTransactionType
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.status.Status
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

import java.time.Instant

@Transactional
class CustomerStatusService {

    def accountActivationRequestService
    def apiConfigService
    def asaasCardService
    def asaasErpCustomerConfigService
    def asaasSecurityMailMessageService
    def asaasSegmentioService
    def asyncActionService
    def bacenCcsService
    def bankAccountInfoService
    def beamerService
    def bifrostAccountManagerService
    def callCenterOutgoingPhoneCallManagerService
    def confirmedFraudService
    def criticalActionService
    def customerEventService
    def customerEventListenerService
    def customerInteractionService
    def customerInvoiceService
    def customerOnboardingStepService
    def customerRegisterUpdateAnalysisRequestService
    def customerStageService
    def facebookEventService
    def grailsApplication
    def hermesAccountService
    def hubspotContactService
    def hubspotEventService
    def leadDataService
    def messageService
    def pushNotificationConfigAlertQueueService
    def receivableAnticipationCancellationService
    def subscriptionService
    def thirdPartyDocumentationOnboardingService
    def unsubscribeService
    def userService
    def creditTransferRequestService
    def pixTransactionService
    def nexinvoiceCustomerConfigService

    public void block(Long customerId, Boolean sendEmail, String reason) {
        Customer customer = Customer.get(customerId)

        AsaasLogger.info("Blocking customer >> ${customer.email}")

        for (User user in userService.list(customerId, 100, 0)) {
            userService.lock([id: user.id])
        }

        customer.status = CustomerStatus.BLOCKED
        customer.save(flush: true, failOnError: true)

        asaasCardService.blockAllCards(customer)

        if (sendEmail) messageService.sendBlockCustomerAlert(customer)

        callCenterOutgoingPhoneCallManagerService.saveAsyncCancellation(customer.id)

        customerInteractionService.saveCustomerBlockInfo(customerId, reason)

        customerEventListenerService.onStatusUpdated(customer)
    }

    public void unblock(Long customerId) {
		Customer provider = Customer.get(customerId)
        BusinessValidation validation = canUnblock(provider)
        if (!validation.isValid()) throw new BusinessException(validation.getFirstErrorMessage())

        AsaasLogger.info("Unblocking customer >> ${provider.email}")

		for (User user in userService.list(customerId, 100, 0)) {
			userService.unlock([id: user.id])
		}

		provider.status = CustomerStatus.ACTIVE
		provider.save(flush: true, failOnError: true)

        asaasCardService.updateAllToUnlockable(provider)

        customerEventListenerService.onStatusUpdated(provider)
	}

    public BusinessValidation canUnblock(Customer customer) {
        BusinessValidation validateBusiness = new BusinessValidation()

        if (!customer.getIsBlocked()) {
            validateBusiness.addError("customer.isUnblocked")
            return validateBusiness
        }

        if (customer.accountDisabled()) {
            validateBusiness.addError("customer.disabled")
            return validateBusiness
        }

        return validateBusiness
    }

    public CriticalAction disableAccount(Customer customer, User user, DisabledReason disabledReason, String observations) {
        List<AsaasError> errorList = buildReasonsWhyCantDisableAccount(customer, user, disabledReason)
        if (errorList) throw new BusinessException(errorList.first().getMessage())

        BusinessValidation businessValidation = canRequestDisableAccount(customer, disabledReason, observations)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        saveCustomerDisabledReason(customer, disabledReason, observations, CustomerDisabledReasonStatus.AWAITING_ACTION_AUTHORIZATION)

        return CriticalAction.saveAccountDisable(customer)
    }

    public CustomerDisabledReason saveCustomerDisabledReason(Customer customer, DisabledReason disabledReason, String observations, CustomerDisabledReasonStatus status) {
        CustomerDisabledReason customerDisabledReason = new CustomerDisabledReason()
        customerDisabledReason.customer = customer
        customerDisabledReason.disabledReason = disabledReason
        customerDisabledReason.observations = observations
        customerDisabledReason.status = status
        customerDisabledReason.save(failOnError: true)

        return customerDisabledReason
    }

    public void onCriticalActionCancellation(Customer customer) {
        CustomerDisabledReason customerDisabledReason = CustomerDisabledReason.query([customer: customer, status: Status.AWAITING_ACTION_AUTHORIZATION]).get()
        if (customerDisabledReason) {
            customerDisabledReason.status = CustomerDisabledReasonStatus.CANCELLED
            customerDisabledReason.save(failOnError: true)
        }
    }

    public Customer executeAccountDisable(Long customerId, User currentUser) {
        executeAccountDisable(customerId, currentUser, null)
    }

    public Customer executeAccountDisable(Long customerId, User currentUser, DisabledReason disabledReason) {
        Customer customer = Customer.get(customerId)
        if (customer.status.isDisabled()) return customer

        String customerOriginalEmail = customer.email

        List<AsaasError> errorList = buildReasonsWhyCantDisableAccount(customer, currentUser, disabledReason)
        if (errorList) throw new BusinessException(errorList.first().getMessage())

        AsaasLogger.info("CustomerStatusService.executeAccountDisable -> Desabilitando cliente. CustomerId: ${customer.id}")

        userService.deleteUsersOnAccountDisable(customer.id)

        pixTransactionService.cancelAllByCustomer(customer)

        creditTransferRequestService.cancelAllowedByCustomer(customer)

        customerInvoiceService.cancelScheduledInvoices(customer)

        receivableAnticipationCancellationService.cancelAllPossibleOnCustomerDisable(customer, disabledReason?.isConfirmedFraud())

        accountActivationRequestService.delete(customer)

        bankAccountInfoService.deleteAllCustomerBankAccountInfo(customer)

        callCenterOutgoingPhoneCallManagerService.saveAsyncCancellation(customer.id)

        asyncActionService.save(AsyncActionType.CANCEL_NOT_AUTHORIZED_CUSTOMER_INVOICE, [customerId: customerId])

        asyncActionService.save(AsyncActionType.DELETE_PENDING_OR_OVERDUE_PAYMENT, [customerId: customerId, userId: null])

        subscriptionService.deleteAll(customer)

        apiConfigService.delete(customer)

        asaasErpCustomerConfigService.delete(customer.id)
        nexinvoiceCustomerConfigService.delete(customer.id)

        asyncActionService.saveCancelAllAsaasCards(customer)

        CustomerUpdateRequest.pending(customer).list().each {
            it.deleted = true
            it.save(failOnError: true)
        }

        BankAccountInfoUpdateRequest.pending(customer).list().each {
            it.deleted = true
            it.save(failOnError: true)
        }

        CustomerDisabledReason customerDisabledReason = CustomerDisabledReason.query([customer: customer, status: Status.AWAITING_ACTION_AUTHORIZATION]).get()
        if (customerDisabledReason) {
            customerDisabledReason.status = CustomerDisabledReasonStatus.AUTHORIZED
            customerDisabledReason.save(failOnError: true)
        }

        if (customer.customerRegisterStatus.commercialInfoStatus == Status.AWAITING_APPROVAL) customer.customerRegisterStatus.commercialInfoStatus = Status.REJECTED
        if (customer.customerRegisterStatus.bankAccountInfoStatus == Status.AWAITING_APPROVAL) customer.customerRegisterStatus.bankAccountInfoStatus = Status.REJECTED
        if (customer.customerRegisterStatus.boletoInfoStatus == Status.AWAITING_APPROVAL) customer.customerRegisterStatus.boletoInfoStatus = Status.REJECTED
        if (customer.customerRegisterStatus.documentStatus == Status.AWAITING_APPROVAL) customer.customerRegisterStatus.documentStatus = Status.REJECTED

        unsubscribeService.executeAccountDisableExternalUnsubscribeWithNewThread(customer.email)

        customer.email = buildEmailForDisabledAccount(customerOriginalEmail)

        customer.status = CustomerStatus.DISABLED

        customer.activationPhone = null

        customerStageService.processAccountDisabled(customer)

        customer.save(flush: true, failOnError: true)

        hubspotContactService.saveCommercialInfoUpdate(customer)

        beamerService.updateUserInformation(customer.id, [:])

        customerInteractionService.saveCustomerDisableInfo(customer)

        bacenCcsService.updateEndOfRelationshipIfNecessary(customer)

        asyncActionService.saveDisableHermesAccount(customer)

        if (!disabledReason?.isConfirmedFraud()) asaasSecurityMailMessageService.notifyDisableAccount(customer, customerOriginalEmail)

        pushNotificationConfigAlertQueueService.deleteAllFromCustomer(customer)

        thirdPartyDocumentationOnboardingService.invalidateLastThirdPartyDocumentationOnboarding(customer.id)

        criticalActionService.cancelPendingOrAwaitingAuthorizationFromCustomer(customer)

        customerEventListenerService.onStatusUpdated(customer)

        customerRegisterUpdateAnalysisRequestService.cancelPending(customer.id, "Cancelado por encerramento da conta")

        leadDataService.delete(customerOriginalEmail, customer.id)

        if (disabledReason?.isConfirmedFraud()) {
            confirmedFraudService.executeNegativeBalanceZeroing(customer)
            asyncActionService.saveCancelCustomerConfirmedFraudBill(customer)
        }

        return customer
	}

    public List<AsaasError> buildReasonsWhyCantDisableAccount(Customer customer, User currentUser) {
        return buildReasonsWhyCantDisableAccount(customer, currentUser, null)
    }

    public List<AsaasError> buildReasonsWhyCantDisableAccount(Customer customer, User currentUser, DisabledReason disabledReason) {
            return validateIfAccountCanBeDisabled(customer, currentUser, disabledReason).collect {
            if (it.value instanceof Map) {
                new AsaasError(it.value.message, it.value.arguments)
            } else {
                new AsaasError(it.value)
            }
        }
    }

    public Map validateIfAccountCanBeDisabled(Customer customer, User currentUser, DisabledReason disabledReason) {
        Map validations = [:]

        if (currentUser?.customer == customer) {
            if (customer.accountRejected()) validations.accountRejected = "accountDisable.restriction.accountRejected"
        }

        if (disabledReason?.isConfirmedFraud()) return validations

        BigDecimal customerBalance = FinancialTransaction.getCustomerBalance(customer)
        validations += validateNegativeBalance(customerBalance, customer)
        validations += validateMaxBalance(customerBalance)
        validations += validateJudicialLockedBalance(customer)

        if (CriticalAction.pendingOrAwaitingAuthorization([customer: customer, column: 'id']).get().asBoolean()) {
            validations.pendingOrAwaitingCriticalActionAuthorization = "accountDisable.restriction.pendingOrAwaitingCriticalActionAuthorization"
        }

        if (AsaasCardRecharge.cancellable([customer: customer, column: 'id']).get().asBoolean()) {
            validations.pendingAsaasCardRecharges = "accountDisable.restriction.pendingAsaasCardRecharges"
        }

        AsaasCardAccountDisableRestrictionsAdapter asaasCardAccountDisableRestrictions = bifrostAccountManagerService.validateToDisable(customer.id)

        if (asaasCardAccountDisableRestrictions.hasUnpaidBills) {
            validations.hasAsaasCardUnpaidBills = "accountDisable.restriction.hasAsaasCardUnpaidBills"
        }

        if (asaasCardAccountDisableRestrictions.hasAvailableCredit) {
            validations.hasAsaasCardAvailableCredit = "accountDisable.restriction.hasAsaasCardAvailableCredit"
        }

        if (asaasCardAccountDisableRestrictions.hasPrepaidCardBalance) {
            validations.hasAsaasPrepaidCardBalance = "accountDisable.restriction.hasAsaasPrepaidCardBalance"
        }

        Boolean hasPendingCreditTransferRequest = CreditTransferRequest.query([exists: true, provider: customer, statusList: CreditTransferRequestStatus.getPendingList()]).get().asBoolean()
        if (hasPendingCreditTransferRequest) {
            validations.hasPendingCreditTransferRequest = "accountDisable.restriction.hasPendingCreditTransferRequest"
        }

        Boolean hasPendingPixTransaction = PixTransaction.query([exists: true, "type[in]": PixTransactionType.getEquivalentToDebitList(), customer: customer, "status[in]": PixTransactionStatus.getPendingList()]).get().asBoolean()
        if (hasPendingPixTransaction) {
            validations.hasPendingPixTransaction = "accountDisable.restriction.hasPendingPixTransaction"
        }

        Boolean canDisablePixAccount = hermesAccountService.accountCanBeDisabled(customer)
        if (!canDisablePixAccount) {
            validations.pixAccountHasRestrictions = "accountDisable.restriction.pixAccountCanBeDisabled"
        }

        Boolean hasAwaitingDebitAnticipation = ReceivableAnticipation.query([exists: true, customer: customer, statusList: [ReceivableAnticipationStatus.AWAITING_PARTNER_CREDIT, ReceivableAnticipationStatus.CREDITED]]).get().asBoolean()
        if (hasAwaitingDebitAnticipation) {
            validations.scheduledAnticipations = "accountDisable.restriction.scheduledAnticipations"
        }

        validations += validatePaymentsForDisableAccount(customer)

        if (Bill.cancellable([customer: customer, column: 'id']).get().asBoolean()) {
            validations.pendingBills = "accountDisable.restriction.pendingBills"
        }

        return validations
    }

    public void autoActivate(Long customerId, String observations) {
        Customer customer = Customer.get(customerId)
        processActivation(customer, false, observations, CustomerEventName.AUTO_ACTIVATION)
    }

    public void activate(Customer customer, Boolean accountManagerActivation, String observations) {
        processActivation(customer, accountManagerActivation, observations, CustomerEventName.ACTIVATION)
    }

    private BusinessValidation canRequestDisableAccount(Customer customer, DisabledReason disabledReason, String observations) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (customer.status.isDisabled()) {
            businessValidation.addError("customerStatusService.disableAccount.hasAlreadyBeenDisabled")
            return businessValidation
        }

        if (!CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL) && !AuthorizationDevice.active([customer: customer, column: 'id']).get().asBoolean()) {
            businessValidation.addError("customerStatusService.disableAccount.authorizationDevice")
            return businessValidation
        }

        if (!disabledReason) {
            businessValidation.addError("customerStatusService.disableAccount.requiredDisabledReason")
            return businessValidation
        }

        if (disabledReason.isObservationRequired() && !observations?.trim()) {
            businessValidation.addError("customerStatusService.disableAccount.requiredObservation")
            return businessValidation
        }

        final Integer observationsMaxLength = 2000
        if (observations.length() > observationsMaxLength) {
            businessValidation.addError("customerStatusService.disableAccount.maxObservationsLength")
            return businessValidation
        }

        return businessValidation
    }

    private String buildEmailForDisabledAccount(String customerOriginalEmail) {
        Long epochTimestamp = Instant.now().getEpochSecond()
        String emailForDisabledAccount = epochTimestamp + "_" + customerOriginalEmail

        return emailForDisabledAccount
    }

    private void processActivation(Customer customer, Boolean accountManagerActivation, String observations, CustomerEventName customerEventName) {
        if (customer.status.isActive()) return

        if (!customer.segment) customer.segment = Customer.INITIAL_SEGMENT
        if (observations) customer.observations = observations

        customer.status = CustomerStatus.ACTIVE
        customer.customerConfig.boletoBlockDate = null

        customerInteractionService.saveCustomerActivation(customer.id)
        customerStageService.processActivation(customer)

        customer.save(failOnError: true)

        customerOnboardingStepService.finishActivationStep(customer)

        if (customer.cpfCnpj) asyncActionService.saveCreateOrUpdateCercCompany(customer.cpfCnpj)

        asaasSegmentioService.identify(customer.id, ["accountManagerEmail": customer.accountManager?.email])
        asaasSegmentioService.track(customer.id, "account_activation__activate__success", [providerEmail: customer.email, accountManager: customer.accountManager?.name])

        customerEventService.save(customer, customerEventName, accountManagerActivation ? customer.accountManager : null, new Date())

        hubspotEventService.trackAsaasOpportunityLead(customer)

        facebookEventService.saveAccountActivationEvent(customer.id)

        bacenCcsService.createStartOfRelationshipIfNecessary(customer)

        customerEventListenerService.onStatusUpdated(customer)
    }

    private Map validatePaymentsForDisableAccount(Customer customer) {
        Map validations = [:]

        if (Payment.query([exists: true, customer: customer, statusList: [PaymentStatus.CONFIRMED, PaymentStatus.AWAITING_RISK_ANALYSIS]]).get().asBoolean()) {
            validations.confirmedOrRiskAnalisysPayments = "accountDisable.restriction.confirmedOrRiskAnalisysPayments"
        }

        if (Payment.query([exists: true, customer: customer, statusList: [PaymentStatus.REFUND_REQUESTED, PaymentStatus.CHARGEBACK_REQUESTED, PaymentStatus.CHARGEBACK_DISPUTE]]).get().asBoolean()) {
            validations.chargebackPayments = "accountDisable.restriction.chargebackPayments"
        }

        return validations
    }

    private Map validateNegativeBalance(BigDecimal customerBalance, Customer customer) {
        Map validations = [:]

        if (customerBalance >= 0) return validations

        BigDecimal maxNegativeBalanceWithoutPaymentReceived = grailsApplication.config.asaas.accountCancellation.maxNegativeBalanceWithoutPaymentReceived
        Boolean hasPaymentReceived = Payment.query([exists: true, customer: customer, statusList: [PaymentStatus.RECEIVED, PaymentStatus.RECEIVED_IN_CASH]]).get().asBoolean()
        if (hasPaymentReceived) {
            validations.balanceBelowLimit = "accountDisable.restriction.balanceBelowLimit"
            return validations
        }

        if ((maxNegativeBalanceWithoutPaymentReceived * -1) > customerBalance) {
            validations.balanceBelowLimit = "accountDisable.restriction.balanceBelowLimit"
        }

        return validations
    }

    private Map validateMaxBalance(BigDecimal customerBalance) {
        Map validations = [:]

        BigDecimal maxBalance = grailsApplication.config.asaas.accountCancellation.maxBalance
        if (customerBalance > maxBalance) {
            validations.balanceAboveLimit = [:]
            validations.balanceAboveLimit.message = "accountDisable.restriction.balanceAboveLimit"
            validations.balanceAboveLimit.arguments = [grailsApplication.config.asaas.accountCancellation.maxBalance]
        }

        return validations
    }

    private Map validateJudicialLockedBalance(Customer customer) {
        Map validations = [:]

        BigDecimal judicialLockedBalanceAmount = FinancialTransaction.getJudicialLockedBalanceAmount(customer.id)
        judicialLockedBalanceAmount += Debit.getBlockedBalance(customer.id)?.value ?: 0.00
        if (judicialLockedBalanceAmount > 0.00) {
            validations.blockedBalance = [:]
            validations.blockedBalance.message = "accountDisable.restriction.blockedBalance"
            validations.blockedBalance.arguments = [judicialLockedBalanceAmount]
        }

        return validations
    }
}

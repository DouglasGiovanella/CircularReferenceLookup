package com.asaas.service.mobilephonerecharge

import com.asaas.authorizationrequest.AuthorizationRequestActionType
import com.asaas.authorizationrequest.AuthorizationRequestType
import com.asaas.checkout.CheckoutValidator
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.mobilephonerecharge.MobilePhoneRecharge
import com.asaas.exception.BusinessException
import com.asaas.integration.celcoin.adapter.mobilephonerecharge.providers.ProviderAdapter
import com.asaas.log.AsaasLogger
import com.asaas.mobilephonerecharge.MobilePhoneRechargeRepository
import com.asaas.mobilephonerecharge.MobilePhoneRechargeStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.FormUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class MobilePhoneRechargeService {

    def asaasMoneyTransactionInfoService
    def asaasMoneyService
    def authorizationRequestService
    def celcoinMobilePhoneRechargeManagerService
    def customerExternalAuthorizationRequestCreateService
    def financialTransactionService
    def pushNotificationRequestMobilePhoneRechargeService

    public MobilePhoneRecharge save(Customer customer, Map params) {
        validateSave(customer, params)

        ProviderAdapter providerAdapter = findProvider(params)

        MobilePhoneRecharge mobilePhoneRecharge = new MobilePhoneRecharge()
        mobilePhoneRecharge.customer = customer
        mobilePhoneRecharge.value = params.value
        mobilePhoneRecharge.phoneNumber = params.phoneNumber
        mobilePhoneRecharge.status = buildStatus(customer)
        mobilePhoneRecharge.publicId = UUID.randomUUID()
        mobilePhoneRecharge.operatorId = providerAdapter.id
        mobilePhoneRecharge.operatorName = providerAdapter.name
        mobilePhoneRecharge.save(failOnError: true)

        if (asaasMoneyService.isAsaasMoneyRequest()) asaasMoneyTransactionInfoService.save(customer, mobilePhoneRecharge, params)
        asaasMoneyTransactionInfoService.setAsPaidIfNecessary(mobilePhoneRecharge)

        validateCheckoutValue(mobilePhoneRecharge)

        financialTransactionService.saveMobilePhoneRecharge(mobilePhoneRecharge)

        asaasMoneyTransactionInfoService.creditAsaasMoneyCashbackIfNecessary(mobilePhoneRecharge)

        pushNotificationRequestMobilePhoneRechargeService.save(PushNotificationRequestEvent.MOBILE_PHONE_RECHARGE_PENDING, mobilePhoneRecharge)

        if (mobilePhoneRecharge.status == MobilePhoneRechargeStatus.WAITING_CRITICAL_ACTION) {
            CriticalAction.saveMobilePhoneRechargeInsert(mobilePhoneRecharge)
        } else if (mobilePhoneRecharge.status == MobilePhoneRechargeStatus.AWAITING_EXTERNAL_AUTHORIZATION) {
            customerExternalAuthorizationRequestCreateService.saveForMobilePhoneRecharge(mobilePhoneRecharge)
        } else {
            return executeRecharge(mobilePhoneRecharge)
        }

        return mobilePhoneRecharge
    }

    public void onCriticalActionInsertAuthorization(CriticalAction criticalAction) {
        criticalAction.mobilePhoneRecharge.status = MobilePhoneRechargeStatus.PENDING
        criticalAction.mobilePhoneRecharge.save(failOnError: true)
    }

    public void onCriticalActionInsertCancellation(CriticalAction criticalAction) {
        cancel(criticalAction.mobilePhoneRecharge)
    }

    public void onExternalAuthorizationApproved(MobilePhoneRecharge mobilePhoneRecharge) {
        if (!mobilePhoneRecharge.status.isAwaitingExternalAuthorization()) {
            throw new RuntimeException("MobilePhoneRechargeService.onExternalAuthorizationApproved > Recarga de celular [${mobilePhoneRecharge.id}] não está aguardando autorização externa.")
        }

        mobilePhoneRecharge.status = MobilePhoneRechargeStatus.PENDING
        mobilePhoneRecharge.save(failOnError: true)
    }

    public void onExternalAuthorizationRefused(MobilePhoneRecharge mobilePhoneRecharge) {
        if (!mobilePhoneRecharge.status.isAwaitingExternalAuthorization()) {
            throw new RuntimeException("MobilePhoneRechargeService.onExternalAuthorizationRefused > Recarga de celular [${mobilePhoneRecharge.id}] não está aguardando autorização externa.")
        }

        cancel(mobilePhoneRecharge)
    }

    public MobilePhoneRecharge cancel(MobilePhoneRecharge mobilePhoneRecharge) {
        if (!mobilePhoneRecharge.canBeCancelled()) throw new BusinessException("Essa recarga não pode ser cancelada.")

        cancelCriticalAction(mobilePhoneRecharge)

        financialTransactionService.cancelMobilePhoneRecharge(mobilePhoneRecharge)

        mobilePhoneRecharge.status = MobilePhoneRechargeStatus.CANCELLED
        mobilePhoneRecharge.save(failOnError: true)

        pushNotificationRequestMobilePhoneRechargeService.save(PushNotificationRequestEvent.MOBILE_PHONE_RECHARGE_CANCELLED, mobilePhoneRecharge)

        asaasMoneyTransactionInfoService.refundCheckoutIfNecessary(mobilePhoneRecharge)

        return mobilePhoneRecharge
    }

    public MobilePhoneRecharge refund(MobilePhoneRecharge mobilePhoneRecharge) {
        if (!mobilePhoneRecharge.canBeRefundable()) throw new BusinessException("Essa recarga não pode ser estornada.")

        financialTransactionService.refundMobilePhoneRecharge(mobilePhoneRecharge)

        mobilePhoneRecharge.status = MobilePhoneRechargeStatus.REFUNDED
        mobilePhoneRecharge.save(failOnError: true)

        asaasMoneyTransactionInfoService.refundCheckoutIfNecessary(mobilePhoneRecharge)

        pushNotificationRequestMobilePhoneRechargeService.save(PushNotificationRequestEvent.MOBILE_PHONE_RECHARGE_REFUNDED, mobilePhoneRecharge)

        return mobilePhoneRecharge
    }

    public ProviderAdapter findProvider(Map params) {
        String phoneNumber = validatePhoneNumber(params.phoneNumber)

        return celcoinMobilePhoneRechargeManagerService.findProvider(phoneNumber)
    }

    public void processPendingMobilePhoneRecharge() {
        List<Long> mobilePhoneRechargeList = MobilePhoneRechargeRepository.query(["status": MobilePhoneRechargeStatus.PENDING]).disableRequiredFilters().column("id").list()

        Utils.forEachWithFlushSession(mobilePhoneRechargeList, 50, { Long mobilePhoneRechargeId ->
            Utils.withNewTransactionAndRollbackOnError ({
                MobilePhoneRecharge mobilePhoneRecharge = MobilePhoneRecharge.get(mobilePhoneRechargeId)

                executeRecharge(mobilePhoneRecharge)
            }, [logErrorMessage: "MobilePhoneRechargeService.processPendingMobilePhoneRecharge >>> Erro ao processar a recarga de celular [id: ${mobilePhoneRechargeId}]."] )
        } )
    }

    private MobilePhoneRecharge setAsConfirmed(MobilePhoneRecharge mobilePhoneRecharge, String externalId) {
        mobilePhoneRecharge.status = MobilePhoneRechargeStatus.CONFIRMED
        mobilePhoneRecharge.externalId = externalId
        mobilePhoneRecharge.confirmedDate = new Date().clearTime()
        mobilePhoneRecharge.save(failOnError: true)

        pushNotificationRequestMobilePhoneRechargeService.save(PushNotificationRequestEvent.MOBILE_PHONE_RECHARGE_CONFIRMED, mobilePhoneRecharge)
        return mobilePhoneRecharge
    }

    private void cancelCriticalAction(MobilePhoneRecharge mobilePhoneRecharge) {
        CriticalAction criticalAction = CriticalAction.pendingOrAwaitingAuthorization([customer: mobilePhoneRecharge.customer, mobilePhoneRecharge: mobilePhoneRecharge]).get()

        if (criticalAction) criticalAction.cancel()
    }

    private MobilePhoneRecharge executeRecharge(MobilePhoneRecharge mobilePhoneRecharge) {
        String transactionId = celcoinMobilePhoneRechargeManagerService.processRecharge(mobilePhoneRecharge)

        mobilePhoneRecharge = setAsConfirmed(mobilePhoneRecharge, transactionId)

        return mobilePhoneRecharge
    }

    private void validateCheckoutValue(MobilePhoneRecharge mobilePhoneRecharge) {
        CheckoutValidator checkoutValidator = new CheckoutValidator(mobilePhoneRecharge.customer)
        List<AsaasError> listOfAsaasError = checkoutValidator.validate(mobilePhoneRecharge.value)

        if (listOfAsaasError) {
            AsaasError asaasError = listOfAsaasError.first()

            if (asaasError.code == "denied.insufficient.balance") {
                AsaasLogger.info("MobilePhoneRechargeService.validateSave() -> Saldo insuficiente [Value: ${mobilePhoneRecharge.value}, Balance: ${FinancialTransaction.getCustomerBalance(mobilePhoneRecharge.customer)}]")
                asaasError.arguments = [mobilePhoneRecharge.value, FinancialTransaction.getCustomerBalance(mobilePhoneRecharge.customer)].collect { FormUtils.formatCurrencyWithMonetarySymbol(it) }
            }

            throw new BusinessException(Utils.getMessageProperty("mobilePhoneRecharge." + asaasError.code, asaasError.arguments))
        }
    }

    private void validateSave(Customer customer, Map params) {
        validateValueForRecharge(params)
        validatePhoneNumber(params.phoneNumber)

        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)

        List<AsaasError> listOfAsaasError = checkoutValidator.validate()
        if (listOfAsaasError) throw new BusinessException(Utils.getMessageProperty("mobilePhoneRecharge." + listOfAsaasError.first().code, listOfAsaasError.first().arguments))
    }

    private void validateValueForRecharge(Map params) {
        if (!params.value) throw new BusinessException("Informe o valor da recarga")

        ProviderAdapter providerAdapter = findProvider(params)

        List<BigDecimal> minValuesList = providerAdapter.values.collect( { it.minValue } )
        List<BigDecimal> maxValuesList = providerAdapter.values.collect( { it.maxValue } )

        BigDecimal minValue = minValuesList.min()
        BigDecimal maxValue = maxValuesList.max()

        if (params.value < minValue) throw new BusinessException("O valor da recarga é menor que o valor mínimo disponível.")
        if (params.value > maxValue) throw new BusinessException("O valor da recarga é maior que o valor máximo disponível.")
    }

    private String validatePhoneNumber(String phoneNumber) {
        if (!PhoneNumberUtils.validateMinSize(phoneNumber)) throw new BusinessException("Número de celular inválido.")

        String sanitizedPhoneNumber = PhoneNumberUtils.sanitizeNumber(phoneNumber)

        if (!PhoneNumberUtils.validateMobilePhone(sanitizedPhoneNumber)) throw new BusinessException("Número de celular inválido.")

        return sanitizedPhoneNumber
    }

    private MobilePhoneRechargeStatus buildStatus(Customer customer) {
        AuthorizationRequestType authorizationRequestType = authorizationRequestService.findAuthorizationRequestType(customer, AuthorizationRequestActionType.MOBILE_PHONE_RECHARGE_SAVE)

        if (authorizationRequestType.isCriticalAction()) {
            return MobilePhoneRechargeStatus.WAITING_CRITICAL_ACTION
        }

        if (authorizationRequestType.isExternalAuthorization()) {
            return MobilePhoneRechargeStatus.AWAITING_EXTERNAL_AUTHORIZATION
        }

        if (authorizationRequestType.isNone()) {
            return MobilePhoneRechargeStatus.PENDING
        }

        throw new IllegalArgumentException("O tipo de autorização ${authorizationRequestType} não está mapeado para um status de recarga")
    }
}

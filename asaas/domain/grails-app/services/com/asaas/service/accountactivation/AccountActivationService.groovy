package com.asaas.service.accountactivation

import com.asaas.accountmanager.AccountManagerChangeOrigin
import com.asaas.customer.CustomerStatus
import com.asaas.onboarding.AccountActivationOrigin
import com.asaas.userdevicesecurity.UserDeviceSecurityVO
import com.asaas.domain.accountactivationrequest.AccountActivationRequest
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.exception.ActionAlreadyExecutedException
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AccountActivationService {

    def accountActivationRequestService
    def createCampaignEventMessageService
    def customerAccountManagerService
    def customerStatusService
    def mobileAppTokenService
    def smsTokenService
    def userService

    public AuthorizationDevice activateAndSaveSmsToken(Customer customer, String token, UserDeviceSecurityVO userDeviceSecurityVO, AccountActivationOrigin accountActivationOrigin) {
        AccountActivationRequest accountActivationRequest = activate(customer, token, accountActivationOrigin)

        CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()

        if (customerCriticalActionConfig) {
            return smsTokenService.saveActive(customer, accountActivationRequest.phone, userDeviceSecurityVO)
        }

        return null
    }

    public AuthorizationDevice activateAndSaveMobileAppToken(Customer customer, String token, String deviceModelName, UserDeviceSecurityVO userDeviceSecurityVO, AccountActivationOrigin accountActivationOrigin) {
        activate(customer, token, accountActivationOrigin)

        return mobileAppTokenService.saveActive(customer, deviceModelName, userDeviceSecurityVO)
    }

    public void activateByCustomerAdminConsole(Customer customer, Long accountManagerId) {
        AccountManager accountManager = customerAccountManagerService.find(customer, accountManagerId)
        customerAccountManagerService.save(accountManager, customer, AccountManagerChangeOrigin.ACCOUNT_ACTIVATION, false)
        customerStatusService.activate(customer, true, null)
        createCampaignEventMessageService.saveForAccountActivated(customer, AccountActivationOrigin.WEB_CUSTOMER_ADMIN_CONSOLE)
    }

    private AccountActivationRequest activate(Customer customer, String token, AccountActivationOrigin accountActivationOrigin) {
        if (customer.status.isActive() && AccountActivationRequest.existsUsed(customer)) {
            AsaasLogger.warn("activate - customer [${customer.id}] tentou validar o token quando já está ativo e já possui solicitação de ativação usado")
            throw new ActionAlreadyExecutedException(Utils.getMessageProperty("activation.code.alreadyValidated"))
        }

        AccountActivationRequest pendingAccountActivationRequest = AccountActivationRequest.getPending(customer).get()
        if (!accountActivationRequestService.tokenIsValid(pendingAccountActivationRequest, token)) {
            throw new BusinessException(Utils.getMessageProperty("activation.code.invalid"))
        }

        if (![CustomerStatus.ACTIVE, CustomerStatus.AWAITING_ACTIVATION].contains(customer.status)) {
            AsaasLogger.warn("activate - customer [${customer.id}] tentou validar o token sem estar ativo ou aguardando ativação")
            throw new BusinessException(Utils.getMessageProperty("activation.code.error"))
        }

        AccountActivationRequest accountActivationRequest = accountActivationRequestService.setTokenAsUsed(pendingAccountActivationRequest)

        if (!customer.mobilePhone) customer.mobilePhone = accountActivationRequest.phone
        customer.activationPhone = accountActivationRequest.phone
        customer.save(failOnError: true)

        userService.saveMobilePhoneAndEnableMfaIfPossible(customer, accountActivationRequest.phone)

        customerStatusService.activate(customer, false, "Ativado com o token SMS")

        createCampaignEventMessageService.saveForAccountActivated(customer, accountActivationOrigin)

        return accountActivationRequest
    }
}

package com.asaas.service.accountactivationrequest

import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerStatus
import com.asaas.domain.accountactivationrequest.AccountActivationRequest
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.exception.SmsFailException
import com.asaas.integration.sauron.adapter.ConnectedAccountInfoAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.apache.commons.lang.RandomStringUtils

@Transactional
class AccountActivationRequestService {

    def apiConfigService
	def asaasSegmentioService
    def childAccountParameterParserService
    def connectedAccountInfoHandlerService
    def crypterService
    def smsSenderService

    public AccountActivationRequest saveForNewCustomer(Customer customer, String mobilePhone, Boolean enableWhiteLabel) {
        if (!mobilePhone) return null
        if (!canCreateForNewCustomer(customer.accountOwnerId, enableWhiteLabel)) return null

        AccountActivationRequest accountActivationRequest = save(customer, customer.email, mobilePhone)
        if (accountActivationRequest.hasErrors()) {
            String errorMessage = DomainUtils.getFirstValidationMessage(accountActivationRequest)
            AsaasLogger.warn("AccountActivationRequestService.saveForNewCustomer >> Erro ao salvar o AccountActivationRequest na criacao do customer [${customer.id}]. MobilePhone [${mobilePhone}] Error: [${errorMessage}]")
            throw new BusinessException(errorMessage)
        }

        return accountActivationRequest
    }

    public AccountActivationRequest validateMobilePhone(Customer customer, String phone) {
        AccountActivationRequest validatedAccountActivationRequest = new AccountActivationRequest()

        if (!PhoneNumberUtils.validateMobilePhone(phone) || !PhoneNumberUtils.validateBlockListNumber(phone)) {
            DomainUtils.addFieldError(validatedAccountActivationRequest, "phone", "invalid")
            return validatedAccountActivationRequest
        }

        if (AccountActivationRequest.isLimitExceededWithSamePhone(phone, ['customer[ne]': customer]) && !CustomerParameter.getValue(customer, CustomerParameterName.ALLOW_DUPLICATED_ACTIVATION_PHONE)) {
            asaasSegmentioService.track(customer.id, "account_activation_request_service", [action: "phone_number_already_in_use_by_other_customer"])
            DomainUtils.addFieldError(validatedAccountActivationRequest, "phone", "phoneNumberHasAlreadyExceededUseLimitByAnotherCustomer")
        }

        return validatedAccountActivationRequest
    }

	public AccountActivationRequest save(Customer customer, String email, String phone) throws SmsFailException {
        AccountActivationRequest accountActivationRequest = validateMobilePhone(customer, phone)

        deletePendingIfExists(customer)
        if (accountActivationRequest.hasErrors()) return accountActivationRequest

        if (AccountActivationRequest.existsUsed(customer)) {
            DomainUtils.addError(accountActivationRequest, Utils.getMessageProperty("activation.code.alreadyValidated"))
            return accountActivationRequest
        }

		accountActivationRequest = AccountActivationRequest.findNotUsedRequest(customer, phone)

        if (!accountActivationRequest) {
            accountActivationRequest = new AccountActivationRequest()
            accountActivationRequest.customer = customer
            accountActivationRequest.phone = phone
            accountActivationRequest.token = "000000"
            accountActivationRequest.save(failOnError: true)
        }

        accountActivationRequest.email = email
        String token = RandomStringUtils.randomNumeric(6)
        accountActivationRequest.token = crypterService.encryptDomainProperty(accountActivationRequest, 'token', token)
        accountActivationRequest.save(failOnError: true)

        sendSms(accountActivationRequest)

        connectedAccountInfoHandlerService.saveInfoIfPossible(new ConnectedAccountInfoAdapter(accountActivationRequest))

		def data = [phone: PhoneNumberUtils.buildFullPhoneNumber(phone)]
		asaasSegmentioService.track(customer.id, "Logged :: AccountActivation :: Fone para ativação informado", data)
		asaasSegmentioService.identify(customer.id, data)

		return accountActivationRequest
    }

	public Boolean tokenIsValid(AccountActivationRequest accountActivationRequest, String token) {
        return accountActivationRequest.getDecryptedToken() == token
	}

	public AccountActivationRequest setTokenAsUsed(AccountActivationRequest accountActivationRequest) {
		if (accountActivationRequest) {
			accountActivationRequest.used = true
			accountActivationRequest.save(flush: true)

			invalidateAllAccountActivationRequestForCustomer(accountActivationRequest.customer)
		}
        return accountActivationRequest
	}

	public void invalidateAllAccountActivationRequestForCustomer(Customer customer) {
		List<AccountActivationRequest> accountActivationRequestList = AccountActivationRequest.executeQuery("from AccountActivationRequest where customer = :customer", [customer: customer])
		for (AccountActivationRequest accountActivationRequest : accountActivationRequestList) {
			accountActivationRequest.valid = false
			accountActivationRequest.save(flush: true)
		}
	}

	public Integer getCurrentActivationStep(Customer customer) {
		Boolean existsPendingActivationRequest = AccountActivationRequest.getPending(customer).get().asBoolean()
		Integer currentStep

		if (existsPendingActivationRequest) {
			currentStep = 2
		} else if (customer.status == CustomerStatus.AWAITING_ACTIVATION || !AccountActivationRequest.existsUsed(customer)) {
			currentStep = 1
		}

		return currentStep
	}

    public void deletePendingIfExists(Customer customer) {
        AccountActivationRequest accountActivationRequest = AccountActivationRequest.getPending(customer).get()
        if (!accountActivationRequest) return

        accountActivationRequest.deleted = true
        accountActivationRequest.save(failOnError: true)
    }

	public void delete(Customer customer) {
		AccountActivationRequest.executeUpdate("update AccountActivationRequest set deleted = true, lastUpdated = :lastUpdated where customer = :customer", [lastUpdated: new Date(), customer: customer])
	}

    public void createAsUsed(Customer customer) {
        AccountActivationRequest accountActivationRequest = new AccountActivationRequest()
        accountActivationRequest.customer = customer
        accountActivationRequest.token = "000000"
        accountActivationRequest.used = true
        accountActivationRequest.save(failOnError: true)
    }

    private void sendSms(AccountActivationRequest accountActivationRequest) {
        String smsText = Utils.getMessageProperty("accountActivationRequest.smsText", [accountActivationRequest.getDecryptedToken()])
        smsSenderService.send(smsText, accountActivationRequest.phone, true, [isSecret: true])

        if (AsaasEnvironment.isDevelopment()) AsaasLogger.info("AccountActivationRequestService.sendSms >> ${smsText}")
    }

    private Boolean canCreateForNewCustomer(Long accountOwnerId, Boolean enableWhiteLabel) {
        if (enableWhiteLabel) return false
        if (!accountOwnerId) return true

        Boolean accountOwnerHasAutoApproveCreatedAccount = apiConfigService.hasAutoApproveCreatedAccount(accountOwnerId)
        if (accountOwnerHasAutoApproveCreatedAccount) return false

        if (CustomerParameter.getValue(accountOwnerId, CustomerParameterName.WHITE_LABEL)) return false

        ChildAccountParameter childAccountParameter = ChildAccountParameter.query([accountOwnerId: accountOwnerId, type: CustomerParameter.simpleName, name: CustomerParameterName.WHITE_LABEL.toString()]).get()
        if (childAccountParameter) {
            Boolean hasChildAccountParamAsWhiteLabel = childAccountParameterParserService.parse(childAccountParameter)
            if (hasChildAccountParamAsWhiteLabel) return false
        }

        return true
    }
}

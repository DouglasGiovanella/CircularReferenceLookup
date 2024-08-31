package com.asaas.service.api

import com.asaas.api.ApiChildAccountParser
import com.asaas.customer.adapter.CreateApiChildAccountCustomerAdapter
import com.asaas.customer.CompanyType
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerStatus
import com.asaas.customer.PersonType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.pushnotification.PushNotificationConfig
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ApiChildAccountService extends ApiBaseService {

    def apiAccountApprovalService
    def apiConfigService
    def apiResponseBuilderService
    def createCustomerService
    def customerService
    def customerStatusService
    def customerUpdateRequestService
    def loginValidationService
    def manualChildAccountConfigService
    def pushNotificationConfigLegacyService
    def pushNotificationConfigService
    def userService

    public Map find(Map params) {
        Customer accountOwner = getProviderInstance(params)

        Customer childAccount = Customer.notDisabledAccounts([accountOwner: accountOwner, publicId: params.id]).get()
        if (!childAccount) return apiResponseBuilderService.buildNotFoundItem()

        return apiResponseBuilderService.buildSuccess(ApiChildAccountParser.buildResponseItem(childAccount, null))
    }

    public Map list(Map params) {
        Customer accountOwner = getProviderInstance(params)
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        List<Long> disabledChildAccountIdList = Customer.childAccounts(accountOwner, ["column": "id", status: CustomerStatus.DISABLED]).list(readonly: true)

        Map filters = ApiChildAccountParser.parseFilterParams(params)
        filters.accountOwner = accountOwner

        if (disabledChildAccountIdList) {
            filters."id[notIn]" = disabledChildAccountIdList
        }

        List<Customer> childAccounts = Customer.query(filters).list(max: limit, offset: offset, readonly: true)

        List<Map> responseList = childAccounts.collect { childAccount -> ApiChildAccountParser.buildResponseItem(childAccount, null) }

        return apiResponseBuilderService.buildList(responseList, limit, offset, childAccounts.totalCount)
    }

    public Map save(Map params) {
        Customer accountOwner = getProviderInstance(params)

        if (accountOwner.accountOwner) throw new BusinessException("Contas filhas não podem gerar novas contas filhas")

        if (CustomerParameter.getValue(accountOwner, CustomerParameterName.DISABLE_CHILD_ACCOUNT_CREATION)) {
            throw new BusinessException("Criação de subcontas desabilitada. Entre em contato com o suporte.")
        }

        Map fields = ApiChildAccountParser.parseRequestParams(accountOwner, params)

        Customer validatedCustomer = validateSave(fields, accountOwner)
        if (validatedCustomer.hasErrors()) {
            transactionStatus.setRollbackOnly()
            return apiResponseBuilderService.buildErrorList(validatedCustomer)
        }

        CreateApiChildAccountCustomerAdapter customerAdapter = CreateApiChildAccountCustomerAdapter.build(fields)
        Customer createdCustomer = createCustomerService.save(customerAdapter)
        if (createdCustomer.hasErrors()) {
            AsaasLogger.warn("ApiChildAccountService.save >> createCustomer retornando lista de erros [${createdCustomer.getErrors()}]", new Throwable())
            transactionStatus.setRollbackOnly()
            return apiResponseBuilderService.buildErrorList(createdCustomer)
        }

        createdCustomer.refresh()

        saveWebhookConfigsIfNecessary(createdCustomer, fields)

        manualChildAccountConfigService.setAccountOwnerConfigs(accountOwner.id, createdCustomer.id, fields)
        def customerUpdateRequest = customerUpdateRequestService.save(createdCustomer.id, fields)
        if (customerUpdateRequest.hasErrors()) {
            transactionStatus.setRollbackOnly()
            return apiResponseBuilderService.buildErrorList(customerUpdateRequest)
        }

        String accessToken = customerService.executeApiAccessTokenCreation(createdCustomer)

        if (createdCustomer.hasAccountAutoApprovalEnabled()) {
            createdCustomer = apiAccountApprovalService.approve(createdCustomer, fields)

            if (createdCustomer.hasErrors()) {
                transactionStatus.setRollbackOnly()
                return apiResponseBuilderService.buildErrorList(createdCustomer)
            }
        }

        if (CustomerParameter.getValue(accountOwner, CustomerParameterName.AUTO_ACTIVATION_ON_CREATING_CHILD_ACCOUNTS)) {
            String observation = "Ativada devido ao parâmetro de auto-ativação habilitado na conta pai."
            customerStatusService.autoActivate(createdCustomer.id, observation)
        }

        userService.sendWelcomeToChildAccount(createdCustomer)

        return apiResponseBuilderService.buildSuccess(ApiChildAccountParser.buildResponseItem(createdCustomer, accessToken))
    }

    public Map redefineApiKey(Map params) {
        Customer accountOwner = getProviderInstance(params)

        Boolean canRedefineApiKeys = apiConfigService.canRedefineChildAccountAccessToken(accountOwner)
        if (!canRedefineApiKeys) return apiResponseBuilderService.buildNotFoundItem()

        if (!params.email) {
            return apiResponseBuilderService.buildErrorFrom("invalid_email", "O email da conta pai deve ser informado.")
        }

        if (!params.password) {
            return apiResponseBuilderService.buildErrorFrom("invalid_password", "A senha da conta pai deve ser informada.")
        }

        User user = loginValidationService.validateLogin(params.email, params.password, null, false)
        if (user.hasErrors()) {
            return apiResponseBuilderService.buildErrorFrom("invalid_email_password", "Dados da conta pai invalidos")
        }

        if (user.customer.id != accountOwner.id) {
            return apiResponseBuilderService.buildErrorFrom("invalid_accountOwner", "Dados da conta pai invalidos")
        }

        Customer childAccount = Customer.notDisabledAccounts([accountOwner: accountOwner, publicId: params.id]).get()
        if (!childAccount) return apiResponseBuilderService.buildNotFoundItem()

        String accessToken = apiConfigService.generateAccessToken(childAccount)

        return apiResponseBuilderService.buildSuccess(ApiChildAccountParser.buildResponseItem(childAccount, accessToken))
    }

    private Customer validateSave(Map fields, Customer accountOwner) {
        Customer customer = new Customer()

        if (!fields.cpfCnpj) {
            DomainUtils.addError(customer, "O campo cpfCnpj deve ser informado.")
        } else if (!CpfCnpjUtils.validate(fields.cpfCnpj)) {
            DomainUtils.addError(customer, "O campo cpfCnpj informado é inválido.")
        }

        if (!fields.name) {
            DomainUtils.addError(customer, "O campo name deve ser informado.")
        }

        if (fields.personType == PersonType.JURIDICA) {
            if (!params.companyType) {
                DomainUtils.addError(customer, "O campo companyType deve ser informado.")
            } else if (!CompanyType.convert(fields.companyType)) {
                DomainUtils.addError(customer, "O campo companyType informado é inválido.")
            }
        } else {
            Long cwsAccountOwnerId = 1591018L
            if (accountOwner.id != cwsAccountOwnerId && !fields.birthDate) {
                DomainUtils.addError(customer, "O campo birthDate deve ser informado.")
            }
        }

        if (fields.userEmail && !userService.validateEmailInUse(fields.userEmail)) {
            DomainUtils.addError(customer, "O email de login ${fields.userEmail} já está em uso.")
        }

        return customer
    }

    private void saveWebhookConfigsIfNecessary(Customer createdCustomer, Map fields) {
        if (fields.paymentWebhookConfig) {
            PushNotificationConfig paymentPushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(createdCustomer, fields.paymentWebhookConfig)
            if (paymentPushNotificationConfig.hasErrors()) throw new ValidationException(null, paymentPushNotificationConfig.errors)
        }

        if (fields.invoiceWebhookConfig) {
            PushNotificationConfig invoicePushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(createdCustomer, fields.invoiceWebhookConfig)
            if (invoicePushNotificationConfig.hasErrors()) throw new ValidationException(null, invoicePushNotificationConfig.errors)
        }

        if (fields.transferWebhookConfig) {
            PushNotificationConfig transferPushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(createdCustomer, fields.transferWebhookConfig)
            if (transferPushNotificationConfig.hasErrors()) throw new ValidationException(null, transferPushNotificationConfig.errors)
        }

        if (fields.accountStatusWebhookConfig) {
            PushNotificationConfig accountStatusPushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(createdCustomer, fields.accountStatusWebhookConfig)
            if (accountStatusPushNotificationConfig.hasErrors()) throw new ValidationException(null, accountStatusPushNotificationConfig.errors)
        }

        if (fields.pixAddressKeyWebhookConfig) {
            PushNotificationConfig pixPushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(createdCustomer, fields.pixAddressKeyWebhookConfig)
            if (pixPushNotificationConfig.hasErrors()) throw new ValidationException(null, pixPushNotificationConfig.errors)
        }

        if (fields.mobilePhoneRechargeWebhookConfig) {
            PushNotificationConfig mobilePhoneRechargePushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(createdCustomer, fields.mobilePhoneRechargeWebhookConfig)
            if (mobilePhoneRechargePushNotificationConfig.hasErrors()) throw new ValidationException(null, mobilePhoneRechargePushNotificationConfig.errors)
        }

        if (fields.billWebhookConfig) {
            PushNotificationConfig billPushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(createdCustomer, fields.billWebhookConfig)
            if (billPushNotificationConfig.hasErrors()) throw new ValidationException(null, billPushNotificationConfig.errors)
        }

        if (fields.receivableAnticipationWebhookConfig) {
            PushNotificationConfig anticipationPushNotificationConfig = pushNotificationConfigLegacyService.createOrUpdatePushNotificationConfig(createdCustomer, fields.receivableAnticipationWebhookConfig)
            if (anticipationPushNotificationConfig.hasErrors()) throw new ValidationException(null, anticipationPushNotificationConfig.errors)
        }

        if (fields.webhooks) {
            for (Map webhookData : fields.webhooks) {
                PushNotificationConfig pushNotificationConfig = pushNotificationConfigService.save(createdCustomer, webhookData)
                if (pushNotificationConfig.hasErrors()) throw new ValidationException(null, pushNotificationConfig.errors)
            }
        }
    }
}

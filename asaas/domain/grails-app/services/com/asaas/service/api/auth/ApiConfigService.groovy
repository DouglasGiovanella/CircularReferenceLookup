package com.asaas.service.api.auth

import com.asaas.api.auth.ApiAuthTokenVO
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.api.ApiConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.exception.BusinessException
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ApiConfigService {

    def apiAccessTokenGeneratorService
    def apiConfigCacheService
    def apiConfigHistoryService
    def customerInteractionService
    def customerParameterService

    public ApiConfig save(Customer customer) {
        ApiConfig apiConfig = new ApiConfig()
        apiConfig.provider = customer
        apiConfig.save(failOnError: true)

        return apiConfig
    }

    public void toggleAutoApproveCreatedAccount(Long customerId, Boolean autoApproveCreatedAccount) {
        Customer customer = Customer.get(customerId)
        ApiConfig apiConfig = ApiConfig.query([provider: customer]).get()

        if (!apiConfig) {
            apiConfig = new ApiConfig()
            apiConfig.provider = customer
        }

        apiConfig.autoApproveCreatedAccount = autoApproveCreatedAccount
        apiConfig.save(failOnError: true)

        String interactionMessage = (autoApproveCreatedAccount ? "Habilitada" : "Desabilitada") + " a auto aprovação de contas filhas"
        customerInteractionService.save(customer, interactionMessage)
        apiConfigCacheService.evict(apiConfig)
    }

    public String generateAccessToken(Customer customer) {
        ApiConfig apiConfig = ApiConfig.query([provider: customer]).get()
        if (!apiConfig) apiConfig = save(customer)

        ApiAuthTokenVO apiAuthTokenVo = apiAccessTokenGeneratorService.generateAccessToken(apiConfig)
        saveAccessToken(apiConfig, apiAuthTokenVo.encrpytedAccessToken, apiAuthTokenVo.salt)

        return apiAuthTokenVo.accessToken
    }

    public void delete(Customer provider) {
        ApiConfig apiConfig = ApiConfig.findByProvider(provider)
        if (!apiConfig) return

        apiConfig.deleted = true
        apiConfig.save(flush: true, failOnError: true)
        apiConfigCacheService.evict(apiConfig)
    }

    public void invalidateAccessToken(Long customerId) {
        ApiConfig apiConfig = ApiConfig.query([providerId: customerId]).get()
        if (!apiConfig) return

        invalidateAccessToken(apiConfig)
    }

    public void invalidateAccessToken(ApiConfig apiConfig) {
        saveAccessToken(apiConfig, null, null)
    }

    public Boolean hasGeneratedApiKey(Long providerId) {
        return ApiConfig.query([exists: true, "accessToken[isNotNull]": true, providerId: providerId]).get().asBoolean()
    }

    public void enableChildAccountAccessTokenRedefinition(Customer customer, Date expirationDate) {
        if (customer.accountOwner) throw new BusinessException("A redefinição de chaves de API deve ser habilitada somente em contas Pai.")
        if (expirationDate < new Date()) throw new BusinessException("A data de expiração deve ser maior que hoje.")

        final Integer limitDays = 2
        Date limitDate = CustomDateUtils.sumDays(new Date(), limitDays, true)
        if (expirationDate > limitDate) throw new BusinessException("A data de expiração não deve ser maior que ${limitDays} dias.")

        String formattedExpirationDate = CustomDateUtils.fromDate(expirationDate, CustomDateUtils.DATABASE_DATETIME_FORMAT)

        CustomerParameter customerParameter = customerParameterService.save(customer, CustomerParameterName.ALLOW_CHILD_ACCOUNT_API_KEY_REDEFINITION, formattedExpirationDate)
        if (customerParameter.hasErrors()) throw new ValidationException(null, customerParameter.errors)
    }

    public Boolean canRedefineChildAccountAccessToken(Customer customer) {
        String expirationDateString = CustomerParameter.getStringValue(customer, CustomerParameterName.ALLOW_CHILD_ACCOUNT_API_KEY_REDEFINITION)
        if (!expirationDateString) return false

        Date expirationDate = CustomDateUtils.fromString(expirationDateString, CustomDateUtils.DATABASE_DATETIME_FORMAT)
        if (!expirationDate) throw new RuntimeException("A data de expiração salva não é válida: CustomerId: ${customer.id}: ${expirationDateString}")

        return expirationDate >= new Date()
    }

    public Boolean hasAutoApproveCreatedAccount(Long customerId) {
        return apiConfigCacheService.getByCustomer(customerId)?.autoApproveCreatedAccount
    }

    private void saveAccessToken(ApiConfig apiConfig, String accessToken, String accessTokenSalt) {
        apiConfig.accessToken = accessToken
        apiConfig.accessTokenSalt = accessTokenSalt
        apiConfig.accessTokenExpirationDate = null
        apiConfig.save(flush: true, failOnError: true)

        apiConfigHistoryService.save(apiConfig)
        apiConfigCacheService.evict(apiConfig)
    }
}

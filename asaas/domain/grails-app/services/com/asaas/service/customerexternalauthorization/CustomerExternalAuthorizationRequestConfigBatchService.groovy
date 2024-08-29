package com.asaas.service.customerexternalauthorization

import com.asaas.criticalaction.CriticalActionType
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationRequestConfigType
import com.asaas.customerexternalauthorization.adapter.CustomerExternalAuthorizationRequestConfigBatchSaveAdapter
import com.asaas.customerexternalauthorization.adapter.CustomerExternalAuthorizationRequestConfigBatchUpdateAdapter
import com.asaas.customerexternalauthorization.adapter.CustomerExternalAuthorizationRequestConfigSaveAdapter
import com.asaas.customerexternalauthorization.adapter.CustomerExternalAuthorizationRequestConfigUpdateAdapter
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestConfig
import com.asaas.exception.BusinessException
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CustomerExternalAuthorizationRequestConfigBatchService {

    def criticalActionService
    def customerExternalAuthorizationRequestConfigService

    public List<CustomerExternalAuthorizationRequestConfig> save(Customer customer, CustomerExternalAuthorizationRequestConfigBatchSaveAdapter saveAdapter) {
        String hash = buildCriticalActionHashForSave(customer, saveAdapter)
        BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(customer.id, saveAdapter.criticalActionGroupId, saveAdapter.criticalActionToken, CriticalActionType.CUSTOMER_EXTERNAL_AUTHORIZATION_REQUEST_CONFIG_INSERT, hash)

        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        Boolean hasCustomerExternalAuthorizationRequestConfig = CustomerExternalAuthorizationRequestConfig.query([exists: true, customer: saveAdapter.customer]).get().asBoolean()

        if (hasCustomerExternalAuthorizationRequestConfig) {
            throw new BusinessException(Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.validate.error.alreadyExists"))
        }

        List<CustomerExternalAuthorizationRequestConfig> configList = []
        for (CustomerExternalAuthorizationRequestConfigType type : CustomerExternalAuthorizationRequestConfigType.values()) {
            CustomerExternalAuthorizationRequestConfig config = customerExternalAuthorizationRequestConfigService.save(new CustomerExternalAuthorizationRequestConfigSaveAdapter(saveAdapter, type))
            if (config.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(config))
            configList.add(config)
        }
        return configList
    }

    public List<CustomerExternalAuthorizationRequestConfig> saveOrUpdate(Customer customer, CustomerExternalAuthorizationRequestConfigBatchUpdateAdapter updateAdapter) {
        String hash = buildCriticalActionHashForUpdate(customer, updateAdapter)
        BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(customer.id, updateAdapter.criticalActionGroupId, updateAdapter.criticalActionToken, CriticalActionType.CUSTOMER_EXTERNAL_AUTHORIZATION_REQUEST_CONFIG_UPDATE, hash)

        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        List<CustomerExternalAuthorizationRequestConfig> configList = []

        Boolean hasConfig = CustomerExternalAuthorizationRequestConfig.query([exists: true, customer: customer]).get().asBoolean()
        if (!hasConfig) throw new BusinessException(Utils.getMessageProperty("customerExternalAuthorizationRequestConfig.validate.error.notFound"))

        for (CustomerExternalAuthorizationRequestConfigType type : CustomerExternalAuthorizationRequestConfigType.values()) {
            CustomerExternalAuthorizationRequestConfig config = CustomerExternalAuthorizationRequestConfig.query([customer: customer, type: type]).get()
            if (!config) {
                config = customerExternalAuthorizationRequestConfigService.save(new CustomerExternalAuthorizationRequestConfigSaveAdapter(customer, updateAdapter, type))
            } else {
                config = customerExternalAuthorizationRequestConfigService.update(config, new CustomerExternalAuthorizationRequestConfigUpdateAdapter(updateAdapter, type))
            }
            if (config.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(config))
            configList.add(config)
        }

        return configList
    }

    public void delete(Customer customer, Long criticalActionGroupId, String criticalActionToken) {
        String hash = buildCriticalActionHashForDelete(customer)
        BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(customer.id, criticalActionGroupId, criticalActionToken, CriticalActionType.CUSTOMER_EXTERNAL_AUTHORIZATION_REQUEST_CONFIG_DELETE, hash)

        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        List<CustomerExternalAuthorizationRequestConfig> configList = CustomerExternalAuthorizationRequestConfig.query([customer: customer]).list()
        for (CustomerExternalAuthorizationRequestConfig config : configList) {
            customerExternalAuthorizationRequestConfigService.delete(config)
        }
    }

    public CriticalActionGroup requestCriticalActionTokenForSave(Customer customer, CustomerExternalAuthorizationRequestConfigBatchSaveAdapter saveAdapter) {
        String hash = buildCriticalActionHashForSave(customer, saveAdapter)

        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.CUSTOMER_EXTERNAL_AUTHORIZATION_REQUEST_CONFIG_INSERT, hash)
    }

    public CriticalActionGroup requestCriticalActionTokenForUpdate(Customer customer, CustomerExternalAuthorizationRequestConfigBatchUpdateAdapter updateAdapter) {
        String hash = buildCriticalActionHashForUpdate(customer, updateAdapter)

        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.CUSTOMER_EXTERNAL_AUTHORIZATION_REQUEST_CONFIG_UPDATE, hash)
    }

    public CriticalActionGroup requestCriticalActionTokenForDelete(Customer customer) {
        String hash = buildCriticalActionHashForDelete(customer)

        return criticalActionService.saveAndSendSynchronous(customer, CriticalActionType.CUSTOMER_EXTERNAL_AUTHORIZATION_REQUEST_CONFIG_DELETE, hash)
    }

    private String buildCriticalActionHashForSave(Customer customer, CustomerExternalAuthorizationRequestConfigBatchSaveAdapter saveAdapter) {
        String operation = new StringBuilder(customer.id.toString())
            .append(saveAdapter.email.toString())
            .append(saveAdapter.url.toString())
            .append(saveAdapter.accessToken.toString())
            .append(saveAdapter.enabled.toString())
            .append(saveAdapter.forceAuthorization.toString())
            .toString()

        return operation.encodeAsMD5()
    }

    private String buildCriticalActionHashForUpdate(Customer customer, CustomerExternalAuthorizationRequestConfigBatchUpdateAdapter updateAdapter) {
        String operation = new StringBuilder(customer.id.toString())
            .append(updateAdapter.email.toString())
            .append(updateAdapter.url.toString())
            .append(updateAdapter.accessToken.toString())
            .append(updateAdapter.enabled.toString())
            .append(updateAdapter.forceAuthorization.toString())
            .toString()

        return operation.encodeAsMD5()
    }

    private String buildCriticalActionHashForDelete(Customer customer) {
        String operation = customer.id.toString()

        return operation.encodeAsMD5()
    }
}

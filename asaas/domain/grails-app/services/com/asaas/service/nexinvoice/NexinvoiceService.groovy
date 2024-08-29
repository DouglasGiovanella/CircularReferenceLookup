package com.asaas.service.nexinvoice

import com.asaas.cache.nexinvoicecustomerconfig.NexinvoiceCustomerConfigCacheVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.userpermission.RoleAuthority

class NexinvoiceService {

    def nexinvoiceCustomerManagerService
    def nexinvoiceCustomerCreateAsyncActionService
    def nexinvoiceCustomerConfigService
    def nexinvoiceUserConfigService
    def nexinvoiceCustomerConfigCacheService
    def userService

    public void integrateCustomer(Customer customer) {
        if (!customer) throw new BusinessException("Cliente não foi informado.")

        Boolean hasNexinvoiceCustomerConfig = nexinvoiceCustomerConfigCacheService.byCustomerId(customer.id).id.asBoolean()
        if (hasNexinvoiceCustomerConfig) throw new BusinessException("Cliente já realizou integração com a Nexinvoice.")

        String nexinvoiceCustomerConfigPublicId = UUID.randomUUID().toString()
        nexinvoiceCustomerConfigService.create(customer, nexinvoiceCustomerConfigPublicId)

        List<User> userList = getIntegrationUserList(customer)
        nexinvoiceUserConfigService.saveUserList(userList)

        nexinvoiceCustomerCreateAsyncActionService.save(customer, userList)
    }

    public String buildNexinvoiceLoginUrl(Customer customer) {
        if (AsaasEnvironment.isDevelopment()) {
            throw new BusinessException("Não é possível realizar o login em ambiente de desenvolvimento.")
        }

        if (!customer) throw new BusinessException("Cliente não foi informado.")

        NexinvoiceCustomerConfigCacheVO nexinvoiceCustomerConfig = nexinvoiceCustomerConfigCacheService.byCustomerId(customer.id)
        if (!nexinvoiceCustomerConfig.id) throw new BusinessException("Cliente não possui integração habilitada com a Nexinvoice.")

        String loginUrl = nexinvoiceCustomerManagerService.authenticate(customer.email, nexinvoiceCustomerConfig.publicId)

        return loginUrl
    }

    private getIntegrationUserList(Customer customer) {
        List<User> userList = userService.listUserWithoutRoleAuthority(customer, RoleAuthority.ROLE_ASAAS_ERP_APPLICATION, null, null)

        return userList
    }
}

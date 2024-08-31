package com.asaas.service.sandbox

import com.asaas.customer.BaseCustomer
import com.asaas.customer.CustomerStatus
import com.asaas.customer.adapter.SandboxCustomerAdapter
import com.asaas.domain.accountactivationrequest.AccountActivationRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.onboarding.AccountActivationOrigin
import com.asaas.userdevicesecurity.UserDeviceSecurityVO
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class SandboxInternalService {

    def accountActivationService
    def apiAccountApprovalService
    def apiConfigService
    def createCustomerService
    def customerPartnerApplicationService
    def customerStatusService
    def customerUpdateRequestService

    public Customer saveCustomer(Map params) {
        SandboxCustomerAdapter createCustomerAdapter = SandboxCustomerAdapter.build(params)
        Customer createdCustomer = createCustomerService.save(createCustomerAdapter)
        if (createdCustomer.hasErrors()) {
            throw new BusinessException(DomainUtils.getValidationMessages(createdCustomer.getErrors()).first())
        }

        apiConfigService.toggleAutoApproveCreatedAccount(createdCustomer.id, true)

        Map createCustomerProperties = createCustomerAdapter.properties
        BaseCustomer customerUpdateRequest = customerUpdateRequestService.save(createdCustomer.id, createCustomerProperties)
        if (customerUpdateRequest.hasErrors()) {
            throw new BusinessException(DomainUtils.getValidationMessages(customerUpdateRequest.getErrors()).first())
        }

        customerPartnerApplicationService.saveAsaasSandbox(createdCustomer)

        String observation = "Ativada devido a auto-ativação para clientes provindos do Asaas ao Sandbox."
        customerStatusService.autoActivate(createdCustomer.id, observation)

        createdCustomer = apiAccountApprovalService.approve(createdCustomer, createCustomerProperties)

        createdCustomer.refresh()

        String token = AccountActivationRequest.findNotUsedRequest(createdCustomer, createCustomerAdapter.mobilePhone).getDecryptedToken()
        UserDeviceSecurityVO userDeviceSecurityVO = new UserDeviceSecurityVO(null, false)
        accountActivationService.activateAndSaveSmsToken(createdCustomer, token, userDeviceSecurityVO, AccountActivationOrigin.WEB_INTERNAL_ONBOARDING)

        return createdCustomer
    }

    public Map getCustomerDetails(Map params) {
        String email = params.email
        Long customerIdByEmail = User.query(["username": email, column: "customer.id", "ignoreCustomer": true]).get()
        String customerPublicId = Customer.query(["id": customerIdByEmail, column: "publicId"]).get()
        if (!customerPublicId) customerPublicId = Customer.query([email: email, column: "publicId"]).get()

        String cpfCnpj = params.cpfCnpj
        Boolean isCpfCnpjAlreadyCreated = Customer.query([exists: true, cpfCnpj: cpfCnpj, "status[ne]": CustomerStatus.DISABLED]).get().asBoolean()
        CustomerStatus lastCustomerStatus = Customer.query([cpfCnpj: cpfCnpj, column: "status", "accountOwner[isNull]": true, order: "desc"]).get()

        return [
            customerPublicId: customerPublicId,
            isCpfCnpjAlreadyCreated: isCpfCnpjAlreadyCreated,
            isAccountDeleted: lastCustomerStatus?.isDisabled()
        ]
    }
}

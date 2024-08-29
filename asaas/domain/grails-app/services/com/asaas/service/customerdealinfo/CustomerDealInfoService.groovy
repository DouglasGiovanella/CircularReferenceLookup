package com.asaas.service.customerdealinfo

import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.customerdealinfo.CustomerDealInfoStatus
import com.asaas.customerdealinfo.DealerTeam
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.feenegotiation.FeeNegotiationRequest
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.feenegotiation.FeeNegotiationRequestStatus
import com.asaas.feenegotiation.FeeNegotiationType
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

import org.apache.commons.lang3.NotImplementedException

@Transactional
class CustomerDealInfoService {

    def customerDealInfoFeeConfigGroupService
    def customerDealInfoFeeConfigMccService
    def customerDealInfoInteractionService
    def feeNegotiationRequestService

    public CustomerDealInfo saveOrUpdate(Customer customer, User dealer, DealerTeam dealerTeam, FeeNegotiationType feeNegotiationType, Date dealDate) {
        if (!customer) throw new BusinessException("Cliente não encontrado.")
        Long customerDealInfoId = CustomerDealInfo.query([column: "id", customerId: customer.id, includeDeleted: true]).get()

        if (customerDealInfoId) return update(customerDealInfoId, dealer, dealerTeam, feeNegotiationType, dealDate)

        return save(customer, dealer, dealerTeam, feeNegotiationType, dealDate)
    }

    public void delete(Long id) {
        throw new NotImplementedException("Método não implementado")

        CustomerDealInfo customerDealInfo = CustomerDealInfo.get(id)
        if (!customerDealInfo) throw new BusinessException("Negociação não encontrada")

        Map search = [customerDealInfoId: customerDealInfo.id, replicationType: FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC]
        Boolean hasCustomerDealInfoFeeConfigMcc = CustomerDealInfoFeeConfigGroupRepository.query(search).exists()
        if (hasCustomerDealInfoFeeConfigMcc) {
            customerDealInfoFeeConfigMccService.deleteAll(customerDealInfo)
        }

        customerDealInfoFeeConfigGroupService.deleteAll(customerDealInfo)

        feeNegotiationRequestService.deleteAll(customerDealInfo.id)

        customerDealInfo.deleted = true
        customerDealInfo.save(failOnError: true)

        customerDealInfoInteractionService.save(customerDealInfo, "Negociação removida")
    }

    public void setAsNegotiated(Long id) {
        CustomerDealInfo customerDealInfo = CustomerDealInfo.get(id)

        customerDealInfo.status = CustomerDealInfoStatus.NEGOTIATED
        customerDealInfo.save(failOnError: true)
    }

    private CustomerDealInfo save(Customer customer, User dealer, DealerTeam dealerTeam, FeeNegotiationType feeNegotiationType, Date dealDate) {
        CustomerDealInfo validatedDomain = validateSave(customer, dealer, dealerTeam, dealDate)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        CustomerDealInfo customerDealInfo = new CustomerDealInfo()

        customerDealInfo.customer = customer
        customerDealInfo.dealer = dealer
        customerDealInfo.dealerTeam = dealerTeam
        customerDealInfo.dealDate = dealDate
        customerDealInfo.status = CustomerDealInfoStatus.IN_NEGOTIATION
        customerDealInfo.feeNegotiationType = feeNegotiationType
        customerDealInfo.save(failOnError: true)

        return customerDealInfo
    }

    private CustomerDealInfo update(Long id, User dealer, DealerTeam dealerTeam, FeeNegotiationType feeNegotiationType, Date dealDate) {
        CustomerDealInfo customerDealInfo = CustomerDealInfo.get(id)

        CustomerDealInfo validatedDomain = validateUpdate(dealer, dealerTeam, dealDate, feeNegotiationType, customerDealInfo)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        customerDealInfo.dealer = dealer
        customerDealInfo.dealerTeam = dealerTeam
        customerDealInfo.feeNegotiationType = feeNegotiationType
        customerDealInfo.dealDate = dealDate
        customerDealInfo.deleted = false
        customerDealInfo.save(failOnError: true)

        return customerDealInfo
    }

    private CustomerDealInfo validateSave(Customer customer, User dealer, DealerTeam dealerTeam, Date dealDate) {
        CustomerDealInfo validatedDomain = new CustomerDealInfo()

        if (!customer) return DomainUtils.addError(validatedDomain, "O cliente informado não existe")

        if (!dealer) return DomainUtils.addError(validatedDomain, "O usuário informado não existe")

        if (!dealerTeam) return DomainUtils.addError(validatedDomain, "Time de negociação inválido")

        if (!dealDate) return DomainUtils.addError(validatedDomain, "Data de negociação inválida")

        Boolean customerDealInfo = CustomerDealInfo.query([customerId: customer.id, exists: true]).get().asBoolean()
        if (customerDealInfo) return DomainUtils.addError(validatedDomain, "Já existe uma negociação para esse cliente [$customer.id]")

        return validatedDomain
    }

    private CustomerDealInfo validateUpdate(User dealer, DealerTeam dealerTeam, Date newDealDate, FeeNegotiationType newFeeNegotiationType, CustomerDealInfo customerDealInfo) {
        CustomerDealInfo validatedDomain = new CustomerDealInfo()

        if (customerDealInfo.deleted) return DomainUtils.addError(validatedDomain, Utils.getMessageProperty("customerDealInfo.notAllowedUpdate.isDeleted"))

        if (!dealer) return DomainUtils.addError(validatedDomain, "O usuário informado não existe")

        if (!dealerTeam) return DomainUtils.addError(validatedDomain, "Time de negociação inválido")

        if (!newDealDate) return DomainUtils.addError(validatedDomain, "Data de negociação inválida")

        Date currentDealDate = customerDealInfo.dealDate
        if (currentDealDate > newDealDate) return DomainUtils.addError(validatedDomain, "Data de negociação deve ser maior ou igual a data de negociação atual")

        Boolean hasFeeNegotiationRequestAwaitingApproval = FeeNegotiationRequest.query([exists: true, customerDealInfo: customerDealInfo, status: FeeNegotiationRequestStatus.AWAITING_APPROVAL]).get().asBoolean()
        FeeNegotiationType currentFeeNegotiationType = customerDealInfo.feeNegotiationType
        if (hasFeeNegotiationRequestAwaitingApproval && currentFeeNegotiationType != newFeeNegotiationType) return DomainUtils.addError(validatedDomain, "Não é possível alterar o limite de negociação, pois a negociação possui uma solicitação aguardando aprovação.")

        return validatedDomain
    }
}

package com.asaas.service.asaascard

import com.asaas.asaascard.AsaasCardType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.customercreditlimit.AsaasCardCreditLimitListAdapter

import grails.transaction.Transactional

@Transactional
class AsaasCardCreditLimitService {

    def bifrostCustomerCreditLimitManagerService

    public void save(Long customerId, BigDecimal value) {
        validateToSave(customerId, value)
        if (!Customer.read(customerId).status.isActive()) throw new BusinessException("Não foi possível cadastrar o limite. Cliente inativo.")
        bifrostCustomerCreditLimitManagerService.save(customerId, value)
    }

    public void update(Long customerId, BigDecimal value) {
        validateToSave(customerId, value)
        if (AsaasCard.query([customerId: customerId, type: AsaasCardType.CREDIT, exists: true]).get()) throw new BusinessException("Não foi possível atualizar o limite. Cliente já possui pós-pago emitido.")
        bifrostCustomerCreditLimitManagerService.update(customerId, value)
    }

    public AsaasCardCreditLimitListAdapter list(Long customerId, BigDecimal value, Integer offset, Integer limit) {
        return bifrostCustomerCreditLimitManagerService.list(customerId, value, offset, limit)
    }

    private void validateToSave(Long customerId, BigDecimal value) {
        if (!customerId) throw new BusinessException("Cliente deve ser informado.")
        if (!value) throw new BusinessException("Valor do limite deve ser informado.")

        return
    }
}

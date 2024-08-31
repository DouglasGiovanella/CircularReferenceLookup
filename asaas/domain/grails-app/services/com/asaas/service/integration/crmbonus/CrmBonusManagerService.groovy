package com.asaas.service.integration.crmbonus

import com.asaas.integration.crmbonus.adapter.CrmBonusCreateCustomerAccountRequestDTO
import com.asaas.integration.crmbonus.adapter.CrmBonusResponseAdapter
import com.asaas.integration.crmbonus.adapter.CrmBonusSendBonusRequestDTO
import com.asaas.integration.crmbonus.enums.CrmManagerResponseStatus
import com.asaas.integration.crmbonus.manager.CrmBonusManager

import grails.transaction.Transactional

@Transactional
class CrmBonusManagerService {

    public CrmBonusResponseAdapter integrateCustomerAccount(String cpf) {
        CrmBonusCreateCustomerAccountRequestDTO request = new CrmBonusCreateCustomerAccountRequestDTO(cpf)
        CrmBonusManager manager = new CrmBonusManager()
        manager.post("onboarding/by-partner", request.toMap())

        return buildResponseAdapter(manager, request.toMap())
    }

    public CrmBonusResponseAdapter sendBonus(String cpf, BigDecimal bonusValue) {
        CrmBonusSendBonusRequestDTO request = new CrmBonusSendBonusRequestDTO(cpf, bonusValue)
        CrmBonusManager manager = new CrmBonusManager()
        manager.post("transactions/pre-recharge", request.toMap())

        return buildResponseAdapter(manager, request.toMap())
    }

    private CrmBonusResponseAdapter buildResponseAdapter(CrmBonusManager manager, Map requestMap) {
        CrmBonusResponseAdapter adapter = new CrmBonusResponseAdapter()

        adapter.requestMap = requestMap
        adapter.responseBody = manager.responseBody

        if (manager.isSuccessful()) {
            adapter.id = adapter.responseBody.id
            adapter.status = CrmManagerResponseStatus.SUCCESS
            return adapter
        }

        if (manager.isConnectionFailure()) {
            adapter.status = CrmManagerResponseStatus.FAILED
            adapter.exception = new RuntimeException(manager.getErrorMessage())
            return adapter
        }

        adapter.status = CrmManagerResponseStatus.ERROR
        adapter.errorMessage = "${ adapter.responseBody.code ?: adapter.responseBody.error } - ${ adapter.responseBody.message }"
        return adapter
    }
}

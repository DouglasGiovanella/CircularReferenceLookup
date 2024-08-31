package com.asaas.service.bifrost

import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.customercreditlimit.AsaasCardCreditLimitListAdapter
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.integration.bifrost.dto.asaascard.creditcardlimit.BifrostListCustomerCreditLimitResponseDTO
import com.asaas.integration.bifrost.dto.customercreditlimit.BifrostGetCustomerCreditLimitResponseDTO
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BifrostCustomerCreditLimitManagerService {

    private static final BigDecimal DEFAULT_CREDIT_CARD_LIMIT = 1000.00

    public void save(Long customerId, BigDecimal value) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/customerCreditLimits", [customerId: customerId, value: value])

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public BigDecimal get(Long customerId) {
        if (!BifrostManager.isEnabled()) return DEFAULT_CREDIT_CARD_LIMIT

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/customerCreditLimits", [customerId: customerId])

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível consultar o limite. Por favor, tente novamente.")
        if (!bifrostManager.responseBody) return null

        BifrostGetCustomerCreditLimitResponseDTO getCustomerCreditLimitResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostGetCustomerCreditLimitResponseDTO)
        return getCustomerCreditLimitResponseDTO.customerCreditLimit
    }

    public void update(Long customerId, BigDecimal value) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.post("/customerCreditLimits/update", [customerId: customerId, value: value])

        if (!bifrostManager.isSuccessful()) throw new BusinessException(bifrostManager.getErrorMessage())
    }

    public AsaasCardCreditLimitListAdapter list(Long customerId, BigDecimal value, Integer offset, Integer limit) {
        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/customerCreditLimits/list", [customerId: customerId, value: value, offset: offset, limit: limit])

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível listar limites. Por favor, tente novamente.")

        BifrostListCustomerCreditLimitResponseDTO bifrostListCreditLimitResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostListCustomerCreditLimitResponseDTO)

        return new AsaasCardCreditLimitListAdapter(bifrostListCreditLimitResponseDTO)
    }

}

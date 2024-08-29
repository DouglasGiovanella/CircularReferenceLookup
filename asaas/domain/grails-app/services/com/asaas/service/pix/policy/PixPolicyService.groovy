package com.asaas.service.pix.policy

import com.asaas.exception.BusinessException
import com.asaas.integration.pix.enums.policy.PolicyType
import com.asaas.pix.adapter.policy.PolicyAdapter

import grails.transaction.Transactional

@Transactional
class PixPolicyService {

    def hermesPolicyManagerService

    public PolicyAdapter get(PolicyType policyType) {
        if (!policyType) throw new BusinessException("Necessário informar o tipo de política a ser buscado")

        return hermesPolicyManagerService.get(policyType)
    }
}

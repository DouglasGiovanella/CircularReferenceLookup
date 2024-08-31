package com.asaas.service.customer

import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CustomerCriticalActionService {

    def criticalActionService
    def customerService

    public CriticalActionGroup saveApiKeyCreationCriticalActionGroup(User user) {
        String hash = buildCriticalActionHash(user)

        return criticalActionService.saveAndSendSynchronous(user.customer, CriticalActionType.API_KEY_CREATION, hash, null)
    }

    public CriticalActionGroup saveCommercialInfoPeriodicUpdateCriticalActionGroup(User user) {
        String hash = buildCriticalActionHash(user)

        return criticalActionService.saveAndSendSynchronous(user.customer, CriticalActionType.PERIODIC_CUSTOMER_COMMERCIAL_INFO_UPDATE, hash, null)
    }

    public String validateTokenAndCreateApiKey(User user, Map params) {
        if (Utils.isEmptyOrNull(params.criticalActionGroupId)) {
            throw new BusinessException("Não foi possível autorizar o evento crítico")
        }

        Long criticalActionGroupId = Long.valueOf(params.criticalActionGroupId)
        validateCriticalActionToken(user, params.criticalActionToken, criticalActionGroupId, CriticalActionType.API_KEY_CREATION)

        return customerService.createApiAccessToken(user)
    }

    public void validateCriticalActionToken(User user, String criticalActionToken, Long criticalActionGroupId, CriticalActionType criticalActionType) {
        String hash = buildCriticalActionHash(user)

        BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(user.customer.id, criticalActionGroupId, criticalActionToken, criticalActionType, hash)
        if (!businessValidation.isValid())  {
            throw new BusinessException(businessValidation.getFirstErrorMessage())
        }
    }

    private String buildCriticalActionHash(User user) {
        String operation = new StringBuilder(user.id.toString())
            .append(user.customer.id.toString())
            .toString()

        return operation.encodeAsMD5()
    }
}

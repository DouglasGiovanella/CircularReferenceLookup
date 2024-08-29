package com.asaas.service.notificationtemplate

import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.exception.CriticalActionValidationException
import com.asaas.notificationtemplate.CustomNotificationTemplateVO
import com.asaas.validation.BusinessValidation
import org.apache.commons.lang.NotImplementedException

import grails.transaction.Transactional

@Transactional
class CustomNotificationTemplateCriticalActionService {

    def criticalActionService

    public CriticalActionGroup requestToken(CustomNotificationTemplateVO customNotificationTemplateVO) {
        String hash = buildCriticalActionHash(customNotificationTemplateVO)

        return criticalActionService.saveAndSendSynchronous(customNotificationTemplateVO.customer, CriticalActionType.CUSTOM_NOTIFICATION_TEMPLATE_SAVE, hash)
    }

    public void authorize(CustomNotificationTemplateVO customNotificationTemplateVO, Map tokenParams) {
        String hash = buildCriticalActionHash(customNotificationTemplateVO)

        BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(customNotificationTemplateVO.customer.id, tokenParams.groupId, tokenParams.token, CriticalActionType.CUSTOM_NOTIFICATION_TEMPLATE_SAVE, hash)
        if (!businessValidation.isValid()) throw new CriticalActionValidationException(businessValidation.getFirstErrorMessage())
    }

    private String buildCriticalActionHash(CustomNotificationTemplateVO customNotificationTemplateVO) {
        String operation = ""
        operation += customNotificationTemplateVO.customer.id.toString()
        operation += customNotificationTemplateVO.templateGroup.id.toString()
        operation += customNotificationTemplateVO.type.toString()
        operation += customNotificationTemplateVO.body

        if (customNotificationTemplateVO.type.isEmail()) {
            operation += customNotificationTemplateVO.subject
            operation += customNotificationTemplateVO.headerTitle
        } else if (!customNotificationTemplateVO.type.isSms()) {
            throw new NotImplementedException("Não foi implementado processamento para a notificação do tipo [${customNotificationTemplateVO.type}]")
        }

        if (!operation) throw new RuntimeException("CustomNotificationTemplateCriticalActionService.buildCriticalActionHash >> Operação não suportada!")
        return operation.encodeAsMD5()
    }
}

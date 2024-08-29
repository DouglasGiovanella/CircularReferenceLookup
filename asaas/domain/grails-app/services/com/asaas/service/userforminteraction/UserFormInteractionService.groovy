package com.asaas.service.userforminteraction

import com.asaas.domain.userforminteraction.UserFormInteraction
import com.asaas.log.AsaasLogger
import com.asaas.userFormInteraction.enums.UserFormInteractionType
import com.asaas.userFormInteraction.vo.UserFormInteractionInfoVO
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class UserFormInteractionService {

    public List<UserFormInteraction> saveList(List<UserFormInteractionInfoVO> userFormInteractionInfoVOList) {
        UserFormInteraction validatedDomain = validateSave(userFormInteractionInfoVOList)
        if (validatedDomain.hasErrors()) {
            AsaasLogger.warn("UserFormInteractionService.saveList >>> Erro na validação dos dados de interação de usuários. Erro: ${DomainUtils.getValidationMessages(validatedDomain.getErrors()).first()}")
        }

        List<UserFormInteraction> savedUserFormInteractionList = []
        for (UserFormInteractionInfoVO userFormInteractionInfoVO : userFormInteractionInfoVOList) {
            UserFormInteraction userFormInteraction = new UserFormInteraction()
            String pastedFieldString = userFormInteractionInfoVO.pastedFieldList?.join(",")
            if (pastedFieldString) userFormInteraction.pastedFieldList = pastedFieldString
            userFormInteraction.customerId = userFormInteractionInfoVO.customerId
            userFormInteraction.eventOrigin = userFormInteractionInfoVO.eventOrigin
            userFormInteraction.remoteIp = userFormInteractionInfoVO.remoteIp
            userFormInteraction.type = userFormInteractionInfoVO.type
            userFormInteraction.interactionStart = userFormInteractionInfoVO.interactionStart
            userFormInteraction.interactionEnd = userFormInteractionInfoVO.interactionEnd

            savedUserFormInteractionList +=  userFormInteraction.save(flush: true, failOnError: true)
        }

        return savedUserFormInteractionList
    }

    public Boolean validateOrderOfTypes(List<UserFormInteractionInfoVO> userFormInteractionInfoVOList) {
        UserFormInteractionInfoVO previousUserFormInteractionInfoVO

        for (UserFormInteractionInfoVO userFormInteractionInfoVO : userFormInteractionInfoVOList) {
            if (previousUserFormInteractionInfoVO) {
                List<UserFormInteractionType> currentInteractionTypeList = userFormInteractionInfoVO.tokenizedInteractionTypeList.clone()
                currentInteractionTypeList.pop()

                AsaasLogger.info("UserFormInteractionService.validateOrderOfTypes >>> CurrentList: ${currentInteractionTypeList}, PreviousList: ${previousUserFormInteractionInfoVO.tokenizedInteractionTypeList}")

                if (previousUserFormInteractionInfoVO.tokenizedInteractionTypeList != currentInteractionTypeList) {
                    return false
                }
            }

            previousUserFormInteractionInfoVO = userFormInteractionInfoVO
        }

        return true
    }

    private UserFormInteraction validateSave(List<UserFormInteractionInfoVO> userFormInteractionInfoVOList) {
        UserFormInteraction validatedDomain = new UserFormInteraction()

        if (!userFormInteractionInfoVOList) return DomainUtils.addError(validatedDomain, "Lista de eventos não informada")

        if (!validateOrderOfTypes(userFormInteractionInfoVOList)) return DomainUtils.addError(validatedDomain, "Ordem de eventos inválidos.")

        return validatedDomain
    }
}

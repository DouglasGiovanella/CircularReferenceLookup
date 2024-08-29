package com.asaas.service.hubspot

import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.email.EmailValidator
import com.asaas.hubspot.adapter.HubspotDealWithContactAssociationSaveAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class HubspotDealService {

    private static final Integer FLUSH_EVERY = 50

    def asyncActionService
    def hubspotContactManagerService
    def hubspotDealManagerService
    def hubspotService

    public void saveCreateHubspotDealWithContactAssociation(HubspotDealWithContactAssociationSaveAdapter adapter) {
        adapter.contactId = getHubspotContactIdIfExists(adapter.personEmail)

        asyncActionService.saveCreateHubspotDealWithContactAssociation(adapter.toMap())
    }

    public processPendingCreateSdrDealAndAssociateWithContact() {
        final Integer maxPendingItems = 100
        List<Map> asyncActionDataList = asyncActionService.listPendingCreateHubspotDealWithContactAssociation(maxPendingItems)
        Utils.forEachWithFlushSession(asyncActionDataList, FLUSH_EVERY, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError ({
                String contactId = asyncActionData.contactId
                if (Utils.isEmptyOrNull(contactId)) {
                    contactId = getHubspotContactIdIfExists(asyncActionData.personEmail)
                    if (Utils.isEmptyOrNull(contactId)) {
                        AsaasLogger.warn("HubspotDealService.processPendingCreateSdrDealAndAssociateWithContact >> ContactId não encontrado. AsyncActionData: [${asyncActionData}]")
                        AsyncAction asyncAction = asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                        if (asyncAction.status.isCancelled()) AsaasLogger.error("HubspotDealService.processPendingCreateSdrDealAndAssociateWithContact >> Erro ao criar negocio e quantidade de tentativas foi excedida. AsyncActionData: [${asyncActionData}]")
                        return
                    }
                }

                Boolean success = hubspotDealManagerService.createDealAndContactAssociation(asyncActionData, contactId)
                if (!success) {
                    asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                    return
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "HubspotDealService.processPendingCreateSdrDealAndAssociateWithContact >> Erro ao criar o negócio. ",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        })
    }

    public BusinessValidation validateSaveHubspotDeal(HubspotDealWithContactAssociationSaveAdapter adapter) {
        BusinessValidation businessValidation = new BusinessValidation()

        String blankStateErrorCode = "createHubspotDealAndContactAssociation.blankState.message"
        String invalidStateErrorCode = "createHubspotDealAndContactAssociation.invalidState.message"

        if (!adapter.personName) businessValidation.addError(blankStateErrorCode, ["name"])
        if (!adapter.personEmail) businessValidation.addError(blankStateErrorCode, ["personEmail"])
        if (!adapter.personPhone) businessValidation.addError(blankStateErrorCode, ["personPhone"])
        if (!adapter.organizationName) businessValidation.addError(blankStateErrorCode, ["organizationName"])
        if (!adapter.cnpj) businessValidation.addError(blankStateErrorCode, ["cnpj"])
        if (!adapter.monthlyPaymentsAmount) businessValidation.addError(blankStateErrorCode, ["monthlyPaymentsAmount"])
        if (!adapter.customerGoals) businessValidation.addError(blankStateErrorCode, ["customerGoals"])
        if (adapter.customerGoals?.isOther() && !adapter.otherGoal) businessValidation.addError(blankStateErrorCode, ["otherGoal"])

        if (!businessValidation.isValid()) return businessValidation

        if (!EmailValidator.isValid(adapter.personEmail)) businessValidation.addError(invalidStateErrorCode, ["personEmail"])
        if (!PhoneNumberUtils.validatePhone(adapter.personPhone)) businessValidation.addError(invalidStateErrorCode, ["personPhone"])
        if (!CpfCnpjUtils.validate(adapter.cnpj)) businessValidation.addError(invalidStateErrorCode, ["cnpj"])

        return businessValidation
    }

    private String getHubspotContactIdIfExists(String email) {
        String contactId = hubspotService.getHubspotContactIdIfExists(email)
        if (Utils.isEmptyOrNull(contactId)) contactId = hubspotContactManagerService.searchContact(email)

        return contactId
    }
}

package com.asaas.service.beamer

import com.asaas.domain.customer.Customer
import com.asaas.domain.externalidentifier.ExternalIdentifier
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.externalidentifier.ExternalResource
import com.asaas.integration.beamer.adapter.BeamerUserAdapter
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BeamerService {

    def asyncActionService
    def beamerManagerService
    def externalIdentifierCacheService
    def externalIdentifierService

    public void integrateBeamerIfNecessary(Long customerId, Map customProperties) {
        if (isIntegratedWithBeamer(customerId)) return

        Map actionData = [customerId: customerId, customProperties: customProperties]
        asyncActionService.saveBeamerUserCreation(actionData)
    }

    public void updateUserInformation(Long customerId, Map customProperties) {
        if (!isIntegratedWithBeamer(customerId)) return

        Map actionData = [customerId: customerId, customProperties: customProperties]
        asyncActionService.saveBeamerUserUpdate(actionData)
    }

    public void processPendingCreateUser() {
        final Integer maxPendingItems = 50

        List<Map> asyncActionDataList = asyncActionService.listPendingBeamerUserCreation(maxPendingItems)

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError ({
                Customer customer = Customer.read(asyncActionData.customerId)
                BeamerUserAdapter beamerUserAdapter = beamerManagerService.createUser(customer, asyncActionData.customProperties)

                if (!beamerUserAdapter.success) {
                    asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                    return
                }

                externalIdentifierService.save(customer, ExternalApplication.BEAMER, ExternalResource.CUSTOMER, beamerUserAdapter.beamerId, customer)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "BeamerService.processPendingCreateUser() >> Erro ao criar usuario no beamer. CustomerId: [${asyncActionData.customerId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        }
    }

    public void processPendingUpdateUser() {
        final Integer maxPendingItems = 50

        List<Map> asyncActionDataList = asyncActionService.listPendingBeamerUserUpdate(maxPendingItems)

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError ({
                Customer customer = Customer.read(asyncActionData.customerId)
                Boolean success = beamerManagerService.updateUser(customer, asyncActionData.customProperties)

                if (!success) {
                    asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
                    return
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "BeamerService.processPendingUpdateUser() >> Erro ao atualizar usuario no beamer. CustomerId: [${asyncActionData.customerId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }]
            )
        }
    }

    public void createOrUpdateUser(Long customerId, Map customProperties) {
        if (isIntegratedWithBeamer(customerId)) {
            updateUserInformation(customerId, customProperties)
        } else {
            integrateBeamerIfNecessary(customerId, customProperties)
        }
    }

    private Boolean isIntegratedWithBeamer(Long customerId) {
        Boolean isIntegratedWithBeamer = externalIdentifierCacheService.getExternalIdentifier(customerId, ExternalApplication.BEAMER).asBoolean()
        return isIntegratedWithBeamer
    }
}

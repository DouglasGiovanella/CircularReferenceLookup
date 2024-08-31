package com.asaas.service.integration.bacen.ccs

import com.asaas.asyncaction.AsyncActionType
import com.asaas.ccs.adapters.BacenCcsAdapter
import com.asaas.ccs.adapters.BacenCcsItemAdapter
import com.asaas.customer.CustomerStatus
import com.asaas.domain.auditlog.AuditLogEvent
import com.asaas.domain.ccs.CcsInitialLoadBatchFileItem
import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.bacen.ccs.BacenCcs
import com.asaas.domain.integration.bacen.ccs.BacenCcsItem
import com.asaas.domain.integration.bacen.ccs.enums.BacenCcsSyncStatus
import com.asaas.domain.integration.bacen.ccs.enums.BacenCcsSyncType
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class BacenCcsService {

    def asyncActionService
    def bacenCcsManagerService
    def messageService
    def presentaManagerService

    public void createStartOfRelationshipIfNecessary(Customer customer) {
        if (!customer.status.isActive()) return
        if (!customer.customerRegisterStatus.generalApproval.isApproved()) return
        if (!customer.cpfCnpj) {
            AsaasLogger.warn("BacenCcsService.createStartOfRelationshipIfNecessary -> Erro ao reportar o relacionamento para o customerId: [${customer.id}] não possui registro de CpfCnpj.")
            return
        }

        saveStartOfRelationshipIfNecessary(customer.cpfCnpj, customer.dateCreated, customer)

        Map actionData = [customerId: customer.id, syncType: BacenCcsSyncType.START_RELATIONSHIP]
        if (!asyncActionService.hasAsyncActionPendingWithSameParameters(actionData, AsyncActionType.REGULATORY_SEND_RELATIONSHIP_STATUS)) {
            asyncActionService.save(AsyncActionType.REGULATORY_SEND_RELATIONSHIP_STATUS, actionData)
        }
    }

    public void updateEndOfRelationshipIfNecessary(Customer customer) {
        if (!customer.cpfCnpj) {
            AsaasLogger.warn("BacenCcsService.updateEndOfRelationshipIfNecessary -> Erro ao reportar o relacionamento para o customerId: [${customer.id}] não possui registro de CpfCnpj.")
            return
        }

        saveEndOfRelationshipIfNecessary(customer.cpfCnpj, customer)
        asyncActionService.save(AsyncActionType.REGULATORY_SEND_RELATIONSHIP_STATUS, [customerId: customer.id, syncType: BacenCcsSyncType.END_RELATIONSHIP])
    }

    public void updateForCustomerCpfCnpjChange(String oldCpfCnpj, String newCpfCnpj, Customer customer) {
        saveEndOfRelationshipIfNecessary(oldCpfCnpj, customer)
        saveStartOfRelationshipIfNecessary(newCpfCnpj, new Date(), customer)
        asyncActionService.save(AsyncActionType.REGULATORY_SEND_RELATIONSHIP_STATUS, [customerId: customer.id, syncType: BacenCcsSyncType.UPDATE_CUSTOMER_CPF_CNPJ_CHANGE, oldCpfCnpj: oldCpfCnpj, newCpfCnpj: newCpfCnpj])
    }

    public void setStatusForBacenCcsList(List<Long> ccsIdList, BacenCcsSyncStatus syncStatus) {
        Utils.forEachWithFlushSession(ccsIdList, 50, { Long bacenCcsId ->
            BacenCcs bacenCcs = BacenCcs.get(bacenCcsId)
            bacenCcs.syncStatus = syncStatus
            bacenCcs.save(failOnError: true)
        })
    }

    public List<BacenCcsAdapter> searchBacenCcsList(List<String> cpfCnpjList, Date relationshipStartDate, Date relationshipEndDate) {
        List<BacenCcsAdapter> bacenCcsList = bacenCcsManagerService.searchBacenCcs(cpfCnpjList, relationshipStartDate, relationshipEndDate)
        return bacenCcsList
    }

    public List<BacenCcsItemAdapter> searchBacenCcsItemList(List<Long> bacenCcsIdList, Date relationshipStartDate, Date relationshipEndDate) {
        List<BacenCcsItemAdapter> bacenCcsItemList = bacenCcsManagerService.searchBacenCcsItem(bacenCcsIdList, relationshipStartDate, relationshipEndDate)
        return bacenCcsItemList
    }

    public List<BacenCcsItemAdapter> searchBacenCcsItemByCpfCnpjList(List<String> cpfCnpjList, Date relationshipStartDate, Date relationshipEndDate) {
        List<BacenCcsItemAdapter> bacenCcsItemList = bacenCcsManagerService.searchBacenCcsItemByCpfCnpjList(cpfCnpjList, relationshipStartDate, relationshipEndDate)
        return bacenCcsItemList
    }

    public void sendRelationshipStatus() {
        final Integer maxItemsPerCycle = 100
        List<Long> pendingRelationshipForDeleteIdList = []
        List<Long> asyncActionErrorList = []

        for (Map asyncActionData: asyncActionService.listPending(AsyncActionType.REGULATORY_SEND_RELATIONSHIP_STATUS, maxItemsPerCycle)) {
            try {
                switch (BacenCcsSyncType.convert(asyncActionData.syncType)) {
                    case BacenCcsSyncType.START_RELATIONSHIP:
                        bacenCcsManagerService.createStartOfRelationship(Customer.read(asyncActionData.customerId))
                        pendingRelationshipForDeleteIdList.add(asyncActionData.asyncActionId)
                        break
                    case BacenCcsSyncType.END_RELATIONSHIP:
                        bacenCcsManagerService.updateEndOfRelationship(Customer.read(asyncActionData.customerId))
                        pendingRelationshipForDeleteIdList.add(asyncActionData.asyncActionId)
                        break
                    case BacenCcsSyncType.UPDATE_CUSTOMER_CPF_CNPJ_CHANGE:
                        bacenCcsManagerService.updateForCustomerCpfCnpjChange(asyncActionData.oldCpfCnpj, asyncActionData.newCpfCnpj, Customer.read(asyncActionData.customerId))
                        pendingRelationshipForDeleteIdList.add(asyncActionData.asyncActionId)
                        break
                    default:
                        throw new NotImplementedException("Tipo de relacionamento não implementado.")
                }
            } catch (Exception exception) {
                asyncActionErrorList.add(asyncActionData.asyncActionId)
                AsaasLogger.error("BacenCcsService.executeSendingRelationshipStatus >> Erro ao enviar os registros do BacencCcs AsyncActionID: ${asyncActionData.asyncActionId}", exception)
            }
        }

        Utils.withNewTransactionAndRollbackOnError( {
            asyncActionService.setListAsError(asyncActionErrorList)
            asyncActionService.deleteList(pendingRelationshipForDeleteIdList)
        }, [logErrorMessage: "BacenCcsService.executeSendingRelationshipStatus >> Erro ao deletar ou atualizar para error os registros do BacencCcs - deletedList: ${pendingRelationshipForDeleteIdList}  ErrorList - ${asyncActionErrorList}"])
    }

    public void saveStartOfRelationshipIfNecessary(String cpfCnpj, Date startRelationshipDate, Customer customer) {
        if (!customer.status.isActive()) return

        if (!customer.customerRegisterStatus.generalApproval.isApproved()) return

        BacenCcs activeBacenCcs = BacenCcs.getActiveForCpfCnpj(cpfCnpj)
        if (!activeBacenCcs) {
            activeBacenCcs = BacenCcs.getAwaitingSyncForCpfCnpj(cpfCnpj)
            if (activeBacenCcs) {
                activeBacenCcs = saveRestartOfRelationship(activeBacenCcs)
            } else {
                activeBacenCcs = saveStartOfRelationship(cpfCnpj, startRelationshipDate, customer.getProviderName())
            }
        }

        saveStartOfCcsItem(activeBacenCcs, customer, cpfCnpj, startRelationshipDate)
    }

    private void saveEndOfRelationshipIfNecessary(String cpfCnpj, Customer customer) {
        BacenCcs bacenCcs = BacenCcs.getActiveForCpfCnpj(cpfCnpj)

        fixMissingCustomerName(bacenCcs)

        saveEndOfCcsItem(bacenCcs, customer)

        Boolean cpfCnpjExistsInAnotherActiveCustomer = Customer.query([exists: true, "cpfCnpj": cpfCnpj, "id[notIn]": customer.id, "status": CustomerStatus.ACTIVE]).get().asBoolean()
        if (cpfCnpjExistsInAnotherActiveCustomer) return

        saveEndOfRelationship(bacenCcs, cpfCnpj, customer)
    }

    private void setAsErrorWithNewTransaction(Long bacenCcsId) {
        Utils.withNewTransactionAndRollbackOnError({
            BacenCcs bacenCcs = BacenCcs.get(bacenCcsId)
            bacenCcs.syncStatus = BacenCcsSyncStatus.ERROR
            bacenCcs.save(failOnError: true)
        })
    }

    private void fixMissingCustomerName(BacenCcs bacenCcs) {
        if (bacenCcs && !bacenCcs.name) {
            CcsInitialLoadBatchFileItem ccsInitialLoadBatchFileItem = CcsInitialLoadBatchFileItem.query([ccsId: bacenCcs.id]).get()
            if (!ccsInitialLoadBatchFileItem) {
                AsaasLogger.error("[Bacen CCS] Não foi possível recuperar o nome do cliente no registro [${bacenCcs.id}] do CCS ")
                return
            }

            bacenCcs.name = ccsInitialLoadBatchFileItem.name
            bacenCcs.save(failOnError: true)
        }
    }

    private BacenCcs saveStartOfRelationship(String cpfCnpj, Date startDate, String name) {
        BacenCcs bacenCcs = new BacenCcs()
        bacenCcs.cpfCnpj = cpfCnpj
        bacenCcs.name = name
        bacenCcs.syncStatus = BacenCcsSyncStatus.AWAITING_INCLUSION
        bacenCcs.relationshipStartDate = startDate
        bacenCcs.relationshipEndDate = null

        return bacenCcs.save(failOnError: true)
    }

    private BacenCcsItem saveStartOfCcsItem(BacenCcs activeBacenCcs, Customer customer, String cpfCnpj, Date startRelationshipDate) {
        BacenCcsItem bacenCcsItem =  BacenCcsItem.query([ccsId: activeBacenCcs.id, customerId: customer.id]).get()
        if (bacenCcsItem) {
            AsaasLogger.info("BacenCcsService.saveStartOfCcsItem >> O registro do BacenCcsItem já existe para o cliente: [${customer.id}] e BacenCcs: [${activeBacenCcs.id}]")
            return bacenCcsItem
        }

        bacenCcsItem = new BacenCcsItem()
        bacenCcsItem.cpfCnpj = cpfCnpj
        bacenCcsItem.ccs = activeBacenCcs
        bacenCcsItem.customer = customer
        bacenCcsItem.relationshipStartDate = startRelationshipDate
        bacenCcsItem.relationshipEndDate = null
        return bacenCcsItem.save(failOnError: true)
    }

    private BacenCcs saveRestartOfRelationship(BacenCcs activeBacenCcs) {
        activeBacenCcs.relationshipEndDate = null
        if (activeBacenCcs.syncStatus.isAwaitingUpdate()) activeBacenCcs.syncStatus = BacenCcsSyncStatus.AWAITING_PARTNER
        return activeBacenCcs.save(failOnError: true)
    }

    private BacenCcsItem saveEndOfCcsItem(BacenCcs bacenCcs, Customer customer) {
        if (!bacenCcs) return null

        BacenCcsItem bacenCcsItem = BacenCcsItem.query([ccsId: bacenCcs.id, customerId: customer.id]).get()
        if (!bacenCcsItem) {
            bacenCcsItem = saveStartOfCcsItem(bacenCcs, customer, bacenCcs.cpfCnpj, bacenCcs.relationshipStartDate)
        }

        bacenCcsItem.relationshipEndDate = new Date()
        return bacenCcsItem.save(failOnError: true)
    }

    private BacenCcs saveEndOfRelationship(BacenCcs bacenCcs, String cpfCnpj, Customer customer) {
        if (!bacenCcs) {
            if (!shouldExistsBacenCcsRecord(customer)) return null

            AsaasLogger.error("BacenCcsService.saveEndOfRelationship >> Cliente encerrou o relacionamento, mas o registro do Bacen CCS não foi encontrado: customerId: [${customer.id}, cpfCnpj: [${cpfCnpj}]]")
            return null
        }

        bacenCcs.relationshipEndDate = new Date()
        if (!bacenCcs.syncStatus.isAwaitingInclusion()) bacenCcs.syncStatus = BacenCcsSyncStatus.AWAITING_UPDATE
        return bacenCcs.save(failOnError: true)
    }

    private Boolean shouldExistsBacenCcsRecord(Customer customer) {
        if (!customer.hadGeneralApproval()) return false

        return AuditLogEvent.query([exists: true, persistedObjectId: customer.id.toString(), className: Customer.simpleName, propertyName: "status", newValue: CustomerStatus.ACTIVE.toString()]).get().asBoolean()
    }
}

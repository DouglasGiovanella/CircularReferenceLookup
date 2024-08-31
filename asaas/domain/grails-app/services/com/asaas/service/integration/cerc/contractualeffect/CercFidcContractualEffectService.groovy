package com.asaas.service.integration.cerc.contractualeffect

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.integration.cerc.CercBankAccount
import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffect
import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffectGuarantee
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationItem
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.domain.webhook.WebhookRequest
import com.asaas.integration.cerc.adapter.contract.CercContractAdapter
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectGuaranteeStatus
import com.asaas.integration.cerc.enums.webhook.CercContractErrorReason
import com.asaas.integration.cerc.enums.webhook.CercEffectType
import com.asaas.integration.cerc.parser.CercParser
import com.asaas.log.AsaasLogger
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercFidcContractualEffectService {

    def asyncActionService
    def cercFidcContractualEffectGuaranteeService
    def grailsApplication

    public void processAsyncResponse(CercContractAdapter contractAdapter, WebhookRequest webhookRequest) {
        CercFidcContractualEffect contractualEffect = CercFidcContractualEffect.query([externalIdentifier: contractAdapter.externalIdentifier]).get()
        if (!contractualEffect) throw new RuntimeException("CercFidcContractualEffect não encontrado para o identificador ${contractAdapter.externalIdentifier}")

        if (contractAdapter.errorList) {
            if (CercParser.shouldIgnoreErrorResponse([isError: true, errorList: contractAdapter.errorList])) return

            List<CercContractErrorReason> errorReasonList = []
            for (Map error : contractAdapter.errorList) {
                CercContractErrorReason reason = CercContractErrorReason.findByCode(error.codigo.toString())
                errorReasonList.add(reason)
            }

            if (errorReasonList.contains(CercContractErrorReason.CONTRACT_ALREADY_INFORMED) && errorReasonList.contains(CercContractErrorReason.CONTRACT_WITH_DUPLICITY)) return

            if (errorReasonList.contains(CercContractErrorReason.COMPANY_NOT_REGISTERED)) {
                asyncActionService.saveCreateOrUpdateCercCompany(contractualEffect.customerCpfCnpj)
                setAsAwaitingCompanyActivate(contractualEffect)
                return
            }

            if (errorReasonList.any { it in [CercContractErrorReason.CONTRACT_NOT_FOUND, CercContractErrorReason.CONTRACT_WITH_DUPLICITY, CercContractErrorReason.CONTRACT_EFFECT_TYPE_CANNOT_BE_UPDATED] }) {
                recreate(contractualEffect)
                return
            }

            String contractualEffectLimitReachedErrorMessage = contractAdapter.errorList.find { it.codigo == CercContractErrorReason.CONTRACTUAL_EFFECT_LIMIT_REACHED.getCode() }?.mensagem
            if (contractualEffectLimitReachedErrorMessage) {
                Map guaranteeInfo = getPaymentArrangementAndEstimatedCreditDateFromErrorMessage(contractualEffectLimitReachedErrorMessage)
                Long fidcContractualEffectId = CercFidcContractualEffect.active([column: "id", externalIdentifier: contractAdapter.externalIdentifier]).get()

                cancelAwaitingSettlementGuarantees(fidcContractualEffectId, guaranteeInfo)
                AsaasLogger.warn("CercFidcContractualEffectService.processAsyncResponse --> Contrato ${fidcContractualEffectId} atingiu o limite de efeitos aplicados, informações: ${guaranteeInfo}.")
                return
            }

            String errorMessage = CercParser.parseErrorList(contractAdapter.errorList)
            AsaasLogger.error("CercFidcContractualEffectService.processAsyncResponse >> Foram encontrados erros no contrato [id: ${contractualEffect.id}, webhook: ${webhookRequest.id}, errorList: [${errorMessage}]]")
            setAsError(contractualEffect)
        }
    }

    public void saveIfNecessary(ReceivableAnticipation anticipation) {
        String customerCpfCnpj = anticipation.customer.cpfCnpj
        Date estimatedCreditDate = anticipation.getLastEstimatedCreditDate()

        CercFidcContractualEffect contractualEffect = CercFidcContractualEffect.active([
            customerCpfCnpj: customerCpfCnpj,
            effectType: CercEffectType.FIDUCIARY_ASSIGNMENT
        ]).get()

        if (contractualEffect) {
            extendDueDateIfNecessary(contractualEffect, estimatedCreditDate)
        } else {
            contractualEffect = new CercFidcContractualEffect()
            contractualEffect.partner = anticipation.getPartner()
            contractualEffect.customerCpfCnpj = customerCpfCnpj
            contractualEffect.signatureDate = anticipation.dateCreated
            contractualEffect.dueDate = estimatedCreditDate
            contractualEffect.bankAccount = CercBankAccount.findAsaasBankAccount()
            contractualEffect.save(failOnError: true, flush: true)

            contractualEffect.externalIdentifier = getExternalIdentifier(contractualEffect.id)
            contractualEffect.save(failOnError: true, flush: true)
        }

        setAsAwaitingCompanyActivateIfNecessary(contractualEffect)

        saveGuarantees(contractualEffect, anticipation)

        consolidate(contractualEffect)
    }

    public void cancelGuarantees(Long anticipationId) {
        List<CercFidcContractualEffectGuarantee> guaranteeList = CercFidcContractualEffectGuarantee.query([anticipationId: anticipationId]).list()
        if (!guaranteeList) return

        for (CercFidcContractualEffectGuarantee guarantee in guaranteeList) {
            cercFidcContractualEffectGuaranteeService.setAsCancelled(guarantee)
        }

        consolidate(guaranteeList.first().contractualEffect)
    }

    public void processPendingCancelFidcContractualEffectGuaranteeWithPaymentRefunded() {
        final Integer maxAsyncActionsPerExecution = 100

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.CANCEL_FIDC_CONTRACTUAL_EFFECT_GUARANTEE_WITH_PAYMENT_REFUNDED, maxAsyncActionsPerExecution)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Boolean hasCancelledAllGuarantees = cancelAwaitingSettlementGuaranteesWithPaymentRefunded(asyncActionData.contractualEffectId)
                if (!hasCancelledAllGuarantees) return

                consolidate(CercFidcContractualEffect.get(asyncActionData.contractualEffectId))
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "CercFidcContractualEffectService.processPendingCancelFidcContractualEffectGuaranteeWithPaymentRefunded -> Falha ao processar a ação assincrona [asyncActionId: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }
            ])
        }
    }

    public void settleGuarantee(Long anticipationId, Long anticipationItemId) {
        Map search = [:]
        search.anticipationId = anticipationId
        if (anticipationItemId) search.anticipationItemId = anticipationItemId

        CercFidcContractualEffectGuarantee guarantee = CercFidcContractualEffectGuarantee.query(search).get()
        if (!guarantee) return

        cercFidcContractualEffectGuaranteeService.setAsSettled(guarantee)

        consolidate(guarantee.contractualEffect)
    }

    public void setAsErrorWithNewTransaction(Long id) {
        Utils.withNewTransactionAndRollbackOnError({
            CercFidcContractualEffect contractualEffect = CercFidcContractualEffect.get(id)
            if (!contractualEffect) throw new RuntimeException("Efeito de contrato não encontrado")

            setAsError(contractualEffect)
        }, [logErrorMessage: "CercFidcContractualEffectService.setAsErrorWithNewTransaction >> Falha ao marcar o efeito de contrato do FIDC [${id}] com o status de erro"])
    }

    public void setAsError(CercFidcContractualEffect contractualEffect) {
        contractualEffect.syncStatus = CercSyncStatus.ERROR
        contractualEffect.save(failOnError: true)
    }

    public void setAsSynced(CercFidcContractualEffect contractualEffect) {
        if (contractualEffect.operationType.isCreate()) contractualEffect.operationType = CercOperationType.UPDATE
        contractualEffect.syncStatus = CercSyncStatus.SYNCED
        contractualEffect.lastSyncAttempt = null
        contractualEffect.syncAttempts = 0
        contractualEffect.save(failOnError: true)
    }

    public String getExternalIdentifier(Long id) {
        return "${grailsApplication.config.asaas.cnpj.substring(1)}-${id}"
    }

    public void setAsAwaitingSync(CercFidcContractualEffect contractualEffect) {
        contractualEffect.syncStatus = CercSyncStatus.AWAITING_SYNC
        contractualEffect.save(failOnError: true)
    }

    public void setAsAwaitingSyncIfPossible(CercFidcContractualEffect contractualEffect) {
        if (contractualEffect.syncStatus.isAwaitingCompanyActivate()) return

        setAsAwaitingSync(contractualEffect)
    }

    public void registerLastSync(CercFidcContractualEffect contractualEffect) {
        contractualEffect.lastSyncAttempt = new Date()
        contractualEffect.syncAttempts++
        contractualEffect.save(failOnError: true)
    }

    public void setAsAwaitingCompanyActivate(CercFidcContractualEffect contractualEffect) {
        contractualEffect.syncStatus = CercSyncStatus.AWAITING_COMPANY_ACTIVATE
        contractualEffect.save(failOnError: true)
    }

    public void refreshGuaranteeIfPossible(ReceivableUnitItem paymentReceivableUnitItem) {
        CercFidcContractualEffectGuarantee guarantee = cercFidcContractualEffectGuaranteeService.refreshIfPossible(paymentReceivableUnitItem)
        if (!guarantee) return

        consolidate(guarantee.contractualEffect)
    }

    public void finishGuarantees() {
        processPendingCancelGuarantees()
        processPendingFinishGuarantees()
    }

    private Boolean cancelAwaitingSettlementGuaranteesWithPaymentRefunded(Long contractualEffectId) {
        Map search = [:]
        search.column = "id"
        search.contractualEffectId = contractualEffectId
        search.status = CercContractualEffectGuaranteeStatus.AWAITING_SETTLEMENT
        search.disableSort = true

        final Integer maxGuaranteesPerList = 600
        List<Long> guaranteesIdList = CercFidcContractualEffectGuarantee.query([anticipationPaymentStatusRefunded: true] + search).list(max: maxGuaranteesPerList)
        if (!guaranteesIdList || guaranteesIdList.size() < maxGuaranteesPerList) {
            guaranteesIdList.addAll(CercFidcContractualEffectGuarantee.query([anticipationItemPaymentStatusRefunded: true] + search).list(max: (maxGuaranteesPerList - guaranteesIdList.size())))
        }

        final Integer flushEvery = 200
        final Integer batchSize = 200
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(guaranteesIdList, batchSize, flushEvery, { Long guaranteeId ->
            cercFidcContractualEffectGuaranteeService.setAsCancelled(CercFidcContractualEffectGuarantee.get(guaranteeId))
        }, [logErrorMessage: "CercFidcContractualEffectService.cancelAwaitingSettlementGuaranteesWithPaymentRefunded -> Erro ao tentar cancelar as garantias com pagamento estornado", appendBatchToLogErrorMessage: true])

        return guaranteesIdList.size() < maxGuaranteesPerList
    }

    private void consolidate(CercFidcContractualEffect contractualEffect) {
        recalculate(contractualEffect)
        setOperationType(contractualEffect)

        setAsAwaitingSyncIfPossible(contractualEffect)
    }

    private void saveGuarantees(CercFidcContractualEffect contractualEffect, ReceivableAnticipation anticipation) {
        if (anticipation.payment) {
            cercFidcContractualEffectGuaranteeService.save(contractualEffect, anticipation, null)
        } else if (anticipation.installment) {
            for (ReceivableAnticipationItem anticipationItem : anticipation.items) {
                cercFidcContractualEffectGuaranteeService.save(contractualEffect, anticipation, anticipationItem)
            }
        }
    }

    private CercFidcContractualEffect extendDueDateIfNecessary(CercFidcContractualEffect contractualEffect, Date estimatedCreditDate) {
        if (contractualEffect.dueDate > estimatedCreditDate) return contractualEffect

        contractualEffect.dueDate = estimatedCreditDate
        return contractualEffect.save(failOnError: true, flush: true)
    }

    private void recalculate(CercFidcContractualEffect contractualEffect) {
        Map search = [
            contractualEffectId: contractualEffect.id,
            status: CercContractualEffectGuaranteeStatus.AWAITING_SETTLEMENT
        ]
        contractualEffect.value = Utils.toBigDecimal(CercFidcContractualEffectGuarantee.sumValue(search).get())
        contractualEffect.netValue = Utils.toBigDecimal(CercFidcContractualEffectGuarantee.sumAnticipatedValue(search).get())
        if (contractualEffect.isDirty()) contractualEffect.save(failOnError: true)
    }

    private void setOperationType(CercFidcContractualEffect contractualEffect) {
        Boolean existsNotDebitedItems = existsNotDebitedItems(contractualEffect)

        if (contractualEffect.operationType.isCreate()) {
            if (!existsNotDebitedItems) delete(contractualEffect)
            return
        }

        contractualEffect.operationType = existsNotDebitedItems ? CercOperationType.UPDATE : CercOperationType.FINISH
        if (contractualEffect.isDirty()) contractualEffect.save(failOnError: true)
    }

    private void delete(CercFidcContractualEffect contractualEffect) {
        contractualEffect.deleted = true
        contractualEffect.save(failOnError: true)
    }

    private void recreate(CercFidcContractualEffect contractualEffect) {
        if (!existsNotDebitedItems(contractualEffect)) {
            contractualEffect.syncStatus = CercSyncStatus.NOT_SYNCABLE
            contractualEffect.save(failOnError: true)
            return
        }

        setAsAwaitingSyncIfPossible(contractualEffect)

        contractualEffect.syncAttempts = 0
        contractualEffect.operationType = CercOperationType.CREATE
        contractualEffect.externalIdentifier = getExternalIdentifier(contractualEffect.id)
        contractualEffect.save(failOnError: true)
    }

    private Boolean existsNotDebitedItems(CercFidcContractualEffect contractualEffect) {
        return CercFidcContractualEffectGuarantee.query([
            exists: true,
            contractualEffectId: contractualEffect.id,
            status: CercContractualEffectGuaranteeStatus.AWAITING_SETTLEMENT
        ]).get().asBoolean()
    }

    private void cancelAwaitingSettlementGuarantees(Long fidcContractualEffectId, Map options) {
        Map search = [:]
        search.contractualEffectId = fidcContractualEffectId
        search.status = CercContractualEffectGuaranteeStatus.AWAITING_SETTLEMENT

        List<CercFidcContractualEffectGuarantee> guaranteeList = CercFidcContractualEffectGuarantee.query(options + search).list()
        if (!guaranteeList) return

        for (CercFidcContractualEffectGuarantee guarantee : guaranteeList) {
            cercFidcContractualEffectGuaranteeService.setAsCancelled(guarantee)
        }

        consolidate(guaranteeList.first().contractualEffect)
    }

    private Map getPaymentArrangementAndEstimatedCreditDateFromErrorMessage(String errorMessage) {
        final Integer paymentArrangementLength = 3
        Integer startIndex = errorMessage.lastIndexOf('_') - paymentArrangementLength
        Integer endIndex = errorMessage.lastIndexOf('"]')
        String filteredMessage = errorMessage.substring(startIndex, endIndex)

        String paymentArrangementString = filteredMessage.substring(0, paymentArrangementLength)
        String estimatedCreditDateString = filteredMessage.substring(4)

        return [
                paymentArrangement: PaymentArrangement.valueOf(paymentArrangementString),
                estimatedCreditDate: CustomDateUtils.fromString(estimatedCreditDateString, CustomDateUtils.DATABASE_DATE_FORMAT).clearTime()
        ]
    }

    private void setAsAwaitingCompanyActivateIfNecessary(CercFidcContractualEffect contractualEffect) {
        if (contractualEffect.syncStatus.isAwaitingCompanyActivate()) return

        Boolean companyActivated = CercCompany.activated([exists: true, cpfCnpj: contractualEffect.customerCpfCnpj]).get().asBoolean()
        if (companyActivated) return

        setAsAwaitingCompanyActivate(contractualEffect)
    }

    private void processPendingCancelGuarantees() {
        final Integer maxItemsPerCycle = 100
        for (Map asyncActionData : asyncActionService.listPendingCancelFidcContractualEffectGuarantee(maxItemsPerCycle)) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                cancelGuarantees(asyncActionData.anticipationId)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "CercFidcContractualEffectService.processPendingCancelGuarantees >> Falha ao cancelar garantia de contrato do FIDC da ação assíncrona [${asyncActionData.asyncActionId}]",
                onError: { hasError = true }])

            if (hasError) asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
        }
    }

    private void processPendingFinishGuarantees() {
        final Integer maxItemsPerCycle = 100
        for (Map asyncActionData : asyncActionService.listPendingFinishFidcContractualEffectGuarantee(maxItemsPerCycle)) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                settleGuarantee(asyncActionData.anticipationId, asyncActionData.anticipationItemId)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "CercFidcContractualEffectService.processPendingFinishGuarantees >> Falha ao liquidar garantia de contrato do FIDC da ação assíncrona [${asyncActionData.asyncActionId}]",
                onError: { hasError = true }])

            if (hasError) asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
        }
    }
}

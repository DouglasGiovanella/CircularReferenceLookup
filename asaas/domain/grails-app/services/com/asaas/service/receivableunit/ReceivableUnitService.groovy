package com.asaas.service.receivableunit

import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.integration.cerc.enums.CercProcessingStatus
import com.asaas.integration.cerc.enums.webhook.CercEffectType
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableregistration.receivableunit.ReceivableUnitStatus
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ReceivableUnitService {

    def asyncActionService
    def cercContractualEffectService
    def grailsApplication
    def receivableRegistrationEventQueueService

    public ReceivableUnit saveIfNecessary(ReceivableUnitItem item) {
        Map search = [:]
        search.customerCpfCnpj = item.customerCpfCnpj
        search.estimatedCreditDate = item.estimatedCreditDate
        search.paymentArrangement = item.paymentArrangement
        search.holderCpfCnpj = item.holderCpfCnpj

        ReceivableUnit receivableUnit = ReceivableUnit.active(search).get()
        if (receivableUnit) return receivableUnit

        return save(item)
    }

    public void consolidate(ReceivableUnit receivableUnit) {
        recalculate(receivableUnit)

        if (!hasPaymentItemToConstitute(receivableUnit)) {
            inactivate(receivableUnit)
        } else if (receivableUnit.operationType.isInactivate()) {
            setAsCreate(receivableUnit)
        } else {
            finishIfPossible(receivableUnit)
        }

        sendToCalculateSettlementsIfPossible(receivableUnit)
    }

    public void inactivate(ReceivableUnit receivableUnit) {
        receivableUnit.operationType = CercOperationType.INACTIVATE
        receivableUnit.status = ReceivableUnitStatus.INACTIVE
        receivableUnit.save(failOnError: true)
    }

    public void updateStatusAsProcessed(ReceivableUnit receivableUnit) {
        receivableUnit.status = ReceivableUnitStatus.PROCESSED
        receivableUnit.save(failOnError: true)
    }

    public void updateOperationTypeAsUpdate(ReceivableUnit receivableUnit) {
        receivableUnit.operationType = CercOperationType.UPDATE
        receivableUnit.save(failOnError: true)
    }

    public void recalculate(ReceivableUnit receivableUnit) {
        Map search = [:]
        search.receivableUnitId = receivableUnit.id
        search.excludeAnticipatedReceivableUnit = true
        search."status[in]" = ReceivableUnitItemStatus.listNotSettledStatuses()
        if (!wasCreatedByHolderChangeContract(receivableUnit)) search."payment[isNotNull]" = true

        BigDecimal newValue = ReceivableUnitItem.sumValue(search).get()
        BigDecimal newNetValue = ReceivableUnitItem.sumNetValue(search).get()

        if (!wasCreatedByHolderChangeContract(receivableUnit)) {
            Map searchContract = [:]
            searchContract.affectedReceivableUnitId = receivableUnit.id
            searchContract."effectType[in]" = CercEffectType.listHolderChangeType()
            searchContract."status[in]" = CercProcessingStatus.listActiveStatuses()
            BigDecimal valueCompromisedInHolderChangeContracts = CercContractualEffect.sumValue(searchContract).get()

            newNetValue = BigDecimalUtils.max(newNetValue - valueCompromisedInHolderChangeContracts, 0.0)
        }

        if (receivableUnit.value != newValue || receivableUnit.netValue != newNetValue) {
            receivableUnit.value = newValue
            receivableUnit.netValue = newNetValue
            receivableUnit.save(failOnError: true, flush: true)

            cercContractualEffectService.setAllContractsAsPending(receivableUnit.id)
        }

        if (receivableUnit.isDirty()) receivableUnit.save(failOnError: true, flush: true)
    }

    public void updateStatusAsError(Long receivableUnitId) {
        ReceivableUnit receivableUnit = ReceivableUnit.get(receivableUnitId)
        receivableUnit.status = ReceivableUnitStatus.ERROR
        receivableUnit.save(failOnError: true)
    }

    public void updateStatusAsAwaitingCompanyActivate(ReceivableUnit receivableUnit) {
        receivableUnit.status = ReceivableUnitStatus.AWAITING_COMPANY_ACTIVATE
        receivableUnit.save(failOnError: true, flush: true)
    }

    public void settleIfPossible(ReceivableUnit receivableUnit) {
        if (hasNotSettledPaymentItems(receivableUnit.id)) return
        receivableUnit.status = ReceivableUnitStatus.SETTLED
        receivableUnit.save(failOnError: true, flush: true)
    }

    private void setAsCreate(ReceivableUnit receivableUnit) {
        receivableUnit.operationType = CercOperationType.CREATE
        receivableUnit.status = ReceivableUnitStatus.AWAITING_PROCESSING
        receivableUnit.save(failOnError: true)
    }

    private void finishIfPossible(ReceivableUnit receivableUnit) {
        if (receivableUnit.operationType.isFinish() || receivableUnit.operationType.isCreate()) return
        if (hasNotSettledPaymentItems(receivableUnit.id)) return

        if (wasCreatedByHolderChangeContract(receivableUnit) && hasNotSettledItems(receivableUnit.id)) {
            ReceivableUnit originalReceivableUnit = CercContractualEffect.query([column: "affectedReceivableUnit", receivableUnitId: receivableUnit.id]).get()
            if (originalReceivableUnit && !originalReceivableUnit.operationType.isFinish()) return
        }

        receivableUnit.operationType = CercOperationType.FINISH
        receivableUnit.status = ReceivableUnitStatus.SETTLED
        receivableUnit.save(failOnError: true)

        finishRelatedUnitsIfPossible(receivableUnit.id)
    }

    private void finishRelatedUnitsIfPossible(Long receivableUnitId) {
        List<ReceivableUnit> changedHolderReceivableUnitList = CercContractualEffect.query([
            column: "receivableUnit",
            affectedReceivableUnitId: receivableUnitId,
            "effectType[in]": CercEffectType.listHolderChangeType(),
            status: CercProcessingStatus.PROCESSED
        ]).list()

        for (ReceivableUnit changedHolderReceivableUnit : changedHolderReceivableUnitList) {
            finishIfPossible(changedHolderReceivableUnit)
            sendToCalculateSettlementsIfPossible(changedHolderReceivableUnit)
        }
    }

    private void sendToCalculateSettlementsIfPossible(ReceivableUnit receivableUnit) {
        if (receivableUnit.status.isAwaitingCompanyActivate()) return

        Map eventInfo = [receivableUnitId: receivableUnit.id]
        receivableRegistrationEventQueueService.save(ReceivableRegistrationEventQueueType.CALCULATE_RECEIVABLE_UNIT_SETTLEMENTS, eventInfo, eventInfo.encodeAsMD5())
    }

    private ReceivableUnit save(ReceivableUnitItem item) {
        ReceivableUnit validatedDomain = validateSave(item)
        if (validatedDomain.hasErrors()) throw new ValidationException("Falha ao salvar unidade de recebíveis", validatedDomain.errors)

        ReceivableUnit receivableUnit = new ReceivableUnit()
        receivableUnit.customerCpfCnpj = Utils.removeNonNumeric(item.customerCpfCnpj)
        receivableUnit.holderCpfCnpj = Utils.removeNonNumeric(item.holderCpfCnpj)
        receivableUnit.estimatedCreditDate = item.estimatedCreditDate
        receivableUnit.paymentArrangement = item.paymentArrangement
        receivableUnit.status = ReceivableUnitStatus.AWAITING_PROCESSING
        receivableUnit.save(failOnError: true, flush: true)

        receivableUnit.externalIdentifier = "${grailsApplication.config.asaas.cnpj.substring(1)}-${receivableUnit.id}"
        receivableUnit.save(failOnError: true, flush: true)

        applyContractualEffectIfNecessary(receivableUnit)

        setAsAwaitingCompanyActivateIfNecessary(receivableUnit)

        return receivableUnit
    }

    private ReceivableUnit validateSave(ReceivableUnitItem item) {
        ReceivableUnit validatedDomain = new ReceivableUnit()

        Map search = [:]
        search.customerCpfCnpj = item.customerCpfCnpj
        search.estimatedCreditDate = item.estimatedCreditDate
        search.holderCpfCnpj = item.holderCpfCnpj
        search.paymentArrangement = item.paymentArrangement

        if (item.contractualEffect && CercEffectType.listHolderChangeType().contains(item.contractualEffect.effectType)) {
            Boolean hasActiveReceivableUnit = ReceivableUnit.active(search + [exists: true]).get().asBoolean()
            if (!hasActiveReceivableUnit) return validatedDomain
        }

        Map receivableUnitMap = ReceivableUnit.query(search + [columnList: ["id", "operationType"]]).get()
        if (receivableUnitMap) DomainUtils.addError(validatedDomain, "Unidade de recebível já cadastrada [ReceivableUnitId: ${receivableUnitMap.id}, operationType: ${receivableUnitMap.operationType}]")

        return validatedDomain
    }

    private void applyContractualEffectIfNecessary(ReceivableUnit receivableUnit) {
        Map search = [:]
        search.customerCpfCnpj = receivableUnit.customerCpfCnpj
        search.holderCpfCnpj = receivableUnit.holderCpfCnpj
        search.paymentArrangement = receivableUnit.paymentArrangement
        search.estimatedCreditDate = receivableUnit.estimatedCreditDate
        search."affectedReceivableUnitId[isNull]" = true
        search."status[in]" = CercProcessingStatus.listActiveStatuses()

        List<CercContractualEffect> contractualEffectList = CercContractualEffect.query(search).list()
        for (CercContractualEffect contractualEffect : contractualEffectList) {
            contractualEffect.affectedReceivableUnit = receivableUnit
            contractualEffect.save(failOnError: true, flush: true)
        }

        if (contractualEffectList) {
            Map eventData = [:]
            eventData.receivableUnitId = receivableUnit.id
            receivableRegistrationEventQueueService.saveIfHasNoEventPendingWithSameGroupId(ReceivableRegistrationEventQueueType.REFRESH_CONTRACTUAL_EFFECTS_COMPROMISED_VALUE_WITH_AFFECTED_RECEIVABLE_UNIT, eventData, eventData.encodeAsMD5())
        }
    }

    private void setAsAwaitingCompanyActivateIfNecessary(ReceivableUnit receivableUnit) {
        Boolean companyActivated = CercCompany.activated([exists: true, cpfCnpj: receivableUnit.customerCpfCnpj]).get().asBoolean()
        if (companyActivated) return

        updateStatusAsAwaitingCompanyActivate(receivableUnit)
    }

    private Boolean wasCreatedByHolderChangeContract(ReceivableUnit receivableUnit) {
        return receivableUnit.customerCpfCnpj != receivableUnit.holderCpfCnpj
    }

    private Boolean hasNotSettledPaymentItems(Long receivableUnitId) {
        Map search = [:]
        search.exists = true
        search."payment[isNotNull]" = true
        search.receivableUnitId = receivableUnitId

        return ReceivableUnitItem.notSettled(search).get().asBoolean()
    }

    private Boolean hasNotSettledItems(Long receivableUnitId) {
        Map search = [:]
        search.exists = true
        search.receivableUnitId = receivableUnitId

        return ReceivableUnitItem.notSettled(search).get().asBoolean()
    }

    private Boolean hasPaymentItemToConstitute(ReceivableUnit receivableUnit) {
        Long receivableUnitId = receivableUnit.id

        if (wasCreatedByHolderChangeContract(receivableUnit)) {
            Long originalReceivableUnitId = CercContractualEffect.query([column: "affectedReceivableUnit.id", receivableUnitId: receivableUnit.id, "compromisedValue[gt]": BigDecimal.ZERO]).get()
            if (originalReceivableUnitId) receivableUnitId = originalReceivableUnitId
        }

        Map search = [:]
        search.exists = true
        search."payment[isNotNull]" = true
        search.receivableUnitId = receivableUnitId
        search."anticipatedReceivableUnit[isNull]" = true
        search."status[in]" = [ReceivableUnitItemStatus.PENDING] + ReceivableUnitItemStatus.listConstituted()

        return ReceivableUnitItem.query(search).get().asBoolean()
    }
}

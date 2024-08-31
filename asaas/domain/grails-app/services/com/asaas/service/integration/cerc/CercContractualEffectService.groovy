package com.asaas.service.integration.cerc

import com.asaas.domain.customer.Customer
import com.asaas.domain.installment.Installment
import com.asaas.domain.integration.cerc.CercBankAccount
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.adapter.contractualeffect.ContractualEffectAdapter
import com.asaas.integration.cerc.enums.CercAccountType
import com.asaas.integration.cerc.enums.CercProcessingStatus
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercContractualEffectService {

    def cercContractService
    def paymentArrangementService
    def receivableRegistrationEventQueueService

    public void save(ContractualEffectAdapter contractualEffectAdapter) {
        CercContractualEffect contractualEffect = CercContractualEffect.query([
            customerCpfCnpj: contractualEffectAdapter.customerCpfCnpj,
            holderCpfCnpj: contractualEffectAdapter.holderCpfCnpj,
            paymentArrangement: contractualEffectAdapter.paymentArrangement,
            estimatedCreditDate: contractualEffectAdapter.estimatedCreditDate,
            protocol: contractualEffectAdapter.protocol
        ]).get()

        if (!contractualEffect) {
            if (contractualEffectAdapter.compromisedValue <= 0) return

            contractualEffect = new CercContractualEffect()
            contractualEffect.protocol = contractualEffectAdapter.protocol
        }

        contractualEffect.properties["effectType", "beneficiaryCpfCnpj", "compromisedValue", "divisionType", "externalIdentifier", "priority"] = contractualEffectAdapter
        contractualEffect.properties["customerCpfCnpj", "holderCpfCnpj", "paymentArrangement", "estimatedCreditDate"] = contractualEffectAdapter
        contractualEffect.dueDate = contractualEffect.estimatedCreditDate
        contractualEffect.affectedReceivableUnit = ReceivableUnit.findByCompositeKey(contractualEffect.customerCpfCnpj, contractualEffect.holderCpfCnpj, contractualEffect.paymentArrangement, contractualEffect.estimatedCreditDate).get()
        contractualEffect.contract = cercContractService.saveIfNecessary(contractualEffect.customerCpfCnpj, contractualEffect.externalIdentifier, contractualEffect.beneficiaryCpfCnpj)

        CercAccountType type = contractualEffectAdapter.financialInstitution.accountType
        String accountNumberValue = type?.isContaPagamento() ? Utils.removeNonNumeric(contractualEffectAdapter.financialInstitution.accountNumber) : contractualEffectAdapter.financialInstitution.accountNumber
        String compeValue = Utils.removeNonNumeric(contractualEffectAdapter.financialInstitution.compe)
        String agencyValue = contractualEffectAdapter.financialInstitution.agency ?: CercBankAccount.DEFAULT_AGENCY

        Map bankAccountSearch = [:]
        bankAccountSearch.cpfCnpj = contractualEffectAdapter.financialInstitution.accountHolderDocument
        bankAccountSearch.type = type
        bankAccountSearch.ispb = contractualEffectAdapter.financialInstitution.ispb
        bankAccountSearch.accountNumber = accountNumberValue
        bankAccountSearch.agency = agencyValue
        bankAccountSearch.compe = compeValue

        contractualEffect.bankAccount = CercBankAccount.query(bankAccountSearch).get()

        if (contractualEffect.id == null || contractualEffect.isDirty()) {
            contractualEffect.status = contractualEffect.bankAccount ? CercProcessingStatus.PENDING : CercProcessingStatus.AWAITING_BANK_ACCOUNT_CREATION
            contractualEffect.save(failOnError: true)

            if (contractualEffect.status.isPending() && contractualEffect.affectedReceivableUnit) saveProcessContractualEffectsWithAffectReceivableUnitEvent(contractualEffect.affectedReceivableUnitId)
        }

        if (!contractualEffect.bankAccount) {
            Map eventData = [:]
            eventData.contractualEffectId = contractualEffect.id
            eventData.bankAccountData = bankAccountSearch
            eventData.bankAccountData.holderName = contractualEffectAdapter.financialInstitution.holderName
            receivableRegistrationEventQueueService.saveIfHasNoEventPendingWithSameGroupId(ReceivableRegistrationEventQueueType.CREATE_CERC_BANK_ACCOUNT, eventData, eventData.encodeAsMD5())
        }
    }

    public void setBankAccount(Long contractualEffectId, CercBankAccount cercBankAccount) {
        CercContractualEffect cercContractualEffect = CercContractualEffect.get(contractualEffectId)
        cercContractualEffect.bankAccount = cercBankAccount
        setAsPending(cercContractualEffect)
        if (cercContractualEffect.affectedReceivableUnit) saveProcessContractualEffectsWithAffectReceivableUnitEvent(cercContractualEffect.affectedReceivableUnit.id)
    }

    public void setAsFinished(CercContractualEffect contractualEffect) {
        contractualEffect.status = CercProcessingStatus.FINISHED
        contractualEffect.value = BigDecimal.ZERO
        contractualEffect.save(failOnError: true)
    }

    public List<CercContractualEffect> listEffectsAffectedByReceivableUnit(Customer customer, String externalIdentifier, Integer max, Integer offset) {
        Map search = [:]
        search."affectedReceivableUnitId[isNotNull]" = true
        search."status[ne]" = CercProcessingStatus.AWAITING_BANK_ACCOUNT_CREATION
        search.sort = "estimatedCreditDate"
        search.order = "asc"

        return CercContractualEffect.byExternalIdentifier(customer, externalIdentifier, search).list(max: max, offset: offset)
    }

    public Boolean paymentWillBeAffectedByContractualEffect(Payment payment) {
        Map search = [:]
        search.exists = true
        search.customerCpfCnpj = payment.provider.cpfCnpj
        search.holderCpfCnpj = payment.provider.cpfCnpj
        search.estimatedCreditDate = payment.creditDate

        search.paymentArrangement = paymentArrangementService.getPaymentArrangement(payment, false)
        if (!search.paymentArrangement) return false

        return CercContractualEffect.ignoringFidc(search).get().asBoolean()
    }

    public List<CercContractualEffect> listActiveContractsForReceivableUnit(Long receivableUnitId) {
        Map search = [:]
        search."status[in]" = CercProcessingStatus.listActiveStatuses()
        search."affectedReceivableUnitId" = receivableUnitId

        return CercContractualEffect.prioritized(search).list()
    }

    public void setAllContractsAsError(Long receivableUnitId) {
        for (CercContractualEffect contractualEffect : listActiveContractsForReceivableUnit(receivableUnitId)) {
            contractualEffect.status = CercProcessingStatus.ERROR
            contractualEffect.save(failOnError: true)
        }
    }

    public void setAllContractsAsPending(Long receivableUnitId) {
        Boolean hasItemInSettlementProcess = ReceivableUnitItem.query([exists: true, "status[in]": ReceivableUnitItemStatus.listInSettlementProcess(), receivableUnitId: receivableUnitId, "anticipatedReceivableUnit[isNull]": true]).get().asBoolean()
        for (CercContractualEffect cercContractualEffectItem : listActiveContractsForReceivableUnit(receivableUnitId)) {
            if (!hasItemInSettlementProcess) setAsPending(cercContractualEffectItem)
        }

        saveProcessContractualEffectsWithAffectReceivableUnitEvent(receivableUnitId)
    }

    public void setAsProcessed(CercContractualEffect cercContractualEffect) {
        cercContractualEffect.status = CercProcessingStatus.PROCESSED
        cercContractualEffect.save(failOnError: true)
    }

    public void setAsPending(CercContractualEffect cercContractualEffect) {
        cercContractualEffect.status = CercProcessingStatus.PENDING
        cercContractualEffect.save(failOnError: true, flush: true)
    }

    public Boolean installmentHasAnyPaymentWithEffect(Installment installment) {
        return installment.payments.any { CercContractualEffect.getExternalActiveContractBeneficiaryCpfCnpj(it) }
    }

    private void saveProcessContractualEffectsWithAffectReceivableUnitEvent(Long receivableUnitId) {
        if (!receivableUnitId) return

        Map eventData = [receivableUnitId: receivableUnitId]
        receivableRegistrationEventQueueService.saveIfHasNoEventPendingWithSameGroupId(ReceivableRegistrationEventQueueType.PROCESS_CONTRACTUAL_EFFECTS_WITH_AFFECTED_RECEIVABLE_UNIT, eventData, eventData.encodeAsMD5())
    }
}

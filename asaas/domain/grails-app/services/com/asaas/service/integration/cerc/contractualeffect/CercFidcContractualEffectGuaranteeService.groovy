package com.asaas.service.integration.cerc.contractualeffect

import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffect
import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffectGuarantee
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationItem
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectGuaranteeStatus
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CercFidcContractualEffectGuaranteeService {

    public void save(CercFidcContractualEffect contractualEffect, ReceivableAnticipation anticipation, ReceivableAnticipationItem anticipationItem) {
        Payment payment = anticipation.payment ?: anticipationItem.payment
        ReceivableUnitItem receivableUnitItem = ReceivableUnitItem.query([paymentId: payment.id, "contractualEffect[isNull]": true]).get()
        if (!receivableUnitItem || receivableUnitItem.status.isInSettlementProcess()) return

        CercFidcContractualEffectGuarantee validatedDomain = validateSave(contractualEffect, anticipation, anticipationItem, receivableUnitItem.paymentArrangement)
        if (validatedDomain.hasErrors()) throw new ValidationException("Falha ao validar garantia de contrato do FIDC para a antecipação [${anticipation.id}], cobrança [${payment.id}]", validatedDomain.errors)

        CercFidcContractualEffectGuarantee guarantee = new CercFidcContractualEffectGuarantee()
        guarantee.contractualEffect = contractualEffect
        guarantee.anticipation = anticipation
        guarantee.anticipationItem = anticipationItem
        guarantee.paymentArrangement = receivableUnitItem.paymentArrangement
        guarantee.estimatedCreditDate = receivableUnitItem.estimatedCreditDate
        guarantee.value = receivableUnitItem.value
        guarantee.anticipatedValue = receivableUnitItem.netValue
        guarantee.save(failOnError: true, flush: true)
    }

    public void setAsCancelled(CercFidcContractualEffectGuarantee guarantee) {
        guarantee.status = CercContractualEffectGuaranteeStatus.CANCELLED
        guarantee.save(failOnError: true, flush: true)
    }

    public void setAsSettled(CercFidcContractualEffectGuarantee guarantee) {
        guarantee.status = CercContractualEffectGuaranteeStatus.SETTLED
        guarantee.save(failOnError: true, flush: true)
    }

    public CercFidcContractualEffectGuarantee refreshIfPossible(ReceivableUnitItem paymentReceivableUnitItem) {
        if (paymentReceivableUnitItem.anticipatedReceivableUnit) return null

        CercFidcContractualEffectGuarantee guarantee = CercFidcContractualEffectGuarantee.findFromPayment(paymentReceivableUnitItem.payment)
        if (!guarantee || !guarantee.status.isAwaitingSettlement()) return null

        return refresh(guarantee, paymentReceivableUnitItem)
    }

    private CercFidcContractualEffectGuarantee refresh(CercFidcContractualEffectGuarantee guarantee, ReceivableUnitItem paymentReceivableUnitItem) {
        if (paymentReceivableUnitItem.deleted) {
            setAsCancelled(guarantee)
            return guarantee
        }

        guarantee.estimatedCreditDate = paymentReceivableUnitItem.estimatedCreditDate
        guarantee.value = paymentReceivableUnitItem.value
        guarantee.anticipatedValue = paymentReceivableUnitItem.netValue
        if (guarantee.isDirty()) guarantee.save(failOnError: true)

        return guarantee
    }

    private CercFidcContractualEffectGuarantee validateSave(CercFidcContractualEffect contractualEffect, ReceivableAnticipation anticipation, ReceivableAnticipationItem anticipationItem, PaymentArrangement paymentArrangement) {
        CercFidcContractualEffectGuarantee validatedDomain = new CercFidcContractualEffectGuarantee()

        if (!paymentArrangement) DomainUtils.addError(validatedDomain, "O arranjo de pagamento é obrigatório")

        Boolean isContractualEffectNotActive = CercOperationType.listNotActiveTypes().contains(contractualEffect.operationType)
        if (isContractualEffectNotActive) DomainUtils.addError(validatedDomain, "O contrato [${contractualEffect.id}] não está ativo. Será necessário criar um novo contrato")

        List<ReceivableAnticipationStatus> canCreateGuaranteeStatusList = [ReceivableAnticipationStatus.APPROVED, ReceivableAnticipationStatus.AWAITING_PARTNER_CREDIT, ReceivableAnticipationStatus.CREDITED]
        if (!canCreateGuaranteeStatusList.contains(anticipation.status)) DomainUtils.addError(validatedDomain, "Status [${anticipation.status.toString()}] da antecipação [${anticipation.id}] não permite a criação de garantias")

        Map defaultSearch = [
            exists: true,
            "status[in]": [CercContractualEffectGuaranteeStatus.AWAITING_SETTLEMENT, CercContractualEffectGuaranteeStatus.SETTLED]
        ]

        if (anticipation.installment && anticipationItem) {
            Boolean isAnticipationItemRegisteredAlready = CercFidcContractualEffectGuarantee.query([anticipationItemId: anticipationItem.id] + defaultSearch).get().asBoolean()
            if (isAnticipationItemRegisteredAlready) DomainUtils.addError(validatedDomain, "O item de antecipação [${anticipationItem.id}] já está salvo como garantia")
        } else {
            Boolean isAnticipationRegisteredAlready = CercFidcContractualEffectGuarantee.query([anticipationId: anticipation.id] + defaultSearch).get().asBoolean()
            if (isAnticipationRegisteredAlready) DomainUtils.addError(validatedDomain, "A antecipação [${anticipation.id}] já está salva como garantia")
        }

        return validatedDomain
    }
}

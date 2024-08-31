package com.asaas.service.netpromoterscore

import com.asaas.domain.netpromoterscore.NetPromoterScoreReason
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class NetPromoterScoreReasonService {

    public NetPromoterScoreReason save(String description) {
        NetPromoterScoreReason validateParams = validateSave(description)
        if (validateParams.hasErrors()) throw new ValidationException("Erro ao salvar um motivo para o NPS", validateParams.errors)

        NetPromoterScoreReason netPromoterScoreReason = new NetPromoterScoreReason()
        netPromoterScoreReason.description = description
        netPromoterScoreReason.save(failOnError: true)

        return netPromoterScoreReason
    }

    public void update(Long id, String description, Boolean deleted) {
        NetPromoterScoreReason netPromoterScoreReason = NetPromoterScoreReason.get(id)

        NetPromoterScoreReason validateParams = validateUpdate(description, netPromoterScoreReason)
        if (validateParams.hasErrors()) throw new ValidationException("Erro ao alterar o motivo do NPS", validateParams.errors)

        netPromoterScoreReason.description = description
        netPromoterScoreReason.deleted = deleted
        netPromoterScoreReason.save(failOnError: true)
    }

    private NetPromoterScoreReason validateUpdate(String description, NetPromoterScoreReason netPromoterScoreReasonToUpdate) {
        NetPromoterScoreReason validatedDomain = new NetPromoterScoreReason()

        if (!netPromoterScoreReasonToUpdate) {
            return DomainUtils.addError(validatedDomain, "Motivo não encontrado")
        }

        if (!description) {
            return DomainUtils.addError(validatedDomain, "É necessário informar uma descrição para o motivo")
        }

        if (netPromoterScoreReasonToUpdate.description != description && NetPromoterScoreReason.query([exists: true, includeDeleted: true, description: description]).get().asBoolean()) {
            DomainUtils.addError(validatedDomain, "Já existe um motivo com essa descrição")
        }

        return validatedDomain
    }

    private NetPromoterScoreReason validateSave(String description) {
        NetPromoterScoreReason validatedDomain = new NetPromoterScoreReason()
        if (!description) {
            return DomainUtils.addError(validatedDomain, "É necessário informar uma descrição para o motivo")
        }

        if (NetPromoterScoreReason.query([exists: true, includeDeleted: true, description: description]).get().asBoolean()) {
            DomainUtils.addError(validatedDomain, "Já existe um motivo com essa descrição")
        }

        return validatedDomain
    }
}

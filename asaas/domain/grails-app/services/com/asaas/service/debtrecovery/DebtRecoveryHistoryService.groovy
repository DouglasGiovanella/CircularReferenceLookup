package com.asaas.service.debtrecovery

import com.asaas.debtrecovery.DebtRecoveryHistoryType
import com.asaas.domain.debtrecovery.DebtRecovery
import com.asaas.domain.debtrecovery.DebtRecoveryHistory
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class DebtRecoveryHistoryService {

    public DebtRecoveryHistory saveAnalystManualInteraction(DebtRecovery debtRecovery, DebtRecoveryHistoryType type, String description) {
        return save(debtRecovery, type, description)
    }

    public DebtRecoveryHistory saveForDebtRecoveryChanged(DebtRecovery debtRecovery, String description) {
        return save(debtRecovery, DebtRecoveryHistoryType.OTHER, description)
    }

    public void saveForRestartDebtRecovery(DebtRecovery canceledDebtRecovery, DebtRecovery debtRecovery) {
        save(canceledDebtRecovery, DebtRecoveryHistoryType.OTHER, "Recuperação cancelada devido ao reinício da recuperação, gerando assim a recuperação ID ${debtRecovery.id}")
        save(debtRecovery, DebtRecoveryHistoryType.OTHER, "Recuperação originada do reinício da recuperação ID ${canceledDebtRecovery.id}")
    }

    private DebtRecoveryHistory save(DebtRecovery debtRecovery, DebtRecoveryHistoryType type, String description) {
        DebtRecoveryHistory validatedHistory = validateSave(type, description)
        if (validatedHistory.hasErrors()) throw new ValidationException("Erro ao salvar o histórico da recuperação ID ${debtRecovery.id}" , validatedHistory.errors)

        DebtRecoveryHistory history = new DebtRecoveryHistory()
        history.debtRecovery = debtRecovery
        history.analyst = UserUtils.getCurrentUser()
        history.type = type
        history.description = description

        return history.save(failOnError: true)
    }

    private DebtRecoveryHistory validateSave(DebtRecoveryHistoryType type, String description) {
        DebtRecoveryHistory history = new DebtRecoveryHistory()

        if (!type) DomainUtils.addError(history, "É necessário informar um tipo para o histórico")

        if (!description) DomainUtils.addError(history, "É necessário preencher a descrição do histórico")

        if (description.length() > DebtRecoveryHistory.constraints.description.maxSize) {
            DomainUtils.addError(history, Utils.getMessageProperty("description.maxSize.exceeded", [DebtRecoveryHistory.constraints.description.maxSize]))
        }

        return history
    }
}

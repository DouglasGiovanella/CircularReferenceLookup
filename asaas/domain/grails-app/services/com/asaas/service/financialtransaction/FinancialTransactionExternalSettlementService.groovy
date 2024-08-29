package com.asaas.service.financialtransaction

import com.asaas.domain.externalsettlement.ExternalSettlement
import com.asaas.domain.financialtansactionexternalsettlement.FinancialTransactionExternalSettlement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.exception.BusinessException
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FinancialTransactionExternalSettlementService {

    def financialTransactionService

    public void saveCredit(ExternalSettlement externalSettlement) {
        String description = "Saldo para liquidação de valores reservados por efeitos de contrato"
        FinancialTransaction financialTransaction = financialTransactionService.saveExternalSettlementCredit(externalSettlement, description)
        save(externalSettlement, financialTransaction)
    }

    public void reverseCredit(ExternalSettlement externalSettlement) {
        FinancialTransaction transactionToBeReversed = FinancialTransactionExternalSettlement.query([column: "financialTransaction",
                                                                                                     externalSettlementId: externalSettlement.id,
                                                                                                     financialTransactionType: externalSettlement.origin.convertToFinancialTransactionType()]).get()
        if (!transactionToBeReversed) throw new BusinessException("Não há uma transação correspondente a liqudação externa [${externalSettlement.id}]")

        String description = "Estorno do saldo para liquidação de valores reservados por efeitos de contrato"
        FinancialTransaction reversedTransaction = financialTransactionService.saveExternalSettlementCreditReversal(transactionToBeReversed, externalSettlement, description)
        save(externalSettlement, reversedTransaction)
    }

    private void save(ExternalSettlement externalSettlement, FinancialTransaction financialTransaction) {
        FinancialTransactionExternalSettlement validatedDomain = validateSave(externalSettlement, financialTransaction.transactionType)
        if (validatedDomain.hasErrors()) throw new ValidationException("Erro ao salvar transação de liquidação externa", validatedDomain.errors)

        FinancialTransactionExternalSettlement financialTransactionExternalSettlement = new FinancialTransactionExternalSettlement()
        financialTransactionExternalSettlement.externalSettlement = externalSettlement
        financialTransactionExternalSettlement.financialTransaction = financialTransaction
        financialTransactionExternalSettlement.save(failOnError: true)
    }

    private FinancialTransactionExternalSettlement validateSave(ExternalSettlement externalSettlement, FinancialTransactionType type) {
        FinancialTransactionExternalSettlement validatedDomain = new FinancialTransactionExternalSettlement()

        Boolean hasTransactionWithSameType = FinancialTransactionExternalSettlement.query([exists: true,
                                                                                           externalSettlementId: externalSettlement.id,
                                                                                           financialTransactionType: type]).get().asBoolean()
        if (hasTransactionWithSameType) DomainUtils.addError(validatedDomain, "A liquidação externa [${externalSettlement.id}] já possui uma transação do tipo [${type}]")

        return validatedDomain
    }
}

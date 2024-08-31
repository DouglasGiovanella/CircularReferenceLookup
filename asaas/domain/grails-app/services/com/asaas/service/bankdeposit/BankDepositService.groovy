package com.asaas.service.bankdeposit

import com.asaas.bankdeposit.BankDepositVO
import com.asaas.bankdepositstatus.BankDepositStatus
import com.asaas.depositreceiptbuilder.DepositReceiptBuilder
import com.asaas.domain.bank.Bank
import com.asaas.domain.bankdeposit.BankDeposit
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.exception.BusinessException
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class BankDepositService {

    def financialStatementService
    def financialStatementItemService

    public BankDeposit save(Map params) {
        Map parsedParams = parseParams(params)

        BankDeposit bankDeposit = new BankDeposit()
        bankDeposit.properties = parsedParams
        bankDeposit.status = getInitialStatus(bankDeposit.bank)
        bankDeposit.save(failOnError: true)

        return bankDeposit
    }

    public BankDeposit save(BankDepositVO bankDepositVO) {
        BankDeposit bankDeposit = new BankDeposit()
        bankDeposit.properties = bankDepositVO.properties
        bankDeposit.status = getInitialStatus(bankDeposit.bank)
        bankDeposit.save(failOnError: true)

        return bankDeposit
    }

    public BankDeposit update(Long id, Map params) {
        Map parsedParams = parseParams(params)

        BankDeposit bankDeposit = BankDeposit.get(id)

        if (!bankDeposit.canManipulate()) {
            return DomainUtils.addError(bankDeposit, "Não é possível editar este depósito bancário.")
        }

        bankDeposit.properties = parsedParams
        bankDeposit.save(failOnError: true)

        return bankDeposit
    }

    public void delete(Long id) {
        BankDeposit bankDeposit = BankDeposit.get(id)

        if (!bankDeposit.canManipulate()) throw new BusinessException("Não é possível remover este depósito bancário.")

        bankDeposit.deleted = true
        bankDeposit.save(failOnError: true)
    }

    public void generateBalanceAppropriation() {
        List<BankDeposit> bankDepositList = BankDeposit.readyForBalanceAppropriation([:]).list()
        if (!bankDepositList) return

        FinancialStatementType creditFinancialStatementType = FinancialStatementType.APPROPRIATED_BALANCE_CREDIT
        FinancialStatementType debitFinancialStatementType = FinancialStatementType.APPROPRIATED_BALANCE_DEBIT

        Date statementDate = new Date().clearTime()

        Map<Bank, List<BankDeposit>> bankDepositListGroupedByBank = bankDepositList.groupBy { it.bank }

        for (Bank bank : bankDepositListGroupedByBank.keySet()) {
            List<BankDeposit> bankDepositListByBank = bankDepositListGroupedByBank[bank]

            BigDecimal financialStatementValue = bankDepositListByBank.value.sum()

            FinancialStatement creditFinancialStatement = financialStatementService.save(creditFinancialStatementType, statementDate, bank, financialStatementValue)
            financialStatementService.saveItems(creditFinancialStatement, bankDepositListByBank)

            FinancialStatement debitFinancialStatement = financialStatementService.save(debitFinancialStatementType, statementDate, bank, financialStatementValue)
            financialStatementService.saveItems(debitFinancialStatement, bankDepositListByBank)
        }

        for (BankDeposit bankDeposit : bankDepositList) {
            bankDeposit.status = BankDepositStatus.APPROPRIATED_BALANCE
            bankDeposit.save(failOnError: true)
        }
    }

    public void expropriateBalance(Long id) {
        BankDeposit bankDeposit = BankDeposit.get(id)

        BusinessValidation businessValidation = bankDeposit.canExpropriateBalance()

        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        FinancialStatement financialStatement = financialStatementService.save(FinancialStatementType.EXPROPRIATED_BALANCE_EXPENSE, new Date(), bankDeposit.bank, bankDeposit.value)
        financialStatementItemService.save(financialStatement, bankDeposit)

        bankDeposit.status = BankDepositStatus.AWAITING_MANUAL_CONCILIATION
        bankDeposit.save(failOnError: true)
    }

    public void sendToManualConciliation(Long id) {
        BankDeposit bankDeposit = BankDeposit.get(id)

        if (bankDeposit.status.isAwaitingManualConciliation()) return

        BusinessValidation businessValidation = bankDeposit.canSendToManualConciliation()
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        bankDeposit.status = BankDepositStatus.AWAITING_MANUAL_CONCILIATION
        bankDeposit.save(failOnError: true)
    }

    private BankDepositStatus getInitialStatus(Bank bank) {
        switch (bank.code) {
            case [SupportedBank.BANCO_DO_BRASIL.code(), SupportedBank.ITAU.code(), SupportedBank.BRADESCO.code(), SupportedBank.CAIXA.code(), SupportedBank.SANTANDER.code()]:
                return BankDepositStatus.AWAITING_AUTOMATIC_CONCILIATION
            default:
                return BankDepositStatus.AWAITING_MANUAL_CONCILIATION
        }
    }

    private Map parseParams(Map params) {
        Map parsedDocumentNumberParams = DepositReceiptBuilder.parseDocumentNumberParams(params)

        Map parsedParams = [
            bank: Bank.ignoreAsaasBank([code: params.bankCode]).get(),
            billingType: params.billingType,
            documentDate: DepositReceiptBuilder.buildDocumentDate(params.bankCode, params.documentTime, params.documentDate),
            value: Utils.toBigDecimal(params.value),
            originName: params.originName,
            originCpfCnpj: params.originCpfCnpj,
            originAgency: DepositReceiptBuilder.buildAgency(params.originAgency),
            originAgencyDigit: params.originAgencyDigit,
            originAccount: DepositReceiptBuilder.buildAccount(params.originAccount, params.bankCode),
            originAccountDigit: params.originAccountDigit,
            originTerminal: params.originTerminal,
            originTransactionNumber: params.originTransactionNumber,
            documentNumber: DepositReceiptBuilder.buildDocumentNumber(parsedDocumentNumberParams)
        ]

        return parsedParams
    }
}

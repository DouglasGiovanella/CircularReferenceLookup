package com.asaas.service.financialstatement

import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.repository.FinancialTransactionCustomerCommissionRepository
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class FinancialStatementCustomerCommissionService {

    FinancialStatementService financialStatementService

    public void createForBankSlip(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            createForBankSlipFee(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForBankSlipFee >> Falha ao gerar os lançamentos contábeis de comissão de boleto."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForBankSlipFeeRefund(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForBankSlipFeeRefund >> Falha ao gerar os lançamentos contábeis de estorno de comissão de boleto."])
    }

    public void createForPix(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            createForPixFee(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForPixFee >> Falha ao gerar os lançamentos contábeis de comissão de pix."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForPixFeeRefund(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForPixFeeRefund >> Falha ao gerar os lançamentos contábeis de estorno de comissão de Pix."])
    }

    public void createForCreditCard(Date transactionDate) {
       Utils.withNewTransactionAndRollbackOnError({
            createForCreditCardFee(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForCreditCardFee >> Falha ao gerar os lançamentos contábeis de comissão de cartão de crédito."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForCreditCardFeeRefund(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForCreditCardFeeRefund >> Falha ao gerar os lançamentos contábeis de estorno de comissão de cartão de crédito."])
    }

    public void createForDebitCard(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            createForDebitCardFee(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForDebitCardFee >> Falha ao gerar os lançamentos contábeis de comissão de cartão de débito."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForDebitCardFeeRefund(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForDebitCardFeeRefund >> Falha ao gerar os lançamentos contábeis de estorno de comissão de cartão de débito."])
    }

    public void createForReceivableAnticipation(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            createForAsaasBankSlipReceivableAnticipation(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForAsaasBankSlipReceivableAnticipation >> Falha ao gerar os lançamentos contábeis de comissão de antecipação para boleto Asaas."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForAsaasPixReceivableAnticipation(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForAsaasPixReceivableAnticipation >> Falha ao gerar os lançamentos contábeis de comissão de antecipação para Pix Asaas."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForAsaasCreditCardReceivableAnticipation(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForAsaasCreditCardReceivableAnticipation >> Falha ao gerar os lançamentos contábeis de comissão de antecipação para cartão de crédito. Asaas"])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForFidcBankSlipReceivableAnticipation(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForFidcBankSlipReceivableAnticipation >> Falha ao gerar os lançamentos contábeis de comissão de antecipação para boleto FIDC."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForFidcPixReceivableAnticipation(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForFidcPixReceivableAnticipation >> Falha ao gerar os lançamentos contábeis de comissão de antecipação para Pix FIDC."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForFidcCreditCardReceivableAnticipation(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForFidcCreditCardReceivableAnticipation >> Falha ao gerar os lançamentos contábeis de comissão de antecipação para cartão de crédito. FIDC"])
    }

    public void createFinancialStatementsForCustomerCommission(Date transactionDate) {
        Utils.withNewTransactionAndRollbackOnError({
            createForInvoiceFee(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForInvoiceFee >> Falha ao gerar os lançamentos contábeis de estorno de comissão de nota fiscal."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForPaymentDunningFee(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForPaymentDunningFee >> Falha ao gerar os lançamentos contábeis de comissão de negativação."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForPaymentDunningFeeRefund(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForPaymentDunningFeeRefund >> Falha ao gerar os lançamentos contábeis de estorno de comissão de negativação."])

        Utils.flushAndClearSession()

        Utils.withNewTransactionAndRollbackOnError({
            createForConvertedCustomer(transactionDate)
        }, [logErrorMessage: "FinancialStatementCustomerCommissionService.createForConvertedCustomer >> Falha ao gerar os lançamentos contábeis de comissão de cliente convertido."])
    }

    private void createForBankSlipFee(Date transactionDate) {
        List<FinancialTransaction> transactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.BANK_SLIP_FEE,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_BANK_SLIP_FEE_CREDIT,
            transactionDate
        )
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_BANK_SLIP_FEE_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_BANK_SLIP_FEE_DEBIT]
        ] as List<Map>

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createForBankSlipFeeRefund(Date transactionDate) {
        List<FinancialTransaction> transactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.BANK_SLIP_FEE_REFUND,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_DEBIT,
            FinancialStatementType.CUSTOMER_COMMISSION_BANK_SLIP_FEE_REFUND_CREDIT,
            transactionDate
        )
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_BANK_SLIP_FEE_REFUND_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_BANK_SLIP_FEE_REFUND_DEBIT]
        ] as List<Map>

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createForInvoiceFee(Date transactionDate) {
        List<FinancialTransaction> transactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.INVOICE_FEE,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_INVOICE_FEE_CREDIT,
            transactionDate
        )
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_INVOICE_FEE_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_INVOICE_FEE_DEBIT]
        ] as List<Map>

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createForPixFee(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listSettledCommissionsTransactions(CustomerCommissionType.PIX_FEE, FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT, FinancialStatementType.CUSTOMER_COMMISSION_PIX_FEE_CREDIT, transactionDate)
        if (!financialTransactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_PIX_FEE_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_PIX_FEE_DEBIT]
        ] as List<Map>

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, santander)
    }

    private void createForPixFeeRefund(Date transactionDate) {
        List<FinancialTransaction> transactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.PIX_FEE_REFUND,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_DEBIT,
            FinancialStatementType.CUSTOMER_COMMISSION_PIX_FEE_REFUND_CREDIT,
            transactionDate
        )
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_PIX_FEE_REFUND_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_PIX_FEE_REFUND_DEBIT]
        ] as List<Map>

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createForPaymentDunningFee(Date transactionDate) {
        List<FinancialTransaction> transactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.PAYMENT_DUNNING_FEE,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_PAYMENT_DUNNING_FEE_CREDIT,
            transactionDate
        )
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_PAYMENT_DUNNING_FEE_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_PAYMENT_DUNNING_FEE_DEBIT]
        ] as List<Map>

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createForPaymentDunningFeeRefund(Date transactionDate) {
        List<FinancialTransaction> transactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.PAYMENT_DUNNING_REFUND,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_DEBIT,
            FinancialStatementType.CUSTOMER_COMMISSION_PAYMENT_DUNNING_FEE_REFUND_CREDIT,
            transactionDate
        )
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_PAYMENT_DUNNING_FEE_REFUND_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_PAYMENT_DUNNING_FEE_REFUND_DEBIT]
        ] as List<Map>

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createForCreditCardFee(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = FinancialTransactionCustomerCommissionRepository.query([
            customerCommissionSettled: true,
            "customerCommissionType[in]": [CustomerCommissionType.CREDIT_CARD_FEE, CustomerCommissionType.CREDIT_CARD_PAYMENT_FEE],
            financialTransactionType: FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            "financialTransactionDate": transactionDate,
            "financialStatementType[notExists]": FinancialStatementType.CUSTOMER_COMMISSION_CREDIT_CARD_FEE_CREDIT,
        ]).distinct("financialTransaction").disableSort().readOnly().list() as List<FinancialTransaction>

        if (!financialTransactionList) return

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_CREDIT_CARD_FEE_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_CREDIT_CARD_FEE_DEBIT]
        ] as List<Map>

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, santander)
    }

    private void createForCreditCardFeeRefund(Date transactionDate) {
        List<FinancialTransaction> transactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.CREDIT_CARD_REFUND,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_DEBIT,
            FinancialStatementType.CUSTOMER_COMMISSION_CREDIT_CARD_FEE_REFUND_CREDIT,
            transactionDate
        )
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_CREDIT_CARD_FEE_REFUND_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_CREDIT_CARD_FEE_REFUND_DEBIT]
        ] as List<Map>

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createForDebitCardFee(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.DEBIT_CARD_FEE, FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_DEBIT_CARD_FEE_CREDIT,
            transactionDate
        )
        if (!financialTransactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_DEBIT_CARD_FEE_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_DEBIT_CARD_FEE_DEBIT]
        ] as List<Map>

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, santander)
    }

    private void createForDebitCardFeeRefund(Date transactionDate) {
        List<FinancialTransaction> transactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.DEBIT_CARD_REFUND,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_DEBIT,
            FinancialStatementType.CUSTOMER_COMMISSION_DEBIT_CARD_FEE_REFUND_CREDIT,
            transactionDate
        )
        if (!transactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_DEBIT_CARD_FEE_REFUND_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_DEBIT_CARD_FEE_REFUND_DEBIT]
        ] as List<Map>

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createForConvertedCustomer(Date transactionDate) {
        List<FinancialTransaction> transactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.CONVERTED_CUSTOMER,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_CONVERTED_CUSTOMER_CREDIT,
            transactionDate
        )
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_CONVERTED_CUSTOMER_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_CONVERTED_CUSTOMER_DEBIT]
        ] as List<Map>

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void createForAsaasBankSlipReceivableAnticipation(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.ASAAS_BANK_SLIP_RECEIVABLE_ANTICIPATION,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_ASAAS_BANK_SLIP_RECEIVABLE_ANTICIPATION_CREDIT,
            transactionDate
        )
        if (!financialTransactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_ASAAS_BANK_SLIP_RECEIVABLE_ANTICIPATION_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_ASAAS_BANK_SLIP_RECEIVABLE_ANTICIPATION_DEBIT]
        ] as List<Map>

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, santander)
    }

    private void createForAsaasPixReceivableAnticipation(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.ASAAS_PIX_RECEIVABLE_ANTICIPATION,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_ASAAS_PIX_RECEIVABLE_ANTICIPATION_CREDIT,
            transactionDate
        )
        if (!financialTransactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_ASAAS_PIX_RECEIVABLE_ANTICIPATION_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_ASAAS_PIX_RECEIVABLE_ANTICIPATION_DEBIT]
        ] as List<Map>

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, santander)
    }

    private void createForFidcBankSlipReceivableAnticipation(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.FIDC_BANK_SLIP_RECEIVABLE_ANTICIPATION,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_FIDC_BANK_SLIP_RECEIVABLE_ANTICIPATION_CREDIT,
            transactionDate
        )
        if (!financialTransactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_FIDC_BANK_SLIP_RECEIVABLE_ANTICIPATION_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_FIDC_BANK_SLIP_RECEIVABLE_ANTICIPATION_DEBIT]
        ] as List<Map>

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, santander)
    }

    private void createForFidcPixReceivableAnticipation(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.FIDC_PIX_RECEIVABLE_ANTICIPATION,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_FIDC_PIX_RECEIVABLE_ANTICIPATION_CREDIT,
            transactionDate
        )
        if (!financialTransactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_FIDC_PIX_RECEIVABLE_ANTICIPATION_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_FIDC_PIX_RECEIVABLE_ANTICIPATION_DEBIT]
        ] as List<Map>

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, santander)
    }

    private void createForAsaasCreditCardReceivableAnticipation(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.ASAAS_CREDIT_CARD_RECEIVABLE_ANTICIPATION,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_ASAAS_CREDIT_CARD_RECEIVABLE_ANTICIPATION_CREDIT,
            transactionDate
        )
        if (!financialTransactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_ASAAS_CREDIT_CARD_RECEIVABLE_ANTICIPATION_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_ASAAS_CREDIT_CARD_RECEIVABLE_ANTICIPATION_DEBIT]
        ] as List<Map>

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, santander)
    }

    private void createForFidcCreditCardReceivableAnticipation(Date transactionDate) {
        List<FinancialTransaction> financialTransactionList = listSettledCommissionsTransactions(
            CustomerCommissionType.FIDC_CREDIT_CARD_RECEIVABLE_ANTICIPATION,
            FinancialTransactionType.CUSTOMER_COMMISSION_SETTLEMENT_CREDIT,
            FinancialStatementType.CUSTOMER_COMMISSION_FIDC_CREDIT_CARD_RECEIVABLE_ANTICIPATION_CREDIT,
            transactionDate
        )
        if (!financialTransactionList) return

        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_FIDC_CREDIT_CARD_RECEIVABLE_ANTICIPATION_CREDIT],
            [financialStatementType: FinancialStatementType.CUSTOMER_COMMISSION_FIDC_CREDIT_CARD_RECEIVABLE_ANTICIPATION_DEBIT]
        ] as List<Map>

        Bank santander = Bank.findByCode(SupportedBank.SANTANDER.code())
        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", financialTransactionList, financialStatementInfoMapList, santander)
    }

    private List<FinancialTransaction> listSettledCommissionsTransactions(CustomerCommissionType commissionType, FinancialTransactionType transactionType, FinancialStatementType statementType, Date transactionDate) {
        return FinancialTransactionCustomerCommissionRepository.query([
            customerCommissionSettled: true,
            customerCommissionType: commissionType,
            financialTransactionType: transactionType,
            financialTransactionDate: transactionDate,
            "financialStatementType[notExists]": statementType
        ]).distinct("financialTransaction").disableSort().readOnly().list() as List<FinancialTransaction>
    }
}

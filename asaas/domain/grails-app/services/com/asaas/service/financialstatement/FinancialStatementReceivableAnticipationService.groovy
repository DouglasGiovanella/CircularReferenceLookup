package com.asaas.service.financialstatement

import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.domain.bank.Bank
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementBatch
import com.asaas.financialstatement.FinancialStatementAcquirerUtils
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementReceivableAnticipationService {

    def financialStatementService
    def financialStatementItemService

    public void createForReceivableAnticipationPartnerSettlementBatch(Long partnerSettlementBatchId) {
        Utils.withNewTransactionAndRollbackOnError({
            ReceivableAnticipationPartnerSettlementBatch receivableAnticipationPartnerSettlementBatch = ReceivableAnticipationPartnerSettlementBatch.get(partnerSettlementBatchId)
            if (!receivableAnticipationPartnerSettlementBatch) return

            FinancialStatement anticipationDebit = financialStatementService.save(FinancialStatementType.RECEIVABLE_ANTICIPATION_DEBIT, new Date().clearTime(), null, receivableAnticipationPartnerSettlementBatch.getItemsValue())

            financialStatementItemService.save(anticipationDebit, receivableAnticipationPartnerSettlementBatch)
        }, [logErrorMessage: "FinancialStatementReceivableAnticipationService - Erro ao executar createForReceivableAnticipationPartnerSettlementBatch para a remessa [${partnerSettlementBatchId}]"])
    }

    public void createAsaasBankSlipReceivableAnticipationStatements(Date transactionDate) {
        processAsaasBankSlipOrPixReceivableAnticipationGrossCreditTransactions(transactionDate, FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_BANK_SLIP_DEBIT, BillingType.BOLETO)
    }

    public void createAsaasPixReceivableAnticipationStatements(Date transactionDate) {
        processAsaasBankSlipOrPixReceivableAnticipationGrossCreditTransactions(transactionDate, FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_PIX_DEBIT, BillingType.PIX)
    }

    public void createAsaasCreditCardReceivableAnticipationStatements(Date startDate, Date endDate) {
        Map search = [:]

        search.transactionType = FinancialTransactionType.RECEIVABLE_ANTICIPATION_GROSS_CREDIT
        search."partnerAcquisition.partner[in]" = [ReceivableAnticipationPartner.ASAAS, ReceivableAnticipationPartner.OCEAN]
        search."transactionDate[ge]" = startDate
        search."transactionDate[lt]" = endDate
        search."financialStatementTypeList[notExists]" = [FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_CREDIT]
        search.receivableAnticipationBillingType = BillingType.MUNDIPAGG_CIELO

        Bank asaasBank = Bank.findByCode(SupportedBank.ASAAS.code())
        for (CreditCardAcquirer acquirer : CreditCardAcquirer.listToFilter()) {
            List<FinancialTransaction> transactionsWithPaymentList = FinancialTransaction.query(search + [
                receivableAnticipationPaymentCreditCardAcquirer: acquirer
            ]).list(readOnly: true)

            List<Long> transactionsWithInstallmentIdList = FinancialTransaction.query(search + [
                receivableAnticipationInstallmentCreditCardAcquirer: acquirer,
                distinct: "id"
            ]).list(readOnly: true)

            List<FinancialTransaction> transactionsWithInstallmentList = FinancialTransaction.getAll(transactionsWithInstallmentIdList)

            List<Map> financialStatementInfoMapList = [
                [financialStatementType: FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_CREDIT],
                [financialStatementType: FinancialStatementAcquirerUtils.getAcquirerAsaasReceivableAnticipationDebitFinancialStatementType(acquirer)]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionsWithPaymentList + transactionsWithInstallmentList, financialStatementInfoMapList, asaasBank)

            Map customerCreditFinancialStatementInfoMap = [
                financialStatementType: FinancialStatementAcquirerUtils.getAcquirerCustomerReceivableAnticipationCreditFinancialStatementType(acquirer)
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionsWithPaymentList + transactionsWithInstallmentList, [customerCreditFinancialStatementInfoMap], null)
        }
    }

    public void createAsaasBankSlipReceivableAnticipationSettlementStatements(Date transactionDate) {
        processBankSlipOrPixReceivableAnticipationPartnerSettlementTransactions(transactionDate, FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_BANK_SLIP_SETTLEMENT, BillingType.BOLETO)
    }

    public void createAsaasPixReceivableAnticipationSettlementStatements(Date transactionDate) {
        processBankSlipOrPixReceivableAnticipationPartnerSettlementTransactions(transactionDate, FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_PIX_SETTLEMENT, BillingType.PIX)
    }

    public void createAsaasCreditCardReceivableAnticipationSettlementStatements(Date startDate, Date endDate) {
        Map search = [:]
        search.transactionTypeList = [FinancialTransactionType.RECEIVABLE_ANTICIPATION_DEBIT, FinancialTransactionType.RECEIVABLE_ANTICIPATION_PARTNER_SETTLEMENT]
        search."partnerAcquisition.partner[in]" = [ReceivableAnticipationPartner.ASAAS, ReceivableAnticipationPartner.OCEAN]
        search."transactionDate[ge]" = startDate
        search."transactionDate[lt]" = endDate
        search."financialStatementTypeList[notExists]" = [FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_SETTLEMENT_DEBIT]
        search.receivableAnticipationBillingType = BillingType.MUNDIPAGG_CIELO

        Bank asaasBank = Bank.findByCode(SupportedBank.ASAAS.code())
        for (CreditCardAcquirer acquirer : CreditCardAcquirer.listToFilter()) {
            List<FinancialTransaction> transactionsWithPaymentList = FinancialTransaction.query(search + [
                receivableAnticipationPaymentCreditCardAcquirer: acquirer
            ]).list(readOnly: true)

            List<Long> transactionsWithInstallmentIdList = FinancialTransaction.query(search + [
                receivableAnticipationInstallmentCreditCardAcquirer: acquirer,
                distinct: "id"
            ]).list(readOnly: true)

            List<FinancialTransaction> transactionsWithInstallmentList = FinancialTransaction.getAll(transactionsWithInstallmentIdList)

            List<Map> financialStatementInfoMapList = [
                [financialStatementType: FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_SETTLEMENT_DEBIT],
                [financialStatementType: FinancialStatementAcquirerUtils.getAcquirerAsaasReceivableAnticipationSettlementFinancialStatementType(acquirer)]
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionsWithPaymentList + transactionsWithInstallmentList, financialStatementInfoMapList, asaasBank)

            Map customerDebtFinancialStatementInfoMap = [
                financialStatementType: FinancialStatementAcquirerUtils.getAcquirerCustomerReceivableAnticipationSettlementDebitFinancialStatementType(acquirer)
            ]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionsWithPaymentList + transactionsWithInstallmentList, [customerDebtFinancialStatementInfoMap], null)
        }
    }

    public void createAsaasReceivableAnticipationFeeStatements(Date transactionDate) {
        List<CreditCardAcquirer> acquirerList = [CreditCardAcquirer.ADYEN, CreditCardAcquirer.REDE]
        Bank asaasBank = Bank.findByCode(SupportedBank.ASAAS.code())
        createAsaasReceivableAnticipationFeeStatementsGroupedByAcquirer(acquirerList, asaasBank, transactionDate)

        acquirerList = [CreditCardAcquirer.CIELO]
        Bank santanderBank = Bank.findByCode(SupportedBank.SANTANDER.code())
        createAsaasReceivableAnticipationFeeStatementsGroupedByAcquirer(acquirerList, santanderBank, transactionDate)
    }

    public void createFidc1BankSlipReceivableAnticipationStatements(Date transactionDate) {
        processBankSlipOrPixFidc1ReceivableAnticipationGrossCreditTransactions(transactionDate, FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_BANK_SLIP_CREDIT, BillingType.BOLETO)
    }

    public void createFidc1PixReceivableAnticipationStatements(Date transactionDate) {
        processBankSlipOrPixFidc1ReceivableAnticipationGrossCreditTransactions(transactionDate, FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_PIX_CREDIT, BillingType.PIX)
    }

    public void createFidc1CreditCardReceivableAnticipationStatements(Date startDate, Date endDate) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.RECEIVABLE_ANTICIPATION_GROSS_CREDIT
        search."partnerAcquisition.partner[in]" = [ReceivableAnticipationPartner.VORTX]
        search."transactionDate[ge]" = startDate
        search."transactionDate[lt]" = endDate
        search."financialStatementTypeList[notExists]" = [FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_ADYEN_CREDIT, FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_CIELO_CREDIT, FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_REDE_CREDIT]
        search.receivableAnticipationBillingType = BillingType.MUNDIPAGG_CIELO

        Bank bank = Bank.findByCode(SupportedBank.BRADESCO.code())
        List<Map> acquirerInfoMapList = [
            [
                acquirer: CreditCardAcquirer.ADYEN,
                financialStatementType: FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_ADYEN_CREDIT
            ],
            [
                acquirer: CreditCardAcquirer.CIELO,
                financialStatementType: FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_CIELO_CREDIT
            ],
            [
                acquirer: CreditCardAcquirer.REDE,
                financialStatementType: FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_REDE_CREDIT
            ]
        ]

        for (Map acquirerInfoMap : acquirerInfoMapList) {
            List<FinancialTransaction> transactionsWithPaymentList = FinancialTransaction.query(search + [
                receivableAnticipationPaymentCreditCardAcquirer: acquirerInfoMap.acquirer
            ]).list(readOnly: true)

            List<Long> transactionsWithInstallmentIdList = FinancialTransaction.query(search + [
                receivableAnticipationInstallmentCreditCardAcquirer: acquirerInfoMap.acquirer,
                distinct: "id"
            ]).list(readOnly: true)

            List<FinancialTransaction> transactionsWithInstallmentList = transactionsWithInstallmentIdList.collect { Long financialTransactionId -> FinancialTransaction.read(financialTransactionId) }

            Map financialStatementInfoMap = [financialStatementType: acquirerInfoMap.financialStatementType]

            financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionsWithPaymentList + transactionsWithInstallmentList, [financialStatementInfoMap], bank)
        }
    }

    public void createFidc1ReceivableAnticipationFeeStatements(Date startDate, Date endDate) {
        List<FinancialTransaction> transactionList = FinancialTransaction.query([
            "transactionType": FinancialTransactionType.RECEIVABLE_ANTICIPATION_FEE,
            "partnerAcquisition.partner[in]": [ReceivableAnticipationPartner.VORTX],
            "transactionDate[ge]": startDate,
            "transactionDate[lt]": endDate,
            "financialStatementTypeList[notExists]": [FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_FEE_DEBIT]
        ]).list(readOnly: true)

        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.BRADESCO.code())
        Map financialStatementInfoMap = [
            financialStatementType: FinancialStatementType.FIDC_RECEIVABLE_ANTICIPATION_FEE_DEBIT
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, [financialStatementInfoMap], bank)
    }

    private void createAsaasReceivableAnticipationFeeStatementsGroupedByAcquirer(List<CreditCardAcquirer> acquirerList, Bank bank, Date transactionDate) {
        Map search = [:]
        search."transactionType" = FinancialTransactionType.RECEIVABLE_ANTICIPATION_FEE
        search."partnerAcquisition.partner[in]" = [ReceivableAnticipationPartner.ASAAS, ReceivableAnticipationPartner.OCEAN]
        search."transactionDate" = transactionDate
        search."financialStatementTypeList[notExists]" = [FinancialStatementType.ANTICIPATION_FEE_REVENUE, FinancialStatementType.ANTICIPATION_FEE_EXPENSE, FinancialStatementType.ANTICIPATION_FEE_DEBIT]

        List<FinancialTransaction> transactionsWithPaymentList = []
        List<FinancialTransaction> transactionsWithInstallmentList = []
        for (CreditCardAcquirer acquirer : acquirerList) {
            transactionsWithPaymentList += FinancialTransaction.query(search + [
                receivableAnticipationPaymentCreditCardAcquirer: acquirer
            ]).list(readOnly: true)

            List<Long> transactionsWithInstallmentIdList = FinancialTransaction.query(search + [
                receivableAnticipationInstallmentCreditCardAcquirer: acquirer,
                distinct: "id"
            ]).list(readOnly: true)

            transactionsWithInstallmentList += FinancialTransaction.getAll(transactionsWithInstallmentIdList)
        }
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.ANTICIPATION_FEE_REVENUE],
            [financialStatementType: FinancialStatementType.ANTICIPATION_FEE_DEBIT]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionsWithPaymentList + transactionsWithInstallmentList, financialStatementInfoMapList, bank)
    }

    private void processBankSlipOrPixReceivableAnticipationPartnerSettlementTransactions(Date transactionDate, FinancialStatementType financialStatementSettlementType, BillingType billingType) {
        Map search = [:]
        search.transactionTypeList = [FinancialTransactionType.RECEIVABLE_ANTICIPATION_DEBIT, FinancialTransactionType.RECEIVABLE_ANTICIPATION_PARTNER_SETTLEMENT]
        search."partnerAcquisition.partner[in]" = [ReceivableAnticipationPartner.ASAAS, ReceivableAnticipationPartner.OCEAN]
        search."transactionDate" = transactionDate
        search."financialStatementTypeList[notExists]" = [FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_SETTLEMENT_DEBIT]
        search.receivableAnticipationBillingType = billingType

        List<FinancialTransaction> transactionList = FinancialTransaction.query(search).list(readOnly: true)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_SETTLEMENT_DEBIT],
            [financialStatementType: financialStatementSettlementType]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void processAsaasBankSlipOrPixReceivableAnticipationGrossCreditTransactions(Date transactionDate, FinancialStatementType financialStatementDebitType, BillingType billingType) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.RECEIVABLE_ANTICIPATION_GROSS_CREDIT
        search."partnerAcquisition.partner[in]" = [ReceivableAnticipationPartner.ASAAS, ReceivableAnticipationPartner.OCEAN]
        search."transactionDate" = transactionDate
        search."financialStatementTypeList[notExists]" = [FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_CREDIT]
        search.receivableAnticipationBillingType = billingType

        List<FinancialTransaction> transactionList = FinancialTransaction.query(search).list(readOnly: true)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.SANTANDER.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: FinancialStatementType.ASAAS_RECEIVABLE_ANTICIPATION_CREDIT],
            [financialStatementType: financialStatementDebitType]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }

    private void processBankSlipOrPixFidc1ReceivableAnticipationGrossCreditTransactions(Date transactionDate, FinancialStatementType financialStatementCreditType, BillingType billingType) {
        Map search = [:]
        search.transactionType = FinancialTransactionType.RECEIVABLE_ANTICIPATION_GROSS_CREDIT
        search."partnerAcquisition.partner[in]" = [ReceivableAnticipationPartner.VORTX]
        search."transactionDate" = transactionDate
        search."financialStatementTypeList[notExists]" = [financialStatementCreditType]
        search.receivableAnticipationBillingType = billingType

        List<FinancialTransaction> transactionList = FinancialTransaction.query(search).list(readOnly: true)
        if (!transactionList) return

        Bank bank = Bank.findByCode(SupportedBank.BRADESCO.code())
        List<Map> financialStatementInfoMapList = [
            [financialStatementType: financialStatementCreditType]
        ]

        financialStatementService.groupFinancialTransactionsAndSave("transactionDate", transactionList, financialStatementInfoMapList, bank)
    }
}

package com.asaas.service.financialstatement

import com.asaas.asyncaction.AsyncActionType
import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.domain.bank.Bank
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.debitcard.DebitCardTransactionInfo
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.domain.financialstatement.FinancialStatementItem
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentConfirmRequest
import com.asaas.financialstatement.FinancialStatementAcquirerUtils
import com.asaas.financialstatement.FinancialStatementStatus
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.status.Status
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class FinancialStatementService {

    def asyncActionService
    def financialStatementBankSlipFeeService
    def financialStatementCacheService
    def financialStatementItemService
    def grailsApplication

    public Long saveWithNewTransaction(FinancialStatementType financialStatementType, Date statementDate, Bank bank, BigDecimal value) {
        Long financialStatementId = null
        Boolean success = true

        Utils.withNewTransactionAndRollbackOnError({
            FinancialStatement financialStatement = save(financialStatementType, statementDate, bank, value)
            financialStatementId = financialStatement.id
        }, [
            onError: {
                success = false
            }
        ])

        if (!success) throw new RuntimeException("FinancialStatementService.saveWithNewTransaction() -> Falha ao salvar lançamento financeiro [financialStatementType: ${financialStatementType}]")

        return financialStatementId
    }

    public FinancialStatement save(FinancialStatementType financialStatementType, Date statementDate, Bank bank, BigDecimal value, String customDescription = null) {
        FinancialStatement financialStatement = new FinancialStatement()
        financialStatement.financialStatementType = financialStatementType
        financialStatement.statementDate = statementDate.clone().clearTime()
        financialStatement.bank = bank
        financialStatement.bankAccountId = selectBankAccountId(financialStatement)

        financialStatement.provisionDate = getProvisionDate(statementDate, financialStatementType)
        financialStatement.description = customDescription ?: financialStatementType.getLabel()

        if (financialStatementType.isAsyncProcessSaveItems()) {
            financialStatement.status = FinancialStatementStatus.AWAITING_SAVE_ITEMS

            financialStatement.save(failOnError: true)
        } else {
            financialStatement.value = value.abs()
            financialStatement.status = FinancialStatementStatus.PENDING

            financialStatement.save(flush: true, failOnError: true)

            asyncActionService.save(AsyncActionType.BUILD_VALUE_AND_ORIGIN_INFO_FOR_FINANCIAL_STATEMENT_ITEM,
                null,
                [financialStatementId: financialStatement.id],
                [allowDuplicatePendingWithSameParameters: true])
        }

        return financialStatement
    }

    public void saveItems(FinancialStatement financialStatement, List<Object> domainInstanceList) {
        for (Object domainInstance : domainInstanceList) {
            financialStatementItemService.save(financialStatement, domainInstance)
		}
    }

    public void createForReceivedBankSlipPayments(List<Payment> paymentList, Bank bank, Boolean isCustomerRevenue) {
        final Integer paymentIdListCollateSize = 2500

        List<Map> paymentConfirmRequestList = []
        for (List<Long> paymentIdPartialList : paymentList.collate(paymentIdListCollateSize).collect { it.id }) {
            paymentConfirmRequestList += PaymentConfirmRequest.query([
                columnList: ["creditDate", "paidInternally", "payment"],
                "paymentId[in]": paymentIdPartialList,
                status: Status.SUCCESS,
                duplicatedPayment: false
            ]).list()
        }

        paymentConfirmRequestList.groupBy { it.creditDate as Date }.each { Date creditDate, List<Map> paymentConfirmRequestListGroupedByDate ->
            List<Payment> paymentListGrouped = paymentConfirmRequestListGroupedByDate.findAll { !it.paidInternally }.collect { it.payment as Payment }
            if (!paymentListGrouped) return

            if (isCustomerRevenue) {
                saveCustomerRevenue(paymentListGrouped, bank, creditDate)
            } else if (paymentListGrouped.first().isPlanPayment()) {
                savePlanRevenue(paymentListGrouped, bank, creditDate)
            } else {
                saveAsaasErpRevenue(paymentListGrouped, bank, creditDate)
            }

            saveBankFeeExpense(paymentListGrouped, bank, creditDate)
        }
    }

    public void saveCustomerRevenue(List<Payment> paymentList, Bank bank, Date statementDate) {
        Payment firstPayment = paymentList.first()

        FinancialStatementType financialStatementType
        if (firstPayment.billingType.isPix()) financialStatementType = FinancialStatementType.PIX_CUSTOMER_REVENUE

        String description
        CreditCardAcquirer creditCardAcquirer

        switch (firstPayment.billingType) {
            case BillingType.MUNDIPAGG_CIELO:
                creditCardAcquirer = CreditCardTransactionInfo.query([column: "acquirer", paymentId: firstPayment.id]).get()
                if (creditCardAcquirer) {
                    financialStatementType = FinancialStatementAcquirerUtils.getAcquirerCustomerRevenueFinancialStatementType(creditCardAcquirer)
                    description = "${creditCardAcquirer} - ${financialStatementType.getLabel()}"

                    saveAcquirerCustomerRevenueDebit(firstPayment.billingType, creditCardAcquirer, paymentList, statementDate, bank)
                }

                break
            case BillingType.DEBIT_CARD:
                Map debitCardTransactionInfo = DebitCardTransactionInfo.query([columnList: ["id", "acquirer"], payment: firstPayment]).get()

                financialStatementType = FinancialStatementType.ADYEN_DEBIT_CARD_CUSTOMER_REVENUE
                description = "${debitCardTransactionInfo.acquirer} - ${financialStatementType.getLabel()}"
                break
            case BillingType.DEPOSIT:
                financialStatementType = FinancialStatementType.DEPOSIT_CUSTOMER_REVENUE
                break
            case BillingType.BOLETO:
                financialStatementType = FinancialStatementType.BANK_SLIP_CUSTOMER_REVENUE
                break
            case BillingType.TRANSFER:
                financialStatementType = FinancialStatementType.TRANSFER_CUSTOMER_REVENUE
                break
        }

        if (!description) description = financialStatementType.getLabel()

        FinancialStatement financialStatement = save(financialStatementType, statementDate, bank, paymentList.value.sum(), description)
        financialStatementItemService.saveInBatch(financialStatement, paymentList)
    }

    public void savePlanRevenue(List<Payment> paymentList, Bank bank, Date statementDate) {
        FinancialStatementType type = FinancialStatementType.PLAN_REVENUE
        if (paymentList.first().billingType.isPix()) {
            type = FinancialStatementType.PIX_PLAN_REVENUE
            statementDate = paymentList.first().creditDate
        }

        FinancialStatement planRevenue = save(type, statementDate, bank, paymentList.value.sum())
        financialStatementItemService.saveInBatch(planRevenue, paymentList)
    }

    public void saveAsaasErpRevenue(List<Payment> paymentList, Bank bank, Date statementDate) {
        FinancialStatementType type = FinancialStatementType.ASAAS_ERP_REVENUE
        if (paymentList.first().billingType.isPix()) {
            type = FinancialStatementType.PIX_ASAAS_ERP_REVENUE
            statementDate = paymentList.first().creditDate
        }

        FinancialStatement asaasErpRevenue = save(type, statementDate, bank, paymentList.value.sum())

        financialStatementItemService.saveInBatch(asaasErpRevenue, paymentList)
    }

    public FinancialStatement readExistingFinancialStatementOrCreate(FinancialStatementType financialStatementType, Date statementDate, Bank bank) {
        Long financialStatementId = financialStatementCacheService.getCachedFinancialStatementId(financialStatementType, statementDate)

        if (!financialStatementId) {
            Boolean success = true
            Utils.withNewTransactionAndRollbackOnError({
                Map search = [:]
                search.financialStatementType = financialStatementType
                search.statementDate = statementDate
                search.status = FinancialStatementStatus.AWAITING_SAVE_ITEMS
                search.column = "id"

                financialStatementId = FinancialStatement.query(search).get()

                if (!financialStatementId) {
                    FinancialStatement financialStatement = save(financialStatementType, statementDate, bank, null)
                    financialStatementId = financialStatement.id
                }

                financialStatementCacheService.setCachedFinancialStatementId(financialStatementType, statementDate, financialStatementId)
            }, [onError: { success = false }])

            if (!success) throw new RuntimeException("FinancialStatementService.readExistingAwaitingFinancialStatementOrCreate() -> Falha ao carregar/salvar lançamento financeiro [financialStatementType: ${financialStatementType}]")
        }

        if (financialStatementCacheService.isAwaitingFinancialStatementGeneratedByAnotherThread(financialStatementId)) return null

        return FinancialStatement.read(financialStatementId)
    }

    public void delete(FinancialStatement financialStatement) {
        financialStatement.deleted = true
        financialStatement.save(flush: true, failOnError: true)
    }

    public void groupFinancialTransactionsAndSave(String groupByField, List<FinancialTransaction> financialTransactionList, List<Map> financialStatementInfoList, Bank bank) {
        Map<Date, List<FinancialTransaction>> transactionListGrouped = financialTransactionList.groupBy { it[groupByField] }
        transactionListGrouped.each { Date transactionDate, List<FinancialTransaction> financialTransactionListGrouped ->
            BigDecimal financialStatementSummedValue = financialTransactionListGrouped.value.sum()
            financialStatementInfoList.each { item ->
                FinancialStatement financialStatement = save(item.financialStatementType, financialTransactionListGrouped.first().transactionDate, bank, financialStatementSummedValue)
                Utils.forEachWithFlushSession(financialTransactionListGrouped, 50, { Object domainInstance ->
                    financialStatementItemService.save(financialStatement, domainInstance)
                })
            }
        }
    }

    public void groupFinancialTransactionsAndSaveInBatch(String groupByField, List<FinancialTransaction> financialTransactionList, List<Map> financialStatementInfoList, Bank bank) {
        Map<Date, List<FinancialTransaction>> transactionListGrouped = financialTransactionList.groupBy { it[groupByField] }
        for (Date transactionDate : transactionListGrouped.keySet()) {
            List<FinancialTransaction> financialTransactionListGrouped = transactionListGrouped[transactionDate]
            BigDecimal financialStatementSummedValue = financialTransactionListGrouped.value.sum()
            for (Map financialStatementInfo : financialStatementInfoList) {
                FinancialStatement financialStatement = save(financialStatementInfo.financialStatementType, transactionDate, bank, financialStatementSummedValue)
                financialStatementItemService.saveInBatch(financialStatement, financialTransactionListGrouped)
            }
        }
    }

    public void saveForPaymentIdList(List<Long> paymentIdList, List<Map> financialStatementInfoMapList, Date statementDate, Bank bank) {
        for (Map financialStatementInfoMap : financialStatementInfoMapList) {
            FinancialStatement financialStatement = save(financialStatementInfoMap.financialStatementType, statementDate, bank, financialStatementInfoMap.totalValue)

            Utils.forEachWithFlushSession(paymentIdList, 50, { Long paymentId ->
                financialStatementItemService.save(financialStatement, Payment.load(paymentId))
            })
        }
    }

    public void saveAccountingEntriesForTransactionIdList(List<Long> financialTransactionIdList, FinancialStatementType financialStatementType, Bank bank) {
        Date transactionDate = FinancialTransaction.query([column: 'transactionDate', id: financialTransactionIdList.first()]).get()
        Long financialStatementId = saveWithNewTransaction(financialStatementType, transactionDate, bank, null)
        financialStatementItemService.saveItemsWithThreads(financialTransactionIdList, financialStatementId)
    }

    public void setAsAwaitingCalculateFinancialStatementValue(FinancialStatement financialStatement) {
        financialStatement.status = FinancialStatementStatus.AWAITING_CALCULATE_FINANCIAL_STATEMENT_VALUE
        financialStatement.save(failOnError: true)
    }

    public void setAsSuccess(FinancialStatement financialStatement) {
        financialStatement.status = FinancialStatementStatus.SUCCESS
        financialStatement.save(failOnError: true)

        asyncActionService.save(AsyncActionType.BUILD_VALUE_AND_ORIGIN_INFO_FOR_FINANCIAL_STATEMENT_ITEM,
            null,
            [financialStatementId: financialStatement.id],
            [allowDuplicatePendingWithSameParameters: true])
    }

    public void setAsError(FinancialStatement financialStatement) {
        financialStatement.status = FinancialStatementStatus.ERROR
        financialStatement.save(failOnError: true)
    }

    public void processFinishedFinancialStatementsOnDemand(Date statementDate) {
        if (statementDate <= CustomDateUtils.fromString("28/05/2024")) return

        List<Long> financialStatementIdList = FinancialStatement.query([column: "id", statementDate: statementDate, status: FinancialStatementStatus.AWAITING_SAVE_ITEMS]).list() as List<Long>

        for (Long financialStatementId : financialStatementIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                FinancialStatement financialStatement = FinancialStatement.get(financialStatementId)
                if (!financialStatement.financialStatementType.isAsyncOnDemandGeneratedItems()) return

                setAsAwaitingCalculateFinancialStatementValue(financialStatement)
            }, [
                logErrorMessage: "FinancialStatementService.processFinishedFinancialStatementsOnDemand() -> Falha ao processar lançamento financeiro [financialStatementId: ${financialStatementId}]",
                onError: { Exception e ->
                    Utils.withNewTransactionAndRollbackOnError({
                        setAsError(FinancialStatement.get(financialStatementId))
                    }, [
                        logErrorMessage: "FinancialStatementService.processFinishedFinancialStatementsOnDemand() -> Falha ao enviar para ERRO um lançamento financeiro que ao liberar para cálculo do valor total do lançamento financeiro [financialStatementId: ${financialStatementId}]"
                    ])
                }
            ])
        }
    }

    public void processAwaitingCalculateValue() {
        List<Long> financialStatementIdList = FinancialStatement.query([column: "id", status: FinancialStatementStatus.AWAITING_CALCULATE_FINANCIAL_STATEMENT_VALUE]).list(max: 100) as List<Long>

        for (Long financialStatementId : financialStatementIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                FinancialStatement financialStatement = FinancialStatement.get(financialStatementId)
                setValueByCalculatingBasedOnItems(financialStatement)
                setAsSuccess(financialStatement)
            }, [
                logErrorMessage: "FinancialStatementItemService.processAwaitingCalculateValue() -> Falha ao calcular valor total do lanćamento financeiro [financialStatementId: ${financialStatementId}]",
                onError: { Exception e ->
                    Utils.withNewTransactionAndRollbackOnError({
                        setAsError(FinancialStatement.get(financialStatementId))
                    }, [
                        logErrorMessage: "FinancialStatementItemService.calculateValueBasedOnItemsWithNewTransaction() -> Falha ao enviar para ERRO um lançamento financeiro que falhou ao calcular o valor total dos itens [financialStatementId: ${financialStatementId}]"
                    ])
                }
            ])
        }
    }

    private void setValueByCalculatingBasedOnItems(FinancialStatement financialStatement) {
        if (!financialStatement.status.isAwaitingCalculateFinancialStatementValue()) throw new RuntimeException("FinancialStatementService.setValueByCalculatingBasedOnItems() -> Situação inválida para calcular valor total do lançamento financeiro. [financialStatement.id: ${financialStatement.id}, financialStatement.status: ${financialStatement.status}]")

        BigDecimal totalFinancialStatementValue = FinancialStatementItem.sumItemsValue([financialStatementId: financialStatement.id]).get()

        if (!totalFinancialStatementValue) throw new RuntimeException("FinancialStatementService.setValueByCalculatingBasedOnItems() -> Valor calculado inválido. [financialStatement.id: ${financialStatement.id}, totalFinancialStatementValue: ${totalFinancialStatementValue}]")
        financialStatement.value = totalFinancialStatementValue

        financialStatement.save(failOnError: true)
    }

    private void saveBankFeeExpense(List<Payment> paymentList, Bank bank, Date statementDate) {
        List<Payment> bankSlipPaymentList = paymentList.findAll{ it.billingType == BillingType.BOLETO }
		if (!bankSlipPaymentList) return

        BigDecimal financialStatementValue = financialStatementBankSlipFeeService.calculateFee(bankSlipPaymentList, bank)

        if (bank.code == SupportedBank.SICREDI.code()) {
            statementDate = CustomDateUtils.subtractBusinessDays(statementDate, 1)
        }

        FinancialStatementType financialStatementType
        if (bank.code == SupportedBank.ASAAS.code()) {
            financialStatementType = FinancialStatementType.BANKSLIP_SETTLEMENT_ASAAS_EXPENSE
        } else {
            financialStatementType = FinancialStatementType.BANK_FEE_EXPENSE
        }

		FinancialStatement bankFeeExpense = save(financialStatementType, statementDate, bank, financialStatementValue)

        for (Payment payment : bankSlipPaymentList) {
            financialStatementItemService.save(bankFeeExpense, payment)
		}
	}

    private void saveAcquirerCustomerRevenueDebit(BillingType billingType, CreditCardAcquirer creditCardAcquirer, List<Payment> paymentList, Date statementDate, Bank bank) {
        if (billingType.isCreditCard()) {
            FinancialStatementType acquirerAccountCutomerRevenueDebitType = FinancialStatementAcquirerUtils.getAcquirerCustomerRevenueDebitFinancialStatementType(creditCardAcquirer)
            String acquirerStatementDescription = "${acquirerAccountCutomerRevenueDebitType.getLabel()} - ${creditCardAcquirer.toString()}"
            FinancialStatement acquirerAccountDebitStatement = save(acquirerAccountCutomerRevenueDebitType, statementDate, bank, paymentList.value.sum(), acquirerStatementDescription)
            financialStatementItemService.saveInBatch(acquirerAccountDebitStatement, paymentList)
        }
    }

	private String selectBankAccountId(FinancialStatement financialStatement) {
        String accountType = financialStatement.financialStatementType.isAsaasBalance() ? "asaas" : "customer"

        if (!financialStatement.bank && (financialStatement.financialStatementType.isBillExpense() || financialStatement.financialStatementType.isRefundBillPaymentExpense())) {
            return grailsApplication.config.bank.api."${accountType}".celcoin.id
        }

        if (financialStatement.financialStatementType.isAsaasCardTransitory()) return grailsApplication.config.bank.api."${accountType}".transitoryElo.id

        if (financialStatement.financialStatementType.useStandardTransitoryBankAccount()) return grailsApplication.config.bank.api."${accountType}".transitory.id

        if (financialStatement.financialStatementType.isCustomerLossProvision()) return grailsApplication.config.bank.api."${accountType}".transitoryLossProvision.id

        if (financialStatement.financialStatementType.isScdLossProvision()) return grailsApplication.config.bank.api."${accountType}".transitoryLossProvision.id

        if (financialStatement.financialStatementType.isAdyenCreditCardTransitory()) return grailsApplication.config.bank.api."${accountType}".transitoryAdyen.id

        if (financialStatement.financialStatementType.isCieloCreditCardTransitory()) return grailsApplication.config.bank.api."${accountType}".transitoryCielo.id

        if (financialStatement.financialStatementType.isRedeCreditCardTransitory()) return grailsApplication.config.bank.api."${accountType}".transitoryRede.id

        if (financialStatement.financialStatementType.useBacenBankAccount()) {
            return grailsApplication.config.bank.api."${accountType}".bacenjud.id
        }

        if (financialStatement.financialStatementType.isPixTransaction()) {
            if (financialStatement.financialStatementType.isPixDirect()) {
                return grailsApplication.config.bank.api."${accountType}".pixDirect.id
            } else {
                return grailsApplication.config.bank.api."${accountType}".pixIndirect.id
            }
        }

        if (financialStatement.financialStatementType.isMobilePhoneRechargeExpense()) return grailsApplication.config.bank.api."${accountType}".celcoin.id

        if (financialStatement.financialStatementType.isRefundMobilePhoneRechargeExpense()) return grailsApplication.config.bank.api."${accountType}".celcoin.id

        if (financialStatement.financialStatementType.isBacenTransitory()) return grailsApplication.config.bank.api."${accountType}".transitoryBacen.id

        if (financialStatement.financialStatementType.isScdTransitory()) return grailsApplication.config.bank.api."${accountType}".transitoryScd.id

        return grailsApplication.config.bank.api."${accountType}"."${financialStatement.bank.code}".id
    }

    private Date getProvisionDate(Date statementDate, FinancialStatementType financialStatementType) {
        Integer dayLimitNumberForProvisions

        switch (financialStatementType) {
            case FinancialStatementType.ISS_TAX_ASAAS_BALANCE_PROVISION_DEBIT:
                dayLimitNumberForProvisions = 15
                break
            case FinancialStatementType.IR_TAX_ASAAS_BALANCE_PROVISION_DEBIT:
                dayLimitNumberForProvisions = 20
                break
            default:
                return null
        }

        Date provisionDate = CustomDateUtils.getLastDayOfMonth(statementDate)
        provisionDate = CustomDateUtils.sumDays(provisionDate, dayLimitNumberForProvisions)
        if (Holiday.isHoliday(provisionDate)) CustomDateUtils.setDateForLastBusinessDay(provisionDate)

        return provisionDate
    }
}

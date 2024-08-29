package com.asaas.service.confirmedfraud

import com.asaas.credit.CreditType
import com.asaas.customer.DisabledReason
import com.asaas.debit.DebitType
import com.asaas.domain.credit.Credit
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerDisabledReason
import com.asaas.domain.debit.Debit
import com.asaas.exception.BusinessException
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ConfirmedFraudService {

    def creditService
    def debitService
    def financialTransactionService

    public void executeNegativeBalanceZeroing(Customer customer) {
        BigDecimal currentBalanceWithoutPartnerSettlement = financialTransactionService.getCurrentBalanceWithoutPartnerSettlement(customer)
        if (currentBalanceWithoutPartnerSettlement >= 0) return

        saveCredit(customer.id, currentBalanceWithoutPartnerSettlement)
    }

    public void executeBalanceZeroingWhenBalanceChange(Customer customer) {
        Boolean isCustomerDisabledByConfirmedFraud = CustomerDisabledReason.query([customer: customer, disabledReason: DisabledReason.CONFIRMED_FRAUD]).get().asBoolean()
        if (!isCustomerDisabledByConfirmedFraud) return

        BigDecimal currentBalanceWithoutPartnerSettlement = financialTransactionService.getCurrentBalanceWithoutPartnerSettlement(customer)
        if (currentBalanceWithoutPartnerSettlement == 0) return

        if (currentBalanceWithoutPartnerSettlement < 0) {
            saveCredit(customer.id, currentBalanceWithoutPartnerSettlement)
            return
        }

        BigDecimal confirmedFraudTotalCreditValue = Credit.sumNotDerivative([customer: customer, type: CreditType.CONFIRMED_FRAUD_BALANCE_ZEROING]).get()
        BigDecimal confirmedFraudTotalDebitValue = Debit.sumValue([customer: customer, type: DebitType.CONFIRMED_FRAUD_BALANCE_ZEROING, done: true]).get()

        BigDecimal remainingConfirmedFraudCreditValue = confirmedFraudTotalCreditValue.abs() - confirmedFraudTotalDebitValue.abs()
        if (remainingConfirmedFraudCreditValue <= 0) return

        Boolean isPartialReversal = currentBalanceWithoutPartnerSettlement < remainingConfirmedFraudCreditValue
        BigDecimal debitValue = isPartialReversal ? currentBalanceWithoutPartnerSettlement : remainingConfirmedFraudCreditValue

        saveDebit(customer, debitValue, DebitType.CONFIRMED_FRAUD_BALANCE_ZEROING)
    }

    public void appropriatePositiveBalanceAfterOneYearOfConfirmedFraudDisable() {
        Date oneYearAgo = CustomDateUtils.sumDays(new Date(), -365)
        List<Long> customerIdList = CustomerDisabledReason.query([column: "customer.id", disabledReason: DisabledReason.CONFIRMED_FRAUD, "dateCreated[ge]": oneYearAgo, "dateCreated[le]": CustomDateUtils.setTimeToEndOfDay(oneYearAgo)]).list()

        final Integer flushEvery = 50
        final Integer batchSize = 50
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerIdList, batchSize, flushEvery, { Long customerId ->
            Customer customer = Customer.read(customerId)

            BigDecimal currentBalanceWithoutPartnerSettlement = financialTransactionService.getCurrentBalanceWithoutPartnerSettlement(customer)
            if (currentBalanceWithoutPartnerSettlement <= 0) return

            saveDebit(customer, currentBalanceWithoutPartnerSettlement, DebitType.CONFIRMED_FRAUD_POSITIVE_BALANCE_APPROPRIATION)
        }, [logErrorMessage: "ConfirmedFraudService.appropriatePositiveBalanceAfterOneYearOfConfirmedFraudDisable >> Falha ao apropriar o saldo de clientes desabilitados por fraude confirmada",
            appendBatchToLogErrorMessage: true])
    }

    public void appropriatePositiveBalanceManually(Customer customer) {
        Boolean isCustomerDisabledByConfirmedFraud = CustomerDisabledReason.query([customer: customer, disabledReason: DisabledReason.CONFIRMED_FRAUD]).get().asBoolean()
        if (!isCustomerDisabledByConfirmedFraud) throw new BusinessException("Cliente ${customer.id} não está desabilitado por fraude confirmada.")

        BigDecimal currentBalanceWithoutPartnerSettlement = financialTransactionService.getCurrentBalanceWithoutPartnerSettlement(customer)
        if (currentBalanceWithoutPartnerSettlement <= 0) throw new BusinessException("Cliente ${customer.id} não possui saldo positivo.")

        saveDebit(customer, currentBalanceWithoutPartnerSettlement, DebitType.CONFIRMED_FRAUD_POSITIVE_BALANCE_APPROPRIATION)
    }

    private void saveCredit(Long customerId, BigDecimal value) {
        creditService.save(
            customerId,
            CreditType.CONFIRMED_FRAUD_BALANCE_ZEROING,
            Utils.getMessageProperty("com.asaas.credit.CreditType.${CreditType.CONFIRMED_FRAUD_BALANCE_ZEROING.toString()}"),
            BigDecimalUtils.abs(value),
            null
        )
    }

    private void saveDebit(Customer customer, BigDecimal value, DebitType debitType) {
        debitService.save(
            customer,
            BigDecimalUtils.abs(value),
            debitType,
            Utils.getMessageProperty("com.asaas.debit.DebitType.${debitType.toString()}"),
            null
        )
    }
}

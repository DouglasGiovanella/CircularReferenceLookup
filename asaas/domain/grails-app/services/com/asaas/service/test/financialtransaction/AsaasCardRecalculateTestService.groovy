package com.asaas.service.test.financialtransaction

import com.asaas.asaascard.AsaasCardBrand
import com.asaas.asaascard.AsaasCardType
import com.asaas.asaascardbillpayment.AsaasCardBillPaymentMethod
import com.asaas.asaascardtransaction.AsaasCardTransactionType
import com.asaas.chargedfee.ChargedFeeType
import com.asaas.credit.CreditType
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascard.AsaasCardBalanceRefund
import com.asaas.domain.asaascard.AsaasCardCashback
import com.asaas.domain.asaascard.AsaasCardRecharge
import com.asaas.domain.asaascard.AsaasCardTransactionSettlement
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPaymentRefund
import com.asaas.domain.asaascardtransaction.AsaasCardTransaction
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.credit.Credit
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerParameter
import com.asaas.integration.bifrost.BifrostEnabledManager
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillPaymentAdapter
import com.asaas.integration.bifrost.adapter.asaascardcashback.AsaasCardCashbackAdapter
import com.asaas.integration.bifrost.adapter.asaascardtransaction.SaveAsaasCardTransactionRequestAdapter
import com.asaas.integration.bifrost.adapter.asaascardtransaction.SaveAsaasCardTransactionResponseAdapter
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

class AsaasCardRecalculateTestService {

    public static final BigDecimal ASAAS_CARD_TRANSACTIONS_VALUE = 10.00
    private static final BigDecimal ASAAS_CARD_PARTIAL_REFUND_VALUE = 5.00
    private static final BigDecimal NECESSARY_BALANCE_FOR_TEST = 60.00

    def asaasCardBalanceRefundService
    def asaasCardBillPaymentService
    def asaasCardBillPaymentRefundService
    def asaasCardHolderService
    def asaasCardCashbackService
    def asaasCardService
    def asaasCardRechargeService
    def asaasCardTransactionService
    def chargedFeeService
    def creditService
    def customerFeatureService
    def customerParameterCacheService

    public BigDecimal asaasCardDebitsAndCredits(Customer customer) {
        enableAsaasCards(customer)
        softDeleteCurrentCards(customer)

        BifrostEnabledManager bifrostEnabledManager = new BifrostEnabledManager()
        Boolean bifrostEnabledAtStartUp = bifrostEnabledManager.getBifrostEnabled()
        BigDecimal balance = 0

        Credit credit = creditService.save(customer.id, CreditType.MANUAL_INTERNAL_TRANSFER, "Crédito mínimo para teste de recalculo de cartões", NECESSARY_BALANCE_FOR_TEST, new Date())
        balance += credit.value

        if (bifrostEnabledAtStartUp) bifrostEnabledManager.disableBifrost()
        balance += saveAsaasPrepaidCardTransactions(customer)
        balance += saveAsaasDebitCardTransactions(customer)
        balance += saveAsaasCreditCardTransactions(customer)
        if (bifrostEnabledAtStartUp) bifrostEnabledManager.enableBifrost()

        return balance
    }

    private BigDecimal saveAsaasDebitCardTransactions(Customer customer) {
        BigDecimal balance = 0

        AsaasCard asaasCard = createAndActivateAsaasCard(customer, AsaasCardType.DEBIT)
        balance -= saveRequestFeeIfNecessary(asaasCard)

        AsaasCardCashback asaasCardCashback = asaasCardCashbackService.save(new AsaasCardCashbackAdapter(asaasCard, AsaasCardRecalculateTestService.ASAAS_CARD_TRANSACTIONS_VALUE, Utils.generateRandomFourDigitsLong()))
        balance += asaasCardCashback.value

        AsaasCardTransaction purchase1 = saveAsaasCardTransactionDebit(asaasCard, AsaasCardTransactionType.PURCHASE)
        balance -= purchase1.value

        AsaasCardTransaction purchase2 = saveAsaasCardTransactionDebit(asaasCard, AsaasCardTransactionType.PURCHASE)
        balance -= purchase2.value

        AsaasCardTransaction withdrawal = saveAsaasCardTransactionDebit(asaasCard, AsaasCardTransactionType.WITHDRAWAL)
        balance -= withdrawal.value

        AsaasCardTransaction purchaseRefund = saveAsaasCardTransactionRefund(purchase1, asaasCard, AsaasCardRecalculateTestService.ASAAS_CARD_TRANSACTIONS_VALUE)
        balance -= purchaseRefund.value

        AsaasCardTransaction purchasePartialRefund = saveAsaasCardTransactionRefund(purchase2, asaasCard, AsaasCardRecalculateTestService.ASAAS_CARD_PARTIAL_REFUND_VALUE)
        balance -= purchasePartialRefund.value

        AsaasCardTransaction withdrawalRefund = saveAsaasCardTransactionRefund(withdrawal, asaasCard, AsaasCardRecalculateTestService.ASAAS_CARD_TRANSACTIONS_VALUE)
        balance -= withdrawalRefund.value

        balance -= cancelAsaasCardTransactionRefund(asaasCard, purchaseRefund)
        balance -= cancelAsaasCardTransactionRefund(asaasCard, purchasePartialRefund)

        return balance
    }

    private AsaasCardTransaction saveAsaasCardTransactionDebit(AsaasCard asaasCard, AsaasCardTransactionType type) {
        BigDecimal fee = type.isWithdrawal() ? AsaasCard.ELO_CARD_NATIONAL_CASH_WITHDRAWAL_FEE : 0.00
        SaveAsaasCardTransactionRequestAdapter requestAdapter = buildSaveAsaasCardTransactionDebitRequestAdapter(asaasCard, type, fee)
        SaveAsaasCardTransactionResponseAdapter debit = asaasCardTransactionService.saveDebit(requestAdapter)
        addSettlementToAsaasCardTransaction(debit.asaasCardTransaction)
        return debit.asaasCardTransaction
    }

    private AsaasCardTransaction saveAsaasCardTransactionRefund(AsaasCardTransaction transaction, AsaasCard asaasCard, BigDecimal value) {
        SaveAsaasCardTransactionRequestAdapter requestAdapter = buildSaveAsaasCardTransactionRefundRequestAdapter(asaasCard, transaction, value)
        SaveAsaasCardTransactionResponseAdapter asaasCardTransactionResponseAdapter = asaasCardTransactionService.saveRefund(requestAdapter)
        addSettlementToAsaasCardTransaction(asaasCardTransactionResponseAdapter.asaasCardTransaction)
        return asaasCardTransactionResponseAdapter.asaasCardTransaction
    }

    private BigDecimal cancelAsaasCardTransactionRefund(AsaasCard asaasCard, AsaasCardTransaction refund) {
        return asaasCardTransactionService.saveRefundCancellation(asaasCard, Utils.generateRandomFourDigitsLong(), refund.id).asaasCardTransaction.value
    }

    private SaveAsaasCardTransactionRequestAdapter buildSaveAsaasCardTransactionDebitRequestAdapter(AsaasCard asaasCard, AsaasCardTransactionType type, BigDecimal fee) {
        SaveAsaasCardTransactionRequestAdapter requestAdapter = new SaveAsaasCardTransactionRequestAdapter(asaasCard)
        requestAdapter.externalId = Utils.generateRandomFourDigitsLong()
        requestAdapter.type = type
        requestAdapter.fee = fee
        requestAdapter.totalAmount = AsaasCardRecalculateTestService.ASAAS_CARD_TRANSACTIONS_VALUE
        requestAdapter.dollarAmount = 0.00
        requestAdapter.international = false
        requestAdapter.forceAuthorization = false
        requestAdapter.establishmentName = ""
        requestAdapter.transactionFeeList = []

        return requestAdapter
    }

    private SaveAsaasCardTransactionRequestAdapter buildSaveAsaasCardTransactionRefundRequestAdapter(AsaasCard asaasCard, AsaasCardTransaction transaction, BigDecimal value) {
        SaveAsaasCardTransactionRequestAdapter requestAdapter = new SaveAsaasCardTransactionRequestAdapter(asaasCard)
        requestAdapter.externalId = Utils.generateRandomFourDigitsLong()
        requestAdapter.type = transaction.type.getRefundType()
        requestAdapter.fee = transaction.fee ? BigDecimalUtils.negate(transaction.fee) : transaction.fee
        requestAdapter.totalAmount = BigDecimalUtils.negate(value)
        requestAdapter.transactionOriginId = transaction.id

        return requestAdapter
    }

    private void addSettlementToAsaasCardTransaction(AsaasCardTransaction transaction) {
        transaction.asaasCardTransactionSettlement = AsaasCardTransactionSettlement.query([asaasCardTransactionId: transaction.id]).get()
        transaction.save(failOnError: true)
    }

    private BigDecimal saveAsaasCreditCardTransactions(Customer customer) {
        BigDecimal balance = 0

        AsaasCard asaasCard = getMigratedCreditCard(customer)

        AsaasCardBillPayment asaasCardBillPayment = asaasCardBillPaymentService.save(asaasCard, AsaasCardBillPaymentMethod.MANUAL_ACCOUNT_BALANCE_DEBIT, null, new AsaasCardBillPaymentAdapter(asaasCard.id, Utils.generateRandomFourDigitsLong(), AsaasCardRecalculateTestService.ASAAS_CARD_TRANSACTIONS_VALUE))
        balance -= asaasCardBillPayment.value

        AsaasCardBillPaymentRefund asaasCardBillPaymentRefund = asaasCardBillPaymentRefundService.save(asaasCardBillPayment)
        balance += asaasCardBillPaymentRefund.value

        return balance
    }

    private AsaasCard getMigratedCreditCard(Customer customer) {
        AsaasCard asaasCard = AsaasCard.query([customer: customer, type: AsaasCardType.DEBIT]).get()
        asaasCard.type = AsaasCardType.CREDIT
        asaasCard.save(failOnError: true)

        return asaasCard
    }

    private BigDecimal saveAsaasPrepaidCardTransactions(Customer customer) {
        BigDecimal balance = 0

        AsaasCard asaasCard = createAndActivateAsaasCard(customer, AsaasCardType.PREPAID)
        balance -= saveRequestFeeIfNecessary(asaasCard)

        AsaasCardRecharge asaasCardRecharge = asaasCardRechargeService.save(asaasCard, AsaasCardRecalculateTestService.ASAAS_CARD_TRANSACTIONS_VALUE)
        balance -= asaasCardRecharge.value

        AsaasCardBalanceRefund asaasCardBalanceRefund = asaasCardBalanceRefundService.refundBalanceToAsaasAccount(asaasCard)
        balance += asaasCardBalanceRefund.amount

        return balance
    }

    private AsaasCard createAndActivateAsaasCard(Customer customer, AsaasCardType asaasCardType) {
        AsaasCard asaasCard = asaasCardService.save(customer, buildAsaasCardParams(customer, asaasCardType))
        if (asaasCard.hasErrors()) throw new RuntimeException(DomainUtils.getFirstValidationMessage(asaasCard))

        return asaasCardService.activatePaysmartElo(asaasCard, [:])
    }

    private Map buildAsaasCardParams(Customer customer, AsaasCardType type) {
        Map params = asaasCardHolderService.getHolderInfoIfNecessary(customer)
        params.type = type
        params.brand = AsaasCardBrand.ELO
        if (customer.isLegalPerson()) params.birthDate = customer.dateCreated

        return params
    }

    private BigDecimal saveRequestFeeIfNecessary(AsaasCard asaasCard) {
        ChargedFee requestFee = ChargedFee.query(["object": asaasCard, "type": asaasCard.type.isPrepaid() ? ChargedFeeType.ASAAS_PREPAID_CARD_REQUEST : ChargedFeeType.ASAAS_DEBIT_CARD_REQUEST]).get()
        if (!requestFee) requestFee = chargedFeeService.saveAsaasCardRequestFee(asaasCard.customer, asaasCard, ChargedFee.ELO_CARD_REQUEST_FEE)

        return requestFee.value
    }

    private void enableAsaasCards(Customer customer) {
        CustomerFeature customerFeature = CustomerFeature.query([customerId: customer.id]).get()
        if (!customerFeature) throw new RuntimeException("É necessário informar um customer válido para o teste de recalculo")

        if (!customerFeature.asaasCardElo) customerFeatureService.toggleAsaasCardElo(customer.id, true)

        if (customerParameterCacheService.getValue(customer.id, CustomerParameterName.DISABLE_ASAAS_CARD)) {
            CustomerParameter customerParameter = CustomerParameter.query([customer: customer, name: CustomerParameterName.DISABLE_ASAAS_CARD]).get()
            customerParameter.value = false
            customerParameter.save(failOnError: true)
        }
    }

    private void softDeleteCurrentCards(Customer customer) {
        List<AsaasCard> asaasCardList = AsaasCard.query([customer: customer]).list()
        asaasCardList.each {
            it.deleted = true
            it.save(failOnError: true)
        }
    }
}

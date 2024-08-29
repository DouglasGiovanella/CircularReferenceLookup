package com.asaas.service.asaascardbillpayment

import com.asaas.asaascardbillpayment.AsaasCardBillPaymentMethod
import com.asaas.criticalaction.CriticalActionType
import com.asaas.domain.asaascard.AsaasCard
import com.asaas.domain.asaascardbillpayment.AsaasCardBillPayment
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.payment.Payment
import com.asaas.exception.AsaasCardNotFoundException
import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.asaascardbill.AsaasCardBillPaymentAdapter
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class AsaasCardBillPaymentService {

    def asaasCardNotificationService
    def asaasCardService
    def bifrostCardBillManagerService
    def criticalActionService
    def financialTransactionService

    public CriticalActionGroup savePayBillCriticalActionGroup(AsaasCard asaasCard) {
        String hash = buildBillPaymentCriticalActionHash(asaasCard.id, asaasCard.customer.id)
        String authorizationMessage = "o código para pagar sua fatura do cartão ${asaasCard.getFormattedName()} é"

        return criticalActionService.saveAndSendSynchronous(asaasCard.customer, CriticalActionType.ASAAS_CARD_PAY_BILL, hash, authorizationMessage)
    }

    public void saveManualDebit(AsaasCard asaasCard, BigDecimal value, Long groupId, String token) {
        BigDecimal finalBalance = FinancialTransaction.getCustomerBalance(asaasCard.customer)
        if (finalBalance < value) throw new BusinessException("Não é possível realizar o pagamento pois o saldo em conta é inferior ao valor à ser pago.")

        String hash = buildBillPaymentCriticalActionHash(asaasCard.id, asaasCard.customer.id)
        BusinessValidation businessValidation = criticalActionService.authorizeSynchronousWithNewTransaction(asaasCard.customer.id, groupId, token, CriticalActionType.ASAAS_CARD_PAY_BILL, hash)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        savePayment(asaasCard, value, AsaasCardBillPaymentMethod.MANUAL_ACCOUNT_BALANCE_DEBIT)
    }

    public void savePayment(AsaasCard asaasCard, BigDecimal value, AsaasCardBillPaymentMethod method) {
        savePayment(asaasCard, value, method, null)
    }

    public void savePayment(AsaasCard asaasCard, BigDecimal value, AsaasCardBillPaymentMethod method, Payment payment) {
        Long billPaymentId = bifrostCardBillManagerService.savePayment(asaasCard, value)
        AsaasCardBillPayment asaasCardBillPayment = save(asaasCard, method, payment, new AsaasCardBillPaymentAdapter(asaasCard.id, billPaymentId, value))
        asaasCardNotificationService.notifyAsaasCardBillPaymentReceived(asaasCardBillPayment)
    }

    public AsaasCardBillPayment saveAutomaticDebit(AsaasCardBillPaymentAdapter asaasCardBillPaymentAdapter) {
        AsaasCard asaasCard = AsaasCard.get(asaasCardBillPaymentAdapter.asaasCardId)
        if (!asaasCard) throw new AsaasCardNotFoundException(asaasCardBillPaymentAdapter.asaasCardId.toString(), "Não foi possível localizar o cartão [id: ${asaasCardBillPaymentAdapter.asaasCardId}].")

        BigDecimal totalBalance = FinancialTransaction.getCustomerBalance(asaasCard.customer)
        if (totalBalance < asaasCardBillPaymentAdapter.value) throw new RuntimeException("Valor insuficiente para pagamento.")

        AsaasCardBillPayment asaasCardBillPayment = save(asaasCard, AsaasCardBillPaymentMethod.AUTOMATIC_ACCOUNT_BALANCE_DEBIT, null, asaasCardBillPaymentAdapter)

        if (!asaasCardBillPaymentAdapter.isPartialPayment) {
            asaasCardNotificationService.notifyAsaasCardBillPaidByAutomaticDebit(asaasCardBillPayment, asaasCardBillPaymentAdapter.billId, asaasCardBillPaymentAdapter.dueDate)
        } else {
            asaasCardNotificationService.notifyAsaasCardBillPaymentReceived(asaasCardBillPayment)
        }

        return asaasCardBillPayment
    }

    public AsaasCardBillPayment save(AsaasCard asaasCard, AsaasCardBillPaymentMethod method, Payment payment, AsaasCardBillPaymentAdapter asaasCardBillPaymentAdapter) {
        validateToSavePayment(asaasCard, method, asaasCardBillPaymentAdapter)

        AsaasCardBillPayment asaasCardBillPayment = new AsaasCardBillPayment()
        asaasCardBillPayment.asaasCard = asaasCard
        asaasCardBillPayment.customer = asaasCard.customer
        asaasCardBillPayment.externalId = asaasCardBillPaymentAdapter.externalId
        asaasCardBillPayment.value = asaasCardBillPaymentAdapter.value
        asaasCardBillPayment.method = method
        asaasCardBillPayment.payment = payment
        asaasCardBillPayment.save(failOnError: true)

        if (method.isBalanceDebit()) financialTransactionService.saveAsaasCardBillPayment(asaasCardBillPayment)

        return asaasCardBillPayment
    }

    private String buildBillPaymentCriticalActionHash(Long asaasCardId, Long customerId) {
        String operation = ""
        operation += asaasCardId.toString()
        operation += customerId.toString()

        if (!operation) throw new RuntimeException("Operação não suportada!")
        return operation.encodeAsMD5()
    }

    private void validateToSavePayment(AsaasCard asaasCard, AsaasCardBillPaymentMethod method, AsaasCardBillPaymentAdapter asaasCardBillPaymentAdapter) {
        if (!asaasCard.type.isCredit()) throw new RuntimeException("O tipo de cartão não permite que seja gerado pagamento de fatura.")
        if (!method) throw new RuntimeException("É necessário informar o método de pagamento de fatura.")
        if (asaasCardBillPaymentAdapter.value < AsaasCardBillPayment.MINIMUM_VALUE) throw new RuntimeException("Valor inválido para pagamento")
    }
}

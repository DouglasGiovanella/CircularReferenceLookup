package com.asaas.service.payment

import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.domain.ted.TedTransaction
import com.asaas.domain.ted.TedTransactionExternalAccount
import com.asaas.pix.adapter.transaction.credit.CreditAdapter
import com.asaas.utils.StringUtils
import grails.transaction.Transactional

@Transactional
class ReceivedPaymentCreationService {

    def customerAccountService
    def paymentService

    public Payment saveFromPixTransaction(CreditAdapter creditAdapter) {
        String description = "Cobrança gerada automaticamente a partir de Pix recebido."
        if (creditAdapter.message?.trim()) description += " Mensagem: ${creditAdapter.message.trim()}"

        Map paymentDataMap = [
            billingType: BillingType.PIX,
            pixOriginType: creditAdapter.originType,
            value: creditAdapter.value,
            automaticRoutine: true,
            onReceiving: true,
            interest: [value: 0],
            fine: [value: 0],
            description: description,
            dueDate: new Date().clearTime(),
            externalReference: creditAdapter.externalReference
        ]

        if (creditAdapter.payment) {
            paymentDataMap.duplicatedPayment = creditAdapter.payment
            paymentDataMap.customerAccount = creditAdapter.payment.customerAccount
            paymentDataMap.dueDate = creditAdapter.payment.dueDate

            return paymentService.save(paymentDataMap, true, false)
        }

        if (!creditAdapter.payer?.cpfCnpj) throw new RuntimeException("CPF/CNPJ do pagador não encontrado para transação Pix ExternalId ${creditAdapter.externalIdentifier} para o cliente ID ${creditAdapter.customer.id}")

        paymentDataMap.customerAccount = CustomerAccount.query([customerId: creditAdapter.customer.id, cpfCnpj: creditAdapter.payer.cpfCnpj]).get()
        if (!paymentDataMap.customerAccount) paymentDataMap.customerAccount = createCustomerAccount(creditAdapter.customer, creditAdapter.payer.name, creditAdapter.payer.cpfCnpj)

        return paymentService.save(paymentDataMap, true, false)
    }

    public Payment saveFromTedTransaction(TedTransaction tedTransaction) {
        Map payerDataMap = TedTransactionExternalAccount.query([columnList: ["name", "cpfCnpj"], tedTransaction: tedTransaction]).get() as Map

        if (!payerDataMap.cpfCnpj) throw new RuntimeException("CPF/CNPJ do pagador não encontrado para transação TED ID ${tedTransaction.id} para o cliente ID ${tedTransaction.customer.id}")

        CustomerAccount customerAccount = CustomerAccount.query([customerId: tedTransaction.customer.id, cpfCnpj: payerDataMap.cpfCnpj]).get()
        if (!customerAccount) customerAccount = createCustomerAccount(tedTransaction.customer, payerDataMap.name.toString(), payerDataMap.cpfCnpj.toString())

        Map paymentDataMap = [
            customerAccount: customerAccount,
            billingType: BillingType.TRANSFER,
            value: tedTransaction.value,
            description: "Cobrança gerada automaticamente a partir de TED recebido.",
            automaticRoutine: true,
            onReceiving: true,
            dueDate: new Date().clearTime()
        ]

        return paymentService.save(paymentDataMap, true, false)
    }

    private CustomerAccount createCustomerAccount(Customer customer, String payerName, String payerCpfCnpj) {
        Map customerAccountDataMap = [
            cpfCnpj: payerCpfCnpj,
            name: StringUtils.capitalizeNames(payerName),
            automaticRoutine: true
        ]

        CustomerAccount customerAccount = customerAccountService.save(customer, null, customerAccountDataMap)
        customerAccountService.createNotificationsForCustomerAccount(customerAccount)

        return customerAccount
    }
}

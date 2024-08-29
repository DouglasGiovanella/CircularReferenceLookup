package com.asaas.service.sandbox

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.domain.bank.Bank
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentserviceprovider.PaymentServiceProvider
import com.asaas.domain.pix.PixTransaction
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.payment.PaymentUtils
import com.asaas.pix.PixAccountType
import com.asaas.pix.PixTransactionOriginType
import com.asaas.pix.PixTransactionRefundReason
import com.asaas.pix.adapter.transaction.credit.CreditAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PaymentSandboxService {

    def overduePaymentService
    def paymentService
    def paymentConfirmRequestService
    def paymentCreditCardService
    def paymentRefundService
    def pixCreditService

    public Payment confirmPaymentWithMockedData(Long paymentId, Long customerId) {
        if (AsaasEnvironment.isProduction()) throw new RuntimeException("Operação exclusiva para testes em Sandbox!")

        Payment payment = paymentService.findPayment(paymentId, customerId)

        if (payment.billingType.isPix()) {
            confirmPixWithMockedData(payment)
        } else if (payment.billingType.isCreditCard()) {
            confirmCreditCardWithMockedData(payment)
        } else {
            confirmBankSlipWithMockedData(payment)
        }

        return payment
    }

    public Payment overduePaymentWithMockedData(Payment payment) {
        if (AsaasEnvironment.isProduction()) throw new RuntimeException("Operação exclusiva para testes em Sandbox!")

        if (!payment.status.isPending()) throw new BusinessException("Esta cobrança não pode ser alterada para vencida")

        payment.dueDate = PaymentUtils.getToleranceDate()
        payment.ignoreDueDateValidator = true
        payment.save(flush: true, failOnError: true)

        overduePaymentService.processPayment(payment.id)

        return payment
    }

    public Payment receiveCreditCardWithMockedData(Long paymentId, Long customerId) {
        if (AsaasEnvironment.isProduction()) throw new RuntimeException("Operação exclusiva para testes em Sandbox!")

        Payment payment = paymentService.findPayment(paymentId, customerId)

        if (!payment.status.isConfirmed()) {
            throw new BusinessException("Esta cobrança não pode ser recebida porque ainda não foi confirmada.")
        }

        payment.creditDate = new Date().clearTime()
        return paymentCreditCardService.executePaymentCredit(payment)
    }

    public Payment refundPixCredit(Long paymentId, Customer customer) {
        if (AsaasEnvironment.isProduction()) throw new RuntimeException("Não é possível executar a operação PaymentSandboxService.refundPixCredit no ambiente de produção")

        Payment payment = paymentService.findPayment(paymentId, customer.id)
        if (!payment) throw new BusinessException("Cobrança não encontrada.")

        Map refundOptions = [:]
        refundOptions.reason = PixTransactionRefundReason.REQUESTED_BY_RECEIVER
        refundOptions.description = "Simulação de estorno de cŕedito Pix em Sandbox"
        refundOptions.bypassCustomerValidation = true

        if (!validateRefundPixCredit(payment, refundOptions)) throw new BusinessException(payment.refundDisabledReason ?: "Não é possível estornar esta cobrança.")

        return paymentRefundService.executeRefundRequestedByProvider(paymentId, customer, refundOptions)
    }

    public Boolean validateRefundPixCredit(Payment payment, Map options) {
        if (!payment.billingType.isPix()) return false

        payment = paymentRefundService.validateRefundRequestedByProvider(payment, options)
        if (payment.hasErrors()) return false

        return true
    }

    private void confirmPixWithMockedData(Payment payment) {
        final String agencyTest = "1"
        final String accountTest = "11111111"

        Map creditInfo = [:]
        creditInfo.value = payment.value
        creditInfo.message = "Confirmação manual"
        creditInfo.externalIdentifier = UUID.randomUUID().toString()
        creditInfo.endToEndIdentifier = UUID.randomUUID().toString()
        creditInfo.conciliationIdentifier = UUID.randomUUID().toString()
        creditInfo.originType = PixTransactionOriginType.DYNAMIC_QRCODE

        Map payerInfo = [:]
        payerInfo.ispb = Long.valueOf(AsaasApplicationHolder.getConfig().asaas.ispb)
        payerInfo.ispbName = PaymentServiceProvider.findCorporateNameByIspb(PixUtils.parseIspb(payerInfo.ispb))
        payerInfo.name = payment.customerAccount.name
        payerInfo.cpfCnpj = payment.customerAccount.cpfCnpj
        payerInfo.agency = agencyTest
        payerInfo.account = accountTest
        payerInfo.accountType = PixAccountType.CHECKING_ACCOUNT
        payerInfo.personType = payment.customerAccount.personType

        creditInfo.externalAccount = payerInfo

        CreditAdapter creditAdapter = new CreditAdapter(creditInfo)
        creditAdapter.customer = payment.provider
        creditAdapter.payment = payment
        creditAdapter.receivedWithAsaasQrCode = false

        PixTransaction transaction = pixCreditService.save(creditAdapter)

        if (transaction.hasErrors()) throw new BusinessException(Utils.getMessageProperty("payment.message.NOTCONFIRMED"))
    }

    private void confirmCreditCardWithMockedData(Payment payment) {
        CustomerAccount customerAccount = payment.customerAccount

        Map creditCard = [:]
        creditCard.holderName = customerAccount.name
        creditCard.number = "4988 4388 4388 4305"
        creditCard.expiryMonth = "03"
        creditCard.expiryYear = "2030"
        creditCard.ccv = "737"

        Map holderInfo = [:]
        String phone = (customerAccount.mobilePhone ?: customerAccount.phone) ?: "0000000000"
        holderInfo.name = customerAccount.name
        holderInfo.email = customerAccount.email
        holderInfo.cpfCnpj = customerAccount.cpfCnpj
        holderInfo.phone = Utils.extractNumberWithoutDDD(phone)
        holderInfo.phoneDDD = Utils.retrieveDDD(phone)
        holderInfo.postalCode = customerAccount.postalCode ?: AsaasApplicationHolder.config.asaas.company.postalCode
        holderInfo.address = customerAccount.address ?: AsaasApplicationHolder.config.asaas.company.address
        holderInfo.addressNumber = customerAccount.addressNumber ?: AsaasApplicationHolder.config.asaas.company.addressNumber
        holderInfo.city = customerAccount.getCityNameAndState().name ?: AsaasApplicationHolder.config.asaas.company.city
        holderInfo.uf = customerAccount.getCityNameAndState().state ?: AsaasApplicationHolder.config.asaas.company.state
        holderInfo.country = "Brasil"

        Map paymentInfo = [:]
        paymentInfo.creditCard = creditCard
        paymentInfo.creditCardHolderInfo = holderInfo

        paymentService.processCreditCardPayment(payment, paymentInfo)
    }

    private void confirmBankSlipWithMockedData(Payment payment) {
        List paymentInfoList = []
        Map paymentInfo = [:]

        paymentInfo.id = payment.id
        paymentInfo.nossoNumero = payment.nossoNumero
        paymentInfo.value = FormUtils.formatCurrencyWithoutMonetarySymbol(payment.value)
        paymentInfo.creditDate = new Date().clearTime()
        paymentInfo.date = CustomDateUtils.fromDate(new Date().clearTime())
        paymentInfo.bank = Bank.ASAAS_BANK_CODE
        paymentInfoList.add(paymentInfo)

        paymentConfirmRequestService.receive(BillingType.BOLETO, Bank.findByCode(Bank.ASAAS_BANK_CODE), paymentInfoList)
    }
}

package com.asaas.service.test.payment

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.UnitTestUtils
import grails.transaction.Transactional

@Transactional
class ApiCreateCreditCardPaymentTestService {

    def apiPaymentService

    public Boolean runTests() {
        try {
            transactionStatus.setRollbackOnly()

            Customer customer = Customer.get(AsaasApplicationHolder.getConfig().asaas.test.recalculateBalance.customer.id)
            if (!customer) throw new RuntimeException("Não foi encontrado o customer informado para os testes de recálculo")

            executePaymentCreationTests(customer)
            return true
        } catch (Exception exception) {
            String warningMessage = """
                ******************************
                           ATENCAO
                ******************************
                *
                Chame o time responsavel pela feature que quebrou e ajuste seu ambiente local.
            """.stripIndent()

            AsaasLogger.error("ApiCreateCreditCardPaymentTestService.runTests >> Erro na criação de Payment.\n \n${warningMessage}\n \n", exception)
            return false
        }
    }

    private void executePaymentCreationTests(Customer customer) {
        UnitTestUtils.bindMockWebRequestWithProviderParam(customer)
        String currentTest
        try {
            currentTest = "Cadastro de Cobrança com cartão de crédito"
            createTokenizedCreditCardPaymentTest()
        } catch (Exception exception) {
            throw new RuntimeException("Falha no teste de ${currentTest}", exception)
        }
    }

    private void createTokenizedCreditCardPaymentTest() {
        Map paymentCreitCardData = [
            customer: [
                name: "Teste Novo Pagador",
                cpfCnpj: "92593962046",
                email: "testenovopagador@asaas.com",
                phone: "49999009999",
                mobilePhone: "49999009999",
                postalCode: "89223-005",
                addressNumber: "277",
                addressComplement: "Complemento de endereço"
            ],
            billingType: BillingType.CREDIT_CARD.toString(),
            value: 100.00,
            dueDate: CustomDateUtils.fromDate(CustomDateUtils.tomorrow(), "dd/MM/yyyy"),
            description: "Teste cobrança com info de cartao de credito",
            remoteIp: "127.0.0.1",
            creditCard: [
                holderName: "Teste Novo Pagador",
                number: "5162306219378829",
                expiryMonth: CustomDateUtils.fromDate(new Date(), "MM"),
                expiryYear: CustomDateUtils.fromDate(new Date(), "yyyy"),
                ccv: "318"
            ],
            creditCardHolderInfo: [
                name: "Teste Novo Pagador",
                cpfCnpj: "92593962046",
                email: "testenovopagador@asaas.com",
                phone: "49999009999",
                mobilePhone: "49999009999",
                postalCode: "89223-005",
                addressNumber: "277",
                addressComplement: "Complemento de endereço"
            ]
        ]

        apiPaymentService.save(paymentCreitCardData)
    }
}

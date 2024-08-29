package com.asaas.service.test.payment

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.billinginfo.ChargeType
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.receipttype.ReceiptType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.UnitTestUtils
import com.asaas.wizard.WizardSendType
import grails.transaction.Transactional

@Transactional
class ApiCreatePaymentWizardTestService {

    def apiPaymentWizardService

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

            AsaasLogger.error("ApiCreatePaymentWizardTestService.runTests >> Erro na criação de Payment.\n \n${warningMessage}\n \n", exception)
            return false
        }
    }

    private void executePaymentCreationTests(Customer customer) {
        UnitTestUtils.bindMockWebRequestWithProviderParam(customer)
        String currentTest
        try {
            currentTest = "Wizard de Cobranças com novo CustomerAccount"
            createWizardPaymentAndCustomerAccountTest()
        } catch (Exception exception) {
            throw new RuntimeException("Falha no teste de ${currentTest}", exception)
        }
    }

    private void createWizardPaymentAndCustomerAccountTest() {
        Map paymentWizardData = [
            value: 100.00,
            description: "Teste cobrança avulsa no boleto",
            billingType: BillingType.BOLETO.toString(),
            chargeType: ChargeType.DETACHED.toString(),
            dueDate: CustomDateUtils.fromDate(CustomDateUtils.tomorrow(), "dd/MM/yyyy"),
            receiptType: ReceiptType.NOT_ANTICIPATION.toString(),
            sendTypes: [WizardSendType.EMAIL.toString(), WizardSendType.SMS.toString(), WizardSendType.WHATSAPP.toString()],
            customer: [
                name: "Teste Novo Pagador",
                cpfCnpj: "92707489018",
                email: "testenovopagador@asaas.com",
                phone: "49999009999",
                mobilePhone: "49999009999",
                postalCode: "89223-005",
                addressNumber: "277",
                addressComplement: "Complemento de endereço"
            ]
        ]

        apiPaymentWizardService.save(paymentWizardData)
    }
}

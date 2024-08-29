package com.asaas.service.test.customeraccount

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.utils.UnitTestUtils
import grails.transaction.Transactional

@Transactional
class ApiCreateCustomerAccountTestService {

    def apiCustomerAccountService

    public Boolean runTests() {
        try {
            transactionStatus.setRollbackOnly()

            Customer customer = Customer.get(AsaasApplicationHolder.getConfig().asaas.test.recalculateBalance.customer.id)
            if (!customer) throw new RuntimeException("Não foi encontrado o customer informado para os testes de recálculo")

            executeCustomerAccountCreationTests(customer)
            return true
        } catch (Exception exception) {
            String warningMessage = """
                ******************************
                           ATENCAO
                ******************************
                *
                Chame o time responsavel pela feature que quebrou e ajuste seu ambiente local.
            """.stripIndent()

            AsaasLogger.error("ApiCreateCustomerAccountTestService.runTests >> Erro na criação de CustomerAccount.\n \n${warningMessage}\n \n", exception)
            return false
        }
    }

    private void executeCustomerAccountCreationTests(Customer customer) {
        UnitTestUtils.bindMockWebRequestWithProviderParam(customer)
        String currentTest
        try {
            currentTest = "Cadastro de CustomerAccount"
            createCustomerAccountTest()
        } catch (Exception exception) {
            throw new RuntimeException("Falha no teste de ${currentTest}", exception)
        }
    }

    private void createCustomerAccountTest() {
        Map mockDataMap = [
            name: "Teste Novo Pagador",
            cpfCnpj: "91346250030",
            email: "testenovopagador@asaas.com",
            phone: "49999009999",
            mobilePhone: "49999009999",
            postalCode: "89223-005",
            addressNumber: "277",
            addressComplement: "Complemento de endereço"
        ]

        Map resultMap = apiCustomerAccountService.save(mockDataMap)

        if (resultMap.containsKey("errors")) {
            throw new Exception("Erro de validação não esperado: ${resultMap.errors.toString()}")
        }
    }
}

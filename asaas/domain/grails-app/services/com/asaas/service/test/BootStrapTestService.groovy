package com.asaas.service.test

class BootStrapTestService {

    private final static Integer TEST_THREAD_SLEEP_MS = 3000

    def apiCreateCreditCardPaymentTestService
    def apiCreateCustomerAccountTestService
    def apiCreatePaymentWizardTestService
    def createCustomerTestService
    def recalculateTestService

    public void runTests() {
        Thread.start {
            Map testValidator = buildTestValidatorMap()

            while (true) {
                Thread.sleep(TEST_THREAD_SLEEP_MS)

                if (!testValidator.createCustomerTest) testValidator.createCustomerTest = createCustomerTestService.runTests()
                if (!testValidator.recalculateTest) testValidator.recalculateTest = recalculateTestService.runTests()
                if (!testValidator.apiCreateCustomerAccountTest) testValidator.apiCreateCustomerAccountTest = apiCreateCustomerAccountTestService.runTests()
                if (!testValidator.apiCreatePaymentWizardTest) testValidator.apiCreatePaymentWizardTest = apiCreatePaymentWizardTestService.runTests()
                if (!testValidator.apiCreateCreditCardPaymentTest) testValidator.apiCreateCreditCardPaymentTest = apiCreateCreditCardPaymentTestService.runTests()

                if (allTestsPassed(testValidator)) break
            }
        }
    }

    private Boolean allTestsPassed(Map testValidatorMap) {
        return !testValidatorMap.values().contains(false)
    }

    private Map buildTestValidatorMap() {
        return [
            createCustomerTest: false,
            apiCreateCustomerAccountTest: false,
            apiCreatePaymentWizardTest: false,
            apiCreateCreditCardPaymentTest: false,
            recalculateTest: false
        ]
    }
}

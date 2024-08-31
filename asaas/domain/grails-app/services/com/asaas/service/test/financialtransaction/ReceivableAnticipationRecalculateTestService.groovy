package com.asaas.service.test.financialtransaction

import com.asaas.billinginfo.BillingType
import com.asaas.chargedfee.ChargedFeeStatus
import com.asaas.chargedfee.ChargedFeeType
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigChangeOrigin
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationOriginRequesterInfoMethod
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.integration.acquiring.AcquiringEnabledManager
import com.asaas.receivableanticipation.ReceivableAnticipationDocumentVO
import com.asaas.receivableanticipation.ReceivableAnticipationFinancialInfoVO
import com.asaas.receivableanticipation.adapter.CreateReceivableAnticipationAdapter
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.apache.commons.fileupload.FileItem
import org.apache.commons.fileupload.disk.DiskFileItemFactory
import org.apache.commons.io.IOUtils
import org.apache.commons.lang.RandomStringUtils
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class ReceivableAnticipationRecalculateTestService {

    private static final BigDecimal PAYMENT_TEST_MINIMUM_VALUE = 15
    private static final BigDecimal PAYMENT_TEST_MAXIMUM_VALUE = 100

    def customerAccountService
    def customerPlanService
    def feeAdminService
    def mandatoryCustomerAccountNotificationService
    def paymentService
    def paymentSandboxService
    def receivableAnticipationFinancialInfoService
    def receivableAnticipationSandboxService
    def receivableAnticipationService

    public BigDecimal executeScenarios(Customer customer) {
        AcquiringEnabledManager acquiringEnabledManager = new AcquiringEnabledManager()
        Boolean acquiringEnabled = acquiringEnabledManager.isEnabled()
        if (acquiringEnabled) acquiringEnabledManager.disable()

        BigDecimal currentBalance = calculateCreditedValue(creditScenario(customer, BillingType.BOLETO, ReceivableAnticipationPartner.VORTX))
        currentBalance += calculateCreditedValue(creditScenario(customer, BillingType.PIX, ReceivableAnticipationPartner.VORTX))
        currentBalance += calculateCreditedValue(creditScenario(customer, BillingType.MUNDIPAGG_CIELO, ReceivableAnticipationPartner.OCEAN))
        currentBalance += calculateCreditedValue(creditWithConfigFeeScenario(customer, BillingType.MUNDIPAGG_CIELO, ReceivableAnticipationPartner.OCEAN))
        currentBalance += calculateCreditedValue(debitScenario(customer, BillingType.BOLETO, ReceivableAnticipationPartner.VORTX))
        currentBalance += calculateCreditedValue(debitScenario(customer, BillingType.PIX, ReceivableAnticipationPartner.VORTX))
        currentBalance += calculateCreditedValue(debitScenario(customer, BillingType.MUNDIPAGG_CIELO, ReceivableAnticipationPartner.OCEAN))

        if (acquiringEnabled) acquiringEnabledManager.enable()

        return currentBalance
    }

    private BigDecimal calculateCreditedValue(ReceivableAnticipation anticipation) {
        BigDecimal mandatoryNotificationFee = 0

        Boolean wasMandatoryNotificationEnabled = mandatoryCustomerAccountNotificationService.shouldEnableNotificationsOnCreditReceivableAnticipation(anticipation.customer, anticipation.customerAccount.cpfCnpj, anticipation.billingType, anticipation.value, anticipation.installment?.installmentCount)
        if (anticipation.status.isDebited() && wasMandatoryNotificationEnabled) {
            mandatoryNotificationFee += CustomerFee.calculateMessagingNotificationFee(anticipation.payment.provider)
        }

        return anticipation.netValue - mandatoryNotificationFee
    }

    private ReceivableAnticipation createReceivableAnticipation(Customer customer, BillingType billingType, ReceivableAnticipationPartner partner) {
        CustomerAccount customerAccount = mockNewCustomerAccount(customer)
        Payment payment = mockNewPayment(customerAccount, billingType)

        List<ReceivableAnticipationDocumentVO> receivableAnticipationDocumentVOList = []
        if (billingType.isBoletoOrPix()) {
            CommonsMultipartFile contractFile = mockContractFile()
            receivableAnticipationDocumentVOList.add(new ReceivableAnticipationDocumentVO(contractFile))
        } else if (billingType.isCreditCard()) {
            paymentSandboxService.confirmPaymentWithMockedData(payment.id, customer.id)
        }

        CreateReceivableAnticipationAdapter createReceivableAnticipationAdapter = CreateReceivableAnticipationAdapter.buildByPayment(payment, receivableAnticipationDocumentVOList, ReceivableAnticipationOriginRequesterInfoMethod.API)
        ReceivableAnticipation anticipation = receivableAnticipationService.save(createReceivableAnticipationAdapter)

        if (anticipation.partner != partner) throw new RuntimeException("A antecipação foi atribuída a um parceiro diferente do esperado")
        return anticipation
    }

    private void creditReceivableAnticipation(ReceivableAnticipation anticipation) {
        if (anticipation.billingType.isCreditCard()) {
            receivableAnticipationService.approve(anticipation.id, null)
            receivableAnticipationService.processApprovedCreditCardAnticipation(anticipation)
        } else {
            receivableAnticipationService.processApprovedBankSlipAndPixAnticipation(anticipation)
        }

        if (anticipation.status.isAwaitingPartnerCredit()) receivableAnticipationSandboxService.credit(anticipation)
    }

    private ReceivableAnticipation creditScenario(Customer customer, BillingType billingType, ReceivableAnticipationPartner partner) {
        ReceivableAnticipation anticipation = createReceivableAnticipation(customer, billingType, partner)
        creditReceivableAnticipation(anticipation)

        if (!anticipation.status.isCredited()) throw new RuntimeException("A antecipação não foi creditada com sucesso para o teste de recálculo")
        return anticipation
    }

    private void debitReceivableAnticipation(ReceivableAnticipation anticipation) {
        if (anticipation.billingType.isCreditCard()) {
            paymentSandboxService.receiveCreditCardWithMockedData(anticipation.payment.id, anticipation.customer.id)
        } else {
            paymentSandboxService.confirmPaymentWithMockedData(anticipation.payment.id, anticipation.customer.id)
        }
    }

    private ReceivableAnticipation debitScenario(Customer customer, BillingType billingType, ReceivableAnticipationPartner partner) {
        ReceivableAnticipation anticipation = creditScenario(customer, billingType, partner)
        debitReceivableAnticipation(anticipation)

        if (!anticipation.status.isDebited()) throw new RuntimeException("A antecipação não foi debitada com sucesso para o teste de recálculo")
        return anticipation
    }

    private CommonsMultipartFile mockContractFile() {
        InputStream contentStream = new ByteArrayInputStream("Mock contract text".getBytes())
        DiskFileItemFactory factory = new DiskFileItemFactory()
        FileItem fileItem = factory.createItem( "file", "multipart/form-data", false, "contract.pdf" )
        IOUtils.copy(contentStream, fileItem.getOutputStream())

        return new CommonsMultipartFile(fileItem)
    }

    private CustomerAccount mockNewCustomerAccount(Customer customer) {
        Map saveCustomerAccountData = [
            cpfCnpj: "234.269.580-23",
            name: "Test CustomerAccount",
            phone: "47900000000",
            email: "recalculate_test@asaas.com.br"
        ]
        return customerAccountService.save(customer, null, saveCustomerAccountData)
    }

    private Payment mockNewPayment(CustomerAccount customerAccount, BillingType billingType) {
        final Integer businessDaysToAdd = 9
        final BigDecimal paymentValue = BigDecimalUtils.random(PAYMENT_TEST_MINIMUM_VALUE, PAYMENT_TEST_MAXIMUM_VALUE)
        Date today = new Date().clearTime()

        Map params = [:]
        params.value = paymentValue
        params.netValue = paymentValue
        params.billingType = billingType
        params.dueDate = CustomDateUtils.addBusinessDays(today, businessDaysToAdd)
        params.customerAccount = customerAccount

        return paymentService.save(params, false)
    }

    private ReceivableAnticipation creditWithConfigFeeScenario(Customer customer, BillingType billingType, ReceivableAnticipationPartner partner) {
        final BigDecimal detachedDailyFeeValue = 0.05555
        feeAdminService.updateReceivableAnticipationConfigFee(customer.id, detachedDailyFeeValue, null, null, CustomerReceivableAnticipationConfigChangeOrigin.MANUAL_CHANGE_TO_DEFAULT_FEE)

        ReceivableAnticipation anticipation = createReceivableAnticipation(customer, billingType, partner)

        ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO = new ReceivableAnticipationFinancialInfoVO()
        receivableAnticipationFinancialInfoVO.build(anticipation.payment, false)
        Map financialInfo = receivableAnticipationFinancialInfoService.buildFinancialInfo(customer, receivableAnticipationFinancialInfoVO, partner)
        if (financialInfo.fee != anticipation.fee) throw new RuntimeException("A antecipação teve sua taxa calculada errada, esperado ${anticipation.fee} e teve ${financialInfo.fee}")

        creditReceivableAnticipation(anticipation)
        if (!anticipation.status.isCredited()) throw new RuntimeException("A antecipação não foi creditada com sucesso para o teste de recálculo")

        return anticipation
    }
}

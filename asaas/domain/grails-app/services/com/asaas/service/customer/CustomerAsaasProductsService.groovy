package com.asaas.service.customer

import com.asaas.bill.BillStatus
import com.asaas.cache.asaaserpcustomerconfig.AsaasErpCustomerConfigCacheVO
import com.asaas.customerasaasproduct.CustomerAsaasProductName
import com.asaas.domain.bill.Bill
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.login.LoginAttempt
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.login.LoginAttemptChannel
import com.asaas.utils.CustomDateUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CustomerAsaasProductsService {

    def asaasErpCustomerConfigCacheService
    def customerReceivableAnticipationConfigService

    public List<Map> getCustomerAsaasProducts(Customer customer) {
        if (customer.isNotFullyApproved()) return []

        List<Map> asaasProducts = [
            getInvoiceProduct(customer),
            getAnticipationProduct(customer),
            getAsaasMoneyProduct(customer),
            getBaseErpProduct(customer),
            getAsaasCardProduct(customer),
            getCreditBureauReportProduct(customer),
            getCreditBureauDunningProduct(customer),
            getBillProduct(customer)
        ]

        return asaasProducts
    }

    private Map getAsaasCardProduct(Customer customer) {
        return [name: CustomerAsaasProductName.ASAAS_CARD, inUse: customer.hasAsaasCard()]
    }

    private Map getInvoiceProduct(Customer customer) {
        Map invoiceProductMap = [name: CustomerAsaasProductName.INVOICE, inUse: false]
        if (!CustomerFeature.isInvoiceEnabled(customer.id)) return invoiceProductMap

        Boolean hasAuthorizedInvoice = Invoice.authorized([exists: true, customer: customer]).get().asBoolean()
        invoiceProductMap.inUse = hasAuthorizedInvoice

        return invoiceProductMap
    }

    private Map getAnticipationProduct(Customer customer) {
        if (customerReceivableAnticipationConfigService.calculateBankSlipAndPixAvailableLimit(customer) <= 0) return [name: CustomerAsaasProductName.ANTICIPATION, inUse: false]

        Boolean inUse = ReceivableAnticipation.query([exists: true, customer: customer]).get().asBoolean()

        Boolean canAnticipateBankSlipPayment

        if (!inUse) {
            canAnticipateBankSlipPayment = customer.canAnticipateBoleto()
        }

        return [name: CustomerAsaasProductName.ANTICIPATION, inUse: inUse, message: !canAnticipateBankSlipPayment && !inUse ? "(Somente antecipações de cobranças via cartão de crédito estão disponíveis)" : ""]
    }

    private Map getCreditBureauReportProduct(Customer customer) {
        return [name: CustomerAsaasProductName.CREDTI_BUREAU_REPORT, inUse: CreditBureauReport.customerHasCreditBureauReport(customer)]
    }

    private Map getCreditBureauDunningProduct(Customer customer) {
        BusinessValidation validatedBusiness = PaymentDunning.validateCustomerPermissionsToCreditBureau(customer)

        return [
            name: CustomerAsaasProductName.CREDIT_BUREAU_DUNNING,
            inUse: PaymentDunning.customerHasAlreadyRequestedCreditBureauDunning(customer),
            message: validatedBusiness.getFirstErrorMessage(),
            disabled: !validatedBusiness.isValid()
        ]
    }

    private Map getBillProduct(Customer customer) {
        Date threeMonthsAgo = CustomDateUtils.addMonths(new Date().clearTime(), -3)
        Date lastBillPaymentDate = Bill.query([column: "paymentDate", customerId: customer.id, status: BillStatus.PAID, "lastUpdated[ge]": threeMonthsAgo]).get()
        return [name: CustomerAsaasProductName.BILL, inUse: lastBillPaymentDate.asBoolean(), message: lastBillPaymentDate ? "(Último pagamento: ${CustomDateUtils.fromDate(lastBillPaymentDate)})" : ""]
    }

    private Map getBaseErpProduct(Customer customer) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customer.id)
        return [name: CustomerAsaasProductName.BASE_ERP, inUse: asaasErpCustomerConfigCacheVO.fullyIntegrated]
    }

    private Map getAsaasMoneyProduct(Customer customer) {
        Boolean customerAccessAsaasMoney = false
        if (customer.status.isActive()) {
            customerAccessAsaasMoney = LoginAttempt.query([exists: true, customer: customer, channel: LoginAttemptChannel.ASAAS_MONEY, success: true]).get().asBoolean()
        }
        return [name: CustomerAsaasProductName.ASAAS_MONEY, inUse: customerAccessAsaasMoney.asBoolean()]
    }
}

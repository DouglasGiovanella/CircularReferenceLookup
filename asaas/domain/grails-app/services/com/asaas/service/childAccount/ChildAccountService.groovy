package com.asaas.service.childAccount

import com.asaas.childAccount.adapter.ChildAccountFeeInfoAdapter
import com.asaas.childAccount.list.ChildAccountListVO
import com.asaas.creditcard.CreditCardFeeConfigVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixCreditFeeType
import com.mysql.jdbc.exceptions.MySQLTimeoutException
import grails.orm.PagedResultList
import grails.transaction.Transactional

@Transactional
class ChildAccountService {

    public ChildAccountListVO list(Customer accountOwner, Map search, Integer max, Integer offset) {
        try {
            search.accountOwner = accountOwner
            search.columnList = ["id", "name", "company", "email", "cpfCnpj", "personType", "dateCreated", "customerRegisterStatus.generalApproval"]
            PagedResultList<Map> childAccountListMap = Customer.query(search).list(max: max, offset: offset, readOnly: true)

            return new ChildAccountListVO(childAccountListMap)
        } catch (MySQLTimeoutException mySQLTimeoutException) {
            AsaasLogger.warn("ChildAccountService.list >> Timeout na listagem de subcontas. Params [${search}]", mySQLTimeoutException)

            throw mySQLTimeoutException
        }
    }

    public Customer findByAccountOwner(Long childAccountId, Customer accountOwner) {
        return Customer.query([id: childAccountId, accountOwner: accountOwner, readOnly: true]).get()
    }

    public ChildAccountFeeInfoAdapter buildFeeInfo(Customer customer) {
        Map bankSlipFeeInfoMap = buildBankSlipFeeInfo(customer)
        CreditCardFeeConfigVO creditCardFeeConfigVO = new CreditCardFeeConfigVO(customer)
        Map pixCreditFeeMap = buildPixCreditFeeInfo(customer)
        Map customerFeeMap = buildCustomerFeeInfo(customer)
        Map receivableAnticipationFeeMap = buildReceivableAnticipationFeeInfo(customer)

        return new ChildAccountFeeInfoAdapter(customer.id, bankSlipFeeInfoMap, creditCardFeeConfigVO, pixCreditFeeMap, customerFeeMap, receivableAnticipationFeeMap)
    }

    private Map buildBankSlipFeeInfo(Customer customer) {
        final List<String> columnList = ["defaultValue", "discountValue", "discountExpiration", "percentageFee", "minimumFee", "maximumFee", "type"]

        Map bankSlipFeeMap = BankSlipFee.query([columnList: columnList, customer: customer, disableSort: true, readOnly: true]).get()
        if (bankSlipFeeMap) return bankSlipFeeMap

        return [defaultValue: BankSlipFee.DEFAULT_BANK_SLIP_FEE, type: BankSlipFee.DEFAULT_BANK_SLIP_FEE_TYPE]
    }

    private Map buildPixCreditFeeInfo(Customer customer) {
        final List<String> columnList = ["type", "fixedFee", "fixedFeeWithDiscount", "discountExpiration", "percentageFee", "minimumFee", "maximumFee"]

        Map pixCreditFeeMap = PixCreditFee.query([columnList: columnList, customer: customer, disableSort: true, readOnly: true]).get()
        if (pixCreditFeeMap) return pixCreditFeeMap

        return [type: PixCreditFeeType.FIXED, fixedFee: PixCreditFee.DEFAULT_PIX_FEE]
    }

    private Map buildCustomerFeeInfo(Customer customer) {
        CustomerFee customerFee = CustomerFee.query([customer: customer, disableSort: true, readOnly: true]).get()

        return [
            pixDebitFee: customerFee?.pixDebitFee ?: CustomerFee.PIX_DEBIT_FEE,
            transferValue: customerFee?.transferValue ?: CustomerFee.CREDIT_TRANSFER_FEE_VALUE,
            paymentMessagingNotificationFeeValue: customerFee?.paymentMessagingNotificationFeeValue ?: CustomerFee.PAYMENT_MESSAGING_NOTIFICATION_FEE_VALUE,
            whatsappNotificationFee: customerFee?.whatsappNotificationFee ?: CustomerFee.WHATSAPP_FEE,
            phoneCallNotificationFee: customerFee?.phoneCallNotificationFee ?: CustomerFee.PHONE_CALL_NOTIFICATION_FEE,
            creditBureauReportNaturalPersonFee: customerFee?.creditBureauReportNaturalPersonFee ?: CustomerFee.CREDIT_BUREAU_REPORT_NATURAL_PERSON_FEE,
            creditBureauReportLegalPersonFee: customerFee?.creditBureauReportLegalPersonFee ?: CustomerFee.CREDIT_BUREAU_REPORT_LEGAL_PERSON_FEE,
            dunningCreditBureauFeeValue: customerFee?.dunningCreditBureauFeeValue ?: CustomerFee.DUNNING_CREDIT_BUREAU_FEE_VALUE,
            invoiceValue: customerFee?.invoiceValue ?: CustomerFee.SERVICE_INVOICE_FEE,
            productInvoiceValue: customerFee?.productInvoiceValue ?: CustomerFee.PRODUCT_INVOICE_FEE,
            consumerInvoiceValue: customerFee?.consumerInvoiceValue ?: CustomerFee.CONSUMER_INVOICE_FEE
        ]
    }

    private Map buildReceivableAnticipationFeeInfo(Customer customer) {
        return [
            creditCardDetachedDailyFee: CustomerReceivableAnticipationConfig.getCreditCardDetachedDailyFee(customer),
            creditCardInstallmentDailyFee: CustomerReceivableAnticipationConfig.getCreditCardInstallmentDailyFee(customer),
            bankSlipDailyFee: CustomerReceivableAnticipationConfig.getBankSlipDailyFee(customer)
        ]
    }
}

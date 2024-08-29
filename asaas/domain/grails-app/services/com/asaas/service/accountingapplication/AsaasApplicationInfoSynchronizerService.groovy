package com.asaas.service.accountingapplication

import com.asaas.asaascard.AsaasCardRechargeStatus
import com.asaas.asaascard.AsaasCardTransactionSettlementStatus
import com.asaas.bill.BillStatus
import com.asaas.billinginfo.BillingType
import com.asaas.chargeback.ChargebackStatus
import com.asaas.contaazul.statement.utils.ContaAzulAccount
import com.asaas.creditcard.CreditCardAcquirer
import com.asaas.creditcard.CreditCardBrand
import com.asaas.credittransferrequest.CreditTransferRequestStatus
import com.asaas.customer.PersonType
import com.asaas.financialstatement.BalanceType
import com.asaas.financialstatement.FinancialStatementCategoryUtils
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectSettlementBatchStatus
import com.asaas.mobilephonerecharge.MobilePhoneRechargeStatus
import com.asaas.payment.PaymentStatus
import com.asaas.paymentcustody.PaymentCustodyStatus
import com.asaas.pix.PixTransactionStatus
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.refundrequest.RefundRequestStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AsaasApplicationInfoSynchronizerService {

    def grailsApplication

    public Map buildAsaasApplicationInfo() {
        Map asaasApplicationInfoData = [:]
        asaasApplicationInfoData.bankAccountInfoData = buildBankAccountInfoData()
        asaasApplicationInfoData.financialStatementType = buildFinancialStatementTypeData()
        asaasApplicationInfoData.financialTransactionType = buildFinancialTransactionTypeData()
        asaasApplicationInfoData.financialStatementTypeExpenseAndRevenueType = buildFinancialStatementTypeByRevenueAndExpenseData()
        asaasApplicationInfoData.balanceType = buildBalanceTypeData()
        asaasApplicationInfoData.receivableAnticipationActiveStatusData = buildReceivableAnticipationActiveStatusData()
        asaasApplicationInfoData.bifrostFinancialStatementTypeData = buildBifrostFinancialStatementTypeData()
        asaasApplicationInfoData.creditCardAcquirerData = buildEnumData(CreditCardAcquirer)
        asaasApplicationInfoData.paymentStatusData = buildEnumData(PaymentStatus)
        asaasApplicationInfoData.asaasCardRechargeStatusData = buildEnumData(AsaasCardRechargeStatus)
        asaasApplicationInfoData.asaasCardTransactionSettlementStatusData = buildEnumData(AsaasCardTransactionSettlementStatus)
        asaasApplicationInfoData.mobilePhoneRechargeStatusData = buildEnumData(MobilePhoneRechargeStatus)
        asaasApplicationInfoData.refundRequestStatusData = buildEnumData(RefundRequestStatus)
        asaasApplicationInfoData.paymentCustodyStatusData = buildEnumData(PaymentCustodyStatus)
        asaasApplicationInfoData.chargebackStatusData = buildEnumData(ChargebackStatus)
        asaasApplicationInfoData.personTypeData = buildEnumData(PersonType)
        asaasApplicationInfoData.billStatusData = buildEnumData(BillStatus)
        asaasApplicationInfoData.billingTypeData = buildEnumData(BillingType)
        asaasApplicationInfoData.creditCardBrandData = buildEnumData(CreditCardBrand)
        asaasApplicationInfoData.cercContractualEffectSettlementBatchStatusData = buildEnumData(CercContractualEffectSettlementBatchStatus)
        asaasApplicationInfoData.creditTransferRequestStatusData = buildEnumData(CreditTransferRequestStatus)
        asaasApplicationInfoData.pixTransactionStatusData = buildEnumData(PixTransactionStatus)

        return asaasApplicationInfoData
    }

    public Map buildBankAccountInfoData() {
        Map bankAccountInfo = [:]

        bankAccountInfo.asaas = grailsApplication.config.bank.api.asaas
        bankAccountInfo.customer = grailsApplication.config.bank.api.customer

        return bankAccountInfo
    }

    private Map buildFinancialStatementTypeData() {
        Map financialStatementTypeData = [:]

        for (FinancialStatementType financialStatementType : FinancialStatementType.values()) {
            try {
                financialStatementTypeData."${financialStatementType.name()}" = [label: financialStatementType.getLabel()]
            } catch (Exception exception) {
                financialStatementTypeData."${financialStatementType.name()}" = [label: ""]
            }
        }

        return financialStatementTypeData
    }

    private Map buildFinancialTransactionTypeData() {
        Map financialTransactionTypeData = [:]

        for (FinancialTransactionType financialTransactionType : FinancialTransactionType.values()) {
            try {
                financialTransactionTypeData."${financialTransactionType.name()}" = [label: Utils.getMessageProperty("financialTransaction.type.${financialTransactionType}.label")]
            } catch (Exception exception) {
                financialTransactionTypeData."${financialTransactionType.name()}" = [label: ""]
            }
        }

        return financialTransactionTypeData
    }

    private Map buildFinancialStatementTypeByRevenueAndExpenseData() {
        Map financialStatementByRevenueAndExpenseData = [:]

        financialStatementByRevenueAndExpenseData.revenue = FinancialStatementCategoryUtils.listRevenueTypes()
        financialStatementByRevenueAndExpenseData.expense = FinancialStatementCategoryUtils.listExpenseTypes()

        return financialStatementByRevenueAndExpenseData
    }

    private List<String> buildReceivableAnticipationActiveStatusData() {
        List<String> receivableAnticipationActiveStatusData = ReceivableAnticipationStatus.getActiveStatuses().collect { it.toString() }

        return receivableAnticipationActiveStatusData
    }

    private List<String> buildBifrostFinancialStatementTypeData() {
        List<String> bifrostFinancialStatementTypeData = FinancialStatementType.managedByBifrostList().collect { it.toString() }

        return bifrostFinancialStatementTypeData
    }

    private Map buildBalanceTypeData() {
        Map balanceTypeData = [:]
        List<String> asaasBalanceTypeList = []
        List<String> customerBalanceTypeList = []

        for (FinancialStatementType financialStatementType : FinancialStatementType.values()) {
            if (financialStatementType.isAsaasBalance()) asaasBalanceTypeList.add(financialStatementType.toString())
            if (financialStatementType.isCustomerBalance()) customerBalanceTypeList.add(financialStatementType.toString())
        }

        balanceTypeData."${BalanceType.ASAAS_BALANCE.name()}" = asaasBalanceTypeList
        balanceTypeData."${BalanceType.CUSTOMER_BALANCE.name()}" = customerBalanceTypeList

        return balanceTypeData
    }

    private List<String> buildEnumData(Class enumType) {
        return enumType.values().collect { it.toString() }
    }
}

package com.asaas.service.financialstatement

import com.asaas.domain.bank.Bank
import com.asaas.domain.payment.Payment
import com.asaas.financialstatement.FinancialStatementType
import grails.transaction.Transactional

@Transactional
class FinancialStatementAsaasErpService {

    def financialStatementService

    public void save(Bank bank, List<Long> asaasErpPaymentIdList) {
        List<Payment> paymentList = Payment.query(["id[in]": asaasErpPaymentIdList, "financialStatementTypeList[notExists]": [FinancialStatementType.ASAAS_ERP_REVENUE, FinancialStatementType.PIX_ASAAS_ERP_REVENUE]]).list(readOnly: true)
        if (!paymentList) return

        Payment firstPayment = paymentList.first()
        if (firstPayment.billingType.isBoleto()) {
            financialStatementService.createForReceivedBankSlipPayments(paymentList, bank, false)
        } else {
            financialStatementService.saveAsaasErpRevenue(paymentList, bank, firstPayment.creditDate)
        }
    }
}

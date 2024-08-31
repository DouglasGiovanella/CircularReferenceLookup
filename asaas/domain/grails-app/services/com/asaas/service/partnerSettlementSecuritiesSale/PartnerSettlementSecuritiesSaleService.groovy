package com.asaas.service.partnerSettlementSecuritiesSale

import com.asaas.credit.CreditType
import com.asaas.domain.credit.Credit
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerSettlement
import com.asaas.exception.BusinessException
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartnerSettlementStatus
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PartnerSettlementSecuritiesSaleService {

    def creditService
    def receivableAnticipationValidationCacheService

    public void save(Customer customer, BigDecimal value) {
        validateSave(customer, value)

        List<ReceivableAnticipationPartnerSettlement> partnerSettlementList = ReceivableAnticipationPartnerSettlement.query([customer: customer, partner: ReceivableAnticipationPartner.VORTX, status: ReceivableAnticipationPartnerSettlementStatus.AWAITING_CREDIT]).list()

        final Integer descriptionMaxSize = 255
        String creditDescription = Utils.truncateString("Liquidação paga por venda do(s) título(s) ID(s): ${partnerSettlementList.id}", descriptionMaxSize)
        Credit credit = creditService.save(customer.id, CreditType.PARTNER_SETTLEMENT_SECURITIES_SALE, creditDescription, value, null)
        if (credit.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(credit))

        for (ReceivableAnticipationPartnerSettlement partnerSettlement : partnerSettlementList) {
            partnerSettlement.status = ReceivableAnticipationPartnerSettlementStatus.PAID_BY_TITLE_SALE
            partnerSettlement.save(failOnError: true)
        }

        receivableAnticipationValidationCacheService.evictIsCustomerWithPartnerSettlementAwaitingCreditForTooLong(customer.id)
    }

    private void validateSave(Customer customer, BigDecimal value) {
        if (!value) throw new BusinessException(Utils.getMessageProperty("default.null.message", ["valor"]))

        BigDecimal partnerSettlementValue = ReceivableAnticipationPartnerSettlement.getUnpaidValueToPartnerByCustomer(customer, ReceivableAnticipationPartner.VORTX)
        if (partnerSettlementValue != value) throw new BusinessException("O valor do crédito é diferente do valor FIDC")

        BigDecimal balanceAfterCredit = value + FinancialTransaction.getCustomerBalance(customer)
        if (balanceAfterCredit > new BigDecimal(0)) throw new BusinessException("O cliente ficará com saldo positivo após essa operação")
    }
}

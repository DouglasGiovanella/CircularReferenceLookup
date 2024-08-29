package com.asaas.service.paymentdunning.creditbureau.conciliation

import com.asaas.customer.PersonType
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningReturnBatchItem
import com.asaas.domain.paymentdunning.creditbureau.conciliation.CreditBureauDunningConciliationItem
import com.asaas.domain.paymentdunning.creditbureau.conciliation.CreditBureauDunningInvoiceSummary
import com.asaas.exception.BusinessException
import com.asaas.paymentdunning.creditbureau.conciliation.CreditBureauDunningInvoiceSummaryStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional

@Transactional
class CreditBureauDunningInvoiceSummaryService {

    public CreditBureauDunningInvoiceSummary save(Map params) {
        CreditBureauDunningInvoiceSummary creditBureauDunningInvoiceSummary = validate(params)
        if (creditBureauDunningInvoiceSummary.hasErrors()) return creditBureauDunningInvoiceSummary

        creditBureauDunningInvoiceSummary.properties["customer", "customerAccountPersonType", "partialCnpj", "cpfCnpj", "quantity", "startDate", "endDate"] = params
        creditBureauDunningInvoiceSummary.save(failOnError: true)
        return creditBureauDunningInvoiceSummary
    }

    public void setAsPreConciliated(CreditBureauDunningInvoiceSummary creditBureauDunningInvoiceSummary) {
        creditBureauDunningInvoiceSummary.status = CreditBureauDunningInvoiceSummaryStatus.PRE_CONCILIATED
        creditBureauDunningInvoiceSummary.save(failOnError: true)
    }

    public void setAsPreConciliatedWithInconsistencies(Long creditBureauDunningInvoiceSummaryId) {
        CreditBureauDunningInvoiceSummary creditBureauDunningInvoiceSummary = CreditBureauDunningInvoiceSummary.get(creditBureauDunningInvoiceSummaryId)

        BusinessValidation businessValidation = creditBureauDunningInvoiceSummary.canSetAsPreConciliateWithInconsistencies()
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        creditBureauDunningInvoiceSummary.status = CreditBureauDunningInvoiceSummaryStatus.PRE_CONCILIATED_WITH_INCONSISTENCIES
        creditBureauDunningInvoiceSummary.save(failOnError: true)
    }

    public void setAsAwaitingManualConciliation(CreditBureauDunningInvoiceSummary creditBureauDunningInvoiceSummary) {
        creditBureauDunningInvoiceSummary.status = CreditBureauDunningInvoiceSummaryStatus.AWAITING_MANUAL_CONCILIATION
        creditBureauDunningInvoiceSummary.save(failOnError: true)
    }

    public Map buildConciliationsTotalInfo(Map invoiceSummaryParams) {
        if (!invoiceSummaryParams.containsKey("startDate[ge]") || !invoiceSummaryParams.containsKey("endDate[le]") ) return [disabledReason: "Para totalizar é necessário informar o período inicial e final."]

        if (CustomDateUtils.calculateDifferenceInMonthsIgnoringDays(invoiceSummaryParams."startDate[ge]", invoiceSummaryParams."endDate[le]") > 1) return [disabledReason: "O período analisado não pode ser maior que 1 mês."]

        Map conciliationTotalInfo = [:]
        List<Long> creditBureauDunningInvoiceSummaryIdList = CreditBureauDunningInvoiceSummary.query(invoiceSummaryParams + [column: "id"]).list()

        conciliationTotalInfo.preConciliatedCount = CreditBureauDunningInvoiceSummary.query([column: "id", "status[in]": CreditBureauDunningInvoiceSummaryStatus.listPreConciliatedStatuses()]).count()
        conciliationTotalInfo << buildNaturalPersonConciliationsTotalInfo(creditBureauDunningInvoiceSummaryIdList)
        conciliationTotalInfo << buildLegalPersonConciliationsTotalInfo(creditBureauDunningInvoiceSummaryIdList)

        return conciliationTotalInfo
    }

    private Map buildNaturalPersonConciliationsTotalInfo(List<Long> creditBureauDunningInvoiceSummaryIdList) {
        if (creditBureauDunningInvoiceSummaryIdList.size() == 0) return [naturalPersonTotalCount: 0, naturalPersonTotalValue: 0]

        Map search = [
                    "column": "creditBureauDunningReturnBatchItem",
                    "creditBureauDunningInvoiceSummaryId[in]": creditBureauDunningInvoiceSummaryIdList,
                    "customerAccountPersonType": PersonType.FISICA
        ]

        List<CreditBureauDunningReturnBatchItem> creditBureauDunningReturnBatchItemList = CreditBureauDunningConciliationItem.query(search).list()

        return [naturalPersonTotalCount: creditBureauDunningReturnBatchItemList.size(), naturalPersonTotalValue: creditBureauDunningReturnBatchItemList.partnerChargedFeeValue.sum() ?: 0]
    }

    private Map buildLegalPersonConciliationsTotalInfo(List<Long> creditBureauDunningInvoiceSummaryIdList) {
        if (creditBureauDunningInvoiceSummaryIdList.size() == 0) return [legalPersonTotalCount: 0, legalPersonTotalValue: 0]

        Map search = [
                    "column": "creditBureauDunningReturnBatchItem",
                    "creditBureauDunningInvoiceSummaryId[in]": creditBureauDunningInvoiceSummaryIdList,
                    "customerAccountPersonType": PersonType.JURIDICA
        ]

        List<CreditBureauDunningReturnBatchItem> creditBureauDunningReturnBatchItemList = CreditBureauDunningConciliationItem.query(search).list()

        return [legalPersonTotalCount: creditBureauDunningReturnBatchItemList.size(), legalPersonTotalValue: creditBureauDunningReturnBatchItemList.partnerChargedFeeValue.sum() ?: 0]
    }

    private CreditBureauDunningInvoiceSummary validate(Map params) {
        CreditBureauDunningInvoiceSummary creditBureauDunningInvoiceSummary = new CreditBureauDunningInvoiceSummary()

        if (CreditBureauDunningInvoiceSummary.query(params).get().asBoolean()) {
            return DomainUtils.addError(creditBureauDunningInvoiceSummary, "O valor cobrado pelo Serasa já existe.")
        }

        return creditBureauDunningInvoiceSummary
    }
}

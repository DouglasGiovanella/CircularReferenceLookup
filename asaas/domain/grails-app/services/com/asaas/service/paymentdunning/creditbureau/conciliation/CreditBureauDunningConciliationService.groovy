package com.asaas.service.paymentdunning.creditbureau.conciliation

import com.asaas.domain.paymentdunning.creditbureau.conciliation.CreditBureauDunningConciliationItem
import com.asaas.domain.paymentdunning.creditbureau.conciliation.CreditBureauDunningInvoiceSummary
import com.asaas.domain.paymentdunning.creditbureau.conciliation.CreditBureauDunningConciliation
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningReturnBatchItem
import com.asaas.log.AsaasLogger
import com.asaas.paymentdunning.creditbureau.conciliation.CreditBureauDunningConciliationStatus
import com.asaas.paymentdunning.creditbureau.conciliation.CreditBureauDunningInvoiceSummaryStatus
import com.asaas.user.UserUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CreditBureauDunningConciliationService {

    def creditBureauDunningInvoiceSummaryService

    def paymentDunningCustomerAccountInfoService

    public void conciliate() {
        List<Long> invoiceSummaryIdList = CreditBureauDunningInvoiceSummary.query([column: "id", status: CreditBureauDunningInvoiceSummaryStatus.PENDING]).list()

        Utils.forEachWithFlushSession(invoiceSummaryIdList, 50, { Long invoiceSummaryId ->
            Utils.withNewTransactionAndRollbackOnError({
                CreditBureauDunningInvoiceSummary creditBureauDunningInvoiceSummary = CreditBureauDunningInvoiceSummary.get(invoiceSummaryId)

                Map queryParams = ["creditBureauDunningReturnBatchDateCreated[ge]": creditBureauDunningInvoiceSummary.startDate, "creditBureauDunningReturnBatchDateCreated[le]": creditBureauDunningInvoiceSummary.endDate, "paymentDunningCustomerCpfCnpj": creditBureauDunningInvoiceSummary.cpfCnpj]
                if (creditBureauDunningInvoiceSummary.customer) queryParams += ["paymentDunningCustomer": creditBureauDunningInvoiceSummary.customer]

                List<CreditBureauDunningReturnBatchItem> returnBatchItemPendingConciliationList = CreditBureauDunningReturnBatchItem.pendingConciliation(queryParams).list()
                List<CreditBureauDunningReturnBatchItem> returnBatchItemsFilteredByPersonTypeList = []

                if (creditBureauDunningInvoiceSummary.customerAccountPersonType.isFisica()) {
                    returnBatchItemsFilteredByPersonTypeList = returnBatchItemPendingConciliationList.findAll {
                        (paymentDunningCustomerAccountInfoService.isNaturalPerson(it.paymentDunning))
                    }
                } else if (creditBureauDunningInvoiceSummary.customerAccountPersonType.isJuridica()) {
                    returnBatchItemsFilteredByPersonTypeList = returnBatchItemPendingConciliationList.findAll {
                        (!paymentDunningCustomerAccountInfoService.isNaturalPerson(it.paymentDunning))
                    }
                }

                if (!returnBatchItemsFilteredByPersonTypeList) {
                    creditBureauDunningInvoiceSummaryService.setAsAwaitingManualConciliation(creditBureauDunningInvoiceSummary)
                    return
                }

                CreditBureauDunningConciliation creditBureauDunningConciliation = save(creditBureauDunningInvoiceSummary)
                if (creditBureauDunningConciliation.hasErrors()) throw new ValidationException(null, creditBureauDunningConciliation.errors)

                if (creditBureauDunningInvoiceSummary.quantity == returnBatchItemsFilteredByPersonTypeList.size()) {
                    creditBureauDunningInvoiceSummaryService.setAsPreConciliated(creditBureauDunningInvoiceSummary)
                } else {
                    creditBureauDunningInvoiceSummaryService.setAsAwaitingManualConciliation(creditBureauDunningInvoiceSummary)
                }

                for (CreditBureauDunningReturnBatchItem creditBureauDunningReturnBatchItem in returnBatchItemsFilteredByPersonTypeList) {
                    CreditBureauDunningConciliationItem conciliationItem = saveItem(creditBureauDunningConciliation, creditBureauDunningReturnBatchItem)
                    if (conciliationItem.hasErrors()) throw new ValidationException(null, conciliationItem.errors)
                }
            }, [onError: { Exception exception ->
                    String errorInfo = "Cobrança Serasa: [${invoiceSummaryId.toString()}] >>> ${exception}"
                    AsaasLogger.error("CreditBureauDunningConciliationService.conciliate >> Problemas na conciliação dos valores cobrados do Serasa: Não foi possível conciliar o seguinte dado: ${errorInfo}")

            }])
        })
    }

    public void confirmAllPreConciliated() {
        List<CreditBureauDunningInvoiceSummary> invoiceSummaryList = CreditBureauDunningInvoiceSummary.query(["status[in]": [CreditBureauDunningInvoiceSummaryStatus.PRE_CONCILIATED, CreditBureauDunningInvoiceSummaryStatus.PRE_CONCILIATED_WITH_INCONSISTENCIES]]).list()

        for (CreditBureauDunningInvoiceSummary invoiceSummary in invoiceSummaryList) {
            CreditBureauDunningConciliation conciliation = CreditBureauDunningConciliation.query([creditBureauDunningInvoiceSummary: invoiceSummary]).get()

            if (!conciliation) {
                invoiceSummary.status = CreditBureauDunningInvoiceSummaryStatus.CONCILIATION_CONFIRMED_WITH_INCONSISTENCIES
                invoiceSummary.save(failOnError: true)
                continue
            }

            conciliation.status = CreditBureauDunningConciliationStatus.CONFIRMED
            conciliation.analyst = UserUtils.getCurrentUser()
            conciliation.analysisDate = new Date()

            if (conciliation.creditBureauDunningInvoiceSummary.status == CreditBureauDunningInvoiceSummaryStatus.PRE_CONCILIATED_WITH_INCONSISTENCIES) {
                conciliation.creditBureauDunningInvoiceSummary.status = CreditBureauDunningInvoiceSummaryStatus.CONCILIATION_CONFIRMED_WITH_INCONSISTENCIES
            } else {
                conciliation.creditBureauDunningInvoiceSummary.status = CreditBureauDunningInvoiceSummaryStatus.CONCILIATION_CONFIRMED
            }
            conciliation.save(failOnError: true)
        }
    }

    private CreditBureauDunningConciliation save(CreditBureauDunningInvoiceSummary creditBureauDunningInvoiceSummary) {
        CreditBureauDunningConciliation creditBureauDunningConciliation = new CreditBureauDunningConciliation()
        creditBureauDunningConciliation.status = CreditBureauDunningConciliationStatus.AWAITING_CONFIRMATION
        creditBureauDunningConciliation.customer = creditBureauDunningInvoiceSummary.customer
        creditBureauDunningConciliation.creditBureauDunningInvoiceSummary = creditBureauDunningInvoiceSummary
        creditBureauDunningConciliation.save(failOnError: true)

        return creditBureauDunningConciliation
    }

    private CreditBureauDunningConciliationItem saveItem(CreditBureauDunningConciliation creditBureauDunningConciliation, CreditBureauDunningReturnBatchItem creditBureauDunningReturnBatchItem) {
        CreditBureauDunningConciliationItem conciliationItem = new CreditBureauDunningConciliationItem()
        conciliationItem.creditBureauDunningConciliation = creditBureauDunningConciliation
        conciliationItem.creditBureauDunningReturnBatchItem = creditBureauDunningReturnBatchItem
        conciliationItem.save(failOnError: true)

        return conciliationItem
    }
}

package com.asaas.service.receivableregistration

import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.integration.cerc.optin.CercOptIn
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.filter.PeriodFilterType
import com.asaas.filter.PeriodFilterValuesVO
import com.asaas.integration.cerc.enums.CercProcessingStatus
import com.asaas.receivableregistration.optin.OptInListItemVO
import com.asaas.receivableregistration.optin.OptInListVO
import com.asaas.receivableregistration.receivableunit.ReceivableRegistrationSummaryVO
import com.asaas.receivableregistration.receivableunit.ReceivableUnitListItemVO
import com.asaas.receivableregistration.receivableunit.ReceivableUnitListVO
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.utils.CustomDateUtils
import grails.orm.PagedResultList
import grails.transaction.Transactional

@Transactional
class ReceivableRegistrationListService {

    public ReceivableUnitListVO listReceivableUnits(Map filters, Integer max, Integer offset) {
        Map search = buildSearchFilters(filters)
        PagedResultList<ReceivableUnit> receivableUnitList = ReceivableUnit.query(search).list(max: max, offset: offset)

        List<ReceivableUnitListItemVO> receivableUnitVOList = receivableUnitList.collect { new ReceivableUnitListItemVO(it) }

        return new ReceivableUnitListVO(receivableUnitVOList, receivableUnitList.totalCount)
    }

    public ReceivableRegistrationSummaryVO querySummaryInfo(Map filters) {
        Map search = buildSearchFilters(filters)

        List<Long> receivableUnitIdList = ReceivableUnit.query(search + [column: "id"]).list()

        if (!receivableUnitIdList) return new ReceivableRegistrationSummaryVO(BigDecimal.ZERO, BigDecimal.ZERO, search."estimatedCreditDate[ge]", search."estimatedCreditDate[le]")

        BigDecimal contractualEffectValue = CercContractualEffect.sumValue(["affectedReceivableUnitId[in]": receivableUnitIdList, "status[in]": CercProcessingStatus.listActiveStatuses()]).get()
        BigDecimal receivableUnitTotalValue = ReceivableUnitItem.sumNetValue(["receivableUnitId[in]": receivableUnitIdList, "payment[isNotNull]": true, "contractualEffect[isNull]": true, "anticipatedReceivableUnit[isNull]": true]).get()
        BigDecimal netValue = Math.max(receivableUnitTotalValue - contractualEffectValue, BigDecimal.ZERO)

        return new ReceivableRegistrationSummaryVO(contractualEffectValue, netValue, search."estimatedCreditDate[ge]", search."estimatedCreditDate[le]")
    }

    public OptInListVO listOptIns(Map filters, Integer max, Integer offset) {
        Map search = buildOptInSearchFilters(filters)
        PagedResultList<CercOptIn> cercOptInList = CercOptIn.query(search).list(max: max, offset: offset)

        List<OptInListItemVO> optInVOList = cercOptInList.collect { new OptInListItemVO(it) }

        return new OptInListVO(optInVOList, cercOptInList.totalCount, offset)
    }

    private Map buildSearchFilters(Map filters) {
        Map search = [:]

        search.customerCpfCnpj = filters.customerCpfCnpj
        search.holderCpfCnpj = filters.customerCpfCnpj

        if (filters.containsKey("searchFilters") && filters.searchFilters != "ALL") {
            search."${filters.searchFilters}" = true
        }

        if (filters.containsKey("statusList")) {
            List<String> statusList = []
            statusList.addAll(filters.statusList)
            search."status[in]" = statusList
        }

        if (filters.containsKey("paymentArrangementList")) {
            List<String> paymentArrangementList = []
            paymentArrangementList.addAll(filters.paymentArrangementList)
            search."paymentArrangement[in]" = paymentArrangementList.collect { PaymentArrangement.valueOf(it.toString()) }
        }

        if (filters.containsKey("paymentArrangement")) {
            search.paymentArrangement = PaymentArrangement.valueOf(filters.paymentArrangement.toString())
        }

        PeriodFilterType periodFilterType = PeriodFilterType.convert(filters.period.toString())

        if (periodFilterType == PeriodFilterType.CUSTOM && filters.startDate && filters.finishDate) {
            search."estimatedCreditDate[ge]" = CustomDateUtils.fromString(filters.startDate, "dd/MM/yyyy").clearTime()
            search."estimatedCreditDate[le]" = CustomDateUtils.fromString(filters.finishDate, "dd/MM/yyyy").clearTime()
        } else if (periodFilterType && periodFilterType != PeriodFilterType.FROM_BEGINNING) {
            PeriodFilterValuesVO periodFilter = periodFilterType.parsePeriodFilter()
            search."estimatedCreditDate[ge]" = periodFilter.startDate.clearTime()
            search."estimatedCreditDate[le]" = periodFilter.endDate.clearTime()
        }

        return search
    }

    private Map buildOptInSearchFilters(Map filters) {
        Map search = [:]

        search.customerCpfCnpj = filters.customerCpfCnpj

        if (filters.containsKey("requesterCpfCnpj")) {
            search.requesterCpfCnpj = filters.requesterCpfCnpj
        }

        return search
    }
}

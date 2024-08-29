package com.asaas.service.riskanalysis

import com.asaas.customer.CompanyType
import com.asaas.customer.CustomerStatus
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerRegisterStatus
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class MoneyLaunderingRiskAnalysisService {

    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService

    public void createRiskAnalysisByCreditTransferToThirdParty() {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.SUSPECT_OF_MONEY_LAUNDERING_BY_CREDIT_TRANSFER_TO_THIRD_PARTY
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        final Integer recurrenceOfAnalysisInMonths = 3
        final Long minNumberCreditTransfers = 3
        final BigDecimal minAmountValueTransfered = 15000
        final List<CompanyType> companyTypeList = [CompanyType.ASSOCIATION, CompanyType.LIMITED, CompanyType.NON_PROFIT_ASSOCIATION]
        final BigDecimal minTransfersAmountPercentageToThirdPartyAccount = 90

        Date referenceDate = CustomDateUtils.addMonths(new Date(), recurrenceOfAnalysisInMonths * -1).clearTime()

        String sql = """ select
                            ctr.provider.id
                        from
                            CreditTransferRequest ctr
                        where
                            confirmedDate >= :referenceDate
                            and ctr.provider.companyType in :companyTypes
                            and ctr.provider.status not in :inactiveCustomerStatus
                            and ctr.deleted = false
                            and not exists (
                                select 1
                                from RiskAnalysisRequest rar
                                join rar.reasons rarr
                                where rar.customer.id = ctr.provider.id
                                and rar.dateCreated >= :referenceDate
                                and rarr.riskAnalysisReason = :riskAnalysisReason
                                and rar.deleted = false
                                and rarr.deleted = false
                            )
                        group by ctr.provider.id
                        having
                            sum(ctr.value) > :minAmountValueTransfered
                            and (sum(case when ctr.provider.cpfCnpj <> ctr.originalCpfCnpj then ctr.value else 0 end) / sum(ctr.value)) >= :minTransfersAmountPercentageToThirdPartyAccount
                            and count(ctr.provider.id) >= :minNumberCreditTransfers """

        Map queryParams = [
                minAmountValueTransfered: minAmountValueTransfered,
                referenceDate: referenceDate,
                companyTypes: companyTypeList,
                riskAnalysisReason: riskAnalysisReason,
                minNumberCreditTransfers: minNumberCreditTransfers,
                minTransfersAmountPercentageToThirdPartyAccount: minTransfersAmountPercentageToThirdPartyAccount / 100,
                inactiveCustomerStatus: CustomerStatus.inactive()
        ]

        List<Long> customerIdList = CreditTransferRequest.executeQuery(sql, queryParams)

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(customerId)

                riskAnalysisRequestService.save(customer, riskAnalysisReason, null)
            }, [logErrorMessage: "MoneyLauderingService.createRiskAnalysisByCreditTransferToThirdParty >> Falha ao gerar análise por suspeita de lavagem de dinheiro por transferência [${customerId}]."])
        })
    }

    public void createToAnalyzeSuspectMoneyLaundering() {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.SUSPECT_OF_MONEY_LAUNDERING
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        final Integer pendingRegisterStatusDaysLimit = 20
        final BigDecimal minimumBalanceAmount = 5000.00

        Map search = [:]
        search.column = "customer.id"
        Date analysisDate = CustomDateUtils.sumDays(new Date(), -pendingRegisterStatusDaysLimit)
        search."dateCreated[ge]" = analysisDate
        search."dateCreated[le]" = CustomDateUtils.setTimeToEndOfDay(analysisDate)
        search.commercialInfoOrDocumentStatusNotApproved = true
        search.generalApprovalList = [GeneralApprovalStatus.PENDING, GeneralApprovalStatus.AWAITING_APPROVAL]

        List<Long> customerIdList = CustomerRegisterStatus.query(search).list()

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(customerId)

                if (CustomerStatus.inactive().contains(customer.status)) return
                if (customer.hadGeneralApproval()) return

                BigDecimal currentBalance = FinancialTransaction.getCustomerBalance(customer)

                if (currentBalance > minimumBalanceAmount) {
                    riskAnalysisRequestService.save(customer, riskAnalysisReason, null)
                }
            }, [logErrorMessage: "MoneyLaunderingRiskAnalysisService.createToAnalyzeSuspectMoneyLaundering >> Falha ao gerar análise por suspeita de lavagem de dinheiro [${customerId}]."])
        })
    }
}

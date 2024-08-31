package com.asaas.service.customerdebtappropriation

import com.asaas.domain.customerdebtappropriation.CustomerLossProvision
import com.asaas.domain.financialstatement.FinancialStatement
import com.asaas.financialstatement.FinancialStatementType
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class CustomerLossProvisionFinancialStatementService {

    def financialStatementService
    def financialStatementItemService

    public void create() {
        try {
            final BigDecimal limitValueForLossProvisionBelowOneYear = 100
            final Integer limitDaysForLossProvisionUpToOneHundred = 364
            List<CustomerLossProvision> lossProvisionListUpToOneHundred = listLossProvision(["negativeValueWithoutPartnerSettlement[le]": limitValueForLossProvisionBelowOneYear, "daysWithNegativeBalance[le]": limitDaysForLossProvisionUpToOneHundred])

            final Integer limitDaysForLossProvisionAboveOneHundred = 547
            List<CustomerLossProvision> lossProvisionListAboveOneHundred = listLossProvision(["negativeValueWithoutPartnerSettlement[gt]": limitValueForLossProvisionBelowOneYear, "daysWithNegativeBalance[le]": limitDaysForLossProvisionAboveOneHundred])

            List<CustomerLossProvision> lossProvisionList = lossProvisionListUpToOneHundred + lossProvisionListAboveOneHundred

            if (!lossProvisionList) return

            BigDecimal totalProvisionedValue = lossProvisionList.provisionedValue.sum()

            FinancialStatementType debitType = FinancialStatementType.CUSTOMER_LOSS_PROVISION_DEBIT
            FinancialStatement debitFinancialStatement = financialStatementService.save(debitType, CustomDateUtils.getLastDayOfLastMonth(), null, totalProvisionedValue)

            FinancialStatementType creditType = FinancialStatementType.CUSTOMER_LOSS_PROVISION_CREDIT
            FinancialStatement creditFinancialStatement = financialStatementService.save(creditType, CustomDateUtils.getFirstDayOfCurrentMonth(), null, totalProvisionedValue)

            for (CustomerLossProvision lossProvision : lossProvisionList) {
                financialStatementItemService.save(debitFinancialStatement, lossProvision)
                financialStatementItemService.save(creditFinancialStatement, lossProvision)
            }
        } catch (Exception exception) {
            AsaasLogger.error("CustomerLossProvisionFinancialStatementService.create >> Erro ao criar lançamentos de provisão de perdas", exception)
        }
    }

    private List<CustomerLossProvision> listLossProvision(Map params) {
        Map search = [:]
        search."provisionedValue[gt]" = 0
        search."dateCreated[ge]" = CustomDateUtils.getFirstDayOfCurrentMonth().clearTime()
        search."dateCreated[le]" = CustomDateUtils.setTimeToEndOfDay(CustomDateUtils.getFirstDayOfCurrentMonth())
        search."financialStatementTypeList[notExists]" = [FinancialStatementType.CUSTOMER_LOSS_PROVISION_DEBIT, FinancialStatementType.CUSTOMER_LOSS_PROVISION_CREDIT]
        search.disableSort = true

        return CustomerLossProvision.query(search + params).list()
    }
}

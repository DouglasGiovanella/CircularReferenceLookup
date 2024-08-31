package com.asaas.service.regulatory.cadoc

import com.asaas.customer.CustomerStatus
import com.asaas.customer.DisabledReason
import com.asaas.customerdatarestriction.CustomerDataRestrictionType
import com.asaas.domain.customerbalance.CustomerDailyBalanceConsolidation
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.log.AsaasLogger

import grails.transaction.Transactional

@Transactional
class SvrValuesToRefundService {

    public List<Map> redirectToGetValuesToRefundOfMonth(Long customerId, Date baseDate) {
        List<Map> resultList = getValuesToRefundOfMonth(customerId, baseDate)
        List<Map> resultTemporaryList = getValuesToRefundOfMonthTemporary(customerId, baseDate)

        if (resultList.toSet() != resultTemporaryList.toSet()) {
            AsaasLogger.error("SvrValuesToRefundService.redirectToGetValuesToRefundOfMonth -> Divergência entre dados das queries. \nFinancialTransaction: ${resultTemporaryList} \nCustomerDailyBalanceConsolidation: ${resultList}")
            return resultTemporaryList
        }

        return resultList
    }

    private List<Map> getValuesToRefundOfMonth(Long customerId, Date baseDate) {
        List<List> customerDisabledWithValuesToRefundList = CustomerDailyBalanceConsolidation.executeQuery('''
select c.id, c.cpfCnpj, cdbc.consolidatedBalance
from CustomerDailyBalanceConsolidation cdbc
inner join cdbc.customer c
where cdbc.consolidationDate = (
    select max(cdbctemp.consolidationDate)
    from CustomerDailyBalanceConsolidation cdbctemp
    where cdbctemp.customer.id = cdbc.customer.id
    and cdbctemp.consolidationDate <= :baseDate
    and cdbctemp.deleted = :deleted
)
and c.deleted = :deleted
and c.status = :status
and c.cpfCnpj is not null
and (c.suspectedOfFraud = :deleted or c.suspectedOfFraud is null)
and not exists (
    select 1 from CustomerDataRestriction restriction
    where restriction.value = c.cpfCnpj
and restriction.deleted = :deleted
and restriction.type = :type
)
and not exists (
    select 1 from CustomerDisabledReason cdr
    where cdr.customer.id = c.id
    and cdr.disabledReason = :disabledReason
    and cdr.deleted = :deleted
)
and c.id > :customerId
and cdbc.consolidatedBalance > 0
group by c.id
order by c.id asc
''', [deleted: false, baseDate: baseDate, status: CustomerStatus.DISABLED, type: CustomerDataRestrictionType.CPF_CNPJ, customerId: customerId, disabledReason: DisabledReason.CONFIRMED_FRAUD], [max: 20, readOnly: true])

        List<Map> resultList = []
        for (List row : customerDisabledWithValuesToRefundList) {
            Map resultMap = [
                recipientCpfCnpj: row[1],
                refundableValue: row[2],
                customerId: row[0]
            ]

            resultList.add(resultMap)
        }

        return resultList
    }

    private List<Map> getValuesToRefundOfMonthTemporary(Long customerId, Date baseDate) {
        List<List> customerDisabledWithValuesToRefundList = FinancialTransaction.executeQuery('''
select c.id, c.cpfCnpj, sum(ft.value)
from FinancialTransaction ft
inner join ft.provider c
where ft.deleted = :deleted
and ft.dateCreated <= :baseDate
and c.deleted = :deleted
and c.status = :status
and c.cpfCnpj is not null
and (c.suspectedOfFraud = :deleted or c.suspectedOfFraud is null)
and not exists (
    select 1 from CustomerDataRestriction restriction
    where restriction.value = c.cpfCnpj
and restriction.deleted = :deleted
and restriction.type = :type
)
and not exists (
    select 1 from CustomerDisabledReason cdr
    where cdr.customer.id = c.id
    and cdr.disabledReason = :disabledReason
    and cdr.deleted = :deleted
)
and c.id > :customerId
group by c.id
having sum(ft.value) > 0
order by c.id asc
''', [deleted: false, baseDate: baseDate, status: CustomerStatus.DISABLED, type: CustomerDataRestrictionType.CPF_CNPJ, customerId: customerId, disabledReason: DisabledReason.CONFIRMED_FRAUD], [max: 20, readOnly: true])

        List<Map> resultList = []
        for (List row : customerDisabledWithValuesToRefundList) {
            Map resultMap = [
                recipientCpfCnpj: row[1],
                refundableValue: row[2],
                customerId: row[0]
            ]

            resultList.add(resultMap)
        }

        return resultList
    }
}

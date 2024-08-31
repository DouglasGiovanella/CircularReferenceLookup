package com.asaas.service.regulatory.cadoc

import com.asaas.debit.DebitType
import com.asaas.domain.debit.Debit
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class SvrRefundedValuesService {

    public List<Debit> getRefundedValuesOfMonth(Date date) {
        Date startDate = CustomDateUtils.getFirstDayOfMonth(date)
        Date finalDate = CustomDateUtils.getLastDayOfMonth(date)

        Map queryParams = [disableSort: true, 'debitDate[ge]': startDate, 'debitDate[le]': finalDate, type: DebitType.SVR_REFUND_VALUE, ignoreCustomer: true]
        List<Debit> debitList = Debit.query(queryParams).list(readOnly: true)

        return debitList
    }
}

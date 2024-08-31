package com.asaas.service.scd

import com.asaas.domain.financialstatement.FinancialStatementItem
import com.asaas.domain.scd.ScdFinancialStatementItemInfo
import com.asaas.financialstatement.adapter.FinancialStatementItemAdapter
import grails.transaction.Transactional

@Transactional
class ScdFinancialStatementItemInfoService {

    public void save(FinancialStatementItemAdapter adapter, FinancialStatementItem financialStatementItem) {
        ScdFinancialStatementItemInfo itemInfo = new ScdFinancialStatementItemInfo()
        itemInfo.cpfCnpj = adapter.cpfCnpj
        itemInfo.publicId = adapter.publicId
        itemInfo.financialStatementItem = financialStatementItem
        itemInfo.save(failOnError: true)
    }
}

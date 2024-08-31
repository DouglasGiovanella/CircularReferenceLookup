package com.asaas.service.receivableunit

import com.asaas.creditcard.CreditCardBrand
import com.asaas.domain.billinginfo.CreditCardInfo
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class ReceivableUnitItemMonitorService {

    public void alertCreditCardInfoWithUnknownBrand() {
        Map search = [:]
        search."distinct" = "id"
        search."dateCreated[ge]" = CustomDateUtils.getYesterday()
        search."brand" = CreditCardBrand.UNKNOWN
        search."disableSort" = true

        List<Long> creditCardInfoIdList = CreditCardInfo.query(search).list()

        for (Long creditCardInfoId : creditCardInfoIdList) {
            AsaasLogger.warn("ReceivableUnitItemMonitorService.alertCreditCardInfoWithUnknownBrand - creditCardInfoId ${creditCardInfoId}")
        }
    }
}

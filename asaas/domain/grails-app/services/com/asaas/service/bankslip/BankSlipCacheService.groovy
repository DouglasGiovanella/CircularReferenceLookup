package com.asaas.service.bankslip

import com.asaas.billinginfo.BillingType
import com.asaas.domain.bank.Bank
import com.asaas.domain.boleto.BoletoReturnFile
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentConfirmRequest
import com.asaas.status.Status
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class BankSlipCacheService {

    @Cacheable(value = "BankSlip:isReadyToProcessOverdue", key = "#root.target.generateCacheKey(#boletoBankIdList)")
    public Boolean isReadyToProcessOverdue(List<Long> boletoBankIdList) {
        if (Holiday.isHoliday(new Date())) return false

        Boolean withBoletoReturnFile = true
        if (!boletoBankIdList || boletoBankIdList.first() == Payment.SMARTBANK_BOLETO_BANK_ID) {
            withBoletoReturnFile = false
        }

        if (withBoletoReturnFile) {
            Boolean existsBoletoReturnFile = BoletoReturnFile.query([exists: true, "boletoBankId[in]": boletoBankIdList, dateCreated: new Date()]).get().asBoolean()
            if (!existsBoletoReturnFile) return null
        }

        if (existsPaymentConfirmRequestWithStatus(boletoBankIdList, [Status.PENDING], withBoletoReturnFile)) return null

        Boolean existsBankSlipAllowedToProcessOverdue = existsPaymentConfirmRequestWithStatus(boletoBankIdList, [Status.APPROVED, Status.SUCCESS], withBoletoReturnFile)

        if (!existsBankSlipAllowedToProcessOverdue) return null

        return true
    }

    public String generateCacheKey(List<Long> boletoBankIdList) {
        String formatedDate = CustomDateUtils.fromDate(new Date(), CustomDateUtils.DATABASE_DATE_FORMAT)
        String boletoBankKey = boletoBankIdList ? boletoBankIdList.join('_') : "NULL"
        return "${formatedDate}:BOLETO_BANK_ID_${boletoBankKey}"
    }

    private Boolean existsPaymentConfirmRequestWithStatus(List<Long> boletoBankIdList, List<Status> statusList, Boolean withBoletoReturnFile) {
        Map defaultParams = [exists: true, "status[in]": statusList, "dateCreated[ge]": new Date().clearTime(), billingType: BillingType.BOLETO]

        if (withBoletoReturnFile) defaultParams."boletoReturnFile[isNotNull]" = true

        if (!boletoBankIdList) {
            return PaymentConfirmRequest.query(defaultParams + ["boletoBank[isNull]": true, paymentBank: Bank.findByCode(SupportedBank.SANTANDER.code())]).get().asBoolean()
        }

        return PaymentConfirmRequest.query(defaultParams + ["boletoBankId[in]": boletoBankIdList]).get().asBoolean()
    }
}

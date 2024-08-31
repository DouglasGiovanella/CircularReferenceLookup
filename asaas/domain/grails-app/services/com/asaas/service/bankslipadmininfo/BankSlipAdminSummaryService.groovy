package com.asaas.service.bankslipadmininfo

import com.asaas.boleto.returnfile.BoletoReturnStatus
import com.asaas.domain.bankslipadminsummary.BankSlipAdminSummary
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoReturnFileItem
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentConfirmRequestGroup
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class BankSlipAdminSummaryService {

    public void queryAndSavePaidBankSlipSummary() {
        Date currentDate = CustomDateUtils.setTimeToEndOfDay(new Date())

        if (Holiday.isHoliday(currentDate)) return

        Date paymentDate = CustomDateUtils.getYesterday()

        while (Holiday.isHoliday(paymentDate)) {
            paymentDate = CustomDateUtils.sumDays(paymentDate, -1)
        }

        List<Long> boletoBankIdList = buildBoletoBankIdList()

        for (Long boletoBankId : boletoBankIdList) {
            BankSlipAdminSummary bankSlipAdminSummary = BankSlipAdminSummary.query([boletoBankId: boletoBankId, paymentDate: paymentDate]).get()
            if (!bankSlipAdminSummary) {
                bankSlipAdminSummary = new BankSlipAdminSummary()
                bankSlipAdminSummary.boletoBank = BoletoBank.read(boletoBankId)
                bankSlipAdminSummary.paymentDate = paymentDate
            }

            Map dataMap = queryPaidBankSlipData(boletoBankId, paymentDate, currentDate)

            bankSlipAdminSummary.paidRegisteredCount = dataMap.paidRegisteredCount
            bankSlipAdminSummary.paidRegisteredTotalFee = dataMap.paidRegisteredTotalFee
            bankSlipAdminSummary.paidUnregisteredCount = dataMap.paidUnregisteredCount
            bankSlipAdminSummary.paidUnregisteredTotalFee = dataMap.paidUnregisteredTotalFee
            bankSlipAdminSummary.paidAmount = dataMap.paidAmount
            bankSlipAdminSummary.registeredBankSlipFee = dataMap.registeredBankSlipFee
            bankSlipAdminSummary.unregisteredBankSlipFee = dataMap.unregisteredBankSlipFee
            bankSlipAdminSummary.save(failOnError: true)
        }
    }

    public List<BankSlipAdminSummary> getPaidBankSlipSummaryList(Date paymentDate) {
        return BankSlipAdminSummary.query([paymentDate: paymentDate, sort: "id", order: "asc"]).list()
    }

    private List<Long> buildBoletoBankIdList() {
        return [
            Payment.BB_BOLETO_BANK_ID, Payment.SANTANDER_ONLINE_BOLETO_BANK_ID, Payment.SANTANDER_BOLETO_BANK_ID,
            Payment.SAFRA_BOLETO_BANK_ID, Payment.SICREDI_BOLETO_BANK_ID, Payment.SMARTBANK_BOLETO_BANK_ID,
            Payment.BRADESCO_ONLINE_BOLETO_BANK_ID, Payment.ASAAS_ONLINE_BOLETO_BANK_ID
        ]
    }

    private Map queryPaidBankSlipData(Long boletoBankId, Date startDate, Date finishDate) {
        BoletoBank boletoBank = BoletoBank.get(boletoBankId)

        Map resultMap = [
            boletoBankId: boletoBankId,
            paymentDate: startDate,
            paidRegisteredCount: 0,
            paidRegisteredTotalFee: 0,
            paidUnregisteredCount: 0,
            paidUnregisteredTotalFee: 0,
            paidAmount: 0.0,
            registeredBankSlipFee: getBankSlipFee(boletoBankId, false),
            unregisteredBankSlipFee: getBankSlipFee(boletoBankId, true)
        ]

        Map boletoReturnFileIdSearch = [startDate: startDate, finishDate: finishDate, paymentBank: boletoBank.bank, boletoBank: boletoBank]
        List<Long> boletoReturnFileIdList = PaymentConfirmRequestGroup.executeQuery("select pcrg.boletoReturnFile.id from PaymentConfirmRequestGroup pcrg where pcrg.deleted = false and pcrg.settlementStartDate between :startDate and :finishDate and pcrg.boletoReturnFile is not null and pcrg.paymentBank = :paymentBank and pcrg.boletoReturnFile.boletoBank = :boletoBank", boletoReturnFileIdSearch)

        if (!boletoReturnFileIdList) return resultMap

        Map searchMap = [boletoReturnFileIdList: boletoReturnFileIdList, paidStatusList: [BoletoReturnStatus.PAID, BoletoReturnStatus.PAID_UNREGISTERED], ocurrenceDate: startDate]
        List boletoReturnFileItemDataList = BoletoReturnFileItem.executeQuery("select count(brfi), sum(brfi.paidValue), brfi.returnStatus from BoletoReturnFileItem brfi where brfi.boletoReturnFile.id in (:boletoReturnFileIdList) and brfi.returnStatus in (:paidStatusList) and brfi.ocurrenceDate = :ocurrenceDate and brfi.deleted = false group by brfi.returnStatus", searchMap)

        if (!boletoReturnFileItemDataList) return resultMap

        BigDecimal totalPaid = 0.0

        for (List boletoReturnFileItemData : boletoReturnFileItemDataList) {
            totalPaid += boletoReturnFileItemData[1]

            switch (boletoReturnFileItemData[2]) {
                case BoletoReturnStatus.PAID.toString():
                    resultMap.paidRegisteredCount = boletoReturnFileItemData[0]
                    resultMap.paidRegisteredTotalFee = boletoReturnFileItemData[0] * getBankSlipFee(boletoBankId, false)
                    break
                case BoletoReturnStatus.PAID_UNREGISTERED.toString():
                    resultMap.paidUnregisteredCount = boletoReturnFileItemData[0]
                    resultMap.paidUnregisteredTotalFee = boletoReturnFileItemData[0] * getBankSlipFee(boletoBankId, true)
                    break
            }
        }

        resultMap.paidAmount = totalPaid

        return resultMap
    }

    private BigDecimal getBankSlipFee(Long boletoBankId, Boolean isUnregistered) {
        if (!isUnregistered) {
            return BoletoBank.query([column: "bankSlipFee", id: boletoBankId]).get()
        }

        BigDecimal bankSlipFee = 0.0

        switch (boletoBankId) {
            case Payment.BB_BOLETO_BANK_ID:
                bankSlipFee = BoletoBank.BANCO_DO_BRASIL_UNREGISTERED_BANK_SLIP_FEE
                break
            case Payment.SANTANDER_BOLETO_BANK_ID:
            case Payment.SANTANDER_ONLINE_BOLETO_BANK_ID:
                bankSlipFee = BoletoBank.SANTANDER_UNREGISTERED_BANK_SLIP_FEE
                break
            case Payment.BRADESCO_ONLINE_BOLETO_BANK_ID:
                bankSlipFee = BoletoBank.BRADESCO_UNREGISTERED_BANK_SLIP_FEE
                break
            case Payment.SAFRA_BOLETO_BANK_ID:
                bankSlipFee = BoletoBank.SAFRA_UNREGISTERED_BANK_SLIP_FEE
                break
        }

        return bankSlipFee
    }

}

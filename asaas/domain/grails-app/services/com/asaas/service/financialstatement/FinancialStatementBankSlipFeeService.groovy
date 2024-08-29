package com.asaas.service.financialstatement

import com.asaas.boleto.BankSlipPaymentChannel
import com.asaas.boleto.returnfile.BoletoReturnStatus
import com.asaas.domain.bank.Bank
import com.asaas.domain.bankslip.PaymentBankSlipInfo
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoReturnFileItem
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import grails.transaction.Transactional

@Transactional
class FinancialStatementBankSlipFeeService {

    def grailsApplication

    public BigDecimal calculateFee(List<Payment> bankSlipPaymentList, Bank bank) {
        BoletoBank boletoBank = bankSlipPaymentList.first().boletoBank ?: BoletoBank.query([bankCode: bank.code]).get()

        if (![SupportedBank.SANTANDER.code(), SupportedBank.BRADESCO.code(), SupportedBank.ASAAS.code()].contains(boletoBank.bank.code)) {
            return bankSlipPaymentList.size() * boletoBank.bankSlipFee
        }

        List<String> nossoNumeroList = bankSlipPaymentList.collect { it.nossoNumero }

        for (Payment payment : bankSlipPaymentList) {
            List<String> nossoNumeroHistoryList = payment.paymentHistory.collect { it.nossoNumero }
            nossoNumeroList.addAll(nossoNumeroHistoryList)
        }

        nossoNumeroList.addAll(PaymentBankSlipInfo.query([column: "nossoNumero", "paymentId[in]": bankSlipPaymentList.collect { it.id }]).list())

        List<Payment> duplicatedPaymentOriginList = bankSlipPaymentList.findAll { it.duplicatedPayment && it.duplicatedPayment.nossoNumero && !it.duplicatedPayment.billingType.isBoleto() }
        if (duplicatedPaymentOriginList) nossoNumeroList.addAll(duplicatedPaymentOriginList.collect { it.duplicatedPayment.nossoNumero })

        final Integer nossoNumeroListCollateSize = 1000
        if (boletoBank.bank.code == SupportedBank.ASAAS.code()) {
            return calculateAsaasFee(nossoNumeroList, boletoBank, nossoNumeroListCollateSize)
        }

        Integer registeredBankSlipCount = 0
        for (List<String> nossoNumeroPartialList : nossoNumeroList.collate(nossoNumeroListCollateSize)) {
            registeredBankSlipCount += BoletoReturnFileItem.query([nossoNumeroList: nossoNumeroPartialList, "paidValue[gt]": 0.0g, returnStatusList: BoletoReturnStatus.PAID, "boletoReturnFile.boletoBank": boletoBank]).count()
        }

        Integer unregisteredBankSlipCount = bankSlipPaymentList.size() - registeredBankSlipCount
        BigDecimal unregisteredBankSlipFee = boletoBank.bankSlipFee

        if (boletoBank.bank.code == SupportedBank.BRADESCO.code()) unregisteredBankSlipFee = BoletoBank.BRADESCO_UNREGISTERED_BANK_SLIP_FEE

        return (unregisteredBankSlipCount * unregisteredBankSlipFee) + (registeredBankSlipCount * boletoBank.bankSlipFee)
    }

    private BigDecimal calculateAsaasFee(List<String> nossoNumeroList, BoletoBank boletoBank, Integer nossoNumeroListCollateSize) {
        BigDecimal registeredBankSlipTotalValue = 0.00

        for (List<String> nossoNumeroPartialList : nossoNumeroList.collate(nossoNumeroListCollateSize)) {
            List<Map> countByPaymentChannelList = BoletoReturnFileItem.countByPaymentChannel([nossoNumeroList: nossoNumeroPartialList, returnStatusList: BoletoReturnStatus.PAID, "boletoReturnFile.boletoBank": boletoBank]).list()
            for (Map countByPaymentChannel : countByPaymentChannelList) {
                BankSlipPaymentChannel asaasBankSlipPaymentChannel = BankSlipPaymentChannel.parseCode(countByPaymentChannel.paymentChannel)

                if (!asaasBankSlipPaymentChannel) {
                    AsaasLogger.error("FinancialStatementBankSlipFeeService.calculateAsaasFee >> Canal de pagamento não encontrado. [paymentChannel: ${countByPaymentChannel.paymentChannel}]")
                    continue
                }

                BigDecimal fee = grailsApplication.config.asaas.bankSlip.paymentChannel."${asaasBankSlipPaymentChannel}".fee
                registeredBankSlipTotalValue += (countByPaymentChannel.count * fee)
            }
        }

        return registeredBankSlipTotalValue
    }
}

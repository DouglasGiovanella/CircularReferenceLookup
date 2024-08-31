package com.asaas.service.bankslip

import com.asaas.bankslip.adapter.BankSlipPayerInfoAdapter
import com.asaas.domain.bankslip.BankSlipPayerInfo
import com.asaas.domain.bankslip.BankSlipPayerInstitution
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentConfirmRequest
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional
import grails.validation.ValidationException
import org.apache.commons.lang.NotImplementedException

@Transactional
class BankSlipPayerInfoService {

    def asaasBankSlipRegistrationService
    def paymentService

    public BankSlipPayerInfo saveForBoletoBank(Long paymentId, Long boletoBankId) {
        Payment payment = Payment.read(paymentId)

        if (Payment.ASAAS_ONLINE_BOLETO_BANK_ID == payment.boletoBankId) {
            return saveAsaasBankSlipPayerInfo(payment)
        }

        throw new NotImplementedException("Consulta de dados do pagador não implementada para o boletoBankId: ${boletoBankId}")
    }

    private BankSlipPayerInfo saveAsaasBankSlipPayerInfo(Payment payment) {
        BankSlipPayerInfoAdapter bankSlipPayerInfoAdapter = asaasBankSlipRegistrationService.getPayerInfo(payment.nossoNumero, payment.clientPaymentDate)
        if (!bankSlipPayerInfoAdapter) return

        bankSlipPayerInfoAdapter.payment = payment
        Map receiverBankInfo = PaymentConfirmRequest.query([columnList: ["receiverBankCode", "receiverAgency"], payment: payment]).get()
        bankSlipPayerInfoAdapter.receiverAgency = receiverBankInfo.receiverAgency
        bankSlipPayerInfoAdapter.bankSlipPayerInstitution = BankSlipPayerInstitution.query([code: receiverBankInfo.receiverBankCode]).get()

        if (!bankSlipPayerInfoAdapter.bankSlipPayerInstitution) {
            AsaasLogger.warn("BankSlipPayerInfoService.saveAsaasBankSlipPayerInfo >>> Instituição pagadora não encontrada. [paymentId: ${payment.id},  receiverBankCode: ${receiverBankInfo.receiverBankCode}]")
        }

        return save(bankSlipPayerInfoAdapter)
    }

    private BankSlipPayerInfo save(BankSlipPayerInfoAdapter adapter) {
        BankSlipPayerInfo validatedDomain = validateSave(adapter)
        if (validatedDomain.hasErrors()) throw new ValidationException("Erro ao salvar a BankSlipPayerInfo", validatedDomain.errors)

        BankSlipPayerInfo bankSlipPayerInfo = new BankSlipPayerInfo()
        bankSlipPayerInfo.payment = adapter.payment
        bankSlipPayerInfo.bankSlipPayerInstitution = adapter.bankSlipPayerInstitution
        bankSlipPayerInfo.receiverAgency = adapter.receiverAgency
        bankSlipPayerInfo.holderPersonType = adapter.holderPersonType
        bankSlipPayerInfo.holderCpfCnpj = adapter.holderCpfCnpj
        bankSlipPayerInfo.holderName = adapter.holderName
        bankSlipPayerInfo.paidValue = adapter.paidValue
        bankSlipPayerInfo.paymentChannel = adapter.paymentChannel
        bankSlipPayerInfo.paymentMethod = adapter.paymentMethod
        bankSlipPayerInfo.barCode = adapter.barCode
        bankSlipPayerInfo.digitableLine = adapter.digitableLine

        return bankSlipPayerInfo.save(failOnError: true)
    }

    private BankSlipPayerInfo validateSave(BankSlipPayerInfoAdapter adapter) {
        BankSlipPayerInfo bankSlipPayerInfo = new BankSlipPayerInfo()

        if (!adapter.payment) return DomainUtils.addError(bankSlipPayerInfo, "Cobrança não encontrada!")

        if (!adapter.paidValue) return DomainUtils.addError(bankSlipPayerInfo, "Valor do pagamento não informado!")

        if (!adapter.barCode) return DomainUtils.addError(bankSlipPayerInfo, "Código de barras do pagamento não informado!")

        return bankSlipPayerInfo
    }
}

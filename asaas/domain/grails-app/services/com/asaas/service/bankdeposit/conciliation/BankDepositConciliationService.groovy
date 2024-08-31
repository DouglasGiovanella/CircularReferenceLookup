package com.asaas.service.bankdeposit.conciliation

import com.asaas.bankdepositstatus.BankDepositStatus
import com.asaas.domain.bankdeposit.BankDeposit
import com.asaas.domain.bankdeposit.BankDepositConciliation
import com.asaas.domain.invoicedepositinfo.InvoiceDepositInfo
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentConfirmRequest
import com.asaas.domain.paymentdepositreceipt.PaymentDepositReceipt
import com.asaas.log.AsaasLogger
import com.asaas.paymentdepositreceiptstatus.PaymentDepositReceiptStatus
import com.asaas.status.Status
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class BankDepositConciliationService {

    def paymentConfirmRequestService
    def paymentDepositReceiptService

    public BankDepositConciliation conciliate(List<Long> bankDepositIdList, List<Long> paymentDepositReceiptIdList, List<Long> paymentDepositReceiptIdListWithPaymentDuplicated) {
        List<BankDeposit> bankDepositList = BankDeposit.getAll(bankDepositIdList)
        List<PaymentDepositReceipt> paymentDepositReceiptList = PaymentDepositReceipt.getAll(paymentDepositReceiptIdList)
        List<PaymentDepositReceipt> paymentDepositReceiptListWithPaymentDuplicated = PaymentDepositReceipt.getAll(paymentDepositReceiptIdListWithPaymentDuplicated)

        BankDepositConciliation validatedConciliation = validateConciliation(bankDepositList, paymentDepositReceiptList, paymentDepositReceiptListWithPaymentDuplicated)

        if (validatedConciliation.hasErrors()) return validatedConciliation

        BankDepositConciliation bankDepositConciliation = processConciliation(bankDepositList, paymentDepositReceiptList)

        return bankDepositConciliation
    }

    public BankDepositConciliation conciliate(Long bankDepositId, Long invoiceDepositInfoId) {
        BankDeposit bankDeposit = BankDeposit.get(bankDepositId)
        InvoiceDepositInfo invoiceDepositInfo = InvoiceDepositInfo.get(invoiceDepositInfoId)

        BankDepositConciliation validatedConciliation = validateConciliation(bankDeposit, invoiceDepositInfo)
        if (validatedConciliation.hasErrors()) return validatedConciliation

        BankDepositConciliation bankDepositConciliation = processConciliation(bankDeposit, invoiceDepositInfo)
        if (bankDepositConciliation.hasErrors()) return bankDepositConciliation

        paymentDepositReceiptService.cancelIfExists(bankDepositConciliation.payment)

        return bankDepositConciliation
    }

    private BankDepositConciliation save(BankDeposit bankDeposit, PaymentDepositReceipt paymentDepositReceipt) {
        paymentDepositReceipt.status = PaymentDepositReceiptStatus.CONCILIATED
        paymentDepositReceipt.save(failOnError: true)

        bankDeposit.conciliationUser = UserUtils.getCurrentUser()
        bankDeposit.conciliationDate = new Date()
        bankDeposit.status = BankDepositStatus.CONCILIATED
        bankDeposit.save(failOnError: true)

        BankDepositConciliation bankDepositConciliation = new BankDepositConciliation()

        bankDepositConciliation.paymentDepositReceipt = paymentDepositReceipt
        bankDepositConciliation.payment = paymentDepositReceipt.payment
        bankDepositConciliation.bankDeposit = bankDeposit
        bankDepositConciliation.save(failOnError: true)

        return bankDepositConciliation
    }

    private BankDepositConciliation save(BankDeposit bankDeposit, Payment payment) {
        bankDeposit.conciliationUser = UserUtils.getCurrentUser()
        bankDeposit.conciliationDate = new Date()
        bankDeposit.status = BankDepositStatus.CONCILIATED

        BankDepositConciliation bankDepositConciliation = new BankDepositConciliation()
        bankDepositConciliation.payment = payment
        bankDepositConciliation.bankDeposit = bankDeposit
        bankDepositConciliation.save(failOnError: true)

        return bankDepositConciliation
    }

    private BankDepositConciliation validateConciliation(BankDeposit bankDeposit, InvoiceDepositInfo invoiceDepositInfo) {
        BankDepositConciliation bankDepositConciliation = new BankDepositConciliation()

        if (!bankDeposit) DomainUtils.addError(bankDepositConciliation, "Nenhum depósito bancário selecionado.")

        if (!invoiceDepositInfo) DomainUtils.addError(bankDepositConciliation, "Nenhuma informação de depósito da fatura.")
        if (bankDepositConciliation.hasErrors()) return bankDepositConciliation

        if (invoiceDepositInfo.payment.value != bankDeposit.value) {
            DomainUtils.addError(bankDepositConciliation, "O valor total de baixa dos itens selecionados é diferente do valor total dos registros do extrato.")
            return bankDepositConciliation
        }

        if (invoiceDepositInfo.payment.isReceivingProcessInitiated()) {
            DomainUtils.addError(bankDepositConciliation, "Não foi possível efetuar a conciliação, pois a cobrança deve estar pendente de recebimento. Cobrança já confirmada: ${invoiceDepositInfo.payment.id}")
            return bankDepositConciliation
        }

        return bankDepositConciliation
    }

    private BankDepositConciliation validateConciliation(List<BankDeposit> bankDepositList, List<PaymentDepositReceipt> paymentDepositReceiptList, List<PaymentDepositReceipt> paymentDepositReceiptListWithPaymentDuplicated) {
        BankDepositConciliation bankDepositConciliation = new BankDepositConciliation()

        if (!bankDepositList) DomainUtils.addError(bankDepositConciliation, "Nenhum depósito bancário selecionado.")

        if (!paymentDepositReceiptList) DomainUtils.addError(bankDepositConciliation, "Nenhum comprovante selecionado.")

        if (bankDepositConciliation.hasErrors()) return bankDepositConciliation

        BigDecimal totalConciliatedValue = 0
        List<Long> duplicatedPaymentConfirmationUncheckedList = []

        for (PaymentDepositReceipt paymentDepositReceipt in paymentDepositReceiptList) {
            totalConciliatedValue += paymentDepositReceipt.conciliatedValue

            if (paymentDepositReceipt.payment.isReceivingProcessInitiated() && !paymentDepositReceiptListWithPaymentDuplicated.contains(paymentDepositReceipt)) {
                duplicatedPaymentConfirmationUncheckedList.add(paymentDepositReceipt.paymentId)
            }
        }

        if (totalConciliatedValue != bankDepositList.value.sum()) {
            DomainUtils.addError(bankDepositConciliation, "O valor total de baixa dos itens selecionados é diferente do valor total dos registros do extrato.")
            return bankDepositConciliation
        }

        if (duplicatedPaymentConfirmationUncheckedList) {
            DomainUtils.addError(bankDepositConciliation, "Não foi possível efetuar a conciliação, pois todas as cobranças devem estar pendentes de recebimento. Se a cobrança foi paga em duplicidade marque-a como duplicada e concilie novamente. Cobranças já confirmadas: ${duplicatedPaymentConfirmationUncheckedList.join(", ")}")
            return bankDepositConciliation
        }

        return bankDepositConciliation
    }

    private Boolean hasUnexpectedReceivingError(Long groupId) {
        return PaymentConfirmRequest.query([exists: true, duplicatedPayment: false, groupId: groupId, status: Status.ERROR]).get().asBoolean()
    }

    private BankDepositConciliation processConciliation(List<BankDeposit> bankDepositList, List<PaymentDepositReceipt> paymentDepositReceiptList) {
        BankDepositConciliation bankDepositConciliation = new BankDepositConciliation()

        List<Map> paymentInfoList = buildPaymentInfoList(paymentDepositReceiptList)

        Map confirmResponse = paymentConfirmRequestService.receive(bankDepositList.first().billingType, bankDepositList.first().bank, paymentInfoList)
        if (!confirmResponse.success) {
            DomainUtils.addError(bankDepositConciliation, "Não foi possível realizar a conciliação, por favor contate a equipe de engenharia.")
            return bankDepositConciliation
        }

        if (hasUnexpectedReceivingError(confirmResponse.groupId)) {
            AsaasLogger.error("Status de ERRO do PaymentConfirmRequest não são por duplicidade. Depósitos bancários ${bankDepositList.collect{it.id}} com os comprovantes ${paymentDepositReceiptList.collect{it.id}}")
            DomainUtils.addError(bankDepositConciliation, "Erro ao conciliar registro com cobranças duplicadas, por favor contate a equipe de engenharia.")
            return bankDepositConciliation
        }

        processDuplicatedPayments(confirmResponse.groupId, paymentDepositReceiptList)

        for (BankDeposit bankDeposit in bankDepositList) {
            for (PaymentDepositReceipt paymentDepositReceipt in paymentDepositReceiptList) {
                bankDepositConciliation = save(bankDeposit, paymentDepositReceipt)
            }
        }

        return bankDepositConciliation
    }


    private BankDepositConciliation processConciliation(BankDeposit bankDeposit, InvoiceDepositInfo invoiceDepositInfo) {
        BankDepositConciliation bankDepositConciliation = new BankDepositConciliation()

        List<Map> paymentInfoList = buildPaymentInfoList(invoiceDepositInfo, bankDeposit.documentDate)

        Map confirmResponse = paymentConfirmRequestService.receive(bankDeposit.billingType, bankDeposit.bank, paymentInfoList)
        if (!confirmResponse.success) {
            DomainUtils.addError(bankDepositConciliation, "Não foi possível realizar a conciliação, por favor contate a equipe de engenharia.")
            return bankDepositConciliation
        }

        bankDepositConciliation = save(bankDeposit, invoiceDepositInfo.payment)

        return bankDepositConciliation
    }

    private List<Map> buildPaymentInfoList(List<PaymentDepositReceipt> paymentDepositReceiptList) {
        PaymentDepositReceipt firstPaymentDepositReceipt
        Map paymentInfo
        List<Map> paymentInfoList = []

        Map paymentDepositReceiptListGroupedByPayment = paymentDepositReceiptList.groupBy { it.payment }

        paymentDepositReceiptListGroupedByPayment.each { Payment payment, List<PaymentDepositReceipt> paymentDepositReceiptGroupedByPayment ->
            firstPaymentDepositReceipt = paymentDepositReceiptGroupedByPayment.first()

            paymentInfo = [:]
            paymentInfo.id = firstPaymentDepositReceipt.payment.id
            paymentInfo.nossoNumero = firstPaymentDepositReceipt.payment.nossoNumero
            paymentInfo.value = paymentDepositReceiptGroupedByPayment.conciliatedValue.sum()
            paymentInfo.date = firstPaymentDepositReceipt.documentDate.clone().clearTime()
            paymentInfo.creditDate = firstPaymentDepositReceipt.documentDate.clone().clearTime()
            paymentInfoList.add(paymentInfo)
        }

        return paymentInfoList
    }

    private List<Map> buildPaymentInfoList(InvoiceDepositInfo invoiceDepositInfo, Date documentDate) {
        List<Map> paymentInfoList = []

        Map paymentInfo = [:]
        paymentInfo.id = invoiceDepositInfo.payment.id
        paymentInfo.nossoNumero = invoiceDepositInfo.payment.nossoNumero
        paymentInfo.value = invoiceDepositInfo.payment.value
        paymentInfo.date = documentDate.clone().clearTime()
        paymentInfo.creditDate = documentDate.clone().clearTime()
        paymentInfoList.add(paymentInfo)

        return paymentInfoList
    }

    private void processDuplicatedPayments(Long groupId, List<PaymentDepositReceipt> paymentDepositReceiptList) {
        PaymentConfirmRequest paymentConfirmRequest
        Long previousPaymentId
        Payment newPayment

        for (PaymentDepositReceipt paymentDepositReceipt in paymentDepositReceiptList) {
            if (previousPaymentId != paymentDepositReceipt.paymentId) {
                paymentConfirmRequest = PaymentConfirmRequest.query([duplicatedPayment: true, groupId: groupId, status: Status.ERROR, paymentId: paymentDepositReceipt.paymentId]).get()
                if (!paymentConfirmRequest) continue

                previousPaymentId = paymentDepositReceipt.paymentId
                newPayment = paymentConfirmRequestService.processDuplicatedPayment(paymentConfirmRequest.id, true)
            }

            paymentDepositReceipt.payment = newPayment
            paymentDepositReceipt.save(failOnError: true)
        }
    }
}

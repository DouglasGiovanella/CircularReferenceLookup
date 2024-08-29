package com.asaas.service.bill

import com.asaas.bill.BillPaymentTransactionGateway
import com.asaas.bill.BillPaymentTransactionStatus
import com.asaas.bill.BillStatus
import com.asaas.bill.BillUtils
import com.asaas.boleto.BoletoUtils
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.domain.bank.Bank
import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillPaymentTransaction
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BillPayService {

    def billPaymentTransactionService
    def celcoinManagerService
    def checkoutNotificationService
    def customerAlertNotificationService
    def mobilePushNotificationService
    def paymentConfirmRequestService
    def pushNotificationRequestBillService
    def transactionReceiptService

    public Bill pay(Long billId, Long paymentBankId) {
        try {
            Bill bill = Bill.get(billId)

            bill = validatePayment(bill)
            if (bill.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(bill))

            bill.status = BillStatus.PAID
            bill.statusReason = null
            bill.paymentDate = new Date().clearTime()
            bill.paymentBank = paymentBankId ? Bank.get(paymentBankId) : null
            bill.save(flush: true, failOnError: true)

            transactionReceiptService.saveBillPaid(bill)

            Boolean hasAsaasMoneyTransactionInfo = AsaasMoneyTransactionInfo.query([exists: true, destinationBill: bill]).get().asBoolean()
            if (!hasAsaasMoneyTransactionInfo) {
                mobilePushNotificationService.notifyBillPaid(bill)

                checkoutNotificationService.sendReceiptEmail(bill)
                checkoutNotificationService.sendReceiptSms(bill)
            }

            pushNotificationRequestBillService.save(PushNotificationRequestEvent.BILL_PAID, bill)

            customerAlertNotificationService.notifyBillPaid(bill)

            return bill
        } catch (Exception exception) {
            AsaasLogger.error("BillPayService.pay >> Falha ao efetuar o pagamento da conta [${billId}]", exception)
            throw exception
        }
    }

    public Payment findPaymentAsaasWithBankSlipInfo(String bankCode, String covenant, String covenantDigit, String agency, String nossoNumero) {
        BoletoBank billBoletoBank = findBillBoletoBank(bankCode, covenant, covenantDigit, agency)
        if (!billBoletoBank) return null

        Payment billPayment = findBillPayment(billBoletoBank, nossoNumero)

        return billPayment
    }

    public Payment findPaymentAsaasWithIdentificationField(String identificationField) {
        Map identificationFieldMap = BoletoUtils.buildLinhaDigitavelDataMap(identificationField)

        return findPaymentAsaasWithBankSlipInfo(identificationFieldMap.bankCode, identificationFieldMap.covenant, identificationFieldMap.covenantDigit, identificationFieldMap.agency, identificationFieldMap.nossoNumero)
    }

    public Bill payBillIfPossible(Bill bill) {
        if (!bill.status.isPending()) return bill

        Date scheduleDate = bill.scheduleDate.clone()
        if (scheduleDate.clearTime() >= CustomDateUtils.tomorrow()) return bill

        if (bill.asaasBankSlip) {
            bill = payAsaasBankSlip(bill)
        } else if (canBePaidWithGateway(bill)) {
            AsaasLogger.info("BillPayService.payBillIfPossible >>> Processando pagamento da conta [${bill.id}]")
            bill = payWithCelcoin(bill)
        }

        return bill
    }

    public void payPendingScheduledBillIfIsAsaasBankSlip() {
        List<Long> billIdList = Bill.query([ignoreCustomer: true, scheduleDate: new Date().clearTime(), status: BillStatus.PENDING, awaitingCriticalActionAuthorization: false, asaasBankSlip: true, "dueDate[gt]": CustomDateUtils.subtractBusinessDays(new Date().clearTime(), 1), column: "id"]).list(max: 50)

        Utils.forEachWithFlushSession(billIdList, 25, { Long billId ->
            Utils.withNewTransactionAndRollbackOnError ({
                Bill bill = Bill.get(billId)

                payAsaasBankSlip(bill)
            }, [logErrorMessage: "BillPayService.payPendingScheduledBillIfIsAsaasBankSlip >>> Erro ao pagar a conta [id: ${billId}]."] )
        } )
    }

    private Bill payWithCelcoin(Bill bill) {
        bill = validatePaymentWithCelcoin(bill)
        if (bill.hasErrors()) return bill

        Map registrationResponse = celcoinManagerService.register(bill)

        if (!registrationResponse.success) {
            if (registrationResponse.asaasError) return DomainUtils.addErrorWithErrorCode(bill, registrationResponse.asaasError.code, registrationResponse.asaasError.getMessage())
            return DomainUtils.addErrorWithErrorCode(bill, "celcoin.register.error", "Erro ao cadastrar o pagamento da conta.")
        }

        BillPaymentTransaction billPaymentTransaction = billPaymentTransactionService.save(bill, BillPaymentTransactionGateway.CELCOIN, [externalId: registrationResponse.transactionId])

        bill.status = BillStatus.BANK_PROCESSING
        bill.statusReason = null
        bill.save(failOnError: true)

        Map confirmationResponse = celcoinManagerService.confirm(bill.id, registrationResponse.transactionId)

        if (!confirmationResponse.success) {
            billPaymentTransaction.status = BillPaymentTransactionStatus.ERROR
            billPaymentTransaction.save(failOnError: true)

            if (confirmationResponse.asaasError) return DomainUtils.addErrorWithErrorCode(bill, confirmationResponse.asaasError.code, confirmationResponse.asaasError.getMessage())
            return DomainUtils.addErrorWithErrorCode(bill, "celcoin.capture.error", "Não foi possível confirmar o pagamento.")
        }

        billPaymentTransaction.status = BillPaymentTransactionStatus.CONFIRMED
        billPaymentTransaction.save(failOnError: true)

        return pay(bill.id, null)
    }

    private Boolean canBePaidWithGateway(Bill bill) {
        final BigDecimal gatewayPaymentValueLimit = 250_000L

        if (bill.value >= gatewayPaymentValueLimit) return false

        Date dateStartToProcessPayment = CustomDateUtils.setTime(new Date(), Bill.DEFAULT_START_HOUR_TO_EXECUTE_BILL_ON_GATEWAY, Bill.DEFAULT_START_MINUTE_TO_EXECUTE_BILL_ON_GATEWAY, 0)

        if (bill.scheduleDate < CustomDateUtils.tomorrow() && new Date() >= dateStartToProcessPayment) {
            if (bill.isUtility || BillUtils.isOverdue(bill.dueDate)) return true
        }

        return false
    }

    private Bill validatePaymentWithCelcoin(Bill bill) {
        bill = validatePayment(bill)
        if (bill.hasErrors()) return bill

        if (!bill.status.isPending()) return DomainUtils.addError(bill, "Somente uma conta que esteja aguardando pagamento pode ser paga.")

        return bill
    }

    private BoletoBank findBillBoletoBank(String bankCode, String covenant, String covenantDigit, String agency) {
        Map boletoBankSearchMap = [bankCode: bankCode, covenant: covenant]

        if (covenantDigit) {
            boletoBankSearchMap.covenantDigit = covenantDigit
        }

        if (agency) {
            boletoBankSearchMap.agency = agency
        }

        return BoletoBank.query(boletoBankSearchMap).get()
    }

    private Payment findBillPayment(BoletoBank boletoBank, String nossoNumero) {
        List<Long> paymentList = paymentConfirmRequestService.listPaymentIdByNossoNumeroAndBoletoBank(nossoNumero, boletoBank)

        if (!paymentList || paymentList.size() > 1) return null

        Payment firstPayment = Payment.get(paymentList.first())

        if (firstPayment.provider.isAsaasProvider()) return null

        if (firstPayment.provider.isAsaasDebtRecoveryProvider()) return null

        return firstPayment
    }

    private Bill validatePayment(Bill bill) {
        if (!bill) return DomainUtils.addError(bill, "Pagamento de conta não encontrado.")
        if (bill.awaitingCriticalActionAuthorization) return DomainUtils.addError(bill, "Este pagamento de contas está aguardando confirmação de evento crítico.")
        if (!(bill.status.isPending() || bill.status.isBankProcessing())) return DomainUtils.addError(bill, "Somente uma conta que esteja aguardando pagamento pode ser paga.")

        return bill
    }

    private Bill payAsaasBankSlip(Bill bill) {
        Map linhaDigitavelDataMap = BoletoUtils.buildLinhaDigitavelDataMap(bill.linhaDigitavel)
        Payment payment = findPaymentAsaasWithBankSlipInfo(linhaDigitavelDataMap.bankCode, linhaDigitavelDataMap.covenant, linhaDigitavelDataMap.covenantDigit, linhaDigitavelDataMap.agency, linhaDigitavelDataMap.nossoNumero)

        if (!payment.canConfirm()) {
            return DomainUtils.addErrorWithErrorCode(bill, "bill.payWithGateway.billAlreadyProcessing", Utils.getMessageProperty("bill.payWithGateway.billAlreadyProcessing"))
        }

        if (BillUtils.isOverdue(bill.dueDate) && !payment.canBePaidAfterDueDate()) {
            return DomainUtils.addErrorWithErrorCode(bill, "bill.payWithGateway.cannotBePaidAfterDueDate", Utils.getMessageProperty("bill.payWithGateway.cannotBePaidAfterDueDate"))
        }

        BigDecimal billValueDifference = (bill.value - payment.calculateCurrentPaymentValue()).abs()
        if ( billValueDifference > Payment.MAX_INTEREST_TOLERANCE_VALUE) {
            AsaasLogger.warn("Valor registrado do boleto não condiz com o valor atual da cobrança [billId: ${bill.id} / paymentId: ${payment.id}].")
            return DomainUtils.addErrorWithErrorCode(bill, "bill.pay.valueDifferentFromRegistered", Utils.getMessageProperty("bill.pay.valueDifferentFromRegistered"))
        }

        Bank bank = Bank.query([code: linhaDigitavelDataMap.bankCode]).get()
        if (!bank) throw new RuntimeException("Bank não encontrado em payAsaasBankSlip BillId: ${bill.id}")

        bill = pay(bill.id, null)
        paymentConfirmRequestService.receiveBillPayment(payment.id, linhaDigitavelDataMap.nossoNumero, bill.value, bank)

        return bill
    }
}

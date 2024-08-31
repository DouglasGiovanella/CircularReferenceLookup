package com.asaas.service.asaasmoney

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.asaasmoney.AsaasMoneyCashbackStatus
import com.asaas.asaasmoney.AsaasMoneyChargedFeeStatus
import com.asaas.asaasmoney.AsaasMoneyChargedFeeType
import com.asaas.asaasmoney.AsaasMoneyTransactionStatus
import com.asaas.asaasmoney.AsaasMoneyTransactionType
import com.asaas.billinginfo.BillingType
import com.asaas.domain.asaasmoney.AsaasMoneyCashback
import com.asaas.domain.asaasmoney.AsaasMoneyChargedFee
import com.asaas.domain.asaasmoney.AsaasMoneyTransactionInfo
import com.asaas.domain.bill.Bill
import com.asaas.domain.customer.Customer
import com.asaas.domain.installment.Installment
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.mobilephonerecharge.MobilePhoneRecharge
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixTransaction
import com.asaas.exception.BusinessException
import com.asaas.payment.PaymentStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AsaasMoneyTransactionInfoService {

    def asaasMoneyChargedFeeService
    def asaasMoneyCashbackService
    def billingTypeService
    def asaasMoneyPaymentService
    def installmentService
    def internalTransferService
    def paymentRefundService

    public AsaasMoneyTransactionInfo save(Customer payerCustomer, domainInstance, Map params) {
        validateDomainInstance(domainInstance)
        validateParams(params)

        Customer asaasMoneyMainAccountCustomer = Customer.get(Long.valueOf(AsaasApplicationHolder.config.asaas.asaasMoney.customer.id))
        Payment backingPayment = Payment.query([customer: asaasMoneyMainAccountCustomer, publicId: params.backingPaymentId]).get()
        Installment backingInstallment = Installment.query([customer: asaasMoneyMainAccountCustomer, publicId: params.backingInstallmentId]).get()

        AsaasMoneyTransactionInfo asaasMoneyTransactionInfo = new AsaasMoneyTransactionInfo()

        if (domainInstance instanceof Bill) {
            asaasMoneyTransactionInfo.type = AsaasMoneyTransactionType.BILL
            asaasMoneyTransactionInfo.destinationBill = domainInstance
        } else if (domainInstance instanceof MobilePhoneRecharge) {
            asaasMoneyTransactionInfo.type = AsaasMoneyTransactionType.MOBILE_PHONE_RECHARGE
            asaasMoneyTransactionInfo.destinationMobilePhoneRecharge = domainInstance
        } else if (domainInstance instanceof PixTransaction) {
            asaasMoneyTransactionInfo.type = AsaasMoneyTransactionType.PIX_TRANSACTION
            asaasMoneyTransactionInfo.destinationPixTransaction = domainInstance
        } else if (domainInstance instanceof Payment && params.isCreditCardTransaction) {
            asaasMoneyTransactionInfo.type = AsaasMoneyTransactionType.CREDIT_CARD_PAYMENT
            asaasMoneyTransactionInfo.destinationCreditCardPayment = domainInstance
        } else if (domainInstance instanceof Installment && params.isCreditCardTransaction) {
            asaasMoneyTransactionInfo.type = AsaasMoneyTransactionType.CREDIT_CARD_INSTALLMENT
            asaasMoneyTransactionInfo.destinationCreditCardInstallment = domainInstance
        } else {
            throw new RuntimeException("asaasMoneyTransactionInfoService.save: Objeto [${domainInstance.class.simpleName}] não suportado.")
        }

        asaasMoneyTransactionInfo.status = AsaasMoneyTransactionStatus.PENDING
        asaasMoneyTransactionInfo.value = domainInstance.value.abs()
        asaasMoneyTransactionInfo.payerCustomer = payerCustomer
        asaasMoneyTransactionInfo.payerCustomerCpfCnpj = payerCustomer.cpfCnpj

        asaasMoneyTransactionInfo.save(failOnError: true)

        if (AsaasMoneyTransactionType.getFinanceableList().contains(asaasMoneyTransactionInfo.type)) {
            asaasMoneyTransactionInfo.backingPayment = backingPayment
            asaasMoneyTransactionInfo.backingInstallment = backingInstallment
            asaasMoneyTransactionInfo.backingInternalTransfer = saveAsaasMoneyBackingInternalTransferIfNecessary(asaasMoneyMainAccountCustomer, payerCustomer, domainInstance, backingPayment, backingInstallment)

            if (params.asaasMoneyDiscountValue) asaasMoneyTransactionInfo.discountInternalTransfer = internalTransferService.saveAsaasMoneyDiscountInternalTransfer(domainInstance, asaasMoneyMainAccountCustomer, payerCustomer, asaasMoneyTransactionInfo.asaasMoneyDiscountValue)

            asaasMoneyTransactionInfo.save(failOnError: true)

            if (params.asaasMoneyPaymentFinancingFeeValue) asaasMoneyChargedFeeService.save(payerCustomer, asaasMoneyTransactionInfo, params.asaasMoneyPaymentFinancingFeeValue, AsaasMoneyChargedFeeType.FINANCING_FEE)
            if (params.asaasMoneyPaymentAnticipationFeeValue) asaasMoneyChargedFeeService.save(payerCustomer, asaasMoneyTransactionInfo, params.asaasMoneyPaymentAnticipationFeeValue, AsaasMoneyChargedFeeType.ANTICIPATION_FEE)

            if (params.asaasMoneyCashbackValue) asaasMoneyCashbackService.save(asaasMoneyTransactionInfo.payerCustomer, asaasMoneyTransactionInfo, params.asaasMoneyCashbackValue)
        }

        return asaasMoneyTransactionInfo
    }

    public void saveCreditCardTransaction(Customer payerCustomer, Payment payment) {
        if (!billingTypeService.getAllowedBillingTypeList(payment).contains(BillingType.MUNDIPAGG_CIELO)) throw new RuntimeException("asaasMoneyTransactionInfoService.saveCreditCardTransaction: BillingType [${payment.billingType}] não suportado.")

        if (payment.installment) {
            save(payerCustomer, payment.installment, [isCreditCardTransaction: true])
        } else {
            save(payerCustomer, payment, [isCreditCardTransaction: true])
        }
    }

    public void refundCheckoutIfNecessary(domainInstance) {
        AsaasMoneyTransactionInfo asaasMoneyTransaction

        if (domainInstance instanceof PixTransaction) {
            PixTransaction debitTransaction = PixTransaction.debit([endToEndIdentifier: domainInstance.endToEndIdentifier]).get()
            if (!debitTransaction) return
            if (domainInstance.value.abs() != debitTransaction.value.abs()) return

            asaasMoneyTransaction = AsaasMoneyTransactionInfo.getCheckoutTransaction(debitTransaction)
        } else {
            asaasMoneyTransaction = AsaasMoneyTransactionInfo.getCheckoutTransaction(domainInstance)
        }

        if (!asaasMoneyTransaction) return
        if (asaasMoneyTransaction.status.isRefunded()) return

        refund(asaasMoneyTransaction)
    }

    public void setAsPaidIfNecessary(domainInstance) {
        AsaasMoneyTransactionInfo asaasMoneyTransaction = AsaasMoneyTransactionInfo.getCheckoutTransaction(domainInstance)
        if (!asaasMoneyTransaction) return

        payTransaction(asaasMoneyTransaction)
    }

    public void creditAsaasMoneyCashbackIfNecessary(domainInstance) {
        AsaasMoneyTransactionInfo asaasMoneyTransactionInfo = AsaasMoneyTransactionInfo.getCheckoutTransaction(domainInstance)
        if (!asaasMoneyTransactionInfo) return

        AsaasMoneyCashback asaasMoneyCashback = AsaasMoneyCashback.query([asaasMoneyTransactionInfo: asaasMoneyTransactionInfo, status: AsaasMoneyCashbackStatus.PENDING]).get()
        if (!asaasMoneyCashback) return

        asaasMoneyCashbackService.executeCredit(asaasMoneyCashback)
    }

    public void refund(AsaasMoneyTransactionInfo asaasMoneyTransactionInfo) {
        asaasMoneyCashbackService.refund(asaasMoneyTransactionInfo)
        asaasMoneyChargedFeeService.refund(asaasMoneyTransactionInfo, AsaasMoneyChargedFeeType.FINANCING_FEE)
        asaasMoneyChargedFeeService.refund(asaasMoneyTransactionInfo, AsaasMoneyChargedFeeType.ANTICIPATION_FEE)

        if (asaasMoneyTransactionInfo.backingInternalTransfer) internalTransferService.reverseAsaasMoneyBackingInternalTransfer(asaasMoneyTransactionInfo)
        if (asaasMoneyTransactionInfo.discountInternalTransfer) internalTransferService.reverseAsaasMoneyDiscountInternalTransfer(asaasMoneyTransactionInfo)
        if (asaasMoneyTransactionInfo.backingPayment?.isConfirmedOrReceived()) paymentRefundService.refund(asaasMoneyTransactionInfo.backingPayment, [refundOnAcquirer: true])
        if (asaasMoneyTransactionInfo.backingInstallment?.listConfirmedOrReceivedCreditCardPayments()?.size() > 0) installmentService.refundCreditCard(asaasMoneyTransactionInfo.backingInstallment, true, [:])

        asaasMoneyTransactionInfo.status = AsaasMoneyTransactionStatus.REFUNDED
        asaasMoneyTransactionInfo.save(failOnError: true)

        asaasMoneyPaymentService.updateStatusIfNecessary(asaasMoneyTransactionInfo)
    }

    public Boolean shouldSendToManualAnalysis(Customer customer) {
        final Integer timeLimitToConsiderOnSendToManualAnalysisInHours = 36
        final Integer maxTransactionCountToBypassManualAnalysis = 2

        Map search = [:]
        search.payerCustomer = customer
        search."dateCreated[ge]" = CustomDateUtils.sumHours(new Date(), timeLimitToConsiderOnSendToManualAnalysisInHours * -1)
        search."type[in]" = AsaasMoneyTransactionType.listCreditCardTypes()

        Integer asaasMoneyTransactionCount = AsaasMoneyTransactionInfo.query(search).count()
        if (asaasMoneyTransactionCount >= maxTransactionCountToBypassManualAnalysis) return true

        return false
    }

    private AsaasMoneyTransactionInfo payTransaction(AsaasMoneyTransactionInfo asaasMoneyTransactionInfo) {
        if (AsaasMoneyTransactionType.getFinanceableList().contains(asaasMoneyTransactionInfo.type)) {
            if (asaasMoneyTransactionInfo.backingInternalTransfer) internalTransferService.executeAsaasMoneyBackingInternalTransfer(asaasMoneyTransactionInfo)
            if (asaasMoneyTransactionInfo.discountInternalTransfer) internalTransferService.executeAsaasMoneyDiscountInternalTransfer(asaasMoneyTransactionInfo.discountInternalTransfer)

            List<AsaasMoneyChargedFee> asaasMoneyChargedFeeList = AsaasMoneyChargedFee.query([asaasMoneyTransactionInfo: asaasMoneyTransactionInfo, status: AsaasMoneyChargedFeeStatus.PENDING]).list()
            for (AsaasMoneyChargedFee asaasMoneyChargedFee : asaasMoneyChargedFeeList) {
                asaasMoneyChargedFeeService.executeDebit(asaasMoneyChargedFee)
            }

            if (!asaasMoneyTransactionInfo.payerCustomer.hasSufficientBalance(asaasMoneyTransactionInfo.value)) throw new BusinessException(Utils.getMessageProperty("asaasMoney.payment.error.insufficient.balance"))
        }

        asaasMoneyTransactionInfo.status = AsaasMoneyTransactionStatus.PAID
        asaasMoneyTransactionInfo.save(failOnError: true)
    }

    private void validateParams(Map params) {
        Long asaasMoneyMainAccountCustomerId = AsaasApplicationHolder.config.asaas.asaasMoney.customer.id

        if (params.backingPaymentId && params.backingInstallmentId) throw new BusinessException(Utils.getMessageProperty("asaasMoney.transactionInfo.error.backingPayment.invalid"))

        if (params.backingPaymentId) {
            Boolean hasValidBackingPayment = Payment.query([exists: true, customerId: asaasMoneyMainAccountCustomerId, publicId: params.backingPaymentId, billingType: BillingType.MUNDIPAGG_CIELO, statusList: [PaymentStatus.CONFIRMED, PaymentStatus.RECEIVED]]).get().asBoolean()
            if (!hasValidBackingPayment) throw new BusinessException(Utils.getMessageProperty("asaasMoney.transactionInfo.error.backingPayment.invalid"))
        }

        if (params.backingInstallmentId) {
            Installment backingInstallment = Installment.query([customerId: asaasMoneyMainAccountCustomerId, publicId: params.backingInstallmentId, billingType: BillingType.MUNDIPAGG_CIELO]).get()
            if (!backingInstallment) throw new BusinessException(Utils.getMessageProperty("asaasMoney.transactionInfo.error.backingInstallment.invalid"))
            if (!backingInstallment.containsOnlyConfirmedAndReceivedPayments()) throw new BusinessException(Utils.getMessageProperty("asaasMoney.transactionInfo.error.backingInstallment.invalid"))
        }
    }

    private void validateDomainInstance(domainInstance) {
        if (domainInstance instanceof Bill) return
        if (domainInstance instanceof MobilePhoneRecharge) return
        if (domainInstance instanceof PixTransaction) return
        if (domainInstance instanceof Payment) return
        if (domainInstance instanceof Installment) return

        throw new RuntimeException("DomainInstance [${domainInstance.class.simpleName}] não suportado na integração com Asaas Money.")
    }

    private InternalTransfer saveAsaasMoneyBackingInternalTransferIfNecessary(Customer asaasMoneyMainAccountCustomer, Customer payerCustomer, domainInstance, Payment backingPayment, Installment backingInstallment) {
        InternalTransfer backingInternalTransfer
        BigDecimal backingInternalTransferValue = calculateBackingInternalTransferValue(backingPayment, backingInstallment)
        if (backingInternalTransferValue > 0) {
            backingInternalTransfer = internalTransferService.saveAsaasMoneyBackingInternalTransfer(domainInstance, asaasMoneyMainAccountCustomer, payerCustomer, backingInternalTransferValue)
            if (backingInternalTransfer.hasErrors()) throw new BusinessException(DomainUtils.getValidationMessages(backingInternalTransfer.getErrors()).first())
        }

        return backingInternalTransfer
    }

    private BigDecimal calculateBackingInternalTransferValue(Payment backingPayment, Installment backingInstallment) {
        if (backingPayment) return backingPayment.value
        if (backingInstallment) return backingInstallment.value

        return 0
    }
}

package com.asaas.service.split

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.internaltransfer.InternalTransfer
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.domain.split.PaymentSplit
import com.asaas.domain.wallet.Wallet
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.paymentsplit.PaymentSplitCalculator
import com.asaas.split.PaymentSplitCancellationReason
import com.asaas.split.PaymentSplitStatus
import com.asaas.split.SplitVO
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentSplitService {

    def internalTransferService
    def paymentRefundSplitService
    def receivableUnitItemService
    def receivableHubPaymentOutboxService
    def paymentSplitCacheService

    public void saveSplitInfo(Payment payment, params) {
        if (!params.containsKey("splitInfo")) return

        validateUpdatedSplitForConfirmedPaymentIfNecessary(payment)

        List<PaymentSplit> paymentSplitList = PaymentSplit.query(["paymentId": payment.id, "status[in]": PaymentSplitStatus.getAllowedToCreditStatusList(), disableSort: true]).list()

        List<SplitVO> paymentSplitVoList = paymentSplitList.collect( { new SplitVO(it) } )
        List<SplitVO> splitVoList = params.splitInfo.collect( { new SplitVO(it) } )

        if (!splitInfoHasChanged(paymentSplitVoList, splitVoList)) return

        paymentSplitList.each {
            it.delete(failOnError: true, flush: true)
        }

        if (!params.splitInfo) return

        for (Map splitInfo : params.splitInfo) {
            validateSplit(splitInfo)

            PaymentSplit paymentSplit = new PaymentSplit()
            paymentSplit.status = payment.status.isConfirmed() ? PaymentSplitStatus.AWAITING_CREDIT : PaymentSplitStatus.PENDING
            paymentSplit.publicId = UUID.randomUUID()
            paymentSplit.payment = payment
            paymentSplit.wallet = Wallet.query([publicId: splitInfo.walletId]).get()
            paymentSplit.externalReference = splitInfo.externalReference
            paymentSplit.originCustomer = payment.provider

            if (payment.status.isConfirmed()) paymentSplit.paymentConfirmedDate = payment.confirmedDate

            if (!paymentSplit.wallet) throw new BusinessException("Wallet [${splitInfo.walletId}] inexistente.")

            if (!payment.automaticRoutine && paymentSplit.wallet.destinationCustomer.id == payment.provider.id) {
                AsaasLogger.info("PaymentSplitService.saveSplitInfo : walletId [${paymentSplit.wallet}], destinationCustomer [${paymentSplit.wallet.destinationCustomer.id}], provider [${payment.provider.id}]")
                if (!CustomerParameter.getValue(payment.provider, CustomerParameterName.ALLOW_SPLIT_FOR_OWN_WALLET)) {
                    throw new BusinessException("Não é permitido split para sua própria carteira.")
                }
            }

            BigDecimal fixedValue = Utils.toBigDecimal(splitInfo.fixedValue)
            paymentSplit.fixedValue = fixedValue ? BigDecimalUtils.roundDown(fixedValue) : null

            BigDecimal percentualValue = Utils.toBigDecimal(splitInfo.percentualValue)
            paymentSplit.percentualValue = percentualValue ? BigDecimalUtils.roundDown(percentualValue) : null

            paymentSplit.totalValue = PaymentSplitCalculator.calculateTotalValue(paymentSplit)

            paymentSplit.save(flush: true, failOnError: true)

            cacheEvictToCustomerWithPaidPaymentSplitIfNecessary(paymentSplit.originCustomer.id)
            cacheEvictToCustomerWithReceivedPaymentSplitIfNecessary(paymentSplit.wallet.destinationCustomer.id)

            if (payment.isConfirmed()) {
                ReceivableUnitItem receivableUnitItem = ReceivableUnitItem.query([paymentId: payment.id, "contractualEffect[isNull]": true]).get()
                if (receivableUnitItem) receivableUnitItemService.refreshPaymentItem(receivableUnitItem)
            }
        }

        receivableHubPaymentOutboxService.saveConfirmedPaymentUpdated(payment)

        validateSplitTotalValue(payment)
    }

    public void updateSplitTotalValue(Payment payment) {
        List<PaymentSplit> paymentSplitList = PaymentSplit.listActive(payment)
        for (PaymentSplit paymentSplit : paymentSplitList) {
            paymentSplit.totalValue = PaymentSplitCalculator.calculateTotalValue(paymentSplit)
            paymentSplit.save(failOnError: true, flush: true)
        }

        validateSplitTotalValue(payment)
    }

    public void validateSplitTotalValue(Payment payment) {
        BigDecimal splitedValue = 0
        Boolean existsFixedSplitValue = false

        List<PaymentSplit> paymentSplitList = PaymentSplit.listActive(payment)
        for (PaymentSplit paymentSplit : paymentSplitList) {
            if (paymentSplit.fixedValue) existsFixedSplitValue = true
            splitedValue = splitedValue + paymentSplit.totalValue
        }

        BigDecimal maxSplitValue = payment.netValue
        if (existsFixedSplitValue) {
            maxSplitValue -= payment.calculateCurrentDiscountValue()
        }

        if (splitedValue > maxSplitValue) {
            throw new BusinessException("O valor total do Split [${FormUtils.formatCurrencyWithMonetarySymbol(splitedValue)}] excede o valor a receber da cobrança [${FormUtils.formatCurrencyWithMonetarySymbol(maxSplitValue)}]. O split máximo deve ser menor ou igual ao valor da cobrança menos o desconto.")
        }
    }

    public void executeSplit(Payment payment) {
        executeSplit(payment, null)
    }

    public void executeSplit(Payment payment, BigDecimal maxTransferableValue) {
    	List<PaymentSplit> splitList = PaymentSplit.query([payment: payment, "status[in]": PaymentSplitStatus.getAllowedToCreditStatusList(), disableSort: true]).list()
    	if (!splitList) return

        for (PaymentSplit paymentSplit : splitList) {
            paymentSplit.totalValue = PaymentSplitCalculator.calculateTotalValue(paymentSplit)

            InternalTransfer internalTransfer = internalTransferService.save(paymentSplit)

            paymentSplit.status = PaymentSplitStatus.DONE
            paymentSplit.creditDate = internalTransfer.transferDate
            paymentSplit.paymentConfirmedDate = payment.confirmedDate
            paymentSplit.save(failOnError: true, flush: true)

            paymentRefundSplitService.refundSplitIfNecessary(paymentSplit)
    	}

        if (!maxTransferableValue) maxTransferableValue = payment.netValue

        if (payment.getValueCompromisedWithPaymentSplit() > maxTransferableValue) {
    		throw new RuntimeException("PaymentSplitService.executeSplit: transfered value [${payment.getValueCompromisedWithPaymentSplit()}] exceeds max transferable value [${maxTransferableValue}]. Payment [${payment.id}].")
        }
    }

    public void refundSplit(Payment payment, PaymentSplitCancellationReason cancellationReason) {
        List<PaymentSplit> splitList = PaymentSplit.query([payment: payment, status: PaymentSplitStatus.DONE, disableSort: true]).list()

        for (PaymentSplit paymentSplit : splitList) {
            internalTransferService.refundSplit(paymentSplit)

            paymentSplit.status = PaymentSplitStatus.REFUNDED
            paymentSplit.save(failOnError: true)
        }

        cancelPending(payment, cancellationReason)
    }

    public Boolean splitInfoHasChanged(List<SplitVO> currentSplitList, List<SplitVO> newSplitList) {
        if (currentSplitList.size() != newSplitList.size()) return true

        List<Map> splitList = currentSplitList.collect {
            Map splitMap = [ walletId: it.walletId ]

            if (it.fixedValue != null) splitMap.fixedValue = it.fixedValue
            if (it.percentualValue != null) splitMap.percentualValue = it.percentualValue

            return splitMap
        }

        newSplitList = newSplitList.collect {
            Map splitMap = [ walletId: it.walletId ]

            if (it.fixedValue != null) splitMap.fixedValue = it.fixedValue
            if (it.percentualValue != null) splitMap.percentualValue = it.percentualValue

            return splitMap
        }

        splitList = splitList.sort()
        newSplitList = newSplitList.sort()

        return splitList != newSplitList
    }

    public void validateSplit(Map split) {
        BigDecimal percentualValue = Utils.toBigDecimal(split.percentualValue)
        if (percentualValue && BigDecimalUtils.roundDown(percentualValue) <= 0) throw new BusinessException("Informe um valor percentual superior a 0%")

        BigDecimal fixedValue = Utils.toBigDecimal(split.fixedValue)
        if (fixedValue && BigDecimalUtils.roundDown(fixedValue) <= 0) throw new BusinessException("Informe um valor fixo superior a R\$ 0,00")

        if (split.externalReference) {
            String externalReference = split.externalReference
            if (externalReference.length() > PaymentSplit.constraints.externalReference.getMaxSize()) {
                throw new BusinessException("Não é permitido externalReference para split com tamanho maior que ${PaymentSplit.constraints.externalReference.getMaxSize()} caracteres.")
            }
        }
    }


    public List<Map> buildInstallmentSplitConfig(List<Map> splitInfoList, Integer installmentCount, Integer currentInstallment) {
        if (!splitInfoList) return

        List<Map> clonedSplitInfoList = splitInfoList.collect( { it.clone() } )

        for (Map splitInfo : clonedSplitInfoList) {
            if (splitInfo.containsKey("totalFixedValue")) splitInfo.fixedValue = setInstallmentFixedValueFromTotalFixedValue(Utils.toBigDecimal(splitInfo.totalFixedValue), installmentCount, currentInstallment)
        }

        return clonedSplitInfoList
    }

    public void updateToAwaitingCreditIfExists(Payment payment) {
        if (!payment.status.isConfirmed()) return

        List<PaymentSplit> splitList = PaymentSplit.query([status: PaymentSplitStatus.PENDING, payment: payment, disableSort: true]).list()
        if (!splitList) return

        for (PaymentSplit paymentSplit : splitList) {
            paymentSplit.status = PaymentSplitStatus.AWAITING_CREDIT
            paymentSplit.paymentConfirmedDate = payment.confirmedDate
            paymentSplit.save(failOnError: true)
        }
    }

    public void cancelPending(Payment payment, PaymentSplitCancellationReason cancellationReason) {
        if (!canCancelPendingSplits(payment)) return

        List<PaymentSplit> splitList = PaymentSplit.query(["status[in]": PaymentSplitStatus.getAllowedToCreditStatusList(), payment: payment, disableSort: true]).list()
        if (!splitList) return

        for (PaymentSplit paymentSplit : splitList) {
            paymentSplit.status = PaymentSplitStatus.CANCELLED
            paymentSplit.cancellationReason = cancellationReason
            paymentSplit.save(failOnError: true)
        }
    }

    private BigDecimal setInstallmentFixedValueFromTotalFixedValue(BigDecimal totalFixedValue, Integer installmentCount, Integer currentInstallment) {
        BigDecimal fixedValue = totalFixedValue ? BigDecimalUtils.roundDown(totalFixedValue / installmentCount) : 0

        if (currentInstallment < installmentCount) {
            return fixedValue
        } else {
            BigDecimal remainingValue = totalFixedValue - (fixedValue * installmentCount)

            return fixedValue + remainingValue
        }
    }

    private Boolean canCancelPendingSplits(Payment payment) {
        if (payment.deleted) return true
        if (payment.status.isRefunded()) return true
        if (payment.status.isReceivedInCash()) return true

        return false
    }

    private void validateUpdatedSplitForConfirmedPaymentIfNecessary(Payment payment) {
        if (payment.canEdit()) return
        if (!payment.status.isConfirmed()) throw new BusinessException("Só é permitido alterar splits de cobranças pendentes, vencidas ou confirmadas")
        if (!payment.billingType.isCreditCardOrDebitCard()) throw new BusinessException("Só é permitido alterar splits de cobranças feitas com cartão de crédito ou débito")
        if (payment.anticipated) throw new BusinessException("É permitido alterar apenas cobranças que não possuem antecipação")

        final Integer businessDaysBeforeCreditToEditSplit = 1
        if (CustomDateUtils.calculateDifferenceInBusinessDays(new Date().clearTime(), payment.creditDate) <= businessDaysBeforeCreditToEditSplit) throw new BusinessException("Só é permitido alterar splits ${businessDaysBeforeCreditToEditSplit} dias úteis antes do recebimento")
    }

    private void cacheEvictToCustomerWithPaidPaymentSplitIfNecessary(Long originCustomerId) {
        Boolean hasPaidPaymentSplit = paymentSplitCacheService.isCustomerWithPaidPaymentSplit(originCustomerId)

        if (!hasPaidPaymentSplit) {
            paymentSplitCacheService.evictIsCustomerWithPaidPaymentSplit(originCustomerId)
        }
    }

    private void cacheEvictToCustomerWithReceivedPaymentSplitIfNecessary(Long destinationCustomerId) {
        Boolean hasReceivedPaymentSplit = paymentSplitCacheService.isCustomerWithReceivedPaymentSplit(destinationCustomerId)

        if (!hasReceivedPaymentSplit) {
            paymentSplitCacheService.evictIsCustomerWithReceivedPaymentSplit(destinationCustomerId)
        }
    }
}

package com.asaas.service.payment

import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCardFeeConfigVO
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.creditcard.CreditCardFeeNegotiatedForBrandConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.debitcard.DebitCardFeeNegotiatedForBrandConfig
import com.asaas.domain.freepaymentconfig.FreePaymentUse
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixCreditFee
import com.asaas.domain.promotionalcode.PromotionalCodeUse
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentFeeVO
import com.asaas.pix.PixTransactionOriginType
import com.asaas.utils.BigDecimalUtils
import grails.transaction.Transactional

@Transactional
class PaymentFeeService {

    def pixTransactionFeeService

    public BigDecimal calculateNetValue(PaymentFeeVO paymentFeeVO) {
        if (!paymentFeeVO.value || !paymentFeeVO.billingType)  return 0

        if (!paymentFeeVO.installmentCount) paymentFeeVO.installmentCount = 1

        BillingType billingTypeToSearch = paymentFeeVO.billingType

        if (paymentFeeVO.billingType in [BillingType.BOLETO, BillingType.DEPOSIT]) {
            billingTypeToSearch = BillingType.BOLETO
        }

        try {
            if (billingTypeToSearch.isTransfer()) return paymentFeeVO.value

            if (billingTypeToSearch.isUndefined()) return calculateUndefinedNetValue(paymentFeeVO)

            if (billingTypeToSearch.isBoleto()) return calculateBankSlipNetValue(paymentFeeVO)

            if (billingTypeToSearch.isCreditCard()) return calculateCreditCardNetValue(paymentFeeVO)

            if (billingTypeToSearch.isDebitCard()) return calculateDebitCardNetValue(paymentFeeVO)

            if (billingTypeToSearch.isPix()) return calculatePixDynamicQrCodeNetValue(paymentFeeVO)

            return paymentFeeVO.value
        } catch (Exception exception) {
            AsaasLogger.error("PaymentFeeService.calculateNetValue >> Erro desconhecido ao tentar calcular o valor da cobrança", exception)
            return paymentFeeVO.value
        }
    }

    public BigDecimal calculateCreditCardNetValue(PaymentFeeVO paymentFeeVO) {
        CreditCardFeeConfigVO creditCardFeeConfigVO = new CreditCardFeeConfigVO(paymentFeeVO.customer)
        Map currentCreditCardFees = creditCardFeeConfigVO.getCurrentCreditCardFees()

        BigDecimal fixedFee = creditCardFeeConfigVO.fixedFee
        BigDecimal percentageFee

        if (paymentFeeVO.installmentCount == 1) {
            percentageFee = currentCreditCardFees.upfrontFee
        } else if (paymentFeeVO.installmentCount <= 6) {
            percentageFee = currentCreditCardFees.upToSixInstallmentsFee
        } else if (paymentFeeVO.installmentCount <= 12) {
            percentageFee = currentCreditCardFees.upToTwelveInstallmentsFee
        } else {
            throw new BusinessException("Taxa de cartão para [${paymentFeeVO.installmentCount}] inexistente.")
        }

        if (creditCardFeeConfigVO.hasNegotiatedFeeForBrand && paymentFeeVO.creditCardBrand) {
            Map queryParams = ["customerId": paymentFeeVO.customer.id, "brand": paymentFeeVO.creditCardBrand, "installmentCount": paymentFeeVO.installmentCount]
            CreditCardFeeNegotiatedForBrandConfig creditCardFeeNegotiatedForBrandConfig = CreditCardFeeNegotiatedForBrandConfig.query(queryParams).get()

            if (creditCardFeeNegotiatedForBrandConfig) {
                percentageFee = creditCardFeeNegotiatedForBrandConfig.percentageFee
                fixedFee = creditCardFeeNegotiatedForBrandConfig.fixedFee
            }
        }

        BigDecimal fixedFeePerInstallment = BigDecimalUtils.roundDown(fixedFee / paymentFeeVO.installmentCount)

        return calculateTransactionNetValue(paymentFeeVO.value, percentageFee, fixedFeePerInstallment)
    }

    public BigDecimal calculateDebitCardNetValue(PaymentFeeVO paymentFeeVO) {
        CreditCardFeeConfigVO creditCardFeeConfigVO = new CreditCardFeeConfigVO(paymentFeeVO.customer)

        BigDecimal fixedFee = creditCardFeeConfigVO.debitCardFixedFee
        BigDecimal percentageFee = creditCardFeeConfigVO.debitCardFee

        if (creditCardFeeConfigVO.hasNegotiatedFeeForBrand && paymentFeeVO.debitCardBrand) {
            Map queryParams = ["customerId": paymentFeeVO.customer.id, "brand": paymentFeeVO.debitCardBrand]
            DebitCardFeeNegotiatedForBrandConfig debitCardFeeNegotiatedForBrandConfig = DebitCardFeeNegotiatedForBrandConfig.query(queryParams).get()

            if (debitCardFeeNegotiatedForBrandConfig) {
                percentageFee = debitCardFeeNegotiatedForBrandConfig.percentageFee
                fixedFee = debitCardFeeNegotiatedForBrandConfig.fixedFee
            }
        }

        return calculateTransactionNetValue(paymentFeeVO.value, percentageFee, fixedFee)
    }

    public BigDecimal calculatePixDynamicQrCodeNetValue(PaymentFeeVO paymentFeeVO) {
        return calculatePixNetValue(paymentFeeVO.customer, paymentFeeVO.value, PixTransactionOriginType.DYNAMIC_QRCODE, null)
    }

    public BigDecimal calculatePixNetValue(Customer customer, BigDecimal transactionValue, PixTransactionOriginType originType, String payerCpfCnpj) {
        if (!pixTransactionFeeService.shouldChargeCreditFee(customer, originType, payerCpfCnpj)) return transactionValue

        BigDecimal fee = PixCreditFee.calculateFee(customer, transactionValue)
        return (transactionValue - fee)
    }

    public BigDecimal calculateBankSlipNetValue(PaymentFeeVO paymentFeeVO) {
        if (!paymentFeeVO.customer) return paymentFeeVO.value - BankSlipFee.DEFAULT_BANK_SLIP_FEE

        BigDecimal bankSlipFee = BankSlipFee.calculateFee(paymentFeeVO.customer, paymentFeeVO.value)

        return paymentFeeVO.value - bankSlipFee
    }

    public BigDecimal calculateUndefinedNetValue(PaymentFeeVO paymentFeeVO) {
        BigDecimal bankSlipNetValue = calculateBankSlipNetValue(paymentFeeVO)
        BigDecimal creditCardNetValue = calculateCreditCardNetValue(paymentFeeVO)

        return BigDecimalUtils.min(bankSlipNetValue, creditCardNetValue)
    }

    public BigDecimal getFeeDiscountApplied(Payment payment) {
        BigDecimal discountValueApplied = 0.00
        discountValueApplied += FreePaymentUse.query([column: "feeDiscountApplied", payment: payment]).get() ?: 0.00
        discountValueApplied += PromotionalCodeUse.sumValue([consumerObject: payment]).get()

        return discountValueApplied
    }

    private BigDecimal calculateTransactionNetValue(BigDecimal transactionValue, BigDecimal percentageFee, BigDecimal fixedFee) {
        BigDecimal feeValue = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(transactionValue, percentageFee) + fixedFee
        return transactionValue - feeValue
    }
}

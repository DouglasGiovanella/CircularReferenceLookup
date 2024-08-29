package com.asaas.service.payment

import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCardFeeConfigVO
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.pix.PixCreditFee
import com.asaas.payment.PaymentFeeVO
import com.asaas.payment.PaymentUtils
import com.asaas.paymentSimulator.PaymentSimulatorVO
import com.asaas.paymentSimulator.SimulatorBankSlipVO
import com.asaas.paymentSimulator.SimulatorCreditCardVO
import com.asaas.paymentSimulator.SimulatorDebitCardVO
import com.asaas.paymentSimulator.SimulatorPixVO
import com.asaas.receipttype.ReceiptType
import com.asaas.receivableanticipation.ReceivableAnticipationFinancialInfoVO
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.utils.BigDecimalUtils

import grails.transaction.Transactional

@Transactional
class PaymentSimulatorService {

    def customerReceivableAnticipationConfigService
    def facebookEventService
    def installmentService
    def paymentFeeService
    def receivableAnticipationFinancialInfoService

    public PaymentSimulatorVO simulate(Customer customer, BigDecimal totalValue, Integer installmentCount, ReceiptType receiptType, List<BillingType> billingTypeList) {
        CreditCardFeeConfigVO creditCardFeeConfigVO = new CreditCardFeeConfigVO(customer)

        PaymentSimulatorVO paymentSimulatorVO = new PaymentSimulatorVO()

        paymentSimulatorVO.value = totalValue

        if (billingTypeList.contains(BillingType.MUNDIPAGG_CIELO)) {
            paymentSimulatorVO.creditCard = buildCreditCard(creditCardFeeConfigVO, totalValue, installmentCount)

            if (receiptType.isAnticipation() && customerReceivableAnticipationConfigService.isEnabled(customer.id)) {
                Map anticipation = buildAnticipation(customer, totalValue, paymentSimulatorVO.creditCard.installment.paymentValue, BillingType.MUNDIPAGG_CIELO, installmentCount)
                paymentSimulatorVO.creditCard.anticipationNetValue = anticipation.netValue
            }
        }

        if (billingTypeList.contains(BillingType.DEBIT_CARD)) {
            paymentSimulatorVO.debitCard = buildDebitCard(creditCardFeeConfigVO, totalValue, installmentCount)
        }

        if (billingTypeList.contains(BillingType.BOLETO)) {
            BigDecimal installmentPaymentValue
            if (installmentCount > 1) {
                installmentPaymentValue = calculateInstallmentPaymentValue(customer, totalValue, installmentCount, BillingType.BOLETO)
            } else {
                installmentPaymentValue = totalValue
            }

            paymentSimulatorVO.bankSlip = buildBankSlip(customer, totalValue, installmentPaymentValue, installmentCount)

            if (receiptType.isAnticipation() && customer.canAnticipateBoleto()) {
                Map anticipation = buildAnticipation(customer, totalValue, installmentPaymentValue, BillingType.BOLETO, installmentCount)

                paymentSimulatorVO.bankSlip.anticipationNetValue = anticipation.netValue
            }
        }

        if (billingTypeList.contains(BillingType.PIX)) {
            BigDecimal installmentPaymentValue
            if (installmentCount > 1) {
                installmentPaymentValue = calculateInstallmentPaymentValue(customer, totalValue, installmentCount, BillingType.PIX)
            } else {
                installmentPaymentValue = totalValue
            }

            paymentSimulatorVO.pix = buildPix(customer, totalValue, installmentPaymentValue, installmentCount)
        }

        facebookEventService.saveSimulateAnticipationEvent(customer.id)

        return paymentSimulatorVO
    }

    private SimulatorCreditCardVO buildCreditCard(CreditCardFeeConfigVO creditCardFeeConfig, BigDecimal totalValue, Integer installmentCount) {
        if (!installmentCount) installmentCount = 1

        SimulatorCreditCardVO simulatorCreditCardVO = new SimulatorCreditCardVO()
        simulatorCreditCardVO.netValue = 0
        simulatorCreditCardVO.feePercentage = creditCardFeeConfig.getCurrentCreditCardFeeForInstallmentCount(installmentCount)
        simulatorCreditCardVO.operationFee = creditCardFeeConfig.fixedFee
        simulatorCreditCardVO.installment.count = installmentCount

        List<Map> paymentMapList = []
        for (Integer installmentNumber = 1; installmentNumber <= installmentCount; installmentNumber++) {
            BigDecimal paymentValue = PaymentUtils.calculateInstallmentValue(totalValue, installmentCount, installmentNumber)
            PaymentFeeVO paymentFeeVO = new PaymentFeeVO(paymentValue, BillingType.MUNDIPAGG_CIELO, creditCardFeeConfig.customer, installmentCount)
            BigDecimal paymentNetValue = paymentFeeService.calculateNetValue(paymentFeeVO)
            paymentMapList += [installmentNumber: installmentNumber, value: paymentValue, netValue: paymentNetValue]
        }

        simulatorCreditCardVO.installment.paymentNetValue = paymentMapList.first().netValue
        simulatorCreditCardVO.installment.paymentValue = paymentMapList.first().value
        simulatorCreditCardVO.netValue = paymentMapList.sum { it.netValue }

        return simulatorCreditCardVO
    }

    private SimulatorDebitCardVO buildDebitCard(CreditCardFeeConfigVO creditCardFeeConfig, BigDecimal totalValue, Integer installmentCount) {
        PaymentFeeVO paymentFeeVO = new PaymentFeeVO(totalValue, BillingType.DEBIT_CARD, creditCardFeeConfig.customer, installmentCount)
        BigDecimal netValue = paymentFeeService.calculateDebitCardNetValue(paymentFeeVO)

        SimulatorDebitCardVO simulatorDebitCardVO = new SimulatorDebitCardVO()

        simulatorDebitCardVO.netValue = netValue
        simulatorDebitCardVO.feePercentage = creditCardFeeConfig.debitCardFee
        simulatorDebitCardVO.operationFee = creditCardFeeConfig.debitCardFixedFee

        return simulatorDebitCardVO
    }

    private SimulatorPixVO buildPix(Customer customer, BigDecimal totalValue, BigDecimal value, Integer installmentCount) {
        PaymentFeeVO paymentFeeVO = new PaymentFeeVO(value, BillingType.PIX, customer, installmentCount)
        BigDecimal paymentNetValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        BigDecimal paymentFee = BigDecimalUtils.roundUp(value - paymentNetValue, 2)

        SimulatorPixVO simulatorPixVO = new SimulatorPixVO()

        BigDecimal netValue
        if (installmentCount > 1) {
            BigDecimal installmentFee = BigDecimalUtils.roundDown(paymentFee * installmentCount)
            netValue = BigDecimalUtils.roundUp(totalValue - installmentFee, 2)
        } else {
            netValue = paymentNetValue
        }

        PixCreditFee pixCreditFee = PixCreditFee.query([customer: customer]).get()

        BigDecimal feePercentage
        BigDecimal feeValue

        if (pixCreditFee.type.isPercentage()) {
            feePercentage = pixCreditFee.percentageFee
        } else {
            feeValue = pixCreditFee.calculateFixedFee()
        }

        simulatorPixVO.netValue = netValue
        simulatorPixVO.feePercentage = feePercentage
        simulatorPixVO.feeValue = feeValue

        simulatorPixVO.installment.paymentNetValue = paymentNetValue
        simulatorPixVO.installment.paymentValue = value
        simulatorPixVO.installment.count = installmentCount

        return simulatorPixVO
    }

    private SimulatorBankSlipVO buildBankSlip(Customer customer, BigDecimal totalValue, BigDecimal value, Integer installmentCount) {
        PaymentFeeVO paymentFeeVO = new PaymentFeeVO(value, BillingType.BOLETO, customer, installmentCount)
        BigDecimal paymentNetValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        BigDecimal paymentFee = BigDecimalUtils.roundUp(value - paymentNetValue, 2)

        SimulatorBankSlipVO simulatorBankSlipVO = new SimulatorBankSlipVO()

        BigDecimal netValue
        if (installmentCount > 1) {
            BigDecimal installmentFee = BigDecimalUtils.roundDown(paymentFee * installmentCount)
            netValue = BigDecimalUtils.roundUp(totalValue - installmentFee, 2)
        } else {
            netValue = paymentNetValue
        }

        BankSlipFee bankSlipFee = BankSlipFee.findBankSlipFeeForCustomer(customer)

        simulatorBankSlipVO.netValue = netValue
        simulatorBankSlipVO.feeValue = bankSlipFee.calculateFixedFeeValue()

        simulatorBankSlipVO.installment.paymentNetValue = paymentNetValue
        simulatorBankSlipVO.installment.paymentValue = value
        simulatorBankSlipVO.installment.count = installmentCount

        return simulatorBankSlipVO
    }

    private Map buildAnticipation(Customer customer, BigDecimal totalValue, BigDecimal value, BillingType billingType, Integer installmentCount) {
        ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO = new ReceivableAnticipationFinancialInfoVO()
        if (installmentCount > 1) {
            receivableAnticipationFinancialInfoVO.buildForWizardInstallment(billingType, new Date(), installmentCount, totalValue, value)
        } else {
            receivableAnticipationFinancialInfoVO.buildForWizardDetachedPayment(billingType, new Date(), totalValue)
        }

        return receivableAnticipationFinancialInfoService.buildFinancialInfo(customer, receivableAnticipationFinancialInfoVO, ReceivableAnticipationPartner.VORTX)
    }

    private BigDecimal calculateInstallmentPaymentValue(Customer customer, BigDecimal totalValue, Integer installmentCount, BillingType billingType) {
        Integer maxNumberOfInstallments = null
        List<Map> installmentOptionList = installmentService.buildInstallmentOptionList(customer, totalValue, billingType, maxNumberOfInstallments)

        return installmentOptionList.find { it.installmentCount == installmentCount }.value
    }
}

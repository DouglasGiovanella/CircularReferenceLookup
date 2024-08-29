package com.asaas.service.receivableanticipation

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.discountconfig.DiscountType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDiscountConfig
import com.asaas.domain.productpromotion.ProductPromotion
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.payment.PaymentFeeVO
import com.asaas.product.ProductName
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationFinancialInfoItemVO
import com.asaas.receivableanticipation.ReceivableAnticipationFinancialInfoVO
import com.asaas.receivableanticipationpartner.ReceivableAnticipationPartner
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationFinancialInfoService {

    def paymentFeeService

    public Map simulateAnticipation(Customer customer, BillingType billingType, Date dueDate, BigDecimal totalValue, BigDecimal value, Integer installmentCount, BigDecimal discountValue, DiscountType discountType) {
        ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO = new ReceivableAnticipationFinancialInfoVO()

        if (installmentCount > 1) {
            receivableAnticipationFinancialInfoVO.buildForWizardInstallment(billingType, dueDate, installmentCount, totalValue, value)
        } else {
            receivableAnticipationFinancialInfoVO.buildForWizardDetachedPayment(billingType, dueDate, totalValue)
        }

        if (discountValue && discountType) receivableAnticipationFinancialInfoVO.setDiscountData(discountValue, discountType)

        return buildFinancialInfo(customer, receivableAnticipationFinancialInfoVO, ReceivableAnticipationPartner.VORTX)
    }

    public Map buildFinancialInfo(Customer customer, ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO, ReceivableAnticipationPartner receivableAnticipationPartner) {
        Map properties = [discountValue: 0]
        properties.anticipationDate = calculateAnticipationDate(receivableAnticipationFinancialInfoVO, customer)

        if (receivableAnticipationFinancialInfoVO.chargeType.isInstallment()) {
            List<ReceivableAnticipationFinancialInfoItemVO> anticipationItems = receivableAnticipationFinancialInfoVO.anticipationItems ?: buildAnticipationItemsList(customer, receivableAnticipationFinancialInfoVO)

            ReceivableAnticipationFinancialInfoItemVO lastItemVO = anticipationItems.sort { it.installmentNumber }.last()
            properties.dueDate = calculateDueDate(customer.id, lastItemVO.installmentNumber, receivableAnticipationFinancialInfoVO.billingType, lastItemVO.dueDate, lastItemVO.estimatedCreditDate)
            properties.anticipationDays = ReceivableAnticipationCalculator.calculateAnticipationDays(properties.anticipationDate, properties.dueDate)

            properties.totalValue = anticipationItems*.value.sum()
            properties.value = calculateReceivableAnticipationValue(anticipationItems)
            properties.fee = calculateInstallmentFee(receivableAnticipationFinancialInfoVO, properties.anticipationDate, customer, anticipationItems)
            properties.installmentFee = anticipationItems.sum { it.value - it.netValue }

            for (ReceivableAnticipationFinancialInfoItemVO item : anticipationItems) {
                Date dueDate = calculateDueDate(customer.id, item.installmentNumber, receivableAnticipationFinancialInfoVO.billingType, item.dueDate, item.estimatedCreditDate)
                item.anticipationDays = ReceivableAnticipationCalculator.calculateAnticipationDays(properties.anticipationDate, dueDate)
            }

            properties.anticipationItems = anticipationItems
        } else {
            Boolean isAnticipationPerInstallment = receivableAnticipationFinancialInfoVO.installmentCount && receivableAnticipationFinancialInfoVO.billingType.isCreditCard()

            properties.dueDate = calculateDueDate(customer.id, receivableAnticipationFinancialInfoVO.installmentNumber, receivableAnticipationFinancialInfoVO.billingType, receivableAnticipationFinancialInfoVO.dueDate, receivableAnticipationFinancialInfoVO.estimatedCreditDate)
            properties.anticipationDays = ReceivableAnticipationCalculator.calculateAnticipationDays(properties.anticipationDate, properties.dueDate)
            properties.totalValue = receivableAnticipationFinancialInfoVO.value

            if (isAnticipationPerInstallment) {
                properties.value = calculateReceivableAnticipationValue(receivableAnticipationFinancialInfoVO.discountValue, receivableAnticipationFinancialInfoVO.netValue)
                properties.fee = calculateInstallmentFee(receivableAnticipationFinancialInfoVO, properties.anticipationDate, customer, [])
                properties.installmentFee = receivableAnticipationFinancialInfoVO.value - receivableAnticipationFinancialInfoVO.netValue
            } else {
                BigDecimal netValue = receivableAnticipationFinancialInfoVO.netValue
                if (!netValue) {
                    PaymentFeeVO paymentFeeVO = new PaymentFeeVO(receivableAnticipationFinancialInfoVO.value, receivableAnticipationFinancialInfoVO.billingType, customer, null)
                    netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
                }
                properties.value = calculateReceivableAnticipationValue(receivableAnticipationFinancialInfoVO.discountValue, netValue)
                properties.fee = calculatePaymentFee(customer, receivableAnticipationFinancialInfoVO.billingType, properties.value, properties.anticipationDays, receivableAnticipationPartner)
                properties.defaultFee = ProductPromotion.hasActivePromotion(customer.id, ProductName.ANTICIPATION_PROMOTIONAL_BANK_SLIP_TAXES) ? calculateAnticipationFeeWithDefaultValueForBankSlipPromotion(customer, properties.fee) : null
                properties.paymentFee = BigDecimalUtils.roundHalfUp(receivableAnticipationFinancialInfoVO.value - netValue)
                properties.discountValue = receivableAnticipationFinancialInfoVO.discountValue
            }
        }

        properties.netValue = BigDecimalUtils.roundHalfUp(properties.value - properties.fee)

        return properties
    }

    public BigDecimal calculateInstallmentItemFee(BigDecimal netValue, Date anticipationDate, Date estimatedCreditDate, Customer customer) {
        BigDecimal dailyFeePercentage = CustomerReceivableAnticipationConfig.getCreditCardInstallmentDailyFee(customer)
        BigDecimal dailyFeeValue = netValue * (dailyFeePercentage / 100)

        Integer anticipationDays = CustomDateUtils.calculateDifferenceInDays(anticipationDate, estimatedCreditDate)
        BigDecimal installmentItemFee = dailyFeeValue * anticipationDays

        if (installmentItemFee < 0) installmentItemFee = 0

        return BigDecimalUtils.roundHalfUp(installmentItemFee) ?: ReceivableAnticipation.MIN_FEE
    }

    public BigDecimal calculatePaymentFee(Customer customer, BillingType billingType, BigDecimal value, Integer anticipationDays, ReceivableAnticipationPartner receivableAnticipationPartner) {
        BigDecimal dailyFeePercentage
        if (billingType.isCreditCard()) {
            dailyFeePercentage = CustomerReceivableAnticipationConfig.getCreditCardDetachedDailyFee(customer)
        } else {
            dailyFeePercentage = CustomerReceivableAnticipationConfig.getBankSlipDailyFee(customer)
        }

        BigDecimal operationFeePercentage = calculateOperationFee(customer, billingType, anticipationDays, receivableAnticipationPartner)

        BigDecimal feeValue = calculateFeeValue(dailyFeePercentage, value, anticipationDays)
        BigDecimal operationFeeValue = value * (operationFeePercentage / 100)

        BigDecimal paymentFee = feeValue + operationFeeValue

        if (paymentFee < 0) paymentFee = 0

        return BigDecimalUtils.roundHalfUp(paymentFee) ?: ReceivableAnticipation.MIN_FEE
    }

    public BigDecimal calculateFeeValue(BigDecimal dailyFeePercentage, BigDecimal value, Integer anticipationDays) {
        BigDecimal dailyFeeValue = value * (dailyFeePercentage / 100)
        BigDecimal feeValue = (dailyFeeValue * anticipationDays)

        if (feeValue < 0) feeValue = 0

        return BigDecimalUtils.roundHalfUp(feeValue)
    }

    public BigDecimal calculateAnticipationFeeWithDefaultValueForBankSlipPromotion(Customer customer, BigDecimal anticipationFeeWithPromotionalTax) {
        BigDecimal bankSlipMonthlyFee = CustomerReceivableAnticipationConfig.getBankSlipMonthlyFee(customer)
        BigDecimal anticipationFeeWithDefaultTax = CustomerReceivableAnticipationConfig.getDefaultBankSlipMonthlyFee() * anticipationFeeWithPromotionalTax / bankSlipMonthlyFee
        return BigDecimalUtils.roundUp(anticipationFeeWithDefaultTax, 2)
    }

    public Date calculateEstimatedConfirmationDate(ReceivableAnticipationFinancialInfoVO financialInfoVO, Customer customer) {
        if (financialInfoVO.confirmedDate) return financialInfoVO.confirmedDate
        return calculateScheduleDate(financialInfoVO.billingType, financialInfoVO.dueDate, customer)
    }

    private BigDecimal calculateOperationFee(Customer customer, BillingType billingType, Integer anticipationDays, ReceivableAnticipationPartner receivableAnticipationPartner) {
        if (billingType.isCreditCard()) return 0.00

        Map receivableAnticipationConfig = CustomerReceivableAnticipationConfig.query([columnList: ["operationFee", "operationFeeExemptionDays"], customerId: customer.id]).get()
        if (anticipationDays <= receivableAnticipationConfig.operationFeeExemptionDays) return 0.00

        if (receivableAnticipationConfig.operationFee) return receivableAnticipationConfig.operationFee

        return receivableAnticipationPartner.isAsaas() ? CustomerReceivableAnticipationConfig.getDefaultOperationFee() : 0.00
    }

    private Date calculateDueDate(Long customerId, Integer installmentNumber, BillingType billingType, Date dueDate, Date estimatedCreditDate) {
        if (!billingType.isCreditCard()) return dueDate

        if (estimatedCreditDate) return estimatedCreditDate

        return Payment.calculateEstimatedCreditDate(customerId, installmentNumber)
    }

    private BigDecimal calculateInstallmentFee(ReceivableAnticipationFinancialInfoVO financialInfoVO, Date anticipationDate,  Customer customer, List<ReceivableAnticipationFinancialInfoItemVO> anticipationItems) {
        BigDecimal totalFee = 0

        Date estimatedConfirmationDate = calculateEstimatedConfirmationDate(financialInfoVO, customer)

        if (anticipationItems) {
            for (ReceivableAnticipationFinancialInfoItemVO item : anticipationItems) {
                Date estimatedCreditDate = item.estimatedCreditDate ?: Payment.calculateEstimatedCreditDate(customer.id, item.installmentNumber, estimatedConfirmationDate)
                BigDecimal anticipationValue = calculateReceivableAnticipationValue(item.discountValue, item.netValue)
                item.fee = calculateInstallmentItemFee(anticipationValue, anticipationDate, estimatedCreditDate, customer)

                totalFee += item.fee
            }
        } else {
            Date estimatedCreditDate = financialInfoVO.estimatedCreditDate ?: Payment.calculateEstimatedCreditDate(customer.id, financialInfoVO.installmentNumber, estimatedConfirmationDate)
            BigDecimal anticipationValue = calculateReceivableAnticipationValue(financialInfoVO.discountValue, financialInfoVO.netValue)
            totalFee = calculateInstallmentItemFee(anticipationValue, anticipationDate, estimatedCreditDate, customer)
        }

        return BigDecimalUtils.roundHalfUp(totalFee)
    }

    private Date calculateScheduleDate(BillingType billingType, Date dueDate, Customer customer) {
        Calendar anticipationDate = Calendar.getInstance()

        if (billingType.isBoletoOrPix()) {
            anticipationDate.setTime(dueDate)
            anticipationDate.add(Calendar.DAY_OF_MONTH, - ReceivableAnticipation.getLimitOfDaysToAnticipate(customer))
        }

        return anticipationDate.getTime().clearTime()
    }

    private List<ReceivableAnticipationFinancialInfoItemVO> buildAnticipationItemsList(Customer customer, ReceivableAnticipationFinancialInfoVO receivableAnticipationFinancialInfoVO) {
        List<ReceivableAnticipationFinancialInfoItemVO> receivableAnticipationItensVOList = []
        BigDecimal alreadyCreatedValue = 0

        for (Integer installment = 1; installment <= receivableAnticipationFinancialInfoVO.installmentCount; installment++) {
            Date dueDate = AsaasApplicationHolder.applicationContext.installmentService.calculateNextDueDate(receivableAnticipationFinancialInfoVO.dueDate, installment - 1)
            Date estimatedCreditDate = Payment.calculateEstimatedCreditDate(customer.id, installment)

            BigDecimal value = receivableAnticipationFinancialInfoVO.valuePerPaymentInstallment
            if (installment < receivableAnticipationFinancialInfoVO.installmentCount) {
                alreadyCreatedValue += receivableAnticipationFinancialInfoVO.valuePerPaymentInstallment
            } else {
                value = receivableAnticipationFinancialInfoVO.value - alreadyCreatedValue
            }

            PaymentFeeVO paymentFeeVO = new PaymentFeeVO(value, receivableAnticipationFinancialInfoVO.billingType, customer, receivableAnticipationFinancialInfoVO.installmentCount)
            BigDecimal netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
            BigDecimal discountValue = receivableAnticipationFinancialInfoVO.discountValue ? PaymentDiscountConfig.calculateDiscountValue(value, receivableAnticipationFinancialInfoVO.discountValue, receivableAnticipationFinancialInfoVO.discountType) : 0

            ReceivableAnticipationFinancialInfoItemVO receivableAnticipationFinancialInfoItemVO = new ReceivableAnticipationFinancialInfoItemVO(dueDate, estimatedCreditDate, installment, netValue, value, discountValue, null, null)

            receivableAnticipationItensVOList.add(receivableAnticipationFinancialInfoItemVO)
        }

        return receivableAnticipationItensVOList
    }

    private BigDecimal calculateReceivableAnticipationValue(List<ReceivableAnticipationFinancialInfoItemVO> receivableAnticipationFinancialInfoItemVO) {
        return receivableAnticipationFinancialInfoItemVO.sum { calculateReceivableAnticipationValue(it.discountValue, it.netValue) }
    }

    private BigDecimal calculateReceivableAnticipationValue(BigDecimal discountValue, BigDecimal netValue) {
        BigDecimal receivableAnticipationValue = discountValue ? netValue - discountValue : netValue
        return BigDecimalUtils.roundHalfUp(receivableAnticipationValue)
    }

    private Date calculateAnticipationDate(ReceivableAnticipationFinancialInfoVO financialInfoVO, Customer customer) {
        if (!financialInfoVO.schedule) return new Date().clearTime()

        Date anticipationDate = calculateEstimatedConfirmationDate(financialInfoVO, customer)

        if (financialInfoVO.scheduleDaysAfterConfirmation) return CustomDateUtils.sumDaysAndGetNextBusinessDayIfHoliday(anticipationDate, financialInfoVO.scheduleDaysAfterConfirmation)

        return anticipationDate
    }
}

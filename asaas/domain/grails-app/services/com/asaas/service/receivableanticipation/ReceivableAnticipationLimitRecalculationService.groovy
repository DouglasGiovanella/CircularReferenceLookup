package com.asaas.service.receivableanticipation

import com.asaas.billinginfo.BillingType
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipationLimitRecalculation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationLimitRecalculationInfo
import com.asaas.payment.PaymentStatus
import com.asaas.receivableanticipation.ReceivableAnticipationCreditCardLimitProfile
import com.asaas.receivableanticipation.ReceivableAnticipationLimitRecalculationStatus
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class ReceivableAnticipationLimitRecalculationService {

    def asyncActionService

    public void save(Customer customer, Date nextRecalculationDate) {
        ReceivableAnticipationLimitRecalculation recalculation = new ReceivableAnticipationLimitRecalculation()
        recalculation.customer = customer
        recalculation.status = ReceivableAnticipationLimitRecalculationStatus.PENDING
        recalculation.recalculationDate = nextRecalculationDate
        recalculation.save(failOnError: true)
    }

    public void addCustomerToRecalculateLimitIfNecessary(Customer customer, Boolean nextRecalculationDateWithPriority) {
        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        if (!receivableAnticipationConfig.hasCreditCardPercentage()) return

        Map search = [
            exists: true,
            customerId: customer.id,
            status: ReceivableAnticipationLimitRecalculationStatus.PENDING
        ]

        Boolean hasLimitRecalculationOnQueue = ReceivableAnticipationLimitRecalculation.query(search).get().asBoolean()
        if (hasLimitRecalculationOnQueue) return

        asyncActionService.saveRecalculateCreditCardAnticipationLimitWithPercentage(customer.id, nextRecalculationDateWithPriority)
    }

    public BigDecimal calculateCreditCardLimitWithPercentage(ReceivableAnticipationLimitRecalculation recalculation, CustomerReceivableAnticipationConfig customerReceivableAnticipationConfig) {
        BigDecimal creditCardPercentage = customerReceivableAnticipationConfig.buildCreditCardPercentageLimit()

        Map search = [
            customer: recalculation.customer,
            billingType: BillingType.MUNDIPAGG_CIELO,
            status: PaymentStatus.CONFIRMED
        ]

        BigDecimal creditCardTotalConfirmedValue = Payment.sumValue(search).get()

        saveLastValuesUsedToCalculateCreditCardLimit(recalculation, creditCardTotalConfirmedValue, creditCardPercentage)

        if (!creditCardTotalConfirmedValue || !creditCardPercentage) return 0.00

        BigDecimal calculatedValue = BigDecimalUtils.calculateValueFromPercentageWithRoundHalfUp(creditCardTotalConfirmedValue, creditCardPercentage)

        Boolean isLegalPersonAnticipationBelowMinimumThreshold = customerReceivableAnticipationConfig.customer.isLegalPerson() &&
            calculatedValue < ReceivableAnticipationLimitRecalculation.LEGAL_PERSON_MINIMUM_CREDIT_CARD_ANTICIPATION_LIMIT_VALUE

        if (isLegalPersonAnticipationBelowMinimumThreshold) return ReceivableAnticipationLimitRecalculation.LEGAL_PERSON_MINIMUM_CREDIT_CARD_ANTICIPATION_LIMIT_VALUE

        return calculatedValue
    }

    public Date calculateNextRecalculationDate(Customer customer) {
        ReceivableAnticipationCreditCardLimitProfile creditCardLimitProfile = getCustomerCreditCardLimitProfile(customer)

        return CustomDateUtils.sumMinutes(new Date(), getMinutesForRecalculation(creditCardLimitProfile))
    }

    public void setNextRecalculationDate(ReceivableAnticipationLimitRecalculation recalculation, Date nextRecalculationDate) {
        recalculation.status = ReceivableAnticipationLimitRecalculationStatus.PENDING
        recalculation.recalculationDate = nextRecalculationDate
        recalculation.save(failOnError: true)
    }

    public void setAsError(ReceivableAnticipationLimitRecalculation recalculation) {
        recalculation.status = ReceivableAnticipationLimitRecalculationStatus.ERROR
        recalculation.save(failOnError: true)
    }

    public void setAsDone(ReceivableAnticipationLimitRecalculation recalculation) {
        recalculation.status = ReceivableAnticipationLimitRecalculationStatus.DONE
        recalculation.save(failOnError: true)
    }

    public Integer getMinutesForRecalculation(ReceivableAnticipationCreditCardLimitProfile profile) {
        Integer minutes
        switch (profile) {
            case ReceivableAnticipationCreditCardLimitProfile.VERY_LOW:
                minutes = 1
                break
            case ReceivableAnticipationCreditCardLimitProfile.LOW:
                minutes = 2
                break
            case ReceivableAnticipationCreditCardLimitProfile.MODERATE:
                minutes = 5
                break
            case ReceivableAnticipationCreditCardLimitProfile.HIGH:
                minutes = 60
                break
            case ReceivableAnticipationCreditCardLimitProfile.VERY_HIGH:
                minutes = 240
                break
            case ReceivableAnticipationCreditCardLimitProfile.MORE_THAN_VERY_HIGH:
                minutes = 360
                break
            default:
                throw new NotImplementedException("Não existe uma configuração de tempo para recálculo do perfil [${profile}]")
        }

        return minutes
    }

    private ReceivableAnticipationCreditCardLimitProfile getCustomerCreditCardLimitProfile(Customer customer) {
        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
        BigDecimal creditCardLimit = receivableAnticipationConfig.buildCreditCardAnticipationLimit()
        ReceivableAnticipationCreditCardLimitProfile creditCardLimitProfile = ReceivableAnticipationCreditCardLimitProfile.findByLimit(creditCardLimit)
        return creditCardLimitProfile
    }

    private void saveLastValuesUsedToCalculateCreditCardLimit(ReceivableAnticipationLimitRecalculation recalculation, BigDecimal totalConfirmedValue, BigDecimal percentage) {
        ReceivableAnticipationLimitRecalculationInfo recalculationInfo = getRecalculationInfo(recalculation)
        recalculationInfo.lastCreditCardTotalConfirmedValue = totalConfirmedValue
        recalculationInfo.lastCreditCardPercentage = percentage
        recalculationInfo.save(failOnError: true)
    }

    private ReceivableAnticipationLimitRecalculationInfo getRecalculationInfo(ReceivableAnticipationLimitRecalculation recalculation) {
        ReceivableAnticipationLimitRecalculationInfo recalculationInfo = ReceivableAnticipationLimitRecalculationInfo.query(["recalculationId": recalculation.id]).get()
        if (recalculationInfo) return recalculationInfo

        recalculationInfo = new ReceivableAnticipationLimitRecalculationInfo()
        recalculationInfo.recalculation = recalculation
        return recalculationInfo
    }
}

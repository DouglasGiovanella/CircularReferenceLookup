package com.asaas.service.checkout

import com.asaas.domain.checkout.CustomerCheckoutLimit
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import org.hibernate.LockMode

@Transactional
class CustomerCheckoutLimitService {

    def customerInteractionService

    public CustomerCheckoutLimit save(Customer customer) {
        CustomerCheckoutLimit customerCheckoutLimit = new CustomerCheckoutLimit()
        customerCheckoutLimit.customer = customer
        customerCheckoutLimit.availableDailyLimit = customer.customerConfig.dailyCheckoutLimit
        customerCheckoutLimit.lastReset = new Date().clearTime()

        customerCheckoutLimit.save(flush: true, failOnError: true)

        return customerCheckoutLimit
    }

    public Boolean resetAll() {
        try {
            Boolean allReset = checkAllReset()
            if (allReset) return true

            CustomerCheckoutLimit.executeUpdate("""
                update CustomerCheckoutLimit ccl
                    set
                        ccl.availableDailyLimit = (select cc.dailyCheckoutLimit from CustomerConfig cc where cc.customer = ccl.customer),
                        ccl.lastUpdated = :lastUpdated,
                        ccl.lastReset = :lastReset
                    WHERE
                        ccl.lastReset < :lastReset
            """, [lastUpdated: new Date(), lastReset: new Date().clearTime()])

            return false
        } catch (Exception exception) {
            AsaasLogger.error("CustomerCheckoutLimitService.resetAll() -> Erro ao resetar limite diário.", exception)
            return false
        }
    }

    public Boolean checkAllReset() {
        Date expectedLastReset = new Date().clearTime()

        Boolean allReset = !CustomerCheckoutLimit.query([
            exists: true,
            "lastReset[lt]": expectedLastReset
        ]).get().asBoolean()

        return allReset
    }

    public AsaasError validateAvailableDailyLimit(Customer customer, BigDecimal value) {
        CustomerCheckoutLimit customerCheckoutLimit = customer.getCheckoutLimit()

        if (!customerCheckoutLimit) return

        BigDecimal availableDailyLimit = customerCheckoutLimit.availableDailyLimit - value

        if (availableDailyLimit < 0) return new AsaasError("denied.insufficient.daily.limit", [FormUtils.formatCurrencyWithMonetarySymbol(value), FormUtils.formatCurrencyWithMonetarySymbol(customerCheckoutLimit.availableDailyLimit)])
    }

    public void consumeDailyLimit(Customer customer, BigDecimal value, Boolean withTimeout) {
        CustomerCheckoutLimit customerCheckoutLimit = CustomerCheckoutLimit.createCriteria().get() {
            if (withTimeout) {
                Integer querySecondsTimeout = 1

                setLockMode(LockMode.PESSIMISTIC_WRITE)
                setTimeout(querySecondsTimeout)
            }

            eq("customer", customer)
        }

        if (!customerCheckoutLimit) return

        AsaasError asaasError = validateAvailableDailyLimit(customer, value)
        if (asaasError) throw new BusinessException(asaasError.getMessage())

        customerCheckoutLimit.availableDailyLimit = customerCheckoutLimit.availableDailyLimit - value
        customerCheckoutLimit.save(flush: true, failOnError: true)
    }

    public void refundDailyLimit(Customer customer, BigDecimal value) {
        CustomerCheckoutLimit customerCheckoutLimit = customer.getCheckoutLimit()

    	if (!customerCheckoutLimit) return
        if (value < 0) throw new BusinessException("O valor informado para estorno de consumo é inválido.")

        customerCheckoutLimit.availableDailyLimit = customerCheckoutLimit.availableDailyLimit + value
        customerCheckoutLimit.save(flush: true, failOnError: true)
    }

    public CustomerConfig setDailyLimit(Customer customer, BigDecimal newDailyCheckoutLimit, String observations) {
        return setDailyLimit(customer, newDailyCheckoutLimit, observations, false)
    }

    public CustomerConfig setDailyLimit(Customer customer, BigDecimal newDailyCheckoutLimit, String observations, Boolean byPassCustomerCheckoutLimit) {
        if (!customer.customerConfig.dailyCheckoutLimit) {
            setDefaultDailyCheckoutLimit(customer)
            save(customer)
        }

        if (!customer.getCheckoutLimit()) save(customer)

        BigDecimal dailyCheckoutLimitBeforeUpdate = customer.customerConfig.dailyCheckoutLimit

        if (!byPassCustomerCheckoutLimit) {
            customer.customerConfig = validateDailyCheckoutLimit(customer, newDailyCheckoutLimit)
            if (customer.customerConfig.hasErrors()) return customer.customerConfig
        }

        customer.customerConfig.dailyCheckoutLimit = newDailyCheckoutLimit
        customer.customerConfig.save(flush: false, failOnError: false)

        if (customer.customerConfig.hasErrors()) return customer.customerConfig

        customerInteractionService.saveUpdateCustomerDailyCheckoutLimit(customer, dailyCheckoutLimitBeforeUpdate, newDailyCheckoutLimit, observations)

        recalculateAvailableDailyLimit(customer, dailyCheckoutLimitBeforeUpdate, newDailyCheckoutLimit)

        return customer.customerConfig
    }

    public BigDecimal getAvailableDailyCheckout(Customer customer) {
        BigDecimal customerBalance = FinancialTransaction.getCustomerBalance(customer)
        CustomerCheckoutLimit customerCheckoutLimit = customer.getCheckoutLimit()

        if (!customerCheckoutLimit) return customerBalance
        if (customerBalance < customerCheckoutLimit.availableDailyLimit) return customerBalance

        return customerCheckoutLimit.availableDailyLimit
    }

    public CustomerConfig validateDailyCheckoutLimit(Customer customer, BigDecimal dailyCheckoutLimit) {
        BusinessValidation customerCanUpdateDailyCheckoutLimit = customer.customerConfig.customerCanUpdateDailyCheckoutLimit()
        if (!customerCanUpdateDailyCheckoutLimit.isValid()) return DomainUtils.copyAllErrorsFromBusinessValidation(customerCanUpdateDailyCheckoutLimit, customer.customerConfig)

        BigDecimal maxSegmentLimitValue = CustomerCheckoutLimit.getMaxDailyCheckoutLimit(customer)
        if (dailyCheckoutLimit > maxSegmentLimitValue) {
            DomainUtils.addError(customer.customerConfig, "Limite maior que o permitido para o segmento ${Utils.getMessageProperty("customerSegment.${customer.segment}")}.")
            return customer.customerConfig
        }

        return customer.customerConfig
    }

    private void recalculateAvailableDailyLimit(Customer customer, BigDecimal oldDailyCheckoutLimit, BigDecimal newDailyCheckoutLimit) {
        CustomerCheckoutLimit customerCheckoutLimit = customer.getCheckoutLimit()

        if (!customerCheckoutLimit) return

        BigDecimal dailyCheckoutLimitAlreadyConsumed = oldDailyCheckoutLimit - customerCheckoutLimit.availableDailyLimit

        if (dailyCheckoutLimitAlreadyConsumed < 0) dailyCheckoutLimitAlreadyConsumed = 0

        BigDecimal newAvailableDailyLimit = newDailyCheckoutLimit - dailyCheckoutLimitAlreadyConsumed

        customerCheckoutLimit.availableDailyLimit = newAvailableDailyLimit > 0 ? newAvailableDailyLimit : 0

        customerCheckoutLimit.save(flush: true, failOnError: true)
    }

    private void setDefaultDailyCheckoutLimit(Customer customer) {
        customer.customerConfig.dailyCheckoutLimit = CustomerConfig.getDefaultDailyCheckoutLimit(customer)
        customer.customerConfig.save(failOnError: true)
    }
}

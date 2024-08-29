package com.asaas.service.freepayment

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.freepaymentconfig.FreePaymentConfig
import com.asaas.domain.freepaymentconfig.FreePaymentMonthlyConfig
import com.asaas.domain.freepaymentconfig.FreePaymentUse
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class FreePaymentConfigService {

    def freePaymentConfigCacheService

    public void consumeFreePaymentIfPossible(Payment payment) {
        if (payment.billingType.isCreditCardOrDebitCard()) return
        if (payment.getAsaasValue() == 0) return

        FreePaymentMonthlyConfig freePaymentMonthlyConfig = FreePaymentMonthlyConfig.valid([customer: payment.provider]).get()

        if (!freePaymentMonthlyConfig) return

        if (!freePaymentMonthlyConfig.freePaymentsRemaining) return

        freePaymentMonthlyConfig.lock()

        freePaymentMonthlyConfig.freePaymentsRemaining = freePaymentMonthlyConfig.freePaymentsRemaining - 1
        freePaymentMonthlyConfig.save(failOnError: true)
        setNetValueAndSaveFreePaymentUse(payment, freePaymentMonthlyConfig)
    }

    public void createFreePaymentMonthlyConfigForNextMonth() {
        List<Long> freePaymentConfigIdList = FreePaymentConfig.query([column: "id", hasNotFreePaymentMonthlyConfigForNextMonth: true]).list(max: 200)

        for (Long freePaymentConfigId in freePaymentConfigIdList) {
            try {
                FreePaymentConfig freePaymentConfig = FreePaymentConfig.read(freePaymentConfigId)
                Date validMonth = CustomDateUtils.getFirstDayOfNextMonth().clearTime()
                saveFreePaymentMonthlyConfig(freePaymentConfig, validMonth)
            } catch (Exception e) {
                AsaasLogger.error("FreePaymentConfigService.createFreePaymentMonthlyConfigForNextMonth >> Erro ao salvar configurações mensais de cobranças gratuitas ${freePaymentConfigId}")
            }
        }
    }

    public Boolean customerHasFreePaymentToConsume(Customer customer) {
        FreePaymentMonthlyConfig freePaymentMonthlyConfig = FreePaymentMonthlyConfig.valid([customer: customer]).get()

        if (!freePaymentMonthlyConfig) return false

        return freePaymentMonthlyConfig.freePaymentsRemaining > 0
    }

    public FreePaymentConfig save(CustomerUpdateRequest customerUpdateRequest) {
        Customer customer = customerUpdateRequest.provider
        FreePaymentConfig freePaymentConfig = FreePaymentConfig.query([customer: customer]).get()

        if (freePaymentConfig) return freePaymentConfig

        if (customerUpdateRequest.isNaturalPerson()) {
            return save(customer, FreePaymentConfig.DEFAULT_INITIAL_FREE_PAYMENTS_AMOUNT_FOR_NATURAL_PERSON)
        }

        return save(customer, FreePaymentConfig.DEFAULT_INITIAL_FREE_PAYMENTS_AMOUNT)
    }

    public FreePaymentConfig save(Customer customer, Integer freePaymentsAmount) {
        FreePaymentConfig freePaymentConfig = FreePaymentConfig.query([customer: customer]).get()

        if (freePaymentConfig) throw new Exception("Configurações de cobranças gratuitas já existentes para o customer ${customer.id}")

        freePaymentConfig = new FreePaymentConfig(customer: customer)

        freePaymentConfig.freePaymentsAmount = freePaymentsAmount

        freePaymentConfig.save(failOnError: true)

        freePaymentConfigCacheService.evict(customer.id)

        Date validMonth = CustomDateUtils.getFirstDayOfCurrentMonth().clearTime()
        saveFreePaymentMonthlyConfig(freePaymentConfig, validMonth)

        return freePaymentConfig
    }

    public FreePaymentConfig update(Customer customer, Integer freePaymentsAmount, Boolean updateCurrentMonthlyConfig) {
        FreePaymentConfig freePaymentConfig = FreePaymentConfig.query([customer: customer]).get()
        if (!freePaymentConfig) throw new BusinessException("Não existe configuração de cobranças gratuitas para o customer ${customer.id}")

        freePaymentConfig.freePaymentsAmount = freePaymentsAmount
        freePaymentConfig.save(failOnError: true)

        freePaymentConfigCacheService.evict(customer.id)

        if (updateCurrentMonthlyConfig) updateFreePaymentRemainingCurrentMonthlyConfigIfPossible(freePaymentConfig)

        return freePaymentConfig
    }

    public FreePaymentConfig saveFromCustomerAdminConsole(Customer customer, Integer freePaymentsAmount) {
        if (customer.isNaturalPerson() && freePaymentsAmount > FreePaymentConfig.DEFAULT_INITIAL_FREE_PAYMENTS_AMOUNT_FOR_NATURAL_PERSON) {
            throw new BusinessException("Quantidade de cobranças gratuitas maior que o máximo permitido para pessoa física: ${FreePaymentConfig.DEFAULT_INITIAL_FREE_PAYMENTS_AMOUNT_FOR_NATURAL_PERSON}")
        }

        if (freePaymentsAmount > FreePaymentConfig.DEFAULT_INITIAL_FREE_PAYMENTS_AMOUNT) {
            throw new BusinessException("Quantidade de cobranças gratuitas maior que o máximo permitido: ${FreePaymentConfig.DEFAULT_INITIAL_FREE_PAYMENTS_AMOUNT}")
        }

        return save(customer, freePaymentsAmount)
    }

    public Map getFreePaymentNumbers(Customer customer) {
        Map responseMap = [:]

        FreePaymentMonthlyConfig freePaymentMonthlyConfig = FreePaymentMonthlyConfig.valid([customer: customer]).get()
        if (!freePaymentMonthlyConfig) return responseMap

        responseMap.freePaymentAmount = freePaymentMonthlyConfig.freePaymentConfig.freePaymentsAmount
        responseMap.freePaymentRemaining = freePaymentMonthlyConfig.freePaymentsRemaining

        return responseMap
    }

    private saveFreePaymentMonthlyConfig(FreePaymentConfig freePaymentConfig, Date validMonth) {
        FreePaymentMonthlyConfig freePaymentMonthlyConfig = FreePaymentMonthlyConfig.query([customer: freePaymentConfig.customer, validMonth: validMonth]).get()

        if (freePaymentMonthlyConfig) throw new Exception("Configurações mensais de cobranças gratuitas já existentes para o customer ${freePaymentConfig.customer.id}")

        freePaymentMonthlyConfig = new FreePaymentMonthlyConfig()
        freePaymentMonthlyConfig.customer = freePaymentConfig.customer
        freePaymentMonthlyConfig.freePaymentConfig = freePaymentConfig
        freePaymentMonthlyConfig.freePaymentsRemaining = freePaymentConfig.freePaymentsAmount
        freePaymentMonthlyConfig.validMonth = validMonth
        freePaymentMonthlyConfig.save(failOnError: true)

        return freePaymentConfig
    }


    private FreePaymentMonthlyConfig updateFreePaymentRemainingCurrentMonthlyConfigIfPossible(FreePaymentConfig freePaymentConfig) {
        Date validMonth = CustomDateUtils.getFirstDayOfCurrentMonth().clearTime()
        FreePaymentMonthlyConfig freePaymentMonthlyConfig = FreePaymentMonthlyConfig.query([freePaymentConfig: freePaymentConfig, customer: freePaymentConfig.customer, validMonth: validMonth]).get()
        if (!freePaymentMonthlyConfig) return null

        freePaymentMonthlyConfig.lock()

        Integer freePaymentsUsed = FreePaymentUse.query([freePaymentMonthlyConfig: freePaymentMonthlyConfig]).count()
        if (freePaymentsUsed > freePaymentConfig.freePaymentsAmount) return freePaymentMonthlyConfig

        freePaymentMonthlyConfig.freePaymentsRemaining = freePaymentConfig.freePaymentsAmount - freePaymentsUsed
        freePaymentMonthlyConfig.save(failOnError: true)

        return freePaymentMonthlyConfig
    }

    private void setNetValueAndSaveFreePaymentUse(Payment payment, FreePaymentMonthlyConfig freePaymentMonthlyConfig) {
        FreePaymentUse freePaymentUse = new FreePaymentUse()
        freePaymentUse.customer = payment.provider
        freePaymentUse.freePaymentMonthlyConfig = freePaymentMonthlyConfig
        freePaymentUse.payment = payment
        freePaymentUse.originalFeeValue = payment.getAsaasValue()
        freePaymentUse.feeDiscountApplied = freePaymentUse.originalFeeValue

        freePaymentUse.save(failOnError: true)
        payment.netValue = payment.value - (freePaymentUse.originalFeeValue - freePaymentUse.feeDiscountApplied)
    }
}

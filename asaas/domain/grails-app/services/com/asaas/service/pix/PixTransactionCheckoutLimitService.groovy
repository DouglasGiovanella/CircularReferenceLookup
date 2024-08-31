package com.asaas.service.pix

import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class PixTransactionCheckoutLimitService {

    def childAccountParameterBinderService
    def customerInteractionService
    def pixTransactionCheckoutLimitParameterService

    public PixTransactionCheckoutLimit save(Long customerId, Map limitConfig, Boolean applyForAllChildAccounts, Boolean fromAccountOwner) {
        Customer customer = Customer.get(customerId)

        PixTransactionCheckoutLimit validatedPixTransactionCheckoutLimit = validateSave(customer, limitConfig)
        if (validatedPixTransactionCheckoutLimit.hasErrors()) return validatedPixTransactionCheckoutLimit

        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = PixTransactionCheckoutLimit.query([customer: customer]).get()
        if (!pixTransactionCheckoutLimit) {
            pixTransactionCheckoutLimit = new PixTransactionCheckoutLimit()
            pixTransactionCheckoutLimit.customer = customer
        }

        Map oldLimits = [
            daytimeLimit: pixTransactionCheckoutLimit.daytimeLimit,
            daytimeLimitPerTransaction: pixTransactionCheckoutLimit.daytimeLimitPerTransaction,
            nightlyLimit: pixTransactionCheckoutLimit.nightlyLimit,
            nightlyLimitPerTransaction: pixTransactionCheckoutLimit.nightlyLimitPerTransaction,

            cashValueDaytimeLimit: PixTransactionCheckoutLimit.calculateCashValueDaytimeLimit(customer),
            cashValueDaytimeLimitPerTransaction: PixTransactionCheckoutLimit.calculateCashValueDaytimePerTransaction(customer),
            cashValueNightlyLimit: PixTransactionCheckoutLimit.calculateCashValueNightlyLimit(customer),
            cashValueNightlyLimitPerTransaction: PixTransactionCheckoutLimit.calculateCashValueNightlyLimitPerTransaction(customer),
        ]

        Map limitsChanged = limitConfig.findAll { Map.Entry limit ->
            limit.value != oldLimits.get(limit.key)
        }

        Boolean noChanges = limitsChanged.size() == 0
        if (noChanges) {
            return DomainUtils.addError(validatedPixTransactionCheckoutLimit, "Necessário informar ao menos um valor diferente dos já configurados para o Limite Pix")
        }

        saveCustomerDebitLimitInteraction(customer, oldLimits, limitsChanged, fromAccountOwner)

        pixTransactionCheckoutLimit.properties[
            "daytimeLimit",
            "daytimeLimitPerTransaction",
            "nightlyLimit",
            "nightlyLimitPerTransaction",
            "cashValueDaytimeLimit",
            "cashValueDaytimeLimitPerTransaction",
            "cashValueNightlyLimit",
            "cashValueNightlyLimitPerTransaction"
        ] += limitsChanged

        pixTransactionCheckoutLimit.save(failOnError: true)

        if (applyForAllChildAccounts) {
            PixTransactionCheckoutLimit validatedDomain = validateSetCheckoutLimitForChildAccounts(customerId)
            if (validatedDomain.hasErrors()) return validatedDomain

            saveCheckoutLimitForChildAccounts(customer.id, limitsChanged)
        }

        return pixTransactionCheckoutLimit
    }

    public PixTransactionCheckoutLimit saveDaytimeLimit(Customer customer, BigDecimal newLimit) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = build(customer)
        pixTransactionCheckoutLimit.daytimeLimit = newLimit
        pixTransactionCheckoutLimit.save(failOnError: true)

        return pixTransactionCheckoutLimit
    }

    public PixTransactionCheckoutLimit saveDaytimeLimitPerTransaction(Customer customer, BigDecimal newLimit) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = build(customer)
        pixTransactionCheckoutLimit.daytimeLimitPerTransaction = newLimit
        pixTransactionCheckoutLimit.save(failOnError: true)

        return pixTransactionCheckoutLimit
    }

    public void saveDefaultNightlyLimitIfNecessary(Customer customer) {
        if (customer.isNaturalPerson() || customer.isMEI()) return

        BigDecimal nightlyLimit = PixTransactionCheckoutLimit.query([column: "nightlyLimit", customer: customer]).get()
        if (nightlyLimit != null) return

        saveNightlyLimit(customer, PixTransactionCheckoutLimit.LEGAL_ENTITY_DEFAULT_NIGHTLY_LIMIT)
    }

    public PixTransactionCheckoutLimit saveNightlyLimit(Customer customer, BigDecimal newLimit) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = build(customer)
        pixTransactionCheckoutLimit.nightlyLimit = newLimit
        pixTransactionCheckoutLimit.save(failOnError: true)

        return pixTransactionCheckoutLimit
    }

    public PixTransactionCheckoutLimit saveNightlyLimitPerTransaction(Customer customer, BigDecimal newLimit) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = build(customer)
        pixTransactionCheckoutLimit.nightlyLimitPerTransaction = newLimit
        pixTransactionCheckoutLimit.save(failOnError: true)

        return pixTransactionCheckoutLimit
    }

    public PixTransactionCheckoutLimit saveCashValueDaytimeLimit(Customer customer, BigDecimal newLimit) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = build(customer)
        pixTransactionCheckoutLimit.cashValueDaytimeLimit = newLimit
        pixTransactionCheckoutLimit.save(failOnError: true)

        return pixTransactionCheckoutLimit
    }

    public PixTransactionCheckoutLimit saveCashValueDaytimeLimitPerTransaction(Customer customer, BigDecimal newLimit) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = build(customer)
        pixTransactionCheckoutLimit.cashValueDaytimeLimitPerTransaction = newLimit
        pixTransactionCheckoutLimit.save(failOnError: true)

        return pixTransactionCheckoutLimit
    }

    public PixTransactionCheckoutLimit saveCashValueNightlyLimit(Customer customer, BigDecimal newLimit) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = build(customer)
        pixTransactionCheckoutLimit.cashValueNightlyLimit = newLimit
        pixTransactionCheckoutLimit.save(failOnError: true)

        return pixTransactionCheckoutLimit
    }

    public PixTransactionCheckoutLimit saveCashValueNightlyLimitPerTransaction(Customer customer, BigDecimal newLimit) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = build(customer)
        pixTransactionCheckoutLimit.cashValueNightlyLimitPerTransaction = newLimit
        pixTransactionCheckoutLimit.save(failOnError: true)

        return pixTransactionCheckoutLimit
    }

    public PixTransactionCheckoutLimit applyChangeRequestOnNightlyHour(Customer customer, Integer initialNightlyHourRequested) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = build(customer)
        pixTransactionCheckoutLimit.initialNightlyHour = initialNightlyHourRequested
        pixTransactionCheckoutLimit.save(failOnError: true)

        return pixTransactionCheckoutLimit
    }

    private void saveCustomerDebitLimitInteraction(Customer customer, Map oldLimits, Map limitsChanged, Boolean fromAccountOwner) {
        String description = "Alteração nos limites de transferência Pix"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        limitsChanged.each { Map.Entry changed ->
            String fieldName = Utils.getMessageProperty("pixTransactionCheckoutLimit.${changed.key}.label")
            description += "${fieldName} de "
            description += "${FormUtils.formatCurrencyWithMonetarySymbol(oldLimits.get(changed.key))} para ${FormUtils.formatCurrencyWithMonetarySymbol(changed.value)}\n"
        }

        customerInteractionService.save(customer, description)
    }

    private PixTransactionCheckoutLimit validateSetCheckoutLimitForChildAccounts(Long accountOwnerId) {
        PixTransactionCheckoutLimit validatedDomain = new PixTransactionCheckoutLimit()

        Map search = [:]
        search.exists = true
        search.accountOwnerId = accountOwnerId
        search.type = PixTransactionCheckoutLimit.simpleName
        search."name[in]" = ["daytimeLimitPerTransaction", "nightlyLimitPerTransaction"]

        Boolean hasPixTransactionCheckoutLimitParameter = ChildAccountParameter.query(search).get().asBoolean()
        if (hasPixTransactionCheckoutLimitParameter) DomainUtils.addError(validatedDomain, Utils.getMessageProperty("feeAdmin.validationMessage.customerAlreadyHasParameter", ["limites do Pix"]))

        return validatedDomain
    }

    private void saveCheckoutLimitForChildAccounts(Long customerId, Map limitConfig) {
        limitConfig.each {
            pixTransactionCheckoutLimitParameterService.saveParameter(customerId, it.key, it.value)
            childAccountParameterBinderService.applyParameterForAllChildAccounts(customerId, it.key, PixTransactionCheckoutLimit.simpleName)
        }
    }

    private PixTransactionCheckoutLimit build(Customer customer) {
        PixTransactionCheckoutLimit pixTransactionCheckoutLimit = PixTransactionCheckoutLimit.query([customer: customer]).get()
        if (!pixTransactionCheckoutLimit) {
            pixTransactionCheckoutLimit = new PixTransactionCheckoutLimit()
            pixTransactionCheckoutLimit.customer = customer
        }

        return pixTransactionCheckoutLimit
    }

    private PixTransactionCheckoutLimit validateSave(Customer customer, Map limitConfig) {
        PixTransactionCheckoutLimit validatedPixTransactionCheckoutLimit = new PixTransactionCheckoutLimit()

        AsaasError asaasError = PixTransactionCheckoutLimit.changeCanBeRequested(customer)
        if (asaasError) return DomainUtils.addError(validatedPixTransactionCheckoutLimit, asaasError.getMessage())

        if (limitConfig.daytimeLimitPerTransaction != null) {
            BigDecimal daytimeLimit = limitConfig.daytimeLimit != null ? limitConfig.daytimeLimit : PixTransactionCheckoutLimit.calculateDaytimeLimit(customer)
            if (limitConfig.daytimeLimitPerTransaction > daytimeLimit) {
                return DomainUtils.addError(validatedPixTransactionCheckoutLimit, "O limite diurno por transação deve ser inferior ao limite por período.")
            }
        }

        if (limitConfig.nightlyLimitPerTransaction != null) {
            BigDecimal nightlyLimit = limitConfig.nightlyLimit != null ? limitConfig.nightlyLimit : PixTransactionCheckoutLimit.getNightlyLimit(customer)
            if (limitConfig.nightlyLimitPerTransaction > nightlyLimit) {
                return DomainUtils.addError(validatedPixTransactionCheckoutLimit, "O limite noturno por transação deve ser inferior ao limite por período.")
            }
        }

        if (limitConfig.cashValueDaytimeLimitPerTransaction != null) {
            BigDecimal daytimeLimit = limitConfig.cashValueDaytimeLimit != null ? limitConfig.cashValueDaytimeLimit : PixTransactionCheckoutLimit.calculateCashValueDaytimeLimit(customer)
            if (limitConfig.cashValueDaytimeLimitPerTransaction > daytimeLimit) {
                return DomainUtils.addError(validatedPixTransactionCheckoutLimit, "O limite diurno por transação do Saque e Troco deve ser inferior ao limite por período.")
            }
        }

        if (limitConfig.cashValueNightlyLimitPerTransaction != null) {
            BigDecimal nightlyLimit = limitConfig.cashValueNightlyLimit != null ? limitConfig.cashValueNightlyLimit : PixTransactionCheckoutLimit.calculateCashValueNightlyLimit(customer)
            if (limitConfig.cashValueNightlyLimitPerTransaction > nightlyLimit) {
                return DomainUtils.addError(validatedPixTransactionCheckoutLimit, "O limite noturno por transação do Saque e Troco deve ser inferior ao limite por período.")
            }
        }

        return validatedPixTransactionCheckoutLimit
    }
}

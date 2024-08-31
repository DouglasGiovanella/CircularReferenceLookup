package com.asaas.service.pix.limit

import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimit
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.utils.DomainUtils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class PixTransactionBankAccountInfoCheckoutLimitService {

    def customerMessageService

    public PixTransactionBankAccountInfoCheckoutLimit save(BankAccountInfo bankAccountInfo, BigDecimal dailyLimit) {
        PixTransactionBankAccountInfoCheckoutLimit validatedPixTransactionBankAccountInfoCheckoutLimit = validate(bankAccountInfo, dailyLimit)
        if (validatedPixTransactionBankAccountInfoCheckoutLimit.hasErrors()) return validatedPixTransactionBankAccountInfoCheckoutLimit

        PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit = PixTransactionBankAccountInfoCheckoutLimit.query([bankAccountInfo: bankAccountInfo]).get()
        if (!pixTransactionBankAccountInfoCheckoutLimit) {
            pixTransactionBankAccountInfoCheckoutLimit = new PixTransactionBankAccountInfoCheckoutLimit()
            pixTransactionBankAccountInfoCheckoutLimit.customer = bankAccountInfo.customer
            pixTransactionBankAccountInfoCheckoutLimit.bankAccountInfo = bankAccountInfo
        }

        pixTransactionBankAccountInfoCheckoutLimit.dailyLimit = dailyLimit
        return pixTransactionBankAccountInfoCheckoutLimit.save(failOnError: true)
    }

    public PixTransactionBankAccountInfoCheckoutLimit validate(BankAccountInfo bankAccountInfo, BigDecimal requestedDailyLimit) {
        PixTransactionBankAccountInfoCheckoutLimit validatedPixTransactionBankAccountInfoCheckoutLimit = new PixTransactionBankAccountInfoCheckoutLimit()

        AsaasError asaasError = PixTransactionCheckoutLimit.changeCanBeRequested(bankAccountInfo.customer)
        if (asaasError) return DomainUtils.addError(validatedPixTransactionBankAccountInfoCheckoutLimit, asaasError.getMessage())

        if (requestedDailyLimit == null || requestedDailyLimit < 0) {
            return DomainUtils.addError(validatedPixTransactionBankAccountInfoCheckoutLimit, "Informe um valor maior ou igual a zero.")
        }

        if (!bankAccountInfo.status.isApproved()) {
            return DomainUtils.addError(validatedPixTransactionBankAccountInfoCheckoutLimit, "Apenas contas aprovadas podem ter limite personalizado.")
        }

        BigDecimal currentBankAccountInfoDailyLimit = PixTransactionBankAccountInfoCheckoutLimit.query([column: "dailyLimit", bankAccountInfo: bankAccountInfo]).get()
        Boolean isEqualsToCurrentLimit = requestedDailyLimit == currentBankAccountInfoDailyLimit
        if (isEqualsToCurrentLimit) {
            return DomainUtils.addError(validatedPixTransactionBankAccountInfoCheckoutLimit, "Informe um limite diferente do atual.")
        }

        return validatedPixTransactionBankAccountInfoCheckoutLimit
    }

    public PixTransactionBankAccountInfoCheckoutLimit delete(PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit) {
        pixTransactionBankAccountInfoCheckoutLimit.deleted = true
        pixTransactionBankAccountInfoCheckoutLimit.save(failOnError: true)

        customerMessageService.notifyPixTransactionBankAccountInfoCheckoutLimitDeleted(pixTransactionBankAccountInfoCheckoutLimit)

        return pixTransactionBankAccountInfoCheckoutLimit
    }
}

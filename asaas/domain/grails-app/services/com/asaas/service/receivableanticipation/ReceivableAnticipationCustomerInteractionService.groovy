package com.asaas.service.receivableanticipation

import com.asaas.billinginfo.BillingType
import com.asaas.customerreceivableanticipationconfig.adapter.ToggleBlockBillingTypeAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.user.User
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ReceivableAnticipationCustomerInteractionService {

    def customerInteractionService

    public void saveUpdateCreditCardPercentage(Customer customer, User attendant, BigDecimal previousCreditCardPercentage, BigDecimal newCreditCardPercentage) {
        String customerInteractionMessage
        if (previousCreditCardPercentage == null) {
            customerInteractionMessage = "Cliente inserido com ${FormUtils.formatWithPercentageSymbol(newCreditCardPercentage)} de agenda para antecipações em cartão."
        } else {
            customerInteractionMessage = "Atualização do % de agenda (cartão): Alterado de ${FormUtils.formatWithPercentageSymbol(previousCreditCardPercentage)} para ${FormUtils.formatWithPercentageSymbol(newCreditCardPercentage)}."
        }

        customerInteractionService.save(customer, customerInteractionMessage, attendant)
    }

    public void saveUpdateCreditCardLimit(Customer customer, User attendant, BigDecimal previousCreditCardLimit, BigDecimal newCreditCardLimit) {
        String message = "Valor máximo comprometido com antecipações de cartão de crédito alterado de ${FormUtils.formatCurrencyWithMonetarySymbol(previousCreditCardLimit)} para ${FormUtils.formatCurrencyWithMonetarySymbol(newCreditCardLimit)}"

        customerInteractionService.save(customer, message, attendant)
    }

    public void saveUpdateCustomerReceivableAnticipationConfigFee(Customer customer, Map receivableAnticipationConfigFee) {
        String description = ""

        if (receivableAnticipationConfigFee.creditCardDetachedDailyFeeBefore != receivableAnticipationConfigFee.creditCardDetachedDailyFeeAfter) {
            description += buildFeeUpdateCustomerInteractionDescription("Cobranças avulsas de cartão de crédito", Utils.toBigDecimal(receivableAnticipationConfigFee.creditCardDetachedDailyFeeBefore), Utils.toBigDecimal(receivableAnticipationConfigFee.creditCardDetachedDailyFeeAfter), CustomerReceivableAnticipationConfig.getDefaultCreditCardDetachedDailyFee())
        }

        if (receivableAnticipationConfigFee.creditCardInstallmentDailyFeeBefore != receivableAnticipationConfigFee.creditCardInstallmentDailyFeeAfter) {
            description += buildFeeUpdateCustomerInteractionDescription("Cobranças parceladas de cartão de crédito", Utils.toBigDecimal(receivableAnticipationConfigFee.creditCardInstallmentDailyFeeBefore), Utils.toBigDecimal(receivableAnticipationConfigFee.creditCardInstallmentDailyFeeAfter), CustomerReceivableAnticipationConfig.getDefaultCreditCardInstallmentDailyFee())
        }

        if (receivableAnticipationConfigFee.bankSlipDailyFeeBefore != receivableAnticipationConfigFee.bankSlipDailyFeeAfter) {
            description += buildFeeUpdateCustomerInteractionDescription("Boleto", Utils.toBigDecimal(receivableAnticipationConfigFee.bankSlipDailyFeeBefore), Utils.toBigDecimal(receivableAnticipationConfigFee.bankSlipDailyFeeAfter), CustomerReceivableAnticipationConfig.getDefaultBankSlipDailyFee())
        }

        if (description) {
            customerInteractionService.save(customer, "Taxa diária de antecipação alterada: ${description}")
        }
    }

    public void saveUpdateCustomerReceivableAnticipationConfigEnabled(Customer customer, Map receivableAnticipationConfigEnabled) {
        String description = ""

        if (receivableAnticipationConfigEnabled.bankSlipEnabledBefore != receivableAnticipationConfigEnabled.bankSlipEnabledAfter) {
            description += buildAnticipationEnabledCustomerInteractionDescription("Permitir antecipações de boleto", Utils.toBoolean(receivableAnticipationConfigEnabled.bankSlipEnabledBefore), Utils.toBoolean(receivableAnticipationConfigEnabled.bankSlipEnabledAfter))
        }

        if (receivableAnticipationConfigEnabled.creditCardEnabledBefore != receivableAnticipationConfigEnabled.creditCardEnabledAfter) {
            description += buildAnticipationEnabledCustomerInteractionDescription("Permitir antecipações de cartão de crédito", Utils.toBoolean(receivableAnticipationConfigEnabled.creditCardEnabledBefore), Utils.toBoolean(receivableAnticipationConfigEnabled.creditCardEnabledAfter))
        }

        if (description) {
            customerInteractionService.save(customer, "Configuração de antecipação alterada: ${description}")
        }
    }

    public void saveCustomerInteractionToggleBlockBillingType(ToggleBlockBillingTypeAdapter toggleBlockBillingTypeAdapter, BillingType billingType) {
        final String operationType = toggleBlockBillingTypeAdapter.isEnablingOperation ? "habilitadas" : "desabilitadas"
        String description = "Antecipações de ${billingType.getLabel()} ${operationType}."

        if (toggleBlockBillingTypeAdapter.disableReason) {
            String disableReasonText = toggleBlockBillingTypeAdapter.disableReason.getLabel()
            description += "\nMotivo da Desabilitação: ${disableReasonText}."
        }

        description += "\nObservações: ${toggleBlockBillingTypeAdapter.observation}."

        customerInteractionService.save(toggleBlockBillingTypeAdapter.customer, description)
    }

    private String buildFeeUpdateCustomerInteractionDescription(String feeLabel, BigDecimal feeBefore, BigDecimal feeAfter, BigDecimal defaultFee) {
        String description = ""

        if (feeBefore == null) {
            feeBefore = defaultFee
        } else if (feeAfter == null) {
            feeAfter = defaultFee
        }

        description += ("\n${feeLabel} de ${FormUtils.formatWithPercentageSymbol(feeBefore, 5)} para ${FormUtils.formatWithPercentageSymbol(feeAfter, 5)}.")
        return description
    }

    private String buildAnticipationEnabledCustomerInteractionDescription(String label, Boolean valueBefore, Boolean valueAfter) {
        return "\n${label} de ${valueBefore ? 'Sim' : 'Não'} para ${valueAfter ? 'Sim' : 'Não'}"
    }
}

package com.asaas.service.accountowner

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.accountowner.ChildAccountParameterInteraction
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ChildAccountParameterInteractionService {

    public void saveBankSlipFee(Customer accountOwner, Map bankSlipFeeConfig) {
        StringBuilder bankSlipFeeDescription = new StringBuilder()

        if (bankSlipFeeConfig.containsKey("defaultValue")) {
            BigDecimal parsedDefaultValue = Utils.toBigDecimal(bankSlipFeeConfig.defaultValue)
            bankSlipFeeDescription.append("\nTaxa padrão: ${FormUtils.formatCurrencyWithMonetarySymbol(parsedDefaultValue)}")
        }
        if (bankSlipFeeConfig.containsKey("discountValue")) {
            BigDecimal parsedDiscountValue = Utils.toBigDecimal(bankSlipFeeConfig.discountValue)
            bankSlipFeeDescription.append("\nValor promocional: ${FormUtils.formatCurrencyWithMonetarySymbol(parsedDiscountValue)}")
        }
        if (bankSlipFeeConfig.containsKey("discountExpirationDate")) {
            String expirationDateMessage = "\nDesconto válido até"
            bankSlipFeeDescription.append(bankSlipFeeConfig.discountExpirationDate ? "${expirationDateMessage}: ${CustomDateUtils.fromDate(bankSlipFeeConfig.discountExpirationDate)}" : "${expirationDateMessage}: Não informado")
        }

        if (bankSlipFeeConfig.containsKey("discountExpirationInMonths")) {
            String expirationInMonthsMessage = "\nDesconto válido por até"
            bankSlipFeeDescription.append(bankSlipFeeConfig.discountExpirationInMonths ? "${expirationInMonthsMessage}: ${bankSlipFeeConfig.discountExpirationInMonths} meses" : "${expirationInMonthsMessage}: Não informado")
        }

        save(accountOwner, "Alteração nas configurações de boleto: ${bankSlipFeeDescription}")
    }

    public void savePixCreditFee(Customer accountOwner, Map pixCreditFeeConfig) {
        StringBuilder pixCreditFeeUpdateDescription = new StringBuilder()

        if (pixCreditFeeConfig.containsKey("fixedFee")) {
            pixCreditFeeUpdateDescription.append("\nTaxa fixa padrão: ")
            pixCreditFeeUpdateDescription.append(pixCreditFeeConfig.fixedFee ? FormUtils.formatCurrencyWithMonetarySymbol(pixCreditFeeConfig.fixedFee) : "-")
        }

        if (pixCreditFeeConfig.containsKey("fixedFeeWithDiscount")) {
            pixCreditFeeUpdateDescription.append("\nTaxa promocional: ")
            pixCreditFeeUpdateDescription.append(pixCreditFeeConfig.fixedFeeWithDiscount ? FormUtils.formatCurrencyWithMonetarySymbol(pixCreditFeeConfig.fixedFeeWithDiscount) : "-")
        }

        if (pixCreditFeeConfig.containsKey("discountExpirationDate")) {
            pixCreditFeeUpdateDescription.append("\nDesconto válido até: ")
            pixCreditFeeUpdateDescription.append(pixCreditFeeConfig.discountExpirationDate ? CustomDateUtils.fromDate(pixCreditFeeConfig.discountExpirationDate) : "-")
        }

        if (pixCreditFeeConfig.containsKey("discountExpirationInMonths")) {
            pixCreditFeeUpdateDescription.append("\nDesconto válido por até: ")
            pixCreditFeeUpdateDescription.append(StringUtils.getPlural(pixCreditFeeConfig.discountExpirationInMonths, "mês", "meses") ?: "-")
        }

        if (pixCreditFeeConfig.containsKey("percentageFee")) {
            pixCreditFeeUpdateDescription.append("\nTaxa percentual padrão: ")
            pixCreditFeeUpdateDescription.append(pixCreditFeeConfig.percentageFee ? "${FormUtils.formatWithPercentageSymbol(pixCreditFeeConfig.percentageFee, 4)}" : "-")
        }

        if (pixCreditFeeConfig.containsKey("minimumFee")) {
            pixCreditFeeUpdateDescription.append("\nTaxa mínima: ")
            pixCreditFeeUpdateDescription.append(pixCreditFeeConfig.minimumFee ? FormUtils.formatCurrencyWithMonetarySymbol(pixCreditFeeConfig.minimumFee) : "-")
        }

        if (pixCreditFeeConfig.containsKey("maximumFee")) {
            pixCreditFeeUpdateDescription.append("\nTaxa máxima: ")
            pixCreditFeeUpdateDescription.append(pixCreditFeeConfig.maximumFee ? FormUtils.formatCurrencyWithMonetarySymbol(pixCreditFeeConfig.maximumFee) : "-")
        }

        save(accountOwner, "Alteração nas configurações de taxa de crédito de Pix: ${pixCreditFeeUpdateDescription}")
    }

    public void saveCreditCardFee(Customer accountOwner, Map feeConfig) {
        String description = ""

        feeConfig.each {
            description += "\n${Utils.getMessageProperty("creditCardFeeConfig.${it.key}.label")}: ${it.value}"
        }
        save(accountOwner, "Alteração nas configurações de taxas de cartão de crédito: ${description}")
    }

    public void saveCustomerReceivableAnticipation(Customer accountOwner, String fieldName, Object value) {
        if (CustomerReceivableAnticipationConfig.ENABLED_ANTICIPATION_CONFIG_LIST.contains(fieldName)) {
            saveCustomerReceivableAnticipationConfigEnabled(accountOwner, fieldName, Utils.toBoolean(value))
            return
        }

        saveCustomerReceivableAnticipationFeeConfig(accountOwner, fieldName, Utils.toBigDecimal(value))
    }

    public void saveCustomerAttributesConfig(Customer accountOwner, ChildAccountParameter childAccountParameter) {
        String description = "Alteração no "

        if (childAccountParameter.name == "boletoBankId") {
            BoletoBank boletoBank = BoletoBank.get(childAccountParameter.value)
            description += "banco de emissão de boletos \n Banco responsável: ${boletoBank.bank.name}"
        } else {
            description += "softdescriptor \n Softdescriptor: ${childAccountParameter.value}"
        }

        save(accountOwner, description)
    }

    public void saveCustomerConfigParameter(Customer accountOwner, String fieldName, Object value) {
        String description = "${Utils.getMessageProperty("childParameterDescription.${fieldName}")}: ${value ? "Sim" : "Não"}"
        save(accountOwner, description)
    }

    public void saveCanHandleBillingInfo(Customer accountOwner, Boolean enabled) {
        String valueDescription = enabled ? "Habilitado" : "Desabilitado"
        save(accountOwner, "Tokenização de cartão: ${valueDescription}")
    }

    public void saveCriticalActionConfig(Customer accountOwner, String fieldName, Boolean value) {
        String description = "${Utils.getMessageProperty("customerCriticalAction.${fieldName}")}: ${value ? "Sim" : "Não"} "
        save(accountOwner, "Alteração de evento crítico:\n ${description}")
    }

    public void saveCustomerParameterConfig(Customer accountOwner, CustomerParameterName customerParameterName, value) {
        String description = "${customerParameterName.getLabel()}: "

        if (customerParameterName.valueType == Boolean) {
            description += "${Utils.toBoolean(value) ? "Sim" : "Não"}"
        } else if (CustomerParameterName.listCurrencyFormatParameters().contains(customerParameterName)) {
            description += "${FormUtils.formatCurrencyWithMonetarySymbol(Utils.toBigDecimal(value))}"
        } else {
            description += value
        }

        save(accountOwner, "Alteração de parâmetro:\n ${description}")
    }

    public void saveInternalLoanConfig(Customer accountOwner, Boolean enabled) {
        String valueDescription = enabled ? "Habilitado" : "Desabilitado"
        save(accountOwner, "Alteração da configuração do mecanismo avalista-devedor: ${valueDescription}")
    }

    public void saveCustomerFee(Customer accountOwner, String fieldName, Object value) {
        String feeDescription = Utils.getMessageProperty("feeDescription.${fieldName}")
        String description = "Alteração da ${feeDescription}:\n "

        if (fieldName == "alwaysChargeTransferFee") {
            description += "${Utils.toBoolean(value) ? "Sim" : "Não"}"
        } else {
            description += FormUtils.formatCurrencyWithMonetarySymbol(value)
        }

        save(accountOwner, description)
    }

    public void savePixTransactionCheckoutLimit(Customer accountOwner, String fieldName, Object value) {
        String feeDescription = Utils.getMessageProperty("pixTransactionCheckoutLimit.${fieldName}.label")
        save(accountOwner, "Alteração do ${feeDescription}:\n ${FormUtils.formatCurrencyWithMonetarySymbol(value)}")
    }

    public void saveCustomerTransferConfig(Customer accountOwner, String fieldName, Object value) {
        String description = Utils.getMessageProperty("customerTransferConfig.${fieldName}.label")
        save(accountOwner, "Alteração da ${description}: \n ${value}")
    }

    public void saveDeletion(Customer accountOwner, String reason) {
        save(accountOwner, "Remoção de parâmetros das contas filhas por ${reason}")
    }

    private ChildAccountParameterInteraction save(Customer accountOwner, String description) {
        ChildAccountParameterInteraction validatedInteraction = validateSave(accountOwner, description)
        if (validatedInteraction.hasErrors()) return validatedInteraction

        ChildAccountParameterInteraction childAccountParameterInteraction = new ChildAccountParameterInteraction()
        childAccountParameterInteraction.accountOwner = accountOwner
        childAccountParameterInteraction.user = UserUtils.getCurrentUser()
        childAccountParameterInteraction.description = description
        childAccountParameterInteraction.save(failOnError: true)

        return childAccountParameterInteraction
    }

    private ChildAccountParameterInteraction validateSave(Customer accountOwner, String description) {
        ChildAccountParameterInteraction validatedInteraction = new ChildAccountParameterInteraction()

        if (!description) {
            return DomainUtils.addError(validatedInteraction, "Informe uma descrição.")
        }

        if (!accountOwner) {
            return DomainUtils.addError(validatedInteraction, "Informe uma conta pai.")
        }

        return validatedInteraction
    }

    private void saveCustomerReceivableAnticipationFeeConfig(Customer accountOwner, String fieldName, BigDecimal value) {
        String feeDescription = Utils.getMessageProperty("receivableAnticipationFeeDescription.${fieldName}")
        String description = "Valor da ${feeDescription}: ${FormUtils.formatWithPercentageSymbol(value)}"
        save(accountOwner, "Alteração da ${feeDescription} (${fieldName}): \n ${description}")
    }

    private void saveCustomerReceivableAnticipationConfigEnabled(Customer accountOwner, String fieldName, Boolean value) {
        String configDescription = Utils.getMessageProperty("receivableAnticipationConfigDescription.${fieldName}")
        save(accountOwner, "Alteração de configuração: ${configDescription} (${fieldName}): ${value ? 'Sim' : 'Não'}")
    }
}

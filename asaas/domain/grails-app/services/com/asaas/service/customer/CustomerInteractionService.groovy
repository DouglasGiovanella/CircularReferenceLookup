package com.asaas.service.customer

import com.asaas.criticalaction.CriticalActionType
import com.asaas.customer.CustomerInteractionType
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerSegment
import com.asaas.customer.PersonType
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.bank.Bank
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerInteraction
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.user.User
import com.asaas.integration.callcenter.outgoingphonecall.adapter.OutgoingPhoneCallAdapter
import com.asaas.integration.callcenter.phonecall.PhoneCallType
import com.asaas.promotionalcode.PromotionalCodeType
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.springframework.web.context.request.RequestContextHolder

@Transactional
class CustomerInteractionService {

    def grailsApplication
    def treasureDataService

    public List<CustomerInteraction> listFromCustomer(Customer customer) {
        return listFromCustomer(customer, false)
    }

    public List<CustomerInteraction> listFromCustomer(Customer customer, Boolean showCriticalActionAndSecurity) {
        Map search = [customer: customer]
        if (!showCriticalActionAndSecurity) {
            search."type[notIn]" = CustomerInteractionType.listCanBeFilteredThroughUserInterface()
        }

        List<CustomerInteraction> customerInteractionList = CustomerInteraction.query(search).list(readonly: true)
        return customerInteractionList
    }

    public CustomerInteraction saveManualInteraction(params) {
        Map parsedParams = parseParams(params)

        CustomerInteraction validatedCustomerInteraction = validateSaveManualInteraction(parsedParams)
        if (validatedCustomerInteraction.hasErrors()) return validatedCustomerInteraction

        CustomerInteraction customerInteraction = save(parsedParams)
        trackAttendantMetricIfNecessary(customerInteraction)

        return customerInteraction
    }

    public CustomerInteraction save(Map params) {
        Customer customer = Customer.query([id: params.customerId]).get()
        User user = UserUtils.getCurrentUser(RequestContextHolder.getRequestAttributes()?.getSession())

        return save(customer, params.description, user, params.type)
    }

    public CustomerInteraction save(Customer customer, String description) {
        User user = UserUtils.getCurrentUser(RequestContextHolder.getRequestAttributes()?.getSession())
        return save(customer, description, user)
    }

    public CustomerInteraction saveWithReason(Customer customer, String title, String reason) {
        return save(customer, "${title}. Motivo: ${reason}.")
    }

    public CustomerInteraction save(Customer customer, String description, User attendant) {
        return save(customer, description, attendant, CustomerInteractionType.NONE)
    }

    public CustomerInteraction save(Customer customer, String description, User attendant, CustomerInteractionType type) {
        CustomerInteraction customerInteraction = new CustomerInteraction()
        customerInteraction.type = type
        customerInteraction.customer = customer
        customerInteraction.description = description
        customerInteraction.attendant = attendant

        return customerInteraction.save(failOnError: true)
    }

    public void saveCustomerBlockInfo(Long customerId, String reason) {
        String description = "Cliente bloqueado."
        if (reason != null) description += " ${reason}"
        save(Customer.findById(customerId), description, UserUtils.getCurrentUser())
    }

    public void saveCustomerUnblockInfo(Long customerId) {
        save(Customer.findById(customerId), "Fornecedor desbloqueado.")
    }

    public void saveBoletoBlock(Long customerId) {
        save(Customer.get(customerId), "Geração de boleto bloqueada.")
    }

    public void saveBoletoUnblock(Customer customer) {
        save(customer, "Geração de boleto desbloqueada.")
    }

    public void saveExtendBoletoBlockDate(Long customerId, Integer extendedDays) {
        save(Customer.get(customerId), "Geração de boleto estendida por mais ${extendedDays * 24} horas.")
    }

    public void saveCustomerActivation(Long customerId) {
        save(Customer.get(customerId), "Conta ativada.")
    }

    public void saveCustomerSegmentChange(Customer customer, CustomerSegment oldCustomerSegment, CustomerSegment newCustomerSegment) {
        save(customer, "Mudança de segmento, de ${oldCustomerSegment?.getLabel()} para ${newCustomerSegment.getLabel()}.")
    }

    public void saveCustomerUpdateRequestApproval(Long customerId, String observations) {
        save(Customer.get(customerId), "Alteração dos Dados Comerciais Aprovada.\nObservações: ${observations}")
    }

    public void saveCustomerUpdateRequestDenial(Long customerId, String observations, String denialReason) {
        save(Customer.get(customerId), "Alteração dos Dados Comerciais Negada.\nMotivo da Recusa: ${denialReason}\nObservações: ${observations}")
    }

    public void saveBankAccountApproval(Long customerId, String observations) {
        save(Customer.get(customerId), "Alteração dos Dados Bancários Aprovada.\nObservações: ${observations}")
    }

    public void saveBankAccountDenial(Long customerId, String observations, String denialReason) {
        save(Customer.get(customerId), "Alteração dos Dados Bancários Negada.\nMotivo da Recusa: ${denialReason}\nObservações: ${observations}")
    }

    public void saveBoletoInfoApproval(Long customerId, String observations) {
        save(Customer.get(customerId), "Alteração dos Dados do Boleto Aprovada.\nObservações: ${observations}")
    }

    public void saveBoletoInfoDenial(Long customerId, String observations, String denialReason) {
        save(Customer.get(customerId), "Alteração dos Dados do Boleto Negada.\nMotivo da Recusa: ${denialReason}\nObservações: ${observations}")
    }

    public void saveCustomerRegisterStatusGeneralApproval(Customer customer, GeneralApprovalStatus status, String observations) {
        save(customer, getDescriptionStatusGeneralApproval(customer, status, observations))
    }

    public void saveCustomerBoletoBankChange(Long customerId, Bank bank, Boolean isRegistered) {
        Customer customer = Customer.get(customerId)

        if (bank) {
            save(customer, "Banco de emissão de boleto do cliente alterado para ${bank.name} ${isRegistered ? '(registrado)' : ''}")
        } else {
            save(customer, "Banco de emissão de boleto do cliente removido")
        }
    }

    public void saveCustomerDisableInfo(Customer customer) {
        save(customer, "Cliente desabilitado.")
    }

    public void saveUpdateCustomerDailyCheckoutLimit(Customer customer, BigDecimal oldDailyCheckoutLimit, BigDecimal newDailyCheckoutLimit, String observations) {
        String description = "Limite de saque diário alterado de ${FormUtils.formatCurrencyWithMonetarySymbol(oldDailyCheckoutLimit)} para ${FormUtils.formatCurrencyWithMonetarySymbol(newDailyCheckoutLimit)}"

        if (observations) description += " (${observations})"

        save(customer, "${description}.", UserUtils.getCurrentUser())
    }

    public void saveUpdateAnticipationLimit(Customer customer, BigDecimal anticipationLimitBefore, BigDecimal anticipationLimitAfter) {
        save(customer, "Limite de antecipação alterado de ${FormUtils.formatCurrencyWithMonetarySymbol(anticipationLimitBefore)} para ${FormUtils.formatCurrencyWithMonetarySymbol(anticipationLimitAfter)}.")
    }

    public void saveUpdateMaxPaymentValue(Customer customer, BigDecimal maxPaymentValueBefore, BigDecimal maxPaymentValueAfter) {
        save(customer, "Valor máximo para emissão de cobranças alterado de ${FormUtils.formatCurrencyWithMonetarySymbol(maxPaymentValueBefore)} para ${FormUtils.formatCurrencyWithMonetarySymbol(maxPaymentValueAfter)}.")
    }

    public void saveToggleTransferValidationBypass(Customer customer, Boolean allow) {
        String actionText = allow ? "Habilitada" : "Desabilitada"
        save(customer, "${actionText} transferências para ${grailsApplication.config.asaas.credittransferrequest.defaultEstimatedDaysForConfirmation} dias.")
    }

    public void saveToggleMultiplesBankAccounts(Customer customer, Boolean enabled) {
        String actionText = enabled ? "Habilitada" : "Desabilitada"
        save(customer, "${actionText} múltiplas contas bancárias.")
    }

    public void saveToggleBillPayment(Customer customer, Boolean enabled) {
        String actionText = enabled ? "Habilitada" : "Desabilitada"
        save(customer, "${actionText} pagamento de contas.")
    }

    public void saveToggleHandleBillingInfo(Customer customer, Boolean enabled, Boolean fromAccountOwner) {
        String actionText = enabled ? "Habilitada" : "Desabilitada"

        if (!fromAccountOwner) {
            save(customer, "${actionText} tokenização de cartão de crédito.")
        } else {
            save(customer, "${actionText} tokenização de cartão de crédito (via conta pai).")
        }
    }

    public void saveToggleWhatsAppNotification(Customer customer, Boolean enabled) {
        String actionText = enabled ? "Habilitada" : "Desabilitada"
        save(customer, "${actionText} notificação via WhatsApp.")
    }

    public void saveUpdateSoftDescriptor(Customer customer, Boolean enabled, Boolean fromAccountOwner) {
        String actionText = enabled ? "Habilitado" : "Desabilitado"

        if (!fromAccountOwner) {
            save(customer, "Alteração da manipulação do softdescriptor: ${actionText}.")
        } else {
            save(customer, "Alteração da manipulação do softdescriptor (via conta pai): ${actionText}.")
        }
    }

    public void saveSoftDescriptorText(Customer customer, String softDescriptorText) {
        save(customer, "Alteração no softdescriptor: ${softDescriptorText}.")
    }

    public void saveToggleAsaasCardElo(Customer customer, Boolean enabled) {
        String actionText = enabled ? "Habilitada" : "Desabilitada"
        save(customer, "${actionText} cartão elo.")
    }

    public String getDescriptionStatusGeneralApproval(Customer customer, GeneralApprovalStatus status, String observations) {
        String description = "Aprovação geral do cadastro: ${Utils.getMessageProperty('customerRegisterStatus.' + status + '.label')}"

        if (observations) {
            description += "<br> Observações: " + observations
        }

        return description
    }

    public void saveUpdateBankSlipFee(Customer customer, Map valuesChanged, Boolean fromAccountOwner) {
        StringBuilder bankSlipFeeUpdateHistory = new StringBuilder()

        if (valuesChanged.containsKey("defaultValue")) {
            bankSlipFeeUpdateHistory.append("\nTaxa padrão: ")
            bankSlipFeeUpdateHistory.append(valuesChanged.defaultValue ? FormUtils.formatCurrencyWithMonetarySymbol(valuesChanged.defaultValue) : "-")
        }

        if (valuesChanged.containsKey("discountValue")) {
            bankSlipFeeUpdateHistory.append("\nValor promocional: ")
            bankSlipFeeUpdateHistory.append(valuesChanged.discountValue ? FormUtils.formatCurrencyWithMonetarySymbol(valuesChanged.discountValue) : "-")
        }

        if (valuesChanged.containsKey("discountExpiration")) {
            bankSlipFeeUpdateHistory.append("\nDesconto válido até: ")
            bankSlipFeeUpdateHistory.append(valuesChanged.discountExpiration ? CustomDateUtils.fromDate(valuesChanged.discountExpiration) : "-")
        }

        if (fromAccountOwner) {
            save(customer, "Alteração da taxa de boleto (via conta pai):\n ${bankSlipFeeUpdateHistory}")
        } else {
            save(customer, "Alteração da taxa de boleto:\n ${bankSlipFeeUpdateHistory}")
        }
    }

    public void saveUpdateInternalLoanConfig(Customer customer, Boolean enabled) {
        String valueDescription = enabled ? "Habilitado" : "Desabilitado"

        save(customer, "Alteração da configuração do mecanismo avalista-devedor (via conta pai): ${valueDescription}")
    }

    public void saveUpdatePixCreditFee(Customer customer, Map valuesChanged, Boolean fromAccountOwner) {
        StringBuilder pixCreditFeeUpdateHistory = new StringBuilder()

        if (valuesChanged.containsKey("fixedFee")) {
            pixCreditFeeUpdateHistory.append("\nTaxa fixa padrão: ")
            pixCreditFeeUpdateHistory.append(valuesChanged.fixedFee ? FormUtils.formatCurrencyWithMonetarySymbol(valuesChanged.fixedFee) : "-")
        }

        if (valuesChanged.containsKey("fixedFeeWithDiscount")) {
            pixCreditFeeUpdateHistory.append("\nTaxa promocional: ")
            pixCreditFeeUpdateHistory.append(valuesChanged.fixedFeeWithDiscount ? FormUtils.formatCurrencyWithMonetarySymbol(valuesChanged.fixedFeeWithDiscount) : "-")
        }

        if (valuesChanged.containsKey("discountExpiration")) {
            pixCreditFeeUpdateHistory.append("\nDesconto válido até: ")
            pixCreditFeeUpdateHistory.append(valuesChanged.discountExpiration ? CustomDateUtils.fromDate(valuesChanged.discountExpiration) : "-")
        }

        if (valuesChanged.containsKey("percentageFee")) {
            pixCreditFeeUpdateHistory.append("\nTaxa percentual padrão: ")
            pixCreditFeeUpdateHistory.append(valuesChanged.percentageFee ? "${FormUtils.formatWithPercentageSymbol(valuesChanged.percentageFee, 4)}" : "-")
        }

        if (valuesChanged.containsKey("minimumFee")) {
            pixCreditFeeUpdateHistory.append("\nTaxa mínima: ")
            pixCreditFeeUpdateHistory.append(valuesChanged.minimumFee ? FormUtils.formatCurrencyWithMonetarySymbol(valuesChanged.minimumFee) : "-")
        }

        if (valuesChanged.containsKey("maximumFee")) {
            pixCreditFeeUpdateHistory.append("\nTaxa máxima: ")
            pixCreditFeeUpdateHistory.append(valuesChanged.maximumFee ? FormUtils.formatCurrencyWithMonetarySymbol(valuesChanged.maximumFee) : "-")
        }

        String customerInteractionDescription = "Alteração da taxa de crédito de Pix"
        if (fromAccountOwner) customerInteractionDescription += " (via conta pai)"
        customerInteractionDescription += ": ${pixCreditFeeUpdateHistory.toString()}"

        save(customer, customerInteractionDescription)
    }

    public void saveUpdateCreditCardFee(Customer customer, Map newCreditCardFeeConfig, Boolean fromAccountOwner) {
        StringBuilder creditCardFeeUpdateHistory = new StringBuilder()
        creditCardFeeUpdateHistory.append("Valor fixo: ")
        creditCardFeeUpdateHistory.append(FormUtils.formatCurrencyWithMonetarySymbol(newCreditCardFeeConfig.fixedFee))

        if (newCreditCardFeeConfig.discountExpiration) {
            creditCardFeeUpdateHistory.append("\n\nTaxas promocionais para: ")
            creditCardFeeUpdateHistory.append("\nCobranças à vista: ")
            creditCardFeeUpdateHistory.append(FormUtils.formatWithPercentageSymbol(newCreditCardFeeConfig.discountUpfrontFee))
            creditCardFeeUpdateHistory.append("\n2x a 6x: ")
            creditCardFeeUpdateHistory.append(FormUtils.formatWithPercentageSymbol(newCreditCardFeeConfig.discountUpToSixInstallmentsFee))
            creditCardFeeUpdateHistory.append("\n7x a 12x: ")
            creditCardFeeUpdateHistory.append(FormUtils.formatWithPercentageSymbol(newCreditCardFeeConfig.discountUpToTwelveInstallmentsFee))
            creditCardFeeUpdateHistory.append("\n\n O desconto é válido até: ${CustomDateUtils.fromDate(newCreditCardFeeConfig.discountExpiration)}, após isso considerar: ")
        }

        creditCardFeeUpdateHistory.append("\nCobranças à vista: ")
        creditCardFeeUpdateHistory.append(FormUtils.formatWithPercentageSymbol(newCreditCardFeeConfig.upfrontFee))
        creditCardFeeUpdateHistory.append("\n2x a 6x: ")
        creditCardFeeUpdateHistory.append(FormUtils.formatWithPercentageSymbol(newCreditCardFeeConfig.upToSixInstallmentsFee))
        creditCardFeeUpdateHistory.append("\n7x a 12x: ")
        creditCardFeeUpdateHistory.append(FormUtils.formatWithPercentageSymbol(newCreditCardFeeConfig.upToTwelveInstallmentsFee))

        if (!newCreditCardFeeConfig.discountExpiration) {
            creditCardFeeUpdateHistory.append("\n\nSem taxa promocional aplicada. ")
        }

        if (fromAccountOwner) {
            save(customer, "Alteração da taxa de cartão de crédito (via conta pai):\n ${creditCardFeeUpdateHistory}")
        } else {
            save(customer, "Alteração da taxa de cartão de crédito:\n ${creditCardFeeUpdateHistory}")
        }
    }

    public void saveChargebackReceived(Customer customer, String paymentInvoiceNumber, BigDecimal chargebackValue) {
        save(customer, "Recebido chargeback da cobrança nr. ${paymentInvoiceNumber} - Valor: ${FormUtils.formatCurrencyWithMonetarySymbol(chargebackValue)}")
    }

    public void saveScheduledManuallyCall(OutgoingPhoneCallAdapter outgoingPhoneCallAdapter, Customer customer) {
        String subject = PhoneCallType.MANUALLY_SCHEDULED.getLabel() + " " + outgoingPhoneCallAdapter.subject.getLabel()

        StringBuilder descriptionStringBuilder = new StringBuilder()
        descriptionStringBuilder.append("Realizado agendamento manual de ligação\n")
        descriptionStringBuilder.append("Cliente: ${outgoingPhoneCallAdapter.contactAdapter.contactName}\n")
        descriptionStringBuilder.append("Assunto: ${subject}\n")
        descriptionStringBuilder.append("Próxima ligação: ${CustomDateUtils.fromDateWithTime(outgoingPhoneCallAdapter.nextAttempt)}\n")
        descriptionStringBuilder.append("Motivo: ${outgoingPhoneCallAdapter.reason}\n")

        if (outgoingPhoneCallAdapter.asaasAccountManagerId) {
            String accountManagerName = AccountManager.query([column: "name", id: outgoingPhoneCallAdapter.asaasAccountManagerId]).get()
            descriptionStringBuilder.append("Gerente: ${accountManagerName}\n")
        }

        save(customer, descriptionStringBuilder.toString())
    }

    public void saveCustomerFeeIfNecessary(Customer customer, Map customerFeeConfig) {
        String description = ""
        if (customerFeeConfig.containsKey("invoiceValue")) {
            description += "\nAlteração da taxa de nota fiscal - Valor fixo: ${ FormUtils.formatCurrencyWithMonetarySymbol(customerFeeConfig.invoiceValue)}"
        }

        if (customerFeeConfig.containsKey("transferValue")) {
            description += "\nAlteração da taxa de transferência - Valor fixo: ${ FormUtils.formatCurrencyWithMonetarySymbol(customerFeeConfig.transferValue)}"
        }

        if (customerFeeConfig.containsKey("paymentMessagingNotificationFeeValue")) {
            description += "\nAlteração da taxa de mensageria - Valor fixo: ${ FormUtils.formatCurrencyWithMonetarySymbol(customerFeeConfig.paymentMessagingNotificationFeeValue)}"
        }

        if (description) save(customer, description)
    }

    public void savePromotionalCodeGenerated(Customer customer, String code, String reason, PromotionalCodeType type, Double value, Integer freePaymentAmount) {
        String description = "Gerado o código promocional ${code}"

        if (type.isFreePayment()) {
            description += " com ${freePaymentAmount} cobranças grátis."
        } else {
            description += " no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(value)}."
        }

        description += " \nMotivo: ${reason}"

        save(customer, description)
    }

    public void saveClearSaleToggled(Long customerId, Boolean disable, Boolean fromAccountOwner) {
        Customer customer = Customer.get(customerId)
        String description =  "${disable ? "Desabilitado" : "Habilitado"} validador antifraude"

        if (fromAccountOwner) description += " (via conta pai)"
        save(customer, description)
    }

    public void saveCustomerRegisterStatusEditUpdatedManually(Customer customer) {
        save(customer, "Situação da análise cadastral alterada manualmente.")
    }

    public void saveAuthorizationDeviceUpdateRequest(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        String status = Utils.getMessageProperty("AuthorizationDeviceUpdateRequestStatus.${authorizationDeviceUpdateRequest.status}")
        String description = "Alteração de dispositivo de autorização: ${status}"

        if (authorizationDeviceUpdateRequest.status.isRejected()) {
            String rejectReason = Utils.getMessageProperty("AuthorizationDeviceUpdateRequestRejectReason.analyst.${authorizationDeviceUpdateRequest.rejectReason}")
            description += "\nMotivo de reprovação: ${rejectReason}"
        }

        save(authorizationDeviceUpdateRequest.customer, description)
    }

    public void saveCardTransactionBlockInteraction(Customer customer, String reason, Boolean blocked, Date blockDate) {
        String description = "Bloqueio temporário nas transações de cartão: " + "${blocked ? "Habilitado" : "Desabilitado"}"
        description += "\nMotivo: ${reason}"

        if (blockDate) {
            description += "\nBloqueio até: ${CustomDateUtils.formatDateTime(blockDate)}"
        }

        save(customer, description)
    }

    public void setCustomerInteractionFee(Customer customer, Map feeConfig, Boolean fromAccountOwner) {
        if (feeConfig.invoiceValue != null) saveUpdateServiceInvoiceFee(customer, feeConfig.invoiceValue, fromAccountOwner)

        if (feeConfig.productInvoiceValue != null) saveUpdateProductInvoiceFee(customer, feeConfig.productInvoiceValue, fromAccountOwner)

        if (feeConfig.consumerInvoiceValue != null) saveUpdateConsumerInvoiceFee(customer, feeConfig.consumerInvoiceValue, fromAccountOwner)

        if (feeConfig.transferValue != null) saveUpdateTransferFee(customer, feeConfig.transferValue, fromAccountOwner)

        if (feeConfig.dunningCreditBureauFeeValue != null) saveUpdateDunningCreditBureauFee(customer, feeConfig, fromAccountOwner)

        if (feeConfig.pixDebitFee != null) saveUpdatePixDebitFee(customer, feeConfig.pixDebitFee, fromAccountOwner)

        if (feeConfig.creditBureauReportLegalPersonFee != null) saveUpdateCreditBureauReportLegalPersonFee(customer, feeConfig.creditBureauReportLegalPersonFee, fromAccountOwner)

        if (feeConfig.creditBureauReportNaturalPersonFee != null) saveUpdateCreditBureauReportNaturalPersonFee(customer, feeConfig.creditBureauReportNaturalPersonFee, fromAccountOwner)

        if (feeConfig.paymentMessagingNotificationFeeValue != null) saveUpdatePaymentMessagingNotificationFee(customer, feeConfig.paymentMessagingNotificationFeeValue, fromAccountOwner)

        if (feeConfig.childAccountKnownYourCustomerFee != null) saveUpdateChildAccountKnownYourCustomerFee(customer, feeConfig.childAccountKnownYourCustomerFee, fromAccountOwner)

        if (feeConfig.phoneCallNotificationFee != null) saveUpdatePhoneCallNotificationFee(customer, feeConfig.phoneCallNotificationFee, fromAccountOwner)
    }

    public void saveCustomerTransferConfigInteraction(Customer customer, Integer monthlyQuantityPixWithoutFee, Boolean mustConsiderTedInMonthlyQuantityPixWithoutFee, Boolean fromAccountOwner) {
        String description = "Alteração de quantidade de transferências gratuitas"
        if (fromAccountOwner) description += " (via conta pai)"

        if (monthlyQuantityPixWithoutFee != null) description += "\nQuantidade de Pix gratuitos: ${monthlyQuantityPixWithoutFee}"
        if (mustConsiderTedInMonthlyQuantityPixWithoutFee != null) description += "\nCompartilhamento de transferências gratuitas entre Pix e TED: ${(mustConsiderTedInMonthlyQuantityPixWithoutFee) ? "Ativado" : "Desativado"}"

        save(customer, description)
    }

    public void saveCriticalActionEnableEvent(Long customerId, CriticalActionType criticalActionType) {
        String description = "Ação crítica habilitada: ${Utils.getMessageProperty('criticalAction.title.' + criticalActionType)}"
        saveCriticalActionEvent(customerId, description)
    }

    public void saveCriticalActionDisabledEvent(Long customerId, CriticalActionType criticalActionType) {
        String description = "Ação crítica desabilitada: ${Utils.getMessageProperty('criticalAction.title.' + criticalActionType)}"
        saveCriticalActionEvent(customerId, description)
    }

    public void saveCriticalActionCancelEvent(Long customerId, CriticalActionType criticalActionType) {
        String description = "Ação crítica cancelada: ${Utils.getMessageProperty('criticalAction.title.' + criticalActionType)}"
        saveCriticalActionEvent(customerId, description)
    }

    public void saveCriticalActionAuthorizationEvent(Long customerId, CriticalActionType criticalActionType) {
        String description = "Ação crítica: ${Utils.getMessageProperty('criticalAction.title.' + criticalActionType)}"
        saveCriticalActionEvent(customerId, description)
    }

    public void saveCriticalActionVerificationEvent(Long customerId, CriticalActionType criticalActionType) {
        String description = "Ação crítica verificada: ${Utils.getMessageProperty('criticalAction.title.' + criticalActionType)}"
        saveCriticalActionEvent(customerId, description)
    }

    public void updateOperationNature(Customer customer, String operationNature) {
        save(customer, "Alteração da natureza da operação para emissão de notas fiscais - valor: ${operationNature}")
    }

    public void saveCustomerParameterChange(Customer customer, CustomerParameterName customerParameterName, String value, String previousValue) {
        String description = "Opção ${customerParameterName.getLabel()}: ${value}."

        if (customerParameterName.shouldSavePreviousValueInCustomerInteraction()) {
            description += " (Valor anterior: ${previousValue})"
        }

        save(customer, description)
    }

    public void saveManualResetDeviceInteraction(Customer customer, String phoneNumber) {
        String description = "Realizada redefinição manual do número para recebimento do Token SMS para: ${PhoneNumberUtils.formatPhoneNumberWithMask(phoneNumber)}"
        save(customer, description)
    }

    public void saveSecurityEvent(Long customerId, String description) {
        Map params = [:]
        params.customerId = customerId
        params.description = "Evento de segurança: ${description}"
        params.type = CustomerInteractionType.SECURITY

        save(params)
    }

    public void saveDeletePendingOrOverduePayment(Customer customer) {
        final String description = "Utilizado opção de remover cobranças. Removido cobranças pendentes ou vencidas do cliente."
        save(customer, description)
    }

    public void saveFacematchLoginLockRequest(Customer customer) {
        final String description = "Solicitado desbloqueio de login por facematch."
        save(customer, description)
    }

    public void saveCustomerCompulsoryUpdate(Customer customer, String detail) {
        String description = "Foi realizada uma atualização compulsória de dados do cliente originada de uma análise de atualização cadastral"
        description += "\n ${detail}"

        save(customer, description)
    }

    private void saveCustomerFiscalDataMigrationRequest(Customer customer, Long fromCustomerFiscalInfoId, Long toCustomerFiscalInfoId) {
        String description = "Migração de dados fiscais solicita pelo cliente: ${customer.id}\n"
        description += "Migração dos dados do CustomerFiscalInfo ${fromCustomerFiscalInfoId} realizada para o novo CustomerFiscalInfo ${toCustomerFiscalInfoId} e também os respectivos CustomerFiscalConfig\n"

        save(customer, description)
    }

    private void saveCriticalActionEvent(Long customerId, String description) {
        Map params = [:]
        params.customerId = customerId
        params.description = description
        params.type = CustomerInteractionType.CRITICAL_ACTION

        save(params)
    }

    private void saveUpdateCreditBureauReportLegalPersonFee(Customer customer, BigDecimal creditBureauReportLegalPersonFee, Boolean fromAccountOwner) {
        BigDecimal oldCreditBureauReportLegalPersonFee = CustomerFee.calculateCreditBureauReport(customer, PersonType.JURIDICA)

        Boolean noChanges = oldCreditBureauReportLegalPersonFee == creditBureauReportLegalPersonFee
        if (noChanges) return

        String description = "Alteração da taxa de consulta Serasa"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "Pessoa jurídica de ${FormUtils.formatCurrencyWithMonetarySymbol(oldCreditBureauReportLegalPersonFee)} para ${FormUtils.formatCurrencyWithMonetarySymbol(creditBureauReportLegalPersonFee)}"

        save(customer, description)
    }

    private void saveUpdateCreditBureauReportNaturalPersonFee(Customer customer, BigDecimal creditBureauReportNaturalPersonFee, Boolean fromAccountOwner) {
        BigDecimal oldCreditBureauReportNaturalPersonFee = CustomerFee.calculateCreditBureauReport(customer, PersonType.FISICA)

        Boolean noChanges = oldCreditBureauReportNaturalPersonFee == creditBureauReportNaturalPersonFee
        if (noChanges) return

        String description = "Alteração da taxa de consulta Serasa"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "Pessoa física de ${FormUtils.formatCurrencyWithMonetarySymbol(oldCreditBureauReportNaturalPersonFee)} para ${FormUtils.formatCurrencyWithMonetarySymbol(creditBureauReportNaturalPersonFee)}"

        save(customer, description)
    }

    private void saveUpdateDunningCreditBureauFee(Customer customer, Map feeConfig, Boolean fromAccountOwner) {
        BigDecimal oldDunningCreditBureauFee = CustomerFee.getDunningCreditBureauFeeValue(customer.id)

        Boolean noChanges = (oldDunningCreditBureauFee == feeConfig.dunningCreditBureauFeeValue)
        if (noChanges) return

        String description = "Alteração da taxa de negativação Serasa"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "De ${FormUtils.formatCurrencyWithMonetarySymbol(oldDunningCreditBureauFee)} para ${FormUtils.formatCurrencyWithMonetarySymbol(feeConfig.dunningCreditBureauFeeValue)}\n"

        save(customer, description)
    }

    private void saveUpdateServiceInvoiceFee(Customer customer, BigDecimal serviceInvoiceFeeValue, Boolean fromAccountOwner) {
        BigDecimal oldServiceInvoiceFeeValue = CustomerFee.calculateServiceInvoiceFee(customer)

        Boolean noChanges = (oldServiceInvoiceFeeValue == serviceInvoiceFeeValue)
        if (noChanges) return

        String description = "Alteração da taxa de nota fiscal de Serviço"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "De ${FormUtils.formatCurrencyWithMonetarySymbol(oldServiceInvoiceFeeValue)} para ${FormUtils.formatCurrencyWithMonetarySymbol(serviceInvoiceFeeValue)}\n"

        save(customer, description)
    }

    private void saveUpdateProductInvoiceFee(Customer customer, BigDecimal productInvoiceFeeValue, Boolean fromAccountOwner) {
        BigDecimal oldProductInvoiceFeeValue = CustomerFee.calculateProductInvoiceFee(customer)

        Boolean noChanges = (oldProductInvoiceFeeValue == productInvoiceFeeValue)
        if (noChanges) return

        String description = "Alteração da taxa de nota fiscal de produto"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "De ${FormUtils.formatCurrencyWithMonetarySymbol(oldProductInvoiceFeeValue)} para ${FormUtils.formatCurrencyWithMonetarySymbol(productInvoiceFeeValue)}\n"

        save(customer, description)
    }

    private void saveUpdateConsumerInvoiceFee(Customer customer, BigDecimal consumerInvoiceFeeValue, Boolean fromAccountOwner) {
        BigDecimal oldConsumerInvoiceFeeValue = CustomerFee.calculateConsumerInvoiceFee(customer)

        Boolean noChanges = (oldConsumerInvoiceFeeValue == consumerInvoiceFeeValue)
        if (noChanges) return

        String description = "Alteração da taxa de nota fiscal de consumidor"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "De ${FormUtils.formatCurrencyWithMonetarySymbol(oldConsumerInvoiceFeeValue)} para ${FormUtils.formatCurrencyWithMonetarySymbol(consumerInvoiceFeeValue)}\n"

        save(customer, description)
    }

    private void saveUpdatePaymentMessagingNotificationFee(Customer customer, BigDecimal paymentMessagingNotificationFeeValue, Boolean fromAccountOwner) {
        BigDecimal oldPaymentMessagingNotificationFeeValue = CustomerFee.calculateMessagingNotificationFee(customer)

        Boolean noChanges = (oldPaymentMessagingNotificationFeeValue == paymentMessagingNotificationFeeValue)
        if (noChanges) return

        String description = "Alteração da taxa de mensageria"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "De ${FormUtils.formatCurrencyWithMonetarySymbol(oldPaymentMessagingNotificationFeeValue)} para ${FormUtils.formatCurrencyWithMonetarySymbol(paymentMessagingNotificationFeeValue)}"

        save(customer, description)
    }

    private void saveUpdatePixDebitFee(Customer customer, BigDecimal pixDebitFeeValue, Boolean fromAccountOwner) {
        BigDecimal oldPixDebitFee = CustomerFee.calculatePixDebitFee(customer)

        Boolean noChanges = oldPixDebitFee == pixDebitFeeValue
        if (noChanges) return

        String description = "Alteração das taxas Pix"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"
        description += "Débito de ${FormUtils.formatCurrencyWithMonetarySymbol(oldPixDebitFee)} para ${FormUtils.formatCurrencyWithMonetarySymbol(pixDebitFeeValue)}"

        save(customer, description)
    }

    private void saveUpdateTransferFee(Customer customer, BigDecimal transferValue, Boolean fromAccountOwner) {
        BigDecimal oldTransferFeeValue = CustomerFee.calculateTransferFeeValue(customer)

        Boolean noChanges = (oldTransferFeeValue == transferValue)
        if (noChanges) return

        String description = "Alteração da taxa de transferência"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "De ${FormUtils.formatCurrencyWithMonetarySymbol(oldTransferFeeValue)} para ${FormUtils.formatCurrencyWithMonetarySymbol(transferValue)}\n"

        save(customer, description)
    }

    private void saveUpdateChildAccountKnownYourCustomerFee(Customer customer, BigDecimal childAccountKnownYourCustomerFee, Boolean fromAccountOwner) {
        BigDecimal oldChildAccountKnownYourCustomerFee = CustomerFee.calculateChildAccountKnownYourCustomerFeeValue(customer)

        Boolean noChanges = (oldChildAccountKnownYourCustomerFee == childAccountKnownYourCustomerFee)
        if (noChanges) return

        String description = "Alteração da taxa de criação de subconta"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "De ${FormUtils.formatCurrencyWithMonetarySymbol(oldChildAccountKnownYourCustomerFee)} para ${FormUtils.formatCurrencyWithMonetarySymbol(childAccountKnownYourCustomerFee)}"

        save(customer, description)
    }

    private void saveUpdatePhoneCallNotificationFee(Customer customer, BigDecimal phoneCallNotificationFee, Boolean fromAccountOwner) {
        BigDecimal oldPhoneCallNotificationFee = CustomerFee.calculatePhoneCallNotificationFee(customer)

        Boolean noChanges = (oldPhoneCallNotificationFee == phoneCallNotificationFee)
        if (noChanges) return

        String description = "Alteração da taxa de notificação por robô de voz"
        if (fromAccountOwner) description += " (via conta pai)"
        description = "${description}:\n"

        description += "De ${ FormUtils.formatCurrencyWithMonetarySymbol(oldPhoneCallNotificationFee) } para ${ FormUtils.formatCurrencyWithMonetarySymbol(phoneCallNotificationFee) }"

        save(customer, description)
    }

    private void trackAttendantMetricIfNecessary(CustomerInteraction customerInteraction) {
        if (!customerInteraction.type.isTreasureDataEventTraceable()) return

        TreasureDataEventType treasureDataEventType = TreasureDataEventType.parseCustomerInteractionType(customerInteraction.type)

        if (!treasureDataEventType) return

        treasureDataService.track(customerInteraction.attendant, customerInteraction.customer, treasureDataEventType, [customerInteractionId: customerInteraction.id])
    }

    private CustomerInteraction validateSaveManualInteraction(Map params) {
        CustomerInteraction validatedCustomerInteraction = new CustomerInteraction()

        if (!params.description.trim()) {
            DomainUtils.addError(validatedCustomerInteraction, "É necessário informar uma descrição.")
        }

        if (params.description.length() > CustomerInteraction.constraints.description.maxSize) {
            DomainUtils.addError(validatedCustomerInteraction, Utils.getMessageProperty("description.maxSize.exceeded", [CustomerInteraction.constraints.description.maxSize]))
        }

        return validatedCustomerInteraction
    }

    private Map parseParams(Map params) {
        Map parsedParams = [:]

        parsedParams.description = Utils.removeNonAscii(params.description, "")
        parsedParams.description = parsedParams.description.replace("\n", "<br/>")

        parsedParams.type = params.type ? CustomerInteractionType.convert(params.type) : CustomerInteractionType.NONE
        parsedParams.customerId = Long.valueOf(params.customerId)

        return parsedParams
    }
}

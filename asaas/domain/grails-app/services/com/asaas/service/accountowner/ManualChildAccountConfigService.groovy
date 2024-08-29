package com.asaas.service.accountowner

import com.asaas.customer.CustomerCreditCardAbusePreventionParameterName
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.PersonType
import com.asaas.customerexternalcommunicationconfig.CustomerColorPalette
import com.asaas.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfigChangeOrigin
import com.asaas.customerreceivableanticipationconfig.adapter.ToggleBlockBillingTypeAdapter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.economicactivity.EconomicActivity
import com.asaas.environment.AsaasEnvironment
import com.asaas.notification.NotificationMessageType
import com.asaas.status.Status
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ManualChildAccountConfigService {

    def apiConfigService
    def childAccountParameterBinderService
    def creditCardFeeNegotiatedForBrandConfigService
    def customerAdminService
    def customerBoletoConfigService
    def customerBusinessActivityService
    def customerConfigService
    def customerCreditCardAbusePreventionParameterService
    def customerDocumentGroupProxyService
    def customerInvoiceConfigAdminService
    def customerNotificationConfigService
    def customerParameterService
    def customerProofOfLifeService
    def customerReceivableAnticipationConfigService
    def customerRegisterStatusService
    def economicActivityService
    def grailsApplication

    public void setAccountOwnerConfigs(Long accountOwnerId, Long customerId, Map options) {
        Customer accountOwner = Customer.get(accountOwnerId)
        Customer customer = Customer.get(customerId)

        childAccountParameterBinderService.applyAllParameters(accountOwner, customer)

        // PERFECT PAY TECNOLOGIA, SERVICOS E INTERMEDIACAO LTDA
        if (accountOwner.id == 782867) {
            customerNotificationConfigService.saveCustomerSmsFrom(customer, '')
            customerCreditCardAbusePreventionParameterService.save(customer, CustomerCreditCardAbusePreventionParameterName.APPROVAL_RATE_PERIOD, 0)
        }

        // SEVEN TREINAMENTOS EM MARKETING DIGITAL LTDA
        if (accountOwner.id == 1138213) {
            customerCreditCardAbusePreventionParameterService.save(customer, CustomerCreditCardAbusePreventionParameterName.BYPASS_DISTINCT_API_IP_COUNT, true)
            customerReceivableAnticipationConfigService.toggleBlockBankSlipAnticipation(ToggleBlockBillingTypeAdapter.createForBlockingByOwnerAccount(customer))
            customerReceivableAnticipationConfigService.toggleBlockCreditCardAnticipation(ToggleBlockBillingTypeAdapter.createForBlockingByOwnerAccount(customer))
        }

        // Solutto (WebSoftware Ltda) e Solutto (Access Pro Solucoes Rapidas em Informatica Ltda)
        if (accountOwner.id == 71031 || accountOwner.id == 71577) {
            allowTransferValidationBypass(customer)
        }

        // Prisma Box
        if (accountOwner.id == 112870) {
            allowTransferValidationBypass(customer)
        }

        // Umov.me
        if (accountOwner.id == 122353 || (accountOwner.id == 666 && AsaasEnvironment.isSandbox())) {
            allowTransferValidationBypass(customer)
        }

        // Doutor.es
        if (accountOwner.id == 138624) {
            allowTransferValidationBypass(customer)

            Map customerBoletoConfig = [:]
            customerBoletoConfig.hideContactInfo = true
            customerBoletoConfig.hideInvoiceUrl = true
            customerBoletoConfigService.save(customer, customerBoletoConfig)
        }

        // 001SHOP TECNOLOGIA EM ECOMMERCE LTDA
        if (accountOwner.id == 1350974) {
            Map customerBoletoConfig = [:]
            customerBoletoConfig.hideContactInfo = true
            customerBoletoConfig.showCustomerInfo = false
            customerBoletoConfig.hideInvoiceUrl = true
            customerBoletoConfigService.save(customer, customerBoletoConfig)
        }

        // HOPY TECNOLOGIA LTDA
        if (accountOwner.id == 1351667) {
            Map customerBoletoConfig = [:]
            customerBoletoConfig.hideContactInfo = true
            customerBoletoConfig.showCustomerInfo = false
            customerBoletoConfig.hideInvoiceUrl = true
            customerBoletoConfigService.save(customer, customerBoletoConfig)
        }

        // FNL Comunicação e Desenvolvimento de Sistemas
        if (accountOwner.id == 49870) {
            allowTransferValidationBypass(customer)
        }

        // MOBLEE TECNOLOGIA DA INFORMACAO S.A.
        if (accountOwner.id == 212492 || (accountOwner.id == 1129 && AsaasEnvironment.isSandbox())) {
            allowTransferValidationBypass(customer)
        }

        // RealDrive Simuladores
        if (accountOwner.id == 235969 || accountOwner.id == 280649 || (accountOwner.id == 1251 && AsaasEnvironment.isSandbox())) {
            allowTransferValidationBypass(customer)
        }

        // DESTRA TECNOLOGIA LTDA
        if (accountOwner.id == 17661) {
            customerDocumentGroupProxyService.saveCustomDocument(customer, "Procuração", "Olá! Por gentileza realizar o envio da Procuração Ad Judicia Et Extra")
        }

        // Mary help
        if (accountOwner.id == 398595 || (accountOwner.id in [2208L, 1923L] && AsaasEnvironment.isSandbox())) {
            if (customer.personType == PersonType.FISICA) {
                customerBusinessActivityService.save(customer, grailsApplication.config.asaas.customer.businessActivity.cleaningActivityIdentifier, '')
            } else {
                List<Map> economicActivities = []
                economicActivities.add([economicActivity: EconomicActivity.get(grailsApplication.config.asaas.customer.economicActivity.cleaningActivityIdentifier), isMainActivity: true])
                economicActivityService.associateAllCustomer(customer, economicActivities)
            }

            CustomerReceivableAnticipationConfig customerAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)
            CustomerReceivableAnticipationConfig accountOwnerAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(accountOwner.id)

            customerAnticipationConfig.creditCardDetachedDailyFee = accountOwnerAnticipationConfig?.creditCardDetachedDailyFee
            customerAnticipationConfig.creditCardInstallmentDailyFee = accountOwnerAnticipationConfig?.creditCardInstallmentDailyFee
            customerAnticipationConfig.save(failOnError: true)

            customerReceivableAnticipationConfigService.saveHistory(customerAnticipationConfig, CustomerReceivableAnticipationConfigChangeOrigin.CHILD_ACCOUNT_PARAMETER)

            List customerInfoList = ["NAME", "CITY"]
            customerInvoiceConfigAdminService.save(customer, customerInfoList, CustomerColorPalette.WHITE.primaryColor)

            Map customerBoletoConfig = [:]
            customerBoletoConfig.showCustomerInfo = false
            customerBoletoConfigService.save(customer, customerBoletoConfig)
        }

        // VALLE SOLUCOES EM SISTEMA LTDA.
        if (accountOwner.id == 485201) {
            if (options.containsKey("enableWhiteLabel")) {
                customerParameterService.save(customer, CustomerParameterName.WHITE_LABEL, options.enableWhiteLabel)
            }
        }

        //SISTEMAS JUSTUS LTDA
        if (accountOwner.id == 580957) {
            customerConfigService.changeNotificationMessageType(customer, NotificationMessageType.DONATION)
        }

        // Mercos
        if (accountOwner.id == 979078) {
            customerCreditCardAbusePreventionParameterService.save(customer, CustomerCreditCardAbusePreventionParameterName.BYPASS_DISTINCT_API_IP_COUNT, true)
        }

        // MAGAMOBI
        if (accountOwner.id == 1039457) {
            customerCreditCardAbusePreventionParameterService.save(customer, CustomerCreditCardAbusePreventionParameterName.BYPASS_ATTEMPTS_DENIED_EXCEEDED, true)
            customerProofOfLifeService.updateToSelfieIfPossible(customer)
        }

        // NextFit
        if (accountOwner.id == 1078157) {
            customerReceivableAnticipationConfigService.toggleBlockBankSlipAnticipation(ToggleBlockBillingTypeAdapter.createForBlockingByOwnerAccount(customer))
            customerCreditCardAbusePreventionParameterService.save(customer, CustomerCreditCardAbusePreventionParameterName.SAME_CARD_TOKENIZED_COUNT, 5)
            customerCreditCardAbusePreventionParameterService.save(customer, CustomerCreditCardAbusePreventionParameterName.APPROVAL_RATE_PERIOD, 0)
        }

        if (accountOwner.id == 533474) {
            customerBoletoConfigService.save(customer, [hideInvoiceUrl: true])
        }

        // Alfa Sistemas
        if (accountOwner.id == 1188603) {
            Map customerBoletoConfig = [:]
            customerBoletoConfig.hideContactInfo = true
            customerBoletoConfig.hideInvoiceUrl = true
            customerBoletoConfigService.save(customer, customerBoletoConfig)
        }

        // PIXAPAY TECNOLOGIA DE PAGAMENTOS LTDA
        if (accountOwner.id == 1860228 || (accountOwner.id == 63536 && AsaasEnvironment.isSandbox())) {
            Map customerBoletoConfig = [:]
            customerBoletoConfig.hideContactInfo = true
            customerBoletoConfig.hideInvoiceUrl = true
            customerBoletoConfigService.save(customer, customerBoletoConfig)

            customerDocumentGroupProxyService.saveCustomDocument(customer, "Termos de Uso Asaas - PixaPay", "Documento de comprovação do KYC realizado")
            customerProofOfLifeService.autoApproveByAccountOwner(customer)
            customerRegisterStatusService.updateBankAccountInfoStatus(customer, Status.APPROVED)
        }

        // SIMPLEST SOFTWARE LTDA
        if (accountOwner.id == 1295361) {
            customerCreditCardAbusePreventionParameterService.save(customer, CustomerCreditCardAbusePreventionParameterName.BYPASS_DISTINCT_API_IP_COUNT, true)
        }

        // NEXUS PAY LTDA
        if (accountOwner.id == 1368911) {
            Map customerBoletoConfig = [:]
            customerBoletoConfig.hideContactInfo = false
            customerBoletoConfig.showCustomerInfo = false
            customerBoletoConfig.hideInvoiceUrl = false
            customerBoletoConfigService.save(customer, customerBoletoConfig)
        }

        // Vakinha
        if (accountOwner.id == 761765) {
            customerParameterService.save(customer, CustomerParameterName.BYPASS_PIX_ADDRESS_KEY_EMAIL_OWNERSHIP_CONFIRMATION, "@vakinha.com.br")
        }

        // MK SOLUTIONS CRIACAO DE SOFTWARE LTDA
        if ([1312978L, 1380354L, 1380358L, 1380361L, 1380364L, 1380369L, 1889800L, 1873767L].contains(accountOwner.id)) {
            customerDocumentGroupProxyService.saveCustomDocument(customer, "Termos de Uso Asaas - MK Solutions", "Aceite dos Termos de Uso Asaas pelas contas MK Solutions")
        }

        // SOFTCEL INFORMATICA EIRELI
        if (accountOwner.id == 1366107) {
            Map customerBoletoConfig = [:]
            customerBoletoConfig.hideContactInfo = true
            customerBoletoConfig.showCustomerInfo = false
            customerBoletoConfig.hideInvoiceUrl = true
            customerBoletoConfigService.save(customer, customerBoletoConfig)
        }

        // CARTPANDA TECNOLOGIA DE PAGAMENTOS LTDA
        if (accountOwner.id == 1697379) {
            customerDocumentGroupProxyService.saveCustomDocument(customer, "LOG de KYC realizado", "Documento de comprovação do KYC realizado")
            customerProofOfLifeService.autoApproveByAccountOwner(customer)
            customerRegisterStatusService.updateBankAccountInfoStatus(customer, Status.APPROVED)
        }

        // PIPEIMOB TECNOLOGIA LTDA
        if (accountOwner.id == 1669486) {
            customerAdminService.updateMaxPaymentValue(customer.id, 500000.00)
        }

        // FIDO SOCIEDADE DE EMPRESTIMO ENTRE PESSOAS S.A
        if (accountOwner.id == 1431754) {
            customerDocumentGroupProxyService.saveCustomDocument(customer, "Selfie de Identificação", "Selfie de Identificação do Titular da Conta")
            customerProofOfLifeService.autoApproveByAccountOwner(customer)
            customerRegisterStatusService.updateBankAccountInfoStatus(customer, Status.APPROVED)
        }

        // NEGOCIO ANIMAL TECNOLOGIA S/A
        if (accountOwner.id == 2118443 || (accountOwner.id == 78303 && AsaasEnvironment.isSandbox())) {
            customerDocumentGroupProxyService.saveCustomDocument(customer, "Termos de Uso Asaas - Negocio Animal", "Documento de comprovação do KYC realizado")
            customerProofOfLifeService.autoApproveByAccountOwner(customer)
            customerRegisterStatusService.updateBankAccountInfoStatus(customer, Status.APPROVED)
        }

        // TRYPLO TECNOLOGIA LTDA
        if (accountOwner.id == 2415945) {
            customerReceivableAnticipationConfigService.toggleBlockBankSlipAnticipation(ToggleBlockBillingTypeAdapter.createForBlockingByOwnerAccount(customer))
            customerReceivableAnticipationConfigService.toggleBlockCreditCardAnticipation(ToggleBlockBillingTypeAdapter.createForBlockingByOwnerAccount(customer))
        }

        // CWS
        if (accountOwner.id == 1591018) {
            customerDocumentGroupProxyService.saveCustomDocument(customer, "Procuração / Contrato Social / Comprovante de Residência", "Realizar o upload do documento necessário")
        }

        //IHELP FILANTROPIA LTDA
        if (accountOwner.id == 1493132) {
            customerParameterService.save(customer, CustomerParameterName.BYPASS_PIX_ADDRESS_KEY_EMAIL_OWNERSHIP_CONFIRMATION, "@ihelp.social")
        }

        // SOFTPLAN PLANEJAMENTO E SISTEMAS S/A
        if (accountOwner.id == 1820090) {
            customerCreditCardAbusePreventionParameterService.save(customer, CustomerCreditCardAbusePreventionParameterName.BYPASS_DISTINCT_API_IP_COUNT, true)
        }

        // RIFA4
        if (accountOwner.id == 797867) {
            customerReceivableAnticipationConfigService.toggleBlockBankSlipAnticipation(ToggleBlockBillingTypeAdapter.createForBlockingByOwnerAccount(customer))
            customerReceivableAnticipationConfigService.toggleBlockCreditCardAnticipation(ToggleBlockBillingTypeAdapter.createForBlockingByOwnerAccount(customer))
        }

        // KALYST DESENVOLVIMENTO E TECNOLOGIA LTDA
        if (accountOwner.id == 2116582) {
            customerReceivableAnticipationConfigService.toggleBlockBankSlipAnticipation(ToggleBlockBillingTypeAdapter.createForBlockingByOwnerAccount(customer))
            customerReceivableAnticipationConfigService.toggleBlockCreditCardAnticipation(ToggleBlockBillingTypeAdapter.createForBlockingByOwnerAccount(customer))
        }

        //KIWID TECHNOLOGY LTDA
        if (accountOwner.id == 3017021) {
            List customerInfoList = ["NAME", "CPF_CNPJ", "PHONE_NUMBERS"]
            customerInvoiceConfigAdminService.save(customer, customerInfoList, CustomerColorPalette.WHITE.primaryColor)
        }

        // MERISTES ME
        if (accountOwner.id == 1578068) {
            Map customerBoletoConfig = [:]
            customerBoletoConfig.hideContactInfo = true
            customerBoletoConfig.showCustomerInfo = false
            customerBoletoConfig.hideInvoiceUrl = true
            customerBoletoConfigService.save(customer, customerBoletoConfig)
        }

        // GREENN PAGAMENTOS E TECNOLOGIA LTDA
        if (accountOwner.id == 3572794) {
            customerDocumentGroupProxyService.saveCustomDocument(customer, "LOG de KYC realizado", "Documento de comprovação do KYC realizado")
            customerProofOfLifeService.autoApproveByAccountOwner(customer)
            customerRegisterStatusService.updateBankAccountInfoStatus(customer, Status.APPROVED)
        }

        // YOUSHOP
        if (accountOwner.id == 3008608) {
            Map customerBoletoConfig = [:]
            customerBoletoConfig.hideInvoiceUrl = true
            customerBoletoConfigService.save(customer, customerBoletoConfig)
        }

        // INTEGRA EVOLUCAO INTELIGENTE
        if (accountOwner.id == 2416397) {
            customerAdminService.updateMaxPaymentValue(customer.id, 500000.00)
        }

        // NATURAL WEB COMERCIO E PRESTACAO DE SERVICOS DE INFORMATICA LTDA
        if (accountOwner.id == 3200422) {
            customerAdminService.updateMaxPaymentValue(customer.id, 150000.00)
        }

        // INSWITCH TECNOLOGIAS FINANCEIRAS LTDA - VPAG
        if (accountOwner.id == 3225020 || (accountOwner.id == 7787 && AsaasEnvironment.isSandbox())) {
            customerDocumentGroupProxyService.saveCustomDocument(customer, "LOG de KYC realizado", "Documento de comprovação do KYC realizado")
            customerProofOfLifeService.autoApproveByAccountOwner(customer)
            customerRegisterStatusService.updateBankAccountInfoStatus(customer, Status.APPROVED)
        }

        //AUDITUS SISTEMAS LTDA
        if (accountOwner.id == 1734239) {
            customerConfigService.changeNotificationMessageType(customer, NotificationMessageType.DONATION)
        }

        // HOTINA LTDA
        if (accountOwner.id == 3778544) {
            customerBoletoConfigService.save(customer, [hideInvoiceUrl: true])
        }

        // ESCOLA DE EDUCACAO INFANTIL MULTI SABER LTDA
        if (accountOwner.id == 3680326) {
            customerDocumentGroupProxyService.saveCustomDocument(customer, "Licitação / Diário Oficial", "Realizar o upload do documento necessário")
        }

        // MOVISIS TECNOLOGIA LTDA
        if (accountOwner.id == 2206968) {
            customerAdminService.updateMaxPaymentValue(customer.id, 500000.00)
        }

        // SMART CLIC DESENVOLVIMENTO DE SISTEMAS WEB LTDA
        if (accountOwner.id == 3863350) {
            List customerInfoList = ["NAME", "CITY", "CPF_CNPJ"]
            customerInvoiceConfigAdminService.save(customer, customerInfoList, CustomerColorPalette.WHITE.primaryColor)
        }

        if (apiConfigService.hasAutoApproveCreatedAccount(accountOwner.id) && !customer.customerConfig.transferValidationBypassAllowed) {
            allowTransferValidationBypass(customer)
        }

        if (options.containsKey("automaticTransferEnabled")) {
            customerParameterService.save(customer, CustomerParameterName.AUTOMATIC_TRANSFER, Utils.toBoolean(options.automaticTransferEnabled))
        }

        setCustomDailyPaymentsLimitFromAccountOwner(customer, accountOwner)
    }

    private void allowTransferValidationBypass(Customer customer) {
        customer.customerConfig.transferValidationBypassAllowed = true
        customer.customerConfig.save(failOnError: true)
    }

    private void setCustomDailyPaymentsLimitFromAccountOwner(Customer customer, Customer accountOwner) {
        BigDecimal customPaymentsLimit = CustomerParameter.getNumericValue(accountOwner, CustomerParameterName.CUSTOM_DAILY_PAYMENTS_LIMIT)

        if (!customPaymentsLimit) return

        customerAdminService.updateDailyPaymentsLimit(customer.id, customPaymentsLimit)
    }
}

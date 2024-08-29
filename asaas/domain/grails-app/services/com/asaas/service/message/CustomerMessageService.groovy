package com.asaas.service.message

import com.asaas.customer.CustomerInvoiceConfigRejectReason
import com.asaas.customer.CustomerParameterName
import com.asaas.customerdealinfo.CustomerDealInfoAdapter
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.documentanalysis.RejectedDocumentsBuilder
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.bill.Bill
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.creditbureaureport.CreditBureauReport
import com.asaas.domain.creditbureaureport.CreditBureauReportInfo
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerConfig
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.documentanalysis.DocumentAnalysis
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.contestation.CercContestation
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.notification.NotificationRequestToEvent
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimit
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimitChangeRequest
import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.domain.sms.AsyncSmsType
import com.asaas.domain.sms.SmsPriority
import com.asaas.domain.stage.StageEvent
import com.asaas.domain.stage.StageEventTemplate
import com.asaas.domain.user.User
import com.asaas.email.AsaasEmailEvent
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestType
import com.asaas.sms.SmsSender
import com.asaas.status.Status
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils

import grails.util.Environment
import groovy.text.SimpleTemplateEngine

class CustomerMessageService {

    def asyncSmsMessageService
    def grailsApplication
    def grailsLinkGenerator
    def groovyPagesTemplateEngine
    def messageService
    def smsSenderService

    public Boolean canSendMessage(Customer customer) {
        return canSendMessage(customer, false)
    }

    public Boolean canSendMessage(Customer customer, Boolean isSecurityEmail) {
        if (CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)) return false
        if (isSecurityEmail) return true
        if (customer.isAsaasDebtRecoveryProvider()) return false
        return true
    }

    public void sendDefaultTemplateFromAccountManager(Customer customer, String subject, String bodyPath, Map bodyParams, Boolean isRelevantMail) {
        if (!canSendMessage(customer)) return

        messageService.sendDefaultTemplateFromAccountManager(customer, subject, bodyPath, bodyParams, isRelevantMail)
    }

    public void sendSmsText(Customer customer, String message, String phoneNumber, Boolean throwExceptionOnError) {
        if (!canSendMessage(customer)) return

        smsSenderService.send(message, phoneNumber, throwExceptionOnError, [:])
    }

    public void notifyAboutCercContestationDeadlineForReplyExceeded(Customer customer, CercContestation cercContestation) {
        String emailSubject = "Ainda não tivemos retorno da contestação do contrato"
        String emailBody = messageService.buildTemplate("/mailTemplate/cerc/contractContestationDeadlineForReplyExceeded", [cercContestation: cercContestation])

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void notifyPaymentFloatUpdate(Customer customer, Boolean increased) {
        String emailSubject
        String emailBodyPath
        Map emailBodyParams = [:]

        if (increased) {
            emailSubject = "A quantidade de dias para recebimento de boletos foi alterada"

            emailBodyPath = "/mailTemplate/paymentFloatIncreased"
            emailBodyParams =  [paymentFloat: customer.customerConfig.paymentFloat]
        } else {
            emailSubject = customer.customerConfig.paymentFloat == CustomerConfig.MININUM_PAYMENT_FLOAT ? "Seu dinheiro estará disponível para saque imediatamente" : "Seu dinheiro estará disponível para saque mais cedo"

            emailBodyPath = "/mailTemplate/paymentFloatDecreased"
            emailBodyParams =  [paymentFloat: customer.customerConfig.paymentFloat]
        }

        emailBodyParams += [daysToReceiveCreditCard: grailsApplication.mainContext.creditCardService.buildDaysToReceiveCreditCard(customer)]

        sendDefaultTemplateFromAccountManager(customer, emailSubject, emailBodyPath, emailBodyParams, false)
    }

    public void notifyEstimatedDebitDateUpdated(Customer customer, CreditTransferRequest creditTransferRequest, String reason) {
        String emailSubject = "A previsão da sua transferência foi alterada"

        String emailBodyPath = "/mailTemplate/sendEstimatedDebitDateUpdateReason"
        Map emailBodyParams = [creditTransferRequest: creditTransferRequest, reason: reason]

       sendDefaultTemplateFromAccountManager(creditTransferRequest.provider, emailSubject, emailBodyPath, emailBodyParams, false)

        if (customer.mobilePhone) {
            String smsText

            if (creditTransferRequest.estimatedDaysForDebit == 1) {
                smsText = Utils.getMessageProperty("sms.transfer.authorize.changeEstimatedDaysForConfirmation.tomorrow")
            } else if (creditTransferRequest.estimatedDaysForDebit == 0) {
                smsText = Utils.getMessageProperty("sms.transfer.authorize.changeEstimatedDaysForConfirmation.today")
            } else {
                List smsTextArgs = [creditTransferRequest.estimatedDaysForDebit]
                smsText = Utils.getMessageProperty("sms.transfer.authorize.changeEstimatedDaysForConfirmation", smsTextArgs)
            }

            sendSmsText(customer, smsText, customer.mobilePhone, false)
        }
    }

    public void notifyGeneralApprovalAnalysisResult(Customer customer, GeneralApprovalStatus status, String observations) {
        String generalStatus = (status.isApproved()) ? "aprovada" : "reprovada"
        Map emailBodyParams = [generalStatus: generalStatus, observations: observations ?: ""]

        String emailBodyPath = "/mailTemplate/sendCustomerRegisterGeneralStatus"
        String subject = "Asaas - Sua conta foi ${generalStatus}"
        Boolean isRelevantMail = status.isRejected()

        sendDefaultTemplateFromAccountManager(customer, subject, emailBodyPath, emailBodyParams, isRelevantMail)
    }

    public void notifyCompulsoryCustomerRegisterUpdate(Customer customer) {
        String emailBody = messageService.buildTemplate("/mailTemplate/sendCompulsoryCustomerRegisterUpdate", [:])
        String emailSubject = "Seus dados foram atualizados!"

        Map options = [customer: customer, isRelevantMail: true]

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, options)
    }

    public void sendReportPaymentCheckoutProblemToCustomer(params, Payment payment) {
        try {
            String emailBodyPath = "/mailTemplate/paymentcheckout/reportPaymentCheckoutProblemToCustomer"
            Map emailBodyParams = [invoiceId: payment.getInvoiceNumber(),
                                   problemForm:  params.itemReportProblem,
                                   observationsForm: params.observation ?: "Não informado",
                                   nameForm: params.name,
                                   emailForm: params.email ?: "Não informado",
                                   phoneForm: params.phone ?: "Não informado",
                                   value: payment.value,
                                   dueDate: CustomDateUtils.fromDate(payment.dueDate),
                                   nameCustomerAccount: payment.customerAccount.company ?: payment.customerAccount.name,
                                   accountManager: payment.provider.accountManager]

            sendDefaultTemplateFromAccountManager(payment.provider, "Problema na fatura", emailBodyPath, emailBodyParams, false)
        } catch (Exception e) {
            AsaasLogger.error("CustomerMessageService.sendReportPaymentCheckoutProblemToCustomer >> Erro ao enviar relatório de problema no checkout do pagamento", e)
        }
    }

    public void sendInvoiceConfigRejectionReason(CustomerInvoiceConfig customerInvoiceConfig, CustomerInvoiceConfigRejectReason rejectReason) {
        String emailSubject = "Sua alteração na personalização da fatura precisa de atenção"
        Map params = [
            accountManager: customerInvoiceConfig.customer.accountManager,
            customerInvoiceConfig: customerInvoiceConfig,
            rejectReasonMessage: Utils.getMessageProperty("CustomerInvoiceConfigRejectReason.${rejectReason.toString()}"),
            rejectReasonInstructions: Utils.getMessageProperty("CustomerInvoiceConfigRejectReason.instructions.${rejectReason.toString()}")
        ]
        sendDefaultTemplateFromAccountManager(customerInvoiceConfig.customer, emailSubject, "/mailTemplate/sendInvoiceConfigRejectionReason", params, false)
    }

    public void sendWelcomeToChildAccount(Customer customer, String resetPasswordUrl) {
        String emailBody = messageService.buildTemplate("/mailTemplate/sendWelcomeToChildAccount", [resetPasswordUrl: resetPasswordUrl])
        String emailSubject = "Bem-vindo ao Asaas"

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void notifyCustomerThatInvoiceHasBeenViewed(Payment payment) {
        Utils.withNewTransactionAndRollbackOnError({
            if (CustomerParameter.getValue(payment.provider, CustomerParameterName.DISABLE_INVOICE_VIEWED_NOTIFICATION)) return

            sendInvoiceWasViewedForProvider(payment)

            if (payment.provider.mobilePhone) sendInvoiceWasViewedSmsText(payment)
        }, [logErrorMessage: "Erro no envio de notificações da visualização da fatura"])
	}

    public void notifyCustomerStageEventByEmail(Customer customer, StageEvent stageEvent) {
        if (!canSendMessage(customer)) return

        Map templateInfo = StageEventTemplate.query([columnList: ["subject", "body"], stageEventId: stageEvent.id]).get()
        if (!templateInfo) {
            AsaasLogger.error("CustomerMessageService.notifyCustomerStageEventByEmail >> stageEventId: ${stageEvent.id} não possui template.")
			return
		}

        Map parametersMap = [accountManager: customer.accountManager]

		StringWriter subject = new StringWriter()
		StringWriter body = new StringWriter()

		groovyPagesTemplateEngine.clearPageCache()
		groovyPagesTemplateEngine.createTemplate(templateInfo.subject, 'sample').make(parametersMap).writeTo(subject)
		groovyPagesTemplateEngine.clearPageCache()
		groovyPagesTemplateEngine.createTemplate(templateInfo.body, 'sample').make(parametersMap).writeTo(body)

        messageService.send(customer.accountManager.buildMailFrom(), customer.email, null, subject.toString(), body.toString(), true)
    }

    public void notifyCustomerStageEventBySms(Customer customer, StageEvent stageEvent) {
        String templateBody = StageEventTemplate.query([column: "body", stageEventId: stageEvent.id]).get()
        if (!templateBody) {
            AsaasLogger.error("CustomerMessageService.notifyCustomerStageEventBySms >> stageEventId ${stageEvent.id} não possui template.")
			return
		}

		Map parametersMap = [accountManager: customer.accountManager]
        String smsText = new SimpleTemplateEngine().createTemplate(templateBody).make(parametersMap).toString()
        sendSmsText(customer, smsText, customer.mobilePhone, false)
    }

    public void notifyCustomersAboutPaymentsNotVisualized(Customer customer) {
        if (customer.isAsaasProvider()) return

        String notVisualizedPaymentListLink = grailsLinkGenerator.link(controller: 'paymentList', action: 'index', params: [notVisualized: true, dueDate: CustomDateUtils.fromDate(new Date()), status: PaymentStatus.PENDING], absolute: true)
    	String emailBody = messageService.buildTemplate("/mailTemplate/notifyCustomersAboutPaymentsNotVisualized", [notVisualizedPaymentListLink: notVisualizedPaymentListLink])
        String emailSubject = "Há clientes que ainda não visualizaram a fatura vencendo hoje"

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void sendPaymentDunningCancelledEmail(PaymentDunning paymentDunning) {
        String emailBody = messageService.buildTemplate("/mailTemplate/sendCreditBureauDunningCancelled", [dunning: paymentDunning])
        String emailSubject = "Cancelamento da negativação"
        Customer customer = paymentDunning.payment.provider

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

     public void sendPaymentDunningCancelledByInsufficientBalanceEmail(PaymentDunning paymentDunning) {
        String emailBody = messageService.buildTemplate("/mailTemplate/sendCreditBureauDunningCancelledByInsufficientBalance", [dunning: paymentDunning])
        String emailSubject = "Solicitação de negativação precisa de atenção"
        Customer customer = paymentDunning.payment.provider

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void sendPaymentDunningProcessedEmail(PaymentDunning paymentDunning) {
        String emailBody = messageService.buildTemplate("/mailTemplate/sendCreditBureauDunningProcessed", [dunning: paymentDunning])
        String emailSubject = "Cobrança negativada"
        Customer customer = paymentDunning.payment.provider

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void sendPaymentDunningDeniedEmail(PaymentDunning paymentDunning) {
        String emailBody = messageService.buildTemplate("/mailTemplate/sendCreditBureauDunningDenied", [dunning: paymentDunning])
        String emailSubject = "Solicitação de negativação precisa de atenção"
        Customer customer = paymentDunning.payment.provider

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void sendNotificationToProviderAboutCustomerAccountInvalidMobilePhone(Customer customer, CustomerAccount customerAccount) {
        try {
            String emailBody = messageService.buildTemplate("/mailTemplate/notificationToProviderAboutCustomerAccountInvalidMobilePhone", [customerAccount : customerAccount])
            String emailSubject = "Número de telefone incorreto para ${customerAccount.name}"
            String emailTitle = "Número de telefone incorreto"

            sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [emailTitle : emailTitle])

        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email de número móvel não existente ou inválido. Customer: [${customer.id}], CustomerAccount: [${customerAccount.id}]", exception)
        }
    }

    public void notifyAboutDocumentAnalysis(Customer customer, DocumentAnalysis documentAnalysis) {
        sendDocumentAnalysisEmail(customer, documentAnalysis)

        sendDocumentAnalysisSms(customer, documentAnalysis)
    }

    public void notifyExpiredCriticalAction(Customer customer, CriticalAction action) {
        try {
            String emailSubject = messageService.buildTemplate("/mailTemplate/subject/expiredCriticalAction", [action: action])
            String emailBody = messageService.buildTemplate("/mailTemplate/expiredCriticalAction", [action: action])

            sendDefaultTemplate(action.customer, action.customer.email, null, emailSubject, emailBody, [:])
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email de expiracao de acoes criticas. CriticalAction: [${action.id}]", exception)
        }
    }

    public void notifyBillExpiredCriticalAction(Customer customer, CriticalAction action) {
        String billValue = FormUtils.formatCurrencyWithMonetarySymbol(action.bill.value)
        String authorizationDeviceTypeDescription = AuthorizationDevice.findCurrentTypeDescription(action.customer)

        String messageText = "Sua conta no valor de ${billValue} expirou pois não foi autorizada através do ${authorizationDeviceTypeDescription}. Acesse o Asaas para detalhes."

        sendSmsText(customer, messageText, customer.mobilePhone, false)
    }

    public void notifyNotAuthorizedTransferCriticalActions(Customer customer) {
        try {
            String emailSubject = "Importante! Há transferências esperando autorização para hoje."
            String emailBody = messageService.buildTemplate("/mailTemplate/notifyNotAuthorizedTransferCriticalActions", [customer: customer])

            sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])

            String messageText = "Olá! Você possui transferências não autorizadas. Acesse o Asaas e faça a autorização até às ${CreditTransferRequest.LIMIT_HOUR_TO_APPROVE_TRANSFER_CRITICAL_ACTION_ON_SAME_DAY} para evitar que sejam adiadas."

            sendSmsText(customer, messageText, customer.mobilePhone, false)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email de transferencias aguardando autorizacao. Customer: [${customer.id}]", exception)
        }
    }

    public void notifyDeferredNotAuthorizedTransferCriticalAction(Customer customer, CriticalAction action) {
        try {
            String transferValue = FormUtils.formatCurrencyWithMonetarySymbol(action.transfer.value)
            String authorizationDeviceTypeDescription = AuthorizationDevice.findCurrentTypeDescription(action.customer)

            String emailSubject = "Sua transferência no valor de ${transferValue} foi adiada."
            String emailBody = messageService.buildTemplate("/mailTemplate/deferredNotAuthorizedTransferCriticalAction", [customer: action.customer, transferValue: transferValue, authorizationDeviceTypeDescription: authorizationDeviceTypeDescription])

            sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])

            String messageText = "Sua transferência no valor de ${transferValue} foi adiada pois não foi autorizada através do ${authorizationDeviceTypeDescription}."

            sendSmsText(customer, messageText, action.customer.mobilePhone, false)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email de transferencias adiadas. CriticalAction: [${action.id}]", exception)
        }
    }

    public void notifyCriticalActionsAwaitingAuthorization(Customer customer) {
        try {
            if (!Environment.getCurrent().equals(Environment.PRODUCTION)) return

            String authorizationDeviceTypeDescription = AuthorizationDevice.findCurrentTypeDescription(customer)

            String emailSubject = "Importante! Há eventos críticos aguardando sua autorização."
            String emailBody = messageService.buildTemplate("/mailTemplate/criticalActionsAwaitingAuthorization", [authorizationDeviceTypeDescription: authorizationDeviceTypeDescription])

            sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])

            String messageText = "Olá! Você possui eventos críticos que estão aguardando autorização via ${authorizationDeviceTypeDescription}. Acesse o Asaas e faça a autorização."

            sendSmsText(customer, messageText, customer.mobilePhone, false)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email acoes criticos aguardando autorizacao. Customer: [${customer.id}]", exception)
        }
    }

    public void notifyCustomerUpdateRequestResult(Customer customer, CustomerUpdateRequest customerUpdateRequest) {
        try {
            String action = customerUpdateRequest.status == Status.APPROVED ? "foram aprovadas" : "precisam de sua atenção"

            String subject = Utils.getMessageProperty("customerUpdateRequest.update.emailSubject", [action])
            Map bodyParams = [name: customer.buildTradingName(),
                              denialReason: customerUpdateRequest.denialReason,
                              observations: customerUpdateRequest.observations,
                              accountManager: customer.accountManager,
                              action: action]

            sendDefaultTemplateFromAccountManager(customer, subject, "/mailTemplate/sendResultCustomerUpdateRequest", bodyParams, false)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar notificação sobre resultado da alteração de dados comerciais", exception)
        }
    }

    public void sendInvoiceBatchFileByEmail(Customer customer, AsaasFile asaasFile, String email) {
        String emailSubject = "Seu download de notas fiscais do Asaas está pronto"
        String emailBody = messageService.buildTemplate("/mailTemplate/sendCustomerInvoiceFiles", [asaasFile: asaasFile])

        sendDefaultTemplate(customer, email, null, emailSubject, emailBody, [bypassNotificationConstraints: true])
    }

    public void sendBillPaymentFailedAlert(Bill bill) {
        String subject = "Erro no pagamento da sua conta"
        String body = messageService.buildTemplate("/mailTemplate/sendBillPaymentFailedAlert", [bill: bill])

        sendDefaultTemplate(bill.customer, bill.customer.email, null, subject, body, [:])
    }

    public void notifyScheduledBill(Customer customer, Date scheduleDate, List<Bill> billList) {
        String subject = "Você possui ${StringUtils.getPlural(billList.size(), "pagamento", "pagamentos")} de conta agendado para ${CustomDateUtils.fromDate(scheduleDate, "dd/MM")}"
        String body

        if (billList.size() > 1) {
            body = messageService.buildTemplate("/mailTemplate/notifyScheduledBills", [billList: billList, scheduleDate: scheduleDate])
        } else {
            body = messageService.buildTemplate("/mailTemplate/notifyScheduledBill", [bill: billList.first()])
        }

        sendDefaultTemplate(customer, customer.email, null, subject, body, [:])
    }

    public void notifyBillFailureForInsufficientBalance(List<Bill> billList, Customer customer) {
        String subject
        String body

        if (billList.size() > 1) {
            subject = "${billList.size()} pagamentos de conta precisam de atenção!"
            body = messageService.buildTemplate("/mailTemplate/notifyBillFailuresForInsufficientBalance", [billList: billList])
        } else {
            subject = "Não foi possível realizar o pagamento no valor ${FormUtils.formatCurrencyWithMonetarySymbol(billList.first().value)}"
            body = messageService.buildTemplate("/mailTemplate/notifyBillFailureForInsufficientBalance", [bill: billList.first()])
        }

        sendDefaultTemplate(customer, customer.email, null, subject, body, [:])
    }

    public void sendBankSlipPdfBatchFileByEmail(Customer customer, AsaasFile asaasFile, String email, String subject) {
        String body = messageService.buildTemplate("/mailTemplate/sendCustomerBankSlipFiles", [asaasFile: asaasFile])

        sendDefaultTemplate(customer, email, null, subject, body, [bypassNotificationConstraints: true])
    }

    public void sendPaymentExportBatchFileByEmail(Customer customer, AsaasFile asaasFile, String email, String subject) {
        String body = messageService.buildTemplate("/mailTemplate/sendCustomerPaymentExportFile", [asaasFile: asaasFile])

        sendDefaultTemplate(customer, email, null, subject, body, [bypassNotificationConstraints: true])
    }

    public void sendDebtNotificationNegativeBalanceAlert(Customer customer, BigDecimal negativeBalance) {
        if (!AsaasEnvironment.isProduction()) return

        List<String> bccEmailList = []
        CustomerDealInfoAdapter customerDealInfoAdapter = new CustomerDealInfoAdapter(customer)
        if (customer.segment?.isCorporate()) {
            bccEmailList.add(customer.accountManager.email)
        } else if (customerDealInfoAdapter.customerDealStatus) {
            bccEmailList.add(grailsApplication.config.asaas.contato)
        }

        String emailBody = messageService.buildTemplate("/mailTemplate/debtNotificationNegativeBalanceAlert", [customer: customer, negativeBalance: negativeBalance])
        String emailSubject = "AVISO DE SALDO NEGATIVO"

        sendDefaultTemplate(customer, customer.email, bccEmailList, emailSubject, emailBody, [:])
    }

    public void sendDebtRecoveryAlert(Customer customer, Map emailNotificationData) {
        if (!AsaasEnvironment.isProduction()) return

        String emailBody = messageService.buildTemplate("/mailTemplate/debtRecoveryAlert", emailNotificationData)
        String emailSubject = "Asaas - Cobrança referente ao saldo negativo"

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void sendAppropriatedCustomerDebtRecoveryAlert(Customer customer, Map emailNotificationData) {
        if (!AsaasEnvironment.isProduction()) return

        String emailBody = messageService.buildTemplate("/mailTemplate/appropriatedCustomerDebtRecoveryAlert", emailNotificationData)
        String emailSubject = "Asaas - Cobrança referente ao saldo negativo"

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void notifyAboutEmailDeliveryFailByEmail(Customer customer, NotificationRequestToEvent notificationRequestToEvent, String reasonOfFailMessage) {
        try {
            String emailBody = messageService.buildTemplate("/mailTemplate/notificationToProviderAboutEmailDeliveryFail", [notificationRequestToEvent: notificationRequestToEvent, reasonOfFailMessage : reasonOfFailMessage])
            String emailSubject = AsaasEmailEvent.errorStatusList.contains(notificationRequestToEvent.event) ? "O e-mail de um de seus clientes parece ser inválido" : "Uma mensagem enviada para seu cliente caiu na caixa de spam"

            sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar notificação sobre falha na entrega de e-mail", exception)
        }
    }

    public void sendPixAddressKeyOwnershipConfirmation(Customer customer, String token, String email) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = "Confirmação de e-mail para chave Pix"
        String emailBody = messageService.buildTemplate("/mailTemplate/pixAddressKeyOwnershipConfirmation", [token: token])

        sendDefaultTemplate(customer, email, null, emailSubject, emailBody, [bypassNotificationConstraints: true])
    }

    public void notifyAboutImportFinishedByEmail(Customer customer, String emailSubject, Map bodyParams) {
        String emailBody = messageService.buildTemplate("/mailTemplate/notificationToProviderAboutImportFinished", bodyParams)

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void sendCreditBureauReport(CreditBureauReport creditBureauReport, CreditBureauReportInfo creditBureauReportInfo, byte[] creditBureauReportFileContent) {
        String emailBody = messageService.buildTemplate("/mailTemplate/creditBureauReport",
                [cpfCnpjTypeDescription: CpfCnpjUtils.isCpf(creditBureauReport.cpfCnpj) ? "CPF" : "CNPJ",
                 cpfCnpjName: creditBureauReportInfo.name])

        String emailSubject = "Resultado da consulta Serasa de ${creditBureauReportInfo.name}"

        Map attachment = [
            attachmentName: "Consulta.pdf",
            attachmentBytes: creditBureauReportFileContent
        ]

        Map options = [bypassNotificationConstraints: true, multipart: true, attachmentList: [attachment]]

        sendDefaultTemplate(creditBureauReport.customer, creditBureauReport.customer.email, null, emailSubject, emailBody, options)
    }

    public void sendNotificationToCustomerAboutAvailableBalance(Customer customer, BigDecimal valueAvailable, List<Payment> paymentList) {
        try {
            String emailBody = messageService.buildTemplate("/mailTemplate/notificationToProviderAboutAvailableBalance", [valueAvailable: valueAvailable, paymentList: paymentList])
            String emailSubject = "Saldo de cobranças via cartão de crédito disponibilizado"

            sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
        } catch (Exception e) {
            AsaasLogger.error("CustomerMessageService.sendNotificationToCustomerAboutAvailableBalance ${customer.id}", e)
        }
    }

    public void notifyAccountOwnerAboutPasswordRecoveryAttempt(User user) {
        try {
            Map model = [
                username: user.username,
                date: CustomDateUtils.fromDateWithTime(new Date()),
                child: [
                    name: user.customer.getProviderName(),
                    document: user.customer.cpfCnpj,
                ]
            ]

            Customer customerOwner = user.customer.accountOwner
            String emailSubject = "Asaas - Tentativa de recuperação de senha"
            String emailBody = messageService.buildTemplate("/mailTemplate/passwordRecoveryAttemptAlert", model)

            sendDefaultTemplate(customerOwner, customerOwner.email, null, emailSubject, emailBody, [bypassNotificationConstraints: true])
        } catch (Exception e) {
            AsaasLogger.error("CustomerMessageService.sendToParentCustomerPasswordRecoveryAttemptAlertMail ${user.id}", e)
        }
    }

    public void notifyCustomerAboutInvoiceErrors(Customer customer, Integer totalInvoiceWithErrorCount, List<Map> invoiceInfoList, Date invoiceEffectiveDate) {
        String formattedEffectiveDate = CustomDateUtils.formatDate(invoiceEffectiveDate)

        String emailBody = messageService.buildTemplate("/mailTemplate/customerInvoice/invoiceErrors", [
            cityNameAndState: customer.getCityNameAndState(),
            totalInvoiceWithErrorCount: totalInvoiceWithErrorCount,
            invoiceInfoList: invoiceInfoList,
            formattedEffectiveDate: formattedEffectiveDate
        ])
        String emailSubject = "Você possui Notas Fiscais que não foram enviadas!"
        String emailTitle = "Erro na emissão de notas fiscais previstas para ${formattedEffectiveDate}"

        sendDefaultTemplate(customer, customer.getFiscalInfoEmail(), null, emailSubject, emailBody, [emailTitle: emailTitle])
    }

    public void notifyCustomerAboutInvoiceDenied(Map invoiceInfo) {
        Invoice invoice = invoiceInfo.invoice

        try {
            String customerAccountName = invoice.customerAccount.name ?: invoice.customerAccount.email
            String emailSubject = "A nota fiscal do seu cliente ${customerAccountName} precisa de atenção"
            String emailBody = messageService.buildTemplate("/mailTemplate/customerInvoice/invoiceDenied", [
                invoiceInfo: invoiceInfo,
                customerAccountName: customerAccountName,
                isCancellationDenied: invoice.status.isCancellationDenied(),
                customerAccount: invoice.customerAccount,
                cityNameAndState: invoice.customer.getCityNameAndState()
            ])

            sendDefaultTemplate(invoice.customer, invoice.customer.getFiscalInfoEmail(), null, emailSubject, emailBody, [:])
        } catch (Exception e) {
            AsaasLogger.error("CustomerMessageService.notifyCustomerAboutInvoiceDenied ${invoice.id}", e)
        }
    }

    public void notifyCustomerAboutInvoiceInterestValueProblem(Invoice invoice) {
        try {
            String emailSubject = "A nota fiscal do seu cliente ${invoice.customerAccount.name ?: invoice.customerAccount.email} precisa de atenção!"
            String emailBody = messageService.buildTemplate("/mailTemplate/invoiceInterestProblemReport", [invoice: invoice])

            sendDefaultTemplate(invoice.customer, invoice.customer.getFiscalInfoEmail(), null, emailSubject, emailBody, [:])
        } catch (Exception e) {
            AsaasLogger.error("CustomerMessageService.notifyCustomerAboutInvoiceInterestValueProblem ${invoice.id}", e)
        }
    }

    public void sendReferralAwardInvitation(Map customerData) {
        try {
            Map params = [
                invitationUrl: grailsLinkGenerator.link(controller: "referral", action: "index", absolute: true)
            ]

            String emailBody = messageService.buildTemplate("/mailTemplate/referral/sendReferralAwardInvitation", params)
            emailBody += messageService.buildMarkupToTrackEmailView(grailsLinkGenerator.link(controller: "referral", action: "trackReferralAwardEmailVisualization", customerId: customerData.customerId, absolute: true))

            Customer customer = Customer.get(customerData.customerId)

            sendDefaultTemplate(customer, customerData.customerEmail, null, "Indique e ganhe", emailBody, [:])
        } catch (Exception e) {
            AsaasLogger.error("CustomerMessageService.sendReferralAwardInvitation - Erro ao enviar email de impulsionamento do produto Referral. CustomerId: [${customerData.customerId}]", e)
        }
    }

    public void notifyPixTransactionCheckoutLimitChangeRequestRequested(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        try {
            Customer customer = pixTransactionCheckoutLimitChangeRequest.customer

            Map model = [
                name: customer.name,
                customer: customer,
                pixTransactionCheckoutLimitChangeRequest: pixTransactionCheckoutLimitChangeRequest
            ]

            String emailSubject
            if (pixTransactionCheckoutLimitChangeRequest.type.isChangeLimit()) {
                emailSubject = "Alteração dos seus ${Utils.getMessageProperty("pixTransactionCheckoutLimitChangeRequestScope.plural.${pixTransactionCheckoutLimitChangeRequest.scope}")}"
            } else {
                emailSubject = "${Utils.getEnumLabel(PixTransactionCheckoutLimitChangeRequestType.CHANGE_NIGHTLY_HOUR)} Pix"
            }

            String emailBody = messageService.buildTemplate("/mailTemplate/pixTransactionCheckoutLimitChangeRequest/requested", model)
            Map options = [customer: customer, isRelevantMail: true]

            List<String> adminUsersEmailList = grailsApplication.mainContext.userService.getAdminUsernames(customer)
            adminUsersEmailList.remove(customer.email)

            sendDefaultTemplate(customer, customer.email, adminUsersEmailList, emailSubject, emailBody, options)
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyPixTransactionCheckoutLimitChangeRequestRequested >> Erro ao enviar e-mail de notificação de solicitação de alteração nos Limites Pix. [pixTransactionCheckoutLimitChanheRequestId: ${pixTransactionCheckoutLimitChangeRequest.id}]", exception)
        }
    }

    public void notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestRequested(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        try {
            Customer customer = pixTransactionBankAccountInfoCheckoutLimitChangeRequest.customer

            Map model = [
                name: customer.name,
                customer: customer,
                pixTransactionBankAccountInfoCheckoutLimitChangeRequest: pixTransactionBankAccountInfoCheckoutLimitChangeRequest
            ]

            String emailSubject = "Alteração de limite para conta cadastrada"
            String emailBody = messageService.buildTemplate("/mailTemplate/pixTransactionBankAccountInfoCheckoutLimitChangeRequest/requested", model)
            Map options = [customer: customer, isRelevantMail: true]

            List<String> adminUsersEmailList = grailsApplication.mainContext.userService.getAdminUsernames(customer)
            adminUsersEmailList.remove(customer.email)

            sendDefaultTemplate(customer, customer.email, adminUsersEmailList, emailSubject, emailBody, options)
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestRequested >> Erro ao enviar e-mail de notificação de solicitação de alteração nos Limites Pix por conta bancária. [pixTransactionBankAccountInfoCheckoutLimitChangeRequestId: ${pixTransactionBankAccountInfoCheckoutLimitChangeRequest.id}]", exception)
        }
    }

    public void notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestApproved(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        try {
            Customer customer = pixTransactionBankAccountInfoCheckoutLimitChangeRequest.customer

            Map model = [
                name: customer.name,
                customer: customer,
                pixTransactionBankAccountInfoCheckoutLimitChangeRequest: pixTransactionBankAccountInfoCheckoutLimitChangeRequest
            ]

            String emailSubject = "Limite para conta aprovado"
            String emailBody = messageService.buildTemplate("/mailTemplate/pixTransactionBankAccountInfoCheckoutLimitChangeRequest/approved", model)
            Map options = [customer: customer, isRelevantMail: true]

            List<String> adminUsersEmailList = grailsApplication.mainContext.userService.getAdminUsernames(customer)
            adminUsersEmailList.remove(customer.email)

            sendDefaultTemplate(customer, customer.email, adminUsersEmailList, emailSubject, emailBody, options)
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestApproved >> Erro ao enviar e-mail de aprovação de alteração nos Limites Pix por conta bancária. [pixTransactionBankAccountInfoCheckoutLimitChangeRequestId: ${pixTransactionBankAccountInfoCheckoutLimitChangeRequest.id}]", exception)
        }
    }

    public void notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestDenied(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        try {
            Customer customer = pixTransactionBankAccountInfoCheckoutLimitChangeRequest.customer

            Map model = [
                name: customer.name,
                customer: customer,
                pixTransactionBankAccountInfoCheckoutLimitChangeRequest: pixTransactionBankAccountInfoCheckoutLimitChangeRequest
            ]

            String emailSubject = "Limite para conta rejeitado"
            String emailBody = messageService.buildTemplate("/mailTemplate/pixTransactionBankAccountInfoCheckoutLimitChangeRequest/denied", model)
            Map options = [customer: customer, isRelevantMail: true]

            List<String> adminUsersEmailList = grailsApplication.mainContext.userService.getAdminUsernames(customer)
            adminUsersEmailList.remove(customer.email)

            sendDefaultTemplate(customer, customer.email, adminUsersEmailList, emailSubject, emailBody, options)
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestDenied >> Erro ao enviar e-mail de negação de alteração nos Limites Pix por conta bancária. [pixTransactionBankAccountInfoCheckoutLimitChangeRequestId: ${pixTransactionBankAccountInfoCheckoutLimitChangeRequest.id}]", exception)
        }
    }

    public void notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestCancelled(PixTransactionBankAccountInfoCheckoutLimitChangeRequest pixTransactionBankAccountInfoCheckoutLimitChangeRequest) {
        try {
            Customer customer = pixTransactionBankAccountInfoCheckoutLimitChangeRequest.customer

            Map model = [
                name: customer.name,
                customer: customer
            ]

            String emailSubject = "Alteração de limite por conta cancelada"
            String emailBody = messageService.buildTemplate("/mailTemplate/pixTransactionBankAccountInfoCheckoutLimitChangeRequest/cancelled", model)
            Map options = [customer: customer, isRelevantMail: true]

            List<String> adminUsersEmailList = grailsApplication.mainContext.userService.getAdminUsernames(customer)
            adminUsersEmailList.remove(customer.email)

            sendDefaultTemplate(customer, customer.email, adminUsersEmailList, emailSubject, emailBody, options)
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyPixTransactionBankAccountInfoCheckoutLimitChangeRequestCancelled >> Erro ao enviar e-mail de cancelamento de alteração nos Limites Pix por conta bancária. [pixTransactionBankAccountInfoCheckoutLimitChangeRequestId: ${pixTransactionBankAccountInfoCheckoutLimitChangeRequest.id}]", exception)
        }
    }

    public void notifyPixTransactionBankAccountInfoCheckoutLimitDeleted(PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit) {
        try {
            Customer customer = pixTransactionBankAccountInfoCheckoutLimit.customer

            Map model = [
                name: customer.name,
                customer: customer,
                bankAccountInfoName: pixTransactionBankAccountInfoCheckoutLimit.bankAccountInfo.name
            ]

            String emailSubject = "Limite para conta removido"
            String emailBody = messageService.buildTemplate("/mailTemplate/pixTransactionBankAccountInfoCheckoutLimit/deleted", model)
            Map options = [customer: customer, isRelevantMail: true]

            List<String> adminUsersEmailList = grailsApplication.mainContext.userService.getAdminUsernames(customer)
            adminUsersEmailList.remove(customer.email)

            sendDefaultTemplate(customer, customer.email, adminUsersEmailList, emailSubject, emailBody, options)
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyPixTransactionBankAccountInfoCheckoutLimitDeleted >> Erro ao enviar e-mail de remoção de Limite Pix por conta bancária. [pixTransactionBankAccountInfoCheckoutLimitChangeRequestId: ${pixTransactionBankAccountInfoCheckoutLimitChangeRequest.id}]", exception)
        }
    }

    public void notifyPixTransactionCheckoutLimitChangeRequestApproved(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        try {
            Customer customer = pixTransactionCheckoutLimitChangeRequest.customer

            Map model = [
                name: customer.name,
                customer: customer,
                pixTransactionCheckoutLimitChangeRequest: pixTransactionCheckoutLimitChangeRequest
            ]

            String emailSubject
            if (pixTransactionCheckoutLimitChangeRequest.type.isChangeLimit()) {
                emailSubject = "Alteração dos seus ${Utils.getMessageProperty("pixTransactionCheckoutLimitChangeRequestScope.plural.${pixTransactionCheckoutLimitChangeRequest.scope}")}"
            } else {
                emailSubject = "${Utils.getEnumLabel(PixTransactionCheckoutLimitChangeRequestType.CHANGE_NIGHTLY_HOUR)} Pix"
            }
            String emailBody = messageService.buildTemplate("/mailTemplate/pixTransactionCheckoutLimitChangeRequest/approved", model)
            Map options = [customer: customer, isRelevantMail: true]

            List<String> adminUsersEmailList = grailsApplication.mainContext.userService.getAdminUsernames(customer)
            adminUsersEmailList.remove(customer.email)

            sendDefaultTemplate(customer, customer.email, adminUsersEmailList, emailSubject, emailBody, options)
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyPixTransactionCheckoutLimitChangeRequestApproved >> Erro ao enviar e-mail de notificação de solicitação de alteração nos Limites Pix. [pixTransactionCheckoutLimitChanheRequestId: ${pixTransactionCheckoutLimitChangeRequest.id}]", exception)
        }
    }

    public void notifyPixTransactionCheckoutLimitChangeRequestDenied(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        try {
            Customer customer = pixTransactionCheckoutLimitChangeRequest.customer

            Map model = [
                name: customer.name,
                customer: customer,
                pixTransactionCheckoutLimitChangeRequest: pixTransactionCheckoutLimitChangeRequest
            ]

            String emailSubject = "Alteração dos seus ${Utils.getMessageProperty("pixTransactionCheckoutLimitChangeRequestScope.plural.${pixTransactionCheckoutLimitChangeRequest.scope}")}"
            String emailBody = messageService.buildTemplate("/mailTemplate/pixTransactionCheckoutLimitChangeRequest/denied", model)
            Map options = [customer: customer, isRelevantMail: true]

            List<String> adminUsersEmailList = grailsApplication.mainContext.userService.getAdminUsernames(customer)
            adminUsersEmailList.remove(customer.email)

            sendDefaultTemplate(customer, customer.email, adminUsersEmailList, emailSubject, emailBody, options)
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyPixTransactionCheckoutLimitChangeRequestApproved >> Erro ao enviar e-mail de notificação de solicitação de alteração nos Limites Pix. [pixTransactionCheckoutLimitChanheRequestId: ${pixTransactionCheckoutLimitChangeRequest.id}]", exception)
        }
    }

    public void notifyDuplicatedPaymentReceived(Payment originalPayment, Payment payment) {
        try {
            String emailSubject = "Seu cliente ${payment.customerAccount.name} pagou em duplicidade a cobrança nr. ${originalPayment.getInvoiceNumber()}"
            String emailBody = messageService.buildTemplate("/mailTemplate/duplicatedPayment", [originalPayment: originalPayment, payment: payment])

            sendDefaultTemplate(payment.provider, payment.provider.email, null, emailSubject, emailBody, [:])
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyDuplicatedPaymentReceived >> Erro ao enviar e-mail de notificação de pagamento em duplicidade. [originalPaymentId: ${originalPayment.id}, newPaymentId: ${payment.id}]", exception)
        }
    }

    public void notifyAboutQuotaLimitExceeded(Long customerId, Date nextPeriod) {
        try {
            Customer customer = Customer.load(customerId)
            Customer recipient = getMessageRecipient(customer)
            if (!recipient) return

            final String mailSubject = "⚠️ Suas requisições API ao Asaas estão temporariamente bloqueadas"
            final String mailTitle = "Suas requisições API ao Asaas estão temporariamente bloqueadas"

            Map model = [nextPeriod: nextPeriod]
            Boolean showChildAccount = recipient != customer
            if (showChildAccount) model.childAccount = customer

            String mailBody = messageService.buildTemplate("/mailTemplate/api/quotaLimit/limitExceeded", model)

            sendDefaultTemplate(recipient, recipient.email, null, mailSubject, mailBody, [ emailTitle: mailTitle, bypassNotificationConstraints: true])
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyAboutQuotaLimitExceeded >> Erro ao notificar o cliente[${customerId}] sobre o limite da cota de requisições atingido.", exception)
        }
    }

    public void notifyAboutBurstLimitExceeded(Long customerId, Integer concurrentRequestLimit, Date limitExceededDate) {
        try {
            Customer customer = Customer.load(customerId)
            Customer recipient = getMessageRecipient(customer)
            if (!recipient) return

            final String mailSubject = "⚠️ Identificamos alto uso de requisições concorrentes nas suas chamadas de API"
            final String mailTitle = "Identificamos alto uso de requisições concorrentes nas suas chamadas de API"

            Map model = [concurrentRequestLimit: concurrentRequestLimit, limitExceededDate: limitExceededDate]
            Boolean showChildAccount = recipient != customer
            if (showChildAccount) model.childAccount = customer

            String mailBody = messageService.buildTemplate("/mailTemplate/api/burstLimit/limitExceeded", model)

            sendDefaultTemplate(recipient, recipient.email, null, mailSubject, mailBody, [emailTitle: mailTitle, bypassNotificationConstraints: true])
        } catch (Exception exception) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.notifyAboutBurstLimitExceeded >> Erro ao notificar o cliente[${customerId}] sobre o limite de requisições concorrentes atingido.", exception)
        }
    }

    public void sendChargebackRequested(Chargeback chargeback, Map cancelledCheckouts) {
        String emailSubject = "Recebido chargeback da cobrança no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(chargeback.value)} do cliente ${chargeback.getCustomerAccount().name}"
        String emailBody = messageService.buildTemplate("/mailTemplate/chargebackRequested", [chargeback: chargeback, cancelledCheckouts: cancelledCheckouts])

        sendDefaultTemplate(chargeback.customer, chargeback.customer.email, [grailsApplication.config.asaas.chargebackAnalisysTeam.email], emailSubject, emailBody, [mailReplyTo: grailsApplication.config.asaas.chargebackAnalisysTeam.email])
    }

    public void sendChargebackDone(Chargeback chargeback) {
        String emailSubject = "Chargeback da cobrança do cliente ${chargeback.getCustomerAccount().name} efetivado"
        String emailBody = messageService.buildTemplate("/mailTemplate/chargebackDone", [chargeback: chargeback])

        sendDefaultTemplate(chargeback.customer, chargeback.customer.email, null, emailSubject, emailBody, [:])
    }

    public void notifyCustomerAboutSupportAttendance(Customer customer, String supportProtocolProtocol, Date supportAttendanceDateCreated) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = "Asaas - Protocolo de Atendimento: ${supportProtocolProtocol}"
        String emailBody = messageService.buildTemplate("/mailTemplate/supportattendance/supportAttendance", [supportAttendanceDateCreated: supportAttendanceDateCreated, supportProtocolProtocol: supportProtocolProtocol])

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    public void notifyCustomerAboutInstantTextMessageInvalidMessageRecipient(Customer customer, Date failDate) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = "Sua notificação do dia ${ CustomDateUtils.formatDate(failDate) } não foi entregue"
        String emailBody = messageService.buildTemplate(
            "/mailTemplate/notifyCustomerAboutWhatsappDeliveryFail",
            [failDate: failDate]
        )

        sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
    }

    private void sendDefaultTemplate(Customer customer, String toEmail, List<String> bccMails, String subject, String body, Map options) {
        if (!options.bypassNotificationConstraints && !canSendMessage(customer)) return

        String recipientName = customer.getProviderName()
        messageService.sendDefaultTemplate(grailsApplication.config.asaas.sender, toEmail, bccMails, recipientName, subject, body, options)
    }

    private void sendDocumentAnalysisEmail(Customer customer, DocumentAnalysis documentAnalysis) {
        try {
            RejectedDocumentsBuilder rejectedDocuments = new RejectedDocumentsBuilder(documentAnalysis)

            String emailSubject = grailsApplication.config.asaas.message.document.analysis.emailSubject
            String emailBody = messageService.buildTemplate("/mailTemplate/sendCustomerDocumentAnalysis",
                                                [documentAnalysis: documentAnalysis,
                                                rejectedDocuments: rejectedDocuments.build()])

            if (documentAnalysis.documentRejectType?.isDocumentsNotSent()) {
                emailBody += messageService.buildMarkupToTrackEmailView(grailsLinkGenerator.link(controller: "documentAnalysis", action: "trackDocumentAnalysisEmailVisualization", params: [customerId: documentAnalysis.customer.id], absolute: true))
            }

            sendDefaultTemplate(customer, customer.email, null, emailSubject, emailBody, [:])
        } catch (Exception exception) {
            AsaasLogger.error("CustomerMessageService.sendDocumentAnalysisEmail ${customer.id}", exception)
        }
    }

    private void sendDocumentAnalysisSms(Customer customer, DocumentAnalysis documentAnalysis) {
        if (documentAnalysis.status.isApproved()) return

        String smsMessage = Utils.getMessageProperty("documentAnalysis.documentRejectType.${documentAnalysis.documentRejectType}.sms")

        sendSmsText(customer, smsMessage, customer.mobilePhone, false)
    }

    private void sendInvoiceWasViewedForProvider(Payment payment) {
		String emailBody = messageService.buildTemplate("/mailTemplate/sendInvoiceWasViewedForProvider", [payment: payment])
        String dateTimeFormatted = CustomDateUtils.formatDateTime(new Date())
		String emailSubject = "Cobrança visualizada de ${ payment.customerAccount.name } em ${ dateTimeFormatted }"
		String emailTitle = "Cobrança visualizada"

        sendDefaultTemplate(payment.provider, payment.provider.email, null, emailSubject, emailBody, [emailTitle: emailTitle])
	}

    private void sendInvoiceWasViewedSmsText(Payment payment) {
        String customerAccountName = payment.customerAccount.name
        String paymentValueFormatted = FormUtils.formatCurrencyWithMonetarySymbol(payment.value)
        String dateTimeFormatted = CustomDateUtils.formatDateTime(new Date())

		String smsText = Utils.getMessageProperty("sms.payment.invoiceViewed", [customerAccountName, paymentValueFormatted, dateTimeFormatted])

		while (smsText.length() > SmsSender.MAX_CHARS) {
			customerAccountName = customerAccountName.substring(0, customerAccountName.length() - 1)
			smsText = Utils.getMessageProperty("sms.payment.invoiceViewed", [customerAccountName, paymentValueFormatted, dateTimeFormatted])
		}

        if (!canSendMessage(payment.provider)) return

        asyncSmsMessageService.save(smsText, payment.provider.getPublicInfoConfig()?.smsFrom, payment.provider.mobilePhone, SmsPriority.LOW, AsyncSmsType.REGULAR)
	}

    private Customer getMessageRecipient(Customer customer) {
        if (!CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)) return customer
        if (customer.accountOwner && !CustomerParameter.getValue(customer.accountOwner, CustomerParameterName.WHITE_LABEL)) return customer.accountOwner

        return null
    }
}

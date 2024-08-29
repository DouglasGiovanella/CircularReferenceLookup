package com.asaas.service.message

import com.asaas.api.ApiAsaasFileParser
import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.bankaccountinfo.BaseBankAccount
import com.asaas.bill.returnfile.BillPaymentReturnFileItemStatus
import com.asaas.customer.CustomerInfoFormatter
import com.asaas.domain.Referral
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.bacenjud.BacenJudLock
import com.asaas.domain.bank.Bank
import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillPaymentBatchFile
import com.asaas.domain.bill.BillPaymentReturnFile
import com.asaas.domain.bill.BillPaymentReturnFileItem
import com.asaas.domain.billinginfo.CreditCardInfoCde
import com.asaas.domain.boleto.BoletoChangeInfoRequest
import com.asaas.domain.ccs.CcsRelationshipDetailResponseFile
import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.chargeback.ChargebackDispute
import com.asaas.domain.chargeback.ChargebackDisputeDocument
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.installment.Installment
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.netpromoterscore.NetPromoterScore
import com.asaas.domain.payment.BankSlipFee
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentConfirmRequestGroup
import com.asaas.domain.payment.ReceivedPaymentBatchFile
import com.asaas.domain.paymentdepositreceipt.PaymentDepositReceipt
import com.asaas.domain.pix.pixtransactionrequest.PixTransactionRequest
import com.asaas.domain.postalservice.PaymentPostalServiceBatch
import com.asaas.domain.receivableanticipationpartner.ReceivableAnticipationPartnerAcquisition
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.domain.sdnentry.SDNEntry
import com.asaas.domain.sdnentry.SDNEntryOccurency
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.transferbatchfile.TransferBatchFile
import com.asaas.domain.user.User
import com.asaas.email.AsaasMailMessageType
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.payment.BankSlipFeeVO
import com.asaas.receipt.ReceiptEmailVO
import com.asaas.refundrequest.RefundRequestStatus
import com.asaas.status.Status
import com.asaas.user.UserUtils
import com.asaas.utils.AbTestUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils

import grails.gsp.PageRenderer
import grails.transaction.Transactional

import groovy.text.SimpleTemplateEngine
import groovy.xml.MarkupBuilder

@Transactional
class MessageService {

	PageRenderer groovyPageRenderer
	def asynchronousMailService
	def grailsApplication
	def grailsLinkGenerator
    def notificationRequestParameterService
    def relevantMailHistoryService

    public void sendDefaultTemplate(String fromEmail, Customer customer, String subject, String body) {
        sendDefaultTemplate(fromEmail, customer.email, getRecipientName(customer), subject, body)
    }

    public void sendFormalDefaultTemplate(String fromEmail, Customer customer, String subject, String body) {
        sendDefaultTemplate(fromEmail, customer.email, null, getRecipientName(customer), subject, body, [isFormal: true])
    }

    public void sendDefaultTemplate(String fromEmail, String toEmail, String recipientName, String subject, String body) {
        sendDefaultTemplate(fromEmail, toEmail, null, recipientName, subject, body, [:])
    }

    public void sendDefaultTemplate(String fromEmail, String toEmail, List<String> bccMails, String recipientName, String subject, String body, Map options) {
        String defaultEmail = buildDefaultTemplate(recipientName, subject, body, options.emailTitle, options.isFormal.asBoolean())

        send(fromEmail, toEmail, bccMails, subject, defaultEmail, true, options)

        Boolean isRelevantMail = options.isRelevantMail.asBoolean()
        if (isRelevantMail) {
            Customer customer = options.customer
            if (customer.asBoolean()) {
                String emailBodyToSaveHistory = options.emailBodyToSaveHistory ?: body
                relevantMailHistoryService.save(customer, fromEmail, toEmail, null, subject, emailBodyToSaveHistory, AsaasMailMessageType.GENERAL)
            } else {
                AsaasLogger.warn("MessageService.sendDefaultTemplate >> Método utilizado de forma incorreta: parâmetro options.customer faltante.")
            }
        }
    }

    public String buildDefaultTemplate(String recipientName, String subject, String body, String emailTitle) {
        return buildDefaultTemplate(recipientName, subject, body, emailTitle, false)
    }

    public String buildDefaultTemplate(String recipientName, String subject, String body, String emailTitle, Boolean isFormal) {
        Map defaultTemplateParameters = [:]
        defaultTemplateParameters.put("recipientName", recipientName)
        defaultTemplateParameters.put("mailSubject", subject)
        defaultTemplateParameters.put("mailTitle", emailTitle)
        defaultTemplateParameters.put("body", body)
        defaultTemplateParameters.put("isFormal", isFormal)

        String defaultEmail = buildTemplate("/mailTemplate/defaultTemplate", defaultTemplateParameters)
        return defaultEmail
    }

	public String buildTemplate(String templatePath, Map binding) {
		return groovyPageRenderer.render(template: templatePath, model: binding)
	}

	public Boolean send(String mailFrom, String mailTo, List<String> bccMails, String mailSubject, String mailBody, Boolean isHtml) {
		return send(mailFrom, mailTo, bccMails, mailSubject, mailBody, isHtml, [:])
	}

	public Boolean send(String mailFrom, String mailTo, List<String> bccMails, String mailSubject, String mailBody, Boolean isHtml, Map options) {
		if (!mailBody) mailBody = " "

		try {
            if (AsaasEnvironment.isDevelopment()) {
                if (!grailsApplication.config.grails.mail.username) throw new RuntimeException("Configure o seu envio de email conforme o livro de elite!")

                String prefix = "[TESTE de ${grailsApplication.config.grails.mail.username}] - "
                mailSubject = prefix + mailSubject

                if (!mailTo.contains("@asaas.com") || bccMails.any { !it.contains("@asaas.com") }) throw new RuntimeException("O ambiente local so pode/deve enviar emails para enderecos internos!")
            }

			asynchronousMailService.sendMail {
                if (options.multipart) multipart Boolean.valueOf(options.multipart)

                if (options.priority) priority options.priority

                if (options.delete) delete options.delete

                from mailFrom

                if (options.mailReplyTo) replyTo options.mailReplyTo

                to mailTo
                if (bccMails) bcc bccMails

                subject mailSubject

                if (isHtml)
					html mailBody
				else
					body mailBody

                if (options.attachment || options.attachmentBytes) {
                    AsaasLogger.warn("[MessageService Attachment] Uso incorreto de attachmentList do subject: ${mailSubject}")

                    String mimeType = FileUtils.buildMimeTypeFromFileExtension(FileUtils.getFileExtension(options.attachmentName ?: options.attachment.toString()))
                    attachBytes options.attachmentName ?: options.attachment.toString(), mimeType, options.attachmentBytes ?: options.attachment.readBytes()
                }

                if (options.attachmentList) {
                    for (Map fileOptions in options.attachmentList) {
                        String mimeType = FileUtils.buildMimeTypeFromFileExtension(FileUtils.getFileExtension(fileOptions.attachmentName))
                        attachBytes fileOptions.attachmentName, mimeType, fileOptions.attachmentBytes
                    }
                }
			}
        } catch (Exception e) {
            AsaasLogger.error("MessageService Erro ao enviar com subject ${mailSubject} email para ${mailTo} ", e)
            return false
        }

        return true
	}

    public String getRecipientName(Customer customer) {
        String recipientName = customer.isLegalPerson() ? customer.company : customer.name
        return recipientName ?: ""
    }

	public void sendReportProblem(Map params, Payment payment) {
    	try {
    		String emailBody = makeTemplate(grailsApplication.config.asaas.message.paymentInvoice.reportProblem.emailBody,
											[invoiceId: payment.getInvoiceNumber(),
											 problemForm:  params.itemReportProblem,
											 observationsForm: params.observation ?: "Não informou",
											 nameForm: params.name,
											 emailForm: params.email ?: "Não informou",
											 phoneForm: params.phone ?: "Não informou",
											 value: payment.value,
											 dueDate: CustomDateUtils.fromDate(payment.dueDate),
											 nameCustomerAccount: payment.customerAccount.company ?: payment.customerAccount.name,
											 emailCustomerAccount: payment.customerAccount.email ?: "Não informou",
											 nameProvider: payment.provider.company ?: payment.provider.name,
											 emailProvider: payment.provider.email])

    		List<String> bccList = [payment.provider.accountManager.internalEmail]

			asynchronousMailService.sendMail {
				to grailsApplication.config.asaas.risk.email.external
				from grailsApplication.config.asaas.sender
				subject grailsApplication.config.asaas.message.paymentInvoice.reportProblem.emailSubject
				bcc bccList
				html emailBody
			}
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.sendReportProblem >> Erro ao enviar o relatório de problema. [paymentId: ${payment?.id}]", exception)
		}
    }

  	public void sendReceipt(ReceiptEmailVO receiptEmailVO) {
		try {
			String emailBody = buildTemplate("/mailTemplate/receipt/receipt", [receiptEmailVO : receiptEmailVO])
			if (receiptEmailVO.receiptReceiverType.isThirdParty() && !receiptEmailVO.pixTransaction) {
				emailBody += buildMarkupToTrackEmailView(grailsLinkGenerator.link(controller: "transactionReceipt", action: "trackEmailVisualization", params: [publicId: receiptEmailVO.getTransactionReceiptPublicId(), recipient: receiptEmailVO.recipient], absolute: true))
			}

            send(grailsApplication.config.asaas.sender, receiptEmailVO.recipient, null, receiptEmailVO.subject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("${this.class.simpleName}.sendReceipt >> Erro ao enviar comprovante via email", exception)
        }
	}

    public void notifyReceivedPaymentBatchFileCreated(ReceivedPaymentBatchFile batchFile) {
    	String emailSubject = "Arquivo de pagamentos transferidos gerado"
		String emailBody = buildTemplate("/mailTemplate/receivedPaymentBatchFileCreated", [batchFile: batchFile])

		send(grailsApplication.config.asaas.sender, batchFile.creditTransferRequest.provider.email, null, emailSubject, emailBody, true)
    }

	public void sendCreditTransferRequestFailed(CreditTransferRequest creditTransferRequest, String reasons) {
		try {
            String body = buildTemplate("/mailTemplate/creditTransferRequestFailed", [creditTransferRequest: creditTransferRequest, bankAccountInfo: creditTransferRequest.bankAccountInfo, reasons: reasons])

            sendDefaultTemplate(buildAccountManagerSender(creditTransferRequest.provider.accountManager), creditTransferRequest.provider.email, [creditTransferRequest.provider.accountManager.internalEmail], getRecipientName(creditTransferRequest.provider), "Sua transferência falhou", body, [:])
		} catch (Exception exception) {
			AsaasLogger.error("Erro ao enviar email creditTransferRequestFailed.", exception)
		}
	}

    public void sendResultBankAccountInfoUpdateRequest(BaseBankAccount bankAccountInfo) {
		try {
			String action = bankAccountInfo.status == Status.APPROVED ? "aprovada" : "reprovada"

			String subject = makeTemplate(AsaasApplicationHolder.config.asaas.message.bankaccountinfoupdaterequest.update.emailSubject, [action: action])
			String body = buildTemplate("/mailTemplate/sendResultBankAccountInfoUpdateRequest",
												[name: bankAccountInfo.customer.buildTradingName(),
												 denialReason: bankAccountInfo.denialReason,
												 observations: bankAccountInfo.observations,
												 accountManager: bankAccountInfo.customer.accountManager,
												 action: action])

            send(buildAccountManagerSender(bankAccountInfo.customer.accountManager), bankAccountInfo.customer.email, null, subject, body, true)
		} catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email ResultBankAccountInfoUpdateRequest.", exception)
		}
	}

	public void sendSupportMail(User user, Map params) {
    	try {
    		String accountManagerEmail = user.customer.accountManager?.user?.username ?: "contato@asaas.com.br"

    		String body = grailsApplication.config.asaas.message.supportMail.emailBody
			body = new SimpleTemplateEngine().createTemplate(body).make([email: user.username,
																		 message: params.message])
			asynchronousMailService.sendMail {
				to accountManagerEmail
				from grailsApplication.config.asaas.sender
				subject grailsApplication.config.asaas.message.supportMail.emailSubject
				html body
			}
		} catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email SupportMail.", exception)
		}
    }

    public void sendSetPasswordMailForNewInternalUser(String url, User user) {
        try {
            String emailBody = buildTemplate("/mailTemplate/notificationToNewInternalUserAboutSetPasswordMail", [url: url, email: user.email])

            sendDefaultTemplate(grailsApplication.config.asaas.sender, user.email, user.name ?: user.username, "Defina sua senha", emailBody)
        } catch (Exception exception) {
            AsaasLogger.error("MessageService.sendSetPasswordMailForNewInternalUser >> Erro ao enviar email de alteração de primeira senha para usuários administrativos. Email: ${user.email} Nome: ${user.name}", exception)
        }
    }

    public void sendReferralInvitation(Referral referral) {
        try {
            String emailSubject = Utils.getMessageProperty("referral.email.subject.main", [FormUtils.formatCurrencyWithMonetarySymbol(grailsApplication.config.asaas.referral.promotionalCode.discountValue.invited)])
            String templateReferralInvitation = "/mailTemplate/referral/sendReferralInvitation"
            String fromEmail = buildAccountManagerSenderToReferral(referral.invitedByCustomer)

            Map params = [
                invitorName: referral.getInvitedByName(),
                invitationUrl: grailsLinkGenerator.link(controller: "referral", action: "invitation", id: referral.token, absolute: true),
                unsubscribeUrl: grailsLinkGenerator.link(controller: "referral", action: "unsubscribe", id: referral.token, absolute: true),
                discountValue: FormUtils.formatCurrencyWithMonetarySymbol(grailsApplication.config.asaas.referral.promotionalCode.discountValue.invited)
            ]

            String emailBody = buildTemplate(templateReferralInvitation, params)
            emailBody += buildMarkupToTrackEmailView(grailsLinkGenerator.link(controller: "referral", action: "trackInvitationEmailVisualization", id: referral.id, absolute: true))

            asynchronousMailService.sendMail {
                to referral.invitedEmail
                from fromEmail
                subject emailSubject
                html emailBody
            }
        } catch (Exception e) {
            AsaasLogger.error("Erro ao enviar email de convite do referral.", e)
        }
    }

    public void sendReferralReminder(Referral referral) {
        try {
            String emailSubject = Utils.getMessageProperty("referral.email.subject.reminder")
            String templateReferralReminder = "/mailTemplate/referral/sendReferralInvitation"
            String fromEmail = buildAccountManagerSenderToReferral(referral.invitedByCustomer)

            Map params = [
                invitorName: referral.getInvitedByName(),
                invitationUrl: grailsLinkGenerator.link(controller: "referral", action: "invitation", id: referral.token, absolute: true),
                unsubscribeUrl: grailsLinkGenerator.link(controller: "referral", action: "unsubscribe", id: referral.token, absolute: true),
                discountValue: FormUtils.formatCurrencyWithMonetarySymbol(grailsApplication.config.asaas.referral.promotionalCode.discountValue.invited),
                isReminder: true
            ]

            String emailBody = buildTemplate(templateReferralReminder, params)
            emailBody += buildMarkupToTrackEmailView(grailsLinkGenerator.link(controller: "referral", action: "trackInvitationEmailVisualization", id: referral.id, absolute: true))

            asynchronousMailService.sendMail {
                to referral.invitedEmail
                from fromEmail
                subject emailSubject
                html emailBody
            }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email de lembrete do convite do referral.", exception)
        }
    }

	public void notifyIndicatorAboutReferralRegistration(Referral referral) {
		try {
			String emailSubject = makeTemplate(grailsApplication.config.asaas.message.invitedWasRegistered.emailSubject, [invitedName: referral.invitedCustomer.providerName])
            String emailContent = ""
            Boolean hasReferralPromotionVariantB = AbTestUtils.hasReferralPromotionVariantB(referral.invitedByCustomer)

            if (hasReferralPromotionVariantB) {
                emailContent = "sua premiação em crédito promocional para abater em taxas."
            } else {
                BankSlipFeeVO bankSlipFeeVO = new BankSlipFeeVO(referral.invitedByCustomer)
                if (!bankSlipFeeVO.discountValue) {
                    emailContent += "mais ${BankSlipFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS} meses com a redução de preço de seus boletos."
                } else {
                    emailContent += "mais ${BankSlipFee.DEFAULT_DISCOUNT_PERIOD_IN_MONTHS} meses com a redução de preço de seus boletos de ${FormUtils.formatCurrencyWithMonetarySymbol(bankSlipFeeVO.defaultValue)} para ${FormUtils.formatCurrencyWithMonetarySymbol(bankSlipFeeVO.discountValue)}."
                }
            }

            String emailBody = buildTemplate(
                "/mailTemplate/referral/notifyIndicatorAboutReferralRegistration",
                [
                    providerFirstName: referral.getInvitedByName().split().first(),
                    invitedName: referral.invitedCustomer.providerName,
                    emailContent: emailContent
                ]
            )

			asynchronousMailService.sendMail {
				to referral.invitedByCustomer.email
				from grailsApplication.config.asaas.sender
				subject emailSubject
				html emailBody
			}
		} catch (Exception exception) {
			AsaasLogger.error("MessageService.notifyIndicatorAboutReferralRegistration >> Erro ao notificar o indicador sobre o registro de indicação. [referralId: ${referral?.id}]", exception)
		}
	}

    public void sendTransferOrDepositRejectReasonToCustomerAccountIfPossible(PaymentDepositReceipt paymentDepositReceipt) {
      	if (!AsaasEnvironment.isProduction()) return

        if (!paymentDepositReceipt.payment.customerAccount.email) return

        String body = buildTemplate("/mailTemplate/paymentdepositreceipt/sendTransferOrDepositRejectReasonToCustomerAccount", [paymentDepositReceipt: paymentDepositReceipt])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, paymentDepositReceipt.payment.customerAccount.email, paymentDepositReceipt.payment.customerAccount.name, "Seu comprovante de pagamento precisa de atenção - cobrança: ${paymentDepositReceipt.payment.getInvoiceNumber()}", body)
    }

    public void sendTransferOrDepositRejectReasonToCustomer(PaymentDepositReceipt paymentDepositReceipt) {
        if (!AsaasEnvironment.isProduction()) return

        String body = buildTemplate("/mailTemplate/paymentdepositreceipt/sendTransferOrDepositRejectReasonToCustomer", [paymentDepositReceipt: paymentDepositReceipt])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, paymentDepositReceipt.payment.provider, "Comprovante de pagamento precisa de atenção - cobrança: ${paymentDepositReceipt.payment.getInvoiceNumber()}", body)
    }

    public void sendAlert(String email, String subject, String body) {
		Payment.withNewTransaction { transaction ->
	 		try {
	 			if (!AsaasEnvironment.isProduction()) return

	 			send(AsaasApplicationHolder.config.asaas.sender, email, null, subject, body, true)
	     	} catch (Exception exception) {
                AsaasLogger.error("MessageService.sendAlert >> Erro ao enviar alerta. [email: ${email}]", exception)
			}
		}
    }

	public void sendPaymentConfirmRequests(PaymentConfirmRequestGroup paymentConfirmRequestGroup) {
		try {
			if (!AsaasEnvironment.isProduction()) return

			String emailBody = buildTemplate("/mailTemplate/sendPaymentConfirmRequests", [paymentConfirmRequestGroup: paymentConfirmRequestGroup])

			List ccMails = [grailsApplication.config.asaas.info.email]

			String subject = "Boletos do ${paymentConfirmRequestGroup.paymentBank.name} estão disponíveis. Quantidade: ${paymentConfirmRequestGroup.paymentConfirmRequests.size()} | Valor: ${paymentConfirmRequestGroup.paymentConfirmRequests*.value.sum()}"

			send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.contasAReceber.email, ccMails, subject, emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.sendPaymentConfirmRequests >> Erro ao enviar solicitação de confirmação de pagamento. [paymentConfirmRequestGroupId: ${paymentConfirmRequestGroup?.id}]", exception)
		}
	}

	public void sendScheduledBillsForToday(List<Bill> listOfBill) {
		try {
			if (!AsaasEnvironment.isProduction()) return

			String emailBody = buildTemplate("/mailTemplate/sendScheduledBillsForToday", [listOfBill: listOfBill])

			List<String> bccMails = [grailsApplication.config.asaas.info.email, grailsApplication.config.asaas.manager.financial.email]

			send(grailsApplication.config.asaas.sender, grailsApplication.config.mail.financeiro, bccMails, grailsApplication.config.asaas.message.sendScheduledBillsForToday.emailSubject, emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.sendScheduledBillsForToday >> Erro ao enviar boletos agendados para hoje", exception)
		}
	}

    public void notifyBatchFileCreated(PaymentPostalServiceBatch paymentPostalServiceBatch) {
		try {
			if (!AsaasEnvironment.isProduction()) return

			String emailBody = buildTemplate("/mailTemplate/batchFileCreated", [paymentPostalServiceBatch: paymentPostalServiceBatch])

			send(AsaasApplicationHolder.config.asaas.sender, AsaasApplicationHolder.config.asaas.info.email, null, "Novo remessa para envio via Correios gerada", emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.notifyBatchFileCreated >> Erro ao notificar a criação do arquivo de lote. [paymentPostalServiceBatchId: ${paymentPostalServiceBatch?.id}]", exception)
		}
	}

	public void notifyProviderAboutPostalServiceBatchInconsistencies(Customer customer, List<Map> paymentsInformation, Integer numberOfPaymentsWithoutInconsistencies, Boolean isBeforeSend) {
		try {
			if (!AsaasEnvironment.isProduction()) return

			String emailBody = buildTemplate("/mailTemplate/postalServiceBatchInconsistencies", [customer: customer, paymentsInformation: paymentsInformation, numberOfPaymentsWithoutInconsistencies: numberOfPaymentsWithoutInconsistencies, isBeforeSend: isBeforeSend])
			String emailSubject = isBeforeSend ? "Inconsistências em cobranças com envio via Correios agendado" : "Algumas de suas cobranças não puderam ser enviadas via Correios."

			send(AsaasApplicationHolder.config.asaas.sender, customer.email, [AsaasApplicationHolder.config.asaas.internalTechnicalSuport.email], emailSubject, emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.notifyProviderAboutPostalServiceBatchInconsistencies >> Erro ao notificar o provedor sobre inconsistências no lote de serviço postal. [customerId: ${customer?.id}]", exception)
		}
	}

	public void notifyProviderAboutPostalServiceBatchSuccess(Customer customer, List<Payment> payments) {
		try {
			if (!AsaasEnvironment.isProduction()) return

			String emailBody = buildTemplate("/mailTemplate/postalServiceBatchSuccess", [customer: customer, payments: payments])

			send(AsaasApplicationHolder.config.asaas.sender, customer.email, [AsaasApplicationHolder.config.asaas.internalTechnicalSuport.email], "Os boletos de seus clientes foram enviados via Correios", emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.notifyProviderAboutPostalServiceBatchSuccess >> Erro ao notificar o provedor sobre o sucesso do lote de serviço postal. [customerId: ${customer?.id}]", exception)
		}
	}

	public void OFACCustomerReport(Customer customer, SDNEntry sdnEntry) {
 		try {
 			String emailSubject = "O fornecedor ${customer.getProviderName()} está presente na lista de suspeitos OFAC"
 			String emailBody = buildTemplate("/mailTemplate/OFACCustomerReport", [customer: customer, sdnEntry: sdnEntry])

			send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.risk.email.internal, ["marlon.franca@asaas.com.br"], emailSubject, emailBody, true)
 		} catch (Exception exception) {
            AsaasLogger.error("MessageService.OFACCustomerReport >> Erro ao enviar relatório de cliente OFAC. [customerId: ${customer.id}]", exception)
 		}
 	}

	 public void OFACListReport(List<SDNEntryOccurency> occurencies) {
 		try {
 			String emailSubject = "Foram encontrados fornecedores presentes na lista de suspeitos OFAC"
 			String emailBody = buildTemplate("/mailTemplate/OFACListReport", [occurencies: occurencies])

			send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.risk.email.internal, ["avisos.seals@asaas.com.br"], emailSubject, emailBody, true)
 		} catch (Exception exception) {
            AsaasLogger.error("MessageService.OFACListReport >> Erro ao enviar relatório da lista OFAC", exception)
 		}
 	}

    public void reportCustomerInUnscList(Customer customer, Map details) {
        String customerName = customer.getProviderName()

        Map model = [
            customerName: customerName,
            customerUrl: grailsLinkGenerator.link(controller: "customerAdminConsole", action: "show", id: customer.id, absolute: true),
            unscSanction: details
        ]

        String emailSubject = "Cliente ${customerName} está presente na lista do Conselho de Segurança das Nações Unidas"
        String emailBody = buildTemplate("/mailTemplate/unscSanction", model)

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.risk.email.internal, ["avisos.seals@asaas.com.br"], emailSubject, emailBody, true)
    }

 	public void sendAccountBalanceReport(Customer customer, Integer year, String reportFilePublicId) {
        String recipientName = getRecipientName(customer)
        String emailSubject = "Informe de Saldo em Conta do Asaas ${year}"
        String emailTitle = "Informe de Saldo em Conta"
        String emailBody = buildTemplate("/mailTemplate/accountBalanceReport", [year: year, reportFilePublicId: reportFilePublicId])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, customer.email, null, recipientName, emailSubject, emailBody, [emailTitle: emailTitle])
    }

    public void sendChargebackDisputeDocumentList(Chargeback chargeback, List<ChargebackDisputeDocument> chargebackDisputeDocumentList) {
        String originDescription = chargeback.payment ? "Cobrança ${chargeback.payment.id}" : "Parcelamento ${chargeback.installment.id}"
        String emailSubject = "Documentação para contestação de chargeback - ${originDescription}"
        String emailBody = buildTemplate("/mailTemplate/chargebackDisputeDocumentsSent", [chargeback: chargeback, customer: chargeback.customer, chargebackDisputeDocumentList: chargebackDisputeDocumentList])

        List<Map> attachmentList = chargebackDisputeDocumentList.collect {
            Map attachment = [:]

            attachment.attachmentName = it.fileName
            attachment.attachmentBytes = it.asaasFile.getFileBytes()

            return attachment
        }

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.chargebackAnalisysTeam.email, null, emailSubject, emailBody, true, [attachmentList: attachmentList, multipart: true])
    }

    public void reportExpiredCreditCardSensitiveDataNotRemoved (List<CreditCardInfoCde> expiredCreditCardList, Long expiredCreditCardCount, Date currentExpiryDate) {
        if (!AsaasEnvironment.isProduction()) return

        String subject = "Relatório de verificação de retenção de dados de cartão de crédito - Inconformidades encontradas"
        String emailBody = buildTemplate("/mailTemplate/expiredCreditCardSensitiveDataNotRemovedReport", [expiredCreditCardList: expiredCreditCardList, expiredCreditCardCount: expiredCreditCardCount, currentExpiryDate: currentExpiryDate, hasMoreRecords: expiredCreditCardCount != expiredCreditCardList.size().toLong()])
        send(grailsApplication.config.asaas.sender, "pci@asaas.com.br", null, subject, emailBody, true)
    }

    public void reportAllExpiredCreditCardSensitiveDataRemoved(Long sensitiveCreditCardDataCount, Date currentExpiryDate) {
        if (!AsaasEnvironment.isProduction()) return

        String subject = "Relatório de verificação de retenção de dados de cartão de crédito - Nenhuma inconformidade"
        String emailBody = buildTemplate("/mailTemplate/allExpiredCreditCardSensitiveDataRemovedReport", [sensitiveCreditCardDataCount: sensitiveCreditCardDataCount, currentExpiryDate: currentExpiryDate])
        send(grailsApplication.config.asaas.sender, "pci@asaas.com.br", null, subject, emailBody, true)
    }

    public void sendSensitiveCreditCardDataRemovalReport(Integer removedDataCount, Integer errorCount) {
        if (!AsaasEnvironment.isProduction()) return

        String subject = "Relatório de remoção de dados de cartão expirados"
        String emailBody = buildTemplate("/mailTemplate/sensitiveCreditCardDataRemovalReport", [removedDataCount: removedDataCount, errorCount: errorCount, totalCount: removedDataCount + errorCount])
        send(grailsApplication.config.asaas.sender, "pci@asaas.com.br", null, subject, emailBody, true)
    }

	public void sendChargebackReversed(Chargeback chargeback) {
        String emailSubject = "Contestação do chargeback da cobrança para o cliente ${chargeback.getCustomerAccount().name} aceita"
        String emailBody = buildTemplate("/mailTemplate/chargebackReversed", [chargeback: chargeback])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, chargeback.customer, emailSubject, emailBody)
    }

	public void sendChargebackDisputeLost(ChargebackDispute chargebackDispute) {
        String emailSubject = "Contestação de chargeback da cobrança para o cliente ${chargebackDispute.chargeback.getCustomerAccount().name} recusada"
		String emailBody = buildTemplate("/mailTemplate/chargebackDisputeLost", [chargeback: chargebackDispute.chargeback, chargebackDispute: chargebackDispute])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, chargebackDispute.chargeback.customer, emailSubject, emailBody)
	}

	public void sendPaidRefundRequest(RefundRequest refundRequest) {
		String emailSubject = "Sua solicitação de estorno foi paga"
		String emailBody = buildTemplate("/mailTemplate/refundrequest/customerAccount/paid", [refundRequest: refundRequest])

		send(grailsApplication.config.asaas.sender, refundRequest.email, null, emailSubject, emailBody, true)
	}

	public void sendRefundRequestTransferErrorToAccountManager(RefundRequest refundRequest, String transferErrorReason) {
		String emailSubject = "Erro no pagamento de retorno"
		String emailBody = buildTemplate("/mailTemplate/refundrequest/accountManager/transferError", [refundRequest: refundRequest, transferErrorReason: transferErrorReason])

		send(grailsApplication.config.asaas.sender, refundRequest.payment.provider.accountManager.internalEmail, null, emailSubject, emailBody, true)
	}

	public void sendRefundRequestTransferErrorToCustomerAccount(RefundRequest refundRequest, String transferErrorReason) {
		String emailSubject = "Seu estorno de ${FormUtils.formatCurrencyWithMonetarySymbol(refundRequest.payment.value)} falhou :(!"
		String emailBody = buildTemplate("/mailTemplate/refundrequest/customerAccount/transferError", [refundRequest: refundRequest, transferErrorReason: transferErrorReason])

		send(grailsApplication.config.asaas.sender, refundRequest.email, null, emailSubject, emailBody, true)
	}

	public void sendBillPaymentBatchFileCreatedOrSent(Long billPaymentBatchFileId, String subject) {
        if (!AsaasEnvironment.isProduction()) return

		BillPaymentBatchFile billPaymentBatchFile = BillPaymentBatchFile.get(billPaymentBatchFileId)

		String emailSubject = subject
		String emailBody = buildTemplate("/mailTemplate/billPaymentBatchFileCreatedOrSent", [billPaymentBatchFile: billPaymentBatchFile])

		send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.mail.operacoesFinanceiras, [grailsApplication.config.asaas.info.email, grailsApplication.config.asaas.financeiro.pague.contas], emailSubject, emailBody, true)
	}

	public void sendBillPaymentReturnFileReport(BillPaymentReturnFile billPaymentReturnFile) {
		Set<BillPaymentReturnFileItem> failedItems = billPaymentReturnFile.items.findAll{ it.returnStatus == BillPaymentReturnFileItemStatus.FAILED }
		Set<BillPaymentReturnFileItem> updatedItems = billPaymentReturnFile.items.findAll { [BillPaymentReturnFileItemStatus.PAID, BillPaymentReturnFileItemStatus.SCHEDULED].contains(it.returnStatus) }

		String emailSubject = "Relatório de processamento de pagamento de contas. Atualizados: ${updatedItems.size()} | Falha: ${failedItems.size()}"
		String emailBody = buildTemplate("/mailTemplate/billPaymentReturnFileProcessReport", [fileName: billPaymentReturnFile.fileName, failedItems: failedItems, updatedItems: updatedItems])

		send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.mail.operacoesFinanceiras, [grailsApplication.config.asaas.info.email, grailsApplication.config.asaas.financeiro.pague.contas], emailSubject, emailBody, true)
	}

	public void sendBillPaymentBatchFileSetAsPaid(BillPaymentBatchFile billPaymentBatchFile) {
		String emailSubject = "Remessa de pagamento de contas marcada como paga com sucesso"
		String emailBody = buildTemplate("/mailTemplate/billPaymentBatchFileSetAsPaid", [billPaymentBatchFile: billPaymentBatchFile])

		send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.mail.operacoesFinanceiras, [grailsApplication.config.asaas.info.email, grailsApplication.config.asaas.financeiro.pague.contas], emailSubject, emailBody, true)
	}

	public void sendBillPaymentReturnFileErrorToCustomer(Bill bill, List<String> billPaymentReturnFileErrorReasons) {
		if (!AsaasEnvironment.isProduction()) return
		String emailSubject = Utils.getMessageProperty("billPaymentReturnFile.processReturnFileError.customer.emailSubject", [FormUtils.formatCurrencyWithMonetarySymbol(bill.value)])
		String emailBody = buildTemplate("/mailTemplate/sendBillPaymentReturnFileErrorToCustomer", [bill: bill, billPaymentReturnFileErrorReasons: billPaymentReturnFileErrorReasons])

		send(AsaasApplicationHolder.config.asaas.support.sender, bill.customer.email, null, emailSubject, emailBody, true)
	}

    public void sendBillRefundToCustomer(Bill bill) {
        String recipientName = getRecipientName(bill.customer)
        String recipientEmail = bill.customer.email
        String emailSubject = "Não foi possível realizar o pagamento no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(bill.value)}"
        String billShowUrl = grailsLinkGenerator.link(controller: "bill", action: "show", id: bill.id, absolute: true)
        String emailBody = buildTemplate("/mailTemplate/bill/refund/emailBody", [ billDueDate: bill.dueDate, billShowUrl: billShowUrl, billValue: bill.value, customer: bill.customer ])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, recipientEmail, null, recipientName, emailSubject, emailBody, [emailTitle: "Pagamento de conta estornado"])
    }

    public void sendBillCancelToCustomer(Bill bill) {
        String recipientName = getRecipientName(bill.customer)
        String recipientEmail = bill.customer.email
        String emailSubject = "Não foi possível realizar o pagamento no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(bill.value)}"
        String billShowUrl = grailsLinkGenerator.link(controller: "bill", action: "show", id: bill.id, absolute: true)
        String emailBody = buildTemplate("/mailTemplate/bill/cancel/emailBody", [ billDueDate: bill.dueDate, billShowUrl: billShowUrl, billValue: bill.value, customer: bill.customer ])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, recipientEmail, null, recipientName, emailSubject, emailBody, [emailTitle: "Pagamento de conta cancelado"])
    }

    public void sendNotificationToCustomerAboutSubscriptionEnding(Subscription subscription, Date lastPaymentDueDate) {
        String recipientName = getRecipientName(subscription.provider)
        String recipientEmail = subscription.provider.email
        String emailTitle = Utils.getMessageProperty("customerAlertNotification.SUBSCRIPTION_ENDING.title")
        String emailSubject = Utils.getMessageProperty("customerAlertNotification.SUBSCRIPTION_ENDING.body", [FormUtils.formatCurrencyWithMonetarySymbol(subscription.value)])
        String emailBody = buildTemplate("/mailTemplate/subscription/provider/emailBody", [subscription: subscription, lastPaymentDueDate: lastPaymentDueDate])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, recipientEmail, null, recipientName, emailSubject, emailBody, [emailTitle: emailTitle])
    }

    public void sendNotificationToCustomerAboutInstallmentEnding(Installment installment) {
        String recipientName = getRecipientName(installment.getProvider())
        String recipientEmail = installment.getProvider().email
        Date paymentDueDate = installment.getNotReceivedPayments().first().dueDate
        String emailTitle = Utils.getMessageProperty("customerAlertNotification.INSTALLMENT_ENDING.title")
        String emailSubject = Utils.getMessageProperty("customerAlertNotification.INSTALLMENT_ENDING.body", [FormUtils.formatCurrencyWithMonetarySymbol(installment.getValue())])
        String emailBody = buildTemplate("/mailTemplate/installment/provider/installmentEnding/emailBody", [installment: installment, paymentDueDate: paymentDueDate])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, recipientEmail, null, recipientName, emailSubject, emailBody, [emailTitle: emailTitle])
    }

	public void sendRefundRequestAnalysisResult(RefundRequest refundRequest) {
		try {
			RefundRequestStatus action
			String emailSubject

			emailSubject = makeTemplate(AsaasApplicationHolder.config.asaas.message.refundRequest.approved.emailSubject, [providerName: refundRequest.payment.provider.buildTradingName()])

			String refundRequestLink = grailsLinkGenerator.link(controller: 'refundRequest', action: 'show', id: refundRequest.publicId, absolute: true)
			String refundRequestCreateLink = grailsLinkGenerator.link(controller: 'refundRequest', action: 'create', id: refundRequest.payment.externalToken, absolute: true)

			String emailBody = buildTemplate("/mailTemplate/refundrequest/customerAccount/analysisResult",
												[name: refundRequest.name,
												denialReason: refundRequest.denialReason,
												accountManager: refundRequest.payment.provider.accountManager,
												value: refundRequest.payment.value,
												providerName: refundRequest.payment.provider.buildTradingName(),
												refundRequestLink: refundRequestLink,
												refundRequestCreateLink: refundRequestCreateLink,
												action: action])

			send(grailsApplication.config.asaas.sender, refundRequest.email, null, emailSubject, emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email RefundRequestAnalysisResult.", exception)
		}
	}

	public Boolean sendNotificationToCustomerAboutInstallmentEndingThisMonth(Customer customer, List<Payment> paymentList) {
        try {
            if (!AsaasEnvironment.isProduction()) return false

			String emailBody = buildTemplate("/mailTemplate/notificationToCustomerAboutInstallmentEndingThisMonth", [customer: customer, paymentList: paymentList])
			String emailSubject = "Alguns de seus parcelamentos terminam este mês"

			return send(grailsApplication.config.asaas.sender, customer.email, null, emailSubject, emailBody, true)
		} catch (Exception e) {
			AsaasLogger.error("MessageService.sendNotificationToCustomerAboutInstallmentEndingThisMonth >> Erro ao enviar notificação", e)
            return false
		}
	}

  	public void sendBlockCustomerAlert(Customer customer) {
		if (!AsaasEnvironment.isProduction()) return
		try {
			String emailSubject = "Bloqueio de cadastro"
			String emailBody = buildTemplate("/mailTemplate/blockCustomerAlert", [customer: customer])

			send(grailsApplication.config.asaas.sender, customer.email, null, emailSubject, emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.sendBlockCustomerAlert >> Erro ao enviar alerta de bloqueio de cadastro. [customerId: ${customer?.id}]", exception)
		}
	}

	public void sendBlockCheckoutAlert(Customer customer, String reason) {
		if (!AsaasEnvironment.isProduction()) return
		try {
			String emailSubject = "Saques bloqueados temporariamente"
			String emailBody = buildTemplate("/mailTemplate/blockCheckoutAlert", [customer: customer, reason: reason])

			send(grailsApplication.config.asaas.sender, customer.email, null, emailSubject, emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.sendBlockCheckoutAlert >> Erro ao enviar alerta de bloqueio de saques. [customerId: ${customer?.id}]", exception)
		}
	}

	public void sendNotSetAsPaidBillPaymentBatchFileAlert(Long billPaymentBatchFileId) {
		if (!AsaasEnvironment.isProduction()) return
 		try {
 			String emailSubject = "Atenção! Remessa de pagamento de contas [${billPaymentBatchFileId}] ainda não foi autorizada no banco"
			String emailBody = buildTemplate("/mailTemplate/billPaymentBatchFileNotSetAsPaidAlert", [billPaymentBatchFileId: billPaymentBatchFileId])

            send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.mail.operacoesFinanceiras, [grailsApplication.config.asaas.manager.financial.email, grailsApplication.config.asaas.financialLeader.email], emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email de alerta de remessa de pagamento de contas não autorizada no banco. [billPaymentBatchFileId: ${billPaymentBatchFileId}]", exception)
		}
	}

    public void sendNotDeletedPaymentsAlertIfSysAdmin(Customer customer, List<Long> paymentIdList, User notifyUser) {
        if (!UserUtils.isAsaasTeam(notifyUser.username)) return

        String emailSubject = "Algumas cobranças do cliente ${customer.getProviderName()} não foram excluídas"
        String emailBody = "As cobranças a seguir do cliente ${customer.getProviderName()} [${customer.id}] não foram deletadas corretamente. Por favor, contate o BackOffice para realizar as exclusões destas cobranças: <br>${paymentIdList.join(', ')}"
        send(grailsApplication.config.asaas.sender, notifyUser.email, null, emailSubject, emailBody, true)
    }

	public void notifyAsaasTeamAboutLowScoreOrRelevantObservations(NetPromoterScore netPromoterScore) {
		try {
			if (!AsaasEnvironment.isProduction()) return

            List args = [netPromoterScore.customer.id, netPromoterScore.customer.getProviderName(), netPromoterScore.score, netPromoterScore.customer.accountManager.name]
			String emailSubject = Utils.getMessageProperty("netPromoterScore.notificationAboutNpsAnswered.emailSubject", args)
			String emailBody = buildTemplate("/mailTemplate/notificationToAsaasTeamAboutAnsweredNps", [netPromoterScore: netPromoterScore])

			send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.nps.email, [grailsApplication.config.asaas.productTeam.email], emailSubject, emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email sobre o NPS: ${netPromoterScore.id} respondido pelo cliente ${netPromoterScore.customer.id} - ${netPromoterScore.customer.getProviderName()}", exception)
		}
	}

	public void sendRefundRequestLinkToCustomerAccountCreatedByProvider(RefundRequest refundRequest) {
		String emailSubject = "O estorno da fatura ${refundRequest.payment.getInvoiceNumber()} no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(refundRequest.payment.value)} aguarda seus dados para ser realizado"
		String emailBody = buildTemplate("/mailTemplate/refundrequest/customerAccount/createdByProvider", [refundRequest: refundRequest])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, refundRequest.email, refundRequest.name, emailSubject, emailBody)
	}

	public void sendRefundRequestLinkToCustomerAccountCreatedByAdmin(RefundRequest refundRequest) {
		String emailSubject = "O estorno da fatura ${refundRequest.payment.getInvoiceNumber()} no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(refundRequest.payment.value)} aguarda seus dados para ser realizado"
		String emailBody = buildTemplate("/mailTemplate/refundrequest/customerAccount/createdByAdmin", [refundRequest: refundRequest])

        sendDefaultTemplate(grailsApplication.config.asaas.sender, refundRequest.email, refundRequest.name, emailSubject, emailBody)
	}

	public void notifyCustomerAccountRefundRequestDenied(RefundRequest refundRequest, String denialReason) {
		try {
			String emailSubject = "Sua solicitação de estorno precisa de atenção"
			String emailBody = buildTemplate("/mailTemplate/refundrequest/customerAccount/denied", [refundRequest: refundRequest, reason: denialReason])

			send(grailsApplication.config.asaas.sender, refundRequest?.email, null, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email notificando que o estorno  ${refundRequest.id} foi negado para o cliente ${refundRequest?.payment?.customerAccount?.id}", exception)
        }
	}

	public void notifyProviderWhenRefundRequestExpire(RefundRequest refundRequest) {
		try {
			String emailSubject = "Sua solicitação de estorno expirou"
			String emailBody = buildTemplate("/mailTemplate/refundrequest/provider/expired", [refundRequest: refundRequest])

			send(grailsApplication.config.asaas.sender, refundRequest?.payment?.provider?.email, null, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email notificando que o estorno  ${refundRequest.id} não foi interagido pelo cliente para o fornecedor ${refundRequest?.payment?.provider?.id}", exception)
        }
	}

	public void notifyCustomerAccountWhenRefundRequestExpire(RefundRequest refundRequest) {
		try {
			String emailSubject = "Sua solicitação de estorno expirou"
			String emailBody = buildTemplate("/mailTemplate/refundrequest/customerAccount/expired", [refundRequest: refundRequest])

			send(grailsApplication.config.asaas.sender, refundRequest?.email, null, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email notificando que o estorno  ${refundRequest.id} não foi interagido pelo cliente para o cliente ${refundRequest?.payment?.customerAccount?.id}", exception)
        }
	}

	public void notifyAsaasTeamAboutAndroidAppRatingOrRelevantObservations(Customer customer, Integer rating, String observations) {
		try {
			if (!AsaasEnvironment.isProduction()) return

            List args = [customer.id, customer.getProviderName(), rating, customer.accountManager.name]
			String emailSubject = Utils.getMessageProperty("mobile.android.rating.emailSubject", args)
			String emailBody = buildTemplate("/mailTemplate/notificationToAsaasTeamAboutAndroidAppRating", [customer: customer, rating: rating, observations: observations])

            List<String> ccEmails = [grailsApplication.config.asaas.ceo.email, grailsApplication.config.asaas.productTeam.email, grailsApplication.config.asaas.qualityTeam.researches.email]

            send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.customerSuccessLeaders.email, ccEmails, emailSubject, emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email sobre a Avaliação do App Android respondido pelo cliente ${customer.id} - ${customer.getProviderName()}", exception)
		}
	}

    public void notifyCcsGroupAboutPendingManualCancel() {
        try {
            if (!AsaasEnvironment.isProduction()) return

            String emailSubject = "Cancelamento manual pendente."
            String emailBody = "Verifique os cancelamentos manuais pendentes, executar preferencialmente até as 17:00 de hoje ${CustomDateUtils.fromDate(new Date(), 'dd/MM/yyyy')}"

            emailBody += "Link: ${grailsLinkGenerator.link(controller: 'bacenJudManualCancelLock', action: 'index', absolute: true)}"

            send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.financialSettlement.email, null, emailSubject, emailBody, true)
        } catch (Exception exception) {
			AsaasLogger.error("MessageService.notifyCcsGroupAboutPendingManualCancel >> Erro ao enviar email sobre solitação de cancelamento manual", exception)
            throw exception
        }
    }

    public void notifyExpiredManualTransfer(List<Long> bacenJudTransferRequestIdList) {
        try {
            if (!AsaasEnvironment.isProduction()) return

            String emailSubject = "Transferência manual expirada"
            String emailBody = "As transferências manuais solicitadas não foram realizadas dentro do prazo estabelecido. <br>" +
                "Quantidade de transferências manuais afetadas: (${bacenJudTransferRequestIdList.size()}) <br>" +
                "Link para ver as transferências manuais expiradas: ${grailsLinkGenerator.link(controller: 'bacenJudTransferRequest', action: 'list', params: [status: 'ERROR'], absolute: true)}"

            List<String> bccMails = grailsApplication.config.asaas.devTeam.regulatorio.email

            send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.financialSettlement.email, bccMails, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("MessageService.notifyExpiredManualTransfer >> Erro ao enviar email sobre transferência manual expirada", exception)
        }
    }

    public void notifyJudicialLockRequestToAsaas(BacenJudLock lock) {
        try {
            if (!AsaasEnvironment.isProduction()) return

            String requestType = lock.fileLockItem.canceledOrder ? "Cancelamento de bloqueio" : "Solicitação de bloqueio"
            String receivedType = lock.fileLockItem.canceledOrder ? "recebido um cancelamento de bloqueio" : "recebida uma solicitação de bloqueio"

            String emailSubject = "${requestType} judicial para o CNPJ do Asaas."
            String commonBody = "Foi ${receivedType} judicial para o próprio Asaas. <br>" +
                "Oficio de protocolo: ${lock.fileLockItem.formatProtocolKey()} <br>" +
                "Processo: ${lock.fileLockItem.judicialProcessNumber} <br>" +
                "Valor solicitado: ${lock.fileLockItem.lockValue} <br>"

            String additionalBody = lock.fileLockItem.canceledOrder ? "" : "Agora é necessário analisar o processo e verificar a possibilidade de manifestação."

            String emailBody = commonBody + additionalBody

            List<String> bccMails = grailsApplication.config.asaas.devTeam.regulatorio.email

            send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.juridico.email, bccMails, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("MessageService.notifyJudicialLockRequestToAsaas >> Erro ao enviar email sobre solicitação de bloqueio judicial.", exception)        }
    }

    public void notifyAboutRefundCustomerCreditCardPaymentsInBatch(Customer customer, User asaasUser) {
        String customerName = customer.getProviderName()

        String emailSubject = "Processamos sua solicitação de estorno de cobraças por cartão de crédito."
        String emailBody = "Foi finalizado o processo de estorno das cobranças do cliente ${customerName} de ID: ${customer.id}."

        sendDefaultTemplate(grailsApplication.config.asaas.sender, asaasUser.username, asaasUser.username, emailSubject, emailBody)
    }

    public void sendAuthorizedInvoiceToCustomerAccount(Invoice invoice) {
        try {
            if (!invoice.customerAccount.email) return

            String templatePath = "/notificationTemplates/payment/customer/invoiceAuthorized/"
            Map parameters = buildInvoiceAuthorizedParameters(invoice, templatePath)
            String emailBody = buildTemplate(templatePath + "emailBody", parameters)

            send(grailsApplication.config.asaas.sender, invoice.customerAccount.email, null, parameters.mailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar nota fiscal [${invoice.id}] para o pagador", exception)
        }
    }

    public void notifyCcsGroupAboutManualUnlock(Map params) {
        try {
            if (!AsaasEnvironment.isProduction()) return

            String emailSubject = "Existe um desbloqueio manual pendente"
            String emailBody = "Verifique os desbloqueios manuais pendentes, executar preferencialmente até as 17:00 de hoje ${CustomDateUtils.fromDate(new Date(), 'dd/MM/yyyy')}\""

            emailBody += "Link: ${grailsLinkGenerator.link(controller: 'bacenJudUnlockManual', action: 'index', params: params, absolute: true)}"

            send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.financialSettlement.email, null, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar email sobre solitação de desbloqueio manual", exception)
            throw exception
        }
    }

    public void sendVortxDailyBalanceRequired(List<ReceivableAnticipationPartnerAcquisition> receivableAnticipationPartnerAcquisitionList) {
        try {
            if (!AsaasEnvironment.isProduction()) return

            List<String> ccMails = ["fabio.lira@solisinvestimentos.com.br", grailsApplication.config.asaas.ceo.email, grailsApplication.config.asaas.devTeamLeader.email, grailsApplication.config.asaas.devTeam.warning.vortx.email]

            String subject = "FIDC Asaas - Total diário de cessões"
            String emailBody

            if (receivableAnticipationPartnerAcquisitionList) {
                BigDecimal acquisitionTotalValue = receivableAnticipationPartnerAcquisitionList*.getValueWithPartnerFee().sum()
                BigDecimal bankSlipTotalValue = receivableAnticipationPartnerAcquisitionList.findAll { it.receivableAnticipation.isBankSlip() }*.getValueWithPartnerFee().sum() ?: 0
                BigDecimal pixTotalValue = receivableAnticipationPartnerAcquisitionList.findAll { it.receivableAnticipation.billingType.isPix() }*.getValueWithPartnerFee().sum() ?: 0
                BigDecimal creditCardTotalValue = receivableAnticipationPartnerAcquisitionList.findAll { it.receivableAnticipation.isCreditCard() }*.getValueWithPartnerFee().sum() ?: 0
                emailBody = "Hoje foram enviadas ${receivableAnticipationPartnerAcquisitionList.size()} cessões no total de ${FormUtils.formatCurrencyWithMonetarySymbol(acquisitionTotalValue)}, sendo ${FormUtils.formatCurrencyWithMonetarySymbol(bankSlipTotalValue)} em boletos, ${FormUtils.formatCurrencyWithMonetarySymbol(pixTotalValue)} em Pix e ${FormUtils.formatCurrencyWithMonetarySymbol(creditCardTotalValue)} em cartão."
            } else {
                emailBody = "Hoje não foram enviadas cessões."
            }


            send(AsaasApplicationHolder.config.asaas.sender, "operacional@solisinvestimentos.com.br", ccMails, subject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("MessageService.sendVortxDailyBalanceRequired() >> Erro ao enviar email sobre solitação de desbloqueio manual", exception)
        }
    }

    public void notifySmartBankDailyBalanceTransferJob() {
        try {
            if (!AsaasEnvironment.isProduction()) return

            List<String> ccMails = ["caixa@bip.b.br", "mc@smartbank.com.br"]
            String subject = "Liberação de TED Asaas - Autorização Permanente"
            String emailBody = """
                Solicitação de TED mediante a saldo disponível em conta.<br/>
                <br/>
                Origem:<br/>
                Banco: 630 - SmartBank<br/>
                Agência:0001<br/>
                Conta Corrente: 2108310004<br/>
                <br/>
                Destino:<br/>
                Favorecido: ${grailsApplication.config.asaas.company.displayname}<br/>
                CNPJ: 19.540.550/0001-21<br/>
                Banco: 033 - Santander<br/>
                Agência: 0952-0<br/>
                Conta Corrente: 13000580-9<br/>
            """

            send(grailsApplication.config.mail.financeiro, grailsApplication.config.asaas.devTeam.warning.smartbank.email, ccMails, subject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar solicitação de TED diária para SmartBank", exception)
        }
    }

	public void notifyTeamAboutInvoiceStuckInSynchronization(List<Invoice> invoiceList) {
		try {
			if (!AsaasEnvironment.isProduction()) return

			String emailBody = buildTemplate("/mailTemplate/notifyAboutInvoiceStuckInSynchronization", [invoiceList: invoiceList])
			String emailSubject = "${invoiceList.size()} Notas Fiscais estão sem resposta do eNotas!"
			send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.alert.invoiceStuck.email, [grailsApplication.config.asaas.devTeam.alert.invoice.email], emailSubject, emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.notifyTeamAboutInvoiceStuckInSynchronization >> Erro ao enviar notificação sobre notas fiscais sem resposta do eNotas", exception)
		}
	}

	public void sendAutomaticAnticipationNotSavedToAccountManager(Payment payment, String reason) {
		try {
			String emailBody = buildTemplate("/mailTemplate/automaticAnticipationNotSavedToAccountManager", [payment: payment, customer: payment.provider, reason: reason])

			send(grailsApplication.config.asaas.sender, payment.provider.accountManager.internalEmail, null, "Nao foi possível antecipar automaticamente a cobrança ${payment.getInvoiceNumber()} do fornecedor ${payment.provider.getProviderName()}", emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.sendAutomaticAnticipationNotSavedToAccountManager >> Erro ao enviar notificação de antecipação automática não salva. [paymentId: ${payment?.id}]", exception)
		}
	}

    public void notifyCcsGroupAboutPendingValueOfManualTransfer(Map params) {
        try {
            if (!AsaasEnvironment.isProduction()) return

            String emailSubject = "Existe um valor à ser confirmado"
            String emailBody = "Existe uma transferência, cuja a origem foi um bloqueio manual. São necessárias confirmações para seguir com a transferência, executar preferencialmente até as 11:00 de hoje ${CustomDateUtils.fromDate(new Date(), 'dd/MM/yyyy')}."

            emailBody += "Link: ${grailsLinkGenerator.link(controller: 'bacenJudTransferRequest', action: 'list', params: params, absolute: true)}"

            send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.financialSettlement.email, [grailsApplication.config.asaas.onboarding.email], emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("MessageService.notifyCcsGroupAboutPendingValueOfManualTransfer >> Erro ao enviar email sobre transferência manual", exception)
            throw exception
        }
    }


	public void notifyAccountManagerAboutErrorOnRecurrentPaymentInvoice(Payment payment, String reason) {
		try {
			String emailBody = buildTemplate("/mailTemplate/recurrentPaymentInvoiceNotSavedToAccountManager", [payment: payment, reason: reason])

			send(grailsApplication.config.asaas.sender, payment.provider.accountManager.internalEmail, null, "Não foi possível gerar automaticamente a nota fiscal da cobrança ${payment.getInvoiceNumber()} do fornecedor ${payment.provider.getProviderName()}", emailBody, true)
		} catch (Exception exception) {
            AsaasLogger.error("MessageService.notifyAccountManagerAboutErrorOnRecurrentPaymentInvoice >> Erro ao enviar notificação de erro na geração automática da nota fiscal da cobrança ${payment?.getInvoiceNumber()} do fornecedor ${payment?.provider?.getProviderName()}", exception)
		}
	}

	public void notifyBankSlipRegistrationSuccess(Payment payment, String customerAccountEmail, String customerAccountName) {
		if (!AsaasEnvironment.isProduction()) return

		String emailBody = buildTemplate("/mailTemplate/notifyBankSlipRegistrationSuccess", [payment: payment, customerAccountName: customerAccountName])

		send(grailsApplication.config.asaas.sender, customerAccountEmail, null, "O boleto da sua cobrança para ${payment.provider.buildTradingName()} já foi registrado", emailBody, true)
	}

    public void sendPixBacenReport(AsaasFile file) {
        if (!AsaasEnvironment.isProduction()) return
        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.devTeam.warning.pix.email, null, "Relatório mensal de dados do Pix - ${CustomDateUtils.getMonth(new Date())}/${CustomDateUtils.getYear(new Date())}", "Segue link para download: ${ApiAsaasFileParser.buildResponseItem(file).downloadUrl}", false)
    }

    public void sendPixBlockedAccountUrlReport(AsaasFile file, Date reportDate) {
        if (!AsaasEnvironment.isProduction()) return

        String subject = "Relatório de contas com uso irregular no Pix - ${CustomDateUtils.formatDate(reportDate)}"
        String body = "Segue em anexo o arquivo CSV do relatório de contas com uso irregular no Pix"

        Map options = [
            attachmentList: [
                [
                    attachmentBytes: file.getFileBytes(),
                    attachmentName: file.getOriginalName()
                ]
            ],
            multipart: true
        ]

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.analysis.subArea.email, null, subject, body, false, options)
    }

    public void notifyPixTransactionReceivedWithoutQRCode(PixTransactionRequest pixTransactionRequest, Map decryptedInfo) {
        if (!AsaasEnvironment.isProduction()) return

        String subject = "Transação Pix sem QRCode vinculado"
        String message = """
            Dados da Transação:<br/>
            Valor: RS ${decryptedInfo.value}<br/>
            Descrição: ${decryptedInfo.message}<br/>
            Data: ${CustomDateUtils.fromDate(pixTransactionRequest.dateCreated)}<br/>
            <br/>
            Dados do pagador:<br/>
            Nome: ${decryptedInfo.externalAccount.name}<br/>
            CPF/CNPJ: ${decryptedInfo.externalAccount.cpfCnpj}<br/>
        """

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.financeiro.pix.email, null, subject, message, true)
    }

    public void notifyErrorToProcessPixCreditTransaction(PixTransactionRequest pixTransactionRequest, Map decryptedInfo) {
        if (!AsaasEnvironment.isProduction()) return

        String subject = "[Pix] Erro ao processar uma transação de crédito"
        String message = """
            Dados da Transação:<br>
            pixTansactionRequest.id: ${pixTransactionRequest.id}<br>
            Valor: ${FormUtils.formatCurrencyWithMonetarySymbol(decryptedInfo.value)}<br>
            Descrição: ${decryptedInfo.message}<br>
            Data: ${CustomDateUtils.fromDate(pixTransactionRequest.dateCreated)}<br>
            <br>
            Dados do pagador:<br>
            Nome: ${decryptedInfo.externalAccount.name}<br>
            CPF/CNPJ: ${decryptedInfo.externalAccount.cpfCnpj}<br>
            Tentativas de processamento: ${pixTransactionRequest.attempts}<br>
        """

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.alertas.pix.email, null, subject, message, true)
    }

    public void notifyCustomerCanRequestDunning(List<Payment> paymentList, Customer customer) {
        String body = buildTemplate("/mailTemplate/notifyCustomerCanRequestDunning", [paymentList: paymentList])
        send(grailsApplication.config.asaas.sender, customer.email, null, "Você possui cobranças que podem ser recuperadas", body, true)
    }

    public void sendNewCustomerInManagerPortfolio(AccountManager accountManager, Customer customer) {
        if (!AsaasEnvironment.isProduction()) return

        String mailTemplateBody = buildTemplate("/mailTemplate/newCustomerInAccountManagerPortfolio", [customer: customer])
        sendDefaultTemplate(grailsApplication.config.asaas.sender, accountManager.internalEmail, accountManager.name, "Novo cliente em sua carteira!", mailTemplateBody)
    }

    public void sendTransferBatchFileWithProcessingErrorAlert(List<TransferBatchFile> transferBatchFileList) {
        if (!AsaasEnvironment.isProduction()) return

	    String emailSubject = "Remessa de transferência com erro(s) de processamento"
		String emailBody = buildTemplate("/mailTemplate/transferBatchFileProcessingErrorAlert", [transferBatchFileList: transferBatchFileList])

		send(grailsApplication.config.asaas.sender, grailsApplication.config.mail.financeiro, [grailsApplication.config.asaas.devTeam.alert.email], emailSubject, emailBody, true)
    }

    public void notifyTransferBatchFileCreationDone(TransferBatchFile transferBatchFile) {
        try {
            String emailSubject = "Arquivo de Remessa ${transferBatchFile.fileName}"
            String emailBody = buildTemplate("/mailTemplate/notifyTransferBatchFileCreationDone", [transferBatchFile: transferBatchFile])

            send(grailsApplication.config.asaas.sender, transferBatchFile.createdBy.username, null, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao enviar e-mail do Arquivo de Remessas de transferências", exception)
        }
    }

    public void sendCieloTransactionWithoutTid(String cardNumber, String authorizationNumber) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = 'Arquivo de liquidação da Cielo com transação sem TID'
        String emailBody = "A transação foi ignorada na importação e deve ser verificada manualmente. Número do cartão ${cardNumber} e código de autorização ${authorizationNumber}."

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.mail.operacoesFinanceiras, null, emailSubject, emailBody, true)
	}

    public void sendCreditCardAcquirerOperationBatchProcessResult(Long batchId, Long itemsWithError) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = 'Arquivo de liquidação de cartão de crédito importado'
        String emailBody = buildTemplate("/mailTemplate/creditCardAcquirerOperationBatchProcessResult", [batchId: batchId, itemsWithError: itemsWithError])

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.mail.operacoesFinanceiras, null, emailSubject, emailBody, true)
    }

    public void sendCieloEdiTransactionRejected(String transactionIdentifier, String rejectionCode, String authorizationNumber) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = "Arquivo EDI de liquidação da Cielo - TID ${transactionIdentifier} rejeitado"
        String emailBody = "A transação foi ignorada na importação e deve ser verificada manualmente. Código de rejeição ${rejectionCode} e código de autorização ${authorizationNumber}."

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.mail.operacoesFinanceiras, null, emailSubject, emailBody, true)
    }

    public void notifyAboutNewAccountManagerCreated(AccountManager accountManager) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = "Um novo gerente de contas foi criado no Asaas"
        String emailBody = "Foi criado um novo gerente com as seguintes informações:<br>"
        emailBody += "Nome fake: ${accountManager.name}<br>"
        emailBody += "Email fake: ${accountManager.email}<br>"
        emailBody += "Email real: ${accountManager.internalEmail}<br>"
        emailBody += "Nome real: ${accountManager.user.name}<br>"
        emailBody += "Ramal: ${accountManager.internalExtension ?: "Não há"}"

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.infrastructureTeam.email, null, emailSubject, emailBody, true)
    }

    public String buildMarkupToTrackEmailView(viewingUrl) {
        StringWriter writer = new StringWriter()
        MarkupBuilder markup = new MarkupBuilder(writer)

        markup.setDoubleQuotes(true)

        markup.img(style: "display: none;", src: viewingUrl)

        return writer.toString()
    }

    public void notifyCustomerCreditCardTransactionBlocked(Customer customer) {
        if (!AsaasEnvironment.isProduction()) return

        try {
            String subject = "Detectadas transações de cartão de crédito em massa."
            String body = "Cliente: ${customer.id} - ${customer.getProviderName()}"
            List<String> bccMails =  ["avisos.wallstreet@asaas.com.br"]

            send(grailsApplication.config.asaas.sender, "prevencao@asaas.com.br", bccMails, subject, body, true)
        } catch (Exception e) {
            AsaasLogger.error("Erro ao enviar email: ${subject}, Cliente ${customer.id}", e)
        }
    }

    public void notifyBacenCcsRelationshipDetailResponseFileCreated(CcsRelationshipDetailResponseFile ccsRelationshipDetailResponseFile) {
        String subject = "Arquivo CCS0002 - Número Operação ${ccsRelationshipDetailResponseFile.ccsRelationshipDetailRequestFile.operationNumber} gerado com sucesso"
        try {
            String body = "Número Operação: ${ccsRelationshipDetailResponseFile.ccsRelationshipDetailRequestFile.operationNumber}"
            body += "<br> Identificador CCS: ${ccsRelationshipDetailResponseFile.ccsRelationshipDetailRequestFile.ccsControlNumber}"
            body += "<br><br> Arquivo está disponível para download em: "
            String filePublicId = AsaasFile.get(ccsRelationshipDetailResponseFile.asaasFileId).publicId

            String link = grailsLinkGenerator.link(controller: "asaasFile", action: "compressedDownload", params: [id: filePublicId], absolute: true)
            body += "<br/><pre><a href=\"${link}\">${link}</a></pre>"

            send(grailsApplication.config.asaas.sender,
                 grailsApplication.config.asaas.compliance.email,
                 grailsApplication.config.asaas.regulatorio.ccs.email,
                 subject,
                 body,
                 true)
        } catch (Exception e) {
            AsaasLogger.error("Erro ao enviar email: ${subject}, Id Arquivo CCS0002: ${ccsRelationshipDetailResponseFile.id}", e)
        }
    }

    public void sendCustomerDealInfoImportInconsistencies(List<Map> invalidItemList, String email) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = "Inconsistências na importação de informações de clientes negociados"
        String message = buildTemplate("/mailTemplate/customerDealInfoImport/importInconsistenciesMessage", [invalidItemList: invalidItemList])

        send(grailsApplication.config.asaas.sender, email, null, emailSubject, message, true)
    }

    public void notifyReceivableAnticipationPartnerSettlementBatchCreationResult(String errorMessage) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = "Remessa de antecipação com parceiro"

        try {
            String message = buildTemplate("/mailTemplate/notifyReceivableAnticipationPartnerSettlementBatchCreationResult", [errorMessage: errorMessage])
            send(grailsApplication.config.asaas.sender, grailsApplication.config.mail.financeiro, null, emailSubject, message, true)
        } catch (Exception e) {
            AsaasLogger.error("MessageService.notifyReceivableAnticipationPartnerSettlementBatchCreationResult >> Erro ao enviar email: ${emailSubject}", e)
        }
    }

    public void sendCustomerFinancialTransactionExport(AsaasFile asaasFile, String userEmail, Customer customer, Date startDate, Date finishDate) {
        String recipientName = getRecipientName(customer)
        String emailSubject = "Extrato da sua conta Asaas"

        String emailBody = buildTemplate("/mailTemplate/sendCustomerFinancialTransactionExport", [asaasFile: asaasFile, startDate: startDate, finishDate: finishDate])
        sendDefaultTemplate(grailsApplication.config.asaas.sender, userEmail, recipientName, emailSubject, emailBody)
    }

    public void sendCustomerPixTransactionExport(AsaasFile asaasFile, String userEmail, Customer customer, Date startDate, Date finishDate) {
        String body = buildTemplate("/mailTemplate/sendCustomerPixTransactionExport", [asaasFile: asaasFile, startDate: startDate, finishDate: finishDate])
        String recipientName = getRecipientName(customer)
        String subject = "O arquivo solicitado com os Pix recebidos chegou!"
        String title = "Pix recebidos na sua conta Asaas"

        sendDefaultTemplate(grailsApplication.config.asaas.sender, userEmail, null, recipientName, subject, body, [emailTitle: title])
    }

    public void sendCnab750File(AsaasFile asaasFile, Customer customer, Date filterDate) {
        String emailBody = buildTemplate("/mailTemplate/sendCnab750PixFileExport", [asaasFile: asaasFile, date: filterDate])
        sendDefaultTemplate(grailsApplication.config.asaas.sender, customer.email, getRecipientName(customer), "Asaas | Arquivo de retorno CNAB 750", emailBody)
    }

    public void notifyInternalTechnicalSuportAboutHackedAccount(Customer customer) {
        try {
            if (!AsaasEnvironment.isProduction()) return

            String emailSubject = "ASAAS | Cliente comprometido"

            String emailBody = "O cliente ${customer.getProviderName()}, com o e-mail ${customer.email}, foi comprometido. Os acessos foram invalidados, mas a chave de API (caso exista) permanece válida."

            send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.internalTechnicalSuport.email, null, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("MessageService.notifyInternalTechnicalSuportAboutHackedAccount >> Erro ao enviar o email sobre cliente comprometido", exception)
        }
    }

    public void sendInternalUserPermissionReportForSecurityTeam(List<Map> internalUserPermissionReportList) {
        if (!AsaasEnvironment.isProduction()) return

        String emailSubject = "ASAAS | Relatório de permissões de usuários administrativos"

        String message = buildTemplate("/mailTemplate/internalUser/permissionReport", [internalUserPermissionReportList: internalUserPermissionReportList])

        send(grailsApplication.config.asaas.sender, grailsApplication.config.asaas.securityTeam.email, null, emailSubject, message, true)
    }

    public void sendDefaultTemplateFromAccountManager(Customer customer, String subject, String bodyPath, Map bodyParams, Boolean isRelevantMail) {
        String recipientName = getRecipientName(customer)
        String emailBody = buildTemplate(bodyPath, bodyParams)
        String emailSignature = buildTemplate("/mailTemplate/signature/accountManager", [accountManager: customer.accountManager])

        String body = "Olá, ${ recipientName } <br><br> ${ emailBody } <br><br> ${ emailSignature }"

        String mailFrom = buildAccountManagerSender(customer.accountManager)
        String mailTo = customer.email

        send(mailFrom, mailTo, null, subject, body, true)

        if (isRelevantMail) relevantMailHistoryService.save(customer, mailFrom, mailTo, null, subject, body, AsaasMailMessageType.GENERAL)
    }

    private String buildAccountManagerSenderToReferral(Customer invitedByCustomer) {
        if (invitedByCustomer) return invitedByCustomer.accountManager.buildMailFrom()

        return grailsApplication.config.asaas.defaultAccountManager.email
    }

    private String buildAccountManagerSender(AccountManager accountManager) {
        if (accountManager) return accountManager.buildMailFrom()

        return buildSender(grailsApplication.config.asaas.defaultAccountManager.from, grailsApplication.config.asaas.defaultAccountManager.email)
    }

    private void sendBoletoChangeInfoRequestUpdateEmail(BoletoChangeInfoRequest boletoChangeInfoRequest) {
        try {
            String action = boletoChangeInfoRequest.status.equals(Status.APPROVED) ? "aprovada" : "reprovada"

            String emailSubject = makeTemplate(AsaasApplicationHolder.config.asaas.message.boletochangeinforequest.update.emailSubject, [action: action])
            String emailBody = buildTemplate("/mailTemplate/sendBoletoChangeInfoRequestUpdateEmail",
                                                [name: boletoChangeInfoRequest.customer.providerName,
                                                action: action,
                                                accountManager: boletoChangeInfoRequest.customer.accountManager,
                                                denialReason: boletoChangeInfoRequest.denialReason,
                                                observations: boletoChangeInfoRequest.observations])


            send(buildAccountManagerSender(boletoChangeInfoRequest.customer.accountManager), boletoChangeInfoRequest.customer.email, null, emailSubject, emailBody, true)
        } catch (Exception exception) {
            AsaasLogger.error("MessageService.sendBoletoChangeInfoRequestUpdateEmail >> Erro ao enviar email de atualização de solicitação de alteração de boleto", exception)
        }
    }

    private String makeTemplate(String template, Map binding) {
        return new SimpleTemplateEngine().createTemplate(template).make(binding).toString()
    }

    private String buildSender(String name, String email) {
        return "${name} <${email}>"
    }

    private void sendException(String subject, String mailTo, String message, Exception exception) {
        try {
            if (!AsaasEnvironment.isProduction()) return

            String emailBody = buildTemplate("/mailTemplate/exceptionReport", [message: message, exception: exception])

            send(AsaasApplicationHolder.config.asaas.sender, mailTo, null, subject, emailBody, true)
        } catch (Exception e) {
            AsaasLogger.error("MessageService.sendException >> Erro ao enviar email sobre exception no Asaas", e)
        }
    }

    private Map buildInvoiceAuthorizedParameters(Invoice invoice, String templatePath) {
        Map parameters = [:]
        String baseUrl = notificationRequestParameterService.getBaseUrl(invoice.customer.id)

        parameters.put("baseUrl", baseUrl)
        parameters.put("invoice", invoice)
        parameters.put("providerName", CustomerInfoFormatter.formatName(invoice.customer))
        parameters.put("customer", invoice.customer)
        parameters.put("mailSubject", buildTemplate(templatePath + "emailSubject", parameters).decodeHTML())

        parameters += notificationRequestParameterService.getLogoParameters(invoice.customer, baseUrl)
        parameters += notificationRequestParameterService.getCustomerNotificationColours(invoice.customer)

        if (invoice.originType.isDetached()) return parameters

        Payment invoicePayment = invoice.getPayment()
        parameters.externalToken = invoicePayment
            ? invoicePayment.externalToken
            : invoice.getInstallment().getFirstRemainingPayment().externalToken

        return parameters
    }
}

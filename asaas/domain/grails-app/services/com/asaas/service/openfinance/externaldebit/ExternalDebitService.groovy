package com.asaas.service.openfinance.externaldebit

import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.customer.Customer
import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.openfinance.externaldebit.ExternalDebitPixInfo
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentPixInfo
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentReceiver
import com.asaas.domain.pix.PixTransaction
import com.asaas.integration.aws.managers.OpenFinanceSnsClientProxy
import com.asaas.integration.aws.managers.SnsManager
import com.asaas.integration.openfinance.v3.parser.OpenFinanceParser
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.log.AsaasLogger
import com.asaas.openfinance.WebhookNotificationData
import com.asaas.openfinance.externaldebit.adapter.ExternalDebitAdapter
import com.asaas.openfinance.externaldebit.adapter.ExternalDebitListAdapter
import com.asaas.openfinance.externaldebit.enums.ExternalDebitRefusalReason
import com.asaas.openfinance.externaldebit.enums.ExternalDebitStatus
import com.asaas.openfinance.externaldebit.enums.ExternalDebitType
import com.asaas.openfinance.externaldebitconsent.enums.ExternalDebitConsentOriginRequesterCreatedVersion
import com.asaas.pix.adapter.addresskey.AddressKeyAdapter
import com.asaas.pix.adapter.qrcode.QrCodeAdapter
import com.asaas.pix.vo.transaction.PixExternalDebitVO
import com.asaas.sns.SnsTopicArn
import com.asaas.utils.BankAccountUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.TransactionUtils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ExternalDebitService {

    def pixAddressKeyManagerService
    def externalDebitPixInfoService
    def pixDebitService
    def pixQrCodeService
    def pixTransactionService
    def externalDebitConsentService
    def grailsApplication
    def recurringCheckoutScheduleOpenFinanceService
    def recurringCheckoutScheduleService

    public List<ExternalDebit> saveList(ExternalDebitListAdapter externalDebitAdapterList) {
        List<ExternalDebit> externalDebitList = recurringCheckoutScheduleOpenFinanceService.validateExternalDebitQuantity(externalDebitAdapterList)

        if (externalDebitList.any { it.hasErrors() }) {
            externalDebitConsentService.consume(externalDebitAdapterList.externalDebitConsent)

            return externalDebitList
        }

        for (ExternalDebitAdapter externalDebitAdapter : externalDebitAdapterList.externalDebitAdapterList) {
            externalDebitList.add(save(externalDebitAdapterList.customer, externalDebitAdapter))
        }

        if (externalDebitList.any { it.hasErrors() }) return externalDebitList

        externalDebitConsentService.consume(externalDebitAdapterList.externalDebitConsent)

        return externalDebitList
    }

    public ExternalDebit saveSingle(ExternalDebitAdapter externalDebitAdapter) {
        ExternalDebit externalDebit = save(externalDebitAdapter.customer, externalDebitAdapter)
        if (externalDebit.hasErrors()) return externalDebit

        if (!externalDebit.consent.type.isAutomatic()) externalDebitConsentService.consume(externalDebitAdapter.externalDebitConsent)

        return externalDebit
    }

    public ExternalDebit refuse(ExternalDebit externalDebit, ExternalDebitRefusalReason refusalReason, String refusalReasonDescription) {
        externalDebit.status = ExternalDebitStatus.REFUSED
        externalDebit.refusalReason = refusalReason
        externalDebit.refusalReasonDescription = refusalReasonDescription
        externalDebit.save(failOnError: true)

        sendWebhookNotification(externalDebit)
        return externalDebit
    }

    public ExternalDebit cancel(ExternalDebit externalDebit, ExternalDebitRefusalReason refusalReason, String refusalReasonDescription) {
        externalDebit.status = ExternalDebitStatus.CANCELLED
        externalDebit.refusalReason = refusalReason
        externalDebit.refusalReasonDescription = refusalReasonDescription
        externalDebit.save(failOnError: true)

        PixTransaction pixTransaction = pixTransactionService.cancel(externalDebit.pixTransaction)
        if (pixTransaction.hasErrors()) throw new ValidationException("Não foi possível cancelar a transação Pix agendada.", pixTransaction.errors)

        sendWebhookNotification(externalDebit)

        return externalDebit
    }

    public void finish(ExternalDebit externalDebit) {
        ExternalDebitConsent debitConsent = externalDebit.consent
        if (debitConsent.status.isScheduled()) {
            externalDebitConsentService.consume(debitConsent)

            if (debitConsent.isRecurringScheduled()) {
                recurringCheckoutScheduleService.finish(debitConsent.recurringCheckoutSchedule)
            }
        }

        if (debitConsent.type.isAutomatic()) {
            Boolean totalAmountHasBeenReached = externalDebitConsentService.hasTotalAmountBeenReached(debitConsent)

            if (totalAmountHasBeenReached) {
                externalDebitConsentService.consume(debitConsent)
            }
        }

        externalDebit.status = ExternalDebitStatus.DONE
        externalDebit.save(failOnError: true)

        sendWebhookNotification(externalDebit)
    }

    public void error(ExternalDebit externalDebit) {
        externalDebit.status = ExternalDebitStatus.ERROR
        externalDebit.save(failOnError: true)

        sendWebhookNotification(externalDebit)
    }

    public void schedule(ExternalDebit externalDebit) {
        externalDebit.status = ExternalDebitStatus.SCHEDULED
        externalDebit.save(failOnError: true)

        sendWebhookNotification(externalDebit)
    }

    public void process(ExternalDebit externalDebit) {
        externalDebit.status = ExternalDebitStatus.PROCESSED
        externalDebit.save(failOnError: true)
    }

    public void awaitingCheckoutRiskAnalysisRequest(ExternalDebit externalDebit) {
        externalDebit.status = ExternalDebitStatus.AWAITING_CHECKOUT_RISK_ANALYSIS_REQUEST
        externalDebit.save(failOnError: true)

        sendWebhookNotification(externalDebit)
    }

    public void sendWebhookNotification(ExternalDebit externalDebit) {
        Boolean sendWebhookNotification = grailsApplication.config.asaas.sns.sendNotificationWebhook
        if (!externalDebit.consent.externalPaymentTransactionInitiator.webhookUri || !sendWebhookNotification) return

        TransactionUtils.afterCommit({
            ExternalDebitConsentOriginRequesterCreatedVersion defaultApiVersion = ExternalDebitConsentOriginRequesterCreatedVersion.V2
            WebhookNotificationData webhookNotificationData = new WebhookNotificationData()
            webhookNotificationData.webhookUri = externalDebit.consent.externalPaymentTransactionInitiator.webhookUri
            webhookNotificationData.apiVersion = (externalDebit.consent.getOriginRequesterCreatedVersion() ?: defaultApiVersion).toString().toLowerCase()
            webhookNotificationData.lastUpdate = CustomDateUtils.fromDate(new Date(), CustomDateUtils.UTC_FORMAT)
            webhookNotificationData.externalIdentifier = externalDebit.externalIdentifier
            webhookNotificationData.status = OpenFinanceParser.parseDebitStatus(externalDebit.status)
            webhookNotificationData.notificationType = externalDebit.consent.type.isAutomatic() ? "automatic-payments" : "payments"

            new SnsManager(OpenFinanceSnsClientProxy.CLIENT_INSTANCE, SnsTopicArn.OPEN_FINANCE_WEBHOOK_NOTIFICATION).sendMessage(webhookNotificationData)
        })
    }

    public ExternalDebit createPixTransaction(ExternalDebit externalDebit) {
        ExternalDebitPixInfo pixInfo = ExternalDebitPixInfo.query([externalDebit: externalDebit, ignoreCustomer: true]).get()

        QrCodeAdapter qrCodeAdapter
        if (pixInfo.qrCodePayload) {
            qrCodeAdapter = pixQrCodeService.decode(pixInfo.qrCodePayload, externalDebit.customer)
            if (hasInvalidQrCode(qrCodeAdapter, pixInfo, externalDebit)) {
                AsaasLogger.warn("ExternalDebitService.createPixTransaction() -> Os dados do QR Code são divergentes. [externalDebit: ${externalDebit.id}, qrCodeAdapter: ${qrCodeAdapter?.properties}]")
                return refuse(externalDebit, ExternalDebitRefusalReason.INVALID_PIX_QR_CODE, "Os dados do QR Code são divergentes.")
            }
        } else if (pixInfo.pixKey) {
            Boolean shouldValidatePixKeyOwner = (pixInfo?.originType?.isAddresKey())
            if (shouldValidatePixKeyOwner) {
                AddressKeyAdapter addressKeyAdapter = pixAddressKeyManagerService.findExternallyPixAddressKey(pixInfo.pixKey, null, externalDebit.customer)

                if (hasDivergentReceiver(addressKeyAdapter, pixInfo)) {
                    AsaasLogger.warn("ExternalDebitService.createPixTransaction() -> Os dados da chave Pix divergem do consentimento. [externalDebit: ${externalDebit.id}, addressKeyAdapter: ${addressKeyAdapter?.properties}]")
                    return refuse(externalDebit, ExternalDebitRefusalReason.INVALID_RECEIVER_ACCOUNT, "Os dados da chave Pix divergem do consentimento.")
                }
            }
        }

        PixExternalDebitVO pixExternalDebitVO = new PixExternalDebitVO(pixInfo, qrCodeAdapter)
        PixTransaction pixTransaction = pixDebitService.saveExternalDebit(externalDebit.customer, pixExternalDebitVO)
        if (pixTransaction.hasErrors()) {
            ExternalDebitRefusalReason externalDebitRefusalReason = ExternalDebitRefusalReason.parseDebitRefusalReason(pixTransaction.errors.allErrors.first().getCodes()[1])
            return refuse(externalDebit, externalDebitRefusalReason, pixTransaction.errors.allErrors.first().defaultMessage)
        }

        externalDebit.pixTransaction = pixTransaction
        externalDebit.save(failOnError: true)
        return externalDebit
    }

    public ExternalDebitStatus buildStatus(ExternalDebitConsent consent, PixTransaction pixTransaction) {
        if (pixTransaction.status.isAwaitingCheckoutRiskAnalysisRequest()) {
            return ExternalDebitStatus.AWAITING_CHECKOUT_RISK_ANALYSIS_REQUEST
        } else if (consent.isEquivalentToScheduled()) {
            return ExternalDebitStatus.SCHEDULED
        } else {
            return ExternalDebitStatus.PROCESSED
        }
    }

    private ExternalDebit save(Customer customer, ExternalDebitAdapter externalDebitAdapter) {
        ExternalDebit validatedDebitDomain = validate(externalDebitAdapter.externalDebitConsent, externalDebitAdapter, customer)

        if (validatedDebitDomain.hasErrors()) return validatedDebitDomain

        ExternalDebit externalDebit = new ExternalDebit()

        if (externalDebitAdapter.externalDebitConsent.isEquivalentToScheduled()) {
            externalDebit.scheduledDate = externalDebitAdapter.scheduledDate
        }

        externalDebit.customer = customer
        externalDebit.value = externalDebitAdapter.value
        externalDebit.message = externalDebitAdapter.message
        externalDebit.initiatorCnpj = externalDebitAdapter.initiatorCnpj
        externalDebit.externalIdentifier = UUID.randomUUID().toString()
        externalDebit.status = ExternalDebitStatus.REQUESTED
        externalDebit.type = ExternalDebitType.PIX
        externalDebit.consent = externalDebitAdapter.externalDebitConsent
        externalDebit.save(failOnError: true)

        externalDebitPixInfoService.save(externalDebit, externalDebitAdapter)

        ExternalDebit validatedDomain = validateDebitOverConsent(externalDebitAdapter, externalDebitAdapter.externalDebitConsent)
        if (validatedDomain.hasErrors()) {
            if (!externalDebitAdapter.externalDebitConsent) return validatedDomain

            externalDebitConsentService.consume(externalDebitAdapter.externalDebitConsent)
            refuse(externalDebit, ExternalDebitRefusalReason.INVALID_CONSENT, DomainUtils.getFirstValidationMessage(validatedDomain))

            return validatedDomain
        }

        return externalDebit
    }

    private Boolean hasExternalDebitWithSameQrCode(String qrCodePayload, Customer customer) {
        return ExternalDebitPixInfo.query([exists: true, qrCodePayload: qrCodePayload, externalDebitCustomer: customer, "externalDebitStatus[in]": ExternalDebitStatus.validStatus()]).get().asBoolean()
    }

    private ExternalDebit validate(ExternalDebitConsent externalDebitConsent, ExternalDebitAdapter debitAdapter, Customer customer) {
        ExternalDebit validatedDomain = new ExternalDebit()

        if (debitAdapter.externalDebitConsent.isRecurringScheduled()) validatedDomain = recurringCheckoutScheduleOpenFinanceService.validateStartDate(validatedDomain, debitAdapter)

        if (externalDebitConsent?.status?.isConsumed()) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidConsent.alreadyConsumed", "Consentimento inválido (status diferente de AUTHORISED ou está expirado).")
            return validatedDomain
        }

        if (!CpfCnpjUtils.validaCNPJ(debitAdapter.initiatorCnpj)) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidParameter.initiatorCnpj", "CNPJ da iniciadora inválido.")

        if ((debitAdapter.originType.isManual() || debitAdapter.originType.isAddresKey()) && debitAdapter.conciliationIdentifier) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.uninformed.invalidDebit", "O identificador da transação só deve ser informado quando o [data.localInstrument] for igual QRES ou INIC.")
            return validatedDomain
        }

        Boolean shouldHaveEndToEndIdentifier = (debitAdapter.originType.isAddresKey() || debitAdapter.originType.isQrCode() || debitAdapter.originType.isPaymentInitiationService())
        if (shouldHaveEndToEndIdentifier) {
            if (!debitAdapter.endToEndIdentifier) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.uninformedParameter.invalidDebit", "O endToEndId da transação [data.endToEndId] é obrigatório para o [data.localInstrument] do tipo INIC, QRES, QRDN ou DICT.")
                return validatedDomain
            }

            if (!PixUtils.isValidEndToEndIdentifier(debitAdapter.endToEndIdentifier, debitAdapter.scheduledDate)) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidParameter.invalidDebit", "O endToEndId da transação [data.transactionIdentification] é inválido para o [data.localInstrument] do tipo INIC, QRES, QRDN ou DICTC.")
                return validatedDomain
            }
        }

        if (debitAdapter.originType.isPaymentInitiationService() && !debitAdapter.conciliationIdentifier) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.uninformed.invalidDebit", "O identificador da transação [data.transactionIdentification] é obrigatório para o [data.localInstrument] do tipo INIC.")
            return validatedDomain
        }

        if (debitAdapter.originType.isQrCode()) {
            if (!debitAdapter.qrCodePayload) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.invalidQrCode", "O Qr Code não foi informado.")
                return validatedDomain
            }

            if (debitAdapter.originType.isStaticQrCode() && debitAdapter.conciliationIdentifier) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidPaymentDetail.invalidDebit", "O identificador da transação só deve ser informado no Qr code.")
                return validatedDomain
            }

            if (debitAdapter.originType.isDynamicQrCode() && hasExternalDebitWithSameQrCode(debitAdapter.qrCodePayload, customer)) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.debtorRefuse.invalidQrCode", "O Qr Code já foi informado.")
                return validatedDomain
            }

            Map debitConsent = ExternalDebitConsent.query([columnList: ["id", "value", "scheduledDate"], customer: customer, externalIdentifier: debitAdapter.consentExternalIdentifier]).get()
            String consentQrCodePayload = ExternalDebitConsentPixInfo.query([column: "qrCodePayload", debitConsentId: debitConsent.id]).get()

            if (consentQrCodePayload != debitAdapter.qrCodePayload) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.qrCodeIsDifferent", "O Qr Code é diferente do consentimento.")
                return validatedDomain
            }

            QrCodeAdapter qrCodeAdapter = pixQrCodeService.decode(debitAdapter.qrCodePayload, customer)
            if (!qrCodeAdapter) {
                DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.invalidQrCode", "O Qr Code não foi encontrado.")
                return validatedDomain
            }

            if (debitAdapter.pixKey != qrCodeAdapter.pixKey) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidPaymentDetail.pixKeyIsNotEqual", "A chave informada diverge do Qr Code.")
            if (qrCodeAdapter.value && (debitConsent.value != qrCodeAdapter.value)) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidQrCodeValue.valueIsNotEqual", "O valor informado diverge do Qr Code.")

            Boolean isScheduledForDynamicQrCodeWithDueDate = debitConsent.scheduledDate && qrCodeAdapter.type.isDynamic() && qrCodeAdapter.dueDate
            if (isScheduledForDynamicQrCodeWithDueDate) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidPaymentDetail.data", "QR Codes do tipo dinâmico com vencimento não são permitidos para pagamentos agendados via Open Finance.")
        }

        return validatedDomain
    }

    private ExternalDebit validateDebitOverConsent(ExternalDebitAdapter debitAdapter, ExternalDebitConsent consent) {
        ExternalDebit validatedDomain = new ExternalDebit()

        if (!consent) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidConsent.notFound", "Consentimento não encontrado")
            return validatedDomain
        }

        if (!consent.status.isAuthorized()) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidConsent.notAuthorized", "Consentimento não autorizado ou expirado")

        if (consent.authorizedByUser.id != debitAdapter.userId) {
            DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.invalidUserId", "O usuário referência do pagamento diverge do autorizador do consentimento")
            return validatedDomain
        }

        Boolean consentWasUsedAlready = ExternalDebit.query([consent: consent, ignoreCustomer: true]).count() > 1
        if (consentWasUsedAlready && !consent.type.isRecurring() && !consent.type.isAutomatic()) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.invalidConsent.used", "Consentimento já utilizado em outro débito")

        if (debitAdapter.currency != ExternalDebit.DEFAULT_CURRENCY) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.currency", "Moeda não condiz com os dados autorizados pelo usuário")

        if (!consent.type.isAutomatic()) {
            if (debitAdapter.originType.isAddresKey()) {
                String pixKey = ExternalDebitConsentPixInfo.query([column: "pixKey", debitConsent: consent]).get()
                if (pixKey != debitAdapter.pixKey) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.pixKey", "Chave Pix não condiz com os dados autorizados pelo usuário")
            }

            if (consent.value != debitAdapter.value) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.value", "Valor não condiz com os dados autorizados pelo usuário")

            Map consentReceiver = ExternalDebitConsentReceiver.query([columnList: ["account", "agency"], debitConsent: consent]).get()

            if (consentReceiver.account != debitAdapter.receiver.account) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.account", "Número da conta do recebedor não condiz com os dados autorizados pelo usuário")
            if (consentReceiver.agency != debitAdapter.receiver.agency) DomainUtils.addErrorWithErrorCode(validatedDomain, "openFinance.paymentDiffersFromConsent.agency", "Número da agência do recebedor não condiz com os dados autorizados pelo usuário")
        }

        return validatedDomain
    }

    private Boolean hasInvalidQrCode(QrCodeAdapter qrCodeAdapter, ExternalDebitPixInfo pixInfo, ExternalDebit externalDebit) {
        if (!qrCodeAdapter) return true
        if (pixInfo.conciliationIdentifier && (qrCodeAdapter.conciliationIdentifier != pixInfo.conciliationIdentifier)) return true
        if (qrCodeAdapter.receiver.account != pixInfo.receiverAccount) return true
        if (qrCodeAdapter.receiver.ispb != pixInfo.receiverIspb) return true
        if (pixInfo.pixKey && (qrCodeAdapter.pixKey != pixInfo.pixKey)) return true
        if (pixInfo.receiverAgency != BankAccountUtils.formatAgencyNumber(qrCodeAdapter.receiver.agency)) return true
        if (qrCodeAdapter.value && (externalDebit.value != qrCodeAdapter.value)) return true

        return false
    }

    private Boolean hasDivergentReceiver(AddressKeyAdapter addressKeyAdapter, ExternalDebitPixInfo pixInfo) {
        if (!addressKeyAdapter) return true
        if (BankAccountUtils.formatAgencyNumber(addressKeyAdapter.accountNumber.agency) != pixInfo.receiverAgency) return true
        if (AccountNumber.parseAccount(addressKeyAdapter.accountNumber.account) != AccountNumber.parseAccount(pixInfo.receiverAccount)) return true
        if (AccountNumber.parseAccountDigit(addressKeyAdapter.accountNumber.account) != AccountNumber.parseAccountDigit(pixInfo.receiverAccount)) return true
        if (addressKeyAdapter.accountNumber.ispb != pixInfo.receiverIspb) return true

        return false
    }
}

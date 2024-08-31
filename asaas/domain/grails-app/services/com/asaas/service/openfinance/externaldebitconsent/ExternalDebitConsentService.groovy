package com.asaas.service.openfinance.externaldebitconsent

import com.asaas.customer.CustomerStatus
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.openfinance.ExternalPaymentTransactionInitiator
import com.asaas.domain.openfinance.externaldebit.ExternalDebit
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsent
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentPayer
import com.asaas.domain.openfinance.externaldebitconsent.ExternalDebitConsentPixInfo
import com.asaas.domain.openfinance.externaldebitconsent.automatic.ExternalAutomaticDebitConsentInfo
import com.asaas.domain.user.User
import com.asaas.integration.aws.managers.OpenFinanceSnsClientProxy
import com.asaas.integration.aws.managers.SnsManager
import com.asaas.integration.openfinance.v3.parser.OpenFinanceParser
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.log.AsaasLogger
import com.asaas.openfinance.WebhookNotificationData
import com.asaas.openfinance.automatic.externalautomaticdebitconsent.adapter.ExternalAutomaticDebitConsentAdapter
import com.asaas.openfinance.externaldebitconsent.adapter.ExternalDebitConsentAdapter
import com.asaas.openfinance.externaldebitconsent.adapter.base.BaseExternalDebitConsentAdapter
import com.asaas.openfinance.externaldebitconsent.adapter.base.children.ReceiverAccountAdapter
import com.asaas.openfinance.externaldebitconsent.enums.ExternalDebitConsentOriginRequesterCreatedVersion
import com.asaas.openfinance.externaldebitconsent.enums.ExternalDebitConsentRejectReason
import com.asaas.openfinance.externaldebitconsent.enums.ExternalDebitConsentStatus
import com.asaas.pix.PixCheckoutValidator
import com.asaas.pix.vo.transaction.PixExternalDebitVO
import com.asaas.sns.SnsTopicArn
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.TransactionUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import grails.validation.ValidationException
import org.apache.commons.lang.NotImplementedException

@Transactional
class ExternalDebitConsentService {

    def externalDebitConsentReceiverService
    def externalDebitConsentPayerService
    def externalDebitConsentPixInfoService
    def externalDebitConsentOriginRequesterService
    def criticalActionService
    def externalPaymentTransactionInitiatorService
    def grailsApplication
    def recurringCheckoutScheduleOpenFinanceService

    public ExternalDebitConsent save(BaseExternalDebitConsentAdapter consentAdapter) {
        ExternalDebitConsent consent = saveBaseConsent(consentAdapter)
        if (consent.hasErrors()) throw new ValidationException(null, consent.getErrors())

        switch (consentAdapter) {
            case ExternalDebitConsentAdapter:
                consent = saveDebitConsent(consentAdapter, consent)
                break
            case ExternalAutomaticDebitConsentAdapter:
                consent = saveAutomaticDebitConsent(consentAdapter, consent)
                break
            default:
                throw new NotImplementedException("Tipo de consentimento não permitido")
        }

        if (consent.hasErrors()) throw new ValidationException(null, consent.getErrors())

        if (consentAdapter.payer) {
            consent.payer = externalDebitConsentPayerService.save(consent, consentAdapter.payer)
        }

        externalDebitConsentOriginRequesterService.save(consent, consentAdapter.createdVersion, consentAdapter.apiName)

        ExternalPaymentTransactionInitiator externalPaymentTransactionInitiator = externalPaymentTransactionInitiatorService.createOrUpdate(consentAdapter)
        consent.externalPaymentTransactionInitiator = externalPaymentTransactionInitiator

        consent.save(failOnError: true)

        return consent
    }

    public ExternalDebitConsent authorize(ExternalDebitConsent externalDebitConsent, User authorizedByUser, Long groupId, String authorizationToken) {
        externalDebitConsent = validateCriticalAction(authorizedByUser.customer, externalDebitConsent, groupId, authorizationToken)
        if (externalDebitConsent.hasErrors()) return externalDebitConsent

        externalDebitConsent = validatePayerData(externalDebitConsent, authorizedByUser.customer)
        if (externalDebitConsent.hasErrors()) return externalDebitConsent

        BusinessValidation validation = externalDebitConsent.canBeAuthorized()
        if (!validation.isValid()) {
            DomainUtils.addError(externalDebitConsent, validation.getFirstErrorMessage())
            return externalDebitConsent
        }

        externalDebitConsent.customer = authorizedByUser.customer
        externalDebitConsent.status = ExternalDebitConsentStatus.AUTHORIZED
        externalDebitConsent.authorizationDate = new Date()
        externalDebitConsent.authorizedByUser = authorizedByUser
        if (externalDebitConsent.getOriginRequesterCreatedVersion().isV4()) externalDebitConsent.consumptionDateLimit = CustomDateUtils.sumMinutes(new Date(), ExternalDebitConsent.EXPIRATION_MINUTES_TO_BE_CONSUMED)
        externalDebitConsent.save(failOnError: true)
        return externalDebitConsent
    }

    public ExternalDebitConsent reject(ExternalDebitConsent externalDebitConsent) {
        BusinessValidation validation = externalDebitConsent.canBeRejected()
        if (!validation.isValid()) {
            DomainUtils.addError(externalDebitConsent, validation.getFirstErrorMessage())
            return externalDebitConsent
        }

        externalDebitConsent.status = ExternalDebitConsentStatus.REJECTED
        externalDebitConsent.rejectReason = ExternalDebitConsentRejectReason.REJECTED_BY_USER
        externalDebitConsent.save(failOnError: true)

        sendWebhookNotification(externalDebitConsent)

        return externalDebitConsent
    }

    public ExternalDebitConsent setAsRevoked(ExternalDebitConsent externalDebitConsent) {
        BusinessValidation validation = externalDebitConsent.canBeRevoked()
        if (!validation.isValid()) {
            DomainUtils.addError(externalDebitConsent, validation.getFirstErrorMessage())
            return externalDebitConsent
        }

        externalDebitConsent.status = ExternalDebitConsentStatus.REVOKED
        externalDebitConsent.save(failOnError: true)

        return externalDebitConsent
    }

    public ExternalDebitConsent expireByAuthorizationTimeExceeded(ExternalDebitConsent externalDebitConsent) {
        if (externalDebitConsent.status.isRejected()) return externalDebitConsent

        externalDebitConsent.status = ExternalDebitConsentStatus.REJECTED
        externalDebitConsent.rejectReason = ExternalDebitConsentRejectReason.EXPIRED_AUTHORIZATION_TIME
        externalDebitConsent.save(failOnError: true)

        sendWebhookNotification(externalDebitConsent)

        return externalDebitConsent
    }

    public ExternalDebitConsent expireByConsumptionTimeExceeded(ExternalDebitConsent externalDebitConsent) {
        if (externalDebitConsent.status.isRejected()) return externalDebitConsent

        externalDebitConsent.status = ExternalDebitConsentStatus.REJECTED
        externalDebitConsent.rejectReason = ExternalDebitConsentRejectReason.EXPIRED_AUTHORIZATION_CONSUMPTION
        externalDebitConsent.save(failOnError: true)

        sendWebhookNotification(externalDebitConsent)

        return externalDebitConsent
    }

    public ExternalDebitConsent schedule(ExternalDebitConsent debitConsent) {
        BusinessValidation validation = debitConsent.canBeScheduled()
        if (!validation.isValid()) {
            DomainUtils.addError(debitConsent, validation.getFirstErrorMessage())
            return debitConsent
        }

        debitConsent.status = ExternalDebitConsentStatus.SCHEDULED
        debitConsent.save(failOnError: true)

        sendWebhookNotification(debitConsent)
        return debitConsent
    }

    public ExternalDebitConsent consume(ExternalDebitConsent debitConsent) {
        if (debitConsent.status.isConsumed()) return debitConsent

        BusinessValidation validation = debitConsent.canBeConsumed()
        if (!validation.isValid()) {
            DomainUtils.addError(debitConsent, validation.getFirstErrorMessage())
            return debitConsent
        }

        debitConsent.consumptionDate = new Date()
        debitConsent.status = ExternalDebitConsentStatus.CONSUMED
        debitConsent.save(failOnError: true)

        sendWebhookNotification(debitConsent)

        return debitConsent
    }

    public void processValidation() {
        List<Long> consentIdList = ExternalDebitConsent.query(["column": "id", "customer[isNotNull]": true, status: ExternalDebitConsentStatus.AWAITING_VALIDATION, ignoreCustomer: true]).list()

        for (Long consentId : consentIdList) {
            Utils.withNewTransactionAndRollbackOnError {
                ExternalDebitConsent externalDebitConsent = ExternalDebitConsent.query([id: consentId, ignoreCustomer: true]).get()

                ExternalDebitConsentPixInfo externalDebitConsentPixInfo = ExternalDebitConsentPixInfo.query(debitConsentId: consentId).get()

                AsaasError asaasError = PixCheckoutValidator.validateValue(externalDebitConsent.customer, new PixExternalDebitVO(externalDebitConsentPixInfo))
                if (asaasError) {
                    AsaasLogger.info("ExternalDebitConsentService.processValidation >> consentId: ${consentId}, errorMessage: ${asaasError.getMessage()}")
                    externalDebitConsent.status = ExternalDebitConsentStatus.REJECTED
                    externalDebitConsent.rejectReason = ExternalDebitConsentRejectReason.VALUE_EXCEEDS_LIMIT
                } else {
                    externalDebitConsent.status = ExternalDebitConsentStatus.AWAITING_AUTHORIZATION
                }
                externalDebitConsent.save(failOnError: true)
            }
        }
    }

    public Boolean hasTotalAmountBeenReached(ExternalDebitConsent debitConsent) {
        BigDecimal totalAllowedAmountInConsent = ExternalAutomaticDebitConsentInfo.query(["column": "totalAllowedAmount", "consent": debitConsent]).get() as BigDecimal
        BigDecimal totalAmount = ExternalDebit.query(["column": "value", consent: debitConsent, customer: debitConsent.customer]).list(readOnly: true)?.sum() as BigDecimal

        return totalAmount >= totalAllowedAmountInConsent
    }

    private ExternalDebitConsent saveDebitConsent(ExternalDebitConsentAdapter consentAdapter, ExternalDebitConsent consent) {
        consent.value = consentAdapter.value
        if (consentAdapter.scheduledDate) {
            consent.scheduledDate = consentAdapter.scheduledDate
            consent.consumptionDateLimit = CustomDateUtils.setTimeToEndOfDay(consentAdapter.scheduledDate)
        }

        if (consentAdapter.recurringSchedule) {
            consent.recurringCheckoutSchedule = recurringCheckoutScheduleOpenFinanceService.save(consentAdapter.recurringSchedule)

            if (consent.recurringCheckoutSchedule.hasErrors()) {
                DomainUtils.copyAllErrorsWithErrorCodeFromObject(consent.recurringCheckoutSchedule, consent)

                return consent
            }
        }

        consent.save(failOnError: true)

        externalDebitConsentReceiverService.save(consent, consentAdapter.receiver)

        if (consent.operationType.isPix()) {
            externalDebitConsentPixInfoService.save(consent, consentAdapter.pixInfo)
        }

        return consent
    }

    private ExternalDebitConsent saveAutomaticDebitConsent(ExternalAutomaticDebitConsentAdapter consentAdapter, ExternalDebitConsent consent) {
        if (consentAdapter.expirationDateTime) {
            consent.consumptionDateLimit = consentAdapter.expirationDateTime
            consent.save(failOnError: true)
        }

        for (ReceiverAccountAdapter receiver : consentAdapter.receiverList) {
            externalDebitConsentReceiverService.save(consent, receiver)
        }

        return consent
    }

    private ExternalDebitConsent saveBaseConsent(BaseExternalDebitConsentAdapter consentAdapter) {
        ExternalDebitConsent validatedConsent = validateBuildBaseConsent(consentAdapter)
        if (validatedConsent.hasErrors()) return validatedConsent

        ExternalDebitConsent consent = new ExternalDebitConsent()
        consent.status = buildSaveBaseConsentStatus(consentAdapter)
        consent.type = consentAdapter.type
        consent.operationType = consentAdapter.operationType
        consent.externalIdentifier = UUID.randomUUID().toString()
        consent.authorizationDateLimit = CustomDateUtils.sumMinutes(new Date(), ExternalDebitConsent.EXPIRATION_MINUTES_TO_BE_AUTHORIZED)
        consent.ispbInitiator = consentAdapter.ispbInitiator
        consent.customer = consentAdapter.customer
        consent.consumptionDateLimit = CustomDateUtils.sumMinutes(new Date(), ExternalDebitConsent.EXPIRATION_MINUTES_TO_BE_CONSUMED)

        consent.save(failOnError: true)

        return consent
    }

    private ExternalDebitConsentStatus buildSaveBaseConsentStatus(BaseExternalDebitConsentAdapter consentAdapter) {
        if (consentAdapter instanceof ExternalAutomaticDebitConsentAdapter) return ExternalDebitConsentStatus.AWAITING_AUTHORIZATION

        return ExternalDebitConsentStatus.AWAITING_VALIDATION
    }

    private ExternalDebitConsent validateCriticalAction(Customer customer, ExternalDebitConsent externalDebitConsent, Long groupId, String authorizationToken) {
        CriticalActionGroup criticalAction = criticalActionService.authorizeGroup(customer, groupId, authorizationToken)

        if (!criticalAction.isAuthorized()) {
            String errorCode = "criticalAction.error.invalid.token.withRemainingAuthorizationAttempts"
            List errorArguments = [criticalAction.getRemainingAuthorizationAttempts()]

            if (criticalAction.getRemainingAuthorizationAttempts() == 1) {
                errorCode = "criticalAction.error.invalid.token.withoutRemainingAuthorizationAttempts"
                errorArguments = [AuthorizationDevice.findCurrentTypeDescription(customer)]
            }

            DomainUtils.addError(externalDebitConsent, Utils.getMessageProperty(errorCode, errorArguments))
            return externalDebitConsent
        }

        return externalDebitConsent
    }

    private ExternalDebitConsent validatePayerData(ExternalDebitConsent externalDebitConsent, Customer customer) {
        Integer customerWithSameDocumentCount = Customer.query([cpfCnpj: customer.cpfCnpj, "status[notIn]": CustomerStatus.inactive()]).count()
        if (customerWithSameDocumentCount > 1) throw new RuntimeException("Usuário tem mais de uma conta vinculado ao mesmo documento. [customerId: ${customer.id}]")

        ExternalDebitConsentPayer consentPayer = ExternalDebitConsentPayer.query([debitConsent: externalDebitConsent]).get()

        if (!consentPayer.accountNumber) return DomainUtils.addError(externalDebitConsent, "Os dados do pagador são divergentes.")

        Boolean hasValidAgency = (customer.accountNumber.agency == consentPayer.agency)
        Boolean hasValidAccountNumber = (customer.accountNumber.account == consentPayer.account)

        if (!hasValidAgency || !hasValidAccountNumber) DomainUtils.addError(externalDebitConsent, "Os dados do pagador são divergentes.")

        return externalDebitConsent
    }

    private ExternalDebitConsent validateBuildBaseConsent(BaseExternalDebitConsentAdapter consentAdapter) {
        ExternalDebitConsent validateConsent = new ExternalDebitConsent()

        Boolean isTransferToSameAccount = validateIfTransferToSameAccount(consentAdapter)
        if (isTransferToSameAccount) return DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.isTransferToSameAccount", "A conta indicada pelo usuário para recebimento é a mesma selecionada para o pagamento")

        if (consentAdapter.type.isUnique() || consentAdapter.type.isRecurring()) return validateConsentFromPaymentInitiation(consentAdapter)

        return validateConsent
    }

    private ExternalDebitConsent validateConsentFromPaymentInitiation(BaseExternalDebitConsentAdapter consentAdapter) {
        ExternalDebitConsent validateConsent = new ExternalDebitConsent()

        if (!consentAdapter.scheduledDate && !consentAdapter.creationDate && !consentAdapter.recurringSchedule) return DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.uninformedParameter.shouldInformPaymentDate", "Data de pagamento inválida. É necessário informar o campo [date], [schedule] ou [recurringSchedule].")
        if (consentAdapter.scheduledDate && consentAdapter.creationDate) return DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDate.shouldInformOnlyOneDate", "Data de pagamento inválida. Os campos [date] e [schedule] não podem existir simultaneamente.")

        Boolean scheduledDateIsInvalid = consentAdapter.scheduledDate && consentAdapter.scheduledDate <= new Date()
        if (!scheduledDateIsInvalid) scheduledDateIsInvalid = consentAdapter.scheduledDate && consentAdapter.scheduledDate > CustomDateUtils.sumYears(new Date(), ExternalDebitConsent.MAXIMUM_PERIOD_FOR_SCHEDULING_IN_YEARS)

        if (scheduledDateIsInvalid) DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDate.invalidSchedule", "Data de pagamento inválida. Para agendamentos, a data deverá ser maior que hoje ou no máximo em até um ano corrido.")
        if (consentAdapter.creationDate && (consentAdapter.creationDate != new Date().clearTime())) DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDate.overdue", "Data de pagamento inválida no contexto, por exemplo, data no passado. Para pagamentos únicos deve ser informada a data atual, do dia corrente.")

        if (!consentAdapter.receiver.personType) DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidParameter.personType", "Campo [personType] não reconhecido.")
        if (!consentAdapter.operationType) DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.type", "Campo [type] não reconhecido.")
        if (consentAdapter.currency != ExternalDebit.DEFAULT_CURRENCY) DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.currency", "Moeda inválida.")
        if (consentAdapter.value <= 0) DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.amount", "O campo [amount] não pode ser menor ou igual a zero.")

        Boolean consentIsInvalid = consentAdapter.pixInfo.originType.isManual() && (consentAdapter.pixInfo.qrCodePayload || consentAdapter.pixInfo.pixKey)
        if (!consentIsInvalid) consentIsInvalid = consentAdapter.pixInfo.originType.isAddresKey() && consentAdapter.pixInfo.qrCodePayload
        if (consentIsInvalid) DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.data", "O campo [localInstrument] deve conter o valor DICT.")

        Boolean qrCodeShouldBeInformed = consentAdapter.pixInfo.originType.isQrCode() && !consentAdapter.pixInfo.qrCodePayload
        if (qrCodeShouldBeInformed) DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.data", "O campo [qrCode] deve ser informado.")

        Boolean agencyIsRequired = (consentAdapter.receiver.accountType.isCheckingAccount() || consentAdapter.receiver.accountType.isInvestimentAccount() || consentAdapter.receiver.accountType.isSalaryAccount())
        if (agencyIsRequired && !consentAdapter.receiver.agency) DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.issuerNotInformed", "Preenchimento obrigatório para os seguintes tipos de conta: CACC (CONTA_DEPOSITO_A_VISTA), SVGS (CONTA_POUPANCA) e SLRY (CONTA_SALARIO).")

        if (consentAdapter.pixInfo.originType.isQrCode()) {
            if (!consentAdapter.pixInfo.qrCodePayload) {
                DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.uninformedParameter.qrCodeIsNull", "O campo [qrCode] não foi informado.")
                return validateConsent
            }

            if (!PixUtils.isQrCodePayloadValid(consentAdapter.pixInfo.qrCodePayload)) {
                DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.qrCodeNotFound", "O campo [qrCode] não é válido.")
                return validateConsent
            }

            if (consentAdapter.pixInfo.originType.isDynamicQrCode()) {
                if (!PixUtils.isDynamicQrCode(consentAdapter.pixInfo.qrCodePayload)){
                    DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.qrCodeIsNotDynamic", "O QR Code informado não é dinâmico.")
                    return validateConsent
                }

                Boolean hasExternalDebitConsentWithSameQrCode = ExternalDebitConsentPixInfo.query([exists: true, qrCodePayload: consentAdapter.pixInfo.qrCodePayload, "externalDebitConsentStatus[in]": [ExternalDebitConsentStatus.AWAITING_AUTHORIZATION, ExternalDebitConsentStatus.AUTHORIZED, ExternalDebitConsentStatus.SCHEDULED, ExternalDebitConsentStatus.CONSUMED]]).get().asBoolean()
                if (hasExternalDebitConsentWithSameQrCode) {
                    DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.qrcodeAlreadyUsed", "Já existe um consentimento para esse QR Code.")
                    return validateConsent
                }
            }

            if (consentAdapter.pixInfo.originType.isStaticQrCode()) {
                if (!consentAdapter.pixInfo.qrCodePayload.contains(consentAdapter.pixInfo.pixKey)) {
                    DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.payloadNotContainsPixKey", "A chave informada não corresponde ao QrCode.")
                    return validateConsent
                }

                if (!consentAdapter.pixInfo.qrCodePayload.contains(consentAdapter.value.toString())) {
                    DomainUtils.addErrorWithErrorCode(validateConsent, "openFinance.invalidPaymentDetail.valueNotCorrespondToQrCode", "Valor informado é diferente do valor contido no QrCode.")
                    return validateConsent
                }
            }
        }

        return validateConsent
    }

    private Boolean validateIfTransferToSameAccount(BaseExternalDebitConsentAdapter externalDebitConsentAdapter) {
        if (!externalDebitConsentAdapter.payer) return false
        if (!PixUtils.isAsaasIspb(externalDebitConsentAdapter.receiver.ispb)) return false
        if ((externalDebitConsentAdapter.payer.agency != externalDebitConsentAdapter.receiver.agency)) return false
        if ((externalDebitConsentAdapter.payer.account + externalDebitConsentAdapter.payer.accountDigit) != externalDebitConsentAdapter.receiver.account) return false

        return true
    }

    private void sendWebhookNotification(ExternalDebitConsent externalDebitConsent) {
        Boolean sendWebhookNotification = grailsApplication.config.asaas.sns.sendNotificationWebhook
        if (!externalDebitConsent.externalPaymentTransactionInitiator.webhookUri || !sendWebhookNotification) return

        TransactionUtils.afterCommit({
            ExternalDebitConsentOriginRequesterCreatedVersion defaultApiVersion = ExternalDebitConsentOriginRequesterCreatedVersion.V2
            WebhookNotificationData webhookNotificationData = new WebhookNotificationData()
            webhookNotificationData.webhookUri = externalDebitConsent.externalPaymentTransactionInitiator.webhookUri
            webhookNotificationData.apiVersion = (externalDebitConsent.getOriginRequesterCreatedVersion() ?: defaultApiVersion).toString().toLowerCase()
            webhookNotificationData.lastUpdate = CustomDateUtils.fromDate(new Date(), CustomDateUtils.UTC_FORMAT)
            webhookNotificationData.externalIdentifier = OpenFinanceParser.parseUrn(externalDebitConsent.externalIdentifier)
            webhookNotificationData.status = OpenFinanceParser.parseExternalDebitConsentStatus(externalDebitConsent.status)
            webhookNotificationData.notificationType = externalDebitConsent.type.isAutomatic() ? "automatic-consents" : "consents"

            new SnsManager(OpenFinanceSnsClientProxy.CLIENT_INSTANCE, SnsTopicArn.OPEN_FINANCE_WEBHOOK_NOTIFICATION).sendMessage(webhookNotificationData)
        })
    }
}

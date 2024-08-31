package com.asaas.service.refundrequest

import com.asaas.domain.bill.BillAsaasPayment
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentAfterConfirmEvent
import com.asaas.domain.payment.PaymentAfterCreditEvent
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.refundrequest.RefundRequest
import com.asaas.domain.refundrequest.RefundRequestBankAccount
import com.asaas.domain.refundrequest.RefundRequestDocument
import com.asaas.domain.refundrequest.RefundRequestHistory
import com.asaas.domain.transferbatchfile.TransferReturnFileItem
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentRefundStatus
import com.asaas.payment.PaymentStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.refundrequest.RefundRequestDocumentOwner
import com.asaas.refundrequest.RefundRequestDocumentType
import com.asaas.refundrequest.RefundRequestDocumentVO
import com.asaas.refundrequest.RefundRequestStatus
import com.asaas.transferbatchfile.TransferBatchFileItemStatus
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class RefundRequestService {

    def asaasErpAccountingStatementService
    def fileService
    def financialTransactionService
    def grailsApplication
    def grailsLinkGenerator
    def messageService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def paymentRefundService
    def promotionalCodeService
    def receivableAnticipationValidationService
    def smsSenderService
    def timelineEventService
    def treasureDataService

    public RefundRequest saveAdmin(Long paymentId, Map refundInfo) {
        Payment payment = Payment.get(paymentId)

        return save(payment, refundInfo, true)
    }

    public RefundRequest save(Long paymentId, Customer customer) {
        Payment payment = Payment.find(paymentId, customer.id)

        Map refundInfo = [name: payment.customerAccount.name, email: payment.customerAccount.email, mobilePhone: payment.customerAccount.mobilePhone, feeRefund: true]

        if (!payment.canCreateRefundRequest()) {
            throw new BusinessException(payment.refundDisabledReason)
        }
        return save(payment, refundInfo, false)
    }

    public RefundRequest save(Payment payment, Map refundInfo, Boolean adminRequest) {
        if (!payment.isReceived()) {
            payment.refundDisabledReason = "Só é possível estornar cobranças recebidas."
            throw new BusinessException(payment.refundDisabledReason)
        }

        if (!PaymentUndefinedBillingTypeConfig.equivalentToBoleto(payment) && !payment.isDebitCard()) {
            payment.refundDisabledReason = "Só é possível estornar cobranças com forma de pagamento boleto ou cartão de débito."
            throw new BusinessException(payment.refundDisabledReason)
        }

        if (!validatePreviousTransferReturnFileItens(payment)) {
            payment.refundDisabledReason = "Não é possível solicitar estorno da cobrança ${[payment.getInvoiceNumber()]} pois ela já foi estornada."
            throw new BusinessException(payment.refundDisabledReason)
        }

        if (BillAsaasPayment.shouldRefundBankslipPaidInternally(payment)) {
            payment.refundDisabledReason = "Não é possível solicitar estorno da cobrança ${[payment.getInvoiceNumber()]} pois ela foi paga através de um Pague Contas do Asaas."
            throw new BusinessException(payment.refundDisabledReason)
        }

        Boolean existsPaymentAfterConfirmOrCreditEvent = PaymentAfterConfirmEvent.existsForPayment(payment.id) || PaymentAfterCreditEvent.existsForPayment(payment.id)
        if (existsPaymentAfterConfirmOrCreditEvent) {
            payment.refundDisabledReason = Utils.getMessageProperty("paymentRefund.cannotBeRefunded.atTheMoment")
            throw new BusinessException(payment.refundDisabledReason)
        }

        RefundRequest refundRequest = new RefundRequest(payment: payment, mobilePhone: refundInfo.mobilePhone, name: refundInfo.name, email: refundInfo.email, publicId: RefundRequest.buildPublicId(), creator: UserUtils.getCurrentUser(), observations: refundInfo.reason)

        refundRequest.save(failOnError: true)

        paymentRefundService.refund(payment, [refundRequest: refundRequest])

        payment.refundFee = payment.getAsaasValue()
        payment.status = PaymentStatus.REFUND_REQUESTED
        payment.save(failOnError: true)

        receivableAnticipationValidationService.onPaymentChange(payment)

        if (refundInfo.feeRefund) {
            BigDecimal feeWithDiscountApplied = promotionalCodeService.consumeFeeDiscountBalance(refundRequest.payment.provider, RefundRequest.FEE_VALUE, refundRequest)
            refundRequest.fee = feeWithDiscountApplied
            refundRequest.save(failOnError: true)

            financialTransactionService.saveRefundRequestFee(refundRequest)
        }

        timelineEventService.createRefundRequestCreatedEvent(refundRequest)
        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)

        notifyCustomerAccountRefundCreated(refundRequest, adminRequest)
        saveHistory(refundRequest, refundInfo.reason)

        return refundRequest
    }

    public RefundRequest saveBankAccountAndUploadDocuments(String publicId, Map refundInfo) {
        RefundRequest.withNewTransaction { transaction ->
            try{
                RefundRequest refundRequest = RefundRequest.query([publicId: publicId]).get()
                Customer customer = refundRequest.payment.provider

                if (!refundRequest) return

                if (!RefundRequestStatus.canUploadDocuments().contains(refundRequest.status)) {
                    DomainUtils.addError(refundRequest, "Não foi possível realizar a operação.")
                    return refundRequest
                }

                if (cpfCnpjHasChanged(refundRequest, refundInfo.bankAccount.cpfCnpj)) {
                    RefundRequest validatedRefundRequest = validateRequiredDocuments(RefundRequestDocumentOwner.convert(refundInfo.bankAccount.ownerType), refundInfo.listOfRefundRequestDocumentVo)
                    if (validatedRefundRequest.hasErrors()) return validatedRefundRequest
                }

                RefundRequestBankAccount lastBankAccountInfo = refundRequest.bankAccount
                if (lastBankAccountInfo) {
                    lastBankAccountInfo.deleted = true
                    lastBankAccountInfo.save(failOnError: true)
                }

                buildAndSaveBankAccount(refundRequest, refundInfo)

                if (refundRequest.getDocuments() && refundInfo.listOfRefundRequestDocumentVo) {
                    refundRequest.getDocuments().each {
                        it.deleted = true
                        it.save(failOnError: true)
                    }
                }
                if (refundInfo.listOfRefundRequestDocumentVo) {
                    for (RefundRequestDocumentVO refundRequestDocumentVo : refundInfo.listOfRefundRequestDocumentVo) {
                        AsaasFile documentFile = fileService.saveFileFromTemporary(customer, refundRequestDocumentVo.tempFileId)

                        RefundRequestDocument refundRequestDocument = new RefundRequestDocument()
                        refundRequestDocument.refundRequest = refundRequest
                        refundRequestDocument.asaasFile = documentFile
                        refundRequestDocument.type = refundRequestDocumentVo.type
                        refundRequestDocument.save(failOnError: true)
                    }
                }

                refundRequest.status = RefundRequestStatus.WAITING_AUTHORIZATION
                refundRequest.save(failOnError: true)

                saveHistory(refundRequest, null)

                return refundRequest
            } catch (Exception exception) {
                AsaasLogger.error("RefundRequestService.saveBankAccountAndUploadDocuments >> Erro ao salvar conta bancária e fazer upload de documentos. [publicId: ${publicId}]", exception)
                transaction.setRollbackOnly()
                throw exception
            }
        }
    }

    private RefundRequestBankAccount buildAndSaveBankAccount(RefundRequest refundRequest, Map refundInfo) {
        refundInfo.bankAccount.ownerBirthDate = CustomDateUtils.fromString(refundInfo.bankAccount.ownerBirthDate)
        RefundRequestBankAccount refundRequestBankAccount = new RefundRequestBankAccount(refundInfo.bankAccount + [refundRequest: refundRequest])
        refundRequestBankAccount.save(failOnError: true)
        return refundRequestBankAccount
    }

    public RefundRequest approve(RefundRequest refundRequest, String observations) {
        refundRequest.status = RefundRequestStatus.APPROVED
        refundRequest.analyst = UserUtils.getCurrentUser()
        messageService.sendRefundRequestAnalysisResult(refundRequest)
        refundRequest.save(flush: true, failOnError: true)
        saveHistory(refundRequest, observations)

        treasureDataService.track(refundRequest.payment.provider, TreasureDataEventType.REFUND_ANALYZED, [refundRequestId: refundRequest.id])

        return refundRequest
    }
    public RefundRequest deny(Long refundRequestId, String denialReason, String observations) {
        RefundRequest refundRequest = RefundRequest.get(refundRequestId)
        refundRequest.status = RefundRequestStatus.DENIED
        refundRequest.denialReason = denialReason
        refundRequest.analyst = UserUtils.getCurrentUser()

        refundRequest.save(flush: true, failOnError: true)

        saveHistory(refundRequest, observations)

        messageService.notifyCustomerAccountRefundRequestDenied(refundRequest, denialReason)

        paymentPushNotificationRequestAsyncPreProcessingService.save(refundRequest.payment, PushNotificationRequestEvent.PAYMENT_REFUND_DENIED, [denialReason: denialReason])

        String sms ="Seu estorno de ${FormUtils.formatCurrencyWithMonetarySymbol(refundRequest.payment.value)} precisa de atenção. Acesse o link para verificar o motivo e adicione dados para refaze-lo: ${generateRefundShortenedLink(refundRequest.publicId)}"
        smsSenderService.send(sms, refundRequest.mobilePhone, false, [:])

        treasureDataService.track(refundRequest.payment.provider, TreasureDataEventType.REFUND_ANALYZED, [refundRequestId: refundRequest.id])

        return refundRequest
    }

    public RefundRequest cancel(Long refundRequestId, String observations) {
        RefundRequest refundRequest = RefundRequest.get(refundRequestId)

        if (refundRequest.status.isCancelled()) return refundRequest

        refundRequest.status = RefundRequestStatus.CANCELLED
        refundRequest.analyst = UserUtils.getCurrentUser()
        refundRequest.save(flush: true, failOnError: true)

        saveHistory(refundRequest, observations)

        financialTransactionService.reverseRefundRequest(refundRequest, null)
        cancelPaymentRefundIfExists(refundRequest)

        Payment payment = refundRequest.payment
        payment.refundFee = Utils.bigDecimalFromString('0')
        payment.status = PaymentStatus.RECEIVED
        payment.save(failOnError: true)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
        receivableAnticipationValidationService.onPaymentChange(payment)

        return refundRequest
    }

    public RefundRequest expire(Long refundRequestId) {
        RefundRequest.withNewTransaction { transaction ->
            try{
                RefundRequest refundRequest = RefundRequest.get(refundRequestId)
                refundRequest.status = RefundRequestStatus.EXPIRED
                refundRequest.analyst = UserUtils.getCurrentUser()
                refundRequest.save(flush: true, failOnError: true)

                saveHistory(refundRequest, null)

                Payment payment = refundRequest.payment
                payment.refundFee = 0
                payment.status = PaymentStatus.RECEIVED
                payment.save(failOnError: true)

                financialTransactionService.reverseRefundRequest(refundRequest, null)
                cancelPaymentRefundIfExists(refundRequest)

                messageService.notifyProviderWhenRefundRequestExpire(refundRequest)

                messageService.notifyCustomerAccountWhenRefundRequestExpire(refundRequest)

                timelineEventService.createRefundRequestExpiredEvent(refundRequest)

                asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
                receivableAnticipationValidationService.onPaymentChange(payment)

                return refundRequest
            } catch (Exception exception) {
                AsaasLogger.error("RefundRequestService.expire >> Erro ao expirar reembolso. [refundRequestId: ${refundRequestId}]", exception)
                transaction.setRollbackOnly()
            }
        }
    }

    public RefundRequest updateStatus(RefundRequest refundRequest, RefundRequestStatus status) {
        if (status == RefundRequestStatus.PAID) {
            return setAsPaid(refundRequest)
        } else if (status == RefundRequestStatus.APPROVED) {
            return setAsApproved(refundRequest)
        } else if (status == RefundRequestStatus.TRANSFER_ERROR) {
            return setAsTransferError(refundRequest)
        } else if (status == RefundRequestStatus.BANK_PROCESSING) {
            return setAsBankProcessing(refundRequest)
        }
    }

    public RefundRequest setAsPaid(RefundRequest refundRequest) {
        if (refundRequest.status == RefundRequestStatus.PAID) return refundRequest

        refundRequest.status = RefundRequestStatus.PAID
        refundRequest.save(failOnError: true)

        saveHistory(refundRequest, null)

        if (refundRequest.payment.isRefunded()) {
            AsaasLogger.warn("RefundRequestService.setAsPaid >> A cobrança já está estornada. [RefundRequest: ${refundRequest.id}]")
            return refundRequest
        }

        PaymentRefund paymentRefund = PaymentRefund.query([payment: refundRequest.payment]).get()
        if (paymentRefund) {
            if (paymentRefund.status != PaymentRefundStatus.PENDING) {
                AsaasLogger.warn("RefundRequestService.setAsPaid >> A PaymentRefund não está pendente. [RefundRequest: ${refundRequest.id}]")
                return refundRequest
            }

            paymentRefundService.executeRefund(paymentRefund, false)
        } else {
            paymentRefundService.executeRefund(refundRequest.payment, null)
        }

        if (refundRequest.email) {
            messageService.sendPaidRefundRequest(refundRequest)
        }

        String sms = "A transferência de estorno de seu pagamento a ${refundRequest.payment.getProvider().buildTradingName()}, no valor de ${FormUtils.formatCurrencyWithMonetarySymbol(refundRequest.payment.value)}, acaba de ser realizada."
        smsSenderService.send(sms, refundRequest.mobilePhone, false, [:])

        return refundRequest
    }

    public RefundRequest setAsApproved(RefundRequest refundRequest) {
        refundRequest.status = RefundRequestStatus.APPROVED
        refundRequest.save(flush: true, failOnError: true)

        saveHistory(refundRequest, null)

        return refundRequest
    }

    public RefundRequest setAsTransferError(RefundRequest refundRequest) {
        refundRequest.status = RefundRequestStatus.TRANSFER_ERROR
        refundRequest.save(flush: true, failOnError: true)

        saveHistory(refundRequest, null)

        String transferErrorReason = refundRequest.getTransferErrorReason()

        sendRefundRequestTransferErrorToAccountManager(refundRequest, transferErrorReason)

        sendRefundRequestTransferErrorToCustomerAccount(refundRequest, transferErrorReason)

        return refundRequest
    }

    public RefundRequest setAsBankProcessing(RefundRequest refundRequest) {
        refundRequest.status = RefundRequestStatus.BANK_PROCESSING
        refundRequest.save(flush: true, failOnError: true)

        saveHistory(refundRequest, null)

        return refundRequest
    }

    public String generateRefundLink(String refundPublicId) {
        String link = grailsLinkGenerator.link(controller: 'refundRequest', action: 'upload', id: refundPublicId, absolute: true)

        return link
    }

    private String generateRefundShortenedLink(String refundPublicId) {
        String shortenedLink = String.valueOf(grailsApplication.config.asaas.app.shortenedUrl) + grailsLinkGenerator.link(controller: 'refundRequest', action: 'upload', id: refundPublicId, absolute: false)

        return shortenedLink
    }

    private RefundRequest validateRequiredDocuments(RefundRequestDocumentOwner ownerType, List<RefundRequestDocumentVO> listOfRefundRequestDocumentVo) {
        RefundRequest validatedRefundRequest = new RefundRequest()

        if (ownerType in [RefundRequestDocumentOwner.PARENTS, RefundRequestDocumentOwner.SIBLINGS, RefundRequestDocumentOwner.CHILDREN, RefundRequestDocumentOwner.SPOUSE]) {
            if (!listOfRefundRequestDocumentVo.any { it.type == RefundRequestDocumentType.IDENTIFICATION}) {
                DomainUtils.addError(validatedRefundRequest, "Selecione o arquivo do seu documento de identificação.")
                return validatedRefundRequest
            }

            if (!listOfRefundRequestDocumentVo.any { it.owner != RefundRequestDocumentOwner.OWN && it.type == RefundRequestDocumentType.BANK_ACCOUNT_OWNER_IDENTIFICATION}) {
                DomainUtils.addError(validatedRefundRequest, "Selecione o arquivo de identificação do titular da conta bancária.")
                return validatedRefundRequest
            }

            if (!listOfRefundRequestDocumentVo.any { it.type == RefundRequestDocumentType.PAYMENT_RECEIPT}) {
                DomainUtils.addError(validatedRefundRequest, "Selecione o arquivo do comprovante de pagamento.")
                return validatedRefundRequest
            }
        }

        if ((ownerType == RefundRequestDocumentOwner.SPOUSE) && !listOfRefundRequestDocumentVo.any { it.type == RefundRequestDocumentType.MARRIAGE_CERTIFICATE }) {
            DomainUtils.addError(validatedRefundRequest, "Selecione o arquivo a certidão de casamento.")
            return validatedRefundRequest
        }

        return validatedRefundRequest
    }

    public void sendRefundRequestTransferErrorToAccountManager(RefundRequest refundRequest, String transferErrorReason) {
        AsaasLogger.info("RefundRequest [${refundRequest.id}] >> Enviando email de falha de estorno para o gerente de contas do fornecedor")

        messageService.sendRefundRequestTransferErrorToAccountManager(refundRequest, transferErrorReason)
    }

    public void sendRefundRequestTransferErrorToCustomerAccount(RefundRequest refundRequest, String transferErrorReason) {
        AsaasLogger.info("RefundRequest [${refundRequest.id}] >> Enviando email de falha de estorno para >> ${refundRequest.name} - ${refundRequest.email}")

        messageService.sendRefundRequestTransferErrorToCustomerAccount(refundRequest, transferErrorReason)

        String link = String.valueOf(grailsApplication.config.asaas.app.shortenedUrl) + grailsLinkGenerator.link(controller: 'refundRequest', action: 'upload', id: refundRequest.publicId, absolute: false)
        String sms ="Seu estorno de ${FormUtils.formatCurrencyWithMonetarySymbol(refundRequest.payment.value)} falhou :(. Confira o motivo e adicione dados para refaze-lo: ${link}"
        smsSenderService.send(sms, refundRequest.mobilePhone, false, [:])
    }

    public byte[] buildPreview(Long id) {
        return fileService.buildFilePreview(id)
    }

    private Boolean validatePreviousTransferReturnFileItens(Payment payment) {
        List<RefundRequest> refundRequestList = RefundRequest.query([payment: payment]).list()

        if (!refundRequestList || refundRequestList.size() == 0) {
            return true
        }

        for (RefundRequest refundRequest in refundRequestList) {
            List<TransferReturnFileItem> transferReturnFileItemList = TransferReturnFileItem.query([refundRequest: refundRequest]).list()

            Boolean hasConfirmedItems = transferReturnFileItemList.any { item -> item.returnStatus == TransferBatchFileItemStatus.CONFIRMED }
            Boolean hasFailedInvalidAccountItems = transferReturnFileItemList.any { item -> item.returnStatus == TransferBatchFileItemStatus.FAILED_INVALID_ACCOUNT }

            if (hasConfirmedItems && !hasFailedInvalidAccountItems) {
                return false
            }
        }

        return true
    }

    public Boolean cpfCnpjHasChanged(RefundRequest refundRequest, String cpfCnpj) {
        return (refundRequest.bankAccount?.cpfCnpj != cpfCnpj)
    }

    private void saveHistory(RefundRequest refundRequest, String observations) {
        RefundRequestHistory refundRequestHistory = new RefundRequestHistory()
        refundRequestHistory.refundRequest = refundRequest
        refundRequestHistory.status = refundRequest.status
        refundRequestHistory.user = UserUtils.getCurrentUser()
        refundRequestHistory.observations = observations
        refundRequestHistory.save(flush:true, failOnError: true)
    }

    private void notifyCustomerAccountRefundCreated(RefundRequest refundRequest, Boolean adminRequest) {
        String sms = "Acesse ${generateRefundShortenedLink(refundRequest.publicId)} e insira os dados bancários para receber o estorno de ${com.asaas.utils.FormUtils.formatCurrencyWithMonetarySymbol(refundRequest.payment.value)} de ${refundRequest.payment?.provider?.getFirstName()}"
        smsSenderService.send(sms, refundRequest.mobilePhone, true, [:])

        if (adminRequest) {
            messageService.sendRefundRequestLinkToCustomerAccountCreatedByAdmin(refundRequest)
            return
        }
        messageService.sendRefundRequestLinkToCustomerAccountCreatedByProvider(refundRequest)
    }

    public void processExpired() {
        Calendar calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_MONTH, -1 * RefundRequest.DAYS_TO_EXPIRE)
        Date lastUpdatedDate = calendar.getTime()

        String hql = "Select rr.id from RefundRequest rr where not exists (select 1 from RefundRequestHistory rrh where rrh.refundRequest = rr and rrh.dateCreated > :date) and rr.status in (:statusList) and rr.deleted = false"
        Map params = [date: lastUpdatedDate, statusList: RefundRequestStatus.canUploadDocuments()]

        List<Long> refundRequestIdList = RefundRequest.executeQuery(hql, params)
        for (Long refundRequestId in refundRequestIdList) {
            expire(refundRequestId)
        }
    }

    private void cancelPaymentRefundIfExists(RefundRequest refundRequest) {
        PaymentRefund paymentRefund = PaymentRefund.query([payment: refundRequest.payment]).get()
        if (paymentRefund) paymentRefundService.cancel(paymentRefund)
    }
}

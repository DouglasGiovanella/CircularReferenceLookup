package com.asaas.service.invoice

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.CustomerProductVO
import com.asaas.domain.api.ApiRequestLogOriginEnum
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerFiscalConfig
import com.asaas.domain.customer.CustomerFiscalInfo
import com.asaas.domain.customer.CustomerProduct
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.installment.Installment
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceFiscalConfig
import com.asaas.domain.invoice.InvoiceItem
import com.asaas.domain.invoice.InvoiceTaxInfo
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentCustomerProduct
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.exception.BusinessException
import com.asaas.financialtransaction.FinancialTransactionType
import com.asaas.integration.invoice.api.manager.MunicipalRequestManager
import com.asaas.integration.invoice.api.vo.MunicipalFiscalOptionsVO
import com.asaas.integration.invoice.api.vo.MunicipalServiceVO
import com.asaas.invoice.CustomerInvoiceSummary
import com.asaas.invoice.InvoiceCreationPeriod
import com.asaas.invoice.InvoiceEstimatedTaxesType
import com.asaas.invoice.InvoiceFiscalVO
import com.asaas.invoice.InvoiceOriginType
import com.asaas.invoice.InvoiceProvider
import com.asaas.invoice.InvoiceStatus
import com.asaas.invoice.InvoiceType
import com.asaas.log.AsaasLogger
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.referral.ReferralStatus
import com.asaas.referral.ReferralValidationOrigin
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.apache.commons.lang.StringUtils

@Transactional
class CustomerInvoiceService {

    def asyncActionService
    def customerInvoiceAdminService
    def customerMessageService
    def customerMunicipalFiscalOptionsService
    def financialTransactionService
    def installmentService
    def invoiceFileService
    def invoiceFiscalConfigService
    def invoiceService
    def invoiceSynchronizeService
    def invoiceTaxInfoService
    def messageService
    def originRequesterInfoService
    def paymentCustomerProductService
    def paymentUpdateService
    def promotionalCodeService
    def pushNotificationRequestInvoiceService
    def receivableAnticipationCustomerInvoiceService
    def referralService

    public Boolean hasCreatedInvoice(Customer customer) {
        return Invoice.query([exists: true, customer: customer]).get().asBoolean()
    }

    public Invoice saveDetachedInvoice(Customer customer, InvoiceFiscalVO invoiceFiscalVo, Map params) {
        invoiceFiscalVo.fiscalConfigVo.invoiceCreationPeriod = InvoiceCreationPeriod.ON_CUSTOM_DATE

        params.paymentCustomerProduct = paymentCustomerProductService.save(CustomerProduct.find(params.customerProductId, customer.id))
        params.customerAccount = CustomerAccount.find(params.customerAccountId, customer.id)
        params.originType = InvoiceOriginType.DETACHED

        return save(customer, invoiceFiscalVo, params)
    }

    public Invoice savePaymentInvoice(Payment payment, InvoiceFiscalVO invoiceFiscalVo, Map params) {
        if (!payment.canBeInvoiced()) throw new BusinessException(payment.invoiceDisabledReason)

        params.payment = payment
        params.customerAccount = payment.customerAccount
        params.value = invoiceFiscalVo.value ?: payment.value
        params.effectiveDate = params.effectiveDate ?: new Date()
        params.originType = InvoiceOriginType.PAYMENT

        return save(payment.provider, invoiceFiscalVo, params)
    }

    public Invoice saveInstallmentInvoice(Installment installment, InvoiceFiscalVO invoiceFiscalVo, Map params) {
        if (!installment.canBeInvoiced()) throw new BusinessException(installment.invoiceDisabledReason)

        params.installment = installment
        params.customerAccount = installment.customerAccount
        params.value = invoiceFiscalVo.value ?: installment.getValue()
        params.effectiveDate = params.effectiveDate ?: new Date()
        params.originType = InvoiceOriginType.INSTALLMENT

        return save(installment.getProvider(), invoiceFiscalVo, params)
    }

    public Invoice replicateAndSynchronizeInvoice(Invoice invoice) {
        if (!invoice.status.isCancelled() && !invoice.status.isError()) throw new BusinessException("A nota informada não está cancelada ou com erro.")

        Map params = [:]
        params.payment = invoice.payment
        params.installment = invoice.installment
        params.customerAccount = invoice.customerAccount
        params.value = invoice.value
        params.originType = invoice.originType
        params.effectiveDate = new Date()
        params.serviceDescription = invoice.serviceDescription
        if (invoice.items) params.paymentCustomerProduct = invoice.items[0].paymentCustomerProduct

        InvoiceFiscalVO invoiceFiscalVo = InvoiceFiscalVO.build(invoice)
        invoiceFiscalVo.fiscalConfigVo.invoiceCreationPeriod = InvoiceCreationPeriod.ON_CUSTOM_DATE
        invoiceFiscalVo.shouldReplaceInvoice = false

        if (invoice.number) {
            params.replacedInvoiceId = invoice.id
            invoiceFiscalVo.shouldReplaceInvoice = true
        }

        Invoice replicatedInvoice = save(invoice.customer, invoiceFiscalVo, params)
        invoiceService.setAsPending(replicatedInvoice)

        return replicatedInvoice
    }

	public Invoice update(Long id, Customer customer, Map params) {
        Invoice invoice = Invoice.find(id, customer.id)
        if (!invoice.canBeUpdated()) return invoice

		Invoice invoiceValidate = validateUpdateInvoice(invoice, params)
		if (invoiceValidate.hasErrors()) return invoiceValidate

		processInvoiceEditableParams(invoice, params)
		invoice.estimatedTaxesDescription = buildInvoiceEstimatedTaxesDescription(invoice)

		if (invoice.isDirty('effectiveDate')) {
            if (invoice.status.isWaitingOverduePayment()) invoice.status = InvoiceStatus.SCHEDULED
			InvoiceFiscalConfig invoiceFiscalConfig = invoice.fiscalConfig
			invoiceFiscalConfig.invoiceCreationPeriod = InvoiceCreationPeriod.ON_CUSTOM_DATE
			invoiceFiscalConfig.save(failOnError: true)
		}

		if (invoice.isDirty()) invoice.save(failOnError: false)
		if (invoice.hasErrors()) return invoice

		invoiceTaxInfoService.update(invoice.taxInfo.id, params)

		pushNotificationRequestInvoiceService.save(PushNotificationRequestEvent.INVOICE_UPDATED, invoice)

		if (!Boolean.valueOf(params.updatePayment)) return invoice
        if (invoice.originType.isDetached()) return invoice

		CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(invoice, params)

        if (invoice.originType.isInstallment()) {
    		Installment installment = invoice.getInstallment()
			installment = installmentService.update(installment.id, installment.customerAccount.provider.id, [installment: installment, value: customerInvoiceSummary.updatedPaymentValue])
			if (installment.hasErrors()) throw new BusinessException(Utils.getMessageProperty("customerInvoice.update.error.installment.validation", [Utils.getMessageProperty(installment.errors.allErrors.first())]))
		} else if (invoice.originType.isPayment()) {
			Payment payment = invoice.getPayment()
			payment = paymentUpdateService.update([providerId: customer.id, payment: payment, value: customerInvoiceSummary.updatedPaymentValue])
			if (payment.hasErrors()) throw new BusinessException(Utils.getMessageProperty("customerInvoice.update.error.payment.validation", [payment.getInvoiceNumber(), Utils.getMessageProperty(payment.errors.allErrors.first())]))
		}

		return invoice
	}

	public void updateInvoice(Installment installment) {
		Invoice invoice = installment.getInvoice()
		if (!invoice.canBeUpdated()) return

		invoice.value = installment.calculateInvoiceValue()
		if (!invoice.fiscalConfig.invoiceCreationPeriod.invoiceOnCustomDate()) invoice.effectiveDate = Invoice.getEstimatedEffectiveDate(invoice.fiscalConfig.invoiceCreationPeriod, installment.getNextPendingPayment()?.dueDate ?: installment.getFirstRemainingPayment()?.dueDate, invoice.fiscalConfig.daysBeforeDueDate)
		if (invoice.effectiveDate.before(new Date().clearTime())) invoice.effectiveDate = new Date().clearTime()
		invoice.save(flush: false, failOnError: true)

		InvoiceItem invoiceItem = invoice.getInvoiceItem()
		invoiceItem.value = invoice.value
		invoiceItem.save(flush: false, failOnError: true)

		pushNotificationRequestInvoiceService.save(PushNotificationRequestEvent.INVOICE_UPDATED, invoice)
	}

	public void updateInstallmentInvoice(Payment payment) {
		Invoice invoice = payment.getInvoice()

		if (payment.installment.installmentCount > 0 && payment.installment.calculateInvoiceValue() > 0) {
			updateInvoice(payment.installment)
		} else {
			cancelInvoice(payment.installment)
		}
	}

	public void cancelInvoice(Payment payment) {
		Invoice invoice = payment.getInvoice()
		if (!invoice.canBeCanceled()) return

		cancel(invoice)
	}

	public void cancelInvoice(Installment installment) {
		Invoice invoice = installment.getInvoice()
		if (!invoice.canBeCanceled()) return

		cancel(invoice)
	}

    public void cancelScheduledInvoices(Customer customer) {
        List<InvoiceStatus> scheduledInvoiceStatusList = [InvoiceStatus.SCHEDULED, InvoiceStatus.WAITING_OVERDUE_PAYMENT]

        List<Invoice> invoiceList = Invoice.query([customer: customer, statusList: scheduledInvoiceStatusList]).list()
        if (!invoiceList) return

        try {
            for (Invoice invoice : invoiceList) {
                cancel(invoice)
            }
        } catch (Exception e) {
            transactionStatus.setRollbackOnly()
            AsaasLogger.error("CustomerInvoiceService.cancelScheduledInvoices: Não foi possível realizar o cancelamento em lote de notas pendentes do cliente [${customer.id}]", e)
            throw e
        }
    }

    public void processScheduledInvoices() {
        List<Long> scheduledInvoiceIdList = Invoice.query([column: "id", asaasInvoice: false, statusList: [InvoiceStatus.SCHEDULED], 'effectiveDate[le]': new Date(), sort: "customer.id", order: "asc"]).list()

        for (Long scheduledInvoiceId : scheduledInvoiceIdList) {
			try {
                Utils.withNewTransactionAndRollbackOnError({
                    Invoice invoice = Invoice.get(scheduledInvoiceId)
                    if(!invoice.status.isScheduled()) return
                    if (invoice.fiscalConfig.invoiceCreationPeriod.invoiceOnPaymentConfirmation()) return

                    if (invoice.fiscalConfig.receivedOnly && invoice.fiscalConfig.invoiceCreationPeriod.invoiceOnNextMonth()) {
                        Payment payment

                        if (invoice.originType.isPayment()) {
                            payment = invoice.getPayment()
                        } else if (invoice.originType.isInstallment()) {
                            payment = invoice.getInstallment().getFirstConfirmedOrReceivedPayment()
                        }

                        if (!payment || (!payment.isConfirmed() && !payment.isReceived())) return
                    }
                    invoiceService.setAsPending(invoice)
                }, [onError: { Exception e -> throw e}])
            } catch (Exception e) {
                Utils.withNewTransactionAndRollbackOnError { invoiceService.setAsError(Invoice.get(scheduledInvoiceId), e.getMessage()?.take(255)) }
                AsaasLogger.error("Erro ao processar INVOICE [${scheduledInvoiceId}]", e)
            }
        }
    }

    public void synchronizePendingInvoices() {
        final Integer maxBatchSize = 120
        final Integer errorMessageMaxLength = 255

        List<Long> pendingInvoiceIdList = Invoice.query([
            column: "id",
            asaasInvoice: false,
            statusList: [InvoiceStatus.PENDING],
            sort: "customer.id",
            order: "asc"
        ]).list(max: maxBatchSize)

        for (Long invoiceId : pendingInvoiceIdList) {
            try {
                Utils.withNewTransactionAndRollbackOnError({
                    synchronize(invoiceId)
                }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
            } catch (Exception exception) {
                Utils.withNewTransactionAndRollbackOnError {
                    Invoice invoice = Invoice.get(invoiceId)
                    if (!invoice.status.isPending()) return

                    String errorMessage = exception.getMessage()?.take(errorMessageMaxLength)
                    invoiceService.setAsError(invoice, errorMessage)
                }

                if (exception instanceof BusinessException) continue
                AsaasLogger.error("CustomerInvoiceService.synchronizePendingInvoices >> Erro ao sincronizar INVOICE [${invoiceId}]", exception)
            }
        }
    }

	public Map synchronize(Long invoiceId) {
		Invoice invoice = Invoice.get(invoiceId)

		if (!invoice.statusAllowSynchronization()) {
			throw new BusinessException("A Nota fiscal está com status ${Utils.getMessageProperty('invoice.status.provider.' + invoice.status).toUpperCase()} e não pode ser emitida agora.")
		}

		if (!invoice.canBeSynchronized()) {
			invoiceService.setAsError(invoice, invoice.synchronizationErrors.join("; ").take(2000))
			return [success: false, messageList: invoice.synchronizationErrors]
		}

        if (!customerCanSynchronizeInvoice(invoice.customerId)) throw new BusinessException("Não foi possível emitir essa nota fiscal.")

		invoice = invoiceSynchronizeService.synchronize(invoice.id)
		if (invoice.status.isError()) return [success: false, messageList: [invoice.statusDescription] ]

		return [success: true]
	}

	public void afterUpdateStatus(Invoice invoice) {
		if (invoice.isAsaasInvoice()) throw new RuntimeException("Not allowed to execute this process to ASAAS Invoice.")

        if (shouldDownloadFiles(invoice)) invoiceFileService.downloadCustomerInvoiceFiles(invoice)

		if (invoice.status.isAuthorized()) {
            applyFeeIfNecessary(invoice)
            if (invoice.canSendByEmailToCustomerAccount()) messageService.sendAuthorizedInvoiceToCustomerAccount(invoice)
		}
	}

	public Invoice cancel(Invoice invoice) {
		if (!invoice.canBeCanceled()) {
			DomainUtils.addError(invoice, "A nota fiscal está com status ${Utils.getMessageProperty('invoice.status.provider.' + invoice.status)} e não pode ser cancelada.")
			return invoice
		}

        if (invoice.status.isAuthorized() && hasBankSlipOrPixAnticipationActive(invoice)) {
            DomainUtils.addError(invoice, "A nota fiscal está vinculada a uma antecipação e não pode ser cancelada.")
            return invoice
        }

		if (invoice.status.isAuthorized() || invoice.status.isCancellationDenied()) {
            cancelAuthorizedInvoice(invoice)
        } else {
            invoiceService.setAsCanceled(invoice)
        }

        receivableAnticipationCustomerInvoiceService.denyReceivableAnticipationAwaitingInvoiceAttachment(invoice)
        return invoice
	}

    public Invoice cancelByAccountDisabling(Invoice invoice) {
        if (!InvoiceStatus.listCancelableByAccountDisablingStatus().contains(invoice.status)) {
            throw new RuntimeException("O status [${ invoice.status }] não pode ser cancelado pelo fluxo de desativação de conta")
        }

        List<InvoiceStatus> authorizableStatusList = [
            InvoiceStatus.PENDING,
            InvoiceStatus.SYNCHRONIZED
        ]

        if (authorizableStatusList.contains(invoice.status)) {
            InvoiceStatus previousStatus = invoice.status
            cancelAuthorizedInvoice(invoice)

            if (invoice.status.isCancellationDenied()) {
                AsaasLogger.warn("CustomerInvoiceService.cancelByAccountDisabling >> Requisição para cancelamento negado da Nota Fiscal: [${ invoice.id }] com status: [${ previousStatus }]")
            }
        } else {
            invoiceService.setAsCanceled(invoice)
        }

        receivableAnticipationCustomerInvoiceService.denyReceivableAnticipationAwaitingInvoiceAttachment(invoice)
        return invoice
	}

	public Boolean supportsCancellation(Invoice invoice) {
        if (invoice.provider.isAsaasErp()) return false

        MunicipalFiscalOptionsVO municipalFiscalOptionsVO = customerMunicipalFiscalOptionsService.getOptions(invoice.customer)
		return municipalFiscalOptionsVO.supportsCancellation
	}

	public void processScheduleInvoiceIfNecessary(Payment payment) {
		Invoice scheduledInvoice = Invoice.getPaymentOrInstallmentInvoice(payment)
		if (!scheduledInvoice?.status?.isScheduled()) return

		if (scheduledInvoice.fiscalConfig.receivedOnly && scheduledInvoice.fiscalConfig.invoiceCreationPeriod.invoiceOnNextMonth()) {
			invoiceService.setAsError(scheduledInvoice, "Não foi possível realizar a emissão porque a cobrança desta Nota fiscal não foi recebida e a emissão está configurada para ser feita apenas em cobranças recebidas.")
		}
	}

    public void notifyCustomersAboutInvoiceErrors() {
        Date yesterday = CustomDateUtils.getYesterday()
        List<Long> providerIdList = Invoice.withError([distinct: "customer.id", effectiveDate: yesterday, disableSort: true]).list()

        Utils.forEachWithFlushSession(providerIdList, 50, { Long providerId ->
            Utils.withNewTransactionAndRollbackOnError({
                final Integer maxNumberOfInvoicesToShowDetails = 6

                List<Long> invoiceList = Invoice.withError([column: "id", customerId: providerId, effectiveDate: yesterday, sort: "customer.id", order: "asc"]).list()

                List<Map> invoiceInfoForMailList = invoiceList
                    .take(maxNumberOfInvoicesToShowDetails)
                    .collect { return invoiceService.buildInvoiceInfoForMail(it) }

                customerMessageService.notifyCustomerAboutInvoiceErrors(Customer.read(providerId), invoiceList.size(), invoiceInfoForMailList, yesterday)
            }, [onError: { Exception e ->
                AsaasLogger.error("Erro ao enviar notificação de notas fiscais com erro para o fornecedor [${providerId}]", e)
            }])
        })
    }

    public void updateToWaitingPaymentIfNecessary() {
        List<Invoice> invoiceIdList = Invoice.query([column: "id", asaasInvoice: false, status: InvoiceStatus.SCHEDULED, 'effectiveDate[le]': new Date().clearTime(), "fiscalConfig.invoiceCreationPeriod": InvoiceCreationPeriod.ON_PAYMENT_CONFIRMATION]).list()

        Utils.forEachWithFlushSession(invoiceIdList, 50, { Long invoiceId ->
            Utils.withNewTransactionAndRollbackOnError({
                Invoice invoice = Invoice.get(invoiceId)
                Payment payment = invoice.getPayment()

                if (!payment) payment = invoice.getInstallment().getFirstRemainingPayment()

                if (!payment.isOverdue()) return

                invoice.status = InvoiceStatus.WAITING_OVERDUE_PAYMENT
                invoice.save(failOnError: true)
            })
        })
    }

	public void notifyTeamAboutInvoiceStuckInSynchronization() {
		Date limitDate = CustomDateUtils.sumHours(new Date(), Invoice.MAX_HOURS_FOR_STUCK_INVOICE_NOTIFICATION * -1)
		List<Invoice> invoiceList = Invoice.query([asaasInvoice: false, statusList: [InvoiceStatus.SYNCHRONIZED, InvoiceStatus.PROCESSING_CANCELLATION], 'lastUpdated[le]': limitDate]).list()

		if (!invoiceList) return

		messageService.notifyTeamAboutInvoiceStuckInSynchronization(invoiceList)
	}

    public Map buildDetachedInvoiceSummary(Long customerId, Map params) {
        Map detachedInvoiceParams = buildDetachedInvoiceParams(params)

        CustomerAccount customerAccount = CustomerAccount.find(detachedInvoiceParams.customerAccountId, customerId)
        InvoiceFiscalVO invoiceFiscalVO = InvoiceFiscalVO.build(detachedInvoiceParams)

        CustomerProductVO customerProductVO
        if (detachedInvoiceParams.customerProductId) {
            customerProductVO = CustomerProductVO.build(CustomerProduct.find(detachedInvoiceParams.customerProductId, customerId))
        } else {
            Map customerProductParams = [
                municipalServiceExternalId: detachedInvoiceParams.municipalServiceCode,
                name: detachedInvoiceParams.municipalServiceName ?: detachedInvoiceParams.municipalServiceCode,
                municipalServiceDescription: detachedInvoiceParams.municipalServiceName ?: detachedInvoiceParams.municipalServiceCode,
                issTax: detachedInvoiceParams.taxInfo.issTax,
                defaultProduct: false
            ]
            customerProductVO = CustomerProductVO.build(customerProductParams)
        }

        CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(invoiceFiscalVO, customerProductVO)

        return [customerInvoiceSummary: customerInvoiceSummary, customerAccountName: customerAccount.name, customerProductVO: customerProductVO]
    }

    public Boolean shouldDownloadFiles(Invoice invoice) {
        if (!InvoiceStatus.getDownloadableInvoiceFileStatus().contains(invoice.status)) return false
        if (!invoice.pdfUrl && !invoice.xmlUrl) return false

        return true
    }

    public Invoice save(Customer customer, InvoiceFiscalVO invoiceFiscalVo, Map params) {
        Invoice validateInvoice = validateSaveInvoice(params)

        if (validateInvoice.hasErrors()) return validateInvoice

        Invoice invoice = new Invoice()

        if (params.invoiceProvider?.isAsaasErp()) {
            Boolean isRequesOriginAsaasErp = ApiRequestLogOriginEnum.convert(RequestUtils.getRequestOrigin())?.isAsaasErp()
            if (!isRequesOriginAsaasErp) throw new RuntimeException("O invoiceProvider ASAAS_ERP é específico para requests do Asaas ERP!")
            invoice.provider = params.invoiceProvider
        } else {
            invoice.provider = InvoiceProvider.ENOTAS
        }

        invoice.type = InvoiceType.NFSE
        invoice.status = InvoiceStatus.SCHEDULED
        invoice.observations = invoiceFiscalVo.observations
        invoice.deductions = invoiceFiscalVo.deductions
        invoice.effectiveDate = CustomDateUtils.toDate(params.effectiveDate)
        invoice.customer = customer
        invoice.customerAccount = params.customerAccount
        invoice.value = params.value
        invoice.originType = params.originType
        invoice.externalReference = params.externalReference

        if (params.replacedInvoiceId) {
            Map replaceableInvoiceFilter = [id: params.replacedInvoiceId]

            if (params.payment) replaceableInvoiceFilter.payment = params.payment
            if (params.installment) replaceableInvoiceFilter.installment = params.installment

            invoice.replacedInvoice = Invoice.replaceable(replaceableInvoiceFilter).get()
            if (!invoice.replacedInvoice) throw new BusinessException("A nota fiscal não pode ser substituída.")
        } else if (params.payment && invoiceFiscalVo.shouldReplaceInvoice) {
            invoice.replacedInvoice = Invoice.replaceable([payment: params.payment]).get()
        }

        invoice.save(failOnError: true)

        InvoiceItem invoiceItem = saveInvoiceItem(invoice, params)
        invoice.addToItems(invoiceItem)

        invoice.serviceDescription = params.serviceDescription ?: invoice.buildServiceDescription()
        invoice.estimatedTaxesDescription = buildInvoiceEstimatedTaxesDescription(invoice)
        invoice.publicId = "inv_" + StringUtils.leftPad(String.valueOf(invoice.id), 12, '0')
        invoice.save(failOnError: true)

        InvoiceTaxInfo invoiceTaxInfo = invoiceTaxInfoService.save(invoice, invoiceFiscalVo?.taxInfoVo)
        InvoiceFiscalConfig invoiceFiscalConfig = invoiceFiscalConfigService.save(invoice, invoiceFiscalVo?.fiscalConfigVo)

        if (InvoiceStatus.getScheduledToCustomerList().contains(invoice.status)) {
            if (!invoiceFiscalConfig.invoiceCreationPeriod.invoiceOnCustomDate() && !invoice.originType.isDetached()) {

                Date dueDate = params.payment ? params.payment.dueDate : params.installment.getFirstRemainingPayment().dueDate
                invoice.effectiveDate = Invoice.getEstimatedEffectiveDate(invoiceFiscalConfig.invoiceCreationPeriod, dueDate, invoiceFiscalConfig.daysBeforeDueDate)

                if (invoiceFiscalConfig.invoiceCreationPeriod.invoiceOnPaymentConfirmation() && params.payment?.isOverdue()) invoice.status = InvoiceStatus.WAITING_OVERDUE_PAYMENT
            }

            if (invoice.effectiveDate && invoice.effectiveDate < new Date().clearTime()) invoice.effectiveDate = new Date().clearTime()
            invoice.observations = buildInvoiceObservations(invoice, invoiceTaxInfo)
            invoice.save(flush: true, failOnError: true)

            pushNotificationRequestInvoiceService.save(PushNotificationRequestEvent.INVOICE_CREATED, invoice)

            referralService.saveUpdateToValidatedReferralStatus(invoice.customerId, ReferralValidationOrigin.PRODUCT_USAGE)
        }

        originRequesterInfoService.save(invoice)

        return invoice
	}

    public void applyFeeIfNecessary(Invoice invoice) {
        Boolean alreadyChargedFee = FinancialTransaction.query([exists: true, invoice: invoice, transactionType: FinancialTransactionType.INVOICE_FEE]).get().asBoolean()
        if (alreadyChargedFee) return

        BigDecimal feeWithDiscountApplied = promotionalCodeService.consumeFeeDiscountBalance(invoice.customer, CustomerFee.calculateServiceInvoiceFee(invoice.customer), invoice)
        invoice.fee = feeWithDiscountApplied

        invoice.save(failOnError: true)

        financialTransactionService.saveInvoiceFee(invoice, null)
    }

    public void scheduleUpdateInvoiceStuckInSynchronization() {
        Date limitDate = CustomDateUtils.sumHours(new Date(), Invoice.MAX_HOURS_FOR_STUCK_INVOICE  * -1)
        List<Long> invoiceIdList = Invoice.query([column: "id", asaasInvoice: false, statusList: [InvoiceStatus.SYNCHRONIZED, InvoiceStatus.PROCESSING_CANCELLATION], 'lastUpdated[le]': limitDate]).list()

        Utils.forEachWithFlushSession(invoiceIdList, 50, { Long invoiceId ->
            Utils.withNewTransactionAndRollbackOnError({
                saveUpdateInvoiceStuckInSynchronizationAsyncAction(invoiceId)
            })
        })
    }

    public void processUpdateInvoiceStuckInSynchronization() {
        final Integer limit = 50
        List<Map> asyncActionDataList = listUpdateInvoiceStuckInSynchronization(limit)

        if (!asyncActionDataList) return

        Utils.forEachWithFlushSession(asyncActionDataList, 5, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                Long invoiceId = asyncActionData.invoiceId
                customerInvoiceAdminService.updateInvoiceFromPartner(invoiceId)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [ignoreStackTrace: true, onError: { Exception exception ->
                if (!(exception instanceof BusinessException)) {
                    AsaasLogger.error("CustomerInvoiceService.processUpdateInvoiceStuckInSynchronization >> Erro na atualização da nota fiscal. AsyncActionId: ${ asyncActionData.asyncActionId }", exception)
                }

                asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
            }])
        })
    }

    private void saveUpdateInvoiceStuckInSynchronizationAsyncAction(Long invoiceId) {
        Map asyncActionData = [invoiceId: invoiceId]
        asyncActionService.save(AsyncActionType.UPDATE_INVOICE_STUCK_IN_SYNCHRONIZATION, asyncActionData)
    }

    private List<Map> listUpdateInvoiceStuckInSynchronization(Integer limit) {
        return asyncActionService.listPending(AsyncActionType.UPDATE_INVOICE_STUCK_IN_SYNCHRONIZATION, limit)
    }

    private Invoice validateSaveInvoice(Map params) {
        Invoice invoiceValidate = new Invoice()

        if (!params.customerAccount) {
            DomainUtils.addError(invoiceValidate, Utils.getMessageProperty('customer.invoice.validation.error.invalid.customerAccount'))
        }

        if (!params.value) {
            DomainUtils.addError(invoiceValidate, Utils.getMessageProperty('customer.invoice.validation.error.blank.value'))
        } else if (Utils.toBigDecimal(params.value) <= 0) {
            DomainUtils.addError(invoiceValidate, Utils.getMessageProperty('customer.invoice.validation.error.invalid.value'))
        }

        Boolean isRequesOriginAsaasErp = ApiRequestLogOriginEnum.convert(RequestUtils.getRequestOrigin())?.isAsaasErp()
        if (!params.effectiveDate) {
            DomainUtils.addError(invoiceValidate, Utils.getMessageProperty('customer.invoice.validation.error.blank.effectiveDate'))
        } else if (!isRequesOriginAsaasErp && CustomDateUtils.toDate(params.effectiveDate).clearTime() < new Date().clearTime()) {
            DomainUtils.addError(invoiceValidate, Utils.getMessageProperty('customer.invoice.validation.error.invalid.effectiveDate'))
        }

        if (params.deductions && Utils.toBigDecimal(params.deductions) > Utils.toBigDecimal(params.value)) {
            DomainUtils.addError(invoiceValidate, Utils.getMessageProperty('customer.invoice.validation.error.deductionsAndDiscountsSum'))
        }

        return invoiceValidate
    }

    private InvoiceItem saveInvoiceItem(Invoice invoice, Map params) {
        PaymentCustomerProduct paymentCustomerProduct

        if (params.installment) {
            paymentCustomerProduct = PaymentCustomerProduct.query([installment: params.installment, sort: 'id', order: 'desc']).get()
        } else if (params.payment) {
            paymentCustomerProduct = PaymentCustomerProduct.query([payment: params.payment, sort: 'id', order: 'desc']).get()
        } else {
            paymentCustomerProduct = params.paymentCustomerProduct
        }

        InvoiceItem invoiceItem = new InvoiceItem()
        invoiceItem.paymentCustomerProduct = paymentCustomerProduct
        invoiceItem.value = invoice.value
        invoiceItem.customer = invoice.customer
        invoiceItem.invoice = invoice
        invoiceItem.payment = params.payment
        invoiceItem.installment = params.installment
        invoiceItem.save(failOnError: true)
        return invoiceItem
    }

	private Invoice validateUpdateInvoice(Invoice invoice, Map params) {
        Invoice invoiceValidate = new Invoice()

        if (params.value) {
            if (Utils.toBigDecimal(params.value) <= 0) {
                DomainUtils.addError(invoiceValidate, Utils.getMessageProperty('customer.invoice.validation.error.invalid.value'))
            }
        }

        if (params.effectiveDate) {
            if (CustomDateUtils.toDate(params.effectiveDate).clearTime() < new Date().clearTime()) {
                DomainUtils.addError(invoiceValidate, Utils.getMessageProperty('customer.invoice.validation.error.invalid.effectiveDate'))
            }
        }

        if (params.deductions) {
    		BigDecimal invoiceValue = Utils.toBigDecimal(params.value) ?: invoice.value

            if (Utils.toBigDecimal(params.deductions) > invoiceValue) {
                DomainUtils.addError(invoiceValidate, Utils.getMessageProperty('customer.invoice.validation.error.deductionsAndDiscountsSum'))
            }
        }

		return invoiceValidate
	}

	private Invoice processInvoiceEditableParams(Invoice invoice, Map invoiceParams) {
		if (!invoiceParams) return

		if (invoiceParams.serviceDescription) invoice.serviceDescription = invoiceParams.serviceDescription
		if (invoiceParams.observations != null) invoice.observations = invoiceParams.observations
		if (invoiceParams.value) invoice.value = Utils.toBigDecimal(invoiceParams.value)
		if (invoiceParams.deductions != null) invoice.deductions = Utils.toBigDecimal(invoiceParams.deductions)
		if (invoiceParams.effectiveDate) invoice.effectiveDate = invoiceParams.effectiveDate instanceof Date ? invoiceParams.effectiveDate : CustomDateUtils.fromString(invoiceParams.effectiveDate)
        if (invoiceParams.containsKey("externalReference")) invoice.externalReference = invoiceParams.externalReference

		return invoice
	}

    private Invoice cancelAuthorizedInvoice(Invoice invoice) {
        if(!supportsCancellation(invoice)) return invoiceService.setAsCanceled(invoice)

        invoice = invoiceSynchronizeService.cancel(invoice)
        if (invoice.status.isCancellationDenied()) DomainUtils.addError(invoice, invoice.statusDescription)

        return invoice
    }

    private Map buildDetachedInvoiceParams(Map params) {
        params.value = Utils.toBigDecimal(params.value)
        params.taxInfo.invoiceValue = params.value
        params.fiscalConfig.invoiceCreationPeriod = InvoiceCreationPeriod.ON_CUSTOM_DATE

        if (params.containsKey("customerProductId")) params.customerProductId = Utils.toLong(params.customerProductId)

        if (!params.deductions) params.deductions = 0
        if (!params.effectiveDate) params.effectiveDate = new Date()

        if (!params.taxInfo.cofinsPercent) params.taxInfo.cofinsPercent = 0
        if (!params.taxInfo.cofinsValue) params.taxInfo.cofinsValue = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(params.value, Utils.toBigDecimal(params.taxInfo.cofinsPercent))

        if (!params.taxInfo.csllPercent) params.taxInfo.csllPercent = 0
        if (!params.taxInfo.csllValue) params.taxInfo.csllValue = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(params.value, Utils.toBigDecimal(params.taxInfo.csllPercent))

        if (!params.taxInfo.irPercent) params.taxInfo.irPercent = 0
        if (!params.taxInfo.irValue) params.taxInfo.irValue = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(params.value, Utils.toBigDecimal(params.taxInfo.irPercent))

        if (!params.taxInfo.pisPercent) params.taxInfo.pisPercent = 0
        if (!params.taxInfo.pisValue) params.taxInfo.pisValue = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(params.value, Utils.toBigDecimal(params.taxInfo.pisPercent))

        if (!params.taxInfo.inssPercent) params.taxInfo.inssPercent = 0
        if (!params.taxInfo.inssValue) params.taxInfo.inssValue = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(params.value, Utils.toBigDecimal(params.taxInfo.inssPercent))

        if (!params.taxInfo.irValue) params.taxInfo.irValue = 0
        if (!params.taxInfo.irValue) params.taxInfo.irValue = BigDecimalUtils.calculateValueFromPercentageWithRoundDown(params.value, Utils.toBigDecimal(params.taxInfo.irPercent))

        return params
    }

    private Boolean hasBankSlipOrPixAnticipationActive(Invoice invoice) {
        ReceivableAnticipation anticipation = invoice.getPayment()?.getCurrentAnticipation()
        if (!anticipation) anticipation = invoice.getInstallment()?.getCurrentAnticipation()
        if (!anticipation) return false
        if (!anticipation.billingType.isBoletoOrPix()) return false
        if (ReceivableAnticipationStatus.getActiveStatuses().contains(anticipation.status)) return true
        return false
    }

    private Boolean customerCanSynchronizeInvoice(Long customerId) {
        Map customerFiscalInfoMap = CustomerFiscalInfo.query([columnList: ["id", "synchronizationDisabled"], customerId: customerId]).get()
        if (!customerFiscalInfoMap?.id) return false
        if (customerFiscalInfoMap.synchronizationDisabled) return false

        return true
    }

    private String buildInvoiceObservations(Invoice invoice, InvoiceTaxInfo invoiceTaxInfo) {
        String observations = invoice.observations ?: ""

        if (!invoiceTaxInfo) return observations

        final Map customerFiscalConfigMap = CustomerFiscalConfig.query([
            columnList: ["invoiceRetainedIrDescription", "invoiceRetainedCsrfDescription", "invoiceRetainedInssDescription"],
            customerId: invoice.customerId
        ]).get()
        if (!customerFiscalConfigMap) return observations

        List<String> observationsDetailList = []

        final Boolean canAddRetainedIrTaxesDescription = customerFiscalConfigMap.invoiceRetainedIrDescription && invoiceTaxInfo.irPercentage
        if (canAddRetainedIrTaxesDescription) observationsDetailList.add(customerFiscalConfigMap.invoiceRetainedIrDescription)

        final Boolean canAddRetainedCsrfTaxesDescription = customerFiscalConfigMap.invoiceRetainedCsrfDescription && invoiceTaxInfo.csllPercentage && invoiceTaxInfo.pisPercentage && invoiceTaxInfo.cofinsPercentage
        if (canAddRetainedCsrfTaxesDescription) observationsDetailList.add(customerFiscalConfigMap.invoiceRetainedCsrfDescription)

        final Boolean canAddRetainedInssTaxesDescription = customerFiscalConfigMap.invoiceRetainedInssDescription && invoiceTaxInfo.inssPercentage
        if (canAddRetainedInssTaxesDescription) observationsDetailList.add(customerFiscalConfigMap.invoiceRetainedInssDescription)

        final String observationsDetail = observationsDetailList.join("\n").trim()
        return [observations, observationsDetail].join("\n\n").trim()
    }

    private String buildInvoiceEstimatedTaxesDescription(Invoice invoice) {
        if (invoice.provider.isAsaasErp()) return

        Map customerFiscalConfigMap = CustomerFiscalConfig.query([
            columnList: ["invoiceEstimatedTaxesPercentage", "invoiceEstimatedTaxesType"],
            customerId: invoice.customerId
        ]).get()
        if (!customerFiscalConfigMap) return

        if (customerFiscalConfigMap.invoiceEstimatedTaxesType == InvoiceEstimatedTaxesType.IBPT) {
            try {
                MunicipalRequestManager municipalRequestManager = new MunicipalRequestManager(invoice.customer.city)
                MunicipalServiceVO municipalServiceVO = municipalRequestManager.getServiceList(
                    "codigo",
                    invoice.getCustomerProduct().municipalServiceExternalId,
                    500
                ).get(0)

                return Utils.getMessageProperty("customerFiscalConfig.invoiceEstimatedTaxesType.IBPT", [invoice.getFormattedEstimatedTaxValue(municipalServiceVO.countryTaxes + municipalServiceVO.stateTaxes + municipalServiceVO.cityTaxes), invoice.getFormattedEstimatedTaxValue(municipalServiceVO.countryTaxes), invoice.getFormattedEstimatedTaxValue(municipalServiceVO.stateTaxes), invoice.getFormattedEstimatedTaxValue(municipalServiceVO.cityTaxes)])
            } catch (Exception e) {
                throw new BusinessException("Não foi possível conectar ao IBPT para gerar os tributos dessa nota fiscal. Tente gerar a nota fiscal sem incluir os tributos aproximados automaticamente (Notas Fiscais -> Configurações)")
            }
        } else if (customerFiscalConfigMap.invoiceEstimatedTaxesType == InvoiceEstimatedTaxesType.CUSTOM) {
            return Utils.getMessageProperty("customerFiscalConfig.invoiceEstimatedTaxesType.CUSTOM", [invoice.getFormattedEstimatedTaxValue(customerFiscalConfigMap.invoiceEstimatedTaxesPercentage), customerFiscalConfigMap.invoiceEstimatedTaxesPercentage])
        }
    }
}

package com.asaas.service.installment

import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCardUtils
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerProductVO
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerProduct
import com.asaas.domain.installment.Installment
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentRefund
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.split.PaymentSplit
import com.asaas.exception.BusinessException
import com.asaas.invoice.CustomerInvoiceSummary
import com.asaas.invoice.InvoiceCreationPeriod
import com.asaas.invoice.InvoiceFiscalVO
import com.asaas.log.AsaasLogger
import com.asaas.notification.NotificationPriority
import com.asaas.payment.PaymentUtils
import com.asaas.split.PaymentRefundSplitVO
import com.asaas.user.UserUtils
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.MoneyUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils

import grails.orm.PagedResultList
import grails.transaction.Transactional
import grails.validation.ValidationException

import org.springframework.transaction.TransactionStatus

import java.math.RoundingMode

@Transactional
class InstallmentService {

    def asaasMoneyTransactionInfoService
	def boletoService
	def creditCardService
    def customerAlertNotificationService
	def customerInvoiceService
	def customerProductService
    def messageService
    def mobilePushNotificationService
    def notificationDispatcherPaymentNotificationOutboxService
    def paymentCampaignItemService
    def paymentCustomerProductService
    def paymentRefundService
    def paymentService
    def paymentSplitService
    def paymentUpdateService
    def receivableAnticipationValidationService
    def riskAnalysisPaymentService
    def springSecurityService
    def transactionReceiptService

    def onError(entity, Boolean failOnError) {
        if (failOnError) throw new ValidationException(null, entity.errors)

        transactionStatus.setRollbackOnly()
        return entity
    }

	public PagedResultList list(Long customerAccountId, Long providerId, Integer max, Integer offset, Map search) {
		return Installment.query((search ?: [:]) + [customerId: providerId, customerAccountId: customerAccountId]).list(max: max, offset: offset)
	}

	public Installment save(Map params, Boolean notifyFirstPayment, Boolean failOnError) {
        if (params.description) {
            params.description = params.description.trim()
        }

		Installment installment = validateSaveParams(params)
        if (installment.hasErrors()) return onError(installment, failOnError)

		CustomerAccount customerAccount = params.customerAccountId ? CustomerAccount.find(params.customerAccountId, springSecurityService.currentUser.customer.id) : params.customerAccount
		customerAccount.provider.validateAccountDisabled()

        Integer installmentCount = params.installmentCount ? (params.installmentCount instanceof Integer ? params.installmentCount : Integer.valueOf(params.installmentCount)) : null
        if (installmentCount && !customerAccount.provider.canCreateMorePaymentsToday(installmentCount)) {
            DomainUtils.addError(installment, "O número de parcelas informado excede o limite disponível. O limite de cobranças diário é de ${customerAccount.provider.getDailyPaymentsLimit()}. Para aumentá-lo contate seu gerente.")

            return onError(installment, failOnError)
        }

        BigDecimal value = 0
        BigDecimal totalValue = Utils.toBigDecimal(params.totalValue)
		Date dueDate = params.dueDate ? (params.dueDate instanceof Date ? params.dueDate : CustomDateUtils.fromString(params.dueDate)) : null
		Calendar calendar = Calendar.getInstance()
		calendar.setTime(dueDate)

		BillingType billingType = params.billingType instanceof BillingType ? params.billingType : BillingType.convert(params.billingType)

		installment = new Installment()
		installment.customerAccount = customerAccount
		installment.installmentCount = installmentCount
		installment.code = params.installmentCode
		installment.description = params.description
		installment.billingType = billingType
		installment.expirationDay = calendar.get(Calendar.DAY_OF_MONTH)
		installment.postalService = params.postalService.asBoolean()

        installment.save(failOnError: failOnError)
        if (installment.hasErrors()) return onError(installment, failOnError)

		installment.publicId = UUID.randomUUID()
		installment.createdBy = UserUtils.getCurrentUser() ?: params.createdBy

		if (totalValue) {
			value = (totalValue / installmentCount).setScale(2, RoundingMode.DOWN)
		} else {
			value = Utils.toBigDecimal(params.value)
            totalValue = value * installmentCount
		}

		InvoiceFiscalVO invoiceFiscalVo
		if (params.invoice) invoiceFiscalVo = InvoiceFiscalVO.build(params.invoice)

		if (Boolean.valueOf(params.invoice?.updatePayment)) {
			invoiceFiscalVo.value = value
	    	CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(invoiceFiscalVo)
	    	if (customerInvoiceSummary.paymentValueNeedsToBeUpdated()) {
	    		value = customerInvoiceSummary.updatedPaymentValue
                totalValue = customerInvoiceSummary.getUpdatedInstallmentTotalValue(installment, totalValue)
	    		invoiceFiscalVo.value = customerInvoiceSummary.currentPaymentValue * (invoiceFiscalVo.fiscalConfigVo.invoiceOnceForAllPayments ? installment.installmentCount : 1)
	    	} else {
	    		invoiceFiscalVo.value = null
	    	}
		}

		Map paymentInfoMap = [value: value,
                              billingType: billingType,
                              customerAccount: customerAccount,
                              installment: installment,
                              sendPaymentByPostalService: params.postalService.asBoolean(),
                              externalReference: params.externalReference,
                              import: params.import,
                              interest: params.interest,
                              fine: params.fine,
                              discount: params.discount,
                              boletoBank: params.boletoBank,
                              apiOrigin: params.apiOrigin,
                              asaasErpAccountingStatementExternalId: params.asaasErpAccountingStatementExternalId,
                              softDescriptorText: params.softDescriptorText,
                              paymentUndefinedBillingTypeConfig: params.paymentUndefinedBillingTypeConfig,
                              paymentCheckoutCallbackConfig: params.paymentCheckoutCallbackConfig]
        if (params.daysAfterDueDateToRegistrationCancellation) paymentInfoMap << [daysAfterDueDateToRegistrationCancellation: params.daysAfterDueDateToRegistrationCancellation]

        List<Payment> payments = []
		for (int month = 1; month <= installmentCount; month++) {
            if (totalValue) paymentInfoMap.value = PaymentUtils.calculateInstallmentValue(totalValue, installmentCount, month)

            if (params.splitInfo) paymentInfoMap.splitInfo = paymentSplitService.buildInstallmentSplitConfig(params.splitInfo, installmentCount, month)

            Boolean shouldNotify = (month == 1 && notifyFirstPayment)
			Payment payment = paymentService.save(paymentInfoMap + [dueDate: calculateNextDueDate(dueDate, month - 1), installmentNumber: month, description: buildDescription(month, installmentCount, params.description)], failOnError, shouldNotify)

            if (payment.hasErrors()) {
				if (failOnError) {
                     AsaasLogger.error("Erro ao criar uma cobrança de um novo parcelamento, motivos: ${payment.errors.allErrors.defaultMessage.join(', ')}")
                     throw new RuntimeException("Erro ao criar uma cobrança de um novo parcelamento.")
				} else {
					installment.payments = []
					installment.payments.add(payment)
					return installment
				}
			}

			payments.add(payment)
			installment.addToPayments(payment)
		}
		if (params.customerProduct) saveCustomerProduct(installment, CustomerProductVO.build(params.customerProduct))
		if (invoiceFiscalVo) saveInvoices(installment, invoiceFiscalVo, installment.getNotDeletedPayments(), [:])

        riskAnalysisPaymentService.analyzeInstallmentIfNecessary(installment)

        notificationDispatcherPaymentNotificationOutboxService.saveInstallmentUpdated(installment)

        for (Payment payment : payments) {
            Boolean shouldNotify = payment.installmentNumber == 1 && notifyFirstPayment
            notificationDispatcherPaymentNotificationOutboxService.savePaymentCreated(payment, shouldNotify, NotificationPriority.HIGH)
        }

		return installment
	}

	public Installment saveAndProcessCreditCardIfNecessary(Map fields) {
		Installment installment = save(fields, false, true)

        if (installment.billingType.isCreditCard() && CreditCardUtils.hasAtLeastOneCreditCardInfo(fields.creditCard)) {
            Payment firstRemainingPayment = installment.getFirstRemainingPayment()
            paymentService.processCreditCardPayment(firstRemainingPayment, fields)
            if (firstRemainingPayment.hasErrors()) {
                throw new ValidationException(null, firstRemainingPayment.errors)
            }
        }
		return installment
	}

	public void saveCustomerProduct(Installment installment, CustomerProductVO customerProductVo) {
		CustomerProduct customerProduct = customerProductService.findOrSave(installment.getProvider(), customerProductVo)
        paymentCustomerProductService.save(customerProduct, installment)

        for (Payment payment : installment.getNotDeletedPayments()) {
        	paymentCustomerProductService.save(customerProduct, payment)
        }
	}

	public List<Invoice> saveInvoices(Installment installment, InvoiceFiscalVO invoiceFiscalVo, List<Payment> listOfPayment, Map params) {
		if (invoiceFiscalVo.fiscalConfigVo.invoiceOnceForAllPayments) {
            Invoice invoice = customerInvoiceService.saveInstallmentInvoice(installment, invoiceFiscalVo, params)

			return [invoice]
        } else {
			List<Invoice> invoiceList = []
            Integer count = 0
            for (Payment payment : listOfPayment) {
                if (count > 0) invoiceFiscalVo.fiscalConfigVo.invoiceFirstPaymentOnCreation = false

            	Invoice invoice = customerInvoiceService.savePaymentInvoice(payment, invoiceFiscalVo, params)

				invoiceList.add(invoice)

                count++
            }

			return invoiceList
        }
	}

	public List<Invoice> saveInvoicesForExistingInstallment(Long installmentId, Customer customer, InvoiceFiscalVO invoiceFiscalVo, CustomerProductVO customerProductVo, Map params) {
		Installment installment = Installment.find(installmentId, customer.id)

		saveCustomerProduct(installment, customerProductVo)

		List<Payment> listOfPayment = []
		if (invoiceFiscalVo.fiscalConfigVo.invoiceCreationPeriod == InvoiceCreationPeriod.ON_DUE_DATE_MONTH) {
			listOfPayment = installment.getPendingPaymentsFromDate(CustomDateUtils.getFirstBusinessDayOfNextMonth().getTime().clearTime())
		} else if (invoiceFiscalVo.fiscalConfigVo.invoiceCreationPeriod == InvoiceCreationPeriod.ON_NEXT_MONTH) {
			listOfPayment = installment.getNotDeletedPayments().findAll { it.dueDate >= CustomDateUtils.getFirstBusinessDayOfCurrentMonth().getTime().clearTime() }
		} else {
			listOfPayment = installment.getPendingPayments()
		}

		listOfPayment.removeAll { it.hasActiveInvoice() }

		List<Invoice> invoiceList = saveInvoices(installment, invoiceFiscalVo, listOfPayment, params)

		if (!Boolean.valueOf(params.updatePayment)) return invoiceList

		CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(installment, invoiceFiscalVo, customerProductVo)
		if (!customerInvoiceSummary.paymentValueNeedsToBeUpdated()) return invoiceList

		update(installment.id, installment.customerAccount.provider.id, [installment: installment, value: customerInvoiceSummary.updatedPaymentValue])

		if (installment.hasErrors()) throw new BusinessException(Utils.getMessageProperty("customerInvoice.create.error.installment.validation", [Utils.getMessageProperty(installment.errors.allErrors.first().getCodes()[0])]))

		return invoiceList
	}

	public Installment update(id, Long providerId, Map params) {
        Installment installment = params.installment ?: Installment.find(id, providerId)

		if (!installment.canEdit()) throw new BusinessException("O parcelamento [${installment.id}] não pode ser atualizado.")

        if (params.containsKey("description")) installment = validateDescription(installment, params)
        if (installment.hasErrors()) return installment

		Map paramsToUpdate = [description: params.description] << updateExpirationDay(installment, params) << updateBillingTypeUpdate(installment, params) << updateValue(installment, params) << updateSendByPostalService(installment, params)

        if (params.splitInfo) {
            installment = validateSplitInfo(installment, params.splitInfo)
            if (installment.hasErrors()) return installment

            paramsToUpdate.splitInfo = params.splitInfo
        }

        updatePendingPayments(installment, paramsToUpdate)
        if (installment.hasErrors()) return installment

		installment.description = params.description
		installment.save(failOnError: true)

		return installment
	}

    public Installment updateInstallmentCount(Installment installment) {
        installment.installmentCount = installment.getNotDeletedPaymentsCount()

        installment.save(failOnError: true)

        notificationDispatcherPaymentNotificationOutboxService.saveInstallmentUpdated(installment)

        return installment
    }

	public void delete(id, Long customerId) {
		Installment.withNewTransaction { status ->
			try {
				Installment installment = Installment.find(id, customerId)

				deleteInstallmentOnly(installment)
				deletePayments(installment)
			} catch(Exception e) {
				status.setRollbackOnly()
				throw e
			}
		}
	}

	public void deleteInstallmentOnly(Installment installment) {
		if (installment.deleted) return

		if (!installment.canDelete()) throw new BusinessException("O parcelamento ${installment.getInvoiceNumber()} não pode ser removido. ${installment.editDisabledReason}")

		if (installment.isCreditCard()) transactionReceiptService.savePaymentDeleted(installment)

		if (installment.getInvoice()) customerInvoiceService.cancelInvoice(installment)

		paymentCampaignItemService.deleteFromInstallmentIfNecessary(installment)

		installment.deleted = true
		installment.save(failOnError: true)
	}

	public void deletePayments(Installment installment) {
		installment.getRemainingPaymentsThatCanBeDeleted().each { Payment payment ->
			paymentService.delete(payment, false)
		}
	}

    public List<Map> buildInstallmentOptionList(Customer customer, BigDecimal totalValue, BillingType billingType, Integer maxNumberOfInstallments) {
        List<Map> installmentOptionList = []
        Integer maxInstallmentCount = calculateMaxInstallmentNumberForInstallmentOptionList(totalValue, customer, billingType)

        if (maxNumberOfInstallments && maxNumberOfInstallments < maxInstallmentCount) {
            maxInstallmentCount = maxNumberOfInstallments
        }

        for (Integer i = 1; i <= maxInstallmentCount; i++) {
            Map installmentOption = [:]
            installmentOption.installmentCount = i
            installmentOption.value = BigDecimalUtils.roundDown((totalValue / i))
            installmentOptionList.add(installmentOption)
        }

        return installmentOptionList
    }

    public void notifyCustomerAboutInstallmentEnding(Installment installment) {
        final Integer minimumInstallmentCountToNotify = 3

        if (installment.installmentCount < minimumInstallmentCountToNotify) return
        if (installment.getNotReceivedPayments().size() != 1) return
        if (!CustomerParameter.getValue(installment.getProvider(), CustomerParameterName.ENABLE_NOTIFY_USERS_ON_INSTALLMENT_ENDING)) return

        customerAlertNotificationService.notifyInstallmentEnding(installment)
        messageService.sendNotificationToCustomerAboutInstallmentEnding(installment)
    }

    private Integer calculateMaxInstallmentNumberForInstallmentOptionList(BigDecimal totalValue, Customer customer, BillingType billingType) {
        BigDecimal minValue = billingType == BillingType.MUNDIPAGG_CIELO ? Installment.MIN_CREDIT_CARD_INSTALLMENT_PAYMENT_VALUE : Payment.getMinimumBankSlipAndPixValue(customer)
        Integer maxInstallmentCount = billingType == BillingType.BOLETO ? Installment.getMaxInstallmentCount(customer) : Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD
        Integer calculatedMaxInstallmentCount = BigDecimalUtils.min(BigDecimalUtils.roundDown(totalValue / minValue), maxInstallmentCount)

        if (calculatedMaxInstallmentCount == 0) calculatedMaxInstallmentCount = 1

        return calculatedMaxInstallmentCount
    }

	private Installment validateSaveParams(Map params) {
		Installment installment = new Installment()

		if (!params.customerAccount && !params.customerAccountId) {
			DomainUtils.addFieldError(installment, "customerAccount", "required")
		}

		if (!params.billingType) {
			DomainUtils.addFieldError(installment, "billingType", "required")
		}

		if (!params.dueDate) {
			DomainUtils.addFieldError(installment, "dueDate", "required")
		} else if ((params.dueDate instanceof Date ? params.dueDate : CustomDateUtils.fromString(params.dueDate)) < new Date().clearTime()) {
			DomainUtils.addFieldError(installment, "dueDate", "beforeToday")
		}

        if (params.containsKey("description")) installment = validateDescription(installment, params)

		CustomerAccount customerAccount = params.customerAccountId ? CustomerAccount.find(params.customerAccountId, springSecurityService.currentUser.customer.id) : params.customerAccount

        if (params.containsKey("splitInfo")) installment = validateSplitInfo(installment, params.splitInfo)

		installment = validateInstallmentsInfo(installment, params + [customer: customerAccount?.provider])

		return installment
	}

	public Installment validateUpdateParams(Map params) {
		Installment installment = new Installment()

		if (params.expirationDay && (params.expirationDay.toInteger() < 1 || params.expirationDay.toInteger() > 31)) {
			DomainUtils.addError(installment, "O dia de vencimento deve estar entre 1 e 31.")
		}

		return installment
	}

	public validateInstallmentsInfo(entity, Map params) {
		if (!params.value && !params.totalValue) {
			DomainUtils.addFieldError(entity, "installmentValue", "required")
		} else if (Utils.toBigDecimal(params.value) == 0 && Utils.toBigDecimal(params.totalValue) == 0) {
			DomainUtils.addFieldError(entity, "installmentValue", "required")
		} else {
            BigDecimal minimumValue = Payment.getMinimumBankSlipAndPixValue(params.customer)
            if (params.billingType && BillingType.convert(params.billingType.toString()).isCreditCard()) {
                minimumValue = Installment.MIN_CREDIT_CARD_INSTALLMENT_PAYMENT_VALUE
            }

            if (Utils.toBigDecimal(params.value) < minimumValue && Utils.toBigDecimal(params.totalValue) < minimumValue) {
                DomainUtils.addFieldError(entity, "installmentValue", "minimum.${BillingType.convert(params.billingType.toString()) ?: BillingType.BOLETO}", [MoneyUtils.formatCurrency(minimumValue)] as Object[])
            }
		}

		Integer maxInstallmentCount = Installment.getMaxInstallmentCount(params.customer)

		if (!params.installmentCount || params.installmentCount.toInteger() < 1) {
			DomainUtils.addError(entity, "Informe o número de parcelas.")
		} else if (params.installmentCount.toInteger() > maxInstallmentCount) {
			if (maxInstallmentCount < Installment.MAX_INSTALLMENT_COUNT_FOR_APPROVED_PROVIDER) {
				DomainUtils.addError(entity, "Enquanto sua conta não for totalmente aprovada, é possível parcelar em no máximo ${maxInstallmentCount} vezes. Após a aprovação o limite aumentará para ${Installment.MAX_INSTALLMENT_COUNT_FOR_APPROVED_PROVIDER} vezes.")
			} else {
				DomainUtils.addError(entity, "O número máximo é de ${maxInstallmentCount} parcelas.")
			}
		} else if (params.billingType && [BillingType.MUNDIPAGG_CIELO, BillingType.UNDEFINED].contains(BillingType.convert(params.billingType.toString())) && params.installmentCount.toInteger() > Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD) {
            DomainUtils.addFieldError(entity, "billingType", "maximum.mundipaggCielo", [Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD] as Object[])
        }

		return entity
	}

	public Date calculateNextDueDate(Date dueDate, Integer months) {
		Calendar dueDateCalendar = CustomDateUtils.getInstanceOfCalendar(dueDate)

		Calendar nextDueDate = Calendar.getInstance()
		nextDueDate.setTime(dueDate)
		nextDueDate.add(Calendar.MONTH, months)

		Integer expirationDay = dueDateCalendar.get(Calendar.DAY_OF_MONTH) > nextDueDate.getActualMaximum(Calendar.DAY_OF_MONTH) ? nextDueDate.getActualMaximum(Calendar.DAY_OF_MONTH) : dueDateCalendar.get(Calendar.DAY_OF_MONTH)
		nextDueDate.set(Calendar.DAY_OF_MONTH, expirationDay)

		return nextDueDate.getTime()
	}

	private String buildDescription(Integer currentInstallment, Integer totalInstallments, String paymentDescription) {
		return "Parcela ${currentInstallment} de ${totalInstallments}. ${paymentDescription ?: ''}".trim()
	}

	private Map updateBillingTypeUpdate(Installment installment, Map params) {
		if (!params.containsKey("billingType") || !params.billingType) return [:]

		BillingType newBillingType = params.billingType instanceof BillingType ? params.billingType : BillingType.convert(params.billingType)

		if (newBillingType != installment.billingType && !installment.billingTypeCanBeUpdated()) {
			throw new BusinessException("Não é possível alterar a forma de pagamento deste parcelamento pois há parcelas recebidas")
		}

		if ([BillingType.MUNDIPAGG_CIELO, BillingType.UNDEFINED].contains(newBillingType) && installment.installmentCount > Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD) {
			throw new BusinessException(Utils.getMessageProperty("maximum.mundipaggCielo.com.asaas.domain.installment.Installment.billingType", [Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD]))
		}

		installment.billingType = newBillingType

		return [billingType: installment.billingType]
	}

	private Map updateValue(Installment installment, Map params) {
		if (!params.containsKey("value") || !params.value) return [:]

		return [value: Utils.toBigDecimal(params.value)]
	}

	private Map updateExpirationDay(Installment installment, Map params) {
		if ((!params.containsKey("expirationDay") && !params.expirationDay) || installment.expirationDay == Integer.valueOf(params.expirationDay)) return [:]
		installment.expirationDay = Integer.valueOf(params.expirationDay)

		return [expirationDay: installment.expirationDay]
	}

	private Map updateSendByPostalService(Installment installment, Map params) {
		Map result = [:]

		if (params.containsKey("cancelSendByPostalService") && params.cancelSendByPostalService.toBoolean()) {
			installment.postalService = false

			return [cancelSendByPostalService: true]
		}

		if (params.containsKey("postalService")) {
			installment.postalService = true

			result << [sendPaymentByPostalService: true]
		} else if (installment.postalService) {
			installment.postalService = false

			result << [cancelSendByPostalService: true]
		}

		return result
	}

	private void updatePendingPayments(Installment installment, Map params) {
        List<Payment> paymentsToUpdate = installment.getRemainingPayments().findAll { !it.anticipated }

        for (Payment payment : paymentsToUpdate) {
			Date dueDate = calculateUpdatedDate(payment.dueDate, installment.expirationDay)
            String description = buildDescription(payment.installmentNumber, installment.installmentCount, params.description)

            if (dueDate < new Date().clearTime()) dueDate = null

            Map paymentUpdateParams = [:]
            paymentUpdateParams.billingType = installment.billingType
            paymentUpdateParams.payment = payment
            paymentUpdateParams.dueDate = dueDate
            paymentUpdateParams.description = description
            paymentUpdateParams.installmentUpdate = true
            paymentUpdateParams.disablePostalServiceIfNecessary = true
            paymentUpdateParams.value = params.value
            paymentUpdateParams.sendPaymentByPostalService = params.sendPaymentByPostalService
            paymentUpdateParams.paymentUndefinedBillingTypeConfig = params.paymentUndefinedBillingTypeConfig
            paymentUpdateParams.splitInfo = params.splitInfo

            paymentUpdateService.update(paymentUpdateParams)

			if (payment.hasErrors()) {
				throw new BusinessException("A cobrança ${payment.invoiceNumber} não pode ser atualizada. Motivo: ${Utils.getMessageProperty(payment.errors.allErrors.first())}")
			}
		}
	}

	public Date calculateUpdatedDate(Date dueDate, Integer expirationDay) {
		Calendar calendar = Calendar.getInstance()
		calendar.setTime(dueDate)
		calendar.set(Calendar.DAY_OF_MONTH, expirationDay)

		return calendar.getTime().clearTime()
	}

	public byte[] buildPaymentBook(Long id, Long customerId, Boolean sortByDueDateAsc) {
		Installment installment = Installment.find(id, customerId)
		return buildPaymentBookBytes(installment, sortByDueDateAsc)
	}

	public void refundCreditCard(Long installmentId, Boolean refundOnAcquirer, Map params) {
		Payment.withNewTransaction { TransactionStatus status ->
			try {
				Installment installment = Installment.get(installmentId)

				refundCreditCard(installment, refundOnAcquirer, params)
			} catch (BusinessException businessException) {
                status.setRollbackOnly()
                throw businessException
            } catch (Exception exception) {
				AsaasLogger.error("Error while refunding Installment [${installmentId}] | refundOnAcquirer = ${refundOnAcquirer}, rollingback transaction.", exception)
				status.setRollbackOnly()
				throw exception
			}
		}
	}

	public void refundCreditCard(Installment installment, Boolean refundOnAcquirer, Map params) {
        if (!installment.canBeRefundedByAdmin(refundOnAcquirer, params.value)) throw new RuntimeException("Não é possível estornar o parcelamento [${installment.id}]: ${installment.refundDisabledReason}")

        if (isPartialCreditCardRefund(installment, params.value)) {
            partialCreditCardRefund(installment, refundOnAcquirer, params)
            return
        }

		Set<Payment> payments = installment.listConfirmedOrReceivedCreditCardPayments()

        Boolean hasRefundInProgress = false
		for (Payment p : payments.sort { it.installmentNumber }) {
			paymentRefundService.refund(p.id, [refundOnAcquirer: false])

            if (p.status.isRefundInProgress()) hasRefundInProgress = true

            if (!refundOnAcquirer) {
                CreditCardTransactionInfo creditCardTransactionInfo = CreditCardTransactionInfo.query([paymentId: p.id]).get()
                if (creditCardTransactionInfo) {
                    creditCardTransactionInfo.chargeback = true
                    creditCardTransactionInfo.save(flush: true, failOnError: true)
                }
            }
		}

        if (!hasRefundInProgress) {
            if (refundOnAcquirer) {
                creditCardService.refund(installment)
                mobilePushNotificationService.notifyInstallmentRefunded(installment, payments)
            }

            transactionReceiptService.savePaymentRefunded(installment)

            asaasMoneyTransactionInfoService.refundCheckoutIfNecessary(installment)
        }
	}

	public Installment executeRefundRequestedByProvider(Long installmentId, Customer customer, Map params) {
		Installment.withNewTransaction {
			Installment installment = Installment.find(installmentId, customer.id)

            BigDecimal refundValue = params.value
            if (!refundValue) refundValue = installment.listConfirmedOrReceivedCreditCardPayments()*.getRemainingRefundValue().sum()

			if (!installment.canBeRefundedByUser(isPartialCreditCardRefund(installment, params.value), refundValue)) {
				throw new BusinessException("Não é possível estornar o parcelamento ${installment.getInvoiceNumber()}. ${installment.refundDisabledReason}")
			}
		}

		refundCreditCard(installmentId, true, params)

		return Installment.find(installmentId, customer.id)
	}

	public byte[] buildPaymentBookFromPublicId(String publicId, Boolean sortByDueDateAsc) {
		Installment installment = Installment.findFromPublicId(publicId)
		return buildPaymentBookBytes(installment, sortByDueDateAsc)
	}

	private byte[] buildPaymentBookBytes(Installment installment, Boolean sortByDueDateAsc){
		List<Payment> paymentList = installment.getRemainingPayments().findAll { PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(it) && it.boletoBank }
		if (paymentList.empty) throw new BusinessException("Nenhuma cobrança encontrada para os filtros selecionados.")

		return boletoService.buildPaymentBook(paymentList, sortByDueDateAsc)
	}

    private Installment validateSplitInfo(Installment installment, List<Map> splitInfoList) {
        Boolean hasDuplicatedFixedValueInfo = splitInfoList.any { it.containsKey("totalFixedValue") && it.containsKey("fixedValue") }
        if (hasDuplicatedFixedValueInfo) return DomainUtils.addError(installment, "Somente pode ser informado o valor total do split do parcelamento ou o valor do split de cada parcela.")

        return installment
    }

    private Installment validateDescription(Installment installment, Map params) {
        if (!params.description) return installment

        if (params.description.length() > Installment.constraints.description.maxSize) {
            return DomainUtils.addError(installment, "A descrição do parcelamento não deve ultrapassar ${Installment.constraints.description.maxSize} caracteres.")
        }

        if (StringUtils.textHasEmoji(params.description)) {
            DomainUtils.addError(installment, "Não é permitido emojis na descrição do parcelamento.")
        }

        return installment
    }

    private void partialCreditCardRefund(Installment installment, Boolean refundOnAcquirer, Map params) {
        Set<Payment> paymentList = installment.listConfirmedOrReceivedCreditCardPayments()
        paymentList = paymentList.sort { it.installmentNumber }.sort { it.status.isConfirmed() ? 1 : 2 }

        BigDecimal totalValueRemainingToRefund = paymentList*.getRemainingRefundValue().sum()

        if (params.value && params.value > totalValueRemainingToRefund) throw new BusinessException("Valor informado [${FormUtils.formatCurrencyWithMonetarySymbol(params.value)}] excede o valor disponível para estorno [${FormUtils.formatCurrencyWithMonetarySymbol(totalValueRemainingToRefund)}].")

        BigDecimal totalValueToRefund = params.value ?: totalValueRemainingToRefund
        BigDecimal totalRefundedValue = 0

        Boolean shouldRefundMorePayments = true

        if (params.paymentRefundSplitVoList) {
            params.groupedPaymentRefundSplitVoList = params.paymentRefundSplitVoList.groupBy { PaymentRefundSplitVO it ->
                Long paymentId = PaymentSplit.query([column: "payment.id", "payment[in]": paymentList, publicId: it.paymentSplitPublicId]).get()
                if (!paymentId) throw new BusinessException("O split informado não foi encontrado [${it.paymentSplitPublicId}].")

                return paymentId
            }
        }

        for (Payment payment : paymentList) {
            BigDecimal availablePaymentValueToRefund = payment.getRemainingRefundValue()
            if (!availablePaymentValueToRefund) continue

            Map refundParams = [refundOnAcquirer: false, isInstallmentPartialRefund: true]
            if (params.groupedPaymentRefundSplitVoList) refundParams.paymentRefundSplitVoList = params.groupedPaymentRefundSplitVoList[payment.id]

            BigDecimal currentValueToRefund = availablePaymentValueToRefund + totalRefundedValue

            if (currentValueToRefund < totalValueToRefund) {
                totalRefundedValue += availablePaymentValueToRefund

                if (availablePaymentValueToRefund != payment.value) refundParams.value = availablePaymentValueToRefund
            } else if (currentValueToRefund > totalValueToRefund) {
                refundParams.value = totalValueToRefund - totalRefundedValue

                shouldRefundMorePayments = false
            } else {
                shouldRefundMorePayments = false
            }

            paymentRefundService.refund(payment, refundParams)

            if (!shouldRefundMorePayments) break
        }

        if (refundOnAcquirer) {
            Map creditCardTransactionInfoMap = CreditCardTransactionInfo.query([columnList: ["gateway", "transactionIdentifier"], paymentId: paymentList.first().id]).get()
            creditCardService.refund(creditCardTransactionInfoMap.gateway, creditCardTransactionInfoMap.transactionIdentifier, totalValueToRefund)
        }
    }

    private Boolean isPartialCreditCardRefund(Installment installment, BigDecimal refundValue) {
        Set<Payment> paymentList = installment.listNotDeletedCreditCardPayments()

        BigDecimal installmentValue = paymentList*.getRemainingRefundValue().sum()
        if (refundValue && refundValue < installmentValue) return true

        for (Payment payment : paymentList) {
            Boolean hasPartialRefundRequested = PaymentRefund.query([payment: payment]).get().asBoolean()

            if (hasPartialRefundRequested) return true
        }

        return false
    }
}

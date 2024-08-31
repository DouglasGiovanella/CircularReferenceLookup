package com.asaas.service.subscription

import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardTransactionInfoVo
import com.asaas.creditcard.CreditCardUtils
import com.asaas.creditcard.CreditCardValidator
import com.asaas.creditcard.HolderInfo
import com.asaas.customer.CustomerProductVO
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.checkoutcallbackconfig.SubscriptionCheckoutCallbackConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerProduct
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.plan.Plan
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.subscription.SubscriptionFiscalConfig
import com.asaas.domain.subscription.SubscriptionTaxInfo
import com.asaas.domain.subscription.SubscriptionUndefinedBillingTypeConfig
import com.asaas.exception.BusinessException
import com.asaas.invoice.CustomerInvoiceSummary
import com.asaas.invoice.InvoiceCreationPeriod
import com.asaas.invoice.InvoiceFiscalVO
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.payment.validator.PaymentValidator
import com.asaas.postalservice.PaymentPostalServiceValidator
import com.asaas.product.Cycle
import com.asaas.subscription.SubscriptionStatus
import com.asaas.user.UserUtils
import com.asaas.utils.CryptographyUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import grails.gorm.PagedResultList
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class SubscriptionService {

    def billingInfoService
    def boletoService
    def creditCardService
    def customerInvoiceService
    def customerProductService
    def notificationDispatcherPaymentNotificationOutboxService
    def paymentCampaignItemService
    def paymentCustomerProductService
    def paymentDiscountConfigService
    def paymentAnticipableInfoService
    def paymentFineConfigService
    def paymentInterestConfigService
    def paymentService
    def paymentUpdateService
    def sessionFactory
    def subscriptionFiscalConfigService
    def subscriptionCheckoutCallbackConfigService
    def subscriptionNotificationService
    def subscriptionSplitService
    def subscriptionTaxInfoService
    def subscriptionUndefinedBillingTypeConfigService

    public Subscription onError(Subscription subscription, Boolean failOnError) {
        if (failOnError) throw new ValidationException(null, subscription.errors)

        transactionStatus.setRollbackOnly()
        return subscription
    }

    public Date calculateEndDatePaymentInCycleByNumberPayments(Date dueDate, Cycle subscriptionCycle, Integer numberOfPayments) {
        if (numberOfPayments < 1) throw new BusinessException("Quantidade de parcelas deve ser maior que zero.")
        if (numberOfPayments > Subscription.MAX_PAYMENT_COUNT_FOR_APPROVED_PROVIDER) throw new BusinessException("A quantidade máxima de parcelas é ${Subscription.MAX_PAYMENT_COUNT_FOR_APPROVED_PROVIDER}.")

        if (numberOfPayments == 1) return dueDate

        Subscription subscription = null
        Integer expirationDay = CustomDateUtils.getDayOfDate(dueDate).toInteger()

        (numberOfPayments - 1).times {
            subscription = calculateNextDueDateForSubscription(CustomDateUtils.fromDate(dueDate), subscriptionCycle.toString(), expirationDay)
            dueDate = subscription.nextDueDate
        }

        subscription.discard()

        return subscription.nextDueDate
    }

    public Integer calculateNumberPaymentsInCycleByEndDate(Date dueDate, Cycle subscriptionCycle, Date endDate) {
        Integer numberOfPayments = 1

        if (!endDate) throw new BusinessException("Informe a data da última parcela.")

        Integer yearsBetweenStartAndEnd = CustomDateUtils.calculateDifferenceInYears(dueDate, endDate)

        if (yearsBetweenStartAndEnd > Subscription.MAX_YEARS_TO_PAYMENT_COUNT_FOR_APPROVED_PROVIDER) throw new BusinessException("O limite das parcelas é ${Subscription.MAX_YEARS_TO_PAYMENT_COUNT_FOR_APPROVED_PROVIDER} anos.")

        Subscription subscription = calculateNextDueDateForSubscription(CustomDateUtils.fromDate(dueDate), subscriptionCycle.toString(), CustomDateUtils.getDayOfDate(dueDate).toInteger())

        while (subscription.nextDueDate <= endDate) {
            subscription = calculateNextDueDateForSubscription(subscription)
            numberOfPayments++
        }

        subscription.discard()

        return numberOfPayments
    }

    public Subscription save(CustomerAccount customerAccount, Map params) {
        return save(customerAccount, params, true)
    }

    public Subscription save(CustomerAccount customerAccount, Map params, Boolean failOnError) {
    	customerAccount?.provider.validateAccountDisabled()

		BillingType billingType = params.billingType ? (params.billingType instanceof BillingType ? params.billingType : BillingType.convert(params.billingType)) : null

		Calendar dueDate = getDueDate(params)

		Subscription subscription = buildSubscription(customerAccount, dueDate, billingType, params)

		InvoiceFiscalVO invoiceFiscalVo
		if (params.invoice) invoiceFiscalVo = InvoiceFiscalVO.build(params.invoice)

		if (Boolean.valueOf(params.invoice?.updatePayment)) {
			invoiceFiscalVo.fiscalConfigVo.updateRecurrentPaymentValues = true
			invoiceFiscalVo.value = subscription.value
			CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(invoiceFiscalVo)
	    	invoiceFiscalVo.taxInfoVo.invoiceValue = customerInvoiceSummary.currentPaymentValue
	    	if (customerInvoiceSummary.paymentValueNeedsToBeUpdated()) {
	    		subscription.value = customerInvoiceSummary.updatedPaymentValue
	    		invoiceFiscalVo.value = customerInvoiceSummary.currentPaymentValue
	    	} else {
	    		invoiceFiscalVo.value = null
	    	}
		}

        validateDescription(subscription, params)
        if (subscription.hasErrors()) return onError(subscription, failOnError)

		if (!subscription.validate()) return onError(subscription, failOnError)

        validateDueDateIsBeforeEndDate(subscription, dueDate.getTime())
        if (subscription.hasErrors()) return onError(subscription, failOnError)

        if (!params.containsKey("interest")) {
            params.interest = paymentInterestConfigService.buildCustomerConfig(customerAccount.provider)
        }
        if (!params.containsKey("fine")) {
            params.fine = paymentFineConfigService.buildCustomerConfig(customerAccount.provider)
        }

        List<AsaasError> asaasErrorList = []
        if (params.discount) {
            asaasErrorList.addAll(paymentDiscountConfigService.validate(subscription, params.discount).asaasErrors)
        } else {
            asaasErrorList.addAll(paymentDiscountConfigService.validateObjectValue(subscription).asaasErrors)
        }

        if (params.fine) {
            asaasErrorList.addAll(paymentFineConfigService.validate(subscription, params.fine).asaasErrors)
        } else {
            asaasErrorList.addAll(paymentFineConfigService.validateObjectValue(subscription).asaasErrors)
        }

        asaasErrorList.addAll(paymentInterestConfigService.validate(subscription, params.interest).asaasErrors)

        for (AsaasError asaasError : asaasErrorList) {
            DomainUtils.addError(subscription, asaasError.getMessage())
        }

		if (subscription.hasErrors()) return onError(subscription, failOnError)

		subscription.save(flush: true, failOnError: failOnError)
		if (subscription.hasErrors()) return onError(subscription, failOnError)

        processSubscriptionUndefinedBillingTypeConfigRegisterIfNecessary(subscription, params.subscriptionUndefinedBillingTypeConfig, false)

		paymentDiscountConfigService.save(subscription, params.discount)
		paymentFineConfigService.save(subscription, params.fine)
		paymentInterestConfigService.save(subscription, params.interest)

        if (params.subscriptionCheckoutCallbackConfig) {
            SubscriptionCheckoutCallbackConfig subscriptionCheckoutCallbackConfig = subscriptionCheckoutCallbackConfigService.saveOrUpdate(subscription, params.subscriptionCheckoutCallbackConfig)

            if (subscriptionCheckoutCallbackConfig.hasErrors()) {
                DomainUtils.copyAllErrorsFromObject(subscriptionCheckoutCallbackConfig, subscription)
            }
        }

		if (subscription.hasErrors()) return onError(subscription, failOnError)

		calculateNextDueDateForSubscription(subscription)

		subscription.save(flush: true, failOnError: failOnError)
		if (subscription.hasErrors()) return onError(subscription, failOnError)

        subscriptionSplitService.saveSplitInfo(subscription, params)

    	Payment payment = paymentService.createRecurrentPayment(subscription, dueDate.getTime(), shouldNotifyThisPayment(params), false)
		if (payment.hasErrors()) {
            DomainUtils.copyAllErrorsFromObject(payment, subscription)
            return onError(subscription, failOnError)
        }

		Boolean creditCardTransactionProcessed = processCreditCardSubscription(subscription, payment, params)
		if (subscription.hasErrors()) return onError(subscription, failOnError)

		if (subscription.isCreditCard() && !creditCardTransactionProcessed) {
			paymentService.notifyPaymentCreated(payment)
            notificationDispatcherPaymentNotificationOutboxService.saveCreditCardSubscriptionPaymentCreatedAndNotAuthorized(payment)
		}

		if (isWeeklyOrBiweekly(subscription)) {
			generatePaymentsForNext30Days(subscription)
			if (subscription.hasErrors()) return onError(subscription, failOnError)
		}

		if (params.customerProduct)	saveCustomerProduct(subscription, CustomerProductVO.build(params.customerProduct))

		if (invoiceFiscalVo) saveInvoice(subscription, invoiceFiscalVo, subscription.getPayments())

    	return subscription
    }

    public void saveCustomerProduct(Subscription subscription, CustomerProductVO customerProductVO) {
		CustomerProduct customerProduct = customerProductService.findOrSave(subscription.customerAccount.provider, customerProductVO)
        if (customerProduct.hasErrors()) throw new ValidationException(null, customerProduct.errors)

        paymentCustomerProductService.save(customerProduct, subscription)

        for (Payment payment : subscription.getPayments()) {
        	paymentCustomerProductService.save(customerProduct, payment)
        }
	}

    public Invoice saveInvoice(Subscription subscription, InvoiceFiscalVO invoiceFiscalVo, List<Payment> listOfPayment) {
		SubscriptionFiscalConfig subscriptionFiscalConfig = subscriptionFiscalConfigService.save(subscription, invoiceFiscalVo.fiscalConfigVo)
		subscriptionFiscalConfig.observations = invoiceFiscalVo.observations
		subscriptionFiscalConfig.save(failOnError: true)

        invoiceFiscalVo.fiscalConfigVo.invoiceCreationPeriod = subscriptionFiscalConfig.invoiceCreationPeriod
        invoiceFiscalVo.fiscalConfigVo.receivedOnly = subscriptionFiscalConfig.receivedOnly
        invoiceFiscalVo.fiscalConfigVo.daysBeforeDueDate = subscriptionFiscalConfig.daysBeforeDueDate
        invoiceFiscalVo.fiscalConfigVo.invoiceFirstPaymentOnCreation = subscriptionFiscalConfig.invoiceFirstPaymentOnCreation

        if (invoiceFiscalVo.deductions) invoiceFiscalVo.taxInfoVo.deductions = invoiceFiscalVo.deductions
        SubscriptionTaxInfo subscriptionTaxInfo = subscriptionTaxInfoService.save(subscription, invoiceFiscalVo.taxInfoVo)
        invoiceFiscalVo.taxInfoVo.retainIss = subscriptionTaxInfo.retainIss
        invoiceFiscalVo.taxInfoVo.issTax = subscriptionTaxInfo.issTax
        invoiceFiscalVo.taxInfoVo.cofinsPercentage = subscriptionTaxInfo.cofinsPercentage
        invoiceFiscalVo.taxInfoVo.csllPercentage = subscriptionTaxInfo.csllPercentage
        invoiceFiscalVo.taxInfoVo.inssPercentage = subscriptionTaxInfo.inssPercentage
        invoiceFiscalVo.taxInfoVo.irPercentage = subscriptionTaxInfo.irPercentage
        invoiceFiscalVo.taxInfoVo.pisPercentage = subscriptionTaxInfo.pisPercentage

		Integer count = 0
		for (Payment payment : listOfPayment.sort { it.dueDate }) {
			if (count > 0) invoiceFiscalVo.fiscalConfigVo.invoiceFirstPaymentOnCreation = false

            customerInvoiceService.savePaymentInvoice(payment, invoiceFiscalVo, [:])

			count++
		}
	}

	public Subscription disableInvoiceForExistingSubscription(Long id, Long providerId) {
		Subscription subscription = Subscription.find(id, providerId)

		for (Payment payment : subscription.getPayments()) {
			Invoice paymentInvoice = payment.getInvoice()

			if (!paymentInvoice) continue
			if (!paymentInvoice.status.isScheduled() && !paymentInvoice.status.isPending()) continue

			customerInvoiceService.cancel(paymentInvoice)
		}

		SubscriptionTaxInfo taxInfo = subscription.getTaxInfo()
		taxInfo.deleted = true
		taxInfo.save(failOnError: true)

		SubscriptionFiscalConfig fiscalConfig = subscription.getFiscalConfig()
		fiscalConfig.deleted = true
		fiscalConfig.save(failOnError: true)

		return subscription
	}

	public Invoice saveInvoiceForExistingSubscription(Long subscriptionId, Customer customer, InvoiceFiscalVO invoiceFiscalVo, CustomerProductVO customerProductVo, Map params) {
    	Utils.withNewTransactionAndRollbackOnError({
			Subscription subscription = Subscription.find(subscriptionId, customer.id)

	    	saveCustomerProduct(subscription, customerProductVo)

	        List<Payment> listOfPayment = []
	        if (invoiceFiscalVo.fiscalConfigVo.invoiceCreationPeriod == InvoiceCreationPeriod.ON_DUE_DATE_MONTH) {
	            listOfPayment = subscription.getPendingPaymentsFromDate(CustomDateUtils.getFirstBusinessDayOfNextMonth().getTime().clearTime())
	        } else if (invoiceFiscalVo.fiscalConfigVo.invoiceCreationPeriod == InvoiceCreationPeriod.ON_NEXT_MONTH) {
	            listOfPayment = subscription.getPayments().findAll { it.deleted == false && it.dueDate >= CustomDateUtils.getFirstBusinessDayOfCurrentMonth().getTime().clearTime()}
	        } else {
	            listOfPayment = subscription.getPendingPayments()
	        }

			listOfPayment.removeAll { it.hasActiveInvoice() }

			invoiceFiscalVo.fiscalConfigVo.updateRecurrentPaymentValues = Boolean.valueOf(params.updatePayment)
			CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(subscription, invoiceFiscalVo, customerProductVo)
			if (invoiceFiscalVo.fiscalConfigVo.updateRecurrentPaymentValues) {
    			invoiceFiscalVo.taxInfoVo.invoiceValue = customerInvoiceSummary.currentPaymentValue
	    	}

	    	Boolean paymentValueNeedsToBeUpdated = customerInvoiceSummary.paymentValueNeedsToBeUpdated()

			saveInvoice(subscription, invoiceFiscalVo, listOfPayment)

			if (!invoiceFiscalVo.fiscalConfigVo.updateRecurrentPaymentValues || !paymentValueNeedsToBeUpdated) return

			update([providerId: subscription.customerAccount.provider.id, subscription: subscription, value: customerInvoiceSummary.updatedPaymentValue, updatePendingPayments: true])

	    	if (subscription.hasErrors()) throw new BusinessException(Utils.getMessageProperty("customerInvoice.create.error.subscription.validation", [Utils.getMessageProperty(subscription.errors.allErrors.first().getCodes()[0])]))
        }, [ignoreStackTrace: true, onError: { Exception exception -> throw exception }])
	}

    public void updateInvoiceConfiguration(Long subscriptionId, Customer customer, InvoiceFiscalVO invoiceFiscalVo) {
        Subscription subscription = Subscription.find(subscriptionId, customer.id)

        subscriptionFiscalConfigService.update(subscription.getFiscalConfig().id, invoiceFiscalVo.fiscalConfigVo)

        CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(subscription, invoiceFiscalVo, null)
        if (!invoiceFiscalVo?.fiscalConfigVo?.updateRecurrentPaymentValues) {
            customerInvoiceSummary.updatedPaymentValue = customerInvoiceSummary.currentPaymentValue
        }

        subscriptionTaxInfoService.update(subscription.getTaxInfo().id, invoiceFiscalVo.taxInfoVo)

        update([
            providerId: subscription.providerId,
            subscription: subscription,
            value: customerInvoiceSummary.updatedPaymentValue,
            updatePendingPayments: false
        ])

        if (subscription.hasErrors()) {
            String errorMessageArg = DomainUtils.getFirstValidationMessage(subscription)
            String errorMessage = Utils.getMessageProperty("customerInvoice.create.error.subscription.validation", [errorMessageArg])
            throw new BusinessException(errorMessage)
        }
    }

    public PagedResultList list(Long customerAccountId, Long providerId, Integer max, Integer offset) {
    	list(customerAccountId, providerId, max, offset, null)
    }

    public PagedResultList list(Long customerAccountId, Long providerId, Integer max, Integer offset, Map search) {
		search = search ?: [:]
		search.customerId = providerId
		search.customerAccountId = customerAccountId
		search.sort = search?.sort ?: "lastUpdated"

		return Subscription.query(search).list(max: max, offset: offset)
	}

    @Deprecated
	public Subscription calculateNextDueDateForSubscription(String nextDueDate, String subscriptionCycle, Integer expirationDay) {
		Date dueDate = CustomDateUtils.fromString(nextDueDate)
		Calendar calendar = Calendar.getInstance()
		calendar.setTime(dueDate)
		Subscription subscription = new Subscription()
		subscription.cycle = Cycle.convert(subscriptionCycle)
		subscription.nextDueDate = dueDate
		subscription.expirationDay = expirationDay

		return calculateNextDueDateForSubscription(subscription)
	}

    public Subscription calculateNextDueDateForSubscription(Subscription subscription) {
        if (!subscription.cycle) return

        subscription.nextDueDate = calculateNextDueDate(subscription.nextDueDate, subscription.cycle, subscription.expirationDay)
        return subscription
    }

    public Date calculateNextDueDate(Date currentNextDueDate, Cycle cycle, Integer expirationDay) {
        Date calculatedNextDueDate = CustomDateUtils.addCycle(currentNextDueDate, cycle)
        Boolean isWeeklyOrBiweekly = cycle.isWeekly() || cycle.isBiweekly()
        if (isWeeklyOrBiweekly) return calculatedNextDueDate

        Date lastDayOfTheMonthForCalculatedDate = CustomDateUtils.getLastDayOfMonth(calculatedNextDueDate)
        Boolean expirationDayExceedLastDayOfTheMonth = expirationDay > CustomDateUtils.getDayOfDate(lastDayOfTheMonthForCalculatedDate).toInteger()
        if (expirationDayExceedLastDayOfTheMonth) return lastDayOfTheMonthForCalculatedDate

        Integer calculatedNextDueDateDay = CustomDateUtils.getDayOfDate(calculatedNextDueDate).toInteger()
        Boolean isExpirationDayDifferentFromNextDueDateDay = expirationDay != calculatedNextDueDateDay
        if (isExpirationDayDifferentFromNextDueDateDay) {
            Integer daysToSum = expirationDay - calculatedNextDueDateDay
            calculatedNextDueDate = CustomDateUtils.sumDays(calculatedNextDueDate, daysToSum)
        }

        return calculatedNextDueDate.clearTime()
    }

    public void forEachFutureSubscriptionPayment(Date fromDate, Date finishDate, Date subscriptionNextDueDate, Date subscriptionEndDate, Cycle cycle, Integer expirationDay, Closure action) {
        Date simulatedDueDate = subscriptionNextDueDate.clone().clearTime()

        while (simulatedDueDate <= finishDate) {
            Boolean simulationReachedSubscriptionEndDate = subscriptionEndDate && subscriptionEndDate < simulatedDueDate
            if (simulationReachedSubscriptionEndDate) break

            Boolean simulatedDueDateIsBetweenFilteredDates = simulatedDueDate >= fromDate && simulatedDueDate <= finishDate
            if (simulatedDueDateIsBetweenFilteredDates) {
                action.call(simulatedDueDate)
            }

            simulatedDueDate = calculateNextDueDate(simulatedDueDate, cycle, expirationDay)
        }
    }

    public Subscription update(Map params) {
		List<Map> updatedFields = []

		Subscription subscription = params.subscription ?: Subscription.find(params.id, params.providerId)

		if (!subscription.canBeUpdated()) throw new BusinessException("A assinatura [${subscription.publicId}] não pode ser atualizada.")

		validateStatusBeforeSave(subscription, params)
		if (subscription.hasErrors()) onError(subscription, false)

		if (params.containsKey("planId")) {
			subscription.plan = Plan.findWhere(id: Long.valueOf(params.planId))
			params.value = subscription.plan.value
		}

		processBillingTypeUpdate(subscription, params, updatedFields)

		processCycleUpdate(subscription, params)

		processValueUpdate(subscription, params, updatedFields)
		if (subscription.hasErrors()) onError(subscription, false)

		processNextDueDateUpdate(subscription, params)
		if (subscription.hasErrors()) onError(subscription, false)

		Boolean nextDueDateUpdated = subscription.isDirty('nextDueDate')

		if (params.containsKey("grossValue")) {
			BigDecimal grossValue = Utils.toBigDecimal(params.grossValue)
			setGrossValueIfEnabled(subscription, grossValue)
		}

		processExternalReferenceUpdate(subscription, params, updatedFields)

        processEndDateUpdate(subscription, params)
        if (subscription.hasErrors()) return onError(subscription, false)

        List<AsaasError> asaasErrorList = []
        if (params.discount) {
            asaasErrorList.addAll(paymentDiscountConfigService.validate(subscription, params.discount).asaasErrors)
        } else {
            asaasErrorList.addAll(paymentDiscountConfigService.validateObjectValue(subscription).asaasErrors)
        }

        if (params.fine) {
            asaasErrorList.addAll(paymentFineConfigService.validate(subscription, params.fine).asaasErrors)
        } else {
            asaasErrorList.addAll(paymentFineConfigService.validateObjectValue(subscription).asaasErrors)
        }

        asaasErrorList.addAll(paymentInterestConfigService.validate(subscription, params.interest).asaasErrors)

        for (AsaasError asaasError : asaasErrorList) {
            DomainUtils.addError(subscription, asaasError.getMessage())
        }

        if (subscription.hasErrors()) return onError(subscription, false)

		if (params.containsKey("status")) subscription.status = SubscriptionStatus.valueOf(params.status)
        if (params.containsKey("softDescriptorText")) subscription.softDescriptorText = params.softDescriptorText
		if (params.containsKey("maxPayments")) subscription.maxPayments = params.maxPayments ? Integer.valueOf(params.maxPayments) : null

        processDescriptionUpdate(subscription, params, updatedFields)
        if (subscription.hasErrors()) return onError(subscription, false)

		processPostalServiceStatus(subscription, params)

        if (nextDueDateUpdated) validateDueDateIsBeforeEndDate(subscription, subscription.nextDueDate)
        if (subscription.hasErrors()) return onError(subscription, params.failOnError ? true : false)

		subscription.save(flush:true, failOnError: params.failOnError ? true : false)

        processSubscriptionUndefinedBillingTypeConfigRegisterIfNecessary(subscription, params.subscriptionUndefinedBillingTypeConfig, isBillingTypeUpdatedFromUndefined(subscription, updatedFields))

		paymentDiscountConfigService.save(subscription, params.discount)
		paymentInterestConfigService.save(subscription, params.interest)
		paymentFineConfigService.save(subscription, params.fine)

        if (params.subscriptionCheckoutCallbackConfig) {
            SubscriptionCheckoutCallbackConfig subscriptionCheckoutCallbackConfig = subscriptionCheckoutCallbackConfigService.saveOrUpdate(subscription, params.subscriptionCheckoutCallbackConfig)

            if (subscriptionCheckoutCallbackConfig.hasErrors()) {
                DomainUtils.copyAllErrorsFromObject(subscriptionCheckoutCallbackConfig, subscription)
            }
        }

		if (subscription.hasErrors()) onError(subscription, false)
		updatePendingPaymentsIfNecessary(subscription, params, updatedFields)
		if (subscription.hasErrors()) onError(subscription, false)

		SubscriptionFiscalConfig subscriptionFiscalConfig = subscription.getFiscalConfig()
		if (subscriptionFiscalConfig && params.containsKey("fiscalConfig")) {
			subscriptionFiscalConfig.observations = params.fiscalConfig.invoiceObservations
			subscriptionFiscalConfig.save(failOnError: params.failOnError ? true : false)
		}

        subscriptionSplitService.saveSplitInfo(subscription, params)

        subscriptionSplitService.validateSplitTotalValue(subscription)

		if (nextDueDateUpdated && subscription.nextDueDate <= CustomDateUtils.getNextBusinessDay()) {
			generatePaymentsUntilMaxDate(subscription, subscription.nextDueDate, [notify: true, ignorePaymentIndividualDomainValidationError: false])

			if (subscription.hasErrors()) onError(subscription, false)
		}

		return subscription
	}

    public Subscription batchUpdate(Customer provider, Map params) {
		if (!params.containsKey("billingType")) {
			params.put("billingType", BillingType.BOLETO)
		}
		def subscriptions = list(null, provider.id, 99999, 0, params)

		if (Integer.valueOf(params.totalCount) != subscriptions.size()) {
            throw new IllegalArgumentException("Número de assinaturas localizadas não bate com o esperado")
        }

		def parametersMap = [:]
		if (params.nextDueDate)
			parametersMap.put("nextDueDate", params.nextDueDate)

		if (params.value)
			parametersMap.put("value", params.value)

		for (subscription in subscriptions) {
			subscription = update([subscription: subscription] + parametersMap)
			if (subscription.hasErrors()) return subscription
		}

		return subscriptions[0]
	}

	public void delete(id, Long providerId, Boolean isDeletePendingOrOverduePayments) {
		Subscription subscription = Subscription.find(id, providerId)

		if (isDeletePendingOrOverduePayments) deletePendingOrOverduePayments(subscription)

		paymentCampaignItemService.deleteFromSubscriptionIfNecessary(subscription)

		subscription.bypassValueValidation = true
		subscription.deleted = true
        subscription.status = SubscriptionStatus.INACTIVE
		subscription.save(flush: true, failOnError: true)
	}

    public Subscription inactive(Long subscriptionId, Boolean bypassReceivedValidation) {
        Subscription subscription = Subscription.get(subscriptionId)

        subscription = validateInactivation(subscription, bypassReceivedValidation)
        if (subscription.hasErrors()) return subscription

        subscription.status = SubscriptionStatus.INACTIVE
        subscription.save(failOnError: true)
        return subscription
    }

	public String buildPublicId() {
        String token = CryptographyUtils.generateSecureRandomAlphaNumeric(16)

		if (publicIdAlreadyExists(token)) {
            AsaasLogger.warn("SubscriptionService.buildPublicId >> Já existe o buildPublicId ${token}")
			return buildPublicId()
		} else {
			return "sub_" + token
		}
	}


	public void deleteAll(Customer customer) {
		List<Subscription> listSubscription = Subscription.executeQuery("from Subscription where provider = :customer ", [customer: customer])
		for(Subscription subscription : listSubscription) {
			delete(subscription.id, customer.id, true)
		}
	}

	public byte[] createPublicPaymentBook(String publicId, Map params) {
		Subscription subscription = Subscription.query([publicId: publicId]).get()

		if (!subscription) throw new Exception("Assinatura inexistente.")

        return createPaymentBookUntilMaxDate(subscription, buildDueDateFinish(params), Boolean.valueOf(params.sortByDueDateAsc))
	}

	public byte[] createPaymentBook(Map params, Long providerId) {
		Subscription subscription = Subscription.find(Utils.toLong(params.id), providerId)

		return createPaymentBookUntilMaxDate(subscription, buildDueDateFinish(params), Boolean.valueOf(params.sortByDueDateAsc))
	}

    public void generatePaymentsUntilMaxDate(Subscription subscription, Date maxDate, Map options) {
        if (options.withNewTransaction && !options.ignorePaymentIndividualDomainValidationError) throw new RuntimeException("Para utilizar o withNewTransaction, é necessário utilizar o parâmetro ignorePaymentIndividualDomainValidationError.")

        if (subscription.endDate?.before(maxDate)) maxDate = subscription.endDate

        Boolean shouldCreateNextPayment = canGeneratePaymentsForSubscriptionUntilMaxDate(subscription, maxDate)
        while (shouldCreateNextPayment) {
            if (options.withNewTransaction) {
                shouldCreateNextPayment = executeGeneratePaymentsUntilMaxDateWithNewTransactionAndRevalidateSubscriptionInfo(subscription.id, maxDate, options)
            } else {
                shouldCreateNextPayment = executeGeneratePaymentsUntilMaxDate(subscription, maxDate, options)
            }
        }

        if (options.withNewTransaction) {
            Utils.withNewTransactionAndRollbackOnError {
                Subscription subscriptionWithNewTransaction = Subscription.query([id: subscription.id]).get()
                subscriptionNotificationService.notifyCustomerAboutSubscriptionEndingIfNecessary(subscriptionWithNewTransaction)
            }
        } else {
            subscriptionNotificationService.notifyCustomerAboutSubscriptionEndingIfNecessary(subscription)
        }
    }

    public Boolean hasPaymentsToBeCreated(Subscription subscription) {
        if (!subscription.status.isActive()) return false
        if (!subscription.endDate && !subscription.maxPayments) return true

        if (subscription.endDate) {
            return subscription.endDate >= subscription.nextDueDate
        }

        if (subscription.maxPayments) {
            Integer subscriptionPaymentsSize = Payment.query([subscriptionId: subscription.id]).count()
            return subscription.maxPayments > subscriptionPaymentsSize
        }
    }

	private Date buildDueDateFinish(Map params) {
		Calendar dueDateFinish = Calendar.getInstance()
		dueDateFinish.set(Calendar.MONTH, Utils.toInteger(params.month))
		dueDateFinish.set(Calendar.YEAR, Utils.toInteger(params.year))
		dueDateFinish.set(Calendar.DAY_OF_MONTH, dueDateFinish.getMaximum(Calendar.DAY_OF_MONTH))

		return dueDateFinish.getTime()
	}

    private Boolean executeGeneratePaymentsUntilMaxDateWithNewTransactionAndRevalidateSubscriptionInfo(Long subscriptionId, Date maxDate, Map options) {
        Boolean continueExecution = false
        Utils.withNewTransactionAndRollbackOnError ({
            Subscription subscriptionWithNewTransaction = Subscription.get(subscriptionId)
            if (subscriptionWithNewTransaction.endDate?.before(maxDate)) maxDate = subscriptionWithNewTransaction.endDate
            if (!canGeneratePaymentsForSubscriptionUntilMaxDate(subscriptionWithNewTransaction, maxDate)) return continueExecution

            generatePaymentForSubscription(subscriptionWithNewTransaction, options)
            continueExecution = !subscriptionWithNewTransaction.hasErrors()
        }, [onError: { Exception e -> throw e },
            logLockAsWarning: true])

        if (!continueExecution) return continueExecution

        Utils.withNewTransactionAndRollbackOnError ({
            Subscription subscriptionWithNewTransaction = Subscription.get(subscriptionId)
            subscriptionWithNewTransaction = calculateNextDueDateForSubscription(subscriptionWithNewTransaction)
            subscriptionWithNewTransaction.save(flush: true, failOnError: true)

            continueExecution = !subscriptionWithNewTransaction.nextDueDate.after(maxDate)
        }, [onError: { Exception e -> throw e },
            logLockAsWarning: true])

        return continueExecution
    }

    private Boolean executeGeneratePaymentsUntilMaxDate(Subscription subscription, Date maxDate, Map options) {
        Boolean continueExecution = false

        if (subscription.maxPayments && subscription.getPayments().size() >= subscription.maxPayments) {
            return continueExecution
        }

        generatePaymentForSubscription(subscription, options)
        if (subscription.hasErrors()) return continueExecution

        subscription = calculateNextDueDateForSubscription(subscription)
        subscription.save(flush: true, failOnError: true)

        continueExecution = !subscription.nextDueDate.after(maxDate)

        return continueExecution
    }

    private void generatePaymentForSubscription(Subscription subscription, Map options) {
        Payment payment = paymentService.createRecurrentPayment(subscription, subscription.nextDueDate, options.notify.asBoolean(), false, [subscriptionBulkSave: true])
        if (!payment.hasErrors()) return

        if (shouldIgnoreErrorOnPaymentCreation(payment, options)) {
            AsaasLogger.warn("SubscriptionService.generatePaymentsUntilMaxDate >> Erro ao criar cobrança para a assinatura ${subscription.id}. Erro: [${payment.errors}]")
            return
        }

        DomainUtils.addError(subscription, Utils.getMessageProperty(payment.errors.allErrors[0]))
    }

    private Boolean canGeneratePaymentsForSubscriptionUntilMaxDate(Subscription subscription, Date maxDateToGeneratePayments) {
        if (subscription.nextDueDate.after(maxDateToGeneratePayments)) return false
        if (subscription.maxPayments && subscription.getPayments().size() >= subscription.maxPayments) return false

        return hasPaymentsToBeCreated(subscription)
    }

    private Boolean shouldIgnoreErrorOnPaymentCreation(Payment payment, Map options) {
        if (!options.ignorePaymentIndividualDomainValidationError) return false

        if (DomainUtils.hasErrorCode(payment, PaymentValidator.EXCEEDS_MAX_PAYMENT_CREATION_LIMIT_ERROR_CODE)) return false

        if (DomainUtils.hasErrorCode(payment, "deleted.com.asaas.domain.payment.Payment.customerAccount")) return false

        return true
    }

    private void processEndDateUpdate(Subscription subscription, Map params) {
        if (!params.containsKey("endDate")) return

        if (subscription.status.isExpired() && params.endDate != subscription.endDate) {
            DomainUtils.addError(subscription, "Assinatura expirada, não será possível alterar a data de encerramento")
            return
        }

        subscription.endDate = params.endDate
    }

    private void processSubscriptionUndefinedBillingTypeConfigRegisterIfNecessary(Subscription subscription, Map subscriptionUndefinedBillingTypeConfigMap, Boolean isBillingTypeUpdatedFromUndefined) {
        if (subscription.billingType.isUndefined()) {
            if (!subscriptionUndefinedBillingTypeConfigMap) subscriptionUndefinedBillingTypeConfigMap = [isBankSlipAllowed: true, isCreditCardAllowed: true, isPixAllowed: true]
            subscriptionUndefinedBillingTypeConfigService.save(subscription, subscriptionUndefinedBillingTypeConfigMap)
        } else if (isBillingTypeUpdatedFromUndefined) {
            subscriptionUndefinedBillingTypeConfigService.deleteIfNecessary(subscription.id)
        }
    }

    private Boolean isBillingTypeUpdatedFromUndefined(Subscription subscription, List<Map> updatedFields) {
        if (!(updatedFields.fieldName.contains("billingType"))) return false

        if (subscription.billingType.isUndefined()) return false

        if (BillingType.UNDEFINED == updatedFields.find{ it.fieldName == "billingType" }.oldValue) return true
    }

    private void processDescriptionUpdate(Subscription subscription, Map params, List<Map> updatedFields) {
        if (!params.containsKey("description")) return

        if (params.description == subscription.description) return

        validateDescription(subscription, params)

        updatedFields.add([fieldName: "description", oldValue: subscription.description, newValue: params.description])
        subscription.description = params.description
    }

    private Subscription validateDescription(Subscription subscription, Map params) {
        String subscriptionDescription = params.subscriptionDescription ?: params.description

        if (!subscriptionDescription) return subscription

        if (subscriptionDescription.length() > Subscription.constraints.description.maxSize) {
            DomainUtils.addError(subscription, "A descrição da assinatura não deve ultrapassar ${Subscription.constraints.description.maxSize} caracteres.")
        }

        if (StringUtils.textHasEmoji(subscriptionDescription)) {
            DomainUtils.addError(subscription, "Não é permitido emojis na descrição da assinatura.")
        }

        return subscription
    }

    private BigDecimal getSubscriptionValue(Plan plan, Map params) {
        BigDecimal value = null

        if (plan) {
            value = plan.value
        } else if (params.value || params.subscriptionValue) {
            value = Utils.toBigDecimal(params.value ?: params.subscriptionValue)
        }

        return value
    }

    private Cycle getSubscriptionCycle(Plan plan, Map params) {
        Cycle cycle = null
        if (plan) {
            cycle = plan.cycle
        } else if (params.subscriptionCycle) {
            cycle = Cycle.convert(params.subscriptionCycle)
        }

        return cycle
    }

    private Calendar getDueDate(Map params) {
        Calendar dueDate
        params.nextDueDate = params.nextDueDate ?: params.subscriptionDueDate
        if (params.nextDueDate) {
            if (!(params.nextDueDate instanceof Date)) {
                params.nextDueDate = CustomDateUtils.fromString(params.nextDueDate)
            }

            dueDate = CustomDateUtils.getInstanceOfCalendar(params.nextDueDate)
        } else {
            dueDate = CustomDateUtils.getInstanceOfCalendar()
        }

        return dueDate
    }

    private Plan retrievePlanFromParamsIfExists(Map params) {
        if (params.planId) {
            return Plan.findWhere(id: params.planId.toLong())
        } else {
            return null
        }
    }

    private Subscription buildSubscription(CustomerAccount customerAccount, Calendar dueDate, BillingType billingType, Map params) {
        Plan plan = retrievePlanFromParamsIfExists(params)
        BigDecimal value = getSubscriptionValue(plan, params)
        Cycle cycle = getSubscriptionCycle(plan, params)
        Integer expirationDay = dueDate.get(Calendar.DAY_OF_MONTH)
        BigDecimal grossValue = Utils.toBigDecimal(params.grossValue)

        Subscription subscription = new Subscription()
        subscription.publicId = buildPublicId()
        subscription.provider = customerAccount.provider
        subscription.customerAccount = customerAccount
        subscription.value = value
        subscription.cycle = cycle
        subscription.plan = plan
        subscription.expirationDay = expirationDay
        subscription.nextDueDate = dueDate.getTime()
        subscription.billingType = billingType
        subscription.description = params.subscriptionDescription ?: params.description
        subscription.createdBy = UserUtils.getCurrentUser() ?: params.createdBy
        subscription.externalReference = params.externalReference
        setGrossValueIfEnabled(subscription, grossValue)

        if (params.containsKey("softDescriptorText") && subscription.provider.getConfig()?.overrideAsaasSoftDescriptor) {
            subscription.softDescriptorText = params.softDescriptorText
        }

        if (params.endDate) subscription.endDate = params.endDate
        if (params.maxPayments) subscription.maxPayments = Integer.parseInt(params.maxPayments.toString())

        if (params.sendPaymentByPostalService) {
            subscription.sendPaymentByPostalService = params.sendPaymentByPostalService

            updateSendPaymentByPostalServiceParamIfNecessary(subscription.customerAccount, dueDate, params)
        }

        return subscription
    }

    private void validateDueDateIsBeforeEndDate(Subscription subscription, Date dueDate) {
        if (!subscription.endDate) return
        if (subscription.endDate < dueDate) DomainUtils.addFieldError(subscription, 'endDate', 'before.nextDueDate')
    }

    private Boolean processCreditCardSubscription(Subscription subscription, Payment payment, Map params) {
        if (subscription.billingType != BillingType.MUNDIPAGG_CIELO || !CreditCardUtils.hasAtLeastOneCreditCardInfo(params?.creditCard)) return false

        Customer provider = payment.getProvider()

        Boolean captureSuccess

        CreditCardValidator creditCardValidator = new CreditCardValidator()

        if (params.creditCard.billingInfoPublicId) {
            BillingInfo billingInfo = billingInfoService.findByPublicId(payment.customerAccount, params.creditCard.billingInfoPublicId)

            if (!billingInfo) {
                subscription.errors.rejectValue("creditCard", null, "CreditCardToken [${params.creditCard.billingInfoPublicId}] não encontrado.")
                return false
            }

            if (!creditCardValidator.validate(billingInfo.creditCardInfo)) {
                DomainUtils.copyAllErrorsFromAsaasErrorList(creditCardValidator, "creditCard", subscription)
                return false
            }

            if (payment.dueDate.after(new Date())) {
                subscription.billingInfo = billingInfo
                payment.billingInfo = billingInfo

                captureSuccess = true
            } else {
                Map creditCardOperationResponse

                if (params.authorizeOnly) {
                    creditCardOperationResponse = creditCardService.authorize(payment, billingInfo, params.creditCardTransactionOriginInfo, null)
                } else {
                    creditCardOperationResponse = creditCardService.captureWithBillingInfo(payment, params.creditCard.billingInfoPublicId, params.creditCardTransactionOriginInfo, null)
                }

                captureSuccess = creditCardOperationResponse.success
            }
        } else {
            CreditCard creditCard = CreditCard.build(params.creditCard)

            if (!creditCardValidator.validate(creditCard)) {
                DomainUtils.copyAllErrorsFromAsaasErrorList(creditCardValidator, "creditCard", subscription)
                return false
            }

            HolderInfo holderInfo = HolderInfo.build(params.creditCardHolderInfo)

            Boolean bypassClearSale = provider.customerConfig.clearSaleBypassAllowed || payment.creditCardRiskValidationNotRequired()
            if (!bypassClearSale && !creditCardValidator.validateHolderInfo(holderInfo)) {
                DomainUtils.copyAllErrorsFromAsaasErrorList(creditCardValidator, "creditCard", subscription)
                return false
            }

            CreditCardTransactionInfoVo creditCardTransactionInfo = new CreditCardTransactionInfoVo()
            creditCardTransactionInfo.payment = payment
            creditCardTransactionInfo.creditCard = creditCard
            creditCardTransactionInfo.holderInfo = holderInfo
            creditCardTransactionInfo.bypassClearSale = bypassClearSale
            creditCardTransactionInfo.remoteIp = params.creditCardTransactionOriginInfo?.remoteIp
            creditCardTransactionInfo.payerRemoteIp = params.creditCardTransactionOriginInfo?.payerRemoteIp

            if (payment.dueDate.after(new Date())) {
                if (params.authorizeOnly) {
                    DomainUtils.addError(subscription, "Não é possível autorizar uma assinatura com data futura.")
                    return false
                }

                Map tokenizeResponse = creditCardService.tokenizeCreditCard(payment.customerAccount, creditCardTransactionInfo.creditCard, params.creditCardTransactionOriginInfo, holderInfo)
                captureSuccess = tokenizeResponse.success

                if (captureSuccess) {
                    subscription.refresh()
                    subscription.payments.each { it.refresh() }

                    subscription.billingInfo = tokenizeResponse.billingInfo
                    payment.billingInfo = tokenizeResponse.billingInfo
                }
            } else {
                Map creditCardOperationResponse

                if (params.authorizeOnly) {
                    creditCardOperationResponse = creditCardService.authorize(payment, null, params.creditCardTransactionOriginInfo, creditCardTransactionInfo)
                } else {
                    creditCardOperationResponse = creditCardService.captureWithCreditCardInfo(payment, creditCardTransactionInfo, params.creditCardTransactionOriginInfo)
                }

                captureSuccess = creditCardOperationResponse.success

                subscription.refresh()
                subscription.payments.each { it.refresh() }
            }
        }

        if (!captureSuccess) {
            subscription.errors.rejectValue("creditCard", "failed")
        } else {
            subscription.save(flush: true)
            payment.save(flush: true)
            paymentAnticipableInfoService.updateIfNecessary(payment)
            notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)
        }

        return true
    }

    private Boolean shouldNotifyThisPayment(Map params) {
        Boolean notify = true

        if (params.billingType as BillingType == BillingType.MUNDIPAGG_CIELO) {
            notify = false
        } else if (params.containsKey("notifyPaymentCreatedImmediately")) {
            notify = Boolean.valueOf(params.notifyPaymentCreatedImmediately)
        }

        return notify
    }

    private void validateStatusBeforeSave(Subscription subscription, Map params) {
        if (!subscription.id || !params.status) return

        SubscriptionStatus status
        try {
            status = SubscriptionStatus.valueOf(params.status)
        } catch (Exception exception) {
            DomainUtils.addError(subscription, "Status [${params.status}] inválido para assinatura")
            return
        }

        Date newNextDueDate = params.nextDueDate instanceof Date ? params.nextDueDate : CustomDateUtils.fromString(params.nextDueDate)
        Date nextDueDate = newNextDueDate ?: subscription.nextDueDate

        if (subscription.status != SubscriptionStatus.ACTIVE && status == SubscriptionStatus.ACTIVE && nextDueDate <= new Date().clearTime()) {
            DomainUtils.addFieldError(subscription, 'nextDueDate', 'mandatory.activate')
            return
        }
    }

    private void processBillingTypeUpdate(Subscription subscription, Map params, List<Map> updatedFields) {
        if (!params.containsKey("billingType") || !params.billingType) return

        if (subscription.billingType == BillingType.convert(params.billingType)) return

        subscription.billingType = BillingType.convert(params.billingType)

        if (subscription.billingType != BillingType.MUNDIPAGG_CIELO) subscription.billingInfo = null

        updatedFields.add([fieldName: "billingType", oldValue: subscription.getPersistentValue("billingType"), newValue: subscription.billingType])
    }

    private void processPostalServiceStatus(Subscription subscription, Map params) {
        if (!SubscriptionUndefinedBillingTypeConfig.shouldSubscriptionRegisterBankSlip(subscription)) {
            subscription.sendPaymentByPostalService = false
        } else {
            subscription.sendPaymentByPostalService = params.sendPaymentByPostalService && !params.cancelSendByPostalService.asBoolean() ? true : false
        }
    }

    private void processValueUpdate(Subscription subscription, Map params, List<Map> updatedFields) {
        if (!params.containsKey("value")) return

        BigDecimal newValue = Utils.toBigDecimal(params.value)
        if (newValue == subscription.value) return

        if (!subscription.valueUpdateAllowed()) {
            DomainUtils.addFieldError(subscription, "value", "not.allowed")
            return
        }

        updatedFields.add([fieldName: "value", oldValue: subscription.value, newValue: newValue])
        subscription.value = newValue

        SubscriptionTaxInfo subscriptionTaxInfo = subscription.getTaxInfo()
        if (!subscriptionTaxInfo) return
        if (subscriptionTaxInfo.calculateTotalRetainedTaxesValue() > 0) return

        subscriptionTaxInfo.invoiceValue = newValue
        subscriptionTaxInfo.save(failOnError: true)
    }

    private void processExternalReferenceUpdate(Subscription subscription, Map params, List<Map> updatedFields) {
        if (!params.containsKey("externalReference")) return
        if (params.externalReference == subscription.externalReference) return

        updatedFields.add([fieldName: "externalReference", oldValue: subscription.externalReference, newValue: params.externalReference])
        subscription.externalReference = params.externalReference
    }

    private void processCycleUpdate(Subscription subscription, Map params) {
        if (params.containsKey("cycle")) {
            subscription.cycle = Cycle.convert(params.cycle)
        } else if (params.containsKey("subscriptionCycle")) {
            subscription.cycle = Cycle.convert(params.subscriptionCycle)
        } else {
            return
        }
    }

    private void processNextDueDateUpdate(Subscription subscription, Map params) {
        if (!params.containsKey("nextDueDate")) return

        Date newNextDueDate = params.nextDueDate instanceof Date ? params.nextDueDate : CustomDateUtils.fromString(params.nextDueDate)
        if (subscription.nextDueDate.minus(newNextDueDate) == 0) return

        if (subscription.nextDueDateUpdateAllowed()) {
            subscription.nextDueDate = newNextDueDate
            subscription.expirationDay = CustomDateUtils.getInstanceOfCalendar(subscription.nextDueDate).get(Calendar.DAY_OF_MONTH)
        } else {
            DomainUtils.addFieldError(subscription, 'nextDueDate', "not.allowed")
        }
    }

    private void updatePendingPaymentsIfNecessary(Subscription subscription, Map params, List<Map> updatedFields) {
        if (Boolean.valueOf(params.updatePendingPayments) == false) return

        if (!hasFieldsToUpdatePendingPayments(subscription, params, updatedFields)) return

        List<Long> pendingPaymentsFromSubscription = Payment.executeQuery("select p.id from Payment p join p.subscriptionPayments sp where sp.subscription = :subscription and p.deleted = false and p.status = :paymentStatus", [subscription: subscription, paymentStatus: PaymentStatus.PENDING])
        Long providerId = subscription.provider.id

        Boolean moreThanTwelvePaymentsToUpdate = pendingPaymentsFromSubscription.size() > 12

        Boolean shouldUpdateBankSlipRegister = updatedFields.fieldName.contains("value") || updatedFields.fieldName.contains("billingType") || subscription.discountConfig?.hasBeenUpdated || subscription.fineConfig?.hasBeenUpdated || subscription.interestConfig?.hasBeenUpdated
        for (Long paymentId : pendingPaymentsFromSubscription) {
            if (SubscriptionUndefinedBillingTypeConfig.shouldSubscriptionRegisterBankSlip(subscription) && shouldUpdateBankSlipRegister) {
                Payment pendingPayment = Payment.find(paymentId, providerId)

                PaymentValidator.validateIfBankSlipCanBeRegistered(pendingPayment, pendingPayment.boletoBank ?: pendingPayment.provider.boletoBank)
                if (pendingPayment.hasErrors()) continue
            }

            Map paymentParams = [
                id: paymentId,
                providerId: providerId,
                value: subscription.value,
                billingType: subscription.billingType,
                cancelSendByPostalService: params.cancelSendByPostalService,
                description: subscription.description,
                subscriptionBulkSave: moreThanTwelvePaymentsToUpdate,
                paymentCheckoutCallbackConfig: params.subscriptionCheckoutCallbackConfig
            ]
            if (subscription.externalReference) paymentParams.externalReference = subscription.externalReference

            if (params.containsKey("splitInfo")) paymentParams.splitInfo = params.splitInfo

            if (subscription.discountConfig) {
                paymentParams.discount = [value: subscription.discountConfig.value,
                                          dueDateLimitDays: subscription.discountConfig.dueDateLimitDays,
                                          limitDate: subscription.discountConfig.limitDate,
                                          discountType: subscription.discountConfig.type]
            }
            if (subscription.interestConfig) {
                paymentParams.interest = [value: subscription.interestConfig.value]
            }
            if (subscription.fineConfig) {
                paymentParams.fine = [value: subscription.fineConfig.value, fineType: subscription.fineConfig.type]
            }

            Payment updatedPayment = paymentUpdateService.update(paymentParams)

            if (updatedPayment.hasErrors()) {
                DomainUtils.addError(subscription, Utils.getMessageProperty(updatedPayment.errors.allErrors[0]))
                return
            }
        }
    }

    private Boolean hasFieldsToUpdatePendingPayments(Subscription subscription, Map params, List<Map> updatedFields) {
        if (updatedFields.collect { it.fieldName }.intersect(["value", "billingType", "externalReference", "description"])) return true
        if (params.keySet().intersect(["cancelSendByPostalService", "subscriptionCheckoutCallbackConfig", "splitInfo"])) return true
        if (subscription.discountConfig?.hasBeenUpdated || subscription.fineConfig?.hasBeenUpdated || subscription.interestConfig?.hasBeenUpdated) return true

        return false
    }

    private void deletePendingOrOverduePayments(Subscription subscription) {
        for (Payment payment : subscription.getPendingOrOverduePayments()) {
            if (!payment.canDelete()) continue

            paymentService.delete(payment, false)
        }
    }

    private Boolean publicIdAlreadyExists(String token) {
        def session = sessionFactory.currentSession

        def query = session.createSQLQuery("select count(id) from subscription where public_id = :token")
        query.setString("token", token)

        if (query.list().get(0) == 0) {
            return false
        } else {
            return true
        }
    }

    private void setGrossValueIfEnabled(Subscription subscription, BigDecimal grossValue) {
        if (subscription.customerAccount.provider.customerConfig?.grossValueEnabled) {
            subscription.grossValue = grossValue
        }
    }

    private Boolean isWeeklyOrBiweekly(Subscription subscription) {
        if (subscription.cycle == Cycle.WEEKLY || subscription.cycle == Cycle.BIWEEKLY) {
            return true
        }
        return false
    }

    private void generatePaymentsForNext30Days(Subscription subscription) {
        Calendar nextDueDate = Calendar.getInstance()
        nextDueDate.setTime(new Date())
        nextDueDate.add(Calendar.DAY_OF_MONTH, 30)

        generatePaymentsUntilMaxDate(subscription, nextDueDate.getTime(), [notify: false, ignorePaymentIndividualDomainValidationError: false])
    }

    private void updateSendPaymentByPostalServiceParamIfNecessary(CustomerAccount customerAccount, Calendar dueDate, Map params) {
        PaymentPostalServiceValidator paymentPostalServiceValidator = new PaymentPostalServiceValidator()
        paymentPostalServiceValidator.address = customerAccount.buildAddress()
        paymentPostalServiceValidator.dueDate = dueDate.getTime()
        paymentPostalServiceValidator.customer = customerAccount.provider

        if ((paymentPostalServiceValidator.validate()).size() > 0) {
            params.sendPaymentByPostalService = false
        }
    }

    private byte[] createPaymentBookUntilMaxDate(Subscription subscription, Date maxDate, Boolean sortByDueDateAsc) {
        Calendar dateLimit = CustomDateUtils.getInstanceOfCalendar()
        dateLimit.add(Calendar.MONTH, 24)

        if (maxDate > dateLimit.getTime()) throw new BusinessException("O período até a data final informada deve ser inferior a 2 anos.")

        generatePaymentsUntilMaxDate(subscription, maxDate, [notify: true, ignorePaymentIndividualDomainValidationError: true, withNewTransaction: true])

        byte[] paymentBookPdf

        Utils.withNewTransactionAndRollbackOnError({
            paymentBookPdf = generatePaymentBookPdf(subscription, maxDate, sortByDueDateAsc)
        }, [
            ignoreStackTrace: true,
            onError: { Exception exception ->
                if (!(exception instanceof BusinessException)) {
                    AsaasLogger.error("SubscriptionService.createPaymentBookUntilMaxDate >> Erro ao gerar PDF de carnê de pagamento para a assinatura ${subscription.id}", exception)
                }

                throw exception
            }
        ])

        return paymentBookPdf
    }

    private byte[] generatePaymentBookPdf(Subscription subscription, Date maxDate, Boolean sortByDueDateAsc) {
        List<Payment> listOfPayments = subscription.getPendingOrOverduePaymentsUntilMaxDate(maxDate)
        listOfPayments = listOfPayments.findAll { PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(it) }.sort { it.dueDate }

        if (listOfPayments.empty) throw new BusinessException("Nenhuma cobrança encontrada para os filtros selecionados.")
        return boletoService.buildPaymentBook(listOfPayments, sortByDueDateAsc)
    }

    private Subscription validateInactivation(Subscription subscription, Boolean bypassReceivedValidation) {
        if (subscription.deleted) {
            return DomainUtils.addError(subscription, "Assinatura está deletada")
        }

        if (!subscription.status.isActive()) {
            return DomainUtils.addError(subscription, "Assinatura não está ativa")
        }

        if (!bypassReceivedValidation) {
            Date threeMonthsAgo = CustomDateUtils.addMonths(new Date().clearTime(), -3)
            Date lastPaidPaymentConfirmDate = Payment.receivedOrConfirmed([column: "confirmedDate", subscriptionId: subscription.id, sort: "confirmedDate", order: "desc"]).get()

            if (lastPaidPaymentConfirmDate && lastPaidPaymentConfirmDate >= threeMonthsAgo) {
                return DomainUtils.addError(subscription, "Assinatura teve recebimentos nos últimos meses")
            }
        }

        return subscription
    }
}

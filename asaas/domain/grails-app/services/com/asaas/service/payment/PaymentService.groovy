package com.asaas.service.payment

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.billinginfo.ChargeType
import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.creditcard.*
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerProductVO
import com.asaas.customer.CustomerEventName
import com.asaas.customeraccount.CustomerAccountUpdateResponse
import com.asaas.domain.billinginfo.BillingInfo
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.checkoutcallbackconfig.SubscriptionCheckoutCallbackConfig
import com.asaas.domain.creditcard.CreditCardTransactionAnalysis
import com.asaas.domain.creditcard.CreditCardTransactionInfo
import com.asaas.domain.creditcard.CreditCardTransactionLog
import com.asaas.domain.customer.*
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.payment.ClearsaleResponse
import com.asaas.domain.payment.Payment
import com.asaas.domain.checkoutcallbackconfig.PaymentCheckoutCallbackConfig
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.receivableanticipation.ReceivableAnticipationOriginRequesterInfoMethod
import com.asaas.domain.split.SubscriptionSplit
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.subscription.SubscriptionFiscalConfig
import com.asaas.domain.subscription.SubscriptionTaxInfo
import com.asaas.domain.subscriptionpayment.SubscriptionPayment
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.externalidentifier.SynchronizationStatus
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.invoice.CustomerInvoiceSummary
import com.asaas.invoice.InvoiceFiscalVO
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationPriority
import com.asaas.notification.NotificationReceiver
import com.asaas.originrequesterinfo.EventOriginType
import com.asaas.payment.PaymentBuilder
import com.asaas.payment.PaymentDunningCancellationReason
import com.asaas.payment.PaymentFeeVO
import com.asaas.payment.PaymentStatus
import com.asaas.payment.PaymentUtils
import com.asaas.paymentdunning.PaymentDunningBuilder
import com.asaas.postalservice.PaymentPostalServiceResentValidator
import com.asaas.postalservice.PaymentPostalServiceValidator
import com.asaas.postalservice.PostalServiceSendingError
import com.asaas.postalservice.PostalServiceStatus
import com.asaas.pushnotification.PushNotificationRequestEvent
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.receivableanticipation.adapter.CreateReceivableAnticipationAdapter
import com.asaas.referral.ReferralStatus
import com.asaas.referral.ReferralValidationOrigin
import com.asaas.split.PaymentSplitCancellationReason
import com.asaas.user.UserUtils
import com.asaas.utils.*
import com.asaas.validation.AsaasError
import datadog.trace.api.Trace
import grails.gorm.PagedResultList
import grails.transaction.Transactional
import grails.util.Environment
import grails.validation.ValidationException

import org.hibernate.criterion.CriteriaSpecification

import com.asaas.log.AsaasLogger

@Transactional
class PaymentService {

    def asaasErpAccountingStatementService
    def asaasMoneyPaymentCompromisedBalanceService
    def asaasMoneyMobilePushNotificationService
    def bankSlipRegisterService
    def billingInfoService
    def boletoBatchFileItemService
    def creditCardService
    def creditBureauDunningService
    def customerAccountService
    def customerEventCacheService
    def customerEventService
    def customerInvoiceService
    def customerProductService
    def externalIdentifierService
    def facebookEventService
    def grailsApplication
    def grailsLinkGenerator
    def messageService
    def notificationDispatcherPaymentNotificationOutboxService
    def notificationRequestService
    def originRequesterInfoService
    def paymentAnticipableInfoService
    def paymentBankSlipConfigService
    def paymentCampaignItemService
    def paymentCheckoutCallbackConfigService
    def paymentCustomerProductService
    def paymentDiscountConfigService
    def paymentDunningService
    def paymentEventListenerService
    def paymentFeeService
    def paymentFineConfigService
    def paymentInterestConfigService
    def paymentPostalServiceBatchService
    def paymentSplitService
    def paymentUndefinedBillingTypeConfigService
    def paymentUpdateService
    def pixQrCodeAsyncActionService
    def paymentPushNotificationRequestAsyncPreProcessingService
    def receivableAnticipationCancellationService
    def receivableAnticipationService
    def receivableAnticipationValidationCacheService
    def receivableAnticipationValidationService
    def referralService
    def sessionFactory
    def subscriptionUndefinedBillingTypeConfigService
    def transactionReceiptService

    def onError(entity, Boolean failOnError) {
        if (failOnError) throw new ValidationException(null, entity.errors)

        transactionStatus.setRollbackOnly()
        return entity
    }

	public Payment findPayment(id, providerId) {
		return Payment.find(id, providerId)
	}

	public PagedResultList findPaymentsToExternalQuery(Map params) {
		if (!params.textSearch || !params.value) return

		return Payment.createCriteria().list(max: params.max, offset: params.offset) {
			createAlias('customerAccount', 'ca', CriteriaSpecification.INNER_JOIN)

			and { eq("deleted", false) }

			if (params.textSearch) {
				if (CpfCnpjUtils.validaCPF(params.textSearch) || CpfCnpjUtils.validaCNPJ(params.textSearch)) {
					and { eq("ca.cpfCnpj", params.textSearch) }
				} else {
					String phoneSearch = PhoneNumberUtils.removeBrazilAreaCode(params.textSearch)
					List<String> phoneList = [PhoneNumberUtils.removeNinthDigitIfExists(phoneSearch), PhoneNumberUtils.sanitizeNumber(phoneSearch)]

					or {
						'in'('ca.mobilePhone', phoneList)
						'in'('ca.phone', phoneList)
					}
				}
			}

			if (params.value) eq("value", params.value)

            or {
                isNull("ca.cpfCnpj")
                not { eq("ca.cpfCnpj", grailsApplication.config.asaas.cnpj.substring(1)) }
            }
            and { not { "in"("provider.id", [1L, 241780L, 286793L, 380273L, 444772L]) } }

			order(params?.sort ?: "dueDate", params?.order ?: "desc")
		}
	}

    public PagedResultList list(Long customerAccountId, Long providerId, Integer max, Integer offset, Map search) {
        if (search) {
            if (!search.creditDateStart) search.remove("creditDateStart")
            if (!search.creditDateFinish) search.remove("creditDateFinish")

            if (search.deletedOnly && !Boolean.valueOf(search.deletedOnly)) search.deleted = true
        }

        return Payment.query((search ?: [:]) << [customerId: providerId, customerAccountId: customerAccountId]).list(readOnly: true, max: max, offset: offset)
    }

	public Payment createRecurrentPayment(Subscription subscription, Date dueDate, Boolean notify, Boolean failOnError = true, params = [:]) {
		Double value = 0
		if (params?.value) {
			value = params.value
		} else {
			if (subscription.plan) {
				value = subscription.plan.value
			} else if (subscription.product) {
				value = subscription.product.value
			} else {
				value = subscription.value
			}
		}

		if (subscription.sendPaymentByPostalService) {
			params.sendPaymentByPostalService = true
		}

        if (subscription.billingType.isPix() && !PixUtils.paymentReceivingWithPixEnabled(subscription.customerAccount.provider)) {
            subscription.billingType = BillingType.BOLETO
            subscription.save(failOnError: true)
        }

        Map paymentParams = [
            customerAccount: subscription.customerAccount,
            dueDate: dueDate,
            value: value,
            subscription: subscription,
            subscriptionBulkSave: params.subscriptionBulkSave.asBoolean(),
            billingType: subscription.billingType,
            billingInfo: subscription.billingInfo,
            grossValue: subscription.grossValue,
            automaticRoutine: params.automaticRoutine,
            sendPaymentByPostalService: params.sendPaymentByPostalService,
            externalReference: subscription.externalReference,
            description: subscription.description,
            apiOrigin: params.apiOrigin,
            asaasErpAccountingStatementExternalId: params.asaasErpAccountingStatementExternalId,
            paymentUndefinedBillingTypeConfig: subscriptionUndefinedBillingTypeConfigService.getUndefinedBillingTypeConfigMap(subscription.id)
        ]

		if (subscription.fineConfig) paymentParams.fine = [value: subscription.fineConfig.value, fineType: subscription.fineConfig.type]
		if (subscription.interestConfig) paymentParams.interest = [value: subscription.interestConfig.value]
		if (subscription.discountConfig) {
			paymentParams.discount = [value: subscription.discountConfig.value,
									  dueDateLimitDays:subscription.discountConfig.dueDateLimitDays,
									  limitDate: subscription.discountConfig.limitDate,
									  discountType: subscription.discountConfig.type]
		}

		CustomerProduct customerProduct = subscription.getCustomerProduct()
		if (customerProduct) setCustomerProductParams(paymentParams, customerProduct)

	 	SubscriptionFiscalConfig subscriptionFiscalConfig = subscription.getFiscalConfig()
	 	if (subscriptionFiscalConfig) setPaymentInvoiceParams(paymentParams, subscription)

        List<SubscriptionSplit> subscriptionSplitList = SubscriptionSplit.query([subscriptionId: subscription.id]).list()
        if (subscriptionSplitList) paymentParams.splitInfo = buildSplitInfoForSubscriptionPayment(subscriptionSplitList)

        SubscriptionCheckoutCallbackConfig subscriptionCheckoutCallbackConfig = SubscriptionCheckoutCallbackConfig.query([subscription: subscription]).get()
        if (subscriptionCheckoutCallbackConfig) {
            paymentParams.paymentCheckoutCallbackConfig = buildPaymentCheckoutCallbackConfigForSubscriptionPayment(subscriptionCheckoutCallbackConfig)
            if (params.ignoreCallbackDomainValidation) paymentParams.paymentCheckoutCallbackConfig.ignoreCallbackDomainValidation = true
        }

		Payment payment = save(paymentParams, failOnError, notify)
		if (payment.hasErrors()) return onError(payment, failOnError)

		SubscriptionPayment subscriptionPayment = new SubscriptionPayment()
		subscriptionPayment.subscription = subscription
		subscriptionPayment.payment = payment
		subscriptionPayment.provider = payment.provider

		subscriptionPayment.save(flush: true, failOnError: failOnError)
		if (subscriptionPayment.hasErrors()) {
            DomainUtils.copyAllErrorsFromObject(subscriptionPayment, payment)
            return onError(payment, failOnError)
        }

		subscription.addToSubscriptionPayments(subscriptionPayment)

		if (subscription.automaticAnticipation) {
			try {
                if (!payment.canScheduleAnticipation() && !payment.canAnticipate()) {
                    messageService.sendAutomaticAnticipationNotSavedToAccountManager(payment, payment.receivableAnticipationDisabledReason)
                } else {
                    CreateReceivableAnticipationAdapter createReceivableAnticipationAdapter = CreateReceivableAnticipationAdapter.buildByCreditCardPayment(payment, ReceivableAnticipationOriginRequesterInfoMethod.SUBSCRIPTION_AUTOMATIC)
                    receivableAnticipationService.save(createReceivableAnticipationAdapter)
                }
			} catch (Exception exception) {
                AsaasLogger.warn("PaymentService.createRecurrentPayment >> Erro ao antecipação agendada de cobrança recorrente. Assinatura: [${subscription.id}]", exception)
			}
		}

		return payment
	}

    public BigDecimal calculateNetValueByStatus(Customer customer, PaymentStatus status) {
        Map queryParams = [:]
        queryParams.customer = customer
        queryParams.status = status

        if (status == PaymentStatus.CONFIRMED) {
            queryParams.anticipated = false
        }

        return Payment.sumNetValue(queryParams).get()
    }

	public Payment save(params, Boolean notify) {
		return save(params, true, notify)
	}

    @Trace(resourceName = "PaymentService.save")
	public Payment save(params, Boolean failOnError, Boolean notify) {
        Payment payment = PaymentBuilder.build(params)

        createPaymentCustomTagOnSpan(payment)

        if (!payment.duplicatedPayment) validateCanCreatePaymentThroughUserInterface(payment.provider)

		InvoiceFiscalVO invoiceFiscalVo
		if (params.invoice) invoiceFiscalVo = InvoiceFiscalVO.build(params.invoice)

        Boolean paymentValueIsChangedByInvoice = false
        if (Boolean.valueOf(params.invoice?.updatePayment)) {
			invoiceFiscalVo.value = payment.value
			CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(invoiceFiscalVo)
			if (customerInvoiceSummary.paymentValueNeedsToBeUpdated()) {
				payment.value = customerInvoiceSummary.updatedPaymentValue
                paymentValueIsChangedByInvoice = true
                invoiceFiscalVo.value = customerInvoiceSummary.currentPaymentValue
			} else {
				invoiceFiscalVo.value = null
			}
		}

        if ((payment.installment && payment.installment.isCreditCard()) || paymentValueIsChangedByInvoice) {
            PaymentFeeVO paymentFeeVO = new PaymentFeeVO(payment)
            payment.netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        }

        if (payment.hasErrors()) return onError(payment, failOnError)

        if (!payment.duplicatedPayment) {
			payment.customerAccount?.provider.validateAccountDisabled()
		}

        if (!params.containsKey("discount") || !params.discount?.containsKey('value')) {
            params.discount = paymentDiscountConfigService.buildCustomerConfig(payment.value, payment.billingType, payment.provider)
        }

		if (!params.containsKey("interest") || !params.interest?.containsKey('value')) {
			params.interest = paymentInterestConfigService.buildCustomerConfig(payment.customerAccount?.provider)
		}

		if (!params.containsKey("fine") || !params.fine?.containsKey('value')) {
			params.fine = paymentFineConfigService.buildCustomerConfig(payment.customerAccount?.provider)
		}

        if (params.containsKey("softDescriptorText") && payment.provider.getConfig()?.overrideAsaasSoftDescriptor) {
            payment.softDescriptorText = params.softDescriptorText
        }

        List<AsaasError> asaasErrorList = []
        asaasErrorList.addAll(paymentDiscountConfigService.validate(payment, params.discount).asaasErrors)
        asaasErrorList.addAll(paymentInterestConfigService.validate(payment, params.interest).asaasErrors)
        asaasErrorList.addAll(paymentFineConfigService.validate(payment, params.fine).asaasErrors)

        for (AsaasError asaasError : asaasErrorList) {
            DomainUtils.addError(payment, asaasError.getMessage())
        }

        validateIfCustomerHasBalanceForPaymentCreationFee(payment, Boolean.valueOf(params.import))

        if (payment.hasErrors()) return onError(payment, failOnError)

        setNossoNumero(payment)

		payment.save(flush: true, failOnError: failOnError)
        if (payment.hasErrors()) return onError(payment, failOnError)

        if (payment.billingType.isUndefined()) paymentUndefinedBillingTypeConfigService.save(payment, params.paymentUndefinedBillingTypeConfig)

		payment.orderId = payment.id
        payment.save(failOnError: failOnError)

		paymentDiscountConfigService.save(payment, params.discount)
		paymentInterestConfigService.save(payment, params.interest)
		paymentFineConfigService.save(payment, params.fine)
        paymentSplitService.saveSplitInfo(payment, params)

        if (params.paymentCheckoutCallbackConfig) {
            PaymentCheckoutCallbackConfig paymentCheckoutCallbackConfig = paymentCheckoutCallbackConfigService.save(payment, params.paymentCheckoutCallbackConfig)

            if (paymentCheckoutCallbackConfig.hasErrors()) {
                DomainUtils.copyAllErrorsFromObject(paymentCheckoutCallbackConfig, payment)
            }
        }

        paymentEventListenerService.onCreated(payment)

        savePaymentAnticipableInfoIfNecessary(payment, params)

        if (payment.hasErrors()) return onError(payment, failOnError)

		if (params.customerProduct) saveCustomerProduct(payment, CustomerProductVO.build(params.customerProduct))

		if (invoiceFiscalVo) {
			if (payment.automaticRoutine && params.subscription && !payment.canBeInvoiced()) {
				messageService.notifyAccountManagerAboutErrorOnRecurrentPaymentInvoice(payment, payment.invoiceDisabledReason)
			} else {
				customerInvoiceService.savePaymentInvoice(payment, invoiceFiscalVo, [:])
			}
		}

        NotificationPriority notificationPriority = params.import ? NotificationPriority.LOW : NotificationPriority.HIGH
        if (notify) {
			notifyPaymentCreated(payment, notificationPriority)
		}

        if (!payment.installment) {
            notificationDispatcherPaymentNotificationOutboxService.savePaymentCreated(payment, notify, notificationPriority)
        }
        asaasErpAccountingStatementService.onPaymentCreate(payment.provider, payment, params.asaasErpAccountingStatementExternalId)

        saveAsaasMoneyMobilePushNotificationIfPossible(payment)

        paymentBankSlipConfigService.save(payment, params)

        bankSlipRegisterService.saveInBatchFileIfIsRegisteredBoleto(payment)

		if (Boolean.valueOf(params.createPaymentDunning)) {
            Map dunningParams = PaymentDunningBuilder.buildPaymentDunningMap(payment)
            PaymentDunning paymentDunning = paymentDunningService.save(payment.provider, UserUtils.getCurrentUser(), payment.id, dunningParams)
            if (paymentDunning.hasErrors()) throw new ValidationException(null, paymentDunning.errors)
        }

        saveEventsIfNecessary(payment.provider, BillingType.convert(payment.billingType))

        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CREATED)

		return payment
	}

	public void saveCustomerProduct(Payment payment, CustomerProductVO customerProductVO) {
		CustomerProduct customerProduct = customerProductService.findOrSave(payment.provider, customerProductVO)

		if (customerProduct.hasErrors()) throw new BusinessException(Utils.getMessageProperty(customerProduct.errors.allErrors[0]))

        paymentCustomerProductService.save(customerProduct, payment)
	}

    public Invoice saveInvoiceFromExistingPayment(Long paymentId, Customer customer, InvoiceFiscalVO invoiceFiscalVo, CustomerProductVO customerProductVo, Map params) {
		Payment payment = Payment.find(paymentId, customer.id)

		saveCustomerProduct(payment, customerProductVo)

    	Invoice invoice = customerInvoiceService.savePaymentInvoice(payment, invoiceFiscalVo, params)

		if (!Boolean.valueOf(params.updatePayment)) return invoice

		CustomerInvoiceSummary customerInvoiceSummary = new CustomerInvoiceSummary(payment, invoiceFiscalVo, customerProductVo)
		if (!customerInvoiceSummary.paymentValueNeedsToBeUpdated()) return invoice

		paymentUpdateService.update([providerId: customer.id, payment: payment, value: customerInvoiceSummary.updatedPaymentValue])

		if (payment.hasErrors()) throw new BusinessException(Utils.getMessageProperty("customerInvoice.create.error.payment.validation", [payment.getInvoiceNumber(), Utils.getMessageProperty(payment.errors.allErrors.first())]))

		return invoice
    }

	public void setNossoNumero(Payment payment) {
		if (payment.billingType in [BillingType.BOLETO, BillingType.UNDEFINED, BillingType.TRANSFER, BillingType.DEPOSIT]) {
			payment.nossoNumero = PaymentBuilder.buildNossoNumero(payment)
		}
	}

	public void notifyPaymentCreated(Payment payment) {
		notifyPaymentCreated(payment, NotificationPriority.HIGH)
	}

	public void notifyPaymentCreated(Payment payment, NotificationPriority notificationPriority) {
		notificationRequestService.save(payment.customerAccount, NotificationEvent.PAYMENT_CREATED, payment, notificationPriority)
	}

	public Payment saveWithCreditCard(params, Boolean notify) {
		Payment.withNewTransaction { status ->
			try {
				Customer provider = Customer.get(params.providerId)

				Payment payment

				if (params.customerAccount) {
					CustomerAccount customerAccount

					if (params.customerAccount.id) {
						CustomerAccountUpdateResponse customerAccountUpdateResponse = customerAccountService.update(params.providerId, params.customerAccount)

						customerAccount = customerAccountUpdateResponse.customerAccount
					} else {
						customerAccount = customerAccountService.save(provider, null, params.customerAccount)
						customerAccountService.createDefaultNotifications(customerAccount)
					}

					if (customerAccount.hasErrors()) {
						payment = new Payment()
						payment.errors.rejectValue("customerAccount", "invalid")

						status.setRollbackOnly()
						return payment
					}

					params.customerAccount.id = customerAccount.id
				}

				payment = PaymentBuilder.build(params)

                if (params.containsKey("softDescriptorText") && provider.getConfig()?.overrideAsaasSoftDescriptor) {
                    payment.softDescriptorText = params.softDescriptorText
                }

				payment.save(flush: true, failOnError: false)

				if (payment.hasErrors()) {
					status.setRollbackOnly()
					return payment
				}

                 if (params.paymentCheckoutCallbackConfig) {
                    PaymentCheckoutCallbackConfig paymentCheckoutCallbackConfig = paymentCheckoutCallbackConfigService.save(payment, params.paymentCheckoutCallbackConfig)

                    if (paymentCheckoutCallbackConfig.hasErrors()) {
                        DomainUtils.copyAllErrorsFromObject(paymentCheckoutCallbackConfig, payment)
                        status.setRollbackOnly()
                        return payment
                    }
                }

                payment.orderId = payment.id
                payment.save(flush: true, failOnError: false)

                paymentEventListenerService.onCreated(payment)

                savePaymentAnticipableInfoIfNecessary(payment, params)

                if (payment.hasErrors()) {
                    status.setRollbackOnly()
                    return payment
                }

				paymentSplitService.saveSplitInfo(payment, params)

				paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_CREATED)

                asaasErpAccountingStatementService.onPaymentCreate(payment.provider, payment, params.asaasErpAccountingStatementExternalId)
                notificationDispatcherPaymentNotificationOutboxService.savePaymentCreated(payment, false, null)

                saveEventsIfNecessary(payment.provider, BillingType.convert(payment.billingType))

                // Não executar NADA após esse método
                // Em caso de exceção haverá rollback da transação mas o valor já terá sido debitado no cartão
                if (params.authorizeOnly) {
                    processCreditCardAuthorization(payment, params)
                } else {
                    processCreditCardPayment(payment, params)
                }

                if (payment.hasErrors()) {
                    status.setRollbackOnly()
                    return payment
                }

				return payment
			} catch (Exception e) {
				status.setRollbackOnly()
				throw e
			}
		}
	}

    public void processCreditCardAuthorization(Payment payment, params) {
        Map authorizationResponse = [success: false]

        if (params.creditCard.billingInfoPublicId) {
            BillingInfo billingInfo = billingInfoService.findByPublicId(payment.customerAccount, params.creditCard.billingInfoPublicId)
            payment = validateBillingInfo(payment, billingInfo, params.creditCard.billingInfoPublicId)
            if (payment.hasErrors()) return
            authorizationResponse = creditCardService.authorize(payment, billingInfo, params.creditCardTransactionOriginInfo, null)
        } else {
            CreditCardTransactionInfoVo creditCardTransactionInfo =  buildCreditCardTransactionInfoVo(payment, params)
            if (!creditCardTransactionInfo) return
            authorizationResponse = creditCardService.authorize(payment, null, params.creditCardTransactionOriginInfo, creditCardTransactionInfo)
        }

        if (!authorizationResponse.success) {
            setRefuseReason(payment, authorizationResponse, params.creditCardTransactionOriginInfo?.originInterface)
        } else {
            payment.save(flush: true, failOnError: true)
            paymentAnticipableInfoService.updateIfNecessary(payment)
        }
    }

    public void processCreditCardPayment(Payment payment, params) {
        Map captureResponse = [success: false]

        Boolean needCreateAsaasMoneyPaymentCompromisedBalance = asaasMoneyPaymentCompromisedBalanceService.isNecessaryCreate(payment, params)

        if (params.creditCard.billingInfoPublicId) {
            BillingInfo billingInfo = billingInfoService.findByPublicId(payment.customerAccount, params.creditCard.billingInfoPublicId)
            payment = validateBillingInfo(payment, billingInfo, params.creditCard.billingInfoPublicId)
            if (payment.hasErrors()) return
            captureResponse = creditCardService.captureWithBillingInfo(payment, params.creditCard.billingInfoPublicId, params.creditCardTransactionOriginInfo, params.clearSaleSessionId)
		} else {
            CreditCardTransactionInfoVo creditCardTransactionInfo =  buildCreditCardTransactionInfoVo(payment, params)
            if (!creditCardTransactionInfo) return
            captureResponse = creditCardService.captureWithCreditCardInfo(payment, creditCardTransactionInfo, params.creditCardTransactionOriginInfo)
        }

        processCreditCardCaptureResponse(params, captureResponse, payment)

        if (captureResponse.success) {
            if (needCreateAsaasMoneyPaymentCompromisedBalance) asaasMoneyPaymentCompromisedBalanceService.saveIfNecessary(payment, params)
        }
	}

    public void captureAuthorizedPayment(Payment payment, params) {
        Map captureResponse = creditCardService.captureAuthorizedPayment(payment, params.creditCardTransactionOriginInfo)

        processCreditCardCaptureResponse(params, captureResponse, payment)
    }

    public Integer batchDelete(Long customerId, Map filters) {
        List<Long> idList = Payment.query((filters ?: [:]) + [customerId: customerId, column: "id"]).list(max: 9999, offset: 0)
        if (!filters.isSequencedList && Integer.valueOf(filters.totalCount) != idList.size()) throw new Exception("Quantia de cobranças localizadas [${idList.size()}] diferente do previsto [${filters.totalCount}].")

        Integer errorCount = 0
        Utils.forEachWithFlushSession(idList, 50, { Long paymentId ->
            Utils.withNewTransactionAndRollbackOnError({
                deleteThroughUserInterface(paymentId, customerId, false)
            }, [ignoreStackTrace: true, onError: { Exception exception ->
                errorCount++
                if (exception instanceof BusinessException) return

                AsaasLogger.error("${this.class.simpleName}.batchDelete >> Erro desconhecido ao tentar remover a cobrança [${paymentId}]", exception)
            }])
        })

        return errorCount
    }

	public Payment restore(Customer customer, id, Map params) {
		Payment payment = Payment.find(id, customer.id)

        if (!payment.canBeRestored()) throw new BusinessException(payment.businessErrorMessage)

		if (params.dueDate instanceof String) params.dueDate = CustomDateUtils.fromString(params.dueDate)

		if (params.dueDate) payment.dueDate = params.dueDate

        payment.deleted = false
		payment.postalServiceStatus = null
		payment.save(flush: true, failOnError: true)

        restoreBankSlipRegisterIfNecessary(payment)

		paymentCampaignItemService.restoreFromPaymentIfNecessary(payment)

        if (!payment.isPaid()) {
            payment.status = PaymentStatus.PENDING
            if (PaymentUtils.paymentDateHasBeenExceeded(payment)) payment.status = PaymentStatus.OVERDUE

            payment.save(flush: true, failOnError: true)

            pixQrCodeAsyncActionService.restoreDynamicQrCodeIfNecessary(payment)
        }

        paymentEventListenerService.onRestore(payment)

		paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_RESTORED)

        if (payment.installment) {
            AsaasApplicationHolder.applicationContext.installmentService.updateInstallmentCount(payment.installment)
        }

        Boolean shouldNotify = Utils.toBoolean(params.notifyCustomerAccount)
        if (shouldNotify) notifyPaymentCreated(payment)

        notificationDispatcherPaymentNotificationOutboxService.savePaymentCreated(payment, shouldNotify, NotificationPriority.HIGH)
        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment, null, true)

		return payment
	}

	public void restoreBankSlipRegisterIfNecessary(Payment payment) {
		if (payment.isPaid()) return

		BoletoBatchFileItem registerDeleteNotSent = BoletoBatchFileItem.itemNotSent(payment, BoletoAction.DELETE).get()
		if (registerDeleteNotSent) {
			boletoBatchFileItemService.removeItem(registerDeleteNotSent)
			return
		}

		if (!PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment)) return

		if (!payment.customerAccount.cpfCnpj) {
			throw new BusinessException("Não é possível restaurar a cobrança [${payment.getInvoiceNumber()}] porque o cliente não possui CPF/CNPJ. Preencha o CPF/CNPJ do cliente e tente novamente.")
		}

		Date minimumDueDateToBeRegistered = Payment.getMinimumDueDateToBeRegistered(payment.boletoBank ?: payment.provider.boletoBank)

		if (payment.dueDate.before(minimumDueDateToBeRegistered)) {
			payment.dueDate = minimumDueDateToBeRegistered
			payment.status = PaymentStatus.PENDING
		}

        if (payment.isDeprecatedBoletoBank()) payment.boletoBank = PaymentBuilder.selectBoletoBank(payment.provider, payment.customerAccount)

		paymentUpdateService.updateNossoNumero(payment)
		bankSlipRegisterService.registerBankSlip(payment)
	}

	public Payment undoReceivedInCash(Customer customer, Long paymentId) {
		Payment payment = Payment.find(paymentId, customer.id)

		if (!payment.canUndoReceivedInCash()) throw new BusinessException(payment.editDisabledReason)

		if (PaymentUndefinedBillingTypeConfig.equivalentToBoleto(payment)) payment.billingType = BillingType.BOLETO

		if (payment.originalValue && payment.value != payment.originalValue) {
			payment.value = payment.originalValue
			payment.originalValue = null
		}

		if (payment.dueDate.before(new Date().clearTime())) {
			payment.status = PaymentStatus.OVERDUE
		} else {
			payment.status = PaymentStatus.PENDING
		}

        PaymentFeeVO paymentFeeVO = new PaymentFeeVO(payment)
        payment.netValue = paymentFeeService.calculateNetValue(paymentFeeVO)
        payment.clientPaymentDate = null
		payment.paymentDate = null
		payment.onReceiving = true
		payment.save(failOnError: true)

		restoreBankSlipRegisterIfNecessary(payment)
		paymentDunningService.restoreDunningIfNecessary(payment)

        receivableAnticipationValidationService.onPaymentRestore(payment)

        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_RECEIVED_IN_CASH_UNDONE)

        asaasErpAccountingStatementService.onPaymentUpdate(payment.provider, payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentUpdated(payment)

		return payment
	}

    public Boolean deleteAllPaymentsAwaitingConfirmation(Customer customer, User userToNotifyOnError) {
        final Integer maxPaymentsPerCycle = 1000

        Map search = [:]
        search.column = "id"
        search.customer = customer
        search.anticipated = false
        search.statusList = [PaymentStatus.PENDING, PaymentStatus.OVERDUE, PaymentStatus.DUNNING_REQUESTED]
        PagedResultList paymentIdList = Payment.query(search).list(max: maxPaymentsPerCycle)

        List<Long> paymentErrorIdList = []
        for (Long paymentId in paymentIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                delete(Payment.get(paymentId), false, true)
            }, [logErrorMessage: "PaymentService.deletePendingOrOverdue >> Erro ao deletar a cobrança [${paymentId}]",
                onError: { paymentErrorIdList.add(paymentId) }])
        }

        if (paymentErrorIdList.size() > 0 && userToNotifyOnError) messageService.sendNotDeletedPaymentsAlertIfSysAdmin(customer, paymentErrorIdList, userToNotifyOnError)

        Boolean allPaymentsWereDeleted = maxPaymentsPerCycle >= paymentIdList.totalCount
        return allPaymentsWereDeleted
    }

    public void deleteThroughUserInterface(Long paymentId, Long customerId, Boolean notifyCustomerAccount) {
        Payment payment = Payment.find(paymentId, customerId)

        if (payment.deleted) return
        if (!payment.canDeleteThroughUserInterface()) throw new BusinessException("A cobrança [${payment.id}] não pode ser removida via interface: ${payment.editDisabledReason}")

        delete(payment, notifyCustomerAccount)
    }

	public Payment delete(id, Long providerId, Boolean notifyCustomerAccount) {
		return delete(Payment.find(id, providerId), notifyCustomerAccount, false)
	}

    public Payment delete(Payment payment, Boolean notifyCustomerAccount) {
        return delete(payment, notifyCustomerAccount, false)
    }

    public Payment delete(Payment payment, Boolean notifyCustomerAccount, Boolean isAccountDisabling) {
        if (payment.deleted) return payment

        if (!payment.canDelete(isAccountDisabling)) {
            throw new BusinessException("A cobrança [${payment.id}] não pode ser removida: ${payment.editDisabledReason}")
        }

		if (payment.hasValidReceivableAnticipation()) receivableAnticipationCancellationService.cancel(payment.getCurrentAnticipation(), ReceivableAnticipationCancelReason.PAYMENT_DELETED)

		if (payment.postalServiceStatus in PostalServiceStatus.notSentYet()) payment.postalServiceStatus = null

		payment.deleted = true
		payment.automaticRoutine = true

        payment.avoidExplicitSave = true
        paymentAnticipableInfoService.onPaymentDeleted(payment.id)
        if (PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment)) {
            boletoBatchFileItemService.delete(payment)
        }
        payment.avoidExplicitSave = false

		payment.save(flush: true, failOnError: true)

        asaasErpAccountingStatementService.onPaymentDelete(payment.provider, payment)
        notificationDispatcherPaymentNotificationOutboxService.savePaymentDeleted(payment, notifyCustomerAccount)
        notificationDispatcherPaymentNotificationOutboxService.saveTransactionReceiptPaymentDeletedOnDemandIfNecessary(payment)

        pixQrCodeAsyncActionService.deleteDynamicQrCodeIfNecessary(payment)

        notificationRequestService.cancelPaymentUnsentNotifications(payment)

        if (!CustomerParameter.getValue(payment.provider, CustomerParameterName.DISABLE_NOTIFY_USERS_DELETED_PAYMENT)) {
            notificationRequestService.saveWithoutNotification(payment.customerAccount, NotificationEvent.PAYMENT_DELETED, payment, NotificationPriority.HIGH, NotificationReceiver.PROVIDER)
        }

        if (notifyCustomerAccount) {
			notificationRequestService.saveWithoutNotification(payment.customerAccount, NotificationEvent.PAYMENT_DELETED, payment, NotificationPriority.HIGH, NotificationReceiver.CUSTOMER)
		}

		if (payment.installment) {
			AsaasApplicationHolder.applicationContext.installmentService.updateInstallmentCount(payment.installment)

            if (payment.installment.allPaymentsAreDeleted()) {
				AsaasApplicationHolder.applicationContext.installmentService.deleteInstallmentOnly(payment.installment)
			}
		}

        if (payment.hasCancellableDunning()) {
            PaymentDunning paymentDunning = payment.getDunning()

            if (paymentDunning.type.isDebtRecoveryAssistance()) {
                paymentDunningService.delete(payment)
            } else {
                creditBureauDunningService.cancel(paymentDunning, PaymentDunningCancellationReason.PAYMENT_DELETED)
            }
		}

		if (payment.getInvoice()) {
			customerInvoiceService.cancelInvoice(payment)
		} else if (payment.installment?.getInvoice()) {
			customerInvoiceService.updateInstallmentInvoice(payment)
		}

		paymentCampaignItemService.deleteFromPaymentIfNecessary(payment)
        paymentPushNotificationRequestAsyncPreProcessingService.save(payment, PushNotificationRequestEvent.PAYMENT_DELETED)
        paymentSplitService.cancelPending(payment, PaymentSplitCancellationReason.PAYMENT_DELETED)

		return payment
	}

	def buildViewInvoiceUrlShortened(payment) {
		if (Environment.getCurrent().equals(Environment.PRODUCTION)) {
			return grailsApplication.config.asaas.app.shortenedUrl + grailsLinkGenerator.link(mapping: "payment-checkout", params: [id: payment.externalToken], absolute: false)
		} else {
			return grailsLinkGenerator.link(mapping: "payment-checkout", params: [id: payment.externalToken], absolute: true)
		}
	}

	def buildViewInvoiceCustomerUrlShortened(payment) {
		if (Environment.getCurrent().equals(Environment.PRODUCTION)) {
			return grailsApplication.config.asaas.app.shortenedUrl + grailsLinkGenerator.link(mapping: "payment-checkout", params: [id: payment.externalToken], absolute: false)
		} else {
			return grailsLinkGenerator.link(mapping: "payment-checkout", params: [id: payment.externalToken], absolute: true)
		}
	}

	def buildBoletoUrl(payment) {
		return grailsLinkGenerator.link(controller: 'boleto', action: 'downloadPdf', id: payment.externalToken, absolute: true)
	}

	public void enableSynchronization(Payment payment, ExternalApplication application) {
		if(externalIdentifierService.getSynchronizationStatus(payment, application) == SynchronizationStatus.SUCCESS) return

		externalIdentifierService.findByPayment(payment, application)?.delete(flush: true)
	}

	public Payment validateSaveParams(params) {
		Payment payment = new Payment()
		if (!params.customerAccountId) {
			DomainUtils.addError(payment, "O cliente deve ser informado.")
		}

		if (!params.billingType) {
			DomainUtils.addError(payment, "A forma de pagamento deve ser informada.")
		}

		ChargeType chargeType = ChargeType.convert(params.chargeType)
		if(!chargeType) {
			DomainUtils.addError(payment, "O tipo de cobrança deve ser informado.")
		}

		if (!params.dueDate) {
			DomainUtils.addError(payment, "A data de vencimento da cobrança deve ser informada.")
		}

		if (params.sendPaymentByPostalService) {
			validateAndBuildPostalServiceMessageErrorIfNecessary(payment, params.long("customerAccountId"), params.dueDate)
		}

		return payment
	}

	public Payment resendByPostalService(Long paymentId, Long providerId) {
		Payment payment = findPayment(paymentId, providerId)

        List<AsaasError> errors = PaymentPostalServiceResentValidator.validate(payment)
        for (AsaasError validationError : errors) {
            DomainUtils.addError(payment, Utils.getMessageProperty(validationError.code))
        }

		if (payment.hasErrors()) return payment

		payment.postalServiceStatus = PostalServiceStatus.AWAITING_TO_BE_PROCESSED
		payment.save(failOnError: true)

		return payment
	}

	public Payment sendByPostalService(Payment payment, Map params) {
		PaymentPostalServiceValidator validator = new PaymentPostalServiceValidator(payment)

		if (!validator.isValid()) {
			validator.addErrorsTo(payment)
			return payment
		}

		if (params.sendAllInstallments?.toBoolean()) {
			for (Payment paymentToUpdate : payment.installment.getNextAvailablePaymentsToSendByPostalService(payment)) {
				setPostalServiceAsAwaitingToBeProcessed(paymentToUpdate)
			}
		}

		return setPostalServiceAsAwaitingToBeProcessed(payment)
	}

	public Payment cancelSendByPostalService(Payment payment) {
        if (payment.postalServiceStatus == PostalServiceStatus.PROCESSED) {
            DomainUtils.addError(payment, "Cobrança já enviada")
            return payment
        }

		payment.postalServiceStatus = null

		payment.save(failOnError: true)

		return payment
	}

	public void registerBankSlips(List<Payment> listOfPayment) {
		for (Payment payment : listOfPayment) {
			bankSlipRegisterService.registerBankSlip(payment)
		}
	}

	public Boolean hasAtLeastOnePaymentConfirmedOrReceived(Customer customer) {
 		return Payment.count(customer, null, null, [PaymentStatus.CONFIRMED, PaymentStatus.RECEIVED]) > 0
 	}

 	public List<Map> listCreditCardHistory(Payment payment) {
 		List<Map> result = []

 		Map params = [:]

 		if (payment.installment) {
 			params.installmentId = payment.installment.id
 		} else {
 			params.paymentId = payment.id
 		}

 		List<ClearsaleResponse> clearsaleResponse = ClearsaleResponse.query(params).list()

 		clearsaleResponse.each{
			 Map historyMap = [dateCreated: it.dateCreated, type: "Validador Antifraude", status: it.isApproved() ? "Aprovado" : "Rejeitado"]

			if (it.pedidoScore != null) historyMap.status = historyMap.status + " (score: ${it.pedidoScore})"

			 result.add(historyMap)
		}

 		List<CreditCardTransactionLog> creditCardTransactionLog = CreditCardTransactionLog.query(params + [customerAccountId: payment.customerAccount.id, subscriptionId: payment?.subscription?.id]).list()

 		creditCardTransactionLog.each {
            String typeText = "Operadora do cartão"
            if (it.gateway) typeText += " | Gateway: ${it.gateway}"

            Map historyMap = [dateCreated: it.dateCreated,
                              type: typeText,
                              status: Utils.getMessageProperty("creditCardTransacion.event.${it.event.toString()}.description"),
                              message: it.message]
 			if (it.transactionIdentifier) historyMap.status = historyMap.status + " (TID: ${it.transactionIdentifier})"

 			result.add(historyMap)
 		}

 		return result.sort{ it.dateCreated }
 	}

    public String findCreditCardTransactionMcc(Payment payment) {
        if (!payment.isCreditCard()) return null

        Map queryParams = [:]
        queryParams.column = "mcc"

        if (payment.installment) {
            queryParams.installment = payment.installment
        } else {
            queryParams.payment = payment
        }

        String mcc = CreditCardTransactionInfo.query(queryParams).get()
        if (mcc) return mcc

        return CreditCardTransactionAnalysis.query(queryParams).get()
    }

    public Boolean canCreateThroughUserInterface(Customer customer) {
        Boolean hasCustomerParameterEnabled = CustomerParameter.getValue(customer, CustomerParameterName.PAYMENT_CREATE_THROUGH_USER_INTERFACE_DISABLED)

        if (hasCustomerParameterEnabled) return false

        return true
    }

    public void validateCanCreatePaymentThroughUserInterface(Customer customer) {
        EventOriginType eventOriginType = originRequesterInfoService.getEventOrigin()

        if (!eventOriginType.isWeb() && !eventOriginType.isMobile()) return

        if (!canCreateThroughUserInterface(customer)) {
            throw new BusinessException(Utils.getMessageProperty("default.notAllowed.message"))
        }
    }

    private void savePaymentAnticipableInfoIfNecessary(Payment payment, Map params) {
        Boolean isBankSlipInstallmentWithMoreThanTwelvePayments = payment.installment && payment.billingType.isBoleto() && payment.installment.installmentCount > 12
        Boolean mustAnalyzeAnticipationSubscription = params.subscriptionBulkSave.asBoolean() && payment.billingType.isEquivalentToAnticipation()
        if (isBankSlipInstallmentWithMoreThanTwelvePayments || mustAnalyzeAnticipationSubscription) {
            paymentAnticipableInfoService.sendToAnalysisQueue(payment.id)
        } else if (!payment.onReceiving) {
            receivableAnticipationValidationService.onPaymentChange(payment)
        }
    }

    private void saveEventsIfNecessary(Customer customer, BillingType billingType) {
        Boolean hasEventCreatedPaymentOnMethod = customerEventService.hasCreatedPaymentByBillingType(customer, billingType)

        if (hasEventCreatedPaymentOnMethod) return

        customerEventService.saveFirstPaymentCreatedOnMethod(customer, billingType)
        CustomerEventName eventName = billingType.convertToFirstPaymentCreatedCustomerEventName()
        customerEventCacheService.evictHasEventFirstPaymentCreatedOnMethod(customer.id, eventName)

        if (customerEventCacheService.hasEventCreatedPayments(customer)) return

        referralService.saveUpdateToValidatedReferralStatus(customer.id, ReferralValidationOrigin.PRODUCT_USAGE)
        customerEventService.save(customer, CustomerEventName.PAYMENT_CREATED, null, new Date())
        customerEventCacheService.evictHasEventCreatedPayments(customer.id)
        facebookEventService.saveFirstPaymentCreatedEvent(customer.id)
        receivableAnticipationValidationCacheService.evictIsFirstUse(customer.id)
    }

    private void saveAsaasMoneyMobilePushNotificationIfPossible(Payment payment) {
        if (payment.onReceiving) return
        if (!payment.installment || payment.installmentNumber == 1) asaasMoneyMobilePushNotificationService.onPaymentCreated(payment.customerAccount.cpfCnpj, payment.id)
    }

	private Boolean isCustomerFirstPayment(Payment payment) {
		return Payment.query([customer: payment.provider, differentFromId: payment.id, exists: true, deleted: true]).get() == null
	}

    private void setCustomerProductParams(Map paymentParams, CustomerProduct customerProduct) {
        paymentParams.customerProduct = [:]
        paymentParams.customerProduct.id = customerProduct.id
        paymentParams.customerProduct.name = customerProduct.name
        paymentParams.customerProduct.municipalServiceExternalId = customerProduct.municipalServiceExternalId
        paymentParams.customerProduct.municipalServiceDescription = customerProduct.municipalServiceDescription
        paymentParams.customerProduct.issTax = customerProduct.issTax
        paymentParams.customerProduct.defaultProduct = customerProduct.defaultProduct
    }

    private void setPaymentInvoiceParams(Map paymentParams, Subscription subscription) {
        SubscriptionFiscalConfig subscriptionFiscalConfig = subscription.getFiscalConfig()
        paymentParams.invoice = [:]
        paymentParams.invoice.observations = subscriptionFiscalConfig.observations

        paymentParams.invoice.fiscalConfig = [:]
        paymentParams.invoice.fiscalConfig.invoiceCreationPeriod = subscriptionFiscalConfig.invoiceCreationPeriod
        paymentParams.invoice.fiscalConfig.receivedOnly = subscriptionFiscalConfig.receivedOnly
        paymentParams.invoice.fiscalConfig.daysBeforeDueDate = subscriptionFiscalConfig.daysBeforeDueDate
        paymentParams.invoice.fiscalConfig.updateRecurrentPaymentValues = subscriptionFiscalConfig.updateRecurrentPaymentValues

        SubscriptionTaxInfo subscriptionTaxInfo = subscription.getTaxInfo()
        paymentParams.invoice.taxInfo = [:]
        paymentParams.invoice.taxInfo.retainIss = subscriptionTaxInfo.retainIss
        paymentParams.invoice.taxInfo.issTax = subscriptionTaxInfo.issTax

        paymentParams.invoice.taxInfo.cofinsPercent = subscriptionTaxInfo.cofinsPercentage
        paymentParams.invoice.taxInfo.csllPercent = subscriptionTaxInfo.csllPercentage
        paymentParams.invoice.taxInfo.inssPercent = subscriptionTaxInfo.inssPercentage
        paymentParams.invoice.taxInfo.irPercent = subscriptionTaxInfo.irPercentage
        paymentParams.invoice.taxInfo.pisPercent = subscriptionTaxInfo.pisPercentage

        paymentParams.invoice.taxInfo.cofinsValue = subscriptionTaxInfo.cofinsValue
        paymentParams.invoice.taxInfo.csllValue = subscriptionTaxInfo.csllValue
        paymentParams.invoice.taxInfo.inssValue = subscriptionTaxInfo.inssValue
        paymentParams.invoice.taxInfo.irValue = subscriptionTaxInfo.irValue
        paymentParams.invoice.taxInfo.pisValue = subscriptionTaxInfo.pisValue

        if (Boolean.valueOf(paymentParams.invoice.fiscalConfig.updateRecurrentPaymentValues)) {
            paymentParams.invoice.value = subscriptionTaxInfo.invoiceValue
        }

        paymentParams.invoice.deductions = subscriptionTaxInfo.deductions
    }

    private List<Map> buildSplitInfoForSubscriptionPayment(List<SubscriptionSplit> subscriptionSplitList) {
        List<Map> splitList = subscriptionSplitList.collect {
            Map splitMap = [ walletId: it.wallet.publicId ]

            if (it.fixedValue != null) splitMap.fixedValue = it.fixedValue
            if (it.percentualValue != null) splitMap.percentualValue = it.percentualValue
            if (it.externalReference != null) splitMap.externalReference = it.externalReference

            return splitMap
        }

        return splitList
    }

    private Map buildPaymentCheckoutCallbackConfigForSubscriptionPayment(SubscriptionCheckoutCallbackConfig subscriptionCheckoutCallbackConfig) {
        return [successUrl: subscriptionCheckoutCallbackConfig.successUrl, autoRedirect: subscriptionCheckoutCallbackConfig.autoRedirect]
    }

    private void validateAndBuildPostalServiceMessageErrorIfNecessary(Payment payment, Long customerAccountId, String dueDate) {
        CustomerAccount customerAccount = CustomerAccount.get(customerAccountId)

        PaymentPostalServiceValidator paymentPostalServiceValidator = new PaymentPostalServiceValidator()
        paymentPostalServiceValidator.customer = customerAccount.provider
        paymentPostalServiceValidator.address = customerAccount.buildAddress()
        paymentPostalServiceValidator.dueDate = CustomDateUtils.fromString(dueDate)

        List<PostalServiceSendingError> reasons = paymentPostalServiceValidator.validate()

        if (reasons) {
            for (PostalServiceSendingError reason : reasons) {
                DomainUtils.addError(payment, Utils.getMessageProperty("postal.service.sending.error.${reason.toString()}.description"))
            }
        }
    }

    private Payment setPostalServiceAsAwaitingToBeProcessed(Payment payment) {
        payment.postalServiceStatus = paymentPostalServiceBatchService.getPostalServiceStatusToDeliveryInTime(payment)
        return payment.save(failOnError: true)
    }

    private void validateIfCustomerHasBalanceForPaymentCreationFee(Payment payment, Boolean paymentImport) {
        if (!payment.isNew) return

        if (payment.onReceiving) return

        if (payment.duplicatedPayment) return

        if (payment.automaticRoutine) return

        if (paymentImport) return

        Customer customer = payment.provider

        BigDecimal paymentFee = CustomerParameter.getNumericValue(customer, CustomerParameterName.PAYMENT_CREATION_FEE)

        if (!paymentFee) return

        Boolean hasPaymentCreationRefuse = CustomerParameter.getValue(customer, CustomerParameterName.ENABLE_REFUSE_PAYMENT_CREATION_WITH_CREATION_FEE_IF_HAS_NO_BALANCE)

        if (!hasPaymentCreationRefuse) return

        if (!customer.hasSufficientBalance(paymentFee)) {
            throw new BusinessException(Utils.getMessageProperty('customerParameter.paymentCreationFeeWithoutBalance.validation.message'))
        }
    }

    private Payment validateBillingInfo(Payment payment, BillingInfo billingInfo, String billingInfoPublicId) {
        CreditCardValidator creditCardValidator = new CreditCardValidator()

        if (!billingInfo) {
            DomainUtils.addFieldError(payment, "creditCard", "creditcardtoken.notFound", billingInfoPublicId)
            return payment
        }

        if (!creditCardValidator.validate(billingInfo.creditCardInfo)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(creditCardValidator, "creditCard", payment)
            return payment
        }

        return payment
    }

    private void setRefuseReason(Payment payment, Map transactionResponse, CreditCardTransactionOriginInterface creditCardTransactionOriginInterface) {
        if (creditCardTransactionOriginInterface?.isApi() && CustomerParameter.getValue(payment.provider, CustomerParameterName.ALLOW_CREDIT_CARD_ACQUIRER_REFUSE_REASON)) {
            String refuseReason

            if (transactionResponse.customerErrorMessage) {
                refuseReason = transactionResponse.customerErrorMessage
            } else {
                refuseReason = CreditCardAcquirerRefuseReason.getCreditCardAcquirerRefuseReason(transactionResponse.acquirerReturnCode, transactionResponse.message)
            }

            DomainUtils.addError(payment, refuseReason)
        } else if (transactionResponse.payerErrorMessage) {
            DomainUtils.addError(payment, transactionResponse.payerErrorMessage)
        } else {
            DomainUtils.addFieldError(payment, "creditCard", "failed")
        }
    }

    private CreditCardTransactionInfoVo buildCreditCardTransactionInfoVo(Payment payment, Map params) {
        CreditCard creditCard = CreditCard.build(params.creditCard)
        CreditCardValidator creditCardValidator = new CreditCardValidator()

        if (!creditCardValidator.validate(creditCard)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(creditCardValidator, "creditCard", payment)
            return null
        }

        HolderInfo holderInfo = HolderInfo.build(params.creditCardHolderInfo)

        Boolean bypassClearSale = payment.provider.customerConfig.clearSaleBypassAllowed || payment.creditCardRiskValidationNotRequired()
        if (!bypassClearSale && !creditCardValidator.validateHolderInfo(holderInfo)) {
            DomainUtils.copyAllErrorsFromAsaasErrorList(creditCardValidator, "creditCard", payment)
            return null
        }

        CreditCardTransactionInfoVo creditCardTransactionInfo = new CreditCardTransactionInfoVo()
        creditCardTransactionInfo.payment = payment
        creditCardTransactionInfo.creditCard = creditCard
        creditCardTransactionInfo.holderInfo = holderInfo
        creditCardTransactionInfo.bypassClearSale = bypassClearSale
        creditCardTransactionInfo.remoteIp = params.creditCardTransactionOriginInfo?.remoteIp
        creditCardTransactionInfo.payerRemoteIp = params.creditCardTransactionOriginInfo?.payerRemoteIp
        creditCardTransactionInfo.clearSaleSessionId = params.clearSaleSessionId

        return creditCardTransactionInfo
    }

    private void processCreditCardCaptureResponse(Map params, Map captureResponse, Payment payment) {
        if (!captureResponse.success) {
            setRefuseReason(payment, captureResponse, params.creditCardTransactionOriginInfo?.originInterface)
        } else {
            payment.save(flush: true, failOnError: true)
            paymentAnticipableInfoService.updateIfNecessary(payment)
        }
    }

    private void createPaymentCustomTagOnSpan(Payment payment) {
        if (!payment) return

        ApplicationMonitoringUtils.addCustomTagOnSpan("payment.billingType", payment.billingType?.toString())

        ChargeType chargeType = ChargeType.DETACHED
        if (payment.installment) {
            chargeType = ChargeType.INSTALLMENT
        } else if (payment.subscriptionTemp) {
            chargeType = ChargeType.RECURRENT
        }

        ApplicationMonitoringUtils.addCustomTagOnSpan("payment.chargeType", chargeType.toString())
    }
}

package com.asaas.service.api

import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiCreditCardParser
import com.asaas.api.ApiCriticalActionParser
import com.asaas.api.ApiCustomerAccountGroupParser
import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiPaymentBillingInfoParser
import com.asaas.api.ApiPaymentParser
import com.asaas.api.ApiPaymentRefundParser
import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCardUtils
import com.asaas.customer.CustomerParameterName
import com.asaas.customeraccount.CustomerAccountUpdateResponse
import com.asaas.domain.criticalaction.CriticalActionGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerAccountGroup
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.domain.receivableanticipation.ReceivableAnticipationOriginRequesterInfoMethod
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.exception.PaymentNotFoundException
import com.asaas.exception.SubscriptionNotFoundException
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.log.AsaasLogger
import com.asaas.postalservice.PostalServiceStatus
import com.asaas.receivableanticipation.adapter.CreateReceivableAnticipationAdapter
import com.asaas.segment.PaymentOriginTracker
import com.asaas.utils.DomainUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ApiPaymentService extends ApiBaseService {

    def apiResponseBuilderService
    def billingTypeService
    def boletoService
    def cercContractualEffectService
    def customerAccountService
    def customerFeatureService
    def installmentService
    def linkService
    def paymentConfirmService
    def paymentNotificationRequestService
    def paymentRefundService
    def paymentService
    def paymentUpdateService
    def pixCreditService
    def pixQrCodeService
	def receivableAnticipationAgreementService
    def receivableAnticipationService

	def find(params) {
		try {
            return ApiPaymentParser.buildResponseItem(Payment.find(params.id, getProvider(params)), [expandCustomer: getExpandCustomer(params)])
		} catch(PaymentNotFoundException e) {
			return apiResponseBuilderService.buildNotFoundItem()
		}
	}

	def list(params) {
        Customer customer = getProviderInstance(params)
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)
        Boolean isMobileAppRequest = ApiMobileUtils.isMobileAppRequest()

		Map fields = parseListingFilters(params)

        List<Long> paymentIdList = paymentService.list(fields.customerAccountId, customer.id, limit, offset, fields << [column: "id"])

        List<Map> extraData = []
        if (isMobileAppRequest) {
            List<CustomerAccountGroup> customerAccountGroups = CustomerAccountGroup.query([customerId: customer.id]).list(max: 20, offset: 0, sort: "name", order: "asc", readOnly: true)
            if (customerAccountGroups) {
                List<Map> customerAccountGroupMap = customerAccountGroups.collect { ApiCustomerAccountGroupParser.buildResponseItem(it) }
                extraData << [customerAccountGroups: customerAccountGroupMap]
            }
        }

        Map responseList = apiResponseBuilderService.buildListWithThreads(paymentIdList, { Long paymentId ->
            if (isMobileAppRequest) {
                return ApiPaymentParser.buildLeanResponseItem(paymentId)
            } else {
                return ApiPaymentParser.buildResponseItem(paymentId, [expandCustomer: getExpandCustomer(params)])
            }
        }, extraData)

        return responseList
	}

	def save(params) {
		Map fields = ApiPaymentParser.parseRequestParams(params)
		fields.creditCardTransactionOriginInfo = ApiCreditCardParser.parseRequestCreditCardTransactionOriginInfoParams(params)

		Customer customer = getProviderInstance(params)

        Payment payment = new Payment()
		Map responseItem = [:]

		if (ApiMobileUtils.isMobileAppRequest()){
			responseItem.isFirstPayment = !customer.hasCreatedPayments()
            responseItem.shouldAskCustomerAcquisitionChannel = !customer.hasCreatedPayments()
		}

        if (!fields.customerAccount) return apiResponseBuilderService.buildError("invalid", "customer", "", ["Customer"])

		if (params.subscription) {
			try {
				Subscription subscription = Subscription.find(params.subscription, customer.id)

				Boolean failOnError = false
				payment = paymentService.createRecurrentPayment(subscription, fields.dueDate, true, failOnError, fields)
			} catch(SubscriptionNotFoundException e) {
				return apiResponseBuilderService.buildError("invalid", "subscription", "", ["Assinatura"])
			}
		} else {
			if (isInstallment(fields.installmentCount)) {
				def processInstallmentResult = processInstallment(fields)

				if (processInstallmentResult.hasErrors()) {
					return apiResponseBuilderService.buildErrorList(processInstallmentResult)
				}

				payment = processInstallmentResult.payments?.sort { it.installmentNumber }.first()
			} else if (isCreditCard(fields.billingType) && CreditCardUtils.hasAtLeastOneCreditCardInfo(fields?.creditCard)) {
				try {
                    payment = paymentService.saveWithCreditCard(fields, true)
				} catch  (BusinessException e) {
					return apiResponseBuilderService.buildInvalidActionExceptionError(e)
				}
			} else {
                Payment.withNewTransaction { status ->
                    CustomerAccount customerAccount = findOrCreateCustomerAccount(getProviderInstance(), fields)
                    if (customerAccount.hasErrors()) {
                        status.setRollbackOnly()
                        DomainUtils.copyAllErrorsFromObject(customerAccount, payment)
                    } else {
                        fields.customerAccount.id = customerAccount.id
                        payment = paymentService.save(fields, false, fields.notify)

                        if (payment.hasErrors()) status.setRollbackOnly()
                    }
                }
			}
		}

		if (payment.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(payment)
		}

		trackPaymentCreated([providerId: customer.id, payment: payment])

        if (payment.installment) {
            PaymentOriginTracker.trackApiCreation(null, payment.installment, null, params.mobileAction?.toString())
        } else {
            PaymentOriginTracker.trackApiCreation(payment, null, null, params.mobileAction?.toString())
        }

        Boolean automaticAnticipationEnabled = payment.isCreditCard() && CustomerParameter.getValue(getProviderInstance(params), CustomerParameterName.AUTOMATIC_ANTICIPATION_ON_API)
        Long paymentId = payment.id

        if (automaticAnticipationEnabled) {
            Utils.withNewTransactionAndRollbackOnError({
                Payment anticipablePayment = Payment.get(paymentId)
                anticipateIfPossible(anticipablePayment, fields.anticipation?.scheduleDaysAfterConfirmation)
            }, [ignoreStackTrace: true, onError: { Exception exception ->
                if (exception instanceof BusinessException) return
                AsaasLogger.error("ApiPaymentService.save >> Erro ao antecipar Payment [${ paymentId }]", exception)
            }])
        }

        Payment.withNewTransaction {
            Payment responsePayment = Payment.get(paymentId)
            responseItem << ApiPaymentParser.buildResponseItem(responsePayment, [expandCustomer: getExpandCustomer(params)])
            return apiResponseBuilderService.buildSuccess(responseItem)
        }
	}

	def processInstallment(fields) {
		Installment installment

		CustomerAccount customerAccount

		Payment.withNewTransaction { status ->
			try {
                customerAccount = findOrCreateCustomerAccount(getProviderInstance(), fields)
                if (customerAccount.hasErrors()) return

				installment = installmentService.save(fields + [customerAccount: customerAccount], true, false)

				if (installment.hasErrors() || installment.payments[0].hasErrors()) {
					status.setRollbackOnly()
					return
				}

				if (isCreditCard(fields.billingType) && CreditCardUtils.hasAtLeastOneCreditCardInfo(fields?.creditCard)) {
					fields.creditCardHolderInfo.city = installment.payments[0].customerAccount.city ?: "."
					fields.creditCardHolderInfo.uf = installment.payments[0].customerAccount.city?.state.toString() ?: "."
					fields.creditCardHolderInfo.phoneDDD = Utils.retrieveDDD(fields.creditCardHolderInfo.phone)
					fields.creditCardHolderInfo.phone = Utils.extractNumberWithoutDDD(fields.creditCardHolderInfo.phone)
					fields.creditCardHolderInfo.mobilePhoneDDD = Utils.retrieveDDD(fields.creditCardHolderInfo.mobilePhone)
					fields.creditCardHolderInfo.mobilePhone = Utils.extractNumberWithoutDDD(fields.creditCardHolderInfo.mobilePhone)


                    Payment firstRemainingPayment = installment.getFirstRemainingPayment()
                    if (fields.authorizeOnly) {
                        paymentService.processCreditCardAuthorization(firstRemainingPayment, fields)
                    } else {
                        paymentService.processCreditCardPayment(firstRemainingPayment, fields)
                    }

					if (firstRemainingPayment.hasErrors()) {
						status.setRollbackOnly()
					}
				}
			} catch (BusinessException e) {
				status.setRollbackOnly()
                installment = installment ?: new Installment()
				DomainUtils.addError(installment, e.getMessage())
			} catch (ValidationException validationException) {
				status.setRollbackOnly()
                throw validationException
			} catch (Exception e) {
				if (Utils.isLock(e)) {
                    AsaasLogger.warn("ApiPaymentService.processInstallment >>> Lock ao processar um parcelamento [${fields.providerId}]", e)
                } else {
                    AsaasLogger.error("ApiPaymentService.processInstallment >>> Erro ao processar um parcelamento [${fields.providerId}]", e)
                }
				status.setRollbackOnly()
                installment = installment ?: new Installment()
				DomainUtils.addError(installment, "Ocorreu um erro não tratado. Entre em contato com o suporte da API.")
			}
		}

		if (customerAccount.hasErrors()) return customerAccount

		if (installment.hasErrors()) return installment

		if (installment.payments[0].hasErrors()) return installment.payments[0]

		return installment
	}

    public Map update(Map params) {
        Map fields = ApiPaymentParser.parseRequestParams(params)
        Payment payment = paymentUpdateService.update(fields)

        if (payment.hasErrors()) return apiResponseBuilderService.buildErrorList(payment)

        return apiResponseBuilderService.buildSuccess(ApiPaymentParser.buildResponseItem(payment, [expandCustomer: getExpandCustomer(params)]))
    }

	def delete(params) {
		try {
			if (!params.id) {
				return apiResponseBuilderService.buildError("required", "payment", "id", ["Id"])
			}

			paymentService.delete(params.id, getProvider(params), parseBoolean(params.notifyCustomer))

			return apiResponseBuilderService.buildDeleted(params.id)
		} catch(PaymentNotFoundException e) {
			return apiResponseBuilderService.buildNotFoundItem()
		}
	}

    public Map refund(Map params) {
        Long paymentId = Payment.validateOwnerAndRetrieveId(params.id, getProvider(params))

        Map parsedParams = ApiPaymentRefundParser.parseSaveParams(params)
        Payment payment = paymentRefundService.executeRefundRequestedByProvider(paymentId, getProviderInstance(params), parsedParams)

        if (payment.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(payment)
        }

        Map responseMap = ApiPaymentParser.buildResponseItem(payment, [expandCustomer: ApiMobileUtils.isMobileAppRequest()])
        return apiResponseBuilderService.buildSuccess(responseMap)
    }

	def parseListingFilters(params) {
		Map fields = [:]
		if (params.containsKey("deleted")) {
			fields.deleted = Utils.toBoolean(params.deleted)
		}

		if (params.containsKey("anticipated")) {
			fields.anticipated = Utils.toBoolean(params.anticipated)
		}

		if (params.containsKey("invoiceViewed")) {
			fields.invoiceViewed = Utils.toBoolean(params.invoiceViewed)
		}

		if (params.billingType) {
            BillingType billingType = BillingType.convert(params.billingType)?.toRequestAPI()
            if (billingType) {
                fields.billingType = billingType
            }
		}

        if (params.chargeType) {
            fields.chargeType = params.chargeType
        }

		if (params.paymentDate) {
			fields.paymentDate = ApiBaseParser.parseAndValidateDate(params.paymentDate, "paymentDate")
		}

		if (params."dateCreated[ge]") {
			fields."dateCreated[ge]" = ApiBaseParser.parseAndValidateDate(params."dateCreated[ge]", "dateCreated[ge]")
		}

		if (params."dateCreated[le]") {
			fields."dateCreated[le]" = ApiBaseParser.parseAndValidateDate(params."dateCreated[le]", "dateCreated[le]")
		}

		if (params."paymentDate[ge]") {
            fields."paymentDate[ge]" = ApiBaseParser.parseAndValidateDate(params."paymentDate[ge]", "paymentDate[ge]")
		}

		if (params."paymentDate[le]") {
			fields."paymentDate[le]" = ApiBaseParser.parseAndValidateDate(params."paymentDate[le]", "paymentDate[le]")
		}

		if (params."dueDate[ge]") {
			fields."dueDate[ge]" = ApiBaseParser.parseAndValidateDate(params."dueDate[ge]", "dueDate[ge]")
		}

		if (params."dueDate[le]") {
			fields."dueDate[le]" = ApiBaseParser.parseAndValidateDate(params."dueDate[le]", "dueDate[le]")
		}

		if (params.confirmedDate) {
			fields.confirmedDate = ApiBaseParser.parseAndValidateDate(params.confirmedDate, "confirmedDate")
		}

		if (params."confirmedDate[ge]") {
			fields."confirmedDate[ge]" = ApiBaseParser.parseAndValidateDate(params."confirmedDate[ge]", "confirmedDate[ge]")
		}

		if (params."confirmedDate[le]") {
			fields."confirmedDate[le]" = ApiBaseParser.parseAndValidateDate(params."confirmedDate[le]", "confirmedDate[le]")
		}

		if (params."clientPaymentDate[ge]") {
			fields."clientPaymentDate[ge]" = ApiBaseParser.parseAndValidateDate(params."clientPaymentDate[ge]", "clientPaymentDate[ge]")
		}

		if (params."clientPaymentDate[le]") {
			fields."clientPaymentDate[le]" = ApiBaseParser.parseAndValidateDate(params."clientPaymentDate[le]", "clientPaymentDate[le]")
		}

        if (params.containsKey("estimatedCreditDate[le]")) {
            fields."estimatedCreditDate[le]" = ApiBaseParser.parseAndValidateDate(params."estimatedCreditDate[le]", "estimatedCreditDate[le]")
        }

        if (params.containsKey("estimatedCreditDate[ge]")) {
            fields."estimatedCreditDate[ge]" = ApiBaseParser.parseAndValidateDate(params."estimatedCreditDate[ge]", "estimatedCreditDate[ge]")
        }

        if (params.estimatedCreditDate) {
            fields.estimatedCreditDate = ApiBaseParser.parseAndValidateDate(params.estimatedCreditDate, "estimatedCreditDate")
        }

        if (params.containsKey("creditDate[le]")) {
            fields."creditDate[le]" = ApiBaseParser.parseAndValidateDate(params."creditDate[le]", "creditDate[le]")
        }

        if (params.containsKey("creditDate[ge]")) {
            fields."creditDate[ge]" = ApiBaseParser.parseAndValidateDate(params."creditDate[ge]", "creditDate[ge]")
        }

        if (params.containsKey("creditDate")) {
            fields.creditDate = ApiBaseParser.parseAndValidateDate(params.creditDate, "creditDate")
        }

		if (params.postalServiceStatus) {
			fields.postalServiceStatus = PostalServiceStatus.convert(params.postalServiceStatus)
		}

		if (params.containsKey("customer")) {
			try {
				fields.customerAccountId = CustomerAccount.find(params.customer, getProvider(params)).id
			} catch (Exception e) {
				fields.customerAccountId = -1L
			}
		}

		if (params.containsKey("subscription")) {
			try {
				fields.subscriptionId = Subscription.find(params.subscription, getProvider(params)).id
			} catch (Exception e) {
				fields.subscriptionId = -1L
			}
		}

		if (params.containsKey("installment")) {
			try {
				fields.installmentId = Installment.find(params.installment, getProvider(params)).id
			} catch (Exception e) {
				fields.installmentId = -1L
			}

			if (!fields.installmentId) fields.installmentId = -1L
		}

		if (params.customerAccountText) {
			fields.customerAccountText = params.customerAccountText
		}

		if (params.externalReference) {
			fields.externalReference = params.externalReference
		}

		if (params.status) {
			fields.status = params.status
		}

        if (params.invoiceStatus) {
            fields.invoiceStatus = params.invoiceStatus
        }

		if (params.customerAccountGroup || params.customerGroup) {
			fields.customerAccountGroup = params.customerAccountGroup ?: params.customerGroup
		}

        if (params.containsKey("order")) {
            fields.order = params.order
        }

        if (params.containsKey("receivableUnitId")) {
            fields.receivableUnitId = params.receivableUnitId
        }

        if (params.containsKey("pixQrCodeId")) {
            fields.pixQrCodeConciliationIdentifier = params.pixQrCodeId
        }

        if (params.containsKey("user")) {
            Long userId = User.query([column: "id", username: params.user, customerId: ApiBaseParser.getProviderId()]).get()
            fields.createdById = userId ?: -1L
        }

        if (params.containsKey("customerGroupName")) {
            Long customerAccountGroupId = CustomerAccountGroup.query([column: "id", groupName: params.customerGroupName, customerId: ApiBaseParser.getProviderId()]).get()
            fields.customerAccountGroup = customerAccountGroupId ?: -1L
        }

		return fields
	}

	def isCreditCard(String billingType) {
		try {
            if (billingType && BillingType.convert(billingType) == BillingType.MUNDIPAGG_CIELO) return true
		} catch (Exception exception) {
            AsaasLogger.error("ApiPaymentService.isCreditCard >> Erro ao verificar se é cartão de crédito. [billingType: ${billingType}]", exception)
		}

		return false
	}

	def payWithCreditCard(params) {
		try {
            Map fields = ApiPaymentParser.parseRequestParams(params)
            fields.creditCardTransactionOriginInfo = ApiCreditCardParser.parseRequestCreditCardTransactionOriginInfoParams(params)

            Payment payment = Payment.find(fields.id, fields.providerId)

			paymentService.processCreditCardPayment(payment, fields)

			if (payment.hasErrors()) {
				return apiResponseBuilderService.buildErrorList(payment)
			}

			return apiResponseBuilderService.buildSuccess(ApiPaymentParser.buildResponseItem(payment, [expandCustomer: getExpandCustomer(params)]))
		} catch (Exception e) {
			return apiResponseBuilderService.buildErrorFrom("failed", e.message)
		}
	}

    def resendNotification(params) {
        Long customerId = getProvider(params)
        Payment payment = Payment.find(params.id, customerId)

        paymentNotificationRequestService.resendNotificationManually(payment)

        return apiResponseBuilderService.buildSuccess([message: Utils.getMessageProperty("message.sent.success")])
    }

	def receiveInCash(params) {
		Utils.withNewTransactionAndRollbackOnError({
			Map fields = ApiPaymentParser.parseRequestParams(params)

			Customer customer = getProviderInstance(params)

			Payment payment = paymentConfirmService.confirmReceivedInCash(customer, Payment.find(fields.id, customer.id).id, fields)

			if (payment.hasErrors()) {
				return apiResponseBuilderService.buildErrorList(payment)
			}

			return apiResponseBuilderService.buildSuccess(ApiPaymentParser.buildResponseItem(payment, [expandCustomer: ApiMobileUtils.isMobileAppRequest()]))
		}, [onError: { Exception e -> throw e }, ignoreStackTrace: true])
	}

    public Map undoReceivedInCash(Map params) {
        Customer customer = getProviderInstance(params)
        Payment payment = paymentService.undoReceivedInCash(customer, Payment.find(params.id, customer.id).id)

        if (payment.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(payment)
        }

        return apiResponseBuilderService.buildSuccess(ApiPaymentParser.buildResponseItem(payment, [expandCustomer: ApiMobileUtils.isMobileAppRequest()]))
    }

	def getMinimumBankSlipDueDate(params) {
		try {
			Customer customer = getProviderInstance(params)

			Map responseItem = [:]
			responseItem.minimumDueDate = ApiBaseParser.formatDate(Payment.getMinimumDueDateToBeRegistered(customer.boletoBank))

			return apiResponseBuilderService.buildSuccess(responseItem)
		} catch (Exception e) {
			return apiResponseBuilderService.buildErrorFrom('unknow.error', Utils.getMessageProperty('unknow.error'))
		}
	}

	def getIdentificationField(params) {
		try {
			Payment payment = Payment.find(params.id, getProvider(params))

			if (!PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment)) {
				return apiResponseBuilderService.buildErrorFrom('invalid_action', "Somente é possível obter linha digitável quando a forma de pagamento for boleto bancário.")
			}

			Map responseItem = [:]
			responseItem.identificationField = boletoService.getLinhaDigitavel(payment)
            responseItem.nossoNumero = payment.getCurrentBankSlipNossoNumero()
			responseItem.barCode = boletoService.getBarCode(payment)

			return apiResponseBuilderService.buildSuccess(responseItem)
		} catch(PaymentNotFoundException e) {
			return apiResponseBuilderService.buildNotFoundItem()
		} catch (Exception e) {
			return apiResponseBuilderService.buildErrorFrom('unknow.error', Utils.getMessageProperty('unknow.error'))
		}
	}

    def restore(params) {
        Map fields = ApiPaymentParser.parseRequestParams(params)

        Payment restoredPayment = paymentService.restore(getProviderInstance(params), fields.id, fields)

        return apiResponseBuilderService.buildSuccess(ApiPaymentParser.buildResponseItem(restoredPayment, [expandCustomer: ApiMobileUtils.isMobileAppRequest()]))
    }

    def pixQrCode(params) {
        Payment payment = Payment.find(params.id, getProvider(params))

        if (PixUtils.paymentReceivingWithPixDisabled(payment.provider)) {
            return apiResponseBuilderService.buildErrorFromCode("pix.receivingWithPixDisabled")
        }

        if (!PixUtils.paymentReceivingWithPixEnabled(payment.provider)) {
            customerFeatureService.enablePixWithAsaasKeyFeatureIfNecessary(getProviderInstance(params))
        }

        Map responseItem = pixQrCodeService.createQrCodeForPayment(payment)
        return apiResponseBuilderService.buildSuccess(ApiPaymentParser.buildPixQrCodeResponseItem(responseItem))
    }

    public Map limits(Map params) {
        Customer customer = getProviderInstance(params)

        Long dailyPaymentsLimit = customer.getDailyPaymentsLimit()
        Long createdPaymentsToday = customer.countOfCreatedPaymentsToday()
        Boolean canCreateMorePaymentsToday = customer.canCreateMorePaymentsToday()

        Map daily = [:]
        daily.limit = dailyPaymentsLimit
        daily.used = createdPaymentsToday
        daily.wasReached = !canCreateMorePaymentsToday

        Map creation = [:]
        creation.daily = daily

        Map limitsMap = [:]
        limitsMap.creation = creation

        return apiResponseBuilderService.buildSuccess(limitsMap)
    }

    public Map requestPixRefundToken(Map params) {
        Payment payment = Payment.find(params.id, getProvider())
        BigDecimal valueToRefund = Utils.toBigDecimal(params.value) ?: payment.value

        CriticalActionGroup criticalActionGroup = pixCreditService.requestPaymentRefundToken(payment, valueToRefund, params.description)

        if (criticalActionGroup.hasErrors()) {
            return apiResponseBuilderService.buildErrorList(criticalActionGroup)
        }

        return apiResponseBuilderService.buildSuccess(ApiCriticalActionParser.buildGroupResponseItem(criticalActionGroup))
    }

    public Map captureAuthorizedPayment(params) {
        try {
            Map fields = ApiPaymentParser.parseRequestParams(params)
            fields.creditCardTransactionOriginInfo = ApiCreditCardParser.parseRequestCreditCardTransactionOriginInfoParams(params)

            Payment payment = Payment.find(fields.id, fields.providerId)

            paymentService.captureAuthorizedPayment(payment, fields)

            if (payment.hasErrors()) {
                return apiResponseBuilderService.buildErrorList(payment)
            }

            return apiResponseBuilderService.buildSuccess(ApiPaymentParser.buildResponseItem(payment, [expandCustomer: getExpandCustomer(params)]))
        } catch (BusinessException businessException) {
            return apiResponseBuilderService.buildErrorFrom("failed", businessException.message)
        }
    }

    public Map bankSlipPdf(Map params) {
        Payment payment = Payment.find(params.id, getProvider(params))

        BusinessValidation canDownloadBankSlipValidation = boletoService.validateBankSlipDownload(payment, false)

        if (!canDownloadBankSlipValidation.isValid()) return apiResponseBuilderService.buildErrorFrom("invalid_action", canDownloadBankSlipValidation.getFirstErrorMessage())

        byte[] boletoFileBytes = boletoService.buildPdfBytes(payment, false)

        return apiResponseBuilderService.buildFile(boletoFileBytes, "Boleto_${payment.publicId}.pdf")
    }

    public Map getPaymentStatus(Map params) {
        String paymentStatus = Payment.query([column: "status", publicId: params.id, customerId: getProvider(params), deleted: false]).get()

        Map response = [:]
        response.status = paymentStatus

        if (!response.status) {
            return apiResponseBuilderService.buildNotFoundItem()
        } else {
            return apiResponseBuilderService.buildSuccess(response)
        }
    }

    public Map getBillingInfo(Map params) {
        Payment payment = Payment.find(params.id, getProvider(params))

        List<BillingType> billingTypes = billingTypeService.getAllowedBillingTypeList(payment)

        Map pixQrCodeResponse
        Map creditCardResponse
        Map bankSlipResponse

        if (billingTypes.contains(BillingType.PIX)) {
            Map pixQrCodeInfo = pixQrCodeService.createQrCodeForPayment(payment)
            pixQrCodeResponse = ApiPaymentBillingInfoParser.buildPixQrCodeResponseItem(pixQrCodeInfo)
        }

        if (billingTypes.contains(BillingType.MUNDIPAGG_CIELO)) {
            creditCardResponse = ApiPaymentBillingInfoParser.buildCreditCardResponseItem(payment, payment.provider.customerConfig.overrideAsaasSoftDescriptor)
        }

        if (billingTypes.contains(BillingType.BOLETO)) {
            Map bankSlipInfo = getBankSplipInfo(payment)
            bankSlipResponse = ApiPaymentBillingInfoParser.buildBankSlipResponseItem(bankSlipInfo)
        }

        return ApiPaymentBillingInfoParser.buildBillingInfoResponseItem(pixQrCodeResponse, creditCardResponse, bankSlipResponse)
    }

    private Map getBankSplipInfo(Payment payment) {
        Map bankSlipInfo = [:]
        bankSlipInfo.identificationField = boletoService.getLinhaDigitavel(payment)
        bankSlipInfo.nossoNumero = payment.getCurrentBankSlipNossoNumero()
        bankSlipInfo.barCode = boletoService.getBarCode(payment)
        bankSlipInfo.daysAfterDueDateToRegistrationCancellation = payment.getBankSlipConfig().daysToAutomaticRegistrationCancellation
        bankSlipInfo.bankSlipUrl = linkService.boleto(payment)

        return bankSlipInfo
    }
    private void anticipateIfPossible(Payment payment, Integer scheduleDaysAfterConfirmation) {
		receivableAnticipationAgreementService.saveIfNecessary(payment.provider, RequestUtils.getRequest())

        if (payment.isCreditCard() && payment.isConfirmed()) {
            Boolean paymentWillBeAffected = cercContractualEffectService.paymentWillBeAffectedByContractualEffect(payment)
            if (paymentWillBeAffected) {
                AsaasLogger.info("ApiPaymentService.anticipateIfPossible >> Cobrança com efeito de contrato [paymentId: ${payment.id}]")
                return
            }
        }

		ReceivableAnticipationOriginRequesterInfoMethod method = ReceivableAnticipationOriginRequesterInfoMethod.AUTOMATIC_ON_API_PARAMETER
        if (payment.installment) {
            if (!payment.installment.canAnticipate() && !payment.installment.canScheduleAnticipation()) return

			CreateReceivableAnticipationAdapter adapter = CreateReceivableAnticipationAdapter.buildByCreditCardInstallmentWithScheduleDaysAfterConfirmation(payment.installment, method, scheduleDaysAfterConfirmation)
			receivableAnticipationService.save(adapter)
        } else if (payment) {
            if (!payment.canAnticipate() && !payment.canScheduleAnticipation()) return

			CreateReceivableAnticipationAdapter adapter = CreateReceivableAnticipationAdapter.buildByCreditCardPaymentWithScheduleDaysAfterConfirmation(payment, method, scheduleDaysAfterConfirmation)
			receivableAnticipationService.save(adapter)
        }
    }

    private CustomerAccount findOrCreateCustomerAccount(Customer customer, Map parsedFields) {
        if (parsedFields.customerAccount.id) return CustomerAccount.find(parsedFields.customerAccount.id, customer.id)

        CustomerAccount customerAccount
        String externalReference = parsedFields.customerAccount.externalReference
        String cpfCnpj = parsedFields.customerAccount.cpfCnpj

        if (externalReference.asBoolean() && cpfCnpj.asBoolean()) {
            customerAccount = CustomerAccount.query([customer: customer, externalReference: externalReference, cpfCnpj: cpfCnpj]).get()

            if (customerAccount.asBoolean()) {
                Map customerAccountFields = parsedFields.customerAccount << [id: customerAccount.id]
                CustomerAccountUpdateResponse customerAccountUpdateResponse = customerAccountService.update(customer.id, customerAccountFields)

                if (!customerAccountUpdateResponse.customerAccount.hasErrors()) return customerAccountUpdateResponse.customerAccount

                return customerAccount
            }
        }

        customerAccount = customerAccountService.save(customer, null, false, parsedFields.customerAccount)
        if (customerAccount.hasErrors()) return customerAccount

        customerAccountService.createDefaultNotifications(customerAccount)

        return customerAccount
    }
}

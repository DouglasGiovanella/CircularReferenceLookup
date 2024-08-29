package com.asaas.service.paymentcampaign

import com.asaas.billinginfo.BillingType
import com.asaas.billinginfo.ChargeType
import com.asaas.creditcard.CreditCardUtils
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.checkoutcallbackconfig.PaymentCheckoutCallbackConfig
import com.asaas.domain.paymentcampaign.PaymentCampaign
import com.asaas.domain.paymentcampaign.PaymentCampaignItem
import com.asaas.domain.subscription.Subscription
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentBuilder
import com.asaas.payment.PaymentStatus
import com.asaas.paymentcampaign.PaymentCampaignItemStatus
import com.asaas.product.Cycle
import com.asaas.redis.RedissonProxy
import com.asaas.userFormInteraction.parser.UserFormInteractionInfoVOParser
import com.asaas.userFormInteraction.vo.UserFormInteractionInfoVO
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.converters.JSON
import grails.gorm.PagedResultList
import grails.transaction.Transactional
import grails.validation.ValidationException
import org.redisson.api.RSemaphore

import java.util.concurrent.TimeUnit

@Transactional
class PaymentCampaignService {

    public static final String PAYMENT_CAMPAIGN_AVAILABLE_QUANTITY_SEMAPHORE_PREFIX = "semaphore:PCAQ"

	def customerAccountService
	def installmentService
    def paymentCampaignFileService
    def paymentCampaignStockControlService
    def paymentCheckoutCallbackConfigService
    def paymentCheckoutFormInteractionService
	def paymentInterestConfigService
    def paymentService
    def subscriptionService
    def userFormInteractionService

	public PagedResultList list(Customer customer, Integer max, Integer offset, Map search) {
		return PaymentCampaign.query([customer: customer, order: search?.order ?: "desc"] + (search ?: [:])).list(max: max, offset: offset)
	}

	public PaymentCampaign save(Customer customer, Map parsedParams) {
        if (isPaymentCampaignDisabled(customer)) throw new BusinessException("A criação de link de pagamento está desabilitada.")

        paymentService.validateCanCreatePaymentThroughUserInterface(customer)

		PaymentCampaign paymentCampaign = validateSave(customer, parsedParams)

		if (paymentCampaign.hasErrors()) {
			return paymentCampaign
		}

		paymentCampaign.customer = customer
		paymentCampaign.name = parsedParams.name
		paymentCampaign.billingType = parsedParams.billingType
		paymentCampaign.publicId = PaymentCampaign.buildPublicId()
		paymentCampaign.value = parsedParams.value

		paymentCampaign.chargeType = parsedParams.chargeType
		paymentCampaign.maxInstallmentCount = (paymentCampaign.chargeType.isInstallment()) ? parsedParams.maxInstallmentCount : 1
		paymentCampaign.description = parsedParams.description
		paymentCampaign.dueDateLimitDays = !paymentCampaign.billingType.isCreditCard() ? parsedParams.dueDateLimitDays : 0
		paymentCampaign.emailMandatory = parsedParams.emailMandatory
		paymentCampaign.addressMandatory = parsedParams.addressMandatory
        paymentCampaign.endDate = CustomDateUtils.toDate(parsedParams.endDate)
        paymentCampaign.availableQuantity = parsedParams.availableQuantity
        paymentCampaign.initialQuantity = parsedParams.availableQuantity
        paymentCampaign.soldQuantity = 0
        paymentCampaign.reservedQuantity = 0
        paymentCampaign.notificationEnabled = parsedParams.notificationEnabled

        if (paymentCampaign.chargeType.isRecurrent()) {
            paymentCampaign.subscriptionCycle = Cycle.convert(parsedParams.subscriptionCycle) ?: Cycle.MONTHLY
        } else {
            paymentCampaign.subscriptionCycle = null
        }

		paymentCampaign.save(failOnError: true)

		if (parsedParams.temporaryFileList) paymentCampaignFileService.saveList(paymentCampaign, parsedParams.temporaryFileList)

        if (parsedParams.paymentCheckoutCallbackConfig) {
            PaymentCheckoutCallbackConfig paymentCheckoutCallbackConfig = paymentCheckoutCallbackConfigService.save(paymentCampaign, parsedParams.paymentCheckoutCallbackConfig)

            if (paymentCheckoutCallbackConfig.hasErrors()) {
                DomainUtils.copyAllErrorsFromObject(paymentCheckoutCallbackConfig, paymentCampaign)

                transactionStatus.setRollbackOnly()
                return paymentCampaign
            }
        }

		return paymentCampaign
	}

	public PaymentCampaign update(PaymentCampaign paymentCampaign, Map params) {
		if (!paymentCampaign) return

        validateUpdate(paymentCampaign, params)

		if (paymentCampaign.hasErrors()) return paymentCampaign

		if (params.containsKey("value")) paymentCampaign.value = params.value
		if (params.containsKey("name")) paymentCampaign.name = params.name
		if (params.containsKey("billingType")) paymentCampaign.billingType = params.billingType
		if (params.containsKey("dueDateLimitDays")) paymentCampaign.dueDateLimitDays = !paymentCampaign.billingType.isCreditCard() ? params.dueDateLimitDays : 0
		if (params.containsKey("chargeType")) paymentCampaign.chargeType = params.chargeType
		if (params.containsKey("maxInstallmentCount")) paymentCampaign.maxInstallmentCount = (paymentCampaign.chargeType.isInstallment()) ? params.maxInstallmentCount : 1
		if (params.containsKey("description")) paymentCampaign.description = params.description
		if (params.containsKey("emailMandatory")) paymentCampaign.emailMandatory = params.emailMandatory
		if (params.containsKey("addressMandatory")) paymentCampaign.addressMandatory = params.addressMandatory
        if (params.containsKey("endDate")) paymentCampaign.endDate = CustomDateUtils.toDate(params.endDate)
        if (params.containsKey("availableQuantity")) paymentCampaign.availableQuantity = Utils.toInteger(params.availableQuantity)
        if (params.containsKey("active")) paymentCampaign.active = Utils.toBoolean(params.active)
        if (params.containsKey("notificationEnabled")) paymentCampaign.notificationEnabled = Utils.toBoolean(params.notificationEnabled)

        if (paymentCampaign.chargeType.isRecurrent()) {
            paymentCampaign.subscriptionCycle = Cycle.convert(params.subscriptionCycle) ?: Cycle.MONTHLY
        } else {
            paymentCampaign.subscriptionCycle = null
        }

		paymentCampaign.save(failOnError: true)

        if (params.paymentCheckoutCallbackConfig) {
            PaymentCheckoutCallbackConfig paymentCheckoutCallbackConfig = paymentCheckoutCallbackConfigService.save(paymentCampaign, params.paymentCheckoutCallbackConfig)

            if (paymentCheckoutCallbackConfig.hasErrors()) {
                DomainUtils.copyAllErrorsFromObject(paymentCheckoutCallbackConfig, paymentCampaign)

                transactionStatus.setRollbackOnly()
                return paymentCampaign
            }
        }

		return paymentCampaign
	}

    public List<Map> generateInstallmentOptionList(Customer customer, PaymentCampaign paymentCampaign) {
        List<Map> installmentList
        Integer maxNumberOfInstallments = null

        if (paymentCampaign.value) {
            installmentList = installmentService.buildInstallmentOptionList(customer, paymentCampaign.value, paymentCampaign.billingType, maxNumberOfInstallments)
        }

        Integer maxInstallmentCount

        if (installmentList) {
            maxInstallmentCount = installmentList.size()
        } else if (!paymentCampaign.billingType.isBoleto()) {
            maxInstallmentCount = Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD
        } else {
            maxInstallmentCount = Installment.getMaxInstallmentCount(customer)
        }

        List<Map> installmentOptionList = []

        for (Integer i = 1; i <= maxInstallmentCount; i++) {
            Map installmentOption = [:]

            if (paymentCampaign.value) {
                String installmentValue = FormUtils.formatCurrencyWithMonetarySymbol(installmentList[i-1].value)
                if (i == 1) {
                    installmentOption.installmentCount = "À vista (${installmentValue})"
                } else {
                    installmentOption.installmentCount = "$i parcelas de $installmentValue"
                }
            } else {
                if (i == 1) {
                    installmentOption.installmentCount = "À vista"
                } else {
                    installmentOption.installmentCount = "Em até ${i}x"
                }
            }

            installmentOption.id = i
            installmentOptionList.add(installmentOption)
        }

        return installmentOptionList
    }

    public RSemaphore buildPaymentCampaignSemaphore(PaymentCampaign paymentCampaign) {
        if (paymentCampaign.availableQuantity == null) return null

        String semaphoreName = "${PaymentCampaignService.PAYMENT_CAMPAIGN_AVAILABLE_QUANTITY_SEMAPHORE_PREFIX}:${paymentCampaign.id}"
        RSemaphore paymentCampaignSemaphore = RedissonProxy.instance.getClient().getSemaphore(semaphoreName)
        if (paymentCampaignSemaphore.isExists()) return paymentCampaignSemaphore

        Integer calculatedAvailableQuantity = calculateAndUpdateAvailableQuantityIfNecessary(paymentCampaign)
        paymentCampaignSemaphore.trySetPermits(calculatedAvailableQuantity)
        paymentCampaignSemaphore.expire(1, TimeUnit.HOURS)
        return paymentCampaignSemaphore
    }

	public PaymentCampaign delete(PaymentCampaign paymentCampaign) {
		if (paymentCampaign.deleted) return paymentCampaign

        BusinessValidation canDeleteValidation = paymentCampaign.canDelete()
		if (!canDeleteValidation.isValid()) throw new BusinessException(canDeleteValidation.getFirstErrorMessage())

        paymentCampaign.active = false
		paymentCampaign.deleted = true
		paymentCampaign.save(failOnError: true)

		return paymentCampaign
	}

	public PaymentCampaign restore(PaymentCampaign paymentCampaign) {
        if (isPaymentCampaignDisabled(paymentCampaign.customer)) throw new BusinessException("A criação de link de pagamento está desabilitada.")

		if (!paymentCampaign.deleted) return paymentCampaign

		paymentCampaign.deleted = false
		paymentCampaign.save(failOnError: true)

		return paymentCampaign
	}

	public PaymentCampaign toggleActive(PaymentCampaign paymentCampaign) {
		if (!paymentCampaign) return
		if (paymentCampaign.deleted) DomainUtils.addError(paymentCampaign, "Não é possível editar um link de pagamento removido.")

		if (paymentCampaign.active == true) {
			paymentCampaign.active = false
		} else {
			paymentCampaign.active = true
            paymentCampaign.endDate = null
		}

		paymentCampaign.save(failOnError: true)
		return paymentCampaign
	}

	public PaymentCampaignItem processAndSaveItem(PaymentCampaign paymentCampaign, Map params, Map customerAccountData) {
        Boolean wasConsumedStock = false
        try {
            PaymentCampaignItem paymentCampaignItem = new PaymentCampaignItem()
            Long paymentCampaignStockControlId = null

            if (paymentCampaign.availableQuantity != null) {
                wasConsumedStock = consumeStock(paymentCampaign)
                if (!wasConsumedStock) return DomainUtils.addError(paymentCampaignItem, "Estoque esgotado. Não é possível consumir mais unidades.")

                paymentCampaignStockControlId = paymentCampaignStockControlService.saveWithNewTransaction(paymentCampaign.id)
            }

            List<UserFormInteractionInfoVO> userFormInteractionInfoVOList
            if (params.interactions) {
                List<Map> interactionsMapList = JSON.parse(params.interactions) as List<Map>
                if (!interactionsMapList) AsaasLogger.warn("PaymentCampaignService.processAndSaveItem >>> InteractionsMapList vazio. Lista sem conversão: [${params.interactions}]")
                userFormInteractionInfoVOList = getPaymentCampaignInteractionsList(paymentCampaign.customer, interactionsMapList)
            }

            Map paymentData = parsePaymentData(paymentCampaign, params)

            paymentCampaignItem = validateItem(paymentCampaign, paymentData, customerAccountData)
            if (paymentCampaignItem.hasErrors()) throw new ValidationException(null, paymentCampaignItem.errors)

            CustomerAccount customerAccount = customerAccountService.save(paymentCampaign.customer, null, customerAccountData)
            if (customerAccount.hasErrors()) throw new ValidationException(null, customerAccount.errors)

            customerAccountService.createDefaultNotifications(customerAccount)

            if (!paymentCampaign.notificationEnabled) {
                customerAccountService.disableNotifications(customerAccount)
            }

            paymentData.customerAccount = customerAccount

            if (paymentData.billingType.isBoleto()) {
                paymentData.boletoBank = PaymentBuilder.findOnlineRegistrationBoletoBank(paymentCampaign.customer, customerAccount, null)
            }

            updateAvailableQuantityIfNecessary(paymentCampaign, true)

            if (paymentCampaign.chargeType == ChargeType.RECURRENT) {
                Subscription subscription = subscriptionService.save(customerAccount, paymentData, false)
                paymentCheckoutFormInteractionService.saveUserFormInteractionIfNecessary(subscription.billingType, subscription.class.simpleName, subscription.id, userFormInteractionInfoVOList)

                if (subscription.hasErrors()) throw new ValidationException(null, subscription.errors)

                return saveItem(paymentCampaign, customerAccount, [subscription: subscription, paymentCampaignStockControlId: paymentCampaignStockControlId])
            }

            if (paymentCampaign.chargeType == ChargeType.INSTALLMENT) {
                Installment installment = installmentService.saveAndProcessCreditCardIfNecessary(paymentData)
                paymentCheckoutFormInteractionService.saveUserFormInteractionIfNecessary(installment.billingType, installment.class.simpleName, installment.id, userFormInteractionInfoVOList)

                return saveItem(paymentCampaign, customerAccount, [installment: installment, paymentCampaignStockControlId: paymentCampaignStockControlId])
            }

            Payment payment
            if (CreditCardUtils.hasAtLeastOneCreditCardInfo(paymentData.creditCard)) {
                payment = paymentService.save(paymentData, false)
                paymentCheckoutFormInteractionService.saveUserFormInteractionIfNecessary(payment.billingType, payment.class.simpleName, payment.id, userFormInteractionInfoVOList)

                paymentService.processCreditCardPayment(payment, paymentData)
                if (payment.hasErrors()) throw new ValidationException(null, payment.errors)

                return saveItem(paymentCampaign, customerAccount, [payment: payment, paymentCampaignStockControlId: paymentCampaignStockControlId])
            }

            payment = paymentService.save(paymentData, false)
            paymentCheckoutFormInteractionService.saveUserFormInteractionIfNecessary(payment.billingType, payment.class.simpleName, payment.id, userFormInteractionInfoVOList)

            return saveItem(paymentCampaign, customerAccount, [payment: payment, paymentCampaignStockControlId: paymentCampaignStockControlId])
        } catch (ValidationException validationException) {
            if (wasConsumedStock && paymentCampaign.availableQuantity != null) incrementStock(paymentCampaign)
            throw validationException
        } catch (RuntimeException runtimeException) {
            if (!Utils.isLock(runtimeException)) AsaasLogger.error("PaymentCampaignService.processAndSaveItem >>> Erro ao salvar item de link de pagamento [paymentCampaignId: ${paymentCampaign.id}]", runtimeException)

            if (wasConsumedStock && paymentCampaign.availableQuantity != null) incrementStock(paymentCampaign)
            throw runtimeException
        }
	}

	public void addCampaignViewedCount(String paymentCampaignPublicId) {
		if (!paymentCampaignPublicId) return

		Utils.withNewTransactionAndRollbackOnError {
			PaymentCampaign paymentCampaign = PaymentCampaign.findByPublicId(paymentCampaignPublicId)
			paymentCampaign.viewCount += 1
			paymentCampaign.save(failOnError: true)
		}
	}

    public void deactivateWithExceedEndDate() {
        Date yesterday = CustomDateUtils.getYesterday()
        List<Long> paymentCampaignIdList = PaymentCampaign.query([column: "id", active: true, "endDate[le]": yesterday]).list()

        for (Long paymentCampaignId in paymentCampaignIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PaymentCampaign paymentCampaign = PaymentCampaign.get(paymentCampaignId)
                toggleActive(paymentCampaign)
            }, [logErrorMessage: "Error ao desabilitar link de pagamento vencido [${paymentCampaignId}]"])
        }
    }

    public void processPaymentReceived(Payment payment) {
        PaymentCampaignItem paymentCampaignItem = PaymentCampaignItem.getPaymentCampaignItemFromPayment(payment)

        if (!paymentCampaignItem) return

        updateReceivedValue(paymentCampaignItem, payment.value)

        updateStatusToSold(paymentCampaignItem)
    }

    public void processPaymentCreditCardReceived(Payment payment) {
        PaymentCampaignItem paymentCampaignItem = PaymentCampaignItem.getPaymentCampaignItemFromPayment(payment)

        if (!paymentCampaignItem) return

        Map itemParams = [:]
        itemParams.payment = paymentCampaignItem.payment
        itemParams.installment = paymentCampaignItem.installment
        itemParams.subscription = paymentCampaignItem.subscription

        updateReceivedValue(paymentCampaignItem, getReceivedValue(itemParams))

        updateStatusToSold(paymentCampaignItem)
    }

    public void processOverduePayment(Payment payment) {
        PaymentCampaignItem paymentCampaignItem = PaymentCampaignItem.getPaymentCampaignItemFromPayment(payment)

        if (!paymentCampaignItem) return

        if (!paymentCampaignItem.status.isReserved()) return

        PaymentCampaign paymentCampaign = paymentCampaignItem.paymentCampaign

        if (paymentCampaign.availableQuantity == null) {
            updateStatusToCancelled(paymentCampaignItem)
            return
        }

        Boolean hasIncrementedStock = false

        try {
            hasIncrementedStock = incrementStock(paymentCampaign)
            Long paymentCampaignStockControlId = paymentCampaignStockControlService.saveWithNewTransaction(paymentCampaign.id)

            updateStatusToCancelled(paymentCampaignItem)

            updateAvailableQuantityIfNecessary(paymentCampaign, false)
            paymentCampaignStockControlService.delete(paymentCampaignStockControlId)
        } catch (RuntimeException runtimeException) {
            if (!Utils.isLock(runtimeException)) AsaasLogger.error("PaymentCampaignService.processOverduePayment >>> Erro ao processar pagamento vencido do link de pagamento [paymentCampaignId: ${paymentCampaign.id}]", runtimeException)

            if (hasIncrementedStock) consumeStock(paymentCampaign)
            throw runtimeException
        }
    }

    public isPaymentCampaignDisabled(Customer customer) {
        return CustomerParameter.getValue(customer, CustomerParameterName.PAYMENT_CAMPAIGN_CREATE_DISABLED)
    }

    private void updatePaymentCampaignItemStatusWithStock(PaymentCampaignItem paymentCampaignItem) {
        PaymentCampaign paymentCampaign = paymentCampaignItem.paymentCampaign
        if (paymentCampaign.availableQuantity == null) return

        Boolean hasConfirmedOrReceivedPayment = paymentCampaignItem.hasConfirmedOrReceivedPayment()
        if (!hasConfirmedOrReceivedPayment) {
            paymentCampaignItem.status = PaymentCampaignItemStatus.RESERVED
            paymentCampaignItem.save(failOnError: true)
        }
    }

    private void updateStatusToSold(PaymentCampaignItem paymentCampaignItem) {
        if (!paymentCampaignItem) return

        if (!paymentCampaignItem.status.isReserved()) return

        paymentCampaignItem.status = PaymentCampaignItemStatus.SOLD
        paymentCampaignItem.save(failOnError: true)
    }

    private void updateStatusToCancelled(PaymentCampaignItem paymentCampaignItem) {
        if (!paymentCampaignItem) return

        if (!paymentCampaignItem.status.isReserved()) return

        paymentCampaignItem.status = PaymentCampaignItemStatus.CANCELLED
        paymentCampaignItem.save(failOnError: true)
    }

    private void updateAvailableQuantityIfNecessary(PaymentCampaign paymentCampaign, Boolean isDecrement) {
        if (paymentCampaign.availableQuantity == null) return

        Integer quantity = isDecrement ? -1 : 1

        Integer rowsUpdated = PaymentCampaign.executeUpdate("update PaymentCampaign set availableQuantity = availableQuantity + :quantity, lastUpdated = :now where id = :id and availableQuantity + :quantity >= 0", [quantity: quantity, now: new Date(), id: paymentCampaign.id])
        if (rowsUpdated == 0) throw new BusinessException("Estoque esgotado. Não é possível consumir mais unidades.")
    }

    private void updateReceivedValue(PaymentCampaignItem paymentCampaignItem, BigDecimal value) {
        if (!paymentCampaignItem) return

        paymentCampaignItem.receivedValue += value
        paymentCampaignItem.save(failOnError: true)
    }

    private PaymentCampaign validateSave(Customer customer, Map params) {
        PaymentCampaign paymentCampaign = new PaymentCampaign()
        BillingType billingType = BillingType.convert(params.billingType)
        ChargeType chargeType = ChargeType.convert(params.chargeType)

        if (!params.name) {
            DomainUtils.addError(paymentCampaign, "O nome do link de pagamento deve ser informado")
        }

        validateNameLength(params, paymentCampaign)

        if (!billingType) {
            DomainUtils.addError(paymentCampaign, "A forma de pagamento deve ser informada")
        }

        validateValue(paymentCampaign, customer, params)

        if (!chargeType) {
            DomainUtils.addError(paymentCampaign, "O tipo de cobrança deve ser informado.")
        }

        if (!billingType?.isCreditCard() && params.dueDateLimitDays?.toInteger() < 0) {
            DomainUtils.addError(paymentCampaign, "É necessário informar em quantos dias o boleto vencerá.")
        }

        if (chargeType == ChargeType.INSTALLMENT) validateInstallmentCount(paymentCampaign, customer, billingType, params.maxInstallmentCount?.toInteger())

        if (params.temporaryFileList?.size() > PaymentCampaign.MAX_FILE_QUANTITY) {
            DomainUtils.addError(paymentCampaign, Utils.getMessageProperty('paymentCampaign.limite.items', [PaymentCampaign.MAX_FILE_QUANTITY]))
        }

        Map interest = paymentInterestConfigService.buildCustomerConfig(customer)
        if (interest) {
            BusinessValidation businessValidation = paymentInterestConfigService.validateCustomerConfig(interest)
            if (!businessValidation.isValid()) DomainUtils.addError(paymentCampaign, businessValidation.getFirstErrorMessage())
        }

        if (params.endDate && CustomDateUtils.toDate(params.endDate) < new Date().clearTime()) {
            DomainUtils.addFieldError(paymentCampaign, "endDate", "before.today")
        }

        return paymentCampaign
    }

    private PaymentCampaign validateUpdate(PaymentCampaign paymentCampaign, Map params) {
        BusinessValidation canEditValidation = paymentCampaign.canEdit()

        if (!canEditValidation.isValid()) {
            DomainUtils.addError(paymentCampaign, canEditValidation.getFirstErrorMessage())
            return paymentCampaign
        }

        if (Utils.isEmptyOrNull(params.name?.trim())) {
            DomainUtils.addError(paymentCampaign, "O nome do link de pagamento deve ser informado")
        }

        validateNameLength(params, paymentCampaign)

        validateValue(paymentCampaign, paymentCampaign.customer, params)

        if (params.chargeType == ChargeType.INSTALLMENT) validateInstallmentCount(paymentCampaign, paymentCampaign.customer, params.billingType, params.maxInstallmentCount?.toInteger())

        if (params.endDate && CustomDateUtils.toDate(params.endDate) < new Date().clearTime()) {
            DomainUtils.addFieldError(paymentCampaign, "endDate", "before.today")
        }

        if (params.containsKey("billingType")) {
            BillingType billingType = BillingType.convert(params.billingType)
            if (!billingType.isCreditCard() && params.dueDateLimitDays?.toInteger() < 0) {
                DomainUtils.addError(paymentCampaign, "É necessário informar em quantos dias o boleto vencerá.")
            }
        }

        return paymentCampaign
    }

    private void validateNameLength(Map params, PaymentCampaign paymentCampaign) {
        if (params.name?.length() > PaymentCampaign.constraints.name.maxSize) {
            DomainUtils.addError(paymentCampaign, "O nome do link de pagamento não deve ultrapassar ${PaymentCampaign.constraints.name.maxSize} caracteres")
        }
    }

    private void validateValue(PaymentCampaign paymentCampaign, Customer customer, Map params) {
        if (!params.value) return

        if (BillingType.convert(params.billingType) == BillingType.MUNDIPAGG_CIELO) {
            BigDecimal minCreditCardValue = Installment.MIN_CREDIT_CARD_INSTALLMENT_PAYMENT_VALUE
            if (params.value < minCreditCardValue) {
                DomainUtils.addError(paymentCampaign, Utils.getMessageProperty("minimum.creditcard.com.asaas.domain.payment.Payment.value", [minCreditCardValue]))
            }
        } else if (params.value < Payment.getMinimumBankSlipAndPixValue(customer)) {
            DomainUtils.addError(paymentCampaign, "O valor mínimo para cobranças é ${FormUtils.formatCurrencyWithMonetarySymbol(Payment.getMinimumBankSlipAndPixValue(customer))}")
        }

        BigDecimal customerMaxPaymentValue
        if (customer) {
            customerMaxPaymentValue = customer.calculateMaxPaymentValue()
        } else {
            customerMaxPaymentValue = Customer.defaultMaxPaymentValue
        }

        if (params.value > customerMaxPaymentValue) DomainUtils.addError(paymentCampaign, Utils.getMessageProperty("maximum.limit.com.asaas.domain.payment.Payment.value"))
    }

    private void validateInstallmentCount(PaymentCampaign paymentCampaign, Customer customer, BillingType billingType, Integer maxInstallmentCount) {
        Integer maxCustomerInstallmentCount = Installment.getMaxInstallmentCount(customer)

        if (!maxInstallmentCount || maxInstallmentCount <= 0) {
            DomainUtils.addError(paymentCampaign, "Informe o número máximo de parcelas.")
        } else if (billingType && [BillingType.MUNDIPAGG_CIELO, BillingType.UNDEFINED].contains(billingType) && maxInstallmentCount.toInteger() > Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD) {
            DomainUtils.addError(paymentCampaign, Utils.getMessageProperty("maximum.mundipaggCielo.com.asaas.domain.installment.Installment.billingType", [Installment.MAX_INSTALLMENT_COUNT_FOR_CREDIT_CARD]))
        } else if (maxInstallmentCount > maxCustomerInstallmentCount) {
            DomainUtils.addError(paymentCampaign, "O número máximo é de ${maxCustomerInstallmentCount} parcelas.")
        }
    }

    private List<UserFormInteractionInfoVO> getPaymentCampaignInteractionsList(Customer customer, List<Map> interactionsMap) {
        List<UserFormInteractionInfoVO> userFormInteractionInfoVOList
        if (interactionsMap) {
            userFormInteractionInfoVOList = UserFormInteractionInfoVOParser.listFromMap(customer.id, interactionsMap)
            if (!userFormInteractionService.validateOrderOfTypes(userFormInteractionInfoVOList)) {
                AsaasLogger.warn("PaymentCampaignService.processAndSaveItem >>> Erro na validação dos dados de interação de usuários, ordems dos eventos inconsistente.")
            }
        }

        return userFormInteractionInfoVOList
    }

    private PaymentCampaignItem saveItem(PaymentCampaign paymentCampaign, CustomerAccount customerAccount, Map params) {
        PaymentCampaignItem paymentCampaignItem = new PaymentCampaignItem()
        paymentCampaignItem.paymentCampaign = paymentCampaign
        paymentCampaignItem.customerAccount = customerAccount
        paymentCampaignItem.generatedValue = getGeneratedValue(params)
        paymentCampaignItem.receivedValue = getReceivedValue(params)
        paymentCampaignItem.quantity = 1
        paymentCampaignItem.status = PaymentCampaignItemStatus.SOLD
        paymentCampaignItem.payment = params.payment
        paymentCampaignItem.installment = params.installment
        paymentCampaignItem.subscription = params.subscription
        paymentCampaignItem.save(failOnError: true)

        updatePaymentCampaignItemStatusWithStock(paymentCampaignItem)

        if (params.paymentCampaignStockControlId) {
            paymentCampaignStockControlService.delete(params.paymentCampaignStockControlId)
        }

        return paymentCampaignItem
    }

    private PaymentCampaignItem validateItem(PaymentCampaign paymentCampaign, Map params, Map customerAccountData) {
        PaymentCampaignItem paymentCampaignItem = new PaymentCampaignItem()
        if (paymentCampaign.billingType != BillingType.UNDEFINED && paymentCampaign.billingType != params.billingType) {
            return DomainUtils.addError(paymentCampaignItem, "A forma de pagamento informada deve ser igual a forma de pagamento do link de pagamento")
        }

        if (paymentCampaign.addressMandatory) {
            Boolean hasAddressInfo = (customerAccountData.address && customerAccountData.addressNumber && customerAccountData.province && customerAccountData.city && customerAccountData.postalCode)
            if (!hasAddressInfo) return DomainUtils.addError(paymentCampaignItem, "Não foi possível salvar. O preenchimento dos dados de endereço é obrigatório.")
        }

        return paymentCampaignItem
    }

    private Map parsePaymentData(PaymentCampaign paymentCampaign, Map params) {
        Map paymentData = [:]
        Date dueDate = CustomDateUtils.todayPlusBusinessDays(paymentCampaign.dueDateLimitDays).getTime()

        paymentData.chargeType = paymentCampaign.chargeType

        BillingType billingType = BillingType.convert(params.paymentData?.billingType).toRequestAPI()
        paymentData.billingType = billingType

        if (paymentCampaign.chargeType.isRecurrent()) {
            paymentData.value = paymentCampaign.value ?: Utils.bigDecimalFromString(params.paymentData.value)
            paymentData.subscriptionCycle = paymentCampaign.subscriptionCycle.toString()
            paymentData.nextDueDate = paymentData.billingType == BillingType.BOLETO ? dueDate : new Date()
        } else if (paymentCampaign.chargeType.isInstallment()) {
            paymentData.totalValue = paymentCampaign.value ?: Utils.bigDecimalFromString(params.paymentData.value)
        } else {
            paymentData.value = paymentCampaign.value ?: Utils.bigDecimalFromString(params.paymentData.value)
        }

        paymentData.description = Utils.truncateString(paymentCampaign.description, 500)
        paymentData.dueDateLimitDays = paymentCampaign.dueDateLimitDays
        paymentData.dueDate = dueDate
        paymentData.providerId = paymentCampaign.customer.id

        if (billingType.isCreditCard()) {
            paymentData.creditCard = params.creditCard
            paymentData.creditCard.number = Utils.removeNonNumeric(params.creditCard.number)
            paymentData.creditCardHolderInfo = params.creditCardHolderInfo
            paymentData.creditCardTransactionOriginInfo = params.creditCardTransactionOriginInfo
        }

        Integer installmentCount
        if (params.paymentData?.installmentCount) {
            installmentCount = Utils.toInteger(params.paymentData.installmentCount)
        }

        paymentData.installmentCount = installmentCount ?: 1
        paymentData.notifyPaymentCreatedImmediately = Utils.toBoolean(params?.notifyPaymentCreatedImmediately)

        return paymentData
    }

    private BigDecimal getReceivedValue(Map params) {
        if (params.payment) {
            Payment payment = params.payment
            if (payment.isCreditCard() && payment.isConfirmed()) return payment.value

            return 0
        }

        if (params.installment) {
            Installment installment = params.installment
            if (!installment.isCreditCard()) return 0

            Boolean hasConfirmedPayment = Payment.query([exists: true, installmentId: installment.id, status: PaymentStatus.CONFIRMED]).get().asBoolean()
            if (hasConfirmedPayment) return installment.value

            return 0
        }

        if (params.subscription) {
            Subscription subscription = params.subscription
            if (!subscription.isCreditCard()) return 0

            Boolean hasConfirmedPayment = Payment.query([exists: true, subscriptionId: subscription.id, status: PaymentStatus.CONFIRMED]).get().asBoolean()
            if (hasConfirmedPayment) return subscription.value

            return 0
        }

        return 0
    }

    private BigDecimal getGeneratedValue(Map params) {
        if (params.payment) return params.payment.value

        if (params.installment) return params.installment.value

        if (params.subscription) return params.subscription.value

        return 0
    }

    private Boolean consumeStock(PaymentCampaign paymentCampaign) {
        final Integer quantity = 1

        RSemaphore paymentCampaignSemaphore = buildPaymentCampaignSemaphore(paymentCampaign)
        return paymentCampaignSemaphore.tryAcquire(quantity)
    }

    private Boolean incrementStock(PaymentCampaign paymentCampaign) {
        final Integer quantity = 1

        RSemaphore paymentCampaignSemaphore = buildPaymentCampaignSemaphore(paymentCampaign)
        paymentCampaignSemaphore.addPermits(quantity)

        return true
    }

    private Integer calculateAndUpdateAvailableQuantityIfNecessary(PaymentCampaign paymentCampaign) {
        paymentCampaign.lock()

        Integer soldOrReservedCount = paymentCampaign.countSoldOrReservedItems()
        Integer calculatedAvailableQuantity = paymentCampaign.initialQuantity - soldOrReservedCount

        if (paymentCampaign.availableQuantity != calculatedAvailableQuantity) {
            paymentCampaign.availableQuantity = calculatedAvailableQuantity
            paymentCampaign.save(failOnError: true)
        }

        return calculatedAvailableQuantity
    }
}

package com.asaas.service.api

import com.asaas.api.ApiCreditCardParser
import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.creditcard.CreditCard
import com.asaas.creditcard.CreditCardAcquirerRefuseReason
import com.asaas.creditcard.CreditCardTransactionOriginInterface
import com.asaas.creditcard.CreditCardValidator
import com.asaas.creditcard.HolderInfo
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerFeature
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.exception.CustomerAccountNotFoundException
import com.asaas.log.AsaasLogger
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class ApiCreditCardService extends ApiBaseService {

    def creditCardService
	def apiResponseBuilderService
	def boletoService
	def installmentService

    def tokenizeCreditCard(params) {
        CustomerAccount customerAccount
        try {
            Customer customer = getProviderInstance(params)

            if (!CustomerFeature.canHandleBillingInfoEnabled(customer.id)) {
                return apiResponseBuilderService.buildForbidden()
            }

            customerAccount = CustomerAccount.find(getCustomer(params), customer.id)
        } catch(CustomerAccountNotFoundException e) {
            return apiResponseBuilderService.buildError("invalid", "customer", "", ["Cliente"])
        }

        CreditCard creditCard = CreditCard.build(ApiCreditCardParser.parseRequestCreditCardParams(params))

        CreditCardValidator creditCardValidator = new CreditCardValidator()
        if (!creditCardValidator.validate(creditCard)) {
            return apiResponseBuilderService.buildErrorList("invalid_creditCard", creditCardValidator.errors)
        }

        HolderInfo holderInfo
        if (params.creditCardHolderInfo) {
            holderInfo = HolderInfo.build(ApiCreditCardParser.parseRequestCreditCardHolderInfoParams(params))
            if (!creditCardValidator.validateHolderInfo(holderInfo)) return apiResponseBuilderService.buildErrorList("invalid_holderInfo", creditCardValidator.errors)
        }

        Map tokenizeResponse = creditCardService.tokenizeCreditCard(customerAccount, creditCard, buildCreditCardTransactionOriginInfo(params.remoteIp), holderInfo)

        if (!tokenizeResponse.success) {
            if (tokenizeResponse.customerErrorMessage) {
                return apiResponseBuilderService.buildErrorFrom("invalid_creditCard", tokenizeResponse.customerErrorMessage)
            } else if (CustomerParameter.getValue(customerAccount.provider, CustomerParameterName.ALLOW_CREDIT_CARD_ACQUIRER_REFUSE_REASON)) {
                if (tokenizeResponse.wasBlockedByPreventAbuse) return apiResponseBuilderService.buildErrorFrom("invalid_creditCard", "Transação de tokenização não autorizada. Aguarde para tentar novamente.")
                if (tokenizeResponse.acquirerReturnCode)  return apiResponseBuilderService.buildErrorFrom("invalid_creditCard", CreditCardAcquirerRefuseReason.getCreditCardAcquirerRefuseReason(tokenizeResponse.acquirerReturnCode, tokenizeResponse.errorMessage))
            }

            return apiResponseBuilderService.buildErrorFrom("invalid_creditCard", "Informações de cartão de crédito são inválidas")
        }

        return apiResponseBuilderService.buildSuccess(ApiCreditCardParser.buildResponseItem(tokenizeResponse.billingInfo))
    }

    def validatePayment(params) {
		try {
			Map fields = parseRequestParams(params, params.namespace)

			Customer customer = Customer.get(params.provider)

			List<Map> errors = []

			BigDecimal value = Utils.bigDecimalFromString(fields.value)

			if (!value) {
				errors.add([code: "payment_invalidValue", description: "O valor da cobrança é inválido"])
			}

			if (boletoService.boletoDisabledForCustomer(customer)) {
				errors.add(buildBoletoDisabledErrorMap(customer))
			}

			if (isInstallment(fields.installmentCount)) {
				Installment installment = new Installment()
				installmentService.validateInstallmentsInfo(installment, [value: fields.installmentValue, installmentCount: fields.installmentCount, billingType: BillingType.MUNDIPAGG_CIELO, customer: customer])
				if (installment.hasErrors()) {
					errors.addAll(apiResponseBuilderService.buildErrorList(installment).errors)
				}

				value = (Utils.bigDecimalFromString(fields.installmentValue) * Integer.valueOf(fields.installmentCount))
			}

			if (errors.size() > 0)
				return [errors: errors]

			Boolean creditCardRiskValidationNotRequired = Payment.creditCardRiskValidationNotRequired(customer, value)

			return apiResponseBuilderService.buildSuccess([creditCardRiskValidationNotRequired: creditCardRiskValidationNotRequired])
		} catch (Exception exception) {
            AsaasLogger.error("ApiCreditCardService.validatePayment >> Erro ao validar pagamento. [providerId: ${params.provider}]", exception)
			return apiResponseBuilderService.buildErrorFromCode("unknow.error")
		}
	}

	private Map buildBoletoDisabledErrorMap(Customer customer) {
		if (customer.status == CustomerStatus.AWAITING_ACTIVATION)
			return [code: "customer_boletoDisabled", description: Utils.removeHtmlTags(Utils.getMessageProperty("boletoDisabled.messageCustomer.awaitingActivation.creditCard"))]

		return [code: "customer_boletoDisabled", description: Utils.getMessageProperty("api.boletoDisabled.messageCustomer.otherReason.creditCard", [AsaasApplicationHolder.config.asaas.phone])]
	}

	private Map parseRequestParams(params, String namespace) {
		Map fields = [:]

		if (params.value) {
			fields.value = params.value
		}

		if (params.installmentCount) {
			fields.installmentCount = params.installmentCount
		}

		if (params.installmentValue) {
			fields.installmentValue = params.installmentValue
		}

		return fields
	}

    private Map buildCreditCardTransactionOriginInfo(String payerRemoteIp) {
        Map creditCardTransactionOriginInfoMap = [:]
        creditCardTransactionOriginInfoMap.originInterface = CreditCardTransactionOriginInterface.API
        creditCardTransactionOriginInfoMap.remoteIp = RequestUtils.getRemoteIp()
        creditCardTransactionOriginInfoMap.payerRemoteIp = payerRemoteIp

        return creditCardTransactionOriginInfoMap
    }

}

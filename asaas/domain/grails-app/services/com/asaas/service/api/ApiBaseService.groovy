package com.asaas.service.api

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.api.ApiBaseParser
import com.asaas.api.ApiMobileUtils
import com.asaas.domain.customer.Customer
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils

class ApiBaseService {

	def getLimit(params) {
		return ApiBaseParser.getPaginationLimit()
   }

	def getOffset(params) {
		return ApiBaseParser.getPaginationOffset()
	}

	def getCustomer(params) {
		return params.customer ?: null
	}

	def setCustomer(params, customer) {
		params.customer = customer
	}

	def getProvider(params) {
		return ApiBaseParser.getProviderId()
	}

	def getProviderInstance(params) {
		return ApiBaseParser.getProviderInstance()
	}

	def getExpandCustomer(params) {
		return Boolean.valueOf(params.expandCustomer)
	}

	def parseBoolean(value) {
		Utils.toBoolean(value)
	}

	public Boolean isInstallment(installmentCount) {
		return installmentCount && Utils.toInteger(installmentCount) > 0
	}

	def getParams() {
		return ApiBaseParser.getParams()
	}

	public trackPaymentCreated(Map fields) {
		try {
			Map trackMap = [:]

            trackMap.paymentId = fields.payment?.id
			trackMap.mobile = ApiMobileUtils.isMobileAppRequest()
			trackMap.customerAccountId = fields.payment?.customerAccount?.id
			trackMap.value = fields.payment?.value
			trackMap.billingType = fields.payment?.billingType?.toResponseAPI()
			trackMap.status = fields.payment?.status?.toString()

			trackMap.installment = fields.payment?.installment != null
			trackMap.subscription = fields.payment?.subscription != null

			if (trackMap.installment) {
				trackMap.installmentCount = fields.payment.installment.installmentCount
			}

			if (trackMap.subscription) {
				trackMap.subscriptionCycle = fields.payment.subscription.cycle.toString()
			}

			if (fields.providerId) AsaasApplicationHolder.applicationContext.asaasSegmentioService.track(fields.providerId, "Service :: API :: Cobrança gerada", trackMap)
		} catch (Exception exception) {
            AsaasLogger.error("ApiBaseService.trackPaymentCreated >> Erro ao rastrear pagamento criado. [paymentId: ${fields?.payment?.id}]", exception)
		}
	}
}

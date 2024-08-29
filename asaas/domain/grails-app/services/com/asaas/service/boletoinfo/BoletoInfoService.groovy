package com.asaas.service.boletoinfo

import com.asaas.domain.boleto.BoletoInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerPaymentConfig
import com.asaas.domain.customer.CustomerRegisterStatus
import com.asaas.domain.interest.InterestConfig
import com.asaas.interestconfig.FineType
import com.asaas.discountconfig.DiscountType
import com.asaas.interestconfig.InterestPeriod
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class BoletoInfoService {

	def boletoChangeInfoRequestService
	def interestConfigService
	def customerPaymentConfigService

	def findFromCustomerOrReturnDefault(Customer customer) {
		BoletoInfo boletoInfo = BoletoInfo.findByCustomer(customer)
		
		if (!boletoInfo)
			boletoInfo = BoletoInfo.findByCustomer(null) 
			
		return boletoInfo
	}

	public BoletoInfo validateSaveOrUpdateParams(Long customerId) {
		BoletoInfo boletoInfo = new BoletoInfo()

		Customer customer = Customer.find("from Customer where id = :id", [id: customerId])
		
		if (!customer.cpfCnpj) {
			DomainUtils.addError(boletoInfo, "Preencha seu CPF ou CNPJ nas informações comerciais antes de salvar as informações de boleto.")
		}
		
		return boletoInfo
	}
	
	public BoletoInfo save(Customer customer, Map properties) {
		BoletoInfo boletoInfo = BoletoInfo.findByCustomer(customer)
		
		if (!boletoInfo) boletoInfo = new BoletoInfo(customer: customer)

		boletoInfo.transferor = properties.transferor
		boletoInfo.cpfCnpj = properties.cpfCnpj
		boletoInfo.instructions = properties.instructions
		boletoInfo.receiveAfterDueDate = properties.receiveAfterDueDate

		boletoInfo.save(flush: true, failOnError: true)

		return boletoInfo
	}

	public Map update(Customer customer, params) {
		Map responseMap = [:]

		BoletoInfo.withNewTransaction { status ->
			responseMap.success = false
			responseMap.messageCode = "default.formulary.inconsistency"

			try {

				Map interestParams = [enabled: true, 
									  provider: customer, 
									  fine: 0, 
									  fineValue: params.interest.fineValue,
									  fineType: params.interest.fineType ?: FineType.PERCENTAGE, 
									  interestPeriod: InterestPeriod.MONTHLY, 
									  interest: params.interest.interestValue, 
									  discount: params.interest.discountValue ?: 0, 
									  discountType: params.interest.discountType ?: DiscountType.PERCENTAGE]

				InterestConfig interestConfig = interestConfigService.save(interestParams)
				if (interestConfig.hasErrors()) {
					status.setRollbackOnly()
					responseMap.inconsistencyObject = interestConfig
					return responseMap
				}

				if (params.showNotarialProtestMessage) {
					Map attributes = [showNotarialProtestMessage: params.showNotarialProtestMessage, daysToProtest: params.daysToProtest?.trim()]
					CustomerPaymentConfig customerPaymentConfig = customerPaymentConfigService.saveOrUpdate(customer, attributes)
					if (customerPaymentConfig.hasErrors()) {
						status.setRollbackOnly()
						responseMap.inconsistencyObject = customerPaymentConfig
						return responseMap
					}
				}

				responseMap.success = true
				responseMap.messageCode = "information.save.success"

				return responseMap
			} catch (Exception exception) {
				status.setRollbackOnly()

                AsaasLogger.error("BoletoInfoService.update >> Erro ao atualizar informações de boleto. [customerId: ${customer.id}]", exception)
				responseMap.messageCode = "unknow.error"

				return responseMap
			}
		}
	}
}

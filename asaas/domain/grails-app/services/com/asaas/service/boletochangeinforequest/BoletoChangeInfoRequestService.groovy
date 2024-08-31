package com.asaas.service.boletochangeinforequest

import com.asaas.domain.boleto.BoletoChangeInfoRequest
import com.asaas.domain.boleto.BoletoInfo
import com.asaas.domain.customer.Customer
import com.asaas.status.Status
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class BoletoChangeInfoRequestService {

	def messageService

	def springSecurityService

	def customerRegisterStatusService

	def customerInteractionService

	def boletoInfoService

	def treasureDataService

	def save(Long customerId, params) {
		BoletoInfo validatedBoletoInfo = boletoInfoService.validateSaveOrUpdateParams(customerId)
		if (validatedBoletoInfo.hasErrors()) return validatedBoletoInfo

		Customer customer = Customer.findById(customerId)

		if (params.instructions) {
			params.instructions = params.instructions.trim()
		}

		def boletoInfoToBeUpdated = findLatestIfIsPendingOrDenied(customer) ?: boletoInfoService.findFromCustomerOrReturnDefault(customer)
		if (!DomainUtils.attributesHaveChanged(boletoInfoToBeUpdated, params)) return findLatestFromCustomer(customer)

		removePreviousRequests(customer)
		BoletoChangeInfoRequest boletoChangeInfoRequest = new BoletoChangeInfoRequest(customer: customer)
		boletoChangeInfoRequest.transferor = params.transferor
		boletoChangeInfoRequest.cpfCnpj = params.cpfCnpj
		boletoChangeInfoRequest.instructions = params.instructions
		boletoChangeInfoRequest.receiveAfterDueDate = Boolean.valueOf(params.receiveAfterDueDate)

		boletoChangeInfoRequest.save(flush: true, failOnError: false)

		if (boletoChangeInfoRequest.hasErrors()) return boletoChangeInfoRequest

		approveAutomatically(boletoChangeInfoRequest, params)

		return boletoChangeInfoRequest
	}

	private void removePreviousRequests(Customer customer) {
		BoletoChangeInfoRequest.executeUpdate("update BoletoChangeInfoRequest set status = :newStatus, lastUpdated = :lastUpdated where customer = :customer and status = :previousStatus", [newStatus: Status.CANCELLED, lastUpdated: new Date(), customer: customer, previousStatus: Status.PENDING])
	}

	public BoletoChangeInfoRequest findLatestFromCustomer(Customer customer) {
		List<BoletoChangeInfoRequest> requests = BoletoChangeInfoRequest.executeQuery("from BoletoChangeInfoRequest where customer = :customer and deleted = false order by id desc", [customer: customer])

		if (requests.size() == 0)
			return null
		else
			return requests.get(0)
	}

	public BoletoChangeInfoRequest findLatestIfIsPendingOrDenied(Customer customer) {
		return BoletoChangeInfoRequest.executeQuery("""from BoletoChangeInfoRequest bcir
                                                      where bcir.id = (select max(id) from BoletoChangeInfoRequest where customer.id = :customerId)
                                                        and bcir.status in (:denied, :pending)""", [customerId: customer.id, pending: Status.PENDING, denied: Status.DENIED])[0]
	}

	def list(Map params) {
		def list = BoletoChangeInfoRequest.createCriteria().list(max: params.max, offset: params.offset) {
			createAlias('customer','customer')

			if (params.containsKey("providerId")) {
				and { eq("customer.id", Long.parseLong(params.providerId)) }
			}

			if (params?.providerName) {
				or {
					like("customer.name", "%" + params.description + "%")
					like("customer.email", "%" + params.description + "%")
				}
			}

			if (params?.status) {
				and { eq("status", Status.valueOf(params.status)) }
			}

			order(params?.sort ?: "lastUpdated", params?.order ?: "desc")
		}

		return list
	}

	public void approve(Long id, Map params) {
		BoletoChangeInfoRequest boletoChangeInfoRequest = BoletoChangeInfoRequest.get(id)

		boletoChangeInfoRequest.status = Status.APPROVED
		boletoChangeInfoRequest.user = springSecurityService.currentUser
		boletoChangeInfoRequest.observations = params.observations
		boletoChangeInfoRequest.transferor = params.transferor
		boletoChangeInfoRequest.cpfCnpj = params.cpfCnpj
		boletoChangeInfoRequest.instructions = params.instructions
		boletoChangeInfoRequest.save(flush: true, failOnError: true)

		boletoInfoService.save(boletoChangeInfoRequest.customer, boletoChangeInfoRequest.properties)

		customerRegisterStatusService.updateBoletoInfoStatus(boletoChangeInfoRequest.customer, Status.APPROVED)

		customerInteractionService.saveBoletoInfoApproval(boletoChangeInfoRequest.customer.id, boletoChangeInfoRequest.observations)
	}

	public void deny(Long id, String denialReason, String observations) {
		BoletoChangeInfoRequest boletoChangeInfoRequest = BoletoChangeInfoRequest.get(id)
		boletoChangeInfoRequest.status = Status.DENIED
		boletoChangeInfoRequest.denialReason = denialReason
		boletoChangeInfoRequest.observations = observations
		boletoChangeInfoRequest.user = springSecurityService.currentUser
		boletoChangeInfoRequest.save(flush: true, failOnError: true)

		messageService.sendBoletoChangeInfoRequestUpdateEmail(boletoChangeInfoRequest)

		customerRegisterStatusService.updateBoletoInfoStatus(boletoChangeInfoRequest.customer, Status.REJECTED)

		customerInteractionService.saveBoletoInfoDenial(boletoChangeInfoRequest.customer.id, boletoChangeInfoRequest.observations, boletoChangeInfoRequest.denialReason)

		treasureDataService.track(boletoChangeInfoRequest.customer, TreasureDataEventType.BOLETO_INFO_ANALYZED, [boletoChangeInfoRequestId: boletoChangeInfoRequest.id])
	}

	private void approveAutomatically(BoletoChangeInfoRequest boletoChangeInfoRequest, Map params) {
        String observations = Utils.getMessageProperty("system.automaticApproval.description")
		approve(boletoChangeInfoRequest.id, params + [observations: observations])
	}

	public void approveManually(Long boletoChangeInfoRequestId, Map params) {
		approve(boletoChangeInfoRequestId, params)

		BoletoChangeInfoRequest boletoChangeInfoRequest = BoletoChangeInfoRequest.get(boletoChangeInfoRequestId)
		messageService.sendBoletoChangeInfoRequestUpdateEmail(boletoChangeInfoRequest)

		treasureDataService.track(boletoChangeInfoRequest.customer, TreasureDataEventType.BOLETO_INFO_ANALYZED, [boletoChangeInfoRequestId: boletoChangeInfoRequest.id])
	}
}

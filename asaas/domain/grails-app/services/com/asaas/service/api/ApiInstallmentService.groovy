package com.asaas.service.api

import com.asaas.api.ApiCustomerAccountGroupParser
import com.asaas.api.ApiInstallmentParser
import com.asaas.api.ApiMobileUtils
import com.asaas.api.ApiSplitParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccountGroup
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.split.PaymentSplit
import com.asaas.exception.InstallmentNotFoundException
import com.asaas.segment.PaymentOriginTracker

import grails.transaction.Transactional

@Transactional
class ApiInstallmentService extends ApiBaseService {

	def apiPaymentService
	def apiResponseBuilderService
	def installmentService

	def find(params) {
		try {
			Installment installment = Installment.find(params.id, getProvider(params))
			return ApiInstallmentParser.buildResponseItem(installment, [:])
		} catch (InstallmentNotFoundException e) {
			return apiResponseBuilderService.buildNotFoundItem()
		}
	}

    def list(params) {
        Customer customer = getProviderInstance(params)
        Integer limit = getLimit(params)
        Integer offset = getOffset(params)

        Map fields = ApiInstallmentParser.parseRequestParams(customer.id, params)

        List<Installment> installments = installmentService.list(null, customer.id, limit, offset, fields)

        List<Map> responseItems = []
        List<Map> extraData = []

        if (ApiMobileUtils.isMobileAppRequest()) {
            responseItems = installments.collect { ApiInstallmentParser.buildLeanResponseItem(it) }

            List<CustomerAccountGroup> customerAccountGroups = CustomerAccountGroup.query([customerId: customer.id]).list(max: 20, offset: 0, sort: "name", order: "asc", readOnly: true)
            if (customerAccountGroups) {
                List<Map> customerAccountGroupMap = customerAccountGroups.collect { ApiCustomerAccountGroupParser.buildResponseItem(it) }
                extraData << [customerAccountGroups: customerAccountGroupMap]
            }
        } else {
            responseItems = installments.collect { ApiInstallmentParser.buildResponseItem(it, [:]) }
        }

        return apiResponseBuilderService.buildList(responseItems, limit, offset, installments.totalCount, extraData)
    }

	def save(params) {
        Map fields = ApiInstallmentParser.parseRequestParams(getProvider(params), params)
        Installment installment = installmentService.save(fields, true, false)
        if (installment.hasErrors()) {
			return apiResponseBuilderService.buildErrorList(installment)
		}

        PaymentOriginTracker.trackApiCreation(null, installment, null, "API")
		return ApiInstallmentParser.buildResponseItem(installment, [:])
	}

	def update(Map params) {
        Long providerId = getProvider(params)

        Map fields = ApiInstallmentParser.parseRequestParams(providerId, params)
        Installment installment = installmentService.update(params.id, providerId, fields)

        if (installment.hasErrors()) return apiResponseBuilderService.buildErrorList(installment)

        return apiResponseBuilderService.buildSuccess(ApiInstallmentParser.buildResponseItem(installment, [:]))
	}

    public Map updateSplits(Map params) {
        Long providerId = getProvider(params)

        Map fields = ApiInstallmentParser.parsePaymentSplitRequestParams(params)
        Installment installment = installmentService.update(params.id, providerId, fields)

        if (installment.hasErrors()) return apiResponseBuilderService.buildErrorList(installment)

        List<Payment> payments = installment.getNotDeletedPayments()
        List<PaymentSplit> paymentSplitList = PaymentSplit.query(["payment[in]": payments]).list(readOnly: true)

        Map responseItems = [:]
        responseItems.splits = paymentSplitList.collect { ApiSplitParser.buildPaymentResponseItem(it) }

        return apiResponseBuilderService.buildSuccess(responseItems)
    }

	def delete(params) {
        Installment installment = Installment.find(params.id, getProvider(params))

        installmentService.delete(installment.id, getProvider(params))

        return apiResponseBuilderService.buildDeleted(installment.id)
	}

    public Map getPaymentBook(Map params) {
        Installment installment = Installment.find(params.id, getProvider(params))
        Map fields = ApiInstallmentParser.parsePaymentBookParams(params)
        byte[] paymentBookFile = installmentService.buildPaymentBookFromPublicId(installment.publicId, fields.sortByDueDateAsc)
        return apiResponseBuilderService.buildFile(paymentBookFile, "Carnê_${installment.publicId}.pdf")
    }

    def deleteRemainingPayments(params) {
        Installment installment = Installment.find(params.id, getProvider(params))

        installmentService.deletePayments(installment)

        return apiResponseBuilderService.buildDeleted(installment.id)
    }

	def payWithCreditCard(params) {
		try {
            Installment installment = Installment.find(params.id, getProvider(params))
			params.id = installment.getFirstRemainingPayment().publicId
        	return apiPaymentService.payWithCreditCard(params)
    	} catch(InstallmentNotFoundException e) {
    		return apiResponseBuilderService.buildNotFoundItem()
    	}
	}

	def refund(params) {
        Map fields = ApiInstallmentParser.parseRequestParams(getProvider(params), params)
        Installment installment = Installment.find(params.id, getProvider(params))

        installmentService.executeRefundRequestedByProvider(installment.id, getProviderInstance(params), fields)

        Installment.withNewTransaction {
            Installment refundedInstallment = Installment.find(params.id, getProvider(params))
            return apiResponseBuilderService.buildSuccess(ApiInstallmentParser.buildResponseItem(refundedInstallment, [:]))
        }
	}
}

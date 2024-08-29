package com.asaas.service.link

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.payment.Payment
import com.asaas.domain.paymentcampaign.PaymentCampaign

import grails.transaction.Transactional
import grails.util.Environment

@Transactional
class LinkService {

	def grailsLinkGenerator
	def grailsApplication

    def viewInvoice(payment) {
        return viewInvoice(payment, [:])
    }

    def viewInvoice(payment, Map params) {
        return grailsLinkGenerator.link(mapping: "payment-checkout", params: formatParams(params, payment), absolute: params.absoluteUrl == null ? true : params.absoluteUrl, base: params.baseUrl)
    }

    def viewTransactionReceipt(publicId, Boolean absolute) {
        if (!publicId) return null
        return grailsLinkGenerator.link(controller: 'transactionReceipt', action: 'show', id: publicId, absolute: absolute)
    }

    def viewTransactionReceiptOnDemand(String hash, Boolean absolute) {
        if (!hash) return null
        return grailsLinkGenerator.link(controller: 'transactionReceipt', action: 'showOnDemand', id: hash, absolute: absolute)
    }

    def viewInvoiceShort(payment) {
        return viewInvoiceShort(payment, [:])
    }

    def viewInvoiceShort(payment, Map params) {
        if (Environment.getCurrent().equals(Environment.PRODUCTION)) {
            String baseUrl = String.valueOf(grailsApplication.config.asaas.app.shortenedUrl)
            return grailsLinkGenerator.link(mapping: "payment-checkout", params: formatParams(params, payment), base: baseUrl, absolute: true)
        } else {
            return grailsLinkGenerator.link(mapping: "payment-checkout", params: formatParams(params, payment), absolute: true)
        }
    }

	def viewCampaignPublic(PaymentCampaign paymentCampaignBill) {
		return grailsLinkGenerator.link(controller: 'paymentCampaignBill', action: 'index', id: paymentCampaignBill.publicId, absolute: true)
	}

	def boleto(payment) {
        Boolean enableBankSlipHtmlPreview = CustomerParameter.getValue(payment.provider, CustomerParameterName.ENABLE_BANK_SLIP_HTML_PREVIEW)

        if (enableBankSlipHtmlPreview) return grailsLinkGenerator.link(controller: 'boleto', action: 'preview', id: payment.externalToken, absolute: true)

		return grailsLinkGenerator.link(controller: 'boleto', action: 'downloadPdf', id: payment.externalToken, absolute: true)
	}

    def showCustomerAccount(Long customerAccountId, Boolean absoluteUrl) {
        return grailsLinkGenerator.link(controller: 'customerAccount', action: 'show', id: customerAccountId, absolute: absoluteUrl)
    }

    def showPayment(Long paymentId, Boolean absoluteUrl) {
        return grailsLinkGenerator.link(controller: 'payment', action: 'show', id: paymentId, absolute: absoluteUrl)
    }

	public String customerNotificationLogo(Customer customer, String baseUrl) {
        return grailsLinkGenerator.link(controller: 'customerLogo', action: 'notification', id: customer.id, absolute: true, base: baseUrl)
	}

    public String buildImageButtonUrl(String id, Map params) {
        return grailsLinkGenerator.link(controller: 'static', action: 'images', id: id, absolute: true, base: params.baseUrl)
    }

    private Map formatParams(Map params, Payment payment) {
        Map formattedParams = [:]

        formattedParams.id = payment.externalToken

        if (params.containsKey("notificationRequestId")) formattedParams.nr = params.notificationRequestId
        if (params.containsKey("sentBySms")) formattedParams.s = (params.sentBySms ? 1 : 0)
        if (params.containsKey("sentByEmail")) formattedParams.e = (params.sentByEmail ? 1 : 0)
        if (params.containsKey("sentByWhatsApp")) formattedParams.w = (params.sentByWhatsApp ? 1 : 0)

        if (params.containsKey("reportProblem")) formattedParams.reportProblem = params.reportProblem

        return formattedParams
    }
}

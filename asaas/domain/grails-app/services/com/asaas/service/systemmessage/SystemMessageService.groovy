package com.asaas.service.systemmessage

import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.messagetype.MessageType
import com.asaas.systemmessage.SystemMessage

import javax.servlet.http.Cookie
import javax.servlet.http.HttpServletRequest

import grails.transaction.Transactional

@Transactional
class SystemMessageService {

	def accountActivationRequestService
	def paymentService
    def springSecurityService

	public List<SystemMessage> list(HttpServletRequest request) {
		List<SystemMessage> messagesToShow = []

		Customer customer = springSecurityService.currentUser.customer

		Map systemMessages = getSystemMessagesShouldBeDisplayed(customer)

        messagesToShow += getOnboardingSystemMessages(customer, systemMessages, request)

		removeMessagesIfCookieExistsAndIsNull(messagesToShow, request.cookies)

		if (messagesToShow) {
			return [messagesToShow[0]]
		} else {
			return messagesToShow
		}
	}

    private List<SystemMessage> getOnboardingSystemMessages(Customer customer, Map systemMessages, HttpServletRequest request) {
        List<SystemMessage> messagesToShow = []

        def dashboardURLPattern = ~/\/dashboard/
        Boolean dashboardPage = (request.forwardURI =~ dashboardURLPattern).size() > 0

        def configURLPattern = ~/\/config\/index/
        Boolean configPage = (request.forwardURI =~ configURLPattern).size() > 0

        if (!dashboardPage && systemMessages.activationRequest) messagesToShow += activationRequest()
        if (!dashboardPage && !configPage && systemMessages.customerRegisterStatusIncomplete) messagesToShow += buildOnboardingLink(customer)

        return messagesToShow
    }

	private void removeMessagesIfCookieExistsAndIsNull(List<SystemMessage> listOfSystemMessage, Cookie[] listOfCookie) {
		listOfSystemMessage.removeAll { SystemMessage systemMessage ->
			return listOfCookie.any{ Cookie cookie -> !systemMessage || cookie.name == systemMessage.cookieName && (!systemMessage.permanent) }
		}
	}

	public SystemMessage buildOnboardingLink(Customer customer) {
		return new SystemMessage(
			templatePath: "/systemMessage/templates/onboardingLink",
			type: MessageType.WARNING,
			templateModel: [customer: customer],
			permanent: true,
            iconMessageClass: "onboarding-icon-class"
		)
	}

    public SystemMessage activationRequest() {
        Customer customer = springSecurityService.currentUser.customer
        Integer currentStep = accountActivationRequestService.getCurrentActivationStep(customer)

        Boolean showStep1 = false
        Boolean showStep2 = false

        if (currentStep == 1) {
            showStep1 = true
        } else if (currentStep == 2) {
            showStep2 = true
        }

        return new SystemMessage(
            templatePath: "/systemMessage/templates/atlas/activationRequest",
            templateModel: [currentCustomer: springSecurityService.currentUser.customer, showStep1: showStep1, showStep2: showStep2, isAtlas: true],
            type: MessageType.WARNING,
            permanent: true,
            hasFeedbackMessage: true
        )
    }

	public Map getSystemMessagesShouldBeDisplayed(Customer customer) {
		Map systemMessages = [:]

		systemMessages.customerRegisterStatusIncomplete = customer.customerRegisterStatus.anyMandatoryIsPending()

		systemMessages.activationRequest = shouldExhibitActivationRequestMessage(customer)

		return systemMessages
	}

	private Boolean shouldExhibitActivationRequestMessage(Customer customer) {
		Integer currentStep = accountActivationRequestService.getCurrentActivationStep(customer)

		return currentStep.asBoolean()
	}

	private SystemMessage existsPaymentsToBeRegistered(Customer customer) {
		if (!Payment.query([exists: true, invalidPaymentsThatNeedToBeRegistered: true, customer: customer]).get()) return

		return new SystemMessage(
			templatePath: "/systemMessage/templates/existsPaymentsToBeRegistered",
			templateModel: [customer: customer],
			type: MessageType.WARNING,
			cookieName: "2b7e369c-0031-4d78-a571-8b7645a184fe",
			cookieMaxAge: 60*60*24,
		)
	}
}

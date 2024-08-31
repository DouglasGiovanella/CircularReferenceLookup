package com.asaas.service.user

import com.asaas.domain.user.User
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.partnerapplication.PartnerApplicationName
import com.asaas.domain.partnerapplication.UserPartnerToken
import com.asaas.utils.CpfCnpjUtils

import grails.transaction.Transactional

@Transactional
class UserPartnerTokenService {

    def customerPartnerApplicationService

    public String generateAuthToken(String cpfCnpj, PartnerApplicationName partnerApplicationName) {
        if (!CpfCnpjUtils.validate(cpfCnpj)) return null
        CustomerPartnerApplication customerPartnerApplication = customerPartnerApplicationService.findCustomerPartnerApplication(cpfCnpj, partnerApplicationName)
        return generateAuthToken(customerPartnerApplication)
    }

    public String generateAuthTokenByCustomerPublicId(String publicId, PartnerApplicationName partnerApplicationName) {
        CustomerPartnerApplication customerPartnerApplication = customerPartnerApplicationService.findCustomerPartnerApplicationByCustomerPublicId(publicId, partnerApplicationName)
        return generateAuthToken(customerPartnerApplication)
    }

    public String generateAuthToken(CustomerPartnerApplication customerPartnerApplication) {
        if (!customerPartnerApplication) return null

        User user = User.admin(customerPartnerApplication.customer, [:]).get()
        if (!user) return null

        String authToken = UUID.randomUUID()

        UserPartnerToken userPartnerToken	= new UserPartnerToken()
        userPartnerToken.user = user
        userPartnerToken.customerPartnerApplication = customerPartnerApplication
        userPartnerToken.authToken = UserPartnerToken.encrypt(authToken)
        userPartnerToken.save(flush: true, failOnError: true)

        return authToken
	}

	public String authenticateByTokenAndGetUsername(String authToken) {
        UserPartnerToken userPartnerToken = UserPartnerToken.findByAuthToken(authToken)
		if (!userPartnerToken) return null

        userPartnerToken.used = true
        userPartnerToken.save(flush: true, failOnError: true)

        return userPartnerToken.user.username
	}
}

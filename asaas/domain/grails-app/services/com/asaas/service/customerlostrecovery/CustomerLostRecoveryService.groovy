package com.asaas.service.customerlostrecovery

import com.asaas.customerlostrecovery.CustomerLostRecoveryCampaign
import com.asaas.customerlostrecovery.CustomerLostRecoveryEmail
import com.asaas.customerlostrecovery.CustomerLostRecoveryStage
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerlostrecovery.CustomerLostRecovery
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerLostRecoveryService {

    public CustomerLostRecovery ignoreCustomerToRecoveryCampaign(Customer customer, CustomerLostRecoveryCampaign campaign) {
        CustomerLostRecovery activeCustomerLostRecovery = CustomerLostRecovery.query([customer: customer, emailUnsubscribed: false]).get()

        if (activeCustomerLostRecovery) {
            activeCustomerLostRecovery.emailUnsubscribed = true
            activeCustomerLostRecovery.save(failOnError: true)

            return activeCustomerLostRecovery
        }

        CustomerLostRecoveryCampaign firstCampaign
        if (CustomerLostRecoveryCampaign.listPriceReductionCampaigns().contains(campaign)) {
            firstCampaign = CustomerLostRecoveryCampaign.PRICE_REDUCTION_FIRST_ATTEMPT
        } else if (CustomerLostRecoveryCampaign.listPixReleaseCampaigns().contains(campaign)) {
            firstCampaign = CustomerLostRecoveryCampaign.PIX_RELEASE_FIRST_ATTEMPT
        } else if (campaign.isHubspotFirstFlowAttempt()) {
            firstCampaign = CustomerLostRecoveryCampaign.HUBSPOT_FIRST_FLOW_ATTEMPT
        }

        CustomerLostRecovery customerLostRecovery = new CustomerLostRecovery(customer: customer, stage: CustomerLostRecoveryStage.ACCOUNT_APPROVED, emailType: CustomerLostRecoveryEmail.SIMPLE, campaign: firstCampaign, dateEmailSent: new Date(), emailUnsubscribed: true)
        customerLostRecovery.save(failOnError: true)

        return customerLostRecovery
    }

    public CustomerLostRecovery processCustomerLostRecoveryCreation(Customer customer, CustomerLostRecoveryStage stage, CustomerLostRecoveryEmail emailType, CustomerLostRecoveryCampaign campaign, Map optionalParams) {
        if (customer.email.toLowerCase().contains("@asaas.com")) {
            CustomerLostRecovery customerLostRecovery = ignoreCustomerToRecoveryCampaign(customer, campaign)
            DomainUtils.addError(customerLostRecovery, "É uma conta de um funcionário do Asaas.")

            return customerLostRecovery
        }

        CustomerLostRecovery activeCustomerLostRecovery = CustomerLostRecovery.query([customer: customer]).get()

        if (activeCustomerLostRecovery) {
            activeCustomerLostRecovery.deleted = true
            activeCustomerLostRecovery.save(failOnError: true)
        }

        CustomerLostRecovery customerLostRecovery = save(customer, stage, emailType, false, campaign, optionalParams)

        return customerLostRecovery
    }

    public CustomerLostRecovery convertLostCustomerIfNecessary(Long customerId, List<CustomerLostRecoveryCampaign> campaignList) {
        if (!canConvert(customerId, campaignList)) {
            CustomerLostRecovery validatedCustomerLostRecovery = new CustomerLostRecovery()
            DomainUtils.addError(validatedCustomerLostRecovery, "Cliente não pode ser convertido.")

            return validatedCustomerLostRecovery
        }

        AsaasLogger.info("CustomerLostRecoveryService.convertLostCustomerIfNecessary - CustomerId [${customerId}]")

        CustomerLostRecovery customerLostRecovery = CustomerLostRecovery.query([customerId: customerId, "dateConverted[isNull]": true, "campaign[in]": campaignList, order: "desc"]).get()
        customerLostRecovery.dateConverted = new Date()

        customerLostRecovery.save(failOnError: true)

        return customerLostRecovery
    }

    public Boolean canUnsubscribe(String email, CustomerLostRecoveryCampaign campaign) {
        if (!Utils.emailIsValid(email)) return false

        if (!campaign) return false

        Long customerId = Customer.query([column: "id", "email[eq]": email]).get()
        if (!customerId) return false

        CustomerLostRecovery activeCustomerLostRecovery = CustomerLostRecovery.query([customerId: customerId, "campaign[in]": [campaign]]).get()
        if (!activeCustomerLostRecovery) return false

        return true
    }

    private CustomerLostRecovery save(Customer customer, CustomerLostRecoveryStage stage, CustomerLostRecoveryEmail emailType, Boolean emailUnsubscribed, CustomerLostRecoveryCampaign campaign, Map optionalParams) {
        CustomerLostRecovery customerLostRecovery = new CustomerLostRecovery(customer: customer, stage: stage, emailType: emailType, campaign: campaign, dateEmailSent: new Date(), emailUnsubscribed: emailUnsubscribed)

        if (optionalParams.sendPush) customerLostRecovery.datePushSent = new Date()

        if (optionalParams.sendSms) customerLostRecovery.dateSmsSent = new Date()

        customerLostRecovery.save(flush: true, failOnError: true)

        return customerLostRecovery
    }

    private Boolean canConvert(Long customerId, List<CustomerLostRecoveryCampaign> campaignList) {
        CustomerLostRecovery existingCustomerLostRecovery = CustomerLostRecovery.query([customerId: customerId, "campaign[in]": campaignList]).get()

        if (!existingCustomerLostRecovery) return false

        if (existingCustomerLostRecovery.dateConverted) return false

        if (existingCustomerLostRecovery.emailUnsubscribed) return false

        return true
    }
}

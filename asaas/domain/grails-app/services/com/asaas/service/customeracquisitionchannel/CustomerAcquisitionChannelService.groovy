package com.asaas.service.customeracquisitionchannel

import com.asaas.customeracquisitionchannel.CustomerAcquisitionChannelOption
import com.asaas.domain.customer.Customer
import com.asaas.domain.customeracquisitionchannel.CustomerAcquisitionChannel
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class CustomerAcquisitionChannelService {

    public CustomerAcquisitionChannel save(Customer customer, CustomerAcquisitionChannelOption channelOption, String otherChannelOptionDescription) {
        CustomerAcquisitionChannel customerAcquisitionChannel = new CustomerAcquisitionChannel()

        customerAcquisitionChannel.customer = customer
        customerAcquisitionChannel.channelOption = channelOption
        customerAcquisitionChannel.otherChannelOptionDescription = otherChannelOptionDescription
        customerAcquisitionChannel.save(failOnError: true)

        return customerAcquisitionChannel
    }

    public Boolean isAnswerMandatory(Customer customer) {
        if (customer.dateCreated < CustomerAcquisitionChannel.QUESTIONARY_INITIAL_DATE) return false

        if (hasQuestionAnswered(customer)) return false

        if (!customer.hasCreatedPayments()) return false

        Integer daysToAskAcquisitionChannel = 7

        if (CustomDateUtils.calculateDifferenceInDays(customer.getFirstCreatedPayment(), new Date()) < daysToAskAcquisitionChannel) return false

        return true
    }

    public Boolean hasQuestionAnswered(Customer customer) {
        if (CustomerPartnerApplication.hasBradesco(customer.id)) return true

        return CustomerAcquisitionChannel.query([customer: customer, exists: true]).get().asBoolean()
    }
}

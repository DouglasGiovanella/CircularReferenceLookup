package com.asaas.service.integration.pix

import com.asaas.domain.customer.Customer
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.commons.CustomerInfoDTO
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class HermesAccountManagerService {

    public Map disable(Long customerId) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.post("/accounts/${customerId}/disable", [:], null)

        if (hermesManager.isSuccessful() || hermesManager.isNotFound()) return [success: true]

        return [error: true, errorMessage: hermesManager.errorMessage]
    }

    public Map block(Long customerId) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.post("/accounts/${customerId}/block", [:], null)

        if (hermesManager.isSuccessful() || hermesManager.isNotFound()) return [success: true]

        return [error: true, errorMessage: hermesManager.errorMessage]
    }

    public Map activate(Long customerId) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.post("/accounts/${customerId}/activate", [:], null)

        if (hermesManager.isSuccessful() || hermesManager.isNotFound()) return [success: true]

        return [error: true, errorMessage: hermesManager.errorMessage]
    }

    public Map synchronizeAccountNumber(Customer customer, Integer timeout) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false

        if (timeout != null) hermesManager.timeout = timeout

        CustomerInfoDTO customerInfoDTO = new CustomerInfoDTO(customer)

        if (Utils.emailIsValid(customerInfoDTO.name)) return [error: true, errorMessage: "Name inválido"]

        hermesManager.post("/accounts/${customer.id}/accountnumber/save", customerInfoDTO.properties, null)

        if (hermesManager.isSuccessful()) return [success: true]

        if (hermesManager.isTimeout()) return [withoutExternalResponse: true]

        return [success: false, errorMessage: hermesManager.errorMessage]
    }

    public Map updateAccountNumber(Customer customer) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false

        CustomerInfoDTO customerInfoDTO = new CustomerInfoDTO(customer)

        if (Utils.emailIsValid(customerInfoDTO.name)) return [error: true, errorMessage: "Name inválido"]

        hermesManager.post("/accounts/${customer.id}/update", customerInfoDTO.properties, null)

        if (hermesManager.isSuccessful())  return [success: true]

        if (hermesManager.isTimeout()) return [withoutExternalResponse: true]

        return [error: true, errorMessage: hermesManager.errorMessage]
    }
}

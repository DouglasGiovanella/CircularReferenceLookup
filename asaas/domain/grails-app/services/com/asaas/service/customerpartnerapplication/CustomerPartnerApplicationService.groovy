package com.asaas.service.customerpartnerapplication

import com.asaas.customer.CustomerStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.partnerapplication.PartnerApplicationName
import com.asaas.partnerapplication.PartnerApplicationPermission
import com.asaas.utils.Utils

class CustomerPartnerApplicationService {

    def customerPartnerApplicationCacheService

    public CustomerPartnerApplication saveBradesco(Customer customer) {
        CustomerPartnerApplication customerPartnerApplication = new CustomerPartnerApplication()
        customerPartnerApplication.customer = customer
        customerPartnerApplication.partnerApplicationName = PartnerApplicationName.BRADESCO
        customerPartnerApplication.permission = PartnerApplicationPermission.LOGIN

        customerPartnerApplication.save(failOnError: true)

        customerPartnerApplicationCacheService.evictByCustomer(customer.id)

        return customerPartnerApplication
    }

    public CustomerPartnerApplication saveAsaasSandbox(Customer customer) {
        CustomerPartnerApplication customerPartnerApplication = new CustomerPartnerApplication()
        customerPartnerApplication.customer = customer
        customerPartnerApplication.partnerApplicationName = PartnerApplicationName.ASAAS_SANDBOX
        customerPartnerApplication.permission = PartnerApplicationPermission.LOGIN

        customerPartnerApplication.save(failOnError: true)

        customerPartnerApplicationCacheService.evictByCustomer(customer.id)

        return customerPartnerApplication
    }

    public CustomerPartnerApplication findCustomerPartnerApplication(String cpfCnpj, PartnerApplicationName partnerApplicationName) {
        return findCustomerPartnerApplication(cpfCnpj, null, partnerApplicationName)
    }

    public CustomerPartnerApplication findCustomerPartnerApplicationByCustomerPublicId(String publicId, PartnerApplicationName partnerApplicationName) {
        return findCustomerPartnerApplication(null, publicId, partnerApplicationName)
    }

    public CustomerPartnerApplication findCustomerPartnerApplication(String cpfCnpj, String publicId, PartnerApplicationName partnerApplicationName) {
        return CustomerPartnerApplication.createCriteria().get() {
            eq("permission", PartnerApplicationPermission.LOGIN)
            eq("partnerApplicationName", partnerApplicationName)
            eq("deleted", false)

            createAlias("customer", "customer")
            ne("customer.status", CustomerStatus.DISABLED)

            if (cpfCnpj) {
                eq("customer.cpfCnpj", Utils.removeNonNumeric(cpfCnpj))
            } else {
                eq("customer.publicId", publicId)
            }
        }
    }
}

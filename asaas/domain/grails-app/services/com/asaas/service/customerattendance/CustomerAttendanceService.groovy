package com.asaas.service.customerattendance

import com.asaas.domain.customer.Customer
import com.asaas.domain.user.User
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerAttendanceService {

    public Customer findNotDisabledCustomer(String cpfCnpj, String phoneNumber) {
        String sanitizedPhoneNumber = sanitizeNumber(phoneNumber)
        String sanitizedCpfCnpj = Utils.removeNonNumeric(cpfCnpj)

        if (sanitizedCpfCnpj) {
            List<Long> customerIdList = Customer.notDisabledAccounts([column: "id", cpfCnpj: sanitizedCpfCnpj]).list()
            if (customerIdList?.size() == 1) return Customer.read(customerIdList[0])

            Customer customer = Customer.notDisabledAccounts([cpfCnpj: sanitizedCpfCnpj, "phone[or]": sanitizedPhoneNumber]).get()
            if (customer) return customer

            if (PhoneNumberUtils.validateMobilePhone(sanitizedPhoneNumber)) {
                String formattedPhoneNumber = PhoneNumberUtils.removeNinthDigitIfExists(sanitizedPhoneNumber)
                customer = Customer.notDisabledAccounts([cpfCnpj: sanitizedCpfCnpj, "phone[or]": formattedPhoneNumber]).get()
                if (customer) return customer
            }

            if (customerIdList) return Customer.read(customerIdList[0])
        }

        return findNotDisabledCustomerByPhoneNumber(sanitizedPhoneNumber)
    }

    public Customer findCustomerIncludingDisabled(String cpfCnpj, String phoneNumber) {
        String sanitizedPhoneNumber = sanitizeNumber(phoneNumber)
        String sanitizedCpfCnpj = Utils.removeNonNumeric(cpfCnpj)

        if (sanitizedCpfCnpj) {
            List<Long> customerIdList = Customer.query([column: "id", cpfCnpj: sanitizedCpfCnpj, sort: "status"]).list()
            if (customerIdList?.size() == 1) return Customer.read(customerIdList[0])

            Customer customer = Customer.query([cpfCnpj: sanitizedCpfCnpj, "phone[or]": sanitizedPhoneNumber, sort: "status"]).get()
            if (customer) return customer

            if (PhoneNumberUtils.validateMobilePhone(sanitizedPhoneNumber)) {
                String formattedPhoneNumber = PhoneNumberUtils.removeNinthDigitIfExists(sanitizedPhoneNumber)
                customer = Customer.query([cpfCnpj: sanitizedCpfCnpj, "phone[or]": formattedPhoneNumber, sort: "status"]).get()
                if (customer) return customer
            }

            if (customerIdList) return Customer.read(customerIdList[0])
        }

        return findCustomerIncludingDisabledByPhoneNumber(sanitizedPhoneNumber)
    }

    public Long findCustomerId(String email) {
        if (!email) return null

        Long customerId = Customer.query([column: "id", "email[eq]": email]).get()
        if (customerId) return customerId

        customerId = User.query([column: "customer.id", ignoreCustomer: true, username: email]).get()
        if (customerId) return customerId
    }

    public List<Long> findCustomerIdList(String cpfCnpj) {
        if (!cpfCnpj) return null
        if (!CpfCnpjUtils.validate(cpfCnpj)) return null

        List<Long> customerIdList = Customer.query([column: "id", "cpfCnpj": cpfCnpj]).list()
        return customerIdList
    }

    private Customer findNotDisabledCustomerByPhoneNumber(String phoneNumber) {
        Customer customer = Customer.notDisabledAccounts(["phone": phoneNumber]).get()
        if (customer) return customer

        customer = Customer.notDisabledAccounts(["mobilePhone": phoneNumber]).get()
        if (customer) return customer

        customer = Customer.notDisabledAccounts(["activationPhone": phoneNumber]).get()
        if (customer) return customer

        if (!PhoneNumberUtils.validateMobilePhone(phoneNumber)) return null

        String formattedPhoneNumber = PhoneNumberUtils.removeNinthDigitIfExists(phoneNumber)
        customer = Customer.notDisabledAccounts(["phone": formattedPhoneNumber]).get()
        if (customer) return customer

        customer = Customer.notDisabledAccounts(["mobilePhone": formattedPhoneNumber]).get()
        if (customer) return customer

        return Customer.notDisabledAccounts(["activationPhone": formattedPhoneNumber]).get()
    }

    private Customer findCustomerIncludingDisabledByPhoneNumber(String phoneNumber) {
        Customer customer = Customer.query(["phone": phoneNumber, sort: "status"]).get()
        if (customer) return customer

        customer = Customer.query(["mobilePhone": phoneNumber, sort: "status"]).get()
        if (customer) return customer

        customer = Customer.query(["activationPhone": phoneNumber, sort: "status"]).get()
        if (customer) return customer

        if (!PhoneNumberUtils.validateMobilePhone(phoneNumber)) return null

        String formattedPhoneNumber = PhoneNumberUtils.removeNinthDigitIfExists(phoneNumber)
        customer = Customer.query(["phone": formattedPhoneNumber, sort: "status"]).get()
        if (customer) return customer

        customer = Customer.query(["mobilePhone": formattedPhoneNumber, sort: "status"]).get()
        if (customer) return customer

        return Customer.query(["activationPhone": formattedPhoneNumber, sort: "status"]).get()
    }

    private String sanitizeNumber(String phoneNumber) {
        if (!phoneNumber) return null
        String sanitizedPhoneNumber = PhoneNumberUtils.removeBrazilAreaCode(phoneNumber)
        return PhoneNumberUtils.sanitizeNumber(sanitizedPhoneNumber)
    }
}

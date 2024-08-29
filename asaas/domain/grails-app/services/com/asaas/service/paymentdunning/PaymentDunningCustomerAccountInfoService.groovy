package com.asaas.service.paymentdunning

import com.asaas.customeraccount.CustomerAccountUpdateResponse
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentdunning.PaymentDunningCustomerAccountInfo
import com.asaas.domain.postalcode.PostalCode
import com.asaas.paymentdunning.PaymentDunningType
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PaymentDunningCustomerAccountInfoService {

    def customerAccountService

    public CustomerAccount updateCustomerAccountIfNecessary(PaymentDunningCustomerAccountInfo dunningCustomerAccountInfo) {
        Map fields = [:]
        fields.id = dunningCustomerAccountInfo.customerAccount.id

        if (!dunningCustomerAccountInfo.customerAccount.cpfCnpj) {
            fields.cpfCnpj = dunningCustomerAccountInfo.cpfCnpj
        }

        if (!dunningCustomerAccountInfo.customerAccount.name) {
            fields.name = dunningCustomerAccountInfo.name
        }

        if (!dunningCustomerAccountInfo.customerAccount.address) {
            fields.address = dunningCustomerAccountInfo.address
        }

        if (!dunningCustomerAccountInfo.customerAccount.addressNumber) {
            fields.addressNumber = dunningCustomerAccountInfo.addressNumber
        }

        if (!dunningCustomerAccountInfo.customerAccount.complement) {
            fields.complement = dunningCustomerAccountInfo.complement
        }

        if (!dunningCustomerAccountInfo.customerAccount.province) {
            fields.province = dunningCustomerAccountInfo.province
        }

        if (!dunningCustomerAccountInfo.customerAccount.postalCode) {
            fields.postalCode = dunningCustomerAccountInfo.postalCode
        }

        CustomerAccountUpdateResponse customerAccountUpdateResponse = customerAccountService.update(dunningCustomerAccountInfo.paymentDunning.customer.id, fields)

        return customerAccountUpdateResponse.customerAccount
    }

    public PaymentDunningCustomerAccountInfo save(PaymentDunning paymentDunning, Map params) {
        Map customerAccountInfo = getLatestCustomerAccountInfo(paymentDunning.payment.customerAccount)
        params.customerAccountCpfCnpj =  Utils.removeNonNumeric(customerAccountInfo?.cpfCnpj ?: params.customerAccountCpfCnpj)
        params.customerAccountName = customerAccountInfo?.name ?: params.customerAccountName

        Map parsedParams = parseSaveParams(params)

        PaymentDunningCustomerAccountInfo validatedDunningCustomerAccountInfo = validateCustomerAccountInfo(paymentDunning.customer, parsedParams)
        if (validatedDunningCustomerAccountInfo.hasErrors()) return validatedDunningCustomerAccountInfo

        PaymentDunningCustomerAccountInfo paymentDunningCustomerAccountInfo = new PaymentDunningCustomerAccountInfo()
        paymentDunningCustomerAccountInfo.paymentDunning = paymentDunning
        paymentDunningCustomerAccountInfo.customerAccount = paymentDunning.payment.customerAccount
        paymentDunningCustomerAccountInfo.properties["name", "cpfCnpj", "postalCode", "address", "addressNumber", "province", "city", "complement"] = parsedParams.customerAccount

        String additionalPhones = parsedParams.customerAccount.primaryPhone
        if (parsedParams.customerAccount.secondaryPhone) additionalPhones += ",${parsedParams.customerAccount.secondaryPhone}"
        paymentDunningCustomerAccountInfo.additionalPhones = additionalPhones
        paymentDunningCustomerAccountInfo.save()

        return paymentDunningCustomerAccountInfo
    }

    public Boolean isNaturalPerson(PaymentDunning paymentDunning) {
        String cpfCnpj = PaymentDunningCustomerAccountInfo.query([column: "cpfCnpj", paymentDunning: paymentDunning, includeDeleted: true]).get()
        return CpfCnpjUtils.isCpf(cpfCnpj)
    }

    public Map getLatestCustomerAccountInfo(CustomerAccount customerAccount) {
        PaymentDunningCustomerAccountInfo dunningCustomerAccountInfo = PaymentDunningCustomerAccountInfo.query([customerAccount: customerAccount, includeDeleted: true]).get()

        Map customerAccountInfo = [:]
        customerAccountInfo.id = customerAccount.id
        customerAccountInfo.name = customerAccount.name
        customerAccountInfo.cpfCnpj = customerAccount.cpfCnpj ?: dunningCustomerAccountInfo?.cpfCnpj
        customerAccountInfo.postalCode = customerAccount.postalCode ?: dunningCustomerAccountInfo?.postalCode
        customerAccountInfo.address = customerAccount.address ?: dunningCustomerAccountInfo?.address
        customerAccountInfo.addressNumber = customerAccount.addressNumber ?: dunningCustomerAccountInfo?.addressNumber
        customerAccountInfo.province = customerAccount.province ?: dunningCustomerAccountInfo?.province
        customerAccountInfo.complement = customerAccount.complement ?: dunningCustomerAccountInfo?.complement

        List<String> phoneList = []
        if (customerAccount.phone) phoneList.add(customerAccount.phone)
        if (customerAccount.mobilePhone) phoneList.add(customerAccount.mobilePhone)
        if (dunningCustomerAccountInfo?.getPhoneList()) phoneList += dunningCustomerAccountInfo.getPhoneList()

        if (phoneList) {
            phoneList.unique()
            customerAccountInfo.primaryPhone = phoneList[0]
            if (phoneList.size() > 1) customerAccountInfo.secondaryPhone = phoneList[1]
        }

        return customerAccountInfo
    }

    private PaymentDunningCustomerAccountInfo validateCustomerAccountInfo(Customer customer, Map params) {
        PaymentDunningCustomerAccountInfo dunningCustomerAccountInfo = new PaymentDunningCustomerAccountInfo()

        if (!CpfCnpjUtils.validate(params.customerAccount.cpfCnpj)) {
            DomainUtils.addError(dunningCustomerAccountInfo, "É necessário informar um CPF/CNPJ válido.")
        }

        if (!PostalCode.validate(params.customerAccount.postalCode)) {
            DomainUtils.addError(dunningCustomerAccountInfo, "É necessário informar um CEP válido.")
        }

        if (!params.customerAccount.address) {
            DomainUtils.addError(dunningCustomerAccountInfo, "É necessário informar o endereço do seu cliente.")
        }

        if (!params.customerAccount.addressNumber) {
            DomainUtils.addError(dunningCustomerAccountInfo, "É necessário informar o número do endereço do seu cliente.")
        }

        if (!params.customerAccount.province) {
            DomainUtils.addError(dunningCustomerAccountInfo, "É necessário informar o bairro do seu cliente.")
        }

        if (!params.customerAccount.city) {
            DomainUtils.addError(dunningCustomerAccountInfo, "É necessário informar a cidade do seu cliente.")
        }

        if (!params.customerAccount.name) {
            DomainUtils.addError(dunningCustomerAccountInfo, "É necessário informar o nome de seu cliente.")
        }

        if (!params.customerAccount.primaryPhone) {
            DomainUtils.addError(dunningCustomerAccountInfo, "É necessário informar o número de telefone de seu cliente.")
        } else {
            Boolean isEqualsToCustomerPhone = verifyIfPhoneIsEqualsToCustomerPhone(customer, params.customerAccount.primaryPhone)

            if (isEqualsToCustomerPhone) {
                DomainUtils.addError(dunningCustomerAccountInfo, "O número de telefone primário não pode ser igual ao número de telefone do seu cadastro.")
            }
        }

        if (params.customerAccount.secondaryPhone) {
            Boolean isEqualsToCustomerPhone = verifyIfPhoneIsEqualsToCustomerPhone(customer, params.customerAccount.secondaryPhone)

            if (isEqualsToCustomerPhone) {
                DomainUtils.addError(dunningCustomerAccountInfo, "O número de telefone secundário não pode ser igual ao número de telefone do seu cadastro.")
            }
        }

        return dunningCustomerAccountInfo
    }

    private Map parseSaveParams(Map params) {
        if (!params.customerAccount) params.customerAccount = [:]

        if (params.customerAccountCpfCnpj) params.customerAccount.cpfCnpj = params.customerAccountCpfCnpj
        if (params.customerAccountName) params.customerAccount.name = params.customerAccountName
        if (params.customerAccountPrimaryPhone) params.customerAccount.primaryPhone = Utils.removeNonNumeric(params.customerAccountPrimaryPhone)
        if (params.customerAccountSecondaryPhone) params.customerAccount.secondaryPhone = Utils.removeNonNumeric(params.customerAccountSecondaryPhone)
        if (params.customerAccountPostalCode) params.customerAccount.postalCode = Utils.removeNonNumeric(params.customerAccountPostalCode)
        if (params.customerAccountAddress) params.customerAccount.address = params.customerAccountAddress
        if (params.customerAccountAddressNumber) params.customerAccount.addressNumber = params.customerAccountAddressNumber
        if (params.customerAccountProvince) params.customerAccount.province = params.customerAccountProvince
        if (params.customerAccountComplement) params.customerAccount.complement = params.customerAccountComplement

        setAddressFromPostalCode(params)

        return params
    }

    private void setAddressFromPostalCode(Map params) {
        if (!params.customerAccount.postalCode) return

        PostalCode postalCode = PostalCode.find(params.customerAccount.postalCode)
        if (!postalCode) return

        params.customerAccount.city = postalCode.city

        if (!params.customerAccount.address && !postalCode.isGeneral()) params.customerAccount.address = postalCode.address
        if (!params.customerAccount.province) params.customerAccount.province = postalCode.province
    }

    private Boolean verifyIfPhoneIsEqualsToCustomerPhone(Customer customer, String phone) {
        List<String> customerPhones = []
        if (customer.phone) customerPhones.add(Utils.removeNonNumeric(customer.phone))
        if (customer.mobilePhone) customerPhones.add(Utils.removeNonNumeric(customer.mobilePhone))

        return customerPhones.contains(phone)
    }
}

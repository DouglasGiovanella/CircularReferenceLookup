package com.asaas.service.asaascard

import com.asaas.domain.asaascard.AsaasCardHolder
import com.asaas.domain.companypartnerquery.CompanyPartnerQuery
import com.asaas.domain.customer.Customer
import com.asaas.domain.postalcode.PostalCode
import com.asaas.user.UserUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AsaasCardHolderService {

    def messageService

    public AsaasCardHolder save(Customer customer, Map params) {
        setAddressFromPostalCode(params)
        AsaasCardHolder asaasCardHolder = validateRequestParams(params)
        if (asaasCardHolder.hasErrors()) return asaasCardHolder

        asaasCardHolder.properties["name","cpfCnpj","birthDate","email","mobilePhone","address","addressNumber","complement","postalCode","province","city","motherName"] = params
        asaasCardHolder.customer = customer
        asaasCardHolder.creator = UserUtils.getCurrentUser()

        asaasCardHolder.save(failOnError: true)

        return asaasCardHolder
    }

    public AsaasCardHolder updateForReissue(Customer customer, Long holderId, Map params) {
        AsaasCardHolder asaasCardHolder = updateAddress(customer, holderId, params)
        asaasCardHolder = updateContactInfo(customer, holderId, params.email, params.mobilePhone)

        return asaasCardHolder
    }

    public AsaasCardHolder updateAddress(Customer customer, Long holderId, Map params) {
        setAddressFromPostalCode(params)

        AsaasCardHolder asaasCardHolder = validateAddressInfo(params)

        if (asaasCardHolder.hasErrors()) return asaasCardHolder

        asaasCardHolder = AsaasCardHolder.find(holderId, customer)
        asaasCardHolder.properties["address","addressNumber","complement","postalCode","province","city"] = params

        asaasCardHolder.save(failOnError: true)

        return asaasCardHolder
    }

    public AsaasCardHolder updateContactInfo(Customer customer, Long holderId, String email, String mobilePhone) {
        AsaasCardHolder asaasCardHolder = AsaasCardHolder.find(holderId, customer)

        asaasCardHolder.email = email
        asaasCardHolder.mobilePhone = mobilePhone

        AsaasCardHolder validateHolder = validateHolderInfo(asaasCardHolder.properties)
        if (validateHolder.hasErrors()) return validateHolder

        asaasCardHolder.save(failOnError: true)

        return asaasCardHolder
    }

    public AsaasCardHolder validateHolderInfo(Map params) {
        AsaasCardHolder asaasCardHolder = new AsaasCardHolder()

        if (!params.name) DomainUtils.addError(asaasCardHolder, "Preencha o nome.")
        if (params.name.toString().length() > AsaasCardHolder.PAYSMART_ELO_NAME_MAX_SIZE) {
            DomainUtils.addError(asaasCardHolder, Utils.getMessageProperty("asaasCardHolder.name.maxSize.exceeded", [AsaasCardHolder.PAYSMART_ELO_NAME_MAX_SIZE]))
        }
        if (!params.birthDate) DomainUtils.addError(asaasCardHolder, "Informe uma data de nascimento válida.")
        if (!CpfCnpjUtils.isCpf(params.cpfCnpj) || !CpfCnpjUtils.validate(params.cpfCnpj)) DomainUtils.addError(asaasCardHolder, "Informe um CPF válido.")
        if (!PhoneNumberUtils.validateMobilePhone(params.mobilePhone)) DomainUtils.addError(asaasCardHolder, "Informe um celular válido.")
        if (!params.email || !Utils.emailIsValid(params.email)) DomainUtils.addError(asaasCardHolder, "Informe um email válido.")

        return asaasCardHolder
    }

    public AsaasCardHolder validateAddressInfo(Map params) {
        AsaasCardHolder asaasCardHolder = new AsaasCardHolder()

        if (!params.address) DomainUtils.addError(asaasCardHolder, "Preencha o endereço.")
        if (!params.addressNumber) DomainUtils.addError(asaasCardHolder, "Preencha o número do endereço.")
        if (!params.province) DomainUtils.addError(asaasCardHolder, "Preencha o bairro.")

        if (!PostalCode.validate(params.postalCode)) {
            DomainUtils.addError(asaasCardHolder, "Informe um CEP válido.")
        } else if (!params.city) {
            DomainUtils.addError(asaasCardHolder, "Preencha a cidade.")
        }

        if (params.address.toString().length() > AsaasCardHolder.PAYSMART_ELO_ADDRESS_MAX_SIZE) {
            DomainUtils.addError(asaasCardHolder, Utils.getMessageProperty("asaasCardHolder.address.maxSize.exceeded", [AsaasCardHolder.PAYSMART_ELO_ADDRESS_MAX_SIZE]))
        }

        if (params.addressNumber.toString().length() > AsaasCardHolder.PAYSMART_ELO_ADDRESS_NUMBER_MAX_SIZE) {
            DomainUtils.addError(asaasCardHolder, Utils.getMessageProperty("asaasCardHolder.addressNumber.maxSize.exceeded", [AsaasCardHolder.PAYSMART_ELO_ADDRESS_NUMBER_MAX_SIZE]))
        }

        if (params.province.toString().length() > AsaasCardHolder.PAYSMART_ELO_PROVINCE_MAX_SIZE) {
            DomainUtils.addError(asaasCardHolder, Utils.getMessageProperty("asaasCardHolder.province.maxSize.exceeded", [AsaasCardHolder.PAYSMART_ELO_PROVINCE_MAX_SIZE]))
        }

        if (params.complement) {
            if (params.complement.toString().length() > AsaasCardHolder.PAYSMART_ELO_COMPLEMENT_MAX_SIZE) {
                DomainUtils.addError(asaasCardHolder, Utils.getMessageProperty("asaasCardHolder.complement.maxSize.exceeded", [AsaasCardHolder.PAYSMART_ELO_COMPLEMENT_MAX_SIZE]))
            }
        }

        return asaasCardHolder
    }

    public void setAddressFromPostalCode(Map params) {
        if (!params.postalCode) return

        PostalCode postalCode = PostalCode.find(params.postalCode)
        if (!postalCode) return

        params.city = postalCode.city

        if (!params.address && !postalCode.isGeneral()) params.address = postalCode.address
        if (!params.province) params.province = postalCode.province
    }

    public Map getHolderInfoIfNecessary(Customer customer) {
        if (!customer.asaasCardEloEnabled()) return [:]

        Map holderInfo = [:]
        if (customer.isLegalPerson()) {
            List<CompanyPartnerQuery> companyPartnerList = CompanyPartnerQuery.query([companyCnpj: customer.cpfCnpj,
                                                                                      isAdmin: true,
                                                                                      sort: "participationPercentage",
                                                                                      order: "desc"]).list()

            if (companyPartnerList && companyPartnerList[0].name) {
                if (companyPartnerList.size() == 1) {
                    holderInfo = [name: companyPartnerList[0].name, cpfCnpj: companyPartnerList[0].cpf]
                } else if (companyPartnerList[0].participationPercentage != companyPartnerList[1].participationPercentage) {
                    holderInfo = [name: companyPartnerList[0].name, cpfCnpj: companyPartnerList[0].cpf]
                }
            }
        } else {
            holderInfo = [name: customer.name, birthDate: customer.birthDate, cpfCnpj: customer.cpfCnpj]
        }

        holderInfo.address = customer.address
        holderInfo.addressNumber = customer.addressNumber
        holderInfo.complement = customer.complement
        holderInfo.province = customer.province
        holderInfo.city = customer.city
        holderInfo.state = customer.state
        holderInfo.postalCode = customer.postalCode
        holderInfo.mobilePhone = customer.mobilePhone
        holderInfo.email = customer.email

        return holderInfo
    }

    public Map getHolderInfoForReissue(AsaasCardHolder holder) {
        Map holderInfo = [:]
        holderInfo.name = holder.name
        holderInfo.email = holder.email
        holderInfo.birthDate = holder.birthDate
        holderInfo.cpfCnpj = holder.cpfCnpj
        holderInfo.mobilePhone = holder.mobilePhone

        return getAddressInfoForReissue(holder, holderInfo)
    }

    public Map getAddressInfoForReissue(AsaasCardHolder holder, Map holderInfo) {
        holderInfo.address = holder.address
        holderInfo.addressNumber = holder.addressNumber
        holderInfo.complement = holder.complement
        holderInfo.postalCode = holder.postalCode
        holderInfo.province = holder.province
        holderInfo.city = holder.city

        return holderInfo
    }

    private AsaasCardHolder validateRequestParams(Map params) {
        AsaasCardHolder asaasCardHolder = validateHolderInfo(params)
        AsaasCardHolder asaasCardHolderWithAddressValidation = validateAddressInfo(params)

        if (asaasCardHolderWithAddressValidation.hasErrors()) {
            for (error in asaasCardHolderWithAddressValidation.errors.allErrors) {
                DomainUtils.addError(asaasCardHolder, error.defaultMessage)
            }
        }

        return asaasCardHolder
    }
}

package com.asaas.service.customer

import com.asaas.domain.customer.CustomerAccount
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.codehaus.groovy.grails.commons.DefaultGrailsDomainClass
import org.codehaus.groovy.grails.orm.hibernate.cfg.NamedCriteriaProxy

@Transactional
class CustomerAccountAdminService {

    public NamedCriteriaProxy getCriteria(Map search) {
        return new NamedCriteriaProxy(criteriaClosure: customerAccountListCriteria(search), domainClass: new DefaultGrailsDomainClass(CustomerAccount))
    }

    public Boolean toggleForeign(Long customerAccountId) {
        CustomerAccount customerAccount = CustomerAccount.get(customerAccountId)
        customerAccount.foreignCustomer = !customerAccount.foreignCustomer

        customerAccount.save(failOnError: true)

        return customerAccount.foreignCustomer
    }

    private Closure customerAccountListCriteria(Map search) {
        return {
            order(search.sort ?: "id", search.order ?: "desc")

            if (joinWithProvider(search)) {
                createAlias('provider', 'provider')
            }

            if (search.containsKey("id[in]")) {
                'in'("id", search.'id[in]' instanceof String ? search.'id[in]'.split(',').collect { it.toLong() } : search.'id[in]'.collect { it.toLong() } )
            }

            if (search.providerName) {
                or {
                    like("provider.name", "%" + search.providerName + "%")
                    like("provider.company", "%" + search.providerName + "%")
                    like("provider.email", "%" + search.providerName + "%")
                }
            }

            if (search.providerEmail) {
                or {
                    like("provider.email", search.providerEmail + "%")
                }
            }

            if (search.customerName) {
                or {
                    like("name", search.customerName + "%")
                    like("email", search.customerName + "%")
                }
            }

            if (search.name) {
                like("name", search.name + "%")
            }

            if (search.email) {
                eq("email", search.email)
            }

            if (search.cpfCnpj) {
                eq("cpfCnpj", Utils.removeNonNumeric(search.cpfCnpj))
            }

            if (search.cpfCnpjStatus) {
                if (Boolean.valueOf(search.cpfCnpjStatus)) {
                    and { isNotNull("cpfCnpj") }
                } else {
                    and { isNull("cpfCnpj") }
                }
            }

            if (search.phoneStatus) {
                if (Boolean.valueOf(search.phoneStatus)) {
                    and {
                        or {
                            isNotNull("phone")
                            isNotNull("mobilePhone")
                        }
                    }
                } else {
                    and { isNull("phone") }
                    and { isNull("mobilePhone") }
                }
            }

            if (search.phone) {
                String phoneSearch = Utils.removeNonNumeric(search.phone)
                or {
                    eq("phone", phoneSearch)
                    eq("mobilePhone", phoneSearch)
                }
            }
        }
    }

    private Boolean joinWithProvider(Map search) {
        if (search.providerName || search.providerEmail) return true

        return false
    }
}

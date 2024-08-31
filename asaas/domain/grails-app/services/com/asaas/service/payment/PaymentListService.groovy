package com.asaas.service.payment

import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.mysql.jdbc.exceptions.MySQLTimeoutException
import grails.transaction.Transactional
import org.codehaus.groovy.grails.orm.hibernate.cfg.NamedCriteriaProxy

@Transactional
class PaymentListService {

    def grailsApplication

    public Map listWithTimeout(Customer customer, Map params, Integer max, Integer offset, Boolean getTotalCount) {
        try {
            Map returnParams = [:]

            if (params.deletedOnly && !Boolean.valueOf(params.deletedOnly)) params.deleted = true

            params.customerId = customer.id
            NamedCriteriaProxy criteriaProxy = Payment.query(params.findAll { it.value != null })

            if (customer.hasLargeBase()) {
                returnParams.payments = SequencedResultList.build(
                    criteriaProxy,
                    max,
                    offset
                )
            } else {
                returnParams.payments = criteriaProxy.list(readOnly: true, max: max, offset: offset, timeout: grailsApplication.config.asaas.query.defaultTimeoutInSeconds)

                if (getTotalCount) {
                    returnParams.totalCount = criteriaProxy.count() { setTimeout(grailsApplication.config.asaas.query.defaultTimeoutInSeconds) }
                }
            }

            return returnParams
        } catch (error) {
            if (error.cause instanceof MySQLTimeoutException) {
                AsaasLogger.error("PaymentListService.listWithTimeout >> Timeout na listagem de payments. Params [${params}]]", error)
            }

            throw error
        }
    }

}

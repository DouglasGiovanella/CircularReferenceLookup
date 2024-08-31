package com.asaas.service.api.asaasmoney

import com.asaas.api.asaasmoney.ApiAsaasMoneyInvoiceParser
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.namedqueries.NamedQueries
import com.asaas.namedqueries.SqlOrder
import com.asaas.service.api.ApiBaseService

import grails.transaction.Transactional

import org.codehaus.groovy.grails.orm.hibernate.cfg.NamedCriteriaProxy
import org.hibernate.criterion.Projections
import org.hibernate.impl.CriteriaImpl

@Transactional
class ApiAsaasMoneyInvoiceService extends ApiBaseService {

    def apiResponseBuilderService

    public Map find(Map params) {
        Customer customer = getProviderInstance(params)

        Payment payment = Payment.queryByPayerCpfCnpj(customer.cpfCnpj, [publicId: params.id]).get()
        if (!payment) {
            return apiResponseBuilderService.buildNotFoundItem()
        }

        return apiResponseBuilderService.buildSuccess(ApiAsaasMoneyInvoiceParser.buildResponseItem(payment))
    }

    public Map list(Map params) {
        Customer customer = getProviderInstance(params)
        Map fields = ApiAsaasMoneyInvoiceParser.parseListingFilters(customer, params)
        fields.disableSort = true

        SqlOrder sqlOrder = new SqlOrder("(status in ('OVERDUE', 'PENDING')) DESC,status, due_date")

        NamedCriteriaProxy criteriaProxy = Payment.queryByPayerCpfCnpj(customer.cpfCnpj, fields)

        CriteriaImpl criteria = NamedQueries.buildCriteriaFromNamedQuery(criteriaProxy)
        criteria.addOrder(sqlOrder)
            .setReadOnly(true)
            .setFirstResult(getOffset(params))
            .setMaxResults(getLimit(params))

        List<Payment> paymentList = criteria.list()
        List<Map> responseList = paymentList.collect { payment -> ApiAsaasMoneyInvoiceParser.buildLeanResponseItem(payment) }

        criteria.setFirstResult(0)
        Integer totalCount = criteria.setProjection(Projections.rowCount()).uniqueResult().intValue()

        return apiResponseBuilderService.buildList(responseList, getLimit(params), getOffset(params), totalCount)
    }
}

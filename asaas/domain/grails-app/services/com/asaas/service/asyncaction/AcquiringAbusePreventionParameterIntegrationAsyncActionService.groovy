package com.asaas.service.asyncaction

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.builder.AsyncActionDataDeserializer
import com.asaas.domain.asyncAction.AcquiringAbusePreventionParameterIntegrationAsyncAction
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerCreditCardAbusePreventionParameter
import com.asaas.utils.Utils

import grails.transaction.Transactional

import org.hibernate.SQLQuery

@Transactional
class AcquiringAbusePreventionParameterIntegrationAsyncActionService {

    def acquiringMerchantManagerService
    def baseAsyncActionService
    def grailsApplication

    public void save(Long customerId) {
        baseAsyncActionService.save(new AcquiringAbusePreventionParameterIntegrationAsyncAction(), [customerId: customerId])
    }

    public Boolean process() {
        List<Map> asyncActionDataList = listPendingData()
        if (!asyncActionDataList) return false

        baseAsyncActionService.processListWithNewTransaction(AcquiringAbusePreventionParameterIntegrationAsyncAction, asyncActionDataList, { Map asyncActionData ->
            Customer customer = Customer.read(asyncActionData.customerId)

            Boolean merchantSaved = acquiringMerchantManagerService.save(customer)

            if (!merchantSaved) throw new RuntimeException("Não foi possível incluir o cliente no Acquiring.")

            processCustomerParameters(customer)
        }, [shouldRetryOnErrorClosure: { Exception exception -> return true }])

        return true
    }

    private List<Map> listPendingData() {
        AcquiringAbusePreventionParameterIntegrationAsyncAction.withSession { session ->
            final Integer maxItemsPerCycle = 250
            final String queuesSchemaName = grailsApplication.config.asaas.database.schema.queues.name

            SQLQuery query = session.createSQLQuery("SELECT id, action_data FROM ${queuesSchemaName}.acquiring_abuse_prevention_parameter_integration_async_action FORCE INDEX(primary) WHERE status = :status LIMIT :limit")
            query.setString("status", AsyncActionStatus.PENDING.toString())
            query.setLong("limit", maxItemsPerCycle)

            List<List> asyncActionInfoList = query.list()
            if (!asyncActionInfoList) return []

            return asyncActionInfoList.collect( { AsyncActionDataDeserializer.buildDataMap(it[1], Utils.toLong(it[0])) } )
        }
    }

    private void processCustomerParameters(Customer customer) {
        List<CustomerCreditCardAbusePreventionParameter> parameterList = CustomerCreditCardAbusePreventionParameter.query([customer: customer]).list(readOnly: true)

        for (CustomerCreditCardAbusePreventionParameter parameter : parameterList) {
            final Boolean saved = acquiringMerchantManagerService.saveCardAbusePreventionParameter(customer, parameter.name, getValue(parameter))

            if (!saved) throw new RuntimeException("Não foi possível salvar o parâmetro.")
        }
    }

    private String getValue(CustomerCreditCardAbusePreventionParameter parameter) {
        if (parameter.name.isIntegerValue()) return parameter.integerValue.toString()
        if (parameter.name.isBooleanValue()) return parameter.value.toString()
        if (parameter.name.isStringValue()) return parameter.stringValue

        throw new RuntimeException("Tipo de parâmetro não identificado.")
    }
}

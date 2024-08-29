package com.asaas.service.asaasCompany

import com.asaas.billinginfo.BillingType
import com.asaas.customer.CustomerEventName
import com.asaas.domain.asaasCompany.AsaasCompanyNumbers
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.paymentcampaign.PaymentCampaign
import com.asaas.payment.PaymentStatus
import com.asaas.stage.StageCode

import grails.transaction.Transactional

import org.hibernate.SQLQuery

@Transactional
class AsaasCompanyNumbersService {

    def sessionFactory

    public void updateTotalActiveCustomersCount(Integer totalActiveCustomers) {
        AsaasCompanyNumbers asaasCompanyNumbers = AsaasCompanyNumbers.getInstance()

        asaasCompanyNumbers.totalActiveCustomersCount = totalActiveCustomers

        asaasCompanyNumbers.save(failOnError: true)
    }

    public void updateTotalReceivedPaymentsData(BigDecimal sumQuantity, BigDecimal sumValue) {
        AsaasCompanyNumbers asaasCompanyNumbers = AsaasCompanyNumbers.getInstance()
        asaasCompanyNumbers.totalReceivedPaymentsValue = sumValue
        asaasCompanyNumbers.totalReceivedPaymentsCount = sumQuantity
        asaasCompanyNumbers.save(failOnError: true)
    }

    public void updateTotalReceivedBankslipsCountData(BigDecimal totalReceivedBankslipsCount) {
        AsaasCompanyNumbers asaasCompanyNumbers = AsaasCompanyNumbers.getInstance()
        asaasCompanyNumbers.totalReceivedBankslipsCount = totalReceivedBankslipsCount
        asaasCompanyNumbers.save(failOnError: true)
    }

    public void updateTotalEmittedInvoicesCountData(BigDecimal totalEmittedInvoicesCount) {
        AsaasCompanyNumbers asaasCompanyNumbers = AsaasCompanyNumbers.getInstance()
        asaasCompanyNumbers.totalEmittedInvoicesCount = totalEmittedInvoicesCount
        asaasCompanyNumbers.save(failOnError: true)
    }

    public void updateTotalCreatedPaymentCampaignCountData() {
        BigDecimal totalCreatedPaymentCampaignCount = calculateTotalCreatedPaymentCampaignCount()

        AsaasCompanyNumbers asaasCompanyNumbers = AsaasCompanyNumbers.getInstance()
        asaasCompanyNumbers.totalCreatedPaymentCampaignCount = totalCreatedPaymentCampaignCount
        asaasCompanyNumbers.save(failOnError: true)
    }

    public Map calculateReceivedPaymentsValue(Long initialId, Long finalId) {
        StringBuilder builder = new StringBuilder()
        builder.append("SELECT SUM(value) AS sum, COUNT(id) AS count FROM payment")
        builder.append("    WHERE status IN :hasBeenConfirmedStatusList")
        builder.append("    AND value IS NOT NULL")
        builder.append("    AND id >= :initialId AND id < :finalId")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(builder.toString())

        query.setParameterList("hasBeenConfirmedStatusList", PaymentStatus.hasBeenConfirmedStatusList().collect { it.toString() })
        query.setLong("initialId", initialId)
        query.setLong("finalId", finalId)

        List resultQuery = query.uniqueResult()
        Map receivedPayments = [
            sumValue: resultQuery.getAt(0),
            sumQuantity: resultQuery.getAt(1)
        ]

        return receivedPayments
    }

    public BigDecimal calculateTotalEmittedInvoicesCount(Long initialId, Long finalId) {
        return Invoice.totalEmittedInvoice(initialId, finalId).get()
    }

    public Integer calculateTotalActiveCustomersCount(Long initialId, Long finalId) {
        StringBuilder builder = new StringBuilder()
        builder.append("SELECT COUNT(c.id) FROM customer c")
        builder.append("    JOIN customer_stage cs ON cs.customer_id = c.id")
        builder.append("    JOIN stage s ON s.id = cs.stage_id")
        builder.append("    WHERE (EXISTS (SELECT 1 FROM customer_event ce WHERE ce.customer_id = c.id AND ce.event IN (:eventList))")
        builder.append("    OR  EXISTS (SELECT 1 FROM account_activation_request aar WHERE aar.customer_id = c.id AND aar.used = true))")
        builder.append("    AND s.code = :stageCode")
        builder.append("    AND cs.customer_id >= :initialId AND cs.customer_id < :finalId")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(builder.toString())

        query.setString("stageCode", StageCode.ACTIVATED.toString())

        query.setParameterList("eventList", [CustomerEventName.ACTIVATION.toString(), CustomerEventName.AUTO_ACTIVATION.toString()])

        query.setLong("initialId", initialId)

        query.setLong("finalId", finalId)

        return query.list().first()
    }

    public BigDecimal calculateTotalReceivedBankslipsCount(Long initialId, Long finalId) {
        StringBuilder builder = new StringBuilder()
        builder.append("SELECT COUNT(id) FROM payment")
        builder.append("    WHERE status IN :hasBeenConfirmedStatusList")
        builder.append("    AND billing_type = :billingType")
        builder.append("    AND id >= :initialId AND id < :finalId")

        SQLQuery query = sessionFactory.currentSession.createSQLQuery(builder.toString())

        query.setParameterList("hasBeenConfirmedStatusList", PaymentStatus.hasBeenConfirmedStatusList().collect { it.toString() })
        query.setString("billingType", BillingType.BOLETO.toString())
        query.setLong("initialId", initialId)
        query.setLong("finalId", finalId)

        return query.list().first()
    }

    private BigDecimal calculateTotalCreatedPaymentCampaignCount() {
        return PaymentCampaign.count()
    }
}

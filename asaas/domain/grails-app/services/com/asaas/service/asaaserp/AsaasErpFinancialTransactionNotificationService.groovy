package com.asaas.service.asaaserp

import com.asaas.asaaserp.AsaasErpFinancialTransactionNotificationStatus
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.domain.asaaserp.AsaasErpFinancialTransactionNotification
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.exception.AsaasErpUndefinedErrorException
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class AsaasErpFinancialTransactionNotificationService {

    def asaasErpFinancialTransactionNotificationManagerService
    def sessionFactory

    public AsaasErpFinancialTransactionNotification save(AsaasErpCustomerConfig asaasErpCustomerConfig, FinancialTransaction financialTransaction) {
        AsaasErpFinancialTransactionNotification asaasErpFinancialTransactionNotification = new AsaasErpFinancialTransactionNotification()
        asaasErpFinancialTransactionNotification.asaasErpCustomerConfig = asaasErpCustomerConfig
        asaasErpFinancialTransactionNotification.financialTransaction = financialTransaction
        asaasErpFinancialTransactionNotification.status = AsaasErpFinancialTransactionNotificationStatus.PENDING
        asaasErpFinancialTransactionNotification.save(failOnError: true)

        return asaasErpFinancialTransactionNotification
    }

    public void processAsaasErpFinancialTransactionNotification() {
        List<Long> asaasErpCustomerConfigIdList = AsaasErpFinancialTransactionNotification.query([distinct: "asaasErpCustomerConfig.id", "asaasErpCustomerConfig.fullyIntegrated": true, disableSort: true, status: AsaasErpFinancialTransactionNotificationStatus.PENDING]).list(max: 1000)

        final Integer flushEvery = 100
        Utils.forEachWithFlushSession(asaasErpCustomerConfigIdList, flushEvery, { asaasErpCustomerConfigId ->
            Utils.withNewTransactionAndRollbackOnError ({
                AsaasErpFinancialTransactionNotification asaasErpFinancialTransactionNotification = AsaasErpFinancialTransactionNotification.query([asaasErpCustomerConfigId: asaasErpCustomerConfigId, disableSort: true]).get()

                asaasErpFinancialTransactionNotificationManagerService.send(asaasErpFinancialTransactionNotification)

                deletePendingNotification(asaasErpFinancialTransactionNotification.asaasErpCustomerConfig.id)
            }, [onError: { Exception exception ->
                if (exception instanceof AsaasErpUndefinedErrorException) {
                    AsaasLogger.warn("AsaasErpFinancialTransactionNotificationService.processAsaasErpFinancialTransactionNotification >> AsaasErpUndefinedErrorException ao notificar a atualização de extrato [asaasErpCustomerConfigId: ${asaasErpCustomerConfigId}].", exception)
                    return
                }

                setAsErrorWithNewTransaction(asaasErpCustomerConfigId)
                AsaasLogger.error("AsaasErpFinancialTransactionNotificationService.processAsaasErpFinancialTransactionNotification >> Erro notificar o syncronismo com o Base Erp ${asaasErpCustomerConfigId}.", exception)
            }, ignoreStackTrace: true])
        })
    }

    public void deletePendingNotification(Long asaasErpCustomerConfigId) {
        final String sql = "DELETE FROM asaas_erp_financial_transaction_notification WHERE asaas_erp_customer_config_id = :asaasErpCustomerConfigId and status = :status"

        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(sql)
        sqlQuery.setLong("asaasErpCustomerConfigId", asaasErpCustomerConfigId)
        sqlQuery.setString("status", AsaasErpFinancialTransactionNotificationStatus.PENDING.toString())
        sqlQuery.executeUpdate()
    }

    public void sendNotificationForCustomerWithoutFinancialTransaction(AsaasErpCustomerConfig asaasErpCustomerConfig) {
        AsaasErpFinancialTransactionNotification asaasErpFinancialTransactionNotification = new AsaasErpFinancialTransactionNotification()
        asaasErpFinancialTransactionNotification.asaasErpCustomerConfig = asaasErpCustomerConfig
        asaasErpFinancialTransactionNotificationManagerService.send(asaasErpFinancialTransactionNotification)
    }

    private void setAsErrorWithNewTransaction(Long asaasErpCustomerConfigId) {
        Utils.withNewTransactionAndRollbackOnError({
            final String sql = "UPDATE asaas_erp_financial_transaction_notification SET status = :status, last_updated = :lastUpdated, version = version + 1  WHERE asaas_erp_customer_config_id = :asaasErpCustomerConfigId"

            SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(sql)
            sqlQuery.setString("status", AsaasErpFinancialTransactionNotificationStatus.ERROR.toString())
            sqlQuery.setTimestamp("lastUpdated", new Date())
            sqlQuery.setLong("asaasErpCustomerConfigId", asaasErpCustomerConfigId)
            sqlQuery.executeUpdate()
        }, [logErrorMessage: "AsaasErpFinancialTransactionNotificationService.setAsErrorWithNewTransaction >> Não foi possível registrar o status de erro asaasErpCustomerConfigId: [${asaasErpCustomerConfigId}]"])
    }
}

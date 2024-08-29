package com.asaas.service.databricks

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.databricks.DatabricksQueryExecutor
import grails.transaction.Transactional
import org.hibernate.SQLQuery
import org.hibernate.transform.AliasToEntityMapResultTransformer

@Transactional
class DatabricksService {

    def sessionFactory

    public List runQuery(String sql, Map params, Closure callback = null) {
        if (AsaasEnvironment.isProduction()) {
            DatabricksQueryExecutor databricksQueryExecutor = new DatabricksQueryExecutor(callback)

            return databricksQueryExecutor.runQuery(sql, params)
        }

        List result = runFallbackQuery(sql, params)
        if (callback) {
            callback.call(result)
            return []
        }

        return result
    }

    private List runFallbackQuery(String sql, Map params) {
        SQLQuery query = sessionFactory.currentSession.createSQLQuery(sql)
        query.resultTransformer = AliasToEntityMapResultTransformer.INSTANCE

        params.each { String key, Object value ->
            if (value instanceof List) {
                query.setParameterList(key, value)
                return
            }

            query.setParameter(key, value)
        }

        List result = []
        for (Map item : query.list()) {
            if (item.size() == 1) {
                result.add(item.values().iterator().next())
            } else {
                result.add(item)
            }
        }

        return result
    }
}

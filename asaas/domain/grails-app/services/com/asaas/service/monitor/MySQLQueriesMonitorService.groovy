package com.asaas.service.monitor

import com.asaas.log.AsaasLogger
import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class MySQLQueriesMonitorService {

    def sessionFactory

    public Boolean runningLongerThanMinutes(Integer minutes) {
        if (!minutes || minutes <= 0) {
            throw new IllegalArgumentException("Valor inválido para o parâmetro minutes (${minutes}). Informe um valor maior que zero(0)")
        }

        Integer seconds = minutes * 60

        AsaasLogger.info("Checando consultas sendo executadas a mais de ${minutes} minuto(s)")
        String query = "select 1 from information_schema.processlist where command = 'Query' and state = 'executing' and time >= :seconds limit 1"

        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(query)
        sqlQuery.setInteger("seconds", seconds)

        return !sqlQuery.list().empty
    }
}

package com.asaas.service.integration.cerc.batch.receivableunitconciliation

import com.asaas.domain.integration.cerc.conciliation.receivableunit.ActiveReceivableUnitSnapshot
import com.asaas.integration.cerc.enums.CercProcessingStatus
import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class ActiveReceivableUnitSnapshotService {

    def sessionFactory

    public Boolean generate(Date snapshotDate) {
        Boolean todaysSnapshotAlreadyExists = ActiveReceivableUnitSnapshot.query([snapshotDate: snapshotDate.clearTime(), exists: true]).get().asBoolean()
        if (todaysSnapshotAlreadyExists) return false

        save(snapshotDate)
        updateNotSettledContractualEffectValue()

        return true
    }

    public void deleteAll() {
        ActiveReceivableUnitSnapshot.executeUpdate("delete from ActiveReceivableUnitSnapshot")
    }

    private void save(Date snapshotDate) {
        String sql = "INSERT INTO active_receivable_unit_snapshot (version, receivable_unit_id, payment_arrangement, external_identifier, total_value, net_value, snapshot_date)" +
            "SELECT 0, ru.id, ru.payment_arrangement, ru.external_identifier, ru.value, ru.net_value, :snapshotDate FROM receivable_unit ru WHERE ru.deleted = false and ru.operation_type = 'UPDATE' and ru.status = 'PROCESSED'"

        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(sql)
        sqlQuery.setTimestamp("snapshotDate", snapshotDate.clearTime())
        sqlQuery.executeUpdate()
    }

    private void updateNotSettledContractualEffectValue() {
        String sql = """
                        UPDATE active_receivable_unit_snapshot arus
                        SET arus.not_settled_contractual_effect_value = (
                          SELECT COALESCE(SUM(value), 0)
                          FROM cerc_contractual_effect cce
                          WHERE cce.affected_receivable_unit_id = arus.receivable_unit_id
                          AND cce.deleted = false
                          AND cce.status = :status
                        )
                    """

        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(sql)
        sqlQuery.setString("status", CercProcessingStatus.PROCESSED.toString())
        sqlQuery.executeUpdate()
    }
}

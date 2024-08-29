package com.asaas.service.internaluser

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.user.Role
import com.asaas.userpermission.RoleAuthority
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.hibernate.impl.SQLQueryImpl

@Transactional
class DeveloperActionPermissionReportService {

    def reportFileService
    def sessionFactory

    public AsaasFile export() {
        final String fileName = "relatorio-de-permissao-de-desenvolvedor-por-usuario-administrativo-${new Date().format('ddMMyyyy-HHmm')}"
        final List<String> headerList = [
            Utils.getMessageProperty("user.id.label"),
            Utils.getMessageProperty("user.name.label"),
            Utils.getMessageProperty("email.label"),
            "Permissões de desenvolvedor"
        ]

        List<List> rowList = buildRowList()
        return reportFileService.createCsvFile(fileName, headerList, rowList)
    }

    private List<List> buildRowList() {
        final String sql = """select user.id , user.name, user.username, developer_action_permission.permission from user
                              inner join developer_action_permission on user.id = developer_action_permission.user_id
                              where user.account_locked = false
                              and user.enabled = true
                              and user.deleted = false
                              and exists (select 1 from user_role where user_id = user.id and role_id = :sysAdminRoleId)
                              order by user.id asc"""

        Long sysAdminRoleId = Role.query([column: "id", authority: RoleAuthority.ROLE_SYSADMIN.toString()]).get()
        SQLQueryImpl sqlQuery = sessionFactory.currentSession.createSQLQuery(sql)
        sqlQuery.setLong("sysAdminRoleId", sysAdminRoleId)

        List<List> rowList = sqlQuery.list()
        return rowList
    }
}

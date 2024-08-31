package com.asaas.service.internaluser

import com.asaas.domain.file.AsaasFile
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.userpermission.DeveloperActionPermissionName
import com.asaas.userpermission.RoleAuthority
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class InternalUserPermissionReportService {

    def reportFileService

    public AsaasFile export() {
        final String fileName = "relatorio-de-permissoes-e-autorizacoes-${new Date().format('ddMMyyyy-HHmm')}"
        final List<String> headerList = ["Perfis de acesso", "Tradução"]
        List<List> rowList = buildRowList()

        return reportFileService.createCsvFile(fileName, headerList, rowList)
    }

    private List<List> buildRowList() {
        final List emptyLine = []
        List<List> rowList = []

        rowList.addAll(convertListToLines(RoleAuthority.values(), "Role"))
        rowList.add(emptyLine)

        final List<String> adminUserPermissionNameHeaderList = ["Permissões de acesso", "Tradução"]
        rowList.add(adminUserPermissionNameHeaderList)
        rowList.addAll(convertListToLines(AdminUserPermissionName.values(), "AdminUserPermissionName"))
        rowList.add(emptyLine)

        final List<String> developerActionPermissionNameHeaderList = ["Permissões de desenvolvedor", "Tradução"]
        rowList.add(developerActionPermissionNameHeaderList)
        rowList.addAll(convertListToLines(DeveloperActionPermissionName.values(), "DeveloperActionPermissionName"))

        return rowList
    }

    private <PermissionName> List<List> convertListToLines(PermissionName[] permissionList, String messagePropertyPrefix) {
        List<List> rowList = []
        for (PermissionName permission : permissionList) {
            rowList.add([permission, Utils.getMessageProperty("${messagePropertyPrefix}.${permission.toString()}")])
        }

        return rowList
    }
}

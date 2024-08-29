package com.asaas.userrolepermission

import grails.plugin.springsecurity.SpringSecurityUtils
import grails.transaction.Transactional

@Transactional
class UserRolePermissionService {

    def grailsApplication

    public Boolean canConfirmPayment() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.payment.confirm.join(","))
    }

    public Boolean canGenerateTransferBatchFile() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.transferbatchfile.create.join(","))
    }

    public Boolean canCancelTransferBatchFile() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.transferbatchfile.cancel.join(","))
    }

    public Boolean canDoTransferRiskAuthorization() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.transfer.authorize.join(","))
    }

    public Boolean canUploadReturnTransferBatchFile() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.transferbatchfile.returnfile.upload.join(","))
    }

    public Boolean canApproveOrDenyUpdateRequest() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.updaterequest.approve.join(","))
    }

    public Boolean canAnalyzeDocuments() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.documentanalysis.analyze.join(","))
    }

    public Boolean canSaveAnticipationObservations() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.saveAnticipationObservations.join(","))
    }

    public Boolean canSetFeeForAllChildAccounts() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.feeAdmin.setFeeForChildAccount.join(","))
    }

    public Boolean canUpdateCustomerTransferConfig() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.customerTransferConfig.manage.join(","))
    }

    public Boolean canUpdateEstimatedDebitDate() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.transfer.updateEstimatedDebitDate.join(","))
    }

    public Boolean canConfirmAsaasCardRechargeBatch() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.asaascardbatch.confirm.join(","))
    }

    public Boolean canConfirmReceivableAnticipationPartnerSettlementBatch() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.receivableAnticipationPartnerSettlementBatch.confirm.join(","))
    }

    public Boolean canUpdateAnticipationLimit() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.creditAnalyst.join(","))
    }

    public Boolean canEditPaymentFloat() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.editPaymentFloat.join(","))
    }

    public Boolean canCancelAsaasCardRecharge() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.asaasCardRecharge.cancel.join(","))
    }

    public Boolean canUpdateRevenueServiceRegister() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.revenueServiceRegister.update.join(","))
    }

    public Boolean canUpdateCustomerFreePayment() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.customerFreePayment.manage.join(","))
    }

    public boolean canAccessAccountingApplication() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.accountingApplication.join(","))
    }

    public Boolean canUpdatePixLimit() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.role.permission.preventionAnalyst.join(","))
    }

    public Boolean canManageInternalUser() {
        return SpringSecurityUtils.ifAnyGranted('ROLE_INFORMATION_TECHNOLOGY_INFRASTRUCTURE')
    }

    public Boolean canReadInternalUserList() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.adminModule.internalUserConsole.read.join(","))
    }

    public Boolean canReadAccountManagersList() {
        return SpringSecurityUtils.ifAnyGranted(grailsApplication.config.asaas.adminModule.accountManagerConsole.execute.join(","))
    }
}

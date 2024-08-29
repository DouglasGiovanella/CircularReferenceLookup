package com.asaas.service.asyncaction

import com.asaas.domain.asyncAction.CreateAsaasUserOnNexinvoiceAsyncAction
import com.asaas.domain.user.User
import com.asaas.nexinvoice.adapter.NexinvoiceUserCreateAdapter
import com.asaas.userpermission.RoleAuthority
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CreateAsaasUserOnNexinvoiceAsyncActionService {

    def nexinvoiceCustomerConfigCacheService
    def nexinvoiceCustomerManagerService
    def baseAsyncActionService
    def nexinvoiceUserConfigService

    public void saveIfNecessary(User user) {
        if (!isCustomerAlreadyIntegrated(user.customerId)) return
        if (!isAllowedToIntegrateWithNexinvoice(user)) return

        final Map asyncActionData = [
            userId: user.id,
        ]

        nexinvoiceUserConfigService.save(user)
        baseAsyncActionService.save(new CreateAsaasUserOnNexinvoiceAsyncAction(), asyncActionData)
    }

    public Boolean process() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(CreateAsaasUserOnNexinvoiceAsyncAction, [:], maxItemsPerCycle)
        if (!asyncActionDataList) return false

        baseAsyncActionService.processListWithNewTransaction(CreateAsaasUserOnNexinvoiceAsyncAction, asyncActionDataList, { Map asyncActionData ->
            User user = User.read(Utils.toLong(asyncActionData.userId))
            String nexinvoiceCustomerConfigPublicId = nexinvoiceCustomerConfigCacheService.byCustomerId(user.customerId).publicId
            String jwtToken = nexinvoiceCustomerManagerService.getJwtToken(user.customer.email, nexinvoiceCustomerConfigPublicId)

            NexinvoiceUserCreateAdapter nexinvoiceUserCreateAdapter = new NexinvoiceUserCreateAdapter(user, nexinvoiceCustomerConfigPublicId)
            String externalId = nexinvoiceCustomerManagerService.addNewUser(nexinvoiceUserCreateAdapter, jwtToken)
            nexinvoiceUserConfigService.update(user, externalId, true)
        }, [
            logErrorMessage: "CreateAsaasUserOnNexinvoiceAsyncActionService.process >> Não foi possível processar a ação assíncrona [${asyncActionDataList}]",
        ])

        return true
    }

    private Boolean isCustomerAlreadyIntegrated(Long customerId) {
        return nexinvoiceCustomerConfigCacheService.byCustomerId(customerId).isIntegrated.asBoolean()
    }

    private isAllowedToIntegrateWithNexinvoice(User user) {
        List<RoleAuthority> userRoleList = user.listRoleAuthority()
        List<RoleAuthority> enabledUserRoleList = [RoleAuthority.ROLE_ADMIN, RoleAuthority.ROLE_USER_FINANCIAL]

        return userRoleList.any { it in enabledUserRoleList }
    }
}

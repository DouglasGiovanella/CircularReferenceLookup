package com.asaas.service.asaaserp

import com.asaas.asyncaction.AsyncActionType
import com.asaas.cache.asaaserpcustomerconfig.AsaasErpCustomerConfigCacheVO
import com.asaas.domain.api.UserApiKey
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.domain.user.User
import com.asaas.userpermission.RoleAuthority
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class AsaasErpUserAsyncActionService {

    def asyncActionService
    def asaasErpCustomerConfigCacheService

    public RoleAuthority getUserAuthorityWithHighestPrivilege(User user) {
        List<RoleAuthority> userRoleList = user.listRoleAuthority()

        if (userRoleList.any { it == RoleAuthority.ROLE_ADMIN }) return RoleAuthority.ROLE_ADMIN
        if (userRoleList.any { it == RoleAuthority.ROLE_USER_FINANCIAL }) return RoleAuthority.ROLE_USER_FINANCIAL
        if (userRoleList.any { it == RoleAuthority.ROLE_USER_COMMERCIAL }) return RoleAuthority.ROLE_USER_COMMERCIAL

        return null
    }

    public void saveIfNecessary(User user, String authority) {
        if (!isCustomerIntegratedWithAsaasErp(user.customerId)) return

        save(user, authority)
    }

    public void updateIfNecessary(User user, Map updatedProperties) {
        if (!isCustomerIntegratedWithAsaasErp(user.customerId)) return

        final Boolean hasChangedUserPertinentPropertyToSynchronizeInAsaasErp = updatedProperties.containsKey("name") || updatedProperties.containsKey("username") || updatedProperties.containsKey("authority")
        if (!hasChangedUserPertinentPropertyToSynchronizeInAsaasErp) return

        update(user, updatedProperties.authority)
    }

    public void deleteIfNecessary(User user) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(user.customerId)
        if (!asaasErpCustomerConfigCacheVO.id) return

        delete(user, asaasErpCustomerConfigCacheVO.id)
    }

    public saveForNotIntegrated(Long customerId) {
        AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customerId)
        if (!asaasErpCustomerConfigCacheVO.id) return

        List<Long> userIdList = User.query([column: "id", customerId: customerId, "role[notExists]": RoleAuthority.ROLE_ASAAS_ERP_APPLICATION.toString()]).list()
        final Integer flushEvery = 50

        Utils.forEachWithFlushSession(userIdList, flushEvery, { Long userId ->
            Utils.withNewTransactionAndRollbackOnError({
                User user = User.read(userId)

                Boolean userHasApiKey = UserApiKey.query([exists: true, user: user, device: AsaasErpCustomerConfig.ASAAS_ERP_DEVICE]).get().asBoolean()
                if (userHasApiKey) throw new RuntimeException("AsaasErpUserAsyncActionService.saveForNotIntegrated >> Usuário[${user.id}] já possui chave de api vinculada.")

                String userAuthority = getUserAuthorityWithHighestPrivilege(user)
                if (!userAuthority) throw new RuntimeException("AsaasErpUserAsyncActionService.saveForNotIntegrated >> Nenhuma role válida encontrada para o usuário[${user.id}]")

                save(user, userAuthority.toString())
            }, [logErrorMessage: "AsaasErpUserAsyncActionService.saveForNotIntegrated >> Ocorreu um erro ao registrar usuário[${userId}] para ser integrado ao Asaas ERP"])
        })
    }

    public void save(User user, String authority) {
        final Map asyncActionData = [
            id: user.id,
            name: user.name,
            username: user.username,
            authorityAsaas: authority
        ]

        asyncActionService.save(AsyncActionType.SEND_CREATE_ASAAS_USER_TO_ASAAS_ERP, asyncActionData)
    }

    private void update(User user, String authority) {
        Map asyncActionData = [
            id: user.id,
            name: user.name,
            username: user.username
        ]

        if (authority) asyncActionData.authorityAsaas = authority

        asyncActionService.save(AsyncActionType.SEND_UPDATE_ASAAS_USER_TO_ASAAS_ERP, asyncActionData)
    }

    private void delete(User user, Long asaasErpCustomerConfigId) {
        final Map asyncActionData = [id: user.id, asaasErpCustomerConfigId: asaasErpCustomerConfigId]

        asyncActionService.save(AsyncActionType.SEND_DELETE_ASAAS_USER_TO_ASAAS_ERP, asyncActionData)
    }

    private Boolean isCustomerIntegratedWithAsaasErp(Long customerId) {
        final AsaasErpCustomerConfigCacheVO asaasErpCustomerConfigCacheVO = asaasErpCustomerConfigCacheService.getInstance(customerId)

        return asaasErpCustomerConfigCacheVO.id.asBoolean()
    }
}

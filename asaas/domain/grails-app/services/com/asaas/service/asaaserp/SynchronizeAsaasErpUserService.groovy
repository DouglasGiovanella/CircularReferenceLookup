package com.asaas.service.asaaserp

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.api.UserApiKey
import com.asaas.domain.asaaserp.AsaasErpCustomerConfig
import com.asaas.domain.asaaserp.AsaasErpUserConfig
import com.asaas.domain.asyncAction.AsyncAction
import com.asaas.domain.user.User
import com.asaas.integration.asaaserp.adapter.user.AsaasErpCreateUserAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class SynchronizeAsaasErpUserService {

    def asaasErpUserConfigService
    def asaasErpUserManagerService
    def asyncActionService
    def userApiKeyService

    public void process() {
        final Integer maxItemsPerCycle = 50
        final List<AsyncActionType> asaasErpUserIntegrationTypeList = [AsyncActionType.SEND_CREATE_ASAAS_USER_TO_ASAAS_ERP, AsyncActionType.SEND_UPDATE_ASAAS_USER_TO_ASAAS_ERP, AsyncActionType.SEND_DELETE_ASAAS_USER_TO_ASAAS_ERP]
        List<AsyncAction> asyncActionList = AsyncAction.oldestPending(["type[in]": asaasErpUserIntegrationTypeList]).list(max: maxItemsPerCycle)

        for (AsyncAction asyncAction : asyncActionList) {
            AsyncActionType asyncActionType = asyncAction.type
            Map asyncActionData = asyncAction.getDataAsMap()

            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                switch (asyncActionType) {
                    case AsyncActionType.SEND_CREATE_ASAAS_USER_TO_ASAAS_ERP:
                        create(asyncActionData)
                        break
                    case AsyncActionType.SEND_UPDATE_ASAAS_USER_TO_ASAAS_ERP:
                        update(asyncActionData)
                        break
                    case AsyncActionType.SEND_DELETE_ASAAS_USER_TO_ASAAS_ERP:
                        delete(asyncActionData)
                        break
                    default:
                        throw new RuntimeException("Evento de sincronização de usuários inválido")
                }

                asyncActionService.setAsDone(asyncActionData.asyncActionId)
            }, [logErrorMessage: "SynchronizeAsaasErpUserService.process >> Erro na sincronização do usuário no Asaas ERP (${asyncActionType}) [Dados: ${asyncActionData}]",
                onError: { hasError = true }]
            )

            if (hasError) {
                AsyncActionStatus asyncActionStatus = asyncActionService.sendToReprocessIfPossible(asyncAction.id).status
                if (asyncActionStatus.isCancelled()) {
                    AsaasLogger.error("SynchronizeAsaasErpUserService.process >> Processo de sincronização do usuário com o Asaas ERP foi cancelado por ultrapassar o máximo de tentativas. [asyncActionId: ${asyncAction.id}]")
                }
            }
        }
    }

    private void create(Map asyncActionData) {
        User user = User.read(Utils.toLong(asyncActionData.id))

        Boolean customerHasUserApiKey = UserApiKey.query([exists: true, user: user, device: AsaasErpCustomerConfig.ASAAS_ERP_DEVICE]).get().asBoolean()
        if (customerHasUserApiKey) throw new RuntimeException("SynchronizeAsaasErpUserService.create >> Usuário [ID: ${user.id}] já possui chave de API para integração com Asaas ERP.")

        final String userAccessToken = userApiKeyService.generateAccessToken(user, AsaasErpCustomerConfig.ASAAS_ERP_DEVICE)

        AsaasErpCreateUserAdapter asaasErpCreateUserAdapter = asaasErpUserManagerService.create(user, userAccessToken, asyncActionData)
        asaasErpUserConfigService.save(user, asaasErpCreateUserAdapter.id, asaasErpCreateUserAdapter.tokenOriginal)
    }

    private void update(Map asyncActionData) {
        User user = User.read(Utils.toLong(asyncActionData.id))
        asaasErpUserManagerService.update(user, asyncActionData)
    }

    private void delete(Map asyncActionData) {
        User user = User.query([id: Utils.toLong(asyncActionData.id), ignoreCustomer: true, includeDeleted: true]).get()
        asaasErpUserManagerService.delete(user, Utils.toLong(asyncActionData.asaasErpCustomerConfigId))
        AsaasErpUserConfig asaasErpUserConfig = AsaasErpUserConfig.query([user: user]).get()
        asaasErpUserConfigService.delete(asaasErpUserConfig)
    }
}

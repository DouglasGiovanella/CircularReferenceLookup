package com.asaas.service.user

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.user.User
import com.asaas.domain.user.UserSecurityStage
import com.asaas.user.adapter.UserAdditionalInfoAdapter
import com.asaas.usersecuritylevel.UserSecurityLevel
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class UserSecurityStageService {

    def asyncActionService
    def userAdditionalInfoService

    public void saveUpdateUserSecurityStageAsyncAction(Long userId) {
        Map asyncActionData = [:]
        asyncActionData.userId = userId

        AsyncActionType asyncActionType = AsyncActionType.UPDATE_USER_SECURITY_STAGE

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    public void processUpdateUserSecurityStage() {
        final Integer maxUpdatesToProcess = 100
        List<Long> updateUserSecurityStageForDeleteIdList = []

        for (Map asyncActionData : asyncActionService.listPending(AsyncActionType.UPDATE_USER_SECURITY_STAGE, maxUpdatesToProcess)) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                Long userId = Long.valueOf(asyncActionData.userId)
                update(userId)
            }, [logErrorMessage: "UserSecurityStageService.processUpdateUserSecurityStage > Erro ao atualizar UserSecurityStage. AsyncActionId: ${asyncActionData.asyncActionId}",
                onError: {
                    asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
                    hasError = true
                }
            ])

            if (!hasError) updateUserSecurityStageForDeleteIdList.add(asyncActionData.asyncActionId)
        }

        asyncActionService.deleteList(updateUserSecurityStageForDeleteIdList)
    }

    public Map getUserSecurityLevelData(User user) {
        Map userSecurityLevelData = [:]
        UserSecurityLevel userSecurityLevel = UserSecurityStage.query([column: "level", user: user]).get()

        if (userSecurityLevel) {
            userSecurityLevelData = userSecurityLevel.buildLevelData()
        } else {
            userSecurityLevelData = UserSecurityLevel.getFirstLevel().buildLevelData()
        }

        return userSecurityLevelData
    }

    public Boolean isLastLevel(User user) {
        return user.getSecurityLevel().isLastLevel()
    }

    private void update(Long userId) {
        UserAdditionalInfoAdapter userAdditionalInfo = userAdditionalInfoService.find(userId)
        User user = User.read(userId)
        UserSecurityStage currentUserSecurityStage = UserSecurityStage.query([user: user]).get()

        if (!currentUserSecurityStage) {
            currentUserSecurityStage = new UserSecurityStage()
            currentUserSecurityStage.user = user
        }

        UserSecurityLevel levelBasedOnLastUserDataUpdate = UserSecurityLevel.HAS_BASIC_DATA

        if (userHasCompleteAdditionalInfo(userAdditionalInfo)) {
            levelBasedOnLastUserDataUpdate = UserSecurityLevel.HAS_COMPLETE_ADDITIONAL_INFO

            if (userHasCompleteDocumentSent(userAdditionalInfo)) {
                levelBasedOnLastUserDataUpdate = UserSecurityLevel.HAS_COMPLETE_DOCUMENT_SENT
            }
        }

        currentUserSecurityStage.level = levelBasedOnLastUserDataUpdate
        currentUserSecurityStage.save(failOnError: true)
    }

    private Boolean userHasCompleteAdditionalInfo(UserAdditionalInfoAdapter userAdditionalInfo) {
        final List<String> userAdditionalInfoRequiredProperties = ["cpf", "birthDate", "address", "addressNumber", "province",
                                                                   "city", "postalCode", "isPoliticallyExposedPerson",
                                                                   "incomeRange", "disabilityType"]

        for (String property : userAdditionalInfoRequiredProperties) {
            if (Utils.isEmptyOrNull(userAdditionalInfo[property])) return false
        }

        return true
    }

    private Boolean userHasCompleteDocumentSent(UserAdditionalInfoAdapter userAdditionalInfoAdapter) {
        if (!userAdditionalInfoAdapter.identificationUserDocument) return false
        if (!userAdditionalInfoAdapter.selfieUserDocument) return false

        return true
    }
}

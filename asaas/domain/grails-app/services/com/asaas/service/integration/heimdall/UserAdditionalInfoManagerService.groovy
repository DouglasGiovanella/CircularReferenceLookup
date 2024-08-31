package com.asaas.service.integration.heimdall

import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.exception.UserAdditionalInfoException
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.user.UserAdditionalInfoDTO
import com.asaas.integration.heimdall.dto.user.userdocument.save.HeimdallSaveUserDocumentListRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.user.adapter.UserAdditionalInfoAdapter
import com.asaas.user.adapter.UserDocumentAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import com.asaas.utils.Utils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class UserAdditionalInfoManagerService {

    public UserAdditionalInfoAdapter save(UserAdditionalInfoAdapter userAdditionalInfoAdapter) {
        if (!AsaasEnvironment.isProduction()) {
            UserAdditionalInfoDTO userAdditionalInfoDTO = new MockJsonUtils("heimdall/UserAdditionalInfoManagerService/save.json").buildMock(UserAdditionalInfoDTO)
            return new UserAdditionalInfoAdapter(userAdditionalInfoDTO)
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.post("/users/${userAdditionalInfoAdapter.userId}", new UserAdditionalInfoDTO(userAdditionalInfoAdapter))

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                AsaasLogger.warn("UserAdditionalInfoManagerService.save > Heimdall não encontrou o UserAdditionalInfo do UserId [${userAdditionalInfoAdapter.userId}].")
                return null
            } else if (heimdallManager.isUnprocessableEntity()) {
                throw new BusinessException(heimdallManager.getErrorMessages().first())
            } else {
                heimdallManager.generateAlertLogIfNecessary()
                throw new UserAdditionalInfoException(Utils.getMessageProperty("user.userAdditionalInfo.unavailable"))
            }
        }

        String responseBodyJson = (heimdallManager.responseBody as JSON).toString()
        UserAdditionalInfoDTO userAdditionalInfoDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, UserAdditionalInfoDTO)
        return new UserAdditionalInfoAdapter(userAdditionalInfoDTO)
    }

    public UserAdditionalInfoAdapter get(Long userId) {
        if (!AsaasEnvironment.isProduction()) {
            UserAdditionalInfoDTO userAdditionalInfoDTO = new MockJsonUtils("heimdall/UserAdditionalInfoManagerService/get.json").buildMock(UserAdditionalInfoDTO)
            return new  UserAdditionalInfoAdapter(userAdditionalInfoDTO)
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get("/users/${userId}", [:])

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                AsaasLogger.warn("UserAdditionalInfoManagerService.get > Heimdall não encontrou o UserAdditionalInfo do UserId [${userId}].")
                return null
            } else if (heimdallManager.isUnprocessableEntity()) {
                throw new BusinessException(heimdallManager.getErrorMessages().first())
            } else {
                heimdallManager.generateAlertLogIfNecessary()
                throw new UserAdditionalInfoException(Utils.getMessageProperty("user.userAdditionalInfo.unavailable"))
            }
        }

        String responseBodyJson = (heimdallManager.responseBody as JSON).toString()
        UserAdditionalInfoDTO userAdditionalInfoDTO = GsonBuilderUtils.buildClassFromJson(responseBodyJson, UserAdditionalInfoDTO)
        return new UserAdditionalInfoAdapter(userAdditionalInfoDTO)
    }

    public Boolean delete(Long userId) {
        if (!AsaasEnvironment.isProduction()) {
            return true
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.delete("/users/${userId}", [:])

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                AsaasLogger.warn("UserAdditionalInfoManagerService.delete > Heimdall não encontrou o UserAdditionalInfo do UserId [${userId}].")
            } else {
                heimdallManager.generateAlertLogIfNecessary()
            }
            return false
        }
        return true
    }

    public void saveDocumentList(User user, List<UserDocumentAdapter> userDocumentAdapterList) {
        if (!AsaasEnvironment.isProduction()) {
            return
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.post("/userDocuments/saveList/${user.id}", new HeimdallSaveUserDocumentListRequestDTO(userDocumentAdapterList))

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                throw new BusinessException("Documentação de usuário não encontrada")
            } else if (heimdallManager.isUnprocessableEntity()) {
                throw new BusinessException(heimdallManager.getErrorMessages().first())
            } else {
                heimdallManager.generateAlertLogIfNecessary()
                throw new UserAdditionalInfoException(Utils.getMessageProperty("user.userAdditionalInfo.unavailable"))
            }
        }
    }

    private HeimdallManager buildHeimdallManager() {
        final Integer timeout = 10000

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        heimdallManager.setTimeout(timeout)

        return heimdallManager
    }
}

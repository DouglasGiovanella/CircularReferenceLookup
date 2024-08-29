package com.asaas.service.integration.heimdall

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class UserFacematchValidationManagerService {

    public Boolean canUserUseFacematch(Long userId) {
        if (!AsaasEnvironment.isProduction()) return false

        final String path = "/users/$userId/facematchValidations/isUserEnabledForFacematch"
        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get(path, [:])

        if (!heimdallManager.isSuccessful()) {
            AsaasLogger.error("UserFacematchValidationManagerService.canUserUseFacematch >> Heimdall retornou um status diferente de sucesso ao consultar se usuário é elegível ao FacematchValidation. StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}] ")
            return false
        }

        Boolean canUserUseFacematch = heimdallManager.responseBody.isUserEnabledForFacematch?.asBoolean()
        return canUserUseFacematch
    }

    public List<Long> buildUserIdListEnabledForFacematch(List<Long> userIdList) {
        if (!AsaasEnvironment.isProduction()) return []

        final String path = "/users/facematchValidations/getUserIdListEnabledForFacematch"
        Map requestParams = [:]
        requestParams.userIdList = ""
        for (Long userId : userIdList) {
            requestParams.userIdList += "${userId},"
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get(path, requestParams)

        if (!heimdallManager.isSuccessful()) {
            AsaasLogger.error("UserFacematchValidationManagerService.getUserIdListEnabledForFacematch >> Heimdall retornou um status diferente de sucesso ao consultar se lista de usuários é elegível ao FacematchValidation. StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}] ")
            throw new RuntimeException("Falha ao verificar elegibilidade de lista usuários ao facematch.")
        }

        List<Long> userIdListEnabledForFacematch = heimdallManager.responseBody.userIdListEnabledForFacematch.collect { Long.valueOf(it as String) }
        return userIdListEnabledForFacematch
    }

    private HeimdallManager buildHeimdallManager() {
        final Integer timeout = 10000

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        heimdallManager.setTimeout(timeout)

        return heimdallManager
    }
}

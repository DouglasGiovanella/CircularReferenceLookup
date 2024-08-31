package com.asaas.service.integration.heimdall

import com.asaas.exception.BusinessException
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.user.userselfie.update.HeimdallUpdateUserBestSelfieRequestDTO
import com.asaas.user.adapter.UserSelfieAdapter
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class UserSelfieManagerService {

    public void updateBestSelfie(Long userId, UserSelfieAdapter userSelfieAdapter) {
        final String path = "/users/$userId/selfies/bestSelfie"

        HeimdallUpdateUserBestSelfieRequestDTO requestDTO = new HeimdallUpdateUserBestSelfieRequestDTO(userSelfieAdapter)

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.put(path, requestDTO)

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                throw new BusinessException("Selfie não foi encontrada")
            } else if (heimdallManager.isUnprocessableEntity()) {
                throw new BusinessException(heimdallManager.getErrorMessages().first())
            } else {
                heimdallManager.generateAlertLogIfNecessary()
                throw new RuntimeException("UserAdditionalInfoManagerService.updateBestSelfie >> erro ao tentar atualizar bestSelfie. UserId [${userId}]")
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

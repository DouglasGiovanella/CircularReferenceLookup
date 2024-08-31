package com.asaas.service.integration.heimdall

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.heimdall.HeimdallManager
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class HeimdallAccountDocumentMigrationManagerService {

    public Boolean exists(Long customerId) {
        if (!AsaasEnvironment.isProduction()) return false

        final String path = "/accounts/${customerId}/documentMigrations/exists"
        HeimdallManager heimdallManager = buildHeimdallManager()

        heimdallManager.get(path, [:])

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentMigrationManagerService.exists >> Erro ao verificar se cliente tem documento migrado. Customer [${customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
        }

        Boolean hasDocumentMigration = heimdallManager.responseBody.exists?.asBoolean()
        return hasDocumentMigration
    }

    public void toggle(Long customerId, Boolean enabled) {
        if (!AsaasEnvironment.isProduction()) return

        final String path = "/accounts/${customerId}/documentMigrations"
        HeimdallManager heimdallManager = buildHeimdallManager()

        heimdallManager.post(path, [enabled: enabled])

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("HeimdallAccountDocumentMigrationManagerService.toggle >> Erro ao alterar registro de documento migrado. Customer [${customerId}] StatusCode: [${heimdallManager.statusCode}], ResponseBody: [${heimdallManager.responseBody}]")
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

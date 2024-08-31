package com.asaas.service.integration.sauron

import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sauron.api.SauronManager
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class FraudTrackingAccountManagerService {

    public void save(Long customerId) {
        if (!AsaasEnvironment.isProduction()) return

        SauronManager sauronManager = buildSauronManager()
        sauronManager.post("/fraudTrackingAccount/save", [accountId: customerId])

        if (!sauronManager.isSuccessful()) {
            throw new RuntimeException("FraudTrackingAccountManagerService.save >> Ocorreu um erro no Sauron ao tentar salvar a conta [${customerId}] para monitoramento. ResponseBody: [${sauronManager.responseBody}]")
        }
    }

    public void updateListByIdRange(Long firstId, Long lastId) {
        SauronManager sauronManager = buildSauronManager()
        sauronManager.post("/fraudTrackingAccount/updateListByIdRange", [firstId: firstId, lastId: lastId])

        if (!sauronManager.isSuccessful()) {
            throw new RuntimeException("FraudTrackingAccountId range: [${firstId}] - [${lastId}]. ResponseBody: [${sauronManager.responseBody}]")
        }
    }

    private SauronManager buildSauronManager() {
        final Integer timeout = 10000

        SauronManager sauronManager = new SauronManager()
        sauronManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        sauronManager.setTimeout(timeout)

        return sauronManager
    }
}

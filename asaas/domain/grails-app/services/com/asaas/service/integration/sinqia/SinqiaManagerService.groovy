package com.asaas.service.integration.sinqia

import com.asaas.domain.integration.sinqia.SinqiaFinancialStatement
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sinqia.api.SinqiaManager
import com.asaas.integration.sinqia.dto.financialmovement.FinancialMovementDTO
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class SinqiaManagerService {

    private static final String FINANCIAL_MOVEMENT_INSERT_PATH = "/BJ14M03/TE/BJ14SS0101A/contasaPagarReceber"

    def grailsApplication

    public Map syncFinancialMovement(SinqiaFinancialStatement sinqiaFinancialStatement) {
        if (!AsaasEnvironment.isProduction()) return

        FinancialMovementDTO financialMovementDTO = new FinancialMovementDTO(sinqiaFinancialStatement)

        SinqiaManager sinqiaManager = new SinqiaManager()
        sinqiaManager.post(SinqiaManagerService.FINANCIAL_MOVEMENT_INSERT_PATH, financialMovementDTO)

        if (!sinqiaManager.isSuccessful()) {
            AsaasLogger.warn("SinqiaManagerService.syncFinancialMovement >> Falha ao sincronizar registro [${sinqiaFinancialStatement.id}].\nStatus: [${sinqiaManager.statusCode}]\nResponseBody: [${sinqiaManager.responseBody}]")
            return [:]
        }

        return sinqiaManager.responseBody
    }
}

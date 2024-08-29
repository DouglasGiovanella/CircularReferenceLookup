package com.asaas.service.integration.sinqia

import com.asaas.domain.integration.sinqia.SinqiaFinancialStatement
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.sinqia.enums.SinqiaSyncStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class SinqiaSynchronizationService {

    def grailsApplication
    def sinqiaFinancialStatementService
    def sinqiaManagerService

    public void syncFinancialMovements() {
        if (!AsaasEnvironment.isProduction()) return

        final Integer limitItemsPerCycle = 400

        List<Long> sinqiaFinancialStatementIdList = SinqiaFinancialStatement.query([column: "id",
                                                                                    syncStatus: SinqiaSyncStatus.PENDING,
                                                                                    sort: "id",
                                                                                    order: "asc"]).list(max: limitItemsPerCycle)
        if (!sinqiaFinancialStatementIdList) return

        for (Long sinqiaFinancialStatementId in sinqiaFinancialStatementIdList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                SinqiaFinancialStatement sinqiaFinancialStatement = SinqiaFinancialStatement.get(sinqiaFinancialStatementId)

                Map response = sinqiaManagerService.syncFinancialMovement(sinqiaFinancialStatement)
                if (!response.externalId) throw new Exception("Número de documento da Sinqia não identificado na resposta [${response}]")

                sinqiaFinancialStatementService.setAsDone(sinqiaFinancialStatement, response.externalId.toString())
            }, [logErrorMessage: "SinqiaSynchronizationService.syncFinancialMovements >> Falha ao realizar a sincronização do registro [${sinqiaFinancialStatementId}]", onError: { hasError = true }])

            if (hasError) sinqiaFinancialStatementService.setAsErrorWithNewTransaction(sinqiaFinancialStatementId)
        }
    }
}

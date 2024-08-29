package com.asaas.service.integration.cerc

import com.asaas.domain.holiday.Holiday
import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.domain.integration.cerc.conciliation.contract.CercContractConciliationSummary
import com.asaas.domain.integration.cerc.conciliation.receivableunit.ReceivableUnitConciliationSummary
import com.asaas.domain.integration.cerc.contestation.CercContestation
import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffect
import com.asaas.domain.integration.cerc.optin.CercAsaasOptIn
import com.asaas.domain.integration.cerc.optin.CercAsaasOptOut
import com.asaas.exception.BusinessException
import com.asaas.integration.cerc.api.CercResponseAdapter
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.integration.cerc.enums.conciliation.ReceivableRegistrationConciliationOrigin
import com.asaas.integration.cerc.parser.CercParser
import com.asaas.log.AsaasLogger
import com.asaas.utils.ThreadUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.hibernate.StaleObjectStateException
import org.springframework.dao.CannotAcquireLockException
import org.springframework.orm.hibernate3.HibernateOptimisticLockingFailureException

@Transactional
class CercSynchronizationService {

    private final Integer LIMIT_ITEMS_PER_CYCLE = 150
    private final Integer LIMIT_OF_THREADS = 10

    def cercCompanyService
    def cercAsaasOptInService
    def cercAsaasOptOutService
    def cercContractConciliationService
    def cercFidcContractualEffectService
    def cercManagerService
    def receivableUnitConciliationService

    public void syncReceivableUnitConciliation() {
        Map search = [:]
        search.columnList = ["id", "conciliation.id"]
        search.conciliationSyncStatus = CercSyncStatus.AWAITING_SYNC
        search.origin = ReceivableRegistrationConciliationOrigin.ASAAS
        search.sort = "id"
        search.order = "asc"

        List<Map> summaryInfoList = ReceivableUnitConciliationSummary.query(search).list()

        for (Map summaryInfo : summaryInfoList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                ReceivableUnitConciliationSummary summary = ReceivableUnitConciliationSummary.read(summaryInfo.id)

                Boolean isSynced = cercManagerService.syncReceivableUnitConciliation(summary)
                if (!isSynced) throw new BusinessException("A requisição não retornou um status de sucesso")

                receivableUnitConciliationService.setAsSynced(summaryInfo."conciliation.id")
            }, [logErrorMessage: "CercSynchronizationService.syncReceivableUnitConciliation >> Falha ao sincronizar conciliação de recebíveis do resumo [${summaryInfo.id}]",
                onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    receivableUnitConciliationService.setAsError(summaryInfo."conciliation.id")
                }, [logErrorMessage: "CercSynchronizationService.syncReceivableUnitConciliation >> Falha ao marcar a sincronização da conciliação [${summaryInfo."conciliation.id"}] como erro"])
            }
        }
    }

    public void syncContractConciliation() {
        Map search = [:]
        search.columnList = ["id", "conciliation.id"]
        search.conciliationSyncStatus = CercSyncStatus.AWAITING_SYNC
        search.origin = ReceivableRegistrationConciliationOrigin.ASAAS
        search.sort = "id"
        search.order = "asc"

        List<Map> awaitingSyncAsaasSummaryInfoList = CercContractConciliationSummary.query(search).list()
        for (Map summaryInfo : awaitingSyncAsaasSummaryInfoList) {
            Boolean success = false
            Utils.withNewTransactionAndRollbackOnError({
                CercContractConciliationSummary asaasSummary = CercContractConciliationSummary.read(summaryInfo.id)
                Boolean isSynced = cercManagerService.syncContractConciliation(asaasSummary)
                if (!isSynced) throw new BusinessException("A requisição não retornou um status de sucesso")

                cercContractConciliationService.setAsSynced(summaryInfo."conciliation.id")
                success = true
            }, [logErrorMessage: "CercSynchronizationService.syncContractConciliation >> Falha ao realizar a conciliação de contratos com base no resumo [${summaryInfo.id}]"])

            if (!success) {
                Utils.withNewTransactionAndRollbackOnError({
                    cercContractConciliationService.setAsError(summaryInfo."conciliation.id")
                }, [logErrorMessage: "CercSynchronizationService.syncContractConciliation >> Não foi possível marcar a conciliação de contratos  [${summaryInfo."conciliation.id"}] com erro de sincronização"])
            }
        }
    }

    public void syncAsaasOptIn() {
        if (!cercManagerService.isApiAvailable()) return

        Map search = [:]
        search.column = "id"
        search.syncStatus = CercSyncStatus.AWAITING_SYNC
        search.sort = "id"
        search.order = "asc"

        List<Long> asaasOptInIdList = CercAsaasOptIn.query(search).list(max: LIMIT_ITEMS_PER_CYCLE)
        for (Long asaasOptInId in asaasOptInIdList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                CercAsaasOptIn asaasOptIn = CercAsaasOptIn.get(asaasOptInId)

                CercResponseAdapter response = cercManagerService.syncAsaasOptIn(asaasOptIn)
                if (!response.status.isSuccess()) {
                    String responseAdapterErrors = CercParser.parseErrorList(response.errorList)
                    throw new RuntimeException("A requisição não foi bem sucedida [${responseAdapterErrors}]")
                } else if (!response.protocol) {
                    throw new BusinessException("Número de protocolo não identificado na resposta [${response}]")
                }

                cercAsaasOptInService.setAsSynced(asaasOptIn, response.protocol)
            }, [logErrorMessage: "CercSynchronizationService.syncAsaasOptIn >> Falha ao sincronizar opt-in [${asaasOptInId}]",
                onError: { hasError = true }])

            if (hasError) cercAsaasOptInService.setAsErrorWithNewTransaction(asaasOptInId)
        }
    }

    public void syncAsaasOptOut() {
        if (!cercManagerService.isApiAvailable()) return

        Map search = [:]
        search.column = "id"
        search.syncStatus = CercSyncStatus.AWAITING_SYNC
        search.sort = "id"
        search.order = "asc"

        List<Long> asaasOptOutIdList = CercAsaasOptOut.query(search).list(max: LIMIT_ITEMS_PER_CYCLE)
        for (Long asaasOptOutId in asaasOptOutIdList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                CercAsaasOptOut asaasOptOut = CercAsaasOptOut.get(asaasOptOutId)

                CercResponseAdapter response = cercManagerService.syncAsaasOptOut(asaasOptOut)
                if (!response.status.isSuccess()) {
                    String responseAdapterErrors = CercParser.parseErrorList(response.errorList)
                    throw new RuntimeException("A requisição não foi bem sucedida [${responseAdapterErrors}]")
                } else if (!response.protocol) {
                    throw new BusinessException("Número de protocolo não identificado na resposta [${response}]")
                }

                cercAsaasOptOutService.setAsSynced(asaasOptOut, response.protocol)
            }, [logErrorMessage: "CercSynchronizationService.syncAsaasOptOut >> Falha ao sincronizar opt-out [${asaasOptOutId}]",
                onError: { hasError = true }])

            if (hasError) cercAsaasOptOutService.setAsErrorWithNewTransaction(asaasOptOutId)
        }
    }

    public void syncFidcContractualEffects() {
        if (Holiday.isHoliday(new Date())) return

        if (!cercManagerService.isApiAvailable()) return

        List<Long> fidcContractualEffectIdList = CercFidcContractualEffect.orderSyncQueue([column: "id", syncStatus: CercSyncStatus.AWAITING_SYNC]).list(max: LIMIT_ITEMS_PER_CYCLE)
        Utils.processWithThreads(fidcContractualEffectIdList, LIMIT_OF_THREADS, { List<Long> idList ->
            for (Long id in idList) {
                Boolean hasError = false
                Utils.withNewTransactionAndRollbackOnError({
                    CercFidcContractualEffect contractualEffect = CercFidcContractualEffect.get(id)
                    CercResponseAdapter syncResponse = cercManagerService.syncFidcContractualEffect(contractualEffect)

                    if (syncResponse.status.isTimeout()) {
                        cercFidcContractualEffectService.registerLastSync(contractualEffect)
                        return
                    }

                    if (syncResponse.status.isServerError()) {
                        cercFidcContractualEffectService.registerLastSync(contractualEffect)
                        verifyIfMaxAttemptsHasBeenReached(contractualEffect.syncAttempts)
                        return
                    }

                    if (!syncResponse.status.isSuccess()) throw new RuntimeException("A requisição não foi bem sucedida")

                    cercFidcContractualEffectService.setAsSynced(contractualEffect)
                }, [logErrorMessage: "CercSynchronizationService.syncFidcContractualEffects >> Falha ao sincronizar efeito de contrato do FIDC [${id}]",
                    onError: { hasError = true }])

                if (hasError) cercFidcContractualEffectService.setAsErrorWithNewTransaction(id)
            }
        })
    }

    public void syncCompany() {
        if (Holiday.isHoliday(new Date())) return

        if (!cercManagerService.isApiAvailable()) return

        final Integer maxItemsPerThread = 75
        final Integer flushEvery = 50
        List<Long> companyIdList = CercCompany.orderSyncQueue([column: "id", syncStatus: CercSyncStatus.AWAITING_SYNC]).list(max: LIMIT_ITEMS_PER_CYCLE)
        ThreadUtils.processWithThreadsOnDemand(companyIdList, maxItemsPerThread, { List<Long> subGroupCompanyIdList ->
            Utils.forEachWithFlushSession(subGroupCompanyIdList, flushEvery, { Long companyId ->
                Utils.withNewTransactionAndRollbackOnError({
                    CercCompany cercCompany = CercCompany.get(companyId)
                    CercResponseAdapter responseAdapter = cercManagerService.syncCompany(cercCompany, companyId.toString())

                    if (responseAdapter.status.isTimeout()) {
                        cercCompanyService.registerLastSync(cercCompany)
                        return
                    }

                    if (responseAdapter.status.isServerError()) {
                        cercCompanyService.registerLastSync(cercCompany)
                        verifyIfMaxAttemptsHasBeenReached(cercCompany.syncAttempts)
                        return
                    }

                    if (!responseAdapter.status.isSuccess()) {
                        cercCompanyService.processSynchronizationErrors(cercCompany, responseAdapter.errorList)
                        cercCompanyService.registerLastSync(cercCompany)
                        return
                    }

                    cercCompanyService.setAsSynced(cercCompany)
                }, [ignoreStackTrace: true,
                    onError: { Exception exception ->
                        if (exception instanceof HibernateOptimisticLockingFailureException
                            || exception instanceof CannotAcquireLockException
                            || exception instanceof StaleObjectStateException) return

                        AsaasLogger.error("CercSynchronizationService.syncCompany >> Falha ao sincronizar estabelecimento [${companyId}]", exception)
                        Utils.withNewTransactionAndRollbackOnError({
                            CercCompany cercCompany = CercCompany.get(companyId)
                            cercCompanyService.setAsError(cercCompany)
                        }, [logErrorMessage: "CercSynchronizationService.syncCompany >> Falha ao setar status ERROR no estabelecimento [${companyId}]"])
                    }
                ])
            })
        })
    }

    private void verifyIfMaxAttemptsHasBeenReached(Integer syncAttempts) {
        if (syncAttempts >= 3) throw new RuntimeException("Número máximo de tentativas de sincronização foi atingido.")
    }
}

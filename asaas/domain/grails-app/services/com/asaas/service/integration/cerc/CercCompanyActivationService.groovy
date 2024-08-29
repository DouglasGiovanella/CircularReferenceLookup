package com.asaas.service.integration.cerc

import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffect
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.integration.cerc.enums.company.CercCompanyStatus
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.log.AsaasLogger
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableregistration.receivableunit.ReceivableUnitStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercCompanyActivationService {

    def cercCompanyService
    def cercFidcContractualEffectService
    def receivableRegistrationEventQueueService

    public Boolean processActiveAndSuspendedCompaniesWithEstimatedActivationDateForToday() {
        Map search = [:]
        search.columnList = ["id", "cpfCnpj"]
        search.syncStatus = CercSyncStatus.SYNCED
        search.statusList = [CercCompanyStatus.ACTIVE, CercCompanyStatus.SUSPENDED]
        search."estimatedActivationDate[le]" = CustomDateUtils.setTimeToEndOfDay(new Date())
        search.disableSort = true

        List<Map> companyInfoList = CercCompany.query(search).list(max: 3000)
        if (!companyInfoList) return false

        final Integer flushEvery = 50
        Utils.forEachWithFlushSession(companyInfoList, flushEvery, { Map companyInfo ->
            try {
                processContractualEffectToAwaitingSync(companyInfo.cpfCnpj)
                sendReceivableUnitsAwaitingCompanyActivationToCalculateSettlements(companyInfo.cpfCnpj)
                cercCompanyService.updateEstimatedActivationDateToNull(CercCompany.get(companyInfo.id))
            } catch (Exception exception) {
                AsaasLogger.error("CercCompanyActivationService.processActiveCompaniesWithEstimatedActivationDateForToday >> Falha ao processar company para o cpfCnpj [${companyInfo.cpfCnpj}]", exception)
            }
        })

        return true
    }

    private void processContractualEffectToAwaitingSync(String cpfCnpj) {
        Map search = [
            column: "id",
            customerCpfCnpj: cpfCnpj,
            syncStatus: CercSyncStatus.AWAITING_COMPANY_ACTIVATE
        ]

        List<Long> contractualEffectIdList = CercFidcContractualEffect.query(search).list()
        for (Long contractualEffectId in contractualEffectIdList) {
            try {
                CercFidcContractualEffect contractualEffect = CercFidcContractualEffect.get(contractualEffectId)
                cercFidcContractualEffectService.setAsAwaitingSync(contractualEffect)
            } catch (Exception exception) {
                AsaasLogger.error("CercAwaitingCompanyActivateService.processContractualEffectToAwaitingSync >> Falha ao atualizar efeito de contrato para awaiting sync [${contractualEffectId}]", exception)
            }
        }
    }

    private void sendReceivableUnitsAwaitingCompanyActivationToCalculateSettlements(String cpfCnpj) {
        Map search = [
            column: "id",
            customerCpfCnpj: cpfCnpj,
            status: ReceivableUnitStatus.AWAITING_COMPANY_ACTIVATE,
            operationType: CercOperationType.CREATE,
            disableSort: true
        ]

        List<Long> receivableUnitIdList = ReceivableUnit.query(search).list()
        for (Long receivableUnitId : receivableUnitIdList) {
            try {
                Map eventInfo = [receivableUnitId: receivableUnitId]
                receivableRegistrationEventQueueService.save(ReceivableRegistrationEventQueueType.CALCULATE_RECEIVABLE_UNIT_SETTLEMENTS, eventInfo, eventInfo.encodeAsMD5())
            } catch (Exception exception) {
                AsaasLogger.error("CercAwaitingCompanyActivateService.processReceivableUnitToAwaitingSync >> Falha ao enviar UR para calcular pagamentos [${receivableUnitId}]", exception)
            }
        }
    }
}

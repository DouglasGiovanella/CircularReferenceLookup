package com.asaas.service.integration.cerc.contractualeffect

import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffect
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.integration.cerc.enums.webhook.CercEffectType
import grails.transaction.Transactional

@Transactional
class CercFidcContractualEffectAuditService {

    def cercFidcContractualEffectService
    def cercManagerService

    public void check(CercFidcContractualEffect contractualEffect) {
        if (!contractualEffect.syncStatus.isError()) return

        Boolean isActiveOnCerc = isActiveOnCerc(contractualEffect)
        Boolean isContractualEffectNotActive = CercOperationType.listNotActiveTypes().contains(contractualEffect.operationType)
        if (!isActiveOnCerc && isContractualEffectNotActive) {
            cercFidcContractualEffectService.setAsSynced(contractualEffect)
            return
        }

        Boolean isEffectTypeAllowedForFidcContract = CercEffectType.listAllowedForFidcType().contains(contractualEffect.effectType)
        if (!isActiveOnCerc && !isContractualEffectNotActive && isEffectTypeAllowedForFidcContract) {
            recreateItemWithNewExternalIdentifier(contractualEffect)
            return
        }

        if (!isActiveOnCerc && !isEffectTypeAllowedForFidcContract) {
            delete(contractualEffect)
            return
        }

        if (isActiveOnCerc && !isEffectTypeAllowedForFidcContract) {
            forceFinish(contractualEffect)
            return
        }

        cercFidcContractualEffectService.setAsAwaitingSyncIfPossible(contractualEffect)
    }

    private void delete(CercFidcContractualEffect contractualEffect) {
        contractualEffect.deleted = true
        contractualEffect.save(failOnError: true)
    }

    private Boolean isActiveOnCerc(CercFidcContractualEffect contractualEffect) {
        Map response = cercManagerService.queryFidcContractInfo(contractualEffect)
        return response.asBoolean()
    }

    private void recreateItemWithNewExternalIdentifier(CercFidcContractualEffect contractualEffect) {
        contractualEffect.operationType = CercOperationType.CREATE
        contractualEffect.externalIdentifier = cercFidcContractualEffectService.getExternalIdentifier(contractualEffect.id)
        contractualEffect.save(failOnError: true)

        cercFidcContractualEffectService.setAsAwaitingSyncIfPossible(contractualEffect)
    }

    private void forceFinish(CercFidcContractualEffect contractualEffect) {
        contractualEffect.operationType = CercOperationType.FINISH
        contractualEffect.save(failOnError: true)

        cercFidcContractualEffectService.setAsAwaitingSyncIfPossible(contractualEffect)
    }
}

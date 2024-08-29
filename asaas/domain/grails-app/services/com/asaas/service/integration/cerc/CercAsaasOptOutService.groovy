package com.asaas.service.integration.cerc

import com.asaas.domain.integration.cerc.optin.CercAsaasOptIn
import com.asaas.domain.integration.cerc.optin.CercAsaasOptOut
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercAsaasOptOutService {

    public CercAsaasOptOut save(CercAsaasOptIn cercAsaasOptIn) {
        if (!cercAsaasOptIn.syncStatus.isSynced()) throw new IllegalArgumentException("OPT-IN não sincronizado.")

        CercAsaasOptOut cercAsaasOptOut = new CercAsaasOptOut()
        cercAsaasOptOut.cercAsaasOptIn = cercAsaasOptIn
        cercAsaasOptOut.save(failOnError: true)
        return cercAsaasOptOut
    }

    public void setAsSynced(CercAsaasOptOut optOut, String externalIdentifier) {
        if (!externalIdentifier) throw new RuntimeException("Identificador externo não pode ser nulo")

        optOut.syncStatus = CercSyncStatus.SYNCED
        optOut.externalIdentifier = externalIdentifier
        optOut.save(failOnError: true)
    }

    public void setAsErrorWithNewTransaction(Long optOutId) {
        Utils.withNewTransactionAndRollbackOnError({
            CercAsaasOptOut optOut = CercAsaasOptOut.get(optOutId)
            optOut.syncStatus = CercSyncStatus.ERROR
            optOut.save(failOnError: true)
        }, [logErrorMessage: "CercAsaasOptOutService.setAsErrorWithNewTransaction >> Falha ao marcar o OPT-Out [${optOutId}] como erro de sincronia"])
    }
}

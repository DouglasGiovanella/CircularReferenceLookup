package com.asaas.service.integration.cerc.batch

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.CercBatch
import com.asaas.integration.cerc.enums.CercBatchStatus
import com.asaas.integration.cerc.enums.CercBatchType

import grails.transaction.Transactional

@Transactional
class CercBatchService {

    public CercBatch save(CercBatchType type, AsaasFile file) {
        CercBatch batch = new CercBatch()
        batch.type = type
        batch.file = file
        batch.status = CercBatchStatus.PENDING
        batch.save(failOnError: true)

        return batch
    }

    public void setAsProcessed(Long batchId) {
        CercBatch batch = CercBatch.get(batchId)
        batch.status = CercBatchStatus.PROCESSED
        batch.save(failOnError: true)
    }

    public void setAsError(Long batchId) {
        CercBatch batch = CercBatch.get(batchId)
        batch.status = CercBatchStatus.ERROR
        batch.save(failOnError: true)
    }
}

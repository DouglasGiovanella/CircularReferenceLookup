package com.asaas.service.integration.cerc.batch.companyconciliation

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.CercBatch
import com.asaas.integration.cerc.adapter.batch.CompanyConciliationItemAdapter
import com.asaas.integration.cerc.enums.CercBatchType
import com.asaas.integration.cerc.parser.CercBatchParser
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CompanyConciliationBatchProcessingService {

    def cercBatchService
    def companyConciliationItemService

    public void processBatch() {
        Map search = [:]
        search.columnList = ["id", "file"]
        search.sort = "id"
        search.order = "asc"
        search.type = CercBatchType.COMPANY_CONCILIATION

        Map cercBatchMap = CercBatch.pending(search).get()
        if (!cercBatchMap) return

        List<CompanyConciliationItemAdapter> itemAdapterList = []
        try {
            itemAdapterList = parseItemsFromBatch(cercBatchMap.file)
        } catch (Exception exception) {
            Utils.withNewTransactionAndRollbackOnError({
                cercBatchService.setAsError(cercBatchMap.id)
            }, [logErrorMessage: "CompanyConciliationBatchProcessingService.processBatch >> Erro ao salvar o status ERROR no batch [${cercBatchMap.id}]"])

            AsaasLogger.error("CompanyConciliationBatchProcessingService.processBatch >> Erro ao processar o arquivo do batch [${cercBatchMap.id}]", exception)
            return
        }

        for (CompanyConciliationItemAdapter item : itemAdapterList) {
            Utils.withNewTransactionAndRollbackOnError({
                CercBatch batch = CercBatch.read(cercBatchMap.id)
                companyConciliationItemService.save(batch, item)
            }, [logErrorMessage: "CompanyConciliationBatchProcessingService.processBatch >> Erro ao salvar item [linha: ${item.lineNumber}]"])
        }

        Utils.withNewTransactionAndRollbackOnError({
            cercBatchService.setAsProcessed(cercBatchMap.id)
        }, [logErrorMessage: "CompanyConciliationBatchProcessingService.processBatch >> Erro ao salvar o status PROCESSED no batch [${cercBatchMap.id}]"])
    }

    private List<CompanyConciliationItemAdapter> parseItemsFromBatch(AsaasFile batchFile) {
        CercBatchParser batchParser = new CercBatchParser(batchFile, CercBatchType.COMPANY_CONCILIATION)
        List<String> requiredHeadersList = ["Referencia_Externa", "Estabelecimento_Comercial", "Status"]
        Closure toAdapter = { Map item -> new CompanyConciliationItemAdapter(item) }
        return batchParser.parse(requiredHeadersList, toAdapter)
    }
}

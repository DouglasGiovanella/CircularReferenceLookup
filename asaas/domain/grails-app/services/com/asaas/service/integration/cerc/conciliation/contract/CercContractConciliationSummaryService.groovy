package com.asaas.service.integration.cerc.conciliation.contract

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.CercBatch
import com.asaas.domain.integration.cerc.conciliation.contract.CercContractConciliation
import com.asaas.domain.integration.cerc.conciliation.contract.CercContractConciliationSummary
import com.asaas.integration.cerc.adapter.batch.CercContractConciliationItemAdapter
import com.asaas.integration.cerc.builder.conciliation.CercContractConciliationSnapshotFileHandler
import com.asaas.integration.cerc.enums.conciliation.ReceivableRegistrationConciliationOrigin
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CercContractConciliationSummaryService {

    def fileService

    public void save(CercContractConciliation conciliation, ReceivableRegistrationConciliationOrigin origin, Integer amountOfContracts, Integer amountOfCustomers, BigDecimal value, List<CercContractConciliationItemAdapter> contractsList) {
        CercContractConciliationSummary validatedDomain = validateSave(conciliation, origin)
        if (validatedDomain.hasErrors()) throw new ValidationException("Não foi possível salvar o resumo da conciliação", validatedDomain.errors)

        CercContractConciliationSummary conciliationSummary = new CercContractConciliationSummary()
        conciliationSummary.conciliation = conciliation
        conciliationSummary.origin = origin
        conciliationSummary.amountOfContracts = amountOfContracts
        conciliationSummary.amountOfCustomers = amountOfCustomers
        conciliationSummary.value = value
        conciliationSummary.contractsSnapshotFile = createSnapshotFile(conciliation, origin, contractsList)
        conciliationSummary.save(failOnError: true)
    }

    private CercContractConciliationSummary validateSave(CercContractConciliation conciliation, ReceivableRegistrationConciliationOrigin origin) {
        CercContractConciliationSummary validatedDomain = new CercContractConciliationSummary()

        Boolean alreadyExistsSummaryOfSameOriginForConciliation = CercContractConciliationSummary.query([exists: true, conciliationId: conciliation.id, origin: origin]).get().asBoolean()
        if (alreadyExistsSummaryOfSameOriginForConciliation) DomainUtils.addError(validatedDomain, "Já existe um resumo com a origem [${origin.toString()}] para a conciliação [${conciliation.id}].")

        return validatedDomain
    }

    private AsaasFile createSnapshotFile(CercContractConciliation conciliation, ReceivableRegistrationConciliationOrigin origin, List<CercContractConciliationItemAdapter> itemsAdapterList) {
        String formattedReferenceDate = conciliation.referenceDate.format(CercBatch.FILE_NAME_DATE_FORMAT)
        String fileName = "${formattedReferenceDate}_${conciliation.partner}_${conciliation.effectType}_${conciliation.operationMode}_${origin}.csv"

        String fileContent = CercContractConciliationSnapshotFileHandler.build(itemsAdapterList)

        return fileService.createFile(fileName, fileContent)
    }
}

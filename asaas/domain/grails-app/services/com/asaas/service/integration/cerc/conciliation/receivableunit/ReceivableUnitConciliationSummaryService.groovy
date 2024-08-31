package com.asaas.service.integration.cerc.conciliation.receivableunit

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.cerc.CercBatch
import com.asaas.domain.integration.cerc.conciliation.receivableunit.ReceivableUnitConciliationSummary
import com.asaas.domain.receivableunit.ReceivableUnitConciliation
import com.asaas.integration.cerc.adapter.batch.ReceivableUnitConciliationItemAdapter
import com.asaas.integration.cerc.builder.conciliation.ReceivableUnitConciliationSnapshotFileHandler
import com.asaas.integration.cerc.enums.conciliation.ReceivableRegistrationConciliationOrigin
import com.asaas.utils.DomainUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class ReceivableUnitConciliationSummaryService {

    def fileService

    public void save(ReceivableUnitConciliation conciliation, ReceivableRegistrationConciliationOrigin origin, BigDecimal netValue, BigDecimal totalValue, BigDecimal preAnticipatedValue, BigDecimal anticipatedValue, BigDecimal contractualEffectValue, Integer amountOfReceivableUnits, List<ReceivableUnitConciliationItemAdapter> itemAdapterList) {
        ReceivableUnitConciliationSummary validatedDomain = validate(conciliation, origin, contractualEffectValue)
        if (validatedDomain.hasErrors()) throw new ValidationException("Não foi possível salvar o resumo da conciliação", validatedDomain.errors)

        ReceivableUnitConciliationSummary summary = new ReceivableUnitConciliationSummary()
        summary.conciliation = conciliation
        summary.origin = origin
        summary.netValue = netValue
        summary.totalValue = totalValue
        summary.preAnticipatedValue = preAnticipatedValue
        summary.anticipatedValue = anticipatedValue
        summary.contractualEffectValue = contractualEffectValue
        summary.amountOfReceivableUnits = amountOfReceivableUnits
        summary.snapshotFile = createSnapshotFile(conciliation, origin, itemAdapterList)
        summary.save(failOnError: true)
    }

    private AsaasFile createSnapshotFile(ReceivableUnitConciliation conciliation, ReceivableRegistrationConciliationOrigin origin, List<ReceivableUnitConciliationItemAdapter> itemAdapterList) {
        String formattedReferenceDate = conciliation.referenceDate.format(CercBatch.FILE_NAME_DATE_FORMAT)
        String fileName = "${formattedReferenceDate}_${conciliation.paymentArrangement}_${origin}.csv"

        String fileContent = ReceivableUnitConciliationSnapshotFileHandler.build(itemAdapterList)

        return fileService.createFile(fileName, fileContent)
    }

    private ReceivableUnitConciliationSummary validate(ReceivableUnitConciliation conciliation, ReceivableRegistrationConciliationOrigin origin, BigDecimal contractualEffectValue) {
        ReceivableUnitConciliationSummary validatedDomain = new ReceivableUnitConciliationSummary()

        Boolean summaryAlreadyExists = ReceivableUnitConciliationSummary.query([exists: true, conciliationId: conciliation.id, origin: origin]).get().asBoolean()
        if (summaryAlreadyExists) DomainUtils.addError(validatedDomain, "Já existe um resumo com a origem [${origin.toString()}] para a conciliação [${conciliation.id}].")

        if (contractualEffectValue == null) DomainUtils.addError(validatedDomain, "O valor aplicado de efeitos não pode ser nulo.")

        return validatedDomain
    }
}

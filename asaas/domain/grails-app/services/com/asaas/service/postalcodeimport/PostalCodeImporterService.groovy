package com.asaas.service.postalcodeimport

import com.asaas.domain.city.City
import com.asaas.domain.postalcode.PostalCode
import com.asaas.domain.postalcode.importdata.PostalCodeImportFile
import com.asaas.postalcode.importdata.PostalCodeImportFileStatus
import com.asaas.postalcode.importdata.PostalCodeImportLogStatus
import com.asaas.log.AsaasLogger
import com.asaas.postalcode.importdata.PostalCodeReader
import com.asaas.state.State
import com.asaas.utils.Utils
import grails.async.Promise
import grails.transaction.Transactional

import static grails.async.Promises.waitAll
import static grails.async.Promises.task

@Transactional
class PostalCodeImporterService {

    static final int NUMBER_PER_SAVE_THREAD = 100000

    def postalCodeImportLogItemService

    def postalCodeImportFileService

    def cityService

    def postalCodeService

    public void processPending() {
        try {
            Long postalCodeImportFileId = PostalCodeImportFile.query([column: "id", status: PostalCodeImportFileStatus.PENDING]).get() as Long
            this.processPostalCodeImportFile(postalCodeImportFileId)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao processar importação de atualização de PostalCode", exception)
        }
    }

    private void processPostalCodeImportFile(Long id) {
        try {
            AsaasLogger.info("PostalCodeImportFileService -> processPostalCodeImportFile -> Started: PostalCodeImportFile ID: [${id}]")

            PostalCodeImportFile postalCodeImportFile = PostalCodeImportFile.get(id)

            if (!postalCodeImportFile) {
                AsaasLogger.error("PostalCodeImportFileService -> Não foi possível encontrar - PostalCodeImportFile ID: [${id}]")
                return
            }

            if (!postalCodeImportFile.file.isCsvExtension()) {
                postalCodeImportFileService.update(id, PostalCodeImportFileStatus.FAILED)
                AsaasLogger.error("PostalCodeImportFileService -> processPostalCodeImportFile -> Failed: PostalCodeImportFile ID: [${id}]")
                return
            }

            PostalCodeImportFile.withNewTransaction {
                postalCodeImportFileService.update(id, PostalCodeImportFileStatus.PROCESSING)
            }

            List<Promise> promiseList = []

            PostalCodeReader postalCodeReader = new PostalCodeReader()
            postalCodeReader.readCsvFile(postalCodeImportFile.file).collate(NUMBER_PER_SAVE_THREAD).each {
                List rows = it.collect()
                promiseList << task { this.importRowsWithNewThread(id, rows) }
            }

            waitAll(promiseList)

            AsaasLogger.info("PostalCodeImportFileService -> processPostalCodeImportFile -> Finished: PostalCodeImportFile ID: [${id}]")
            PostalCodeImportFile.withNewTransaction { postalCodeImportFileService.update(id, PostalCodeImportFileStatus.IMPORTED) }
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao processar arquivo de atualização do PostalCode", exception)
            PostalCodeImportFile.withNewTransaction {
                postalCodeImportFileService.update(id, PostalCodeImportFileStatus.FAILED)
            }
        }
    }

    private void importRowsWithNewThread(Long postalCodeImportFileId, List<Map> rows) {
        AsaasLogger.info("PostalCodeImportFileService -> importRowsThread -> Started on PostalCodeImportFileId: [${postalCodeImportFileId}] - SIZE: [${rows.size()}]")
        try {
            rows.each { row ->
                this.importRow(postalCodeImportFileId, row)
            }
        } catch (Exception e) {
            AsaasLogger.error("PostalCodeImportFileService -> importRowsThread -> Error on PostalCodeImportFileId: [${postalCodeImportFileId}] - SIZE: [${rows.size()}]", e)
        }
    }

    private void importRow(Long postalCodeImportFileId, Map row) {
        Utils.withNewTransactionAndRollbackOnError({
            Boolean cityHasEmptyValues = PostalCodeReader.cityHasEmptyColumns(row)
            if (cityHasEmptyValues) {
                postalCodeImportLogItemService.saveCityLogItem(postalCodeImportFileId, row.ibgeCode, PostalCodeImportLogStatus.FAILED)
                return
            }
            Boolean postalCodeHasEmptyIdentifier = PostalCodeReader.postalCodeHasEmptyIdentifierColumn(row)
            if (postalCodeHasEmptyIdentifier) {
                postalCodeImportLogItemService.savePostalCodeLogItem(postalCodeImportFileId, row.postalCode, PostalCodeImportLogStatus.FAILED)
                return
            }
            Boolean postalCodeExists = PostalCode.query([postalCode: row.postalCode, exists: true]).get().asBoolean()
            if (postalCodeExists) return

            Boolean cityExists = City.query([exists: true, state: State.findByDescription(row.stateName), ibgeCode: row.ibgeCode]).get().asBoolean()
            if (!cityExists) {
                City city = cityService.importCity(row.ibgeCode, row.city, row.stateName)
                postalCodeImportLogItemService.saveCityLogItem(postalCodeImportFileId, row.ibgeCode, PostalCodeImportLogStatus.IMPORTED)
                AsaasLogger.warn("PostalCodeImportFileService -> importRow -> CITY: [ID: ${city.id}] [IBGE_CODE: ${city.ibgeCode}]")
            }
            City city = City.query([state: State.findByDescription(row.stateName), ibgeCode: row.ibgeCode, sort: "districtCode", order: "asc"]).get()
            String address = PostalCodeReader.getAddress(row)
            postalCodeService.importPostalCode(row.postalCode, address, row.province, city)
            postalCodeImportLogItemService.savePostalCodeLogItem(postalCodeImportFileId, row.postalCode, PostalCodeImportLogStatus.IMPORTED)
        }, [onError: { Exception e ->
            AsaasLogger.error("PostalCodeImportFileService -> importRow -> POSTAL_CODE: [${row.postalCode}]", e)
            postalCodeImportLogItemService.savePostalCodeLogItem(postalCodeImportFileId, row.postalCode, PostalCodeImportLogStatus.FAILED)
        }])
    }
}

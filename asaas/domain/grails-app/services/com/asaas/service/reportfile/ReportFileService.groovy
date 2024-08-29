package com.asaas.service.reportfile

import com.asaas.domain.file.AsaasFile
import com.asaas.export.CsvExporter
import com.asaas.log.AsaasLogger
import com.asaas.utils.FileUtils
import grails.transaction.Transactional

@Transactional
class ReportFileService {

    def fileService

    public AsaasFile createCsvFile(String fileName, List<String> headerList, List<List> rowList) {
        try {
            CsvExporter csvExporter = new CsvExporter(headerList, rowList)
            byte[] csvFileAsBytes = csvExporter.asBytes()

            AsaasFile asaasFile
            FileUtils.withDeletableTempFile(csvFileAsBytes) { File tempFile ->
                asaasFile = fileService.createFile(null, tempFile, "${fileName}.csv")
            }

            return asaasFile
        } catch (Exception exception) {
            AsaasLogger.error("ReportFileService.createCsvFile >> Erro ao salvar CSV [${fileName}] com [${rowList.size()}] linhas", exception)
            throw exception
        }
    }
}

package com.asaas.service.financialtransaction

import com.asaas.asyncaction.AsyncActionType
import com.asaas.converter.HtmlToPdfConverter
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.financialtransaction.FinancialTransactionExportFormat
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.export.CsvExporter
import com.asaas.export.ExcelExporter
import com.asaas.financialtransaction.FinancialTransactionListVo
import com.asaas.financialtransaction.cnab240.FinancialTransactionCnab240Builder
import com.asaas.financialtransaction.ofx.FinancialTransactionOfxBuilder
import com.asaas.financialtransaction.tabular.FinancialTransactionTabularCsvBuilder
import com.asaas.financialtransaction.tabular.FinancialTransactionTabularExcelBuilder
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.RequestUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.apache.commons.lang.NotImplementedException

@Transactional
class FinancialTransactionExportService {

    def asyncActionService
    def fileService
    def financialTransactionListService
    def groovyPageRenderer
    def messageService

    public Map export(FinancialTransactionExportFormat fileType, Customer customer, User requestedBy, Date startDate, Date finishDate, Integer totalFinancialTransactionFiltered, Integer maximumItemsForDownload) {
        if (!fileType) throw new BusinessException("É necessário informar um tipo válido")

        Map responseMap = [asyncExport: false]

        if (totalFinancialTransactionFiltered == null) {
            AsaasLogger.warn("FinancialTransactionExportService.export >> Parâmetro [totalFinancialTransactionFiltered] nulo para o usuario [${UserUtils.getCurrentUserId(null)}]", new Throwable())
            responseMap.asyncExport = true
        }

        if (totalFinancialTransactionFiltered) {
            responseMap.asyncExport = totalFinancialTransactionFiltered > maximumItemsForDownload
            if (!responseMap.asyncExport) responseMap.asyncExport = totalFinancialTransactionFiltered == FinancialTransaction.LIMIT_TO_LIST_IN_WEB
        }

        if (responseMap.asyncExport) {
            AsyncActionType asyncActionType = convertToAsyncActionType(fileType)
            asyncActionService.saveFinancialTransactionExport(asyncActionType, customer.id, requestedBy.username, startDate, finishDate)
            responseMap.recipientEmail = requestedBy.username
        } else {
            responseMap.fileBytes = buildFile(fileType, customer, startDate, finishDate)
        }

        return responseMap
    }

    public void sendPendingExportFiles(FinancialTransactionExportFormat fileType) {
        final Integer max = 5
        AsyncActionType asyncActionType = convertToAsyncActionType(fileType)
        List<Map> asyncActionDataList = asyncActionService.listPending(asyncActionType, max)

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                AsaasFile asaasFile
                Customer customer = Customer.read(asyncActionData.customerId)
                Date startDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.startDate)
                Date finishDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.finishDate)

                byte[] fileBytes = buildFile(fileType, customer, startDate, finishDate)
                FileUtils.withDeletableTempFile(fileBytes, { File tempFile ->
                    asaasFile = fileService.createFile(customer, tempFile, "Extrato_Asaas.${FinancialTransactionExportFormat.convertToTypeExtension(fileType)}")
                })

                messageService.sendCustomerFinancialTransactionExport(asaasFile, asyncActionData.userEmail, customer, startDate, finishDate)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "FinancialTransactionExportService.sendPendingExportFiles >> Erro na exportação do extrato em ${fileType}. AsyncAction ID: ${asyncActionData.asyncActionId} | CustomerId: ${asyncActionData.customerId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private byte[] buildFile(FinancialTransactionExportFormat fileType, Customer customer, Date startDate, Date finishDate) {
        Map queryParams = [initialDate: startDate, finalDate: finishDate]

        if (fileType.isOfx()) {
            queryParams.includePaymentId = true
        } else if (fileType.isCsv() || fileType.isXlsx()) {
            queryParams.includePaymentId = true
            queryParams.includeReversedTransactionId = true
            queryParams.includeReceivableAnticipationId = true
            queryParams.includeInvoiceId = true
            queryParams.includeInternalTransferId = true
        }

        FinancialTransactionListVo transactionListVo = financialTransactionListService.list(customer, queryParams)

        Boolean isSiteExport = !AsaasEnvironment.isJobServer() && !RequestUtils.isApiRequest()
        if (isSiteExport) {
            Integer numberOfRowsToExport = transactionListVo.transactionList?.size() ?: 0

            Boolean isUserExportingAboveSiteLimitOfRows = numberOfRowsToExport > FinancialTransaction.LIMIT_TO_LIST_IN_WEB
            if (isUserExportingAboveSiteLimitOfRows) {
                throw new RuntimeException("A exportação está ultrapassando o limite de linhas permitido. Linhas exportadas: [${numberOfRowsToExport}] Linhas permitidas: [${FinancialTransaction.LIMIT_TO_LIST_IN_WEB}]")
            }
        }

        if (fileType.isPdf()) return buildPdf(transactionListVo)

        if (fileType.isCnab240()) return buildCnab240(transactionListVo)

        if (fileType.isOfx()) return buildOfx(transactionListVo)

        if (fileType.isCsv()) return buildCsv(transactionListVo)

        if (fileType.isXlsx()) return buildXlsx(transactionListVo)

        throw new NotImplementedException("Tipo de arquivo [${fileType.toString()}] não implementado")
    }

    private byte[] buildOfx(FinancialTransactionListVo transactionListVo) {
        FinancialTransactionOfxBuilder financialTransactionOfxBuilder = new FinancialTransactionOfxBuilder(transactionListVo)
        String fileContent = financialTransactionOfxBuilder.buildFile()

        byte[] ofxBytes
        FileUtils.withDeletableTempFile(fileContent, "US-ASCII", { File tempFile ->
            ofxBytes = tempFile.readBytes()
        })

        return ofxBytes
    }

    private byte[] buildCnab240(FinancialTransactionListVo transactionListVo) {
        FinancialTransactionCnab240Builder financialTransactionCnab240Builder = new FinancialTransactionCnab240Builder(transactionListVo)
        String fileContent = financialTransactionCnab240Builder.buildFile()

        byte[] cnab240Bytes
        FileUtils.withDeletableTempFile(fileContent, "UTF-8", { File tempFile ->
            cnab240Bytes = tempFile.readBytes()
        })

        return cnab240Bytes
    }

    private byte[] buildPdf(FinancialTransactionListVo transactionListVo) {
        String htmlString = groovyPageRenderer.render(template: "/financialTransaction/templates/extractReportPdf", model:[
            transactionListVo: transactionListVo,
            customer: transactionListVo.customer,
            accountNumber: transactionListVo.customer.getAccountNumber(),
        ]).decodeHTML()

        byte[] pdfFile = HtmlToPdfConverter.asBytes(htmlString)

        return pdfFile
    }

    private byte[] buildCsv(FinancialTransactionListVo transactionListVo) {
        final String csvSeparator = ","

        FinancialTransactionTabularCsvBuilder csvBuilder = new FinancialTransactionTabularCsvBuilder(transactionListVo)
        CsvExporter csvExporter = new CsvExporter(csvBuilder.headerList, csvBuilder.rowList)
        csvExporter.setSeparator(csvSeparator)

        return csvExporter.asBytes()
    }

    private byte[] buildXlsx(FinancialTransactionListVo transactionListVo) {
        FinancialTransactionTabularExcelBuilder excelBuilder = new FinancialTransactionTabularExcelBuilder(transactionListVo)
        return ExcelExporter.buildXlsx().addMultipleRows(excelBuilder.rowList).asBytes()
    }

    private AsyncActionType convertToAsyncActionType(FinancialTransactionExportFormat fileType) {
        switch (fileType) {
            case FinancialTransactionExportFormat.PDF:
                return AsyncActionType.FINANCIAL_TRANSACTION_EXPORT_PDF
            case FinancialTransactionExportFormat.XLS:
                return AsyncActionType.FINANCIAL_TRANSACTION_EXPORT_XLS
            case FinancialTransactionExportFormat.XLSX:
                return AsyncActionType.FINANCIAL_TRANSACTION_EXPORT_XLSX
            case FinancialTransactionExportFormat.CNAB240:
                return AsyncActionType.FINANCIAL_TRANSACTION_EXPORT_CNAB240
            case FinancialTransactionExportFormat.OFX:
                return AsyncActionType.FINANCIAL_TRANSACTION_EXPORT_OFX
            case FinancialTransactionExportFormat.CSV:
                return AsyncActionType.FINANCIAL_TRANSACTION_EXPORT_CSV
            default:
                AsaasLogger.error("FinancialTransactionExportService.convertToAsyncActionType >>> Erro ao mapear tipo de arquivo. [fileType: ${fileType}]")
                throw new BusinessException("Erro ao mapear tipo de arquivo")
        }
    }
}

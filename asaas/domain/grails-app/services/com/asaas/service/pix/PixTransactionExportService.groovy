package com.asaas.service.pix

import au.com.bytecode.opencsv.CSVWriter
import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.pix.PixTransaction
import com.asaas.exception.BusinessException
import com.asaas.export.ExcelExporter
import com.asaas.export.pixtransaction.PixCreditTransactionsExportDataBuilder
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.PixTransactionType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.gorm.PagedResultList
import grails.transaction.Transactional
import org.hibernate.SQLQuery
import org.hibernate.criterion.CriteriaSpecification

import java.nio.charset.StandardCharsets

@Transactional
class PixTransactionExportService {

    private static final Integer MAXIMUM_PIX_CREDIT_TRANSACTION_ITEMS = 9999

    private static final Integer MAXIMUM_PIX_CREDIT_TRANSACTION_ITEMS_FOR_SYNC_DOWNLOAD = 2000

    def asyncActionService
    def fileService
    def messageService
    def sessionFactory

    public AsaasFile exportDailyReport(Customer customer, Date date, PixTransactionStatus status) {
        BusinessValidation validatedDailyReport = validateDailyReport(date, status)
        if (!validatedDailyReport.isValid()) throw new BusinessException(validatedDailyReport.getFirstErrorMessage())

        SQLQuery query = sessionFactory.currentSession.createSQLQuery("""
            SELECT pt.effective_date AS effectiveDate, pt.end_to_end_identifier AS endToEndIdentifier, pt.conciliation_identifier AS conciliationIdentifier, pt.origin_type AS originType, pt.type AS type, pt.value AS value, pt.status AS status, cf.value AS chargedFeeValue
            FROM pix_transaction pt
            LEFT JOIN charged_fee cf ON pt.id = cf.pix_transaction_id
            WHERE
                pt.effective_date >= :effectiveDateStart
                AND pt.effective_date <= :effectiveDateEnd
                AND pt.customer_id = :customerId
                AND pt.status = :status
        """)
        query.setTimestamp("effectiveDateStart", date.clearTime())
        query.setTimestamp("effectiveDateEnd", CustomDateUtils.setTimeToEndOfDay(date))
        query.setLong("customerId", customer.id)
        query.setString("status", status.toString())
        query.setResultTransformer(CriteriaSpecification.ALIAS_TO_ENTITY_MAP)
        List<Map> pixTransactionInfoList = query.list()

        StringWriter stringWriter = new StringWriter()
        CSVWriter writer = new CSVWriter(stringWriter, ';' as char)
        String[] header = ["Data", "Identificador fim a fim", "Identificador do QR Code", "Origem", "Tipo", "Valor", "Situação", "Valor da taxa"]
        writer.writeNext(header)

        for (Map pixTransactionInfo : pixTransactionInfoList) {
            List<String> rowContent = []
            rowContent.add(CustomDateUtils.formatDateTime(pixTransactionInfo.effectiveDate))
            rowContent.add(pixTransactionInfo.endToEndIdentifier)
            rowContent.add(pixTransactionInfo.conciliationIdentifier)
            rowContent.add(pixTransactionInfo.originType)
            rowContent.add(pixTransactionInfo.type)
            rowContent.add(pixTransactionInfo.value)
            rowContent.add(pixTransactionInfo.status)
            rowContent.add(pixTransactionInfo.chargedFeeValue ?: "0")

            writer.writeNext(rowContent as String[])
        }

        writer.flush()
        stringWriter.flush()
        writer.close()
        stringWriter.close()

        String fileName = "pix_${CustomDateUtils.fromDate(date, "yyyyMMdd")}"
        return fileService.createFile("${fileName}.csv", stringWriter.toString(), StandardCharsets.UTF_8.toString())
    }

    public Map exportCreditTransactions(Customer customer, String recipientEmail, Map search) {
        search.type = PixTransactionType.CREDIT
        search."payment[isNull]" = true

        PagedResultList pixTransactionList = PixTransaction.query(search + [customer: customer]).list(max: MAXIMUM_PIX_CREDIT_TRANSACTION_ITEMS_FOR_SYNC_DOWNLOAD, readOnly: true)

        if (!pixTransactionList) throw new BusinessException("Não foram encontrados Pix recebidos a serem exportados. Verifique os filtros informados e tente novamente.")

        if (pixTransactionList.totalCount > MAXIMUM_PIX_CREDIT_TRANSACTION_ITEMS) throw new BusinessException("Não é possível exportar mais do que ${MAXIMUM_PIX_CREDIT_TRANSACTION_ITEMS} Pix recebidos. Aplique mais filtros para diminuir a quantidade de registros.")

        Map response = [:]

        if (pixTransactionList.totalCount <= MAXIMUM_PIX_CREDIT_TRANSACTION_ITEMS_FOR_SYNC_DOWNLOAD) {
            response.asyncExport = false
            response.fileBytes = buildExcelFile(pixTransactionList)
            response.fileName = buildExcelFileName()
        } else {
            response.asyncExport = true

            if (search."dateCreated[ge]") search."dateCreated[ge]" = CustomDateUtils.fromDate(search."dateCreated[ge]")
            if (search."dateCreated[le]") search."dateCreated[le]" = CustomDateUtils.fromDate(search."dateCreated[le]")

            asyncActionService.savePixTransactionExport(customer.id, recipientEmail, search)
        }

        return response
    }

    public void sendPendingExportFiles() {
        final Integer max = 5
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.PIX_TRANSACTION_EXPORT, max)

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)

                List<PixTransaction> pixTransactionList = PixTransaction.query(asyncActionData.filters + [customer:  customer]).list(max: MAXIMUM_PIX_CREDIT_TRANSACTION_ITEMS, readOnly: true)

                AsaasFile asaasFile
                byte[] fileBytes = buildExcelFile(pixTransactionList)
                FileUtils.withDeletableTempFile(fileBytes, { File tempFile ->
                    asaasFile = fileService.createFile(customer, tempFile, buildExcelFileName())
                })

                Date startDate = CustomDateUtils.fromString(asyncActionData.filters."dateCreated[ge]")
                Date finishDate = CustomDateUtils.fromString(asyncActionData.filters."dateCreated[le]")

                messageService.sendCustomerPixTransactionExport(asaasFile, asyncActionData.userEmail, customer, startDate, finishDate)

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                logErrorMessage: "PixTransactionExportService.sendPendingExportFiles >> Erro na exportação [AsyncAction.id: ${asyncActionData.asyncActionId}, Customer.id: ${asyncActionData.customerId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }
            ])
        }
    }

    private byte[] buildExcelFile(List<PixTransaction> pixTransactionList) {
        PixCreditTransactionsExportDataBuilder dataBuilder = new PixCreditTransactionsExportDataBuilder(pixTransactionList)
        return ExcelExporter.buildXls()
            .addRow(dataBuilder.headerList)
            .addMultipleRows(dataBuilder.rowList)
            .asBytes()
    }

    private String buildExcelFileName() {
        return "${Utils.getMessageProperty("file.pixTransaction.credit")}.xls"
    }

    private BusinessValidation validateDailyReport(Date date, PixTransactionStatus status) {
        BusinessValidation businessValidation = new BusinessValidation()
        if (!date) {
            businessValidation.addError("pixTransactionExportDailyReport.validate.error.nullDate")
            return businessValidation
        }

        if (date > new Date()) {
            businessValidation.addError("pixTransactionExportDailyReport.validate.error.dateAfterToday")
            return businessValidation
        }

        if (!status?.isDone()) {
            businessValidation.addError("pixTransactionExportDailyReport.validate.error.status")
            return businessValidation
        }

        return businessValidation
    }
}

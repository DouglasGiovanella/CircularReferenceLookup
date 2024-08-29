package com.asaas.service.invoice

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerFiscalInfo
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.invoice.Invoice
import com.asaas.domain.invoice.InvoiceBatchFile
import com.asaas.domain.invoice.InvoiceBatchFileItem
import com.asaas.exception.BusinessException
import com.asaas.invoice.InvoiceBatchFileStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class InvoiceBatchDownloadService {

    public static final Integer BATCH_FILE_MAXIMUM_FILES = 9999

    def customerMessageService
    def fileService
    def invoiceFileService

    public InvoiceBatchFile saveBatchFile(Customer customer, List<Invoice> invoiceList, String fileType) {
        InvoiceBatchFile invoiceBatchFile = new InvoiceBatchFile()
        invoiceBatchFile.status = InvoiceBatchFileStatus.PENDING
        invoiceBatchFile.customer = customer
        invoiceBatchFile.email = CustomerFiscalInfo.query([column: "email", customerId: customer.id]).get()
        invoiceBatchFile.fileType = fileType
        invoiceBatchFile.save(flush: true, failOnError: true)

        for (Invoice invoice : invoiceList) {
            InvoiceBatchFileItem invoiceBatchFileItem = new InvoiceBatchFileItem()
            invoiceBatchFileItem.invoiceBatchFile = invoiceBatchFile
            invoiceBatchFileItem.invoice = invoice
            invoiceBatchFileItem.save(flush: true, failOnError: true)
        }

        return invoiceBatchFile
    }

    public processPendingInvoiceBatchFile() {
        List<Long> invoiceBatchFileIdList = InvoiceBatchFile.query([column: "id", status: InvoiceBatchFileStatus.PENDING]).list(max: 30)

        for (Long invoiceBatchFileId in invoiceBatchFileIdList) {
            try {
                Utils.withNewTransactionAndRollbackOnError({
                    InvoiceBatchFile invoiceBatchFile = InvoiceBatchFile.get(invoiceBatchFileId)

                    List<Invoice> invoiceList = InvoiceBatchFileItem.query([column: "invoice", invoiceBatchFile: invoiceBatchFile]).list()

                    File zipFile = buildInvoiceListZipFile(invoiceList, invoiceBatchFile.fileType)
                    AsaasFile asaasFile = fileService.createFile(invoiceBatchFile.customer, zipFile, "${buildInvoiceZipFileName(invoiceBatchFile.fileType)}.zip")

                    invoiceBatchFile.file = asaasFile
                    invoiceBatchFile.status = InvoiceBatchFileStatus.SENT
                    invoiceBatchFile.save(flush: true, failOnError: true)
                    customerMessageService.sendInvoiceBatchFileByEmail(invoiceBatchFile.customer, invoiceBatchFile.file, invoiceBatchFile.email)
                }, [onError: { Exception e -> throw e }])
            } catch (Exception e) {
                InvoiceBatchFile invoiceBatchFile = InvoiceBatchFile.get(invoiceBatchFileId)
                invoiceBatchFile.status = InvoiceBatchFileStatus.ERROR
                invoiceBatchFile.save(flush: true, failOnError: true)
                AsaasLogger.error("Erro ao processar InvoiceBatchFile [${invoiceBatchFile.id}]", e)
            }
        }
    }

    public Boolean shouldSendInvoiceBatchFileByEmail(List<Invoice> invoiceList) {
        final Integer maximumFilesForDownload = 200

        return invoiceList.size() > maximumFilesForDownload
    }

    public File buildInvoiceListZipFile(List<Invoice> invoiceList,  String fileType) {
        if (invoiceList.size() > BATCH_FILE_MAXIMUM_FILES) throw new BusinessException("O número de arquivos excede ao número maximo de notas para download em lote.")

        File zipFile
        if (fileType == 'pdf') {
            zipFile = FileUtils.buildZipFromFileList(invoiceList.collect { [name: "${invoiceFileService.buildInvoiceFileName(it)}.pdf", file: it.pdfFile.getFileInMemory()] } )
        } else {
            zipFile = FileUtils.buildZipFromFileList(invoiceList.collect { [name: "${invoiceFileService.buildInvoiceFileName(it)}.xml", file: it.xmlFile.getFileInMemory()] } )
        }

        return zipFile
    }

    public String buildInvoiceZipFileName(String fileType) {
        return "${fileType}Notas${CustomDateUtils.fromDate(new Date(), "ddMMyyyy")}"
    }
}

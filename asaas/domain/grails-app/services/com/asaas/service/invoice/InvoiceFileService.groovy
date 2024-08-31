package com.asaas.service.invoice

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.invoice.Invoice
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.utils.FileUtils
import grails.transaction.Transactional
import org.springframework.util.StopWatch

@Transactional
class InvoiceFileService {

    def fileService

    public void downloadCustomerInvoiceFiles(Invoice invoice) {
        if (invoice.isAsaasInvoice()) return

        try {
            if (invoice.pdfUrl) invoice.pdfFile = download(invoice, invoice.pdfUrl, "pdf")
            if (invoice.xmlUrl) invoice.xmlFile = download(invoice, invoice.xmlUrl, "xml")
            invoice.save(failOnError: true)
        } catch (Exception e) {
            AsaasLogger.error("Erro ao executar o download dos arquivos da nota ${invoice.id}.", e)
            throw e
        }
    }

    public String buildInvoiceFileName(Invoice invoice) {
        return "${invoice.number}-${invoice.customerAccount.cpfCnpj}"
    }

    private AsaasFile download(Invoice invoice, String fileUrl, String fileExtension) {
        if (!AsaasEnvironment.isProduction()) return
        AsaasLogger.info("InvoiceDownload ${invoice.id}-> Baixando arquivo ${fileExtension}")
        StopWatch downloadInvoiceFileStopWatch = new StopWatch()
        downloadInvoiceFileStopWatch.start("DownloadPt01")

        String fileName = buildInvoiceFileName(invoice)
        AsaasLogger.info("InvoiceDownload ${invoice.id}-> Baixando arquivo ${fileName}.${fileExtension}")
        final Integer downloadTimeoutInMilliseconds = 10000
        File file = FileUtils.downloadFile(fileUrl, fileName, fileExtension, downloadTimeoutInMilliseconds)

        AsaasFile asaasFile = fileService.createFile(invoice.customer, file, "${fileName}.${fileExtension}")

        downloadInvoiceFileStopWatch.stop()
        AsaasLogger.info("InvoiceDownload ${invoice.id}-> conclusão arquivo ${fileName}.${fileExtension} [${downloadInvoiceFileStopWatch.getTotalTimeMillis().toFloat()}]")

        return asaasFile
    }
}

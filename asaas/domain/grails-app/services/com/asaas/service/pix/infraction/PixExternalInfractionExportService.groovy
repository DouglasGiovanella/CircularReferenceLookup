package com.asaas.service.pix.infraction

import au.com.bytecode.opencsv.CSVWriter
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.file.AsaasFile
import com.asaas.exception.BusinessException
import com.asaas.generatereceipt.PixTransactionGenerateReceiptUrl
import com.asaas.integration.pix.dto.infraction.InfractionExportMailDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.infraction.ExternalInfractionAdapter
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

import java.nio.charset.StandardCharsets

@Transactional
class PixExternalInfractionExportService {

    def fileService
    def grailsApplication
    def messageService
    def pixExternalInfractionService
    def transactionReceiptPixTransactionService

    public void sendYesterdayInfractionReport() {
        List<Long> customerParameterIdList = CustomerParameter.query([column: "id", "stringValue[isNotNull]": true, name: CustomerParameterName.ENABLE_GENERATE_EXTERNAL_INFRACTION_REPORT]).list() as List<Long>
        if (!customerParameterIdList) return

        Date yesterday = CustomDateUtils.getYesterday()

        for (Long customerParameterId : customerParameterIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CustomerParameter customerParameter = CustomerParameter.read(customerParameterId)
                if (!customerParameter) return

                send(customerParameter.customerId, yesterday, yesterday)
            })
        }
    }

    public void send(Long customerId, Date initialDate, Date finalDate) {
        BusinessValidation businessValidation = validateSend(customerId, initialDate, finalDate)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        Customer customer = Customer.read(customerId)
        String customerParameterStringValue = CustomerParameter.getStringValue(customer, CustomerParameterName.ENABLE_GENERATE_EXTERNAL_INFRACTION_REPORT)

        Map filters = [
            customerId: customerId,
            initialDate: CustomDateUtils.fromDate(initialDate, CustomDateUtils.DATABASE_DATETIME_FORMAT),
            finalDate: CustomDateUtils.fromDate(CustomDateUtils.setTimeToEndOfDay(finalDate), CustomDateUtils.DATABASE_DATETIME_FORMAT)
        ]

        List<ExternalInfractionAdapter> externalInfractionAdapterList = pixExternalInfractionService.listAll(filters)
        if (!externalInfractionAdapterList) return

        String initialDateString = CustomDateUtils.fromDate(initialDate)
        String finalDateString = CustomDateUtils.fromDate(finalDate)

        AsaasFile externalInfractionExporterCsv = buildCsvFile(externalInfractionAdapterList, initialDateString, finalDateString)

        sendEmail(customer, initialDateString, finalDateString, externalInfractionExporterCsv, new InfractionExportMailDTO(customerParameterStringValue))

        AsaasLogger.info("${this.getClass().getSimpleName()}.send() -> E-mail enviado para o customerId: ${customerId}.")
    }

    private AsaasFile buildCsvFile(List<ExternalInfractionAdapter> externalInfractionAdapterList, String initialDate, String finalDate) {
        StringWriter stringWriter = new StringWriter()
        CSVWriter writer = new CSVWriter(stringWriter)

        String[] header = ["Nome do pagador", "Valor recebido", "Data de recebimento", "Link do recebimento", "ID fim a fim"]

        writer.writeNext(header)

        for (ExternalInfractionAdapter externalInfraction : externalInfractionAdapterList) {
            List<String> rowContent = []

            rowContent.add(externalInfraction.pixTransaction.externalAccount?.name)
            rowContent.add(externalInfraction.pixTransaction.value as String)
            rowContent.add(externalInfraction.pixTransaction.effectiveDate as String)
            rowContent.add(new PixTransactionGenerateReceiptUrl(externalInfraction.pixTransaction).generateAbsoluteUrl())
            rowContent.add(externalInfraction.pixTransaction.endToEndIdentifier)

            writer.writeNext(rowContent as String[])
        }

        writer.flush()
        stringWriter.flush()
        writer.close()
        stringWriter.close()

        String period = initialDate == finalDate ? "${initialDate}" : "${initialDate}_ate_${finalDate}"
        String fileName = "relatorio_de_infracoes_dia_${period}"
        return fileService.createFile("${fileName}.csv", stringWriter.toString(), StandardCharsets.UTF_8.toString())
    }

    private void sendEmail(Customer customer, String initialDate, String finalDate, AsaasFile csvFile, InfractionExportMailDTO mailParams) {
        String mailFrom = grailsApplication.config.asaas.sender
        String mailTo = mailParams.mailTo
        List<String> bccMails = mailParams.bccMails

        List<Map> attachmentList = []
        attachmentList.add([attachmentName: csvFile.originalName, attachmentBytes: csvFile.getFileBytes()])

        Map options = [:]
        options.attachmentList = attachmentList
        options.multipart = true

        String mailBody = "Seguem as novas infrações de Pix recebidas na conta de ${customer.getProviderName()}"

        String period = initialDate == finalDate ? "${initialDate}" : "${initialDate} até ${finalDate}"
        String mailSubject = "Infrações Pix recebidas na data de ${period} para a conta de ${customer.getProviderName()}"

        Boolean emailSent = messageService.send(mailFrom, mailTo, bccMails, mailSubject, mailBody, false, options)
        if (!emailSent) throw new RuntimeException("Erro ao enviar o email para o cliente id: ${customer.id}.")
    }

    private BusinessValidation validateSend(Long customerId, Date initialDate, Date finalDate) {
        BusinessValidation validatedBusiness = new BusinessValidation()
        if (!(customerId && initialDate && finalDate)) validatedBusiness.addError("PixExternalInfraction.reportGeneration.invalidParams")

        Customer customer = Customer.read(customerId)

        Boolean customerHasInfractionReportEnable = CustomerParameter.getStringValue(customer, CustomerParameterName.ENABLE_GENERATE_EXTERNAL_INFRACTION_REPORT).asBoolean()
        if (!customerHasInfractionReportEnable) validatedBusiness.addError("PixExternalInfraction.reportGeneration.notEnabled", [customerId])

        return validatedBusiness
    }
}

package com.asaas.service.pix

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.pix.PixTransaction
import com.asaas.exception.BusinessException
import com.asaas.pix.PixTransactionStatus
import com.asaas.pix.batchFileBuilder.PixCnab750FileBuilder
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class PixCnab750TransactionReportFileService {

    def asyncActionService
    def fileService
    def messageService

    public void save(Customer customer, Date date) {
        if (!date) throw new BusinessException("Informe a data na requisição para obter o arquivo CNAB 750.")

        if (date > new Date().clearTime()) throw new BusinessException("A data da requisição não pode ser maior do que a data atual.")

        Map asyncActionDataMap = [customerId: customer.id, date: date]

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionDataMap, AsyncActionType.PIX_CNAB750_FILE_EXPORT)) throw new BusinessException("Já existe um arquivo com a data informada em processamento.")

        asyncActionService.save(AsyncActionType.PIX_CNAB750_FILE_EXPORT, asyncActionDataMap)
    }

    public void sendPendingExportFilesCnab750() {
        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.PIX_CNAB750_FILE_EXPORT, null)
        for (Map asyncActionData : asyncActionDataList) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)

                Date filterDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.date, CustomDateUtils.DATABASE_DATE_FORMAT)

                List<PixTransaction> pixTransactionList = PixTransaction.credit([status: PixTransactionStatus.DONE, customer: customer, "dateCreated[ge]": filterDate.clearTime(), "dateCreated[le]": CustomDateUtils.setTimeToEndOfDay(filterDate)]).list(readOnly: true)

                String fileName = "CNAB750_ASAAS_${filterDate.format('yyyyMMdd')}.txt"

                PixCnab750FileBuilder pixCnab750FileBuilder = new PixCnab750FileBuilder()
                pixCnab750FileBuilder.buildFile(pixTransactionList, customer)

                FileUtils.withDeletableTempFile(pixCnab750FileBuilder.fileContents, "UTF-8", { File tempFile ->
                    AsaasFile asaasFile = fileService.createFile(customer, tempFile, fileName)
                    messageService.sendCnab750File(asaasFile, customer, filterDate)
                })

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [
                    logErrorMessage: "PixCnab750TransactionReportFileService.sendPendingExportFilesCnab >> Erro na exportação do arquivo CNAB750. AsyncAction ID: ${asyncActionData.asyncActionId} | CustomerId: ${asyncActionData.customerId}",
                    onError: { hasError = true }
                ]
            )
            if (hasError) asyncActionService.sendToReprocessIfPossible(asyncActionData.asyncActionId)
        }
    }
}

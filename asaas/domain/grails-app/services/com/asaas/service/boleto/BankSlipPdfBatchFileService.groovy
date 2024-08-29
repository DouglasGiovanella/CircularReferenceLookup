package com.asaas.service.boleto

import com.asaas.boleto.BankSlipPdfBatchFileStatus
import com.asaas.domain.boleto.BankSlipPdfBatchFile
import com.asaas.domain.boleto.BankSlipPdfBatchFileItem
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.payment.Payment
import com.asaas.exception.BusinessException
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BankSlipPdfBatchFileService {

    def boletoService
    def customerMessageService
    def fileService
    def grailsApplication

    public Map getBankSlipPdfBatchFile(Customer customer, Map params, Boolean original) {
        Integer paymentCount = Payment.query(params + [customer: customer]).count()

        if (paymentCount == 0) throw new BusinessException(Utils.getMessageProperty("payment.bankSlipPdfBatchFile.bankSlip.notFound.message"))
        if (paymentCount > 1000 && !params.bypassMaxPayments) throw new BusinessException("É possível gerar até 1.000 boletos de cada vez, porém foram encontrados ${paymentCount} para os filtros selecionados. Altere os filtros para que a quantia de boletos fique abaixo de 1.000.")
        if (!boletoService.isAdminAndOriginalBankSlip(original) && boletoService.boletoDisabledForCustomer(customer)) throw new BusinessException(Utils.getMessageProperty("boletoDisabled.messageCustomer.preValidated", [grailsApplication.config.asaas.phone]))

        Map bankSlipPdfFileData = [:]

        List<Payment> paymentList = Payment.query(params + [customer: customer]).list(max: params.max, offset: params.offset)

        paymentList.removeAll { it.billingType.isCreditCard() || it.billingType.isPix() }

        if (!paymentList) throw new BusinessException(Utils.getMessageProperty("payment.bankSlipPdfBatchFile.bankSlip.notFound.message"))

        if (shouldSendBankSlipBatchFileByEmail(paymentList.size())) {
            save(customer, paymentList)
            bankSlipPdfFileData.message = "payment.bankSlipPdfBatchFile.sentByEmail.message"
        } else {
            bankSlipPdfFileData.file = boletoService.buildPdfListBytes(customer, paymentList, original).encodeBase64().toString()
            bankSlipPdfFileData.fileName = "Boletos"
            bankSlipPdfFileData.fileExtension = "pdf"
        }

        return bankSlipPdfFileData
    }

    public void processPendingBankSlipPdfBatchFile() {
        List<Long> bankSlipPdfBatchFileList = BankSlipPdfBatchFile.query([column: "id", status: BankSlipPdfBatchFileStatus.PENDING]).list(max: 5)

        for (Long bankSlipPdfBatchFileId in bankSlipPdfBatchFileList) {
            Boolean processedFile = false

            Utils.withNewTransactionAndRollbackOnError({
                BankSlipPdfBatchFile bankSlipPdfBatchFile = BankSlipPdfBatchFile.get(bankSlipPdfBatchFileId)

                List<Payment> paymentList = BankSlipPdfBatchFileItem.query([column: "payment", bankSlipPdfBatchFile: bankSlipPdfBatchFile]).list()

                byte[] bankSlipByteFile = boletoService.buildPdfListBytes(bankSlipPdfBatchFile.customer, paymentList, false)
                File bankSlipPdfFile = FileUtils.buildFileFromBytes(bankSlipByteFile)

                AsaasFile asaasFile = fileService.createFile(bankSlipPdfBatchFile.customer, bankSlipPdfFile, "Boletos.pdf")

                bankSlipPdfBatchFile.file = asaasFile

                String subject = "Seu download de boletos do Asaas solicitado em ${CustomDateUtils.formatDateTime(bankSlipPdfBatchFile.dateCreated)} está pronto"
                customerMessageService.sendBankSlipPdfBatchFileByEmail(bankSlipPdfBatchFile.customer, bankSlipPdfBatchFile.file, bankSlipPdfBatchFile.email, subject)

                bankSlipPdfBatchFile.status = BankSlipPdfBatchFileStatus.SENT
                bankSlipPdfBatchFile.save(failOnError: true)

                processedFile = true
            }, [logErrorMessage: "BankSlipPdfBatchFileService.processPendingBankSlipPdfBatchFile >> Erro ao enviar arquivo em lote ${bankSlipPdfBatchFileId}"])

            if (processedFile) continue

            Utils.withNewTransactionAndRollbackOnError({
                BankSlipPdfBatchFile bankSlipPdfBatchFile = BankSlipPdfBatchFile.get(bankSlipPdfBatchFileId)
                bankSlipPdfBatchFile.status = BankSlipPdfBatchFileStatus.ERROR
                bankSlipPdfBatchFile.save(failOnError: true)
            }, [logErrorMessage: "BankSlipPdfBatchFileService.processPendingBankSlipPdfBatchFile >> Erro ao salvar status do arquivo em lote ${bankSlipPdfBatchFileId}"])
        }
    }

    private void save(Customer customer, List<Payment> paymentList) {
        BankSlipPdfBatchFile bankSlipPdfBatchFile = new BankSlipPdfBatchFile()
        bankSlipPdfBatchFile.status = BankSlipPdfBatchFileStatus.PENDING
        bankSlipPdfBatchFile.customer = customer
        bankSlipPdfBatchFile.email = UserUtils.getCurrentUser().email
        bankSlipPdfBatchFile.save(failOnError: true)

        for (Payment payment : paymentList) {
            BankSlipPdfBatchFileItem bankSlipPdfBatchFileItem = new BankSlipPdfBatchFileItem()
            bankSlipPdfBatchFileItem.bankSlipPdfBatchFile = bankSlipPdfBatchFile
            bankSlipPdfBatchFileItem.payment = payment
            bankSlipPdfBatchFileItem.save(failOnError: true)
        }
    }

    private Boolean shouldSendBankSlipBatchFileByEmail(Integer paymentListSize) {
        return paymentListSize > BankSlipPdfBatchFile.BATCH_FILE_MAXIMUM_FILES_FOR_DOWNLOAD
    }
}

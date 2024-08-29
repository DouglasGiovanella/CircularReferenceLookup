package com.asaas.service.payment

import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.payment.PaymentExportBatchFile
import com.asaas.domain.payment.PaymentExportBatchFileItem
import com.asaas.exception.BusinessException
import com.asaas.export.payment.PaymentExporter
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentExportBatchFileStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import grails.gorm.PagedResultList
import grails.transaction.Transactional
import groovy.json.JsonOutput
import org.hibernate.Query

@Transactional
class PaymentExportService {

    public static final MAXIMUM_ITEMS_FOR_DOWNLOAD = 1500

    private static final MAXIMUM_ITEMS = 9999

    def paymentService
    def fileService
    def customerMessageService
    def sessionFactory
    def paymentListService
    def dataSource

    public Map export(Customer customer, String recipientEmail, Long customerAccountId, Map search) {
        search.columnList = [
            "id",
            "externalReference",
            "customerAccount.name",
            "customerAccount.cpfCnpj",
            "customerAccount.email",
            "customerAccount.additionalEmails",
            "customerAccount.mobilePhone",
            "customerAccount.phone",
            "installment.id",
            "subscriptionPayment.id",
            "billingType",
            "dueDate",
            "originalDueDate",
            "paymentDate",
            "value",
            "originalValue",
            "netValue",
            "status",
            "nossoNumero",
            "description",
            "estimatedCreditDate",
            "dateCreated",
            "confirmedDate",
            "creditDate",
            "createdBy.username"
        ]

        Map paymentList = paymentListService.listWithTimeout(customer, search + [customerAccountId: customerAccountId], MAXIMUM_ITEMS + 1, 0, false)
        List exportList = paymentList.payments

        if (!exportList) throw new BusinessException("Não foram encontradas cobranças a serem exportadas. Verifique os filtros informados e tente novamente.")

        if (exportList.size() > MAXIMUM_ITEMS) {
            AsaasLogger.warn("PaymentExportService.export >> customer ${customer.id} exportando uma quantidade maior que o limite definido de ${MAXIMUM_ITEMS}")
            throw new BusinessException("Não é possível exportar mais do que ${MAXIMUM_ITEMS} cobranças. Aplique mais filtros para diminuir a quantidade de registros")
        }

        Map responseMap = [:]
        if (exportList.size() <= MAXIMUM_ITEMS_FOR_DOWNLOAD) {
            setPaymentsGroupListNameColumn(customer, exportList)
            responseMap.fileBytes = new PaymentExporter(exportList).exportXlsx()
            responseMap.asyncExport = false
        } else {
            String filterDataHash = JsonOutput.toJson(search).encodeAsMD5()
            String pendingBatchFileRecipientEmail = PaymentExportBatchFile.query([
                column: "recipientEmail",
                customer: customer,
                filterDataHash: filterDataHash,
                status: PaymentExportBatchFileStatus.PENDING
            ]).get()

            if (pendingBatchFileRecipientEmail) {
                throw new BusinessException("A exportação das cobranças com estes mesmos registros já está em processamento. Em breve nosso sistema gerará a planilha e enviará para o email ${pendingBatchFileRecipientEmail}")
            }

            savePaymentsForAsyncExport(customer, exportList, recipientEmail, filterDataHash)
            responseMap.asyncExport = true
        }

        return responseMap
    }

    public void processPendingPaymentExportBatchFile() {
        List<Long> batchFileIdList = PaymentExportBatchFile.query([column: "id", status: PaymentExportBatchFileStatus.PENDING, order: "asc", sort: "id"]).list(max: 5)

        for (Long batchFileId in batchFileIdList) {
            Boolean isFileProcessed = false

            Utils.withNewTransactionAndRollbackOnError({
                PaymentExportBatchFile batchFile = PaymentExportBatchFile.get(batchFileId)

                List<Map> paymentList = PaymentExportBatchFileItem.query([
                    columnList            : [
                        "payment.id",
                        "payment.externalReference",
                        "customerAccount.name",
                        "customerAccount.cpfCnpj",
                        "customerAccount.email",
                        "customerAccount.additionalEmails",
                        "customerAccount.mobilePhone",
                        "customerAccount.phone",
                        "installment.id",
                        "subscriptionPayment.id",
                        "payment.billingType",
                        "payment.dueDate",
                        "payment.originalDueDate",
                        "payment.paymentDate",
                        "payment.value",
                        "payment.originalValue",
                        "payment.netValue",
                        "payment.status",
                        "payment.nossoNumero",
                        "payment.description",
                        "payment.estimatedCreditDate",
                        "payment.dateCreated",
                        "payment.confirmedDate",
                        "payment.creditDate",
                        "createdBy.username"
                    ],
                    paymentExportBatchFile: batchFile,
                    order                 : "desc", sort: "payment.id"
                ]).list()

                setPaymentsComplexColumns(batchFileId, paymentList)
                byte[] fileBytes = new PaymentExporter(paymentList).setIsPaymentExportBatchFile().exportXlsx()

                File file = FileUtils.buildFileFromBytes(fileBytes)
                AsaasFile asaasFile = fileService.createFile(batchFile.customer, file, "Cobranças.xlsx")
                file.delete()

                String subject = "A exportação de cobranças solicitada em ${CustomDateUtils.formatDateTime(batchFile.dateCreated)} está pronta"
                customerMessageService.sendPaymentExportBatchFileByEmail(batchFile.customer, asaasFile, batchFile.recipientEmail, subject)

                batchFile.file = asaasFile
                batchFile.status = PaymentExportBatchFileStatus.SENT
                batchFile.save(failOnError: true)

                isFileProcessed = true
            }, [logErrorMessage: "PaymentExportService.processPendingPaymentExportBatchFile >> Erro ao enviar arquivo ${batchFileId}"])

            Utils.flushAndClearSession()

            if (isFileProcessed) continue

            Utils.withNewTransactionAndRollbackOnError({
                PaymentExportBatchFile batchFile = PaymentExportBatchFile.get(batchFileId)
                batchFile.status = PaymentExportBatchFileStatus.ERROR
                batchFile.save(failOnError: true)
            }, [logErrorMessage: "PaymentExportService.processPendingPaymentExportBatchFile >> Erro ao salvar status do arquivo ${batchFileId}"])
        }
    }

    private List<Map> setPaymentsComplexColumns(Long batchFileId, List<Map> payments) {
        String sql = '''
         SELECT bfi.payment_id,
                GROUP_CONCAT(cag.name SEPARATOR ', ') groupNameList
           FROM payment_export_batch_file_item bfi
      LEFT JOIN payment pay
             ON pay.id = bfi.payment_id
      LEFT JOIN subscription_payment sbp
             ON sbp.payment_id = bfi.payment_id
      LEFT JOIN group_customer_account gca
             ON gca.customer_account_id = pay.customer_account_id
      LEFT JOIN customer_account_group cag
             ON cag.id = gca.group_id
          WHERE bfi.payment_export_batch_file_id = :batchFileId
       GROUP BY bfi.payment_id'''

        def query = sessionFactory.currentSession.createSQLQuery(sql)
        query.setLong("batchFileId", batchFileId)
        List<Map> paymentsDataColumns = query.list().collect { [paymentId: it[0], groupNameList: it[1]] }

        for (Map paymentDataColumn : paymentsDataColumns) {
            Map payment = payments.find({ it."payment.id" == paymentDataColumn.paymentId })
            payment."payment.groupNameList" = paymentDataColumn.groupNameList
        }
    }

    private setPaymentsGroupListNameColumn(Customer customer, List<Map> payments) {
        String sql = """
        SELECT pay.id paymentId,
               GROUP_CONCAT(cag.name SEPARATOR ', ') groupNameList
          FROM payment pay
     LEFT JOIN group_customer_account gca
            ON gca.customer_account_id = pay.customer_account_id
     LEFT JOIN customer_account_group cag
            ON cag.id = gca.group_id
         WHERE pay.id IN (:paymentsIdList)
           AND pay.provider_id = :customerId
      GROUP BY pay.id"""
        Query query = sessionFactory.currentSession.createSQLQuery(sql)
        query.setParameterList("paymentsIdList", payments.collect { it.id })
        query.setParameter("customerId", customer.id)

        List<Map> paymentsDataColumns = query.list().collect { [paymentId: it[0], groupNameList: it[1]] }

        for (Map paymentDataColumn : paymentsDataColumns) {
            Map payment = payments.find({ it.id == paymentDataColumn.paymentId })
            payment.groupNameList = paymentDataColumn.groupNameList
        }
    }

    private void savePaymentsForAsyncExport(Customer customer, List<Map> paymentsToExport, String recipientEmail, String filterDataHash) {
        Long batchFileId

        Utils.withNewTransactionAndRollbackOnError({
            PaymentExportBatchFile batchFile = new PaymentExportBatchFile()
            batchFile.status = PaymentExportBatchFileStatus.PENDING
            batchFile.customer = customer
            batchFile.recipientEmail = recipientEmail
            batchFile.filterDataHash = filterDataHash
            batchFile.save(failOnError: true)
            batchFileId = batchFile.id
        }, [
            ignoreStackTrace: true,
            onError: { Exception exception ->
                AsaasLogger.error("PaymentExportService.savePaymentsForAsyncExport >> Erro ao salvar o export do pagamento do customer ${customer.id}", exception)
                throw exception
            }
        ])

        List<Map> batchFileItemToInsert = paymentsToExport.collect { Map paymentInfo ->
            return [
                "version": 0,
                "date_created": new Date(),
                "deleted": false,
                "last_updated": new Date(),
                "payment_id": paymentInfo.id,
                "payment_export_batch_file_id": batchFileId
            ]
        }

        DatabaseBatchUtils.insertInBatchWithNewTransaction(dataSource, "payment_export_batch_file_item", batchFileItemToInsert)
    }
}

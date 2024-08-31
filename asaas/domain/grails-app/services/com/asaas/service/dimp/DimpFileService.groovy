package com.asaas.service.dimp

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.dimp.DimpBatchFileStatus
import com.asaas.dimp.DimpStatus
import com.asaas.dimp.DimpFileBuilder
import com.asaas.dimp.DimpFileType
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.integration.dimp.DimpConfirmedTransaction
import com.asaas.domain.integration.dimp.DimpConfirmedPixTransaction
import com.asaas.domain.integration.dimp.DimpCustomer
import com.asaas.domain.integration.dimp.DimpRefundedTransaction
import com.asaas.domain.integration.dimp.DimpRefundedPixTransaction
import com.asaas.domain.integration.dimp.batchfile.DimpBatchFile
import com.asaas.integration.aws.managers.S3Manager
import com.asaas.integration.azure.blob.managers.AzureBlobManager
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class DimpFileService {

    def fileService
    def grailsApplication

    public Boolean sendPendingFile() {
        List<DimpFileType> fileTypeList = DimpFileType.listTypesAvailableForAutomaticProcessing()
        DimpBatchFile pendingFile = DimpBatchFile.query(["fileType[in]": fileTypeList, status: DimpBatchFileStatus.AWAITING_TRANSMISSION, sort: "id", order: "asc"]).get()
        if (!pendingFile) return false

        Date originalBatchDate = getOriginalBatchDate(pendingFile)
        String fileName = DimpFileBuilder.buildFileName(pendingFile.fileType, originalBatchDate, pendingFile.id)

        Date today = new Date().clearTime()
        Date s3UploadStartDate = CustomDateUtils.fromString("02/02/2024")
        if (today >= s3UploadStartDate) {
            sendDimpBatchFileToS3Bucket(pendingFile, fileName)
        } else {
            sendDimpBatchFileToAzureBlob(pendingFile, fileName)
        }

        return true
    }

    public void sendDimpBatchFileToAzureBlob(DimpBatchFile dimpBatchFile, String fileName) {
        if (!dimpBatchFile.file) throw new RuntimeException("DimpFileService.sendDimpBatchFileToAzureBlob: Não existe AsaasFile associado ao DimpBatchFile [${dimpBatchFile.id}]")
        if (!fileName) fileName = dimpBatchFile.file.originalName

        String accountName = grailsApplication.config.dimp.ecommit.azure.accountName
        String accountKey = grailsApplication.config.dimp.ecommit.azure.accountKey
        String container = grailsApplication.config.dimp.ecommit.azure.container

        AzureBlobManager azureBlobManager = new AzureBlobManager(accountName, accountKey, container)

        azureBlobManager.uploadFile(dimpBatchFile.file.getFile(), fileName)

        if (!azureBlobManager.isSuccessful()) {
            throw new RuntimeException("DimpFileService.sendDimpBatchFileToAzureBlob - Erro na requisição PUT para o DimpBatchFile [${dimpBatchFile.id}] Status [${azureBlobManager.getStatusCode()}]")
        }

        dimpBatchFile.sentDate = new Date()
        dimpBatchFile.status = DimpBatchFileStatus.TRANSMITTED
        dimpBatchFile.save(failOnError: true)
    }

    public void sendDimpBatchFileToS3Bucket(DimpBatchFile dimpBatchFile, String fileName) {
        if (!dimpBatchFile.file) throw new RuntimeException("DimpFileService.sendDimpBatchFileToS3Bucket: Não existe AsaasFile associado ao DimpBatchFile [${dimpBatchFile.id}]")
        if (!fileName) fileName = dimpBatchFile.file.originalName

        Map dimpBucketConfigAccess = getDimpBucketConfigAccess()
        final String s3AccessKey =  AsaasApplicationHolder.config.asaas.dimp.s3."${dimpBucketConfigAccess.configAccessKey}"
        final String s3SecretKey = AsaasApplicationHolder.config.asaas.dimp.s3."${dimpBucketConfigAccess.configSecretKey}"
        final String bucket = AsaasApplicationHolder.config.asaas.dimp.s3."${dimpBucketConfigAccess.configBucket}"

        S3Manager s3Manager = new S3Manager(s3AccessKey, s3SecretKey, bucket, fileName)
        Boolean fileUploaded = s3Manager.write(dimpBatchFile.file.getFile())

        if (!fileUploaded) throw new RuntimeException("DimpFileService.sendDimpBatchFileToS3Bucket - Erro ao enviar DimpBatchFile para o s3. DimpBatchFile: [${dimpBatchFile.id}] Bucket: [${s3Manager.bucket}]")

        dimpBatchFile.sentDate = new Date()
        dimpBatchFile.status = DimpBatchFileStatus.TRANSMITTED
        dimpBatchFile.save(failOnError: true)
    }

    public Map getDimpBucketConfigAccess() {
        Date today = new Date()
        Date newS3BucketStartDate = CustomDateUtils.fromString("22/04/2024")

        if (today >= newS3BucketStartDate) return [configAccessKey: "accessKey", configSecretKey: "secretKey", configBucket: "bucket"]

        return [configAccessKey: "accessKeyOld", configSecretKey: "secretKeyOld", configBucket: "bucketOld"]
    }

    public Boolean processAwaitingFileCreation(DimpFileType type) {
        DimpBatchFile dimpBatchFile = DimpBatchFile.byStatus(DimpBatchFileStatus.AWAITING_FILE_CREATION, [fileType: type, sort: "id", order: "asc"]).get()
        if (!dimpBatchFile) return false

        if (type.isCustomers()) createCustomersFile(dimpBatchFile)
        if (type.isConfirmedTransactions()) createConfirmedTransactionsFile(dimpBatchFile)
        if (type.isConfirmedPixTransactions()) createConfirmedPixTransactionsFile(dimpBatchFile.id)
        if (type.isRefundedTransactions()) createRefundedTransactionsFile(dimpBatchFile)
        if (type.isRefundedPixTransactions()) createRefundedPixTransactionsFile(dimpBatchFile)

        return true
    }

    public DimpBatchFile createRetroactiveCustomersFile(Date startDate, Date endDate, Map params, String fileName, Date originalDate) {
        Utils.withNewTransactionAndRollbackOnError({
            Map queryParams = [:]
            queryParams."relationshipStartDate[ge]" = startDate
            queryParams."relationshipStartDate[lt]" = endDate
            queryParams."status[in]" = [DimpStatus.PENDING, DimpStatus.DONE]
            queryParams.exists = true

            if (params.idList) queryParams."id[in]" = params.idList
            if (params.cpfCnpjList) queryParams."cpfCnpj[in]" = params.cpfCnpjList
            if (params.stateList) queryParams."state[in]" = params.stateList

            List<Long> dimpCustomerIdList = DimpCustomer.query(queryParams).list()
            if (!dimpCustomerIdList) return null

            DimpBatchFile dimpBatchFile = new DimpBatchFile()
            dimpBatchFile.fileType = DimpFileType.RETROACTIVE_CUSTOMERS
            dimpBatchFile.file = createAsaasFile(dimpCustomerIdList, fileName, DimpFileType.RETROACTIVE_CUSTOMERS, originalDate)
            dimpBatchFile.save(failOnError: true)

            return dimpBatchFile
        }, [logErrorMessage: "Erro na geração do arquivo de DimpCustomer retroativo."])
    }

    public DimpBatchFile createCustomersFile(DimpBatchFile dimpBatchFile) {
        Boolean hasDimpCustomers = verifyIfThereAreItemsLinkedToBatchFile(DimpCustomer, dimpBatchFile)
        if (!hasDimpCustomers) return

        DimpCustomer.executeUpdate("update DimpCustomer set status = :doneStatus, lastUpdated = :lastUpdated where dimpBatchFile = :dimpBatchFile", [doneStatus: DimpStatus.DONE, lastUpdated: new Date(), dimpBatchFile: dimpBatchFile])

        Utils.withNewTransactionAndRollbackOnError({
            dimpBatchFile = createAsaasFileForDimpBatchFile(dimpBatchFile.id)

            setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_TRANSMISSION)

            return dimpBatchFile
        }, [logErrorMessage: "Erro na geração do arquivo de DimpCustomer."])
    }

    public DimpBatchFile createConfirmedTransactionsFile(DimpBatchFile dimpBatchFile) {
        Boolean hasDimpConfirmedTransactions = verifyIfThereAreItemsLinkedToBatchFile(DimpConfirmedTransaction, dimpBatchFile)
        if (!hasDimpConfirmedTransactions) return

        DimpConfirmedTransaction.executeUpdate("update DimpConfirmedTransaction set status = :doneStatus, lastUpdated = :lastUpdated where dimpBatchFile = :dimpBatchFile", [doneStatus: DimpStatus.DONE, lastUpdated: new Date(), dimpBatchFile: dimpBatchFile])

        Utils.withNewTransactionAndRollbackOnError({
            dimpBatchFile = createAsaasFileForDimpBatchFile(dimpBatchFile.id)

            setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_TRANSMISSION)

            return dimpBatchFile
        }, [logErrorMessage: "Erro na geração do arquivo de DimpConfirmedTransaction."])
    }

    public void createConfirmedPixTransactionsFile(Long dimpBatchFileId) {
        Boolean hasDimpConfirmedPixTransactions = verifyIfThereAreItemsLinkedToBatchFile(DimpConfirmedPixTransaction, DimpBatchFile.get(dimpBatchFileId))
        if (!hasDimpConfirmedPixTransactions) return

        DimpConfirmedPixTransaction.executeUpdate("update DimpConfirmedPixTransaction set status = :doneStatus, lastUpdated = :lastUpdated where dimpBatchFile.id = :dimpBatchFileId", [doneStatus: DimpStatus.DONE, lastUpdated: new Date(), dimpBatchFileId: dimpBatchFileId])

        Utils.withNewTransactionAndRollbackOnError({
            DimpBatchFile dimpBatchFile = createAsaasFileForDimpBatchFile(dimpBatchFileId)

            setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_TRANSMISSION)
        }, [logErrorMessage: "Erro na geração do arquivo de DimpConfirmedPixTransaction."])
    }

    public String createRetroactiveConfirmedTransactionsFile(List<Long> idList, String fileName) {
        String asaasFilePublicId
        Utils.withNewTransactionAndRollbackOnError({
            Map queryParams = [:]
            queryParams."id[in]" = idList
            queryParams.exists = true

            List<Long> dimpConfirmedTransactionIdList = DimpConfirmedTransaction.query(queryParams).list()
            if (!dimpConfirmedTransactionIdList) return null

            DimpBatchFile dimpBatchFile = new DimpBatchFile()
            dimpBatchFile.fileType = DimpFileType.RETROACTIVE_CONFIRMED_TRANSACTIONS
            dimpBatchFile.file = createAsaasFile(dimpConfirmedTransactionIdList, fileName, DimpFileType.RETROACTIVE_CONFIRMED_TRANSACTIONS, null)
            dimpBatchFile.save(failOnError: true)

            asaasFilePublicId = dimpBatchFile.file.publicId
        }, [logErrorMessage: "Erro na geração do arquivo de DimpConfirmedTransaction retroativo."])

        return asaasFilePublicId
    }

    public DimpBatchFile createRefundedTransactionsFile(DimpBatchFile dimpBatchFile) {
        Boolean hasRefundedTransactions = verifyIfThereAreItemsLinkedToBatchFile(DimpRefundedTransaction, dimpBatchFile)
        if (!hasRefundedTransactions) return

        DimpRefundedTransaction.executeUpdate("update DimpRefundedTransaction set status = :doneStatus, lastUpdated = :lastUpdated where dimpBatchFile = :dimpBatchFile", [doneStatus: DimpStatus.DONE, lastUpdated: new Date(), dimpBatchFile: dimpBatchFile])

        Utils.withNewTransactionAndRollbackOnError({
            dimpBatchFile = createAsaasFileForDimpBatchFile(dimpBatchFile.id)

            setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_TRANSMISSION)

            return dimpBatchFile
        }, [logErrorMessage: "Erro na geração do arquivo de DimpRefundedTransaction."])
    }

    public void createRefundedPixTransactionsFile(DimpBatchFile dimpBatchFile) {
        Boolean hasRefundedPixTransactions = verifyIfThereAreItemsLinkedToBatchFile(DimpRefundedPixTransaction, dimpBatchFile)
        if (!hasRefundedPixTransactions) return

        DimpRefundedTransaction.executeUpdate("update DimpRefundedPixTransaction set status = :doneStatus, lastUpdated = :lastUpdated where dimpBatchFile.id = :dimpBatchFileId", [doneStatus: DimpStatus.DONE, lastUpdated: new Date(), dimpBatchFileId: dimpBatchFile.id])

        Long dimpBatchFileId = dimpBatchFile.id
        Utils.withNewTransactionAndRollbackOnError({
            dimpBatchFile = createAsaasFileForDimpBatchFile(dimpBatchFileId)
            setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_TRANSMISSION)
        }, [logErrorMessage: "Erro na geração do arquivo de DimpRefundedTransaction."])
    }

    public DimpBatchFile createAsaasFileForDimpBatchFile(Long dimpBatchFileId) {
        List<Long> idList
        DimpBatchFile dimpBatchFile = DimpBatchFile.get(dimpBatchFileId)

        if (dimpBatchFile.fileType.isCustomers()) {
            idList = DimpCustomer.query([dimpBatchFile: dimpBatchFile, column: "id", sort: "id", order: "asc"]).list()
        } else if (dimpBatchFile.fileType.isConfirmedTransactions()) {
            idList = DimpConfirmedTransaction.query([dimpBatchFile: dimpBatchFile, column: "id", sort: "id", order: "asc"]).list()
        } else if (dimpBatchFile.fileType.isConfirmedPixTransactions()) {
            idList = DimpConfirmedPixTransaction.query([column: "id", dimpBatchFileId: dimpBatchFile.id, sort: "id", order: "asc"]).list()
        } else if (dimpBatchFile.fileType.isRefundedTransactions()) {
            idList = DimpRefundedTransaction.query([dimpBatchFile: dimpBatchFile, column: "id", sort: "id", order: "asc"]).list()
        }  else if (dimpBatchFile.fileType.isRefundedPixTransactions()) {
            idList = DimpRefundedPixTransaction.query([column: "id", dimpBatchFileId: dimpBatchFile.id, sort: "id", order: "asc"]).list()
        } else {
            throw new RuntimeException("Tipo de DimpBatchFile não suportado para geração do arquivo.")
        }

        dimpBatchFile.file = createAsaasFile(idList, "DIMP_${dimpBatchFile.fileType}-${dimpBatchFileId}", dimpBatchFile.fileType, dimpBatchFile.dateCreated)
        dimpBatchFile.save(failOnError: true)

        return dimpBatchFile
    }

    public Long findOrCreateDimpBatchFile(DimpFileType type, DimpBatchFileStatus status) {
        Long dimpBatchFileId = DimpBatchFile.byStatus(status, [column: "id", fileType: type, sort: "id", order: "desc"]).get()
        if (dimpBatchFileId) return dimpBatchFileId

        return createDimpBatchFile(type, status)
    }

    public Long createDimpBatchFile(DimpFileType type, DimpBatchFileStatus status) {
        Long dimpBatchFileId
        Utils.withNewTransactionAndRollbackOnError({
            DimpBatchFile dimpBatchFile = new DimpBatchFile()
            dimpBatchFile.fileType = type
            dimpBatchFile.status = status
            dimpBatchFile.save(failOnError: true, flush: true)

            dimpBatchFileId = dimpBatchFile.id
        })

        return dimpBatchFileId
    }

    public void setDimpBatchFileStatus(DimpBatchFile dimpBatchFile, DimpBatchFileStatus status) {
        dimpBatchFile.status = status
        dimpBatchFile.save(failOnError: true)
    }

    public void createDimpBatchFileForPendingDimpCustomers() {
        final Integer dimpCustomerLimit = 1000
        List<Long> pendingDimpCustomerIdList = DimpCustomer.query([column: "id", "status": DimpStatus.PENDING, "lastUpdatedDateFromCustomerWithSameCpfCnpj": new Date()]).list(max: dimpCustomerLimit)

        if (!pendingDimpCustomerIdList) return

        Long dimpBatchFileId = findOrCreateDimpBatchFile(DimpFileType.CUSTOMERS, DimpBatchFileStatus.PENDING)

        final Integer flushEvery = 50
        Utils.forEachWithFlushSession(pendingDimpCustomerIdList, flushEvery, { Long dimpCustomerId ->
            Utils.withNewTransactionAndRollbackOnError({
                DimpCustomer dimpCustomer = DimpCustomer.get(dimpCustomerId)
                dimpCustomer.dimpBatchFile = DimpBatchFile.load(dimpBatchFileId)
                dimpCustomer.save(failOnError: true)
            }, [logErrorMessage: "DimpFileService.createDimpBatchFileForPendingDimpCustomers() >> Erro ao salvar o dimpBatchFile para o DimpCustomer de id: [${dimpCustomerId}]"])
        })

        Utils.withNewTransactionAndRollbackOnError({
            DimpBatchFile dimpBatchFile = DimpBatchFile.get(dimpBatchFileId)
            setDimpBatchFileStatus(dimpBatchFile, DimpBatchFileStatus.AWAITING_FILE_CREATION)
        }, [logErrorMessage: "DimpFileService.createDimpBatchFileForPendingDimpCustomers() >> Erro ao alterar status do DimpBatchFile: ${dimpBatchFileId}"])
    }

    private Date getOriginalBatchDate(DimpBatchFile dimpBatchFile) {
        Date dateOriginBatch
        if (dimpBatchFile.fileType.isConfirmedTransactions()) {
            dateOriginBatch = DimpConfirmedTransaction.query([column: "confirmedDate", "dimpBatchFileId": dimpBatchFile.id]).get()
        } else if (dimpBatchFile.fileType.isConfirmedPixTransactions()) {
            dateOriginBatch = DimpConfirmedPixTransaction.query([column: "confirmedDate", "dimpBatchFileId": dimpBatchFile.id]).get()
        } else if (dimpBatchFile.fileType.isRefundedTransactions()) {
            dateOriginBatch = DimpRefundedTransaction.query([column: "refundedDate", "dimpBatchFile": dimpBatchFile]).get()
        } else if (dimpBatchFile.fileType.isRefundedPixTransactions()) {
            dateOriginBatch = DimpRefundedPixTransaction.query([column: "refundedDate", "dimpBatchFileId": dimpBatchFile.id]).get()
        } else {
            dateOriginBatch = dimpBatchFile.dateCreated
        }

        return dateOriginBatch
    }

    private AsaasFile createAsaasFile(List<Long> idList, String fileName, DimpFileType dimpFileType, Date originalDate) {
        File temporaryDiskFile = DimpFileBuilder.buildFile(idList, fileName, dimpFileType, originalDate)
        AsaasFile asaasFile = fileService.createFile(null, temporaryDiskFile, fileName)

        AsaasLogger.info("DimpFileService -> Criado AsaasFile ${fileName} [${asaasFile.id}]")

        temporaryDiskFile.delete()
        return asaasFile
    }

    private Boolean verifyIfThereAreItemsLinkedToBatchFile(Class domainClass, DimpBatchFile dimpBatchFile) {
        Map queryParams = [:]
        queryParams.dimpBatchFileId = dimpBatchFile.id
        queryParams.exists = true

        Boolean hasItemsLinkedToBatchFile = domainClass.query(queryParams).get().asBoolean()
        if (!hasItemsLinkedToBatchFile) {
            dimpBatchFile.deleted = true
            dimpBatchFile.save(failOnError: true)
        }

        return hasItemsLinkedToBatchFile
    }
}

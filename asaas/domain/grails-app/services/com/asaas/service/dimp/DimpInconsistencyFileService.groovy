package com.asaas.service.dimp

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.dimp.DimpFileBuilder
import com.asaas.dimp.DimpFileType
import com.asaas.domain.integration.dimp.DimpConfirmedTransaction
import com.asaas.domain.integration.dimp.DimpConfirmedPixTransaction
import com.asaas.domain.integration.dimp.DimpCustomer
import com.asaas.domain.integration.dimp.batchfile.DimpBatchFile
import com.asaas.integration.aws.managers.S3Manager
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.google.common.io.Files
import grails.transaction.Transactional
import org.apache.commons.io.Charsets

@Transactional
class DimpInconsistencyFileService {

    def dimpFileService
    def fileService

    public Boolean processDimpInconsistencyFiles() {
        Map dimpBucketConfigAccess = dimpFileService.getDimpBucketConfigAccess()
        final String s3AccessKey =  AsaasApplicationHolder.config.asaas.dimp.s3."${dimpBucketConfigAccess.configAccessKey}"
        final String s3SecretKey = AsaasApplicationHolder.config.asaas.dimp.s3."${dimpBucketConfigAccess.configSecretKey}"
        final String bucket = AsaasApplicationHolder.config.asaas.dimp.s3."${dimpBucketConfigAccess.configBucket}"
        final String path = AsaasApplicationHolder.config.asaas.dimp.s3.inconsistencyPath

        S3Manager s3Manager = new S3Manager(s3AccessKey, s3SecretKey, bucket, path)

        List<String> fileNameList = s3Manager.listFileName().findAll { it != path }
        if (!fileNameList) return false

        for (String fileName : fileNameList) {
            processDimpInconsistencyFile(s3Manager, fileName)
        }

        return true
    }

    public void processDimpInconsistencyFile(S3Manager s3Manager, String fileName) {
        final String clientsFilePrefix = "clients_missing"
        final String transactionsFilePrefix = "transactions_missing"

        List<String> fileContentLines = getFileContentLines(s3Manager, fileName)

        if (!fileContentLines) {
            AsaasLogger.warn("DimpInconsistencyFileService.processDimpInconsistencyFiles >> Arquivo de inconsistência vazio: ${fileName}")
            return
        }

        Date originalDate
        if (fileName.contains(clientsFilePrefix)) {
            originalDate = CustomDateUtils.fromString(fileName.substring(38, 47), "yyyyMMdd")
            processCustomerInconsistencies(fileContentLines, originalDate, fileName)
        } else if (fileName.contains(transactionsFilePrefix)) {
            originalDate = CustomDateUtils.fromString(fileName.substring(55, 63), "yyyyMMdd")
            Boolean isConfirmedPixTransaction = fileContentLines.first().startsWith("pix")
            if (isConfirmedPixTransaction) {
                processConfirmedPixTransactionInconsistencies(fileContentLines, originalDate, fileName)
            } else {
                processConfirmedTransactionInconsistencies(fileContentLines, originalDate, fileName)
            }
        } else {
            AsaasLogger.warn("DimpInconsistencyFileService.processDimpInconsistencyFiles >> Arquivo de inconsistência não mapeado para tratamento: ${fileName}")
        }
    }

    private List<String> getFileContentLines(S3Manager s3Manager, String fileName) {
        s3Manager.setFullPath(fileName)

        File dimpInconsistencyFile = s3Manager.read()
        fileService.createFile(null, dimpInconsistencyFile, fileName)
        List<String> fileContent = Files.readLines(dimpInconsistencyFile, Charsets.UTF_8)

        s3Manager.delete()
        dimpInconsistencyFile.delete()

        return fileContent
    }

    private void processConfirmedTransactionInconsistencies(List<String> linesTransactionInconsistency, Date originalDate, String fileName) {
        final Integer idListOperationCollateSize = 500
        List<Long> paymentIdList = linesTransactionInconsistency.collect { Long.parseLong(it) }
        List<Long> dimpTransactionIdToSendList = []
        for (List<Long> paymentIdPartialList : paymentIdList.collate(idListOperationCollateSize)) {
            dimpTransactionIdToSendList.addAll(DimpConfirmedTransaction.query([column: "id", "paymentId[in]": paymentIdPartialList]).list())
        }

        sendCorrectionFileToS3Bucket(dimpTransactionIdToSendList, originalDate, DimpFileType.RETROACTIVE_CONFIRMED_TRANSACTIONS)

        if (paymentIdList.size() > dimpTransactionIdToSendList.size()) {
            AsaasLogger.warn("DimpInconsistencyFileService.processConfirmedTransactionInconsistencies >> Transações do arquivo ${fileName} não encontradas. Transações no arquivo: ${paymentIdList.size()} - Transações encontradas: ${dimpTransactionIdToSendList.size()}")
        }
    }

    private void processConfirmedPixTransactionInconsistencies(List<String> linesTransactionInconsistency, Date originalDate, String fileName) {
        final Integer idListOperationCollateSize = 500
        List<Long> pixTransactionIdList = parseFileLinesToPixTransactionIdList(linesTransactionInconsistency)
        List<Long> dimpConfirmedPixTransactionIdToSendList = []
        for (List<Long> pixTransactionIdPartialList : pixTransactionIdList.collate(idListOperationCollateSize)) {
            dimpConfirmedPixTransactionIdToSendList.addAll(DimpConfirmedPixTransaction.query([column: "id", "pixTransactionId[in]": pixTransactionIdPartialList]).list())
        }

        sendCorrectionFileToS3Bucket(dimpConfirmedPixTransactionIdToSendList, originalDate, DimpFileType.RETROACTIVE_CONFIRMED_PIX_TRANSACTIONS)

        if (pixTransactionIdList.size() > dimpConfirmedPixTransactionIdToSendList.size()) {
            AsaasLogger.warn("DimpInconsistencyFileService.processConfirmedPixTransactionInconsistencies >> Transações do arquivo ${fileName} não encontradas. Transações no arquivo: ${pixTransactionIdList.size()} - Transações encontradas: ${dimpConfirmedPixTransactionIdToSendList.size()}")
        }
    }

    private List<Long> parseFileLinesToPixTransactionIdList(List<String> fileLines) {
        return fileLines.collect { Long.parseLong(it.substring(3)) }
    }

    private void processCustomerInconsistencies(List<String> linesCustomerInconsistency, Date originalDate, String fileName) {
        final Integer idListOperationCollateSize = 500
        List<Long> dimpCustomerIdList = linesCustomerInconsistency.collect { Long.parseLong(it) }
        List<Long> dimpCustomerIdToSendList = []
        for (List<Long> dimpCustomerIdPartialList : dimpCustomerIdList.collate(idListOperationCollateSize)) {
            dimpCustomerIdToSendList.addAll(DimpCustomer.query([column: "id", "id[in]": dimpCustomerIdPartialList]).list())
        }
        sendCorrectionFileToS3Bucket(dimpCustomerIdToSendList, originalDate, DimpFileType.RETROACTIVE_CUSTOMERS)

        if (dimpCustomerIdList.size() > dimpCustomerIdToSendList.size()) {
            dimpCustomerIdList.removeAll(dimpCustomerIdToSendList)
            AsaasLogger.warn("DimpInconsistencyFileService.processCustomerInconsistencies >> Clientes solicitados no arquivo ${fileName} não encontrados no banco de dados: ${dimpCustomerIdList}")
        }
    }

    private void sendCorrectionFileToS3Bucket(List<Long> idList, Date originalDate, DimpFileType dimpFileType) {
        if (!idList) return

        Utils.withNewTransactionAndRollbackOnError({
            String fileName = DimpFileBuilder.buildFileName(dimpFileType, originalDate, null)

            DimpBatchFile dimpBatchFile = new DimpBatchFile()
            dimpBatchFile.fileType = dimpFileType
            dimpBatchFile.file = dimpFileService.createAsaasFile(idList, fileName, dimpFileType, originalDate)
            dimpBatchFile.save(failOnError: true)

            dimpFileService.sendDimpBatchFileToS3Bucket(dimpBatchFile, fileName)
        }, [logErrorMessage: "DimpInconsistencyFileService.sendCorrectionFileToS3Bucket >> Erro na geração do arquivo de correção de inconsistências."])
    }
}

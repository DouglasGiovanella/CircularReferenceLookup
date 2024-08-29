package com.asaas.service.tessera

import com.amazonaws.services.lambda.model.AWSLambdaException
import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.bankslip.BankSlipVO
import com.asaas.domain.customer.Customer
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.http.HttpRequestManager
import com.asaas.integration.aws.managers.LambdaManager
import com.asaas.integration.aws.managers.S3Manager
import com.asaas.integration.aws.managers.TesseraLambdaClientProxy
import com.asaas.log.AsaasLogger
import com.asaas.tessera.TesseraBankSlipRequestBatchDTO
import com.asaas.tessera.TesseraBankSlipRequestDTO
import com.asaas.tessera.TesseraS3PayloadInfoDTO
import com.asaas.utils.FileUtils
import com.asaas.utils.Utils
import com.google.gson.Gson
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

import java.nio.charset.StandardCharsets

@Transactional
class TesseraManagerService {

    public byte[] getBankSlipPdf(BankSlipVO bankSlipVO, Customer customer) {
        try {
            TesseraBankSlipRequestDTO bankSlipRequestDTO = new TesseraBankSlipRequestDTO(bankSlipVO, customer)
            return generateBankSlipBytes(bankSlipRequestDTO, false)
        } catch (Exception exception) {
            AsaasLogger.error("TesseraManagerService.getBankSlipPdf >>> Ocorreu um erro:", exception)
            throw new BusinessException(Utils.getMessageProperty("unknow.error"))
        }
    }

    public byte[] getPaymentBookPdf(List<BankSlipVO> bankSlipVO, String templateName, Customer customer) {
        try {
            TesseraBankSlipRequestBatchDTO bankSlipRequestDTO = new TesseraBankSlipRequestBatchDTO(bankSlipVO, templateName, customer)
            return generateBankSlipBytes(bankSlipRequestDTO, false)
        } catch (Exception exception) {
            AsaasLogger.error("TesseraManagerService.getPaymentBookPdf >>> Ocorreu um erro:", exception)
            throw new BusinessException(Utils.getMessageProperty("unknow.error"))
        }
    }

    public byte[] getBankSlipPdfBatch(List<BankSlipVO> bankSlipVOList, Customer customer) {
        try {
            TesseraBankSlipRequestBatchDTO bankSlipRequestDTO = new TesseraBankSlipRequestBatchDTO(bankSlipVOList.reverse(), bankSlipVOList.first().customTemplate, customer)
            return generateBankSlipBytes(bankSlipRequestDTO, true)
        } catch (Exception exception) {
            AsaasLogger.error("TesseraManagerService.getBankSlipPdfBatch >>> Ocorreu um erro:", exception)
            throw new BusinessException(Utils.getMessageProperty("unknow.error"))
        }
    }

    private byte[] generateBankSlipBytes(Object bankSlipRequestDTO, Boolean isBatch) {
        if (AsaasEnvironment.isProduction()) {
            String lambdaFunctionName = isBatch ? AsaasApplicationHolder.config.tessera.lambdaArnBatch : AsaasApplicationHolder.config.tessera.lambdaArn
            return generateBankSlipBytesUsingLambdaManager(bankSlipRequestDTO, lambdaFunctionName, isBatch)
        }

        String url = isBatch ? AsaasApplicationHolder.config.tessera.urlBatch : AsaasApplicationHolder.config.tessera.url
        return generateBankSlipBytesUsingHttpManager(bankSlipRequestDTO, url)
    }

    private byte[] generateBankSlipBytesUsingHttpManager(Object bankSlipRequestDTO, String url) {
        HttpRequestManager httpManager = new HttpRequestManager(url, ["x-api-key": AsaasApplicationHolder.config.tessera.apiKey, "useAwsS3": bankSlipRequestDTO.properties.useAwsS3], bankSlipRequestDTO.properties)
        httpManager.enableLoggingOnlyForErrors()
        httpManager.post()

        if (httpManager.responseHttpStatus == HttpStatus.OK.value()) {
            if (httpManager.responseBodyMap.bucket) {
                return processS3ResponseAndReturnBytes(httpManager.responseBodyMap)
            }

            return httpManager.responseBodyMap.file.decodeBase64()
        }

        AsaasLogger.error("TesseraManagerService.generateBankSlipBytes >>> Ocorreu um erro ao gerar PDF. ResponseStatus: [${httpManager.responseHttpStatus}] ResponseError: [${httpManager.errorMessage}]")
        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }

    private byte[] generateBankSlipBytesUsingLambdaManager(Object bankSlipRequestDTO, String lambdaFunctionName, Boolean shouldSendBankSlipBatchPayloadToS3Bucket) {
        final String payload = shouldSendBankSlipBatchPayloadToS3Bucket ? sendBankSlipBatchPayloadToS3Bucket(bankSlipRequestDTO) : new Gson().toJson(bankSlipRequestDTO, bankSlipRequestDTO.class)
        Integer logoBase64Size = bankSlipRequestDTO.properties.hasLogo ? bankSlipRequestDTO.properties.customer.logo.size() : 0

        try {
            LambdaManager manager = new LambdaManager(TesseraLambdaClientProxy.CLIENT_INSTANCE, lambdaFunctionName)

            manager.invoke(payload)
            if (manager.isSuccessful()) {
                Map response = manager.parseBody(Map)
                if (response.bucket) return processS3ResponseAndReturnBytes(response)

                return response.file.decodeBase64()
            }

            AsaasLogger.error("TesseraManagerService.generateBankSlipBytesUsingLambdaManager >>> Ocorreu um erro ao gerar PDF. ResponseStatus: [${manager.rawResponse}] ResponseError: [${manager.getError()}] Payload size: [${payload.size()}] LogoBase64 size: [${logoBase64Size}]")
        } catch (AWSLambdaException awsLambdaException) {
            AsaasLogger.error("TesseraManagerService.generateBankSlipBytesUsingLambdaManager >>> Ocorreu um erro ao gerar PDF. Payload size: [${payload.size()}] LogoBase64 size: [${logoBase64Size}] Exception: [${awsLambdaException}]")
        }

        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }

    private byte[] processS3ResponseAndReturnBytes(Map responseBodyMap) {
        S3Manager s3Manager = new S3Manager(responseBodyMap.bucket, responseBodyMap.fileName)
        byte[] bytes = s3Manager.readBytes()

        Utils.withNewTransactionAndRollbackOnError ({
            s3Manager.delete()
        }, [logErrorMessage: "TesseraManagerService.processS3ResponseAndReturnBytes >>> Ocorreu um erro ao deletar o arquivo"])

        return bytes
    }

    private String sendBankSlipBatchPayloadToS3Bucket(Object bankSlipRequestDTO) {
        TesseraS3PayloadInfoDTO tesseraS3PayloadInfoDTO = new TesseraS3PayloadInfoDTO("payload_bank_slip_batch_${UUID.randomUUID().toString()}.json")

        final String bankSlipPayload = new Gson().toJson(bankSlipRequestDTO, bankSlipRequestDTO.class)
        S3Manager s3Manager = new S3Manager(tesseraS3PayloadInfoDTO.bucket, tesseraS3PayloadInfoDTO.fileName)
        Boolean fileUploaded = false
        FileUtils.withDeletableTempFile(bankSlipPayload, StandardCharsets.UTF_8.toString(), {
            File tempFile -> fileUploaded = s3Manager.write(tempFile)
        })

        if (!fileUploaded) throw new RuntimeException("TesseraManagerService.sendBankSlipBatchPayloadToS3Bucket - Erro ao enviar bankSlipPayload para o s3. Bucket: [${s3Manager.bucket}]")

        return new Gson().toJson(tesseraS3PayloadInfoDTO, TesseraS3PayloadInfoDTO)
    }

}

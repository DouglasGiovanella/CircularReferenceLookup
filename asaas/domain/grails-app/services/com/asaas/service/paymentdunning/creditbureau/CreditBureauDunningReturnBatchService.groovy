package com.asaas.service.paymentdunning.creditbureau

import com.asaas.domain.file.AsaasFile
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentdunning.PaymentDunningCustomerAccountInfo
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningBatch
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningBatchItem
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningReturnBatch
import com.asaas.domain.paymentdunning.creditbureau.CreditBureauDunningReturnBatchItem
import com.asaas.integration.serasa.manager.SerasaCreditBureauSftpManager
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentDunningCancellationReason
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningBatchItemType
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningReturnBatchItemOperationType
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningReturnBatchItemVO
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningReturnBatchStatus
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningReturnBatchType
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningReturnBatchVO
import com.asaas.paymentdunning.creditbureau.CreditBureauDunningReturnErrorCodeReason
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CreditBureauDunningReturnBatchService {

    def creditBureauDunningBatchItemService
    def creditBureauDunningService
    def fileService
    def paymentDunningService

    public void processReturnBatch() {
        SerasaCreditBureauSftpManager manager = new SerasaCreditBureauSftpManager()
        List<String> filenameList = manager.listFileNames()

        for (String filename in filenameList) {
            Long asaasFileId

            Utils.withNewTransactionAndRollbackOnError({
                String fileContent = manager.download(filename)

                AsaasFile returnBatchFile = fileService.createFile(filename, fileContent)
                asaasFileId = returnBatchFile.id

                manager.delete(filename)
            }, [logErrorMessage: "CreditBureauDunningReturnBatchService >> Ocorreu um erro ao salvar o conteúdo de retorno."])

            processReturnBatchContent(asaasFileId)
        }
    }

    public void processReturnBatchContent(Long asaasFileId) {
        List<Long> returnBatchItemIdList = []

        Utils.withNewTransactionAndRollbackOnError({
            AsaasFile returnBatchFile = AsaasFile.get(asaasFileId)

            CreditBureauDunningReturnBatchVO batchVO = parse(returnBatchFile.getFileContent())

            CreditBureauDunningReturnBatch returnBatch = save(returnBatchFile, batchVO)
            returnBatchItemIdList = saveItems(returnBatch, batchVO.creditBureauDunningReturnItemVOList)

            if (returnBatch.errorCodes) {
                AsaasLogger.error("CreditBureauDunningReturnBatchService >> Códigos de erros no header de retorno [${returnBatch.id}]")
            }
        }, [logErrorMessage: "CreditBureauDunningReturnBatchService >> Ocorreu um erro ao processar arquivo de retorno [${asaasFileId}]"])

        processReturnBatchItems(returnBatchItemIdList)
    }

    public void processReturnBatchItems(List<Long> returnBatchItemIdList) {
        for (Long itemId in returnBatchItemIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CreditBureauDunningReturnBatchItem item = CreditBureauDunningReturnBatchItem.get(itemId)

                if (item.creditBureauDunningReturnBatch.type.isInformational() && !item.paymentDunning) return

                if (item.operationType.isRemoval() && item.paymentDunning.status.isPaid() && item.paymentDunning.payment.isDunningReceived()) {
                    AsaasLogger.warn("CreditBureauDunningReturnBatchService.processReturnBatchItems >> Erro ao processar o item [${ item.id }] do arquivo de retorno do Serasa, tentativa de cancelamento de negativação paga!")
                    return
                }

                if (item.errorCodes) {
                    if (item.creditBureauDunningReturnBatch.type.isInformational()) {
                        if (item.operationType.isSpecialCondition()) return
                        if (item.operationType.isRemoval() && !item.paymentDunning.status.isCancelled()) {
                            Boolean isSuccessfulProcessed = processErrorCodeAndCancelIfPossible(item)
                            if (isSuccessfulProcessed) return

                            AsaasLogger.error("CreditBureauDunningReturnBatchService.processReturnBatchItems >> O item [${item.id}] foi removido da base de dados do Serasa")
                            return
                        }
                    }

                    if (item.creditBureauDunningReturnBatch.type.isReturn() && item.creditBureauDunningReturnBatch.creditBureauDunningBatch.type.isCreation())  {
                        Boolean isSuccessfulProcessed = processErrorCodeAndDenyDunningIfPossible(item)
                        if (isSuccessfulProcessed) return
                    }

                    if (item.creditBureauDunningReturnBatch.type.isReturn() && item.creditBureauDunningReturnBatch.creditBureauDunningBatch.type.isRemoval()) {
                        Boolean isSuccessfulProcessed = processErrorCodeAndRefundDunningIfPossible(item)
                        if (isSuccessfulProcessed) return
                    }

                    AsaasLogger.error("CreditBureauDunningReturnBatchService.processReturnBatchItems >> Códigos de erros no item de retorno [${item.id}]")
                    return
                }

                if (item.paymentDunning.status.isPaid() && item.paymentDunning.payment.isDunningReceived() && item.creditBureauDunningReturnBatch.creditBureauDunningBatch.type.isRemoval()) {
                    creditBureauDunningService.deleteRegistrationIfExists(item.paymentDunning)
                    return
                }

                if (item.creditBureauDunningReturnBatch.type.isReturn() && item.creditBureauDunningReturnBatch.creditBureauDunningBatch.type.isCreation()) {
                    setPartnerChargedFee(item)

                    if (item.paymentDunning.payment.status.hasBeenConfirmed()) {
                        creditBureauDunningService.cancel(item.paymentDunning, PaymentDunningCancellationReason.PAYMENT_CONFIRMED)
                        return
                    }
                }

                if (item.paymentDunning.status.isPaid()) {
                    Boolean hasRemovalCreditBureauDunningBatchItem = CreditBureauDunningBatchItem.query([exists: true, paymentDunning: item.paymentDunning, type: CreditBureauDunningBatchItemType.REMOVAL]).get().asBoolean()
                    if (hasRemovalCreditBureauDunningBatchItem) return
                }

                if (item.paymentDunning.status.isAwaitingCancellation()) {
                    Boolean hasPendingRemovalCreditBureauDunningBatchItem = creditBureauDunningBatchItemService.existsPendingRemovalItem(item.paymentDunning)
                    if (!hasPendingRemovalCreditBureauDunningBatchItem) throw new Exception("CreditBureauDunningReturnBatchService.processReturnBatchItems >> Negativação aguardando cancelamento e sem item de remoção pendente.")
                    return
                }

                if (item.paymentDunning.status.isAwaitingPartnerCancellation()) {
                    if (item.creditBureauDunningReturnBatch.creditBureauDunningBatch.type.isCreation()) return

                    paymentDunningService.cancel(item.paymentDunning, item.paymentDunning.cancellationReason)
                    creditBureauDunningService.onPartnerCancellation(item.paymentDunning)
                    return
                }

                if (item.paymentDunning.status.isAwaitingPartnerApproval()) {
                    creditBureauDunningService.onPartnerApproval(item.paymentDunning)
                    return
                }

                throw new Exception("Situação da negativação não suportada")
            }, [logErrorMessage: "CreditBureauDunningReturnBatchService.processReturnBatchItems >> Ocorreu um erro ao processar item de retorno [${itemId}]"])
        }
    }

    public Boolean processErrorCodeAndDenyDunningIfPossible(CreditBureauDunningReturnBatchItem item) {
        if (!item.errorCodes || !item.paymentDunning.status.isAwaitingPartnerApproval()) return false

        String description = getErrorCodeDescriptionIfExists(item)
        if (!description) return false

        creditBureauDunningService.onPartnerDenial(item.paymentDunning, description)
        AsaasLogger.info("CreditBureauDunningReturnBatchService >> Negativação [${item.paymentDunning.id}] foi negada pelo Serasa")
        return true
    }

    private void setPartnerChargedFee(CreditBureauDunningReturnBatchItem creditBureauDunningReturnBatchItem) {
        String cpfCnpj = PaymentDunningCustomerAccountInfo.query([column: "cpfCnpj", paymentDunning: creditBureauDunningReturnBatchItem.paymentDunning]).get()
        creditBureauDunningReturnBatchItem.partnerChargedFeeValue = CpfCnpjUtils.isCpf(cpfCnpj) ? PaymentDunning.SERASA_PARTNER_NATURAL_PERSON_FEE : PaymentDunning.SERASA_PARTNER_LEGAL_PERSON_FEE

        creditBureauDunningReturnBatchItem.save(failOnError: true)
    }

    private Boolean processErrorCodeAndRefundDunningIfPossible(CreditBureauDunningReturnBatchItem item) {
        if (!item.errorCodes) return false

        List<String> errorCodeList = item.getErrorCodeList()
        if (errorCodeList.size() > 1) return false

        String errorCode = errorCodeList[0]
        if (errorCode != "022") return false

        if (item.paymentDunning.status.isPaid() && item.paymentDunning.payment.isDunningReceived()) return false

        CreditBureauDunningReturnBatchItem creationItem = CreditBureauDunningReturnBatchItem.query([paymentDunning: item.paymentDunning, "partnerChargedFeeValue[isNotNull]": true]).get()
        if (creationItem) return false

        creditBureauDunningService.refundFeeAndCancel(item.paymentDunning, item.paymentDunning.cancellationReason)
        AsaasLogger.info("CreditBureauDunningReturnBatchService >> Negativação [${item.paymentDunning.id}] foi estornada.")

        return true
    }

    private Boolean processErrorCodeAndCancelIfPossible(CreditBureauDunningReturnBatchItem item) {
        String description = getErrorCodeDescriptionIfExists(item)
        if (!description) return false

        creditBureauDunningService.onAutomaticRemovedByPartner(item.paymentDunning, description)
        return true
    }

    private CreditBureauDunningReturnBatchVO parse(String fileContent) {
        CreditBureauDunningReturnBatchVO batchVO = new CreditBureauDunningReturnBatchVO()

        fileContent.eachLine {
            String registerType = it.substring(0, 1)

            if (registerType == "0") {
                parseFileHeader(it, batchVO)
            } else if (registerType == "1") {
                parseFileItem(it, batchVO)
            }
        }

        return batchVO
    }

    private void parseFileHeader(String line, CreditBureauDunningReturnBatchVO batchVO) {
        batchVO.batchId = Utils.toLong(StringUtils.removeWhitespaces(line.substring(120, 125)))
        batchVO.batchType = line.substring(125, 126)
        batchVO.headerErrorCodeList = parseErrorCodes(line, batchVO.batchType, true)
    }

    private void parseFileItem(String line, CreditBureauDunningReturnBatchVO batchVO) {
        CreditBureauDunningReturnBatchItemVO batchItemVO = new CreditBureauDunningReturnBatchItemVO()
        batchItemVO.operationCode = line.substring(1, 2)
        batchItemVO.paymentDunningId = Utils.toLong(StringUtils.removeWhitespaces(line.substring(644, 658)))
        batchItemVO.errorCodeList = parseErrorCodes(line, batchVO.batchType, false)

        batchVO.creditBureauDunningReturnItemVOList.add(batchItemVO)
    }

    private List<String> parseErrorCodes(String line, String batchType, Boolean isHeader) {
        String errorCodes

        if (batchType == "I" && !isHeader) {
            errorCodes = line.substring(833, 836)
        } else {
            errorCodes = line.substring(833, 893)
        }

        errorCodes = StringUtils.removeWhitespaces(errorCodes)

        if (errorCodes.isEmpty()) return []

        final Integer errorCodeLength = 3
        return errorCodes.toList().collate(errorCodeLength).collect() { it.join() }
    }

    private CreditBureauDunningReturnBatch save(AsaasFile returnBatchFile, CreditBureauDunningReturnBatchVO batchVO) {
        CreditBureauDunningReturnBatch returnBatch = new CreditBureauDunningReturnBatch()

        if (batchVO.batchType == "I") {
            returnBatch.type = CreditBureauDunningReturnBatchType.INFORMATIONAL
            returnBatch.status = CreditBureauDunningReturnBatchStatus.IGNORED
        } else if (batchVO.batchType == "R") {
            returnBatch.type = CreditBureauDunningReturnBatchType.RETURN
            returnBatch.status = CreditBureauDunningReturnBatchStatus.PENDING
        } else {
            throw new Exception("Tipo de retorno não suportado [${batchVO.batchType}] para a remessa com o id [${batchVO.batchId}]")
        }

        returnBatch.creditBureauDunningBatch = CreditBureauDunningBatch.get(batchVO.batchId)
        returnBatch.errorCodes = batchVO.headerErrorCodeList.join(",") ?: null
        returnBatch.file = returnBatchFile
        returnBatch.save(failOnError: true)

        return returnBatch
    }

    private List<Long> saveItems(CreditBureauDunningReturnBatch returnBatch, List<CreditBureauDunningReturnBatchItemVO> batchItemVOList) {
        List<Long> returnBatchItemIdList = []

        for (CreditBureauDunningReturnBatchItemVO itemVO in batchItemVOList) {
            try {
                PaymentDunning paymentDunning = PaymentDunning.get(itemVO.paymentDunningId)
                if (!paymentDunning && returnBatch.creditBureauDunningBatch) throw new Exception("Não existe uma negativação com o id [${itemVO.paymentDunningId}] para o remessa com o id [${returnBatch.id}]")
                if (paymentDunning && !paymentDunning.type.isCreditBureau()) paymentDunning = null

                CreditBureauDunningReturnBatchItem item = new CreditBureauDunningReturnBatchItem()
                item.creditBureauDunningReturnBatch = returnBatch
                item.paymentDunning = paymentDunning
                item.errorCodes = itemVO.errorCodeList.join(",") ?: null
                item.operationType = convertToOperationType(itemVO.operationCode)
                item.save(failOnError: true)

                returnBatchItemIdList.add(item.id)
            } catch (Exception exception) {
                AsaasLogger.error("CreditBureauDunningReturnBatchService >> Ocorreu um erro ao salvar item de retorno", exception)
            }
        }

        return returnBatchItemIdList
    }

    private CreditBureauDunningReturnBatchItemOperationType convertToOperationType(String operationCode) {
        switch (operationCode) {
            case "I":
                return CreditBureauDunningReturnBatchItemOperationType.CREATION
                break
            case "E":
                return CreditBureauDunningReturnBatchItemOperationType.REMOVAL
                break
            case "C":
                return CreditBureauDunningReturnBatchItemOperationType.SPECIAL_CONDITION
                break
        }
        throw new Exception("Item de retorno sem código de operação")
    }

    private String getErrorCodeDescriptionIfExists(CreditBureauDunningReturnBatchItem item) {
        List<String> errorCodeList = item.getErrorCodeList()
        if (!errorCodeList) return

        String description
        for (String errorCode in errorCodeList) {
            description = getErrorCodeDescriptionIfExists(errorCode)
            if (description) break
        }
        return description
    }

    private String getErrorCodeDescriptionIfExists(String errorCode) {
        String description

        if (CreditBureauDunningReturnErrorCodeReason.getInformationalBatchReasons().any {it.errorCode() == errorCode }) {
            description = "A negativação para esse cliente foi cancelada pelo Serasa, pois "
        } else {
            description = "O pedido de negativação para esse cliente foi negado pelo Serasa, pois "
        }

        if (errorCode.startsWith("Asaas")) {
            description += getAsaasCustomizedErrorCodeDescriptionIfExists(errorCode)
            return description
        }

        switch (errorCode) {
            case "105":
                description += "há uma ordem judicial para restrição de negativação do CPF/CNPJ indicado."
                break
            case "227":
                description += "a cobrança não está vencida."
                break
            case "234":
                description += "o nome do devedor é divergente no cadastro da Receita Federal."
                break
            case "711":
                description += "o CPF/CNPJ do seu cliente estão iguais aos da sua empresa."
                break
            case "121":
                description += "a idade do devedor deve ser superior a 18 anos."
                break
            case "273":
                description += "o seu CEP é inválido e não corresponde ao endereço informado. Atualize essa informação em Minha conta > dados comerciais e solicite novamente a negativação"
                break
            case "274":
                description += "o CNPJ do devedor não existe."
                break
            case "275":
                description += "o CPF do devedor não existe."
                break
            case "296":
                description += "o nome não corresponde ao CPF informado."
                break
            case "295":
                description += "o nome do seu cliente não corresponde ao CNPJ informado"
                break
            case "269":
                description += "o seu endereço está incompleto."
                break
            case "291":
                description += "existe uma determinação judicial."
                break
            case "290":
                description += "a data de ocorrência foi decursada."
                break
            case "292":
                description += "a exclusão foi feita manualmente via Sisconvem."
                break
            case "772":
                description += "está em descumprimento com a lei distrital 3.335/2004-DF"
                break
            case "773":
                description += "está em descumprimento com a lei estadual 7.160/2002-ES"
                break
            case "774":
                description += "está em descumprimento com a lei estadual 3.749/2009-MS"
                break
            case "775":
                description += "está em descumprimento com a lei estadual 4.054/2011-MS"
                break
            case "900":
                description += "está em estado de calamidade pública"
                break
            default:
                description = null
                break
        }

        return description
    }

    private String getAsaasCustomizedErrorCodeDescriptionIfExists(String errorCode) {
        if (errorCode == "Asaas-01") return "o CNPJ do seu cliente foi encerrado."
        if (errorCode == "Asaas-02") return "sua atividade econômica não permite negativação."

        throw new UnsupportedOperationException("Erro customizado não suportado.")
    }
}

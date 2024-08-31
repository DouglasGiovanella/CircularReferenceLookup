package com.asaas.service.chargeback

import com.asaas.domain.chargeback.Chargeback
import com.asaas.domain.chargeback.ChargebackDispute
import com.asaas.domain.chargeback.ChargebackDisputeDocument
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.file.TemporaryFile
import com.asaas.exception.BusinessException

import grails.transaction.Transactional

@Transactional
class ChargebackDisputeDocumentService {

    def chargebackDisputeService
    def fileService
    def messageService

    public List<ChargebackDisputeDocument> createChargebackDisputeWithDocumentList(Chargeback chargeback, List<Long> temporaryFilesIdList) {
        if (!chargeback.canOpenDispute()) throw new BusinessException("Não é possível abrir uma contestação para chargebacks que estão encerrados ou já possuem uma disputa aberta.")

        List<ChargebackDisputeDocument> chargebackDisputeDocumentList = []

        ChargebackDispute chargebackDispute = chargebackDisputeService.save(chargeback.id)

        for (Long fileId in temporaryFilesIdList) {
            TemporaryFile tempFile = TemporaryFile.find(chargeback.customer, fileId)

            ChargebackDisputeDocument chargebackDisputeDocument = new ChargebackDisputeDocument()
            AsaasFile asaasFile = fileService.saveFileFromTemporary(chargeback.customer, tempFile)

            chargebackDisputeDocument.chargeback = chargeback
            chargebackDisputeDocument.chargebackDispute = chargebackDispute
            chargebackDisputeDocument.asaasFile = asaasFile
            chargebackDisputeDocument.fileName = asaasFile.originalName

            chargebackDisputeDocument.save(failOnError: true)

            chargebackDisputeDocumentList.add(chargebackDisputeDocument)
        }

        messageService.sendChargebackDisputeDocumentList(chargeback, chargebackDisputeDocumentList)

        return chargebackDisputeDocumentList
    }
}

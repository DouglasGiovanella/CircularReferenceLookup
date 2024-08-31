package com.asaas.service.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionExternalQrCodeInfo
import com.asaas.pix.vo.transaction.children.PixExternalQrCodeInfoVO

import grails.transaction.Transactional

@Transactional
class PixTransactionExternalQrCodeInfoService {

    public PixTransactionExternalQrCodeInfo save(PixTransaction pixTransaction, PixExternalQrCodeInfoVO pixExternalQrCodeInfoVO) {
        PixTransactionExternalQrCodeInfo pixTransactionExternalQrCodeInfo = new PixTransactionExternalQrCodeInfo()
        pixTransactionExternalQrCodeInfo.pixTransaction = pixTransaction
        pixTransactionExternalQrCodeInfo.type = pixExternalQrCodeInfoVO.type
        pixTransactionExternalQrCodeInfo.description = pixExternalQrCodeInfoVO.description
        pixTransactionExternalQrCodeInfo.conciliationIdentifier = pixExternalQrCodeInfoVO.conciliationIdentifier
        pixTransactionExternalQrCodeInfo.originalValue = pixExternalQrCodeInfoVO.originalValue
        pixTransactionExternalQrCodeInfo.interestValue = pixExternalQrCodeInfoVO.interestValue
        pixTransactionExternalQrCodeInfo.fineValue = pixExternalQrCodeInfoVO.fineValue
        pixTransactionExternalQrCodeInfo.discountValue = pixExternalQrCodeInfoVO.discountValue
        pixTransactionExternalQrCodeInfo.dueDate = pixExternalQrCodeInfoVO.dueDate
        pixTransactionExternalQrCodeInfo.expirationDate = pixExternalQrCodeInfoVO.expirationDate
        pixTransactionExternalQrCodeInfo.payerCpfCnpj = pixExternalQrCodeInfoVO.payerCpfCnpj
        pixTransactionExternalQrCodeInfo.payerName = pixExternalQrCodeInfoVO.payerName
        pixTransactionExternalQrCodeInfo.payload = pixExternalQrCodeInfoVO.payload
        if (pixTransaction.cashValueFinality) {
            pixTransactionExternalQrCodeInfo.pssIspb = pixExternalQrCodeInfoVO.pssIspb
            pixTransactionExternalQrCodeInfo.pssModality = pixExternalQrCodeInfoVO.pssModality
        }
        pixTransactionExternalQrCodeInfo.save(failOnError: true)

        return pixTransactionExternalQrCodeInfo
    }
}

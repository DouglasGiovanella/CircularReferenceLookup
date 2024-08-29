package com.asaas.service.bankaccountinfo

import com.asaas.bankaccountinfo.BankAccountType
import com.asaas.bankaccountinfo.BankAccountValidationStatus
import com.asaas.bankaccountinfo.BankAccountValidationTransferStatus
import com.asaas.credittransferrequest.CreditTransferRequestTransferBatchFileStatus
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.bankaccountinfo.BankAccountValidationTransfer
import com.asaas.domain.transferbatchfile.TransferBatchFile
import com.asaas.domain.transferbatchfile.TransferReturnFile
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class BankAccountValidationTransferService {

    def transferBatchFileService

    public BankAccountValidationTransfer save(BankAccountInfo bankAccountInfo) {
        BankAccountValidationTransfer bankAccountValidationTransfer = BankAccountValidationTransfer.query([bankAccountInfo: bankAccountInfo]).get()

        if (bankAccountValidationTransfer) {
            throw new RuntimeException("Já existe uma transferência de validação para esta conta bancária: BankAccountInfo [${bankAccountInfo.id}]")
        }

        bankAccountValidationTransfer = new BankAccountValidationTransfer()
        bankAccountValidationTransfer.bankAccountInfo = bankAccountInfo
        bankAccountValidationTransfer.customer = bankAccountInfo.customer
        bankAccountValidationTransfer.value = BigDecimalUtils.random(BankAccountValidationTransfer.MINIMUM_VALUE, BankAccountValidationTransfer.MAXIMUM_VALUE)
        bankAccountValidationTransfer.validationStatus = BankAccountValidationStatus.PENDING
        bankAccountValidationTransfer.transferStatus = BankAccountValidationTransferStatus.PENDING

        bankAccountValidationTransfer.save(failOnError: true)

        return bankAccountValidationTransfer
    }

    public Boolean validateTransfer(BankAccountInfo bankAccountInfo, BigDecimal transferValueInformedByCustomer) {
        BankAccountValidationTransfer bankAccountValidationTransfer = BankAccountValidationTransfer.query([bankAccountInfo: bankAccountInfo]).get()

        if (!bankAccountValidationTransfer || !bankAccountValidationTransfer.transferStatus.alreadySentToBank()) return false

        if (bankAccountValidationTransfer.value == transferValueInformedByCustomer) {
            bankAccountValidationTransfer.validationStatus = BankAccountValidationStatus.SUCCESSFUL
        } else {
            bankAccountValidationTransfer.validationStatus = BankAccountValidationStatus.FAILED
        }

        bankAccountValidationTransfer.save(failOnError: true)

        return bankAccountValidationTransfer.validationStatus.isSuccessful()
    }

    public void updateTransferReturnFileAndTransferStatus(Long id, TransferReturnFile transferReturnFile, CreditTransferRequestTransferBatchFileStatus status) {
        BankAccountValidationTransfer bankAccountValidationTransfer = BankAccountValidationTransfer.get(id)

        bankAccountValidationTransfer.transferStatus = status.convertToBankAccountValidationTransferStatus()
        bankAccountValidationTransfer.transferReturnFile = transferReturnFile
        bankAccountValidationTransfer.save(flush: true, failOnError: true)
    }

}

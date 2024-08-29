package com.asaas.service.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionExternalAccount
import com.asaas.pix.adapter.transaction.credit.CreditAdapter
import com.asaas.pix.vo.transaction.children.PixExternalAccountVO
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixTransactionExternalAccountService {

    public PixTransactionExternalAccount save(PixTransaction pixTransaction, PixExternalAccountVO pixExternalAccountVO) {
        PixTransactionExternalAccount pixTransactionExternalAccount = new PixTransactionExternalAccount()
        pixTransactionExternalAccount.pixTransaction = pixTransaction
        pixTransactionExternalAccount.ispb = Utils.toLong(pixExternalAccountVO.ispb)
        pixTransactionExternalAccount.name = pixExternalAccountVO.name
        pixTransactionExternalAccount.cpfCnpj = pixExternalAccountVO.cpfCnpj
        pixTransactionExternalAccount.agency = pixExternalAccountVO.agency
        pixTransactionExternalAccount.account = pixExternalAccountVO.account
        pixTransactionExternalAccount.accountType = pixExternalAccountVO.accountType
        pixTransactionExternalAccount.personType = pixExternalAccountVO.personType
        pixTransactionExternalAccount.pixKey = pixExternalAccountVO.pixKey
        pixTransactionExternalAccount.pixKeyType = pixExternalAccountVO.pixKeyType
        pixTransactionExternalAccount.save(flush: true, failOnError: true)

        return pixTransactionExternalAccount
    }

    public PixTransactionExternalAccount saveFromCredit(PixTransaction pixTransaction, CreditAdapter creditAdapter) {
        PixTransactionExternalAccount pixTransactionExternalAccount = new PixTransactionExternalAccount()
        pixTransactionExternalAccount.pixTransaction = pixTransaction
        pixTransactionExternalAccount.ispb = Utils.toLong(creditAdapter.payer.ispb)
        pixTransactionExternalAccount.name = creditAdapter.payer.name
        pixTransactionExternalAccount.cpfCnpj = creditAdapter.payer.cpfCnpj
        pixTransactionExternalAccount.agency = creditAdapter.payer.agency
        pixTransactionExternalAccount.account = creditAdapter.payer.account
        pixTransactionExternalAccount.accountType = creditAdapter.payer.pixAccountType
        pixTransactionExternalAccount.personType = creditAdapter.payer.personType
        pixTransactionExternalAccount.pixKey = creditAdapter.pixKey
        pixTransactionExternalAccount.pixKeyType = creditAdapter.pixAddressKeyType

        return pixTransactionExternalAccount.save(flush: true, failOnError: true)
    }

    public PixTransactionExternalAccount saveFromPixTransactionExternalAccount(PixTransaction pixTransaction, PixTransactionExternalAccount originalExternalAccount) {
        PixTransactionExternalAccount externalAccount = new PixTransactionExternalAccount()
        externalAccount.pixTransaction = pixTransaction
        externalAccount.ispb = originalExternalAccount.ispb
        externalAccount.name = originalExternalAccount.name
        externalAccount.cpfCnpj = originalExternalAccount.cpfCnpj
        externalAccount.agency = originalExternalAccount.agency
        externalAccount.account = originalExternalAccount.account
        externalAccount.accountType = originalExternalAccount.accountType
        externalAccount.personType = originalExternalAccount.personType
        externalAccount.pixKey = originalExternalAccount.pixKey
        externalAccount.pixKeyType = originalExternalAccount.pixKeyType

        return externalAccount.save(flush: true, failOnError: true)
    }
}

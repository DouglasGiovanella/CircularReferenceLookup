package com.asaas.service.bankaccountinfo

import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.bankaccountinfo.BankAccountInfoPixKey
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixAddressKeyType
import com.asaas.pix.vo.transaction.children.PixExternalAccountVO
import com.asaas.service.bankaccountinfo.parser.BankAccountInfoParser
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class BankAccountInfoPixKeyService {

    public void save(BankAccountInfo bankAccountInfo, String pixKey, PixAddressKeyType pixAddressKeyType) {
        BankAccountInfoPixKey bankAccountInfoPixKey = new BankAccountInfoPixKey()
        bankAccountInfoPixKey.bankAccountInfo = bankAccountInfo
        bankAccountInfoPixKey.pixKey = pixKey
        bankAccountInfoPixKey.pixAddressKeyType = pixAddressKeyType
        bankAccountInfoPixKey.save(failOnError: true)
    }

    public AsaasError validateOwnership(BankAccountInfo bankAccountInfo, PixExternalAccountVO externalAccount) {
        if (externalAccount.ispb != bankAccountInfo.financialInstitution.paymentServiceProvider.ispb) {
            AsaasLogger.warn("${this.class.simpleName}.validateOwnership >> Chave cadastrada não está na mesma instituição. [bankAccountInfoId: ${bankAccountInfo.id}}]")
            return new AsaasError("pixTransaction.denied.bankAccountInfoPixKey.changedInstitution")
        }

        if (!isSameAccount(bankAccountInfo, externalAccount)) {
            AsaasLogger.warn("${this.class.simpleName}.validateOwnership >> Chave cadastrada não possui os mesmo dados da conta bancária registrada. [bankAccountInfoId: ${bankAccountInfo.id}}]")
            return new AsaasError("pixTransaction.denied.bankAccountInfoPixKey.changedAccount")
        }

        return null
    }

    private Boolean isSameAccount(BankAccountInfo bankAccountInfo, PixExternalAccountVO externalAccount) {
        Map bankAccountInfoMap = BankAccountInfoParser.parseFromPixExternalAccountVO(externalAccount)

        if (bankAccountInfoMap.cpfCnpj != bankAccountInfo.cpfCnpj) return false
        if (bankAccountInfoMap.agency != bankAccountInfo.agency) return false
        if (bankAccountInfoMap.account != bankAccountInfo.account) return false
        if (bankAccountInfoMap.accountDigit != bankAccountInfo.accountDigit) return false
        if (bankAccountInfoMap.bankAccountType != bankAccountInfo.bankAccountType) return false
        return true
    }
}

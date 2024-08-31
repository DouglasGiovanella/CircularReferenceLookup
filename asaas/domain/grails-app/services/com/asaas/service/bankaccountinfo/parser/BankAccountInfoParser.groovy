package com.asaas.service.bankaccountinfo.parser

import com.asaas.bankaccountinfo.BankAccountType
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.bankaccountinfo.BankAccountInfoPixKey
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialinstitution.FinancialInstitution
import com.asaas.domain.pix.PixTransactionExternalAccount
import com.asaas.integration.pix.parser.PixParser
import com.asaas.integration.pix.utils.PixUtils
import com.asaas.pix.PixAccountType
import com.asaas.pix.vo.transaction.children.PixExternalAccountVO
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.Utils
import org.apache.commons.lang.NotImplementedException

class BankAccountInfoParser {

    public static Map parseSaveParams(Customer customer, PixTransactionExternalAccount pixTransactionExternalAccount, FinancialInstitution financialInstitution) {
        Map params = [:]
        params.thirdPartyAccount = (pixTransactionExternalAccount.cpfCnpj != customer.cpfCnpj)
        params.financialInstitution = financialInstitution.id
        params.bank = financialInstitution.bank?.id
        params.bankAccountType = BankAccountInfoParser.parseFromPixAccountType(pixTransactionExternalAccount.accountType).toString()
        params.agency = pixTransactionExternalAccount.agency
        params.account = PixTransactionExternalAccount.removeAccountDigit(pixTransactionExternalAccount.account)
        params.accountDigit = PixTransactionExternalAccount.retrieveAccountDigit(pixTransactionExternalAccount.account)
        params.accountName = pixTransactionExternalAccount.name
        params.name = pixTransactionExternalAccount.name
        params.cpfCnpj = pixTransactionExternalAccount.cpfCnpj
        params.mainAccount = false
        params.bypassCriticalAction = true

        return params
    }

    public static Map parseFromPixExternalAccountVO(PixExternalAccountVO pixExternalAccountVO) {
        Long financialInstitutionId = FinancialInstitution.query([column: "id", paymentServiceProviderIspb: PixUtils.parseIspb(pixExternalAccountVO.ispb)]).get()
        if (!financialInstitutionId) return

        Map params = [:]
        params.financialInstitution = financialInstitutionId
        params.bankAccountType = BankAccountInfoParser.parseFromPixAccountType(pixExternalAccountVO.accountType)
        params.agency = pixExternalAccountVO.agency
        params.account = PixTransactionExternalAccount.removeAccountDigit(pixExternalAccountVO.account)
        params.accountDigit = PixTransactionExternalAccount.retrieveAccountDigit(pixExternalAccountVO.account)
        params.accountName = pixExternalAccountVO.name
        params.name = pixExternalAccountVO.name
        params.cpfCnpj = pixExternalAccountVO.cpfCnpj

        return params
    }

    public static Map parseFilterParams(Map params) {
        Map filters = [:]

        if (params.containsKey("bankAccountSearch")) filters.accountSearchText = params.bankAccountSearch

        return filters
    }

    public static Map parseSelectBankAccountResponse(BankAccountInfo bankAccountInfo) {
        PixAccountType pixAccountType = PixParser.parsePixAccountType(bankAccountInfo.bankAccountType)

        Map bankAccountInfoMap = [
            id: bankAccountInfo.id,
            name: bankAccountInfo.name,
            financialInstitutionName: bankAccountInfo.financialInstitution.name,
            financialInstitutionId: bankAccountInfo.financialInstitution.id,
            bankAccountType: pixAccountType.toString(),
            bankAccountTypeName: Utils.getMessageProperty("PixAccountType.${pixAccountType}"),
            cpfCnpj: CpfCnpjUtils.maskCpfCnpjForPublicVisualization(bankAccountInfo.cpfCnpj),
            thirdPartyAccount: bankAccountInfo.thirdPartyAccount,
            responsibleEmail: bankAccountInfo.responsibleEmail,
            responsiblePhone: bankAccountInfo.responsiblePhone
        ]

        BankAccountInfoPixKey bankAccountInfoPixKey = bankAccountInfo.getPixKey()
        if (bankAccountInfoPixKey) {
            bankAccountInfoMap.pixKey = bankAccountInfoPixKey.getMaskedPixKey()
            bankAccountInfoMap.account = "00000000"
            bankAccountInfoMap.agency = "0000"
        } else {
            bankAccountInfoMap.account = bankAccountInfo.account + bankAccountInfo.accountDigit
            bankAccountInfoMap.agency = bankAccountInfo.agency
        }

        return bankAccountInfoMap
    }

    private static BankAccountType parseFromPixAccountType(PixAccountType pixAccountType) {
        if (!pixAccountType) return pixAccountType

        if (pixAccountType.isCheckingAccount()) return BankAccountType.CONTA_CORRENTE
        if (pixAccountType.isInvestimentAccount()) return BankAccountType.CONTA_POUPANCA
        if (pixAccountType.isPaymentAccount()) return BankAccountType.CONTA_DE_PAGAMENTO
        if (pixAccountType.isSalaryAccount()) return BankAccountType.CONTA_SALARIO

        throw new NotImplementedException("BankAccountInfoParser.parseFromPixAccountType >> BankAccountType não suportado: ${pixAccountType.toString()}")
    }
}

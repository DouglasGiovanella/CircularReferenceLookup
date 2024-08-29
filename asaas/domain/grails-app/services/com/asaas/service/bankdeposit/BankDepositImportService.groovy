package com.asaas.service.bankdeposit

import com.asaas.bankdeposit.BankDepositVO
import com.asaas.bankdeposit.BankDepositVOBBParser
import com.asaas.bankdeposit.BankDepositVOBradescoParser
import com.asaas.bankdeposit.BankDepositVOCaixaParser
import com.asaas.bankdeposit.BankDepositVOItauParser
import com.asaas.domain.bankdeposit.BankDeposit
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class BankDepositImportService {
    def bankDepositService

    public List<Long> save(CommonsMultipartFile file, SupportedBank supportedBank) {
        List<BankDepositVO> bankDepositVOList = []

        switch(supportedBank) {
            case SupportedBank.BANCO_DO_BRASIL:
                bankDepositVOList = new BankDepositVOBBParser(file).parse()
                break
            case SupportedBank.ITAU:
                bankDepositVOList = new BankDepositVOItauParser(file).parse()
                bankDepositVOList = removeDepositReversal(bankDepositVOList)
                bankDepositVOList = removeAsaasDeposit(bankDepositVOList)
                break
            case SupportedBank.CAIXA:
                bankDepositVOList = new BankDepositVOCaixaParser(file).parse()
                break
            case SupportedBank.BRADESCO:
                bankDepositVOList = new BankDepositVOBradescoParser(file).parse()
                bankDepositVOList = removeDepositReversal(bankDepositVOList)
                break
        }

        return save(bankDepositVOList)
    }

    public List<Long> save(List<BankDepositVO> bankDepositVOList) {
        List<Long> bankDepositIdList = []
        List<Map> errorList = []

        bankDepositVOList = removeExisting(bankDepositVOList)

        Utils.forEachWithFlushSession(bankDepositVOList, 50, { BankDepositVO bankDepositVO ->
            Utils.withNewTransactionAndRollbackOnError({
                BankDeposit bankDeposit = bankDepositService.save(bankDepositVO)
                if (!bankDeposit.hasErrors()) bankDepositIdList.add(bankDeposit.id)
            }, [onError: { Exception e -> errorList.add(bankDepositVO: bankDepositVO, e: e) }])
        })

        if (errorList) {
            String errorInfo = errorList.collect { "[${(it.bankDepositVO as JSON).toString()}] >>> ${it.e.getMessage()}" }.join(",")
            AsaasLogger.error("Erro ao importar depósito bancário. ${errorInfo}")

            throw new BusinessException("Não foi possível importar todos os depósitos bancários, por favor, contate a equipe de engenharia. De qualquer forma, foram importados ${bankDepositIdList.size()} de ${bankDepositVOList.size()} depósitos bancários com sucesso.")
        }

        return bankDepositIdList
    }

    private List<BankDepositVO> removeExisting(List<BankDepositVO> bankDepositVOList) {
        return bankDepositVOList.findAll { BankDepositVO bankDepositVO ->
            Map queryParams = [
                bank: bankDepositVO.bank,
                billingType: bankDepositVO.billingType,
                documentDate: bankDepositVO.documentDate,
                originAgency: bankDepositVO.originAgency,
                originName: bankDepositVO.originName,
                originAccount: bankDepositVO.originAccount,
                originAccountDigit: bankDepositVO.originAccountDigit,
                value: bankDepositVO.value
            ].findAll { it.value }

            return !BankDeposit.query(queryParams + [exists: true]).get().asBoolean()
        }
    }

    private List<BankDepositVO> removeDepositReversal(List<BankDepositVO> bankDepositVOList) {
        List<BankDepositVO> bankDepositReversalList = bankDepositVOList.findAll{it.value < 0}
        List<BankDepositVO> bankDepositValidList = bankDepositVOList.findAll{it.value > 0}

        for (BankDepositVO item in bankDepositReversalList) {
            bankDepositValidList.removeAll {
                isTransactionReversal(it, item)
            }
        }

        return bankDepositValidList
    }

    private Boolean isTransactionReversal(BankDepositVO bankDepositValid, BankDepositVO bankDepositReversal) {
        Boolean isReversal = true

        List<String> fieldNameList = ['documentNumber', 'documentDate', 'billingType', 'originAccount', 'originAgency', 'originAccountDigit', 'originCpfCnpj', 'originName']
        for (String fieldName in fieldNameList) {
            if (bankDepositValid[fieldName] != bankDepositReversal[fieldName]) {
                isReversal = false
                break
            }
        }

        if (!isReversal) return false

        return BigDecimalUtils.abs(bankDepositValid.value) == BigDecimalUtils.abs(bankDepositReversal.value)
    }

    private List<BankDepositVO> removeAsaasDeposit(List<BankDepositVO> bankDepositVOList) {
        bankDepositVOList.removeAll { BankDepositVO  bankDepositVO ->
            bankDepositVO.originName?.contains("ASAAS")
        }
        return bankDepositVOList
    }
}

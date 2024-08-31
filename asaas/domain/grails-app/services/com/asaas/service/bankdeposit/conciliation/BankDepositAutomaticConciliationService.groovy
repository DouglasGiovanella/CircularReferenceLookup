package com.asaas.service.bankdeposit.conciliation

import com.asaas.bankdepositstatus.BankDepositStatus
import com.asaas.billinginfo.BillingType
import com.asaas.domain.bankdeposit.BankDeposit
import com.asaas.domain.bankdeposit.BankDepositConciliation
import com.asaas.domain.invoicedepositinfo.InvoiceDepositInfo
import com.asaas.domain.paymentdepositreceipt.PaymentDepositReceipt
import com.asaas.log.AsaasLogger
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class BankDepositAutomaticConciliationService {

    def bankDepositConciliationService
    def bankDepositService

    private final List<String> BANK_CODES_ALLOWED_TO_AUTOMATIC_CONCILIATION_LIST = [SupportedBank.BANCO_DO_BRASIL, SupportedBank.ITAU, SupportedBank.BRADESCO, SupportedBank.CAIXA, SupportedBank.SANTANDER].collect {it.code()}
    private final List<String> BANK_CODES_ALLOWED_TO_AUTOMATIC_CONCILIATION_WITHOUT_PAYMENT_DEPOSIT_RECEIPT_LIST = [SupportedBank.BANCO_DO_BRASIL, SupportedBank.ITAU, SupportedBank.BRADESCO].collect {it.code()}
    private final Integer BRADESCO_DOCUMENT_NUMBER_MAXIMUM_CHARACTERS = 6

    public void conciliate() {
        List<Map> errorList = []

        Date startDateForConciliate = CustomDateUtils.sumDays(new Date(), -15)
        List<Long> bankDepositIdList = BankDeposit.query([column: "id", "dateCreated[ge]": startDateForConciliate, "bankCode[in]": BANK_CODES_ALLOWED_TO_AUTOMATIC_CONCILIATION_LIST, "status[in]": [BankDepositStatus.AWAITING_AUTOMATIC_CONCILIATION, BankDepositStatus.AWAITING_MANUAL_CONCILIATION]]).list()

        for (Long bankDepositId in bankDepositIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                BankDeposit bankDeposit = BankDeposit.get(bankDepositId)
                BankDepositConciliation bankDepositConciliation

                List<PaymentDepositReceipt> paymentDepositReceiptList = findRespectivePaymentDepositReceiptList(bankDepositId)
                if (paymentDepositReceiptList.size() == 1) {
                    if (paymentDepositReceiptList.first().conciliatedValue < paymentDepositReceiptList.first().payment.value) {
                        bankDepositService.sendToManualConciliation(bankDeposit.id)
                        return
                    }

                    bankDepositConciliation = bankDepositConciliationService.conciliate([bankDepositId], paymentDepositReceiptList.id, [])
                    if (bankDepositConciliation.hasErrors()) throw new ValidationException(null, bankDepositConciliation.errors)
                } else {
                    if (BANK_CODES_ALLOWED_TO_AUTOMATIC_CONCILIATION_WITHOUT_PAYMENT_DEPOSIT_RECEIPT_LIST.contains(bankDeposit.bank.code) && paymentDepositReceiptList.size() == 0) {
                        bankDepositConciliation = conciliateWithoutPaymentDepositReceiptIfPossible(bankDeposit)
                        if (!bankDepositConciliation.hasErrors()) return
                    }

                    bankDepositService.sendToManualConciliation(bankDeposit.id)
                }
            }, [onError: { Exception exception -> errorList.add(bankDepositId: bankDepositId, exception: exception.getMessage()) }])
        }

        if (errorList) {
            String errorInfo = errorList.collect { "Depósito: [${(it.bankDepositId).toString()}] >>> ${it.exception}" }.join(",")
            AsaasLogger.error("Problemas no processamento automático de Depósitos Bancários: Não foi possível processar os seguintes dados: ${errorInfo}")
        }
    }

    private List<PaymentDepositReceipt> findRespectivePaymentDepositReceiptList(Long bankDepositId) {
        BankDeposit bankDeposit = BankDeposit.get(bankDepositId)

        Map searchDefault = [billingType: bankDeposit.billingType, bank: bankDeposit.bank, value: bankDeposit.value]
        Map searchBuilded = [:]

        switch (bankDeposit.bank.code) {
            case SupportedBank.BANCO_DO_BRASIL.code():
            case SupportedBank.ITAU.code():
                searchBuilded << buildQueryParams(bankDeposit.billingType, bankDeposit.originAccount, bankDeposit.originAgency, bankDeposit.documentNumber, bankDeposit.documentDate)
                break
            case SupportedBank.BRADESCO.code():
                searchBuilded << buildBradescoQueryParams(bankDeposit.billingType, bankDeposit.documentNumber, bankDeposit.originName, bankDeposit.documentDate)
                break
            case SupportedBank.CAIXA.code():
                searchBuilded << buildCaixaQueryParams(bankDeposit.documentDate)
                break
            case SupportedBank.SANTANDER.code():
                searchBuilded << buildSantanderQueryParams(bankDeposit.billingType, bankDeposit.documentNumber, bankDeposit.originCpfCnpj, bankDeposit.documentDate)
                break
            default:
                return []
        }

        if (!searchBuilded) return []

        return PaymentDepositReceipt.awaitingConciliation(searchDefault + searchBuilded).list()
    }

    private BankDepositConciliation conciliateWithoutPaymentDepositReceiptIfPossible(BankDeposit bankDeposit) {
        BankDepositConciliation bankDepositConciliation = new BankDepositConciliation()

        List<InvoiceDepositInfo> invoiceDepositInfoList = findRespectiveInvoiceDepositInfoList(bankDeposit)

        if (invoiceDepositInfoList.size() != 1) {
            DomainUtils.addError(bankDepositConciliation, "Não foi encontrado somente um registro de dados da fatura para o mesmo depósito bancário.")
            return bankDepositConciliation
        }

        bankDepositConciliation = bankDepositConciliationService.conciliate(bankDeposit.id, invoiceDepositInfoList.first().id)

        return bankDepositConciliation
    }

    private List<InvoiceDepositInfo> findRespectiveInvoiceDepositInfoList(BankDeposit bankDeposit) {
        if (!bankDeposit.originName) return []

        final Integer businessDayToSubtract = 1

        Map search = [
                "lastUpdated[ge]": CustomDateUtils.subtractBusinessDays(bankDeposit.documentDate.clone().clearTime(), businessDayToSubtract),
                "lastUpdated[le]": CustomDateUtils.setTimeToEndOfDay(bankDeposit.documentDate),
                "billingType": bankDeposit.billingType,
                "bank": bankDeposit.bank,
                "payment.value": bankDeposit.value,
                "name[like]": bankDeposit.originName
        ]

        return InvoiceDepositInfo.visualizedAndWithoutPaymentDepositReceipt(search).list()
    }

    private Map buildQueryParams(BillingType billingType, String originAccount, String originAgency, Long documentNumber, Date documentDate) {
        Map defaultParams = ["documentDate[ge]": CustomDateUtils.subtractBusinessDays(documentDate, 1), "documentDate[le]": CustomDateUtils.addBusinessDays(documentDate, 1)]
        if (billingType.isTransfer() && originAccount && originAgency) {
            return [agency: originAgency, account: originAccount] + defaultParams
        }

        if (billingType.isDeposit() && documentNumber) {
            return [documentNumber: documentNumber] + defaultParams
        }

        return [:]
    }

    private Map buildBradescoQueryParams(BillingType billingType, Long documentNumber, String originName, Date documentDate) {
        Map defaultParams = ["documentDate[ge]": CustomDateUtils.subtractBusinessDays(documentDate, 1), "documentDate[le]": CustomDateUtils.addBusinessDays(documentDate, 1)]
        if (billingType.isTransfer() && originName) {
            return [name: originName] + defaultParams
        }

        if (billingType.isDeposit() && documentNumber) {
            String documentNumberParsed = documentNumber
            if (documentNumberParsed.length() > BRADESCO_DOCUMENT_NUMBER_MAXIMUM_CHARACTERS) {
                return [documentNumber: documentNumberParsed.substring(documentNumberParsed.length() - BRADESCO_DOCUMENT_NUMBER_MAXIMUM_CHARACTERS, documentNumberParsed.length())] + defaultParams
            }

            return [documentNumber: documentNumber] + defaultParams
        }

        return [:]
    }

    private Map buildCaixaQueryParams(Date documentDate) {
        Integer hour = CustomDateUtils.getHourOfDate(documentDate)
        Integer minute = CustomDateUtils.getMinuteOfDate(documentDate)

        if (hour == 0 && minute == 0) return [:]

        return ["documentDate": documentDate]
    }

    private Map buildSantanderQueryParams(BillingType billingType, Long documentNumber, String cpfCnpj, Date documentDate) {
        Map defaultParams = ["documentDate[ge]": CustomDateUtils.subtractBusinessDays(documentDate, 1), "documentDate[le]": CustomDateUtils.addBusinessDays(documentDate, 1)]
        if (billingType.isTransfer() && cpfCnpj) {
            return [cpfCnpj: cpfCnpj] + defaultParams
        }

        if (billingType.isDeposit() && documentNumber) {
            return [documentNumber: documentNumber] + defaultParams
        }

        return [:]
    }
}

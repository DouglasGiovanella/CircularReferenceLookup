package com.asaas.service.ted

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.billinginfo.BillingType
import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.ted.TedTransaction
import com.asaas.exception.BusinessException
import com.asaas.integration.jdspb.api.utils.JdSpbUtils
import com.asaas.log.AsaasLogger
import com.asaas.pagination.SequencedResultList
import com.asaas.payment.PaymentStatus
import com.asaas.ted.TedTransactionRejectedReason
import com.asaas.ted.TedTransactionStatus
import com.asaas.ted.TedTransactionType
import com.asaas.ted.adapter.TedAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class TedTransactionService {

    def grailsApplication
    def receivedPaymentCreationService
    def paymentConfirmService
    def paymentService
    def tedTransactionExternalAccountService

    public TedTransaction saveCreditTedTransaction(TedAdapter adapter) {
        TedTransaction validatedDomain = validateSave(adapter)
        if (validatedDomain.hasErrors()) throw new ValidationException("Erro ao salvar a TED", validatedDomain.errors)

        Customer customer = findCustomerByAccountInfo(adapter)

        TedTransaction tedTransaction = new TedTransaction()
        tedTransaction.externalIdentifier  = adapter.externalIdentifier
        tedTransaction.strControlNumber = adapter.strControlNumber
        tedTransaction.messageCode = adapter.messageCode
        tedTransaction.messageNumber = adapter.messageNumber
        tedTransaction.type = TedTransactionType.CREDIT
        tedTransaction.transactionDate = adapter.transactionDate
        tedTransaction.receivedDate = adapter.receivedDate
        tedTransaction.value = adapter.value
        tedTransaction.customer = customer
        tedTransaction.asaasRecipient = adapter.asaasRecipient
        tedTransaction.purposeCode = adapter.purposeCode
        tedTransaction.rejectedReason = buildRejectedReason(adapter, customer)
        tedTransaction.status = tedTransaction.rejectedReason ? TedTransactionStatus.REJECTED : TedTransactionStatus.PENDING
        tedTransaction.save(failOnError: true)

        tedTransactionExternalAccountService.save(tedTransaction, adapter.payer)

        return tedTransaction
    }

    public void executePendingTedTransactions() {
        final Integer transactionsLimit = 4000
        final Integer flushEvery = 50
        final Integer batchSize = 50

        List<Long> tedTransactionIdList = TedTransaction.query([column: "id", "status": TedTransactionStatus.PENDING]).list(max: transactionsLimit)
        List<Long> tedTransactionWithErrorIdList = []
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(tedTransactionIdList, batchSize, flushEvery, { Long tedTransactionId ->
            try {
                TedTransaction tedTransaction = TedTransaction.get(tedTransactionId)

                if (!tedTransaction.asaasRecipient) {
                    tedTransaction.payment = receivedPaymentCreationService.saveFromTedTransaction(tedTransaction)
                    paymentConfirmService.confirmPayment(tedTransaction.payment, tedTransaction.value, tedTransaction.transactionDate, BillingType.TRANSFER)
                }

                tedTransaction.status = tedTransaction.payment?.status?.isConfirmed() ? TedTransactionStatus.CONFIRMED : TedTransactionStatus.DONE

                tedTransaction.save(failOnError: true)
            } catch (Exception exception) {
                if (!Utils.isLock(exception)) {
                    AsaasLogger.error("TedTransactionService.executePendingTedTransactions >> Não foi possível processar o recebimento de TED [tedTransactionId: ${tedTransactionId}].", exception)
                    tedTransactionWithErrorIdList += tedTransactionId
                }

                throw exception
            }
        }, [logErrorMessage: "TedTransactionService.executePendingTedTransactions >> Erro ao processar recebimentos de Ted ", appendBatchToLogErrorMessage: true])

        if (tedTransactionWithErrorIdList) setListAsErrorWithNewTransaction(tedTransactionWithErrorIdList)
    }

    public Boolean executeConfirmedTransfersPaymentsCredit() {
        final Date automaticTransferConfirmStartDate = CustomDateUtils.setTime(new Date(), TedTransaction.DEFAULT_START_HOUR_TO_EXECUTE_TRANSFER_CREDIT, 0, 0)
        if (new Date() < automaticTransferConfirmStartDate) throw new RuntimeException("A execução automática de confirmação de transferências só pode ser realizada após as ${TedTransaction.DEFAULT_START_HOUR_TO_EXECUTE_TRANSFER_CREDIT}h")

        final Integer limitPaymentsToProcess = 2000
        Map search = [:]
        search.column = "id"
        search.status = PaymentStatus.CONFIRMED
        search.billingTypeList = [BillingType.TRANSFER]
        search.creditDate = new Date().clearTime()
        search.disableSort = true
        List<Long> paymentIdList = Payment.query(search).list(max: limitPaymentsToProcess)

        if (!paymentIdList) return false

        paymentConfirmService.executeCreditForPaymentList(paymentIdList)

        return true
    }

    public void rejectTedAfterPaymentRefundIfNecessary(Payment payment) {
        TedTransaction tedTransaction = TedTransaction.query([payment: payment]).get()
        if (!tedTransaction) return

        tedTransaction.status = TedTransactionStatus.REFUNDED
        tedTransaction.rejectedReason = TedTransactionRejectedReason.PAYMENT_REFUNDED
        tedTransaction.save(failOnError: true)
    }

    public void setTransactionAsDoneIfNecessary(Payment payment) {
        TedTransaction tedTransaction = TedTransaction.query([payment: payment]).get()
        if (!tedTransaction) return

        tedTransaction.status = TedTransactionStatus.DONE
        tedTransaction.save(failOnError: true)
    }

    public List<TedTransaction> list(Map params) {
        Map filters = buildListFilters(params)
        validateFilters(filters)
        return TedTransaction.query(filters).list(timeout: AsaasApplicationHolder.config.asaas.query.defaultTimeoutInSeconds)
    }

    public SequencedResultList<TedTransaction> listPaginated(Map params, Integer limitPerPage, Integer currentPage) {
        Map filters = buildListFilters(params)
        validateFilters(filters)

        return SequencedResultList.build(
            TedTransaction.query(filters),
            limitPerPage,
            currentPage
        )
    }

    private TedTransactionRejectedReason buildRejectedReason(TedAdapter adapter, Customer customer) {
        if (!adapter.asaasRecipient) {
            if (adapter.customerCpfCnpj == grailsApplication.config.asaas.cnpj.substring(1)) return TedTransactionRejectedReason.INVALID_CUSTOMER
            if (!customer) return  TedTransactionRejectedReason.CUSTOMER_NOT_FOUND
            if (customer.accountDisabled() || customer.status.isInactive() || !customer.hadGeneralApproval()) return TedTransactionRejectedReason.CUSTOMER_INACTIVE
        } else {
            if (adapter.customerCpfCnpj  && adapter.customerCpfCnpj != grailsApplication.config.asaas.cnpj.substring(1)) return TedTransactionRejectedReason.INVALID_TRANSACTION
        }

        if (JdSpbUtils.isInvalidPurposeCode(adapter.messageCode, adapter.purposeCode)) return TedTransactionRejectedReason.INVALID_PURPOSE_CODE

        final String defaultAgency = "1"
        if (adapter.agency && adapter.agency != defaultAgency) return TedTransactionRejectedReason.INVALID_AGENCY

        Date str0008ProcessingStartDate = CustomDateUtils.fromString("25/01/2024")
        if (adapter.messageCode == "STR0008R2" && adapter.transactionDate < str0008ProcessingStartDate) return TedTransactionRejectedReason.INVALID_INTERNAL_PROCESS_DATE

        return null
    }

    private Customer findCustomerByAccountInfo(TedAdapter adapter) {
        if (!adapter.customerCpfCnpj) return null
        if (!adapter.accountNumber || adapter.accountNumber.length() < 2) return null

        String accountDigit = adapter.accountNumber[-1]
        String account = adapter.accountNumber[0..-2]

        Customer customer = AccountNumber.query([column: "customer", customerCpfCnpj: adapter.customerCpfCnpj, account: account, accountDigit: accountDigit]).get()

        return customer
    }

    private TedTransaction validateSave(TedAdapter tedAdapter) {
        TedTransaction tedTransaction = new TedTransaction()

        if (!tedAdapter.externalIdentifier) return DomainUtils.addError(tedTransaction, "Identificador externo da mensagem não encontrado!")

        if (!tedAdapter.messageCode) return DomainUtils.addError(tedTransaction,"Código da mensagem não encontrado!")

        if (!JdSpbUtils.isSupportedCodeMessage(tedAdapter.messageCode)) return DomainUtils.addError(tedTransaction,"Código da mensagem não suportado")

        if (!tedAdapter.messageType) return DomainUtils.addError(tedTransaction,"Tipo de retorno da mensagem não encontrado")

        if (!tedAdapter.payer) return DomainUtils.addError(tedTransaction,"Informações do remetente não encontradas")

        if (!tedAdapter.transactionDate) return DomainUtils.addError(tedTransaction,"Data da transação não encontrada")

        return tedTransaction
    }

    private void setListAsErrorWithNewTransaction(List<Long> tedTransactionIdWithErrorList) {
        final Integer flushEvery = 100
        final Integer batchSize = 100

        Utils.forEachWithFlushSessionAndNewTransactionInBatch(tedTransactionIdWithErrorList, batchSize, flushEvery, { Long tedTransactionId ->
            TedTransaction tedTransaction = TedTransaction.get(tedTransactionId)
            tedTransaction.status = TedTransactionStatus.ERROR
            tedTransaction.save(failOnError: true)
        }, [logErrorMessage: "TedTransactionService.setListAsErrorWithNewTransaction >>> Erro ao alterar a status da transação para ERROR", appendBatchToLogErrorMessage: true])
    }

    private Map buildListFilters(Map params) {
        Map filters = [:]
        if (params.customerId) filters.customerId = params.customerId
        if (params.status) filters.status = params.status
        if (params."dateCreated[ge]") {
            filters."dateCreated[ge]" = CustomDateUtils.fromString(params."dateCreated[ge]")
        }
        if (params."dateCreated[le]") {
            filters."dateCreated[le]" = CustomDateUtils.setTimeToEndOfDay(params."dateCreated[le]")
        }
        if (params.strControlNumber) filters.strControlNumber = params.strControlNumber
        if (params.messageCode) filters.messageCode = params.messageCode
        if (params.messageNumber) filters.messageNumber = params.messageNumber

        return filters
    }

    private void validateFilters(Map filters) {
        final Integer daysLimitToFilter = 15

        if (!filters."dateCreated[ge]") {
            throw new BusinessException("A data inicial deve ser informada")
        }

        if (!filters."dateCreated[le]") {
            throw new BusinessException("A data final deve ser informada")
        }

        if (filters."dateCreated[ge]" > filters."dateCreated[le]") {
            throw new BusinessException("A data inicial deve ser anterior à data final")
        }

        if (CustomDateUtils.calculateDifferenceInDays(filters."dateCreated[ge]", filters."dateCreated[le]") > daysLimitToFilter) {
            throw new BusinessException("O período máximo para filtro é de até ${daysLimitToFilter} dias")
        }
    }
}

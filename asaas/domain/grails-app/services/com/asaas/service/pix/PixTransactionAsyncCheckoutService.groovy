package com.asaas.service.pix

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.criticalaction.CriticalAction
import com.asaas.domain.criticalaction.CustomerCriticalActionConfig
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.pix.PixTransaction
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixTransactionRefusalReason
import com.asaas.pix.PixTransactionType
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixTransactionAsyncCheckoutService {

    def financialTransactionService
    def lastCheckoutInfoService
    def pixTransactionService

    private List<Long> enabledPixAsyncCustomerIdListCache
    private Date expirationDateCache = null
    private final Integer minutesExpirationCache = 30

    public void processAwaitingBalanceValidationTransactions() {
        setCustomerIdListCacheIfNecessary()

        for (Long customerId : enabledPixAsyncCustomerIdListCache) {
            try {
                Customer customer = Customer.read(customerId)
                processTransactionsForCustomer(customer)
            } catch (Exception error) {
                AsaasLogger.error("${this.getClass().getSimpleName()}.processAwaitingBalanceValidationTransactions >> erro ao processar transações [customerId: ${customerId}]", error)
            }
        }
    }

    public void refuseAfterMaximumTimeReached() {
        Date maximumTime = CustomDateUtils.sumMinutes(new Date(), -20)
        List<Long> pixTransactionIdList = PixTransaction.awaitingBalanceValidation([column: "id", type: PixTransactionType.DEBIT, "dateCreated[lt]": maximumTime, disableSort: true]).list(max: 200)

        for (Long pixTransactionId : pixTransactionIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransaction pixTransaction = PixTransaction.get(pixTransactionId)
                if (!pixTransaction.status.isAwaitingBalanceValidation()) throw new RuntimeException("A situação da transação não permite recusar por falta de saldo.")

                PixTransactionRefusalReason reason = PixTransactionRefusalReason.INSUFFICIENT_BALANCE
                String refusalDescription = Utils.getMessageProperty("PixTransactionRefusalReason.${reason.toString()}")
                pixTransactionService.refuse(pixTransaction, reason, refusalDescription, null)
            }, [logErrorMessage: "${this.getClass().getSimpleName()}.refuseAfterMaximumTimeReached >> erro ao recusar transação [pixTransactionId: ${pixTransactionId}]"])
        }
    }

    private void processTransactionsForCustomer(Customer customer) {
        Map validPixTransactionsMap = listValidTransactions(customer)
        if (!validPixTransactionsMap) return

        if (validPixTransactionsMap.debitList) processDebitList(customer, validPixTransactionsMap.debitList)
        if (validPixTransactionsMap.creditRefundList) processCreditRefundList(customer, validPixTransactionsMap.creditRefundList)
    }

    private void processDebitList(Customer customer, List<Long> validPixTransactionListIds) {
        Utils.withNewTransactionAndRollbackOnError({
            List<PixTransaction> validPixTransactionList = PixTransaction.getAll(validPixTransactionListIds)
            CustomerCriticalActionConfig customerCriticalActionConfig = CustomerCriticalActionConfig.query([customerId: customer.id]).get()

            for (PixTransaction pixTransaction : validPixTransactionList) {
                if (pixTransactionService.shouldSetAsWaitingExternalAuthorization(customer, pixTransaction.originType)) {
                    pixTransactionService.setAsAwaitingExternalAuthorization(pixTransaction)
                } else if (customerCriticalActionConfig?.isPixTransactionAuthorizationEnabled()) {
                    pixTransactionService.setAsAwaitingCriticalActionAuthorization(pixTransaction)
                    CriticalAction.savePixTransaction(pixTransaction)
                } else {
                    pixTransactionService.setAsAwaitingRequest(pixTransaction)
                }
            }

            pixTransactionService.createDebitFinancialTransactionListForAsyncCheckout(customer, validPixTransactionList)
        }, [logErrorMessage: "${this.getClass().getSimpleName()}.processDebitList >> Erro ao processar transações [customerId: ${customer.id} validPixTransactionListIds: ${validPixTransactionListIds}]"])
    }

    private void processCreditRefundList(Customer customer, List<Long> validPixTransactionListIds) {
        Utils.withNewTransactionAndRollbackOnError({
            List<PixTransaction> pixTransactionList = PixTransaction.getAll(validPixTransactionListIds)

            lastCheckoutInfoService.save(customer)
            for (PixTransaction pixTransaction : pixTransactionList) {
                pixTransactionService.setAsAwaitingRequest(pixTransaction)
                financialTransactionService.refundPixTransactionCredit(pixTransaction)
            }
        }, [logErrorMessage: "${this.getClass().getSimpleName()}.processCreditRefundList >> Erro ao processar transações [customerId: ${customer.id} validPixTransactionListIds: ${validPixTransactionListIds}]"])
    }

    private Map listValidTransactions(Customer customer) {
        Map search = [
            columnList: ["id", "value", "type"],
            customerId: customer.id,
            "type[in]": [PixTransactionType.DEBIT, PixTransactionType.CREDIT_REFUND],
            disableSort: true,
            includeDeleted: true
        ]
        List<Map> pixTransactionMapList = PixTransaction.awaitingBalanceValidation(search).list(max: 40)
        if (!pixTransactionMapList) return null

        BigDecimal totalValue = 0
        BigDecimal maxCheckoutValue = calculateMaxCheckoutValue(customer)
        BigDecimal debitFee = CustomerFee.calculatePixDebitFee(customer)

        Map validTransactionsMap = [
            debitList: [],
            creditRefundList: []
        ]
        for (Map pixTransactionMap : pixTransactionMapList) {
            BigDecimal valueAbs = BigDecimalUtils.abs(pixTransactionMap.value)

            Boolean isDebit = (pixTransactionMap.type as PixTransactionType).isDebit()
            totalValue = totalValue + valueAbs
            if (isDebit) totalValue = totalValue + debitFee

            if (totalValue <= maxCheckoutValue) {
                if (isDebit) {
                    validTransactionsMap.debitList.add(pixTransactionMap.id)
                } else {
                    validTransactionsMap.creditRefundList.add(pixTransactionMap.id)
                }
            } else {
                break
            }
        }

        return validTransactionsMap
    }

    private BigDecimal calculateMaxCheckoutValue(Customer customer) {
        BigDecimal customerBalance = FinancialTransaction.getCustomerBalance(customer)
        BigDecimal availableDailyLimit = customer.getCheckoutLimit().availableDailyLimit

        return BigDecimalUtils.min(customerBalance, availableDailyLimit)
    }

    private void setCustomerIdListCacheIfNecessary() {
        Boolean expired = !enabledPixAsyncCustomerIdListCache || new Date() > expirationDateCache
        if (expired) {
            enabledPixAsyncCustomerIdListCache = CustomerParameter.query([column: "customer.id", name: CustomerParameterName.PIX_ASYNC_CHECKOUT, value: true]).list()
            expirationDateCache = CustomDateUtils.sumMinutes(new Date(), minutesExpirationCache)
        }
    }
}

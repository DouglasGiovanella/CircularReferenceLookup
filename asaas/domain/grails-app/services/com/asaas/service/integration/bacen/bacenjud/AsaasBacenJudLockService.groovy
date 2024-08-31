package com.asaas.service.integration.bacen.bacenjud

import com.asaas.bacen.bacenJud.AsaasActiveCustomerBalanceDTO
import com.asaas.bacen.bacenJud.AsaasCustomerBlockedBalanceDTO
import com.asaas.customer.CustomerParameterName
import com.asaas.debit.DebitType
import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.debit.Debit
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.utils.CpfCnpjUtils
import grails.transaction.Transactional
/**
 * Centralizador dos fluxos do [ plugin Bacen ]
 */
@Transactional
class AsaasBacenJudLockService {

    def asaasBacenJudManagerService

    def asyncActionService

    def customerAdminService

    def customerInteractionService

    def debitService

    def financialTransactionService

    def grailsApplication

    def judicialLockItemService

    def judicialProcessManualBalanceBlockService

    public List<AsaasActiveCustomerBalanceDTO> listActiveCustomerWithBalanceByCpfCnpj(String cpfCnpj, Boolean affectsBranches) {
        Map params = affectsBranches ? ['cpfCnpj[startsWith]': cpfCnpj.substring(0, 8)] : [cpfCnpj: cpfCnpj]
        params += [column: 'id', personType: CpfCnpjUtils.getPersonType(cpfCnpj)]

        return Customer.notDisabledAccounts(params).list().collect { Long customerId ->
            AsaasActiveCustomerBalanceDTO dto = new AsaasActiveCustomerBalanceDTO()
            dto.customerId = customerId
            dto.balance = FinancialTransaction.getCustomerBalance(customerId)
            return dto
        }
    }

    public List<AsaasCustomerBlockedBalanceDTO> listCustomerBlockedBalanceByCpfCnpj(String cpfCnpj) {
        Map params = [cpfCnpj: cpfCnpj, column: 'id']

        return Customer.query(params).list().collect { Long customerId ->
            AsaasCustomerBlockedBalanceDTO dto = new AsaasCustomerBlockedBalanceDTO()
            dto.customerId = customerId
            dto.balance = FinancialTransaction.getCustomerBalance(customerId)
            dto.blockedBalance = Debit.getBlockedBalance(customerId)?.value ?: 0
            dto.customerName = getCustomerName(customerId)
            return dto
        }
    }

    public LockAttemptResponseDTO tryLockValue(Long lockItemId, Long customerId, BigDecimal requestedLockValue, String detailForCustomer) {
        LockAttemptResponseDTO lockAttemptResponseDTO = new LockAttemptResponseDTO()
        lockAttemptResponseDTO.lockItemId = lockItemId

        BigDecimal balance = FinancialTransaction.getCustomerBalance(customerId)
        BigDecimal lockValue = balance < requestedLockValue ? balance : requestedLockValue

        if (grailsApplication.config.asaas.bacenJud.ignoredCustomerIdListOnLocks.contains(customerId) || lockValue <= 0) {
            lockAttemptResponseDTO.lockedValue = 0
            lockAttemptResponseDTO.processedDate = new Date()
            return lockAttemptResponseDTO
        }

        FinancialTransaction financialTransaction = financialTransactionService.saveJudicialLock(customerId, lockValue, detailForCustomer)
        lockAttemptResponseDTO.financialTransactionId = financialTransaction.id
        lockAttemptResponseDTO.lockedValue = financialTransaction.value * -1
        lockAttemptResponseDTO.processedDate = financialTransaction.dateCreated
        lockAttemptResponseDTO.hasTriggeredDisableCheckout = triggerDisableCustomerCheckoutIfNecessary(customerId, requestedLockValue, lockValue, detailForCustomer)
        judicialLockItemService.registerJudicialLock(financialTransaction.provider, lockValue, lockItemId, financialTransaction.id)
        String interactionDescription = """SISBAJUD - ORDEM JUDICIAL DE BLOQUEIO
${detailForCustomer}
Valor solicitado: ${requestedLockValue}
Valor efetivamente bloqueado: ${lockAttemptResponseDTO.lockedValue}
Ordem de bloqueio cumprida ${requestedLockValue == lockAttemptResponseDTO.lockedValue ? 'totalmente' : 'parcialmente (conta monitorada até cumprimento da ordem)'}"""
        customerInteractionService.save(financialTransaction.provider, interactionDescription)

        return lockAttemptResponseDTO
    }

    public UnlockResponseDTO unlockValue(Long unlockId, Long customerId, BigDecimal unlockValue, String detailForCustomer, Long reversedTransactionId) {
        UnlockResponseDTO unlockAttemptResponseDTO = new UnlockResponseDTO()
        unlockAttemptResponseDTO.unlockId = unlockId
        FinancialTransaction financialTransaction = financialTransactionService.saveJudicialUnlock(customerId, unlockValue, detailForCustomer, reversedTransactionId)
        unlockAttemptResponseDTO.financialTransactionId = financialTransaction.id
        unlockAttemptResponseDTO.unlockedValue = financialTransaction.value
        unlockAttemptResponseDTO.processedDate = financialTransaction.dateCreated
        judicialLockItemService.registerJudicialUnlock(financialTransaction.provider, unlockValue, unlockId, financialTransaction.id)
        String interactionDescription = """SISBAJUD - ORDEM JUDICIAL DE DESBLOQUEIO
${detailForCustomer}
Valor desbloqueado: ${unlockValue}"""
        customerInteractionService.save(financialTransaction.provider, interactionDescription)

        return unlockAttemptResponseDTO
    }

    public CancelLockResponseDTO cancelLock(Long cancelLockId, Long customerId, BigDecimal unlockValue, String detailForCustomer, Long reversedTransactionId) {
        CancelLockResponseDTO cancelLockResponseDTO = new CancelLockResponseDTO()
        cancelLockResponseDTO.cancelLockId = cancelLockId

        FinancialTransaction financialTransaction = financialTransactionService.saveJudicialUnlock(customerId, unlockValue, detailForCustomer, reversedTransactionId)
        cancelLockResponseDTO.financialTransactionId = financialTransaction.id
        cancelLockResponseDTO.unlockedValue = financialTransaction.value
        cancelLockResponseDTO.processedDate = financialTransaction.dateCreated
        judicialLockItemService.registerJudicialLockCancel(financialTransaction.provider, unlockValue, cancelLockId, financialTransaction.id)
        String interactionDescription = """SISBAJUD - ORDEM JUDICIAL DE CANCELAMENTO DE BLOQUEIO
${detailForCustomer}
Valor desbloqueado referente ao cancelamento: ${unlockValue}"""
        customerInteractionService.save(financialTransaction.provider, interactionDescription)

        return cancelLockResponseDTO
    }

    public Long getCustomerIdByAccountNumber(String agency, String accountNumberWithDigit) {
        Integer accountNumberLength = accountNumberWithDigit.length()
        String account = accountNumberWithDigit.substring(0, accountNumberLength)
        String accountDigit = accountNumberWithDigit.substring(accountNumberLength - 1)

        return AccountNumber.query([column: 'customer.id', agency: agency, account: account, accountDigit: accountDigit]).get()
    }

    public void markCustomersToReprocessJudicialLock() {
        List<Map> reprocessJudicialLockForCustomerAsyncActions = asyncActionService.listPendingReprocessJudicialLockForCustomer()

        reprocessJudicialLockForCustomerAsyncActions.groupBy { Map asyncActionData -> asyncActionData.cpfCnpj }.each {
            String cpfCnpj = it.key
            List<Long> asyncActionsIds = it.value.collect { Map asyncAction -> asyncAction.asyncActionId }

            asaasBacenJudManagerService.markCustomerToReprocessJudicialLocks(cpfCnpj)

            asyncActionService.deleteList(asyncActionsIds)
        }
    }

    public void enableCustomerCheckout(Long customerId) {
        try {
            Customer customer = Customer.get(customerId)
            customerAdminService.enableCheckout(customer, true)
        } catch (Exception e) {
            AsaasLogger.warn("[BacenJud] AsaasBacenJudLockService.enableCustomerCheckout >>> Não foi possível habilitar o checkout automaticamente para o Customer [${customerId}]", e)
        }
    }

    public String getCustomerName(Long customerId) {
        return Customer.read(customerId).getProviderName()
    }

    public UnlockResponseDTO reverseCustomerBalanceBlock(Long unlockId, Long customerId, BigDecimal unlockValue) {
        UnlockResponseDTO unlockResponseDTO = new UnlockResponseDTO()
        unlockResponseDTO.unlockId = unlockId
        unlockResponseDTO.unlockedValue = unlockValue

        FinancialTransaction financialTransaction = debitService.reverseBalanceBlock(customerId)
        unlockResponseDTO.financialTransactionId = financialTransaction.id
        unlockResponseDTO.processedDate = financialTransaction.transactionDate

        BigDecimal keepBlockedBalance = financialTransaction.value - unlockValue
        unlockResponseDTO.remainingValue = keepBlockedBalance

        if (keepBlockedBalance > 0) {
            saveBlockedBalanceKept(Customer.read(customerId), keepBlockedBalance)
        } else if (keepBlockedBalance < 0) {
            unlockResponseDTO.unlockedValue = financialTransaction.value
            AsaasLogger.warn("[bacenJud] Valor a desbloquear maior que o bloqueado. ID desbloqueio [${unlockId}] - valor solicitado [${unlockValue}] - diferença [${keepBlockedBalance}]")
        }

        return unlockResponseDTO
    }

    public JudicialDebitResponseDTO registerJudicialDebit(JudicialDebitRegisterDTO judicialDebitRegisterDTO) {
        if (!judicialDebitRegisterDTO.unlockValue && !judicialDebitRegisterDTO.debitValue && !judicialDebitRegisterDTO.keepLockValue) {
            throw new BusinessException('Deve existir ao menos um valor a ser processado: unlockValue, debitValue ou keepLockValue')
        }

        JudicialDebitResponseDTO responseDTO = new JudicialDebitResponseDTO()
        responseDTO.transferRequestItemId = judicialDebitRegisterDTO.transferRequestItemId
        Customer customer = Customer.read(judicialDebitRegisterDTO.customerId)

        if (judicialDebitRegisterDTO.unlockValue > 0) {
            String detailForCustomer = "Desbloqueio devido ao ${judicialDebitRegisterDTO.detailForCustomer}"

            FinancialTransaction financialTransaction = financialTransactionService.saveJudicialUnlock(judicialDebitRegisterDTO.customerId, judicialDebitRegisterDTO.unlockValue, detailForCustomer, judicialDebitRegisterDTO.reversedTransactionId)
            responseDTO.processedDate = financialTransaction.dateCreated

            judicialLockItemService.registerJudicialUnlockFromTransfer(financialTransaction.provider, judicialDebitRegisterDTO.unlockValue, judicialDebitRegisterDTO.transferRequestItemId, financialTransaction.id)
        }

        if (judicialDebitRegisterDTO.debitValue > 0) {
            String detailForCustomer = "Transferência ref. ao ${judicialDebitRegisterDTO.detailForCustomer}"

            BigDecimal debitValue = judicialDebitRegisterDTO.debitValue
            Debit debit = debitService.save(customer, debitValue, DebitType.JUDICIAL_TRANSFER, detailForCustomer, [:])

            responseDTO.debitId = debit.id
            responseDTO.processedDate = debit.dateCreated
        }

        if (judicialDebitRegisterDTO.keepLockValue > 0) {
            String detailForCustomer = "Bloqueio após transferência do ${judicialDebitRegisterDTO.detailForCustomer}"
            FinancialTransaction financialTransaction = financialTransactionService.saveJudicialLock(judicialDebitRegisterDTO.customerId, judicialDebitRegisterDTO.keepLockValue, detailForCustomer)
            responseDTO.processedDate = financialTransaction.dateCreated

            judicialLockItemService.registerJudicialLockFromTransfer(financialTransaction.provider, judicialDebitRegisterDTO.keepLockValue, judicialDebitRegisterDTO.transferRequestItemId, financialTransaction.id)
        }

        if (!responseDTO.processedDate) {
            AsaasLogger.warn("Item da solicitação de transferência [${judicialDebitRegisterDTO.transferRequestItemId}] não tem nada a fazer no cliente [${judicialDebitRegisterDTO.customerId}]")
            return responseDTO
        }

        String interactionDescription = """SISBAJUD - ORDEM JUDICIAL DE TRANSFERÊNCIA DE VALORES
Transferência do ${judicialDebitRegisterDTO.detailForCustomer}
Valor desbloqueado nessa solicitação: ${judicialDebitRegisterDTO.unlockValue}
Valor transferido nessa solicitação: ${judicialDebitRegisterDTO.debitValue}
Saldo ainda bloqueado dessa solicitação: ${judicialDebitRegisterDTO.keepLockValue}"""
        customerInteractionService.save(customer, interactionDescription)

        return responseDTO
    }

    public JudicialDebitResponseDTO registerJudicialDebitExternal(JudicialDebitRegisterDTO judicialDebitRegisterDTO) {
        JudicialDebitResponseDTO responseDTO = new JudicialDebitResponseDTO()
        responseDTO.transferRequestItemId = judicialDebitRegisterDTO.transferRequestItemId

        FinancialTransaction financialTransaction = debitService.reverseBalanceBlock(judicialDebitRegisterDTO.customerId)

        Customer customer = Customer.read(judicialDebitRegisterDTO.customerId)

        Debit debit = debitService.save(customer, judicialDebitRegisterDTO.debitValue, DebitType.JUDICIAL_PROCESS_BACENJUD, "Transferência judicial ref. ao ${judicialDebitRegisterDTO.detailForCustomer}", null)
        responseDTO.processedDate = debit.debitDate
        responseDTO.debitId = debit.id

        BigDecimal keepBlockedBalance = financialTransaction.value - judicialDebitRegisterDTO.debitValue
        responseDTO.remainingValue = keepBlockedBalance

        if (keepBlockedBalance > 0) {
            saveBlockedBalanceKept(customer, keepBlockedBalance)
        } else if (keepBlockedBalance < 0) {
            AsaasLogger.warn("[bacenJud] Valor a transferir maior que o bloqueado. ID transferência manual [${judicialDebitRegisterDTO.transferRequestItemId}] - valor solicitado [${judicialDebitRegisterDTO.debitValue}] - diferença [${keepBlockedBalance}]")
        }

        return responseDTO
    }

    private Boolean triggerDisableCustomerCheckoutIfNecessary(Long customerId, BigDecimal requestedLockValue, BigDecimal lockedValue, String detailForCustomer) {
        if (lockedValue >= requestedLockValue) return false

        if (CustomerParameter.getValue(customerId, CustomerParameterName.CHECKOUT_DISABLED)) return false

        customerAdminService.disableCheckout(customerId, detailForCustomer)
        return true
    }

    private void saveBlockedBalanceKept(Customer customer, BigDecimal keepBlockedBalance) {
        final String description = 'Mantendo valor bloqueado manualmente - BACEN'
        try {
            judicialProcessManualBalanceBlockService.save(customer, keepBlockedBalance, description)
        } catch (BusinessException businessException) {
            AsaasLogger.warn("[bacenJud] Mantendo o valor [${keepBlockedBalance}] bloqueado pelo processo antigo", businessException)
            debitService.save(customer, keepBlockedBalance, DebitType.BALANCE_BLOCK, description, null)
        }
    }
}

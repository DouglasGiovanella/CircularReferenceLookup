package com.asaas.service.integration.bacen.bacenjud

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.log.AsaasLogger
import com.asaas.utils.CpfCnpjUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

/**
 * Centralizador das chamadas [ ASAAS domain ] --> [ plugin Bacen ]
 */
@Transactional
class AsaasBacenJudManagerService {

    def asyncActionService
    def bacenJudAsaasService
    def bacenJudLockCacheService

    public void saveReprocessLockForCustomerIfNecessary(Long financialTransactionId, Customer customer) {
        if (!customer.cpfCnpj) {
            AsaasLogger.warn("AsaasBacenJudManagerService.saveReprocessLockForCustomerIfNecessary >> CpfCnpj vazio para a financialTransactionId [${financialTransactionId}] - customer.id [${customer.id}] (criado em ${customer.dateCreated} - status atual ${customer.status})")
            return
        }

        Boolean hasBypassForActiveMonitoringEnabled = CustomerParameter.getValue(customer, CustomerParameterName.BYPASS_JUDICIAL_LOCK_MONITORING)
        if (hasBypassForActiveMonitoringEnabled) return

        if (existsOpenedLock(customer.cpfCnpj)) saveReprocessLockForCustomer(financialTransactionId, customer)
    }

    public void markCustomerToReprocessJudicialLocks(String cpfCnpj) {
        bacenJudAsaasService.markCustomerToReprocessJudicialLocks(cpfCnpj)
    }

    private void saveReprocessLockForCustomer(Long financialTransactionId, Customer customer) {
        try {
            asyncActionService.saveReprocessJudicialLockForCustomer(financialTransactionId, customer.id, customer.cpfCnpj)
        } catch (ValidationException e) {
            AsaasLogger.error("AsaasBacenJudManagerService.saveReprocessLockForCustomer >> Erro ao salvar o async action da transação [${financialTransactionId}] do customer [${customer.id}] - ${e.message} ")
        }
    }

    private Boolean existsOpenedLock(String cpfCnpj) {
        List<String> openedLockedForCpfCnpjList = bacenJudLockCacheService.listOpenedDefendantCpfCnpj()
        if (openedLockedForCpfCnpjList.contains(cpfCnpj)) return true
        if (CpfCnpjUtils.isCpf(cpfCnpj)) return false

        String rootCnpj = cpfCnpj.substring(0, 8)
        List<String> openedLockedForRootCpfCnpjList = bacenJudLockCacheService.listOpenedDefendantRootCnpj()
        return openedLockedForRootCpfCnpjList.contains(rootCnpj)
    }
}

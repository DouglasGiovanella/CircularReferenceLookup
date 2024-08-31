package com.asaas.service.integration.sage

import com.asaas.domain.creditpolicy.CreditPolicyProfile
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.integration.sage.SageManager
import com.asaas.integration.sage.adapter.bureauReport.SageSummaryBureauReportAdapter
import com.asaas.integration.sage.adapter.scr.SageCustomerScrSummaryAdapter
import com.asaas.integration.sage.dto.account.SageSaveAccountRequestDTO
import com.asaas.integration.sage.dto.bureauReport.SageSerasaBureauReportQueryUpdateRequestDTO
import com.asaas.integration.sage.dto.bureauReport.SageSummaryBureauReportResponseDTO
import com.asaas.integration.sage.dto.profile.SageSaveCreditPolicyProfileRequestDTO
import com.asaas.integration.sage.dto.scr.SageCustomerScrSummaryResponseDTO
import com.asaas.integration.sage.dto.scr.SageFetchScrBacenQueryRequestDTO
import com.asaas.integration.sage.dto.user.CreateUserRequestDTO
import com.asaas.integration.sage.enums.SageRequestOrigin
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class SageManagerService {

    public void disableCreditPolicyProfile(CreditPolicyProfile profile) {
        if (!SageManager.isAvailable()) return

        SageManager sageManager = new SageManager()
        sageManager.post("/api/customer/disablePolicyProfile/${profile.customer.id}", [:])

        if (!sageManager.isSuccessful()) {
            AsaasLogger.error("SageManagerService.disableCreditPolicyProfile -> Erro ao desabilitar perfil, profileId: [${profile.id}]")
            throw new RuntimeException(sageManager.getErrorMessage())
        }
    }

    public void registerCreditPolicyProfile(CreditPolicyProfile profile) {
        if (!SageManager.isAvailable()) return

        SageSaveCreditPolicyProfileRequestDTO profileRequestDTO = new SageSaveCreditPolicyProfileRequestDTO(profile)

        SageManager sageManager = new SageManager()
        sageManager.post("/api/account/saveCreditPolicyProfile", profileRequestDTO.properties)

        if (!sageManager.isSuccessful()) {
            AsaasLogger.error("SageManagerService.registerCreditPolicyProfile -> Erro ao realizar o envio do perfil ao motor de crédito, profileId: [${profile.id}]")
            throw new RuntimeException(sageManager.getErrorMessage())
        }
    }

    public void enableUser(Long userId, String username) {
        if (!SageManager.isAvailable()) return

        CreateUserRequestDTO requestDTO = new CreateUserRequestDTO(userId, username)

        SageManager manager = new SageManager()
        manager.post("/api/user/enable", requestDTO.properties)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("SageManagerService.enableUser >> Erro ao habilitar usuário [userId: ${userId}]")
            throw new RuntimeException(manager.getErrorMessage())
        }
    }

    public void disableUser(Long userId) {
        if (!SageManager.isAvailable()) return

        SageManager manager = new SageManager()
        manager.put("/api/user/disable/${userId}", null)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("SageManagerService.disableUser >> Erro ao desabilitar usuário [userId: ${userId}]")
            throw new RuntimeException(manager.getErrorMessage())
        }
    }

    public void saveAccountFromCustomer(Customer customer) {
        if (!SageManager.isAvailable()) return

        SageSaveAccountRequestDTO requestDTO = new SageSaveAccountRequestDTO(customer)

        SageManager manager = new SageManager()
        manager.post("/api/account/save", requestDTO.properties)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("SageManagerService.saveAccountFromCustomer >> Erro ao salvar Account no Sage, customerId: [${customer.id}]")
            throw new RuntimeException(manager.getErrorMessage())
        }
    }

    public void inactivateAccountFromCustomerId(Long customerId) {
        if (!SageManager.isAvailable()) return

        SageManager manager = new SageManager()
        manager.post("/api/account/inactivate/${customerId}", [:])

        if (manager.isNotFound()) return

        if (!manager.isSuccessful()) {
            AsaasLogger.error("SageManagerService.inactivateAccountFromCustomerId >> Erro ao inativar account no Sage [customerId: ${customerId}]")
            throw new RuntimeException(manager.getErrorMessage())
        }
    }

    public SageSummaryBureauReportAdapter getSerasaBureauReportSummary(String cpfCnpj) {
        if (!SageManager.isAvailable()) return null

        SageManager manager = new SageManager()
        manager.notWarnNonSuccessErrors()
        manager.get("/api/bureauReport/${cpfCnpj}/summary", [:])

        if (manager.isNotFound()) return null

        if (!manager.isSuccessful()) {
            AsaasLogger.error("SageManagerService.getSerasaBureauReportSummary >> Erro ao buscar resumo da consulta do Serasa no Sage [cpfCnpj: ${cpfCnpj}]", new RuntimeException(manager.getErrorMessage()))
            return null
        }

        SageSummaryBureauReportResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((manager.responseBody as JSON).toString(), SageSummaryBureauReportResponseDTO)

        return new SageSummaryBureauReportAdapter(responseDTO)
    }

    public void updateSerasaBureauReportQuery(Long customerId, SageRequestOrigin requestOrigin) {
        if (!SageManager.isAvailable()) return

        SageSerasaBureauReportQueryUpdateRequestDTO requestDTO = new SageSerasaBureauReportQueryUpdateRequestDTO(UserUtils.getCurrentUser(), requestOrigin)

        SageManager manager = new SageManager()
        manager.post("/api/bureauReport/${customerId}/updateQuery", requestDTO.properties)

        if (!manager.isSuccessful()) {
            AsaasLogger.error("SageManagerService.updateSerasaBureauReportQuery >> Erro ao atualizar a consulta Serasa no Sage [customerId: ${customerId}]", new Exception(manager.getErrorMessage()))
            throw new BusinessException("Ocorreu um erro ao solicitar a atualização da consulta Serasa")
        }
    }

    public SageCustomerScrSummaryAdapter getCustomerScrSummary(Long customerId) {
        if (!SageManager.isAvailable()) return null

        SageManager manager = new SageManager()
        manager.notWarnNonSuccessErrors()
        manager.get("/api/externalCreditInfo/${customerId}", [:])

        if (manager.isNotFound()) return null

        if (!manager.isSuccessful()) {
            AsaasLogger.error("SageManagerService.getCustomerScrSummary >> Erro ao buscar dados do último relatório SCR no Sage [customerId: ${customerId}]", new RuntimeException(manager.getErrorMessage()))
            return null
        }

        SageCustomerScrSummaryResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((manager.responseBody as JSON).toString(), SageCustomerScrSummaryResponseDTO)

        return new SageCustomerScrSummaryAdapter(responseDTO)
    }

    public void fetchScrBacenQueryIfNecessary(Long customerId, SageRequestOrigin requestOrigin) {
        if (!SageManager.isAvailable()) return

        SageFetchScrBacenQueryRequestDTO requestDTO = new SageFetchScrBacenQueryRequestDTO(UserUtils.getCurrentUser(), requestOrigin)

        SageManager manager = new SageManager()
        manager.post("/api/externalCreditInfo/${customerId}", requestDTO.properties)

        if (manager.isSuccessful()) return

        AsaasLogger.error("SageManagerService.fetchScrBacenQueryIfNecessary >> Erro ao solicitar novo relatório SCR no Sage [customerId: ${customerId}][analystUserId: ${UserUtils.getCurrentUser().id}]", new RuntimeException(manager.getErrorMessage()))
        throw new RuntimeException(manager.getErrorMessage())
    }
}

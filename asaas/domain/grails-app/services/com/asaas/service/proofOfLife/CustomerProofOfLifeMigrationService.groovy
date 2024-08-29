package com.asaas.service.proofOfLife

import com.asaas.customerdocumentgroup.CustomerDocumentGroupType
import com.asaas.domain.authorizationdevice.AuthorizationDeviceUpdateRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdocumentgroup.CustomerDocumentGroup
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.proofOfLife.CustomerProofOfLife
import com.asaas.log.AsaasLogger
import com.asaas.proofOfLife.ProofOfLifeType
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerProofOfLifeMigrationService {

    def customerDocumentGroupProxyService
    def customerDocumentMigrationCacheService
    def customerDocumentProxyService
    def customerInteractionService
    def customerProofOfLifeService

    public void migrateToSelfieFromCustomerIfPossible(Customer customer) {
        Map customerProofOfLifeInfo = CustomerProofOfLife.query([columnList: ["approved", "type"], customer: customer]).get()

        if (!customerProofOfLifeInfo) {
            CustomerProofOfLife customerProofOfLife = customerProofOfLifeService.saveIfNecessary(customer, ProofOfLifeType.SELFIE)
            customerDocumentGroupProxyService.addSelfieToIdentificationGroupIfNecessary(customer)
            saveCustomerInteractionIfNecessary(customerProofOfLife)
            return
        }

        if (customerProofOfLifeInfo.approved) return

        ProofOfLifeType proofOfLifeType = customerProofOfLifeInfo.type
        if (proofOfLifeType.isConfirmedTransfer()) {
            CustomerProofOfLife customerProofOfLife = customerProofOfLifeService.updateToSelfieIfPossible(customer)
            customerDocumentGroupProxyService.addSelfieToIdentificationGroupIfNecessary(customer)
            saveCustomerInteractionIfNecessary(customerProofOfLife)
        }
    }

    public void migrateToSelfieFromAuthorizationDeviceUpdateRequest(AuthorizationDeviceUpdateRequest authorizationDeviceUpdateRequest) {
        try {
            if (customerDocumentMigrationCacheService.hasDocumentationInHeimdall(authorizationDeviceUpdateRequest.customerId)) return
            if (!authorizationDeviceUpdateRequest.authorizationDevice.type.isMobileAppToken()) return
            if (!authorizationDeviceUpdateRequest.file) return

            CustomerProofOfLife customerProofOfLife = migrateToSelfieIfPossible(authorizationDeviceUpdateRequest.customer, authorizationDeviceUpdateRequest.file, AsaasFile.FILES_DIRECTORY, true)
            saveCustomerInteractionIfNecessary(customerProofOfLife)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerProofOfLifeMigrationService.migrateToSelfieFromAuthorizationDeviceUpdateRequest >> erro ao migrar selfie e prova de vida. AuthorizationDeviceUpdateRequest [${authorizationDeviceUpdateRequest.id}]", exception)
        }
    }

    private CustomerProofOfLife migrateToSelfieIfPossible(Customer customer, AsaasFile selfieFile, String selfieFileDirectory, Boolean shouldApprove) {
        if (customer.getProofOfLifeType().isSelfie()) return null

        Map search = [column: "id"]
        search.customer = customer
        if (customer.isNaturalPerson()) {
            search.typeList = [CustomerDocumentGroupType.ASAAS_ACCOUNT_OWNER, CustomerDocumentGroupType.ASAAS_ACCOUNT_OWNER_EMANCIPATION_AGE]
        } else {
            search.type = customerDocumentGroupProxyService.getIdentificationGroupType(customer)
        }

        Long customerDocumentGroupId = CustomerDocumentGroup.query(search).get()
        if (!customerDocumentGroupId) {
            AsaasLogger.warn("CustomerProofOfLifeMigrationService.migrateToSelfieIfPossible >> Não iremos migrar pois conta não tem o devido grupo de documentos. Customer [${customer.id}]")
            return null
        }

        customerDocumentProxyService.copyFromAsaasFile(customer, customerDocumentGroupId, selfieFile, selfieFileDirectory)

        CustomerProofOfLife customerProofOfLife = customerProofOfLifeService.saveIfNecessary(customer, ProofOfLifeType.SELFIE)

        customerProofOfLifeService.updateToSelfieIfPossible(customerProofOfLife, true)

        if (shouldApprove) {
            customerProofOfLifeService.approve(customerProofOfLife)
        }

        return customerProofOfLife
    }

    private void saveCustomerInteractionIfNecessary(CustomerProofOfLife customerProofOfLife) {
        try {
            if (!customerProofOfLife) return
            if (!customerProofOfLife.type.isSelfie()) return

            String description = Utils.getMessageProperty("customerInteraction.customerProofOfLifeMigratedToSelfie")
            customerInteractionService.saveSecurityEvent(customerProofOfLife.customer.id, description)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerProofOfLifeMigrationService.saveCustomerInteraction >> CustomerID [${customerProofOfLife.customer.id}]", exception)
        }
    }
}

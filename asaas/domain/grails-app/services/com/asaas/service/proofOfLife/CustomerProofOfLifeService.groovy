package com.asaas.service.proofOfLife

import com.asaas.customer.CustomerParameterName
import com.asaas.customerdocument.CustomerDocumentStatus
import com.asaas.customerdocument.CustomerDocumentType
import com.asaas.domain.accountowner.ChildAccountParameter
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.proofOfLife.CustomerProofOfLife
import com.asaas.log.AsaasLogger
import com.asaas.proofOfLife.ProofOfLifeType
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
public class CustomerProofOfLifeService {

    def accountNumberService
    def customerDocumentProxyService
    def synchronizeHermesAccountNumberService

    public CustomerProofOfLife saveForNewCustomer(Customer customer) {
        if (!customer.accountOwner) return saveIfNecessary(customer, ProofOfLifeType.SELFIE)

        Boolean isThirdPartyOnboardingEnabledByAccountOwner = CustomerParameter.getValue(customer.accountOwner, CustomerParameterName.ENABLE_THIRD_PARTY_ONBOARDING_FOR_CHILD_ACCOUNTS)
        if (isThirdPartyOnboardingEnabledByAccountOwner) {
            return saveIfNecessary(customer, ProofOfLifeType.SELFIE)
        }

        Map searchParams = [
            column: "value",
            type: CustomerParameter.simpleName,
            name: CustomerParameterName.WHITE_LABEL.toString(),
            accountOwnerId: customer.accountOwner.id
        ]
        String childAccountParameterValue = ChildAccountParameter.query(searchParams).get()
        Boolean isWhiteLabelCustomer = Utils.toBoolean(childAccountParameterValue)
        if (!isWhiteLabelCustomer) {
            return saveIfNecessary(customer, ProofOfLifeType.SELFIE)
        }

        return saveIfNecessary(customer, ProofOfLifeType.CONFIRMED_TRANSFER)
    }

    public void autoApproveByAccountOwner(Customer customer) {
        try {
            if (!canAutoApproveByAccountOwner(customer)) throw new RuntimeException("Cliente não atendeu os critérios de auto aprovação de prova de vida")

            CustomerProofOfLife customerProofOfLife = saveIfNecessary(customer, ProofOfLifeType.ACCOUNT_OWNER)
            if (customerProofOfLife.approved) return

            if (customerProofOfLife.type != ProofOfLifeType.ACCOUNT_OWNER) customerProofOfLife.type = ProofOfLifeType.ACCOUNT_OWNER

            approve(customerProofOfLife)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerProofOfLifeService.autoApproveByAccountOwner -> Erro ao auto-aprovar prova de vida de conta filha. customerId: [${customer.id}] - accountOwnerId: [${customer.accountOwner?.id}]", exception)
        }
    }

    public void approveByCreditTransferIfPossible(Customer customer) {
        try {
            CustomerProofOfLife customerProofOfLife = saveIfNecessary(customer, ProofOfLifeType.CONFIRMED_TRANSFER)
            if (customerProofOfLife.type.isSelfie()) return
            if (customerProofOfLife.approved) return

            approve(customerProofOfLife)
        } catch (Exception exception) {
            AsaasLogger.error("Erro ao aprovar prova de vida via transferencia confirmada. customerId: [${customer.id}]", exception)
        }
    }

    public void approveBySelfieIfPossible(Customer customer) {
        try {
            Boolean hasIdentificationApproved = customerDocumentProxyService.exists(customer.id, [
                status: CustomerDocumentStatus.APPROVED,
                "type[in]": [CustomerDocumentType.IDENTIFICATION_SELFIE, CustomerDocumentType.IDENTIFICATION]
            ])
            if (!hasIdentificationApproved) return

            CustomerProofOfLife customerProofOfLife = CustomerProofOfLife.query([customer: customer]).get()

            if (!customerProofOfLife) return
            if (customerProofOfLife.type == ProofOfLifeType.CONFIRMED_TRANSFER) return
            if (customerProofOfLife.approved) return

            approve(customerProofOfLife)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerProofOfLifeService.approveBySelfieIfPossible -> Erro ao aprovar prova de vida via Selfie. customerId: [${customer.id}]", exception)
        }
    }

    public CustomerProofOfLife updateToSelfieIfPossible(Customer customer) {
        CustomerProofOfLife customerProofOfLife = CustomerProofOfLife.query([customer: customer]).get()
        updateToSelfieIfPossible(customerProofOfLife, false)

        return customerProofOfLife
    }

    public void updateToSelfieIfPossible(CustomerProofOfLife customerProofOfLife, Boolean forceUpdate) {
        if (!customerProofOfLife) return

        if (customerProofOfLife.type.isSelfie()) return

        if (customerProofOfLife.approved && !forceUpdate) return

        customerProofOfLife.type = ProofOfLifeType.SELFIE
        customerProofOfLife.save(failOnError: true)
    }

    public void approve(CustomerProofOfLife customerProofOfLife) {
        customerProofOfLife.approved = true
        customerProofOfLife.approvedDate = new Date()
        customerProofOfLife.save(failOnError: true)

        accountNumberService.saveIfNotExists(customerProofOfLife.customer)
        synchronizeHermesAccountNumberService.create(customerProofOfLife.customer)
    }

    public CustomerProofOfLife saveIfNecessary(Customer customer, ProofOfLifeType type) {
        CustomerProofOfLife customerProofOfLife = CustomerProofOfLife.query([customer: customer]).get()

        if (customerProofOfLife) return customerProofOfLife

        customerProofOfLife = new CustomerProofOfLife()
        customerProofOfLife.customer = customer
        customerProofOfLife.type = type
        customerProofOfLife.approved = false
        customerProofOfLife.save(failOnError: true)

        return customerProofOfLife
    }

    private Boolean canAutoApproveByAccountOwner(Customer customer) {
        if (customer.accountOwner) return true

        return CustomerPartnerApplication.hasValidApplicationToAutoApprove(customer.id)
    }
}

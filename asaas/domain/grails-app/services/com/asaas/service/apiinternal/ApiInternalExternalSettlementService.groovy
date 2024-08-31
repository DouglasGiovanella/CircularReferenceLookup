package com.asaas.service.apiinternal

import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.externalsettlement.ExternalSettlement
import com.asaas.domain.externalsettlement.ExternalSettlementIdempotency
import com.asaas.exception.BankAccountInfoNotFoundException
import com.asaas.externalsettlement.repository.ExternalSettlementIdempotencyRepository
import com.asaas.externalsettlement.ExternalSettlementOrigin
import com.asaas.externalsettlement.adapter.CreateExternalSettlementRequestAdapter
import com.asaas.service.externalsettlement.ExternalSettlementContractualEffectSettlementBatchService
import com.asaas.service.externalsettlement.ExternalSettlementService
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional
import groovy.transform.CompileDynamic
import org.codehaus.groovy.grails.commons.GrailsApplication

@GrailsCompileStatic
@Transactional
class ApiInternalExternalSettlementService {

    ExternalSettlementContractualEffectSettlementBatchService externalSettlementContractualEffectSettlementBatchService
    ExternalSettlementService externalSettlementService
    GrailsApplication grailsApplication

    @CompileDynamic
    public void save(CreateExternalSettlementRequestAdapter adapter) {
        Boolean alreadyExistsExternalSettlement = ExternalSettlementIdempotencyRepository.query([publicId: adapter.publicId]).exists()
        if (alreadyExistsExternalSettlement) return

        Customer asaasCustomer = Customer.get(grailsApplication.config.asaas.contractualEffectSettlementBatch.customer.id)
        BankAccountInfo bankAccountInfo = externalSettlementContractualEffectSettlementBatchService.saveBankAccountInfoForBankAccountIfNecessary(adapter.bankAccountAdapter, asaasCustomer)
        if (!bankAccountInfo) throw new BankAccountInfoNotFoundException("Não foi possível localizar a conta bancária com o ISPB: [${adapter.bankAccountAdapter.ispb}]")

        ExternalSettlement externalSettlement = externalSettlementService.save(asaasCustomer, bankAccountInfo, adapter.value, ExternalSettlementOrigin.RECEIVABLE_HUB_CONTRACTUAL_EFFECT_SETTLEMENT)
        saveIdempotency(adapter.publicId, externalSettlement)
    }

    private void saveIdempotency(String publicId, ExternalSettlement externalSettlement) {
        ExternalSettlementIdempotency idempotency = new ExternalSettlementIdempotency()
        idempotency.publicId = publicId
        idempotency.externalSettlement = externalSettlement
        idempotency.save(failOnError: true)
    }
}

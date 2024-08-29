package com.asaas.service.integration.cerc

import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.CercContract
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.integration.cerc.contractualeffect.CercContractualEffectSettlement
import com.asaas.integration.cerc.enums.CercContractStatus
import com.asaas.integration.cerc.enums.CercProcessingStatus
import grails.transaction.Transactional

@Transactional
class CercContractService {

    public List<CercContract> list(Customer customer, Integer max, Integer offset, Map filter) {
        if (!filter) filter = [:]
        filter.customerCpfCnpj = customer.cpfCnpj
        filter.ignoringFidc = true

        return CercContract.query(filter).list(max: max, offset: offset)
    }

    public CercContract saveIfNecessary(String customerCpfCnpj, String externalIdentifier, String beneficiaryCpfCnpj) {
        CercContract cercContract = CercContract.find(customerCpfCnpj, externalIdentifier)
        if (cercContract) return cercContract

        cercContract = new CercContract()
        cercContract.externalIdentifier = externalIdentifier
        cercContract.beneficiaryCpfCnpj = beneficiaryCpfCnpj
        cercContract.customerCpfCnpj = customerCpfCnpj
        cercContract.status = CercContractStatus.PENDING
        cercContract.save(failOnError: true)

        return cercContract
    }

    public void consolidate(CercContract contract) {
        if (!contract) return

        contract.value = CercContractualEffect.sumValue([contract: contract]).get()
        contract.settledValue = CercContractualEffectSettlement.sumValue([contractId: contract.id]).get()

        Boolean existsActiveContractualEffects = CercContractualEffect.query([exists: true, contract: contract, "status[in]": CercProcessingStatus.listActiveStatuses()]).get().asBoolean()
        if (existsActiveContractualEffects) {
            contract.status = CercContractStatus.PENDING
        } else {
            contract.status = CercContractStatus.SETTLED
        }

        contract.save(failOnError: true)
    }

    public Date getClosestEstimatedCreditDate(CercContract contract) {
        Date estimatedCreditDate = CercContractualEffect.query([column: "estimatedCreditDate", contract: contract, "estimatedCreditDate[ge]": new Date()]).get()
        if (estimatedCreditDate) return estimatedCreditDate

        return CercContractualEffect.query([column: "estimatedCreditDate", contract: contract, "estimatedCreditDate[le]": new Date()]).get()
    }
}

package com.asaas.service.externalsettlement

import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.externalsettlement.ExternalSettlement
import com.asaas.domain.transfer.Transfer
import com.asaas.externalsettlement.ExternalSettlementOrigin
import com.asaas.externalsettlement.ExternalSettlementStatus
import grails.transaction.Transactional

@Transactional
class ExternalSettlementService {

    public ExternalSettlement save(Customer asaasCustomer, BankAccountInfo bankAccountInfo, BigDecimal totalValue, ExternalSettlementOrigin origin) {
        ExternalSettlement externalSettlement = new ExternalSettlement()
        externalSettlement.asaasCustomer = asaasCustomer
        externalSettlement.bankAccountInfo = bankAccountInfo
        externalSettlement.totalValue = totalValue
        externalSettlement.origin = origin
        externalSettlement.status = ExternalSettlementStatus.PENDING
        externalSettlement.save(failOnError: true)

        return externalSettlement
    }

    public void setAsInProgress(ExternalSettlement externalSettlement) {
        externalSettlement.status = ExternalSettlementStatus.IN_PROGRESS
        externalSettlement.save(failOnError: true)
    }

    public void setAsProcessed(ExternalSettlement externalSettlement) {
        externalSettlement.status = ExternalSettlementStatus.PROCESSED
        externalSettlement.save(failOnError: true)
    }

    public void setAsRefunded(ExternalSettlement externalSettlement) {
        externalSettlement.status = ExternalSettlementStatus.REFUNDED
        externalSettlement.save(failOnError: true)
    }

    public void setAsError(ExternalSettlement externalSettlement) {
        externalSettlement.status = ExternalSettlementStatus.ERROR
        externalSettlement.save(failOnError: true)
    }

    public void setAsPreProcessedIfPossible(Transfer transfer) {
        ExternalSettlement externalSettlement = ExternalSettlement.query([transferId: transfer.id]).get()
        if (!externalSettlement) return

        externalSettlement.status = ExternalSettlementStatus.PRE_PROCESSED
        externalSettlement.save(failOnError: true)
    }
}

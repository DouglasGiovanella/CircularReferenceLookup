package com.asaas.service.integration.cerc.batch.companyconciliation

import com.asaas.domain.integration.cerc.CercBatch
import com.asaas.domain.integration.cerc.conciliation.company.CompanyConciliationItem
import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.exception.BusinessException
import com.asaas.integration.cerc.adapter.batch.CompanyConciliationItemAdapter
import com.asaas.integration.cerc.enums.CercBatchItemStatus
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CompanyConciliationItemService {

    def cercCompanyService

    public void save(CercBatch batch, CompanyConciliationItemAdapter itemAdapter) {
        CompanyConciliationItem item = new CompanyConciliationItem()
        item.batch = batch
        item.status = CercBatchItemStatus.PENDING
        item.externalIdentifier = itemAdapter.externalIdentifier
        item.customerCpfCnpj = itemAdapter.customerCpfCnpj
        item.companyStatus = itemAdapter.companyStatus
        item.save(failOnError: true)
    }

    public void processPendingItems() {
        final Integer maxItems = 300
        List<Long> itemIdList = CompanyConciliationItem.query([column: "id", status: CercBatchItemStatus.PENDING]).list(max: maxItems)

        for (Long itemId : itemIdList) {
            Boolean hasError = false

            Utils.withNewTransactionAndRollbackOnError({
                CompanyConciliationItem item = CompanyConciliationItem.get(itemId)

                CercCompany cercCompany = CercCompany.query([cpfCnpj: item.customerCpfCnpj]).get()
                if (!cercCompany) throw new BusinessException("CompanyConciliationItemService.processPendingItems >> O item não existe para o CPF/CNPJ [customerCpfCnpj: ${item.customerCpfCnpj}]")

                if (item.companyStatus != cercCompany.status) {
                    cercCompanyService.updateIfNecessary(cercCompany)
                    if (!cercCompany.syncStatus.isAwaitingSync()) throw new BusinessException("CompanyConciliationItemService.processPendingItems >> O item não foi atualizado [customerCpfCnpj: ${item.customerCpfCnpj}, cercCompanyId: ${cercCompany.id}]")
                }

                setAsProcessed(item)
            }, [logErrorMessage: "CompanyConciliationItemService.processPendingItems >> Erro ao processar item [${itemId}]", onError: { hasError = true }])

            if (hasError) {
                Utils.withNewTransactionAndRollbackOnError({
                    CompanyConciliationItem item = CompanyConciliationItem.get(itemId)
                    setAsError(item)
                }, [logErrorMessage: "CompanyConciliationItemService.processPendingItems >> Falha ao marcar item como ERROR [${itemId}]"])
            }
        }

    }

    private void setAsProcessed(CompanyConciliationItem item) {
        item.status = CercBatchItemStatus.PROCESSED
        item.save(failOnError: true)
    }

    private void setAsError(CompanyConciliationItem item) {
        item.status = CercBatchItemStatus.ERROR
        item.save(failOnError: true)
    }
}

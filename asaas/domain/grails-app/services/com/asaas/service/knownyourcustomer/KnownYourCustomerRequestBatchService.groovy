package com.asaas.service.knownyourcustomer

import com.asaas.domain.customer.Customer
import com.asaas.domain.knownyourcustomer.KnownYourCustomerRequest
import com.asaas.domain.knownyourcustomer.KnownYourCustomerRequestBatch
import com.asaas.knownyourcustomer.KnownYourCustomerRequestBatchStatus
import com.asaas.knownyourcustomer.KnownYourCustomerRequestStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class KnownYourCustomerRequestBatchService {

    def chargedFeeService

    def knownYourCustomerRequestService

    public void createDailyBatches() {
        final Integer maxItemsPerCycle = 500
        Date yesterday = CustomDateUtils.setTimeToEndOfDay(CustomDateUtils.getYesterday())

        List<KnownYourCustomerRequest> pendingKnownYourCustomerRequestList = KnownYourCustomerRequest.query([status: KnownYourCustomerRequestStatus.PENDING,
                                                                                                             "dateCreated[le]": yesterday]).list(max: maxItemsPerCycle)
        if (!pendingKnownYourCustomerRequestList) return

        Map pendingRequestsGroupedByAccountOwner = pendingKnownYourCustomerRequestList.groupBy { it.accountOwner.id }

        pendingRequestsGroupedByAccountOwner.each { Long accountOwnerId, List<KnownYourCustomerRequest> pendingRequestList ->
            BigDecimal requestBatchTotalValue = pendingRequestList*.fee.sum()
            List<Long> pendingRequestIdList = pendingRequestList.collect { it.id }

            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(accountOwnerId)

                KnownYourCustomerRequestBatch requestBatch = save(customer, yesterday, requestBatchTotalValue)
                Long requestBatchId = requestBatch.id

                Utils.forEachWithFlushSession(pendingRequestIdList, 50, { Long pendingRequestId ->
                    knownYourCustomerRequestService.setAsProcessed(pendingRequestId, KnownYourCustomerRequestBatch.load(requestBatchId))
                })

                setAsProcessedIfPossible(requestBatch)
            }, [logErrorMessage: "KnownYourCustomerBatchService.create - Erro ao criar lote de cobrança de taxa de criação de subconta do cliente [{$accountOwnerId}]" ])
        }
    }

    private KnownYourCustomerRequestBatch save(Customer customer, Date referenceDate, BigDecimal value) {
        Map search = [:]
        search.customer = customer
        search.referenceDate = referenceDate
        search.status = KnownYourCustomerRequestBatchStatus.PENDING

        KnownYourCustomerRequestBatch pendingRequestBatch = KnownYourCustomerRequestBatch.query(search).get()
        if (!pendingRequestBatch) {
            pendingRequestBatch = new KnownYourCustomerRequestBatch()
        }

        pendingRequestBatch.referenceDate = referenceDate
        pendingRequestBatch.customer = customer
        pendingRequestBatch.value += value
        pendingRequestBatch.save(flush: true, failOnError: true)

        return pendingRequestBatch
    }

    private void setAsProcessedIfPossible(KnownYourCustomerRequestBatch requestBatch) {
        Boolean hasPendingKnownYourCustomerRequest = KnownYourCustomerRequest.query([exists: true,
                                                                                     accountOwner: requestBatch.customer,
                                                                                     status: KnownYourCustomerRequestStatus.PENDING,
                                                                                     "dateCreated[le]": requestBatch.referenceDate]).get().asBoolean()
        if (hasPendingKnownYourCustomerRequest) return

        requestBatch.status = KnownYourCustomerRequestBatchStatus.PROCESSED
        requestBatch.save(failOnError: true)

        chargedFeeService.saveKnownYourCustomerRequestBatchFee(requestBatch.customer, requestBatch)
    }
}

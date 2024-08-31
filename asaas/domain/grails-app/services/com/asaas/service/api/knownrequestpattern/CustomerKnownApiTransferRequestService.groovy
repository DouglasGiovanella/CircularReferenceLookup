package com.asaas.service.api.knownrequestpattern

import com.asaas.api.knownrequestpattern.CustomerKnownApiRequestPatternType
import com.asaas.api.knownrequestpattern.adapter.CustomerKnownApiRequestPatternAdapter
import com.asaas.domain.api.knownrequestpattern.CustomerKnownApiRequestPattern
import com.asaas.domain.api.knownrequestpattern.CustomerKnownApiTransferRequest
import com.asaas.domain.transfer.Transfer
import com.asaas.log.AsaasLogger
import com.asaas.service.api.ApiBaseService
import com.asaas.transfer.TransferStatus
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerKnownApiTransferRequestService extends ApiBaseService {

    def customerKnownApiRequestPatternService

    public void save(Transfer transfer, CustomerKnownApiRequestPatternAdapter patternAdapter) {
        try {
            CustomerKnownApiRequestPattern customerKnownApiRequestPattern = customerKnownApiRequestPatternService.saveIfNecessary(transfer.customer, patternAdapter, CustomerKnownApiRequestPatternType.TRANSFER)

            final Integer daysToApproval = 7

            Date scheduleDate = transfer.getEstimatedCreditDate()
            if (!scheduleDate) scheduleDate = new Date()

            CustomerKnownApiTransferRequest customerKnownApiTransferRequest = new CustomerKnownApiTransferRequest()
            customerKnownApiTransferRequest.transfer = transfer
            customerKnownApiTransferRequest.requestPattern = customerKnownApiRequestPattern
            customerKnownApiTransferRequest.scheduleDate = CustomDateUtils.sumDays(scheduleDate, daysToApproval)
            customerKnownApiTransferRequest.save(failOnError: true)
        } catch (Exception exception) {
            AsaasLogger.error("CustomerKnownApiTransferRequestService.save >>> Falha ao salvar CustomerKnownApiTransferRequest. transferId: ${transfer.id} | pattern: ${patternAdapter.current}", exception)
        }
    }

    public Boolean processAwaitingApproval() {
        Map query = [
            columnList: ["id", "requestPattern.id"],
            "scheduleDate[le]": new Date().clearTime(),
            requestPatternTrusted: false,
            validated: false,
            transferStatus: TransferStatus.DONE,
            disableSort: true
        ]

        final Integer maxItems = 300
        final Integer flushEvery = 50

        List<Map> knownApiTransferRequestList = CustomerKnownApiTransferRequest.query(query).list(max: maxItems)
        if (!knownApiTransferRequestList) return false

        List<Long> knownApiTransferIdList = knownApiTransferRequestList.unique({ it."requestPattern.id" }).collect { it.id }
        Utils.forEachWithFlushSession(knownApiTransferIdList, flushEvery, { Long apiTransferRequestId ->
            approve(CustomerKnownApiTransferRequest.get(apiTransferRequestId))
        })

        return true
    }

    public void approveIfPossible(Long transferId) {
        CustomerKnownApiTransferRequest customerKnownApiTransferRequest = CustomerKnownApiTransferRequest.query([transferId: transferId]).get()
        if (!customerKnownApiTransferRequest) return

        approve(customerKnownApiTransferRequest)
    }

    public void approve(CustomerKnownApiTransferRequest customerKnownApiTransferRequest) {
        customerKnownApiRequestPatternService.approve(customerKnownApiTransferRequest.requestPattern)

        customerKnownApiTransferRequest.validated = true
        customerKnownApiTransferRequest.save(failOnError: true)
    }
}

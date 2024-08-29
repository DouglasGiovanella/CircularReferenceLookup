package com.asaas.service.pix.polling

import com.asaas.domain.pix.pixtransactionrequest.PixTransactionRequest
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.transaction.credit.CreditAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PixTransactionPollingService {

    def pixTransactionPollingManagerService
    def pixTransactionRequestService

    public void reprocessPolling(Date startPollingDate, Date endPollingDate) {
        if (CustomDateUtils.calculateDifferenceInDays(startPollingDate, endPollingDate) > 1) throw new BusinessException("Data pra reprocessamento é inválida.")

        while (startPollingDate < endPollingDate) {
            Map pollingParams = [:]
            pollingParams.reprocessingPolling = true
            pollingParams.initialDate = startPollingDate
            pollingParams.endDate = CustomDateUtils.sumMinutes(startPollingDate, 30)
            pollingParams.pageSize = 1000

            Utils.withNewTransactionAndRollbackOnError({
                polling(pollingParams)
            }, [logErrorMessage: "PixTransactionPollingService.reprocessPolling() -> Falha ao reprocessar o polling. [params: ${pollingParams}]"])

            startPollingDate = pollingParams.endDate
        }
    }

    public void polling(Map params) {
        List<CreditAdapter> creditInfoList = pixTransactionPollingManagerService.polling(params)

        for (CreditAdapter creditInfo : creditInfoList) {
            Boolean alreadyProcessed = PixTransactionRequest.query([exists: true, pixTransactionExternalIdentifier: creditInfo.externalIdentifier]).get()
            if (alreadyProcessed) {
                continue
            }

            if (params.reprocessingPolling) {
                if (params.containsKey("logOnly") && params.logOnly) {
                    AsaasLogger.info("PixTransactionPollingService.polling() -> Pix recebido (apenas log) ${creditInfo.externalIdentifier} [payment: ${creditInfo.payment.id}, reprocessingPolling: ${params.reprocessingPolling}, initialDate: ${params.initialDate}, endDate: ${params.endDate}]")
                    continue
                }
                AsaasLogger.info("PixTransactionPollingService.polling() -> Pix recebido ${creditInfo.externalIdentifier} [reprocessingPolling: ${params.reprocessingPolling}, initialDate: ${params.initialDate}, endDate: ${params.endDate}]")
            }
            pixTransactionRequestService.save(creditInfo)
        }
    }

}

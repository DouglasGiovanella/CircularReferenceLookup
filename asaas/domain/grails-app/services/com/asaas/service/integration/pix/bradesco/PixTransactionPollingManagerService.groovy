package com.asaas.service.integration.pix.bradesco

import com.asaas.domain.pix.transactionpolling.PixTransactionPollingInfo
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.bradesco.BradescoPixManager
import com.asaas.integration.pix.dto.bradesco.polling.BradescoPixTransactionPollingRequestDTO
import com.asaas.integration.pix.dto.bradesco.polling.BradescoPixTransactionPollingResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.transaction.credit.CreditAdapter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class PixTransactionPollingManagerService {

    public List<CreditAdapter> polling(Map params) {
        final Integer initialPage = 0

        BradescoPixTransactionPollingResponseDTO responseDTO = list(params, initialPage)
        if (!responseDTO?.pix) return null

        if (!params.reprocessingPolling) {
            List<Date> transactionInclusionDateList = responseDTO.getTransactionDateList()
            setLatestInitialDateFilter(transactionInclusionDateList.sort().last())
        }

        return responseDTO.pix.collect { new CreditAdapter(it) }
    }

    private BradescoPixTransactionPollingResponseDTO list(Map params, Integer page) {
        if (!params.initialDate) params.initialDate = getLatestInitialDateFilter()
        if (!params.endDate) params.endDate = new Date()

        BradescoPixManager pixManager = new BradescoPixManager()
        pixManager.get("/v1/spi/pix?${new BradescoPixTransactionPollingRequestDTO(params, page).toParamsUrl()}", null)

        if (pixManager.isSuccessful()) {
            BradescoPixTransactionPollingResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((pixManager.responseBody as JSON).toString(), BradescoPixTransactionPollingResponseDTO)

            if (responseDTO.parametros?.paginacao?.hasMore()) {
                Integer nextPage = responseDTO.parametros.paginacao.getNextPage()
                BradescoPixTransactionPollingResponseDTO responseNewPageDTO = list(params, nextPage)
                if (responseNewPageDTO.pix) {
                    responseDTO.pix.addAll(responseNewPageDTO.pix)
                } else {
                    AsaasLogger.warn("PixTransactionPollingManagerService.executePolling() -> Nova página [${nextPage}] veio sem transações Pix")
                }
            }
            return responseDTO
        }

        AsaasLogger.error("PixTransactionPollingManagerService.executePolling() -> Erro ao consultar lista de transações recebidas. [status: ${pixManager.statusCode}, page: ${page} ,error: ${pixManager.responseBody}]")
        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }

    private Date getLatestInitialDateFilter() {
        PixTransactionPollingInfo pollingInfo = PixTransactionPollingInfo.getInstance()
        if (!pollingInfo) {
            pollingInfo = new PixTransactionPollingInfo()
            pollingInfo.lastInitialDateFilter = CustomDateUtils.getFirstDayOfMonth(new Date())
            pollingInfo.save(flush: true, failOnErrro: true)
        }
        return pollingInfo.lastInitialDateFilter
    }

    private void setLatestInitialDateFilter(Date lastInitialDateFilter) {
        PixTransactionPollingInfo pollingInfo = PixTransactionPollingInfo.getInstance()

        if (!lastInitialDateFilter) {
            AsaasLogger.warn("PixTransactionPollingManagerService.setLatestInitialDateFilter() -> Tentativa de atualização do Polling com data inválida. [lastInitialDateFilter: ${lastInitialDateFilter}]")
            return
        }

        if (lastInitialDateFilter < pollingInfo.lastInitialDateFilter) {
            AsaasLogger.warn("PixTransactionPollingManagerService.setLatestInitialDateFilter() -> Tentativa de atualização do Polling com data menor do que a data atual. [lastInitialDateFilter (nova): ${lastInitialDateFilter}, lastInitialDateFilter (atual): ${pollingInfo.lastInitialDateFilter}]")
            return
        }

        pollingInfo.lastInitialDateFilter = lastInitialDateFilter
        pollingInfo.save(flush: true, failOnErrro: true)
    }

}

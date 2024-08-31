package com.asaas.service.integration.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.base.BaseResponseDTO
import com.asaas.integration.pix.dto.infraction.cancel.HermesCancelInfractionRequestDTO
import com.asaas.integration.pix.dto.infraction.get.HermesGetInfractionRequestDTO
import com.asaas.integration.pix.dto.infraction.get.HermesGetInfractionResponseDTO
import com.asaas.integration.pix.dto.infraction.list.ListInfractionRequestDTO
import com.asaas.integration.pix.dto.infraction.list.ListInfractionResponseDTO
import com.asaas.integration.pix.dto.infraction.save.HermesSaveInfractionRequestDTO
import com.asaas.integration.pix.dto.infraction.save.HermesSaveInfractionResponseDTO
import com.asaas.integration.pix.dto.infraction.saveanalysis.HermesSaveExternalInfractionAnalysisRequestDTO
import com.asaas.integration.pix.dto.infraction.validate.ValidateInfractionDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixInfractionCancellationRequester
import com.asaas.pix.PixInfractionOpeningRequester
import com.asaas.pix.adapter.infraction.ExternalInfractionAdapter
import com.asaas.pix.adapter.infraction.InfractionAdapter
import com.asaas.pix.adapter.infraction.SaveExternalInfractionAnalysisAdapter
import com.asaas.pix.adapter.infraction.SaveInfractionAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class PixInfractionManagerService {

    public Map save(SaveInfractionAdapter saveInfractionAdapter) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/infractions/save", new HermesSaveInfractionRequestDTO(saveInfractionAdapter).properties, null)

        HermesSaveInfractionResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesSaveInfractionResponseDTO)

        if (hermesManager.isSuccessful()) return [success: true, daysLimitToAnalyzeInfraction: responseDto.daysLimitToAnalyzeInfraction]

        return [success: false, error: responseDto.errorMessage]
    }

    public void cancel(Long id, PixInfractionCancellationRequester cancellationRequester, Long customerId) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/infractions/${id}/cancel", new HermesCancelInfractionRequestDTO(cancellationRequester, customerId).properties, null)

        if (hermesManager.isSuccessful()) return

        if (hermesManager.isClientError()) {
            BaseResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), BaseResponseDTO)
            throw new BusinessException(responseDto.errorMessage)
        }

        AsaasLogger.error("PixInfractionManagerService.cancel >> Erro ao cancelar infração de uma transação Pix [Infraction.id: ${id}, Customer.id: ${customerId}, status: ${hermesManager.statusCode}, error: ${hermesManager.responseBody}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public Map validate(PixTransaction pixTransaction, PixInfractionOpeningRequester openingRequester) {
        if (AsaasEnvironment.isDevelopment()) return [success: false, error: "Não é possível solicitar infração em desenvolvimento."]

        HermesManager hermesManager = new HermesManager()
        hermesManager.timeout = 5000
        hermesManager.logged = false
        hermesManager.post("/infractions/validate", new ValidateInfractionDTO(pixTransaction, openingRequester).properties, null)

        if (hermesManager.isSuccessful()) return [success: true]

        if (hermesManager.isClientError()) {
            BaseResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), BaseResponseDTO)
            return [success: false, error: responseDto.errorMessage]
        }

        AsaasLogger.error("PixInfractionManagerService.validate >> Erro ao validar infração de uma transação Pix [PixTransaction.id: ${pixTransaction.id}, openingRequester: ${openingRequester}, status: ${hermesManager.statusCode}, error: ${hermesManager.responseBody}]")
        return [success: false, error: Utils.getMessageProperty("unknow.error")]
    }

    public InfractionAdapter get(Long id, Long customerId) {
        return executeGet(new HermesGetInfractionRequestDTO(id, customerId))
    }

    public InfractionAdapter getByPixTransaction(PixTransaction pixTransaction, Long customerId) {
        return executeGet(new HermesGetInfractionRequestDTO(pixTransaction, customerId))
    }

    public Map list(Map filters, Integer limit, Integer offset) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/infractions/list", new ListInfractionRequestDTO(filters, limit, offset).properties, null)

        if (hermesManager.isSuccessful()) {
            ListInfractionResponseDTO infractionListDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), ListInfractionResponseDTO)

            List<InfractionAdapter> infractionList = []
            if (infractionListDto.data) infractionList = infractionListDto.data.collect { infraction -> new InfractionAdapter(infraction) }

            return [infractionList: infractionList, totalCount: infractionListDto.totalCount]
        }

        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public Map getExternal(Long id) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/externalInfractions/${id}/get", null)

        if (hermesManager.isSuccessful()) {
            HermesGetInfractionResponseDTO infractionDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetInfractionResponseDTO)
            return [success: true, infraction: new ExternalInfractionAdapter(infractionDto)]
        }

        BaseResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), BaseResponseDTO)
        return [success: false, error: responseDto.errorMessage]
    }

    public Map listExternal(Map filters, Integer limit, Integer offset, Boolean isPagedList) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/externalInfractions/list", new ListInfractionRequestDTO(filters, limit, offset).properties, null)

        if (hermesManager.isSuccessful()) {
            ListInfractionResponseDTO infractionListDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), ListInfractionResponseDTO)

            List<ExternalInfractionAdapter> infractionList = []
            if (infractionListDto.data) infractionList = infractionListDto.data.collect { infraction -> new ExternalInfractionAdapter(infraction) }

            if (!isPagedList && infractionListDto.hasMore) {
                Map externalInfractionList = listExternal(filters, limit, offset + limit, isPagedList)
                infractionList.addAll(externalInfractionList.infractionList)
            }

            return [infractionList: infractionList, totalCount: infractionListDto.totalCount]
        }

        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public List<ExternalInfractionAdapter> listAllExternal(Map filters) {
        final Integer defaultLimit = 100
        final Integer defaultOffset = 0

        Map externalInfractionList = listExternal(filters, defaultLimit, defaultOffset, false)
        return externalInfractionList.infractionList
    }

    public void saveAnalysis(SaveExternalInfractionAnalysisAdapter analysisAdapter) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/externalInfractions/${analysisAdapter.id}/saveAnalysis", new HermesSaveExternalInfractionAnalysisRequestDTO(analysisAdapter).properties, null)
        if (hermesManager.isSuccessful()) return

        throw new BusinessException(hermesManager.getErrorMessage())
    }

    private InfractionAdapter executeGet(HermesGetInfractionRequestDTO requestDto) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.timeout = 5000
        hermesManager.logged = false
        hermesManager.get("/infractions/get", requestDto.toMap())

        if (hermesManager.isSuccessful()) {
            HermesGetInfractionResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetInfractionResponseDTO)
            return new InfractionAdapter(responseDto)
        }

        if (hermesManager.isNotFound()) return null

        if (hermesManager.isClientError()) {
            BaseResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), BaseResponseDTO)
            throw new BusinessException(responseDto.errorMessage)
        }

        AsaasLogger.error("PixInfractionManagerService.executeGet >> Erro ao buscar infração Pix [params: ${requestDto.toMap()}, status: ${hermesManager.statusCode}, error: ${hermesManager.responseBody}]")
        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }
}

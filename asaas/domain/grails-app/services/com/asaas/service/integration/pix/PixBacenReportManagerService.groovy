package com.asaas.service.integration.pix

import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.bacenreport.BacenReportInfoResponseDTO
import com.asaas.integration.pix.dto.bacenreport.get.GetPixBacenReportXmlResponseDTO
import com.asaas.integration.pix.dto.bacenreport.save.HermesSavePrecautionaryBlockInfoRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class PixBacenReportManagerService {

    public void create() {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/bacenReport/create", [:])

        if (hermesManager.isSuccessful()) return

        AsaasLogger.error("PixBacenReportManagerService.create() -> O seguinte erro foi retornado ao criar o BacenReport manualmente no Hermes: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public String getBacenReportXml(String id) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/bacenReport/send/${id}", [:])

        if (hermesManager.isSuccessful()) {
            GetPixBacenReportXmlResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), GetPixBacenReportXmlResponseDTO)
            return responseDTO.bacenReportXml
        }

        AsaasLogger.error("PixBacenReportManagerService.send() -> O seguinte erro foi retornado ao enviar o BacenReport manualmente: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public void saveFeeAmountsInfo(Map amountInfo) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/bacenReport/saveFeeAmountsInfo", new BacenReportInfoResponseDTO(amountInfo).properties, null)

        if (hermesManager.isSuccessful()) return

        AsaasLogger.error("PixBacenReportManagerService.saveFeeAmountsInfo() -> O seguinte erro foi retornado ao salvar as taxas do relatório para o Hermes: ${hermesManager.getErrorMessage()}")
    }

    public void savePrecautionaryBlockInfo(Map precautionaryBlockInfo) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/bacenReport/savePrecautionaryBlockInfo", new HermesSavePrecautionaryBlockInfoRequestDTO(precautionaryBlockInfo).properties, null)

        if (hermesManager.isSuccessful()) return

        AsaasLogger.error("PixBacenReportManagerService.savePrecautionaryBlockInfo >> O seguinte erro foi retornado ao enviar as informações sobre bloqueio cautelar para o Hermes: ${hermesManager.getErrorMessage()}")
    }
}

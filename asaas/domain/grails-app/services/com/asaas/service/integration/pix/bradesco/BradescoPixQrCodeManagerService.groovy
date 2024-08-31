package com.asaas.service.integration.pix.bradesco

import com.asaas.domain.pix.PixAsaasQrCode
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.bradesco.BradescoPixManager
import com.asaas.integration.pix.dto.bradesco.qrcode.BradescoPixDeleteQrCodeRequestDTO
import com.asaas.integration.pix.dto.bradesco.qrcode.BradescoPixSaveQrCodeRequestDTO
import com.asaas.integration.pix.dto.bradesco.qrcode.BradescoPixSaveQrCodeResponseDTO
import com.asaas.integration.pix.dto.bradesco.qrcode.BradescoPixUpdateQrCodeRequestDTO
import com.asaas.integration.pix.dto.bradesco.qrcode.BradescoPixUpdateQrCodeResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BradescoPixQrCodeManagerService {

    public String createDynamic(PixAsaasQrCode pixAsaasQrCode) {
        if (!AsaasEnvironment.isProduction()) {
            return new MockJsonUtils("pix/BradescoPixQrCodeManagerService/createDynamic.json").buildMock(BradescoPixSaveQrCodeResponseDTO).location
        }

        BradescoPixManager pixManager = new BradescoPixManager()
        pixManager.put("/v1/spi/cob/${pixAsaasQrCode.conciliationIdentifier}", new BradescoPixSaveQrCodeRequestDTO(pixAsaasQrCode).properties)

        if (pixManager.isSuccessful()) {
            BradescoPixSaveQrCodeResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((pixManager.responseBody as JSON).toString(), BradescoPixSaveQrCodeResponseDTO)
            return responseDto.location
        }

        AsaasLogger.error("${this.getClass().getSimpleName()}.createDynamic() -> Erro ao criar QR Code dinâmico [qrCode.id: ${pixAsaasQrCode.id}, error: ${pixManager.responseBody}, status: ${pixManager.statusCode}]")
        throw new BusinessException(Utils.getMessageProperty("unknow.error"))
    }

    public Map deleteDynamic(PixAsaasQrCode pixAsaasQrCode) {
        if (AsaasEnvironment.isDevelopment()) return new MockJsonUtils("pix/BradescoPixQrCodeManagerService/deleteDynamic.json").buildMock(BradescoPixUpdateQrCodeResponseDTO).toMap()

        BradescoPixManager bradescoPixManager = new BradescoPixManager()
        bradescoPixManager.patch("/v1/spi/cob/${pixAsaasQrCode.conciliationIdentifier}", new BradescoPixDeleteQrCodeRequestDTO().properties)

        if (bradescoPixManager.isSuccessful()) {
            BradescoPixUpdateQrCodeResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((bradescoPixManager.responseBody as JSON).toString(), BradescoPixUpdateQrCodeResponseDTO)
            return responseDto.toMap()
        }

        if (bradescoPixManager.isTimeout()) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.deleteDynamic() -> Timeout ao remover QR Code dinâmico [qrCode.id: ${pixAsaasQrCode.id}, error: ${bradescoPixManager.responseBody}, status: ${bradescoPixManager.statusCode}]")
            return [withoutExternalResponse: true]
        }

        if (bradescoPixManager.isClientError()) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.deleteDynamic() -> Erro ao remover QR Code dinâmico [qrCode.id: ${pixAsaasQrCode.id}, error: ${bradescoPixManager.responseBody}, status: ${bradescoPixManager.statusCode}]")
            return [error: true]
        }

        AsaasLogger.error("${this.getClass().getSimpleName()}.deleteDynamic() -> Erro ao remover QR Code dinâmico [qrCode.id: ${pixAsaasQrCode.id}, error: ${bradescoPixManager.responseBody}, status: ${bradescoPixManager.statusCode}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public Map updateDynamic(PixAsaasQrCode pixAsaasQrCode) {
        if (AsaasEnvironment.isDevelopment()) return new MockJsonUtils("pix/BradescoPixQrCodeManagerService/updateDynamic.json").buildMock(BradescoPixUpdateQrCodeResponseDTO).toMap()

        BradescoPixManager bradescoPixManager = new BradescoPixManager()
        bradescoPixManager.patch("/v1/spi/cob/${pixAsaasQrCode.conciliationIdentifier}", new BradescoPixUpdateQrCodeRequestDTO(pixAsaasQrCode).properties)

        if (bradescoPixManager.isSuccessful()) {
            BradescoPixUpdateQrCodeResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((bradescoPixManager.responseBody as JSON).toString(), BradescoPixUpdateQrCodeResponseDTO)
            return responseDto.toMap()
        }

        if (bradescoPixManager.isTimeout()) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.updateDynamic() -> Timeout ao atualizar QR Code dinâmico [qrCode.id: ${pixAsaasQrCode.id}, error: ${bradescoPixManager.responseBody}, status: ${bradescoPixManager.statusCode}]")
            return [withoutExternalResponse: true]
        }

        if (bradescoPixManager.isClientError()) {
            AsaasLogger.error("${this.getClass().getSimpleName()}.updateDynamic() -> Erro ao atualizar QR Code dinâmico [qrCode.id: ${pixAsaasQrCode.id}, error: ${bradescoPixManager.responseBody}, status: ${bradescoPixManager.statusCode}]")
            return [error: true]
        }

        AsaasLogger.error("${this.getClass().getSimpleName()}.updateDynamic() -> Erro ao atualizar QR Code dinâmico [qrCode.id: ${pixAsaasQrCode.id}, error: ${bradescoPixManager.responseBody}, status: ${bradescoPixManager.statusCode}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }
}

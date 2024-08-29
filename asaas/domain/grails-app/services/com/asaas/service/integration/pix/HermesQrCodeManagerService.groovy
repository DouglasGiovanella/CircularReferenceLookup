package com.asaas.service.integration.pix

import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.payment.PixPaymentInfo
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.qrcode.decode.HermesDecodeQrCodeRequestDTO
import com.asaas.integration.pix.dto.qrcode.decode.HermesDecodeQrCodeResponseDTO
import com.asaas.integration.pix.dto.qrcode.delete.HermesDeleteDynamicQrCodeRequestDTO
import com.asaas.integration.pix.dto.qrcode.get.HermesGetImmediateQrCodeResponseDTO
import com.asaas.integration.pix.dto.qrcode.jws.HermesGetDynamicQrCodeJwsRequestDTO
import com.asaas.integration.pix.dto.qrcode.jws.HermesGetQrCodeJwsResponseDTO
import com.asaas.integration.pix.dto.qrcode.list.HermesListImmediateQrCodeRequestDTO
import com.asaas.integration.pix.dto.qrcode.list.HermesListImmediateQrCodeResponseDTO
import com.asaas.integration.pix.dto.qrcode.list.HermesListQrCodeTransactionRequestDTO
import com.asaas.integration.pix.dto.qrcode.list.HermesListQrCodeTransactionResponseDTO
import com.asaas.integration.pix.dto.qrcode.restore.HermesRestoreDynamicQrCodeRequestDTO
import com.asaas.integration.pix.dto.qrcode.save.HermesSaveDynamicQrCodeRequestDTO
import com.asaas.integration.pix.dto.qrcode.save.HermesSaveImmediateQrCodeRequestDTO
import com.asaas.integration.pix.dto.qrcode.save.HermesSaveImmediateQrCodeResponseDTO
import com.asaas.integration.pix.dto.qrcode.save.HermesSaveQrCodeResponseDTO
import com.asaas.integration.pix.dto.qrcode.save.HermesSaveStaticQrCodeRequestDTO
import com.asaas.integration.pix.dto.qrcode.update.HermesUpdateImmediateQrCodeRequestDTO
import com.asaas.integration.pix.dto.qrcode.update.HermesUpdateImmediateQrCodeResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.pix.adapter.qrcode.PixImmediateQrCodeListAdapter
import com.asaas.pix.adapter.qrcode.PixQrCodeTransactionListAdapter
import com.asaas.pix.adapter.qrcode.QrCodeAdapter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import com.asaas.utils.Utils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class HermesQrCodeManagerService {

    public Map createStaticQrCode(Long customerId, Map pixQrCodeParams) {
        if (AsaasEnvironment.isDevelopment()) return new MockJsonUtils("pix/HermesQrCodeManagerService/createStaticQrCode.json").buildMock(HermesSaveQrCodeResponseDTO).toMap()

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.useCustomEncoders = true
        hermesManager.post("/accounts/${customerId}/qrcodes/static/save", new HermesSaveStaticQrCodeRequestDTO(customerId, pixQrCodeParams).properties, null)
        if (hermesManager.isSuccessful()) {
            HermesSaveQrCodeResponseDTO hermesSaveQrCodeResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesSaveQrCodeResponseDTO)
            return  hermesSaveQrCodeResponseDTO.toMap()
        }

        if (hermesManager.isBadRequest()) throw new BusinessException(hermesManager.getErrorMessage())

        AsaasLogger.error("HermesQrCodeManagerService.createStaticQrCode() -> Erro ao salvar QR Code [error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${customerId}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public void deleteStatic(Long customerId, String conciliationIdentifier) {
        if (AsaasEnvironment.isDevelopment()) return

        HermesManager hermesManager = new HermesManager()
        hermesManager.timeout = 5000
        hermesManager.logged = false
        hermesManager.useCustomEncoders = true
        hermesManager.delete("/accounts/${customerId}/qrcodes/static/${conciliationIdentifier}", null, null)

        if (hermesManager.isSuccessful()) return
        if (hermesManager.isNotFound()) throw new ResourceNotFoundException(hermesManager.getErrorMessage())
        if (hermesManager.isBadRequest()) throw new BusinessException(hermesManager.getErrorMessage())

        AsaasLogger.error("HermesQrCodeManagerService.deleteStatic >> Erro ao deletar QR Code [error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customerId: ${customerId}, conciliationIdentifier: ${conciliationIdentifier}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public Map createDynamicQrCode(Payment payment) {
        if (AsaasEnvironment.isDevelopment()) return new MockJsonUtils("pix/HermesQrCodeManagerService/createDynamicQrCode.json").buildMock(HermesSaveQrCodeResponseDTO).toMap()

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.useCustomEncoders = true
        hermesManager.post("/accounts/${payment.provider.id}/qrcodes/dynamic/save", new HermesSaveDynamicQrCodeRequestDTO(payment).properties, null)
        if (hermesManager.isSuccessful()) {
            HermesSaveQrCodeResponseDTO hermesSaveQrCodeResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesSaveQrCodeResponseDTO)
            return hermesSaveQrCodeResponseDTO.toMap()
        }

        if (hermesManager.isBadRequest()) throw new BusinessException(hermesManager.getErrorMessage())

        AsaasLogger.error("HermesQrCodeManagerService.createDynamicQrCode() -> Erro ao salvar QR Code [payment.id: ${payment.id}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${payment.provider.id}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public Map updateDynamicQrCodeIfNecessary(Payment payment) {
        if (AsaasEnvironment.isDevelopment()) return [success: true]

        HermesManager hermesManager = new HermesManager()
        hermesManager.timeout = 5000
        hermesManager.logged = false
        hermesManager.useCustomEncoders = true
        hermesManager.put("/qrcodes/${PixPaymentInfo.retrieveQrCodePublicId(payment)}", new HermesSaveDynamicQrCodeRequestDTO(payment).properties)

        if (hermesManager.isSuccessful()) return [success: true]

        if (hermesManager.isNotFound()) return [success: true]

        if (hermesManager.isTimeout()) {
            AsaasLogger.warn("HermesQrCodeManagerService.updateDynamicQrCodeIfNecessary() -> Timeout ao tentar atualizar o QRCode Pix do Pagamento [${payment.id}]")
            return [withoutExternalResponse: true]
        }

        AsaasLogger.warn("HermesQrCodeManagerService.updateDynamicQrCodeIfNecessary() -> Erro ao atualizar QR Code [payment.id: ${payment.id}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${payment.provider.id}]")
        return [success: false]
    }

    public Map deleteDynamicQrCodeIfNecessary(Payment payment) {
        if (AsaasEnvironment.isDevelopment()) return [success: true]

        HermesManager hermesManager = new HermesManager()
        hermesManager.timeout = 5000
        hermesManager.logged = false
        hermesManager.delete("/qrcodes/${PixPaymentInfo.retrieveQrCodePublicId(payment)}", new HermesDeleteDynamicQrCodeRequestDTO(payment).properties, null)

        if (hermesManager.isSuccessful()) return [success: true]

        if (hermesManager.isNotFound()) return [success: true]

        if (hermesManager.isTimeout()) {
            AsaasLogger.warn("HermesQrCodeManagerService.deleteDynamicQrCodeIfNecessary() -> Timeout ao tentar deletar o QRCode Pix do Pagamento [${payment.id}]")
            return [withoutExternalResponse: true]
        }

        AsaasLogger.warn("HermesQrCodeManagerService.deleteDynamicQrCodeIfNecessary() -> Erro ao deletar QR Code [payment.id: ${payment.id}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${payment.provider.id}]")
        return [success: false]
    }

    public Map restoreDynamicQrCodeIfNecessary(Payment payment) {
        if (AsaasEnvironment.isDevelopment()) return [success: true]

        HermesManager hermesManager = new HermesManager()
        hermesManager.timeout = 5000
        hermesManager.logged = false
        hermesManager.put("/qrcodes/${PixPaymentInfo.retrieveQrCodePublicId(payment)}/restore", new HermesRestoreDynamicQrCodeRequestDTO(payment).properties)

        if (hermesManager.isSuccessful()) return [success: true]

        if (hermesManager.isNotFound()) return [success: true]

        if (hermesManager.isTimeout()) {
            AsaasLogger.warn("HermesQrCodeManagerService.restoreDynamicQrCodeIfNecessary() -> Timeout ao tentar restaurar o QRCode Pix do Pagamento [${payment.id}]")
            return [withoutExternalResponse: true]
        }

        AsaasLogger.warn("HermesQrCodeManagerService.restoreDynamicQrCodeIfNecessary() -> Erro ao restaurar QR Code [payment.id: ${payment.id}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${payment.provider.id}]")
        return [success: false]
    }

    public Map createImmediateQrCode(Long customerId, Map pixQrCodeParams) {
        if (AsaasEnvironment.isDevelopment()) return new MockJsonUtils("pix/HermesQrCodeManagerService/createImmediateQrCode.json").buildMock(HermesSaveQrCodeResponseDTO).toMap()

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/accounts/${customerId}/qrcodes/immediate/save", new HermesSaveImmediateQrCodeRequestDTO(customerId, pixQrCodeParams).properties, null)

        if (hermesManager.isSuccessful()) {
            HermesSaveImmediateQrCodeResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesSaveImmediateQrCodeResponseDTO)
            return responseDto.toMap()
        }

        if (hermesManager.isClientError()) {
            throw new BusinessException(hermesManager.getErrorMessage())
        }

        AsaasLogger.error("HermesQrCodeManagerService.createImmediateQrCode() -> Erro ao salvar QR Code [error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${customerId}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public Map getImmediate(Long customerId, String conciliationIdentifier) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customerId}/qrcodes/immediate/${conciliationIdentifier}", null)

        if (hermesManager.isSuccessful()) {
            HermesGetImmediateQrCodeResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetImmediateQrCodeResponseDTO)
            return responseDto.toMap()
        }

        if (hermesManager.isNotFound()) return null

        AsaasLogger.error("HermesQrCodeManagerService.getImmediate >> Erro ao buscar QR Code [error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${customerId}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public PixImmediateQrCodeListAdapter listImmediate(Long customerId, Map filters) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customerId}/qrcodes/immediate", new HermesListImmediateQrCodeRequestDTO(filters).properties)

        if (hermesManager.isSuccessful()) {
            HermesListImmediateQrCodeResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesListImmediateQrCodeResponseDTO)
            return new PixImmediateQrCodeListAdapter(responseDto)
        }

        AsaasLogger.error("HermesQrCodeManagerService.listImmediate >> Erro ao listar QR Codes [error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${customerId}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public Map updateImmediate(Long customerId, Map pixQrCodeParams) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.put("/accounts/${customerId}/qrcodes/immediate/${pixQrCodeParams.conciliationIdentifier}", new HermesUpdateImmediateQrCodeRequestDTO(customerId, pixQrCodeParams).properties)

        if (hermesManager.isSuccessful()) {
            HermesUpdateImmediateQrCodeResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesUpdateImmediateQrCodeResponseDTO)
            return responseDto.toMap()
        }

        if (hermesManager.isClientError()) {
            throw new BusinessException(hermesManager.getErrorMessage())
        }

        AsaasLogger.error("HermesQrCodeManagerService.updateImmediate >> Erro ao atualizar QR Code [error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${customerId}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public String getDynamicQrCodeJws(Payment payment) {
        if (AsaasEnvironment.isDevelopment()) return new MockJsonUtils("pix/HermesQrCodeManagerService/getDynamicQrCodeJws.json").buildMock(HermesGetQrCodeJwsResponseDTO).payloadJws

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/accounts/${payment.provider.id}/qrcodes/dynamic/${PixPaymentInfo.retrieveQrCodePublicId(payment)}/jws", new HermesGetDynamicQrCodeJwsRequestDTO(payment).properties, null)

        if (hermesManager.isSuccessful()) {
            HermesGetQrCodeJwsResponseDTO hermesGetQrCodeJwsResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetQrCodeJwsResponseDTO)
            return hermesGetQrCodeJwsResponseDTO.payloadJws
        }

        if (hermesManager.isClientError()) throw new BusinessException(hermesManager.getErrorMessage())

        AsaasLogger.error("HermesQrCodeManagerService.getDynamicQrCodeJws() -> Erro ao requisitar JWS do QR Code dinâmico [payment.id: ${payment.id}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${payment.provider.id}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public String getImmediateQrCodeJws(String publicId) {
        if (AsaasEnvironment.isDevelopment()) return new MockJsonUtils("pix/HermesQrCodeManagerService/getImmediateQrCodeJws.json").buildMock(HermesGetQrCodeJwsResponseDTO).payloadJws

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/accounts/qrcodes/immediate/${publicId}/jws", null, null)

        if (hermesManager.isSuccessful()) {
            HermesGetQrCodeJwsResponseDTO hermesGetQrCodeJwsResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetQrCodeJwsResponseDTO)
            return hermesGetQrCodeJwsResponseDTO.payloadJws
        }

        AsaasLogger.error("HermesQrCodeManagerService.getImmediateQrCodeJws() -> Erro ao requisitar JWS do QR Code imediato [publicId: ${publicId}, error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public QrCodeAdapter decode(String payload, Customer customer) {
        if (AsaasEnvironment.isProduction() && payload.contains("-h.")) {
            AsaasLogger.info("HermesQrCodeManagerService.decode -> Tentativa de pagamento de QR Code de homologação. [customer.id: ${customer.id}]")
            throw new RuntimeException("O Qr Code informado é inválido.")
        }
        if (AsaasEnvironment.isDevelopment()) {
            HermesDecodeQrCodeResponseDTO mockedResponseDto = new MockJsonUtils("pix/HermesQrCodeManagerService/decodeStaticQrCode.json").buildMock(HermesDecodeQrCodeResponseDTO)
            mockedResponseDto.endToEndIdentifier = UUID.randomUUID()

            return new QrCodeAdapter(mockedResponseDto, payload)
        }

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/qrcodes/decode", new HermesDecodeQrCodeRequestDTO(customer, payload).properties, null)
        if (hermesManager.isSuccessful()) {
            return new QrCodeAdapter(GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesDecodeQrCodeResponseDTO) as HermesDecodeQrCodeResponseDTO, payload)
        }

        if (hermesManager.isNotFound()) return null
        if (hermesManager.isBadRequest() || hermesManager.isForbidden()) throw new BusinessException(hermesManager.getErrorMessage())

        if (hermesManager.isTooManyRequests()) throw new BusinessException(Utils.getMessageProperty("pix.hermesConnection.tooManyRequests"))

        AsaasLogger.error("HermesQrCodeManagerService.decode() -> Erro ao decodificar QR Code [error: ${hermesManager.responseBody}, status: ${hermesManager.statusCode}, customer.id: ${customer.id}]")
        throw new RuntimeException(Utils.getMessageProperty("unknow.error"))
    }

    public PixQrCodeTransactionListAdapter listTransactions(Long customerId, String conciliationIdentifier, Integer offset, Integer limit) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/accounts/${customerId}/qrcodes/${conciliationIdentifier}/transactions", new HermesListQrCodeTransactionRequestDTO(limit, offset).properties)

        if (hermesManager.isSuccessful()) {
            HermesListQrCodeTransactionResponseDTO transactionResponseDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesListQrCodeTransactionResponseDTO)
            return new PixQrCodeTransactionListAdapter(transactionResponseDTO)
        }

        AsaasLogger.error("HermesQrCodeManagerService.listTransactions() -> O seguinte erro foi retornado ao buscar as transações do qrcode ${conciliationIdentifier} / ${customerId}: ${hermesManager.getErrorMessage()}")
        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public Map mockDynamicQrCode() {
        MockJsonUtils mockJson = new MockJsonUtils("pix/HermesQrCodeManagerService/createDynamicQrCode.json")
        mockJson.bypassLogWarning = true

        return mockJson.buildMock(HermesSaveQrCodeResponseDTO).toMap()
    }
}

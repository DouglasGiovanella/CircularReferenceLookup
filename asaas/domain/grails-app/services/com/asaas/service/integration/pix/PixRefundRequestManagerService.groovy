package com.asaas.service.integration.pix

import com.asaas.domain.pix.PixTransaction
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.api.HermesManager
import com.asaas.integration.pix.dto.base.BaseResponseDTO
import com.asaas.integration.pix.dto.refundrequest.get.HermesGetExternalRefundRequestResponseDTO
import com.asaas.integration.pix.dto.refundrequest.get.HermesGetRefundRequestResponseDTO
import com.asaas.integration.pix.dto.refundrequest.list.HermesListExternalRefundRequestRequestDTO
import com.asaas.integration.pix.dto.refundrequest.list.HermesListExternalRefundRequestResponseDTO
import com.asaas.integration.pix.dto.refundrequest.list.HermesListRefundRequestRequestDTO
import com.asaas.integration.pix.dto.refundrequest.list.HermesListRefundRequestResponseDTO
import com.asaas.integration.pix.dto.refundrequest.save.HermesSaveRefundRequestRequestDTO
import com.asaas.integration.pix.dto.refundrequest.validate.HermesValidateRefundRequestRequestDTO
import com.asaas.pix.adapter.refundRequest.ExternalRefundRequestAdapter
import com.asaas.pix.adapter.refundRequest.ListExternalRefundRequestAdapter
import com.asaas.pix.adapter.refundRequest.ListRefundRequestAdapter
import com.asaas.pix.adapter.refundRequest.RefundRequestAdapter
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class PixRefundRequestManagerService {

    public Map save(PixTransaction pixTransaction, BigDecimal value, String details) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/refundRequest/save", new HermesSaveRefundRequestRequestDTO(pixTransaction, value, details).properties, null)

        if (hermesManager.isSuccessful()) return [success: true]

        BaseResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), BaseResponseDTO)
        return [success: false, error: responseDto.errorMessage]
    }

    public void cancel(Long id) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/refundRequest/${id}/cancel", null, null)

        if (hermesManager.isSuccessful()) return

        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public Map validate(PixTransaction pixTransaction) {
        if (AsaasEnvironment.isDevelopment()) return [success: false, error: "Não é possível solicitar devolução especial em desenvolvimento."]

        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/refundRequest/validate", new HermesValidateRefundRequestRequestDTO(pixTransaction).properties, null)

        if (hermesManager.isSuccessful()) return [success: true]

        BaseResponseDTO responseDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), BaseResponseDTO)
        return [success: false, error: responseDto.errorMessage]
    }

    public Map list(Map filters, Integer limit, Integer offset) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/refundRequest", new HermesListRefundRequestRequestDTO(filters, limit, offset).properties)

        if (hermesManager.isSuccessful()) {
            HermesListRefundRequestResponseDTO refundRequestListDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesListRefundRequestResponseDTO)
            return new ListRefundRequestAdapter(refundRequestListDto).properties
        }

        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public Map get(Long id) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/refundRequest/${id}", null)

        if (hermesManager.isSuccessful()) {
            HermesGetRefundRequestResponseDTO refundRequestDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetRefundRequestResponseDTO)
            return new RefundRequestAdapter(refundRequestDTO).properties
        }

        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public Map listExternal(Map filters, Integer limit, Integer offset) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/externalRefundRequest", new HermesListExternalRefundRequestRequestDTO(filters, limit, offset).properties)

        if (hermesManager.isSuccessful()) {
            HermesListExternalRefundRequestResponseDTO externalRefundRequestListDto = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesListExternalRefundRequestResponseDTO)
            return new ListExternalRefundRequestAdapter(externalRefundRequestListDto).properties
        }

        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public Map getExternal(Long id) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.get("/externalRefundRequest/${id}", null)

        if (hermesManager.isSuccessful()) {
            HermesGetExternalRefundRequestResponseDTO externalRefundRequestDTO = GsonBuilderUtils.buildClassFromJson((hermesManager.responseBody as JSON).toString(), HermesGetExternalRefundRequestResponseDTO)
            return new ExternalRefundRequestAdapter(externalRefundRequestDTO).properties
        }

        throw new BusinessException(hermesManager.getErrorMessage())
    }

    public void processPixExternalRefundRequest(String id) {
        HermesManager hermesManager = new HermesManager()
        hermesManager.logged = false
        hermesManager.post("/externalRefundRequest/${id}/process", [:], null)

        if (hermesManager.isSuccessful()) return

        throw new BusinessException(hermesManager.getErrorMessage())
    }
}

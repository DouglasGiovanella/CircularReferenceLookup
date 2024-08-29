package com.asaas.service.integration.celcoin

import com.asaas.domain.bill.Bill
import com.asaas.exception.BusinessException
import com.asaas.integration.celcoin.adapter.billpayments.authorize.AuthorizeAdapter
import com.asaas.integration.celcoin.adapter.statusconsult.StatusConsultAdapter
import com.asaas.integration.celcoin.api.CelcoinManager
import com.asaas.integration.celcoin.dto.billpayments.authorize.AuthorizeDTO
import com.asaas.integration.celcoin.dto.billpayments.authorize.AuthorizeResponseDTO
import com.asaas.integration.celcoin.dto.billpayments.capture.CaptureDTO
import com.asaas.integration.celcoin.dto.billpayments.capture.CaptureResponseDTO
import com.asaas.integration.celcoin.dto.billpayments.payment.BillPaymentDTO
import com.asaas.integration.celcoin.dto.billpayments.payment.BillPaymentResponseDTO
import com.asaas.integration.celcoin.dto.statusconsult.StatusConsultDTO
import com.asaas.integration.celcoin.dto.statusconsult.StatusConsultResponseDTO
import com.asaas.log.AsaasLogger
import com.asaas.service.integration.celcoin.converter.CelcoinErrorToAsaasErrorConverter
import com.asaas.utils.GsonBuilderUtils
import com.asaas.validation.AsaasError
import grails.converters.JSON
import grails.transaction.Transactional
import com.asaas.environment.AsaasEnvironment

@Transactional
class CelcoinManagerService {

    public Map register(Bill bill) {
        AuthorizeAdapter authorizeAdapter = authorize(bill.id, bill.linhaDigitavel, true)

        if (authorizeAdapter.error) return [ success: false, asaasError: authorizeAdapter.asaasError ]

        BillPaymentDTO billPaymentDTO = new BillPaymentDTO(bill, authorizeAdapter.transactionId)

        CelcoinManager celcoinManager = new CelcoinManager()
        celcoinManager.post("/v5/transactions/billpayments", billPaymentDTO.properties)

        BillPaymentResponseDTO billPaymentResponseDTO = GsonBuilderUtils.buildClassFromJson((celcoinManager.responseBody as JSON).toString(), BillPaymentResponseDTO)

        if (!celcoinManager.isSuccessful()) {
            if (!noNeedToLogErrorCodeOnRegister(billPaymentResponseDTO?.errorCode)) AsaasLogger.error("CelcoinManagerService.register >>> Falha ao registrar a conta para pagamento [id: ${bill.id}] | Erro: ${billPaymentResponseDTO?.errorCode} - ${billPaymentResponseDTO?.message}")
            AsaasError asaasError = CelcoinErrorToAsaasErrorConverter.convertToAsaasErrorOnRegister(billPaymentResponseDTO?.errorCode)
            return [ success: false, asaasError: asaasError ]
        }

        return [ success: true, transactionId: billPaymentResponseDTO?.transactionId ]
    }

    public Map confirm(Long billId, String transactionId) {
        CaptureDTO captureDTO = new CaptureDTO(billId)

        CelcoinManager celcoinManager = new CelcoinManager()
        celcoinManager.put("/v5/transactions/billpayments/${transactionId}/capture", captureDTO.properties)

        CaptureResponseDTO captureResponseDTO = GsonBuilderUtils.buildClassFromJson((celcoinManager.responseBody as JSON).toString(), CaptureResponseDTO)

        if (!celcoinManager.isSuccessful()) {
            AsaasLogger.error("CelcoinManagerService.confirm >>> Falha ao confirmar o pagamento da conta [id: ${billId}] | Erro: ${captureResponseDTO?.errorCode} - ${captureResponseDTO?.message}")
            AsaasError asaasError = CelcoinErrorToAsaasErrorConverter.convertToAsaasErrorOnConfirm(captureResponseDTO?.errorCode)
            return [success: false, asaasError: asaasError]
        }

        return [success: true, transactionId: transactionId ]
    }

    public AuthorizeAdapter authorize(Long billId, String digitableLine, Boolean isPaymentProcess) {
        if (!digitableLine) throw new BusinessException("Informe a linha digitável.")

        AuthorizeDTO authorizeDTO = new AuthorizeDTO(billId, digitableLine)

        CelcoinManager celcoinManager = new CelcoinManager()
        celcoinManager.post("/v5/transactions/billpayments/authorize", authorizeDTO.properties)

        AuthorizeResponseDTO authorizeResponseDTO = GsonBuilderUtils.buildClassFromJson((celcoinManager.responseBody as JSON).toString(), AuthorizeResponseDTO)

        AuthorizeAdapter authorizeAdapter = new AuthorizeAdapter(authorizeResponseDTO)

        if (authorizeAdapter.error) {
            if (AsaasEnvironment.isSandbox()) return authorizeAdapter

            AsaasLogger.warn("CelcoinManagerService.authorize >>> Falha ao buscar os dados de registro [${billId} - ${digitableLine}] | Erro: ${authorizeAdapter.errorCode} - ${authorizeAdapter.errorMessage}")

            if (isPaymentProcess) {
                authorizeAdapter.asaasError = CelcoinErrorToAsaasErrorConverter.convertToAsaasErrorOnAuthorize(authorizeAdapter.errorCode)
            } else {
                final List<String> dontBlockProcessWhenExistsInErrorCodeList = ["480", "481", "619", "623"]

                if (!dontBlockProcessWhenExistsInErrorCodeList.contains(authorizeAdapter.errorCode)) {
                    authorizeAdapter.asaasError = CelcoinErrorToAsaasErrorConverter.convertToAsaasErrorOnAuthorize(authorizeAdapter.errorCode)
                }
            }
        }

        return authorizeAdapter
    }

    public StatusConsultAdapter getStatus(String transactionId) {
        StatusConsultDTO statusConsultDTO = new StatusConsultDTO(transactionId)

        CelcoinManager celcoinManager = new CelcoinManager()
        celcoinManager.get("/v5/transactions/status-consult", statusConsultDTO.properties)

        StatusConsultResponseDTO statusConsultResponseDTO = GsonBuilderUtils.buildClassFromJson((celcoinManager.responseBody as JSON).toString(), StatusConsultResponseDTO)

        return new StatusConsultAdapter(statusConsultResponseDTO)
    }

    private Boolean noNeedToLogErrorCodeOnRegister(String errorCode) {
        return ["183"].contains(errorCode)
    }
}

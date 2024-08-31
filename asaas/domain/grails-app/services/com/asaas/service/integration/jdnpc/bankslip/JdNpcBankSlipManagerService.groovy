package com.asaas.service.integration.jdnpc.bankslip

import com.asaas.bankslip.adapter.BankSlipPayerInfoAdapter
import com.asaas.boleto.BoletoRegistrationStatus
import com.asaas.boleto.asaas.AsaasBankSlipWriteOffType
import com.asaas.boleto.batchfile.BoletoAction
import com.asaas.domain.bankslip.BankSlipOnlineRegistrationResponse
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.integration.jdnpc.api.bankslip.JdNpcBankSlipManager
import com.asaas.boleto.asaas.AsaasBoletoBuilder
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcBankSlipPayerInfoRequestDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcBankSlipPayerInfoResponseDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcExecuteBankSlipWriteOffCancelRequestDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcExecuteBankSlipWriteOffCancelResponseDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcExecuteBankSlipWriteOffRequestDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcExecuteBankSlipWriteOffResponseDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcGetBankSlipStatusRequestDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcGetBankSlipStatusResponseDTO
import com.asaas.integration.jdnpc.dto.bankslip.recipient.JdNpcRecipientBankSlipInfoRequestDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcRegisterBankSlipDiscountRequestDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcRegisterBankSlipRequestDTO
import com.asaas.integration.jdnpc.dto.bankslip.JdNpcRegisterBankSlipResponseDTO
import com.asaas.integration.jdnpc.dto.bankslip.recipient.JdNpcRecipientBankSlipInfoResponseDTO
import com.asaas.integration.jdnpc.enums.JdNpcBankSlipWsOperationType
import com.asaas.integration.jdnpc.parser.JdNpcBankSlipRegistrationParser
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class JdNpcBankSlipManagerService {

    private final static String PATH = "/JDNPCWS_TituloDestinatario.dll/soap/IJDNPCWS_TituloDestinatario"
    private final static String PAYER_INFO_PATH = "/JDNPCWS_TituloPagadorEletronico.dll/soap/IJDNPC_TituloPagadorEletronico"
    private final static String RECIPIENT_INFO_PATH = "/JDNPCWS_RecebimentoPgtoTit.dll/soap/IJDNPCWS_RecebimentoPgtoTit"

    def boletoBatchFileItemService

    public Map doRegistrationRequest(BoletoBatchFileItem boletoBatchFileItem, Boolean useAsaasAsBeneficiary) {
        AsaasBoletoBuilder asaasBoletoBuilder = new AsaasBoletoBuilder()

        BankSlipOnlineRegistrationResponse lastSentResponse
        if (boletoBatchFileItem.action.isUpdate()) {
            lastSentResponse = getLastSentRegistrationResponse(boletoBatchFileItem)
            if (!lastSentResponse.externalIdentifier) {
                Map referenceMap = getRegisterReferences(lastSentResponse.barCode)
                if (referenceMap.externalIdentifier) {
                    lastSentResponse.externalIdentifier = referenceMap.externalIdentifier
                    lastSentResponse.registrationReferenceId = referenceMap.registrationReferenceId
                    lastSentResponse.save(failOnError: true)
                }
            }
        }

        asaasBoletoBuilder.build(boletoBatchFileItem, useAsaasAsBeneficiary, lastSentResponse)

        JdNpcRegisterBankSlipRequestDTO jdnpcBankSlipDTO = asaasBoletoBuilder.bankSlip
        saveRegistrationInfo(jdnpcBankSlipDTO, boletoBatchFileItem)

        JdNpcBankSlipWsOperationType operationType = boletoBatchFileItem.action.isCreate() ? JdNpcBankSlipWsOperationType.REGISTER : JdNpcBankSlipWsOperationType.UPDATE
        JdNpcBankSlipManager jdNpcBankSlipManager = new JdNpcBankSlipManager()

        jdNpcBankSlipManager.post(PATH, asaasBoletoBuilder.buildXmlRequestBody(operationType))

        JdNpcRegisterBankSlipResponseDTO registerResponseDTO = JdNpcRegisterBankSlipResponseDTO.fromMap(jdNpcBankSlipManager.responseBody, boletoBatchFileItem.action.isUpdate())

        Map responseMap = [:]
        if (jdNpcBankSlipManager.isServerError()) {
            responseMap = [returnCode: null, errorCode: null, errorMessage: jdNpcBankSlipManager.responseBody?.take(255), registrationStatus: BoletoRegistrationStatus.FAILED, systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable()]
        } else if (!jdNpcBankSlipManager.responseBody) {
            responseMap = [returnCode: null, errorCode: null, errorMessage: "Sem retorno", systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable()]
        } else {
            if (registerResponseDTO.included) {
                responseMap.registrationStatus = BoletoRegistrationStatus.WAITING_REGISTRATION
            } else {
                responseMap = JdNpcBankSlipRegistrationParser.parseRegistrationFromJdNpcBankSlipRegisterResponse(registerResponseDTO)
            }
        }

        responseMap.barCode = jdnpcBankSlipDTO.CodBarras
        responseMap.digitableLine = jdnpcBankSlipDTO.LinhaDigitavel
        responseMap.responseHttpStatus = jdNpcBankSlipManager.statusCode

        return responseMap
    }

    public Map doWriteOffRequest(String externalIdentifier, String barCode, AsaasBankSlipWriteOffType asaasBankSlipWriteOffType, Map receiverInfo) {
        JdNpcBankSlipManager jdNpcBankSlipManager = new JdNpcBankSlipManager()

        JdNpcExecuteBankSlipWriteOffRequestDTO jdNpcExecuteBankSlipWriteOffRequestDTO  = new JdNpcExecuteBankSlipWriteOffRequestDTO(externalIdentifier,
            barCode,
            JdNpcBankSlipRegistrationParser.parseWriteOffCode(asaasBankSlipWriteOffType),
            receiverInfo.paymentChannel,
            receiverInfo.value,
            Utils.toInteger(receiverInfo.ispb),
            Utils.toInteger(receiverInfo.code)
        )

        jdNpcBankSlipManager.post(PATH, jdNpcExecuteBankSlipWriteOffRequestDTO.toXml())

        Map responseMap = [:]
        if (jdNpcBankSlipManager.isServerError()) {
            responseMap = [returnCode: null, errorCode: null, errorMessage: jdNpcBankSlipManager.responseBody?.take(255), registrationStatus: BoletoRegistrationStatus.FAILED, systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable(), responseHttpStatus: jdNpcBankSlipManager.statusCode, externalIdentifier: externalIdentifier]
        } else if (!jdNpcBankSlipManager.responseBody || jdNpcBankSlipManager.isTimeout()) {
            responseMap = [returnCode: null, errorCode: null, errorMessage: "Sem retorno", registrationStatus: BoletoRegistrationStatus.WAITING_REGISTRATION, systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable()]
        } else {
            JdNpcExecuteBankSlipWriteOffResponseDTO writeOffResponseDTO = JdNpcExecuteBankSlipWriteOffResponseDTO.fromMap(jdNpcBankSlipManager.responseBody)
            if (writeOffResponseDTO.included) {
                responseMap.registrationStatus = BoletoRegistrationStatus.WAITING_REGISTRATION
            } else {
                responseMap = JdNpcBankSlipRegistrationParser.parseWriteOffStatusFromJdNpcExecuteBankSlipWriteOffResponse(writeOffResponseDTO, externalIdentifier)
            }
        }

        return responseMap
    }

    public Map doWriteOffCancelRequest(String externalIdentifier, Integer cancellingReason, Date writeOffProcessedDate) {
        JdNpcBankSlipManager jdNpcBankSlipManager = new JdNpcBankSlipManager()
        jdNpcBankSlipManager.post(PATH, new JdNpcExecuteBankSlipWriteOffCancelRequestDTO(externalIdentifier, cancellingReason, writeOffProcessedDate).toXml())

        Map responseMap = [:]
        if (jdNpcBankSlipManager.isServerError()) {
            responseMap = [returnCode: null, errorCode: null, errorMessage: jdNpcBankSlipManager.responseBody?.take(255), registrationStatus: BoletoRegistrationStatus.FAILED, systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable(), responseHttpStatus: jdNpcBankSlipManager.statusCode, externalIdentifier: externalIdentifier]
        } else if (!jdNpcBankSlipManager.responseBody || jdNpcBankSlipManager.isTimeout()) {
            responseMap = [returnCode: null, errorCode: null, errorMessage: "Sem retorno", registrationStatus: BoletoRegistrationStatus.WAITING_REGISTRATION, systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable()]
        } else {
            JdNpcExecuteBankSlipWriteOffCancelResponseDTO writeOffResponseDTO = JdNpcExecuteBankSlipWriteOffCancelResponseDTO.fromMap(jdNpcBankSlipManager.responseBody)
            if (writeOffResponseDTO.included) {
                responseMap.registrationStatus = BoletoRegistrationStatus.WAITING_REGISTRATION
            } else {
                responseMap = JdNpcBankSlipRegistrationParser.parseCancelWriteOffStatusFromJdNpcExecuteBankSlipWriteOffCancelResponseDTO(writeOffResponseDTO, externalIdentifier)
            }
        }

        return responseMap
    }

    public Map getBankSlipRegistrationStatusResponseMap(String barCode, String digitableLine) {
        JdNpcBankSlipManager jdNpcBankSlipManager = new JdNpcBankSlipManager()
        jdNpcBankSlipManager.post(PATH, new JdNpcGetBankSlipStatusRequestDTO(barCode, null).toXml())

        if (jdNpcBankSlipManager.isServerError()) {
            return [returnCode: null, errorCode: null, errorMessage: jdNpcBankSlipManager.responseBody?.take(255), registrationStatus: BoletoRegistrationStatus.FAILED, systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable()]
        } else if (!jdNpcBankSlipManager.responseBody || jdNpcBankSlipManager.isTimeout()) {
            return [returnCode: null, errorCode: null, errorMessage: "Sem retorno", registrationStatus: BoletoRegistrationStatus.WAITING_REGISTRATION, systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable()]
        }

        Map registrationResponseMap = [:]
        if (jdNpcBankSlipManager.responseBody?.Body?.Fault) {
            registrationResponseMap =  JdNpcBankSlipRegistrationParser.parseStatusRequestReturnWithError(jdNpcBankSlipManager.responseBody, true)
        } else {
            List<JdNpcGetBankSlipStatusResponseDTO> jdnpcBankSlipStatusResponseDTOList = JdNpcGetBankSlipStatusResponseDTO.parseGetBankSlipStatusResponse(jdNpcBankSlipManager.responseBody)
            if (jdnpcBankSlipStatusResponseDTOList) {
                registrationResponseMap = JdNpcBankSlipRegistrationParser.parseRegistrationFromJdNpcBankSlipStatusResponse(jdnpcBankSlipStatusResponseDTOList.get(0))
            }
        }

        registrationResponseMap.barCode = barCode
        registrationResponseMap.responseHttpStatus = jdNpcBankSlipManager.statusCode
        registrationResponseMap.systemAvailable = true
        registrationResponseMap.digitableLine = digitableLine

        return registrationResponseMap
    }

    public Map getBankSlipWriteOffStatusResponseMap(String barCode, String externalIdentifier) {
        JdNpcBankSlipManager jdNpcBankSlipManager = new JdNpcBankSlipManager()
        jdNpcBankSlipManager.post(PATH, new JdNpcGetBankSlipStatusRequestDTO(barCode, externalIdentifier).toXml())

        if (jdNpcBankSlipManager.isServerError()) {
            return [returnCode: null, errorCode: null, errorMessage: jdNpcBankSlipManager.responseBody?.take(255), registrationStatus: BoletoRegistrationStatus.FAILED, systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable(), responseHttpStatus: jdNpcBankSlipManager.statusCode]
        } else if (!jdNpcBankSlipManager.responseBody || jdNpcBankSlipManager.isTimeout()) {
            return [returnCode: null, errorCode: null, errorMessage: "Sem retorno", registrationStatus: BoletoRegistrationStatus.WAITING_REGISTRATION, systemAvailable: !jdNpcBankSlipManager.isServiceUnavailable()]
        }

        if (jdNpcBankSlipManager.responseBody?.Body?.Fault) {
            return JdNpcBankSlipRegistrationParser.parseStatusRequestReturnWithError(jdNpcBankSlipManager.responseBody, false)
        }

        Map writeOffResponseMap = [:]
        writeOffResponseMap.responseHttpStatus = jdNpcBankSlipManager.statusCode

        List<JdNpcGetBankSlipStatusResponseDTO> jdnpcBankSlipStatusResponseDTOList = JdNpcGetBankSlipStatusResponseDTO.parseGetBankSlipStatusResponse(jdNpcBankSlipManager.responseBody)
        if (jdnpcBankSlipStatusResponseDTOList) {
            writeOffResponseMap = JdNpcBankSlipRegistrationParser.parseWriteOffStatusFromJdNpcBankSlipStatusResponse(jdnpcBankSlipStatusResponseDTOList.get(0), writeOffResponseMap.responseHttpStatus)
        }

        return writeOffResponseMap
    }

    public Map getRegisterReferences(String barCode) {
        Map referencesMap = [:]
        JdNpcBankSlipManager jdNpcBankSlipManager = new JdNpcBankSlipManager()
        jdNpcBankSlipManager.post(PATH, new AsaasBoletoBuilder().buildDetailedXmlRequestBody(barCode))

        if (jdNpcBankSlipManager.isServerError() || !jdNpcBankSlipManager.responseBody) {
            return referencesMap
        }

        JdNpcGetBankSlipStatusResponseDTO jdNpcGetBankSlipStatusResponseDTO = JdNpcGetBankSlipStatusResponseDTO.parseGetDetailedBankSlipInfoResponse(jdNpcBankSlipManager.responseBody)
        referencesMap.externalIdentifier = jdNpcGetBankSlipStatusResponseDTO?.NumIdentcTit
        referencesMap.registrationReferenceId = jdNpcGetBankSlipStatusResponseDTO?.NumRefAtlCadTit

        if (!referencesMap.externalIdentifier) {
            AsaasLogger.info("JdNpcBankSlipManagerService.getRegisterReferences >> Usando consulta de recebedor para obter referências do boleto [barCode: ${barCode}]")
            referencesMap = getBankSlipReferencesAsRecipient(barCode)
        }

        return referencesMap
    }

    public List<BankSlipPayerInfoAdapter> getBankSlipPayerInfo(String nossoNumero, Date paymentDate) {
        JdNpcBankSlipManager jdNpcBankSlipManager = new JdNpcBankSlipManager()
        jdNpcBankSlipManager.post(PAYER_INFO_PATH, new JdNpcBankSlipPayerInfoRequestDTO(nossoNumero, paymentDate).toXml())

        if (jdNpcBankSlipManager.isServerError() || !jdNpcBankSlipManager.responseBody || jdNpcBankSlipManager.isTimeout()) {
            return null
        }

        if (jdNpcBankSlipManager.responseBody?.Body?.Fault) {
            AsaasLogger.warn("JdNpcBankSlipManagerService.getBankSlipPayerInfo >> Erro ao consultar dados do pagador. [nossoNumero: ${nossoNumero}, paymentDate: ${paymentDate}, erro: ${jdNpcBankSlipManager.responseBody?.Body?.Fault}]")
            return null
        }

        List<JdNpcBankSlipPayerInfoResponseDTO> jdNpcBankSlipPayerInfoResponseDTOList = JdNpcBankSlipPayerInfoResponseDTO.listFromXmlNode(jdNpcBankSlipManager.responseBody)

        List<BankSlipPayerInfoAdapter> bankSlipPayerInfoAdapterList = []
        for (JdNpcBankSlipPayerInfoResponseDTO jdNpcBankSlipPayerInfoResponseDTO : jdNpcBankSlipPayerInfoResponseDTOList) {
            bankSlipPayerInfoAdapterList += new BankSlipPayerInfoAdapter(jdNpcBankSlipPayerInfoResponseDTO)
        }

        return bankSlipPayerInfoAdapterList
    }

    public Map getBankSlipReferencesAsRecipient(String barCode) {
        Map referencesMap = [:]

        JdNpcBankSlipManager jdNpcBankSlipManager = new JdNpcBankSlipManager()
        jdNpcBankSlipManager.post(JdNpcBankSlipManagerService.RECIPIENT_INFO_PATH, new JdNpcRecipientBankSlipInfoRequestDTO(barCode).toXml())

        JdNpcRecipientBankSlipInfoResponseDTO jdNpcRecipientBankSlipInfoResponseDTO = JdNpcRecipientBankSlipInfoResponseDTO.parseResponseMap(jdNpcBankSlipManager.responseBody)
        referencesMap.externalIdentifier = jdNpcRecipientBankSlipInfoResponseDTO?.NumIdentcTit
        referencesMap.registrationReferenceId = jdNpcRecipientBankSlipInfoResponseDTO?.NumRefAtlCadTit
        referencesMap.writeOffStatus = jdNpcRecipientBankSlipInfoResponseDTO?.SitTitPgto
        referencesMap.writeOffDate = jdNpcRecipientBankSlipInfoResponseDTO?.DtHrSitTit

        return referencesMap
    }

    private void saveRegistrationInfo(JdNpcRegisterBankSlipRequestDTO bankSlip, BoletoBatchFileItem boletoBatchFileItem) {
        Map registrationInfoMap = [
            value: bankSlip.Valor,
            dueDate: CustomDateUtils.fromString(bankSlip.DtVencTit.toString(), "yyyyMMdd"),
            cpfCnpj: bankSlip.CPFCNPJPagdr,
            monthlyInterestPercentage: bankSlip.VlrPercJuros,
            fineValue: bankSlip.CodMulta == "1" ? bankSlip.VlrPercMulta : null,
            finePercentage: bankSlip.CodMulta == "2" ? bankSlip.VlrPercMulta : null
        ]

        if (bankSlip.RepetDesconto) {
            JdNpcRegisterBankSlipDiscountRequestDTO discountDTO = bankSlip?.RepetDesconto?.get(0)
            if (discountDTO) {
                if (discountDTO.CodDesctTit == "1") registrationInfoMap.discountValue = discountDTO.Vlr_PercDesctTit
                if (discountDTO.CodDesctTit == "2") registrationInfoMap.discountPercentage = discountDTO.Vlr_PercDesctTit
                registrationInfoMap.discountLimitDate = CustomDateUtils.fromString(discountDTO.DtDesctTit.toString(), "yyyyMMdd")
            }
        }

        boletoBatchFileItemService.saveRegistrationInfo(boletoBatchFileItem, registrationInfoMap)
    }

    private BankSlipOnlineRegistrationResponse getLastSentRegistrationResponse(BoletoBatchFileItem boletoBatchFileItem) {
        Map searchParams = [:]
        searchParams.payment = boletoBatchFileItem.payment
        searchParams."action[in]" = [BoletoAction.CREATE, BoletoAction.UPDATE]
        searchParams.nossoNumero = boletoBatchFileItem.nossoNumero
        searchParams.sort = "id"
        searchParams.order = "desc"

        BoletoBatchFileItem lastSentBoletoBatchFileItem = BoletoBatchFileItem.sentItem(searchParams).get()

        return BankSlipOnlineRegistrationResponse.query(boletoBatchFileItemId: lastSentBoletoBatchFileItem.id, sort: "id", order: "desc").get()
    }
}

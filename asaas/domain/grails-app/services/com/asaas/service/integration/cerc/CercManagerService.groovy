package com.asaas.service.integration.cerc

import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.domain.integration.cerc.conciliation.contract.CercContractConciliationSummary
import com.asaas.domain.integration.cerc.conciliation.receivableunit.ReceivableUnitConciliationSummary
import com.asaas.domain.integration.cerc.contestation.CercContestation
import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffect
import com.asaas.domain.integration.cerc.optin.CercAsaasOptIn
import com.asaas.domain.integration.cerc.optin.CercAsaasOptOut
import com.asaas.domain.receivableunit.AnticipatedReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.externalreceivableunit.vo.ExternalReceivableUnitVO
import com.asaas.integration.cerc.adapter.externalreceivableunit.QueryExternalReceivableUnitResponseAdapter
import com.asaas.integration.cerc.api.CercManager
import com.asaas.integration.cerc.api.CercResponseAdapter
import com.asaas.integration.cerc.dto.anticipatedreceivableunit.AnticipatedReceivableUnitRequestDTO
import com.asaas.integration.cerc.dto.company.CompanyRequestDTO
import com.asaas.integration.cerc.dto.contestation.ContestationDTO
import com.asaas.integration.cerc.dto.contract.ContractConciliationDTO
import com.asaas.integration.cerc.dto.contract.QueryContractDTO
import com.asaas.integration.cerc.dto.externalreceivableunit.QueryExternalReceivableUnitResponseDTO
import com.asaas.integration.cerc.dto.fidccontractualeffect.FidcContractualEffectDTO
import com.asaas.integration.cerc.dto.fidccontractualeffect.QueryExternalReceivableUnitRequestDTO
import com.asaas.integration.cerc.dto.optin.OptInDTO
import com.asaas.integration.cerc.dto.optout.OptOutDTO
import com.asaas.integration.cerc.dto.receivableunit.QueryReceivableUnitContractualEffectDTO
import com.asaas.integration.cerc.dto.receivableunit.ReceivableUnitConciliationDTO
import com.asaas.integration.cerc.dto.receivableunit.ReceivableUnitRequestDTO
import com.asaas.integration.cerc.enums.CercResponseStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class CercManagerService {

    def grailsApplication

    public CercResponseAdapter syncContestation(CercContestation cercContestation) {
        CercResponseAdapter responseAdapter = new CercResponseAdapter()
        responseAdapter.status = CercResponseStatus.SUCCESS
        responseAdapter.errorList = []
        if (!grailsApplication.config.cerc.api.testMode) {
            responseAdapter.protocol = UUID.randomUUID()
            return responseAdapter
        }

        ContestationDTO contestationDTO = new ContestationDTO(cercContestation)

        CercManager cercManager = new CercManager()
        cercManager.returnAsList = false
        cercManager.timeout = 300000
        cercManager.post("/contestacao/enviar", contestationDTO)

        responseAdapter.status = processResponseStatus(cercManager)
        if (!responseAdapter.status.isSuccess()) {
            responseAdapter.errorList = cercManager.responseBody?.errorList
            return responseAdapter
        }

        responseAdapter.protocol = cercManager.responseBody.protocol?.toString()
        return responseAdapter
    }

    public Map queryReceivableUnitContractualEffects(ReceivableUnit receivableUnit) {
        if (!grailsApplication.config.cerc.api.testMode) return [:]

        QueryReceivableUnitContractualEffectDTO queryDTO = new QueryReceivableUnitContractualEffectDTO(receivableUnit)

        CercManager cercManager = new CercManager()
        cercManager.timeout = 60000
        cercManager.returnAsList = false
        cercManager.shouldParseResponse = false
        cercManager.post("/efeito_contrato/consultar", queryDTO)

        if (!cercManager.isSuccessful()) {
            AsaasLogger.warn("CercManagerService.queryReceivableUnitContractualEffects >> Falha ao consultar efeitos da UR [${receivableUnit.id}].\nStatus: [${cercManager.statusCode}]\nResponseBody: [${cercManager.responseBody}]")
            return [:]
        }

        return cercManager.responseBody
    }

    public Boolean syncReceivableUnitConciliation(ReceivableUnitConciliationSummary conciliationSummary) {
        if (!grailsApplication.config.cerc.api.testMode) return true

        ReceivableUnitConciliationDTO conciliationDTO = new ReceivableUnitConciliationDTO(conciliationSummary)

        final Integer timeout = 120000
        CercManager cercManager = new CercManager()
        cercManager.setTimeout(timeout)
        cercManager.post("/conciliacao/agenda", conciliationDTO)

        if (!cercManager.isSuccessful()) {
            AsaasLogger.warn("CercManagerService.syncReceivableUnitConciliation >> Falha ao conciliar agenda [${conciliationSummary.conciliation.id}].\nStatus: [${cercManager.statusCode}]\nResponseBody: [${cercManager.responseBody}]")
            return false
        }

        return true
    }

    public Boolean syncContractConciliation(CercContractConciliationSummary conciliationSummary) {
        if (!grailsApplication.config.cerc.api.testMode) return true

        ContractConciliationDTO conciliationDTO = new ContractConciliationDTO(conciliationSummary)

        final Integer timeout = 120000
        CercManager cercManager = new CercManager()
        cercManager.setTimeout(timeout)
        cercManager.shouldParseResponse = false
        cercManager.post("/conciliacao/contrato", conciliationDTO)

        if (!cercManager.isSuccessful()) {
            AsaasLogger.warn("CercManagerService.syncContractConciliation >> Falha ao conciliar contratos a partir do resumo [${conciliationSummary.id}].\nStatus: [${cercManager.statusCode}]\nResponseBody: [${cercManager.responseBody}]")
            return false
        }

        return true
    }

    public CercResponseAdapter syncCompany(CercCompany cercCompany, String externalIdentifier) {
        CercResponseAdapter responseAdapter = new CercResponseAdapter()
        responseAdapter.status = CercResponseStatus.SUCCESS
        responseAdapter.errorList = []

        if (!grailsApplication.config.cerc.api.testMode) return responseAdapter

        CompanyRequestDTO companyRequestDTO = new CompanyRequestDTO(cercCompany, externalIdentifier)

        CercManager cercManager = new CercManager()
        cercManager.put("/estabelecimento", companyRequestDTO)

        responseAdapter.status = processResponseStatus(cercManager)
        if (!responseAdapter.status.isSuccess()) {
            responseAdapter.errorList = cercManager.responseBody?.errorList
            return responseAdapter
        }

        return responseAdapter
    }

    public CercResponseAdapter syncReceivableUnit(ReceivableUnit receivableUnit) {
        return syncReceivableUnit(receivableUnit, true)
    }

    public CercResponseAdapter syncReceivableUnit(ReceivableUnit receivableUnit, Boolean async) {
        CercResponseAdapter responseAdapter = new CercResponseAdapter()
        responseAdapter.status = CercResponseStatus.SUCCESS
        responseAdapter.errorList = []

        if (!grailsApplication.config.cerc.api.testMode) return responseAdapter

        ReceivableUnitRequestDTO receivableUnitRequestDTO = new ReceivableUnitRequestDTO(receivableUnit)

        CercManager cercManager = new CercManager()
        cercManager.timeout = 30000

        String receivableUnitPath = async ? "/v15/unidades_recebiveis" : "/unidades_recebiveis"
        cercManager.put(receivableUnitPath, receivableUnitRequestDTO)

        responseAdapter.protocol = cercManager.responseBody?.protocol?.toString()

        responseAdapter.status = processResponseStatus(cercManager)
        if (!responseAdapter.status.isSuccess()) {
            responseAdapter.errorList = cercManager.responseBody?.errorList
            return responseAdapter
        }

        return responseAdapter
    }

    public CercResponseAdapter syncAnticipatedReceivableUnit(AnticipatedReceivableUnit anticipatedReceivableUnit) {
        CercResponseAdapter responseAdapter = new CercResponseAdapter()
        responseAdapter.status = CercResponseStatus.SUCCESS
        responseAdapter.errorList = []
        if (!grailsApplication.config.cerc.api.testMode) return responseAdapter

        AnticipatedReceivableUnitRequestDTO anticipatedReceivableUnitRequestDTO = new AnticipatedReceivableUnitRequestDTO(anticipatedReceivableUnit)

        CercManager cercManager = new CercManager()
        cercManager.timeout = 360000
        cercManager.put("/pos_contratadas", anticipatedReceivableUnitRequestDTO)

        responseAdapter.protocol = cercManager.responseBody?.protocol?.toString()

        responseAdapter.responseBody = cercManager.responseBody
        responseAdapter.status = processResponseStatus(cercManager)

        if (!responseAdapter.status.isSuccess()) {
            responseAdapter.errorList = responseAdapter.responseBody?.errorList
        }

        return responseAdapter
    }

    public CercResponseAdapter syncAsaasOptIn(CercAsaasOptIn asaasOptIn) {
        CercResponseAdapter responseAdapter = new CercResponseAdapter()
        responseAdapter.status = CercResponseStatus.SUCCESS
        responseAdapter.errorList = []
        if (!grailsApplication.config.cerc.api.testMode) {
            responseAdapter.protocol = UUID.randomUUID()
            return responseAdapter
        }

        OptInDTO optInDTO = new OptInDTO(asaasOptIn)

        CercManager cercManager = new CercManager()
        cercManager.post("/opt_in", optInDTO)

        responseAdapter.status = processResponseStatus(cercManager)
        if (!responseAdapter.status.isSuccess()) {
            responseAdapter.errorList = cercManager.responseBody?.errorList
            return responseAdapter
        }

        responseAdapter.protocol = cercManager.responseBody.protocol?.toString()

        return responseAdapter
    }

    public CercResponseAdapter syncAsaasOptOut(CercAsaasOptOut asaasOptOut) {
        CercResponseAdapter responseAdapter = new CercResponseAdapter()
        responseAdapter.status = CercResponseStatus.SUCCESS
        responseAdapter.errorList = []

        if (!grailsApplication.config.cerc.api.testMode) {
            responseAdapter.protocol = UUID.randomUUID()
            return responseAdapter
        }

        OptOutDTO optOutDTO = new OptOutDTO(asaasOptOut)

        CercManager cercManager = new CercManager()
        cercManager.post("/opt_out", optOutDTO)

        responseAdapter.status = processResponseStatus(cercManager)
        if (!responseAdapter.status.isSuccess()) {
            responseAdapter.errorList = cercManager.responseBody?.errorList
            return responseAdapter
        }

        responseAdapter.protocol = cercManager.responseBody.protocol?.toString()
        return responseAdapter
    }

    public CercResponseAdapter syncFidcContractualEffect(CercFidcContractualEffect contractualEffect) {
        CercResponseAdapter responseAdapter = new CercResponseAdapter()
        responseAdapter.status = CercResponseStatus.SUCCESS
        responseAdapter.errorList = []
        if (!grailsApplication.config.cerc.api.testMode) return responseAdapter

        FidcContractualEffectDTO contractualEffectDTO = new FidcContractualEffectDTO(contractualEffect)

        CercManager cercManager = new CercManager()
        cercManager.put("/v15/contratos", contractualEffectDTO)

        responseAdapter.status = processResponseStatus(cercManager)

        return responseAdapter
    }

    public Map queryFidcContractInfo(CercFidcContractualEffect contractualEffect) {
        if (!grailsApplication.config.cerc.api.testMode) return [:]

        QueryContractDTO queryDTO = new QueryContractDTO(contractualEffect.externalIdentifier, contractualEffect.contractIdentifier)

        CercManager cercManager = new CercManager()
        cercManager.timeout = 60000
        cercManager.returnAsList = false
        cercManager.shouldParseResponse = false
        cercManager.post("/contrato/consultar", queryDTO)

        if (!cercManager.isSuccessful()) {
            AsaasLogger.warn("CercManagerService.queryFidcContractInfo >> Falha ao consultar contrato do FIDC [${contractualEffect.id}].\nStatus: [${cercManager.statusCode}]\nResponseBody: [${cercManager.responseBody}]")
            return [:]
        }

        return cercManager.responseBody
    }

    public Boolean isApiAvailable() {
        if (!grailsApplication.config.cerc.api.testMode) return true

        CercManager cercManager = new CercManager()
        cercManager.get("/health_check", null)

        return cercManager.isSuccessful()
    }

    public QueryExternalReceivableUnitResponseAdapter queryExternalReceivableUnit(ExternalReceivableUnitVO externalReceivableUnitVO, Boolean online) {
        if (AsaasEnvironment.isDevelopment() && !grailsApplication.config.cerc.api.testMode) {
            QueryExternalReceivableUnitResponseDTO mockDto = new MockJsonUtils("cerc/externalreceivableunit/queryExternalReceivableUnit.json").buildMock(QueryExternalReceivableUnitResponseDTO)
            mockDto.documentoUsuarioFinalRecebedor = externalReceivableUnitVO.customerCpfCnpj
            mockDto.agendas.first().instituicaoCredenciadora = externalReceivableUnitVO.shouldQueryAllAcquirers ? grailsApplication.config.asaas.cnpj.substring(1) : externalReceivableUnitVO.acquirerCnpjList.first()
            mockDto.agendas.first().codigoArranjoPagamento = externalReceivableUnitVO.paymentArrangementList.first()

            QueryExternalReceivableUnitResponseAdapter mockAdapter = new QueryExternalReceivableUnitResponseAdapter(mockDto)

            return mockAdapter
        }

        CercResponseAdapter responseAdapter = new CercResponseAdapter()
        responseAdapter.status = CercResponseStatus.SUCCESS
        responseAdapter.errorList = []

        QueryExternalReceivableUnitRequestDTO queryDTO = new QueryExternalReceivableUnitRequestDTO(externalReceivableUnitVO)

        final Integer timeoutForQuery = 60000
        CercManager cercManager = new CercManager()
        cercManager.setTimeout(timeoutForQuery)
        cercManager.returnAsList = false
        cercManager.shouldParseResponse = false
        cercManager.post(online ? "/v15/agenda/consultar?online=true" : "/v15/agenda/consultar", queryDTO)

        responseAdapter.status = processResponseStatus(cercManager)
        if (!responseAdapter.status.isSuccess()) {
            String errorMessage = "A consulta não foi bem sucedida. "
            if (cercManager.responseBody?.erros) errorMessage += "Os seguintes códigos de erro foram retornados: ${cercManager.responseBody?.erros.collect { it.codigo }.join(", ")}"

            throw new BusinessException(errorMessage)
        }

        responseAdapter.responseBody = cercManager.responseBody

        QueryExternalReceivableUnitResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((responseAdapter.responseBody as JSON).toString(), QueryExternalReceivableUnitResponseDTO)
        QueryExternalReceivableUnitResponseAdapter adapter = new QueryExternalReceivableUnitResponseAdapter(responseDTO)

        return adapter
    }

    public Map rawQueryExternalReceivableUnit(ExternalReceivableUnitVO externalReceivableUnitVO) {
        QueryExternalReceivableUnitRequestDTO queryDTO = new QueryExternalReceivableUnitRequestDTO(externalReceivableUnitVO)

        final Integer timeoutForQuery = 60000
        CercManager cercManager = new CercManager()
        cercManager.setTimeout(timeoutForQuery)
        cercManager.returnAsList = false
        cercManager.shouldParseResponse = false
        cercManager.post("/v15/agenda/consultar?online=true", queryDTO)

        CercResponseStatus responseStatus = processResponseStatus(cercManager)
        if (!responseStatus.isSuccess()) {
            String errorMessage = "A consulta não foi bem sucedida. "
            if (cercManager.responseBody?.erros) errorMessage += "Os seguintes códigos de erro foram retornados: ${cercManager.responseBody?.erros.collect { it.codigo }.join(", ")}"

            throw new BusinessException(errorMessage)
        }

        return cercManager.responseBody
    }

    private CercResponseStatus processResponseStatus(CercManager manager) {
        if (manager.isTimeout()) return CercResponseStatus.TIMEOUT

        if (manager.isServerError()) return CercResponseStatus.SERVER_ERROR

        if (manager.isUnauthorized()) return CercResponseStatus.UNAUTHORIZED

        if (!manager.isSuccessful()) return CercResponseStatus.ERROR

        return CercResponseStatus.SUCCESS
    }
}

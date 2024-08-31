package com.asaas.service.integration.heimdall

import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.dataenhancement.get.natural.basicdata.NaturalBasicDataDTO
import com.asaas.integration.heimdall.dto.legalpersonadjacencylisttree.HeimdallGetLegalPersonAdjacencyListTreeResponseDTO
import com.asaas.integration.heimdall.dto.revenueserviceregister.query.legal.LegalRevenueServiceRegisterResponseDTO
import com.asaas.integration.heimdall.dto.revenueserviceregister.query.natural.NaturalRevenueServiceRegisterResponseDTO
import com.asaas.integration.heimdall.enums.revenueserviceregister.RevenueServiceRegisterCacheLevel
import com.asaas.log.AsaasLogger
import com.asaas.revenueserviceregister.adapter.RevenueServiceRegisterAdapter
import com.asaas.legalpersonpartnertree.adapter.LegalPersonAdjacencyListTreeAdapter
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class RevenueServiceRegisterManagerService {

    public NaturalBasicDataDTO getNaturalData(String cpf) {
        if (AsaasEnvironment.isDevelopment()) {
            NaturalBasicDataDTO naturalBasicDataDTO = new MockJsonUtils("heimdall/RevenueServiceRegisterManagerService/queryNaturalBasicData.json").buildMock(NaturalBasicDataDTO)
            return naturalBasicDataDTO
        }

        HeimdallManager heimdallManager = new HeimdallManager()
        heimdallManager.get("/revenueServiceRegister/getNatural?cpf=${cpf}", [:])
        if (!heimdallManager.isSuccessful()) throw new RuntimeException("Ocorreu um problema no servidor do Heimdall e não foi possível consultar os dados. [cpf: ${CpfCnpjUtils.maskCpfCnpjForPublicVisualization(cpf)}]")

        NaturalBasicDataDTO naturalBasicDataDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), NaturalBasicDataDTO)
        return naturalBasicDataDTO
    }

    public RevenueServiceRegisterAdapter queryNaturalPerson(String cpf, Date birthDate, RevenueServiceRegisterCacheLevel cacheLevel) {
        if (AsaasEnvironment.isDevelopment()) {
            NaturalRevenueServiceRegisterResponseDTO naturalPersonDTO = new MockJsonUtils("heimdall/RevenueServiceRegisterManagerService/queryNaturalPerson.json").buildMock(NaturalRevenueServiceRegisterResponseDTO)
            naturalPersonDTO.cpf = cpf
            naturalPersonDTO.birthDate = birthDate
            return new  RevenueServiceRegisterAdapter(naturalPersonDTO)
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        String birthDateParsed = parseBirthDate(birthDate)
        heimdallManager.get("/revenueServiceRegister/queryNaturalPerson?cpf=${cpf}&birthDate=${birthDateParsed}&cacheLevel=${cacheLevel}", [:])

        if (!heimdallManager.isSuccessful()) {
            if (heimdallManager.isNotFound()) {
                throw new BusinessException("revenueServiceRegister.errors.cpfNonexistent")
            }
            if (heimdallManager.isReadTimeout()) {
                throw new BusinessException("revenueServiceRegister.errors.unsuccessfulQuery")
            }
            if (heimdallManager.isUnprocessableEntity()) {
                throw new BusinessException(heimdallManager.getErrorMessages().first())
            }

            throw new RuntimeException("RevenueServiceRegisterManagerService.queryNaturalPerson. CPF: [${CpfCnpjUtils.maskCpfCnpjForPublicVisualization(cpf)}], birthDate: [${birthDateParsed}]")
        }

        NaturalRevenueServiceRegisterResponseDTO naturalPerson = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), NaturalRevenueServiceRegisterResponseDTO)

        if (naturalPerson.registerStatus == "CPF E DATA NASCIMENTO DIVERGENTES") {
            throw new BusinessException("revenueServiceRegister.errors.cpfAndBirthDateAreDivergent")
        }

        return new RevenueServiceRegisterAdapter(naturalPerson)
    }

    public RevenueServiceRegisterAdapter queryLegalPerson(String cnpj, Boolean reload) {
        return queryLegalPerson(cnpj, RevenueServiceRegisterCacheLevel.convert(reload))
    }

    public RevenueServiceRegisterAdapter queryLegalPerson(String cnpj, RevenueServiceRegisterCacheLevel cacheLevel) {
        if (AsaasEnvironment.isDevelopment()) {
            LegalRevenueServiceRegisterResponseDTO legalPersonDTO = new MockJsonUtils("heimdall/RevenueServiceRegisterManagerService/queryLegalPerson.json").buildMock(LegalRevenueServiceRegisterResponseDTO)
            legalPersonDTO.cnpj = cnpj
            return new  RevenueServiceRegisterAdapter(legalPersonDTO)
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get("/revenueServiceRegister/queryLegalPerson?cnpj=${cnpj}&cacheLevel=${cacheLevel}", [:])
        if (heimdallManager.isNotFound()) {
            throw new BusinessException("revenueServiceRegister.errors.cnpjNonexistent")
        }
        if (heimdallManager.isReadTimeout()) {
            throw new BusinessException("revenueServiceRegister.errors.unsuccessfulQuery")
        }
        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("RevenueServiceRegisterManagerService.queryLegalPerson. CNPJ: [${CpfCnpjUtils.maskCpfCnpjForPublicVisualization(cnpj)}]")
        }
        LegalRevenueServiceRegisterResponseDTO legalPerson = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), LegalRevenueServiceRegisterResponseDTO)

        if (legalPerson.registerStatus == "BAIXADA") {
            throw new BusinessException("revenueServiceRegister.errors.blockedCnpj")
        }

        return new RevenueServiceRegisterAdapter(legalPerson)
    }

    public void savePartnerTreeRequest(Long customerId, String cnpj) {
        if (!AsaasEnvironment.isProduction()) return

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.post("/revenueServiceRegister/savePartnerTreeRequest?customerId=${customerId}&cnpj=${cnpj}", [:])

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("RevenueServiceRegisterManagerService.savePartnerTreeRequest. customerId: [${customerId}] CNPJ: [${CpfCnpjUtils.maskCpfCnpjForPublicVisualization(cnpj)}]")
        }
    }

    public LegalPersonAdjacencyListTreeAdapter getLegalPersonAdjacencyListTree(String cnpj) {
        if (AsaasEnvironment.isDevelopment()) {
            HeimdallGetLegalPersonAdjacencyListTreeResponseDTO treeResponseDTO = new MockJsonUtils("heimdall/RevenueServiceRegisterManagerService/getLegalPersonAdjacencyListTree.json").buildMock(HeimdallGetLegalPersonAdjacencyListTreeResponseDTO)
            treeResponseDTO.legalPersonAdjacencyListTree.last().cnpj = cnpj
            return new LegalPersonAdjacencyListTreeAdapter(treeResponseDTO)
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get("/revenueServiceRegister/getLegalPersonAdjacencyListTree?cnpj=${cnpj}", [:])

        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("RevenueServiceRegisterManagerService.getLegalPersonAdjacencyListTree. CNPJ: [${CpfCnpjUtils.maskCpfCnpjForPublicVisualization(cnpj)}]")
        }

        HeimdallGetLegalPersonAdjacencyListTreeResponseDTO treeResponseDTO = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), HeimdallGetLegalPersonAdjacencyListTreeResponseDTO)
        LegalPersonAdjacencyListTreeAdapter LegalPersonAdjacencyListTree = new LegalPersonAdjacencyListTreeAdapter(treeResponseDTO)
        return LegalPersonAdjacencyListTree
    }

    public RevenueServiceRegisterAdapter findLatestLegalPerson(String cnpj) {
        if (AsaasEnvironment.isDevelopment()) {
            LegalRevenueServiceRegisterResponseDTO legalPersonDTO = new MockJsonUtils("heimdall/RevenueServiceRegisterManagerService/findLatestLegalPerson.json").buildMock(LegalRevenueServiceRegisterResponseDTO)
            legalPersonDTO.cnpj = cnpj
            return new RevenueServiceRegisterAdapter(legalPersonDTO)
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get("/revenueServiceRegister/getLatestLegalPerson?cnpj=${cnpj}", [:])

        if (!heimdallManager.isSuccessful()) {
            String maskedCnpj = CpfCnpjUtils.maskCpfCnpjForPublicVisualization(cnpj)
            if (heimdallManager.isNotFound()) {
                AsaasLogger.warn("RevenueServiceRegisterManagerService.findLatestLegalPerson >> CNPJ não encontrado [${maskedCnpj}]")
                return null
            }
            throw new RuntimeException("RevenueServiceRegisterManagerService.findLatestLegalPerson. CNPJ: [${maskedCnpj}]")
        }

        LegalRevenueServiceRegisterResponseDTO legalPerson = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), LegalRevenueServiceRegisterResponseDTO)
        return new RevenueServiceRegisterAdapter(legalPerson)
    }

    public RevenueServiceRegisterAdapter findLatestNaturalPerson(String cpf) {
        if (AsaasEnvironment.isDevelopment()) {
            NaturalRevenueServiceRegisterResponseDTO naturalPersonDTO = new MockJsonUtils("heimdall/RevenueServiceRegisterManagerService/findLatestNaturalPerson.json").buildMock(NaturalRevenueServiceRegisterResponseDTO)
            naturalPersonDTO.cpf = cpf
            return new RevenueServiceRegisterAdapter(naturalPersonDTO)
        }

        HeimdallManager heimdallManager = buildHeimdallManager()
        heimdallManager.get("/revenueServiceRegister/getLatestNaturalPerson?cpf=${cpf}", [:])

        if (!heimdallManager.isSuccessful()) {
            String maskedCpf = CpfCnpjUtils.maskCpfCnpjForPublicVisualization(cpf)
            if (heimdallManager.isNotFound()) {
                AsaasLogger.warn("RevenueServiceRegisterManagerService.findLatestNaturalPerson >> CPF não encontrado [${maskedCpf}]")
                return null
            }
            throw new RuntimeException("RevenueServiceRegisterManagerService.findLatestNaturalPerson. CPF: [${maskedCpf}]")
        }

        NaturalRevenueServiceRegisterResponseDTO naturalPerson = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), NaturalRevenueServiceRegisterResponseDTO)
        return new RevenueServiceRegisterAdapter(naturalPerson)
    }

    private HeimdallManager buildHeimdallManager() {
        final Integer TIMEOUT = 10000

        HeimdallManager heimdallManager = new HeimdallManager()

        heimdallManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        heimdallManager.setTimeout(TIMEOUT)

        return heimdallManager
    }

    private String parseBirthDate(Date birthDate) {
        if (!birthDate) return null

        return CustomDateUtils.fromDate(birthDate)
    }
}

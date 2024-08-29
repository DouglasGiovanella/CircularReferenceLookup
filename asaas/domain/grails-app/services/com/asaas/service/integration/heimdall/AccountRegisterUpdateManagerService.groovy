package com.asaas.service.integration.heimdall

import com.asaas.accountregisterupdate.adapter.divergence.LegalPersonDivergenceAdapter
import com.asaas.accountregisterupdate.adapter.divergence.NaturalPersonDivergenceAdapter
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.heimdall.HeimdallManager
import com.asaas.integration.heimdall.dto.accountregisterupdate.get.LegalPersonDivergenceResponseDTO
import com.asaas.integration.heimdall.dto.accountregisterupdate.get.NaturalPersonDivergenceResponseDTO
import com.asaas.utils.GsonBuilderUtils
import com.asaas.utils.MockJsonUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class AccountRegisterUpdateManagerService {

    public LegalPersonDivergenceAdapter getLegalPersonDivergences(Long id) {
        if (AsaasEnvironment.isDevelopment()) {
            LegalPersonDivergenceResponseDTO legalPersonDivergencesDTO = new MockJsonUtils("heimdall/AccountRegisterUpdateManagerService/getLegalPersonDivergences.json").buildMock(LegalPersonDivergenceResponseDTO)
            return new LegalPersonDivergenceAdapter(legalPersonDivergencesDTO)
        }
        HeimdallManager heimdallManager = get("/accountRegisterUpdate/getLegalPersonDivergences/?id=${id}")
        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("AccountRegisterUpdateManagerService >> Não foi possível buscar as divergências para Pessoa Jurídica. ID: [${id}]")
        }
        LegalPersonDivergenceResponseDTO legalPersonDivergences = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), LegalPersonDivergenceResponseDTO)
        LegalPersonDivergenceAdapter legalPersonDivergenceAdapter = new LegalPersonDivergenceAdapter(legalPersonDivergences)
        return legalPersonDivergenceAdapter
    }

    public NaturalPersonDivergenceAdapter getNaturalPersonDivergences(Long id) {
        if (AsaasEnvironment.isDevelopment()) {
            NaturalPersonDivergenceResponseDTO naturalPersonDivergencesDTO = new MockJsonUtils("heimdall/AccountRegisterUpdateManagerService/getNaturalPersonDivergences.json").buildMock(NaturalPersonDivergenceResponseDTO)
            return new  NaturalPersonDivergenceAdapter(naturalPersonDivergencesDTO)
        }
        HeimdallManager heimdallManager = get("/accountRegisterUpdate/getNaturalPersonDivergences/?id=${id}")
        if (!heimdallManager.isSuccessful()) {
            throw new RuntimeException("AccountRegisterUpdateManagerService >> Não foi possível buscar as divergências para Pessoa Física. ID: [${id}]")
        }
        NaturalPersonDivergenceResponseDTO naturalPersonDivergences = GsonBuilderUtils.buildClassFromJson((heimdallManager.responseBody as JSON).toString(), NaturalPersonDivergenceResponseDTO)
        NaturalPersonDivergenceAdapter naturalPersonDivergenceAdapter = new NaturalPersonDivergenceAdapter(naturalPersonDivergences)
        return naturalPersonDivergenceAdapter
    }

    private HeimdallManager get(String path) {
        HeimdallManager heimdallManager = new HeimdallManager()

        heimdallManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        heimdallManager.get(path, [:])

        return heimdallManager
    }

}

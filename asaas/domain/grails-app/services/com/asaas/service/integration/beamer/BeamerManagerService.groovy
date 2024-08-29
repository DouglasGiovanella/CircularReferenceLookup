package com.asaas.service.integration.beamer

import com.asaas.domain.customer.Customer
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.beamer.adapter.BeamerUserAdapter
import com.asaas.integration.beamer.api.v0.BeamerManager
import com.asaas.integration.beamer.dto.BeamerCreateUserResponseDTO
import com.asaas.integration.beamer.dto.BeamerCreateUserRequestDTO
import com.asaas.log.AsaasLogger
import com.asaas.utils.MockJsonUtils
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BeamerManagerService {

    public BeamerUserAdapter createUser(Customer customer, Map customProperties) {
        if (!AsaasEnvironment.isProduction()) return new BeamerUserAdapter(new MockJsonUtils("beamer/BeamerManagerService/createUser.json").buildMock(BeamerCreateUserResponseDTO))

        BeamerCreateUserRequestDTO beamerCreateUserRequestDTO = new BeamerCreateUserRequestDTO(customer, customProperties)

        BeamerManager beamerManager = new BeamerManager()
        beamerManager.post("/v0/users", beamerCreateUserRequestDTO.toMap())

        if (!beamerManager.isSuccessful()) {
            AsaasLogger.error("BeamerManagerService.createUser - Nao foi possivel fazer a requisicao. Customer: [${customer}]. customProperties: [${customProperties}]. ResponseBody: [${beamerManager.responseBody}]. ErrorMessage: [${beamerManager.errorMessage}]. StatusCode: [${beamerManager.statusCode}]")
            BeamerCreateUserResponseDTO createUserResponseDTO = GsonBuilderUtils.buildClassFromJson(([beamerId: null, success: beamerManager.isSuccessful()] as JSON).toString(), BeamerCreateUserResponseDTO)

            return new BeamerUserAdapter(createUserResponseDTO)
        }

        BeamerCreateUserResponseDTO createUserResponseDTO = GsonBuilderUtils.buildClassFromJson((beamerManager.responseBody + [success: beamerManager.isSuccessful()] as JSON).toString(), BeamerCreateUserResponseDTO)
        return new BeamerUserAdapter(createUserResponseDTO)
    }

    public Boolean updateUser(Customer customer, Map customProperties) {
        if (!AsaasEnvironment.isProduction()) return true

        try {
            BeamerCreateUserRequestDTO beamerCreateUserRequestDTO = new BeamerCreateUserRequestDTO(customer, customProperties)

            BeamerManager beamerManager = new BeamerManager()
            beamerManager.put("/v0/users", beamerCreateUserRequestDTO.toMap())

            if (!beamerManager.isSuccessful()) {
                AsaasLogger.error("BeamerManagerService.updateUser - Nao foi possivel fazer a requisicao. Customer: [${customer}]. customProperties: [${customProperties}]. ResponseBody: [${beamerManager.responseBody}]. ErrorMessage: [${beamerManager.errorMessage}]. StatusCode: [${beamerManager.statusCode}]")
            }

            return beamerManager.isSuccessful()
        } catch (Exception e) {
            AsaasLogger.error("BeamerManagerService.updateUser -> Ocorreu um erro ao atualizar o usuario.", e)
            return false
        }
    }
}

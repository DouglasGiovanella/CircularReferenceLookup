package com.asaas.service.postalcode

import com.asaas.domain.city.City
import com.asaas.domain.postalcode.InvalidPostalCodeCache
import com.asaas.domain.postalcode.PostalCode
import com.asaas.integration.correios.api.CorreiosSoapManager
import com.asaas.integration.correios.vo.ConsultaCepResponseVO
import com.asaas.log.AsaasLogger
import com.asaas.state.State
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class PostalCodeService {

    def asaasSegmentioService

    public PostalCode find(String postalCodeString) {
        postalCodeString = Utils.removeNonNumeric(postalCodeString)

        if (postalCodeString.size() < PostalCode.POSTAL_CODE_SIZE) return null

        PostalCode postalCode = PostalCode.query([postalCode: postalCodeString]).get()
        if (postalCode) return postalCode

        postalCode = correiosConsultAndCreate(postalCodeString)
        if (postalCode) return postalCode

        return null
    }

    public PostalCode correiosConsultAndCreate(String postalCodeString) {
        try {
            Boolean inInvalidCache = InvalidPostalCodeCache.query([exists: true, postalCode: postalCodeString, 'expirationDate[ge]': new Date().clearTime()]).get()
            if (inInvalidCache) return null

            Date startTime = new Date()

            CorreiosSoapManager correiosSoapManager = new CorreiosSoapManager()
            ConsultaCepResponseVO consultaCepResponseVO = correiosSoapManager.doConsultaCepRequest(postalCodeString)

            Map postalCodeRequestTrack = [
                postalCode: postalCodeString,
                dateCreated: startTime,
                startedRequest: startTime,
                httpStatus: correiosSoapManager.getHttpStatus(),
                success: correiosSoapManager.isSuccessful(),
                finishedRequest: new Date(),
                errorMessage: correiosSoapManager.getErrorMessage()
            ]
            postalCodeRequestTrack.runtimeMs = postalCodeRequestTrack.finishedRequest.getTime() - postalCodeRequestTrack.startedRequest.getTime()

            Long currentCustomerId = UserUtils.getCurrentUser()?.customerId
            asaasSegmentioService.track(currentCustomerId, "postal_code_request", postalCodeRequestTrack)

            if (!consultaCepResponseVO || !consultaCepResponseVO.cep) {
                saveInvalidPostalCodeCache(postalCodeString)
                return null
            }

            City city = City.query([name: consultaCepResponseVO.cidade, state: State.valueOf(consultaCepResponseVO.uf)]).get()
            if (!city) {
                saveInvalidPostalCodeCache(postalCodeString)
                return null
            }

            AsaasLogger.info("Criando PostalCode nao encontrado no Asaas: ${consultaCepResponseVO.cep}")
            PostalCode postalCode = new PostalCode(postalCode: consultaCepResponseVO.cep, address: consultaCepResponseVO.end ?: consultaCepResponseVO.cidade, province: consultaCepResponseVO.bairro, city: city).save()

            return postalCode
        } catch (Exception e) {
            Long currentCustomerId = UserUtils.getCurrentUser()?.customerId
            asaasSegmentioService.track(currentCustomerId, "postal_code_request", [postalCode: postalCodeString, dateCreated: new Date(), exceptionError: e.getMessage()])
            AsaasLogger.error("Não foi possível consultar um CEP. [${postalCodeString}]", e)
            return null
        }
    }

    public PostalCode importPostalCode (String postalCode, String address, String province, City city) {
        PostalCode newPostalCode = new PostalCode()
        newPostalCode.postalCode = postalCode
        newPostalCode.address = address
        newPostalCode.province = province
        newPostalCode.city = city
        newPostalCode.save(flush: true, failOnError: true)
        return newPostalCode
    }

    public Boolean hasValidCityPostalCode(String postalCode, String city) {
        if (!postalCode) return true
        if (!city) return true

        Long postalCodeCityId = PostalCode.find(postalCode)?.city?.id
        if (!postalCodeCityId) return true

        Long cityId = Long.valueOf(city)
        return postalCodeCityId == cityId
    }

    private void saveInvalidPostalCodeCache(String postalCodeString) {
        InvalidPostalCodeCache invalidPostalCodeCache = InvalidPostalCodeCache.query([postalCode: postalCodeString]).get()

        if (!invalidPostalCodeCache) invalidPostalCodeCache = new InvalidPostalCodeCache(postalCode: postalCodeString)

        invalidPostalCodeCache.expirationDate = CustomDateUtils.sumDays(new Date(), 7)

        invalidPostalCodeCache.save(failOnError: true)
    }
}

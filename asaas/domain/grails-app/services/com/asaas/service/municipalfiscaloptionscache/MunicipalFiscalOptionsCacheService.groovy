package com.asaas.service.municipalfiscaloptionscache

import com.asaas.domain.cache.MunicipalFiscalOptionsCache
import com.asaas.integration.invoice.api.vo.MunicipalFiscalOptionsVO
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import groovy.json.JsonOutput

@Transactional
class MunicipalFiscalOptionsCacheService {

    public MunicipalFiscalOptionsCache save(String ibgeCode, Boolean isNationalPortalCache, MunicipalFiscalOptionsVO municipalFiscalOptionsVO) {
        if (!municipalFiscalOptionsVO || municipalFiscalOptionsVO.errorCode) return

        MunicipalFiscalOptionsCache municipalFiscalOptionsCache
        if (isNationalPortalCache) {
            municipalFiscalOptionsCache = MunicipalFiscalOptionsCache.findOrCreateWhere(isNationalPortalCache: true)
        } else {
            municipalFiscalOptionsCache = MunicipalFiscalOptionsCache.findOrCreateWhere(ibgeCode: ibgeCode)
        }

        municipalFiscalOptionsCache.expirationDate = CustomDateUtils.sumDays(new Date(), MunicipalFiscalOptionsCache.DAYS_TO_EXPIRE)
        municipalFiscalOptionsCache.authenticationType = municipalFiscalOptionsVO.authenticationType
        municipalFiscalOptionsCache.digitalSignature = municipalFiscalOptionsVO.digitalSignature
        municipalFiscalOptionsCache.supportsCancellation = municipalFiscalOptionsVO.supportsCancellation
        municipalFiscalOptionsCache.usesSpecialTaxRegimes = municipalFiscalOptionsVO.usesSpecialTaxRegimes
        municipalFiscalOptionsCache.usesServiceListItem = municipalFiscalOptionsVO.usesServiceListItem
        municipalFiscalOptionsCache.isServiceListIntegrationAvailable = municipalFiscalOptionsVO.isServiceListIntegrationAvailable
        municipalFiscalOptionsCache.municipalInscriptionHelp = Utils.truncateString(municipalFiscalOptionsVO.municipalInscriptionHelp, MunicipalFiscalOptionsCache.MAX_HELP_TEXT_SIZE)
        municipalFiscalOptionsCache.specialTaxRegimeHelp = Utils.truncateString(municipalFiscalOptionsVO.specialTaxRegimeHelp, MunicipalFiscalOptionsCache.MAX_HELP_TEXT_SIZE)
        municipalFiscalOptionsCache.serviceListItemHelp = Utils.truncateString(municipalFiscalOptionsVO.serviceListItemHelp, MunicipalFiscalOptionsCache.MAX_HELP_TEXT_SIZE)
        municipalFiscalOptionsCache.digitalCertificatedHelp = Utils.truncateString(municipalFiscalOptionsVO.digitalCertificatedHelp, MunicipalFiscalOptionsCache.MAX_HELP_TEXT_SIZE)
        municipalFiscalOptionsCache.accessTokenHelp = Utils.truncateString(municipalFiscalOptionsVO.accessTokenHelp, MunicipalFiscalOptionsCache.MAX_HELP_TEXT_SIZE)
        municipalFiscalOptionsCache.municipalServiceCodeHelp = Utils.truncateString(municipalFiscalOptionsVO.municipalServiceCodeHelp, MunicipalFiscalOptionsCache.MAX_HELP_TEXT_SIZE)
        municipalFiscalOptionsCache.specialTaxRegimesList = municipalFiscalOptionsVO.specialTaxRegimesList ? new JsonOutput().toJson(municipalFiscalOptionsVO.specialTaxRegimesList) : null
        municipalFiscalOptionsCache.usesAedf = municipalFiscalOptionsVO.usesAedf
        municipalFiscalOptionsCache.save(failOnError: true)

        return municipalFiscalOptionsCache
    }

    public MunicipalFiscalOptionsVO getFromCache(String ibgeCode, Boolean isNationalPortalCache) {
        MunicipalFiscalOptionsCache municipalFiscalOptionsCache
        if (isNationalPortalCache) {
            municipalFiscalOptionsCache = MunicipalFiscalOptionsCache.query([isNationalPortalCache: isNationalPortalCache, 'expirationDate[ge]': new Date()]).get()
        } else {
            municipalFiscalOptionsCache = MunicipalFiscalOptionsCache.query([ibgeCode: ibgeCode, 'expirationDate[ge]': new Date()]).get()
        }

        if (!municipalFiscalOptionsCache) return null

        return new MunicipalFiscalOptionsVO(municipalFiscalOptionsCache)
    }
}

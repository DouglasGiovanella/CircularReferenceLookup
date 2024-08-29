package com.asaas.service.invoice

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.cache.MunicipalFiscalOptionsCache
import com.asaas.domain.city.City
import com.asaas.exception.BusinessException
import com.asaas.integration.invoice.api.manager.MunicipalRequestManager
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class InvoiceCityConfigService {

    def asyncActionService

    public Boolean isCityIntegrationEnabled(City city) {
        MunicipalRequestManager municipalRequestManager = new MunicipalRequestManager(city)
        return municipalRequestManager.isMunicipalIntegrationEnabled()
    }

    public void populateMunicipalFiscalOptionsCache() {
        List cityIdList = City.createCriteria().list() {
            projections {
                property "id"
            }

            eq("deleted", false)
            eqProperty("name", "district")
            order('id', 'asc')
        }

        Utils.forEachWithFlushSession(cityIdList, 50, { Long cityId ->
            Utils.withNewTransactionAndRollbackOnError({
                saveUpdateMunicipalFiscalOptionCache(cityId)
            })
        })
    }

    public Boolean processUpdateMunicipalFiscalOptionsCache() {
        List<Map> asyncActionDataList = listUpdateMunicipalFiscalOptionCache(150)
        if (!asyncActionDataList) return false

        Utils.forEachWithFlushSession(asyncActionDataList, 50, { Map asyncActionData ->
            Utils.withNewTransactionAndRollbackOnError({
                City city = City.get(asyncActionData.cityId)
                MunicipalRequestManager municipalRequestManager = new MunicipalRequestManager(city)
                municipalRequestManager.getOptions()

                asyncActionService.delete(asyncActionData.asyncActionId)
            })
        })

        return true
    }

    public void invalidateMunicipalFiscalOptionsCache(String ibgeCode) {
        MunicipalFiscalOptionsCache municipalFiscalOptionsCache = MunicipalFiscalOptionsCache.query([ibgeCode: ibgeCode]).get()

        if (!municipalFiscalOptionsCache) throw new BusinessException("Não existe cache para o municipio ${ibgeCode}")

        municipalFiscalOptionsCache.expirationDate = CustomDateUtils.getYesterday()
        municipalFiscalOptionsCache.save(failOnError: true)
    }

    private void saveUpdateMunicipalFiscalOptionCache(Long cityId) {
        Map asyncActionData = [cityId: cityId]
        asyncActionService.save(AsyncActionType.UPDATE_MUNICIPAL_FISCAL_OPTIONS_CACHE, asyncActionData)
    }

    private List<Map> listUpdateMunicipalFiscalOptionCache(Integer max) {
        return asyncActionService.listPending(AsyncActionType.UPDATE_MUNICIPAL_FISCAL_OPTIONS_CACHE, max)
    }
}

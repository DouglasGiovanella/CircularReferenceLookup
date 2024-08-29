package com.asaas.service.bifrost

import com.asaas.exception.BusinessException
import com.asaas.integration.bifrost.adapter.dailydollarconversionrate.DailyDollarConversionRateAdapter
import com.asaas.integration.bifrost.api.BifrostManager
import com.asaas.integration.bifrost.dto.dailydollarconversionrate.BifrostGetDailyDollarConversionRateResponseDTO
import com.asaas.integration.bifrost.dto.dailydollarconversionrate.BifrostListDailyDollarConversionRateResponseDTO
import com.asaas.utils.GsonBuilderUtils

import grails.converters.JSON
import grails.transaction.Transactional

@Transactional
class BifrostDailyDollarConversionRateManagerService {

    public List<DailyDollarConversionRateAdapter> list() {
        if (!BifrostManager.isEnabled()) return null

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/dailyDollarConversionRates/list", [:])

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível consultar as taxas de conversão. Por favor, tente novamente.")

        BifrostListDailyDollarConversionRateResponseDTO listDailyDollarConversionRateResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostListDailyDollarConversionRateResponseDTO)
        return listDailyDollarConversionRateResponseDTO.dailyDollarConversionRateList.collect { new DailyDollarConversionRateAdapter(it) }
    }

    public DailyDollarConversionRateAdapter getLatest() {
        if (!BifrostManager.isEnabled()) return null

        BifrostManager bifrostManager = new BifrostManager()
        bifrostManager.get("/dailyDollarConversionRates/getLatestRate", [:])

        if (!bifrostManager.isSuccessful()) throw new BusinessException("Não foi possível consultar a última taxa de conversão. Por favor, tente novamente.")

        BifrostGetDailyDollarConversionRateResponseDTO getDailyDollarConversionRateResponseDTO = GsonBuilderUtils.buildClassFromJson((bifrostManager.responseBody as JSON).toString(), BifrostGetDailyDollarConversionRateResponseDTO)
        return new DailyDollarConversionRateAdapter(getDailyDollarConversionRateResponseDTO)
    }
}

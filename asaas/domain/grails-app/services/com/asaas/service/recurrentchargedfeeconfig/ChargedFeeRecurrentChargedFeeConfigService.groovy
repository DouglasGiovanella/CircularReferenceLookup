package com.asaas.service.recurrentchargedfeeconfig

import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.recurrentchargedfeeconfig.ChargedFeeRecurrentChargedFeeConfig
import com.asaas.domain.recurrentchargedfeeconfig.RecurrentChargedFeeConfig

import grails.transaction.Transactional

@Transactional
class ChargedFeeRecurrentChargedFeeConfigService {

    public ChargedFeeRecurrentChargedFeeConfig save(RecurrentChargedFeeConfig recurrentChargedFeeConfig, ChargedFee chargedFee) {
        ChargedFeeRecurrentChargedFeeConfig chargedFeeRecurrentChargedFeeConfig = new ChargedFeeRecurrentChargedFeeConfig()
        chargedFeeRecurrentChargedFeeConfig.recurrentChargedFeeConfig = recurrentChargedFeeConfig
        chargedFeeRecurrentChargedFeeConfig.chargedFee = chargedFee
        chargedFeeRecurrentChargedFeeConfig.save(failOnError: true)

        return chargedFeeRecurrentChargedFeeConfig
    }
}

package com.asaas.service.recurrentchargedfeeconfig

import com.asaas.chargedfee.ChargedFeeType
import com.asaas.domain.chargedfee.ChargedFee
import com.asaas.domain.customer.Customer
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.recurrentchargedfeeconfig.RecurrentChargedFeeConfig
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.product.Cycle
import com.asaas.recurrentchargedfeeconfig.enums.RecurrentChargedFeeConfigStatus
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class RecurrentChargedFeeConfigService {

    def chargedFeeService
    def chargedFeeRecurrentChargedFeeConfigService
    def recurrentChargedFeeConfigForPlanService

    public RecurrentChargedFeeConfig save(Customer customer, Cycle cycle, BigDecimal value, ChargedFeeType chargedFeeType, Date nextDueDate) {
        RecurrentChargedFeeConfig recurrentChargedFeeConfig = new RecurrentChargedFeeConfig()
        recurrentChargedFeeConfig.customer = customer
        recurrentChargedFeeConfig.value = value
        recurrentChargedFeeConfig.type = chargedFeeType
        recurrentChargedFeeConfig.cycle = cycle
        recurrentChargedFeeConfig.nextDueDate = nextDueDate
        recurrentChargedFeeConfig.status = RecurrentChargedFeeConfigStatus.ACTIVE
        recurrentChargedFeeConfig.startDate = new Date().clearTime()
        recurrentChargedFeeConfig.save(failOnError: true)

        if (recurrentChargedFeeConfig.nextDueDate.clearTime() == recurrentChargedFeeConfig.startDate) {
            chargeFee(recurrentChargedFeeConfig, cycle, chargedFeeType)
        }

        return recurrentChargedFeeConfig
    }

    public void refund(Long recurrentChargedFeeConfigId, ChargedFeeType chargedFeeType) {
        RecurrentChargedFeeConfig recurrentChargedFeeConfig = RecurrentChargedFeeConfig.get(recurrentChargedFeeConfigId)
        chargedFeeService.refundLastRecurrentChargedFeeConfig(recurrentChargedFeeConfig, chargedFeeType)
    }

    public void setAsCancelled(Long recurrentChargedFeeConfigId) {
        RecurrentChargedFeeConfig recurrentChargedFeeConfig = RecurrentChargedFeeConfig.get(recurrentChargedFeeConfigId)
        recurrentChargedFeeConfig.status = RecurrentChargedFeeConfigStatus.CANCELLED
        recurrentChargedFeeConfig.save(failOnError: true)
    }

    public void chargeFee(RecurrentChargedFeeConfig recurrentChargedFeeConfig, Cycle cycle, ChargedFeeType type) {
        if (type.isAccountInactivity()) {
            chargeInactivityRecurrentFee(recurrentChargedFeeConfig, cycle, type)
            return
        }

        if (!recurrentChargedFeeConfigForPlanService.hasEnoughBalance(recurrentChargedFeeConfig.customerId, recurrentChargedFeeConfig.value)) throw new BusinessException("Seu saldo em conta é insuficiente para pagamento do plano.")

        ChargedFee chargedFee = chargedFeeService.saveRecurrentChargedFeeConfigForPlan(recurrentChargedFeeConfig, type)
        chargedFeeRecurrentChargedFeeConfigService.save(recurrentChargedFeeConfig, chargedFee)
        updateNextDueDate(recurrentChargedFeeConfig, cycle)
    }

    private void chargeInactivityRecurrentFee(RecurrentChargedFeeConfig recurrentChargedFeeConfig, Cycle cycle, ChargedFeeType type) {
        BigDecimal chargedFeeValue = recurrentChargedFeeConfig.value
        if (!type.isAccountInactivity()) throw new RuntimeException("Tipo de taxa inválido para cobrança de taxa de inatividade.")

        BigDecimal customerBalance = FinancialTransaction.getCustomerBalance(recurrentChargedFeeConfig.customerId)
        if (!customerBalance) {
            setAsCancelled(recurrentChargedFeeConfig.id)
            AsaasLogger.warn("RecurrentChargedFeeConfigService.chargeInactivityRecurrentFee() >> Cobrança de taxa de inatividade cancelada por falta de saldo [recurrentChargedFeeConfigId: ${recurrentChargedFeeConfig.id}]")
            return
        }

        Boolean cancelRecurrentChargedFeeAfterCharge = false
        if (customerBalance < chargedFeeValue)  chargedFeeValue = customerBalance

        BigDecimal remainingBalanceValue = customerBalance - chargedFeeValue
        if (remainingBalanceValue <= 0) cancelRecurrentChargedFeeAfterCharge = true

        ChargedFee chargedFee = chargedFeeService.saveAccountInactivityFee(recurrentChargedFeeConfig.customer, type, chargedFeeValue)
        chargedFeeRecurrentChargedFeeConfigService.save(recurrentChargedFeeConfig, chargedFee)
        updateNextDueDate(recurrentChargedFeeConfig, cycle)

        if (cancelRecurrentChargedFeeAfterCharge) setAsCancelled(recurrentChargedFeeConfig.id)
    }

    private void updateNextDueDate(RecurrentChargedFeeConfig recurrentChargedFeeConfig, Cycle cycle) {
        recurrentChargedFeeConfig.nextDueDate = CustomDateUtils.addCycle(new Date().clearTime(), cycle)
        recurrentChargedFeeConfig.save(failOnError: true)
    }
}

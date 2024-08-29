package com.asaas.service.bankslip

import com.asaas.customer.CustomerBankSlipBeneficiaryStatus
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoBatchFileItem
import com.asaas.domain.customer.CustomerBankSlipBeneficiary
import com.asaas.domain.holiday.Holiday
import com.asaas.domain.payment.Payment
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class BankSlipHealthCheckService {

    public Boolean pendingBeneficiaryRegisters() {
        final Integer toleranceSeconds = 120
        Date toleranceInstant = CustomDateUtils.sumSeconds(new Date(), -1 * toleranceSeconds)

        Boolean pendingBeneficiaryDetected = CustomerBankSlipBeneficiary.query("lastUpdated[lt]": toleranceInstant, "boletoBankId": Payment.ASAAS_ONLINE_BOLETO_BANK_ID, "status": CustomerBankSlipBeneficiaryStatus.PENDING, exists: true).get().asBoolean()

        if (pendingBeneficiaryDetected) return false

        return true
    }

    public Boolean checkBoletoOnlineRegistration(BoletoBank boletoBank) {
        try {
            if (isNotTimeToCheck()) {
                return true
            }

            Date tolerance = CustomDateUtils.sumMinutes(new Date(), -10)

            Integer boletoBatchFileItemCount = BoletoBatchFileItem.pendingCreateForOnlineRegistration(["lastUpdated[le]": tolerance, "boletoBankId": boletoBank.id]).count()

            if (boletoBatchFileItemCount) {
                AsaasLogger.warn("HealthCheckController.boletoOnlineRegistration >> boletos na fila : ${boletoBatchFileItemCount} [${boletoBank.bank.name}]")
            }

            Integer boletoCountToAlert = 5
            if (boletoBank.id == Payment.SMARTBANK_BOLETO_BANK_ID) boletoCountToAlert = 1

            if (boletoBatchFileItemCount < boletoCountToAlert) {
                return true
            }

            return false
        } catch (Exception exception) {
            AsaasLogger.error("HealthCheckController.boletoOnlineRegistration >> Problema no boletoOnlineRegistration ${boletoBank.id}", exception)
            return false
        }
    }

    public Boolean checkBoletoOnlineWriteOff(BoletoBank boletoBank) {
        try {
            if (isNotTimeToCheck()) {
                return true
            }

            Date tolerance = CustomDateUtils.sumMinutes(new Date(), -10)

            Integer boletoBatchFileDeletedItemCount = BoletoBatchFileItem.pendingDeleteIdsForOnlineRegistration(["lastUpdated[le]": tolerance, "boletoBankId": boletoBank.id]).count()

            if (boletoBatchFileDeletedItemCount) {
                AsaasLogger.warn("BankSlipHealthCheckService.checkBoletoOnlineWriteOff >> boletos na fila para baixa: ${boletoBatchFileDeletedItemCount} [${boletoBank.bank.name}]")
            }

            Integer boletoCountToAlert = 5
            if (boletoBank.id == Payment.SMARTBANK_BOLETO_BANK_ID) boletoCountToAlert = 1

            if (boletoBatchFileDeletedItemCount < boletoCountToAlert) {
                return true
            }

            return false
        } catch (Exception exception) {
            AsaasLogger.error("BankSlipHealthCheckService.checkBoletoOnlineWriteOff >> Erro ao checar fila de baixas de registro [boletoBankId: ${boletoBank.id}]", exception)
            return false
        }
    }

    private Boolean isNotTimeToCheck() {
        Date boletoHealthCheckHoursStart = CustomDateUtils.setTime(new Date(), 7, 00, 0)
        Date boletoHealthCheckHoursEnd = CustomDateUtils.setTime(new Date(), 22, 0, 0)

        if (Holiday.isHoliday(new Date())) {
            boletoHealthCheckHoursStart = CustomDateUtils.setTime(new Date(), 9, 0, 0)
            boletoHealthCheckHoursEnd = CustomDateUtils.setTime(new Date(), 20, 0, 0)
        }

        if (new Date() < boletoHealthCheckHoursStart || new Date() > boletoHealthCheckHoursEnd) {
            return true
        }

        return false
    }
}

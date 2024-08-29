package com.asaas.service.billbankslipinfo

import com.asaas.bill.BillBankSlipRegistrationInfoStatus
import com.asaas.bill.BillStatus
import com.asaas.domain.bill.Bill
import com.asaas.domain.bill.BillBankSlipRegistrationInfo
import com.asaas.environment.AsaasEnvironment
import com.asaas.integration.smartBank.manager.SmartBankBankSlipQueryManager
import com.asaas.linhadigitavel.LinhaDigitavelInfo
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class BillBankSlipRegistrationInfoService {

    def celcoinManagerService

    public BillBankSlipRegistrationInfo save(Bill bill, LinhaDigitavelInfo linhaDigitavelInfo) {
        BillBankSlipRegistrationInfo billBankSlipRegistrationInfo = new BillBankSlipRegistrationInfo(bill: bill, status: BillBankSlipRegistrationInfoStatus.PENDING)
        billBankSlipRegistrationInfo.save(failOnError: true)

        updateRegistrationInfoWithCelcoin(billBankSlipRegistrationInfo, linhaDigitavelInfo)

        return billBankSlipRegistrationInfo
    }

    public void queryRegistrationInfo() {
        Integer maxItems = 100

        Map pendingAndErrorItemsSearchMap = [
            "bill.scheduleDate": new Date().clearTime(),
            "bill.statusList": [BillStatus.PENDING, BillStatus.SCHEDULED, BillStatus.WAITING_RISK_AUTHORIZATION],
            "statusList": [BillBankSlipRegistrationInfoStatus.PENDING, BillBankSlipRegistrationInfoStatus.ERROR],
            "column": "id"
        ]

        List<Long> billBankSlipRegistrationInfoIdList = BillBankSlipRegistrationInfo.query(pendingAndErrorItemsSearchMap).list(max: maxItems)

        Calendar oneHourAgoCalendar = CustomDateUtils.getInstanceOfCalendar()
        oneHourAgoCalendar.add(Calendar.HOUR_OF_DAY, -1)

        Map notFoundItemsSearchMap = [
            "bill.scheduleDate": new Date().clearTime(),
            "bill.statusList": [BillStatus.PENDING, BillStatus.SCHEDULED, BillStatus.WAITING_RISK_AUTHORIZATION],
            "statusList": [BillBankSlipRegistrationInfoStatus.NOT_FOUND],
            "lastUpdated[lt]": oneHourAgoCalendar.getTime(),
            "column": "id"
        ]

        billBankSlipRegistrationInfoIdList.addAll( BillBankSlipRegistrationInfo.query(notFoundItemsSearchMap).list(max: maxItems))

        for (Long billBankSlipRegistrationInfoId in billBankSlipRegistrationInfoIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                BillBankSlipRegistrationInfo billBankSlipRegistrationInfo = BillBankSlipRegistrationInfo.get(billBankSlipRegistrationInfoId)

                LinhaDigitavelInfo linhaDigitavelInfo = LinhaDigitavelInfo.getInstance(billBankSlipRegistrationInfo.bill.linhaDigitavel)
                linhaDigitavelInfo.build()

                if (linhaDigitavelInfo.beneficiaryCpfCnpj) {
                    updateRegistrationInfoWithCelcoin(billBankSlipRegistrationInfo, linhaDigitavelInfo)
                } else {
                    updateRegistrationInfoWithSmartBank(billBankSlipRegistrationInfo)
                }
            })
        }
    }

    private void updateRegistrationInfoWithSmartBank(BillBankSlipRegistrationInfo billBankSlipRegistrationInfo) {
        AsaasLogger.info("BillBankSlipRegistrationInfoService.updateRegistrationInfoWithSmartBank >>> Utilizando Smart Bank para buscar dados de registro do boleto [id: ${billBankSlipRegistrationInfo.id}].")

        Map registrationInfoMap = queryRegistrationInfoInSmartBank(billBankSlipRegistrationInfo.bill.linhaDigitavel)

        billBankSlipRegistrationInfo.beneficiaryCpfCnpj = registrationInfoMap.beneficiaryCpfCnpj
        billBankSlipRegistrationInfo.beneficiaryName = registrationInfoMap.beneficiaryName
        billBankSlipRegistrationInfo.payerCpfCnpj = registrationInfoMap.payerCpfCnpj
        billBankSlipRegistrationInfo.payerName = registrationInfoMap.payerName
        billBankSlipRegistrationInfo.status = registrationInfoMap.status

        billBankSlipRegistrationInfo.save(failOnError: true)
    }

    private Map queryRegistrationInfoInSmartBank(String linhaDigitavel) {
        Map registrationInfoMap = [:]

        if (!AsaasEnvironment.isProduction()) {
            registrationInfoMap.status = BillBankSlipRegistrationInfoStatus.NOT_FOUND
            return registrationInfoMap
        }

        try {
            SmartBankBankSlipQueryManager queryManager = new SmartBankBankSlipQueryManager()
            queryManager.queryBankSlip(linhaDigitavel)

            if (queryManager.responseHttpStatus == HttpStatus.OK.value()) {
                registrationInfoMap.beneficiaryCpfCnpj = queryManager.response.beneficiaryCnpjCpf
                registrationInfoMap.beneficiaryName = queryManager.response.beneficiaryName ?: queryManager.response.beneficiaryCorporateName
                registrationInfoMap.payerCpfCnpj = queryManager.response.payerCnpjCpf
                registrationInfoMap.payerName = queryManager.response.payerName
                registrationInfoMap.status = BillBankSlipRegistrationInfoStatus.SUCCESSFUL
            } else if (queryManager.responseHttpStatus == HttpStatus.NOT_FOUND.value()) {
                registrationInfoMap.status = BillBankSlipRegistrationInfoStatus.NOT_FOUND
            } else {
                registrationInfoMap.status = BillBankSlipRegistrationInfoStatus.ERROR
            }
        } catch (Exception exception) {
            AsaasLogger.error("Não foi possível consultar linha digitável no SmartBank: [${linhaDigitavel}]", exception)
            registrationInfoMap.status = BillBankSlipRegistrationInfoStatus.ERROR
        }

        return registrationInfoMap
    }

    private void updateRegistrationInfoWithCelcoin(BillBankSlipRegistrationInfo billBankSlipRegistrationInfo, LinhaDigitavelInfo linhaDigitavelInfo) {
        try {
            if (linhaDigitavelInfo.beneficiaryCpfCnpj) {
                billBankSlipRegistrationInfo.beneficiaryCpfCnpj = linhaDigitavelInfo.beneficiaryCpfCnpj
                billBankSlipRegistrationInfo.beneficiaryName = linhaDigitavelInfo.beneficiaryName
                billBankSlipRegistrationInfo.payerCpfCnpj = linhaDigitavelInfo.payerCpfCnpj
                billBankSlipRegistrationInfo.payerName = linhaDigitavelInfo.payerName
                billBankSlipRegistrationInfo.status = BillBankSlipRegistrationInfoStatus.SUCCESSFUL
                billBankSlipRegistrationInfo.save(failOnError: true)
            }
        } catch (Exception exception) {
            AsaasLogger.error("Não foi possível consultar linha digitável na Celcoin: Bill [${billBankSlipRegistrationInfo.bill.id}]: ${billBankSlipRegistrationInfo.bill.linhaDigitavel}")
        }
    }

}

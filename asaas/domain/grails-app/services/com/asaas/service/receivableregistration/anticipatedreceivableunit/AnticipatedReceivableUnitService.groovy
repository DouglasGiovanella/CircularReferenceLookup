package com.asaas.service.receivableregistration.anticipatedreceivableunit

import com.asaas.domain.receivableunit.AnticipatedReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitItem
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.receivableregistration.ReceivableRegistrationEventQueueType
import com.asaas.receivableregistration.anticipatedreceivableunit.AnticipatedReceivableUnitStatus
import com.asaas.receivableregistration.synchronization.ReceivableRegistrationSynchronizationEventQueueName
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.receivableunit.ReceivableUnitItemCalculator
import com.asaas.receivableunit.ReceivableUnitItemStatus
import com.asaas.utils.CustomDateUtils
import grails.transaction.Transactional

@Transactional
class AnticipatedReceivableUnitService {

    def grailsApplication
    def receivableRegistrationEventQueueService
    def receivableRegistrationSynchronizationEventQueueService

    public void consolidate(AnticipatedReceivableUnit anticipatedReceivableUnit) {
        List<ReceivableUnitItem> itemList = ReceivableUnitItem.query([
            anticipatedReceivableUnitId: anticipatedReceivableUnit.id,
            "status[in]": ReceivableUnitItemStatus.listConstituted(),
            includeDeleted: true,
            disableSort: true
        ]).list()

        BigDecimal anticipatedValue = 0.00
        BigDecimal anticipatedNetValue = 0.00

        for (ReceivableUnitItem item : itemList) {
            anticipatedValue += ReceivableUnitItemCalculator.calculateAnticipatedValue(item)
            anticipatedNetValue += ReceivableUnitItemCalculator.calculateAnticipatedNetValue(item)
        }

        anticipatedReceivableUnit.value = anticipatedValue
        anticipatedReceivableUnit.netValue = anticipatedNetValue
        anticipatedReceivableUnit.save(failOnError: true, flush: true)
    }

    public AnticipatedReceivableUnit save(String customerCpfCnpj, PaymentArrangement paymentArrangement, Date estimatedCreditDate, Date anticipationCreditDate, String holderCpfCnpj) {
        AnticipatedReceivableUnit anticipatedReceivableUnit = new AnticipatedReceivableUnit()
        anticipatedReceivableUnit.customerCpfCnpj = customerCpfCnpj
        anticipatedReceivableUnit.holderCpfCnpj = holderCpfCnpj
        anticipatedReceivableUnit.anticipationCreditDate = anticipationCreditDate
        anticipatedReceivableUnit.status = AnticipatedReceivableUnitStatus.AWAITING_PROCESSING
        anticipatedReceivableUnit.estimatedCreditDate = estimatedCreditDate
        anticipatedReceivableUnit.paymentArrangement = paymentArrangement
        anticipatedReceivableUnit.save(failOnError: true)

        anticipatedReceivableUnit.externalIdentifier = "${grailsApplication.config.asaas.cnpj.substring(1)}-${anticipatedReceivableUnit.id}"
        anticipatedReceivableUnit.save(failOnError: true)

        receivableRegistrationSynchronizationEventQueueService.saveIfNecessary(anticipatedReceivableUnit.id, ReceivableRegistrationSynchronizationEventQueueName.ANTICIPATED_RECEIVABLE_UNIT)

        return anticipatedReceivableUnit
    }

    public void handleRegistration(AnticipatedReceivableUnit anticipatedReceivableUnit) {
        anticipatedReceivableUnit.status = AnticipatedReceivableUnitStatus.REGISTERED
        anticipatedReceivableUnit.operationType = CercOperationType.UPDATE
        anticipatedReceivableUnit.save(failOnError: true)

        saveCalculateAnticipatedReceivableUnitSettlementsEvent(anticipatedReceivableUnit)
        saveFinishAnticipatedReceivableUnitEvent(anticipatedReceivableUnit)
    }

    public void updateStatusAsAnticipated(AnticipatedReceivableUnit anticipatedReceivableUnit) {
        anticipatedReceivableUnit.status = AnticipatedReceivableUnitStatus.ANTICIPATED
        anticipatedReceivableUnit.save(failOnError: true)
    }

    public void updateOperationTypeAsFinish(Long id) {
        AnticipatedReceivableUnit anticipatedReceivableUnit = AnticipatedReceivableUnit.get(id)
        anticipatedReceivableUnit.operationType = CercOperationType.FINISH
        anticipatedReceivableUnit.save(failOnError: true)
    }

    public void updateStatusAsError(Long id) {
        AnticipatedReceivableUnit anticipatedReceivableUnit = AnticipatedReceivableUnit.get(id)
        anticipatedReceivableUnit.status = AnticipatedReceivableUnitStatus.ERROR
        anticipatedReceivableUnit.save(failOnError: true)
    }

    private void saveFinishAnticipatedReceivableUnitEvent(AnticipatedReceivableUnit anticipatedReceivableUnit) {
        Map eventData = [anticipatedReceivableUnitId: anticipatedReceivableUnit.id]
        receivableRegistrationEventQueueService.save(ReceivableRegistrationEventQueueType.FINISH_ANTICIPATED_RECEIVABLE_UNIT, eventData, eventData.encodeAsMD5())
    }

    private void saveCalculateAnticipatedReceivableUnitSettlementsEvent(AnticipatedReceivableUnit anticipatedReceivableUnit) {
        Long affectedReceivableUnitId = ReceivableUnitItem.query([column: "receivableUnit.id", anticipatedReceivableUnitId: anticipatedReceivableUnit.id]).get()
        Map eventData = [receivableUnitId: affectedReceivableUnitId, anticipatedDate: CustomDateUtils.formatDate(anticipatedReceivableUnit.anticipationCreditDate)]
        receivableRegistrationEventQueueService.save(ReceivableRegistrationEventQueueType.CALCULATE_ANTICIPATED_RECEIVABLE_UNIT_SETTLEMENTS, eventData, eventData.encodeAsMD5())
    }
}

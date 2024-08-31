package com.asaas.service.domicileinstitution

import com.asaas.customer.CustomerStatus
import com.asaas.domain.accountnumber.AccountNumber
import com.asaas.domain.customer.Customer
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationFile
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementConciliationItem
import com.asaas.domain.domicileinstitution.DomicileInstitutionSettlementFile
import com.asaas.domain.integration.cerc.CercContractualEffect
import com.asaas.domain.receivableUnitSettlement.ReceivableUnitSettlementScheduleReturnFileItem
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.domain.receivableunit.ReceivableUnitSettlement
import com.asaas.domicileinstitution.DomicileInstitutionSettlementConciliationItemStatus
import com.asaas.domicileinstitution.DomicileInstitutionSettlementOccurrence
import com.asaas.domicileinstitution.DomicileInstitutionSettlementType
import com.asaas.domicileinstitution.parser.DomicileInstitutionSettlementFileParser
import com.asaas.domicileinstitution.vo.DomicileInstitutionCentralizingGroupVO
import com.asaas.domicileinstitution.vo.DomicileInstitutionSellingPointGroupVO
import com.asaas.domicileinstitution.vo.DomicileInstitutionTransactionsSettlementGroupVO
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.codehaus.groovy.grails.plugins.orm.auditable.AuditLogListener

@Transactional
class DomicileInstitutionSettlementConciliationItemService {

    private final static String DATE_FORMAT_PATTERN = "yyyyMMdd"
    private final static String CREDIT_TYPE_NUMBER = "22"
    private final static String DEBIT_TYPE_NUMBER = "24"
    private final static String ANTICIPATION_TYPE_NUMBER = "32"
    private final static String ASAAS_CENTRALIZING_ACCOUNT_NUMBER = "1"
    private final static Integer ASAAS_CENTRALIZING_AGENCY_NUMBER = 1

    def grailsApplication

    public List<DomicileInstitutionSettlementConciliationItem> processItems(Long domicileInstitutionSettlementFileId, DomicileInstitutionTransactionsSettlementGroupVO domicileInstitutionTransactionsSettlementGroupVO) {
        List<DomicileInstitutionSettlementConciliationItem> domicileInstitutionSettlementConciliationItemList = []
        DomicileInstitutionSettlementFile domicileInstitutionSettlementFile = DomicileInstitutionSettlementFile.load(domicileInstitutionSettlementFileId)

        for (DomicileInstitutionCentralizingGroupVO centralizingGroupVO : domicileInstitutionTransactionsSettlementGroupVO.domicileInstitutionCentralizingGroupVOList) {
            for (DomicileInstitutionSellingPointGroupVO sellingPointGroupVO : centralizingGroupVO.domicileInstitutionSellingPointGroupVOList) {
                Boolean hasDomicileInstitutionSettlementConciliationItem = DomicileInstitutionSettlementConciliationItem.query([exists: true, "settlementExternalIdentifier": sellingPointGroupVO.settlementExternalIdentifier]).get().asBoolean()
                if (hasDomicileInstitutionSettlementConciliationItem) continue

                DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem = new DomicileInstitutionSettlementConciliationItem()
                domicileInstitutionSettlementConciliationItem.status = DomicileInstitutionSettlementConciliationItemStatus.PENDING
                domicileInstitutionSettlementConciliationItem.settlementFile = domicileInstitutionSettlementFile
                domicileInstitutionSettlementConciliationItem.mainParticipantIspb = domicileInstitutionTransactionsSettlementGroupVO.mainParticipantIdentifier
                domicileInstitutionSettlementConciliationItem.managedParticipantIspb = domicileInstitutionTransactionsSettlementGroupVO.managedParticipantIdentifier
                domicileInstitutionSettlementConciliationItem.debtorIspb = domicileInstitutionTransactionsSettlementGroupVO.debtorIspb
                domicileInstitutionSettlementConciliationItem.settlementExternalIdentifier = sellingPointGroupVO.settlementExternalIdentifier
                domicileInstitutionSettlementConciliationItem.type = domicileInstitutionSettlementFile.type
                domicileInstitutionSettlementConciliationItem.value = sellingPointGroupVO.paymentValue
                domicileInstitutionSettlementConciliationItem.scheduleDate = sellingPointGroupVO.paymentDate
                if (domicileInstitutionSettlementFile.type.isDebit()) {
                    domicileInstitutionSettlementConciliationItem.debitCardBrand = DomicileInstitutionSettlementFileParser.getDebitArrangementByCode(sellingPointGroupVO.institutorCodePaymentArrangement)
                } else {
                    domicileInstitutionSettlementConciliationItem.creditCardBrand = DomicileInstitutionSettlementFileParser.getCreditArrangementByCode(sellingPointGroupVO.institutorCodePaymentArrangement)
                }
                domicileInstitutionSettlementConciliationItem.arrangementCode = sellingPointGroupVO.institutorCodePaymentArrangement
                domicileInstitutionSettlementConciliationItem.centralizingPersonType = centralizingGroupVO.centralizingPersonType
                domicileInstitutionSettlementConciliationItem.centralizingCpfCnpj = centralizingGroupVO.centralizingCpfCnpj
                domicileInstitutionSettlementConciliationItem.centralizingCode = centralizingGroupVO.centralizingCode
                domicileInstitutionSettlementConciliationItem.centralizingAgency = centralizingGroupVO.centralizingAgency
                domicileInstitutionSettlementConciliationItem.centralizingPaymentAccountNumber = centralizingGroupVO.centralizingAccount ?: centralizingGroupVO.centralizingPaymentAccountNumber
                domicileInstitutionSettlementConciliationItem.controlNumber = buildControlNumber(domicileInstitutionSettlementFile.type)
                domicileInstitutionSettlementConciliationItem = doConciliation(domicileInstitutionSettlementConciliationItem.scheduleDate, domicileInstitutionSettlementFile.type, domicileInstitutionSettlementConciliationItem)

                domicileInstitutionSettlementConciliationItemList.add(domicileInstitutionSettlementConciliationItem)
            }
        }

        AuditLogListener.withoutAuditLogWhenExecutedByJobs ({
            DomicileInstitutionSettlementConciliationItem.saveAll(domicileInstitutionSettlementConciliationItemList)
        })

        return domicileInstitutionSettlementConciliationItemList
    }

    public void updateItemOccurrence(Long conciliationItemId, DomicileInstitutionSettlementOccurrence occurrence) {
        DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem = DomicileInstitutionSettlementConciliationItem.get(conciliationItemId)

        domicileInstitutionSettlementConciliationItem.occurrence = occurrence
        domicileInstitutionSettlementConciliationItem.save(failOnError: true)
    }

    public List<DomicileInstitutionSettlementConciliationItem> setNewControlNumberAndOcurrence(List<DomicileInstitutionSettlementConciliationItem> domicileInstitutionSettlementConciliationItemList, DomicileInstitutionSettlementType domicileInstitutionSettlementType, Boolean isScheduleReprocess) {
        for (DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem : domicileInstitutionSettlementConciliationItemList) {
            if (domicileInstitutionSettlementConciliationItem.occurrence.isScheduled() && !isScheduleReprocess) {
                domicileInstitutionSettlementConciliationItem.occurrence = DomicileInstitutionSettlementOccurrence.SUCCESS
            }

            domicileInstitutionSettlementConciliationItem.controlNumber = buildControlNumber(domicileInstitutionSettlementType)
        }

        return domicileInstitutionSettlementConciliationItemList
    }

    public List<DomicileInstitutionSettlementConciliationItem> updateConciliationInfo(List<DomicileInstitutionSettlementConciliationItem> domicileInstitutionSettlementConciliationItemList, DomicileInstitutionSettlementConciliationFile domicileInstitutionSettlementConciliationFile, Boolean isScheduleReprocess) {
        for (DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem : domicileInstitutionSettlementConciliationItemList) {
            domicileInstitutionSettlementConciliationItem.conciliationDate = new Date()
            domicileInstitutionSettlementConciliationItem.conciliationFile = domicileInstitutionSettlementConciliationFile
            if (isScheduleReprocess) domicileInstitutionSettlementConciliationItem.status = DomicileInstitutionSettlementConciliationItemStatus.PENDING
            domicileInstitutionSettlementConciliationItem.save(failOnError: true)
        }

        return domicileInstitutionSettlementConciliationItemList
    }

    public String buildControlNumber(DomicileInstitutionSettlementType type) {
        StringBuilder conciliationFileName = new StringBuilder()
        conciliationFileName.append(CustomDateUtils.fromDate(new Date(), DomicileInstitutionSettlementConciliationItemService.DATE_FORMAT_PATTERN))

        if (type.isAnticipation()) {
            conciliationFileName.append(DomicileInstitutionSettlementConciliationItemService.ANTICIPATION_TYPE_NUMBER)
        } else if (type.isCredit()) {
            conciliationFileName.append(DomicileInstitutionSettlementConciliationItemService.CREDIT_TYPE_NUMBER)
        } else if (type.isDebit()) {
            conciliationFileName.append(DomicileInstitutionSettlementConciliationItemService.DEBIT_TYPE_NUMBER)
        }
        conciliationFileName.append(UUID.randomUUID().toString().replace("-", ""))

        return conciliationFileName.substring(0, 20).toUpperCase()
    }

    private DomicileInstitutionSettlementConciliationItem doConciliation(Date scheduleDate, DomicileInstitutionSettlementType type, DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem) {
        Boolean isAsaasDebtorIspb = domicileInstitutionSettlementConciliationItem.debtorIspb == grailsApplication.config.asaas.ispb
        ReceivableUnitSettlement receivableUnitSettlement
        if (isAsaasDebtorIspb) {
            ReceivableUnitSettlementScheduleReturnFileItem receivableUnitSettlementScheduleReturnFileItem = ReceivableUnitSettlementScheduleReturnFileItem.query(settlementExternalIdentifier: domicileInstitutionSettlementConciliationItem.settlementExternalIdentifier).get()

            if (receivableUnitSettlementScheduleReturnFileItem) {
                Long receivableUnitId = ReceivableUnit.query([column: "id", externalIdentifier: receivableUnitSettlementScheduleReturnFileItem.receivableUnitExternalIdentifier]).get()
                Map receivableUnitSettlementQueryParams = [
                    receivableUnitId: receivableUnitId,
                    settlementDate: domicileInstitutionSettlementConciliationItem.scheduleDate
                ]

                if (receivableUnitSettlementScheduleReturnFileItem.contractualEffectProtocol) {
                    Long cercContractualEffectId = CercContractualEffect.query([column: "id", protocol: receivableUnitSettlementScheduleReturnFileItem.contractualEffectProtocol]).get()
                    receivableUnitSettlementQueryParams.contractualEffectId = cercContractualEffectId
                }

                receivableUnitSettlement = ReceivableUnitSettlement.query(receivableUnitSettlementQueryParams).get()

                domicileInstitutionSettlementConciliationItem.receivableUnitSettlement = receivableUnitSettlement
            } else {
                AsaasLogger.warn("DomicileInstitutionSettlementConciliationItemService.save - Erro na conciliação de liquidação da instituição domicílio, retorno não encontrado. [settlementExternalIdentifier: ${domicileInstitutionSettlementConciliationItem.settlementExternalIdentifier}, occurrence: ${domicileInstitutionSettlementConciliationItem.occurrence}]")
            }
        }

        Boolean isCreditSchedule = isCreditSchedule(scheduleDate, type)
        domicileInstitutionSettlementConciliationItem.occurrence = getConciliationOccurrence(domicileInstitutionSettlementConciliationItem, receivableUnitSettlement, isAsaasDebtorIspb, isCreditSchedule)
        if (isCreditSchedule) domicileInstitutionSettlementConciliationItem.scheduleOccurrence = domicileInstitutionSettlementConciliationItem.occurrence

        return domicileInstitutionSettlementConciliationItem
    }

    private DomicileInstitutionSettlementOccurrence getConciliationOccurrence(DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem, ReceivableUnitSettlement receivableUnitSettlement, Boolean isAsaasDebtorIspb, Boolean isCreditSchedule) {
        if (isAsaasDebtorIspb) {
            validateInternalReceivableUnit(domicileInstitutionSettlementConciliationItem, receivableUnitSettlement)
        } else {
            DomicileInstitutionSettlementOccurrence occurrence = getOccurrenceByAccountNumber(domicileInstitutionSettlementConciliationItem)
            if (!occurrence.isSuccess()) return occurrence
        }

        if (isCreditSchedule) return DomicileInstitutionSettlementOccurrence.SCHEDULED

        return DomicileInstitutionSettlementOccurrence.SUCCESS
    }

    private void validateInternalReceivableUnit(DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem, ReceivableUnitSettlement receivableUnitSettlement) {
        DomicileInstitutionSettlementOccurrence occurrence = DomicileInstitutionSettlementOccurrence.SUCCESS
        if (!receivableUnitSettlement) {
            occurrence = DomicileInstitutionSettlementOccurrence.INTERNAL_ERROR
        } else if (domicileInstitutionSettlementConciliationItem.value != receivableUnitSettlement.settledValue) {
            occurrence = DomicileInstitutionSettlementOccurrence.VALUE_DIFFERENCE
        } else if (domicileInstitutionSettlementConciliationItem.scheduleDate != receivableUnitSettlement.settlementDate) {
            occurrence = DomicileInstitutionSettlementOccurrence.INVALID_PAYMENT_DATE
        } else if (domicileInstitutionSettlementConciliationItem.centralizingCpfCnpj != receivableUnitSettlement.bankAccount.cpfCnpj) {
            occurrence = DomicileInstitutionSettlementOccurrence.INVALID_CPFCNPJ
        } else if (Utils.toLong(domicileInstitutionSettlementConciliationItem.centralizingPaymentAccountNumber) != Utils.toLong(receivableUnitSettlement.bankAccount.accountNumber)) {
            occurrence = DomicileInstitutionSettlementOccurrence.INVALID_BANK_ACCOUNT
        }

        if (!occurrence.isSuccess()) {
            AsaasLogger.warn("DomicileInstitutionSettlementConciliationItemService.save - Erro na conciliação de liquidação da instituição domicílio. [settlementExternalIdentifier: ${domicileInstitutionSettlementConciliationItem.settlementExternalIdentifier}, occurrence: ${occurrence}]")
        }
    }

    private DomicileInstitutionSettlementOccurrence getOccurrenceByAccountNumber(DomicileInstitutionSettlementConciliationItem domicileInstitutionSettlementConciliationItem) {
        Boolean isAsaasCentralizingCpfCnpj = Utils.toLong(domicileInstitutionSettlementConciliationItem.centralizingCpfCnpj) == Utils.toLong(grailsApplication.config.asaas.cnpj)
        if (isAsaasCentralizingCpfCnpj) {
            Boolean isAsaasCentralizingPaymentAccountNumber = domicileInstitutionSettlementConciliationItem.centralizingPaymentAccountNumber == DomicileInstitutionSettlementConciliationItemService.ASAAS_CENTRALIZING_ACCOUNT_NUMBER
            if (!isAsaasCentralizingPaymentAccountNumber) return DomicileInstitutionSettlementOccurrence.INVALID_BANK_ACCOUNT
        } else {
            List<Map> accountInfoMapList = AccountNumber.query([columnList: ["account", "accountDigit", "customer"], customerCpfCnpj: domicileInstitutionSettlementConciliationItem.centralizingCpfCnpj]).list([readOnly: true])
            if (!accountInfoMapList) return DomicileInstitutionSettlementOccurrence.INVALID_CPFCNPJ

            List<Map> accountWithDigitMapList = accountInfoMapList.collect { [accountWithDigit: String.valueOf("${it.account}${it.accountDigit}"), customer: it.customer] }

            Map validAccountInfo = accountWithDigitMapList.find { it.accountWithDigit == domicileInstitutionSettlementConciliationItem.centralizingPaymentAccountNumber }
            if (!validAccountInfo) return DomicileInstitutionSettlementOccurrence.INVALID_BANK_ACCOUNT

            Customer customer = validAccountInfo.customer
            if (isBlockedBankAccount(customer)) return DomicileInstitutionSettlementOccurrence.BLOCKED_BANK_ACCOUNT
        }

        if (domicileInstitutionSettlementConciliationItem.centralizingAgency) {
            Boolean isAsaasCentralizingAgencyNumber = Utils.toInteger(domicileInstitutionSettlementConciliationItem.centralizingAgency) == DomicileInstitutionSettlementConciliationItemService.ASAAS_CENTRALIZING_AGENCY_NUMBER
            if (!isAsaasCentralizingAgencyNumber) return DomicileInstitutionSettlementOccurrence.INVALID_AGENCY
        }

        return DomicileInstitutionSettlementOccurrence.SUCCESS
    }

    private Boolean isBlockedBankAccount(Customer customer) {
        if (customer.status.isInactive()) return true
        if (customer.customerRegisterStatus.generalApproval.isRejected()) return true
        if (!customer.hadGeneralApproval()) return true

        return false
    }

    private Boolean isCreditSchedule(Date scheduleDate, DomicileInstitutionSettlementType type) {
        if (!type.isCredit()) return false
        if (!scheduleDate) return false
        if (scheduleDate.clone().clearTime() <= new Date().clearTime()) return false

        return true
    }
}

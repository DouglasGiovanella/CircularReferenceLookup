package com.asaas.service.integration.cerc

import com.asaas.customer.CustomerStatus
import com.asaas.customer.PersonType
import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.cerc.CercCompany
import com.asaas.domain.integration.cerc.contractualeffect.CercFidcContractualEffectGuarantee
import com.asaas.domain.receivableunit.ReceivableUnit
import com.asaas.exception.BusinessException
import com.asaas.integration.cerc.enums.CercOperationType
import com.asaas.integration.cerc.enums.company.CercCompanyErrorReason
import com.asaas.integration.cerc.enums.company.CercCompanyStatus
import com.asaas.integration.cerc.enums.company.CercCompanyType
import com.asaas.integration.cerc.enums.company.CercSyncStatus
import com.asaas.integration.cerc.enums.contractualeffect.CercContractualEffectGuaranteeStatus
import com.asaas.log.AsaasLogger
import com.asaas.receivableregistration.receivableunit.ReceivableUnitStatus
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CercCompanyService {

    def asyncActionService
    def receivableUnitItemService

    public void processCreateOrUpdateCercCompany() {
        final Integer maxItemsPerCycle = 100

        for (Map asyncActionData : asyncActionService.listPendingCreateOrUpdateCercCompany(maxItemsPerCycle)) {
            Boolean hasError = false
            Utils.withNewTransactionAndRollbackOnError({
                CercCompany cercCompany = CercCompany.query([cpfCnpj: asyncActionData.cpfCnpj]).get()
                if (cercCompany) {
                    updateIfNecessary(cercCompany)
                } else {
                    save(asyncActionData.cpfCnpj)
                }

                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    if (Utils.isLock(exception)) {
                        AsaasLogger.warn("CercCompanyService.processCreateOrUpdateCercCompany >> Ocorreu um lock ao criar ou atualizar company, asyncActionId: [${asyncActionData.asyncActionId}]", exception)
                        return
                    }

                    AsaasLogger.error("CercCompanyService.processCreateOrUpdateCercCompany >> Erro ao criar ou atualizar company, asyncActionId: [${asyncActionData.asyncActionId}]", exception)
                    hasError = true
                }
            ])

            if (hasError) asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId)
        }
    }

    public CercCompany saveIfNecessary(String cpfCnpj) {
        CercCompany cercCompany = CercCompany.query([cpfCnpj: cpfCnpj]).get()
        if (cercCompany) return cercCompany

        return save(cpfCnpj)
    }

    public void updateIfNecessary(CercCompany cercCompany) {
        changeCustomerIfNecessary(cercCompany)

        Customer currentCustomer = cercCompany.customer
        String oldCpfCnpj = cercCompany.cpfCnpj
        String newCpfCnpj = currentCustomer.cpfCnpj

        Boolean customerChangedCpfCnpj = oldCpfCnpj != newCpfCnpj
        if (!customerChangedCpfCnpj) {
            cercCompany = build(currentCustomer, cercCompany)
            if (cercCompany.isDirty()) setAsAwaitingSync(cercCompany)
            return
        }

        Customer oldCustomer = Customer.query([cpfCnpj: oldCpfCnpj]).get()
        if (!oldCustomer) receivableUnitItemService.changeItemsCpfCnpj(oldCpfCnpj, newCpfCnpj)

        if (oldCustomer && oldCustomer.id != currentCustomer.id) {
            cercCompany = build(oldCustomer, cercCompany)
        } else {
            cercCompany.status = CercCompanyStatus.INACTIVE
        }

        if (cercCompany.isDirty()) setAsAwaitingSync(cercCompany)

        CercCompany companyWithTheNewCpfCnpj = saveIfNecessary(newCpfCnpj)
        Boolean mustNotUpdateCompanyData = companyWithTheNewCpfCnpj.status.isActive()
        if (mustNotUpdateCompanyData) return

        companyWithTheNewCpfCnpj = build(currentCustomer, companyWithTheNewCpfCnpj)
        if (cercCompany.isDirty()) setAsAwaitingSync(companyWithTheNewCpfCnpj)
    }

    public CercCompany build(Customer customer, CercCompany cercCompany) {
        String companyNewName = customer.isNaturalPerson() ? customer.name : customer.company
        if (companyNewName) cercCompany.name = companyNewName

        cercCompany.tradingName = customer.isNaturalPerson() ? customer.name : customer.tradingName
        cercCompany.cpfCnpj = customer.cpfCnpj

        cercCompany.email = customer.email
        if (!cercCompany.validate(["email"])) cercCompany.email = null

        cercCompany.phone = buildPhoneNumber(customer.phone)
        if (!cercCompany.validate(["phone"])) cercCompany.phone = null

        cercCompany.mobilePhone = buildPhoneNumber(customer.mobilePhone)
        if (!cercCompany.validate(["mobilePhone"])) cercCompany.mobilePhone = null

        cercCompany.postalCode = customer.postalCode
        cercCompany.address = customer.address
        cercCompany.addressNumber = customer.addressNumber
        cercCompany.complement = customer.complement
        cercCompany.province = customer.province
        cercCompany.city = customer.cityString
        cercCompany.state = customer.state
        cercCompany.mcc = customer.getMcc()
        cercCompany.status = getCompanyStatus(customer)
        cercCompany.type = CercCompanyType.COMPANY
        cercCompany.customer = customer
        cercCompany.lastBuildInfoUpdated = new Date()

        return cercCompany
    }

    public void registerLastBuildInfoUpdate(CercCompany cercCompany) {
        cercCompany.lastBuildInfoUpdated = new Date()
        cercCompany.save(failOnError: true, flush: true)
    }

    public void setAsSynced(CercCompany company) {
        if (company.operationType.isCreate()) {
            company.estimatedActivationDate = calculateEstimatedActivationDate()
        } else {
            Boolean hasReceivableUnitAwaitingCompanyActivate = ReceivableUnit.query([customerCpfCnpj: company.cpfCnpj, operationType: CercOperationType.CREATE, status: ReceivableUnitStatus.AWAITING_COMPANY_ACTIVATE, exists: true]).get().asBoolean()
            if (hasReceivableUnitAwaitingCompanyActivate) company.estimatedActivationDate = calculateEstimatedActivationDate()
        }

        company.operationType = CercOperationType.UPDATE
        company.lastSyncAttempt = null
        company.syncAttempts = 0
        company.syncStatus = CercSyncStatus.SYNCED
        company.save(failOnError: true, flush: true)
    }

    public void setAsError(CercCompany company) {
        company.syncStatus = CercSyncStatus.ERROR
        company.save(failOnError: true, flush: true)
    }

    public void registerLastSync(CercCompany company) {
        company.lastSyncAttempt = new Date()
        company.syncAttempts = (company.syncAttempts ?: 0) + 1
        company.save(failOnError: true, flush: true)
    }

    public void setAsAwaitingSync(CercCompany company) {
        company.syncStatus = CercSyncStatus.AWAITING_SYNC
        company.save(failOnError: true, flush: true)
    }

    public void updateEstimatedActivationDateToNull(CercCompany company) {
        company.estimatedActivationDate = null
        company.save(failOnError: true)
    }

    public void processSynchronizationErrors(CercCompany company, List<Map> errorList) {
        if (!errorList) return

        List<CercCompanyErrorReason> errorReasonList = []
        for (Map error in errorList) {
            CercCompanyErrorReason reason = CercCompanyErrorReason.findByCode(error.codigo.toString())
            errorReasonList.add(reason)
        }

        if (errorReasonList.contains(CercCompanyErrorReason.INVALID_OPERATION_FOR_CURRENT_REGISTRATION)) {
            if (company.operationType.isCreate()) {
                company.operationType = CercOperationType.UPDATE
            } else if (company.operationType.isUpdate()) {
                company.operationType = CercOperationType.CREATE
            }
        }

        if (!company.isDirty()) throw new RuntimeException("cercCompanyService.processSynchronizationErrors >> nenhuma solução foi aplicada ao estabelecimento")

        company.save(failOnError: true)
    }

    public CercCompanyStatus getCompanyStatusByCpfCnpj(String cpfCnpj) {
        List<Customer> customerList = Customer.query([cpfCnpj: cpfCnpj]).list()
        Boolean hasOtherCustomerIsActive = customerList.any { !it.getIsBlocked() && !it.accountDisabled() && !it.deleted }
        if (hasOtherCustomerIsActive) return CercCompanyStatus.ACTIVE

        Boolean hasActiveCercFidcContractualEffectGuarantee = CercFidcContractualEffectGuarantee.query([exists: true, fidcContractualEffectCustomerCpfCnpj: cpfCnpj, status: CercContractualEffectGuaranteeStatus.AWAITING_SETTLEMENT, disableSort: true]).get().asBoolean()
        if (hasActiveCercFidcContractualEffectGuarantee) return CercCompanyStatus.ACTIVE

        Boolean customerHasActiveReceivableUnit = ReceivableUnit.active([exists: true, "estimatedCreditDate[ge]": new Date(), customerCpfCnpj: cpfCnpj]).get().asBoolean()
        if (customerHasActiveReceivableUnit) return CercCompanyStatus.SUSPENDED

        return CercCompanyStatus.INACTIVE
    }

    public String buildPhoneNumber(String phoneNumber) {
        String phoneWithoutAreaCode = PhoneNumberUtils.removeBrazilAreaCode(phoneNumber)
        if (!phoneWithoutAreaCode) return ""

        List<String> splitAreaCodeAndNumber = PhoneNumberUtils.splitAreaCodeAndNumber(phoneWithoutAreaCode)
        if (splitAreaCodeAndNumber[0] == "00") return ""

        final Integer numberSize = splitAreaCodeAndNumber[1].size()
        final Integer numberSizeLimit = 10
        final Integer numberSizeAllowedWithoutAreaCode = 9
        if (numberSize >= numberSizeLimit) return splitAreaCodeAndNumber[0] + splitAreaCodeAndNumber[1].substring(numberSize - numberSizeAllowedWithoutAreaCode, numberSize)

        return splitAreaCodeAndNumber[0] + splitAreaCodeAndNumber[1]
    }

    private Date calculateEstimatedActivationDate() {
        final Date now = new Date()
        final Date timeLimitOfDay = CustomDateUtils.setTime(now, 19, 0, 0)
        Integer daysToActivation = (now >= timeLimitOfDay) ? 3 : 2
        return CustomDateUtils.addBusinessDays(now, daysToActivation)
    }

    private CercCompany save(String cpfCnpj) {
        Customer customer = getCustomerToCreateCompany(cpfCnpj)
        if (!customer) throw new BusinessException("CercCompanyService.save >> Não foi possível encontrar um cliente com o CPF/CNPJ [${cpfCnpj}]")

        CercCompany cercCompanyBuilt = build(customer, new CercCompany())
        cercCompanyBuilt.save(failOnError: true, flush: true)

        return cercCompanyBuilt
    }

    private void changeCustomerIfNecessary(CercCompany cercCompany) {
        Customer currentCustomer = cercCompany.customer
        Customer latestCustomer = getLatestActiveCustomer(cercCompany.cpfCnpj)
        if (!latestCustomer) return
        if (latestCustomer.id == currentCustomer.id) return

        AsaasLogger.info("CercCompanyService.changeCustomerIfNecessary >> estabelecimento teve o customer atualizado de ${currentCustomer.id} para ${latestCustomer.id}")

        cercCompany.customer = latestCustomer
        cercCompany.save(failOnError: true)
    }

    private Customer getCustomerToCreateCompany(String cpfCnpj) {
        Customer customerActive = getLatestActiveCustomer(cpfCnpj)
        if (customerActive) return customerActive

        Map search = getDefaultParametersToSearchCustomer(cpfCnpj)
        return Customer.query(search).get()
    }

    private Customer getLatestActiveCustomer(String cpfCnpj) {
        Map search = [
            status: CustomerStatus.ACTIVE
        ] + getDefaultParametersToSearchCustomer(cpfCnpj)

        return Customer.query(search).get()
    }

    private CercCompanyStatus getCompanyStatus(Customer customer) {
        Boolean customerIsActive = !customer.getIsBlocked() && !customer.accountDisabled() && !customer.deleted
        if (customerIsActive) return CercCompanyStatus.ACTIVE

        List<Customer> customerList = Customer.query([cpfCnpj: customer.cpfCnpj]).list()
        Boolean hasOtherCustomerIsActive = customerList.any { !it.getIsBlocked() && !it.accountDisabled() && !it.deleted }
        if (hasOtherCustomerIsActive) return CercCompanyStatus.ACTIVE

        Boolean hasActiveCercFidcContractualEffectGuarantee = CercFidcContractualEffectGuarantee.query([exists: true, fidcContractualEffectCustomerCpfCnpj: customer.cpfCnpj, status: CercContractualEffectGuaranteeStatus.AWAITING_SETTLEMENT, disableSort: true]).get().asBoolean()
        if (hasActiveCercFidcContractualEffectGuarantee) return CercCompanyStatus.ACTIVE

        Boolean customerHasActiveReceivableUnit = ReceivableUnit.active([exists: true, "estimatedCreditDate[ge]": new Date(), customerCpfCnpj: customer.cpfCnpj]).get().asBoolean()
        if (customerHasActiveReceivableUnit) return CercCompanyStatus.SUSPENDED

        return CercCompanyStatus.INACTIVE
    }

    private Map getDefaultParametersToSearchCustomer(String cpfCnpj) {
        PersonType personType = CpfCnpjUtils.getPersonType(cpfCnpj)
        if (!personType) throw new BusinessException("CercCompanyService.getDefaultParametersToSearchCustomer >> Não foi possível encontrar personType [${cpfCnpj}]")

        Map search = [
            cpfCnpj: cpfCnpj,
            order: "desc",
            sort: "id"
        ]

        if (personType.isJuridica()) {
            search."company[ne]" = ""
        } else {
            search."name[ne]" = ""
        }

        return search
    }
}

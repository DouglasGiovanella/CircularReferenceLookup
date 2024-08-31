package com.asaas.service.customer

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.customer.CustomerSegment
import com.asaas.customer.CustomerStatus
import com.asaas.customer.PersonType
import com.asaas.customerbusinessopportunityanalysis.CustomerBusinessOpportunityAnalysisStatus
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerEconomicActivity
import com.asaas.domain.customerbusinessopportunityanalysis.CustomerBusinessOpportunityAnalysis
import com.asaas.domain.economicactivity.EconomicActivity
import com.asaas.exception.BusinessException
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CustomerBusinessOpportunityAnalysisService {

    def customerStageEventService

    private final Integer MAX_ITEMS_PER_CYCLE = 100

    public void createAll() {
        List<Long> customerIdList = listCustomersAvailableToBusinessOpportunity()

        Utils.forEachWithFlushSession(customerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(customerId)

                save(customer)
            }, [logErrorMessage: "CustomerBusinessOpportunityAnalysisService.createAll >> Erro ao criar análises de oportunidade de negócio para o cliente [$customerId]",
                onError: { Exception exception -> throw exception }])
        })
    }

    public void setAsPhoneCallExpiredInBatch() {
        final Integer daysToChangeStatus = 10

        Date dateCreatedLimit = CustomDateUtils.sumDays(new Date(), -daysToChangeStatus)
        List<Long> idList = CustomerBusinessOpportunityAnalysis.query([column: "id", status: CustomerBusinessOpportunityAnalysisStatus.AWAITING_PHONE_CALL, "dateCreated[le]": CustomDateUtils.setTimeToEndOfDay(dateCreatedLimit)]).list(max: MAX_ITEMS_PER_CYCLE)

        Utils.forEachWithFlushSession(idList, 50, { Long id ->
            Utils.withNewTransactionAndRollbackOnError({
                update(id, CustomerBusinessOpportunityAnalysisStatus.PHONE_CALL_EXPIRED)
            }, [logErrorMessage: "CustomerBusinessOpportunityAnalysisService.setAsPhoneCallExpiredInBatch >> Erro ao mudar status das análises de oportunidade de negócio para 'expirado' no cliente [$id]"])
        })
    }

    public void setAsNoContactInBatch() {
        final Integer daysToChangeStatus = 5

        Date lastUpdatedLimit = CustomDateUtils.sumDays(new Date(), -daysToChangeStatus)
        List<Long> idList = CustomerBusinessOpportunityAnalysis.query([column: "id", status: CustomerBusinessOpportunityAnalysisStatus.PHONE_CALL_EXPIRED, "lastUpdated[le]": lastUpdatedLimit]).list(max: MAX_ITEMS_PER_CYCLE)

        Utils.forEachWithFlushSession(idList, 50, { Long id ->
            Utils.withNewTransactionAndRollbackOnError({
                update(id, CustomerBusinessOpportunityAnalysisStatus.NO_CONTACT)
            }, [logErrorMessage: "CustomerBusinessOpportunityAnalysisService.setAsNoContactInBatch >> Erro ao mudar status das análises de oportunidade de negócio para 'sem contato' no cliente [$id]"])
        })
    }

    public BusinessValidation canBeBusinessOpportunity(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        EconomicActivity economicActivity = customer.getMainEconomicActivity()
        if (!economicActivity?.cnaeCode) {
            businessValidation.addError("customerBusinessOpportunityAnalysis.validation.error.cnaeCodeNotInformed")
            return businessValidation
        }

        if (!AsaasApplicationHolder.config.customerBusinessOpportunityAnalysis.allowedCnaeCodeList.code.contains(economicActivity.cnaeCode)) {
            businessValidation.addError("customerBusinessOpportunityAnalysis.validation.error.cnaeCodeNotFound")
            return businessValidation
        }

        return businessValidation
    }

    public void toggleOpportunity(Long customerId, Boolean isOpportunity) {
        Long id = CustomerBusinessOpportunityAnalysis.query([column: "id", customerId: customerId]).get()

        CustomerBusinessOpportunityAnalysisStatus status = isOpportunity ? CustomerBusinessOpportunityAnalysisStatus.IS_OPPORTUNITY : CustomerBusinessOpportunityAnalysisStatus.NOT_OPPORTUNITY

        if (!id) {
            Customer customer = Customer.read(customerId)
            id = save(customer)
        }

        update(id, status)
    }

    private void update(Long id, CustomerBusinessOpportunityAnalysisStatus status) {
        CustomerBusinessOpportunityAnalysis customerBusinessOpportunityAnalysis = CustomerBusinessOpportunityAnalysis.get(id)

        customerBusinessOpportunityAnalysis.status = status
        customerBusinessOpportunityAnalysis.save(failOnError: true)
    }

    private Long save(Customer customer) {
        BusinessValidation businessValidation = canBeBusinessOpportunity(customer)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        CustomerBusinessOpportunityAnalysis customerBusinessOpportunityAnalysis = new CustomerBusinessOpportunityAnalysis()
        customerBusinessOpportunityAnalysis.customer = customer
        customerBusinessOpportunityAnalysis.save(failOnError: true)

        customerStageEventService.cancelPhoneCallCustomerStageEvents(customer.currentCustomerStage)

        return customerBusinessOpportunityAnalysis.id
    }

    private List<Long> listCustomersAvailableToBusinessOpportunity() {
        List<Long> economicActivityIdList = EconomicActivity.query([
            column: "id",
            "cnaeCode[in]": AsaasApplicationHolder.config.customerBusinessOpportunityAnalysis.allowedCnaeCodeList.code,
            disableSort: true
        ]).list()

        final Date dateLimit = CustomDateUtils.sumDays(new Date().clearTime(), -1)
        return CustomerEconomicActivity.createCriteria().list(max: MAX_ITEMS_PER_CYCLE) {
            projections {
                property "customer.id"
            }

            createAlias("customer", "customer")
            createAlias("customer.customerRegisterStatus", "customerRegisterStatus")

            eq("isMainActivity", true)
            "in"("economicActivity.id", economicActivityIdList)

            gt("customer.dateCreated", dateLimit)
            eq("customer.personType", PersonType.JURIDICA)
            "in"("customer.segment", [CustomerSegment.SMALL, CustomerSegment.BUSINESS])
            not { "in"("customer.status", [CustomerStatus.BLOCKED, CustomerStatus.DISABLED]) }

            eq("customerRegisterStatus.generalApproval", GeneralApprovalStatus.APPROVED)

            notExists CustomerBusinessOpportunityAnalysis.where {
                setAlias("customerBusinessOpportunityAnalysis")

                eqProperty("customerBusinessOpportunityAnalysis.customer.id", "this.customer.id")
            }.id()
        }
    }
}

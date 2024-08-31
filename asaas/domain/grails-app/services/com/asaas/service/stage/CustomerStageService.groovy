package com.asaas.service.stage

import com.asaas.customer.CustomerStatus
import com.asaas.domain.api.ApiRequestLog
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerstage.CustomerStage
import com.asaas.domain.payment.Payment
import com.asaas.domain.stage.Stage
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.stage.StageCode
import com.asaas.treasuredata.TreasureDataEventType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class CustomerStageService {

    def asaasSegmentioService

    def customerStageEventService

    def sessionFactory

    def treasureDataService

    def grailsApplication

    public CustomerStage save(Customer customer, StageCode stageCode) {
        if (customer.getCurrentStageCode() == stageCode) return customer.currentCustomerStage

        if (!Stage.findWhere(code: stageCode)) {
            AsaasLogger.error("Stage with code [${stageCode}] not found!")
            return null
        }

        if (customer.currentCustomerStage) {
            customerStageEventService.cancelCustomerStageEvents(customer.currentCustomerStage)
        }

        CustomerStage customerStage = new CustomerStage(customer: customer, stage: Stage.findWhere(code: stageCode))
        customerStage.save(failOnError: true)

        customer.currentCustomerStage = customerStage
        customer.save(failOnError: true)

        customerStageEventService.createImmediateCustomerEventsForStage(customerStage)

        asaasSegmentioService.identify(customer.id, ["stage": customer.getCurrentStageCode().toString()])

        return customerStage
    }

    public void processActivation(Customer customer) {
        StageCode currentStageCode = customer.getCurrentStageCode()
        Boolean shouldChangeToActivated = [StageCode.OPPORTUNITY, StageCode.LEAD].contains(currentStageCode)

        if (!shouldChangeToActivated) return

        save(customer, StageCode.ACTIVATED)
    }

    public void processCashInReceived(Customer customer) {
        StageCode currentStageCode = customer.getCurrentStageCode()

        switch (currentStageCode) {
            case StageCode.LEAD:
            case StageCode.OPPORTUNITY:
            case StageCode.FORGOTTEN:
            case StageCode.ACTIVATED:
                treasureDataService.track(customer.accountManager?.user, customer, TreasureDataEventType.CONVERSION)
                save(customer, StageCode.CONVERTED)
                break
            case StageCode.LOST:
                save(customer, StageCode.RECOVERED)
                break
            case StageCode.RECOVERED:
                save(customer, StageCode.RETAINED)
                break
        }
    }

    public void processEventExpired(Customer customer) {
        StageCode currentStageCode = customer.getCurrentStageCode()
        Boolean shouldChangeToRetained = [StageCode.CONVERTED, StageCode.RECOVERED].contains(currentStageCode)

        if (!shouldChangeToRetained) return

        save(customer, StageCode.RETAINED)
    }

    public void processTransferConfirmed(Customer customer) {
        StageCode currentStageCode = customer.getCurrentStageCode()
        Boolean shouldChangeToRetained = [StageCode.CONVERTED].contains(currentStageCode)

        if (!shouldChangeToRetained) return

        save(customer, StageCode.RETAINED)
    }

    public void processAccountDisabled(Customer customer) {
        save(customer, StageCode.WONT_USE)
    }

    public List<Long> processForgottenCustomers() {
        List<Long> forgottenCustomerIdList = listForgottenCustomerIds()

        Utils.forEachWithFlushSession(forgottenCustomerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.get(customerId)
                save(customer, StageCode.FORGOTTEN)
            }, [logErrorMessage: "CustomerStageService.processForgottenCustomers -> Erro ao salvar estágio do cliente [${customerId}]."])
        })

        return forgottenCustomerIdList
    }

    public List<Long> processLostCustomers() {
        List<Long> lostCustomerIdList = listLostCustomerIds()
        List<Long> customerIdToRemoveList = []

        Utils.forEachWithFlushSession(lostCustomerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Date dateLimit = CustomDateUtils.sumDays(new Date(), -35)
                Boolean hasReceivedPayment = Payment.query([exists: true, customerId: customerId, "paymentDate[ge]": dateLimit]).get().asBoolean()
                if (hasReceivedPayment) {
                    customerIdToRemoveList.add(customerId)
                    return
                }

                if (hasApiRequestRecently(customerId)) {
                    customerIdToRemoveList.add(customerId)
                    return
                }

                Customer customer = Customer.get(customerId)
                save(customer, StageCode.LOST)
                customerIdToRemoveList.add(customerId)
            }, [logErrorMessage: "CustomerStageService.processLostCustomers -> Erro ao salvar estágio do cliente [${customerId}]."])
        })

        lostCustomerIdList -= customerIdToRemoveList

        return lostCustomerIdList
    }

    public List<Long> processLostOccasionalCustomers() {
        List<Long> lostOccasionalCustomerIdList = listLostOccasionalCustomerIds()
        List<Long> customerIdToRemoveList = []

        Utils.forEachWithFlushSession(lostOccasionalCustomerIdList, 50, { Long customerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Date dateLimit = CustomDateUtils.sumDays(new Date(), -35)
                Boolean hasReceivedPayment = Payment.query([exists: true, customerId: customerId, "paymentDate[ge]": dateLimit]).get().asBoolean()
                if (hasReceivedPayment) {
                    customerIdToRemoveList.add(customerId)
                    return
                }

                Customer customer = Customer.get(customerId)
                save(customer, StageCode.LOST_OCCASIONAL)
                customerIdToRemoveList.add(customerId)
            }, [logErrorMessage: "CustomerStageService.processLostOccasionalCustomers -> Erro ao salvar estágio do cliente [${customerId}]."])
        })

        lostOccasionalCustomerIdList -= customerIdToRemoveList

        return lostOccasionalCustomerIdList
    }

    public Date findConvertedDate(Customer customer) {
        String hql = "from CustomerStage cs where cs.customer = :customer and cs.stage.code = :converted"

        CustomerStage customerStage = CustomerStage.executeQuery(hql, [customer: customer, converted: StageCode.CONVERTED])[0]

        return customerStage ? customerStage.dateCreated : null
    }

    public Date findConvertedDateWithFallback(Customer customer) {
        Date customerConvertedDate = findConvertedDate(customer)
        if (customerConvertedDate) return  customerConvertedDate

        customerConvertedDate = Payment.query([column: "paymentDate", customer: customer, statusList: PaymentStatus.hasBeenConfirmedStatusList(), "paymentDate[isNotNull]": true, sort: "paymentDate", order: "asc"]).get()
        if (customerConvertedDate) updateOldConverted(customer, customerConvertedDate)

        return customerConvertedDate
    }

    private CustomerStage updateOldConverted(Customer customer, Date convertedDate) {
        CustomerStage customerStage = new CustomerStage()
        customerStage.customer = customer
        customerStage.stage = Stage.findWhere(code: StageCode.CONVERTED)
        customerStage.save(failOnError: true)
        customerStage.dateCreated = convertedDate
        customerStage.save(failOnError: true)

        return  customerStage
    }

    private List<Long> listForgottenCustomerIds() {
        StringBuilder builder = new StringBuilder()
        builder.append(" SELECT cs.customer_id from customer_stage cs ")
        builder.append(" INNER JOIN customer c ON cs.id = c.current_customer_stage_id ")
        builder.append(" WHERE cs.stage_id = :activatedId ")
        builder.append(" AND cs.deleted = false ")
        builder.append(" AND cs.date_created < :dateLimit ")
        builder.append(" AND c.status not in (:inactiveStatusList) ")
        builder.append(" AND c.deleted = 0 ")
        builder.append(" AND c.cpf_cnpj <> :asaasCnpj")

        def query = sessionFactory.currentSession.createSQLQuery(builder.toString())

        Date dateLimit = CustomDateUtils.sumDays(new Date(), -49)
        query.setDate("dateLimit", dateLimit)

        Long activatedId = Stage.query([column: "id", stageCode: StageCode.ACTIVATED]).get()
        query.setLong("activatedId", activatedId)

        query.setParameterList("inactiveStatusList", CustomerStatus.inactive().collect { it.toString() })
        query.setString("asaasCnpj", grailsApplication.config.asaas.cnpj.substring(1))

        List<Long> customerIdList = query.list().collect { Utils.toLong(it) }

        return customerIdList
    }

    private List<Long> listLostCustomerIds() {
        List<Long> stageIdList = Stage.query([column: "id", "stageCode[in]": [StageCode.CONVERTED, StageCode.RETAINED]]).list()
        Long lostOccasionalId = Stage.query([column: "id", stageCode: StageCode.LOST_OCCASIONAL]).get()

        StringBuilder builder = new StringBuilder()
        builder.append(" SELECT distinct cs1.customer_id FROM customer_stage cs1 ")
        builder.append(" INNER JOIN customer c ON cs1.id = c.current_customer_stage_id ")
        builder.append(" LEFT JOIN user u ON (c.id = u.customer_id AND u.last_login >= :minActivityDate) ")
        builder.append(" LEFT JOIN customer_stage cs2 ON (cs2.customer_id = c.id and cs2.stage_id = :lostOccasionalId) ")
        builder.append(" WHERE cs1.stage_id in (:stageIdList) ")
        builder.append(" AND cs1.deleted = 0 ")
        builder.append(" AND c.status not in (:inactiveStatusList) ")
        builder.append(" AND c.deleted = 0 ")
        builder.append(" AND c.cpf_cnpj <> :asaasCnpj")
        builder.append(" AND u.id is null ")
        builder.append(" AND cs2.id is null ")

        def query = sessionFactory.currentSession.createSQLQuery(builder.toString())

        Date minActivityDate = CustomDateUtils.sumMonths(new Date(), -3)
        query.setDate("minActivityDate", minActivityDate)

        query.setLong("lostOccasionalId", lostOccasionalId)
        query.setParameterList("stageIdList", stageIdList)
        query.setParameterList("inactiveStatusList", CustomerStatus.inactive().collect { it.toString() })
        query.setString("asaasCnpj", grailsApplication.config.asaas.cnpj.substring(1))

        List<Long> customerIdList = query.list().collect { Utils.toLong(it) }

        return customerIdList
    }

    private List<Long> listLostOccasionalCustomerIds() {
        List<Long> stageIdList = Stage.query([column: "id", "stageCode[in]": [StageCode.CONVERTED, StageCode.RETAINED]]).list()
        Long lostOccasionalId = Stage.query([column: "id", stageCode: StageCode.LOST_OCCASIONAL]).get()

        StringBuilder builder = new StringBuilder()
        builder.append(" SELECT DISTINCT cs.customer_id FROM customer_stage cs ")
        builder.append(" INNER JOIN customer c ON (cs.id = c.current_customer_stage_id) ")
        builder.append(" INNER JOIN customer_stage cs2 ON (cs2.customer_id = c.id and cs2.stage_id = :lostOccasionalId) ")
        builder.append(" WHERE cs.stage_id in (:stageIdList) ")
        builder.append(" AND cs.deleted = 0 ")
        builder.append(" AND c.status not in (:inactiveStatusList) ")
        builder.append(" AND c.deleted = 0 ")
        builder.append(" AND c.cpf_cnpj <> :asaasCnpj")

        def query = sessionFactory.currentSession.createSQLQuery(builder.toString())

        query.setLong("lostOccasionalId", lostOccasionalId)
        query.setParameterList("stageIdList", stageIdList)
        query.setParameterList("inactiveStatusList", CustomerStatus.inactive().collect { it.toString() })
        query.setString("asaasCnpj", grailsApplication.config.asaas.cnpj.substring(1))

        List<Long> customerIdList = query.list().collect { Utils.toLong(it) }

        return customerIdList
    }

    private Boolean hasApiRequestRecently(Long customerId) {
        final Date recentActivityLimitDate = CustomDateUtils.sumMonths(new Date(), -3)

        Boolean hasApiRequestOnPeriod = ApiRequestLog.query([exists: true, customerId: customerId, "dateCreated[ge]": recentActivityLimitDate]).get().asBoolean()
        return hasApiRequestOnPeriod
    }
}

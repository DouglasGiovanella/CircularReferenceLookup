package com.asaas.service.customerplan

import com.asaas.billinginfo.BillingType
import com.asaas.chargedfee.ChargedFeeStatus
import com.asaas.chargedfee.ChargedFeeType
import com.asaas.creditcard.CreditCardTransactionOriginInterface
import com.asaas.customerplan.adapters.CreateMembershipAdapter
import com.asaas.customerplan.adapters.CreditCardAdapter
import com.asaas.customerplan.adapters.CreditCardValidationAdapter
import com.asaas.customerplan.adapters.CustomerPlanPaymentMethodAdapter
import com.asaas.customerplan.enums.CustomerPlanPaymentSource
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.payment.Payment
import com.asaas.domain.plan.Plan
import com.asaas.domain.subscription.Subscription
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentStatus
import com.asaas.plan.CustomerPlanName
import com.asaas.product.Cycle
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.StringUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import grails.validation.ValidationException
import org.hibernate.SQLQuery
import org.hibernate.transform.AliasToEntityMapResultTransformer

@Transactional
class CustomerPlanMembershipService {

    private static final Integer PERIOD_IN_DAYS_TO_REFUND = 7

    def customerAccountService
    def customerPlanService
    def grailsApplication
    def paymentRefundService
    def recurrentChargedFeeConfigService
    def sessionFactory
    def subscriptionService

    public void create(CreateMembershipAdapter createMembershipAdapter, CustomerPlanPaymentSource customerPlanPaymentSource) {
        if (customerPlanPaymentSource.isCreditCard()) {
            createSubscription(createMembershipAdapter)
        } else {
            recurrentChargedFeeConfigService.save(
                createMembershipAdapter.customer,
                createMembershipAdapter.plan.cycle,
                createMembershipAdapter.plan.value,
                ChargedFeeType.CONTRACTED_CUSTOMER_PLAN,
                createMembershipAdapter.nextDueDate)
        }
    }

    public void cancel(CustomerPlanPaymentMethodAdapter customerPlanPaymentMethod) {
        if (customerPlanPaymentMethod.paymentSource.isCreditCard()) {
            deleteSubscription(customerPlanPaymentMethod.id)
        } else {
            recurrentChargedFeeConfigService.setAsCancelled(customerPlanPaymentMethod.id)
        }
    }

    public void deleteSubscription(Long currentPlanSubscriptionId) {
        Long asaasProviderId = Long.valueOf(grailsApplication.config.asaas.customer.id)
        subscriptionService.delete(currentPlanSubscriptionId, asaasProviderId, true)
    }

    public Boolean shouldPlanBeRefunded(Date dateCreated) {
        return (new Date().clearTime() <= CustomDateUtils.sumDays(dateCreated.clearTime(), PERIOD_IN_DAYS_TO_REFUND))
    }

    public void refund(CustomerPlanPaymentMethodAdapter customerPlanPaymentMethod) {
        if (customerPlanPaymentMethod.paymentSource.isCreditCard()) {
            Long paymentId = Payment.receivedOrConfirmed([column: "id", subscriptionId: customerPlanPaymentMethod.id]).get()
            if (paymentId) paymentRefundService.refund(paymentId, [refundOnAcquirer: true])
        } else {
            recurrentChargedFeeConfigService.refund(customerPlanPaymentMethod.id, ChargedFeeType.CONTRACTED_CUSTOMER_PLAN)
        }

        customerPlanService.setDefaultPlanForCustomerIfNecessary(customerPlanPaymentMethod.customer)
    }

    public CreditCardAdapter validateCreditCardInfo(CreditCardAdapter creditCardInfo) {
        CreditCardValidationAdapter creditCardValidation = creditCardInfo.validate()

        if (!creditCardValidation.isSuccess) {
            AsaasLogger.warn("CustomerPlanMembershipService.buildCreditCardInfo() >> Dados de cartão informados são inválidos: ${creditCardValidation.validationMessages.toString()}")
            throw new BusinessException(StringUtils.formatFormularyValidationMessagesToHtml(creditCardValidation.validationMessages))
        }

        return creditCardInfo
    }

    public Boolean isPlanPaymentReceivedOrConfirmed (String status) {
        PaymentStatus paymentStatus = PaymentStatus.convert(status)
        if (paymentStatus) return paymentStatus.isReceivedOrConfirmed()

        ChargedFeeStatus chargedFeeStatus = ChargedFeeStatus.convert(status)
        if (chargedFeeStatus) return chargedFeeStatus.isDebited()

        return false
    }

    public Map buildPlanPaymentHistory(Customer customer, Integer limitPerPage, Integer currentPage) {
        List<Map> planPaymentAndRecurrentChargedFeeHistory = []
        List<Long> planIdList = Plan.query([column: "id", "name[in]": CustomerPlanName.getAllCustomerPlanName()]).list()

        List<Map> planPaymentHistory = getPlanPaymentHistory(customer.id, planIdList)
        if (planPaymentHistory.length) {
            for (Map planPaymentHistoryItem : planPaymentHistory) {
                Map item = [:]
                item.name = planPaymentHistoryItem.name
                item.transactionId = planPaymentHistoryItem.transactionId
                item.cycle = planPaymentHistoryItem.cycle
                item.date = planPaymentHistoryItem.date
                item.status = planPaymentHistoryItem.status
                item.value = planPaymentHistoryItem.value
                item.paymentMethod = "Cartão de crédito ${planPaymentHistoryItem.creditCardBrand} final ${planPaymentHistoryItem.lastDigits}"
                item.planName = Utils.getMessageProperty("customerPlan.${planPaymentHistoryItem.planName}.label")

                planPaymentAndRecurrentChargedFeeHistory.add(item)
            }
        }

        List<Map> planRecurrentChargedFeeConfigHistory = getPlanRecurrentChargedFeeConfigHistoryIfExists(customer.id)
        if (planRecurrentChargedFeeConfigHistory.length) {
            for (Map planRecurrentChargedFeeConfigHistoryItem : planRecurrentChargedFeeConfigHistory) {
                Map item = [:]
                item.name = planRecurrentChargedFeeConfigHistoryItem.name
                item.transactionId = planRecurrentChargedFeeConfigHistoryItem.transactionId
                item.cycle = planRecurrentChargedFeeConfigHistoryItem.cycle
                item.date = planRecurrentChargedFeeConfigHistoryItem.date
                item.status = planRecurrentChargedFeeConfigHistoryItem.status
                item.value = planRecurrentChargedFeeConfigHistoryItem.value
                item.paymentMethod = 'Saldo em conta'
                item.planName = Utils.getMessageProperty("customerPlan.${planRecurrentChargedFeeConfigHistoryItem.planName}.label")

                planPaymentAndRecurrentChargedFeeHistory.add(item)
            }
        }

        planPaymentAndRecurrentChargedFeeHistory.sort({itemA, itemB -> itemB.date.compareTo(itemA.date)})

        Map lastPlanPaymentHistory = planPaymentAndRecurrentChargedFeeHistory[0]
        Map planSubscriptionHistory = buildPlanSubscriptionHistory(customer, planPaymentAndRecurrentChargedFeeHistory, lastPlanPaymentHistory)

        List<Map> nonPendingPlanPayments = planPaymentAndRecurrentChargedFeeHistory.findAll{ Map item -> PaymentStatus.PENDING.toString() != item.status }

        Integer totalCount = nonPendingPlanPayments.size()
        Integer page = currentPage + limitPerPage
        Integer toIndex = page < totalCount ? page : totalCount

        return [
            lastPlanPaymentHistory: lastPlanPaymentHistory,
            subscriptionProps: planSubscriptionHistory,
            list: nonPendingPlanPayments.subList(currentPage, toIndex),
            totalCount: totalCount
        ]
    }

    private void createSubscription(CreateMembershipAdapter createMembershipAdapter) {
        Date maxNextDueDate = CustomDateUtils.addMonths(new Date(), 1).clearTime()
        if (createMembershipAdapter.nextDueDate.clearTime().after(maxNextDueDate)) throw new BusinessException("Data de vencimento informada para assinatura é inválida.")

        CustomerAccount customerAccount = customerAccountService.saveOrUpdateAsaasCustomerAccountFromProvider(createMembershipAdapter.customer)
        Map creditCardHolderInfo = getCreditCardHolderInfo(createMembershipAdapter.customer)
        Map creditCardTransactionOriginInfoMap = [:]
        creditCardTransactionOriginInfoMap.remoteIp = createMembershipAdapter.creditCardInfo.remoteIp
        creditCardTransactionOriginInfoMap.originInterface = CreditCardTransactionOriginInterface.CUSTOMER_PLAN

        Map subscriptionParams = [:]
        subscriptionParams.planId = createMembershipAdapter.plan.id
        subscriptionParams.billingType = BillingType.MUNDIPAGG_CIELO
        subscriptionParams.creditCard = createMembershipAdapter.creditCardInfo.toMap()
        subscriptionParams.creditCardHolderInfo = creditCardHolderInfo
        subscriptionParams.subscriptionCycle = Cycle.MONTHLY
        subscriptionParams.creditCardTransactionOriginInfo = creditCardTransactionOriginInfoMap
        subscriptionParams.nextDueDate = createMembershipAdapter.nextDueDate

        Subscription subscription = subscriptionService.save(customerAccount, subscriptionParams, true)

        if (subscription.hasErrors()) {
            throw new ValidationException("CustomerPlanMembershipService.createSubscription() -> Erro ao criar subscription", subscription.errors)
        }
    }

    private Map getCreditCardHolderInfo(Customer customer) {
        return [
            name: customer.name,
            email: customer.email,
            cpfCnpj: customer.cpfCnpj,
            phone: customer.phone,
            phoneDDD: PhoneNumberUtils.splitAreaCodeAndNumber(customer.phone)[0],
            mobilePhone: customer.mobilePhone,
            mobilePhoneDDD: PhoneNumberUtils.splitAreaCodeAndNumber(customer.mobilePhone)[0],
            postalCode: customer.postalCode,
            address: customer.address,
            addressNumber: customer.addressNumber,
            complement: customer.complement,
            province: customer.province,
            city: customer.city,
            uf: customer.city.state,
        ]
    }

    private Map buildPlanSubscriptionHistory(Customer customer, List<Map> planPaymentHistory,  Map lastPlanPaymentHistory) {
        if (!planPaymentHistory) return

        CustomerPlanPaymentMethodAdapter customerPlanPaymentMethod = new CustomerPlanPaymentMethodAdapter(customer)
        Map props = [:]
        props.isActive = customerPlanPaymentMethod.id.asBoolean()
        props.status = props.isActive ? "Ativo" : "Cancelado"

        Map lastConfirmedPlanPaymentHistory = planPaymentHistory.find{ Map item -> isPlanPaymentReceivedOrConfirmed(item.status) }
        if (lastConfirmedPlanPaymentHistory) {
            props.lastPayedDate = lastConfirmedPlanPaymentHistory.date
            props.lastPayedValue = lastConfirmedPlanPaymentHistory.value
        }

        if (lastPlanPaymentHistory) {
            String currentPaymentMethod = customerPlanPaymentMethod.paymentSource?.isAccountBalance() ? "Saldo em conta" : lastPlanPaymentHistory.paymentMethod
            props.currentPaymentMethod = currentPaymentMethod
            props.currentPaymentValue = lastPlanPaymentHistory.value
            props.currentPaymentDate = lastPlanPaymentHistory.date
        }

        return props
    }

    private List<Map> getPlanRecurrentChargedFeeConfigHistoryIfExists(Long customerId) {
        String sql = '''
        SELECT rcfc.id as transactionId, c.name, rcfc.cycle, rcfc.value, cf.last_updated as date, p.name as planName, cf.status FROM recurrent_charged_fee_config as rcfc
        INNER JOIN charged_fee_recurrent_charged_fee_config as cfrcf ON rcfc.id = cfrcf.recurrent_charged_fee_config_id
        INNER JOIN charged_fee as cf ON cf.id = cfrcf.charged_fee_id
        INNER JOIN customer_plan as cp ON cp.customer_id = rcfc.customer_id
        INNER JOIN customer as c ON c.id = cp.customer_id
        INNER JOIN plan as p ON p.id = cp.plan_id
        WHERE rcfc.type = :type
        AND rcfc.cycle = :cycle
        AND rcfc.customer_id = :customerId
        '''

        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(sql)
        sqlQuery.setLong("customerId", customerId)
        sqlQuery.setString("cycle", Cycle.MONTHLY.toString())
        sqlQuery.setString("type", ChargedFeeType.CONTRACTED_CUSTOMER_PLAN.toString())
        sqlQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE)

        return sqlQuery.list()
    }

    private List<Map> getPlanPaymentHistory(Long customerId, List<Long> planIdList) {
        String sql = '''
            SELECT cci.last_digits as lastDigits, cci.brand as creditCardBrand, p.status as status, p.credit_card_tid as transactionId, p.date_created as date,
            s.cycle as cycle, s.value as value, plan.name as planName, ca.name as name FROM subscription as s
            INNER join customer_account as ca ON s.customer_account_id=ca.id
            INNER join plan ON s.plan_id=plan.id
            INNER JOIN subscription_payment as sp ON s.id=sp.subscription_id
            INNER JOIN payment as p ON sp.payment_id=p.id
            INNER JOIN credit_card_info as cci ON cci.billing_info_id = s.billing_info_id
            WHERE plan.id in (:planIdList) AND plan.id=s.plan_id AND sp.subscription_id=s.id
            AND p.id=sp.payment_id AND ca.id=s.customer_account_id
            AND ca.customer_id = :customerId AND p.deleted=false
        '''

        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(sql)
        sqlQuery.setLong("customerId", customerId)
        sqlQuery.setParameterList("planIdList", planIdList)
        sqlQuery.setResultTransformer(AliasToEntityMapResultTransformer.INSTANCE)

        return sqlQuery.list()
    }
}

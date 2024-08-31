package com.asaas.service.customer.commercialinfo

import com.asaas.authorizationdevice.AuthorizationDeviceType
import com.asaas.customer.commercialinfoexpiration.CommercialInfoExpirationVO
import com.asaas.customercommercialinfoexpiration.CustomerCommercialInfoExpirationType
import com.asaas.domain.authorizationdevice.AuthorizationDevice
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessActivity
import com.asaas.domain.customer.CustomerUpdateRequest
import com.asaas.domain.customer.commercialinfo.CustomerCommercialInfoExpiration
import com.asaas.domain.customer.knowyourcustomerinfo.KnowYourCustomerInfo
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.user.User
import com.asaas.environment.AsaasEnvironment
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerCommercialInfoExpirationService {

    private static final Integer MANDATORY_INFO_EXPIRATION_DAYS = 30

    private static final Integer FIRST_MANDATORY_INFO_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS = 5

    private static final Integer PERIODIC_EXPIRATION_DAYS = 365

    private static final Integer FIRST_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS = 45

    private static final Integer SECOND_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS = 30

    private static final Integer THIRD_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS = 15

    private static final Integer FOURTH_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS = 10

    private static final Integer FIFTH_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS = 5

    private static final Integer DAILY_NOTIFICATION = 1

    def customerAlertNotificationService
    def customerCommercialInfoExpirationCacheService
    def mobilePushNotificationService
    def modulePermissionService

    public void save(Customer customer) {
        if (!canSchedule(customer)) return

        CustomerCommercialInfoExpiration customerCommercialInfoExpiration = CustomerCommercialInfoExpiration.query([customerId: customer.id]).get()
        if (!customerCommercialInfoExpiration) {
            customerCommercialInfoExpiration = new CustomerCommercialInfoExpiration()
            customerCommercialInfoExpiration.customer = customer
        }

        customerCommercialInfoExpiration.type = buildExpirationType(customerCommercialInfoExpiration)
        customerCommercialInfoExpiration.scheduledDate = buildScheduleDate(customerCommercialInfoExpiration.type, customer.dateCreated)
        customerCommercialInfoExpiration.hasBeenNotified = false
        customerCommercialInfoExpiration.save(failOnError: true)

        customerCommercialInfoExpirationCacheService.evictIsExpired(customer.id)
    }

    public CommercialInfoExpirationVO buildCommercialInfoExpirationAlertData(User currentUser) {
        CommercialInfoExpirationVO commercialInfoExpirationVO = new CommercialInfoExpirationVO()

        Map searchParams = [columnList: ["scheduledDate", "type"], customerId: currentUser.customer.id]
        Map commercialInfoExpirationData = CustomerCommercialInfoExpiration.query(searchParams).get()
        if (!commercialInfoExpirationData) return commercialInfoExpirationVO

        Date scheduledDate = commercialInfoExpirationData.scheduledDate
        CustomerCommercialInfoExpirationType type = commercialInfoExpirationData.type
        if (!scheduledDate) return commercialInfoExpirationVO

        if (!type) return commercialInfoExpirationVO

        if (shouldShowMandatoryInfoExpirationAlert(scheduledDate, type)) {
            return buildMandatoryExpirationAlertData(commercialInfoExpirationVO, currentUser, scheduledDate)
        }

        if (shouldShowPeriodicExpirationAlert(scheduledDate, type)) {
            return buildPeriodicExpirationAlertData(commercialInfoExpirationVO, currentUser, scheduledDate)
        }

        return commercialInfoExpirationVO
    }

    public List<Long> processNotification() {
        final Integer maxItemsPerCycle = 500
        final Integer daysToAlert = 5
        final Date dateToAlert = CustomDateUtils.sumDays(new Date().clearTime(), daysToAlert)

        Map searchParams = [
            column: "id",
            "scheduledDate": dateToAlert,
            hasBeenNotified: false,
            ignoreCustomer: true,
            disableSort: true
        ]

        List<Long> customerCommercialInfoIdList = CustomerCommercialInfoExpiration.query(searchParams).list(max: maxItemsPerCycle)
        if (!customerCommercialInfoIdList) return []

        Utils.forEachWithFlushSession(customerCommercialInfoIdList, 100, { Long customerCommercialInfoId ->
            Utils.withNewTransactionAndRollbackOnError({
                CustomerCommercialInfoExpiration customerCommercialInfoExpiration = CustomerCommercialInfoExpiration.get(customerCommercialInfoId)
                notify(customerCommercialInfoExpiration)
            }, [
                logErrorMessage: "CustomerCommercialInfoExpirationService.processNotification >> Erro ao notificar cliente sobre expiração dos Dados Comerciais [${customerCommercialInfoId}]."
            ])
        })

        return customerCommercialInfoIdList
    }

    public Boolean shouldShowExpirationAlert(Date scheduledDate, CustomerCommercialInfoExpirationType type) {
        if (shouldShowMandatoryInfoExpirationAlert(scheduledDate, type)) return true

        if (shouldShowPeriodicExpirationAlert(scheduledDate, type)) return true

        return false
    }

    private CommercialInfoExpirationVO buildMandatoryExpirationAlertData(CommercialInfoExpirationVO commercialInfoExpirationVO, User currentUser, Date scheduledDate) {
        commercialInfoExpirationVO.shouldShowMandatoryInfoExpirationAlert = true
        commercialInfoExpirationVO.daysToShowNextAlert = DAILY_NOTIFICATION
        commercialInfoExpirationVO = fillCommonExpirationAlertData(commercialInfoExpirationVO, currentUser, scheduledDate)

        return commercialInfoExpirationVO
    }

    private CommercialInfoExpirationVO buildPeriodicExpirationAlertData(CommercialInfoExpirationVO commercialInfoExpirationVO, User currentUser, Date scheduledDate) {
        CustomerUpdateRequest customerUpdateRequest = CustomerUpdateRequest.findLatestIfIsPendingOrDeniedOrAwaitingActionAuthorization(currentUser.customer)
        commercialInfoExpirationVO.shouldShowPeriodicExpirationAlert = true
        commercialInfoExpirationVO.baseCustomer = customerUpdateRequest ?: currentUser.customer
        commercialInfoExpirationVO.customerBusinessActivity = CustomerBusinessActivity.query([customer: currentUser.customer]).get()
        commercialInfoExpirationVO.lastBusinessActivity = customerUpdateRequest?.businessActivity ?: commercialInfoExpirationVO.customerBusinessActivity?.businessActivity
        commercialInfoExpirationVO.incomeRange = customerUpdateRequest?.incomeRange ?: KnowYourCustomerInfo.findIncomeRange(currentUser.customer)
        commercialInfoExpirationVO.incomeValue = customerUpdateRequest?.incomeValue ? customerUpdateRequest.buildDecryptedIncomeValue() : KnowYourCustomerInfo.findIncomeValue(currentUser.customer)
        commercialInfoExpirationVO.daysToShowNextAlert = buildDaysToNextAlertForPeriodicExpiration(scheduledDate)
        commercialInfoExpirationVO = fillAuthorizationDeviceInfo(commercialInfoExpirationVO, currentUser)
        commercialInfoExpirationVO = fillCommonExpirationAlertData(commercialInfoExpirationVO, currentUser, scheduledDate)

        return commercialInfoExpirationVO
    }

    private CommercialInfoExpirationVO fillAuthorizationDeviceInfo(CommercialInfoExpirationVO commercialInfoExpirationVO, User currentUser) {
        AuthorizationDevice authorizationDevice = AuthorizationDevice.query([customer: currentUser.customer, sort: "id", order: "desc"]).get()
        if (authorizationDevice) {
            commercialInfoExpirationVO.authorizationDeviceType = authorizationDevice.type
            commercialInfoExpirationVO.isAuthorizationDeviceLocked = authorizationDevice.locked

            return commercialInfoExpirationVO
        }

        commercialInfoExpirationVO.authorizationDeviceType = AuthorizationDeviceType.SMS_TOKEN
        commercialInfoExpirationVO.isAuthorizationDeviceLocked = true

        return commercialInfoExpirationVO
    }

    private CommercialInfoExpirationVO fillCommonExpirationAlertData(CommercialInfoExpirationVO commercialInfoExpirationVO, User currentUser, Date scheduledDate) {
        final Date today = new Date().clearTime()
        commercialInfoExpirationVO.isLegalPerson = currentUser.customer.isLegalPerson()
        commercialInfoExpirationVO.isExpired = scheduledDate <= today
        commercialInfoExpirationVO.scheduledDate = scheduledDate
        commercialInfoExpirationVO.isUserEnableToUpdate = modulePermissionService.allowed(currentUser, "admin")
        if (!commercialInfoExpirationVO.isUserEnableToUpdate) {
            commercialInfoExpirationVO.adminUsernameList = User.admin(currentUser.customer, [column: "username"]).list()
        }

        return commercialInfoExpirationVO
    }

    private void notify(CustomerCommercialInfoExpiration customerCommercialInfoExpiration) {
        customerAlertNotificationService.notifyCustomerOnCommercialInfoExpiration(customerCommercialInfoExpiration.customer)
        mobilePushNotificationService.notifyCustomerOnCommercialInfoExpiration(customerCommercialInfoExpiration.customer)

        customerCommercialInfoExpiration.hasBeenNotified = true
        customerCommercialInfoExpiration.save(failOnError: true)
    }

    private Integer buildDaysToNextAlertForPeriodicExpiration(Date scheduledDate) {
        Integer dateDifferenceInDays = CustomDateUtils.calculateDifferenceInDays(new Date().clearTime(), scheduledDate)

        switch (dateDifferenceInDays) {
            case { it <= FIFTH_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS }:
                return DAILY_NOTIFICATION
            case { it <= FOURTH_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS }:
                return dateDifferenceInDays - FIFTH_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS
            case { it <= THIRD_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS }:
                return dateDifferenceInDays - FOURTH_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS
            case { it <= SECOND_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS }:
                return dateDifferenceInDays - THIRD_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS
            case { it <= FIRST_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS }:
                return dateDifferenceInDays - SECOND_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS
            default:
                AsaasLogger.error("CustomerCommercialInfoExpirationService.buildDaysToNextAlert >> Não foi possível calcular os dias para o próximo alerta. DateDifferenceInDays [${dateDifferenceInDays}].")
                return null
        }
    }

    private Boolean shouldShowMandatoryInfoExpirationAlert(Date scheduledDate, CustomerCommercialInfoExpirationType type) {
        if (!type.isMandatoryInfo()) return false

        Date dateToFirstAlert = CustomDateUtils.sumDays(scheduledDate.clearTime(), -FIRST_MANDATORY_INFO_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS)

        return new Date().after(dateToFirstAlert)
    }

    private Boolean shouldShowPeriodicExpirationAlert(Date scheduledDate, CustomerCommercialInfoExpirationType type) {
        if (!type.isPeriodic()) return false

        Date dateToFirstAlert = CustomDateUtils.sumDays(scheduledDate.clearTime(), -FIRST_PERIODIC_EXPIRATION_ALERT_NOTIFICATION_IN_DAYS)
        return new Date().after(dateToFirstAlert)
    }

    private Date buildScheduleDate(CustomerCommercialInfoExpirationType type, Date customerDateCreated) {
        Integer daysToExpire = type.isMandatoryInfo() ? MANDATORY_INFO_EXPIRATION_DAYS : PERIODIC_EXPIRATION_DAYS
        Date referenceDate = type.isMandatoryInfo() ? customerDateCreated.clone().clearTime() : new Date().clearTime()

        return CustomDateUtils.sumDays(referenceDate, daysToExpire)
    }

    private CustomerCommercialInfoExpirationType buildExpirationType(CustomerCommercialInfoExpiration customerCommercialInfoExpiration) {
        if (canScheduleForMandatoryInfo(customerCommercialInfoExpiration)) {
            return CustomerCommercialInfoExpirationType.MANDATORY_INFO
        }

        return CustomerCommercialInfoExpirationType.PERIODIC
    }

    private Boolean canSchedule(Customer customer) {
        if (AsaasEnvironment.isSandbox()) return false

        if (CustomerPartnerApplication.hasBradesco(customer.id)) return false

        if (customer.hadGeneralApproval()) return true

        if (KnowYourCustomerInfo.hasIncomeRange(customer.id)) return true

        if (KnowYourCustomerInfo.hasIncomeValue(customer.id)) return true

        return false
    }

    private Boolean canScheduleForMandatoryInfo(CustomerCommercialInfoExpiration customerCommercialInfoExpiration) {
        Boolean hasIncomeRange = KnowYourCustomerInfo.hasIncomeRange(customerCommercialInfoExpiration.customer.id)
        if (hasIncomeRange) return false

        Boolean hasIncomeValue = KnowYourCustomerInfo.hasIncomeValue(customerCommercialInfoExpiration.customer.id)
        if (hasIncomeValue) return false

        return true
    }
}

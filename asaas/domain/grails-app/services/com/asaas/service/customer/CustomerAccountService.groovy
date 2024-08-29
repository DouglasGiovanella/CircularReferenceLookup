package com.asaas.service.customer

import com.asaas.abtest.enums.AbTestPlatform
import com.asaas.address.Address
import com.asaas.boleto.RegisteredBoletoValidator
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.PersonType
import com.asaas.customeraccount.CustomerAccountParameterName
import com.asaas.customeraccount.CustomerAccountUpdateResponse
import com.asaas.customerstatistic.CustomerStatisticName
import com.asaas.domain.city.City
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerAccountExternalIdentifier
import com.asaas.domain.customer.CustomerAccountGroup
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerStatistic
import com.asaas.domain.foreignaddress.ForeignAddress
import com.asaas.domain.notification.Notification
import com.asaas.domain.payment.Payment
import com.asaas.domain.postalcode.PostalCode
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.notification.NotificationEvent
import com.asaas.notification.NotificationReceiver
import com.asaas.notification.NotificationSchedule
import com.asaas.notification.NotificationType
import com.asaas.postalservice.PaymentPostalServiceValidator
import com.asaas.user.UserUtils
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class CustomerAccountService {

    def abTestService
    def asaasErpCustomerAccountService
    def customerAccountGroupService
    def customerAccountParameterService
    def customerMessageService
    def customerParameterService
    def foreignAddressService
    def grailsApplication
    def mandatoryCustomerAccountNotificationService
    def notificationDispatcherCustomerAccountService
    def notificationDispatcherPaymentNotificationOutboxService
    def notificationService
    def paymentService
    def receivableAnticipationValidationService
    def subscriptionService


    public Map buildCustomerAccountMapFromCustomer(Customer customer) {
        Map customerAccountMap = [:]

        customerAccountMap.name = customer.providerName
        customerAccountMap.email = customer.email
        customerAccountMap.cpfCnpj = customer.cpfCnpj
        customerAccountMap.phone = customer.phone
        customerAccountMap.mobilePhone = customer.mobilePhone
        customerAccountMap.address = customer.address
        customerAccountMap.addressNumber = customer.addressNumber
        customerAccountMap.complement = customer.complement
        customerAccountMap.province = customer.cpfCnpj
        customerAccountMap.city = customer.city
        customerAccountMap.postalCode = customer.postalCode

        return customerAccountMap
    }

	public void delete(id, Long providerId, Boolean deletePendingPayments) {
		CustomerAccount customerAccount = CustomerAccount.find(id, providerId)

		if (customerAccount.deleted) return
		if (!customerAccount.canBeDeleted()) throw new BusinessException("O cliente [${customerAccount.id}] não pode ser removido: ${customerAccount.editDisabledReason}")

		if (deletePendingPayments) deletePendingPaymentsAndSubscriptions(customerAccount)

		customerAccount.deleted = true
		customerAccount.save(flush: true, failOnError: true)

        asaasErpCustomerAccountService.deleteIfNecessary(customerAccount)
		CustomerStatistic.expire(customerAccount.provider, CustomerStatisticName.TOTAL_CUSTOMER_ACCOUNT_COUNT)
        receivableAnticipationValidationService.onDeleteCustomerAccount(customerAccount)
        notificationDispatcherPaymentNotificationOutboxService.saveCustomerAccountUpdated(customerAccount)
	}

	private void deletePendingPaymentsAndSubscriptions(CustomerAccount customerAccount) {
		Subscription.queryId([customerAccount: customerAccount]).list().each{ Long subscriptionId ->
			subscriptionService.delete(subscriptionId, customerAccount.provider.id, true)
		}

		customerAccount.listPendingAndOverduePayments().each{ Payment payment ->
			if (payment.canDelete()) paymentService.delete(payment, false)
		}
	}

    public Long findAsaasCustomerAccount(Customer customer) {
        Customer asaasProvider = Customer.get(Long.valueOf(grailsApplication.config.asaas.customer.id))
        return findAsaasCustomerAccount(asaasProvider, customer)
    }

	public Long findAsaasCustomerAccount(Customer asaasProvider, Customer customer) {
        String hql = "select id from CustomerAccount ca where ca.provider = :asaasProvider and ca.customer = :customer and ca.deleted = false"
        return CustomerAccount.executeQuery(hql, [asaasProvider: asaasProvider, customer: customer])[0]
    }

    public CustomerAccount saveOrUpdateAsaasCustomerAccountFromProvider(Customer customer) {
        Customer asaasProvider = Customer.get(Long.valueOf(grailsApplication.config.asaas.customer.id))
        return saveOrUpdateAsaasCustomerAccountFromProvider(asaasProvider, customer)
    }

    public CustomerAccount saveOrUpdateAsaasCustomerAccountFromProvider(Customer asaasProvider, Customer customer) {
        Map customerAccountMap = buildCustomerAccountMapFromCustomer(customer)
        customerAccountMap.automaticRoutine = true
        customerAccountMap.id = findAsaasCustomerAccount(asaasProvider, customer)

        CustomerAccount customerAccount
        if (customerAccountMap.id) {
            customerAccount = update(asaasProvider.id, customerAccountMap).customerAccount
        } else {
            customerAccount = save(asaasProvider, customer, customerAccountMap)
            createNotificationsForCustomerAccount(customerAccount)
        }

        return customerAccount
    }

	public CustomerAccount updateAsaasCustomerAccountFromProviderIfExists(Customer customer) {
        Long customerAccountId = findAsaasCustomerAccount(customer)

		if (!customerAccountId) return null

		Map customerAccountMap = buildCustomerAccountMapFromCustomer(customer)
		customerAccountMap.id = customerAccountId

		return update(grailsApplication.config.asaas.customer.id, customerAccountMap).customerAccount
	}

    public CustomerAccount save(Customer provider, Customer customer, Boolean failOnError, Map params) {
        provider.validateAccountDisabled()

        CustomerAccount validatedCustomerAccount = validateSaveParams(provider, params)

		if (validatedCustomerAccount.hasErrors()) {
			if (failOnError) throw new BusinessException(DomainUtils.getFirstValidationMessage(validatedCustomerAccount))

			return validatedCustomerAccount
		}

        CustomerAccount customerAccount = new CustomerAccount()
        customerAccount.properties = params
        customerAccount.provider = provider
        customerAccount.customer = customer

        if (customerAccount.cpfCnpj) customerAccount.personType = customerAccount.getPersonTypeFromCpfCnpj()

		setAddressIfPostalCodeIsPresent(customerAccount)

		sanitizeNameAndAddressInfo(customerAccount)

		customerAccount.createdBy = UserUtils.getCurrentUser() ?: params.createdBy

		customerAccount.save(flush: true, failOnError: failOnError)

		if (customerAccount.hasErrors()) return customerAccount

		customerAccount.orderId = customerAccount.id

        customerAccount.publicId = "cus_" + customerAccount.id.toString().padLeft(12, '0')

        customerAccount.save(failOnError: failOnError)

		saveCustomerAccountGroup(params.groupName, customerAccount)

		saveOrUpdateForeignAddressIfNecessary(customerAccount, params)

        if (!params.import) customerParameterService.setAsLargeCustomerBaseIfNecessary(customerAccount.provider)

        CustomerStatistic.expireWithNewTransaction(customerAccount.provider.id, CustomerStatisticName.TOTAL_CUSTOMER_ACCOUNT_COUNT)

        validateMobilePhoneNumber(provider, customerAccount)
		setCreatedBy(customerAccount, params)

        asaasErpCustomerAccountService.saveIfNecessary(customerAccount, params.asaasErpCustomerAccountExternalId, false)
        notificationDispatcherCustomerAccountService.saveIfCustomerMigrated(customerAccount)
        notificationDispatcherPaymentNotificationOutboxService.saveCustomerAccountUpdated(customerAccount)

    	return customerAccount
    }

    public Boolean canCreateMoreCustomerAccountsToday(Customer customer, Integer quantity) {
        BigDecimal dailyCustomerAccountLimit = customer.getDailyPaymentsLimit()
        BigDecimal customerAccountsCreatedToday = CustomerAccount.query([customerId: customer.id, "dateCreated[ge]": new Date().clearTime(), deleted: true, disableSort: true]).count()

        Boolean limitWillBeReached = (quantity + customerAccountsCreatedToday) > dailyCustomerAccountLimit
        return !limitWillBeReached
    }

    private CustomerAccount validateSaveParams(Customer customer, Map params) {
        CustomerAccount customerAccount = new CustomerAccount()

        if (params.name?.length() > CustomerAccount.constraints.name.maxSize) {
            DomainUtils.addError(customerAccount, "O campo name deve ter no máximo ${CustomerAccount.constraints.name.maxSize} caracteres.")
        }

        if (params.personType && !PersonType.convert(params.personType)) {
            DomainUtils.addError(customerAccount, "Não é possível salvar. O tipo de pessoa informado é inválido.")
        }

        if (!params.automaticRoutine && !params.import && !canCreateMoreCustomerAccountsToday(customer, 1)) {
            DomainUtils.addError(customerAccount, "O limite de pagadores diário é de ${BigDecimalUtils.roundDown(customer.getDailyPaymentsLimit(), 0)}. Para aumentá-lo contate seu gerente.")
        }

        BusinessValidation validatedMunicipalInscription = validateMunicipalInscription(params.municipalInscription)
        if (!validatedMunicipalInscription.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedMunicipalInscription, customerAccount)
        }

        validateCpfCnpj(customerAccount, params.cpfCnpj?.toString())

        validateAddress(customerAccount, params.address?.toString())

        return customerAccount
    }

    private BusinessValidation validateMunicipalInscription(String municipalInscription) {
        BusinessValidation validatedMunicipalInscription = new BusinessValidation()
        final Integer municipalInscriptionMaxSize = CustomerAccount.constraints.municipalInscription.maxSize

        if (!municipalInscription) {
            return validatedMunicipalInscription
        }

        if (municipalInscription.size() > municipalInscriptionMaxSize) {
            validatedMunicipalInscription.addError("customerAccount.error.municipalInscription.maxSize", [municipalInscriptionMaxSize])
        }

        return validatedMunicipalInscription
    }

    private void setCreatedBy(CustomerAccount customerAccount, params) {
    	if (UserUtils.getCurrentUser()) {
    		customerAccount.createdBy = UserUtils.getCurrentUser()
    	} else if (params.createdBy) {
    		customerAccount.createdBy = params.createdBy
    	} else if (params.createdById) {
    		customerAccount.createdBy = User.find(params.createdById, customerAccount.provider)
    	}
    }

	private void saveCustomerAccountGroup(String groupName, CustomerAccount customerAccount){
    	if (!groupName) {
    		return
    	}

		Integer customerAccountGroupId = -1
		CustomerAccountGroup customerAccountGroup = CustomerAccountGroup.query([customerId: customerAccount.provider.id,
																				groupName: groupName,
																				workspace: false]).get()

		if (customerAccountGroup) customerAccountGroupId = customerAccountGroup.id

		customerAccountGroupService.associateCustomerAccount(customerAccount.provider, customerAccountGroupId, groupName, customerAccount.id)

    }

    private void validateCpfCnpj(CustomerAccount customerAccount, String cpfCnpj) {
        if (!cpfCnpj) return

        if (!Utils.removeNonNumeric(cpfCnpj) || !CpfCnpjUtils.validate(cpfCnpj)) {
            DomainUtils.addError(customerAccount, "O CPF/CNPJ informado é inválido.")
        }
    }

    private void validateAddress(CustomerAccount customerAccount, String address) {
        if (address?.length() > CustomerAccount.constraints.address.maxSize) {
            DomainUtils.addError(customerAccount, "O campo address deve ter no máximo ${CustomerAccount.constraints.address.maxSize} caracteres.")
        }
    }

    public CustomerAccount save(Customer provider, Customer customer, params) {
    	return save(provider, customer, true, params)
    }

    public CustomerAccountUpdateResponse update(Long providerId, Map params) {
        CustomerAccountUpdateResponse response = new CustomerAccountUpdateResponse()

        CustomerAccount currentCustomerAccount = CustomerAccount.find(params.id, providerId)
        Boolean customerAccountChanged = DomainUtils.hasAttributesChanged(currentCustomerAccount, params)

        Boolean foreignAddressChanged = false
        if (currentCustomerAccount.foreignCustomer) {
            ForeignAddress currentForeignAddress = ForeignAddress.find(currentCustomerAccount)
            Map foreignAddressParams = [
                country: params.foreignAddressCountry,
                city: params.foreignAddressCity,
                state: params.foreignAddressState
            ]
            foreignAddressChanged = DomainUtils.hasAttributesChanged(currentForeignAddress, foreignAddressParams)
        }

		if (!customerAccountChanged && !foreignAddressChanged) {
			response.customerAccount = CustomerAccount.find(params.id, providerId)
			return response
		}

		CustomerAccount customerAccount = CustomerAccount.find(params.id, providerId)

		if (!customerAccount.canBeUpdated()) {
			DomainUtils.addError(customerAccount, "O cliente [${customerAccount.id}] não pode ser atualizado: ${customerAccount.editDisabledReason}")
			response.customerAccount = customerAccount
			return response
		}

		validateUpdateParams(customerAccount, params)
		if (customerAccount.hasErrors()) {
			response.customerAccount = customerAccount
			return response
		}

		if (!customerAccount.mobilePhone && !params.mobilePhone && params.phone && PhoneNumberUtils.isMobilePhoneNumber(params.phone)) {
			params.mobilePhone = params.phone
			params.phone = null
		}

        if (params.containsKey("externalReference")) customerAccount.externalReference = params.externalReference

		if (params.containsKey("sendPaymentsByPostalService")) {
            params.sendPaymentsByPostalService = Utils.toBoolean(params.sendPaymentsByPostalService)

			if (params.sendPaymentsByPostalService) customerAccount = validateIfCanSendPaymentsByPostalService(providerId, params)
		}

		response.customerAccount = customerAccount

		if (customerAccount.hasErrors()) return response

        if (params.cpfCnpj) params.cpfCnpj = Utils.removeNonNumeric(params.cpfCnpj)
        if (params.postalCode) params.postalCode = Utils.removeNonNumeric(params.postalCode)
        if (params.phone) params.phone = PhoneNumberUtils.sanitizeNumber(params.phone)
        if (params.mobilePhone) params.mobilePhone = PhoneNumberUtils.sanitizeNumber(params.mobilePhone)

        final List<String> excludeFieldList = ["id", "customer", "provider", "deleted", "orderId", "createdBy", "publicId", "dateCreated", "lastUpdated"]
        customerAccount.properties = params.findAll { !excludeFieldList.contains(it.key) }

        if (customerAccount.cpfCnpj) customerAccount.personType = customerAccount.getPersonTypeFromCpfCnpj()

		setAddressIfPostalCodeIsPresent(customerAccount)

		sanitizeNameAndAddressInfo(customerAccount)

		response.updatedFields = DomainUtils.getUpdatedFields(customerAccount)

        List<String> updatedFieldsNameList = response.updatedFields.collect { it.fieldName }
        receivableAnticipationValidationService.onCustomerAccountInfoUpdated(customerAccount, updatedFieldsNameList)

        Boolean isDirty = customerAccount.isDirty()

        customerAccount.lock()
		customerAccount.save(flush: true, failOnError: false)

        customerAccountParameterService.disableIfPossible(customerAccount, CustomerAccountParameterName.OVERRIDE_NOTIFICATION_UPDATE_BLOCK)

		response.customerAccount = customerAccount

		if (customerAccount.hasErrors()) return response

		notificationService.disableNotificationsIfNecessary(customerAccount)

		if (params.containsKey("cancelPaymentsThatCannotBeSend")) {
			params.cancelPaymentsThatCannotBeSend = params.cancelPaymentsThatCannotBeSend instanceof Boolean ? params.cancelPaymentsThatCannotBeSend : params.cancelPaymentsThatCannotBeSend == "true"
			if (params.cancelPaymentsThatCannotBeSend) {
				customerAccount.listPendingPaymentsOfSend().each { Payment payment ->
					if (!(new PaymentPostalServiceValidator(payment).isValid())) {
						payment.postalServiceStatus = null
						payment.save(failOnError: true)
					}
				}
			}
		}

		if (RegisteredBoletoValidator.validate(customerAccount)) {
			paymentService.registerBankSlips(Payment.canBeRegisteredWithFilter(customerAccount).list())
		}

		if (!customerAccount.foreignCustomer) {
			foreignAddressService.delete(customerAccount)
		} else {
			customerAccount.city = null
			customerAccount.save(flush: true, failOnError: true)
			foreignAddressService.saveOrUpdate(customerAccount, params.foreignAddressCountry, params.foreignAddressCity, params.foreignAddressState)
		}

		validateMobilePhoneNumber(customerAccount.provider, customerAccount)

        asaasErpCustomerAccountService.updateIfNecessary(customerAccount, params.asaasErpCustomerAccountExternalId)
        if (isDirty) notificationDispatcherPaymentNotificationOutboxService.saveCustomerAccountUpdated(customerAccount)

		return response
	}

    public void enableNotification(Long id, Long customerId) {
        CustomerAccount customerAccount = CustomerAccount.find(id, customerId)
        customerAccount.notificationDisabled = false
        customerAccount.save(failOnError: true)

        asaasErpCustomerAccountService.updateIfNecessary(customerAccount, null)
        notificationDispatcherPaymentNotificationOutboxService.saveCustomerAccountUpdated(customerAccount)
    }

	private void setAddressIfPostalCodeIsPresent(CustomerAccount customerAccount) {
		if (!customerAccount.postalCode || (customerAccount.id && !customerAccount.isDirty("postalCode"))) return

		PostalCode postalCode = PostalCode.find(customerAccount.postalCode)
		if (!postalCode) return

		customerAccount.city = postalCode.city

		if (!customerAccount.address) customerAccount.address = postalCode.address
		if (!customerAccount.province) customerAccount.province = postalCode.province
	}

	public CustomerAccount validateIfCanSendPaymentsByPostalService(Long providerId, Map params) {
		CustomerAccount customerAccount = CustomerAccount.find(params.id, providerId)

		Address address =  new Address(
			address: params.address,
			addressNumber: params.addressNumber,
			complement: params.complement,
			province: params.province,
			city: City.get(params.city),
			postalCode: params.postalCode
		)

		PaymentPostalServiceValidator validator = new PaymentPostalServiceValidator(address, true)

		if (!validator.customerAccountIsValid()) {
			for(reason in validator.validateCustomerAccountInfo()) {
				DomainUtils.addError(customerAccount, Utils.getMessageProperty("postal.service.sending.error.${reason.toString()}.description"))
			}
		}

		return customerAccount
	}

	private void validateMobilePhoneNumber(Customer provider, CustomerAccount customerAccount) {
        if (!customerAccount.mobilePhone) return

        if (PhoneNumberUtils.validateMobilePhone(customerAccount.mobilePhone)) return

        customerMessageService.sendNotificationToProviderAboutCustomerAccountInvalidMobilePhone(provider, customerAccount)
	}

    public void createNotificationsForCustomerAccount(CustomerAccount customerAccount) {
        createNotificationsForCustomerAccount(customerAccount, true)
    }

    public void createNotificationsForCustomerAccount(CustomerAccount customerAccount, Boolean activeNotifications) {
        setDefaultNotificationForCustomerAccount(customerAccount, null)
        if (!activeNotifications) return

        if (customerAccount.email) {
            setDefaultNotificationForCustomerAccount(customerAccount, NotificationType.EMAIL)
        }

        if (customerAccount.mobilePhone) {
            setDefaultNotificationForCustomerAccount(customerAccount, NotificationType.SMS)
        }
    }

    public void createDefaultNotifications(CustomerAccount customerAccount) {
        createDefaultNotifications(customerAccount, true)
    }

    public void createDefaultNotifications(CustomerAccount customerAccount, Boolean activeNotifications) {
        if (activeNotifications) {
            notificationService.createDefaultNotificationsForProvider(customerAccount)
        }

        createNotificationsForCustomerAccount(customerAccount, activeNotifications)
    }

    public void disableNotifications(CustomerAccount customerAccount) {
        List<Notification> notificationList = Notification.getCustomerAccountNotificationList(customerAccount)

        for (Notification notification : notificationList) {
            notificationService.findAndDisableProviderNotifications(customerAccount, notification.trigger.event, notification.trigger.schedule, notification.trigger.scheduleOffset, [NotificationType.SMS, NotificationType.EMAIL])
            notificationService.disableSmsAndEmailForCustomerAccountNotification(customerAccount, notification.trigger.event, notification.trigger.schedule, notification.trigger.scheduleOffset)
        }
    }

	public void setDefaultNotificationForCustomerAccount(CustomerAccount customerAccount, NotificationType notificationType) {
		notificationService.saveNotification(customerAccount, NotificationEvent.PAYMENT_CREATED, notificationType, NotificationReceiver.CUSTOMER, true, NotificationSchedule.IMMEDIATELY, 0)
		notificationService.saveNotification(customerAccount, NotificationEvent.PAYMENT_UPDATED, notificationType, NotificationReceiver.CUSTOMER, true, NotificationSchedule.IMMEDIATELY, 0)
		notificationService.saveNotification(customerAccount, NotificationEvent.PAYMENT_DUEDATE_WARNING, notificationType, NotificationReceiver.CUSTOMER, true, NotificationSchedule.IMMEDIATELY, 0)
		notificationService.saveNotification(customerAccount, NotificationEvent.PAYMENT_DUEDATE_WARNING, notificationType, NotificationReceiver.CUSTOMER, true, NotificationSchedule.BEFORE, 10)
		notificationService.saveNotification(customerAccount, NotificationEvent.CUSTOMER_PAYMENT_RECEIVED, notificationType, NotificationReceiver.CUSTOMER, true, NotificationSchedule.IMMEDIATELY, 0)
		notificationService.saveNotification(customerAccount, NotificationEvent.CUSTOMER_PAYMENT_OVERDUE, notificationType, NotificationReceiver.CUSTOMER, true, NotificationSchedule.IMMEDIATELY, 0)

		BigDecimal customerParamOffset = CustomerParameter.getNumericValue(customerAccount.provider, CustomerParameterName.OVERDUE_NOTIFICATION_AFTER_OFFSET)
		Integer scheduleOffset = customerParamOffset ? customerParamOffset.toInteger() : Notification.DEFAULT_OVERDUE_NOTIFICATION_AFTER_OFFSET

		notificationService.saveNotification(customerAccount, NotificationEvent.CUSTOMER_PAYMENT_OVERDUE, notificationType, NotificationReceiver.CUSTOMER, true, NotificationSchedule.AFTER, scheduleOffset)
		notificationService.saveNotification(customerAccount, NotificationEvent.SEND_LINHA_DIGITAVEL, notificationType, NotificationReceiver.CUSTOMER, true, NotificationSchedule.IMMEDIATELY, 0)
	}

	public CustomerAccountExternalIdentifier saveOrUpdateExternalIdentifier(CustomerAccount customerAccount, externalIdentifier, ExternalApplication application){
		CustomerAccountExternalIdentifier customerAccountExternalIdentifier = CustomerAccountExternalIdentifier.findOrCreateWhere(customerAccount: customerAccount, application: application, deleted: false)
		customerAccountExternalIdentifier.externalIdentifier = externalIdentifier
		customerAccountExternalIdentifier.application = application
		customerAccountExternalIdentifier.synchronize = true
		customerAccountExternalIdentifier.save(flush: true, failOnError: true)

		return customerAccountExternalIdentifier
	}

	public CustomerAccount findByCpfCnpjOrEmail(Customer provider, String cpfCnpj, String email) {
		CustomerAccount customerAccount

		customerAccount = findByCpfCnpj(cpfCnpj, provider)

		if(!customerAccount) {
			customerAccount = findByEmail(email, provider)
		}

		return customerAccount
	}

	public CustomerAccount findByCpfCnpj(cpfCnpj, Customer provider) {
		if(!cpfCnpj || !provider) return
		return CustomerAccount.executeQuery("""from CustomerAccount
	                                       	  where provider = :provider
	                                       	 	and cpfCnpj = :cpfCnpj""", [provider: provider, cpfCnpj: cpfCnpj])[0]
	}

	public CustomerAccount findByEmail(email, Customer provider) {
		if(!email || !provider) return

		return CustomerAccount.executeQuery("""from CustomerAccount
		                                      where provider = :provider
			                                    and email = :email""", [provider: provider, email: email])[0]
	}

	public Map updateAdditionalEmails(Long customerAccountId, Long customerId, String additionalEmails) {
		CustomerAccount customerAccount = CustomerAccount.find(customerAccountId, customerId)
		List validEmailsList = []
		List invalidEmailsList = []

		if (additionalEmails) {
			List emailsList = additionalEmails.toString().tokenize(CustomerAccount.ADDITIONAL_EMAILS_SEPARATOR)
			for (email in emailsList) {
				if (!Utils.emailIsValid(email)) {
					DomainUtils.addError(customerAccount, "O seguinte endereço de email foi ignorado pois é inválido:")
					invalidEmailsList.add(email)
				} else if (customerAccount.email == email) {
					DomainUtils.addError(customerAccount, "O seguinte endereço de email foi ignorado pois é igual ao email principal:")
					invalidEmailsList.add(email)
				} else {
					validEmailsList.add(email)
				}
			}
		}

		String additionalEmailsString = validEmailsList.join(",")

		if (!customerAccount.hasErrors()) {
			customerAccount.additionalEmails = additionalEmailsString
			customerAccount.save(failOnError: true)
		}

		return [customerAccount: customerAccount, validEmailsList: validEmailsList, invalidEmailsList: invalidEmailsList]
	}

	public CustomerAccount validateUpdateParams(CustomerAccount customerAccount, Map params) {
		if (customerAccount.updateWillImpactAutomaticAnticipations(params)) {
			DomainUtils.addError(customerAccount, "Não é possível remover os dados deste cliente, pois ele possui cobranças agendadas para serem antecipadas.")
		}

        if (updateWillImpactMandatoryNotification(customerAccount, params)) {
            DomainUtils.addError(customerAccount, "Não é possível alterar os dados de contato deste cliente pois ele possui notificações obrigatórias.")
        }

		if (customerAccount.cpfCnpj && params.containsKey("cpfCnpj") && !params.cpfCnpj) {
			Boolean existsRegisteredBoleto = Payment.registeredOrAwaitingBoletoRegistration([customerAccount: customerAccount]).get()

			if (existsRegisteredBoleto) {
				DomainUtils.addError(customerAccount, "Não é possível remover o CPF/CNPJ deste cliente pois ele possui boletos registrados.")
			}
		}

        BusinessValidation validatedMunicipalInscription = validateMunicipalInscription(params.municipalInscription)
        if (!validatedMunicipalInscription.isValid()) {
            DomainUtils.copyAllErrorsFromBusinessValidation(validatedMunicipalInscription, customerAccount)
        }

        validateCpfCnpj(customerAccount, params.cpfCnpj?.toString())

        validateAddress(customerAccount, params.address?.toString())

        return customerAccount
    }

	private void saveOrUpdateForeignAddressIfNecessary(CustomerAccount customerAccount, Map params) {
		if (customerAccount.foreignCustomer) {
			foreignAddressService.saveOrUpdate(customerAccount, params.foreignAddressCountry, params.foreignAddressCity, params.foreignAddressState)
		}
	}

	public void deleteExternalIdentifier(CustomerAccount customerAccount, ExternalApplication externalApplication) {
		CustomerAccountExternalIdentifier customerAccountExternalIdentifier = CustomerAccountExternalIdentifier.synchronizedCustomerAccount(customerAccount, externalApplication).list()[0]

		customerAccountExternalIdentifier.deleted = true
		customerAccountExternalIdentifier.save(failOnError: true)
	}

	public CustomerAccount restore(Long customerId, id) {
		CustomerAccount customerAccount = CustomerAccount.find(id, customerId)

        if (!customerAccount.deleted) return customerAccount

        if (customerAccount.cpfCnpj) customerAccount.personType = customerAccount.getPersonTypeFromCpfCnpj()

		customerAccount.deleted = false
		customerAccount.save(flush: true, failOnError: true)

        receivableAnticipationValidationService.onRestoreCustomerAccount(customerAccount)
        notificationDispatcherPaymentNotificationOutboxService.saveCustomerAccountUpdated(customerAccount)

        return customerAccount
	}

    public Boolean canManageNotificationsOnCustomerAccountCreation(Customer customer) {
        String variantValue = drawCanManageNotificationsOnCustomerAccountCreationAbTest(customer)
        return variantValue == grailsApplication.config.asaas.abtests.variantB
    }

    private String drawCanManageNotificationsOnCustomerAccountCreationAbTest(Customer customer) {
        final String defaultVariantValue = grailsApplication.config.asaas.abtests.defaultVariantValue
        if (!abTestService.canDrawAbTestFollowingGrowthRules(customer)) {
            return defaultVariantValue
        }

        if (customer.isSignedUpThroughMobile()) return defaultVariantValue

        final Date testStartDate = CustomDateUtils.fromString("18/06/2024")
        if (customer.dateCreated < testStartDate) {
            return defaultVariantValue
        }

        String abTestName = grailsApplication.config.asaas.abtests.manageNotificationsOnCustomerAccountCreation.name
        return abTestService.chooseVariant(abTestName, customer, AbTestPlatform.WEB_DESKTOP)
    }

	private void sanitizeNameAndAddressInfo(CustomerAccount customerAccount) {
		if (customerAccount.name) customerAccount.name = Utils.removeSpecialChars(customerAccount.name)
		if (customerAccount.company) customerAccount.company = Utils.removeSpecialChars(customerAccount.company)
		if (customerAccount.address) customerAccount.address = Utils.removeSpecialChars(customerAccount.address)
		if (customerAccount.addressNumber) customerAccount.addressNumber = Utils.removeSpecialChars(customerAccount.addressNumber)
		if (customerAccount.complement) customerAccount.complement = Utils.removeSpecialChars(customerAccount.complement)
		if (customerAccount.province) customerAccount.province = Utils.removeSpecialChars(customerAccount.province)
	}

    private Boolean updateWillImpactMandatoryNotification(CustomerAccount customerAccount, Map params) {
        Boolean hasMandatoryNotification = mandatoryCustomerAccountNotificationService.hasAnyMandatoryNotification(customerAccount)
        if (!hasMandatoryNotification) return false

        Boolean phoneAndMobilePhoneRemoved = (customerAccount.mobilePhone || customerAccount.phone) && !params.phone && !params.mobilePhone
        if (phoneAndMobilePhoneRemoved) return true

        Boolean emailRemoved = customerAccount.email && !params.email
        if (emailRemoved) return true

        return false
    }
}

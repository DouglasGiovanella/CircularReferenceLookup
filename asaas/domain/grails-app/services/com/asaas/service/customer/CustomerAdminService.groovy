package com.asaas.service.customer

import com.asaas.accountmanager.AccountManagerDepartment
import com.asaas.customer.CustomerParameterName
import com.asaas.customer.CustomerStatus
import com.asaas.customer.DisabledReason
import com.asaas.customerdatarestriction.CustomerDataRestrictionType
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerDisabledReason
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerdebtappropriation.CustomerDebtAppropriation
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.sms.SmsSender
import com.asaas.userpermission.RoleAuthority
import com.asaas.utils.DomainUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.plugin.springsecurity.SpringSecurityUtils
import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class CustomerAdminService {

    def asaasCardRechargeService
    def asaasCardService
    def billAdminService
    def creditTransferRequestAdminService
    def customerAdminService
    def customerDataRestrictionService
    def customerInteractionService
    def customerParameterService
    def customerService
    def customerStatusService
    def messageService
    def paymentService
    def smsSenderService
    def userPasswordManagementService

    public Customer update(Long customerId, Map params) {
        Customer customer = Customer.get(customerId)
        if (!customer) return DomainUtils.addError(new Customer(), "Cliente não encontrado")

        Map parsedParams = parseParams(params)

        customer = validateUpdate(customer, parsedParams)
        if (customer.hasErrors()) return customer

        customerService.setDistrustLevelIfPossible(customer, params.distrustLevel)

        if (params.activationPhone) customer.activationPhone = PhoneNumberUtils.sanitizeNumber(params.activationPhone)
        if (params.observations) customer.observations = params.observations

        customer.save(failOnError: true)

        return customer
    }

    public Customer updateMaxPaymentValue(Long customerId, BigDecimal maxPaymentValue) {
        Customer customer = Customer.get(customerId)

        BigDecimal maxPaymentValueBefore = customer.calculateMaxPaymentValue()

        customer.maxPaymentValue = maxPaymentValue
        customer.save(flush: false, failOnError: false)

        if (customer.hasErrors()) return customer

        customerInteractionService.saveUpdateMaxPaymentValue(customer, maxPaymentValueBefore, customer.calculateMaxPaymentValue())

        return customer
    }

    public Customer updateDailyPaymentsLimit(Long customerId, BigDecimal dailyPaymentsLimit) {
        Customer customer = Customer.get(customerId)

        if (!dailyPaymentsLimit){
            DomainUtils.addError(customer, "Informe um ${CustomerParameterName.CUSTOM_DAILY_PAYMENTS_LIMIT.getLabel()} válido.")
            return customer
        }

        customerParameterService.save(customer, CustomerParameterName.CUSTOM_DAILY_PAYMENTS_LIMIT, dailyPaymentsLimit)

        return customer
    }

    public Boolean saveIncomeAndCompanyCreationDate(Long customerId, Double income, Date companyCreationDate) {
        try {
            Customer customer = Customer.findById(customerId)
            customer.income = income
            customer.companyCreationDate = companyCreationDate
            customer.save(flush: true, failOnError: true)

            return true
        } catch (Exception exception) {
            AsaasLogger.error("CustomerAdminService.saveIncomeAndCompanyCreationDate >> Erro ao salvar a renda e a data de criação da empresa", exception)

            return false
        }
    }

    public BusinessValidation canDisableCustomer(Customer customer) {
        BusinessValidation validateBusiness = new BusinessValidation()

        if (customer.status.isDisabled()) {
            validateBusiness.addError("customer.disabled")
            return validateBusiness
        }

        Boolean hasNotLockedUserWithSysAdminRole = User.query([exists: true, customer: customer, accountLocked: false, role: RoleAuthority.ROLE_SYSADMIN.toString()]).get()
        if (hasNotLockedUserWithSysAdminRole) {
            validateBusiness.addError("customer.disable.internalUser.denied")
            return validateBusiness
        }

        return validateBusiness
    }

    public BusinessValidation canBlockCustomer(Customer customer) {
        BusinessValidation validateBusiness = new BusinessValidation()

        if (customer.getIsBlocked()) {
            validateBusiness.addError("customer.isBlocked")
            return validateBusiness
        }

        if (customer.accountDisabled()) {
            validateBusiness.addError("customer.disabled")
            return validateBusiness
        }

        if (customer.hasUserWithSysAdminRole())  {
            validateBusiness.addError("customer.block.internalUser.denied")
            return validateBusiness
        }

        return validateBusiness
    }

    public void unblockManually(Long customerId) {
        BusinessValidation validateBusiness = canUnblockManually(Customer.read(customerId))
        if (!validateBusiness.isValid()) throw new BusinessException(validateBusiness.getFirstErrorMessage())

        customerStatusService.unblock(customerId)

        customerInteractionService.saveCustomerUnblockInfo(customerId)
    }

    public BusinessValidation canUnblockManually(Customer customer) {
        BusinessValidation validateBusiness = customerStatusService.canUnblock(customer)
        if (!validateBusiness.isValid()) return validateBusiness

        if (CustomerDebtAppropriation.active([customer: customer, exists: true]).get().asBoolean()) {
            validateBusiness.addError("customerDebtAppropriation.appropriatedCustomer.message")
            return validateBusiness
        }

        return validateBusiness
    }

    public void enableCheckout(Customer customer, Boolean bypassEnableCheckoutValidation) {
        BusinessValidation businessValidation = customerAdminService.validateIfCanEnableCheckout(customer, bypassEnableCheckoutValidation)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        customerParameterService.save(customer, CustomerParameterName.CHECKOUT_DISABLED, false)

        asaasCardService.updateAllToUnlockable(customer)
        billAdminService.authorizeAllWaitingRiskAuthorization(customer)
        asaasCardRechargeService.updateBlockedRechargesToPending(customer)
    }

    public void disableCheckout(Long customerId, String reason) {
        disableCheckout(Customer.get(customerId), reason)
    }

    public void disableCheckout(Customer customer, String reason) {
        customerParameterService.save(customer, CustomerParameterName.CHECKOUT_DISABLED, true)

        asaasCardService.blockAllCards(customer)

        asaasCardRechargeService.updatePendingRechargesToBlocked(customer)
        billAdminService.updatePendingBillsToWaitingRiskAuthorization(customer)
        creditTransferRequestAdminService.updatePendingTransfersToBlocked(customer)

        if (reason) customerInteractionService.save(customer, "Motivo do bloqueio de saques: ${reason}")
        messageService.sendBlockCheckoutAlert(customer, reason)
    }

    public Customer sendCustomSms(Long customerId, String message) {
        Customer customer = Customer.read(customerId)

        Customer validatedCustomer = validateCustomSmsInfo(customer, message)
        if (validatedCustomer.hasErrors()) throw new ValidationException("Não foi possível enviar o SMS ao cliente", validatedCustomer.errors)

        Boolean sentSMS = smsSenderService.send(message, customer.mobilePhone, true, [:])
        if (!sentSMS) throw new RuntimeException("Erro ao enviar SMS ao cliente. Entre em contato com o time de Engenharia.")

        customerInteractionService.save(customer, "Envio de SMS: ${message}")

        return customer
    }

    public void updateSoftDescriptor(Long customerId, String softDescriptor) {
        Customer customer = Customer.get(customerId)

        if (softDescriptor) {
            if (softDescriptor.length() > 18) throw new BusinessException("O SoftDescriptor precisa conter no máximo 18 caracteres.")

            if (Utils.replaceNonAlphanumericCharacters(softDescriptor, "") != softDescriptor) throw new BusinessException("O SoftDescriptor permite somente letras e números.")
        }

        customer.softDescriptorText = softDescriptor ?: null
        customer.save(failOnError: true)

        customerInteractionService.saveSoftDescriptorText(customer, softDescriptor)
    }

    public void executeAccountDisable(Long customerId, User currentUser, DisabledReason disabledReason, String observations, Boolean shouldRestrictCustomerCpfCnpj) {
        if (!disabledReason) throw new BusinessException("É obrigatório informar o motivo.")

        if (disabledReason.isObservationRequired() && !observations) throw new BusinessException("É obrigatório informar a observação para esse motivo.")

        Customer customer = Customer.read(customerId)
        BusinessValidation validatedBusiness = canDisableCustomer(customer)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        customerStatusService.executeAccountDisable(customerId, currentUser, disabledReason)

        CustomerDisabledReason customerDisabledReason = new CustomerDisabledReason()
        customerDisabledReason.customer = customer
        customerDisabledReason.disabledReason = disabledReason
        customerDisabledReason.observations = observations
        customerDisabledReason.save(failOnError: true)

        if (!shouldRestrictCustomerCpfCnpj) return

        String cpfCnpj = customerDisabledReason.customer.cpfCnpj
        if (!cpfCnpj) return
        if (customerDataRestrictionService.hasDataInCustomerDataRestriction(CustomerDataRestrictionType.CPF_CNPJ, cpfCnpj)) return

        try {
            Map params = [type: CustomerDataRestrictionType.CPF_CNPJ, value: cpfCnpj]
            customerDataRestrictionService.save(params)
        } catch (Exception exception) {
            String message = "CustomerAdminService.executeAccountDisable >> Ocorreu um erro ao salvar o CPF/CNPJ: [${cpfCnpj}] na lista de restrição. CustomerId: [${customerId}]"
            AsaasLogger.error(message, exception)
        }
    }

    public void expireAllUsersPassword(Customer customer) {
        userPasswordManagementService.expireAllUsersPassword(customer)

        String description = Utils.getMessageProperty("customerInteraction.expireAllUsersPassword")
        customerInteractionService.save(customer, description)
    }

    public Boolean hasActiveAccountOwnerManagerAndSegmentInheritance(Customer accountOwner) {
        if (!accountOwner.segment?.isCorporate()) return false

        if (CustomerParameter.getValue(accountOwner, CustomerParameterName.DISABLE_INHERITANCE_OF_ACCOUNT_OWNER_MANAGER_AND_SEGMENT_FOR_CORPORATE_ACCOUNT)) return false

        return true
    }

    public BusinessValidation validateIfCanSendCustomSms(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        Boolean isSysAdmin = SpringSecurityUtils.ifAllGranted("ROLE_SYSADMIN")
        if (!isSysAdmin) businessValidation.addError("customer.canSendCustomSms.validateCustomer.isNotSysAdmin")

        if (CustomerParameter.getValue(customer, CustomerParameterName.WHITE_LABEL)) businessValidation.addError("customer.canSendCustomSms.validateCustomer.isWhiteLabel")

        if (CustomerPartnerApplication.hasBradesco(customer.id)) businessValidation.addError("customer.canSendCustomSms.validateCustomer.hasBradesco")

        if (!customer.mobilePhone) {
            businessValidation.addError("customer.canSendCustomSms.validateCustomer.mobilePhoneNotFound")
        } else {
            String sanitizedPhone = PhoneNumberUtils.sanitizeNumber(customer.mobilePhone)
            if (!PhoneNumberUtils.validateMobilePhone(sanitizedPhone)) businessValidation.addError("customer.canSendCustomSms.validateCustomer.invalidMobilePhone")
        }

        return businessValidation
    }

    public BusinessValidation validateIfCanUnblockOrExtendBoleto(Customer customer) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (!customer.boletoIsDisabled()) {
            businessValidation.addError("customer.isUnblockedBoleto")
            return businessValidation
        }

        AccountManagerDepartment accountManagerDepartment = AccountManager.query([column: "department", email: customer.email]).get()
        if (accountManagerDepartment?.shouldUseFakeInfo()) {
            businessValidation.addError("internalUser.fakeInfo.notAllowedUnblockOrExtendBoleto")
            return businessValidation
        }

        return businessValidation
    }

    public BusinessValidation validateIfCanEnableCheckout(Customer customer, Boolean bypassEnableCheckoutValidation) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (bypassEnableCheckoutValidation) return businessValidation

        Boolean isCheckoutDisabled = CustomerParameter.getValue(customer, CustomerParameterName.CHECKOUT_DISABLED)
        if (!isCheckoutDisabled) {
            businessValidation.addError("customer.isEnabledCheckout")
            return businessValidation
        }

        Boolean isDocumentsAnalyst = SpringSecurityUtils.ifAnyGranted("ROLE_DOCUMENTS_ANALYST")
        if (!isDocumentsAnalyst) {
            businessValidation.addError("internalUser.isNotDocumentsAnalyst.notAllowedEnableCheckout")
            return businessValidation
        }

        AccountManagerDepartment accountManagerDepartment = AccountManager.query([column: "department", email: customer.email]).get()
        if (accountManagerDepartment?.shouldUseFakeInfo()) {
            businessValidation.addError("internalUser.fakeInfo.notAllowedEnableCheckout")
            return businessValidation
        }

        return businessValidation
    }

    private Customer validateCustomSmsInfo(Customer customer, String message) {
        Customer validatedCustomer = new Customer()
        BusinessValidation businessValidation = validateIfCanSendCustomSms(customer)
        if (!businessValidation.isValid()) DomainUtils.copyAllErrorsFromBusinessValidation(businessValidation, validatedCustomer)

        if (!message) DomainUtils.addError(validatedCustomer, "É necessário informar a mensagem.")

        if (message.length() > SmsSender.MAX_CHARS) DomainUtils.addError(validatedCustomer, "A mensagem não pode conter mais que ${SmsSender.MAX_CHARS} caracteres.")

        return validatedCustomer
    }

    private Map parseParams(Map params) {
        return [
                activationPhone: params.activationPhone,
                distrustLevel: Utils.toInteger(params.distrustLevel),
                observations: params.observations,
                status: CustomerStatus.convert(params.status)
        ].findAll { it.value }
    }

    private Customer validateUpdate(Customer customer, Map params) {
        if (params.containsKey("activationPhone")) {
            if (!params.activationPhone) return DomainUtils.addError(customer, "Informe um telefone de ativação válido")

            if (!PhoneNumberUtils.validateMobilePhone(params.activationPhone)) return DomainUtils.addError(customer, "O telefone informado não está em um formato válido")
        }

        return customer
    }
}

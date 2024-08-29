package com.asaas.service.accountmanager

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customer.CustomerSegment
import com.asaas.domain.accountmanager.AccountManager
import com.asaas.domain.user.User
import com.asaas.domain.user.UserWorkingHours
import com.asaas.exception.BusinessException
import com.asaas.accountmanager.AccountManagerAdapter
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.OfficeHoursChecker
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional
import grails.validation.ValidationException

import java.time.Instant

@Transactional
class AccountManagerService {

    def accountManagerCallCenterService
    def accountManagerDataMigrationService
    def asyncActionService
    def messageService

    private final Integer START_INTERNAL_EXTENSION_RANGE = 100
    private final Integer FINAL_INTERNAL_EXTENSION_RANGE = 500

    public void disable(AccountManager accountManager) {
        accountManager.customerPortfolio = false
        accountManager.internalExtension = null
        accountManager.email = Instant.now().getEpochSecond() + "_" + accountManager.email
        accountManager.internalEmail = Instant.now().getEpochSecond() + "_" + accountManager.internalEmail
        accountManager.deleted = true
        accountManager.save(failOnError: true)

        distributeCustomerPortfolioAsyncIfPossible(accountManager)

        accountManagerCallCenterService.disable(accountManager)
    }

    public void save(AccountManagerAdapter accountManagerAdapter) {
        Boolean isCustomerSuccess = accountManagerAdapter.department?.isCustomerSuccess()

        AccountManager validatedAccountManager = validateSave(isCustomerSuccess, accountManagerAdapter.customerSegment, accountManagerAdapter.internalExtension)
        if (validatedAccountManager.hasErrors()) throw new ValidationException("Não foi possível salvar os dados do gerente", validatedAccountManager.errors)

        User user = accountManagerAdapter.user

        AccountManager accountManager = new AccountManager()
        accountManager.user = user
        accountManager.email = user.username
        accountManager.internalEmail = accountManagerAdapter.email
        accountManager.name = user.customer.name
        accountManager.internalExtension = accountManagerAdapter.internalExtension ?: getAvailableInternalExtensionIfExists()
        accountManager.customerPortfolio = false
        accountManager.customerSegment = accountManagerAdapter.customerSegment
        accountManager.department = accountManagerAdapter.department
        accountManager.save(failOnError: true)

        accountManagerCallCenterService.create(accountManager)
        messageService.notifyAboutNewAccountManagerCreated(accountManager)
    }

    public void update(Long id, AccountManagerAdapter accountManagerAdapter) {
        AccountManager accountManager = AccountManager.get(id)
        if (!accountManager) throw new BusinessException("Gerente não encontrado")

        AccountManager validatedAccountManager = validateUpdate(accountManagerAdapter, accountManager)
        if (validatedAccountManager.hasErrors()) throw new ValidationException("Não foi possível salvar os dados do gerente", validatedAccountManager.errors)

        accountManager.department = accountManagerAdapter.department
        accountManager.customerSegment = accountManagerAdapter.customerSegment
        accountManager.internalExtension = accountManagerAdapter.internalExtension
        accountManager.customerPortfolio = accountManagerAdapter.customerPortfolio
        accountManager.save(failOnError: true)

        accountManagerCallCenterService.update(accountManager)
    }

    public void disableTraining(AccountManager accountManager) {
        BusinessValidation businessValidation = canDisableTraining(accountManager)
        if (!businessValidation.isValid()) throw new BusinessException(businessValidation.getFirstErrorMessage())

        accountManager.customerPortfolio = shouldHaveCustomerPortfolioEnabled(accountManager)
        accountManager.save(failOnError: true)

        accountManagerCallCenterService.disableTraining(accountManager.id)
    }

    public void changeAttendanceType(String fakeEmail, String attendanceType) {
        Long accountManagerId = AccountManager.query([column: "id", email: fakeEmail]).get()
        if (!accountManagerId) throw new BusinessException("Gerente não encontrado.")

        accountManagerCallCenterService.changeAccountManagerAttendanceType(accountManagerId, attendanceType)
    }

    public void distributeCustomerPortfolioAsyncIfPossible(AccountManager accountManager) {
        Boolean hasCustomerInPortfolio = accountManagerDataMigrationService.hasCustomerToMigrate(accountManager.id)
        if (!hasCustomerInPortfolio) return

        if (accountManager.customerPortfolio) throw new BusinessException("Não é possível distribuir clientes quando o gerente está com a carteira de clientes ativada")

        asyncActionService.save(AsyncActionType.DISTRIBUTE_CUSTOMERS_PORTFOLIO_IN_SEGMENT, [accountManagerId: accountManager.id])
    }

    public void disableCustomerPortfolioIfNecessary(AccountManager accountManager, Boolean shouldDistributeCustomerPortfolio) {
        if (!accountManager.customerPortfolio) return

        if (shouldHaveCustomerPortfolioEnabled(accountManager)) return

        accountManager.customerPortfolio = false
        accountManager.save(failOnError: true)

        if (shouldDistributeCustomerPortfolio) distributeCustomerPortfolioAsyncIfPossible(accountManager)
    }

    public AccountManager validateSave(Boolean isCustomerSuccess, CustomerSegment customerSegment, String accountManagerInternalExtension) {
        AccountManager validatedAccountManager = new AccountManager()

        if (isCustomerSuccess && !customerSegment) {
            DomainUtils.addError(validatedAccountManager, "Gerentes do departamento de Sucesso do Cliente devem possuir um segmento.")
        }

        if (accountManagerInternalExtension) {
            validateInternalExtension(validatedAccountManager, accountManagerInternalExtension)
        }

        return validatedAccountManager
    }

    public Map getAccountManagerInfo(User user) {
        AccountManager accountManager = AccountManager.query([user: user, includeDeleted: true]).get()
        Map accountManagerInfo = [:]

        if (!accountManager) return accountManagerInfo

        accountManagerInfo.hasActiveAccountManager = false
        accountManagerInfo.department = accountManager.department
        if (accountManager.deleted) return accountManagerInfo

        accountManagerInfo.hasActiveAccountManager = true
        accountManagerInfo.simpleName = accountManager.class.simpleName
        accountManagerInfo.id = accountManager.id

        return accountManagerInfo
    }

    public BusinessValidation canDisableTraining(AccountManager accountManager) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!accountManager) {
            validatedBusiness.addError("accountManager.notFound")
            return validatedBusiness
        }

        if (accountManager.deleted) {
            validatedBusiness.addError("accountManager.deleted")
            return validatedBusiness
        }

        if (!accountManager.department?.isCustomerSuccess()) validatedBusiness.addError("accountManager.department.notCustomerSuccess")

        if (accountManager.customerPortfolio) validatedBusiness.addError("accountManager.notInTraining")

        return validatedBusiness
    }

    public BusinessValidation canDisable(AccountManager accountManager) {
        BusinessValidation validatedBusiness = new BusinessValidation()

        if (!accountManager) {
            validatedBusiness.addError("accountManager.notFound")
            return validatedBusiness
        }

        if (accountManager.deleted) {
            validatedBusiness.addError("accountManager.deleted")
            return validatedBusiness
        }

        return validatedBusiness
    }

    private AccountManager validateUpdate(AccountManagerAdapter accountManagerAdapter, AccountManager accountManager) {
        AccountManager validatedAccountManager = new AccountManager()

        if (!accountManagerAdapter.customerSegment && accountManagerAdapter.department?.isCustomerExperienceAttendanceDepartment()) {
            DomainUtils.addError(validatedAccountManager, "Gerentes do departamento de \"Experiência do Cliente\" ou \"Experiência do Cliente: Atendimento Especializado\" devem possuir um segmento.")
        }

        if (accountManagerAdapter.customerSegment && !accountManagerAdapter.department?.isCustomerExperienceAttendanceDepartment()) {
            DomainUtils.addError(validatedAccountManager, "Apenas gerentes do departamento de \"Experiência do Cliente\" ou \"Experiência do Cliente: Atendimento Especializado\" devem possuir um segmento.")
        }

        if (accountManagerAdapter.internalExtension && accountManagerAdapter.internalExtension != accountManager.internalExtension) {
            validateInternalExtension(validatedAccountManager, accountManagerAdapter.internalExtension)
        }

        if (accountManagerAdapter.customerPortfolio && !shouldHaveCustomerPortfolioEnabled(accountManager)) {
            DomainUtils.addError(validatedAccountManager, "Este gerente não pode ter carteira de clientes habilitada")
        }

        return validatedAccountManager
    }

    private Boolean shouldHaveCustomerPortfolioEnabled(AccountManager accountManager) {
        if (!accountManager.department?.isCustomerSuccess()) return false

        UserWorkingHours userWorkingHours = UserWorkingHours.query([user: accountManager.user]).get()

        if (!userWorkingHours) return true

        if (userWorkingHours.weekdaysStartHour < OfficeHoursChecker.OUTGOING_CALLS_HOURS_START &&
            userWorkingHours.weekdaysEndHour <= OfficeHoursChecker.OUTGOING_CALLS_HOURS_START) return false

        if (userWorkingHours.weekdaysStartHour >= OfficeHoursChecker.OUTGOING_CALLS_HOURS_END) return false

        return true
    }

    private String getAvailableInternalExtensionIfExists() {
        List<String> internalExtensionList = AccountManager.query([column: "internalExtension", "internalExtension[isNotNull]": true]).list()

        for (Integer internalExtension in START_INTERNAL_EXTENSION_RANGE..FINAL_INTERNAL_EXTENSION_RANGE) {
            if (!internalExtensionList.contains(internalExtension.toString())) return internalExtension.toString()
        }

        AsaasLogger.error("AccountManagerService.getAvailableInternalExtensionIfExists >> Não foram encontrados ramais disponíveis.")
        return null
    }

    private void validateInternalExtension(AccountManager validatedAccountManager, String internalExtension) {
        Integer internalExtensionParsed = Utils.toInteger(internalExtension)
        Boolean isValidRange = (START_INTERNAL_EXTENSION_RANGE..FINAL_INTERNAL_EXTENSION_RANGE).contains(internalExtensionParsed)

        if (!isValidRange) {
            DomainUtils.addError(validatedAccountManager, "O ramal informado deve estar entre ${START_INTERNAL_EXTENSION_RANGE} e ${FINAL_INTERNAL_EXTENSION_RANGE}")
            return
        }

        String accountManagerWithSameInternalExtension = AccountManager.query([column: "name", internalExtension: internalExtension]).get()

        if (accountManagerWithSameInternalExtension) {
            DomainUtils.addError(validatedAccountManager, "O ramal informado está sendo utilizado pelo gerente: ${accountManagerWithSameInternalExtension}.")
        }
    }
}

package com.asaas.emailvalidation

import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.knowyourcustomerinfo.KnowYourCustomerInfo
import com.asaas.domain.emailvalidation.EmailValidationCode
import com.asaas.domain.user.User
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.emailvalidation.enums.EmailValidationStatus
import com.asaas.exception.BusinessException
import com.asaas.user.adapter.UserAdapter
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import org.apache.commons.lang.RandomStringUtils

@Transactional
class EmailValidationService {

    def asaasSecurityMailMessageService
    def crypterService
    def customerAlertNotificationService
    def customerUpdateRequestService
    def userUpdateRequestService

    public void sendEmailValidationCode(User user) {
        String code = createValidationCodeForUser(user)
        asaasSecurityMailMessageService.sendUserEmailValidationCode(user, code)
    }

    public Boolean checkEmailValidationCodeAndSetToValidatedIfPossible(User user, String code) {
        String numericCodeOnly = Utils.removeNonNumeric(code)
        EmailValidationCode emailValidationCode = EmailValidationCode.query([user: user]).get()
        String decriptedCode = crypterService.decryptDomainProperty(emailValidationCode, "code", emailValidationCode.code)
        Boolean isValidCode = numericCodeOnly == decriptedCode
        if (isValidCode) {
            customerAlertNotificationService.deleteCustomerEmailValidationIfExists(user.customer)
            emailValidationCode.status = EmailValidationStatus.VALIDATED
            emailValidationCode.save(failOnError: true)
        }
        return isValidCode
    }

    public Boolean hasAlreadyValidatedCode(User user) {
        return EmailValidationCode.query([exists: true, user: user, status: EmailValidationStatus.VALIDATED]).get().asBoolean()
    }

    public Boolean updateEmailAndSendValidationIfEligible(User user, UserAdapter userAdapter) {
        Customer customer = user.customer
        Integer usersRelatedToCustomerCount = User.query([column: "id", customer: customer]).count()
        Boolean hasOnlyOneUser = usersRelatedToCustomerCount == 1
        Boolean doUserAndCustomerHaveTheSameEmail = customer.email == user.username

        Boolean customerHasAccountBalance = FinancialTransaction.getCustomerBalance(customer) > 0

        if (!customerHasAccountBalance && hasOnlyOneUser && doUserAndCustomerHaveTheSameEmail) {
            Map customerUpdateRequestParams = buildCustomerUpdateRequestParams(customer, userAdapter)
            def customerUpdateRequest = customerUpdateRequestService.save(customer.id, customerUpdateRequestParams)
            if (customerUpdateRequest.hasErrors()) {
                throw new BusinessException(DomainUtils.getValidationMessages(customerUpdateRequest.getErrors()).first())
            }
        }

        userUpdateRequestService.save(userAdapter, !customerHasAccountBalance)
        Boolean isPossibleToSendValidationCode = !customerHasAccountBalance
        if (isPossibleToSendValidationCode) sendEmailValidationCode(user)
        return isPossibleToSendValidationCode
    }

    public void notifyEmailValidationRequestedIfNecessary(User user) {
        if (hasAlreadyValidatedCode(user)) return

        customerAlertNotificationService.notifyEmailValidationRequestedIfNotExist(user.customer)
    }

    private Map buildCustomerUpdateRequestParams(Customer customer, UserAdapter userAdapter) {
        Map fields = [:]

        fields.email = userAdapter.username
        fields.mobilePhone = customer.mobilePhone
        fields.phone = customer.phone
        fields.cpfCnpj = customer.cpfCnpj
        fields.personType = customer.personType
        fields.businessActivity = customer.getBusinessActivity()
        fields.name = customer.name
        fields.company = customer.company
        fields.companyType = customer.companyType
        fields.address = customer.address
        fields.addressNumber = customer.addressNumber
        fields.complement = customer.complement
        fields.province = customer.province
        fields.postalCode = customer.postalCode.toString()
        fields.city = customer.city
        fields.cityString = customer.cityString
        fields.state = customer.state
        fields.additionalEmails = customer.additionalEmails
        fields.birthDate = customer.birthDate
        fields.site = customer.site
        fields.responsibleName = customer.responsibleName
        fields.inscricaoEstadual = customer.inscricaoEstadual

        KnowYourCustomerInfo knowYourCustomerInfo = KnowYourCustomerInfo.query([customer: customer]).get()
        if (knowYourCustomerInfo) {
            fields.incomeRange = crypterService.decryptDomainProperty(knowYourCustomerInfo, "incomeRange", knowYourCustomerInfo.incomeRange)
        }

        fields.bypassIncomeRangeMandatoryValidation = true

        return fields
    }

    private String createValidationCodeForUser(User user) {
        EmailValidationCode emailValidationCode = new EmailValidationCode()
        emailValidationCode.user = user
        emailValidationCode.code = "000000"
        emailValidationCode.save(failOnError: true)

        String code = RandomStringUtils.randomNumeric(6)
        String codeEncrypted = crypterService.encryptDomainProperty(emailValidationCode, "code", code)
        emailValidationCode.code = codeEncrypted
        emailValidationCode.save(failOnError: true)

        return code
    }
}

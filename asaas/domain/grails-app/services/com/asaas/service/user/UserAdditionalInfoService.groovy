package com.asaas.service.user

import com.asaas.asyncaction.AsyncActionType
import com.asaas.documentanalysis.adapter.AccountDocumentAnalysisAdapter
import com.asaas.domain.city.City
import com.asaas.domain.companypartnerquery.CompanyPartnerQuery
import com.asaas.domain.customer.Customer
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.exception.UserAdditionalInfoException
import com.asaas.integration.heimdall.enums.revenueserviceregister.RevenueServiceRegisterCacheLevel
import com.asaas.log.AsaasLogger
import com.asaas.user.adapter.UserAdditionalInfoAdapter
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError
import grails.transaction.Transactional

@Transactional
class UserAdditionalInfoService {

    def accountDocumentAnalysisManagerService
    def asyncActionService
    def companyPartnerQueryService
    def postalCodeService
    def revenueServiceRegisterService
    def userAdditionalInfoManagerService
    def userDocumentService
    def userSecurityStageService

    public UserAdditionalInfoAdapter save(UserAdditionalInfoAdapter userAdditionalInfoAdapter) {
        try {
            return executeSave(userAdditionalInfoAdapter)
        } catch (UserAdditionalInfoException userAdditionalInfoException) {
            saveOrUpdateUserAdditionalInfoAsyncAction(userAdditionalInfoAdapter)
            throw new UserAdditionalInfoException(Utils.getMessageProperty("user.userAdditionalInfo.asyncAction"))
        }
    }

    public UserAdditionalInfoAdapter find(Long userId) {
        return userAdditionalInfoManagerService.get(userId)
    }

    public void deleteIfNecessary(User user) {
        userAdditionalInfoManagerService.delete(user.id)
    }

    public void updateUserBasedOnCustomerIfPossible(Customer customer) {
        if (customer.isNaturalPerson()) {
            updateUserBasedOnCustomerIfPossible(customer, [:])
            return
        }

        try {
            AccountDocumentAnalysisAdapter analysis = accountDocumentAnalysisManagerService.findByAccountId(customer.id)
            if (!analysis) return

            Map analysisData = [ocrCpf: analysis.ocrCpf, ocrBirthDate: analysis.ocrBirthDate]
            updateUserBasedOnCustomerIfPossible(customer, analysisData)
        } catch (Exception exception) {
            AsaasLogger.error("UserAdditionalInfoService.prepareUpdateBasedOnCustomerIfPossible >> Customer: [${customer.id}]", exception)
        }
    }

    public void updateUserBasedOnCustomerIfPossible(Customer customer, Map params) {
        Map asyncActionData = [customerId: customer.id]

        if (customer.isLegalPerson()) {
            Map legalPersonData = buildLegalPersonDataIfPossible(customer, params)
            if (!legalPersonData) return

            asyncActionData.putAll(legalPersonData)
        }

        saveUpdateUserBasedOnCustomerAsyncAction(asyncActionData)
    }

    public void processUpdateUserBasedOnCustomer() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.UPDATE_USER_BASED_ON_CUSTOMER, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                Customer customer = Customer.read(asyncActionData.customerId)
                List<User> userList = User.query([customer: customer]).list(max: 2)
                if (!userList) {
                    AsaasLogger.error("UserAdditionalInfoService.processUpdateUserBasedOnCustomer >> Não foi encontrada lista de usuários para o customer.id [${customer.id}]")
                    asyncActionService.delete(asyncActionData.asyncActionId)
                    return
                }

                if (userList.size() > 1) {
                    asyncActionService.delete(asyncActionData.asyncActionId)
                    return
                }

                User user = userList.first()
                Map userAdditionalInfo = buildUserAdditionalInfoData(customer, asyncActionData, false)
                userAdditionalInfo.id = user.id

                sendUserAdditionalInfoFromCustomer(user, userAdditionalInfo)
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "UserAdditionalInfoService.processUpdateUserBasedOnCustomer >> Falha ao salvar as informações do usuário, customerId: [${asyncActionData.customerId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public void processSaveOrUpdateUserAdditionalInfo() {
        final Integer maxItemsPerCycle = 50

        List<Map> asyncActionDataList = asyncActionService.listPending(AsyncActionType.SAVE_OR_UPDATE_USER_ADDITIONAL_INFO, maxItemsPerCycle)
        if (!asyncActionDataList) return

        for (Map asyncActionData : asyncActionDataList) {
            Utils.withNewTransactionAndRollbackOnError({
                asyncActionData.id = asyncActionData.userId

                if (!Utils.isEmptyOrNull(asyncActionData.city)) {
                    asyncActionData.city = City.read(Utils.toLong(asyncActionData.city))
                }

                executeSave(new UserAdditionalInfoAdapter(asyncActionData))
                asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "UserAdditionalInfoService.processSaveOrUpdateUserAdditionalInfo >> Falha ao salvar as informações do usuário [${asyncActionData.userId}]",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    public List<AsaasError> validateSaveOrUpdateParams(UserAdditionalInfoAdapter userAdditionalInfoAdapter) {
        List<AsaasError> asaasErrorList = []

        if (userAdditionalInfoAdapter.isMigration) return asaasErrorList

        if (!CpfCnpjUtils.validate(userAdditionalInfoAdapter.cpf)) {
            asaasErrorList.add(new AsaasError("invalid.com.asaas.user.UserAdditionalInfo.cpf"))
            return asaasErrorList
        }

        if (userAdditionalInfoAdapter.isNaturalPerson && Utils.isEmptyOrNull(userAdditionalInfoAdapter.birthDate)) {
            asaasErrorList.add(new AsaasError("required.com.asaas.user.UserAdditionalInfo.birthDate"))
            return asaasErrorList
        }

        if (userAdditionalInfoAdapter.postalCode && userAdditionalInfoAdapter.city) {
            if (!postalCodeService.hasValidCityPostalCode(userAdditionalInfoAdapter.postalCode, userAdditionalInfoAdapter.city.id.toString())) {
                asaasErrorList.add(new AsaasError("invalid.com.asaas.user.UserAdditionalInfo.city"))
                return asaasErrorList
            }
        }

        if (userAdditionalInfoAdapter.disabilityType) {
            if (userAdditionalInfoAdapter.disabilityType.isOthers() && Utils.isEmptyOrNull(userAdditionalInfoAdapter.disabilityTypeDescription)) {
                asaasErrorList.add(new AsaasError("required.com.asaas.user.UserAdditionalInfo.disabilityTypeDescription"))
                return asaasErrorList
            }
        }

        return asaasErrorList
    }

    private void sendUserAdditionalInfoFromCustomer(User user, Map userAdditionalInfo) {
        executeSave(new UserAdditionalInfoAdapter(userAdditionalInfo))
        userDocumentService.sendApprovedIdentificationDocuments(user)
    }

    private Map buildUserAdditionalInfoData(Customer customer, Map params, Boolean isMigration) {
        Map userAdditionalInfo = [
            address: customer.address,
            addressNumber: customer.addressNumber,
            complement: customer.complement,
            province: customer.province,
            city: customer.city,
            postalCode: customer.postalCode,
            isMigration: isMigration]

        if (customer.isNaturalPerson()) {
            userAdditionalInfo.name = customer.name
            userAdditionalInfo.birthDate = customer.birthDate
            userAdditionalInfo.cpf = customer.cpfCnpj
            userAdditionalInfo.isNaturalPerson = true
        } else {
            userAdditionalInfo.name = params.name
            userAdditionalInfo.cpf = params.cpf
            userAdditionalInfo.birthDate = findBirthDateToFillLegalPersonData(params)
            userAdditionalInfo.isNaturalPerson = false
        }

        if (!customer.birthDate && customer.hasAccountAutoApprovalEnabled()) {
            final Date defaultBirthDate = CustomDateUtils.fromString("01/01/1900").clearTime()
            userAdditionalInfo.birthDate = defaultBirthDate
        }

        return userAdditionalInfo
    }

    private Map buildLegalPersonDataIfPossible(Customer customer, Map params) {
        Map legalPersonData = [:]
        legalPersonData.birthDate = params.ocrBirthDate

        Boolean isValidOcrCpf = CpfCnpjUtils.validate(params.ocrCpf)

        CompanyPartnerQuery companyPartnerQuery
        if (isValidOcrCpf) {
            companyPartnerQuery = CompanyPartnerQuery.query([cpf: params.ocrCpf, isAdmin: true]).get()
        } else {
            companyPartnerQuery = companyPartnerQueryService.findCompanyPartnerWithSingleAdminIfPossible(customer)
        }

        if (companyPartnerQuery) {
            legalPersonData.cpf = companyPartnerQuery.cpf
            legalPersonData.name = companyPartnerQuery.name
            return legalPersonData
        }

        if (isValidOcrCpf) {
            legalPersonData.cpf = params.ocrCpf
            return legalPersonData
        }

        return null
    }

    private UserAdditionalInfoAdapter executeSave(UserAdditionalInfoAdapter userAdditionalInfoAdapter) {
        List<AsaasError> asaasErrorList = validateSaveOrUpdateParams(userAdditionalInfoAdapter)
        if (asaasErrorList) throw new BusinessException(Utils.getMessageProperty(asaasErrorList.first().code))

        UserAdditionalInfoAdapter userAdditionalInfoAdapterResponse = userAdditionalInfoManagerService.save(userAdditionalInfoAdapter)
        userSecurityStageService.saveUpdateUserSecurityStageAsyncAction(userAdditionalInfoAdapter.userId)

        return userAdditionalInfoAdapterResponse
    }

    private void saveUpdateUserBasedOnCustomerAsyncAction(Map asyncActionData) {
        try {
            if (!asyncActionData.customerId) return
            AsyncActionType asyncActionType = AsyncActionType.UPDATE_USER_BASED_ON_CUSTOMER

            if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

            asyncActionService.save(asyncActionType, asyncActionData)
        } catch (Exception exception) {
            String message = "Ocorreu um erro ao salvar, customer [${asyncActionData.customerId}]"
            AsaasLogger.error("UserAdditionalInfoService.saveUpdateUserBasedOnCustomerAsyncAction >> ${message}", exception)
            throw exception
        }
    }

    private void saveOrUpdateUserAdditionalInfoAsyncAction(UserAdditionalInfoAdapter userAdditionalInfoAdapter) {
        Map asyncActionData = userAdditionalInfoAdapter.properties
        asyncActionData.remove("class")
        asyncActionData.birthDate = CustomDateUtils.fromDate(userAdditionalInfoAdapter.birthDate)
        asyncActionData.city = userAdditionalInfoAdapter.city?.id

        AsyncActionType asyncActionType = AsyncActionType.SAVE_OR_UPDATE_USER_ADDITIONAL_INFO

        if (asyncActionService.hasAsyncActionPendingWithSameParameters(asyncActionData, asyncActionType)) return

        asyncActionService.save(asyncActionType, asyncActionData)
    }

    private Date findBirthDateToFillLegalPersonData(Map params) {
        try {
            if (params.birthDate) return CustomDateUtils.toDate(params.birthDate)

            if (!params.cpf) return null

            RevenueServiceRegister revenueServiceRegister = revenueServiceRegisterService.findNaturalPerson(params.cpf, null, RevenueServiceRegisterCacheLevel.HIGH)
            if (!revenueServiceRegister?.birthDate) return null

            AsaasLogger.info("UserAdditionalInfoService.findBirthDateToFillLegalPersonData >> Encontrado data de nascimento para o cpf [${params.cpf}].")

            return revenueServiceRegister.birthDate
        } catch (Exception exception) {
            AsaasLogger.error("UserAdditionalInfoService.findBirthDateToFillLegalPersonData >> Não foi possível buscar a data de nascimento do sócio [${params.cpf}]", exception)
            return null
        }
    }
}

package com.asaas.service.api

import com.asaas.api.ApiReceivableAnticipationParser
import com.asaas.checkout.CheckoutValidator
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customerreceivableanticipationconfig.CustomerReceivableAnticipationConfig
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.exception.InstallmentNotFoundException
import com.asaas.exception.PaymentNotFoundException
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.ReceivableAnticipationCancelReason
import com.asaas.receivableanticipation.ReceivableAnticipationDocumentVO
import com.asaas.receivableanticipation.ReceivableAnticipationStatus
import com.asaas.receivableanticipation.adapter.CreateReceivableAnticipationAdapter
import com.asaas.receivableanticipation.parser.ReceivableAnticipationListParser
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidationClosures
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidator
import com.asaas.utils.RequestUtils

import grails.gorm.PagedResultList
import grails.transaction.Transactional

import javax.servlet.http.HttpServletRequest

@Transactional
class ApiReceivableAnticipationService extends ApiBaseService {

    def apiResponseBuilderService
    def customerReceivableAnticipationConfigService
    def receivableAnticipationAgreementService
    def receivableAnticipationBatchService
    def receivableAnticipationCancellationService
    def receivableAnticipationSchedulingService
    def receivableAnticipationService
    def temporaryFileService

    public Map index(Map params) {
        Customer customer = getProviderInstance(params)

        Map responseItem = [:]

        responseItem.daysAfterTransferToAnticipateBankSlip = customer.getRequiredDaysAfterTransferToAnticipateBankslip()
        responseItem.daysAfterTransferToAnticipateCreditCard = customer.getRequiredDaysAfterTransferToAnticipateCreditCard()

        Date firstTransferDate = ReceivableAnticipation.findFirstConfirmedTransferDate(customer)
        responseItem.hasConfirmedTransfer = firstTransferDate.asBoolean()

        responseItem.hasCustomerAccountWithNullPhone = CustomerAccount.query([exists: true, customerId: customer.id, "phone[isNull]": true, disableSort: true]).get().asBoolean()
        responseItem.hasCustomerAccountWithNullCpfCnpj = CustomerAccount.query([exists: true, customerId: customer.id, "cpfCnpj[isNull]": true, disableSort: true]).get().asBoolean()

        responseItem.hasPartnerSettlementAwaitingCreditForTooLong = ReceivableAnticipationValidationClosures.isCustomerWithPartnerSettlementAwaitingCreditForTooLong(customer)

        responseItem.hasApprovedProofOfLife = customer.hasApprovedProofOfLife()
        responseItem.hasCreatedPayments = customer.hasCreatedPayments()

        CheckoutValidator checkoutValidator = new CheckoutValidator(customer)
        responseItem.customerCanUsePix = checkoutValidator.customerCanUsePix()
        responseItem.customerHasAlreadyAnticipated = ReceivableAnticipation.query([exists: true, customer: customer]).get().asBoolean()

        responseItem.totalAvailableBalance = receivableAnticipationBatchService.buildAnticipationTotalValues(customer)?.totalBalanceAvailable
        responseItem.isCustomerAwaitingDaysToEnableAnticipation = new ReceivableAnticipationValidator(true).isAwaitingDaysToEnableAnticipation(customer)

        if (responseItem.isCustomerAwaitingDaysToEnableAnticipation) {
            responseItem.daysToEnableCreditCardAnticipation = ReceivableAnticipationCalculator.calculateDaysToEnableCreditCardAnticipation(customer, firstTransferDate)
            responseItem.daysToEnableBankSlipAnticipation = ReceivableAnticipationCalculator.calculateDaysToEnableBankSlipAnticipation(customer, firstTransferDate)
        }

        return apiResponseBuilderService.buildSuccess(responseItem)
    }

    public simulate(Map params) {
        withValidation(params) { Customer customer, Map fields ->
            receivableAnticipationAgreementService.saveIfNecessary(customer, RequestUtils.getRequest())
            Map receivableAnticipationMap = receivableAnticipationService.simulateAnticipation(fields.installment, fields.payment, fields)

            return ApiReceivableAnticipationParser.buildResponseItem(receivableAnticipationMap, [:])
        }
    }

    public request(HttpServletRequest request, Map params) {
        withValidation(params) { Customer customer, Map fields ->
            for (ReceivableAnticipationDocumentVO receivableAnticipationDocumentVO : fields.listOfReceivableAnticipationDocumentVO.findAll { !it.temporaryFileId }) {
                TemporaryFile temporaryFile = temporaryFileService.save(customer, receivableAnticipationDocumentVO.file, true)

                receivableAnticipationDocumentVO.temporaryFileId = temporaryFile.id
            }

            receivableAnticipationAgreementService.saveIfNecessary(customer, request)
            CreateReceivableAnticipationAdapter adapter = CreateReceivableAnticipationAdapter.buildWithScheduleDaysAfterConfirmation(fields.installment, fields.payment, fields.listOfReceivableAnticipationDocumentVO, fields.originRequesterInfoMethod, fields.scheduleDaysAfterConfirmation)
            ReceivableAnticipation receivableAnticipation = receivableAnticipationService.save(adapter)

            if (receivableAnticipation.hasErrors()) {
                return apiResponseBuilderService.buildErrorList(receivableAnticipation)
            }

            return apiResponseBuilderService.buildSuccess(ApiReceivableAnticipationParser.buildResponseItem(receivableAnticipation, [:]))
        }
    }

    public find(Map params) {
        ReceivableAnticipation receivableAnticipation = ReceivableAnticipation.find(params.id, getProvider(params))

        return apiResponseBuilderService.buildSuccess(ApiReceivableAnticipationParser.buildResponseItem(receivableAnticipation, [:]))
    }

    public findByPaymentId(paymentId) {
        Payment payment = Payment.find(paymentId, getProvider(params))
        ReceivableAnticipation receivableAnticipation = ReceivableAnticipation.query([payment: payment, notStatusList: ReceivableAnticipationStatus.getInvalidStatuses()]).get()

        if (!receivableAnticipation) return apiResponseBuilderService.buildNotFoundItem()

        return apiResponseBuilderService.buildSuccess(ApiReceivableAnticipationParser.buildResponseItem(receivableAnticipation, [:]))
    }

    public cancelScheduled(Map params) {
        ReceivableAnticipation anticipation = ReceivableAnticipation.find(ApiReceivableAnticipationParser.parseId(params.id), getProvider(params))

        receivableAnticipationSchedulingService.cancelScheduled(anticipation, ReceivableAnticipationCancelReason.CANCELLED_MANUALLY)

        return apiResponseBuilderService.buildSuccess(ApiReceivableAnticipationParser.buildResponseItem(anticipation, [:]))
    }

    public list(Map params) {
        Map filters = ApiReceivableAnticipationParser.parseFilters(params)

        Customer customer = getProviderInstance(params)

        PagedResultList listOfReceivableAnticipation = receivableAnticipationService.list(customer, getLimit(params), getOffset(params), filters + [sort: 'dateCreated', order: 'desc'])

        List<Map> parsedAnticipationsList = ApiReceivableAnticipationParser.buildResponseItemList(listOfReceivableAnticipation, [:])

        return apiResponseBuilderService.buildList(parsedAnticipationsList, getLimit(params), getOffset(params), listOfReceivableAnticipation.totalCount)
    }

    public Map getFilterSummary(Map params) {
        Customer customer = getProviderInstance(params)

        Map filters = ApiReceivableAnticipationParser.parseFilters(params)
        Map summaryFilters = ReceivableAnticipationListParser.parseSummaryFilterParams(filters)
        Map summaryMap = receivableAnticipationService.summaryAnticipationsByStatus(customer, summaryFilters)

        return apiResponseBuilderService.buildSuccess(ApiReceivableAnticipationParser.buildFilterSummaryMap(summaryMap))
    }

    public Map limits(Map params) {
        Customer customer = getProviderInstance(params)

        CustomerReceivableAnticipationConfig receivableAnticipationConfig = CustomerReceivableAnticipationConfig.findFromCustomer(customer.id)

        Map creditCard = [:]
        creditCard.total = receivableAnticipationConfig.buildCreditCardAnticipationLimit()
        creditCard.available = customerReceivableAnticipationConfigService.calculateCreditCardAvailableLimit(customer)

        Map bankSlip = [:]
        bankSlip.total = receivableAnticipationConfig.bankSlipAnticipationLimit
        bankSlip.available = customerReceivableAnticipationConfigService.calculateBankSlipAndPixAvailableLimit(customer)

        Map limits = [:]
        limits.creditCard = creditCard
        limits.bankSlip = bankSlip

        return limits
    }

    public Map cancel(Map params) {
        ReceivableAnticipation anticipation = ReceivableAnticipation.find(params.id, getProvider(params))

        receivableAnticipationCancellationService.cancel(anticipation, ReceivableAnticipationCancelReason.CANCELLED_MANUALLY)

        return apiResponseBuilderService.buildSuccess(ApiReceivableAnticipationParser.buildResponseItem(anticipation, [:]))
    }

    private withValidation(Map params, Closure action) {
        try {
            Customer customer = getProviderInstance(params)
            Map fields = ApiReceivableAnticipationParser.parseRequestParams(customer, params)
            if (fields.payment && !fields.payment?.canScheduleAnticipation()) return apiResponseBuilderService.buildErrorFrom("cannotAnticipate", fields.payment.receivableAnticipationDisabledReason)
            if (fields.installment && !fields.installment?.canScheduleAnticipation()) return apiResponseBuilderService.buildErrorFrom("cannotAnticipate", fields.installment.receivableAnticipationDisabledReason)
            if (!fields.payment && !fields.installment)  return apiResponseBuilderService.buildErrorFrom("anticipation.requiredEntity", "É necessário informar o que deseja ser antecipado.")

            return action(customer, fields)
        } catch (InstallmentNotFoundException | PaymentNotFoundException e) {
            return apiResponseBuilderService.buildNotFoundItem()
        }
    }
}

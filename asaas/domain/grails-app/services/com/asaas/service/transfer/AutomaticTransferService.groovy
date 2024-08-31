package com.asaas.service.transfer

import com.asaas.checkout.CustomerCheckoutFee
import com.asaas.domain.bankaccountinfo.BankAccountInfo
import com.asaas.domain.checkout.CustomerCheckoutLimit
import com.asaas.domain.credittransferrequest.CreditTransferRequest
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customerfee.CustomerFee
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.pix.PixTransactionBankAccountInfoCheckoutLimit
import com.asaas.domain.pix.PixTransactionCheckoutLimit
import com.asaas.log.AsaasLogger
import com.asaas.pix.PixCheckoutLimitCalculator
import com.asaas.pix.PixCheckoutValidator
import com.asaas.pix.PixTransactionOriginType
import com.asaas.pix.vo.transaction.PixManualDebitVO
import com.asaas.status.Status
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.AsaasError

import grails.transaction.Transactional

@Transactional
class AutomaticTransferService {

    def creditTransferRequestService
    def pixDebitService

    public void create() {
        List<Long> customerIdList = CustomerParameter.queryWithAutomaticTransfer([column: "customer.id"]).list()

        final Integer flushEvery = 100
        final Integer batchSize = 100
        Utils.forEachWithFlushSessionAndNewTransactionInBatch(customerIdList, batchSize, flushEvery, { Long customerId ->
            AsaasLogger.info("AutomaticTransferService.create() -> Criando transferência automática para o Customer [${customerId}]")

            Customer customer = Customer.read(customerId)
            if (customer.status.isInactive()) return

            BankAccountInfo bankAccountInfo = BankAccountInfo.findMainAccount(customerId)
            if (!bankAccountInfo) bankAccountInfo = BankAccountInfo.query([customerId: customerId, thirdPartyAccount: false, status: Status.APPROVED, sort: "id", order: "asc"]).get()
            if (!bankAccountInfo || !bankAccountInfo.isApproved()) return

            BigDecimal valueAvailableToTransfer = FinancialTransaction.getCustomerBalance(customer)
            if (valueAvailableToTransfer <= 0) return

            BigDecimal valueAvailableToTransferByPix = getRecalculatedValueAvailableToTransferByPix(customer, bankAccountInfo, valueAvailableToTransfer)
            AsaasError asaasErrorTransferByPix = canTransferByPix(customer, bankAccountInfo, valueAvailableToTransferByPix)
            if (!asaasErrorTransferByPix) {
                createAutomaticPixDebit(customer, bankAccountInfo, valueAvailableToTransferByPix)
                return
            }

            BigDecimal valueAvailableToTransferByTed = getRecalculatedValueAvailableToTransferByTed(customer, bankAccountInfo, valueAvailableToTransfer)
            AsaasError asaasErrorTransferByTed = canTransferByTed(customer, bankAccountInfo, valueAvailableToTransferByTed)
            if (!asaasErrorTransferByTed) {
                createAutomaticCreditTransferRequest(customer, bankAccountInfo.id, valueAvailableToTransferByTed)

                AsaasLogger.info("AutomaticTransferService.create() -> Transferência automática realizada via TED [customer.id: ${customer.id}, denyPixReason: ${asaasErrorTransferByPix.getMessage()}]")
                return
            }

            String reasonDescription = "Motivos -> [Pix: ${asaasErrorTransferByPix.getMessage()}, TED: ${asaasErrorTransferByTed.getMessage()}]"
            AsaasLogger.info("AutomaticTransferService.create() -> Nenhum tipo de transferência automática válida para o customer: [${customer.id}], value: [${valueAvailableToTransfer}], account: [${bankAccountInfo.id}]. ${reasonDescription}")
        }, [logErrorMessage: "AutomaticTransferService.create() -> Erro ao criar transferência automática para os clientes", appendBatchToLogErrorMessage: true])
    }

    private BigDecimal getRecalculatedValueAvailableToTransferByPix(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal valueAvailableToTransfer) {
        PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit = PixTransactionBankAccountInfoCheckoutLimit.query([customer: customer, bankAccountInfo: bankAccountInfo]).get()
        if (pixTransactionBankAccountInfoCheckoutLimit) {
            BigDecimal currentDayBankAccountInfoRemainingCheckoutLimit = PixCheckoutLimitCalculator.calculateCurrentDayBankAccountInfoRemainingCheckoutLimit(customer, pixTransactionBankAccountInfoCheckoutLimit)
            if (valueAvailableToTransfer > currentDayBankAccountInfoRemainingCheckoutLimit) return currentDayBankAccountInfoRemainingCheckoutLimit
        }

        BigDecimal limitPerTransaction = PixTransactionCheckoutLimit.calculateLimitPerTransaction(customer)
        BigDecimal currentPeriodRemainingCheckoutLimit = PixTransactionCheckoutLimit.calculateCurrentPeriodRemainingCheckoutLimit(customer)

        if (limitPerTransaction && valueAvailableToTransfer > limitPerTransaction) {
            if (currentPeriodRemainingCheckoutLimit >= limitPerTransaction) return limitPerTransaction

            return currentPeriodRemainingCheckoutLimit
        }

        if (valueAvailableToTransfer > currentPeriodRemainingCheckoutLimit) return currentPeriodRemainingCheckoutLimit

        return valueAvailableToTransfer
    }

    private AsaasError canTransferByPix(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal valueAvailableToTransfer) {
        Boolean bankAccountAllowsPix = bankAccountInfo.financialInstitution.isPixActiveParticipant()
        if (!bankAccountAllowsPix) return new AsaasError("pixTransaction.automatic.denied.financialInstitutionIsNotPixActiveParticipant")

        AsaasError automaticPixDebitDenialReason = getAutomaticPixDebitDenialReasonIfExists(customer, bankAccountInfo, valueAvailableToTransfer)
        if (automaticPixDebitDenialReason) return automaticPixDebitDenialReason

        return null
    }

    private AsaasError getAutomaticPixDebitDenialReasonIfExists(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal value) {
        Boolean toSameOwnership = !bankAccountInfo.thirdPartyAccount
        Boolean shouldChargePixDebitFee = CustomerCheckoutFee.shouldChargePixDebitFee(customer, PixTransactionOriginType.MANUAL, toSameOwnership, value)

        BigDecimal pixDebitFee = CustomerFee.calculatePixDebitFee(customer)
        if (shouldChargePixDebitFee && pixDebitFee > 0) {
            return new AsaasError("pixCheckoutValidator.automatic.error.shouldChargeAutomaticPixDebit")
        }

        PixTransactionBankAccountInfoCheckoutLimit pixTransactionBankAccountInfoCheckoutLimit = PixTransactionBankAccountInfoCheckoutLimit.query([customer: customer, bankAccountInfo: bankAccountInfo]).get()
        if (pixTransactionBankAccountInfoCheckoutLimit) {
            AsaasError asaasError = PixCheckoutValidator.validateCheckoutWithBankAccountInfoCheckoutLimit(customer, value, PixTransactionOriginType.MANUAL, new Date().clearTime(), false, toSameOwnership, pixTransactionBankAccountInfoCheckoutLimit)
            if (asaasError) return asaasError
        } else {
            AsaasError asaasError = PixCheckoutValidator.validateCheckoutLimit(customer, value, PixTransactionOriginType.MANUAL, new Date().clearTime(), false, toSameOwnership)
            if (asaasError) return asaasError
        }

        return null
    }

    private void createAutomaticPixDebit(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal valueAvailableToTransfer) {
        AsaasLogger.info("AutomaticTransferService.createAutomaticPixDebit() -> Customer: [${customer.id}], Value: [${valueAvailableToTransfer}]")

        Map options = [isAutomaticTransfer: true, message: "Transferência automática"]
        PixManualDebitVO pixManualDebitVO = new PixManualDebitVO(bankAccountInfo, valueAvailableToTransfer, options)
        PixTransaction pixTransaction = pixDebitService.saveManualDebit(customer, pixManualDebitVO, [:])
        if (pixTransaction.hasErrors()) AsaasLogger.info("AutomaticTransferService.createAutomaticPixDebit() -> Erro na validação Pix ao criar transferência automática ao cliente ${customer.id}: ${DomainUtils.getFirstValidationMessage(pixTransaction)}")
    }

    private AsaasError canTransferByTed(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal valueAvailableToTransfer) {
        Calendar today = CustomDateUtils.getInstanceOfCalendar()
        if (CustomDateUtils.isWeekend(today)) return new AsaasError("transfer.denied.automatic.invalidDayOrHour")

        Integer currentHour = today.get(Calendar.HOUR_OF_DAY)
        if (currentHour > CreditTransferRequest.getLimitHourToExecuteTransferToday()) return new AsaasError("transfer.denied.automatic.invalidDayOrHour")

        AsaasError asaasError = getAutomaticCreditTransferRequestDenialReasonIfExists(customer, bankAccountInfo, valueAvailableToTransfer)
        if (asaasError) return asaasError

        return null
    }

    private BigDecimal getRecalculatedValueAvailableToTransferByTed(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal valueAvailableToTransfer) {
        Boolean mustBypassCheckoutLimitOnTransfer = creditTransferRequestService.mustBypassCheckoutLimitOnTransfer(bankAccountInfo)
        if (!mustBypassCheckoutLimitOnTransfer) {
            CustomerCheckoutLimit customerCheckoutLimit = customer.getCheckoutLimit()
            Boolean valueIsAboveAvailableDailyLimit = (customerCheckoutLimit && valueAvailableToTransfer > customerCheckoutLimit.availableDailyLimit)
            if (valueIsAboveAvailableDailyLimit) valueAvailableToTransfer = customerCheckoutLimit.availableDailyLimit
        }

        return valueAvailableToTransfer
    }

    private AsaasError getAutomaticCreditTransferRequestDenialReasonIfExists(Customer customer, BankAccountInfo bankAccountInfo, BigDecimal valueAvailableToTransfer) {
        Map transferFeeMap = creditTransferRequestService.calculateTransferFee(customer, bankAccountInfo, valueAvailableToTransfer)
        if (transferFeeMap.fee > 0 && valueAvailableToTransfer < CreditTransferRequest.MINIMUM_VALUE_WITHOUT_TRANSFER_FEE) return new AsaasError("transfer.denied.automaticTransferWithFee")

        AsaasError asaasError = creditTransferRequestService.getDenialReasonIfExists(customer, bankAccountInfo.id, valueAvailableToTransfer, [isAutomaticTransfer: true])
        if (asaasError) return asaasError

        return null
    }

    private void createAutomaticCreditTransferRequest(Customer customer, Long bankAccountInfoId, BigDecimal valueAvailableToTransfer) {
        AsaasLogger.info("AutomaticTransferService.createAutomaticCreditTransferRequest() -> Customer: [${customer.id}], Value: [${valueAvailableToTransfer}]")

        creditTransferRequestService.save(customer, bankAccountInfoId, valueAvailableToTransfer, [isAutomaticTransfer: true])
    }
}

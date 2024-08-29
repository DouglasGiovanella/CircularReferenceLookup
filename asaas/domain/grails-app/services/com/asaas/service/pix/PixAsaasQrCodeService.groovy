package com.asaas.service.pix

import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.PixAsaasQrCode
import com.asaas.exception.BusinessException
import com.asaas.integration.pix.builder.PixBrCodeBuilder
import com.asaas.payment.PaymentInterestCalculator
import com.asaas.pix.PixQrCodeStatus
import com.asaas.pix.builder.PixDynamicQrCodeValuesBuilder
import com.asaas.utils.BigDecimalUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixAsaasQrCodeService {

    def bradescoPixQrCodeManagerService
    def paymentDiscountConfigService
    def pixQrCodeErrorLogService
    def pixQrCodeService

    public PixAsaasQrCode saveIfNecessary(Payment payment) {
        BusinessValidation validatedBusiness = pixQrCodeService.validateCanBeGenerated(payment)
        if (!validatedBusiness.isValid()) throw new BusinessException(validatedBusiness.getFirstErrorMessage())

        PixAsaasQrCode pixAsaasQrCode = PixAsaasQrCode.dynamicWithAsaasAddressKeyAvailableForPayment([payment: payment, customer: payment.provider]).get()
        if (pixAsaasQrCode) return pixAsaasQrCode

        pixAsaasQrCode = new PixAsaasQrCode()
        if (payment.customerAccount.cpfCnpj) {
            pixAsaasQrCode.payerCpfCnpj = Utils.removeNonNumeric(payment.customerAccount.cpfCnpj)
            pixAsaasQrCode.payerName = payment.customerAccount.name
        }
        pixAsaasQrCode.payment = payment
        pixAsaasQrCode.customer = payment.provider
        pixAsaasQrCode.dueDate = payment.dueDate
        pixAsaasQrCode.originalValue = Utils.toBigDecimal(payment.value)
        pixAsaasQrCode.publicId = UUID.randomUUID().toString()
        pixAsaasQrCode.expirationDate = CustomDateUtils.setTimeToEndOfDay(new Date())
        pixAsaasQrCode.description = PixAsaasQrCode.buildDescription(pixAsaasQrCode)

        Map feeValues = calculateFineAndInterestPercentage(payment)
        pixAsaasQrCode.finePercentage = Utils.toBigDecimal(feeValues.finePercentage) ?: 0
        pixAsaasQrCode.interestPercentage = Utils.toBigDecimal(feeValues.interestPercentage) ?: 0
        pixAsaasQrCode.discountPercentage = Utils.toBigDecimal(calculateDiscountPercentage(payment)) ?: 0

        PixAsaasQrCode pixAsaasQrCodeValidated = validate(pixAsaasQrCode)
        if (pixAsaasQrCodeValidated.hasErrors()) throw new BusinessException(DomainUtils.getValidationMessages(pixAsaasQrCodeValidated.getErrors()).first())

        setAsAwaitingSynchronization(pixAsaasQrCode)

        pixAsaasQrCode.conciliationIdentifier = PixAsaasQrCode.buildConciliationIdentifier(pixAsaasQrCode)
        pixAsaasQrCode.save(failOnError: true)

        return create(pixAsaasQrCode)
    }

    public PixAsaasQrCode updateValuesIfNecessary(Payment payment) {
        PixAsaasQrCode pixAsaasQrCode = PixAsaasQrCode.query([payment: payment, customer: payment.provider]).get()
        if (!pixAsaasQrCode) return null
        if (pixAsaasQrCode.isExpired()) return setAsDisabled(pixAsaasQrCode)
        if (!pixQrCodeService.validateCanBeGenerated(payment).isValid()) return setAsDisabled(pixAsaasQrCode)

        pixAsaasQrCode.payment = payment
        pixAsaasQrCode.dueDate = pixAsaasQrCode.payment.dueDate
        pixAsaasQrCode.originalValue = Utils.toBigDecimal(payment.value) ?: 0
        pixAsaasQrCode.description = PixAsaasQrCode.buildDescription(pixAsaasQrCode)

        if (pixAsaasQrCode.payment.customerAccount.cpfCnpj) {
            pixAsaasQrCode.payerCpfCnpj = Utils.removeNonNumeric(pixAsaasQrCode.payment.customerAccount.cpfCnpj)
            pixAsaasQrCode.payerName = pixAsaasQrCode.payment.customerAccount.name
        }

        Map values = calculateFineAndInterestPercentage(pixAsaasQrCode.payment)
        pixAsaasQrCode.finePercentage = Utils.toBigDecimal(values.finePercentage) ?: 0
        pixAsaasQrCode.interestPercentage = Utils.toBigDecimal(values.interestPercentage) ?: 0
        pixAsaasQrCode.discountPercentage = Utils.toBigDecimal(calculateDiscountPercentage(pixAsaasQrCode.payment)) ?: 0

        PixAsaasQrCode pixAsaasQrCodeValidated = validate(pixAsaasQrCode)
        if (pixAsaasQrCodeValidated.hasErrors()) {
            return setAsDisabled(pixAsaasQrCode)
        }

        if (pixAsaasQrCode.isDirty()) {
            setAsAwaitingSynchronization(pixAsaasQrCode)
        }

        return pixAsaasQrCode
    }

    public void processAwaitingSynchronization() {
        List<Long> pixAsaasQrCodeIdList = PixAsaasQrCode.awaitingSynchronization([ignoreCustomer: true, column: "id"]).list()

        for (Long id : pixAsaasQrCodeIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixAsaasQrCode pixAsaasQrCode = PixAsaasQrCode.get(id)
                update(pixAsaasQrCode)
            }, [logErrorMessage: "PixAsaasQrCodeService.processAwaitingSynchronization() -> Erro ao sincronizar dados do QR Code. [pixAsaasQrCode.id: ${id}]"])
        }
    }

    public void processExpiredQrCodeWithAsaasAddressKey() {
        List<Long> pixAsaasQrCodeIdList = PixAsaasQrCode.dynamicWithAsaasAddressKeyExpired([ignoreCustomer: true, column: "id"]).list()

        for (Long id : pixAsaasQrCodeIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixAsaasQrCode pixAsaasQrCode = PixAsaasQrCode.get(id)
                setAsDisabled(pixAsaasQrCode)
            }, [logErrorMessage: "PixAsaasQrCodeService.processAwaitingDisableQrCodeWithAsaasAddressKey() -> Erro ao desabilitar QR Code. [pixAsaasQrCode.id: ${id}]"])
        }
    }

    public void setAsAwaitingSynchronization(PixAsaasQrCode pixAsaasQrCode) {
        pixAsaasQrCode.status = PixQrCodeStatus.AWAITING_SYNCHRONIZATION
        pixAsaasQrCode.save(failOnError: true)
    }

    private PixAsaasQrCode validate(PixAsaasQrCode pixQrCode) {
        if (!pixQrCode.dueDate) {
            Boolean hasParametersDependingOnDueDate = (pixQrCode.interestPercentage > 0 || pixQrCode.finePercentage > 0 || pixQrCode.discountPercentage > 0)
            if (hasParametersDependingOnDueDate) DomainUtils.addError(pixQrCode, "Valores de juros, multa e desconto só podem ser informados quando uma data de vencimento existir.")
        }

        if (pixQrCode.payerName) {
            if (!pixQrCode.payerCpfCnpj) DomainUtils.addError(pixQrCode, "Como você informou o nome do pagador, o CPF/CNPJ se torna obrigatório.")
        }

        if (pixQrCode.description) {
            if (pixQrCode.description.length() > PixAsaasQrCode.DESCRIPTION_MAX_LENGTH) {
                DomainUtils.addError(pixQrCode, "A descrição deste QrCode não deve ultrapassar ${PixAsaasQrCode.DESCRIPTION_MAX_LENGTH} caracteres.")
            }
        }

        if (pixQrCode.payment) {
            Boolean paymentCannotBePaidUsingPix = pixQrCode.payment.cannotBePaidAnymore()
            if (!paymentCannotBePaidUsingPix) paymentCannotBePaidUsingPix = pixQrCode.payment.isReceivingProcessInitiated()
            if (!paymentCannotBePaidUsingPix) paymentCannotBePaidUsingPix = pixQrCode.payment.deleted
            if (!paymentCannotBePaidUsingPix) paymentCannotBePaidUsingPix = pixQrCode.payment.provider.boletoIsDisabled()
            if (paymentCannotBePaidUsingPix) DomainUtils.addError(pixQrCode, "Esta cobrança não pode ser paga utilizando o Pix.")
        }

        return pixQrCode
    }

    private PixAsaasQrCode create(PixAsaasQrCode pixAsaasQrCode) {
        String jwsUrl = bradescoPixQrCodeManagerService.createDynamic(pixAsaasQrCode)
        pixAsaasQrCode.jwsUrl = jwsUrl
        pixAsaasQrCode.payload = buildPayload(pixAsaasQrCode)
        pixAsaasQrCode.status = PixQrCodeStatus.SYNCHRONIZED
        pixAsaasQrCode.save(failOnError: true)
        return pixAsaasQrCode
    }

    private PixAsaasQrCode update(PixAsaasQrCode pixAsaasQrCode) {
        if (pixAsaasQrCode.status.isError()) throw new RuntimeException(Utils.getMessageProperty("PixAsaasQrCodeWithAsaasAddressKey.error.invalidStatus"))

        Map response
        if (pixAsaasQrCode.payment.deleted) {
            response = bradescoPixQrCodeManagerService.deleteDynamic(pixAsaasQrCode)
        } else {
            response = bradescoPixQrCodeManagerService.updateDynamic(pixAsaasQrCode)
        }
        if (response.withoutExternalResponse) {
            pixQrCodeErrorLogService.saveConnectionErrorOnSynchronize(pixAsaasQrCode)
            return pixAsaasQrCode
        } else if (response.error) {
            return setAsDisabled(pixAsaasQrCode)
        }

        pixAsaasQrCode.status = (response.status as PixQrCodeStatus)
        pixAsaasQrCode.jwsUrl = null
        if (!pixAsaasQrCode.status.isDisabled()) {
            pixAsaasQrCode.jwsUrl = response.jwsUrl
            pixAsaasQrCode.payload = buildPayload(pixAsaasQrCode)
        }
        pixAsaasQrCode.save(failOnError: true)

        return pixAsaasQrCode
    }

    private String buildPayload(PixAsaasQrCode pixAsaasQrCode) {
        PixDynamicQrCodeValuesBuilder qrCodeValuesBuilder = new PixDynamicQrCodeValuesBuilder(pixAsaasQrCode.payment)
        PixBrCodeBuilder pixBrCodeBuilder = new PixBrCodeBuilder(pixAsaasQrCode.jwsUrl, qrCodeValuesBuilder.totalValue)

        return pixBrCodeBuilder.payload
    }

    private PixAsaasQrCode setAsDisabled(PixAsaasQrCode pixAsaasQrCode) {
        pixAsaasQrCode.deleted = true
        pixAsaasQrCode.status = PixQrCodeStatus.DISABLED
        pixAsaasQrCode.save(failOnError: true)
        return pixAsaasQrCode
    }

    private BigDecimal calculateDiscountPercentage(Payment payment) {
        Map discountInfo = paymentDiscountConfigService.calculatePaymentDiscountInfo(payment)
        if (!discountInfo) return 0

        return BigDecimalUtils.roundHalfUp(((discountInfo.discountValue / payment.value) * 100))
    }

    private Map calculateFineAndInterestPercentage(Payment payment) {
        PaymentInterestCalculator interestCalculator = new PaymentInterestCalculator(payment, new Date())
        interestCalculator.execute()

        Map values = [interestPercentage: 0, finePercentage: 0]
        if (interestCalculator) {
            values.interestPercentage =  BigDecimalUtils.roundHalfUp(interestCalculator.monthlyInterestPercentage)
            values.finePercentage = BigDecimalUtils.roundHalfUp(interestCalculator.finePercentage)
        }
        return values
    }

}

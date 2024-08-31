package com.asaas.service.payment

import com.asaas.domain.customer.Customer
import com.asaas.domain.installment.Installment
import com.asaas.domain.payment.Payment
import com.asaas.domain.receivableanticipation.ReceivableAnticipation
import com.asaas.domain.receivableanticipation.ReceivableAnticipationOriginRequesterInfoMethod
import com.asaas.domain.subscription.Subscription
import com.asaas.receivableanticipation.ReceivableAnticipationDocumentVO
import com.asaas.receivableanticipation.adapter.CreateReceivableAnticipationAdapter
import com.asaas.utils.DomainUtils
import com.asaas.utils.RequestUtils
import com.asaas.wizard.receiptdata.PaymentWizardBankSlipAnticipationOptionVO
import com.asaas.wizard.receiptdata.PaymentWizardBankSlipAnticipationVO

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class PaymentWizardReceivableAnticipationService {

    def fileService
    def receivableAnticipationService
    def receivableAnticipationAgreementService

    public void saveCreditCardAnticipation(Customer customer, List<Payment> paymentList) {
        Payment firstPayment = paymentList.first()

        if (firstPayment.installment) {
            save(customer, null, firstPayment.installment, null)
            return
        }

        if (firstPayment.subscription) {
            Subscription subscription = firstPayment.subscription
            subscription.automaticAnticipation = true
            subscription.save(failOnError: true)
        }

        save(customer, firstPayment, null, null)
    }

    public void saveBankSlipAnticipation(Customer customer, PaymentWizardBankSlipAnticipationVO bankSlipAnticipationVO) {
        List<Long> temporaryFileToBeRemovedIdList = []

        for (PaymentWizardBankSlipAnticipationOptionVO optionVO : bankSlipAnticipationVO.anticipationOptionList) {
            save(customer, optionVO.payment, null, optionVO.documentList)
            temporaryFileToBeRemovedIdList.addAll(optionVO.documentList*.temporaryFileId)
        }

        fileService.removeTemporaryFileList(customer, temporaryFileToBeRemovedIdList.unique())
    }

    private void save(Customer customer, Payment payment, Installment installment, List<ReceivableAnticipationDocumentVO> receivableAnticipationDocumentVOList) {
        receivableAnticipationAgreementService.saveIfNecessary(customer, RequestUtils.getRequest())
        CreateReceivableAnticipationAdapter createReceivableAnticipationAdapter = CreateReceivableAnticipationAdapter.build(installment, payment, receivableAnticipationDocumentVOList, ReceivableAnticipationOriginRequesterInfoMethod.WIZARD)
        ReceivableAnticipation anticipation = receivableAnticipationService.save(createReceivableAnticipationAdapter)

        if (anticipation.hasErrors()) {
            String reason = anticipation.payment?.receivableAnticipationDisabledReason ?: anticipation.installment?.receivableAnticipationDisabledReason
            if (reason) DomainUtils.addError(anticipation, reason)

            throw new ValidationException(null, anticipation.errors)
        }
    }
}

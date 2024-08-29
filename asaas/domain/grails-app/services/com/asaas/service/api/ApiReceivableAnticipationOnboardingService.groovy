package com.asaas.service.api

import com.asaas.domain.customer.Customer
import com.asaas.domain.transfer.Transfer
import com.asaas.receivableanticipation.ReceivableAnticipationCalculator
import com.asaas.receivableanticipation.validator.ReceivableAnticipationValidator
import com.asaas.status.Status

import grails.transaction.Transactional

@Transactional
class ApiReceivableAnticipationOnboardingService extends ApiBaseService {

    public Map firstUse(Map params) {
        Customer customer = getProviderInstance(params)

        Map responseItem = [:]

        responseItem.daysAfterTransferToAnticipateBankSlip = customer.getRequiredDaysAfterTransferToAnticipateBankslip()
        responseItem.daysAfterTransferToAnticipateCreditCard = customer.getRequiredDaysAfterTransferToAnticipateCreditCard()
        responseItem.daysToEnableBankSlipAnticipation = ReceivableAnticipationCalculator.calculateDaysToEnableBankSlipAnticipation(customer)
        responseItem.hasCreatedPayments = customer.hasCreatedPayments()
        responseItem.hasCreatedTransfer = Transfer.query([customer: customer, exists: true]).get().asBoolean()
        responseItem.hasConfirmedTransfer = ReceivableAnticipationValidator.hasConfirmedTransfer(customer)
        responseItem.isAccountActive = customer.getIsActive()
        responseItem.commercialInfoStatus = customer.customerRegisterStatus.commercialInfoStatus.toString()
        responseItem.documentStatus = customer.customerRegisterStatus.documentStatus.toString()
        responseItem.canAnticipate = customer.canAnticipate()

        if (customer.bankAccountInfoApprovalIsNotRequired()) {
            responseItem.bankAccountInfoStatus = Status.APPROVED.toString()
        } else {
            responseItem.bankAccountInfoStatus = customer.customerRegisterStatus.bankAccountInfoStatus.toString()
        }

        return ApiResponseBuilder.buildSuccess(responseItem)
    }
}

package com.asaas.service.customercommission

import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.repository.CustomerCommissionItemRepository
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommissionConfig
import com.asaas.domain.customercommission.CustomerCommissionItem
import com.asaas.log.AsaasLogger
import com.asaas.service.asyncaction.CustomerCommissionAsyncActionService
import com.asaas.service.stage.CustomerStageService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional

@GrailsCompileStatic
@Transactional
class CustomerCommissionConvertedCustomerService {

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    CustomerCommissionService customerCommissionService
    CustomerStageService customerStageService

    public void createCommissions() {
        final Integer maxItemsPerCycle = 600
        List<Map> pendingCreateCustomerCommissionAsyncActionList = customerCommissionAsyncActionService.listPending(CustomerCommissionAsyncActionType.CREATE_CONVERTED_CUSTOMER_CUSTOMER_COMMISSION, maxItemsPerCycle)
        if (!pendingCreateCustomerCommissionAsyncActionList) return

        Utils.forEachWithFlushSession(pendingCreateCustomerCommissionAsyncActionList, 50, { Map asyncActionData ->
            Long asyncActionId = Utils.toLong(asyncActionData.asyncActionId)

            Utils.withNewTransactionAndRollbackOnError({
                Long commissionConfigId = Utils.toLong(asyncActionData.commissionConfigId)
                CustomerCommissionConfig config = CustomerCommissionConfig.read(commissionConfigId)
                if (!config.convertedCustomerFixedValue) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                Long commissionedAccountId = Utils.toLong(asyncActionData.commissionedAccountId)
                Customer ownedAccount = Customer.read(commissionedAccountId)
                Date transactionDate = CustomDateUtils.getDateFromStringWithDateParse(asyncActionData.transactionDate.toString())

                Boolean existsCustomerCommission = customerCommissionService.existsCustomerCommission(config.customer, ownedAccount, transactionDate, CustomerCommissionType.CONVERTED_CUSTOMER)
                if (existsCustomerCommission) {
                    customerCommissionAsyncActionService.delete(asyncActionId)
                    return
                }

                CustomerCommissionItem commissionItem = buildCommissionItem(ownedAccount, config, transactionDate)
                if (commissionItem) customerCommissionService.saveWithItem(config.customer, ownedAccount, transactionDate, CustomerCommissionType.CONVERTED_CUSTOMER, commissionItem)

                customerCommissionAsyncActionService.delete(asyncActionId)
            }, [onError : { Exception exception ->
                if (Utils.isLock(exception)) {
                    AsaasLogger.warn("CustomerCommissionConvertedCustomerService.createCommissions >> Lock ao processar a AsyncAction de comissão [${asyncActionId}]")
                    return
                }

                AsaasLogger.error("CustomerCommissionConvertedCustomerService.createCommissions >> Erro ao criar comissão AsyncActionData: ${asyncActionData}")
                customerCommissionAsyncActionService.setAsErrorWithNewTransaction(asyncActionId)
            }])
        })
    }

    private CustomerCommissionItem buildCommissionItem(Customer ownedAccount, CustomerCommissionConfig config, Date transactionDate) {
        if (!isOwnedAccountEligibleForConvertedCustomerCommission(ownedAccount, transactionDate)) return null

        CustomerCommissionItem item = new CustomerCommissionItem()
        item.customer = ownedAccount
        item.type = CustomerCommissionType.CONVERTED_CUSTOMER
        item.value = config.convertedCustomerFixedValue

        return item
    }

    private Boolean isOwnedAccountEligibleForConvertedCustomerCommission(Customer ownedAccount, Date transactionDate) {
        Boolean isAlreadyCommissioned = CustomerCommissionItemRepository.query([customer: ownedAccount, type: CustomerCommissionType.CONVERTED_CUSTOMER]).exists()
        if (isAlreadyCommissioned) return false

        if (!ownedAccount.getIsActive()) return false
        if (!ownedAccount.customerRegisterStatus.generalApproval.isApproved()) return false
        if (ownedAccount.suspectedOfFraud) return false

        Date convertedDate = customerStageService.findConvertedDate(ownedAccount)
        if (!convertedDate) return false

        Boolean wasConvertedRecently = convertedDate >= transactionDate
        if (wasConvertedRecently) return true

        Boolean wasApprovedRecently = ownedAccount.customerRegisterStatus.lastGeneralStatusChange >= transactionDate
        if (wasApprovedRecently) return true

        return false
    }
}

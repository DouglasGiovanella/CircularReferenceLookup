package com.asaas.service.asyncaction

import com.asaas.asyncaction.AsyncActionStatus
import com.asaas.asyncaction.CustomerCommissionAsyncActionType
import com.asaas.asyncaction.builder.AsyncActionDataBuilder
import com.asaas.asyncaction.repository.CustomerCommissionAsyncActionRepository
import com.asaas.customercommission.GenerateCustomerCommissionQueueAdapter
import com.asaas.domain.asyncAction.CustomerCommissionAsyncAction
import com.asaas.log.AsaasLogger

import com.asaas.utils.DatabaseBatchUtils
import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy

@GrailsCompileStatic
@Transactional
class CustomerCommissionAsyncActionService {

    BaseAsyncActionService baseAsyncActionService
    TransactionAwareDataSourceProxy dataSource

    public void createQueue(GenerateCustomerCommissionQueueAdapter createCustomerCommissionQueueAdapter, Date transactionDate) {
        List<Map> customerCommissionAsyncActionDataList = []

        for (Long commissionedAccountId : createCustomerCommissionQueueAdapter.commissionedAccountIdList) {
            Map actionData = [commissionedAccountId: commissionedAccountId, commissionConfigId: createCustomerCommissionQueueAdapter.configId, transactionDate: transactionDate.toString()]
            String actionDataJsonString = AsyncActionDataBuilder.parseToJsonString(actionData)

            for (CustomerCommissionAsyncActionType type : createCustomerCommissionQueueAdapter.customerCommissionAsyncActionTypeList) {
                if (hasAsyncActionPendingWithSameParameters(commissionedAccountId, actionData, type)) {
                    AsaasLogger.warn("CustomerCommissionAsyncActionService.createQueue >> Já existe um registro de processamento assíncrono de comissão pendente para o tipo [${type}] com os dados ${actionData}.")
                    continue
                }

                Map customerCommissionAsyncActionMap = [
                    version: 0,
                    date_created: new Date(),
                    last_updated: new Date(),
                    deleted: false,
                    status: AsyncActionStatus.PENDING.toString(),
                    type: type.toString(),
                    customer_id: commissionedAccountId,
                    action_data: actionDataJsonString,
                    action_data_hash: AsyncActionDataBuilder.buildHash(actionDataJsonString)
                ]

                customerCommissionAsyncActionDataList.add(customerCommissionAsyncActionMap)
            }
        }

        DatabaseBatchUtils.insertInBatch(dataSource, "queues.customer_commission_async_action", customerCommissionAsyncActionDataList)
    }

    private Boolean hasAsyncActionPendingWithSameParameters(Long customerId, Map actionData, CustomerCommissionAsyncActionType type) {
        String actionDataJson = AsyncActionDataBuilder.parseToJsonString(actionData)
        String actionDataHash = AsyncActionDataBuilder.buildHash(actionDataJson)

        return CustomerCommissionAsyncActionRepository.query([customerId: customerId, actionDataHash: actionDataHash, status: AsyncActionStatus.PENDING, type: type]).exists()
    }

    public List<Map> listPending(CustomerCommissionAsyncActionType type, Integer maxItemsPerCycle) {
        Map search = [type: type]
        List<Map> asyncActionDataList = baseAsyncActionService.listPendingData(CustomerCommissionAsyncAction, search, maxItemsPerCycle)

        return asyncActionDataList
    }

    public Boolean hasPendingCreditCustomerCommission(Long customerId, CustomerCommissionAsyncActionType type) {
        return CustomerCommissionAsyncActionRepository.query([customerId: customerId, type: type, status: AsyncActionStatus.PENDING]).exists()
    }

    public void delete(Long id) {
        CustomerCommissionAsyncAction customerCommissionAsyncAction = CustomerCommissionAsyncAction.get(id)
        customerCommissionAsyncAction.delete(failOnError: true)
    }

    public void setAsErrorWithNewTransaction(Long id) {
        CustomerCommissionAsyncAction.withNewTransaction {
            CustomerCommissionAsyncAction customerCommissionAsyncAction = CustomerCommissionAsyncAction.get(id)
            customerCommissionAsyncAction.status = AsyncActionStatus.ERROR
            customerCommissionAsyncAction.save(failOnError: true)
        }
    }
}

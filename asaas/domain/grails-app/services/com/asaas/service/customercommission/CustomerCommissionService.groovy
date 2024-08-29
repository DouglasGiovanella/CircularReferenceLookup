package com.asaas.service.customercommission

import com.asaas.customercommission.CustomerCommissionItemInsertDataListAdapter
import com.asaas.customercommission.CustomerCommissionStatus
import com.asaas.customercommission.CustomerCommissionType
import com.asaas.customercommission.GenerateCustomerCommissionQueueAdapter
import com.asaas.customercommission.itemlist.CustomerCommissionDataBuilder
import com.asaas.customercommission.repository.CustomerCommissionConfigQueueInfoRepository
import com.asaas.customercommission.repository.CustomerCommissionItemRepository
import com.asaas.customercommission.repository.CustomerCommissionRepository
import com.asaas.domain.customer.Customer
import com.asaas.domain.customercommission.CustomerCommission
import com.asaas.domain.customercommission.CustomerCommissionConfigQueueInfo
import com.asaas.domain.customercommission.CustomerCommissionItem
import com.asaas.domain.financialtransaction.FinancialTransaction
import com.asaas.service.asyncaction.CustomerCommissionAsyncActionService
import com.asaas.service.financialtransaction.FinancialTransactionService
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.DatabaseBatchUtils
import com.asaas.utils.Utils

import grails.compiler.GrailsCompileStatic
import grails.transaction.Transactional
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy

@GrailsCompileStatic
@Transactional
class CustomerCommissionService {

    private static final Integer FLUSH_EVERY = 50

    CustomerCommissionAsyncActionService customerCommissionAsyncActionService
    TransactionAwareDataSourceProxy dataSource
    FinancialTransactionService financialTransactionService

    public Boolean generateCommissionPendingQueue(Date transactionDate) {
        final Integer maxConfigItems = 5

        Date lastExecutionOfDay = CustomDateUtils.setTime(new Date(), CustomerCommissionConfigQueueInfo.FINISH_HOUR_TO_GENERATE_QUEUE, 0, 0)
        Boolean isLastExecutionOfDay = new Date() >= lastExecutionOfDay

        Map listParams = [:]
        if (!isLastExecutionOfDay) listParams.max = maxConfigItems

        Date currentDateWithTimeToEndOfDay = CustomDateUtils.setTimeToEndOfDay(new Date())
        List<Long> customerCommissionConfigQueueInfoIdList = CustomerCommissionConfigQueueInfoRepository.query(["nextCreateDate[le]": currentDateWithTimeToEndOfDay]).column("id").disableSort().list(listParams) as List<Long>

        Utils.forEachWithFlushSession(customerCommissionConfigQueueInfoIdList, 1, { Long queueInfoId ->
            Utils.withNewTransactionAndRollbackOnError({
                CustomerCommissionConfigQueueInfo customerCommissionConfigQueueInfo = CustomerCommissionConfigQueueInfo.get(queueInfoId)
                GenerateCustomerCommissionQueueAdapter generateQueueAdapter = new GenerateCustomerCommissionQueueAdapter(customerCommissionConfigQueueInfo, isLastExecutionOfDay)

                if (!generateQueueAdapter.commissionedAccountIdList) {
                    prepareQueueInfoToNextDay(customerCommissionConfigQueueInfo)
                    return
                }

                customerCommissionAsyncActionService.createQueue(generateQueueAdapter, transactionDate)
                updateQueueInfoAfterProcess(customerCommissionConfigQueueInfo, generateQueueAdapter)
            }, [logErrorMessage: "CustomerCommissionService.generateCommissionPendingQueue >> Ocorreu um erro ao agendar a criação da comissão das seguintes informações. Transaction Date: ${CustomDateUtils.fromDate(transactionDate)}", appendBatchToLogErrorMessage: true])
        })

        return customerCommissionConfigQueueInfoIdList.size() > 0
    }

    public void settleCommissions(Date transactionDate) {
        Map search = [:]
        search."transactionDate[le]" = transactionDate
        search.settled = false
        search.status = CustomerCommissionStatus.ITEM_CREATION_DONE
        search.valueGreaterThanZeroOrRefund = true

        final Integer maxCustomersPerCycle = 200
        List<Long> accountOwnerWithCommissionToSettleIdList = CustomerCommissionRepository.query(search).distinct("customer.id").disableSort().list(max: maxCustomersPerCycle) as List<Long>

        if (!accountOwnerWithCommissionToSettleIdList) return

        Utils.forEachWithFlushSession(accountOwnerWithCommissionToSettleIdList, FLUSH_EVERY, { Long accountOwnerId ->
            Utils.withNewTransactionAndRollbackOnError({
                Customer accountOwner = Customer.read(accountOwnerId)

                search.customer = accountOwner
                List<CustomerCommissionType> typeList = CustomerCommissionRepository.query(search).distinct("type").disableSort().list() as List<CustomerCommissionType>

                for (CustomerCommissionType type : typeList) {
                    search.type = type
                    List<CustomerCommission> customerCommissionList = CustomerCommissionRepository.query(search).disableSort().readOnly().list() as List<CustomerCommission>

                    if (!customerCommissionList) continue

                    settle(accountOwner, type, customerCommissionList)
                }
            }, [logErrorMessage: "CustomerCommissionService.settleCommissions >> Erro ao liquidar comissões do cliente ID: ${accountOwnerId}"])
        })
    }

    public FinancialTransaction settle(Customer accountOwner, CustomerCommissionType type, List<CustomerCommission> customerCommissionList) {
        FinancialTransaction financialTransaction = financialTransactionService.saveCustomerCommission(accountOwner, type, customerCommissionList)

        updateCommissionsToSettled(customerCommissionList.id)

        return financialTransaction
    }

    public CustomerCommission saveWithItem(Customer customer, Customer ownedAccount, Date transactionDate, CustomerCommissionType type, CustomerCommissionItem customerCommissionItem) {
        CustomerCommission customerCommission = save(customer, ownedAccount, transactionDate, type)

        customerCommissionItem.customerCommission = customerCommission
        customerCommissionItem.publicId = UUID.randomUUID()
        customerCommissionItem.save(failOnError: true)

        if (customerCommissionItem.asaasFee) customerCommission.asaasFee = customerCommissionItem.asaasFee

        customerCommission.value = customerCommissionItem.value
        customerCommission.status = CustomerCommissionStatus.ITEM_CREATION_DONE
        customerCommission.save(failOnError: true)

        return customerCommission
    }

    public CustomerCommission save(Customer customer, Customer ownedAccount, Date transactionDate, CustomerCommissionType type) {
        CustomerCommission customerCommission = new CustomerCommission()
        customerCommission.customer = customer
        customerCommission.ownedAccount = ownedAccount
        customerCommission.transactionDate = transactionDate
        customerCommission.type = type
        customerCommission.asaasFee = 0.0
        customerCommission.value = 0.0
        customerCommission.publicId = UUID.randomUUID()
        customerCommission.save(failOnError: true)

        return customerCommission
    }

    public void saveInBatchIfHasItemsAndUpdateCustomerCommission(CustomerCommissionItemInsertDataListAdapter itemInsertDataListAdapter, CustomerCommission customerCommission, Boolean isCreationItemsDone) {
        Boolean existsCommissionItem = CustomerCommissionItemRepository.query([customerCommissionId: customerCommission.id]).exists()
        if (!existsCommissionItem && !itemInsertDataListAdapter.customerCommissionItemDataList) {
            customerCommission.delete(failOnError: true)
            return
        }

        if (!itemInsertDataListAdapter.customerCommissionItemDataList) return

        DatabaseBatchUtils.insertInBatch(dataSource, "customer_commission_item", itemInsertDataListAdapter.customerCommissionItemDataList)
        customerCommission.asaasFee += itemInsertDataListAdapter.totalAsaasFee
        customerCommission.value += itemInsertDataListAdapter.totalValue
        if (isCreationItemsDone) customerCommission.status = CustomerCommissionStatus.ITEM_CREATION_DONE
        customerCommission.save(failOnError: true)
    }

    public Boolean existsCustomerCommission(Customer customer, Customer ownedAccount, Date transactionDate, CustomerCommissionType commissionType) {
        return CustomerCommissionRepository.query(customer: customer, ownedAccount: ownedAccount, type: commissionType, transactionDate: transactionDate).exists()
    }

    public CustomerCommission findExistingCustomerCommissionWithSameParameters(Customer customer, Customer ownedAccount, Date transactionDate, CustomerCommissionType commissionType) {
        return CustomerCommissionRepository.query(customer: customer, ownedAccount: ownedAccount, type: commissionType, transactionDate: transactionDate, settled: false).get()
    }

    public void finishItemCreationIfNecessary(List<Map> financialTransactionMapList, CustomerCommission customerCommission, Long asyncActionId) {
        Boolean isCreateItemsDone = !financialTransactionMapList || financialTransactionMapList?.size() < CustomerCommissionDataBuilder.MAX_ITEMS
        if (customerCommission && isCreateItemsDone) changeCreationCommissionItemsToDone(customerCommission)

        if (isCreateItemsDone) customerCommissionAsyncActionService.delete(asyncActionId)
    }

    public void changeCreationCommissionItemsToDone(CustomerCommission customerCommission) {
         customerCommission.status = CustomerCommissionStatus.ITEM_CREATION_DONE
         customerCommission.save(failOnError: true)
    }

    private void updateCommissionsToSettled(List<Long> customerCommissionIdList) {
        if (!customerCommissionIdList) return

        CustomerCommission.executeUpdate("""
           UPDATE CustomerCommission
              SET settled = :settled, lastUpdated = :lastUpdated, version = version + 1
            WHERE deleted = false AND id IN :customerCommissionIdList AND settled = false""",
            [
                settled: true,
                lastUpdated: new Date(),
                customerCommissionIdList: customerCommissionIdList
            ])
    }

    private void prepareQueueInfoToNextDay(CustomerCommissionConfigQueueInfo customerCommissionConfigQueueInfo) {
        customerCommissionConfigQueueInfo.nextCreateDate = CustomDateUtils.tomorrow()
        customerCommissionConfigQueueInfo.lastChildCustomerIdCreated = null
        customerCommissionConfigQueueInfo.lastSalesPartnerCustomerIdCreated = null
        customerCommissionConfigQueueInfo.save(failOnError: true)
    }

    private void updateQueueInfoAfterProcess(CustomerCommissionConfigQueueInfo customerCommissionConfigQueueInfo, GenerateCustomerCommissionQueueAdapter generateQueueAdapter) {
        if (generateQueueAdapter.commissionedAccountIdList.size() < GenerateCustomerCommissionQueueAdapter.MAX_COMMISSIONED_ACCOUNT_ID_ITEMS) {
            prepareQueueInfoToNextDay(customerCommissionConfigQueueInfo)
            return
        }

        if (generateQueueAdapter.lastChildCustomerIdCreated) customerCommissionConfigQueueInfo.lastChildCustomerIdCreated = generateQueueAdapter.lastChildCustomerIdCreated
        if (generateQueueAdapter.lastSalesPartnerCustomerIdCreated) customerCommissionConfigQueueInfo.lastSalesPartnerCustomerIdCreated = generateQueueAdapter.lastSalesPartnerCustomerIdCreated

        customerCommissionConfigQueueInfo.save(failOnError: true)
    }
}

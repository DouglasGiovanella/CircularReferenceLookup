package com.asaas.service.customer


import com.asaas.customer.CustomerSegment
import com.asaas.domain.businessgroup.BusinessGroup
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessGroup
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class BusinessGroupSegmentReplicationService {

    def asyncActionService
    def customerAdminService
    def customerSegmentService

    private final Integer MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION = 300

    public void start() {
        for (Map asyncActionData in asyncActionService.listPendingBusinessGroupSegmentToReplicate()) {
            Utils.withNewTransactionAndRollbackOnError({
                BusinessGroup businessGroup = BusinessGroup.get(Utils.toLong(asyncActionData.businessGroupId))
                CustomerSegment segment = CustomerSegment.convert(asyncActionData.segment)

                applyReplicationForCustomersWithoutAccountOwner(businessGroup, segment)
                applyReplicationForCustomersWithAccountOwner(businessGroup, segment)

                if (!hasCustomerToReplicate(businessGroup, segment)) asyncActionService.delete(asyncActionData.asyncActionId)
            }, [logErrorMessage: "BusinessGroupSegmentReplicationService.start >> Erro no processamento da replicação dos segmentos dos clientes vinculados ao grupo empresarial. ID: ${asyncActionData.asyncActionId}",
                onError: { asyncActionService.setAsErrorWithNewTransaction(asyncActionData.asyncActionId) }])
        }
    }

    private void applyReplicationForCustomersWithoutAccountOwner(BusinessGroup businessGroup, CustomerSegment segment) {
        Map search = [column: "customer.id", businessGroupId: businessGroup.id, "customerSegment[ne]": segment, "accountOwner[isNull]": true]
        List<Long> businessGroupCustomerIdList = CustomerBusinessGroup.query(search).list(max: MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION / 2)

        applyReplication(businessGroupCustomerIdList, segment)
    }

    private void applyReplicationForCustomersWithAccountOwner(BusinessGroup businessGroup, CustomerSegment segment) {
        Map search = [column: "customer", businessGroupId: businessGroup.id, "customerSegment[ne]": segment, "accountOwner[isNotNull]": true]

        List<Customer> customerListWithoutInheritance = CustomerBusinessGroup.query(search).list(max: MAXIMUM_NUMBER_OF_DATA_PER_MIGRATION / 2).findAll {
            !customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(it.accountOwner)
        }
        List<Long> customerWithoutInheritanceIdList = customerListWithoutInheritance.collect{ it.id }

        applyReplication(customerWithoutInheritanceIdList, segment)
    }

    private void applyReplication(List<Long> businessGroupCustomerIdList, CustomerSegment segment) {
        Utils.forEachWithFlushSession(businessGroupCustomerIdList, 50, { Long customerId ->
            Customer customer = Customer.get(customerId)
            AsaasLogger.info("Alterando segmento da conta vinculada ${customerId} de [${customer.segment}] para [${segment}]")
            customerSegmentService.changeCustomerSegmentAndUpdateAccountManager(customer, segment, true)
        })
    }

    private boolean hasCustomerToReplicate(BusinessGroup businessGroup, CustomerSegment segment) {
        Map search = [column: "customer", businessGroupId: businessGroup.id, "customerSegment[ne]": segment]

        List<Customer> customerListWithoutAccountOwner = CustomerBusinessGroup.query(search + ["accountOwner[isNull]": true]).list()

        List<Customer> customerListWithoutInheritance = CustomerBusinessGroup.query(search + ["accountOwner[isNotNull]": true]).list().findAll {
            !customerAdminService.hasActiveAccountOwnerManagerAndSegmentInheritance(it.accountOwner)
        }

        return (customerListWithoutAccountOwner + customerListWithoutInheritance).asBoolean()
    }
}

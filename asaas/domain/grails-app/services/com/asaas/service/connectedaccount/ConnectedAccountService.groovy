package com.asaas.service.connectedaccount

import com.asaas.connectedaccountgroup.ConnectedAccountGroupListAdapter
import com.asaas.connectedaccountgroup.children.ConnectedAccountGroupAdapter
import com.asaas.customergeneralanalysis.CustomerGeneralAnalysisRejectReason
import com.asaas.domain.customergeneralanalysis.CustomerGeneralAnalysis
import com.asaas.integration.sauron.enums.ConnectedAccountInfoGroupType
import com.asaas.log.AsaasLogger
import grails.transaction.Transactional

@Transactional
class ConnectedAccountService {

    public static final Integer DEFAULT_CONNECTION_LEVEL = 1

    def connectedAccountManagerService

    public List<Long> buildDistinctConnectedAccountIdList(Long customerId, List<ConnectedAccountInfoGroupType> groupTypeList, Integer connectionLevel, Boolean isList) {
        ConnectedAccountGroupListAdapter connectedAccountGroupListAdapter = buildConnectedAccountGroupList(customerId, groupTypeList, connectionLevel, isList)
        if (!connectedAccountGroupListAdapter) return []

        Set<Long> connectedAccountIdSet = new HashSet<Long>()
        for (ConnectedAccountGroupAdapter connectedAccountGroupAdapter : connectedAccountGroupListAdapter.connectedAccountGroupList) {
            connectedAccountIdSet.addAll(connectedAccountGroupAdapter.accountIdList)
        }

        return connectedAccountIdSet.toList()
    }

    public ConnectedAccountGroupListAdapter buildConnectedAccountGroupList(Long customerId, List<ConnectedAccountInfoGroupType> groupTypeList, Integer connectionLevel, Boolean isList) {
        try {
            Map requestParams = [ accountId: customerId, connectionLevel: connectionLevel, isList: isList, groupTypeList: "" ]
            for (ConnectedAccountInfoGroupType groupType : groupTypeList) {
                requestParams.groupTypeList += "${groupType.toString()},"
            }

            return connectedAccountManagerService.getList(requestParams)
        } catch (Exception exception) {
            AsaasLogger.error("ConnectedAccountService.buildConnectedAccountGroupList >> erro ao buscar contas conectadas do cliente: [${customerId}]", exception)
            return null
        }
    }

    public List<Long> buildRejectedByFraudIdList(Long customerId) {
        final Integer connectionLevelToConsider = 1
        List<ConnectedAccountInfoGroupType> groupTypeList = ConnectedAccountInfoGroupType.values()
        List<Long> connectedAccountIdList = buildDistinctConnectedAccountIdList(customerId, groupTypeList, connectionLevelToConsider, true)
        if (!connectedAccountIdList) return []

        List<CustomerGeneralAnalysisRejectReason> fraudRejectReasonList = CustomerGeneralAnalysisRejectReason.values().findAll{ it.rejectReasonDescription.isFraudRelated() }
        Map searchParams = [:]
        searchParams.distinct = "customer.id"
        searchParams."generalAnalysisRejectReason[in]" = fraudRejectReasonList
        searchParams."customerId[in]" = connectedAccountIdList
        searchParams.disableSort = true
        List<Long> rejectedByFraudIdList = CustomerGeneralAnalysis.query(searchParams).list()

        return rejectedByFraudIdList
    }
}

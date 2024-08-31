package com.asaas.service.customerdealinfo.feeconfig

import com.asaas.asyncaction.AsyncActionType
import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigGroupRepository
import com.asaas.customerdealinfo.CustomerDealInfoFeeConfigMccRepository
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigGroup
import com.asaas.domain.customerdealinfo.CustomerDealInfoFeeConfigMcc
import com.asaas.domain.feenegotiation.FeeNegotiationMcc
import com.asaas.exception.BusinessException
import com.asaas.feenegotiation.FeeNegotiationReplicationType

import grails.transaction.Transactional

import org.apache.commons.lang.NotImplementedException

@Transactional
class CustomerDealInfoFeeConfigMccService {

    def asyncActionService
    def customerDealInfoFeeConfigGroupService
    def customerDealInfoInteractionService

    public void saveOrUpdate(CustomerDealInfoFeeConfigGroup feeConfigGroup, List<Long> feeNegotiationMccIdList) {
        Boolean hasFeeConfigMcc = CustomerDealInfoFeeConfigMccRepository.query([feeConfigGroup: feeConfigGroup]).exists()
        if (hasFeeConfigMcc) {
            updateList(feeConfigGroup, feeNegotiationMccIdList)
            return
        }

        saveList(feeConfigGroup, feeNegotiationMccIdList)
    }

    public void deleteAll(CustomerDealInfo customerDealInfo) {
        throw new NotImplementedException("Método não implementado.")

        Map search = [customerDealInfo: customerDealInfo, replicationType: FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT_WITH_DYNAMIC_MCC]
        List<Long> customerDealInfoFeeConfigGroupIdList = CustomerDealInfoFeeConfigGroupRepository.query(search).column("id").list()

        if (!customerDealInfoFeeConfigGroupIdList) throw new BusinessException("Não foram encontradas negociações de MCC dinâmico para este cliente.")

        List<CustomerDealInfoFeeConfigMcc> customerDealInfoFeeConfigMccList = CustomerDealInfoFeeConfigMccRepository.query(["feeConfigGroupId[in]": customerDealInfoFeeConfigGroupIdList]).list()

        for (CustomerDealInfoFeeConfigMcc feeConfigMcc : customerDealInfoFeeConfigMccList) {
            feeConfigMcc.deleted = true
            feeConfigMcc.save(failOnError: true)
        }

        for (Long customerDealInfoFeeConfigGroupId : customerDealInfoFeeConfigGroupIdList) {
            customerDealInfoFeeConfigGroupService.delete(customerDealInfoFeeConfigGroupId)
        }

        customerDealInfoInteractionService.saveDeleteDynamicMccFeeNegotiationInteraction(customerDealInfo)

        Map actionData = [accountOwnerId: customerDealInfo.customer.id]
        asyncActionService.save(AsyncActionType.APPLY_DEFAULT_CREDIT_CARD_FEE_CONFIG_TO_CHILD_ACCOUNTS, actionData)
    }

    private void save(CustomerDealInfoFeeConfigGroup feeConfigGroup, Long feeNegotiationMccId) {
        CustomerDealInfoFeeConfigMcc feeConfigMcc = new CustomerDealInfoFeeConfigMcc()
        FeeNegotiationMcc feeNegotiationMcc = FeeNegotiationMcc.load(feeNegotiationMccId)

        feeConfigMcc.feeConfigGroup = feeConfigGroup
        feeConfigMcc.feeNegotiationMcc = feeNegotiationMcc
        feeConfigMcc.save(failOnError: true)
    }

    private void saveList(CustomerDealInfoFeeConfigGroup feeConfigGroup, List<Long> feeNegotiationMccIdList) {
        for (Long feeNegotiationMccId : feeNegotiationMccIdList) {
            save(feeConfigGroup, feeNegotiationMccId)
        }
    }

    private void updateList(CustomerDealInfoFeeConfigGroup feeConfigGroup, List<Long> feeNegotiationMccIdList) {
        List<Long> currentFeeNegotiationMccIdList = CustomerDealInfoFeeConfigMccRepository.query([feeConfigGroup: feeConfigGroup]).column("feeNegotiationMcc.id").list()
        List<Long> newFeeNegotiationMccIdList = feeNegotiationMccIdList - currentFeeNegotiationMccIdList

        if (newFeeNegotiationMccIdList) {
            saveList(feeConfigGroup, newFeeNegotiationMccIdList)

            currentFeeNegotiationMccIdList = CustomerDealInfoFeeConfigMccRepository.query([feeConfigGroup: feeConfigGroup]).column("feeNegotiationMcc.id").list()
        }

        List<Long> feeNegotiationMccIdToDeleteList = currentFeeNegotiationMccIdList - feeNegotiationMccIdList
        if (feeNegotiationMccIdToDeleteList) {
            deleteList(feeConfigGroup, feeNegotiationMccIdToDeleteList)
        }
    }

    private void deleteList(CustomerDealInfoFeeConfigGroup feeConfigGroup, List<Long> feeNegotiationMccIdList) {
        for (Long feeNegotiationMccId : feeNegotiationMccIdList) {
            CustomerDealInfoFeeConfigMcc feeConfigMcc = CustomerDealInfoFeeConfigMccRepository.query([feeConfigGroup: feeConfigGroup, feeNegotiationMccId: feeNegotiationMccId]).get()

            feeConfigMcc.deleted = true
            feeConfigMcc.save(failOnError: true)
        }
    }
}

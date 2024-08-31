package com.asaas.service.feenegotiation

import com.asaas.asyncaction.AsyncActionType
import com.asaas.domain.customerdealinfo.CustomerDealInfo
import com.asaas.domain.feenegotiation.FeeNegotiationRequest
import com.asaas.domain.feenegotiation.FeeNegotiationRequestGroup
import com.asaas.domain.feenegotiation.FeeNegotiationRequestItem
import com.asaas.domain.user.User
import com.asaas.domain.userpermission.AdminUserPermission
import com.asaas.exception.BusinessException
import com.asaas.feenegotiation.FeeNegotiationProduct
import com.asaas.feenegotiation.FeeNegotiationReplicationType
import com.asaas.feenegotiation.adapter.FeeConfigGroupAdapter
import com.asaas.feenegotiation.adapter.FeeConfigGroupItemAdapter
import com.asaas.feenegotiation.adapter.SaveFeeNegotiationRequestAdapter
import com.asaas.feenegotiation.FeeNegotiationRequestStatus
import com.asaas.feenegotiation.FeeNegotiationType
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionName
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils

import grails.transaction.Transactional
import grails.validation.ValidationException

@Transactional
class FeeNegotiationRequestService {

    def asyncActionService
    def feeNegotiationRequestGroupService
    def feeNegotiationRequestInteractionService
    def feeNegotiationRequestItemService

    @Deprecated
    public void saveNegotiationRequest(CustomerDealInfo customerDealInfo, Map groupParams, List<Map> itemParamsList) {
        FeeNegotiationRequest request = FeeNegotiationRequest.query([customerDealInfo: customerDealInfo, status: FeeNegotiationRequestStatus.AWAITING_APPROVAL]).get()
        if (!request) request = save(customerDealInfo)

        saveOrUpdateGroupItem(request, groupParams, itemParamsList)

        approveAutomaticallyByFeeNegotiationType(request)
    }

    public void saveNegotiationRequest(SaveFeeNegotiationRequestAdapter feeNegotiationRequestAdapter) {
        CustomerDealInfo customerDealInfo = feeNegotiationRequestAdapter.groupAdapter.customerDealInfo
        FeeNegotiationRequest request = FeeNegotiationRequest.query([customerDealInfo: customerDealInfo, status: FeeNegotiationRequestStatus.AWAITING_APPROVAL]).get()
        if (!request) request = save(customerDealInfo)

        saveOrUpdateGroupItem(request, feeNegotiationRequestAdapter.groupAdapter, feeNegotiationRequestAdapter.itemAdapterList)

        approveAutomaticallyByFeeNegotiationType(request)
    }

    public List<FeeNegotiationType> listAllowedFeeNegotiationType(User user) {
        Map search = [:]
        search.column = "permission"
        search.user = user
        search.permissionList = AdminUserPermissionName.listFeeNegotiationPermissions()

        AdminUserPermissionName negotiatorRoleAdminUserPermissionName = AdminUserPermission.query(search).get()
        if (!negotiatorRoleAdminUserPermissionName) throw new BusinessException("O usuário não possui permissão para utilizar os limites negociação de taxa")

        FeeNegotiationType feeNegotiationType = FeeNegotiationType.convertPermissionToFeeNegotiationType(negotiatorRoleAdminUserPermissionName)

        return feeNegotiationType.listAllowedToManage()
    }

    public void approve(Long id) {
        FeeNegotiationRequest request = FeeNegotiationRequest.get(id)
        if (!request.status.isAwaitingApproval()) throw new BusinessException(Utils.getMessageProperty("feeNegotiationRequest.notAllowedChanges"))

        User currentUser = UserUtils.getCurrentUser()

        request.status = FeeNegotiationRequestStatus.APPROVED
        request.analyst = currentUser
        request.save(failOnError: true)

        feeNegotiationRequestInteractionService.save(request, currentUser, "Solicitação de negociação aprovada")

        asyncActionService.save(AsyncActionType.APPLY_FEE_NEGOTIATION, [feeNegotiationRequestId: request.id])
    }

    public void reject(Long id, String reason) {
        FeeNegotiationRequest request = FeeNegotiationRequest.get(id)

        FeeNegotiationRequest validatedDomain = validateReject(request, reason)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        User currentUser = UserUtils.getCurrentUser()

        request.status = FeeNegotiationRequestStatus.REJECTED
        request.analyst = currentUser
        request.save(failOnError: true)

        feeNegotiationRequestInteractionService.save(request, currentUser, "Solicitação de negociação rejeitada: ${reason}")
    }

    public void approveAutomaticallyForDefaultFeeApplication(FeeNegotiationRequest request) {
        approveAutomatically(request)

        feeNegotiationRequestInteractionService.save(request, null, "Solicitação de negociação de aplicação de taxa padrão aprovada automaticamente pelo sistema")
    }

    public void deleteAll(Long customerDealInfoId) {
        Map search = [column: "id", customerDealInfoId: customerDealInfoId, status: FeeNegotiationRequestStatus.AWAITING_APPROVAL]
        List<Long> feeNegotiationRequestIdList = FeeNegotiationRequest.query(search).list()

        for (Long feeNegotiationRequestId : feeNegotiationRequestIdList) {
            delete(feeNegotiationRequestId)
        }
    }

    private void delete(Long id) {
        FeeNegotiationRequest feeNegotiationRequest = FeeNegotiationRequest.get(id)
        if (!feeNegotiationRequest.status.isAwaitingApproval()) throw new BusinessException(Utils.getMessageProperty("feeNegotiationRequest.notAllowedDeletion"))

        List<Long> feeNegotiationRequestGroupIdList = FeeNegotiationRequestGroup.query([column: "id", feeNegotiationRequestId: feeNegotiationRequest.id]).list()

        for (Long feeNegotiationRequestGroupId : feeNegotiationRequestGroupIdList) {
            feeNegotiationRequestGroupService.delete(feeNegotiationRequestGroupId)
        }

        feeNegotiationRequest.deleted = true
        feeNegotiationRequest.save(failOnError: true)

        feeNegotiationRequestInteractionService.save(feeNegotiationRequest, UserUtils.getCurrentUser(), "Solicitação de negociação de taxa removida por remoção de negociação")
    }

    private void approveAutomaticallyByFeeNegotiationType(FeeNegotiationRequest request) {
        if (!request.customerDealInfo.feeNegotiationType.canBeAutomaticallyApproved()) return

        approveAutomatically(request)

        feeNegotiationRequestInteractionService.save(request, null, "Solicitação de negociação aprovada pela alçada automaticamente pelo sistema")
    }

    private void approveAutomatically(FeeNegotiationRequest request) {
        request.status = FeeNegotiationRequestStatus.APPROVED
        request.analyst = null
        request.save(failOnError: true)

        asyncActionService.save(AsyncActionType.APPLY_FEE_NEGOTIATION, [feeNegotiationRequestId: request.id])
    }

    @Deprecated
    private void saveOrUpdateGroupItem(FeeNegotiationRequest request, Map groupParams, List<Map> itemParamsList) {
        if (!request.status.isAwaitingApproval()) throw new BusinessException(Utils.getMessageProperty("feeNegotiationRequest.notAllowedChanges"))

        deleteConflictingReplicationTypeIfNecessary(request, groupParams.product, groupParams.replicationType)

        FeeNegotiationRequestGroup requestGroup = FeeNegotiationRequestGroup.query([feeNegotiationRequest: request, product: groupParams.product, replicationType: groupParams.replicationType]).get()
        if (!requestGroup) requestGroup = feeNegotiationRequestGroupService.save(request, groupParams.product, groupParams.replicationType)

        List<FeeNegotiationRequestItem> feeNegotiationItemList = []
        for (Map itemParams : itemParamsList) {
            feeNegotiationItemList += feeNegotiationRequestItemService.saveOrUpdate(requestGroup, itemParams)
        }

        feeNegotiationRequestInteractionService.saveGroupItemInteraction(requestGroup, feeNegotiationItemList)
    }

    private void saveOrUpdateGroupItem(FeeNegotiationRequest request, FeeConfigGroupAdapter groupAdapter, List<FeeConfigGroupItemAdapter> itemAdapterList) {
        if (!request.status.isAwaitingApproval()) throw new BusinessException(Utils.getMessageProperty("feeNegotiationRequest.notAllowedChanges"))

        deleteConflictingReplicationTypeIfNecessary(request, groupAdapter.product, groupAdapter.replicationType)

        FeeNegotiationRequestGroup requestGroup = FeeNegotiationRequestGroup.query([feeNegotiationRequest: request, product: groupAdapter.product, replicationType: groupAdapter.replicationType]).get()
        if (!requestGroup) requestGroup = feeNegotiationRequestGroupService.save(request, groupAdapter.product, groupAdapter.replicationType)

        List<FeeNegotiationRequestItem> feeNegotiationItemList = []
        for (FeeConfigGroupItemAdapter itemAdapter : itemAdapterList) {
            feeNegotiationItemList += feeNegotiationRequestItemService.saveOrUpdate(requestGroup, itemAdapter)
        }

        feeNegotiationRequestInteractionService.saveGroupItemInteraction(requestGroup, feeNegotiationItemList)
    }

    private FeeNegotiationRequest save(CustomerDealInfo customerDealInfo) {
        FeeNegotiationRequest validatedDomain = validateSave(customerDealInfo)
        if (validatedDomain.hasErrors()) throw new ValidationException(null, validatedDomain.errors)

        FeeNegotiationRequest request = new FeeNegotiationRequest()

        request.customerDealInfo = customerDealInfo
        request.createdBy = UserUtils.getCurrentUser()
        request.type = customerDealInfo.feeNegotiationType
        request.save(failOnError: true)

        feeNegotiationRequestInteractionService.save(request, request.createdBy, "Solicitação de negociação de taxa criada")

        return request
    }

    private FeeNegotiationRequest validateSave(CustomerDealInfo customerDealInfo) {
        FeeNegotiationRequest validatedDomain = new FeeNegotiationRequest()

        if (!customerDealInfo) DomainUtils.addError(validatedDomain, "É necessário informar o cliente negociado")

        if (customerDealInfo.deleted) DomainUtils.addError(validatedDomain, Utils.getMessageProperty("customerDealInfoFeeConfig.notAllowedSaveFeeConfig.isDeleted"))

        return validatedDomain
    }

    private FeeNegotiationRequest validateReject(FeeNegotiationRequest request, String reason) {
        FeeNegotiationRequest validatedDomain = new FeeNegotiationRequest()

        if (!request.status.isAwaitingApproval()) DomainUtils.addError(validatedDomain, Utils.getMessageProperty("feeNegotiationRequest.notAllowedChanges"))

        if (!reason) DomainUtils.addError(validatedDomain, "É necessário informar o motivo da rejeição")

        return validatedDomain
    }

    private void deleteConflictingReplicationTypeIfNecessary(FeeNegotiationRequest request, FeeNegotiationProduct product, FeeNegotiationReplicationType replicationType) {
        List<FeeNegotiationRequestGroup> requestGroupList = FeeNegotiationRequestGroup.query([feeNegotiationRequest: request, product: product]).list()

        for (FeeNegotiationRequestGroup feeNegotiationRequestGroup : requestGroupList) {
            if (shouldDeleteConflictingReplicationType(replicationType, feeNegotiationRequestGroup)) {
                feeNegotiationRequestGroupService.delete(feeNegotiationRequestGroup.id)
            }
        }
    }

    private Boolean shouldDeleteConflictingReplicationType(FeeNegotiationReplicationType feeNegotiationReplicationType, FeeNegotiationRequestGroup feeNegotiationRequestGroup ) {
        switch (feeNegotiationReplicationType) {
            case (FeeNegotiationReplicationType.ACCOUNT_OWNER_AND_CHILD_ACCOUNT):
                List<FeeNegotiationReplicationType> onlyApplicableOnChildAccountOrCustomer = [FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT, FeeNegotiationReplicationType.CUSTOMER]
                if (onlyApplicableOnChildAccountOrCustomer.contains(feeNegotiationRequestGroup.replicationType)) return true

                return false
            case (FeeNegotiationReplicationType.ONLY_CHILD_ACCOUNT):
            case (FeeNegotiationReplicationType.CUSTOMER):
                if (feeNegotiationRequestGroup.replicationType == FeeNegotiationReplicationType.ACCOUNT_OWNER_AND_CHILD_ACCOUNT) return true

                return false
            default:
                return false
        }
    }
}

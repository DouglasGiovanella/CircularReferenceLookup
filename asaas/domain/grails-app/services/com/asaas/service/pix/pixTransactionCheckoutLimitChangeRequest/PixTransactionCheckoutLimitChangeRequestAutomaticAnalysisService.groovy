package com.asaas.service.pix.pixTransactionCheckoutLimitChangeRequest

import com.asaas.domain.pix.PixTransactionCheckoutLimitChangeRequest
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestLimitType
import com.asaas.pix.PixTransactionCheckoutLimitChangeRequestScope
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation

import grails.transaction.Transactional

@Transactional
class PixTransactionCheckoutLimitChangeRequestAutomaticAnalysisService {

    def pixTransactionCheckoutLimitChangeRequestService
    def pixTransactionCheckoutLimitValuesChangeRequestService

    public void approveLimitIncreaseAvailableForAutomaticApproval() {
        List<Long> checkoutLimitChangeRequestIdList = PixTransactionCheckoutLimitChangeRequest.requestedLimitIncreaseAvailableForAutomaticApproval([column: "id"]).list(max: 500)
        approvePendingRequestsIfPossible(checkoutLimitChangeRequestIdList)
    }

    public void approveLimitReduction() {
        List<Long> checkoutLimitChangeRequestIdList = PixTransactionCheckoutLimitChangeRequest.requestedLimitReduction([column: "id"]).list(max: 500)
        approvePendingRequestsIfPossible(checkoutLimitChangeRequestIdList)
    }

    public void approveInitialNightlyHourAvailableForAutomaticApproval() {
        List<Long> checkoutLimitChangeRequestIdList = PixTransactionCheckoutLimitChangeRequest.requestedInitialNightlyHourAvailableForAutomaticApproval([column: "id"]).list(max: 500)
        approvePendingRequestsIfPossible(checkoutLimitChangeRequestIdList)
    }

    public void processExpired() {
        List<Long> expiredIdList = PixTransactionCheckoutLimitChangeRequest.expired([column: "id"]).list(max: 500)
        for (Long id : expiredIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = PixTransactionCheckoutLimitChangeRequest.get(id)

                pixTransactionCheckoutLimitChangeRequestService.denyAutomatically(pixTransactionCheckoutLimitChangeRequest, "Solicitação expirada")
            }, [logErrorMessage: "${this.getClass().getSimpleName()}.processExpired >> Erro ao recusar PixTransactionCheckoutLimitChangeRequest expirada [PixTransactionCheckoutLimitChangeRequestId: ${id}]",
                onError: {
                    Utils.withNewTransactionAndRollbackOnError({
                        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = PixTransactionCheckoutLimitChangeRequest.get(id)

                        pixTransactionCheckoutLimitChangeRequestService.setAsError(pixTransactionCheckoutLimitChangeRequest)
                    }, [logErrorMessage: "${this.getClass().getSimpleName()}.processExpired >> Erro ao tentar atualizar PixTransactionCheckoutLimitChangeRequest status como error [PixTransactionCheckoutLimitChangeRequestId: ${id}]"])
                }
            ])
        }
    }

    private void approvePendingRequestsIfPossible(List<Long> checkoutLimitChangeRequestIdList) {
        for (Long id : checkoutLimitChangeRequestIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = PixTransactionCheckoutLimitChangeRequest.get(id)

                BusinessValidation businessValidation = validateApproval(pixTransactionCheckoutLimitChangeRequest)
                if (businessValidation.isValid()) {
                    pixTransactionCheckoutLimitChangeRequestService.applyChangeRequestAndApproveAutomatically(pixTransactionCheckoutLimitChangeRequest)
                } else {
                    pixTransactionCheckoutLimitChangeRequestService.denyAutomatically(pixTransactionCheckoutLimitChangeRequest, businessValidation.getFirstErrorMessage())
                }
            }, [logErrorMessage: "${this.getClass().getSimpleName()}.approvePendingRequestsIfPossible >> Erro ao tentar aprovar automaticamente a PixTransactionCheckoutLimitChangeRequest [PixTransactionCheckoutLimitChangeRequestId: ${id}]",
                onError: {
                    Utils.withNewTransactionAndRollbackOnError({
                        PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest = PixTransactionCheckoutLimitChangeRequest.get(id)

                        pixTransactionCheckoutLimitChangeRequestService.setAsError(pixTransactionCheckoutLimitChangeRequest)
                    }, [logErrorMessage: "${this.getClass().getSimpleName()}.approvePendingRequestsIfPossible >> Erro ao tentar atualizar PixTransactionCheckoutLimitChangeRequest status como error [PixTransactionCheckoutLimitChangeRequestId: ${id}]"])
                }
            ])
        }
    }

    private BusinessValidation validateApproval(PixTransactionCheckoutLimitChangeRequest pixTransactionCheckoutLimitChangeRequest) {
        BusinessValidation businessValidation = new BusinessValidation()
        if (pixTransactionCheckoutLimitChangeRequest.type.isChangeNightlyHour()) return businessValidation
        if (pixTransactionCheckoutLimitChangeRequest.limitType.isPerPeriod()) return businessValidation

        BigDecimal requestedLimitPerTransaction = pixTransactionCheckoutLimitChangeRequest.requestedLimit
        BigDecimal currentLimitPerPeriod = pixTransactionCheckoutLimitValuesChangeRequestService.calculateCurrentLimit(pixTransactionCheckoutLimitChangeRequest.customer, pixTransactionCheckoutLimitChangeRequest.scope, pixTransactionCheckoutLimitChangeRequest.period, PixTransactionCheckoutLimitChangeRequestLimitType.PER_PERIOD)
        if (requestedLimitPerTransaction > currentLimitPerPeriod) {
            businessValidation.addError("pixTransactionCheckoutLimitChangeRequest.validate.limitPerTransactionMustBeLessThanPerPeriod")
            return businessValidation
        }

        if (pixTransactionCheckoutLimitChangeRequest.scope.isCashValue()) {
            BigDecimal currentLimitOnGeneralScope = pixTransactionCheckoutLimitValuesChangeRequestService.calculateCurrentLimit(pixTransactionCheckoutLimitChangeRequest.customer, PixTransactionCheckoutLimitChangeRequestScope.GENERAL, pixTransactionCheckoutLimitChangeRequest.period, PixTransactionCheckoutLimitChangeRequestLimitType.PER_PERIOD)
            if (pixTransactionCheckoutLimitChangeRequest.requestedLimit > currentLimitOnGeneralScope) {
                businessValidation.addError("pixTransactionCheckoutLimitChangeRequest.validate.cashValueLimitMustBeLessThanGeneral")
                return businessValidation
            }
        }

        return businessValidation
    }
}

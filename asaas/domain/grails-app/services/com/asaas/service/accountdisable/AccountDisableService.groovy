package com.asaas.service.accountdisable

import com.asaas.customer.CustomerStatus
import com.asaas.customer.DisabledReason
import com.asaas.customergeneralanalysis.CustomerGeneralAnalysisRejectReason
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.customergeneralanalysis.CustomerGeneralAnalysis
import com.asaas.log.AsaasLogger
import com.asaas.service.customer.CustomerAdminService
import com.asaas.status.CustomerGeneralAnalysisStatus
import com.asaas.utils.Utils

import grails.transaction.Transactional

@Transactional
class AccountDisableService {

    CustomerAdminService customerAdminService

    public Boolean disableAccountsWithConfirmedFraud() {
        final Short MAX_PENDING = 250
        List<CustomerGeneralAnalysis> customerGeneralAnalysisList = CustomerGeneralAnalysis.createCriteria().list(max: MAX_PENDING) {
            createAlias("customer", "customer")
            createAlias("customer.customerRegisterStatus", "customerRegisterStatus")

            eq('deleted', false)
            eq("status", CustomerGeneralAnalysisStatus.REJECTED)
            ne("customer.status", CustomerStatus.DISABLED)
            eq("customerRegisterStatus.generalApproval", GeneralApprovalStatus.REJECTED)
            'in'("generalAnalysisRejectReason", CustomerGeneralAnalysisRejectReason.getConfirmedFraudReasons())

            order("id", "desc")
        }

        if (!customerGeneralAnalysisList) {
            AsaasLogger.info("AccountDisableService.disableAccountsWithConfirmedFraud -> Não há mais nenhuma conta com fraude confirmada para ser encerrada.")
            return false
        }

        for (CustomerGeneralAnalysis customerGeneralAnalysis : customerGeneralAnalysisList) {
            String observation = "${customerGeneralAnalysis.customerRejectReasonDescription} - Ação automática de encerramento de contas reprovadas por fraude confirmada."
            Utils.withNewTransactionAndRollbackOnError ({
                customerAdminService.executeAccountDisable(customerGeneralAnalysis.customer.id,
                    null,
                    DisabledReason.CONFIRMED_FRAUD,
                    observation,
                    false)
            }, [logErrorMessage: "AccountDisableService.disableAccountsWithConfirmedFraud -> ocorreu um erro durante o encerramento da conta do CustomerId: ${customerGeneralAnalysis.customer.id}."])
        }

        return true
    }
}

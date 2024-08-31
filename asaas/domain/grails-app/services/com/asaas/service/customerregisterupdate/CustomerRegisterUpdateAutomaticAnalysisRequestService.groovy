package com.asaas.service.customerregisterupdate

import com.asaas.customerregisterupdate.enums.CustomerRegisterUpdateAnalysisReason
import com.asaas.customerregisterupdate.enums.CustomerRegisterUpdateAnalysisStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerEconomicActivity
import com.asaas.domain.customerregisterupdate.CustomerRegisterUpdateAnalysisRequest
import com.asaas.domain.economicactivity.EconomicActivity
import com.asaas.domain.partnerapplication.CustomerPartnerApplication
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.exception.BusinessException
import com.asaas.integration.heimdall.enums.revenueserviceregister.RevenueServiceRegisterCacheLevel
import com.asaas.log.AsaasLogger
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerRegisterUpdateAutomaticAnalysisRequestService {

    def asaasSegmentioService
    def boletoChangeInfoRequestService
    def customerAlertNotificationService
    def customerInteractionService
    def customerMessageService
    def economicActivityService
    def mobilePushNotificationService
    def revenueServiceRegisterService

    public void analyzeAutomatically() {
        final Integer automaticAnalysisLimit = 100

        List<Long> customerRegisterUpdateAnalysisRequestIdList = CustomerRegisterUpdateAnalysisRequest.query([
            column: "id",
            status: CustomerRegisterUpdateAnalysisStatus.AWAITING_AUTOMATIC_ANALYSIS
        ]).list([max: automaticAnalysisLimit])

        if (!customerRegisterUpdateAnalysisRequestIdList) return

        for (Long customerRegisterUpdateAnalysisRequestId : customerRegisterUpdateAnalysisRequestIdList) {
            proccess(customerRegisterUpdateAnalysisRequestId)
        }
    }

    private void proccess(Long customerRegisterUpdateAnalysisRequestId) {
        Utils.withNewTransactionAndRollbackOnError ({
            CustomerRegisterUpdateAnalysisRequest analysisRequest = CustomerRegisterUpdateAnalysisRequest.get(customerRegisterUpdateAnalysisRequestId)

            List<CustomerRegisterUpdateAnalysisReason> analysisReasonList = analysisRequest.getAnalysisReasonList()

            List<CustomerRegisterUpdateAnalysisReason> compulsoryUpdateReasonList = analysisReasonList.findAll {
                CustomerRegisterUpdateAnalysisReason.listCompulsoryUpdateReasons().contains(it)
            }

            if (compulsoryUpdateReasonList) {
                executeCompulsoryUpdate(analysisRequest, compulsoryUpdateReasonList)
            }

            Boolean hasManualAnalysisReason = analysisReasonList.any { CustomerRegisterUpdateAnalysisReason.listManualAnalysisReasons().contains(it) }
            if (hasManualAnalysisReason) {
                updateStatus(analysisRequest, CustomerRegisterUpdateAnalysisStatus.PENDING)
                AsaasLogger.info("CustomerRegisterUpdateAutomaticAnalysisRequestService.proccess >> Ações automaticas realizadas Id:[${customerRegisterUpdateAnalysisRequestId}]")
                return
            }

            updateStatus(analysisRequest, CustomerRegisterUpdateAnalysisStatus.FINISHED)
            AsaasLogger.info("CustomerRegisterUpdateAutomaticAnalysisRequestService.proccess >> Análise concluída automaticamente Id:[${customerRegisterUpdateAnalysisRequestId}]")
        }, [onError: { Exception exception ->
            AsaasLogger.error("CustomerRegisterUpdateAutomaticAnalysisRequestService.proccess >> Erro na análise automática de atualização cadastral. Id:[${customerRegisterUpdateAnalysisRequestId}].", exception)
            setStatusToPendingWithNewTransaction(customerRegisterUpdateAnalysisRequestId)
        }])
    }

    private void executeCompulsoryUpdate(CustomerRegisterUpdateAnalysisRequest analysisRequest, List<CustomerRegisterUpdateAnalysisReason> compulsoryUpdateReasonList) {
        if (CustomerPartnerApplication.hasBradesco(analysisRequest.customer.id)) return

        RevenueServiceRegister revenueServiceRegister = revenueServiceRegisterService.findPerson(analysisRequest.customer.cpfCnpj, RevenueServiceRegisterCacheLevel.NORMAL)
        if (revenueServiceRegister.hasErrors()) {
            throw new BusinessException(DomainUtils.getFirstValidationMessage(revenueServiceRegister))
        }

        Boolean shouldUpdateName = compulsoryUpdateReasonList.any { CustomerRegisterUpdateAnalysisReason.listNameDivergenceReasons().contains(it) }
        if (shouldUpdateName) {
            executeCompulsoryNameUpdate(analysisRequest, revenueServiceRegister)
        }

        Boolean shouldUpdateEconomicActivity = compulsoryUpdateReasonList.any { it.isDivergencyInMainEconomicActivity() }
        if (shouldUpdateEconomicActivity) {
            executeCompulsoryEconomicActivityUpdate(analysisRequest, revenueServiceRegister)
        }

        notifyCompulsoryUpdate(analysisRequest.customer)
    }

    private void executeCompulsoryNameUpdate(CustomerRegisterUpdateAnalysisRequest analysisRequest, RevenueServiceRegister revenueServiceRegister) {
        Customer customer = analysisRequest.customer
        String oldName = customer.name
        if (customer.isNaturalPerson()) {
            customer.name = revenueServiceRegister.name
        } else if (customer.isLegalPerson()) {
            String name = revenueServiceRegister.tradingName ?: revenueServiceRegister.corporateName
            customer.name = name
            customer.company = name
        }
        customer.save(failOnError: true)

        boletoChangeInfoRequestService.save(customer.id, [transferor: customer.name, cpfCnpj: customer.cpfCnpj, receiveAfterDueDate: true])

        customerInteractionService.saveCustomerCompulsoryUpdate(customer, "Nome atualizado de: ${oldName}, para: ${customer.name}. Análise de atualização cadastral: ${analysisRequest.id}")
        asaasSegmentioService.track(customer.id, "customer_compulsory_update", [name: "${customer.name}"])
        AsaasLogger.info("CustomerRegisterUpdateAutomaticAnalysisRequestService.executeCompulsoryNameUpdate >> Análise de atualização cadastral efetuou alteração de nome Id:[${analysisRequest.id}]")
    }

    private void executeCompulsoryEconomicActivityUpdate(CustomerRegisterUpdateAnalysisRequest analysisRequest, RevenueServiceRegister revenueServiceRegister) {
        Customer customer = analysisRequest.customer

        Map oldMainEconomicActivity = CustomerEconomicActivity.query([columnList: ["economicActivity.name", "economicActivity.cnaeCode"], customer: customer, isMainActivity: true]).get()
        List<Map> economicActivitiesMapList = revenueServiceRegister.buildEconomicActivitiesMapList()
        EconomicActivity mainEconomicActivity = economicActivitiesMapList.find { it.isMainActivity }?.economicActivity

        economicActivityService.associateAllCustomer(customer, economicActivitiesMapList)

        customerInteractionService.saveCustomerCompulsoryUpdate(customer, buildEconomicActivityUpdateDetailMessage(oldMainEconomicActivity, mainEconomicActivity, analysisRequest.id))
        asaasSegmentioService.track(customer.id, "customer_compulsory_update", [mainEconomicActiviy: true])
        AsaasLogger.info("CustomerRegisterUpdateAutomaticAnalysisRequestService.executeCompulsoryEconomicActivityUpdate >> Análise de atualização cadastral efetuou alteração de atividade comercial Id:[${analysisRequest.id}]")
    }

    private void updateStatus(CustomerRegisterUpdateAnalysisRequest analysisRequest, CustomerRegisterUpdateAnalysisStatus status) {
        analysisRequest.status = status
        analysisRequest.save(failOnError: true)
    }

    private void setStatusToPendingWithNewTransaction(Long customerRegisterUpdateAnalysisRequestId) {
        Utils.withNewTransactionAndRollbackOnError({
            CustomerRegisterUpdateAnalysisRequest analysisRequest = CustomerRegisterUpdateAnalysisRequest.get(customerRegisterUpdateAnalysisRequestId)
            analysisRequest.status = CustomerRegisterUpdateAnalysisStatus.PENDING
            analysisRequest.save(failOnError: true)
        })
    }

    private String buildEconomicActivityUpdateDetailMessage(Map oldMainEconomicActivity, EconomicActivity newMainEconomicActivity, Long analysisRequestId) {
        StringBuilder detail = new StringBuilder("Atividades econômicas atualizadas. ")

        String oldMainEconomicActivityMessage = oldMainEconomicActivity ? "CNAE ${oldMainEconomicActivity."economicActivity.cnaeCode"} - ${oldMainEconomicActivity."economicActivity.name"}" : "Não identificado"
        String newMainEconomicActivityMessage = newMainEconomicActivity ? "CNAE ${newMainEconomicActivity.cnaeCode} - ${newMainEconomicActivity.name}" : "Não identificado"

        detail.append("Atividade principal atualizada de: ${oldMainEconomicActivityMessage}, para: ${newMainEconomicActivityMessage}. ")
        detail.append("Análise de atualização cadastral: ${analysisRequestId}")

        return detail.toString()
    }

    private void notifyCompulsoryUpdate(Customer customer) {
        customerAlertNotificationService.notifyCompulsoryCustomerRegisterUpdate(customer)
        mobilePushNotificationService.notifyCompulsoryCustomerRegisterUpdate(customer)
        customerMessageService.notifyCompulsoryCustomerRegisterUpdate(customer)
    }
}

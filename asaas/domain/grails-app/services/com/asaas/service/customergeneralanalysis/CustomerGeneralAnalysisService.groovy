package com.asaas.service.customergeneralanalysis

import com.asaas.analysisinteraction.AnalysisInteractionType
import com.asaas.customergeneralanalysis.CustomerGeneralAnalysisRejectReason
import com.asaas.domain.blacklist.BlackList
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBusinessActivity
import com.asaas.domain.customer.CustomerEconomicActivity
import com.asaas.domain.customergeneralanalysis.CustomerGeneralAnalysis
import com.asaas.domain.customergeneralanalysis.GeneralAnalysisRejectReason
import com.asaas.domain.revenueserviceregister.RevenueServiceRegister
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.integration.heimdall.dto.dataenhancement.get.common.analysis.DataEnhancementAnalysisDTO
import com.asaas.log.AsaasLogger
import com.asaas.status.CustomerGeneralAnalysisStatus
import com.asaas.user.UserUtils
import com.asaas.utils.DomainUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerGeneralAnalysisService {

    def analysisInteractionService
    def asaasSegmentioService
    def customerInteractionService
    def customerRegisterStatusService
    def dataEnhancementManagerService
    def receivableAnticipationValidationService
    def riskAnalysisConnectedAccountsService

    public CustomerGeneralAnalysis startNextAnalysis(User analyst, Map searchParams) {
        Long customerId = customerRegisterStatusService.findNextGeneralAnalysis(analyst, searchParams)
        if (!customerId) return null

        Customer customer = Customer.read(customerId)
        return createAnalysis(customer, analyst)
    }

    public CustomerGeneralAnalysis createAnalysis(Customer customer, User analyst) {
        deleteNotFinishedAnalysisIfNecessary(customer, analyst)

        CustomerGeneralAnalysis generalAnalysis = CustomerGeneralAnalysis.notFinished([customer: customer, analyst: analyst]).get()

        Boolean isNewAnalysis = false
        if (!generalAnalysis) {
            generalAnalysis = new CustomerGeneralAnalysis()
            generalAnalysis.customer = customer
            generalAnalysis.analyst = analyst
            isNewAnalysis = true
        }

        generalAnalysis.status = CustomerGeneralAnalysisStatus.IN_PROGRESS
        generalAnalysis.save(failOnError: true)

        if (isNewAnalysis) {
            analysisInteractionService.createForCustomerGeneralAnalysis(analyst, AnalysisInteractionType.START, generalAnalysis)
        }

        return generalAnalysis
    }

    public CustomerGeneralAnalysis saveManually(Long customerGeneralAnalysisId, Map params) {
        CustomerGeneralAnalysis customerGeneralAnalysis = CustomerGeneralAnalysis.query([id: customerGeneralAnalysisId, analyst: UserUtils.getCurrentUser(), status: CustomerGeneralAnalysisStatus.IN_PROGRESS]).get()
        if (!customerGeneralAnalysis) throw new BusinessException("A análise geral do cliente não foi encontrada. Verifique se outro analista iniciou a mesma análise.")

        CustomerGeneralAnalysis validateCustomerGeneralAnalysis = validateSaveManually(params)
        if (validateCustomerGeneralAnalysis.hasErrors()) return validateCustomerGeneralAnalysis

        customerGeneralAnalysis.properties["status", "observations", "customerRejectReasonDescription"] = params
        customerGeneralAnalysis.generalAnalysisTeam = params.customerGeneralAnalysisTeam
        customerGeneralAnalysis.generalAnalysisStage = params.customerGeneralAnalysisStage
        customerGeneralAnalysis.generalAnalysisRejectReason = params.customerGeneralAnalysisRejectReason

        customerGeneralAnalysis.save(flush: true, failOnError: true)

        afterSave(customerGeneralAnalysis)

        if (customerGeneralAnalysis.status.isRejected()) saveGeneralAnalysisRejectReason(customerGeneralAnalysis, params.observations)

        return customerGeneralAnalysis
    }

    public CustomerGeneralAnalysis approveAutomaticallyIfPossible(Customer customer) {
        if (!customer.hasAccountAutoApprovalEnabled()) {
            List<String> rejectReasonList = listRejectReasons(customer)

            if (rejectReasonList) {
                CustomerGeneralAnalysis customerGeneralAnalysis = save(customer, [status: CustomerGeneralAnalysisStatus.MANUAL_ANALYSIS_REQUIRED, observations: "Não atendeu aos critérios de aprovação automática."])
                saveGeneralAnalysisRejectReasons(customerGeneralAnalysis, rejectReasonList)

                return customerGeneralAnalysis
            }
        }

        if (!customer.customerRegisterStatus.isReadyForGeneralApprovalAnalysis()) return null

        return save(customer, [status: CustomerGeneralAnalysisStatus.APPROVED, observations: "Aprovado automaticamente."])
    }

    public void rejectAutomaticallyIfPossible(Customer customer, CustomerGeneralAnalysisRejectReason rejectReason) {
        if (!customer.isNaturalPerson()) return
        if (!rejectReason.isAllowedToRejectAutomatically()) return

        String customerRejectReasonObservation = Utils.getMessageProperty("CustomerGeneralAnalysisRejectReason.${rejectReason}")
        String observations = "Reprovado automaticamente. ${customerRejectReasonObservation}"

        reject(customer, rejectReason, observations)
    }

    public void reject(Customer customer, CustomerGeneralAnalysisRejectReason rejectReason, String observations) {
        String customerRejectReasonDescription = Utils.getMessageProperty("CustomerGeneralAnalysisRejectReasonDescription.attribute.${rejectReason.rejectReasonDescription}")

        Map generalAnalysisParams = [:]
        generalAnalysisParams.status = CustomerGeneralAnalysisStatus.REJECTED
        generalAnalysisParams.generalAnalysisRejectReason = rejectReason
        generalAnalysisParams.customerRejectReasonDescription = customerRejectReasonDescription
        generalAnalysisParams.observations = observations

        CustomerGeneralAnalysis customerGeneralAnalysis = save(customer, generalAnalysisParams)
        saveGeneralAnalysisRejectReason(customerGeneralAnalysis, customerGeneralAnalysis.observations)
    }

    public void deleteNotFinishedAnalysisInBatch() {
        Calendar twoHoursAgo = Calendar.getInstance()
        twoHoursAgo.add(Calendar.HOUR_OF_DAY, -2)

        List<Long> generalAnalysisIdList = CustomerGeneralAnalysis.notFinished([column: 'id', 'lastUpdated[le]': twoHoursAgo.getTime()]).list()

        for (Long generalAnalysisId in generalAnalysisIdList) {
            Utils.withNewTransactionAndRollbackOnError({
                CustomerGeneralAnalysis generalAnalysis = CustomerGeneralAnalysis.get(generalAnalysisId)
                generalAnalysis.deleted = true
                generalAnalysis.save(failOnError: true)
                AsaasLogger.warn("A análise geral do cliente [${generalAnalysis.customer.id}] que estava em andamento pelo analista [${generalAnalysis.analyst.username}] será removida pois encontra-se em andamento há mais de duas horas.")
            }, [logErrorMessage:  "Erro ao deletar análise em andamento há mais de duas horas. Id: [${generalAnalysisId}]"])
        }
    }

    public void reprocessIfPossible(String cpfCnpj) {
        Customer customer = Customer.notDisabledAccounts([cpfCnpj: cpfCnpj]).get()

        if (!customer) return

        CustomerGeneralAnalysis customerGeneralAnalysis = approveAutomaticallyIfPossible(customer)

        if (!customerGeneralAnalysis) return

        Map trackInfo = [
            action: "reprocess",
            customer_general_analysis_id: customerGeneralAnalysis.id,
            general_analysis_status: customerGeneralAnalysis.status.toString()
        ]
        asaasSegmentioService.track(customer.id, "process_customer_general_analysis", trackInfo)
    }

    private CustomerGeneralAnalysis save(Customer customer, Map params) {
        CustomerGeneralAnalysis customerGeneralAnalysis = new CustomerGeneralAnalysis()
        customerGeneralAnalysis.customer = customer
        customerGeneralAnalysis.properties = params
        customerGeneralAnalysis.properties["status", "analyst", "observations", "customerRejectReasonDescription"] = params
        customerGeneralAnalysis.save(flush: true, failOnError: true)

        afterSave(customerGeneralAnalysis)

        return customerGeneralAnalysis
    }

    private void afterSave(CustomerGeneralAnalysis customerGeneralAnalysis) {
        setGeneralApprovalStatus(customerGeneralAnalysis)

        customerInteractionService.save(customerGeneralAnalysis.customer, "Análise geral: ${customerGeneralAnalysis.observations}")
        receivableAnticipationValidationService.onCustomerChange(customerGeneralAnalysis.customer)
        if (customerGeneralAnalysis.status.isFinished()) {
            analysisInteractionService.createForCustomerGeneralAnalysis(customerGeneralAnalysis.analyst, AnalysisInteractionType.FINISH, customerGeneralAnalysis)
        }
    }

    private void setGeneralApprovalStatus(CustomerGeneralAnalysis customerGeneralAnalysis) {
        if (customerGeneralAnalysis.status.isApproved()) {
            customerRegisterStatusService.approveGeneralStatus(customerGeneralAnalysis.customer.id)
        } else if (customerGeneralAnalysis.status.isRejected()) {
            customerRegisterStatusService.rejectGeneralStatus(customerGeneralAnalysis.customer, customerGeneralAnalysis.customerRejectReasonDescription)
        }
    }

    private void saveGeneralAnalysisRejectReasons(CustomerGeneralAnalysis customerGeneralAnalysis, List<String> rejectReasonList) {
        for (String rejectReason in rejectReasonList) {
            saveGeneralAnalysisRejectReason(customerGeneralAnalysis, rejectReason)
        }
    }

    private void saveGeneralAnalysisRejectReason(CustomerGeneralAnalysis customerGeneralAnalysis, String rejectReason) {
        GeneralAnalysisRejectReason generalAnalysisRejectReason = new GeneralAnalysisRejectReason()
        generalAnalysisRejectReason.customerGeneralAnalysis = customerGeneralAnalysis
        generalAnalysisRejectReason.description = rejectReason
        generalAnalysisRejectReason.save(failOnError: true)

        riskAnalysisConnectedAccountsService.createIfNecessary(customerGeneralAnalysis)
    }

    private void deleteNotFinishedAnalysisIfNecessary(Customer customer, User analyst) {
        List<CustomerGeneralAnalysis> notFinishedGeneralAnalysisList = CustomerGeneralAnalysis.notFinished([customer: customer, "analyst[ne]": analyst]).list()
        for (CustomerGeneralAnalysis oldAnalysis in notFinishedGeneralAnalysisList) {
            oldAnalysis.deleted = true
            oldAnalysis.save(failOnError: true)
            AsaasLogger.warn("Nova análise geral criada pelo analista [${analyst.username}]. Cliente [${customer.id}]. A análise que estava em andamento pelo analista [${oldAnalysis.analyst.username}] foi removida.")
        }
    }

    private List<String> listRejectReasons(Customer customer) {
        List<String> rejectReasons = listRejectReasonsByHeimdall(customer)

        if (!customer.customerRegisterStatus.commercialInfoStatus.isApproved()) rejectReasons.add("Dados comerciais ainda não aprovados.")
        if (!customer.customerRegisterStatus.boletoInfoStatus.isApproved()) rejectReasons.add("Informações de boletos ainda não aprovados.")
        if (!customer.customerRegisterStatus.documentStatus.isApproved()) rejectReasons.add("Documentos ainda não aprovados.")
        if (!customer.bankAccountInfoApprovalIsNotRequired() && !customer.customerRegisterStatus.bankAccountInfoStatus.isApproved()) rejectReasons.add("Dados bancários ainda não aprovados.")

        if (customer.suspectedOfFraud) rejectReasons.add("Definido como de alto risco.")

        if (BlackList.isInBlackList(customer)) rejectReasons.add("Contém informações adicionadas na lista de suspeitos.")

        if (customer.isLegalPerson() && CustomerEconomicActivity.activityRequiresManualAnalysis(customer)) {
            rejectReasons.add("Atividade econômica precisa ser analisada manualmente.")
        }

        if (CustomerBusinessActivity.activityRequiresManualAnalysis(customer)) {
            rejectReasons.add("Atividade comercial precisa ser analisada manualmente.")
        }

        RevenueServiceRegister revenueServiceRegister = RevenueServiceRegister.findLatest(customer.cpfCnpj)
        if (!revenueServiceRegister) rejectReasons.add("Não foi possível consultar dados da receita.")
        if (revenueServiceRegister && !revenueServiceRegister.hasAcceptableStatus()) rejectReasons.add("Restrições na receita federal.")

        return rejectReasons
    }

    private List<String> listRejectReasonsByHeimdall(Customer customer) {
        List<String> rejectReasons = []

        DataEnhancementAnalysisDTO dataEnhancementAnalysisDTO = dataEnhancementManagerService.findAnalysis(customer.cpfCnpj)

        if (dataEnhancementAnalysisDTO?.status == 'REJECTED') {
            rejectReasons = dataEnhancementAnalysisDTO.rejectReasons
        }

        if (dataEnhancementAnalysisDTO?.status == 'PENDING') {
            rejectReasons.add("Processamento das informações do Enriquecimento de Dados não iniciado")
        }

        if (dataEnhancementAnalysisDTO?.status == 'PROCESSING') {
            rejectReasons.add("As informações do Enriquecimento de dados estão em processamento")
        }

        if (!dataEnhancementAnalysisDTO) rejectReasons.add("Nenhuma informação encontrada no Enriquecimento de dados.")

        return rejectReasons
    }

    private CustomerGeneralAnalysis validateSaveManually(Map params) {
        CustomerGeneralAnalysis customerGeneralAnalysis = new CustomerGeneralAnalysis()

        if (!params.status) {
            DomainUtils.addError(customerGeneralAnalysis, "Informe o status da análise")
        }

        if (!params.observations) {
            DomainUtils.addError(customerGeneralAnalysis, "Informe as observações da análise")
        }

        if (params.status == CustomerGeneralAnalysisStatus.REJECTED) {
            if (!params.customerGeneralAnalysisTeam) {
                DomainUtils.addError(customerGeneralAnalysis, "Informe a equipe")
            }

            if (!params.customerGeneralAnalysisStage) {
                DomainUtils.addError(customerGeneralAnalysis, "Informe a etapa da reprovação")
            }

            if (!params.customerGeneralAnalysisRejectReason) {
                DomainUtils.addError(customerGeneralAnalysis, "Informe o motivo da reprovação")
            }

            if (!params.customerRejectReasonDescription) {
                DomainUtils.addError(customerGeneralAnalysis, "Informe o motivo de recusa da análise")
            }
        }

        return customerGeneralAnalysis
    }

}

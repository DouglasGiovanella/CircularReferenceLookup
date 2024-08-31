package com.asaas.service.customerdocumentanalysisstatus

import com.asaas.documentanalysis.DocumentAnalysisPostponedReason
import com.asaas.documentanalysis.DocumentAnalysisStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerdocumentanalysisstatus.CustomerDocumentAnalysisStatus
import com.asaas.domain.documentanalysis.DocumentAnalysis
import com.asaas.domain.user.User
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class CustomerDocumentAnalysisStatusService {

    public Integer countDocumentAnalysisInQueue(List<Long> customerIdList) {
        if (!customerIdList) return 0

        return CustomerDocumentAnalysisStatus.query([
            exists: true,
            "customerId[in]": customerIdList,
            "status[in]": DocumentAnalysisStatus.inQueue(),
        ]).count()
    }

    public Long findNextDocumentAnalysisInQueue(User analyst, List<Long> customerIdList) {
        Long customerIdDocumentAnalysisInProgress = DocumentAnalysis.query([column: "customer.id", user: analyst, status: DocumentAnalysisStatus.IN_PROGRESS]).get()
        if (customerIdDocumentAnalysisInProgress) return customerIdDocumentAnalysisInProgress

        if (!customerIdList) return null

        Map searchParams = [
            column: "customer.id",
            "customerId[in]": customerIdList,
            "status[in]": DocumentAnalysisStatus.inQueue(),
            sort: "lastUpdated",
            order: "asc"
        ]
        return CustomerDocumentAnalysisStatus.query(searchParams).get()
    }

    public void updateDocumentAnalysisStatus(Customer customer, DocumentAnalysisStatus status) {
        if (!status) return

        CustomerDocumentAnalysisStatus documentAnalysisStatus = CustomerDocumentAnalysisStatus.query([customerId: customer.id]).get()

        if (!documentAnalysisStatus) {
            documentAnalysisStatus = new CustomerDocumentAnalysisStatus()
            documentAnalysisStatus.customer = customer
        }

        documentAnalysisStatus.status = status
        documentAnalysisStatus.save(failOnError: true)
    }

    public void updateStatusAfterPostponedDeadline() {
        final Integer maxItemsPerCycle = 50

        Map queryParams = [
            column: "customer.id",
            status: DocumentAnalysisStatus.POSTPONED
        ]

        List<Long> customerIdListToSetManualAnalysisRequired = []
        List<Map> documentAnalysisStatusCustomerIdList = CustomerDocumentAnalysisStatus.query(queryParams).list(max: maxItemsPerCycle)
        for (Long customerId : documentAnalysisStatusCustomerIdList) {
                if (hasPostponedAnalysisOutOfTimeForCustomer(customerId)) {
                    customerIdListToSetManualAnalysisRequired.add(customerId)
                }
        }

        if (!customerIdListToSetManualAnalysisRequired) return
        Utils.withNewTransactionAndRollbackOnError({
            Map updateParams = [
                now: new Date(),
                customerIdList: customerIdListToSetManualAnalysisRequired
            ]
            CustomerDocumentAnalysisStatus.executeUpdate("UPDATE CustomerDocumentAnalysisStatus SET status = 'MANUAL_ANALYSIS_REQUIRED', lastUpdated = :now WHERE customer.id IN (:customerIdList) AND deleted = 0", updateParams)
        }, [logErrorMessage: "CustomerDocumentAnalysisStatusService.updateStatusAfterPostponedDeadline >> Não foi possível atualizar o status das análises de documentos [${customerIdListToSetManualAnalysisRequired.join(', ')}] após o prazo de prorrogação."])
    }

    public void updateStatusAfterAutomaticAnalysisDeadline() {
        final Integer maxMinutesForAutomaticAnalysisTimeout = 90
        final Integer maxItemsPerCycle = 50

        Date maxLastUpdated = CustomDateUtils.sumMinutes(new Date(), -maxMinutesForAutomaticAnalysisTimeout)

        Map queryParams = [
            column: "customer.id",
            status: DocumentAnalysisStatus.AWAITING_AUTOMATIC_ANALYSIS,
            "lastUpdated[le]": maxLastUpdated
        ]
        List<Long> customerIdListToSetManualAnalysisRequired = CustomerDocumentAnalysisStatus.query(queryParams).list(max: maxItemsPerCycle)
        if (!customerIdListToSetManualAnalysisRequired) return

        Utils.withNewTransactionAndRollbackOnError({
            Map updateParams = [
                now: new Date(),
                customerIdList: customerIdListToSetManualAnalysisRequired
            ]
            CustomerDocumentAnalysisStatus.executeUpdate("UPDATE CustomerDocumentAnalysisStatus SET status = 'MANUAL_ANALYSIS_REQUIRED', lastUpdated = :now WHERE customer.id IN (:customerIdList) AND deleted = 0", updateParams)
        }, [logErrorMessage: "CustomerDocumentAnalysisStatusService.updateStatusAfterAutomaticAnalysisDeadline >> Não foi possível atualizar o status das análises de documentos [${customerIdListToSetManualAnalysisRequired.join(', ')}] após o prazo de análise automática."])
    }

    public void updateStatusOfNotFinishedAnalysis() {
        final Integer maxHoursForNotFinishedAnalysis = -2
        Date maxLastUpdated = CustomDateUtils.sumHours(new Date(), maxHoursForNotFinishedAnalysis)

        Utils.withNewTransactionAndRollbackOnError({
            updateCustomerDocumentAnalysisStatusForNotFinishedAnalysis(maxLastUpdated)
            deleteInProgressDocumentAnalysisForNotFinishedAnalysis(maxLastUpdated)
        }, [logErrorMessage: "CustomerDocumentAnalysisStatusService.updateStatusOfNotFinishedAnalysis >> Não foi possível atualizar o status das análises de documentos após o fim do prazo de análise manual."])
    }

    private void updateCustomerDocumentAnalysisStatusForNotFinishedAnalysis(Date maxLastUpdated) {
        final Integer maxItemsPerCycle = 50
        Map customerStatusQueryParams = [
            column: "customer.id",
            status: DocumentAnalysisStatus.IN_PROGRESS,
            "lastUpdated[le]": maxLastUpdated
        ]

        List<Long> customerIdListToSetManualAnalysisRequired = CustomerDocumentAnalysisStatus.query(customerStatusQueryParams).list(max: maxItemsPerCycle)
        if (!customerIdListToSetManualAnalysisRequired) return

        Map updateParams = [
            now: new Date(),
            customerIdList: customerIdListToSetManualAnalysisRequired,
            status: DocumentAnalysisStatus.MANUAL_ANALYSIS_REQUIRED
        ]

        CustomerDocumentAnalysisStatus.executeUpdate("UPDATE CustomerDocumentAnalysisStatus SET status = :status, lastUpdated = :now WHERE customer.id IN (:customerIdList) AND deleted = false ", updateParams)
    }

    private void deleteInProgressDocumentAnalysisForNotFinishedAnalysis(Date maxLastUpdated) {
        final Integer maxItemsPerCycle = 50
        Map documentAnalysisQueryParams = [
            column: "customer.id",
            status: DocumentAnalysisStatus.IN_PROGRESS,
            "lastUpdated[le]": maxLastUpdated
        ]

        List<Long> customerIdListToBeDeleted = DocumentAnalysis.query(documentAnalysisQueryParams).list(max: maxItemsPerCycle)
        if (!customerIdListToBeDeleted) return

        Map updateParams = [
            now: new Date(),
            customerIdList: customerIdListToBeDeleted,
            status: DocumentAnalysisStatus.IN_PROGRESS
        ]

        DocumentAnalysis.executeUpdate("UPDATE DocumentAnalysis SET deleted = 1, lastUpdated = :now WHERE status = :status AND customer.id IN (:customerIdList) AND deleted = false", updateParams)
    }

    private Boolean hasPostponedAnalysisOutOfTimeForCustomer(Long customerId) {
        Map documentAnalysisQueryParams = [
            "columnList": ["lastUpdated", "postponedReason"],
            "customerId": customerId,
            "status": DocumentAnalysisStatus.POSTPONED,
            "sort": "lastUpdated",
            "order": "desc"
        ]
        Map documentAnalysis = DocumentAnalysis.query(documentAnalysisQueryParams).get()
        if (!documentAnalysis) return false

        DocumentAnalysisPostponedReason reason = documentAnalysis.postponedReason
        Date lastUpdated = documentAnalysis.lastUpdated
        Date currentDate = new Date()

        if (!reason) {
            final Integer defaultPeriodToAnalysisInHoursIfNoReason = 6
            Date postponeEndDate = CustomDateUtils.sumHours(lastUpdated, defaultPeriodToAnalysisInHoursIfNoReason)

            return currentDate.after(postponeEndDate)
        }

        Integer defaultPeriodToAnalysisInHours = reason.defaultPeriodToAnalysisInHours
        Date postponeEndDate = CustomDateUtils.sumHours(lastUpdated, defaultPeriodToAnalysisInHours)

        return currentDate.after(postponeEndDate)
    }
}

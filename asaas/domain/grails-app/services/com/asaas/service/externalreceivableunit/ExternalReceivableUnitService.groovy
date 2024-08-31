package com.asaas.service.externalreceivableunit

import com.asaas.domain.externalreceivableunit.ExternalReceivableUnit
import com.asaas.exception.BusinessException
import com.asaas.externalreceivableunit.vo.ExternalReceivableUnitVO
import com.asaas.integration.cerc.adapter.externalreceivableunit.ExternalReceivableUnitAdapter
import com.asaas.integration.cerc.adapter.externalreceivableunit.ExternalReceivableUnitGroupAdapter
import com.asaas.integration.cerc.adapter.externalreceivableunit.QueryExternalReceivableUnitResponseAdapter
import com.asaas.receivableunit.PaymentArrangement
import com.asaas.utils.Utils
import grails.transaction.Transactional

@Transactional
class ExternalReceivableUnitService {

    def cercManagerService
    def externalReceivableUnitSettlementService

    public QueryExternalReceivableUnitResponseAdapter queryExternalReceivableUnit(String customerCpfCnpj, List<String> acquirerCnpjList, Boolean shouldQueryAllAcquirers, List<PaymentArrangement> paymentArrangementList, Date startDate, Date endDate) {
        validateQuerySearchParams(customerCpfCnpj, acquirerCnpjList, shouldQueryAllAcquirers, paymentArrangementList, startDate, endDate)

        ExternalReceivableUnitVO externalReceivableUnitVO = new ExternalReceivableUnitVO(customerCpfCnpj, acquirerCnpjList, shouldQueryAllAcquirers, paymentArrangementList, startDate, endDate)
        QueryExternalReceivableUnitResponseAdapter response = cercManagerService.queryExternalReceivableUnit(externalReceivableUnitVO, true)

        processQueryExternalReceivableUnitResponse(response)

        return response
    }

    public Map rawQueryExternalReceivableUnit(String customerCpfCnpj, String acquirerCnpj, Boolean shouldQueryAllAcquirers, PaymentArrangement paymentArrangement, Date startDate, Date endDate) {
        validateQuerySearchParams(customerCpfCnpj, [acquirerCnpj], shouldQueryAllAcquirers, [paymentArrangement], startDate, endDate)

        ExternalReceivableUnitVO externalReceivableUnitVO = new ExternalReceivableUnitVO(customerCpfCnpj, [acquirerCnpj], shouldQueryAllAcquirers, [paymentArrangement], startDate, endDate)

        return cercManagerService.rawQueryExternalReceivableUnit(externalReceivableUnitVO)
    }

    private void processQueryExternalReceivableUnitResponse(QueryExternalReceivableUnitResponseAdapter queryExternalReceivableUnitResponseAdapter) {
        for (ExternalReceivableUnitGroupAdapter externalReceivableUnitGroupAdapter : queryExternalReceivableUnitResponseAdapter.externalReceivableUnitGroupAdapterList) {
            for (ExternalReceivableUnitAdapter externalReceivableUnitAdapter : externalReceivableUnitGroupAdapter.externalReceivableUnitAdapterList) {
                Utils.withNewTransactionAndRollbackOnError({
                    ExternalReceivableUnit externalReceivableUnit = save(externalReceivableUnitGroupAdapter.acquirerCpnj, queryExternalReceivableUnitResponseAdapter.customerCpfCnpj, externalReceivableUnitGroupAdapter.paymentArrangement, externalReceivableUnitAdapter)
                    externalReceivableUnitSettlementService.refreshSettlements(externalReceivableUnit, externalReceivableUnitAdapter.settlementList)
                }, [logErrorMessage: "ExternalReceivableUnitService.processQueryExternalReceivableUnitResponse >> Ocorreu um erro ao persistir os dados da consulta de agenda externa"])
            }
        }
    }

    private ExternalReceivableUnit save(String acquirerCnpj, String customerCpfCnpj, PaymentArrangement paymentArrangement, ExternalReceivableUnitAdapter externalReceivableUnitAdapter) {
        ExternalReceivableUnit externalReceivableUnit = ExternalReceivableUnit.findByCompositeKey(customerCpfCnpj, acquirerCnpj, paymentArrangement, externalReceivableUnitAdapter.estimatedCreditDate).get()

        if (!externalReceivableUnit) {
            externalReceivableUnit = new ExternalReceivableUnit()
            externalReceivableUnit.estimatedCreditDate = externalReceivableUnitAdapter.estimatedCreditDate
            externalReceivableUnit.acquirerCnpj = acquirerCnpj
            externalReceivableUnit.customerCpfCnpj = customerCpfCnpj
            externalReceivableUnit.paymentArrangement = paymentArrangement
        }

        externalReceivableUnit.netValue = externalReceivableUnitAdapter.netValue
        externalReceivableUnit.preAnticipatedNetValue = externalReceivableUnitAdapter.preAnticipatedNetValue
        externalReceivableUnit.blockedValue = externalReceivableUnitAdapter.blockedValue
        externalReceivableUnit.value = externalReceivableUnitAdapter.value
        externalReceivableUnit.availableValue = externalReceivableUnitAdapter.availableValue
        externalReceivableUnit.save(failOnError: true)

        return externalReceivableUnit
    }

    private void validateQuerySearchParams(String customerCpfCnpj, List<String> acquirerCnpjList, Boolean shouldQueryAllAcquirers, List<PaymentArrangement> paymentArrangementList, Date startDate, Date endDate) {
        if (!customerCpfCnpj || !startDate || !endDate || (!shouldQueryAllAcquirers && !acquirerCnpjList)) throw new BusinessException("Um ou mais campos não foram informados")

        Boolean hasAnyPaymentArrangementNotAllowed = paymentArrangementList.any { !PaymentArrangement.listAllowedToQueryExternalReceivableUnits().contains(it) }
        if (hasAnyPaymentArrangementNotAllowed) throw new BusinessException("Arranjo de pagamento inválido")
    }
}

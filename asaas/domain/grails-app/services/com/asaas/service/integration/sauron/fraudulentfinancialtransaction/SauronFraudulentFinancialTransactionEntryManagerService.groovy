package com.asaas.service.integration.sauron.fraudulentfinancialtransaction

import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.exception.ResourceNotFoundException
import com.asaas.integration.sauron.adapter.fraudulentfinancialtransaction.FraudulentFinancialTransactionEntryAdapter
import com.asaas.integration.sauron.adapter.fraudulentfinancialtransaction.children.SauronFraudulentFinancialTransactionEntryAdapter
import com.asaas.integration.sauron.api.SauronManager
import com.asaas.integration.sauron.dto.fraudulentfinancialtransaction.SauronFraudulentFinancialTransactionEntryRequestDTO
import com.asaas.integration.sauron.dto.fraudulentfinancialtransaction.SauronListFraudulentFinancialTransactionEntryResponseDTO
import com.asaas.integration.sauron.dto.fraudulentfinancialtransaction.children.SauronFraudulentFinancialTransactionEntryResponseDTO
import com.asaas.pagination.SequencedResultList
import com.asaas.utils.GsonBuilderUtils
import grails.converters.JSON
import grails.transaction.Transactional
import org.springframework.http.HttpStatus

@Transactional
class SauronFraudulentFinancialTransactionEntryManagerService {

    public void save(FraudulentFinancialTransactionEntryAdapter fraudulentFinancialTransactionEntryAdapter) {
        if (!AsaasEnvironment.isProduction()) return

        SauronFraudulentFinancialTransactionEntryRequestDTO requestDTO = new SauronFraudulentFinancialTransactionEntryRequestDTO(fraudulentFinancialTransactionEntryAdapter)

        SauronManager sauronManager = buildSauronManager()
        sauronManager.post("/fraudulentFinancialTransactionEntries", requestDTO)

        if (!sauronManager.isSuccessful()) {
            if (sauronManager.isUnprocessableEntity()) {
                throw new BusinessException(sauronManager.getErrorMessages().first())
            }

            throw new RuntimeException("FraudulentFinancialTransactionEntryManagerService.save >> Ocorreu um erro no Sauron ao tentar salvar a transação como fraudulenta. ResponseBody: [${sauronManager.responseBody}].")
        }
    }

    public SauronFraudulentFinancialTransactionEntryAdapter findById(Long id) {
        if (!AsaasEnvironment.isProduction()) return null

        SauronManager sauronManager = buildSauronManager()
        sauronManager.get("/fraudulentFinancialTransactionEntries/${id}", [:])

        if (!sauronManager.isSuccessful()) {
            if (sauronManager.isNotFound()) {
                throw new ResourceNotFoundException("Não foi possível localizar a transação [${id}] marcada como fraude")
            }
            throw new RuntimeException("FraudulentFinancialTransactionEntryManagerService.findById >> Ocorreu um erro no Sauron ao tentar buscar a transação fraudulenta. ResponseBody: [${sauronManager.responseBody}].")
        }

        SauronFraudulentFinancialTransactionEntryResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((sauronManager.responseBody as JSON).toString(), SauronFraudulentFinancialTransactionEntryResponseDTO)

        return new SauronFraudulentFinancialTransactionEntryAdapter(responseDTO)
    }

    public SequencedResultList<SauronFraudulentFinancialTransactionEntryAdapter> list(Map filterMap) {
        if (!AsaasEnvironment.isProduction()) return null

        SauronManager sauronManager = buildSauronManager()
        sauronManager.get("/fraudulentFinancialTransactionEntries/list", filterMap)

        if (!sauronManager.isSuccessful()) {
            throw new RuntimeException("FraudulentFinancialTransactionEntryManagerService.list >> Ocorreu um erro no Sauron ao tentar buscar as transações fraudulentas. ResponseBody: [${sauronManager.responseBody}].")
        }

        SauronListFraudulentFinancialTransactionEntryResponseDTO responseDTO = GsonBuilderUtils.buildClassFromJson((sauronManager.responseBody as JSON).toString(), SauronListFraudulentFinancialTransactionEntryResponseDTO)

        return new SequencedResultList(
            responseDTO.list.collect { new SauronFraudulentFinancialTransactionEntryAdapter(it) },
            responseDTO.max,
            responseDTO.offset,
            responseDTO.hasPreviousPage,
            responseDTO.hasNextPage)
    }

    public void delete(Long id) {
        if (!AsaasEnvironment.isProduction()) return

        SauronManager sauronManager = buildSauronManager()
        sauronManager.delete("/fraudulentFinancialTransactionEntries/${id}", [:])

        if (!sauronManager.isSuccessful()) {
            throw new RuntimeException("FraudulentFinancialTransactionEntryManagerService.delete >> Ocorreu um erro no Sauron ao tentar deletar a transação fraudulenta. ResponseBody: [${sauronManager.responseBody}].")
        }
    }

    private SauronManager buildSauronManager() {
        final Integer timeout = 10000

        SauronManager sauronManager = new SauronManager()
        sauronManager.ignoreErrorHttpStatus([HttpStatus.NOT_FOUND.value()])
        sauronManager.setTimeout(timeout)

        return sauronManager
    }
}

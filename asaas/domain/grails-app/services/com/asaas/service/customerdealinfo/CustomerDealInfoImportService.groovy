package com.asaas.service.customerdealinfo

import com.asaas.customerdealinfo.DealerTeam
import com.asaas.domain.customer.Customer
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.feenegotiation.FeeNegotiationType
import com.asaas.importdata.ExcelImporter
import com.asaas.importdata.FileImporter
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class CustomerDealInfoImportService {

    def customerDealInfoService
    def messageService

    public Boolean processFile(CommonsMultipartFile file, String currentUserEmail) {
        if (file.empty) throw new BusinessException("Nenhum arquivo foi selecionado.")

        final Integer limitOfRows = 1500
        FileImporter importer = new ExcelImporter(file)
            .startAtRow(1)
            .setLimitOfRowsToImport(limitOfRows)
            .setColumnNames([
                "A": "customerId",
                "B": "dealDate",
                "C": "dealerEmail",
                "D": "dealerTeam"
            ])

        List<Map> inconsistencyList = []
        importer.setFlushAndClearEvery(50).forEachRow { Map rowData ->
            Utils.withNewTransactionAndRollbackOnError({
                try {
                    saveItem(rowData)
                } catch (BusinessException businessException) {
                    rowData.fieldInconsistency = businessException.getMessage()
                    inconsistencyList.add(rowData)
                }
            }, [
                logErrorMessage: "CustomerDealInfoImportService.processFile >> Erro ao salvar item da importação de informações da negociação de clientes [${rowData.toString()}]",
                onError: { Exception exception -> throw exception }
            ])
        }

        if (!importer.numberOfReadRows) throw new BusinessException("Nenhuma linha processada")

        if (inconsistencyList) {
            messageService.sendCustomerDealInfoImportInconsistencies(inconsistencyList, currentUserEmail)
            return false
        }

        return true
    }

    private void saveItem(Map rowData) {
        rowData.customerId = rowData.customerId as Long

        String inconsistency = validate(rowData)
        if (inconsistency) throw new BusinessException(inconsistency)

        Customer customer = Customer.read(rowData.customerId)
        User dealer = User.activeByUsername(rowData.dealerEmail).get()
        DealerTeam dealerTeam = DealerTeam.convert(rowData.dealerTeam)
        Date dealDate = CustomDateUtils.toDate(rowData.dealDate)
        FeeNegotiationType feeNegotiationType = FeeNegotiationType.COMMERCIAL_ANALYST

        customerDealInfoService.saveOrUpdate(customer, dealer, dealerTeam, feeNegotiationType, dealDate)
    }

    private String validate(Map rowData) {
        if (!rowData.customerId) return "Cliente não informado"

        Boolean customerExists = Customer.query([exists: true, id: rowData.customerId]).get().asBoolean()
        if (!customerExists) return "Cliente não encontrado"

        if (!rowData.dealerEmail) return "Usuário não informado"

        User dealer = User.activeByUsername(rowData.dealerEmail).get()
        if (!dealer) return "Usuário não encontrado"

        if (!CustomDateUtils.toDate(rowData.dealDate)) return "Data da negociação não informada"

        DealerTeam dealerTeam = DealerTeam.convert(rowData.dealerTeam)
        if (!dealerTeam) return "Time não encontrado"

        return null
    }
}

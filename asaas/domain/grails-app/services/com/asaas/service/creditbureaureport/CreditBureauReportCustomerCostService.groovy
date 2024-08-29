package com.asaas.service.creditbureaureport

import com.asaas.customer.PersonType
import com.asaas.domain.creditbureaureport.CreditBureauReportCustomerCost
import com.asaas.domain.customer.Customer
import com.asaas.exception.BusinessException
import com.asaas.importdata.ExcelImporter
import com.asaas.importdata.FileImporter
import com.asaas.log.AsaasLogger
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional

import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class CreditBureauReportCustomerCostService {

    public Map importFile(CommonsMultipartFile file) {
        if (file.empty) throw new BusinessException(Utils.getMessageProperty('baseImport.error.fileEmpty'))

        final Integer limitOfRowsToImport = 80000
        FileImporter importer = new ExcelImporter(file)
            .startAtRow(1)
            .setLimitOfRowsToImport(limitOfRowsToImport)
            .setFlushAndClearEvery(50)
            .setColumnNames([
                "C": "cnpj",
                "D": "initialDate"
            ])

        Integer importedRows = 0
        importer.forEachRow { Map row ->
            Utils.withNewTransactionAndRollbackOnError( {
                if (!row.cnpj || !row.initialDate) throw new BusinessException("Verifique os dados, CNPJ ou Data Inicial não informados.")

                Map fileRow = [
                    cnpj: row.cnpj,
                    initialDate: row.initialDate instanceof Date ? row.initialDate : CustomDateUtils.fromStringDatabaseDateFormat(row.initialDate.toString()),
                    legalPersonCost: CreditBureauReportCustomerCost.LEGAL_PERSON_COST_WITH_DISCOUNT,
                    naturalPersonCost: CreditBureauReportCustomerCost.NATURAL_PERSON_COST_WITH_DISCOUNT
                ]

                Boolean hasCreditBureauReportCustomerCostAlready = CreditBureauReportCustomerCost.query([exists: true, cnpj: row.cnpj]).get().asBoolean()
                if (!hasCreditBureauReportCustomerCostAlready) {
                    save(fileRow)
                    importedRows++
                } else {
                    AsaasLogger.info("CreditBureauReportCustomerCostService.importFile >>> Já existe registro na tabela para o CNPJ ${row.cnpj}")
                }
            }, [ignoreStackTrace: true,
                onError: { Exception exception ->
                    AsaasLogger.warn("${this.class.simpleName}.importFile >> Ocorreu um erro ao salvar um item da planilha importada do Serasa: ${row}", exception)
                }
            ])
        }

        return ["importedRows": importedRows, "totalRows": importer.numberOfReadRows]
    }

    public CreditBureauReportCustomerCost save(Map params) {
        CreditBureauReportCustomerCost creditBureauReportCustomerCost = new CreditBureauReportCustomerCost()

        creditBureauReportCustomerCost.cnpj = Utils.removeNonNumeric(params.cnpj)
        creditBureauReportCustomerCost.initialDate = params.initialDate
        creditBureauReportCustomerCost.naturalPersonCost = params.naturalPersonCost
        creditBureauReportCustomerCost.legalPersonCost = params.legalPersonCost

        return creditBureauReportCustomerCost.save(failOnError: true)
    }

    public BigDecimal calculateCost(Customer customer, PersonType personType) {
        CreditBureauReportCustomerCost creditBureauReportCustomerCost = CreditBureauReportCustomerCost.query([cnpj: customer.cpfCnpj, "initialDate[le]": new Date().clearTime()]).get()

        if (personType.isFisica()) return creditBureauReportCustomerCost?.naturalPersonCost ?: CreditBureauReportCustomerCost.NATURAL_PERSON_COST

        return creditBureauReportCustomerCost?.legalPersonCost ?: CreditBureauReportCustomerCost.LEGAL_PERSON_COST
    }
}

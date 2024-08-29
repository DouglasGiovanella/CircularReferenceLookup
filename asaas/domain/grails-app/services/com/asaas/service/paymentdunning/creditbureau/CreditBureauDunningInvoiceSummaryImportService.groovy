package com.asaas.service.paymentdunning.creditbureau

import com.asaas.customer.CompanyType
import com.asaas.customer.PersonType
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.PaymentDunning
import com.asaas.domain.paymentdunning.creditbureau.conciliation.CreditBureauDunningInvoiceSummary
import com.asaas.exception.BusinessException
import com.asaas.importdata.paymentdunning.creditbureau.CreditBureauDunningInvoiceSummaryExcelImporter
import com.asaas.paymentdunning.PaymentDunningType
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class CreditBureauDunningInvoiceSummaryImportService {

    def creditBureauDunningInvoiceSummaryService
    def grailsApplication

    private final String PRODUCT_DESCRIPTION_FOR_CUSTOMER_ACCOUNT_LEGAL_PERSON = "ANOTACAO CONVEM DEVEDORES COM CARTA BOLETO PJ"
    private final String PRODUCT_DESCRIPTION_FOR_CUSTOMER_ACCOUNT_NATURAL_PERSON = "ANOTACAO CONVEM DEVEDORES COM CARTA BOLETO PF"
    private final List<String> CREDIT_BUREAU_DUNNING_LOGON_ALLOWED_LIST = ["03139166", "67940559", "34744720", "81874045", "03242775"]
    private final String ASAAS_COST_CENTER = "1019540550"

    public List<CreditBureauDunningInvoiceSummary> processFile(CommonsMultipartFile file) {
        if (file.empty) throw new BusinessException("Nenhum arquivo foi selecionado.")

        List<CreditBureauDunningInvoiceSummary> invoiceSummaryList = []

        CreditBureauDunningInvoiceSummaryExcelImporter excelImporter = new CreditBureauDunningInvoiceSummaryExcelImporter(file)
        excelImporter.setFlushAndClearEvery(50).forEachRow { Map item ->
            if (!isValidItem(item)) return

            CreditBureauDunningInvoiceSummary invoiceSummary = saveItem(item)
            if (invoiceSummary) invoiceSummaryList.add(invoiceSummary)
        }

        return invoiceSummaryList
    }

    private CreditBureauDunningInvoiceSummary saveItem(Map item) {
        PersonType personType = getPersonType(item.productDescription)
        List<Customer> customerList = listCustomer(item.costCenter)

        String cpfCnpj = customerList.first().cpfCnpj

        Map invoiceSummaryMap = [
            customerAccountPersonType: personType,
            partialCnpj: item.costCenter.toString().substring(2),
            cpfCnpj: cpfCnpj,
            quantity: Utils.toInteger(item.quantity),
            startDate: CustomDateUtils.fromStringDatabaseDateFormat(item.invoiceSummaryStartDate.toString()),
            endDate: CustomDateUtils.fromStringDatabaseDateFormat(item.invoiceSummaryEndDate.toString())
        ]

        Boolean hasMultipleCustomerWithSameCpfCnpj = customerList.size() != 1
        if (!hasMultipleCustomerWithSameCpfCnpj) invoiceSummaryMap.customer = customerList.first()

        CreditBureauDunningInvoiceSummary creditBureauDunningInvoiceSummary = creditBureauDunningInvoiceSummaryService.save(invoiceSummaryMap)
        if (!creditBureauDunningInvoiceSummary.hasErrors()) return creditBureauDunningInvoiceSummary
    }

    private Boolean isValidItem(Map item) {
        if (!getProductDescriptionAllowed().contains(item.productDescription)) return false

        if (!CREDIT_BUREAU_DUNNING_LOGON_ALLOWED_LIST.contains(item.logon)) {
            throw new BusinessException("Existem registros a serem importados que possuem o logon diferente dos permitidos pela aplicação. Entre em contato com a equipe de BackOffice.")
        }

        return true
    }

    private List<String> getProductDescriptionAllowed() {
        return [PRODUCT_DESCRIPTION_FOR_CUSTOMER_ACCOUNT_NATURAL_PERSON, PRODUCT_DESCRIPTION_FOR_CUSTOMER_ACCOUNT_LEGAL_PERSON]
    }

    private List<Customer> listCustomer(String costCenter) {
        if (costCenter == ASAAS_COST_CENTER) return [Customer.get(grailsApplication.config.asaas.debtRecoveryCustomer.id)]

        String initialCnpj = costCenter.substring(2)

        List<Customer> customerList = Customer.createCriteria().list() {
            like("cpfCnpj", initialCnpj + "%")
            eq("personType", PersonType.JURIDICA)
            'in'("companyType", [CompanyType.INDIVIDUAL, CompanyType.LIMITED, CompanyType.MEI])

            exists PaymentDunning.where {
                setAlias("paymentDunning")
                eqProperty("paymentDunning.customer.id", "this.id")

                eq('paymentDunning.deleted', false)
                eq('paymentDunning.type', PaymentDunningType.CREDIT_BUREAU)
            }.id()
        }

        if (!customerList) throw new BusinessException("Não foi encontrado nenhum cliente com negativação para o centro de custo ${costCenter}")

        return customerList
    }

    private PersonType getPersonType(String productDescription) {
        if ([PRODUCT_DESCRIPTION_FOR_CUSTOMER_ACCOUNT_NATURAL_PERSON].contains(productDescription)) return PersonType.FISICA

        if ([PRODUCT_DESCRIPTION_FOR_CUSTOMER_ACCOUNT_LEGAL_PERSON].contains(productDescription)) return PersonType.JURIDICA

        throw new UnsupportedOperationException()
    }
}

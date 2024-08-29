package com.asaas.service.sdnentry

import com.asaas.customer.CustomerStatus
import com.asaas.customerregisterstatus.GeneralApprovalStatus
import com.asaas.domain.customer.Customer
import com.asaas.domain.sdnentry.SDNEntry
import com.asaas.domain.sdnentry.SDNEntryOccurency
import com.asaas.log.AsaasLogger
import com.asaas.riskAnalysis.RiskAnalysisReason
import com.asaas.utils.Utils
import com.github.kevinsawicki.http.HttpRequest
import grails.transaction.Transactional

@Transactional
class SdnEntryService {

    def messageService

    def riskAnalysisRequestService
    def riskAnalysisTriggerCacheService

    private String main_table_url = "https://www.treasury.gov/ofac/downloads/sdn.pip"

    private String alt_table_url = "https://www.treasury.gov/ofac/downloads/alt.pip"

  	public void processSdnList() {
        AsaasLogger.info("----> Started processing processSdnList")

        AsaasLogger.info("----> Getting mainTable from url")
        HttpRequest mainTableResponse = HttpRequest.get(this.main_table_url)
        if (!mainTableResponse) {
            throw new RuntimeException("Ocorreu um erro ao consultar ofac para main_table.")
        }

        AsaasLogger.info("----> Started processing main ofac table")
        processMainTable(mainTableResponse.body())
        AsaasLogger.info("----> Finished processing main ofac table")


        AsaasLogger.info("----> Getting alternative table from url")
        HttpRequest alternativeTableResponse = HttpRequest.get(this.alt_table_url)
        if (!alternativeTableResponse) {
            throw new RuntimeException("Ocorreu um erro ao consultar ofac para alt_table.")
        }

        AsaasLogger.info("----> Started processing alternative ofac table")
        processAlternativeTable(alternativeTableResponse.body())
        AsaasLogger.info("----> Finished processing alternative ofac table")
    }

    public void reportAboutCustomerInSDNListIfNecessary(Customer customer) {
        SDNEntry sdnEntry = customer.getSdnEntry()
        if (sdnEntry) {
			messageService.OFACCustomerReport(customer, sdnEntry)
            saveSDNEntryOccurency(sdnEntry, customer)
		}
    }

    public void processCustomersInSdnList(Date dateFromToConsiderNewEntries) {
        if (!dateFromToConsiderNewEntries) throw new RuntimeException("É obrigatório informar uma data de início")

        List<SDNEntryOccurency> ocurrencies = []

        Map customerRegisterStatus = [ 'generalApproval[ne]': GeneralApprovalStatus.REJECTED ]
        AsaasLogger.info("----> Started processing customer in ofac list")

        List<SDNEntry> sdnEntryList = SDNEntry.query(["lastUpdated[ge]": dateFromToConsiderNewEntries]).list()
        for (SDNEntry entry in sdnEntryList) {
            List<Customer> customerList = Customer.query([
                customerRegisterStatus: customerRegisterStatus,
                "status[ne]": CustomerStatus.BLOCKED,
                "name": entry.sdnName
            ]).list()

            for (Customer customer in customerList) {
                SDNEntryOccurency ocurrency = SDNEntryOccurency.find(entry, customer)
                if (!ocurrency) ocurrency = saveSDNEntryOccurency(entry, customer)
                ocurrencies.add(ocurrency)
            }
        }
        AsaasLogger.info("found ${ocurrencies.size()} ocurrencies processing customers in ofac list")
        if (ocurrencies.size() > 0) messageService.OFACListReport(ocurrencies)
    }

    public SDNEntryOccurency saveSDNEntryOccurency(SDNEntry sdnEntry, Customer customer) {
        SDNEntryOccurency ocurrency = new SDNEntryOccurency()
        ocurrency.customer = customer
        ocurrency.sdnEntry = sdnEntry
        ocurrency.dateCreated = new Date()
        ocurrency.save(failOnError: true)

        saveRiskAnalysisForCustomerFoundInOfacListIfNecessary(customer)

        return ocurrency
    }

    private void saveRiskAnalysisForCustomerFoundInOfacListIfNecessary(Customer customer) {
        final RiskAnalysisReason riskAnalysisReason = RiskAnalysisReason.CUSTOMER_FOUND_IN_OFAC_LIST
        if (!riskAnalysisTriggerCacheService.getInstance(riskAnalysisReason).enabled) return

        riskAnalysisRequestService.save(customer, riskAnalysisReason, null)
    }

    private void processMainTable(String mainTable) {
        List<String> rowList = mainTable.split("\r\n")

        Utils.forEachWithFlushSession(rowList, 50, { String row ->
            Utils.withNewTransactionAndRollbackOnError({
                List<String> unformattedMainEntry = row.split("\\|")

                Long entNum = Utils.toLong(unformattedMainEntry[0])
                if (!entNum) return

                SDNEntry sdnEntry = SDNEntry.query([entNum: entNum]).get() ?: new SDNEntry()
                sdnEntry.parseMainList(unformattedMainEntry)
                sdnEntry.save(failOnError: true)
            }, [logErrorMessage: "SdnEntryService.processMainTable >> Erro ao processar linha da lista principal da OFAC.\nDados: [${row}]"])
        })
    }

    private void processAlternativeTable(String alternativeTable) {
        List<String> rowList = alternativeTable.split("\r\n")

        Utils.forEachWithFlushSession(rowList, 50, { String row ->
            Utils.withNewTransactionAndRollbackOnError({
                List<String> unformattedAltEntry = row.split("\\|")

                Long altNum = Utils.toLong(unformattedAltEntry[1])
                if (!altNum) return

                SDNEntry sdnEntry = SDNEntry.query([altNum: altNum]).get() ?: new SDNEntry()
                sdnEntry.parseAltList(unformattedAltEntry)
                sdnEntry.save(failOnError: true)
            }, [logErrorMessage: "SdnEntryService.processAlternativeTable >> Erro ao processar linha da lista alternativa da OFAC.\nDados: [${row}]"])
        })
    }
}

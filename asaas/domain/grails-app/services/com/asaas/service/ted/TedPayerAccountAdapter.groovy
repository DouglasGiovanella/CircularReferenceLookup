package com.asaas.service.ted

import com.asaas.customer.PersonType
import com.asaas.domain.financialinstitution.FinancialInstitution
import com.asaas.integration.jdspb.api.parser.TedMessageDecoder
import com.asaas.integration.jdspb.api.utils.JdSpbUtils
import com.asaas.log.AsaasLogger
import com.asaas.ted.TedAccountType
import com.asaas.utils.Utils
import groovy.util.slurpersupport.GPathResult

class TedPayerAccountAdapter {

    FinancialInstitution financialInstitution

    String agency

    String account

    TedAccountType accountType

    PersonType personType

    String cpfCnpj

    String name

    public TedPayerAccountAdapter(GPathResult contentData, String messageCode) {
        if (!contentData.ISPBIFDebtd.isEmpty()) {
            String parsedIspb = JdSpbUtils.parseIspb(Utils.toLong(contentData.ISPBIFDebtd.text()))
            this.financialInstitution = FinancialInstitution.query([paymentServiceProviderIspb: parsedIspb]).get()
            if (!this.financialInstitution) {
                this.financialInstitution = FinancialInstitution.query([bankIspb: parsedIspb]).get()
                if (!this.financialInstitution) AsaasLogger.warn("TedPayerAccountAdapter >> Instituição financeira não identificada [ispb: ${contentData.ISPBIFDebtd.text()}]")
            }
        }
        this.agency = contentData.AgDebtd.text()
        this.account = contentData.CtDebtd.text()
        this.accountType = JdSpbUtils.convertAccountType(contentData.TpCtDebtd.text())
        this.personType = TedMessageDecoder.decodePersonType(contentData, messageCode)
        this.cpfCnpj = TedMessageDecoder.decodeCpfCnpjPayer(financialInstitution, this.personType, contentData, messageCode)
        this.name = TedMessageDecoder.decodePayerName(financialInstitution, contentData, messageCode)
    }
}

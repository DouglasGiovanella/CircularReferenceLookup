package com.asaas.service.beamer

import com.asaas.domain.customer.Customer
import com.asaas.domain.integration.beamer.BeamerImport
import com.asaas.integration.beamer.enums.BeamerImportType

import grails.transaction.Transactional

@Transactional
class BeamerImportService {

    public BeamerImport saveBeamerImportIfNecessary(Customer customer, BeamerImportType type) {
        BeamerImport beamerImport = BeamerImport.query([customer: customer, type: type]).get()

        if (!beamerImport) {
            beamerImport = new BeamerImport()
            beamerImport.customer = customer
            beamerImport.type = type
            beamerImport.save()
        }

        return beamerImport
    }
}

package com.asaas.service.wallet

import com.asaas.domain.customer.Customer
import com.asaas.domain.wallet.Wallet

import grails.transaction.Transactional

@Transactional
class WalletService {

    public Wallet save(Customer destinationCustomer) {
        if (Wallet.query([exists: true, destinationCustomer: destinationCustomer]).get().asBoolean()) throw new RuntimeException("Wallet for destinationCustomer [${destinationCustomer.id}] already exists.")

        Wallet wallet = new Wallet(destinationCustomer: destinationCustomer, type: "ASAAS", publicId: UUID.randomUUID())
        wallet.save(failOnError: true)
        return wallet
    }

    public Wallet findOrSave(Customer destinationCustomer) {
        Wallet wallet = Wallet.query([destinationCustomer: destinationCustomer]).get()
        if (wallet) return wallet

        return save(destinationCustomer)
    }

}

package com.asaas.service.api

import com.asaas.domain.customer.Customer
import com.asaas.domain.wallet.Wallet

import grails.transaction.Transactional

@Transactional
class ApiWalletService extends ApiBaseService {

	def apiResponseBuilderService
    def walletService

	def list(params) {
        Customer customer = getProviderInstance(params)

        List<Wallet> walletList = Wallet.query([destinationCustomer: customer]).list(max: getLimit(params), offset: getOffset(params))
        if (!walletList) {
            Wallet wallet = walletService.save(customer)
            walletList.add(wallet)
        }
        def wallets = walletList.collect { buildResponseItem(it) }

        return apiResponseBuilderService.buildList(wallets, getLimit(params), getOffset(params), walletList.totalCount)
	}

    private buildResponseItem(Wallet wallet) {
        def model = [:]
        model.object = 'wallet'
        model.id = wallet.publicId

        return model
    }
}

package com.asaas.service.customerexternalauthorization

import com.asaas.api.ApiBillParser
import com.asaas.api.ApiMobilePhoneRechargeParser
import com.asaas.api.ApiTransferParser
import com.asaas.api.pix.ApiPixTransactionParser
import com.asaas.customerexternalauthorization.CustomerExternalAuthorizationRequestConfigType
import com.asaas.domain.bill.Bill
import com.asaas.domain.customer.Customer
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequest
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestBill
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestConfig
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestMobilePhoneRecharge
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestPixQrCode
import com.asaas.domain.customerexternalauthorization.CustomerExternalAuthorizationRequestTransfer
import com.asaas.domain.mobilephonerecharge.MobilePhoneRecharge
import com.asaas.domain.pix.PixTransaction
import com.asaas.domain.transfer.Transfer

import grails.transaction.Transactional

import groovy.json.JsonOutput

@Transactional
class CustomerExternalAuthorizationRequestCreateService {

    public void saveForTransfer(Transfer transfer) {
        CustomerExternalAuthorizationRequest externalAuthorizationRequest = save(transfer.customer, CustomerExternalAuthorizationRequestConfigType.TRANSFER)

        CustomerExternalAuthorizationRequestTransfer externalAuthorizationTransfer = new CustomerExternalAuthorizationRequestTransfer()
        externalAuthorizationTransfer.externalAuthorizationRequest = externalAuthorizationRequest
        externalAuthorizationTransfer.transfer = transfer
        externalAuthorizationTransfer.data = JsonOutput.toJson(ApiTransferParser.buildResponseItem(transfer, [apiVersion: externalAuthorizationRequest.config.apiVersion]))
        externalAuthorizationTransfer.save(failOnError: true)
    }

    public void saveForPixQrCode(PixTransaction pixTransaction) {
        CustomerExternalAuthorizationRequest externalAuthorizationRequest = save(pixTransaction.customer, CustomerExternalAuthorizationRequestConfigType.PIX_QR_CODE)

        CustomerExternalAuthorizationRequestPixQrCode externalAuthorizationPixQrCode = new CustomerExternalAuthorizationRequestPixQrCode()
        externalAuthorizationPixQrCode.externalAuthorizationRequest = externalAuthorizationRequest
        externalAuthorizationPixQrCode.pixTransaction = pixTransaction
        externalAuthorizationPixQrCode.data = JsonOutput.toJson(ApiPixTransactionParser.buildResponseItem(pixTransaction))
        externalAuthorizationPixQrCode.save(failOnError: true)
    }

    public void saveForBill(Bill bill) {
        CustomerExternalAuthorizationRequest externalAuthorizationRequest = save(bill.customer, CustomerExternalAuthorizationRequestConfigType.BILL)

        CustomerExternalAuthorizationRequestBill externalAuthorizationBill = new CustomerExternalAuthorizationRequestBill()
        externalAuthorizationBill.externalAuthorizationRequest = externalAuthorizationRequest
        externalAuthorizationBill.bill = bill
        externalAuthorizationBill.data = JsonOutput.toJson(ApiBillParser.buildResponseItem(bill, [apiVersion: externalAuthorizationRequest.config.apiVersion]))
        externalAuthorizationBill.save(failOnError: true)
    }

    public void saveForMobilePhoneRecharge(MobilePhoneRecharge mobilePhoneRecharge) {
        CustomerExternalAuthorizationRequest externalAuthorizationRequest = save(mobilePhoneRecharge.customer, CustomerExternalAuthorizationRequestConfigType.MOBILE_PHONE_RECHARGE)

        CustomerExternalAuthorizationRequestMobilePhoneRecharge externalAuthorizationMobilePhoneRecharge = new CustomerExternalAuthorizationRequestMobilePhoneRecharge()
        externalAuthorizationMobilePhoneRecharge.externalAuthorizationRequest = externalAuthorizationRequest
        externalAuthorizationMobilePhoneRecharge.mobilePhoneRecharge = mobilePhoneRecharge
        externalAuthorizationMobilePhoneRecharge.data = JsonOutput.toJson(ApiMobilePhoneRechargeParser.buildResponseItem(mobilePhoneRecharge))
        externalAuthorizationMobilePhoneRecharge.save(failOnError: true)
    }

    private CustomerExternalAuthorizationRequest save(Customer customer, CustomerExternalAuthorizationRequestConfigType type) {
        CustomerExternalAuthorizationRequestConfig config = CustomerExternalAuthorizationRequestConfig.query([customer: customer, type: type]).get()

        CustomerExternalAuthorizationRequest externalAuthorizationRequest = new CustomerExternalAuthorizationRequest()
        externalAuthorizationRequest.config = config
        externalAuthorizationRequest.save(failOnError: true)

        return externalAuthorizationRequest
    }
}

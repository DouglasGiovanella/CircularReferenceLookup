package com.asaas.service.pix

import com.asaas.domain.pix.PixCreditFee
import com.asaas.domain.pix.PixCreditFeeHistory
import grails.transaction.Transactional

@Transactional
class PixCreditFeeHistoryService {

    public PixCreditFeeHistory save(PixCreditFee pixCreditFee) {
        PixCreditFeeHistory pixCreditFeeHistory = new PixCreditFeeHistory()
        pixCreditFeeHistory.pixCreditFee = pixCreditFee
        pixCreditFeeHistory.type = pixCreditFee.type
        pixCreditFeeHistory.fixedFee = pixCreditFee.fixedFee
        pixCreditFeeHistory.fixedFeeWithDiscount = pixCreditFee.fixedFeeWithDiscount
        pixCreditFeeHistory.discountExpiration = pixCreditFee.discountExpiration
        pixCreditFeeHistory.percentageFee = pixCreditFee.percentageFee
        pixCreditFeeHistory.minimumFee = pixCreditFee.minimumFee
        pixCreditFeeHistory.maximumFee = pixCreditFee.maximumFee
        pixCreditFeeHistory.save(failOnError: true)

        return pixCreditFeeHistory
    }
}

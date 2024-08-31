package com.asaas.service.pix.errorlog

import com.asaas.domain.pix.PixAsaasQrCode
import com.asaas.domain.pix.errorlog.PixQrCodeErrorLog
import com.asaas.pix.PixQrCodeErrorLogType

import grails.transaction.Transactional

@Transactional
class PixQrCodeErrorLogService {

    public PixQrCodeErrorLog saveConnectionErrorOnSynchronize(PixAsaasQrCode pixAsaasQrCode) {
        PixQrCodeErrorLog errorLog = new PixQrCodeErrorLog()
        errorLog.pixAsaasQrCode = pixAsaasQrCode
        errorLog.type = PixQrCodeErrorLogType.CONNECTION_ERROR_ON_SYNCHRONIZE_QR_CODE
        errorLog.save(failOnError: true)
        return errorLog
    }

}

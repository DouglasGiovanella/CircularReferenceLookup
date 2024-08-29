package com.asaas.service.customerimei

import com.asaas.domain.customerimei.CustomerImei
import com.asaas.domain.user.User
import com.asaas.mobileappidentifier.MobileAppIdentifier

import grails.transaction.Transactional

@Transactional
class CustomerImeiService {

    public CustomerImei save(User user, String imei, MobileAppIdentifier platform, String device) {
        CustomerImei customerImei = new CustomerImei(user: user, imei: imei, platform: platform, device: device)
        customerImei.save(flush: true, failOnError: true)

        return customerImei
    }    

    public CustomerImei saveCaseNotExists(User user, String imei, MobileAppIdentifier platform, String device) {
        if (!CustomerImei.query([exists: true, user: user, imei: imei]).get()) {
            return save(user, imei, platform, device)
        }

        return CustomerImei.query([user: user, imei: imei]).get()
    }

    public void saveImeis(User user, List<String> imeis, MobileAppIdentifier platform, String device) {
        for(String imei in imeis) {
            saveCaseNotExists(user, imei, platform, device)
        }
        
    }
}

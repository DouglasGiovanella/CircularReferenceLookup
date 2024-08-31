package com.asaas.service.crypto

import com.asaas.crypto.CrypterAlgorithm
import com.asaas.domain.crypto.CrypterDomainInitialVector
import com.asaas.crypto.Crypter

import grails.transaction.Transactional

@Transactional
class CrypterService {

    def grailsApplication

    public String encryptDomainProperty(def domain, String propertyName, String unencryptedData) {
        return encryptDomainProperty(domain, propertyName, unencryptedData, null)
    }

    public String encryptDomainProperty(def domain, String propertyName, String unencryptedData, String secret) {
        Map encryptedKey = encrypt(unencryptedData, secret)

        CrypterDomainInitialVector oldCrypterDomainInitialVector = CrypterDomainInitialVector.query([domainClass: domain.class.name, domainId: domain.id, propertyName: propertyName]).get()
        if (oldCrypterDomainInitialVector) {
            oldCrypterDomainInitialVector.deleted = true
            oldCrypterDomainInitialVector.save(failOnError: true)
        }

        CrypterDomainInitialVector crypterDomainInitialVector = new CrypterDomainInitialVector()
        crypterDomainInitialVector.domainClass = domain.class.name
        crypterDomainInitialVector.domainId = domain.id
        crypterDomainInitialVector.propertyName = propertyName
        crypterDomainInitialVector.iv = encryptedKey.iv
        crypterDomainInitialVector.save(failOnError: true)

        return encryptedKey.encryptedString
    }

    public String decryptDomainProperty(def domain, String propertyName, String encryptedData) {
        return decryptDomainProperty(domain, propertyName, encryptedData, null)
    }

    public String decryptDomainProperty(def domain, String propertyName, String encryptedData, String secret) {
        String iv = CrypterDomainInitialVector.query([column: "iv", domainClass: domain.class.name, domainId: domain.id, propertyName: propertyName]).get()

        if (!iv) throw new RuntimeException("Initial Vector nao encontrado, para utilizar esse metodo a string deve ter sida criptografada com o encryptDomainProperty")

        return decrypt(encryptedData, iv, secret)
    }

    public Map encrypt(String dataToEncrypt, String secret) {
        if (!secret) secret = grailsApplication.config.asaas.crypter.secret

        Crypter crypter = new Crypter(secret, CrypterAlgorithm.AES)
        return crypter.encryptAES(dataToEncrypt)
    }

    public String decrypt(String encryptedData, String iv, String secret) {
        if (!secret) secret = grailsApplication.config.asaas.crypter.secret

        Crypter crypter = new Crypter(secret, CrypterAlgorithm.AES)
        return crypter.decryptAES(encryptedData, iv)
    }

    public Boolean isDomainPropertyEncrypted(def domain, String propertyName) {
        return CrypterDomainInitialVector.query([column: "iv", domainClass: domain.class.name, domainId: domain.id, propertyName: propertyName, exists: true]).get().asBoolean()
    }
}

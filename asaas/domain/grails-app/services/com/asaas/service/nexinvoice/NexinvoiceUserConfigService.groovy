package com.asaas.service.nexinvoice

import com.asaas.domain.nexinvoice.NexinvoiceUserConfig
import com.asaas.domain.user.User
import com.asaas.nexinvoice.repository.NexinvoiceUserConfigRepository
import grails.transaction.Transactional

@Transactional
class NexinvoiceUserConfigService {

    def userService

    public NexinvoiceUserConfig save(User user) {
        NexinvoiceUserConfig nexinvoiceUserConfig = new NexinvoiceUserConfig()

        nexinvoiceUserConfig.user = user
        nexinvoiceUserConfig.isIntegrated = false

        nexinvoiceUserConfig.save(failOnError: true)

        return nexinvoiceUserConfig
    }

    public List<NexinvoiceUserConfig> saveUserList(List<User> users) {
        List<NexinvoiceUserConfig> nexinvoiceUserConfigs = []
        for (User user : users) {
            nexinvoiceUserConfigs.add(save(user))
        }

        return nexinvoiceUserConfigs
    }

    public void update(User user, String externalId, Boolean isIntegrated) {
        NexinvoiceUserConfig nexinvoiceUserConfig = NexinvoiceUserConfigRepository.query([userId: user.id]).get()

        if (!nexinvoiceUserConfig) {
            throw new RuntimeException("Não foi possível encontrar o usuário: ${user.id}")
        }

        nexinvoiceUserConfig.isIntegrated = isIntegrated
        nexinvoiceUserConfig.externalId = externalId
        nexinvoiceUserConfig.save(failOnError: true)
    }

    public void deleteByUserId(Long userId) {
        NexinvoiceUserConfig nexinvoiceUserConfig = NexinvoiceUserConfigRepository.query([userId: userId]).get()
        if (!nexinvoiceUserConfig) return

        delete(nexinvoiceUserConfig.id)
    }

    public void deleteCustomerUserConfig(Long customerId) {
        List<NexinvoiceUserConfig> nexinvoiceUserConfigList = NexinvoiceUserConfigRepository.query(["customerId": customerId]).list()

        for (NexinvoiceUserConfig nexinvoiceUserConfig : nexinvoiceUserConfigList) {
            delete(nexinvoiceUserConfig.id)
        }
    }

    private void delete(Long nexinvoiceUserConfigId) {
        NexinvoiceUserConfig nexinvoiceUserConfig = NexinvoiceUserConfig.get(nexinvoiceUserConfigId)
        if (!nexinvoiceUserConfig) return

        nexinvoiceUserConfig.deleted = true
        nexinvoiceUserConfig.save(failOnError: true)
    }
}

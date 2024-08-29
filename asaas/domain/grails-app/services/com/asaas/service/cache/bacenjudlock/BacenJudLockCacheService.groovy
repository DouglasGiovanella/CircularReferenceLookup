package com.asaas.service.cache.bacenjudlock

import com.asaas.bacenjud.enums.JudicialLockStatus
import com.asaas.domain.bacenjud.BacenJudLock

import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class BacenJudLockCacheService {

    @Cacheable(value = "BacenJudLock:listOpenedDefendantCpfCnpj", key="'openedDefendantCpfCnpjList'")
    public List<String> listOpenedDefendantCpfCnpj() {
        return BacenJudLock.query("column": "defendantCpfCnpj", "status": JudicialLockStatus.OPENED).list()
    }

    @Cacheable(value = "BacenJudLock:listOpenedDefendantRootCnpj", key="'openedDefendantRootCnpjList'")
    public List<String> listOpenedDefendantRootCnpj() {
        return BacenJudLock.query("column": "defendantRootCnpj", "status": JudicialLockStatus.OPENED).list()
    }

    @CacheEvict(value = "BacenJudLock:listOpenedDefendantCpfCnpj", key="'openedDefendantCpfCnpjList'")
    public void evictOpenedDefendantCpfCnpjList() {
        return
    }

    @CacheEvict(value = "BacenJudLock:listOpenedDefendantRootCnpj", key="'openedDefendantRootCnpjList'")
    public void evictOpenedDefendantRootCnpjList() {
        return
    }
}

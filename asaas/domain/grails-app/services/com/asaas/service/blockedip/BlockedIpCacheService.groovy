package com.asaas.service.blockedip

import grails.plugin.cache.CachePut
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class BlockedIpCacheService {

    @SuppressWarnings("UnusedMethodParameter")
    @CachePut(value = "BlockedIp:byRemoteIp", key = "#remoteIp")
    public Date save(String remoteIp, Date releaseDate) {
        return releaseDate
    }

    @SuppressWarnings("UnusedMethodParameter")
    @Cacheable(value = "BlockedIp:byRemoteIp", key = "#remoteIp")
    public Date getReleaseDateIfExists(String remoteIp) {
        return null // Dados devem vir apenas do cache
    }
}

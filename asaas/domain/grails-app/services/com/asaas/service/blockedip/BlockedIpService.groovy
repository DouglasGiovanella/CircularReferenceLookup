package com.asaas.service.blockedip

import com.asaas.domain.blockedip.BlockedIp
import com.asaas.log.AsaasLogger
import com.asaas.user.UserUtils
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional

@Transactional
class BlockedIpService {

    def blockedIpCacheService

    public BlockedIp save(String remoteIp, String controller, String action) {
        Integer lastHourBlockCount = getRecentBlockCount(remoteIp)

        Integer blockMinutes = 5 * (1 + lastHourBlockCount)

        Date releaseDate = CustomDateUtils.sumMinutes(new Date(), blockMinutes)

        BlockedIp blockedIp = new BlockedIp()
        blockedIp.remoteIp = remoteIp
        blockedIp.releaseDate = releaseDate
        blockedIp.controller = controller
        blockedIp.action = action
        blockedIp.save(flush: true, failOnError: true)

        blockedIpCacheService.save(blockedIp.remoteIp, blockedIp.releaseDate)

        AsaasLogger.warn("${this.class.simpleName}.save >> Bloqueando endpoint [${controller}/${action}] para o IP [${remoteIp}] do usuário [${UserUtils.getCurrentUserId(null)}] por ${blockMinutes} minutos", new Throwable())

        return blockedIp
    }

    private Integer getRecentBlockCount(String remoteIp) {
        final Integer hoursToCount = 2

        Date recentBlockReferenceDate = CustomDateUtils.sumHours(new Date(), hoursToCount * -1)
        return BlockedIp.query([remoteIp: remoteIp, "dateCreated[ge]": recentBlockReferenceDate]).count()
    }
}

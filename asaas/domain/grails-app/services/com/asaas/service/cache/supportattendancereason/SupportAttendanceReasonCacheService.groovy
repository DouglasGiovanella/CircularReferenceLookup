package com.asaas.service.cache.supportattendancereason

import com.asaas.integration.callcenter.supportattendance.reason.adapter.SupportAttendanceReasonAdapter
import com.asaas.integration.callcenter.supportattendance.reason.adapter.SupportAttendanceReasonListFilterAdapter
import com.asaas.pagination.SequencedResultList

import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class SupportAttendanceReasonCacheService {

    def callCenterSupportAttendanceReasonManagerService

    @Cacheable(value = "SupportAttendanceReason:list", key="'list'")
    public List<Map> list() {
        SupportAttendanceReasonListFilterAdapter filterAdapter = new SupportAttendanceReasonListFilterAdapter([active: true, ignorePagination: true])
        SequencedResultList<SupportAttendanceReasonAdapter> supportAttendanceReasonList = callCenterSupportAttendanceReasonManagerService.list(filterAdapter)

        return supportAttendanceReasonList.collect { buildMap(it) }
    }

    @CacheEvict(value = "SupportAttendanceReason:list", key="'list'")
    public void evict() {
        return
    }

    private Map buildMap(SupportAttendanceReasonAdapter supportAttendanceReasonAdapter) {
        Map reasonMap = [:]
        reasonMap.id = supportAttendanceReasonAdapter.id
        reasonMap.description = supportAttendanceReasonAdapter.description

        return reasonMap
    }
}

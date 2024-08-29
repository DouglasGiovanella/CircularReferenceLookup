package com.asaas.service.holiday

import com.asaas.domain.holiday.Holiday
import com.asaas.utils.CustomDateUtils

import grails.plugin.cache.CacheEvict
import grails.plugin.cache.Cacheable
import grails.transaction.Transactional

@Transactional
class HolidayCacheService {

    @Cacheable(value = "Holiday:isHoliday", key = "#root.target.truncateDateKey(#date)")
    public Boolean isHoliday(Date date) {
        Date clonedDate = date.clone()
        Boolean isWeekend = CustomDateUtils.isWeekend(clonedDate.toCalendar())
        if (isWeekend) return true

        return Holiday.query([date: clonedDate.clearTime(), exists: true]).get().asBoolean()
    }

    public String truncateDateKey(Date date) {
        Date clonedDate = date.clone()
        return CustomDateUtils.fromDate(clonedDate, CustomDateUtils.DATABASE_DATE_FORMAT)
    }

    @CacheEvict(value = "Holiday:isHoliday", key = "#root.target.truncateDateKey(#date)")
    @SuppressWarnings("UnusedMethodParameter")
    public void evictIsHoliday(Date date) {
        return // Apenas remove a chave do Redis
    }
}


package com.asaas.service.pix.payment

import com.asaas.domain.payment.Payment
import com.asaas.domain.pix.payment.PixPaymentInfo
import com.asaas.utils.CustomDateUtils

import grails.transaction.Transactional
import org.hibernate.SQLQuery

@Transactional
class PixPaymentInfoService {

    def sessionFactory

    public void saveIfNecessary(Payment payment) {
        if (payment.dateCreated < getReleasedDate()) return

        if (!payment.canBeReceived()) return

        Boolean alreadyExists = PixPaymentInfo.query([exists: true, paymentId: payment.id]).get().asBoolean()
        if (alreadyExists) return

        PixPaymentInfo pixPaymentInfo = new PixPaymentInfo()
        pixPaymentInfo.payment = payment
        pixPaymentInfo.qrCodePublicId = UUID.randomUUID().toString()
        pixPaymentInfo.save(failOnError: true)
    }

    public void purgeIfNecessary(Payment payment) {
        if (!payment.status.hasBeenConfirmed()) return

        final String sql = "DELETE FROM pix_payment_info WHERE payment_id = :paymentId"
        SQLQuery sqlQuery = sessionFactory.currentSession.createSQLQuery(sql)
        sqlQuery.setLong("paymentId", payment.id)
        sqlQuery.executeUpdate()
    }

    private Date getReleasedDate() {
        Calendar calendar = CustomDateUtils.getInstanceOfCalendar()
        calendar.set(2023, Calendar.DECEMBER, 01, 00, 00)
        return calendar.getTime()
    }
}

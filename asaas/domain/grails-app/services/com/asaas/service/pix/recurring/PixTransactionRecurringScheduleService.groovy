package com.asaas.service.pix.recurring

import com.asaas.domain.financialinstitution.FinancialInstitution
import com.asaas.domain.recurringCheckoutSchedule.RecurringCheckoutSchedule
import com.asaas.domain.recurringCheckoutSchedule.RecurringCheckoutSchedulePixExternalAccount
import com.asaas.exception.BusinessException
import com.asaas.pix.vo.transaction.PixDebitVO
import com.asaas.recurringCheckoutSchedule.adapter.RecurringCheckoutScheduleAdapter
import com.asaas.recurringCheckoutSchedule.RecurringCheckoutScheduleFrequency
import com.asaas.service.recurringCheckoutSchedule.RecurringCheckoutScheduleService
import com.asaas.user.UserUtils
import com.asaas.userpermission.AdminUserPermissionUtils
import com.asaas.utils.DomainUtils

import grails.transaction.Transactional

@Transactional
class PixTransactionRecurringScheduleService {

    RecurringCheckoutSchedulePixItemService recurringCheckoutSchedulePixItemService
    RecurringCheckoutScheduleService recurringCheckoutScheduleService

    public void save(PixDebitVO pixDebitVO) {
        if (!AdminUserPermissionUtils.canAccessNotReleasedFeatures(UserUtils.getCurrentUser())) return
        validate(pixDebitVO.retrieveRecurringSchedule())

        RecurringCheckoutScheduleAdapter recurringCheckoutScheduleAdapter = pixDebitVO.retrieveRecurringSchedule()
        RecurringCheckoutSchedule recurringCheckoutSchedule = recurringCheckoutScheduleService.save(recurringCheckoutScheduleAdapter)
        if (recurringCheckoutSchedule.hasErrors()) throw new BusinessException(DomainUtils.getFirstValidationMessage(recurringCheckoutSchedule))

        saveExternalAccount(recurringCheckoutSchedule, pixDebitVO)

        recurringCheckoutSchedulePixItemService.saveList(recurringCheckoutSchedule, recurringCheckoutScheduleAdapter.transferDate)
    }

    public RecurringCheckoutSchedule cancel(Long id, Long customerId) {
        RecurringCheckoutSchedule recurringCheckoutSchedule = RecurringCheckoutSchedule.find(id, customerId)

        recurringCheckoutSchedule = recurringCheckoutScheduleService.cancel(recurringCheckoutSchedule)

        return recurringCheckoutSchedule
    }

    public RecurringCheckoutSchedule update(RecurringCheckoutSchedule recurringCheckoutSchedule , RecurringCheckoutScheduleAdapter recurringCheckoutScheduleAdapter) {
        validateUpdate(recurringCheckoutScheduleAdapter)

        return recurringCheckoutScheduleService.update(recurringCheckoutSchedule, recurringCheckoutScheduleAdapter)
    }

    private void validate(RecurringCheckoutScheduleAdapter recurringCheckoutScheduleAdapter) {
        if (!recurringCheckoutScheduleAdapter.frequency) throw new BusinessException("Frequência da recorrência não informada")
        if (!recurringCheckoutScheduleAdapter.quantity) throw new BusinessException("Quantidade da recorrência não informada")
        if (!recurringCheckoutScheduleAdapter.value) throw new BusinessException("Valor da recorrência não informado")

        if (!RecurringCheckoutScheduleFrequency.listPixAllowed().contains(recurringCheckoutScheduleAdapter.frequency)) {
            throw new BusinessException("Não é possível configurar uma recorrência Pix ${recurringCheckoutScheduleAdapter.frequency.getLabel()}")
        }

        if (recurringCheckoutScheduleAdapter.frequency.isMonthly()) {
            if (recurringCheckoutScheduleAdapter.quantity > RecurringCheckoutScheduleFrequency.MAX_MONTHLY_QUANTITY) {
                throw new BusinessException("Não é possível configurar uma recorrência Pix mensal com mais de ${RecurringCheckoutScheduleFrequency.MAX_MONTHLY_QUANTITY} meses")
            }
        } else if (recurringCheckoutScheduleAdapter.frequency.isWeekly()) {
            if (recurringCheckoutScheduleAdapter.quantity > RecurringCheckoutScheduleFrequency.MAX_WEEKLY_QUANTITY) {
                throw new BusinessException("Não é possível configurar uma recorrência Pix semanal com mais de ${RecurringCheckoutScheduleFrequency.MAX_WEEKLY_QUANTITY} semanas")
            }
        }
    }

    private void validateUpdate(RecurringCheckoutScheduleAdapter recurringCheckoutScheduleAdapter) {
        if (!recurringCheckoutScheduleAdapter.value || recurringCheckoutScheduleAdapter.value < RecurringCheckoutSchedule.MINIMUM_VALUE) {
            throw new BusinessException("O valor informado precisa ser maior que zero.")
        }
    }

    private void saveExternalAccount(RecurringCheckoutSchedule recurring, PixDebitVO pixDebitVO) {
        RecurringCheckoutSchedulePixExternalAccount externalAccount = new RecurringCheckoutSchedulePixExternalAccount()
        externalAccount.recurring = recurring
        externalAccount.financialInstitution = FinancialInstitution.findByIspb(pixDebitVO.externalAccount.ispb)
        externalAccount.originType = pixDebitVO.originType
        externalAccount.personType = pixDebitVO.externalAccount.personType
        externalAccount.cpfCnpj = pixDebitVO.externalAccount.cpfCnpj
        externalAccount.name = pixDebitVO.externalAccount.name
        externalAccount.agency = pixDebitVO.externalAccount.agency
        externalAccount.account = pixDebitVO.externalAccount.account
        externalAccount.accountType = pixDebitVO.externalAccount.accountType
        externalAccount.pixKey = pixDebitVO.externalAccount.pixKey
        externalAccount.pixKeyType = pixDebitVO.externalAccount.pixKeyType
        externalAccount.save(failOnError: true)
    }
}

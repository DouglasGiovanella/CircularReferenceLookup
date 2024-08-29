package com.asaas.service.invalidatedcredentials

import com.asaas.customer.CustomerParameterName
import com.asaas.domain.customer.Customer
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.invalidatedcredentials.InvalidatedCredentialsBatch
import com.asaas.domain.invalidatedcredentials.InvalidatedCredentialsBatchItem
import com.asaas.domain.user.User
import com.asaas.exception.BusinessException
import com.asaas.invalidatedcredentials.InvalidatedCredentialsStatus
import com.asaas.log.AsaasLogger
import com.asaas.utils.Utils
import grails.transaction.Transactional
import org.springframework.web.multipart.commons.CommonsMultipartFile

@Transactional
class InvalidatedCredentialsService {

    def asaasCardService
    def criticalActionConfigService
    def customerInteractionService
    def customerParameterService
    def fileService
    def messageService
    def userPasswordManagementService

    public InvalidatedCredentialsBatch save(User user, CommonsMultipartFile file) {
        final Integer limitCredentialsToBeInvalidated = 500

        InvalidatedCredentialsBatch invalidatedCredentialsBatch = new InvalidatedCredentialsBatch()
        invalidatedCredentialsBatch.user = user
        invalidatedCredentialsBatch.status = InvalidatedCredentialsStatus.PENDING
        invalidatedCredentialsBatch.asaasFile = fileService.saveFile(user.customer, file, null)
        invalidatedCredentialsBatch.save()

        List<String> usernames = parse(invalidatedCredentialsBatch.asaasFile)

        if (usernames.size() > limitCredentialsToBeInvalidated) throw new BusinessException(Utils.getMessageProperty("invalidatedCredentials.maxCredentialsError", [limitCredentialsToBeInvalidated]))

        for (String username : usernames) {
            Customer invalidatedCustomer = User.query([column: "customer", ignoreCustomer: true, username: username]).get()
            if (!invalidatedCustomer) continue
            InvalidatedCredentialsBatchItem invalidatedCredentialsBatchItem = new InvalidatedCredentialsBatchItem()
            invalidatedCredentialsBatchItem.invalidatedCustomer = invalidatedCustomer
            invalidatedCredentialsBatchItem.originUsername = username
            invalidatedCredentialsBatchItem.invalidatedCredentialsBatch = invalidatedCredentialsBatch
            invalidatedCredentialsBatchItem.save()
        }

        return invalidatedCredentialsBatch
    }

    public void cancelRequest(Long invalidatedCredentialsBatchId) {
        InvalidatedCredentialsBatch invalidatedCredentialsBatch = InvalidatedCredentialsBatch.get(invalidatedCredentialsBatchId)
        invalidatedCredentialsBatch.status = InvalidatedCredentialsStatus.CANCELED
        invalidatedCredentialsBatch.save()
    }

    public void invalidateCredentialsInBatch(Long invalidatedCredentialsBatchId) {
        List<Long> invalidatedCredentialsBatchItemIdList = InvalidatedCredentialsBatchItem.query([column: "id", invalidatedCredentialsBatchId: invalidatedCredentialsBatchId]).list()

        Utils.forEachWithFlushSession(invalidatedCredentialsBatchItemIdList, 10, { Long invalidatedCredentialsBatchItemId ->
            Utils.withNewTransactionAndRollbackOnError( {
                InvalidatedCredentialsBatchItem invalidatedCredentialsBatchItem = InvalidatedCredentialsBatchItem.get(invalidatedCredentialsBatchItemId)
                Customer customer = invalidatedCredentialsBatchItem.invalidatedCustomer
                invalidateCredentials(customer)
                customerInteractionService.save(customer, "Identificamos uma tentativa de acesso suspeito na conta do cliente.\n" +
                    "Isso pode indicar que alguém obteve acesso ao login e senha desta conta, e preventivamente invalidamos a senha.")

                AsaasLogger.warn("InvalidateCredentialsService.invalidateCredentialsInBatch -> Credenciais invalidadas das contas do cliente [${customer.id}].")
            }, [onError: { Exception e -> AsaasLogger.error("InvalidateCredentialsService.invalidateCredentialsInBatch -> Erro ao invalidar credenciais da conta do cliente referente ao InvalidatedCredentialsBatchItem [${invalidatedCredentialsBatchItemId}].", e) }])
        })
        InvalidatedCredentialsBatch invalidatedCredentialsBatch = InvalidatedCredentialsBatch.get(invalidatedCredentialsBatchId)
        invalidatedCredentialsBatch.status = InvalidatedCredentialsStatus.PROCESSED
        invalidatedCredentialsBatch.save()
    }

    public void invalidateCredentialsAndBlockCheckout(Customer customer) {
        invalidateCredentials(customer)
        customerParameterService.save(customer, CustomerParameterName.CHECKOUT_DISABLED, true)
        criticalActionConfigService.enableAll(customer)

        asaasCardService.blockAllCards(customer)

        if (customer.accountOwnerId || Customer.childAccounts(customer, [exists: true]).get().asBoolean()) {
            messageService.notifyInternalTechnicalSuportAboutHackedAccount(customer)
        }

        customerInteractionService.save(customer, "Utilizada opção de conta comprometida. Todos os usuários da conta tiveram suas senhas e acessos invalidados, eventos críticos ativados e saques bloqueados. A chave de API permanece válida.")
    }

    private void invalidateCredentials(Customer customer) {
        userPasswordManagementService.resetAllUsersPassword(customer)
    }

    private List<String> parse(AsaasFile file) {
        List<String> usernames = []
        Integer row = 0

        file.getInputStream { InputStream inputStream ->
            inputStream.splitEachLine(",") { fields ->
                if (row == 0 && fields[1] == "user") {
                    row++
                    return
                }
                if (row == 1 && !Utils.emailIsValid(fields[1])) throw new BusinessException(Utils.getMessageProperty("invalidatedCredentials.formatFileError"))
                usernames.add(fields[1])
                row++
            }
        }
        return usernames
    }
}

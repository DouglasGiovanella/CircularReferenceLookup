package com.asaas.service.externalidentifier

import com.asaas.exception.BusinessException
import com.asaas.externalidentifier.ExternalApplication
import com.asaas.domain.customer.Customer
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentExternalIdentifier
import grails.transaction.Transactional
import org.codehaus.groovy.grails.commons.DefaultGrailsDomainClass
import com.asaas.externalidentifier.SynchronizationStatus
import com.asaas.domain.externalidentifier.ExternalIdentifier
import com.asaas.externalidentifier.ExternalResource

@Transactional
class ExternalIdentifierService {

	def sessionFactory

	public PaymentExternalIdentifier findByPayment(Payment payment, ExternalApplication application) {
		return PaymentExternalIdentifier.executeQuery("""from PaymentExternalIdentifier
			                                            where payment = :payment
			                                              and application = :application
			                                              and deleted = false""", [payment: payment, application: application])[0]
	}

	public Boolean paymentIsSynchronized(Payment payment, ExternalApplication application) {
		def session = sessionFactory.currentSession
		def query = session.createSQLQuery("""select count(id)
			                                    from payment_external_identifier
		                                       where payment_id = :paymentId
		                                         and application = :application
		                                         and disabled = false
		                                         and deleted = false""");
		query.setLong("paymentId", payment.id)
		query.setString("application", application.toString())

		return query.list().get(0) > 0
	}

	public SynchronizationStatus getSynchronizationStatus(Payment domainObject, ExternalApplication application) {
		String externalIdentifierDefaultPropertyName = "externalIdentifiers"
		if (!domainObject.hasProperty(externalIdentifierDefaultPropertyName)) throw new BusinessException("A cobrança precisa ter o identificador externo vinculado")

		def domainClass = domainObject.getClass()
		def externalIdentifierClass = new DefaultGrailsDomainClass(domainClass).getPropertyByName(externalIdentifierDefaultPropertyName).getReferencedPropertyType()

		def hql = """select disabled
			           from ${externalIdentifierClass.simpleName}
			          where ${domainClass.simpleName.toLowerCase()} = :domainObject
			            and application = :application
			            and deleted = false"""
		def disabled = externalIdentifierClass.executeQuery(hql, [application: application, domainObject: domainObject])[0]
		def synchronizationStatus

		if(disabled == false) {
			synchronizationStatus = SynchronizationStatus.SUCCESS
		} else if(disabled == true) {
			synchronizationStatus = SynchronizationStatus.DISABLED
		} else {
			synchronizationStatus = SynchronizationStatus.WAITING
		}

		if(synchronizationStatus == SynchronizationStatus.WAITING && domainObject.integrationErrors) {
			synchronizationStatus = SynchronizationStatus.PENDING
		}

		return synchronizationStatus
	}

	public ExternalIdentifier save(object, ExternalApplication application, ExternalResource resource, String externalId) {
		return save(object, application, resource, externalId, null)
	}

	public ExternalIdentifier save(object, ExternalApplication application, ExternalResource resource, String externalId, Customer customer) {
		if(objectIsSynchronized(object, application, resource)) {
			delete(object, application, resource)
		}

		ExternalIdentifier externalIdentifier = new ExternalIdentifier()
		externalIdentifier.customer = customer
		externalIdentifier.object = object.class.simpleName
		externalIdentifier.objectId = object.id
		externalIdentifier.application = application
		externalIdentifier.resource = resource
		externalIdentifier.externalId = externalId

		externalIdentifier.save(flush: true, failOnError: true)

		return externalIdentifier
	}

	public ExternalIdentifier findByObject(object, ExternalApplication application, ExternalResource resource) {
		findByObject(object, application, resource, null)
	}

	public ExternalIdentifier findByObject(object, ExternalApplication application, ExternalResource resource, Customer customer) {
		StringBuilder hql = new StringBuilder()

		hql.append("  from ExternalIdentifier ei ")
		hql.append(" where ei.objectId = :objectId ")
		hql.append("   and ei.application = :application ")
		hql.append("   and ei.resource = :resource ")
		hql.append("   and ei.object = :object ")
		hql.append("   and ei.deleted = false ")

		Map hqlParams = [object: object.class.simpleName, objectId: object.id, application: application, resource: resource]

		if (customer) {
			hql.append(" and ei.customer = :customer ")
			hqlParams['customer'] = customer
		}

		return ExternalIdentifier.executeQuery(hql.toString(), hqlParams)[0]
	}

	public Boolean objectIsSynchronized(object, ExternalApplication application, ExternalResource resource) {
		def query = sessionFactory.currentSession.createSQLQuery("""select count(1)
																      from external_identifier ei
																     where ei.object = :object
																       and ei.object_id = :objectId
			                                                           and ei.application = :application
			                                                           and ei.resource = :resource
			                                                           and ei.disabled = false
			                                                           and ei.deleted = false""")

		query.setString("object", object.class.simpleName)
		query.setLong("objectId", object.id)
		query.setString("application", application.toString())
		query.setString("resource", resource.toString())

		return query.list().get(0) > 0
	}

	public void disable(object, ExternalApplication application, ExternalResource resource) {
		ExternalIdentifier externalIdentifier =	save(object, application, resource, "0")
		externalIdentifier.disabled = true

		externalIdentifier.save(failOnError: true)
	}

	public void delete(object, ExternalApplication application, ExternalResource resource) {
		ExternalIdentifier externalIdentifierExisting = findByObject(object, application, resource)

		if (!externalIdentifierExisting) throw new BusinessException("Não foi possível excluir. Identificador externo do objeto não encontrado.")

		externalIdentifierExisting.deleted = true
		externalIdentifierExisting.save(failOnError: true)
	}

	public List<String> listExternalId(String objectClassName, ExternalApplication application, ExternalResource resource, Long customerId) {
		def query = sessionFactory.currentSession.createSQLQuery("""select ei.external_id
																      from external_identifier ei
																     where ei.object = :object
			                                                           and ei.application = :application
			                                                           and ei.resource = :resource
			                                                           and ei.customer_id = :customerId
			                                                           and ei.disabled = false
			                                                           and ei.deleted = false""")

		query.setString("object", objectClassName)
		query.setString("application", application.toString())
		query.setString("resource", resource.toString())
		query.setLong("customerId", customerId)

		return query.list()
	}

	public void updateExternalId(object, ExternalApplication application, ExternalResource resource, Customer customer, String externalId) {
		ExternalIdentifier externalIdentifier = findByObject(object, application, resource, customer)

		if (!externalIdentifier) return

		externalIdentifier.externalId = externalId
		externalIdentifier.save(flush: true, failOnError: true)
	}

	public PaymentExternalIdentifier saveOrUpdatePaymentExternalIdentifier(Payment payment, ExternalApplication application, Map params) {
		PaymentExternalIdentifier paymentExternalIdentifier = PaymentExternalIdentifier.findOrCreateWhere(payment: payment, application: application, deleted: false)
		paymentExternalIdentifier.properties = params
		paymentExternalIdentifier.save(flush: true, failOnError: true)

		return paymentExternalIdentifier
	}
}

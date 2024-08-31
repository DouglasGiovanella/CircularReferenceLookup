package com.asaas.service.payment

import grails.transaction.Transactional
import com.asaas.domain.payment.Payment
import com.asaas.domain.customer.CustomerProduct
import com.asaas.domain.installment.Installment
import com.asaas.domain.subscription.Subscription
import com.asaas.domain.payment.PaymentCustomerProduct

@Transactional
class PaymentCustomerProductService {
	
	public PaymentCustomerProduct save(CustomerProduct customerProduct, Subscription subscription) {
		PaymentCustomerProduct paymentCustomerProduct = new PaymentCustomerProduct()
		paymentCustomerProduct.customerProduct = customerProduct
		paymentCustomerProduct.subscription = subscription
		paymentCustomerProduct.save(failOnError: true)
		
		return paymentCustomerProduct
	}

	public PaymentCustomerProduct save(CustomerProduct customerProduct, Installment installment) {
		PaymentCustomerProduct paymentCustomerProduct = new PaymentCustomerProduct()
		paymentCustomerProduct.customerProduct = customerProduct
		paymentCustomerProduct.installment = installment
		paymentCustomerProduct.save(failOnError: true)

		return paymentCustomerProduct
	}

	public PaymentCustomerProduct save(CustomerProduct customerProduct, Payment payment) {
		PaymentCustomerProduct paymentCustomerProduct = new PaymentCustomerProduct()
		paymentCustomerProduct.customerProduct = customerProduct
		paymentCustomerProduct.payment = payment
		paymentCustomerProduct.save(failOnError: true)

		return paymentCustomerProduct
	}

    public PaymentCustomerProduct save(CustomerProduct customerProduct) {
		PaymentCustomerProduct paymentCustomerProduct = new PaymentCustomerProduct()
		paymentCustomerProduct.customerProduct = customerProduct
		paymentCustomerProduct.save(failOnError: true)

		return paymentCustomerProduct
	}
}
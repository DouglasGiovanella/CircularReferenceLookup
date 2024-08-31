package com.asaas.service.boleto

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.bankslip.BankSlipVO
import com.asaas.boleto.BoletoBankInfo
import com.asaas.boleto.BoletoBuilder
import com.asaas.boleto.PaymentBook
import com.asaas.boleto.itau.ItauBoletoBuilder
import com.asaas.boleto.santander.SantanderBoletoBuilder
import com.asaas.customer.BaseCustomer
import com.asaas.customer.CustomerInfoFormatter
import com.asaas.customer.InvoiceCustomerInfo
import com.asaas.customer.PersonType
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.boleto.BoletoInfo
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerAccount
import com.asaas.domain.customer.CustomerBoletoConfig
import com.asaas.domain.customer.CustomerInvoiceConfig
import com.asaas.domain.customer.CustomerPaymentConfig
import com.asaas.domain.file.AsaasFile
import com.asaas.domain.file.TemporaryFile
import com.asaas.domain.interest.InterestConfig
import com.asaas.domain.payment.Payment
import com.asaas.domain.payment.PaymentDiscountConfig
import com.asaas.domain.payment.PaymentUndefinedBillingTypeConfig
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentBuilder
import com.asaas.payment.PaymentInterestCalculator
import com.asaas.payment.PaymentUtils
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CpfCnpjUtils
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.FormUtils
import com.asaas.utils.PhoneNumberUtils
import com.asaas.utils.UriUtils
import com.asaas.utils.Utils
import com.asaas.validation.BusinessValidation
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.transaction.Transactional
import org.apache.commons.lang.StringUtils
import org.apache.commons.lang.time.DateUtils
import org.jrimum.bopepo.BancosSuportados
import org.jrimum.bopepo.Boleto
import org.jrimum.bopepo.exemplo.banco.bradesco.NossoNumero
import org.jrimum.bopepo.parametro.ParametroBancoSicredi
import org.jrimum.bopepo.view.BoletoViewer
import org.jrimum.domkee.comum.pessoa.endereco.CEP
import org.jrimum.domkee.comum.pessoa.endereco.Endereco
import org.jrimum.domkee.comum.pessoa.endereco.UnidadeFederativa
import org.jrimum.domkee.financeiro.banco.ParametrosBancariosMap
import org.jrimum.domkee.financeiro.banco.febraban.Agencia
import org.jrimum.domkee.financeiro.banco.febraban.Carteira
import org.jrimum.domkee.financeiro.banco.febraban.Cedente
import org.jrimum.domkee.financeiro.banco.febraban.ContaBancaria
import org.jrimum.domkee.financeiro.banco.febraban.NumeroDaConta
import org.jrimum.domkee.financeiro.banco.febraban.Sacado
import org.jrimum.domkee.financeiro.banco.febraban.TipoDeCobranca
import org.jrimum.domkee.financeiro.banco.febraban.TipoDeTitulo
import org.jrimum.domkee.financeiro.banco.febraban.Titulo
import org.jrimum.domkee.financeiro.banco.febraban.Titulo.Aceite

import javax.imageio.ImageIO
import java.awt.*
import java.text.NumberFormat
import java.util.List

@Transactional
class BoletoService {

    def bankSlipService
    def grailsApplication
	def interestConfigService
	def linkService
    def temporaryFileService
    def fileService
    def pictureService

    public byte[] buildPdfBytes(Payment payment, Boolean original) {
        BoletoBank boletoBank = BoletoBank.buildBoletoBankFromPayment(payment)
        if (bankSlipService.isSupportedBoletoBank(boletoBank)) {
            return bankSlipService.buildPdf(payment, original, false)
        }

        return buildBoletoViewer(payment.provider, [payment], original, [pdfTitulo: "Boleto ${payment.customerAccount.name}"])
    }

    public byte[] buildPdfListBytes(Customer customer, List<Payment> paymentList, Boolean original) {
        BoletoBank boletoBank = BoletoBank.buildBoletoBankFromPayment(paymentList[0])
        if (bankSlipService.isSupportedBoletoBank(boletoBank)) {
            if (bankSlipService.shouldBuildPdfListUsingTessera()) {
                return bankSlipService.buildPdfBatchBytes(customer, paymentList, original)
            }
            return bankSlipService.buildPdfListBytes(paymentList, original)
        }

        return buildBoletoViewer(customer, paymentList, original, [:])
    }

	public Boolean boletoDisabledForCustomer(Customer customer) {
		return customer.boletoIsDisabled()
	}

    public Boleto buildBoleto(Payment payment, Boolean originalBankSlip) {
		if (boletoDisabledForCustomer(payment.provider) && !isAdminAndOriginalBankSlip(originalBankSlip)) throw new BusinessException("Geração de boletos desabilitada.")

		if (!payment.getCurrentBankSlipNossoNumero()) {
			payment.nossoNumero = PaymentBuilder.buildNossoNumero(payment)
			payment.save(failOnError: true)
		}

		Boolean buildOriginalBankSlip = (originalBankSlip || payment.deleted || (payment.boletoBank == null && payment.isOverdue()))

		if (!buildOriginalBankSlip && !payment.isPaid() && !payment.boletoBank) throw new BusinessException("Não é possível gerar o boleto da cobrança [${payment.getInvoiceNumber()}] pois ela não está registrada.")

		BoletoInfo defaultBoletoInfo = BoletoInfo.findDefaultBoletoInfo(payment.provider)

		BoletoInfo providerBoletoInfo = BoletoInfo.findWhere(customer: payment.provider)

		BoletoBankInfo boletoBankInfo = (payment.boletoBank ?: defaultBoletoInfo) as BoletoBankInfo

		Calendar dueDate = CustomDateUtils.getInstanceOfCalendar(payment.getCurrentBankSlipDueDate())
		Date now = DateUtils.truncate(new Date(), Calendar.DAY_OF_MONTH)

		if (!buildOriginalBankSlip && !payment.isPaid() && dueDate.getTime().before(now)) {
			dueDate.setTime(now)
		} else if (buildOriginalBankSlip) {
			dueDate.setTime(payment.dueDate)
		} else {
            dueDate.setTime(payment.getCurrentBankSlipDueDate())
        }

        Cedente cedente = new Cedente(providerBoletoInfo?.transferor ?: defaultBoletoInfo.transferor, providerBoletoInfo?.currentCpfCnpj?.trim() ?: defaultBoletoInfo.cpfCnpj.trim())

		Sacado sacado = buildSacado(payment.customerAccount)

		ContaBancaria contaBancaria = buildContaBancaria(boletoBankInfo, payment)

		Boleto boleto = new Boleto(buildTituloInfo(boletoBankInfo, contaBancaria, sacado, cedente, payment, dueDate.getTime(), buildOriginalBankSlip))
		boleto.setLocalPagamento("Pagável em qualquer banco ou casa lotérica")

		if (buildOriginalBankSlip) {
			boleto.addTextosExtras("txtFcDataProcessamento", CustomDateUtils.fromDate(payment.dueDate))
		}

		setInstrucao(boleto, defaultBoletoInfo, providerBoletoInfo, payment, buildOriginalBankSlip)

		overrideBoletoInfoIfNecessary(boleto, boletoBankInfo, payment)

        CustomerBoletoConfig boletoConfig = CustomerBoletoConfig.getConfigForCustomer(payment.provider)
        if (boletoConfig?.customTemplate) {
            if (boletoConfig.showCustomerInfo) setTxtRsInfoCedenteLinha(boleto, providerBoletoInfo, payment.provider, [usePreviousFieldOrder: true])

            if (boletoConfig.customTemplate.contains("stockInfo")) {
                buildStockInfoTemplate(payment, boleto, buildOriginalBankSlip)
            }
        } else if (bankSlipService.canUseTemplateWithHeader(payment.provider)) {
            CustomerInvoiceConfig customerInvoiceConfig = payment.provider.getInvoiceConfig()

            if (customerInvoiceConfig?.canUseBankSlipLogoPicture()) {
                setTxtRsLogoCedente(boleto, customerInvoiceConfig.customer, customerInvoiceConfig.getBankSlipLogo().getFileBytes(), customerInvoiceConfig.getLogoName())
            }

            setTxtRsInfoCedenteLinha(boleto, providerBoletoInfo, payment.provider, [:])
        }

        boleto.addTextosExtras("txtRsNumeroParcela", payment.installmentNumber?.toString())

        return boleto
    }

	public Boleto buildBoletoCbvjCorreios(Payment payment, Boolean originalBankSlip) {
		Boleto boleto = buildBoleto(payment, originalBankSlip)

		boleto.addTextosExtras("txtRsInfoSacadoNome", payment.customerAccount.name.toUpperCase())

		String addressLine = "${payment.customerAccount.address}"
		if (payment.customerAccount.addressNumber) addressLine += ", ${payment.customerAccount.addressNumber}"
		addressLine = [addressLine, payment.customerAccount.complement, payment.customerAccount.province].minus(null).join(' - ')
		boleto.addTextosExtras("txtRsInfoSacadoEndereco", addressLine)

		String cityLine = [payment.customerAccount.city?.name?.toUpperCase(), payment.customerAccount.city?.state, payment.customerAccount.postalCode].minus(null).join(' - ')
		boleto.addTextosExtras("txtRsInfoSacadoCidadeCep", cityLine)

		return boleto
	}

	public byte[] buildPaymentBook(List<Payment> paymentList, Boolean sortByDueDateAsc) {
        Customer customer = paymentList.first().provider
        if (bankSlipService.shouldBuildPdfListUsingTessera()) {
            return bankSlipService.buildPaymentBookBytes(customer, paymentList, false)
        }
        List<byte[]> bankSlipBytesList = []

        for (Payment payment : paymentList) {
            if (bankSlipService.isSupportedBoletoBank(payment.boletoBank)) {
                bankSlipBytesList.add(bankSlipService.buildPdfBytes(payment, false, true, null, false))
            } else {
                Boleto boleto = buildBoleto(payment, false)
                BoletoViewer boletoViewer = new BoletoViewer(boleto, getPaymentBookTemplate())
                bankSlipBytesList.add(boletoViewer.getPdfAsByteArray())
            }
        }

        return PaymentBook.mergeFilesInPages(bankSlipBytesList, sortByDueDateAsc)
	}

    public InputStream getBankSlipTestTemplate(Customer provider, Map params) {
        if (!provider) return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTestTemplate.path)

        if (params.customerInvoiceConfig) {
            if (!Utils.toBoolean(params.customerInvoiceConfig.removeBrand)) {
                return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTestTemplate.withLogoPath)
            }
            return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTestTemplate.withoutLogoPath)
        }

        CustomerInvoiceConfig customerInvoiceConfig = CustomerInvoiceConfig.findCurrent(provider)
        if (customerInvoiceConfig?.getBankSlipLogo()) {
            return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTestTemplate.withLogoPath)
        }
        return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTestTemplate.withoutLogoPath)
    }

    public String addProtestInstructionsIfExists(String instructions, Long customerId) {
        String protestMessage = CustomerPaymentConfig.getProtestMessageIfNecessary(customerId)
        if (protestMessage) instructions += "\n${protestMessage}"

        return instructions
    }

    public BusinessValidation validateBankSlipDownload(Payment payment, Boolean original) {
        BusinessValidation businessValidation = new BusinessValidation()

        if (payment.deleted) {
            businessValidation.addError("bankSlip.validateBankSlipDownload.error.paymentDeleted")
            return businessValidation
        }

        if (isAdminAndOriginalBankSlip(original)) return businessValidation

        if (!PaymentUndefinedBillingTypeConfig.shouldPaymentRegisterBankSlip(payment) && !payment.getDunning()?.hasCreditBureauPartnerProcess()) {
            businessValidation.addError("bankSlip.validateBankSlipDownload.error.billingTypeNotBankSlip")
            return businessValidation
        }

        if (payment.cannotBePaidAnymore()) {
            businessValidation.addError("bankSlip.validateBankSlipDownload.error.cannotBePaidAnymore")
            return businessValidation
        }

        if (boletoDisabledForCustomer(payment.provider)) {
            businessValidation.addError("bankSlip.validateBankSlipDownload.error.awaitingActivation")
            return businessValidation
        }

        return businessValidation
    }

    public BankSlipVO buildBankSlipPreview(Map params, Long providerId) {
        CustomerAccount customerAccount = new CustomerAccount(provider: providerId, name: "João Silva")

        Customer customer = Customer.findById(providerId)

        BoletoBank boletoBank = PaymentBuilder.selectBoletoBank(customer, customerAccount)

        Payment mockedPaymentForBankSlipPreview = new Payment(customerAccount: customerAccount, value: 150, dueDate: new Date(), externalToken: "1245785985545232", nossoNumero: "12457896", dateCreated: new Date(), provider: Customer.findById(providerId), boletoBank: boletoBank)

        BoletoInfo providerBoletoInfo = BoletoInfo.findWhere(customer: mockedPaymentForBankSlipPreview.provider)

        BoletoInfo defaultBoletoInfo = BoletoInfo.findDefaultBoletoInfo(customer)

        BankSlipVO bankSlipVO = BankSlipVO.buildBankSlipConfigPreview(mockedPaymentForBankSlipPreview)

        bankSlipVO.setBankSlipPreviewMockedInstructions(defaultBoletoInfo, providerBoletoInfo, mockedPaymentForBankSlipPreview, true, params)

        setBankSlipPreviewCustomerLogo(customer, params, bankSlipVO)

        return bankSlipVO
    }

    private void setBankSlipPreviewCustomerLogo(Customer customer, Map params, BankSlipVO bankSlipVO) {
        if (bankSlipVO.hasHeader && !(bankSlipVO.customTemplate == "cbvj")) {
            if (!Utils.toBoolean(params.customerInvoiceConfig?.removeBrand)) {
                byte[] fileBytes = []
                if (params.customerInvoiceConfig?.temporaryLogoFileId) {
                    TemporaryFile temporaryFile = temporaryFileService.find(customer, Long.valueOf(params.customerInvoiceConfig.temporaryLogoFileId))
                    AsaasFile asaasFile = fileService.saveFile(customer, null, temporaryFile)

                    fileBytes = resizeLogoPictureAndBuildBackgroundColorPreview(params, asaasFile)
                } else if (params.customerInvoiceConfig?.primaryColor) {
                    CustomerInvoiceConfig customerInvoiceConfig = CustomerInvoiceConfig.findCurrent(customer)
                    if (customerInvoiceConfig?.getLogo()) {
                        AsaasFile asaasFile = customerInvoiceConfig.getLogo()
                        fileBytes = resizeLogoPictureAndBuildBackgroundColorPreview(params, asaasFile)
                    }
                } else {
                    CustomerInvoiceConfig customerInvoiceConfig = CustomerInvoiceConfig.findCurrent(customer)
                    if (!customerInvoiceConfig) return

                    fileBytes = customerInvoiceConfig.getBankSlipLogo()?.getFileBytes()
                }

                bankSlipVO.setCustomerLogo(fileBytes)
            }
        }
    }

    private Sacado buildSacado(CustomerAccount customerAccount) {
		Sacado sacado
		if (customerAccount.cpfCnpj && CpfCnpjUtils.validate(customerAccount.cpfCnpj)) {
			sacado = new Sacado(customerAccount.name, CpfCnpjUtils.formatCpfCnpj(customerAccount.cpfCnpj))
		} else {
			sacado = new Sacado(customerAccount.name)
		}

		sacado.addEndereco(buildEndereco(customerAccount))

		return sacado
	}

	private Endereco buildEndereco(BaseCustomer baseCustomer) {
		Endereco endereco = new Endereco()

		if (baseCustomer.city) {
			endereco.setUF(UnidadeFederativa.valueOf(baseCustomer.city.state.toString()))
			endereco.setLocalidade(baseCustomer.city.name)
		} else {
			if (StringUtils.isNotBlank(baseCustomer.state)) {
				endereco.setUF(UnidadeFederativa.valueOf(baseCustomer.state.toUpperCase()))
			}

			if (StringUtils.isNotBlank(baseCustomer.cityString)) {
				endereco.setLocalidade(baseCustomer.cityString)
			}
		}

		if (StringUtils.isNotBlank(baseCustomer.postalCode)) {
			endereco.setCep(new CEP(baseCustomer.postalCode))
		}

		if (StringUtils.isNotBlank(baseCustomer.province)) {
			endereco.setBairro(baseCustomer.province)
		}

		if (StringUtils.isNotBlank(baseCustomer.address)) {
			endereco.setLogradouro(baseCustomer.address)
		}

		if (StringUtils.isNotBlank(baseCustomer.addressNumber)) {
			endereco.setNumero(baseCustomer.addressNumber)
		}

		if (StringUtils.isNotBlank(baseCustomer.complement)) {
			endereco.setComplemento(baseCustomer.complement)
		}

		return endereco
	}

	private ContaBancaria buildContaBancaria(BoletoBankInfo boletoBankInfo, Payment payment) {
		ContaBancaria contaBancaria = new ContaBancaria(BancosSuportados.valueOf(boletoBankInfo.bank.boletoValue).create())

		contaBancaria.setNumeroDaConta(new NumeroDaConta(Integer.valueOf(boletoBankInfo.covenant), boletoBankInfo.covenantDigit))

		contaBancaria.setCarteira(new Carteira(Integer.valueOf(boletoBankInfo.wallet)))

		if (payment.boletoBank) {
			contaBancaria.carteira.setTipoCobranca(TipoDeCobranca.COM_REGISTRO)
		}

		if (boletoBankInfo.agencyDigit) {
			contaBancaria.setAgencia(new Agencia(Integer.valueOf(boletoBankInfo.agency), boletoBankInfo.agencyDigit))
		} else {
			contaBancaria.setAgencia(new Agencia(Integer.valueOf(boletoBankInfo.agency)))
		}

		return contaBancaria
	}

	private Titulo buildTituloInfo(BoletoBankInfo boletoBankInfo, ContaBancaria contaBancaria, Sacado sacado, Cedente cedente, Payment payment, Date dueDate, Boolean originalBankSlip) {
		Titulo titulo = new Titulo(contaBancaria, sacado, cedente)

        Map nossoNumeroInfoMap = bankSlipService.buildNossoNumeroAndNossoNumeroDigitMap(boletoBankInfo, payment)
        titulo.setNossoNumero(nossoNumeroInfoMap.nossoNumero)

        if (nossoNumeroInfoMap.nossoNumeroDigit) titulo.setDigitoDoNossoNumero(nossoNumeroInfoMap.nossoNumeroDigit)

		titulo.setNumeroDoDocumento(payment.getInvoiceNumber())

		if (originalBankSlip) {
			titulo.setDataDoDocumento(payment.dueDate)
		} else {
			titulo.setDataDoDocumento(new Date())
		}

		titulo.setTipoDeDocumento(TipoDeTitulo.DM_DUPLICATA_MERCANTIL)

		titulo.setDataDoVencimento(dueDate)
		titulo.setValor(BigDecimal.valueOf(originalBankSlip && payment.originalValue ? payment.originalValue : payment.value))

		titulo.setAceite(Aceite.N)

		if (boletoBankInfo.bank.code == SupportedBank.SICREDI.code()) {
			titulo.setTipoDeDocumento(TipoDeTitulo.DSI_DUPLICATA_DE_SERVICO_PARA_INDICACAO)
			titulo.setParametrosBancarios(new ParametrosBancariosMap(ParametroBancoSicredi.POSTO_DA_AGENCIA, grailsApplication.config.asaas.bank.account.office.sicredi as Integer))
		}

		applyInterestIfNecessary(payment, titulo, originalBankSlip)

		return titulo
	}

	private void applyInterestIfNecessary(Payment payment, Titulo titulo, Boolean originalBankSlip) {
		if (payment.isPaid()) return

		if (payment.canApplyCurrentBankSlipFineAndInterest() && PaymentUtils.paymentDateHasBeenExceeded(payment) && !originalBankSlip) {
			Double fineAndInterestValue = interestConfigService.calculateFineAndInterestValue(payment, new Date())
			Double updatedValue = payment.value + fineAndInterestValue
			titulo.setValor(Math.round(updatedValue * 100) / 100)
		}
	}

	private void setInstrucao(Boleto boleto, BoletoInfo defaultBoletoInfo, BoletoInfo providerBoletoInfo, Payment payment, Boolean originalBankSlip) {
        String instructions
        if (providerBoletoInfo?.instructions != null && (payment.canApplyCurrentBankSlipInstructions() || originalBankSlip)) {
            instructions = providerBoletoInfo.instructions
        } else {
            instructions = defaultBoletoInfo.instructions
        }

        instructions = Utils.insertLineBreakEvery(instructions, grailsApplication.config.asaas.boleto.instructions.paymentDescription.breakLineEvery)

		instructions = addCancelledInstructionIfDeleted(instructions, payment)

        if (originalBankSlip || payment.canApplyCurrentBankSlipInstructions()) {
            if (!payment.deleted) {
                instructions = addInterestInstructionsIfExists(instructions, payment, providerBoletoInfo ?: defaultBoletoInfo, originalBankSlip)
            }

            instructions = addDiscountInstructionsIfExists(instructions, payment, originalBankSlip)
            instructions = addProtestInstructionsIfExists(instructions, payment.providerId)
            instructions = addPaymentDescriptionIfExists(instructions, payment)
        }

        Integer maximumRowsForInstructions = 8

        setInstrucao(boleto, instructions, 0, maximumRowsForInstructions, false)

        String invoiceUrl = "Fatura disponível em: ${linkService.viewInvoiceShort(payment)}"
        String contactDescription = "Cobrança intermediada por ASAAS GESTÃO FINANCEIRA - CNPJ 19.540.550/0001-21."

        CustomerBoletoConfig boletoConfig = CustomerBoletoConfig.getConfigForCustomer(payment.provider)
        if (boletoConfig) {
            Integer currentInstructionPosition = maximumRowsForInstructions

            if (!boletoConfig.hideContactInfo) {
                boleto."instrucao${currentInstructionPosition}" = contactDescription
                currentInstructionPosition--
            }

            if (!boletoConfig.hideInvoiceUrl) {
                boleto."instrucao${currentInstructionPosition}" = invoiceUrl
            }
        } else {
            boleto.setInstrucao7(invoiceUrl)
            boleto.setInstrucao8(contactDescription)
        }
	}

	private String addPaymentDescriptionIfExists(String instructions, Payment payment) {
		try {
            String customTemplate = CustomerBoletoConfig.getConfigForCustomer(payment.provider)?.customTemplate
            if (customTemplate == "stockInfo") return instructions

			if (payment.description) {
				instructions += "\n" + Utils.insertLineBreakEvery(payment.description, grailsApplication.config.asaas.boleto.instructions.paymentDescription.breakLineEvery)
			}

			if (payment.subscriptionPayments && payment.subscriptionPayments.size() > 0 && payment.subscriptionPayments[0].subscription.description && payment.subscriptionPayments[0].subscription.description != payment.description) {
				instructions += "\n" + Utils.insertLineBreakEvery(payment.subscriptionPayments[0].subscription.description, grailsApplication.config.asaas.boleto.instructions.paymentDescription.breakLineEvery)
			}
		} catch (Exception exception) {
            AsaasLogger.error("BoletoService.addPaymentDescriptionIfExists >> Erro ao adicionar descrição do pagamento. [paymentId: ${payment?.id}]", exception)
		}
		return instructions
	}

    private String addDiscountInstructionsIfExists(String instructions, Payment payment, Boolean originalBankSlip) {
        if (!originalBankSlip && !payment.canApplyCurrentBankSlipDiscount()) return instructions
        if (!payment.isPending()) return instructions

        PaymentDiscountConfig paymentDiscountConfig = PaymentDiscountConfig.query([paymentId: payment.id]).get()
        if (!paymentDiscountConfig?.valid()) return instructions

        BigDecimal discountValue = paymentDiscountConfig.calculateDiscountValue()

        instructions += "\nConceder ${FormUtils.formatCurrencyWithMonetarySymbol(discountValue)} de desconto "
        instructions += "para pagamento até ${CustomDateUtils.fromDate(paymentDiscountConfig.getDiscountLimitDate())}. "
        instructions += "Valor a pagar: ${FormUtils.formatCurrencyWithMonetarySymbol(payment.value - discountValue)}."

        return instructions
    }

	private String addInterestInstructionsIfExists(String instructions, Payment payment, BoletoInfo providerBoletoInfo, Boolean originalBankSlip) {
		try {
            if (!originalBankSlip && !payment.canApplyCurrentBankSlipFineAndInterest()) return instructions

			InterestConfig interestConfig = InterestConfig.find(payment.provider)

			if (payment.isOverdue() && (interestConfig || providerBoletoInfo?.receiveAfterDueDate != null && providerBoletoInfo?.receiveAfterDueDate == false) && !originalBankSlip) {
				instructions += "\nNão receber após " + new Date().format("dd/MM/yyyy") + "."
			} else {
				PaymentInterestCalculator interestCalculator = new PaymentInterestCalculator(payment, new Date().clearTime())

				NumberFormat formatter = NumberFormat.getNumberInstance()
				formatter.maximumFractionDigits = 2
				formatter.minimumFractionDigits = 2

				String interestInstructions = ""

				if (interestCalculator.fineValue > 0) {
					interestInstructions += "multa de ${'R\$ ' + formatter.format(interestCalculator.fineValue)}"
				}

				if (interestCalculator.monthlyInterestPercentage > 0) {
					if (interestInstructions.length() > 0) interestInstructions += " e "

					interestInstructions += "juros de ${formatter.format(interestCalculator.monthlyInterestPercentage)}% ao mês"
				}

				if (interestInstructions.length() > 0) {
					instructions += "\nApós o vencimento aplicar " + interestInstructions + "."
				}
			}

			return instructions
		} catch (Exception exception) {
            AsaasLogger.error("BoletoService.addInterestInstructionsIfExists >> Erro ao adicionar instruções de juros. [paymentId: ${payment?.id}]", exception)
			return "\nNão receber após o vencimento."
		}
	}

	private String addCancelledInstructionIfDeleted(String instructions, Payment payment) {
		if (payment.deleted) {
			instructions += "\n" + "Cobrança cancelada, não efetue o pagamento deste boleto."
		}

		return instructions
	}

	private void overrideBoletoInfoIfNecessary(Boleto boleto, BoletoBankInfo boletoBankInfo, Payment payment) {
		if (boletoBankInfo.bank.code == SupportedBank.BRADESCO.code()) {
			boleto.addTextosExtras("txtRsNossoNumero", boletoBankInfo.wallet + "/" + NossoNumero.valueOf(Long.valueOf(boleto.titulo.nossoNumero), boletoBankInfo.wallet.toInteger()));
			boleto.addTextosExtras("txtFcNossoNumero", boletoBankInfo.wallet + "/" + NossoNumero.valueOf(Long.valueOf(boleto.titulo.nossoNumero), boletoBankInfo.wallet.toInteger()));
            boleto.addTextosExtras("txtFcCarteira", boletoBankInfo.wallet)
		} else if (boletoBankInfo.bank.code == SupportedBank.SANTANDER.code()) {
			String santanderNossoNumero = new SantanderBoletoBuilder().calculateNossoNumeroDigit(boleto.titulo.nossoNumero)
			boleto.addTextosExtras("txtRsNossoNumero", boleto.titulo.nossoNumero + "-" + santanderNossoNumero);
			boleto.addTextosExtras("txtFcNossoNumero", boleto.titulo.nossoNumero + "-" + santanderNossoNumero);
		} else if (boletoBankInfo.bank.code == SupportedBank.ITAU.code()) {
			String itauNossoNumeroDigit = new ItauBoletoBuilder().calculateNossoNumeroDigit(boleto.titulo.nossoNumero, boletoBankInfo.agency, boletoBankInfo.covenant, boletoBankInfo.wallet)
			boleto.addTextosExtras("txtRsNossoNumero", boletoBankInfo.wallet + "/" + boleto.titulo.nossoNumero + "-" + itauNossoNumeroDigit)
			boleto.addTextosExtras("txtFcNossoNumero", boletoBankInfo.wallet + "/" + boleto.titulo.nossoNumero + "-" + itauNossoNumeroDigit)
		} else if (boletoBankInfo.bank.code == SupportedBank.SAFRA.code()) {
			String agency = StringUtils.leftPad(boletoBankInfo.agency, 4, "0") + boletoBankInfo.agencyDigit
			String account = StringUtils.leftPad(boletoBankInfo.covenant, 8, "0") + boletoBankInfo.covenantDigit
			boleto.addTextosExtras("txtRsAgenciaCodigoCedente", agency + " / " + account)
			boleto.addTextosExtras("txtFcAgenciaCodigoCedente", agency + " / " + account)
		} else if (boletoBankInfo.bank.code == SupportedBank.BANCO_DO_BRASIL.code()) {
			String agencyAndAccount = boletoBankInfo.agency + "-" + boletoBankInfo.agencyDigit + " / " + boletoBankInfo.account + "-" + boletoBankInfo.accountDigit
			boleto.addTextosExtras("txtRsAgenciaCodigoCedente", agencyAndAccount)
			boleto.addTextosExtras("txtFcAgenciaCodigoCedente", agencyAndAccount)
			String nossoNumeroBB = boletoBankInfo.covenant + StringUtils.leftPad(payment.getCurrentBankSlipNossoNumero(), boletoBankInfo.bank.nossoNumeroDigitsCount, "0")
			boleto.addTextosExtras("txtRsNossoNumero", nossoNumeroBB)
			boleto.addTextosExtras("txtFcNossoNumero", nossoNumeroBB)
		}
	}

	public String getLinhaDigitavel(Payment payment) {
		if (!payment) return null
		if (boletoDisabledForCustomer(payment.provider)) return null

		if (bankSlipService.isSupportedBoletoBank(payment.boletoBank)) {
			return bankSlipService.getLinhaDigitavel(payment)
		}

		Boleto boleto = buildBoleto(payment, false);

		String linhaDigitavel = ""
		linhaDigitavel += boleto.getLinhaDigitavel().innerCampo1.getValue().value.toString()
		linhaDigitavel += boleto.getLinhaDigitavel().innerCampo2.getValue().value.toString()
		linhaDigitavel += boleto.getLinhaDigitavel().innerCampo3.getValue().value.toString()
		linhaDigitavel += boleto.getLinhaDigitavel().campo4.getValue().value.toString()
		linhaDigitavel += boleto.getLinhaDigitavel().innerCampo5.getValue().value.toString()

		return linhaDigitavel.replaceAll(/\D/, "");;
	}

    public String getBarCode(Payment payment) {
        if (boletoDisabledForCustomer(payment.provider)) return null

        BoletoBank boletoBank = BoletoBank.buildBoletoBankFromPayment(payment)
        BoletoBuilder boletoBuilder = BankSlipVO.getBoletoBuilder(boletoBank)

        return boletoBuilder.buildBarCode(payment, boletoBank)
    }

	public List<Boleto> buildBoletoListByExternalToken(List<String> listOfExternalToken) {
		List<Boleto> listOfBoleto = []

		for(String externalToken : listOfExternalToken) {
			Payment payment = Payment.findWhere(externalToken: externalToken)
			listOfBoleto.add(buildBoleto(payment, false))
		}

		return listOfBoleto
	}

	public List<Boleto> buildBoletoList(Collection<Payment> listOfPayments) {
		List<Boleto> listOfBoleto = []

		for(Payment payment : listOfPayments) {
			listOfBoleto += buildBoleto(payment, false)
		}

		return listOfBoleto
	}

	private String buildAddressStringFromProvider(Customer provider) {
		StringBuilder address = new StringBuilder()

		address.append(provider.address)
		if (provider.addressNumber) address.append(", ${provider.addressNumber}")
		if (provider.province) address.append(", ${provider.province}")
		if (provider.city) address.append(" - ${provider.city.name}/${provider.city.state}")

		return address.toString()
	}

	private InputStream getPaymentBookTemplate() {
		return this.class.classLoader.getResourceAsStream(AsaasApplicationHolder.config.asaas.paymentBook.defaultTemplate.path)
	}

	public Boolean isAdminAndOriginalBankSlip(Boolean originalBankSlip) {
		return SpringSecurityUtils.ifAllGranted('ROLE_SYSADMIN') && originalBankSlip
	}

    private byte[] buildBoletoViewer(Customer customer, List<Payment> paymentList, Boolean originalBankSlip, Map params) {
        try {
            return getBoletoPdfBytes(paymentList, originalBankSlip, bankSlipService.getBoletoTemplate(customer, false), params)
        } catch (IllegalStateException illegalStateException) {
            AsaasLogger.error("BoletoService >> Erro ao gerar boleto para o cliente [${customer?.id}]", illegalStateException)

            return getBoletoPdfBytes(paymentList, originalBankSlip, bankSlipService.getBoletoTemplateWithoutLogo(customer), params)
        }
    }

    private void setTxtRsInfoCedenteLinha(Boleto boleto, BoletoInfo providerBoletoInfo, Customer customer, Map options) {
        Integer counter = 1

		if (options.usePreviousFieldOrder) {
			boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", customer.getProviderName())
			counter++

			boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", "${customer.personType == PersonType.JURIDICA ? 'CNPJ:' : 'CPF:'} ${CpfCnpjUtils.formatCpfCnpj(customer.cpfCnpj)}")
			counter++

			boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", buildAddressStringFromProvider(customer))
			counter++

			if (customer.postalCode) {
				boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", "CEP: ${customer.postalCode}")
				counter++
			}

			if (PhoneNumberUtils.formatPhoneNumber(customer.phone)) {
				boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", "Telefone: ${PhoneNumberUtils.formatPhoneNumber(customer.phone)}")
				counter++
			}

			if (customer.email) {
				boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", "E-mail: ${customer.email}")
				counter++
			}

			return
		}


		boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", customer.tradingName ?: providerBoletoInfo?.transferor ?: customer.getProviderName())
		counter++

        String cpfCnpj = providerBoletoInfo?.currentCpfCnpj?.trim() ?: customer.cpfCnpj.trim()
		if (CpfCnpjUtils.formatCpfCnpj(cpfCnpj)) {
			boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", "${CpfCnpjUtils.formatCpfCnpj(cpfCnpj)}")
			counter++
		}

        if (CustomerInvoiceConfig.customerInfoIsToBeShowed(customer, InvoiceCustomerInfo.SITE) && UriUtils.isValidUrl(customer.site)) {
            boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", CustomerInfoFormatter.formatSiteWithHttp(customer.site))
            counter++
        }

        String formattedEmail = CustomerInfoFormatter.formatEmail(customer)
		if (CustomerInvoiceConfig.customerInfoIsToBeShowed(customer, InvoiceCustomerInfo.EMAIL) && formattedEmail && formattedEmail != customer.getProviderName()) {
            boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", formattedEmail)
            counter++
		}

		if (CustomerInvoiceConfig.customerInfoIsToBeShowed(customer, InvoiceCustomerInfo.PHONE_NUMBERS) && CustomerInfoFormatter.formatPhoneNumbers(customer)) {
			boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", CustomerInfoFormatter.formatPhoneNumbers(customer))
			counter++
		}

		if (CustomerInvoiceConfig.customerInfoIsToBeShowed(customer, InvoiceCustomerInfo.ADDRESS) && CustomerInfoFormatter.formatAddress(customer)) {
			boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", CustomerInfoFormatter.formatAddress(customer))
			counter++
		}

		if (CustomerInvoiceConfig.customerInfoIsToBeShowed(customer, InvoiceCustomerInfo.POSTAL_CODE) && CustomerInfoFormatter.formatPostalCode(customer)) {
			boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", CustomerInfoFormatter.formatPostalCode(customer))
			counter++
		}

		if (CustomerInvoiceConfig.customerInfoIsToBeShowed(customer, InvoiceCustomerInfo.CITY) && CustomerInfoFormatter.formatCity(customer)) {
			boleto.addTextosExtras("txtRsInfoCedenteLinha${counter.toString()}", CustomerInfoFormatter.formatCity(customer))
			counter++
		}
    }

    private void buildStockInfoTemplate(Payment payment, Boleto boleto, Boolean originalBankSlip) {
        AsaasFile logoFile = CustomerInvoiceConfig.findCurrent(payment.provider)?.logoFile

        if (logoFile) {
            Image imageLogo = ImageIO.read(logoFile.getFileInMemory())
            boleto.addImagensExtras('txtRsLogoCedente', imageLogo)
        }

        Titulo titulo = boleto.getTitulo()

        if (originalBankSlip || payment.canApplyCurrentBankSlipInstructions()) {
            boleto.addTextosExtras("txtRsInstrucaoAoSacado1", "Boleto da parcela de ${CustomDateUtils.fromDate(titulo.getDataDoVencimento(), "MM/yyyy")}")
            boleto.addTextosExtras("txtRsInstrucaoAoSacado6", "Valor Total: R\$ ${titulo.getValor()}")

            setInstrucao(boleto, payment.description, 1, 5, true)
        }

        Cedente cedente = titulo.getCedente()
        Endereco cedenteEndereco = buildEndereco(payment.provider)
        Sacado sacado = titulo.getSacado()

        String cedenteInfo2 = ""
        if (cedenteEndereco.getLogradouro()) cedenteInfo2 += "Logradouro: " + cedenteEndereco.getLogradouro() + " "
        if (cedenteEndereco.getNumero()) cedenteInfo2 += "Nº: " + cedenteEndereco.getNumero() + " "
        if (cedenteEndereco.getBairro()) cedenteInfo2 += "Bairro: " + cedenteEndereco.getBairro()

        String cedenteInfo3 = ""
        if (cedenteEndereco.getCEP()) cedenteInfo3 += "CEP: " + FormUtils.formatPostalCode(cedenteEndereco.getCEP().getCep()) + " "
        if (cedenteEndereco.getLocalidade()) cedenteInfo3 += cedenteEndereco.getLocalidade() + " "
        if (cedenteEndereco.getUF()) cedenteInfo3 += "- " + cedenteEndereco.getUF()

        String sacadoL1 = ""
        String sacadoL2 = ""
        if (sacado.getEnderecos()) {
            Endereco sacadoEndereco = sacado.getEnderecos()[0]

            if (sacadoEndereco.getLogradouro()) sacadoL1 += sacadoEndereco.getLogradouro() + " "
            if (sacadoEndereco.getNumero()) sacadoL1 += "Nº: " + sacadoEndereco.getNumero() + " "
            if (sacadoEndereco.getBairro()) sacadoL1 += "Bairro: " + sacadoEndereco.getBairro()

            if (sacadoEndereco.getCEP()) sacadoL2 += "CEP: " + FormUtils.formatPostalCode(sacadoEndereco.getCEP().getCep()) + " "
            if (sacadoEndereco.getLocalidade()) sacadoL2 += sacadoEndereco.getLocalidade() + " "
            if (sacadoEndereco.getUF()) sacadoL2 += "- " + sacadoEndereco.getUF()
        }

        boleto.addTextosExtras("txtRsSacadoL1", sacadoL1)
        boleto.addTextosExtras("txtRsSacadoL2", sacadoL2)

        boleto.addTextosExtras("txtRsCedente1", cedente.getNome())
        boleto.addTextosExtras("txtRsCedente2", cedenteInfo2)
        boleto.addTextosExtras("txtRsCedente3", cedenteInfo3)

        boleto.addTextosExtras("txtFcCedente2", cedenteInfo2)
        boleto.addTextosExtras("txtFcCedente3", cedenteInfo3)
    }

    private void setInstrucao(Boleto boleto, String instructions, Integer instructionStartPosition, Integer instructionBreakPosition, Boolean isInstrucaoAsSacado) {
        String[] linhas = (instructions).split("\\n");
        Integer instructionPosition = instructionStartPosition
        for (int i = 0; i < linhas.length; i++) {
            String instruction = linhas[i].trim()
            if (StringUtils.isNotEmpty(instruction)) {
                instructionPosition = instructionPosition + 1

                if (instructionPosition > instructionBreakPosition) break

                if (isInstrucaoAsSacado) {
                    boleto.addTextosExtras("txtRsInstrucaoAoSacado${instructionPosition}", instruction)
                } else {
                    boleto."instrucao${instructionPosition}" = instruction
                }
            }
        }
    }

	private void setTxtRsLogoCedente(Boleto boleto, Customer customer, byte[] fileBytes, String logoName) {
		try {
			ByteArrayInputStream fileInputStream = new ByteArrayInputStream(fileBytes)

			Image imageLogo = ImageIO.read(fileInputStream)
			boleto.addImagensExtras('txtRsLogoCedente', imageLogo)

			fileInputStream.close()
		} catch (Exception e) {
			AsaasLogger.error("Logo file not found: images/logo/${logoName}, Customer: ${customer.id}", e)
		}
	}

    private byte[] getBoletoPdfBytes(List<Payment> paymentList, Boolean originalBankSlip, InputStream templateStream, Map params) {
        if (paymentList.size() > 1) {
            List<Boleto> boletoList = buildBoletoList(paymentList)
            return BoletoViewer.groupInOnePdfWithTemplate(boletoList, templateStream)
        } else {
            Boleto boleto = buildBoleto(paymentList[0], originalBankSlip)
            BoletoViewer boletoViewer = new BoletoViewer(boleto, templateStream)

            if (params?.pdfTitulo) boletoViewer.setPdfTitulo(params.pdfTitulo)

            return boletoViewer.getPdfAsByteArray()
        }
    }

    private byte[] resizeLogoPictureAndBuildBackgroundColorPreview(Map params, AsaasFile asaasFile) {
        Map resizeWithNewColorsParams = [:]
        resizeWithNewColorsParams.extentWidth = 560
        resizeWithNewColorsParams.extentHeight = 477

        if (params.customerInvoiceConfig?.primaryColor) {
            resizeWithNewColorsParams.backgroundColorToJpg = params.customerInvoiceConfig.primaryColor
            resizeWithNewColorsParams.backgroundColorToResize = params.customerInvoiceConfig.primaryColor
        }

        File resizedFile = pictureService.resizeMaintainingAspectRatio(asaasFile, CustomerInvoiceConfig.BANK_SLIP_LOGO_MAXIMUM_WIDTH, CustomerInvoiceConfig.BANK_SLIP_LOGO_MAXIMUM_HEIGHT, resizeWithNewColorsParams)

        return resizedFile.getBytes()
    }
}

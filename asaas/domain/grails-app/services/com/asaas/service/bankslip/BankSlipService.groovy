package com.asaas.service.bankslip

import com.asaas.applicationconfig.AsaasApplicationHolder
import com.asaas.bankslip.BankSlipVO
import com.asaas.boleto.BoletoBankInfo
import com.asaas.boleto.BoletoBuilder
import com.asaas.boleto.itau.ItauBoletoBuilder
import com.asaas.boleto.safra.SafraBoletoBuilder
import com.asaas.boleto.santander.SantanderBoletoBuilder
import com.asaas.boleto.sicredi.SicrediBoletoBuilder
import com.asaas.boleto.smartbank.SmartBankBoletoBuilder
import com.asaas.customer.CustomerParameterName
import com.asaas.domain.boleto.BoletoBank
import com.asaas.domain.customer.Customer
import com.asaas.domain.customer.CustomerBoletoConfig
import com.asaas.domain.customer.CustomerParameter
import com.asaas.domain.customer.CustomerPixConfig
import com.asaas.domain.payment.Payment
import com.asaas.environment.AsaasEnvironment
import com.asaas.exception.BusinessException
import com.asaas.log.AsaasLogger
import com.asaas.payment.PaymentBuilder
import com.asaas.transferbatchfile.SupportedBank
import com.asaas.utils.CustomDateUtils
import com.asaas.utils.Utils
import com.lowagie.text.Document
import com.lowagie.text.Image
import com.lowagie.text.Rectangle
import com.lowagie.text.pdf.AcroFields
import com.lowagie.text.pdf.BarcodeInter25
import com.lowagie.text.pdf.PdfBoolean
import com.lowagie.text.pdf.PdfName
import com.lowagie.text.pdf.PdfReader
import com.lowagie.text.pdf.PdfSmartCopy
import com.lowagie.text.pdf.PdfStamper
import grails.plugin.springsecurity.SpringSecurityUtils
import grails.transaction.Transactional
import org.apache.commons.lang.StringUtils
import sun.awt.image.ToolkitImage

import javax.imageio.ImageIO
import java.awt.Color
import java.awt.image.BufferedImage

@Transactional
class BankSlipService {

    private static final String PAYMENT_BOOK_TEMPLATE = "paymentBook"

    def grailsApplication
    def pixQrCodeService
    def tesseraManagerService

    public Boolean isSupportedBoletoBank(BoletoBank boletoBank) {
        List<Long> supportedBoletoBankIdList = [
            Payment.SMARTBANK_BOLETO_BANK_ID,
            Payment.BB_BOLETO_BANK_ID,
            Payment.BRADESCO_ONLINE_BOLETO_BANK_ID,
            Payment.SANTANDER_ONLINE_BOLETO_BANK_ID,
            Payment.SANTANDER_BOLETO_BANK_ID,
            Payment.SICREDI_BOLETO_BANK_ID,
            Payment.SAFRA_BOLETO_BANK_ID,
            Payment.ASAAS_ONLINE_BOLETO_BANK_ID
        ]

        return supportedBoletoBankIdList.contains(boletoBank?.id)
    }

    public String getLinhaDigitavel(Payment payment) {
        BoletoBuilder boletoBuilder = BankSlipVO.getBoletoBuilder(payment.boletoBank)
        String linhaDigitavel = boletoBuilder.buildLinhaDigitavel(payment, payment.boletoBank)
        return linhaDigitavel.replaceAll(/\D/, "")
    }

    public byte[] buildPdf(Payment payment, Boolean originalBankSlip, Boolean isBatch) {
        try {
            return buildPdfBytes(payment, originalBankSlip, null, false, isBatch)
        } catch (IllegalStateException illegalStateException) {
            return buildPdfBytes(payment, originalBankSlip, null, true, isBatch)
        }
    }

    public Map getPixInfo(Payment payment) {
        Map pixInfo

        if (payment.deleted) return null
        if (!CustomerPixConfig.customerCanReceivePaymentWithPixOwnKey(payment.provider)) return null
        if (!pixQrCodeService.validateCanBeGenerated(payment).isValid()) return null
        if (CustomerParameter.getValue(payment.provider, CustomerParameterName.DISABLE_QR_CODE_PIX_ON_BANK_SLIP).asBoolean()) return null

        Long paymentId = payment.id

        Utils.withNewTransactionAndRollbackOnError({
            Payment currentPayment = Payment.get(paymentId)
            if (currentPayment) pixInfo = pixQrCodeService.createDynamicQrCode(currentPayment)
        }, [
            ignoreStackTrace: true,
            onError: { AsaasLogger.warn("BankSlipService.buildPdfBytes >> Erro ao gerar QR Code Pix para a cobrança ID: ${ paymentId }") }
            ])

        return pixInfo
    }

    public byte[] buildPdfBytes(Payment payment, Boolean originalBankSlip, Boolean paymentBook, Boolean templateWithoutLogo, Boolean isBatch) {
        if (payment.provider.boletoIsDisabled() && !isAdminAndOriginalBankSlip(originalBankSlip)) throw new BusinessException("Geração de boletos desabilitada.")

        validateRegisteredPayment(payment, originalBankSlip)
        updatePaymentNossoNumeroIfNecessary(payment)

        BankSlipVO bankSlipVO = BankSlipVO.build(payment, originalBankSlip)

        if (shouldBuildPdfUsingTessera(payment.provider, paymentBook, isBatch, bankSlipVO.customTemplate)) {
            bankSlipVO.barCodeEncodedImage = buildBarCodeImageBase64(bankSlipVO.barCode)
            return tesseraManagerService.getBankSlipPdf(bankSlipVO, payment.provider)
        }

        Map imageFieldsMap = buildImageFieldsMap(bankSlipVO)

        Map textFieldsMap = buildTextFieldsMap(bankSlipVO)

        Map documentInfoMap = buildDocumentInfoMap(payment)

        Boolean customerHasPix = bankSlipVO.pixPayload.asBoolean()

        InputStream template

        if (paymentBook) {
            if (customerHasPix) {
                template = this.class.classLoader.getResourceAsStream(AsaasApplicationHolder.config.asaas.paymentBook.defaultTemplate.withPixPath)
            } else {
                template = this.class.classLoader.getResourceAsStream(AsaasApplicationHolder.config.asaas.paymentBook.defaultTemplate.path)
            }
        } else if (templateWithoutLogo) {
            template = getBoletoTemplateWithoutLogo(payment.provider)
        } else {
            template = getBoletoTemplate(payment.provider, customerHasPix)
        }

        PdfReader pdfReader = new PdfReader(template)

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()

        PdfStamper stamper = new PdfStamper(pdfReader, outputStream)
        stamper.addViewerPreference(PdfName.DISPLAYDOCTITLE, PdfBoolean.PDFTRUE)
        stamper.setMoreInfo(documentInfoMap)

        AcroFields form = stamper.getAcroFields()

        textFieldsMap.each { textFieldName, value ->
            form.setField(textFieldName, value)
        }

        imageFieldsMap.each { fieldName, image ->
            float[] imageFieldPositions

            if (fieldName != null) {
                imageFieldPositions = form.getFieldPositions(fieldName)

                if (imageFieldPositions != null) {
                    Image pdfImage = Image.getInstance(image, null)

                    Rectangle rect = new Rectangle(imageFieldPositions[1], imageFieldPositions[2], imageFieldPositions[3], imageFieldPositions[4])
                    Integer page = (int) imageFieldPositions[0]

                    pdfImage.scaleAbsolute(rect.getWidth(), rect.getHeight())

                    float posX = rect.llx + (rect.getWidth() - pdfImage.getScaledWidth()) / 2
                    float posY = rect.lly + (rect.getHeight() - pdfImage.getScaledHeight()) / 2

                    pdfImage.setAbsolutePosition(posX, posY)

                    stamper.getOverContent(page).addImage(pdfImage)
                }
            }
        }

        stamper.setFullCompression()
        stamper.setFreeTextFlattening(true)
        stamper.setFormFlattening(true)

        pdfReader.removeFields()
        pdfReader.consolidateNamedDestinations()
        pdfReader.eliminateSharedStreams()

        outputStream.flush()

        pdfReader.close()
        stamper.close()

        outputStream.close()

        return outputStream.toByteArray()
    }

    public String buildBarCodeImageBase64(String barCode) {
        ToolkitImage barCodeImage = buildBarCodePdfImage(barCode)

        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream()
        BufferedImage bufferedImage = new BufferedImage(barCodeImage.getWidth(null), barCodeImage.getHeight(null), BufferedImage.TYPE_3BYTE_BGR)
        bufferedImage.getGraphics().drawImage(barCodeImage, 0, 0, null)
        ImageIO.write(bufferedImage, "png", byteArrayOutputStream)
        byte[] imageBytes = byteArrayOutputStream.toByteArray()

        return imageBytes.encodeBase64()
    }

    public byte[] buildPdfListBytes(List<Payment> paymentList, Boolean originalBankSlip) {
        List<byte[]> pdfListBytes = []

        for (Payment payment in paymentList) {
            pdfListBytes.add(buildPdf(payment, originalBankSlip, true))
        }

        ByteArrayOutputStream outputStream = new ByteArrayOutputStream()
        Document document = new Document()
        PdfSmartCopy pdfSmartCopy = new PdfSmartCopy(document, outputStream)
        document.open()

        for (byte[] pdfBytes in pdfListBytes.reverse()) {
            PdfReader pdfReader = new PdfReader(pdfBytes)
            pdfSmartCopy.addPage(pdfSmartCopy.getImportedPage(pdfReader, 1))
            pdfReader.close()
        }

        document.close()

        outputStream.flush()
        outputStream.close()

        return outputStream.toByteArray()
    }

    public byte[] buildPaymentBookBytes(Customer customer, List<Payment> paymentList, Boolean originalBankSlip) {
        return tesseraManagerService.getPaymentBookPdf(generateBankSlipVOList(customer, paymentList, originalBankSlip), PAYMENT_BOOK_TEMPLATE, customer)
    }

    public Map buildNossoNumeroAndNossoNumeroDigitMap(BoletoBankInfo boletoBankInfo, Payment payment) {
        Map response = [:]
        response.nossoNumero = StringUtils.leftPad(payment.getCurrentBankSlipNossoNumero(), boletoBankInfo.bank.nossoNumeroDigitsCount, "0")

        switch (boletoBankInfo.bank.code) {
            case SupportedBank.SMARTBANK.code():
                response.nossoNumero = new SmartBankBoletoBuilder().buildNossoNumero(payment)
                break

            case SupportedBank.SANTANDER.code():
                response.nossoNumero = StringUtils.leftPad(payment.getCurrentBankSlipNossoNumero(), boletoBankInfo.bank.nossoNumeroDigitsCount - 1, "0")
                response.nossoNumeroDigit = new SantanderBoletoBuilder().calculateNossoNumeroDigit(response.nossoNumero)
                break

            case SupportedBank.SICREDI.code():
                response.nossoNumero = payment.getCurrentBankSlipNossoNumero()
                response.nossoNumeroDigit = new SicrediBoletoBuilder().calculateNossoNumeroDigit(response.nossoNumero)
                break

            case SupportedBank.ITAU.code():
                response.nossoNumeroDigit = new ItauBoletoBuilder().calculateNossoNumeroDigit(response.nossoNumero, boletoBankInfo.agency, boletoBankInfo.covenant, boletoBankInfo.wallet)
                break

            case SupportedBank.SAFRA.code():
                String safraNossoNumeroDigit = new SafraBoletoBuilder().calculateNossoNumeroDigit(payment.getCurrentBankSlipNossoNumero())
                response.nossoNumero = StringUtils.leftPad(payment.getCurrentBankSlipNossoNumero() + safraNossoNumeroDigit, boletoBankInfo.bank.nossoNumeroDigitsCount, "0")
                break
        }

        return response
    }

    public Boolean canUseTemplateWithHeader(Customer customer) {
        if (customer) {
            CustomerBoletoConfig config = CustomerBoletoConfig.getConfigForCustomer(customer)
            if (config && !config.showCustomerInfo) return false

            if (customer.getProviderName() && customer.cpfCnpj) return true
        }

        return false
    }

    public InputStream getBoletoTemplate(Customer customer, Boolean customerCanUsePix) {
        if (AsaasEnvironment.isSandbox()) {
            return this.class.classLoader.getResourceAsStream("boletoTemplates/BoletoTeste.pdf")
        }

        if (!customer) {
            return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTemplate.path)
        }

        String customTemplate = CustomerBoletoConfig.getConfigForCustomer(customer)?.customTemplate
        if (customTemplate) return this.class.classLoader.getResourceAsStream("boletoTemplates/custom/${customTemplate}.pdf")

        if (!canUseTemplateWithHeader(customer)) {
            return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTemplate.path)
        }

        if (customer.getInvoiceConfig()?.canUseBankSlipLogoPicture()) {
            if (customerCanUsePix) return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTemplate.pixWithLogoPath)
            return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTemplate.withLogoPath)
        }

        if (customerCanUsePix) return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTemplate.pixWithoutLogoPath)

        return this.class.classLoader.getResourceAsStream(grailsApplication.config.asaas.boleto.defaultTemplate.withoutLogoPath)
    }

    public InputStream getBoletoTemplateWithoutLogo(Customer customer) {
        String templatePath

        if (canUseTemplateWithHeader(customer)) {
            templatePath = grailsApplication.config.asaas.boleto.defaultTemplate.withoutLogoPath
        } else {
            templatePath = grailsApplication.config.asaas.boleto.defaultTemplate.path
        }

        return this.class.classLoader.getResourceAsStream(templatePath)
    }

    public byte[] buildPdfBatchBytes(Customer customer, List<Payment> paymentList, Boolean originalBankSlip) {
        List<BankSlipVO> bankSlipVOList = generateBankSlipVOList(customer, paymentList, originalBankSlip)
        return tesseraManagerService.getBankSlipPdfBatch(bankSlipVOList, customer)
    }

    public Boolean shouldBuildPdfListUsingTessera() {
        return true
    }

    private List<BankSlipVO> generateBankSlipVOList(Customer customer, List<Payment> paymentList, Boolean originalBankSlip) {
        if (customer.boletoIsDisabled() && !isAdminAndOriginalBankSlip(originalBankSlip)) throw new BusinessException("Geração de boletos ou carnês desabilitada.")
        List<BankSlipVO> bankSlipVOList = []

        for (Payment payment : paymentList) {
            validateRegisteredPayment(payment, originalBankSlip)
            updatePaymentNossoNumeroIfNecessary(payment)

            BankSlipVO bankSlipVO = BankSlipVO.build(payment, originalBankSlip)
            bankSlipVO.barCodeEncodedImage = buildBarCodeImageBase64(bankSlipVO.barCode)

            bankSlipVOList.add(bankSlipVO)
        }

        return bankSlipVOList
    }

    private updatePaymentNossoNumeroIfNecessary(Payment payment) {
        if (!payment.getCurrentBankSlipNossoNumero()) {
            payment.nossoNumero = PaymentBuilder.buildNossoNumero(payment)
            payment.save(failOnError: true)
        }
    }

    private validateRegisteredPayment(Payment payment, Boolean originalBankSlip) {
        Boolean buildOriginalBankSlip = (originalBankSlip || payment.deleted || (payment.boletoBank == null && payment.isOverdue()))
        if (!buildOriginalBankSlip && !payment.isPaid() && !payment.boletoBank) throw new BusinessException("Não é possível gerar o boleto ou carnê desta cobrança pois ela não está registrada.")
    }

    private Map buildDocumentInfoMap(Payment payment) {
        return [
                "Title": "Boleto ${payment.customerAccount.name}".toString(),
                "Author": grailsApplication.config.asaas.company.name,
                "Subject": "Boleto",
                "Keywords": "Boleto, Asaas",
                "Creator": grailsApplication.config.asaas.company.name,
                "CreationDate": CustomDateUtils.fromDate(new Date()),
                "ModDate": "",
                "Producer": grailsApplication.config.asaas.company.name
        ]
    }

    private Map buildImageFieldsMap(BankSlipVO bankSlipVO) {
         Map imageFieldsMap = [:]

        imageFieldsMap.imgPix = bankSlipVO.pixQrCode

         if (bankSlipVO.bankLogo) {
             imageFieldsMap.txtRsLogoBanco = bankSlipVO.bankLogo
             imageFieldsMap.txtFcLogoBanco = bankSlipVO.bankLogo
         }

         if (bankSlipVO.customerLogo) {
             imageFieldsMap.txtRsLogoCedente = bankSlipVO.customerLogo
         }

         imageFieldsMap.txtFcCodigoBarra = buildBarCodePdfImage(bankSlipVO.barCode)

         return imageFieldsMap
    }

    private Map buildTextFieldsMap(BankSlipVO bankSlipVO) {
        Map textFieldsMap = [:]

        textFieldsMap.txtRsCodBanco = bankSlipVO.bankCode
        textFieldsMap.txtFcCodBanco = bankSlipVO.bankCode

        textFieldsMap.txtRsLinhaDigitavel = bankSlipVO.linhaDigitavel
        textFieldsMap.txtFcLinhaDigitavel = bankSlipVO.linhaDigitavel

        textFieldsMap.txtRsCedente = bankSlipVO.beneficiary
        textFieldsMap.txtFcCedente = bankSlipVO.beneficiary

        textFieldsMap.txtRsAgenciaCodigoCedente = bankSlipVO.agencyAndBeneficiaryCode
        textFieldsMap.txtFcAgenciaCodigoCedente = bankSlipVO.agencyAndBeneficiaryCode

        textFieldsMap.txtRsEspecie = bankSlipVO.money
        textFieldsMap.txtFcEspecie = bankSlipVO.money

        textFieldsMap.txtRsQuantidade = bankSlipVO.quantity
        textFieldsMap.txtFcQuantidade = bankSlipVO.quantity

        textFieldsMap.txtRsNossoNumero = bankSlipVO.nossoNumero
        textFieldsMap.txtFcNossoNumero = bankSlipVO.nossoNumero

        textFieldsMap.txtRsNumeroDocumento = bankSlipVO.documentNumber
        textFieldsMap.txtFcNumeroDocumento = bankSlipVO.documentNumber

        textFieldsMap.txtRsCpfCnpj = bankSlipVO.cpfCnpj

        textFieldsMap.txtRsDataVencimento = bankSlipVO.dueDate
        textFieldsMap.txtFcDataVencimento = bankSlipVO.dueDate

        textFieldsMap.txtRsValorDocumento = bankSlipVO.documentValue
        textFieldsMap.txtFcValorDocumento = bankSlipVO.documentValue

        textFieldsMap.txtRsDescontoAbatimento = bankSlipVO.discount
        textFieldsMap.txtFcDescontoAbatimento = bankSlipVO.discount

        textFieldsMap.txtRsOutraDeducao = bankSlipVO.deductions
        textFieldsMap.txtFcOutraDeducao = bankSlipVO.deductions

        textFieldsMap.txtRsMoraMulta = bankSlipVO.fine
        textFieldsMap.txtFcMoraMulta = bankSlipVO.fine

        textFieldsMap.txtRsOutroAcrescimo = bankSlipVO.additions
        textFieldsMap.txtFcOutroAcrescimo = bankSlipVO.additions

        textFieldsMap.txtRsValorCobrado = bankSlipVO.amountCharged
        textFieldsMap.txtFcValorCobrado = bankSlipVO.amountCharged

        textFieldsMap.txtRsSacado = bankSlipVO.payer

        textFieldsMap.txtRsInstrucaoAoSacado = bankSlipVO.payerInstructions

        textFieldsMap.txtFcLocalPagamento = bankSlipVO.paymentPlace

        textFieldsMap.txtFcDataDocumento = bankSlipVO.documentDate

        textFieldsMap.txtFcEspecieDocumento = bankSlipVO.documentType

        textFieldsMap.txtFcAceite = bankSlipVO.acceptance

        textFieldsMap.txtFcDataProcessamento = bankSlipVO.processingDate

        textFieldsMap.txtFcUsoBanco = bankSlipVO.bankUse

        textFieldsMap.txtFcCarteira = bankSlipVO.wallet

        textFieldsMap.txtFcValor = bankSlipVO.value

        textFieldsMap.txtFcInstrucaoAoCaixa1 = bankSlipVO.instructionsLine1 ?: ""
        textFieldsMap.txtFcInstrucaoAoCaixa2 = bankSlipVO.instructionsLine2 ?: ""
        textFieldsMap.txtFcInstrucaoAoCaixa3 = bankSlipVO.instructionsLine3 ?: ""
        textFieldsMap.txtFcInstrucaoAoCaixa4 = bankSlipVO.instructionsLine4 ?: ""
        textFieldsMap.txtFcInstrucaoAoCaixa5 = bankSlipVO.instructionsLine5 ?: ""
        textFieldsMap.txtFcInstrucaoAoCaixa6 = bankSlipVO.instructionsLine6 ?: ""
        textFieldsMap.txtFcInstrucaoAoCaixa7 = bankSlipVO.instructionsLine7 ?: ""
        textFieldsMap.txtFcInstrucaoAoCaixa8 = bankSlipVO.instructionsLine8 ?: ""

        textFieldsMap.txtFcInstrucaoAoCaixaLinhaCompleta = textFieldsMap.txtFcInstrucaoAoCaixa1 + textFieldsMap.txtFcInstrucaoAoCaixa2 + textFieldsMap.txtFcInstrucaoAoCaixa3 + textFieldsMap.txtFcInstrucaoAoCaixa4 + textFieldsMap.txtFcInstrucaoAoCaixa5 + textFieldsMap.txtFcInstrucaoAoCaixa6

        textFieldsMap.txtFcSacadoL1 = bankSlipVO.payerLine1
        textFieldsMap.txtFcSacadoL2 = bankSlipVO.payerLine2
        textFieldsMap.txtFcSacadoL3 = bankSlipVO.payerLine3

        textFieldsMap.txtRsInfoCedenteLinha1 = bankSlipVO.customerInfoLine1
        textFieldsMap.txtRsInfoCedenteLinha2 = bankSlipVO.customerInfoLine2
        textFieldsMap.txtRsInfoCedenteLinha3 = bankSlipVO.customerInfoLine3
        textFieldsMap.txtRsInfoCedenteLinha4 = bankSlipVO.customerInfoLine4
        textFieldsMap.txtRsInfoCedenteLinha5 = bankSlipVO.customerInfoLine5
        textFieldsMap.txtRsInfoCedenteLinha6 = bankSlipVO.customerInfoLine6
        textFieldsMap.txtRsInfoCedenteLinha7 = bankSlipVO.customerInfoLine7
        textFieldsMap.txtRsInfoCedenteLinha8 = bankSlipVO.customerInfoLine8

        textFieldsMap.txtRsInfoCedenteLinhaCompleta = textFieldsMap.txtRsInfoCedenteLinha3 + textFieldsMap.txtRsInfoCedenteLinha4 + textFieldsMap.txtRsInfoCedenteLinha5 + textFieldsMap.txtRsInfoCedenteLinha6 + textFieldsMap.txtRsInfoCedenteLinha7 + textFieldsMap.txtRsInfoCedenteLinha8
        textFieldsMap.txtRsNumeroParcela = bankSlipVO.installmentNumber

        // Fields for customized templates:

        if (bankSlipVO.payerInstructionsLine1) textFieldsMap.txtRsInstrucaoAoSacado1 = bankSlipVO.payerInstructionsLine1
        if (bankSlipVO.payerInstructionsLine2) textFieldsMap.txtRsInstrucaoAoSacado2 = bankSlipVO.payerInstructionsLine2
        if (bankSlipVO.payerInstructionsLine3) textFieldsMap.txtRsInstrucaoAoSacado3 = bankSlipVO.payerInstructionsLine3
        if (bankSlipVO.payerInstructionsLine4) textFieldsMap.txtRsInstrucaoAoSacado4 = bankSlipVO.payerInstructionsLine4
        if (bankSlipVO.payerInstructionsLine5) textFieldsMap.txtRsInstrucaoAoSacado5 = bankSlipVO.payerInstructionsLine5
        if (bankSlipVO.payerInstructionsLine6) textFieldsMap.txtRsInstrucaoAoSacado6 = bankSlipVO.payerInstructionsLine6

        if (bankSlipVO.customDocumentNumber) textFieldsMap.txtRsNumeroDoc = bankSlipVO.customDocumentNumber

        if (bankSlipVO.dischargeCode) textFieldsMap.txtRsCodigoBaixa = bankSlipVO.dischargeCode

        if (bankSlipVO.payerInfoLine1) {
            textFieldsMap.txtFcSacadoTopoLinha1 = bankSlipVO.payerInfoLine1
            textFieldsMap.txtFcSacadoL1 = bankSlipVO.payerInfoLine1
        }

        if (bankSlipVO.payerInfoLine2) {
            textFieldsMap.txtFcSacadoTopoLinha2 = bankSlipVO.payerInfoLine2
            textFieldsMap.txtFcSacadoL2 = bankSlipVO.payerInfoLine2
        }

        if (bankSlipVO.payerInfoLine3) {
            textFieldsMap.txtFcSacadoTopoLinha3 = bankSlipVO.payerInfoLine3
            textFieldsMap.txtFcSacadoL3 = bankSlipVO.payerInfoLine3
        }

        if (bankSlipVO.payerCustomInfoLine1) textFieldsMap.txtRsSacadoL1 = bankSlipVO.payerCustomInfoLine1
        if (bankSlipVO.payerCustomInfoLine2) textFieldsMap.txtRsSacadoL2 = bankSlipVO.payerCustomInfoLine2

        if (bankSlipVO.customerCustomInfoLine2a) textFieldsMap.txtRsCedente2 = bankSlipVO.customerCustomInfoLine2a
        if (bankSlipVO.customerCustomInfoLine3a) textFieldsMap.txtRsCedente3 = bankSlipVO.customerCustomInfoLine3a

        if (bankSlipVO.customerCustomInfoLine2b) textFieldsMap.txtFcCedente2 = bankSlipVO.customerCustomInfoLine2b
        if (bankSlipVO.customerCustomInfoLine3b) textFieldsMap.txtFcCedente3 = bankSlipVO.customerCustomInfoLine3b

        if (bankSlipVO.itemCode1) textFieldsMap.txtLinhaItemCodigo1 = bankSlipVO.itemCode1
        if (bankSlipVO.itemDescription1) textFieldsMap.txtLinhaItemDescricao1 = bankSlipVO.itemDescription1
        if (bankSlipVO.itemQuantity1) textFieldsMap.txtLinhaItemQtde1 = bankSlipVO.itemQuantity1
        if (bankSlipVO.itemUnitaryValue1) textFieldsMap.txtLinhaItemVlrUnitario1 = bankSlipVO.itemUnitaryValue1
        if (bankSlipVO.itemTotalValue1) textFieldsMap.txtLinhaItemVlrTotal1 = bankSlipVO.itemTotalValue1

        if (bankSlipVO.itemCode2) textFieldsMap.txtLinhaItemCodigo2 = bankSlipVO.itemCode2
        if (bankSlipVO.itemDescription2) textFieldsMap.txtLinhaItemDescricao2 = bankSlipVO.itemDescription2
        if (bankSlipVO.itemQuantity2) textFieldsMap.txtLinhaItemQtde2 = bankSlipVO.itemQuantity2
        if (bankSlipVO.itemUnitaryValue2) textFieldsMap.txtLinhaItemVlrUnitario2 = bankSlipVO.itemUnitaryValue2
        if (bankSlipVO.itemTotalValue2) textFieldsMap.txtLinhaItemVlrTotal2 = bankSlipVO.itemTotalValue2

        if (bankSlipVO.itemCode3) textFieldsMap.txtLinhaItemCodigo3 = bankSlipVO.itemCode3
        if (bankSlipVO.itemDescription3) textFieldsMap.txtLinhaItemDescricao3 = bankSlipVO.itemDescription3
        if (bankSlipVO.itemQuantity3) textFieldsMap.txtLinhaItemQtde3 = bankSlipVO.itemQuantity3
        if (bankSlipVO.itemUnitaryValue3) textFieldsMap.txtLinhaItemVlrUnitario3 = bankSlipVO.itemUnitaryValue3
        if (bankSlipVO.itemTotalValue3) textFieldsMap.txtLinhaItemVlrTotal3 = bankSlipVO.itemTotalValue3

        if (bankSlipVO.itemCode4) textFieldsMap.txtLinhaItemCodigo4 = bankSlipVO.itemCode4
        if (bankSlipVO.itemDescription4) textFieldsMap.txtLinhaItemDescricao4 = bankSlipVO.itemDescription4
        if (bankSlipVO.itemQuantity4) textFieldsMap.txtLinhaItemQtde4 = bankSlipVO.itemQuantity4
        if (bankSlipVO.itemUnitaryValue4) textFieldsMap.txtLinhaItemVlrUnitario4 = bankSlipVO.itemUnitaryValue4
        if (bankSlipVO.itemTotalValue4) textFieldsMap.txtLinhaItemVlrTotal4 = bankSlipVO.itemTotalValue4

        if (bankSlipVO.itemCode5) textFieldsMap.txtLinhaItemCodigo5 = bankSlipVO.itemCode5
        if (bankSlipVO.itemDescription5) textFieldsMap.txtLinhaItemDescricao5 = bankSlipVO.itemDescription5
        if (bankSlipVO.itemQuantity5) textFieldsMap.txtLinhaItemQtde5 = bankSlipVO.itemQuantity5
        if (bankSlipVO.itemUnitaryValue5) textFieldsMap.txtLinhaItemVlrUnitario5 = bankSlipVO.itemUnitaryValue5
        if (bankSlipVO.itemTotalValue5) textFieldsMap.txtLinhaItemVlrTotal5 = bankSlipVO.itemTotalValue5

        if (bankSlipVO.itemCode6) textFieldsMap.txtLinhaItemCodigo6 = bankSlipVO.itemCode6
        if (bankSlipVO.itemDescription6) textFieldsMap.txtLinhaItemDescricao6 = bankSlipVO.itemDescription6
        if (bankSlipVO.itemQuantity6) textFieldsMap.txtLinhaItemQtde6 = bankSlipVO.itemQuantity6
        if (bankSlipVO.itemUnitaryValue6) textFieldsMap.txtLinhaItemVlrUnitario6 = bankSlipVO.itemUnitaryValue6
        if (bankSlipVO.itemTotalValue6) textFieldsMap.txtLinhaItemVlrTotal6 = bankSlipVO.itemTotalValue6

        if (bankSlipVO.itemCode7) textFieldsMap.txtLinhaItemCodigo7 = bankSlipVO.itemCode7
        if (bankSlipVO.itemDescription7) textFieldsMap.txtLinhaItemDescricao7 = bankSlipVO.itemDescription7
        if (bankSlipVO.itemQuantity7) textFieldsMap.txtLinhaItemQtde7 = bankSlipVO.itemQuantity7
        if (bankSlipVO.itemUnitaryValue7) textFieldsMap.txtLinhaItemVlrUnitario7 = bankSlipVO.itemUnitaryValue7
        if (bankSlipVO.itemTotalValue7) textFieldsMap.txtLinhaItemVlrTotal7 = bankSlipVO.itemTotalValue7

        if (bankSlipVO.itemCode8) textFieldsMap.txtLinhaItemCodigo8 = bankSlipVO.itemCode8
        if (bankSlipVO.itemDescription8) textFieldsMap.txtLinhaItemDescricao8 = bankSlipVO.itemDescription8
        if (bankSlipVO.itemQuantity8) textFieldsMap.txtLinhaItemQtde8 = bankSlipVO.itemQuantity8
        if (bankSlipVO.itemUnitaryValue8) textFieldsMap.txtLinhaItemVlrUnitario8 = bankSlipVO.itemUnitaryValue8
        if (bankSlipVO.itemTotalValue8) textFieldsMap.txtLinhaItemVlrTotal8 = bankSlipVO.itemTotalValue8

        if (bankSlipVO.itemCode9) textFieldsMap.txtLinhaItemCodigo9 = bankSlipVO.itemCode9
        if (bankSlipVO.itemDescription9) textFieldsMap.txtLinhaItemDescricao9 = bankSlipVO.itemDescription9
        if (bankSlipVO.itemQuantity9) textFieldsMap.txtLinhaItemQtde9 = bankSlipVO.itemQuantity9
        if (bankSlipVO.itemUnitaryValue9) textFieldsMap.txtLinhaItemVlrUnitario9 = bankSlipVO.itemUnitaryValue9
        if (bankSlipVO.itemTotalValue9) textFieldsMap.txtLinhaItemVlrTotal9 = bankSlipVO.itemTotalValue9

        if (bankSlipVO.itemCode10) textFieldsMap.txtLinhaItemCodigo10 = bankSlipVO.itemCode10
        if (bankSlipVO.itemDescription10) textFieldsMap.txtLinhaItemDescricao10 = bankSlipVO.itemDescription10
        if (bankSlipVO.itemQuantity10) textFieldsMap.txtLinhaItemQtde10 = bankSlipVO.itemQuantity10
        if (bankSlipVO.itemUnitaryValue10) textFieldsMap.txtLinhaItemVlrUnitario10 = bankSlipVO.itemUnitaryValue10
        if (bankSlipVO.itemTotalValue10) textFieldsMap.txtLinhaItemVlrTotal10 = bankSlipVO.itemTotalValue10

        if (bankSlipVO.itemCode11) textFieldsMap.txtLinhaItemCodigo11 = bankSlipVO.itemCode11
        if (bankSlipVO.itemDescription11) textFieldsMap.txtLinhaItemDescricao11 = bankSlipVO.itemDescription11
        if (bankSlipVO.itemQuantity11) textFieldsMap.txtLinhaItemQtde11 = bankSlipVO.itemQuantity11
        if (bankSlipVO.itemUnitaryValue11) textFieldsMap.txtLinhaItemVlrUnitario11 = bankSlipVO.itemUnitaryValue11
        if (bankSlipVO.itemTotalValue11) textFieldsMap.txtLinhaItemVlrTotal11 = bankSlipVO.itemTotalValue11

        return textFieldsMap
    }

    private Boolean isAdminAndOriginalBankSlip(Boolean originalBankSlip) {
        return SpringSecurityUtils.ifAllGranted('ROLE_SYSADMIN') && originalBankSlip
    }

    private ToolkitImage buildBarCodePdfImage(String barCode) {
        BarcodeInter25 barCodeInter25 = new BarcodeInter25()
        barCodeInter25.setCode(barCode)
        barCodeInter25.setExtended(true)
        barCodeInter25.setBarHeight(35)
        barCodeInter25.setFont(null)
        barCodeInter25.setN(3)
        return barCodeInter25.createAwtImage(Color.BLACK, Color.WHITE)
    }

    private Boolean shouldBuildPdfUsingTessera(Customer customer, Boolean paymentBook, Boolean isBatch, String template) {
        List<String> templatesOnTessera = [
            "cbvj",
            "stockInfo",
            "newBankSlip"
        ]

        if (template && !templatesOnTessera.contains(template)) {
            AsaasLogger.warn("BankSlipService.shouldBuildPdfUsingTessera >>> O template ${template} não pode ser gerado pelo Tessera")
            return false
        }

        if (isBatch || paymentBook) return false

        return true
    }

}

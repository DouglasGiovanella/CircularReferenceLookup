package com.asaas.service.stage

import grails.transaction.Transactional

import com.asaas.domain.stage.Stage
import com.asaas.domain.stage.StageEvent
import com.asaas.domain.stage.StageEventTemplate
import com.asaas.stage.StageCode
import com.asaas.stage.StageEventCycle
import com.asaas.stage.StageEventType

@Transactional
class StageBootStrapService {
    def initialize() {

		initializeLead()
		initializeOpportunity()
		initializeActivated()
		initializeWontUse()
    }

	def initializeLead() {
		Stage stage = Stage.findOrCreateWhere(code: StageCode.LEAD, name: "Lead").save(flush: true, failOnError: true)

		StageEvent stageEvent = StageEvent.findOrCreateWhere(
			stage: stage,
			type: StageEventType.EMAIL,
			subject: "Aviso que cliente não inseriu celular",
			recurrent: false,
			startDelayCycle: StageEventCycle.DAILY,
			startDelayCycleInterval: 3,
			deleted: false).save(flush: true, failOnError: true)

		StageEventTemplate stageEventTemplate = StageEventTemplate.findOrCreateWhere(stageEvent: stageEvent)
		stageEventTemplate.subject = "Aguardamos o seu celular"
		stageEventTemplate.body =
		"""
		Olá!
		Notamos que você ainda não inseriu o seu celular para validação de sua conta no ASAAS.

		Esta é uma etapa importante pois garante que em caso de perda ou mal uso de sua conta, a mesma pode ser recuperada através de uma ligação para seu telefone.

		Além disso, o seu Gerente de Contas <b>${'${accountManager.buildFirstName()}'}</b> está ansioso em ajudá-lo a melhor utilizar a ferramenta.

		<a href="https://www.asaas.com">Clique aqui e cadastre agora mesmo<a> ou acesse: https://www.asaas.com.

		Um abraço.
		Equipe ASAAS.
		"""

		stageEventTemplate.save(flush: true, failOnError: true)
	}

	def initializeOpportunity() {
		Stage stage = Stage.findOrCreateWhere(code: StageCode.OPPORTUNITY, name: "Oportunidade").save(flush: true, failOnError: true)

		StageEvent stageEvent = StageEvent.findOrCreateWhere(
			stage: stage,
			type: StageEventType.SMS,
			subject: "Cliente não terminou de validar a conta",
			recurrent: true,
			cycle: StageEventCycle.DAILY,
			cycleInterval: 1,
			startDelayCycle: StageEventCycle.DAILY,
			startDelayCycleInterval: 1,
			deleted: false).save(flush: true, failOnError: true)

        StageEventTemplate stageEventTemplate = StageEventTemplate.findOrCreateWhere(stageEvent: stageEvent)
		stageEventTemplate.body = "Olá ${'${customer.firstName}'}, sou ${'${accountManager.buildFirstName()}'}. Você não terminou de validar sua conta no ASAAS. Finalize seu cadastro por 47 38010919."
		stageEventTemplate.save(flush: true, failOnError: true)
	}

	def initializeActivated() {
		Stage stage = Stage.findOrCreateWhere(code: StageCode.ACTIVATED, name: "Ativado").save(flush: true, failOnError: true)

		StageEvent stageEvent = StageEvent.findOrCreateWhere(
			stage: stage,
			type: StageEventType.SMS,
			subject: "Notei que você ainda não recebeu cobranças",
			recurrent: false,
			cycle: null,
			cycleInterval: null,
			startDelayCycle: StageEventCycle.WEEKLY,
			startDelayCycleInterval: 1,
			deleted: false).save(flush: true, failOnError: true)

		StageEventTemplate stageEventTemplate = StageEventTemplate.findOrCreateWhere(stageEvent: stageEvent)
		stageEventTemplate.body = "Aqui é a ${'${accountManager.buildFirstName()}'} do ASAAS. Notei que você ainda não recebeu cobranças. Precisa de alguma ajuda? Me ligue em: 47 38010919"
		stageEventTemplate.save(flush: true, failOnError: true)
	}

	def initializeWontUse() {
		Stage stage = Stage.findOrCreateWhere(code: StageCode.WONT_USE, name: "Não vai usar").save(flush: true, failOnError: true)

		StageEvent stageEvent = StageEvent.findOrCreateWhere(
			stage: stage,
			type: StageEventType.SMS,
			subject: "Obrigado por experimentar o ASAAS",
			recurrent: false,
			cycle: null,
			cycleInterval: null,
			startDelayCycle: StageEventCycle.DAILY,
			startDelayCycleInterval: 0,
			deleted: false).save(flush: true, failOnError: true)

		StageEventTemplate stageEventTemplate = StageEventTemplate.findOrCreateWhere(stageEvent: stageEvent)
		stageEventTemplate.body = "Olá, o ASAAS agradece sua experiência. Caso queira usar o ASAAS no futuro, ficaremos felizes em recebê-lo de volta. Carinhoso abraço!"
		stageEventTemplate.save(flush: true, failOnError: true)
	}
}

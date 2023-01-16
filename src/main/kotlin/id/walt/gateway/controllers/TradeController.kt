package id.walt.gateway.controllers

import id.walt.gateway.dto.trades.*
import id.walt.gateway.providers.metaco.ProviderConfig
import id.walt.gateway.usecases.TradeUseCase
import io.javalin.http.Context
import io.javalin.http.HttpCode
import io.javalin.plugin.openapi.dsl.document

class TradeController(
    private val tradeUseCase: TradeUseCase,
) {
//    private val authService = AuthService(AuthSignatureService())
//
//    //    private val tradeUseCase: TradeUseCase = TradeUseCaseImpl()
//    private val tradeUseCase: TradeUseCase =
//        TradeUseCaseImpl(
//            IntentRepositoryImpl(authService),
//            TickerRepositoryImpl(authService),
//            IntentSignatureService()
//        )

    fun sell(ctx: Context) {
        val parameters = ctx.bodyAsClass<SwapParameter>()
        tradeUseCase.sell(
            TradeData(ProviderConfig.domainId, TransferParameter(
                parameters.spend.amount,
                parameters.spend.ticker,
                parameters.spend.maxFee,
                sender = parameters.spend.sender,
                recipient = ProviderConfig.nostroAccountId,
            ), "Sell"),
            TradeData(ProviderConfig.domainId, TransferParameter(
                parameters.receive.amount,
                parameters.receive.ticker,
                parameters.receive.maxFee,
                sender = ProviderConfig.nostroAccountId,
                recipient = parameters.receive.sender
            ), "Buy"),
        ).onSuccess {
            ctx.status(it.result.takeIf { it }?.let { HttpCode.OK } ?: HttpCode.NOT_FOUND)
            ctx.json(it)
        }.onFailure {
            ctx.status(HttpCode.NOT_FOUND)
            ctx.json(it)
        }
    }

    fun buy(ctx: Context) {
        val parameters = ctx.bodyAsClass<SwapParameter>()
        tradeUseCase.buy(
            TradeData(ProviderConfig.domainId, TransferParameter(
                parameters.spend.amount,
                parameters.spend.ticker,
                parameters.spend.maxFee,
                sender = parameters.spend.sender,
                recipient = ProviderConfig.nostroAccountId,
            ), "Buy"),
            TradeData(ProviderConfig.domainId, TransferParameter(
                parameters.receive.amount,
                parameters.receive.ticker,
                parameters.receive.maxFee,
                sender = ProviderConfig.nostroAccountId,
                recipient = parameters.receive.sender,
            ), "Receive"),
        ).onSuccess {
            ctx.status(it.result.takeIf { it }?.let { HttpCode.OK } ?: HttpCode.NOT_FOUND)
            ctx.json(it)
        }.onFailure {
            ctx.status(HttpCode.NOT_FOUND)
            ctx.json(it)
        }
    }

    fun send(ctx: Context) {
        val parameters = ctx.bodyAsClass<TransferParameter>()
        tradeUseCase.send(TradeData(ProviderConfig.domainId, parameters, "Transfer"))
            .onSuccess {
                ctx.status(it.result.takeIf { it }?.let { HttpCode.OK } ?: HttpCode.NOT_FOUND)
                ctx.json(it)
            }.onFailure {
                ctx.status(HttpCode.NOT_FOUND)
                ctx.json(it)
            }
    }

    fun validate(ctx: Context) {
        val parameters = ctx.bodyAsClass<TransferParameter>()
        tradeUseCase.validate(TradeValidationParameter(ProviderConfig.domainId, parameters))
            .onSuccess {
                ctx.status(it.result.takeIf { it }?.let { HttpCode.OK } ?: HttpCode.NOT_FOUND)
                ctx.json(it)
            }.onFailure {
                ctx.status(HttpCode.NOT_FOUND)
                ctx.json(it)
            }
    }

    fun sellDocs() = document().operation {
        it.summary("Returns the sell trade details").operationId("sell").addTagsItem("Trade Management")
    }.body<SwapParameter> {
        it.description("Sell parameters")
    }.json<TradeResult>("200") { it.description("The sell trade details") }

    fun buyDocs() = document().operation {
        it.summary("Returns the buy trade details").operationId("buy").addTagsItem("Trade Management")
    }.body<SwapParameter> {
        it.description("Buy parameters")
    }.json<TradeResult>("200") { it.description("The buy trade details") }

    fun sendDocs() = document().operation {
        it.summary("Returns the send trade details").operationId("send").addTagsItem("Trade Management")
    }.body<TransferParameter> {
        it.description("Send parameters")
    }.json<TradeResult>("200") { it.description("The send trade details") }

    fun validateDocs() = document().operation {
        it.summary("Returns the trade validation details").operationId("validate").addTagsItem("Trade Management")
    }.body<TransferParameter> {
        it.description("Trade preview parameters")
    }.json<TradeResult>("200") { it.description("The trade validation details") }
}
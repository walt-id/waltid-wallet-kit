package id.walt.gateway.providers.metaco.restapi.ticker

import id.walt.gateway.providers.metaco.AuthService
import id.walt.gateway.providers.metaco.repositories.TickerRepository
import id.walt.gateway.providers.metaco.restapi.BaseRestRepository
import id.walt.gateway.providers.metaco.restapi.CommonHttp
import id.walt.gateway.providers.metaco.restapi.ticker.model.Ticker
import id.walt.gateway.providers.metaco.restapi.ticker.model.TickerList

class TickerRepositoryImpl(
    override val authService: AuthService,
) : TickerRepository, BaseRestRepository(authService) {
    private val listEndpoint = "/v1/tickers"
    private val detailEndpoint = "/v1/tickers/%s"

    override fun findAll(criteria: Map<String, String>) = CommonHttp.get<TickerList>(
        client,
        CommonHttp.buildUrl(baseUrl, listEndpoint)
    )

    override fun findById(tickerId: String) = CommonHttp.get<Ticker>(
        client,
        String.format(
            CommonHttp.buildUrl(baseUrl, detailEndpoint),
            tickerId,
        )
    )
}
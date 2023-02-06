package id.walt.gateway.providers.metaco.restapi

import com.beust.klaxon.Klaxon
import id.walt.gateway.Common
import id.walt.gateway.dto.AmountWithValue
import id.walt.gateway.dto.TransferData
import id.walt.gateway.dto.ValueWithChange
import id.walt.gateway.dto.accounts.AccountData
import id.walt.gateway.dto.accounts.AccountIdentifier
import id.walt.gateway.dto.accounts.AccountParameter
import id.walt.gateway.dto.balances.AccountBalance
import id.walt.gateway.dto.balances.BalanceData
import id.walt.gateway.dto.balances.BalanceParameter
import id.walt.gateway.dto.profiles.ProfileData
import id.walt.gateway.dto.profiles.ProfileParameter
import id.walt.gateway.dto.tickers.TickerData
import id.walt.gateway.dto.tickers.TickerParameter
import id.walt.gateway.dto.transactions.TransactionData
import id.walt.gateway.dto.transactions.TransactionListParameter
import id.walt.gateway.dto.transactions.TransactionParameter
import id.walt.gateway.dto.transactions.TransactionTransferData
import id.walt.gateway.providers.metaco.ProviderConfig
import id.walt.gateway.providers.metaco.repositories.*
import id.walt.gateway.providers.metaco.restapi.account.model.Account
import id.walt.gateway.providers.metaco.restapi.models.customproperties.TransactionCustomProperties
import id.walt.gateway.providers.metaco.restapi.order.model.Order
import id.walt.gateway.providers.metaco.restapi.transaction.model.OrderReference
import id.walt.gateway.providers.metaco.restapi.transaction.model.Transaction
import id.walt.gateway.providers.metaco.restapi.transfer.model.Transfer
import id.walt.gateway.providers.metaco.restapi.transfer.model.transferparty.AccountTransferParty
import id.walt.gateway.providers.metaco.restapi.transfer.model.transferparty.AddressTransferParty
import id.walt.gateway.providers.metaco.restapi.transfer.model.transferparty.TransferParty
import id.walt.gateway.usecases.AccountUseCase
import id.walt.gateway.usecases.BalanceUseCase
import id.walt.gateway.usecases.TickerUseCase

class AccountUseCaseImpl(
    private val domainRepository: DomainRepository,
    private val accountRepository: AccountRepository,
    private val transactionRepository: TransactionRepository,
    private val orderRepository: OrderRepository,
    private val transferRepository: TransferRepository,
    private val addressRepository: AddressRepository,
    private val balanceUseCase: BalanceUseCase,
    private val tickerUseCase: TickerUseCase,
) : AccountUseCase {
    override fun profile(parameter: ProfileParameter): Result<ProfileData> = runCatching {
        ProfileData(
            profileId = parameter.id,
            accounts = getProfileAccounts(parameter).map {
                buildProfileData(AccountParameter(AccountIdentifier(it.data.domainId, it.data.id)), it)
            })
    }

    override fun balance(parameter: ProfileParameter): Result<AccountBalance> = runCatching {
        getProfileAccounts(parameter).flatMap {
            balanceUseCase.list(AccountParameter(AccountIdentifier(it.data.domainId, it.data.id))).getOrElse { emptyList() }
        }.filter { !ProviderConfig.tickersIgnore.contains(it.ticker.id) }.let { AccountBalance(it) }
    }

    override fun balance(parameter: BalanceParameter): Result<BalanceData> = runCatching {
        balanceUseCase.get(parameter).getOrThrow()
    }

    override fun transactions(parameter: TransactionListParameter): Result<List<TransactionData>> = runCatching {
        val tickerData = parameter.tickerId?.let { getTickerData(it) }
        val transactions = transactionRepository.findAll(parameter.domainId, mapOf("accountId" to parameter.accountId)).groupBy { it.id }
        val orders = orderRepository.findAll(parameter.domainId, mapOf("accountId" to parameter.accountId)).groupBy { it.data.id }
        transferRepository.findAll(
            parameter.domainId, mapOf(
                "accountId" to parameter.accountId,
                "sortBy" to "registeredAt",
                "sortOrder" to "DESC",
                parameter.tickerId?.let { "tickerId" to it } ?: Pair("", ""),
            )
        ).filter { !it.transactionId.isNullOrEmpty() }.groupBy { it.transactionId!! }.map {
            val transaction = transactions[it.key]?.first()
            val order = orders[transaction?.orderReference?.id]?.first()
            buildTransactionData(
                parameter,
                tickerData ?: getTickerData(it.value.first().tickerId),
                it.value,
                transaction,
                order
            )
        }
    }

    override fun transaction(parameter: TransactionParameter): Result<TransactionTransferData> = runCatching {
        transactionRepository.findById(parameter.domainId, parameter.transactionId).let { transaction ->
            val transfers = transferRepository.findAll(
                parameter.domainId,
                mapOf("transactionId" to parameter.transactionId, "accountId" to parameter.accountId)
            )
            val ticker = getTickerData(transfers.first().tickerId)
            val amount = computeAmount(transfers)
            TransactionTransferData(
                status = getTransactionStatus(transaction),
                date = transaction.registeredAt,
                total = AmountWithValue(amount, ticker),
                transfers = transfers.map {
                    TransferData(
                        amount = it.value,
                        type = it.kind,
                        address = getRelatedAccount(parameter.accountId, transfers),
                    )
                }
            )
        }
    }

    private fun getProfileAccounts(profile: ProfileParameter): List<Account> = let {
        val callback: (domainId: String, id: String) -> List<Account> = getProfileFetchCallback(profile.id)
        domainRepository.findAll(emptyMap()).map {
            runCatching { callback(it.data.id, profile.id) }.getOrDefault(emptyList())
        }.filter {
            it.isNotEmpty()
        }.flatten()
    }

    private fun getProfileFetchCallback(id: String): (domainId: String, accountId: String) -> List<Account> = { domainId, accountId ->
        if (Regex("[a-zA-Z0-9]{8}(-[a-zA-Z0-9]{4}){3}-[a-zA-Z0-9]{12}").matches(id)) {
            listOf(accountRepository.findById(domainId, accountId))
        } else if(Regex("[0-9]{2}").matches(id)){
            accountRepository.findAll(domainId, emptyMap()).filter {
                it.data.alias.equals(id)
            }
        }else {
            accountRepository.findAll(domainId, mapOf("metadata.customProperties" to "iban:$accountId"))
        }
    }

    private fun getTickerData(tickerId: String) = tickerUseCase.get(TickerParameter(tickerId)).getOrThrow()

    private fun getAccountTickers(account: AccountParameter) = balanceUseCase.list(account).getOrNull() ?: emptyList()

    private fun computeAmount(transfers: List<Transfer>) =
        transfers.filter { it.kind == "Transfer" }.map { it.value.toLongOrNull() ?: 0 }
            .fold(0L) { acc, d -> acc + d }.toString()

    private fun getTransactionStatus(transaction: Transaction?) =
        transaction?.ledgerTransactionData?.ledgerStatus ?: transaction?.processing?.status ?: "Unknown"

    private fun buildTransactionData(
        parameter: TransactionListParameter,
        tickerData: TickerData,
        transfers: List<Transfer>,
        transaction: Transaction?,
        order: Order?,
    ) = let {
        val amount = computeAmount(transfers)
        TransactionData(
            id = transaction?.id ?: "",
            date = transaction?.registeredAt ?: transfers.first().registeredAt,
            amount = amount,
            ticker = tickerData,
            type = getTransactionOrderType(parameter.accountId, transaction, order),
            status = getTransactionStatus(transaction),
            price = getTransactionPrice(
                amount,
                tickerData.decimals,
                tickerData.price.value,
                tickerData.price.change,
                order,
            ),
            relatedAccount = getRelatedAccount(parameter.accountId, transfers),
        )
    }

    private fun getTransactionPrice(
        amount: String,
        decimals: Int,
        price: Double,
        change: Double,
        order: Order? = null,
    ) = order?.let {
        it.data.metadata.customProperties["transactionProperties"]?.let {
            Klaxon().parse<TransactionCustomProperties>(it)?.let {
                ValueWithChange(it.value.toDoubleOrNull() ?: .0, it.change.toDoubleOrNull() ?: .0, it.currency)
            }
        }
    } ?: ValueWithChange(
        Common.computeAmount(amount, decimals) * price,
        Common.computeAmount(amount, decimals) * change
    )

    private fun getRelatedAccount(accountId: String, transfers: List<Transfer>) = let {
        val filtered = transfers.filter { it.kind == "Transfer" }
        filtered.flatMap { it.senders }.takeIf {
            it.any { (it as? AccountTransferParty)?.accountId == accountId }
        }?.let {
            filtered.mapNotNull { it.recipient }
        } ?: let {
            filtered.flatMap { it.senders }
        }
    }.let {
        getTransferAddresses(it)
    }.firstOrNull() ?: "Unknown"

    private fun getTransferAddresses(transferParties: List<TransferParty>) = transferParties.flatMap {
        if (it is AddressTransferParty) listOf(it.address)
        else (it as AccountTransferParty).addressDetails?.let { listOf(it.address) } ?: addressRepository.findAll(
            it.domainId, it.accountId, emptyMap()
        ).map { it.address }
    }

    private fun buildProfileData(parameter: AccountParameter, account: Account) = AccountData(
        accountIdentifier = AccountIdentifier(account.data.domainId, account.data.id),
        alias = account.data.alias,
        addresses = addressRepository.findAll(account.data.domainId, account.data.id, emptyMap()).map { it.address },
        tickers = getAccountTickers(parameter).map { it.ticker.id }
    )

    private fun getTransactionOrderType(accountId: String, transaction: Transaction?, order: Order? = null) =
        order?.data?.let {
            transaction?.relatedAccounts?.filter { it.id == accountId }?.none { it.sender }?.takeIf { it }
                ?.let { "Receive" } ?: it.metadata.customProperties["transactionProperties"]?.let {
                Klaxon().parse<TransactionCustomProperties>(it)?.type
            } ?: "Outgoing"
        } ?: "Receive"
}


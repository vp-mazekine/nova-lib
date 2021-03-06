package com.mazekine.nova.client

import arrow.core.Either
import arrow.core.Left
import arrow.core.Right
import com.mazekine.nova.client.interfaces.NovaApiInterface
import com.mazekine.nova.models.*
import com.mazekine.nova.types.ExchangeOrderStateType
import com.mazekine.nova.types.OrderSideType
import com.mazekine.utils.ErrorDescription
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.reflect.TypeToken
import org.slf4j.Logger
import org.slf4j.LoggerFactory.getLogger
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.*
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

object NovaApiService {
    private var api: NovaApiInterface? = null
    private var apiConfig: ApiConfig? = null
    private var gson: Gson? = null
    private val logger: Logger = getLogger(NovaApiService::class.java)

    /**
     * Creates a Retrofit instance of NovaApiService
     *
     * @param config API connection configuration
     * @return
     */
    fun init(config: ApiConfig) {
        val retrofit = Retrofit.Builder()
            .addConverterFactory(GsonConverterFactory.create())
            .baseUrl(config.apiPath)
            .build()

        api = retrofit.create(NovaApiInterface::class.java)
        apiConfig = config
        gson = Gson()
    }

    /**
     * Signs the request for Broxus
     *
     * @param method Path to a method called
     * @param content Request body to be sent
     * @return
     */
    private fun sign(method: String, content: String): Pair<Long, String> {
        val nonce = System.currentTimeMillis()
        val salt: String = nonce.toString() + method + content
        val secretKeySpec = SecretKeySpec(apiConfig!!.apiSecret.toByteArray(), "HmacSHA256")
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(secretKeySpec)
        val signature: ByteArray = mac.doFinal(salt.toByteArray())
        val base64: String = Base64.getEncoder().encodeToString(signature)
        return Pair(nonce, base64)
    }

    /**
     * Parse the server response and convert it to the appropriate type or return error description
     *
     * @param T Expected response type
     * @param r Response object
     * @param t Expected response type's class
     * @return Either<ErrorDescription, T>
     */
    private inline fun <reified T> unfoldResponse(
        r: Response<out JsonElement>,
        t: Class<T>
    ): Either<ErrorDescription, T>? {
        return try {
            when (r.body()) {
                //  If the response body is void
                null -> Left(
                    when (r.message()) {
                        //  Error without details
                        null -> ErrorDescription(
                            when (r.code()) {
                                in 400..499 -> "Request error"
                                in 500..599 -> "Server error"
                                else -> "Unknown error"
                            },
                            r.code().toString()
                        )
                        else -> {
                            ErrorDescription(
                                r.message(),
                                r.code().toString()
                            )
                        }
                    }
                )

                //  If the server returned the content
                else -> {
                    if (r.isSuccessful) {
                        //  Try to apply the requested model
                        Right(gson!!.fromJson(r.body()!!, t))
                    } else {
                        //  Return the error
                        Left(
                            ErrorDescription(
                                r.raw().body()!!.string(),
                                r.code().toString()
                            )
                        )
                        //Left(gson!!.fromJson(r.body()!!, ErrorDescription::class.java))
                    }
                }
            }
        } catch (e: Exception) {
            //  Handle unexpected errors
            Left(
                ErrorDescription(
                    e.message + "\n" + e.stackTrace.joinToString("\n"),
                    r.code().toString()
                )
            )
        }
    }

    /**
     * Transform the returned LinkedTreeMap to the desired type
     *
     * @param T Type to cast
     * @return List<T>
     */
    private inline fun <reified T> List<*>.castJsonArrayToType(): List<T> {
        val result: MutableList<T> = mutableListOf()
        val t = object : TypeToken<T>() {}.type

        this.forEach {
            try {
                result.add(
                    gson!!.fromJson(it.toString(), t)
                )
            } catch (e: Exception) {
                logger.error("Got the error while casting to $t", e)
            }
        }

        return result.toList()
    }

    /**
     * Get static address by the specified user or generate a new one
     *
     * @param currency
     * @param addressType
     * @param userAddress
     * @param workspaceId
     * @return
     */
    fun getStaticAddressByUser(
        currency: String,
        userAddress: String,
        addressType: String,
        workspaceId: String
    ): StaticAddress? {

        val result: Response<JsonObject>

        try {
            //  Input model for the REST call
            val input = StaticAddressRenewInput(
                currency,
                addressType,
                userAddress,
                workspaceId
            )

            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/static_addresses/renew",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.getStaticAddressByUser(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, StaticAddress::class.java).apply {
            return when (this) {
                is Either.Right -> this.b
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }
    }

    /**
     * Returns balances of all workspace users
     *
     * @param workspaceId Unique identifier of the workspace
     * @return WorkspaceBalance object or null in case of error
     */
    @Suppress("UNCHECKED_CAST")
    fun getWorkspaceUsersBalances(workspaceId: String): List<WorkspaceBalance>? {

        val result: Response<JsonArray>

        try {
            //  Input model for the REST call
            val input = WorkspaceBalanceInput(workspaceId)

            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/users/balances",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.getWorkspaceUsersBalances(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, List::class.java).apply {
            return when (this) {
                is Either.Right -> this.b.castJsonArrayToType<WorkspaceBalance>()
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }
    }

    /**
     * This method allows you to see which cryptocurrency trading pairs can be exchanged.
     *
     * @return List of CurrenciesPairMeta items
     */
    @Suppress("UNCHECKED_CAST")
    fun getCurrenciesPairs(): List<CurrenciesPairMeta>? {
        val result: Response<JsonArray>

        try {
            //  Perform request
            result = api!!.getCurrenciesPairs(apiConfig!!.apiKey).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, List::class.java).apply {
            return when (this) {
                is Either.Right -> this.b.castJsonArrayToType<CurrenciesPairMeta>()
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }
    }

    /**
     * Get deposit currencies metadata
     *
     * @return List<DepositMeta> or null in case of error
     */
    fun getDepositCurrencies(): List<DepositMeta>? {
        val result: Response<JsonArray>

        try {
            //  Perform request
            result = api!!.getDepositCurrencies().execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, List::class.java).apply {
            return when (this) {
                is Either.Right -> this.b.castJsonArrayToType<DepositMeta>()
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }
    }

    /**
     * Returns balance of the specific user in different currencies
     *
     * @param userAddress The unique address of the user. Which value to specify the address depends on the addressType. Case sensitive!
     * @param addressType User address type. Case sensitive!
     * @param workspaceId Id of workspace. UUID ver. 4 rfc
     * @return
     */
    @Suppress("UNCHECKED_CAST")
    fun getSpecificUserBalance(
        userAddress: String,
        addressType: String,
        workspaceId: String? = null
    ): List<AccountBalance>? {

        val result: Response<JsonArray>

        try {
            //  Input model for the REST call
            val input = UserAccountInput(userAddress, addressType, workspaceId)

            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/users/balance",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.getSpecificUserBalance(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, List::class.java).apply {
            return when (this) {
                is Either.Right -> this.b.castJsonArrayToType<AccountBalance>()
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }
    }

    /**
     * Get specific user transactions
     *
     * @param userAddress The unique address of the user. Which value to specify the address depends on the addressType. Case sensitive!
     * @param addressType User address type. Case sensitive!
     * @param workspaceId Id of workspace. UUID ver. 4 rfc
     * @param groupKind Transaction group kind.
     * @param orderBy Transaction order.
     * @param from Unix timestamp.
     * @param to Unix timestamp.
     * @param currency Сurrency identifier or ticker. Can contain more than 3 letters.
     * @param state Current transaction state.
     * @param count Max 500
     * @param offset
     * @param kind Transaction kind.
     * @param direction Transaction direction.
     * @param transactionId Id of transaction. UUID ver. 4 rfc
     * @return
     */
    fun getSpecificUserTransactions(
        userAddress: String,
        addressType: String,
        workspaceId: String? = null,
        groupKind: TransactionGroupKind? = null,
        orderBy: TransactionOrderBy? = null,
        from: Long? = null,
        to: Long? = null,
        currency: String? = null,
        state: TransactionState? = null,
        count: Int? = null,
        offset: Int? = null,
        kind: TransactionKind? = null,
        direction: TransactionDirection? = null,
        transactionId: String? = null
    ): List<Transaction>? {

        val result: Response<JsonArray>

        try {
            //  Input model for the REST call
            val input = SearchTransactionsInput(
                userAddress,
                addressType,
                workspaceId,
                groupKind,
                orderBy,
                from,
                to,
                currency,
                state,
                count,
                offset,
                kind,
                direction,
                transactionId
            )

            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/users/transactions",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.getSpecificUserTransactions(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, List::class.java).apply {
            return when (this) {
                is Either.Right -> this.b.castJsonArrayToType<Transaction>()
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }

    }

    /**
     * Returns all orders of the specific user with filter
     *
     * @param id Id of exchange order. UUID ver. 4 rfc
     * @param userAddress User address type. Case sensitive!
     * @param addressType The unique address of the user. Which value to specify the address depends on the addressType. Case sensitive!
     * @param workspaceId Id of workspace. UUID ver. 4 rfc
     * @param base Сurrency identifier or ticker. Can contain more than 3 letters.
     * @param counter Сurrency identifier or ticker. Can contain more than 3 letters.
     * @param orderSide
     * @param state Current Exchange Order state.
     * @param isAlive For open orders this flag is true
     * @param offset
     * @param limit Max 500
     * @param from Unix timestamp in milliseconds
     * @param to Unix timestamp in milliseconds
     *
     * @return List of Exchange items
     */
    @Suppress("UNCHECKED_CAST")
    fun getSpecificUserOrders(
        id: String? = null,
        userAddress: String,
        addressType: String,
        workspaceId: String? = null,
        base: String? = null,
        counter: String? = null,
        orderSide: OrderSideType? = null,
        state: ExchangeOrderStateType? = null,
        isAlive: Boolean? = null,
        offset: Number? = null,
        limit: Number? = null,
        from: Long? = null,
        to: Long? = null
    ): List<Exchange>? {

        val result: Response<JsonArray>

        try {
            //  Input model for the REST call
            val input = ExchangeSearchInput(
                id,
                userAddress,
                addressType,
                workspaceId,
                base,
                counter,
                orderSide,
                state,
                isAlive,
                offset,
                limit,
                from,
                to
            )

            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/users/exchanges",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.getSpecificUserOrders(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, List::class.java).apply {
            return when (this) {
                is Either.Right -> this.b.castJsonArrayToType<Exchange>()
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }
    }

    /**
     * Creates a limit order from a specific user
     *
     * @param id Id of transaction. UUID ver. 4 rfc
     * @param userAddress User address type. Case sensitive!
     * @param addressType The unique address of the user. Which value to specify the address depends on the addressType. Case sensitive!
     * @param workspaceId Id of workspace. UUID ver. 4 rfc
     * @param from Сurrency identifier or ticker. Can contain more than 3 letters.
     * @param to Сurrency identifier or ticker. Can contain more than 3 letters.
     * @param fromValue Amount of currency. Positive floating point number.
     * @param toValue Amount of currency. Positive floating point number.
     * @param applicationId Id of application. Random string
     * @return
     */
    fun createLimitOrder(
        id: String,
        userAddress: String,
        addressType: String,
        workspaceId: String? = null,
        from: String,
        to: String,
        fromValue: String,
        toValue: String,
        applicationId: String? = null,
        selfTradingPrevention: SelfTradingPrevention? = SelfTradingPrevention.DoNothing
    ): ExchangeTransactionId? {

        val result: Response<JsonObject>

        //  Input model for the REST call
        val input = ExchangeLimitInput(
            id,
            userAddress, addressType, workspaceId,
            from, to, fromValue, toValue,
            applicationId, selfTradingPrevention
        )

        try {
            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/exchange/limit",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.createLimitOrder(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, ExchangeTransactionId::class.java).apply {
            return when (this) {
                is Either.Right -> this.b
                is Either.Left -> {
                    logger.error(
                        this.a.toString() + "\nSource request data:\n$input"
                    )
                    null
                }
                else -> null
            }
        }
    }

    /**
     * Cancels selected order
     *
     * @param transactionId
     * @return
     */
    fun cancelOrder(transactionId: String): Boolean {

        val result: Response<String>

        try {
            //  Perform request
            result = api!!.cancelOrder(transactionId, apiConfig!!.apiKey).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return false
        }

        return result.isSuccessful
    }

    /**
     * Get current orders book by specified base and counter currencies
     *
     * @param base Base currency
     * @param counter Quote currency
     * @param workspaceId Id of workspace. UUID ver. 4 rfc
     */
    fun getOrderBook(
        base: String,
        counter: String,
        workspaceId: String? = null
    ): ExchangeOrderBook? {

        val result: Response<JsonObject>

        try {
            //  Input model for the REST call
            val input = ExchangeOrderBookInput(workspaceId, base, counter)

            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/exchange/order_book",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.getOrderBook(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, ExchangeOrderBook::class.java).apply {
            return when (this) {
                is Either.Right -> this.b
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }
    }


    //  ➡️ TRANSFERS

    /**
     * Send internal transaction to another account of Broxus Nova
     *
     * @param value Amount of currency. Positive floating point number.
     * @param currency Сurrency identifier or ticker. Can contain more than 3 letters
     * @param fromUserAddress The unique address of the user. Which value to specify the address depends on the addressType. Case sensitive
     * @param fromAddressType User address type. Case sensitive
     * @param fromWorkspaceId Id of workspace. UUID ver. 4 rfc
     * @param toUserAddress The unique address of the user. Which value to specify the address depends on the addressType. Case sensitive
     * @param toAddressType User address type. Case sensitive
     * @param toWorkspaceId Id of workspace. UUID ver. 4 rfc
     * @param applicationId Id of application. Random string
     *
     * @return InternalTransaction or null in case of error
     */
    fun transfer(
        value: Float,
        currency: String,
        fromUserAddress: String,
        fromAddressType: String,
        fromWorkspaceId: String?,
        toUserAddress: String,
        toAddressType: String,
        toWorkspaceId: String?,
        applicationId: String?
    ): InternalTransaction? {

        val result: Response<JsonObject>

        try {
            //  Input model for the REST call
            val input = InternalTransactionInput(
                UUID.randomUUID().toString(),
                value.toString(), currency,
                fromUserAddress, fromAddressType, fromWorkspaceId,
                toUserAddress, toAddressType, toWorkspaceId,
                applicationId
            )

            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/transfer",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.transfer(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, InternalTransaction::class.java).apply {
            return when (this) {
                is Either.Right -> this.b
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }
    }

    //  ↗️WITHDRAW

    /**
     * Creates a withdrawal request from the user's balance
     *
     * @param id Id of transaction. UUID ver. 4 rfc
     * @param value Amount of currency. Positive floating point number.
     * @param currency Сurrency identifier or ticker. Can contain more than 3 letters.
     * @param userAddress The unique address of the user. Which value to specify the address depends on the addressType. Case sensitive
     * @param addressType User address type. Case sensitive
     * @param workspaceId Id of workspace. UUID ver. 4 rfc
     * @param blockchainAddress Blockchain address
     * @param applicationId Id of application. Random string
     * @return WithdrawTransactionId or null in case of error
     */
    fun withdraw(
        id: String,
        value: Float,
        currency: String,
        userAddress: String,
        addressType: String,
        workspaceId: String?,
        blockchainAddress: String,
        applicationId: String?
    ): WithdrawTransactionId? {

        val result: Response<JsonObject>

        try {
            //  Input model for the REST call
            val input = WithdrawInput(
                UUID.randomUUID().toString(),
                value.toString(), currency,
                userAddress, addressType, workspaceId,
                blockchainAddress, applicationId
            )

            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/withdraw",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.withdraw(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        //  Transform server response
        unfoldResponse(result, WithdrawTransactionId::class.java).apply {
            return when (this) {
                is Either.Right -> this.b
                is Either.Left -> {
                    logger.error(this.a.toString())
                    null
                }
                else -> null
            }
        }
    }

    fun validateBlockchainAddress(
        blockchainAddress: String,
        currency: String
    ): Boolean? {
        val result: Response<String>

        try {
            //  Input model for the REST call
            val input = WithdrawValidate(blockchainAddress, currency)

            //  Prepare the request signature
            val (nonce, signature) = sign(
                "/v1/withdraw/validate",
                gson!!.toJson(input).toString()
            )

            //  Perform request
            result = api!!.validateBlockchainAddress(input, apiConfig!!.apiKey, nonce, signature).execute()
        } catch (e: Exception) {
            logger.error("Nova API service was not properly initialized!", e)
            return null
        }

        return if (result.isSuccessful && (result.body() != null)) {
            result.body().toBoolean()
        } else {
            logger.error(
                when {
                    result.errorBody() != null -> result.errorBody()!!.string()
                    result.body() != null -> result.body()
                    else -> "Unknown error while validating blockchain address. Error code: " + result.code() + ". Message: " + result.message()
                }
            )
            null
        }
    }
}
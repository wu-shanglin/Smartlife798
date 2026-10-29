package com.smartlife798.app.core

import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonParseException
import com.google.gson.reflect.TypeToken
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okio.BufferedSink
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import java.io.IOException
import java.lang.reflect.Type
import java.util.concurrent.TimeUnit

/** Successful or failed business response. */
sealed interface Res<out T> {
    data class Ok<T>(val data: T?, val serverTime: Long = 0L) : Res<T>
    data class Fail(val code: Int, val msg: String) : Res<Nothing>
}

fun <T> Res<T>.dataOrNull(): T? = (this as? Res.Ok<T>)?.data

/** Kotlin mirror of the string overload set used by the original client. */
object Api {
    /**
     * Verified live: the production gateway of the original app.
     * ONLINE  -> https://i.ilife798.com   (main API)
     * PRE     -> https://prerelease.ilife798.com
     */
    const val BASE_URL: String = "https://i.ilife798.com"

    /** Verified bundled fallback; distribution discovery may supply a newer version. See docs/04. */
    const val CLIENT_VERSION: String = "3.1.10"

    private val gson = Gson()
    private val jsonType = "application/json; charset=utf-8".toMediaType()
    private class RequestSession(val token: String?, val protocolVersion: String? = null)

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .addInterceptor { chain ->
            val snapshot = chain.request().tag(RequestSession::class.java)
            val version = snapshot?.protocolVersion ?: session.versionHeader()
            val builder = chain.request().newBuilder()
                .header("ApplicationType", "1,1")
                .header("VersionCode", version)
                .header("user-agent", "Android_ilife798_$version")
            if (chain.request().url.encodedPath == "/api/v1/sys/app/upt") {
                builder.url(chain.request().url.newBuilder().setQueryParameter("version", version).build())
            }
            val token = if (snapshot == null) session.token else snapshot.token
            token?.takeIf { it.isNotBlank() }?.let { builder.header("Authorization", it) }
            chain.proceed(builder.build())
        }
        .build()

    // ---------------------------------------------------------------- plumbing

    private suspend fun <T> request(
        method: String,
        path: String,
        params: Map<String, String?> = emptyMap(),
        body: Any? = null,
        allowConnectionRetry: Boolean = true,
        accountToken: String? = session.token,
        protocolVersion: String? = null,
        dataType: Type,
    ): Res<T> = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        try {
            val url = (BASE_URL + path).toHttpUrl().newBuilder().apply {
                params.forEach { (k, v) -> if (v != null) addQueryParameter(k, v) }
            }.build()
            val builder = Request.Builder().url(url).tag(RequestSession::class.java, RequestSession(accountToken, protocolVersion)).method(
                method,
                when {
                    body == null && method == "GET" -> null
                    body == null -> ByteArray(0).toRequestBody(jsonType)
                    else -> gson.toJson(body).toRequestBody(jsonType)
                },
            )
            val request = builder.build()
            val call = if (allowConnectionRetry) client.newCall(request) else singleAttemptWriteCall(client, request)
            awaitCall(call) { r, text ->
                if (!r.isSuccessful) {
                    return@awaitCall Res.Fail(r.code, "服务异常（HTTP ${r.code}）")
                }
                // Envelope first, payload second: `data` is untyped for command
                // endpoints and must never turn an accepted command into a
                // client-side parse failure.
                val envelope = ApiEnvelope.parse(gson, text)
                    ?: return@awaitCall Res.Fail(-1, "响应解析失败")
                when {
                    envelope.code >= 0 ->
                        Res.Ok(ApiEnvelope.decode(gson, envelope.data, dataType), envelope.time)
                    envelope.code == -99 -> {
                        session.onTokenRejected(accountToken)
                        Res.Fail(envelope.code, envelope.msg ?: "登录状态已过期")
                    }
                    else -> Res.Fail(envelope.code, envelope.msg ?: "请求失败（${envelope.code}）")
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: IOException) {
            Res.Fail(ErrorCodes.NETWORK, "网络不可用，请检查网络连接")
        } catch (e: JsonParseException) {
            Res.Fail(-1, "响应解析失败")
        } catch (e: Exception) {
            Res.Fail(ErrorCodes.UNKNOWN, "请求处理失败，请稍后重试")
        }
    }

    /** Cancellation closes the socket; body ownership never escapes the callback. */
    internal suspend fun <T> awaitCall(call: Call, decode: (Response, String) -> T): T =
        suspendCancellableCoroutine { continuation ->
            continuation.invokeOnCancellation { call.cancel() }
            call.enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }

                override fun onResponse(call: Call, response: Response) {
                    try {
                        val value = response.use {
                            continuation.context.ensureActive()
                            val text = it.body?.string().orEmpty()
                            continuation.context.ensureActive()
                            decode(it, text)
                        }
                        continuation.context.ensureActive()
                        if (continuation.isActive) continuation.resume(value)
                    } catch (e: Exception) {
                        if (continuation.isActive) continuation.resumeWithException(e)
                    }
                }
            })
        }

    /** Connection retries alone do not prevent OkHttp's 503 or redirect follow-ups. */
    internal fun singleAttemptWriteCall(transport: OkHttpClient, request: Request): Call {
        val payload = requireNotNull(request.body) { "A single-attempt write requires a request body" }
        val oneShot = object : RequestBody() {
            override fun contentType() = payload.contentType()
            override fun contentLength() = payload.contentLength()
            override fun isOneShot() = true
            override fun writeTo(sink: BufferedSink) = payload.writeTo(sink)
        }
        val prepared = request.newBuilder().method(request.method, oneShot).build()
        return transport.newBuilder()
            .retryOnConnectionFailure(false)
            .followRedirects(false)
            .followSslRedirects(false)
            .build()
            .newCall(prepared)
    }

    private inline fun <reified T> dataType(): Type = object : TypeToken<T>() {}.type
    private inline fun <reified T> listType(): Type = object : TypeToken<List<T>>() {}.type

    // ------------------------------------------------------------------ auth

    /** GET /api/v1/captcha/?s=<seed>&r=<timestamp> -> image/jpeg */
    suspend fun captcha(seed: String, ts: String): ByteArray? =
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val url = (BASE_URL + "/api/v1/captcha/").toHttpUrl().newBuilder()
                    .addQueryParameter("s", seed)
                    .addQueryParameter("r", ts)
                    .build()
                val req = Request.Builder().url(url)
                    .header("ApplicationType", "1,1")
                    .get().build()
                client.newCall(req).execute().use { if (it.isSuccessful) it.body?.bytes() else null }
            } catch (e: Exception) {
                null
            }
        }

    /** POST /api/v1/acc/login/code  body {un, authCode, s} */
    suspend fun sendSms(phone: String, captcha: String, seed: Double): Res<Unit> =
        request("POST", "/api/v1/acc/login/code", body = mapOf("un" to phone, "authCode" to captcha, "s" to seed), dataType = dataType<Unit>())

    /** POST /api/v1/acc/login  body {un, authCode} */
    suspend fun login(phone: String, smsCode: String): Res<LoginData> =
        request("POST", "/api/v1/acc/login", body = mapOf("un" to phone, "authCode" to smsCode), dataType = dataType<LoginData>())

    /** GET /api/v1/acc/logout */
    suspend fun logout(accountToken: String? = session.token): Res<Unit> =
        request("GET", "/api/v1/acc/logout", accountToken = accountToken, dataType = dataType<Unit>())

    /** GET /api/v1/ui/app/master -> account + favorites + ads */
    suspend fun master(): Res<Master> = request("GET", "/api/v1/ui/app/master", dataType = dataType<Master>())

    /** Original ModifyUserInfoParm: send only the field being changed. */
    suspend fun updateProfile(patch: ProfilePatch): Res<Unit> =
        request("POST", "/api/v1/acc/upt", body = patch, allowConnectionRetry = false, dataType = dataType<Unit>())

    suspend fun checkInMissions(): Res<Missions> =
        request("GET", "/api/v1/acc/score/mission-lst", dataType = dataType<Missions>())

    /** Independent compatibility digest; never carries or impersonates the original APK identity. */
    suspend fun checkIn(params: ScoreParams, sign: String, protocolVersion: String = session.versionHeader()): Res<Unit> =
        request("POST", "/api/v1/acc/score/score-send", mapOf("sign" to sign, "s" to "1"),
            body = params, allowConnectionRetry = false, protocolVersion = protocolVersion, dataType = dataType<Unit>())

    /** GET /api/v1/ui/app/dev/home/1?did=&apply=6 */
    suspend fun deviceDetail(did: String, apply: Int = 6): Res<DeviceDetail> =
        request("GET", "/api/v1/ui/app/dev/home/1", mapOf("did" to did, "apply" to apply.toString()), dataType = dataType<DeviceDetail>())

    /**
     * GET /api/v1/ui/app/dev/status?did=&more=
     *
     * The `device` in this response is **partial** ([StatusSnapshot]/[DeviceStatus]):
     * only status fields, no `bm`/`name`/`ep`. Merge it with
     * [com.smartlife798.app.core.mergeStatus] before rendering.
     */
    suspend fun deviceStatus(did: String, more: Boolean = false): Res<StatusSnapshot> =
        request("GET", "/api/v1/ui/app/dev/status", mapOf("did" to did, "more" to more.toString()), dataType = dataType<StatusSnapshot>())

    /** GET /api/v1/dev/favo?did=&remove= (remove=1 取消收藏, 0 收藏) */
    suspend fun favorite(did: String, remove: Int): Res<Unit> =
        request("GET", "/api/v1/dev/favo", mapOf("did" to did, "remove" to remove.toString()), dataType = dataType<Unit>())

    /**
     * GET /api/v1/dev/start?did=&upgrade=&ptype=&args=&rcp=&cnt=
     *
     * Returns the raw `data` payload as text (empty when absent). The command
     * endpoints are modelled as `Unit`, but `data` is not always empty and may
     * carry a server-side message; keeping it lets the caller quote the server
     * instead of guessing why a machine stayed idle.
     *
     * `receipt` must stay `false`: with `rcp=true` the service answers with the
     * device frame and the machine does not execute the command (verified on a
     * real dispenser). See [DeviceCommands].
     */
    suspend fun deviceStart(
        did: String,
        payType: Int,
        args: String,
        receipt: Boolean = false,
        count: Int = 1,
        upgrade: Boolean = true,
    ): Res<String> {
        val res: Res<JsonElement?> = request(
            "GET", "/api/v1/dev/start",
            DeviceCommands.startQuery(did, payType, args, receipt, count, upgrade),
            dataType = dataType<JsonElement?>(),
        )
        return when (res) {
            is Res.Ok -> {
                val element = res.data
                val text = when {
                    element == null || element.isJsonNull -> ""
                    // A JSON string payload (e.g. `data:""` or `data:"设备离线"`)
                    // is quoted by toString(); unwrap it so callers can print it.
                    element.isJsonPrimitive && element.asJsonPrimitive.isString -> element.asString
                    else -> element.toString()
                }
                Res.Ok(text)
            }
            is Res.Fail -> res
        }
    }

    /** GET /api/v1/dev/end?did=&rcp= — `receipt` false for the same reason as [deviceStart]. */
    suspend fun deviceEnd(did: String, receipt: Boolean = false): Res<Unit> =
        request("GET", "/api/v1/dev/end", DeviceCommands.endQuery(did, receipt), dataType = dataType<Unit>())

    /** GET /api/v1/dev/mode/start?did=&mode=&args= */
    suspend fun setMode(did: String, mode: Int, args: String): Res<Unit> =
        request("GET", "/api/v1/dev/mode/start", mapOf("did" to did, "mode" to mode.toString(), "args" to args), dataType = dataType<Unit>())

    /** GET /api/v1/qr/use?id= — scan/manual ID or the official NFC card q parameter. */
    suspend fun qrUse(id: String): Res<QrData> = request("GET", "/api/v1/qr/use", mapOf("id" to id), dataType = dataType<QrData>())

    /** GET /api/v1/ui/app/dev/dev-use-way?way= */
    suspend fun useWay(way: String): Res<Unit> =
        request("GET", "/api/v1/ui/app/dev/dev-use-way", mapOf("way" to way), dataType = dataType<Unit>())

    /** GET /api/v1/bill/lst-owner?page=&size=&hasCount=&status=  — page is 0-based. */
    suspend fun bills(page: Int = 0, size: Int = 20, status: Int? = null): Res<List<Bill>> =
        request(
            "GET", "/api/v1/bill/lst-owner",
            mapOf("page" to page.toString(), "size" to size.toString(), "hasCount" to "true", "status" to status?.toString()),
            dataType = listType<Bill>(),
        )

    /** GET /api/v1/bill/view-full?id= */
    suspend fun billDetail(id: String): Res<BillDetail> =
        request("GET", "/api/v1/bill/view-full", mapOf("id" to id), dataType = dataType<BillDetail>())

    /** GET /api/v1/ord/lst-owner?... */
    suspend fun orders(page: Int = 1, size: Int = 20, uid: String? = null): Res<List<WorkOrder>> =
        request(
            "GET", "/api/v1/ord/lst-owner",
            mapOf("page" to page.toString(), "size" to size.toString(), "hasCount" to "true", "uid" to uid, "sort" to "ctime"),
            dataType = listType<WorkOrder>(),
        )

    /** GET /api/v1/acc/wallet/owner -> the account wallet of the enterprise */
    suspend fun wallet(): Res<Wallet> {
        val res: Res<WalletOwner> = request("GET", "/api/v1/acc/wallet/owner", dataType = dataType<WalletOwner>())
        return when (res) {
            is Res.Ok -> res.data?.aw?.let { Res.Ok(it) } ?: Res.Fail(-1, "服务端未返回钱包信息")
            is Res.Fail -> res
        }
    }

    /** GET /api/v1/bill/pay/channels?id= */
    suspend fun payChannels(billId: String): Res<PayChannelData> =
        request("GET", "/api/v1/bill/pay/channels", mapOf("id" to billId), dataType = dataType<PayChannelData>())

    /** GET /api/v1/trans/prepay/{type}?id=  -> data is the signed order string for the SDK */
    suspend fun prepay(payType: Int, billId: String): Res<String> {
        val res = request<Any>("GET", "/api/v1/trans/prepay/$payType", mapOf("id" to billId), dataType = dataType<Any>())
        return when (res) {
            is Res.Ok -> {
                val data = res.data
                when (data) {
                    is String -> Res.Ok(data)
                    null -> Res.Fail(-1, "支付订单为空")
                    else -> Res.Ok(data.toString())
                }
            }
            is Res.Fail -> res
        }
    }

    /** GET /api/v1/ui/app/sign-pga?type= — 支付宝免密签约参数（code 为 pgacode） */
    suspend fun signPga(type: Int): Res<AccountSignData> =
        request("GET", "/api/v1/ui/app/sign-pga", mapOf("type" to "$type"), dataType = dataType<AccountSignData>())

    /** GET /api/v1/trans/prepay/{type}?id= — raw payload (WeChat returns an object). */
    suspend fun prepayJson(payType: Int, billId: String): Res<JsonElement?> =
        request("GET", "/api/v1/trans/prepay/$payType", mapOf("id" to billId), dataType = dataType<JsonElement?>())

    /** GET /api/v1/trans/confirm?id= */
    suspend fun confirmPay(billId: String): Res<Unit> =
        request("GET", "/api/v1/trans/confirm", mapOf("id" to billId), dataType = dataType<Unit>())

    /** GET /api/v1/trans/close?id= */
    suspend fun closePay(billId: String): Res<Unit> =
        request("GET", "/api/v1/trans/close", mapOf("id" to billId), dataType = dataType<Unit>())

    /** GET /api/v1/prd/lst?eid=&type=1&status=1&all=false&did=&page=0&size=100&hasCount=false — 充值商品 */
    suspend fun rechargeProducts(eid: String): Res<List<Product>> = request(
        "GET", "/api/v1/prd/lst",
        mapOf(
            "eid" to eid,
            "type" to "1",
            "status" to "1",
            "all" to "false",
            "did" to "",
            "page" to "0",
            "size" to "100",
            "hasCount" to "false",
        ),
        dataType = listType<Product>(),
    )

    /** POST /api/v1/bill/save — 生成充值账单，`data` 为新账单 id */
    suspend fun saveRechargeBill(body: RechargeBillBody): Res<Long> =
        request("POST", "/api/v1/bill/save", body = body, dataType = dataType<Long>())

    /** Original app's update channel, not an updater for com.smartlife798.app. */
    internal fun appUpdateQuery(): Map<String, String> = mapOf(
        "appid" to "com.cloudora.android",
        "version" to session.versionHeader(),
        "type" to "1",
    )

    /** A "no update" response does not prove device commands accept this version. See docs/04. */
    suspend fun appUpdate(): Res<Any> = request(
        "GET", "/api/v1/sys/app/upt", appUpdateQuery(), dataType = dataType<Any>(),
    )

    /** GET /api/v1/dev/near?eid=&dtype=&pos=&lat=&size= */
    suspend fun nearbyDevices(eid: String?, dtype: String?, pos: String?, lat: String?, size: Int = 20): Res<List<NearDevice>> =
        request(
            "GET", "/api/v1/dev/near",
            mapOf("eid" to eid, "dtype" to dtype, "pos" to pos, "lat" to lat, "size" to size.toString()),
            dataType = listType<NearDevice>(),
        )
}

object ErrorCodes {
    const val NETWORK = -1000
    const val UNKNOWN = -1001
    const val TOKEN_EXPIRED = -99
    /** Observed client-side mappings of server business codes. */
    const val INSUFFICIENT_BALANCE = -87
    const val PAYMENT_REQUIRED = -88
    const val UNPAID_BILL = -52

    /** `dev/end` while the machine is still running: press 「结算」 on the device. */
    const val DEVICE_RUNNING = -2

    /**
     * `dev/start` was accepted by the server, but the device never left standby
     * (or reported a terminal state) inside [StartWatch]'s window. Client-side
     * code only; it is never sent to the server.
     */
    const val START_UNCONFIRMED = -1002
    /** `dev/end` accepted, but a matching standby snapshot has not arrived. */
    const val END_UNCONFIRMED = -1003
}

package com.kzvpn.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.google.zxing.BarcodeFormat
import com.google.zxing.qrcode.QRCodeWriter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class PaymentActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var pollJob: Job? = null

    private lateinit var subscriptionStatus: TextView
    private lateinit var paymentCard: LinearLayout
    private lateinit var amountText: TextView
    private lateinit var addressText: TextView
    private lateinit var confirmationText: TextView
    private lateinit var qrImage: ImageView
    private lateinit var createButton: Button
    private lateinit var message: TextView

    private var orderId: String? = null
    private var paymentUri: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        refreshSubscription()
    }

    override fun onDestroy() {
        pollJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(28))
            setBackgroundColor(BG)
        }
        scroll.addView(root, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(Button(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(TEXT_DARK)
            stateListAnimator = null
            background = rounded(BG, BG)
            setOnClickListener { finish() }
        }, LinearLayout.LayoutParams(dp(56), dp(52)))

        header.addView(TextView(this).apply {
            text = "Подписка"
            textSize = 26f
            setTextColor(TEXT_DARK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(View(this), LinearLayout.LayoutParams(dp(56), dp(52)))
        root.addView(header)

        root.addView(centered("Eneida VPN", 14f, TEXT_MUTED, false), lp(2))

        subscriptionStatus = centered("Проверяем подписку…", 16f, TEXT_DARK, true).apply {
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = rounded(CARD, BORDER)
        }
        root.addView(subscriptionStatus, lp(20))

        val plan = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(18))
            background = rounded(CARD, BORDER)
        }
        plan.addView(TextView(this).apply {
            text = "30 дней"
            textSize = 21f
            setTextColor(TEXT_DARK)
            setTypeface(typeface, Typeface.BOLD)
        })
        plan.addView(TextView(this).apply {
            text = "399 ₽"
            textSize = 30f
            setTextColor(PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(8), 0, 0)
        })
        plan.addView(TextView(this).apply {
            text = "Оплата в Monero (XMR). После 1 подтверждения подписка активируется автоматически."
            textSize = 13f
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(plan, lp(14))

        createButton = primaryButton("Получить адрес для оплаты") { createOrder() }
        root.addView(createButton, lp(14))

        paymentCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            background = rounded(CARD, BORDER)
            visibility = View.GONE
        }
        amountText = centered("", 20f, TEXT_DARK, true)
        paymentCard.addView(amountText)

        qrImage = ImageView(this)
        paymentCard.addView(qrImage, LinearLayout.LayoutParams(dp(230), dp(230)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            topMargin = dp(12)
        })

        addressText = centered("", 12f, TEXT_MUTED, false).apply { setTextIsSelectable(true) }
        paymentCard.addView(addressText, lp(10))

        confirmationText = centered("Ожидаем оплату", 14f, TEXT_DARK, true)
        paymentCard.addView(confirmationText, lp(12))

        paymentCard.addView(secondaryButton("Скопировать адрес") {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("Monero address", addressText.text))
            showMessage("Адрес скопирован", false)
        }, lp(12))

        paymentCard.addView(primaryButton("Открыть кошелёк") {
            val value = paymentUri ?: return@primaryButton
            runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(value))) }
                .onFailure { showMessage("Не удалось открыть приложение кошелька", true) }
        }, lp(8))
        root.addView(paymentCard, lp(14))

        message = centered("", 13f, ERROR, true).apply {
            visibility = View.GONE
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = rounded(ERROR_BG, ERROR_BORDER)
        }
        root.addView(message, lp(12))
        root.addView(centered("Eneida 0.7.0", 11f, TEXT_SOFT, false), lp(18))
        return scroll
    }

    private fun refreshSubscription() {
        if (BuildConfig.CONTROL_API_BASE_URL.isBlank()) {
            subscriptionStatus.text = "Сервис оплаты ещё не подключён"
            createButton.isEnabled = false
            showMessage("Нужен HTTPS-адрес Eneida Control API.", true)
            return
        }
        scope.launch {
            runCatching { apiGet("/api/v1/subscriptions/" + deviceId()) }
                .onSuccess { json ->
                    val active = json.optBoolean("active", false)
                    val validUntil = json.optString("valid_until")
                    subscriptionStatus.text = if (active) "Подписка активна до " + formatDate(validUntil) else "Подписка не активна"
                    subscriptionStatus.setTextColor(if (active) SUCCESS else TEXT_DARK)
                }
                .onFailure { subscriptionStatus.text = "Не удалось проверить подписку" }
        }
    }

    private fun createOrder() {
        if (BuildConfig.CONTROL_API_BASE_URL.isBlank()) return
        createButton.isEnabled = false
        showMessage("Создаём платёж…", false)
        scope.launch {
            runCatching {
                apiPost("/api/v1/payments/orders", JSONObject().put("device_id", deviceId()))
            }.onSuccess { json ->
                orderId = json.getString("id")
                paymentUri = json.getString("payment_uri")
                val address = json.getString("address")
                val xmr = json.getString("xmr_amount")
                amountText.text = xmr + " XMR"
                addressText.text = address
                qrImage.setImageBitmap(makeQr(paymentUri.orEmpty(), 800))
                confirmationText.text = "Ожидаем оплату • нужно 1 подтверждение"
                paymentCard.visibility = View.VISIBLE
                showMessage("Адрес создан для этого заказа.", false)
                startPolling()
            }.onFailure {
                createButton.isEnabled = true
                showMessage(it.message ?: "Не удалось создать платёж", true)
            }
        }
    }

    private fun startPolling() {
        pollJob?.cancel()
        val id = orderId ?: return
        pollJob = scope.launch {
            while (isActive) {
                runCatching { apiGet("/api/v1/payments/orders/" + id) }
                    .onSuccess { json ->
                        val status = json.optString("status")
                        val confirmations = json.optInt("confirmations", 0)
                        if (status == "paid") {
                            confirmationText.text = "Оплата подтверждена • подписка активирована"
                            confirmationText.setTextColor(SUCCESS)
                            createButton.isEnabled = true
                            refreshSubscription()
                            cancel()
                        } else if (status == "expired") {
                            confirmationText.text = "Срок платежа истёк"
                            confirmationText.setTextColor(ERROR)
                            createButton.isEnabled = true
                            cancel()
                        } else {
                            confirmationText.text = if (confirmations > 0) "Подтверждений: " + confirmations
                            else "Ожидаем оплату • нужно 1 подтверждение"
                        }
                    }
                delay(5000)
            }
        }
    }

    private suspend fun apiGet(path: String): JSONObject = withContext(Dispatchers.IO) { request("GET", path, null) }
    private suspend fun apiPost(path: String, body: JSONObject): JSONObject = withContext(Dispatchers.IO) { request("POST", path, body.toString()) }

    private fun request(method: String, path: String, body: String?): JSONObject {
        val base = BuildConfig.CONTROL_API_BASE_URL.trimEnd('/')
        val connection = URL(base + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 10000
        connection.readTimeout = 15000
        connection.setRequestProperty("Accept", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = runCatching { JSONObject(text).optString("detail") }.getOrNull()
            throw IllegalStateException(if (detail.isNullOrBlank()) "Ошибка сервера: " + code else detail)
        }
        return JSONObject(text)
    }

    private fun deviceId(): String {
        val prefs = getSharedPreferences("eneida_device", MODE_PRIVATE)
        val current = prefs.getString("device_id", null)
        if (!current.isNullOrBlank()) return current
        val created = UUID.randomUUID().toString()
        prefs.edit().putString("device_id", created).apply()
        return created
    }

    private fun makeQr(value: String, size: Int): Bitmap {
        val matrix = QRCodeWriter().encode(value, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565)
        for (x in 0 until size) for (y in 0 until size) {
            bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
        }
        return bitmap
    }

    private fun formatDate(raw: String): String {
        val p = raw.take(10).split("-")
        return if (p.size == 3) p[2] + "." + p[1] + "." + p[0] else raw
    }

    private fun showMessage(value: String, error: Boolean) {
        message.text = value
        message.setTextColor(if (error) ERROR else TEXT_MUTED)
        message.visibility = if (value.isBlank()) View.GONE else View.VISIBLE
    }

    private fun centered(value: String, size: Float, color: Int, bold: Boolean) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        gravity = Gravity.CENTER
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }

    private fun primaryButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 15f
        setTextColor(Color.WHITE)
        isAllCaps = false
        setTypeface(typeface, Typeface.BOLD)
        stateListAnimator = null
        background = rounded(PRIMARY, PRIMARY)
        setOnClickListener { action() }
    }

    private fun secondaryButton(title: String, action: () -> Unit) = Button(this).apply {
        text = title
        textSize = 14f
        setTextColor(TEXT_DARK)
        isAllCaps = false
        stateListAnimator = null
        background = rounded(Color.WHITE, BORDER)
        setOnClickListener { action() }
    }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(16).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BG = Color.rgb(239, 243, 246)
        private val CARD = Color.rgb(250, 252, 253)
        private val PRIMARY = Color.rgb(46, 98, 138)
        private val BORDER = Color.rgb(199, 211, 219)
        private val TEXT_DARK = Color.rgb(48, 73, 91)
        private val TEXT_MUTED = Color.rgb(105, 124, 138)
        private val TEXT_SOFT = Color.rgb(148, 162, 172)
        private val SUCCESS = Color.rgb(45, 125, 104)
        private val ERROR = Color.rgb(181, 56, 56)
        private val ERROR_BG = Color.rgb(255, 240, 240)
        private val ERROR_BORDER = Color.rgb(245, 190, 190)
    }
}

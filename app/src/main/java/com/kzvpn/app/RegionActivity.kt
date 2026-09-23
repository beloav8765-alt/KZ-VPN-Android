package com.kzvpn.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class RegionActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val api = EneidaApiClient()
    private lateinit var list: LinearLayout
    private lateinit var message: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
        loadRegions()
    }

    override fun onDestroy() {
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
        scroll.addView(root, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

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
            text = "Выбор региона"
            textSize = 25f
            setTextColor(TEXT_DARK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(0, dp(52), 1f))
        header.addView(View(this), LinearLayout.LayoutParams(dp(56), dp(52)))
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Выберите страну или оставьте автоматический выбор"
            textSize = 14f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(6), dp(8), dp(10))
        })

        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(list, lp(10))

        message = TextView(this).apply {
            text = "Загрузка регионов…"
            textSize = 13f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(8), dp(14), dp(8), dp(14))
        }
        root.addView(message, lp(8))
        return scroll
    }

    private fun loadRegions() {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val selected = prefs.getString(KEY_CODE, "auto") ?: "auto"

        list.removeAllViews()
        list.addView(regionButton(
            title = "⚡ Лучший сервер",
            subtitle = "Eneida сама выберет наиболее подходящий сервер",
            selected = selected == "auto"
        ) {
            saveSelection("auto", "Лучший сервер")
        }, lp(6))

        if (!api.enabled) {
            message.text = "Список регионов появится после подключения Eneida Control."
            return
        }

        scope.launch {
            runCatching { api.regions() }
                .onSuccess { regions ->
                    regions.forEach { region ->
                        val title = listOf(region.flag, region.name)
                            .filter { it.isNotBlank() }
                            .joinToString(" ")
                        val subtitle = buildString {
                            append("Серверов: ")
                            append(region.servers)
                            append(" • нагрузка ")
                            append(region.loadPct)
                            append("%")
                        }
                        list.addView(regionButton(
                            title = title.ifBlank { region.name },
                            subtitle = subtitle,
                            selected = selected == region.code
                        ) {
                            saveSelection(region.code, region.name)
                        }, lp(8))
                    }

                    message.text = if (regions.isEmpty()) {
                        "Сейчас нет доступных регионов."
                    } else {
                        "Выбор применяется при следующем подключении."
                    }
                }
                .onFailure {
                    message.text = "Не удалось получить список регионов."
                }
        }
    }

    private fun saveSelection(code: String, name: String) {
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putString(KEY_CODE, code)
            .putString(KEY_NAME, name)
            .apply()
        setResult(RESULT_OK)
        finish()
    }

    private fun regionButton(
        title: String,
        subtitle: String,
        selected: Boolean,
        action: () -> Unit
    ): LinearLayout =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(14), dp(18), dp(14))
            background = rounded(
                if (selected) ACTIVE_BG else CARD,
                if (selected) ACTIVE_BORDER else BORDER
            )
            setOnClickListener { action() }

            addView(TextView(this@RegionActivity).apply {
                text = title
                textSize = 18f
                setTextColor(TEXT_DARK)
                setTypeface(typeface, Typeface.BOLD)
            })
            addView(TextView(this@RegionActivity).apply {
                text = subtitle
                textSize = 12f
                setTextColor(TEXT_MUTED)
                setPadding(0, dp(5), 0, 0)
            })
        }

    private fun rounded(fill: Int, stroke: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(17).toFloat()
        setColor(fill)
        setStroke(dp(1), stroke)
    }

    private fun lp(top: Int = 0) = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT,
        ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { topMargin = dp(top) }

    private fun dp(value: Int) =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        const val PREFS = "eneida_region"
        const val KEY_CODE = "selected_region_code"
        const val KEY_NAME = "selected_region_name"

        private val BG = Color.rgb(239, 243, 246)
        private val CARD = Color.rgb(250, 252, 253)
        private val BORDER = Color.rgb(199, 211, 219)
        private val ACTIVE_BG = Color.rgb(235, 246, 242)
        private val ACTIVE_BORDER = Color.rgb(171, 210, 198)
        private val TEXT_DARK = Color.rgb(48, 73, 91)
        private val TEXT_MUTED = Color.rgb(105, 124, 138)
    }
}

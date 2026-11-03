package com.kzvpn.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.util.TypedValue
import android.text.TextUtils
import android.database.Cursor
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ServersActivity : Activity() {
    private val controller: VpnController
        get() = (application as KzVpnApp).vpnController

    private val uiScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var listContainer: LinearLayout
    private lateinit var message: TextView
    private var lastSignature: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        EneidaTheme.prepareWindow(this)
        setContentView(buildUi())

        uiScope.launch {
            controller.state.collectLatest { state ->
                render(state)
            }
        }
    }

    override fun onDestroy() {
        uiScope.cancel()
        super.onDestroy()
    }

    private fun buildUi(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BG)
        }

        EneidaTheme.applyInsets(scroll)
        val frame = FrameLayout(this)
        scroll.addView(frame, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        val root = EneidaTheme.column(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(BG)
        }
        frame.addView(root, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = Button(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(TEXT_PRIMARY)
            background = EneidaTheme.interactiveSurface(this@ServersActivity, BG, BG, 10)
            contentDescription = "Назад"
            stateListAnimator = null
            backgroundTintList = null
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(56), dp(52)))

        val title = TextView(this).apply {
            text = "Серверы"
            textSize = 26f
            minimumHeight = dp(52)
            setSingleLine(true)
            setAutoSizeTextTypeUniformWithConfiguration(16, 26, 1, TypedValue.COMPLEX_UNIT_SP)
            setTextColor(TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        header.addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        header.addView(View(this), LinearLayout.LayoutParams(dp(56), dp(52)))
        root.addView(header)

        root.addView(TextView(this).apply {
            text = "Добавляйте серверы и переключайтесь между ними одним нажатием"
            textSize = 14f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(8), dp(10), dp(8))
        })

        val addButton = Button(this).apply {
            text = "+ Добавить сервер"
            textSize = 16f
            setTextColor(EneidaTheme.ON_ACCENT)
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            backgroundTintList = null
            background = EneidaTheme.interactiveSurface(this@ServersActivity, PRIMARY, PRIMARY, 16, EneidaTheme.ON_ACCENT)
            minimumHeight = dp(52)
            setOnClickListener { openConfigPicker() }
        }
        root.addView(addButton, lp(top = 18))

        listContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        root.addView(listContainer, lp(top = 14))

        message = TextView(this).apply {
            textSize = 13f
            setTextColor(TEXT_MUTED)
            gravity = Gravity.CENTER
            visibility = View.GONE
            setPadding(dp(10), dp(12), dp(10), dp(12))
        }
        root.addView(message, lp(top = 8))

        return scroll
    }

    private fun render(state: VpnController.UiState) {
        val signature = buildString {
            append(state.activeServerId)
            append('|')
            append(state.connectionStatus.name)
            state.servers.forEach {
                append('|')
                append(it.id)
                append(':')
                append(it.name)
                append(':')
                append(it.endpoint)
            }
        }

        if (signature != lastSignature) {
            lastSignature = signature
            listContainer.removeAllViews()

            if (state.servers.isEmpty()) {
                listContainer.addView(TextView(this).apply {
                    text = "Пока нет серверов.\nДобавьте первый WireGuard .conf."
                    textSize = 15f
                    setTextColor(TEXT_MUTED)
                    gravity = Gravity.CENTER
                    setPadding(0, dp(30), 0, dp(30))
                }, lp())
            } else {
                state.servers.forEach { server ->
                    listContainer.addView(
                        serverCard(
                            server = server,
                            active = server.id == state.activeServerId,
                            switching = state.connectionStatus == VpnController.ConnectionStatus.CONNECTING ||
                                state.connectionStatus == VpnController.ConnectionStatus.DISCONNECTING
                        ),
                        lp(top = 10)
                    )
                }
            }
        }

        val msg = state.message.orEmpty()
        message.text = msg
        message.visibility = if (msg.isBlank()) View.GONE else View.VISIBLE
    }

    private fun serverCard(
        server: VpnController.ServerSummary,
        active: Boolean,
        switching: Boolean
    ): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = roundedDrawable(
                if (active) ACTIVE_BG else CARD,
                if (active) ACTIVE_BORDER else BORDER,
                dp(18).toFloat()
            )
        }

        val nameRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val name = TextView(this).apply {
            text = server.name
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            textSize = 18f
            setTextColor(TEXT_PRIMARY)
            setTypeface(typeface, Typeface.BOLD)
        }
        nameRow.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val badge = TextView(this).apply {
            text = if (active) "ВЫБРАН" else ""
            textSize = 10f
            setTextColor(SUCCESS)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.END
        }
        nameRow.addView(badge)
        card.addView(nameRow)

        card.addView(TextView(this).apply {
            text = server.endpoint.ifBlank { "WireGuard" }
            textSize = 12f
            setTextColor(TEXT_MUTED)
            setPadding(0, dp(5), 0, 0)
        })

        val stackActions = resources.configuration.fontScale >= 1.3f || resources.configuration.screenWidthDp < 380
        val actions = LinearLayout(this).apply {
            orientation = if (stackActions) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun actionParams(spaced: Boolean = false) =
            LinearLayout.LayoutParams(if (stackActions) ViewGroup.LayoutParams.MATCH_PARENT else 0,
                ViewGroup.LayoutParams.WRAP_CONTENT, if (stackActions) 0f else 1f).apply {
                if (spaced) {
                    if (stackActions) topMargin = dp(8) else marginStart = dp(8)
                }
            }

        val use = smallButton(if (active) "Выбран" else "Использовать") {
            if (!active && !switching) controller.selectServer(server.id)
        }.apply {
            isEnabled = !active && !switching
        }
        actions.addView(use, actionParams())

        val rename = smallButton("Переименовать") {
            showRenameDialog(server)
        }
        actions.addView(rename, actionParams(true))

        val delete = smallButton("Удалить") {
            showDeleteDialog(server)
        }
        actions.addView(delete, actionParams(true))

        card.addView(actions, lp(top = 12))
        return card
    }

    private fun smallButton(title: String, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 11f
            setTextColor(TEXT_PRIMARY)
            isAllCaps = false
            stateListAnimator = null
            backgroundTintList = null
            background = EneidaTheme.interactiveSurface(this@ServersActivity, EneidaTheme.SURFACE_RAISED, BORDER, 12)
            minimumHeight = dp(50)
            setTextColor(ColorStateList(arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(TEXT_MUTED, TEXT_PRIMARY)))
            setPadding(dp(6), dp(8), dp(6), dp(8))
            setOnClickListener { action() }
        }

    private fun showRenameDialog(server: VpnController.ServerSummary) {
        val input = EditText(this).apply {
            setText(server.name)
            setSelection(text.length)
            setSingleLine(true)
            setPadding(dp(18), dp(10), dp(18), dp(10))
        }

        AlertDialog.Builder(this)
            .setTitle("Название сервера")
            .setView(input)
            .setPositiveButton("Сохранить") { _, _ ->
                controller.renameServer(server.id, input.text.toString())
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showDeleteDialog(server: VpnController.ServerSummary) {
        AlertDialog.Builder(this)
            .setTitle("Удалить сервер?")
            .setMessage(server.name)
            .setPositiveButton("Удалить") { _, _ ->
                controller.removeServer(server.id)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun openConfigPicker() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            },
            REQ_CONFIG
        )
    }

    @Deprecated("Deprecated in Android API, kept intentionally for a minimal no-AndroidX UI build")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_CONFIG || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val fileName = getDisplayName(uri)
        runCatching { contentResolver.openInputStream(uri) }
            .getOrNull()
            ?.let { controller.importConfig(it, fileName) }
            ?: controller.setMessage("Не удалось открыть выбранный файл")
    }

    private fun getDisplayName(uri: android.net.Uri): String? {
        var cursor: Cursor? = null
        return try {
            cursor = contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            if (cursor != null && cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) cursor.getString(index) else null
            } else null
        } catch (_: Exception) {
            null
        } finally {
            cursor?.close()
        }
    }

    private fun lp(top: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(top)
        }

    private fun roundedDrawable(fill: Int, stroke: Int, radius: Float): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(fill)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    companion object {
        private const val REQ_CONFIG = 2001

        private val BG = EneidaTheme.BACKGROUND
        private val CARD = EneidaTheme.SURFACE
        private val PRIMARY = EneidaTheme.ACCENT
        private val BORDER = EneidaTheme.BORDER
        private val ACTIVE_BG = EneidaTheme.ACCENT_SURFACE
        private val ACTIVE_BORDER = EneidaTheme.ACCENT
        private val TEXT_PRIMARY = EneidaTheme.TEXT_PRIMARY
        private val TEXT_MUTED = EneidaTheme.TEXT_MUTED
        private val SUCCESS = EneidaTheme.ACCENT
    }
}

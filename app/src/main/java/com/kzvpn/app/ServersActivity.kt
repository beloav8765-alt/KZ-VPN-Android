package com.kzvpn.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
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

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(28))
            setBackgroundColor(BG)
        }
        scroll.addView(
            root,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val back = Button(this).apply {
            text = "‹"
            textSize = 30f
            setTextColor(TEXT_DARK)
            background = roundedDrawable(BG, BG, dp(10).toFloat())
            stateListAnimator = null
            setOnClickListener { finish() }
        }
        header.addView(back, LinearLayout.LayoutParams(dp(56), dp(52)))

        val title = TextView(this).apply {
            text = "Серверы"
            textSize = 26f
            setTextColor(TEXT_DARK)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
        }
        header.addView(title, LinearLayout.LayoutParams(0, dp(52), 1f))
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
            setTextColor(Color.WHITE)
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            stateListAnimator = null
            background = roundedDrawable(PRIMARY, PRIMARY, dp(16).toFloat())
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
            textSize = 18f
            setTextColor(TEXT_DARK)
            setTypeface(typeface, Typeface.BOLD)
        }
        nameRow.addView(name, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        val badge = TextView(this).apply {
            text = if (active) "АКТИВЕН" else ""
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

        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val use = smallButton(if (active) "Выбран" else "Использовать") {
            if (!active && !switching) controller.selectServer(server.id)
        }.apply {
            isEnabled = !active && !switching
        }
        actions.addView(use, LinearLayout.LayoutParams(0, dp(50), 1f))

        val rename = smallButton("Переименовать") {
            showRenameDialog(server)
        }
        actions.addView(rename, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
        })

        val delete = smallButton("Удалить") {
            showDeleteDialog(server)
        }
        actions.addView(delete, LinearLayout.LayoutParams(0, dp(50), 1f).apply {
            marginStart = dp(8)
        })

        card.addView(actions, lp(top = 12))
        return card
    }

    private fun smallButton(title: String, action: () -> Unit): Button =
        Button(this).apply {
            text = title
            textSize = 11f
            setTextColor(TEXT_DARK)
            isAllCaps = false
            stateListAnimator = null
            background = roundedDrawable(Color.WHITE, BORDER, dp(12).toFloat())
            setPadding(dp(4), 0, dp(4), 0)
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

        private val BG = Color.rgb(239, 243, 246)
        private val CARD = Color.rgb(250, 252, 253)
        private val PRIMARY = Color.rgb(46, 98, 138)
        private val BORDER = Color.rgb(199, 211, 219)
        private val ACTIVE_BG = Color.rgb(235, 246, 242)
        private val ACTIVE_BORDER = Color.rgb(171, 210, 198)
        private val TEXT_DARK = Color.rgb(48, 73, 91)
        private val TEXT_MUTED = Color.rgb(105, 124, 138)
        private val SUCCESS = Color.rgb(45, 125, 104)
    }
}

package com.resker666.yeshunguangpet

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.roundToInt

internal class MainActivity : Activity() {
    private val app get() = application as PetApplication
    private val store get() = app.store
    private val overlayIntent by lazy {
        Intent(this, PetOverlayService::class.java).setAction(PetOverlayService.START)
    }
    private lateinit var preview: PetSpriteView
    private lateinit var heroName: TextView
    private lateinit var heroCaption: TextView
    private lateinit var overlayState: TextView
    private lateinit var overlayButton: TextView
    private lateinit var recallButton: TextView
    private lateinit var gestureHint: TextView
    private lateinit var importButton: TextView
    private lateinit var message: TextView
    private lateinit var library: LinearLayout
    private var generation = 0
    private var importing = false
    private var startOnResume = false
    private var skins: List<Skin> = emptyList()
    private var selection: String? = null
    private val overlayChanged: () -> Unit = { updateOverlayState() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        startOnResume = savedInstanceState?.getBoolean("startOnResume") ?: false
        buildScreen()
    }

    override fun onStart() {
        super.onStart()
        app.overlayListeners.add(overlayChanged)
        reload()
    }

    override fun onPostResume() {
        super.onPostResume()
        if (!Settings.canDrawOverlays(this) && app.overlayStatus.active) {
            app.overlayStatus = OverlayStatus.NEEDS_PERMISSION
            stopService(overlayIntent)
        }
        updateOverlayState()
        runPendingStart()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) runPendingStart()
    }

    private fun runPendingStart() {
        if (!startOnResume || isFinishing || isDestroyed) return
        startOnResume = false
        startOverlay()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("startOnResume", startOnResume)
        super.onSaveInstanceState(outState)
    }

    override fun onStop() {
        generation++
        app.overlayListeners.remove(overlayChanged)
        preview.show(null)
        super.onStop()
    }

    private fun buildScreen() {
        val scroll = ScrollView(this).apply { isFillViewport = true; setBackgroundColor(BACKGROUND) }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(28))
        }
        scroll.addView(body)
        applySystemInsets(scroll)
        setContentView(scroll)

        body.append(text(getString(R.string.app_name), 27, INK, bold = true))
        body.append(text("在屏幕一角，陪你待一会儿。", 14, MUTED), 6)

        val hero = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(12), dp(20), dp(22))
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR, intArrayOf(0xFF203A46.toInt(), 0xFF172333.toInt()),
            ).apply {
                cornerRadius = dp(24).toFloat()
                setStroke(dp(1), BORDER)
            }
        }
        body.append(hero, 24)
        preview = PetSpriteView(this).apply {
            contentDescription = "点击角色播放互动动画"
            setOnClickListener {
                val line = react()
                if (line != null) heroCaption.text = line
            }
        }
        hero.addView(preview, LinearLayout.LayoutParams(dp(168), dp(150)))
        heroName = text("正在加载角色", 23, INK, bold = true).apply { gravity = Gravity.CENTER }
        heroCaption = text("点一下角色，试试互动", 13, MUTED).apply { gravity = Gravity.CENTER }
        hero.append(heroName, 1)
        hero.append(heroCaption, 7)

        val overlayCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(CARD, 18, BORDER)
        }
        body.append(overlayCard, 16)
        overlayState = text("", 14, BRIGHT_MUTED, bold = true)
        overlayCard.append(overlayState)
        overlayButton = button("", primary = true) { toggleOverlay() }
        overlayCard.append(overlayButton, 11)
        overlayCard.append(buildSizeControl(), 15)
        recallButton = button("找回角色", primary = false) {
            if (app.overlayStatus == OverlayStatus.RUNNING) {
                startService(Intent(overlayIntent).setAction(PetOverlayService.RECALL))
            }
        }
        overlayCard.append(recallButton, 8)
        gestureHint = text("轻点角色互动，长按可收起", 12, MUTED).apply { gravity = Gravity.CENTER }
        overlayCard.append(gestureHint, 8)
        updateOverlayState()

        val skinHeading = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        body.append(skinHeading, 24)
        skinHeading.addView(text("皮肤库", 21, INK, bold = true), LinearLayout.LayoutParams(0, -2, 1f))
        importButton = button("导入 ZIP", primary = false) { openPicker() }
        skinHeading.addView(importButton, LinearLayout.LayoutParams(-2, -2))
        library = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.append(library, 9)
        message = text("", 13, BRIGHT_MUTED).apply {
            visibility = View.GONE
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        body.append(message, 12)
    }

    private fun buildSizeControl(): LinearLayout {
        val initial = store.overlaySizePercent
        val value = text(getString(R.string.pet_size_value, initial), 14, TEAL, bold = true).apply {
            id = R.id.pet_size_value
        }
        val title = text(getString(R.string.pet_size), 14, BRIGHT_MUTED, bold = true).apply {
            labelFor = R.id.pet_size_slider
        }
        val labels = LinearLayout(this).apply {
            addView(title, LinearLayout.LayoutParams(0, -2, 1f))
            addView(value, LinearLayout.LayoutParams(-2, -2))
        }
        val slider = SeekBar(this).apply {
            id = R.id.pet_size_slider
            max = (SkinStore.MAX_OVERLAY_SIZE - SkinStore.MIN_OVERLAY_SIZE) / SIZE_STEP
            progress = (initial - SkinStore.MIN_OVERLAY_SIZE) / SIZE_STEP
            keyProgressIncrement = 1
            progressTintList = ColorStateList.valueOf(TEAL)
            thumbTintList = ColorStateList.valueOf(TEAL)
            if (Build.VERSION.SDK_INT >= 30) stateDescription = value.text
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val percent = SkinStore.MIN_OVERLAY_SIZE + progress * SIZE_STEP
                    value.text = getString(R.string.pet_size_value, percent)
                    if (Build.VERSION.SDK_INT >= 30) seekBar.stateDescription = value.text
                    store.overlaySizePercent = percent
                }

                override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
                override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
            })
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            append(labels)
            addView(slider, LinearLayout.LayoutParams(-1, dp(48)))
        }
    }

    private fun reload() {
        val ticket = ++generation
        app.worker.execute {
            val result = runCatching { store.list() to store.selectedSprite() }
            runOnUiThread {
                if (isFinishing || isDestroyed || ticket != generation) return@runOnUiThread
                result.onSuccess { (installed, sprite) ->
                    skins = installed
                    selection = sprite.skin.manifest.id
                    preview.show(sprite)
                    heroName.text = sprite.skin.manifest.name
                    heroCaption.text = sprite.skin.manifest.description?.takeUnless(String::isBlank)
                        ?: "点一下角色，试试互动"
                    renderSkins()
                }.onFailure { showMessage("读取皮肤失败，请重新打开应用。") }
            }
        }
    }

    private fun renderSkins() {
        library.removeAllViews()
        for (skin in skins) {
            val selected = selection == skin.manifest.id
            val row = LinearLayout(this).apply {
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(15), dp(13), dp(15), dp(13))
                background = ripple(if (selected) SELECTED else CARD, 15, if (selected) TEAL else BORDER)
                isSelected = selected
                setOnClickListener { select(skin) }
            }
            val labels = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            labels.append(text(skin.manifest.name, 16, INK, bold = true))
            labels.append(text(if (skin.directory == null) "内置示例" else "已导入的皮肤", 12, MUTED), 3)
            row.addView(labels, LinearLayout.LayoutParams(0, -2, 1f))
            row.addView(text(if (selected) "使用中" else "使用", 13, TEAL, bold = true))
            library.append(row, 7)
        }
    }

    private fun select(skin: Skin) {
        if (skin.manifest.id == selection) return
        store.select(skin)
        reload()
    }

    private fun toggleOverlay() {
        if (app.overlayStatus.active) {
            stopService(overlayIntent)
            return
        }
        if (Settings.canDrawOverlays(this)) {
            requestNotificationsOrStart()
            return
        }
        AlertDialog.Builder(this)
            .setTitle("允许显示悬浮宠物")
            .setMessage("在系统设置中允许「叶瞬光口袋宠物」显示在其他应用上层，返回后即可开启。")
            .setNegativeButton("取消", null)
            .setPositiveButton("打开设置") { _, _ ->
                try {
                    startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:$packageName")), OVERLAY_PERMISSION)
                } catch (error: ActivityNotFoundException) {
                    showMessage("这台设备无法打开悬浮窗权限设置。")
                }
            }.show()
    }

    private fun requestNotificationsOrStart() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !store.prefs.getBoolean("askedNotifications", false)) {
            store.prefs.edit().putBoolean("askedNotifications", true).apply()
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), NOTIFICATION_PERMISSION)
        } else {
            startOverlay()
        }
    }

    private fun startOverlay() {
        if (app.overlayStatus.active) return
        if (!Settings.canDrawOverlays(this)) {
            app.overlayStatus = OverlayStatus.NEEDS_PERMISSION
            return
        }
        app.overlayStatus = OverlayStatus.STARTING
        try {
            startForegroundService(overlayIntent)
        } catch (_: IllegalStateException) {
            app.overlayStatus = OverlayStatus.WINDOW_ERROR
        } catch (_: SecurityException) {
            app.overlayStatus = OverlayStatus.WINDOW_ERROR
        }
    }

    private fun updateOverlayState() {
        val status = app.overlayStatus
        overlayState.text = when (status) {
            OverlayStatus.STOPPED -> if (Settings.canDrawOverlays(this)) {
                "已授权，点击下方按钮显示宠物"
            } else "切到其他应用时也能陪你"
            OverlayStatus.STARTING -> "正在显示宠物…"
            OverlayStatus.RUNNING -> "悬浮已开启"
            OverlayStatus.NEEDS_PERMISSION -> "请在系统设置中允许显示悬浮窗"
            OverlayStatus.WINDOW_ERROR -> "悬浮窗未能显示，请在此页重新开启"
            OverlayStatus.SPRITE_ERROR -> "当前皮肤无法显示，请在皮肤库切换角色"
        }
        overlayButton.text = when (status) {
            OverlayStatus.RUNNING -> "收起悬浮宠物"
            OverlayStatus.STARTING -> "取消开启"
            else -> "开启悬浮宠物"
        }
        val running = status == OverlayStatus.RUNNING
        recallButton.visibility = if (running) View.VISIBLE else View.GONE
        gestureHint.visibility = if (running) View.VISIBLE else View.GONE
    }

    private fun openPicker() {
        try {
            startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf(
                    "application/zip", "application/x-zip-compressed", "application/octet-stream",
                ))
            }, IMPORT_SKIN)
        } catch (error: ActivityNotFoundException) {
            showMessage("这台设备没有可用的文件选择器。")
        }
    }

    private fun importSkin(intent: Intent) {
        val uri = intent.data ?: return
        if (importing) return
        importing = true
        importButton.isEnabled = false
        importButton.alpha = .65f
        showMessage("正在检查皮肤包…")
        app.worker.execute {
            val result = runCatching { store.import(uri).also { store.select(it.skin) } }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                importing = false
                importButton.isEnabled = true
                importButton.alpha = 1f
                result.onSuccess {
                    showMessage(if (it.alreadyInstalled) "已切换到已导入的皮肤" else "已导入「${it.skin.manifest.name}」")
                    reload()
                }.onFailure { failure ->
                    showMessage((failure as? SkinFormatException)?.message ?: "无法读取这个 ZIP 皮肤包。")
                }
            }
        }
    }

    @Deprecated("Android framework callback")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            OVERLAY_PERMISSION -> {
                if (Settings.canDrawOverlays(this)) {
                    window.decorView.post { requestNotificationsOrStart() }
                } else showMessage("悬浮窗权限尚未开启，仍可在这里预览角色。")
            }
            IMPORT_SKIN -> if (resultCode == RESULT_OK && data != null) importSkin(data)
        }
    }

    @Deprecated("Android framework callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, results: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, results)
        if (requestCode == NOTIFICATION_PERMISSION) {
            startOnResume = true
            window.decorView.post {
                if (hasWindowFocus()) runPendingStart()
            }
        }
    }

    private fun showMessage(value: String) {
        message.text = value
        message.visibility = View.VISIBLE
    }

    @Suppress("DEPRECATION")
    private fun applySystemInsets(view: View) {
        if (Build.VERSION.SDK_INT < 35) {
            window.statusBarColor = BACKGROUND
            window.navigationBarColor = BACKGROUND
            return
        }
        view.setOnApplyWindowInsetsListener { target, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            target.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun text(value: String, size: Int, color: Int, bold: Boolean = false): TextView =
        TextView(this).apply {
            text = value
            textSize = size.toFloat()
            setTextColor(color)
            if (bold) setTypeface(typeface, Typeface.BOLD)
        }

    private fun button(value: String, primary: Boolean, click: () -> Unit): TextView =
        text(value, 15, if (primary) BUTTON_INK else TEAL, bold = true).apply {
            gravity = Gravity.CENTER
            minHeight = dp(48)
            setPadding(dp(16), dp(9), dp(16), dp(9))
            background = ripple(if (primary) TEAL else SELECTED, 12)
            setOnClickListener { click() }
            isFocusable = true
        }

    private fun rounded(fill: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun ripple(fill: Int, radius: Int, stroke: Int? = null): RippleDrawable =
        RippleDrawable(android.content.res.ColorStateList.valueOf(0x37FFFFFF),
            rounded(fill, radius, stroke), rounded(Color.WHITE, radius))

    private fun LinearLayout.append(view: View, top: Int = 0) {
        addView(view, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top) })
    }

    companion object {
        private const val IMPORT_SKIN = 10
        private const val OVERLAY_PERMISSION = 11
        private const val NOTIFICATION_PERMISSION = 12
        private const val SIZE_STEP = 5
        private val BACKGROUND = 0xFF101722.toInt()
        private val CARD = 0xFF182332.toInt()
        private val SELECTED = 0xFF223940.toInt()
        private val BORDER = 0xFF2C4152.toInt()
        private val INK = 0xFFF1F5FA.toInt()
        private val MUTED = 0xFFADBBD0.toInt()
        private val BRIGHT_MUTED = 0xFFCFD9E7.toInt()
        private val TEAL = 0xFF92EAD3.toInt()
        private val BUTTON_INK = 0xFF112B29.toInt()
    }
}

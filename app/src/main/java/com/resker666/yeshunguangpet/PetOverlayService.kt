package com.resker666.yeshunguangpet

import android.app.AppOpsManager
import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ServiceInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.hardware.display.DisplayManager
import android.hardware.input.InputManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.roundToInt

internal class PetOverlayService : Service() {
    private val app get() = application as PetApplication
    private val store get() = app.store
    private lateinit var manager: WindowManager
    private lateinit var overlayContext: Context
    private lateinit var layout: WindowManager.LayoutParams
    private var root: FrameLayout? = null
    private var pet: PetSpriteView? = null
    private var petMenu: LinearLayout? = null
    private var bubble: TextView? = null
    private var watching = false
    private var generation = 0
    private var destroyed = false
    private var foreground = false
    private var screenReceiverRegistered = false
    private lateinit var mainHandler: Handler
    private val preferencesListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (destroyed) return@OnSharedPreferenceChangeListener
        when (key) {
            SkinStore.SELECTED_ID -> loadSprite()
            SkinStore.OVERLAY_SIZE_PERCENT -> resizeOverlay()
        }
    }
    private val overlayModeChanged: () -> Unit = {
        if (root != null && applyInputMode()) updateWindow()
    }
    private val passthroughAlpha: Float by lazy {
        if (Build.VERSION.SDK_INT >= 31) {
            minOf(0.8f, getSystemService(InputManager::class.java).maximumObscuringOpacityForTouch)
        } else 0.8f
    }
    private val clearBubble = Runnable { bubble?.visibility = View.INVISIBLE }
    private val dismissMenu = Runnable { petMenu?.visibility = View.GONE }
    private val firstFrameTimeout = Runnable {
        if (app.overlayStatus == OverlayStatus.STARTING && root?.visibility == View.VISIBLE) {
            Log.w(TAG, "Pet overlay did not draw its first frame")
            fail(OverlayStatus.WINDOW_ERROR)
        }
    }
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, _ ->
        mainHandler.post {
            if (!destroyed && !Settings.canDrawOverlays(this)) fail(OverlayStatus.NEEDS_PERMISSION)
        }
    }
    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val unlocked = !getSystemService(KeyguardManager::class.java).isDeviceLocked
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    dismissMenu.run()
                    root?.visibility = View.INVISIBLE
                }
                Intent.ACTION_USER_PRESENT -> updatePositionAndShow()
                Intent.ACTION_SCREEN_ON -> if (unlocked) updatePositionAndShow()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(mainLooper)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            STOP, null -> stopSelf()
            RECALL -> recall()
            RESTORE_INTERACTION -> restoreInteraction()
            START -> startOverlay()
        }
        return START_NOT_STICKY
    }

    private fun startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            fail(OverlayStatus.NEEDS_PERMISSION)
            return
        }
        if (foreground) return
        app.overlayStatus = OverlayStatus.STARTING
        try {
            val notification = buildNotification()
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foreground = true
            loadSprite()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not start pet overlay", failure)
            fail(OverlayStatus.WINDOW_ERROR)
        }
    }

    private fun fail(status: OverlayStatus) {
        app.overlayStatus = status
        stopSelf()
    }

    @Suppress("RtlHardcoded")
    private fun attachWindow(asset: Sprite) {
        overlayContext = if (Build.VERSION.SDK_INT >= 30) {
            val displayManager = getSystemService(DisplayManager::class.java)
            val display = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY)) {
                "Primary display is unavailable"
            }
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        manager = overlayContext.getSystemService(WindowManager::class.java)

        layout = WindowManager.LayoutParams(
            0, 0,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            setTitle("PocketPetOverlay")
        }

        if (Build.VERSION.SDK_INT >= 30) layout.setFitInsetsTypes(0)

        val panel = FrameLayout(overlayContext)
        val sprite = PetSpriteView(overlayContext).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = getString(R.string.app_name)
            setOnClickListener {
                dismissMenu.run()
                showLine(react())
            }
            setOnLongClickListener { showPetMenu() }
        }
        panel.addView(sprite, FrameLayout.LayoutParams(0, 0, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        installGestures(sprite)

        val dialogue = TextView(overlayContext).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
            maxLines = 2
            setPadding(dp(5), dp(3), dp(5), dp(3))
            background = rounded(0xEF25364B.toInt(), dp(11).toFloat())
            visibility = View.INVISIBLE
        }
        panel.addView(dialogue, FrameLayout.LayoutParams(dp(94), dp(38), Gravity.TOP or Gravity.LEFT))

        val menu = LinearLayout(overlayContext).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            addView(menuButton(R.string.enable_passthrough, 0xFF25524A.toInt(), ::enablePassthrough),
                LinearLayout.LayoutParams(-1, dp(44)))
            addView(menuButton(R.string.stop_pet, 0xFF263A52.toInt()) { stopSelf() },
                LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(4) })
        }
        panel.addView(menu, FrameLayout.LayoutParams(dp(112), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (!interactive || locked) panel.visibility = View.INVISIBLE
        sprite.onFirstFrame = {
            if (!destroyed && app.overlayStatus == OverlayStatus.STARTING) {
                mainHandler.removeCallbacks(firstFrameTimeout)
                app.overlayStatus = OverlayStatus.RUNNING
            }
        }
        sprite.show(asset)
        sprite.contentDescription = asset.skin.manifest.name
        applySavedLayout(sprite)
        applyInputMode()
        manager.addView(panel, layout)
        root = panel
        pet = sprite
        bubble = dialogue
        petMenu = menu
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenChanges, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenChanges, filter)
        screenReceiverRegistered = true
        getSystemService(AppOpsManager::class.java)
            .startWatchingMode(AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW, packageName, permissionListener)
        watching = true
        store.prefs.registerOnSharedPreferenceChangeListener(preferencesListener)
        app.overlayListeners.add(overlayModeChanged)
    }

    private fun loadSprite() {
        val ticket = ++generation
        app.worker.execute {
            val result = runCatching { store.selectedSprite() }
            mainHandler.post {
                if (destroyed || ticket != generation) return@post
                result.onSuccess { asset ->
                    try {
                        val sprite = pet
                        if (sprite == null) {
                            attachWindow(asset)
                        } else {
                            sprite.show(asset)
                            sprite.contentDescription = asset.skin.manifest.name
                            resizeOverlay()
                        }
                        scheduleFirstFrameTimeout()
                    } catch (failure: RuntimeException) {
                        Log.w(TAG, "Could not attach pet overlay to the display", failure)
                        fail(OverlayStatus.WINDOW_ERROR)
                    }
                }.onFailure { error ->
                    Log.w(TAG, "Could not load pet sprite", error)
                    fail(OverlayStatus.SPRITE_ERROR)
                }
            }
        }
    }

    private fun scheduleFirstFrameTimeout() {
        if (app.overlayStatus != OverlayStatus.STARTING || root?.visibility != View.VISIBLE) return
        mainHandler.removeCallbacks(firstFrameTimeout)
        mainHandler.postDelayed(firstFrameTimeout, 6_000)
    }

    private fun recall() {
        if (root == null) {
            if (!foreground) stopSelf()
            return
        }
        val area = bounds()
        val x = area.centerX() - layout.width / 2
        val y = area.centerY() - layout.height / 2
        setPosition(x, y)
        savePosition()
    }

    @Suppress("ClickableViewAccessibility")
    private fun installGestures(sprite: PetSpriteView) {
        val slop = ViewConfiguration.get(overlayContext).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        var previousX = 0f
        var dragging = false
        var longPressed = false
        val longPress = Runnable {
            if (!dragging && sprite.isAttachedToWindow && sprite.isShown) {
                longPressed = sprite.performLongClick()
            }
        }
        sprite.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = layout.x
                    initialY = layout.y
                    previousX = startX
                    dragging = false
                    longPressed = false
                    sprite.removeCallbacks(longPress)
                    sprite.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (!longPressed && (dragging || abs(dx) > slop || abs(dy) > slop)) {
                        sprite.removeCallbacks(longPress)
                        if (!dragging) dismissMenu.run()
                        dragging = true
                        if (event.rawX != previousX) sprite.drag(event.rawX - previousX)
                        previousX = event.rawX
                        setPosition(initialX + dx.roundToInt(), initialY + dy.roundToInt())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    sprite.removeCallbacks(longPress)
                    if (dragging) {
                        sprite.idle()
                        savePosition()
                    } else if (!longPressed && event.actionMasked == MotionEvent.ACTION_UP) {
                        view.performClick()
                    }
                    true
                }
                else -> true
            }
        }
    }

    private fun menuButton(label: Int, color: Int, action: () -> Unit) = Button(overlayContext).apply {
        text = getString(label)
        setTextColor(Color.WHITE)
        textSize = 13f
        gravity = Gravity.CENTER
        minHeight = 0
        minimumHeight = 0
        setPadding(dp(10), 0, dp(10), 0)
        background = RippleDrawable(
            ColorStateList.valueOf(0x26FFFFFF),
            rounded(color, dp(11).toFloat()),
            null,
        )
        backgroundTintList = null
        setOnClickListener { action() }
    }

    private fun enablePassthrough() {
        if (app.overlayStatus != OverlayStatus.RUNNING) return
        dismissMenu.run()
        clearBubble.run()
        changeRunningMode(OverlayStatus.PASSTHROUGH)
    }

    private fun restoreInteraction() {
        if (app.overlayStatus == OverlayStatus.PASSTHROUGH) {
            changeRunningMode(OverlayStatus.RUNNING)
        } else if (!foreground) {
            stopSelf()
        }
    }

    private fun changeRunningMode(status: OverlayStatus) {
        app.overlayStatus = status
        if (app.overlayStatus == status) {
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, buildNotification())
        }
    }

    private fun showPetMenu(): Boolean {
        val menu = petMenu ?: return false
        clearBubble.run()
        menu.visibility = View.VISIBLE
        mainHandler.removeCallbacks(dismissMenu)
        mainHandler.postDelayed(dismissMenu, 6_000)
        return true
    }

    private fun showLine(line: String?) {
        val view = bubble ?: return
        if (line == null) return
        view.text = line
        view.visibility = View.VISIBLE
        view.removeCallbacks(clearBubble)
        view.postDelayed(clearBubble, 1800)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        root?.post { resizeOverlay() }
    }

    @Suppress("DEPRECATION")
    private fun bounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = manager.currentWindowMetrics
            val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Rect(insets.left, insets.top, metrics.bounds.width() - insets.right,
                metrics.bounds.height() - insets.bottom)
        }
        val size = Point()
        manager.defaultDisplay.getSize(size)
        return Rect(0, dp(28), size.x, size.y)
    }

    private fun resizeOverlay() {
        applySavedLayout(pet ?: return)
        updateWindow()
    }

    private fun applySavedLayout(sprite: PetSpriteView) {
        val area = bounds()
        val widthLimit = area.width().coerceAtLeast(1)
        val heightLimit = area.height().coerceAtLeast(1)
        val sideSpace = dp(10).coerceAtMost(widthLimit - 1)
        val topSpace = dp(32).coerceAtMost(heightLimit - 1)
        val baseWidth = dp(122)
        val baseHeight = dp(120)
        val requestedScale = store.overlaySizePercent / 100f
        val frameWidth = widthLimit - sideSpace
        val frameHeight = heightLimit - topSpace
        val idleBounds = checkNotNull(sprite.idleBounds)
        val baseSpriteScale = minOf(baseWidth.toFloat() / sprite.cellWidth, baseHeight.toFloat() / sprite.cellHeight)
        val sizeMultiplier = minOf(
            requestedScale,
            frameWidth / (idleBounds.width() * baseSpriteScale),
            frameHeight / (idleBounds.height() * baseSpriteScale),
        )
        val nominalWidth = (baseWidth * sizeMultiplier).roundToInt().coerceAtLeast(1)
        val nominalHeight = (baseHeight * sizeMultiplier).roundToInt().coerceAtLeast(1)
        val width = nominalWidth.coerceAtMost(frameWidth)
        val height = nominalHeight.coerceAtMost(frameHeight)
        sprite.overlayScale = if (width < nominalWidth || height < nominalHeight) {
            baseSpriteScale * sizeMultiplier
        } else {
            null
        }
        val params = sprite.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            sprite.layoutParams = params
        }
        layout.width = maxOf(width + sideSpace, dp(112)).coerceAtMost(widthLimit)
        layout.height = height + topSpace

        val usableX = (area.width() - layout.width).coerceAtLeast(0)
        val usableY = (area.height() - layout.height).coerceAtLeast(0)
        layout.x = area.left + (usableX * store.prefs.getFloat("petX", .72f)).roundToInt()
        layout.y = area.top + (usableY * store.prefs.getFloat("petY", .68f)).roundToInt()
    }

    private fun setPosition(x: Int, y: Int) {
        val area = bounds()
        layout.x = x.coerceIn(area.left, maxOf(area.left, area.right - layout.width))
        layout.y = y.coerceIn(area.top, maxOf(area.top, area.bottom - layout.height))
        updateWindow()
    }

    private fun savePosition() {
        val area = bounds()
        val travelX = area.width() - layout.width
        val travelY = area.height() - layout.height
        val editor = store.prefs.edit()
        if (travelX > 0) {
            val x = (layout.x - area.left).toFloat() / travelX
            editor.putFloat("petX", x.coerceIn(0f, 1f))
        }
        if (travelY > 0) {
            val y = (layout.y - area.top).toFloat() / travelY
            editor.putFloat("petY", y.coerceIn(0f, 1f))
        }
        editor.apply()
    }

    private fun updatePositionAndShow() {
        val view = root ?: return
        if (!Settings.canDrawOverlays(this)) {
            fail(OverlayStatus.NEEDS_PERMISSION)
            return
        }
        applySavedLayout(pet ?: return)
        view.visibility = View.VISIBLE
        updateWindow()
        scheduleFirstFrameTimeout()
    }

    private fun applyInputMode(): Boolean {
        val editingLargePet = app.settingsVisible && store.overlaySizePercent > 200
        val passthrough = app.overlayStatus == OverlayStatus.PASSTHROUGH
        val flag = WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        val flags = if (editingLargePet || passthrough) layout.flags or flag else layout.flags and flag.inv()
        val alpha = when {
            editingLargePet -> 0.28f
            passthrough -> passthroughAlpha
            else -> 1f
        }
        if (layout.flags == flags && layout.alpha == alpha) return false
        layout.flags = flags
        layout.alpha = alpha
        return true
    }

    private fun updateWindow() {
        val view = root ?: return
        try {
            applyInputMode()
            manager.updateViewLayout(view, layout)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not update pet overlay", error)
            fail(OverlayStatus.WINDOW_ERROR)
        }
    }

    private fun buildNotification(): Notification {
        val notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW),
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }, PendingIntent.FLAG_IMMUTABLE)
        val close = PendingIntent.getService(this, 1, Intent(this, PetOverlayService::class.java).setAction(STOP),
            PendingIntent.FLAG_IMMUTABLE)
        val closeAction = Notification.Action.Builder(
            android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_close),
            getString(R.string.stop_pet), close,
        ).build()
        val passthrough = app.overlayStatus == OverlayStatus.PASSTHROUGH
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(if (passthrough) R.string.notification_passthrough else R.string.notification_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        if (passthrough) {
            val restore = PendingIntent.getService(this, 2,
                Intent(this, PetOverlayService::class.java).setAction(RESTORE_INTERACTION),
                PendingIntent.FLAG_IMMUTABLE)
            val restoreAction = Notification.Action.Builder(
                android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_notification),
                getString(R.string.restore_interaction), restore,
            ).build()
            builder.addAction(restoreAction)
        }
        return builder.addAction(closeAction).build()
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        app.overlayListeners.remove(overlayModeChanged)
        if (watching) {
            store.prefs.unregisterOnSharedPreferenceChangeListener(preferencesListener)
            getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionListener)
        }
        if (screenReceiverRegistered) unregisterReceiver(screenChanges)
        bubble?.removeCallbacks(clearBubble)
        root?.let { view -> runCatching { manager.removeViewImmediate(view) } }
        root = null
        pet = null
        bubble = null
        petMenu = null
        if (app.overlayStatus.active) app.overlayStatus = OverlayStatus.STOPPED
        if (foreground) stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * overlayContext.resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    companion object {
        const val START = "pet.overlay.START"
        const val RECALL = "pet.overlay.RECALL"
        const val RESTORE_INTERACTION = "pet.overlay.RESTORE_INTERACTION"
        private const val STOP = "pet.overlay.STOP"
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "PetOverlay"
    }
}

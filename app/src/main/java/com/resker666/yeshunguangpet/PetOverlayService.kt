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
    private var closeMenu: Button? = null
    private var bubble: TextView? = null
    private var watching = false
    private var generation = 0
    private var destroyed = false
    private var foreground = false
    private var screenReceiverRegistered = false
    private lateinit var mainHandler: Handler
    private val selectionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SkinStore.SELECTED_ID && !destroyed) loadSprite()
    }
    private val clearBubble = Runnable { bubble?.visibility = View.INVISIBLE }
    private val dismissMenu = Runnable { closeMenu?.visibility = View.GONE }
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
    private fun attachWindow() {
        overlayContext = if (Build.VERSION.SDK_INT >= 30) {
            val displayManager = getSystemService(DisplayManager::class.java)
            val display = checkNotNull(displayManager.getDisplay(Display.DEFAULT_DISPLAY)) {
                "Primary display is unavailable"
            }
            createDisplayContext(display).createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        manager = overlayContext.getSystemService(WindowManager::class.java)

        layout = WindowManager.LayoutParams(
            dp(132), dp(152),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            setTitle("PocketPetOverlay")
        }

        if (Build.VERSION.SDK_INT >= 30) layout.setFitInsetsTypes(0)
        applySavedPosition()

        val panel = FrameLayout(overlayContext)
        val sprite = PetSpriteView(overlayContext).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = getString(R.string.app_name)
            setOnClickListener {
                dismissMenu.run()
                showLine(react())
            }
            setOnLongClickListener { showCloseMenu() }
        }
        panel.addView(sprite, FrameLayout.LayoutParams(dp(122), dp(120), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
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

        val close = Button(overlayContext).apply {
            text = getString(R.string.stop_pet)
            setTextColor(Color.WHITE)
            textSize = 13f
            gravity = Gravity.CENTER
            minHeight = 0
            minimumHeight = 0
            setPadding(dp(10), 0, dp(10), 0)
            background = RippleDrawable(
                ColorStateList.valueOf(0x26FFFFFF),
                rounded(0xFF263A52.toInt(), dp(13).toFloat()),
                null,
            )
            backgroundTintList = null
            visibility = View.GONE
            setOnClickListener { stopSelf() }
        }
        panel.addView(close, FrameLayout.LayoutParams(dp(112), dp(44), Gravity.TOP or Gravity.CENTER_HORIZONTAL))

        val interactive = getSystemService(PowerManager::class.java).isInteractive
        val locked = getSystemService(KeyguardManager::class.java).isDeviceLocked
        if (!interactive || locked) panel.visibility = View.INVISIBLE
        sprite.onFirstFrame = {
            if (!destroyed && app.overlayStatus == OverlayStatus.STARTING) {
                mainHandler.removeCallbacks(firstFrameTimeout)
                app.overlayStatus = OverlayStatus.RUNNING
            }
        }
        manager.addView(panel, layout)
        root = panel
        pet = sprite
        bubble = dialogue
        closeMenu = close
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
        store.prefs.registerOnSharedPreferenceChangeListener(selectionListener)
    }

    private fun loadSprite() {
        val ticket = ++generation
        app.worker.execute {
            val result = runCatching { store.selectedSprite() }
            mainHandler.post {
                if (destroyed || ticket != generation) return@post
                result.onSuccess { asset ->
                    try {
                        if (root == null) attachWindow()
                        checkNotNull(pet).apply {
                            show(asset)
                            contentDescription = asset.skin.manifest.name
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

    private fun showCloseMenu(): Boolean {
        val menu = closeMenu ?: return false
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
        root?.post {
            applySavedPosition()
            updateWindow()
        }
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

    private fun applySavedPosition() {
        val area = bounds()
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
        val x = (layout.x - area.left).toFloat() / maxOf(1, area.width() - layout.width)
        val y = (layout.y - area.top).toFloat() / maxOf(1, area.height() - layout.height)
        store.prefs.edit().putFloat("petX", x.coerceIn(0f, 1f)).putFloat("petY", y.coerceIn(0f, 1f)).apply()
    }

    private fun updatePositionAndShow() {
        val view = root ?: return
        if (!Settings.canDrawOverlays(this)) {
            fail(OverlayStatus.NEEDS_PERMISSION)
            return
        }
        applySavedPosition()
        view.visibility = View.VISIBLE
        updateWindow()
        scheduleFirstFrameTimeout()
    }

    private fun updateWindow() {
        val view = root ?: return
        try {
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
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_body))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(closeAction)
            .build()
    }

    override fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        if (watching) {
            store.prefs.unregisterOnSharedPreferenceChangeListener(selectionListener)
            getSystemService(AppOpsManager::class.java).stopWatchingMode(permissionListener)
        }
        if (screenReceiverRegistered) unregisterReceiver(screenChanges)
        bubble?.removeCallbacks(clearBubble)
        root?.let { view -> runCatching { manager.removeViewImmediate(view) } }
        root = null
        pet = null
        bubble = null
        closeMenu = null
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
        private const val STOP = "pet.overlay.STOP"
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "PetOverlay"
    }
}

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
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageButton
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
    private var bubble: TextView? = null
    private var watching = false
    private var generation = 0
    private var destroyed = false
    private var screenReceiverRegistered = false
    private lateinit var mainHandler: Handler
    private val selectionListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == SkinStore.SELECTED_ID && !destroyed) loadSprite()
    }
    private val clearBubble = Runnable { bubble?.visibility = View.INVISIBLE }
    private val permissionListener = AppOpsManager.OnOpChangedListener { _, _ ->
        mainHandler.post {
            if (!destroyed && !Settings.canDrawOverlays(this)) stopSelf()
        }
    }
    private val screenChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val unlocked = !getSystemService(KeyguardManager::class.java).isKeyguardLocked
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> root?.visibility = View.INVISIBLE
                Intent.ACTION_USER_PRESENT -> updatePositionAndShow()
                Intent.ACTION_SCREEN_ON -> if (unlocked) updatePositionAndShow()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        mainHandler = Handler(mainLooper)
        overlayContext = if (Build.VERSION.SDK_INT >= 30) {
            createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else this
        manager = overlayContext.getSystemService(WindowManager::class.java)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == STOP || action == null || !Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            if (root == null) {
                val notification = buildNotification()
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                attachWindow()
            }
            loadSprite()
        } catch (failure: RuntimeException) {
            Log.w(TAG, "Could not start pet overlay", failure)
            stopSelf()
        }
        return START_NOT_STICKY
    }

    @Suppress("RtlHardcoded")
    private fun attachWindow() {
        layout = WindowManager.LayoutParams(
            dp(132), dp(152),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.LEFT }

        if (Build.VERSION.SDK_INT >= 30) layout.setFitInsetsTypes(0)
        applySavedPosition()

        val panel = FrameLayout(overlayContext)
        val sprite = PetSpriteView(overlayContext).apply {
            isClickable = true
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = getString(R.string.app_name)
            setOnClickListener { showLine(react()) }
        }
        panel.addView(sprite, FrameLayout.LayoutParams(dp(122), dp(120), Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL))
        installDrag(sprite)

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

        val close = ImageButton(overlayContext).apply {
            setImageResource(R.drawable.ic_close)
            contentDescription = getString(R.string.stop_pet)
            background = rounded(0xE62B394A.toInt(), dp(15).toFloat())
            setPadding(dp(6), dp(6), dp(6), dp(6))
            setOnClickListener { stopSelf() }
        }
        panel.addView(close, FrameLayout.LayoutParams(dp(32), dp(32), Gravity.TOP or Gravity.RIGHT))

        manager.addView(panel, layout)
        root = panel
        pet = sprite
        bubble = dialogue
        if (getSystemService(KeyguardManager::class.java).isKeyguardLocked) panel.visibility = View.INVISIBLE
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
        app.overlayRunning = true
    }

    private fun loadSprite() {
        val ticket = ++generation
        app.worker.execute {
            val result = runCatching { store.selectedSprite() }
            mainHandler.post {
                if (!destroyed && ticket == generation) {
                    result.onSuccess { asset ->
                        pet?.show(asset)
                        pet?.contentDescription = asset.skin.manifest.name
                    }.onFailure { error ->
                        Log.w(TAG, "Could not load pet sprite", error)
                        stopSelf()
                    }
                }
            }
        }
    }

    @Suppress("ClickableViewAccessibility")
    private fun installDrag(sprite: PetSpriteView) {
        val slop = ViewConfiguration.get(overlayContext).scaledTouchSlop
        var startX = 0f
        var startY = 0f
        var initialX = 0
        var initialY = 0
        var previousX = 0f
        var dragging = false
        sprite.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startX = event.rawX
                    startY = event.rawY
                    initialX = layout.x
                    initialY = layout.y
                    previousX = startX
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - startX
                    val dy = event.rawY - startY
                    if (dragging || abs(dx) > slop || abs(dy) > slop) {
                        dragging = true
                        if (event.rawX != previousX) sprite.drag(event.rawX - previousX)
                        previousX = event.rawX
                        setPosition(initialX + dx.roundToInt(), initialY + dy.roundToInt())
                    }
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        sprite.idle()
                        savePosition()
                    } else if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                    true
                }
                else -> true
            }
        }
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
        layout.x = area.left + (usableX * store.prefs.getFloat("petX", .92f)).roundToInt()
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
            stopSelf()
            return
        }
        applySavedPosition()
        view.visibility = View.VISIBLE
        updateWindow()
    }

    private fun updateWindow() {
        val view = root ?: return
        try {
            manager.updateViewLayout(view, layout)
        } catch (error: RuntimeException) {
            Log.w(TAG, "Could not update pet overlay", error)
            stopSelf()
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
        app.overlayRunning = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * overlayContext.resources.displayMetrics.density).roundToInt()

    private fun rounded(color: Int, radius: Float) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = radius
    }

    companion object {
        const val START = "pet.overlay.START"
        private const val STOP = "pet.overlay.STOP"
        private const val CHANNEL_ID = "pet_overlay"
        private const val NOTIFICATION_ID = 101
        private const val TAG = "PetOverlay"
    }
}

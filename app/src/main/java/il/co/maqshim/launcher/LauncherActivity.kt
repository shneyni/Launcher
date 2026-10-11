package il.co.maqshim.launcher

import android.Manifest
import android.app.ActivityOptions
import android.app.role.RoleManager
import android.provider.ContactsContract
import android.telecom.TelecomManager
import android.app.PendingIntent
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.bluetooth.BluetoothAdapter
import android.net.wifi.WifiManager
import android.telephony.PhoneStateListener
import android.telephony.SignalStrength
import android.graphics.Color
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.telephony.TelephonyManager
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.FrameLayout
import android.widget.GridLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Date
import java.util.Locale

private const val YELLOW = 0xFFFDC100.toInt()
private const val BLACK = 0xFF080A0C.toInt()
private const val GRAY = 0xFFA6A6A6.toInt()
private const val WHITE = 0xFFF5F5F5.toInt()

private data class LaunchableApp(val packageName: String, val label: String, val icon: Drawable)
private enum class Page { HOME, SHORTCUTS, APPS, MANAGE, REORDER, DIAL }
private class ContactEntry(val name: String, val number: String, val digits: String, val wordCodes: List<String>, val fullCode: String)
private class MenuState(val title: String, val items: List<Pair<String, () -> Unit>>, var index: Int = 0)

class LauncherActivity : NoTouchActivity() {
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var page = Page.HOME
    private var returnPage = Page.HOME
    private var selected = 0
    private var menu: MenuState? = null
    private var appsDirty = true
    private val wallpaperReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            WallpaperHelper.invalidate()
            if (::root.isInitialized && (page == Page.HOME || page == Page.APPS)) render()
        }
    }
    private var cardFocus = -1
    private val dialInput = StringBuilder()
    private var dialRows: List<Pair<String, String>> = emptyList()
    private var contacts: List<ContactEntry> = emptyList()
    private var contactsLoadedAt = 0L
    private var contactsPrompted = false
    private var pendingCall: String? = null
    private var cardsView: CardsView? = null
    private val hubListener = Runnable { refreshCards() }
    private val progressTick = object : Runnable {
        override fun run() {
            if (page == Page.HOME) cardsView?.updateProgress()
            handler.postDelayed(this, 1_000L)
        }
    }
    private val cellViews = mutableListOf<LinearLayout>()
    private var gridScroll: ScrollView? = null
    private val listRows = mutableListOf<LinearLayout>()
    private var listScroll: ScrollView? = null
    private val packageReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) { appsDirty = true }
    }
    private var apps: List<LaunchableApp> = emptyList()
    private var shortcuts: MutableList<String> = mutableListOf()
    private var pendingShortcuts: MutableSet<String> = mutableSetOf()
    private var permissionPrompted = false; private var statusReceiverRegistered = false; private val statusReceiver = object : android.content.BroadcastReceiver() { override fun onReceive(context: Context?, intent: Intent?) { if (::root.isInitialized && page == Page.HOME) render() } }
    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            if (page == Page.HOME) render()
            handler.postDelayed(this, 5_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.statusBarColor = BLACK
        window.navigationBarColor = BLACK
        window.decorView.systemUiVisibility = 0
        window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BLACK)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bottomInset = insets.systemWindowInsetBottom
            if (view.paddingBottom != bottomInset) view.setPadding(0, 0, 0, bottomInset)
            insets
        }
        setContentView(root)
        val pkgFilter = android.content.IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED); addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED); addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }
        ContextCompat.registerReceiver(this, packageReceiver, pkgFilter, ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.registerReceiver(this, wallpaperReceiver, android.content.IntentFilter(Intent.ACTION_WALLPAPER_CHANGED), ContextCompat.RECEIVER_NOT_EXPORTED)
        refreshAppsIfNeeded(true)
        render()
        ensureLockService()
        if (handleDialIntent(intent)) { prepareDial(); render() }
        if (notificationAccess()) {
            try { NotificationListenerService.requestRebind(ComponentName(this, NotifListener::class.java)) } catch (_: Exception) { }
        }
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        runCatching { unregisterReceiver(wallpaperReceiver) }
        super.onDestroy()
    }

    /** The app list (and every icon) is only reloaded when something was installed/removed. */
    private fun refreshAppsIfNeeded(force: Boolean = false): Boolean {
        if (!force && !appsDirty && apps.isNotEmpty()) return false
        readApps()
        readShortcuts()
        appsDirty = false
        return true
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Every press of the Home button lands here and returns to the home screen.
        if (intent.hasCategory(Intent.CATEGORY_HOME)) goHome()
        else if (handleDialIntent(intent)) { menu = null; prepareDial(); if (::root.isInitialized) render() }
    }

    private fun goHome() {
        menu = null
        moving = false
        page = Page.HOME
        selected = 0
        cardFocus = -1
        if (::root.isInitialized) render()
    }

    private fun ensureLockService() {
        if (!LockState.enabled(this)) return
        try { ContextCompat.startForegroundService(this, Intent(this, LockService::class.java)) } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume(); if (!statusReceiverRegistered) { val filter=android.content.IntentFilter().apply { addAction(BluetoothAdapter.ACTION_STATE_CHANGED); addAction(WifiManager.WIFI_STATE_CHANGED_ACTION); addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION); addAction(WifiManager.RSSI_CHANGED_ACTION); addAction(Intent.ACTION_BATTERY_CHANGED); addAction(Intent.ACTION_POWER_CONNECTED); addAction(Intent.ACTION_POWER_DISCONNECTED) }; if (Build.VERSION.SDK_INT >= 33) registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED) else registerReceiver(statusReceiver, filter); statusReceiverRegistered=true }
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        NotificationHub.listeners.add(hubListener)
        CallManager.listeners.add(hubListener)
        KeyAccessibilityService.onPoundShort = { dialAppend('#') }
        handler.removeCallbacks(progressTick)
        handler.post(progressTick)
        val changed = refreshAppsIfNeeded()
        val limit = if (page == Page.SHORTCUTS || page == Page.REORDER) shortcuts.size else apps.size
        selected = selected.coerceIn(0, maxOf(0, limit - 1))
        val hadMenu = menu != null
        menu = null
        WallpaperHelper.retryNow()
        if (::root.isInitialized && (changed || hadMenu || page == Page.HOME || page == Page.APPS)) render()
        if (LockState.enabled(this) && LockState.isLocked(this)) LockActivity.show(this)
    }

    override fun onPause() {
        handler.removeCallbacks(clockTick)
        handler.removeCallbacks(progressTick)
        NotificationHub.listeners.remove(hubListener)
        CallManager.listeners.remove(hubListener)
        KeyAccessibilityService.onPoundShort = null
        if (statusReceiverRegistered) { runCatching { unregisterReceiver(statusReceiver) }; statusReceiverRegistered=false }; super.onPause()
    }

    @Suppress("DEPRECATION")
    private fun readApps() {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val results: List<ResolveInfo> = try {
            if (Build.VERSION.SDK_INT >= 33) packageManager.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
            else packageManager.queryIntentActivities(intent, 0)
        } catch (_: Exception) { emptyList() }
        apps = results.mapNotNull { r ->
            try {
                val pkg = r.activityInfo?.packageName ?: return@mapNotNull null
                LaunchableApp(pkg, r.loadLabel(packageManager)?.toString()?.trim().orEmpty().ifBlank { pkg }, r.loadIcon(packageManager))
            } catch (_: Exception) { null }
        }.distinctBy { it.packageName + "/" + it.label }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.label })
    }

    private fun readShortcuts() {
        val saved = prefs.getString("shortcuts", "")!!.split('|').filter { it.isNotBlank() }
        val valid = apps.map { it.packageName }.toSet()
        shortcuts = (saved.filter { it in valid } + apps.map { it.packageName }.filter { it !in saved })
            .filter { it in saved }.take(6).toMutableList()
    }

    private fun render() {
        root.removeAllViews()
        root.addView(statusBar(), LinearLayout.LayoutParams(-1, dp(27)))
        val body = FrameLayout(this).apply { setBackgroundColor(BLACK) }
        root.addView(body, LinearLayout.LayoutParams(-1, 0, 1f))
        when (page) {
            Page.HOME -> { addWallpaper(body, false); renderHome(body) }
            Page.SHORTCUTS -> renderShortcuts(body)
            Page.APPS -> { addWallpaper(body, true); renderApps(body) }
            Page.MANAGE -> renderManage(body)
            Page.REORDER -> renderReorder(body)
            Page.DIAL -> renderDial(body)
        }
        menu?.let { menuOverlay(body, it) }
        root.addView(softKeys(), LinearLayout.LayoutParams(-1, dp(43)))
    }

    /** Home: the device wallpaper. Apps: the same wallpaper, blurred, darker and slightly zoomed in. */
    private fun addWallpaper(body: FrameLayout, closeUp: Boolean) {
        val bitmap = WallpaperHelper.get(this, closeUp) ?: return
        val image = ImageView(this).apply {
            setImageBitmap(bitmap)
            scaleType = ImageView.ScaleType.CENTER_CROP
            if (closeUp) { scaleX = 1.2f; scaleY = 1.2f }
        }
        body.addView(image, FrameLayout.LayoutParams(-1, -1))
        body.addView(View(this).apply { setBackgroundColor(if (closeUp) 0xA6000000.toInt() else 0x59000000) }, FrameLayout.LayoutParams(-1, -1))
    }

    private fun statusBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9), 0, dp(9), 0)
        setBackgroundColor(0xFF030405.toInt())
        val brand = statusGlyph("signal")
        addView(brand, LinearLayout.LayoutParams(dp(19), dp(17)))
        addView(statusGlyph("wifi"), LinearLayout.LayoutParams(dp(19), dp(17)))
        addView(statusGlyph("bluetooth"), LinearLayout.LayoutParams(dp(17), dp(17)))
        addView(View(this@LauncherActivity), LinearLayout.LayoutParams(0, 1, 1f))
        // Bluetooth indicator is placed on the left
        addView(statusGlyph("battery"), LinearLayout.LayoutParams(dp(23), dp(17)))
        addView(label(SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), 12, WHITE, true), LinearLayout.LayoutParams(dp(46), -2))
    }

    private fun renderHome(frame: FrameLayout) {
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
            setPadding(dp(28), dp(10), dp(28), dp(8))
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        frame.addView(column, FrameLayout.LayoutParams(-1, -1))
        column.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        val cards = CardsView(this, true)
        cards.rebuild(cardFocus)
        cardFocus = cards.focus
        cardsView = cards
        column.addView(cards, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(3) })
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.75f))
        val now = LocalDate.now()
        val clock = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        content.addView(label(clock, 55, WHITE, true).apply { gravity = Gravity.RIGHT; contentDescription = "השעה $clock" }, LinearLayout.LayoutParams(-1, -2))
        val rule = View(this).apply { setBackgroundColor(YELLOW) }
        val ruleParams = LinearLayout.LayoutParams((resources.displayMetrics.widthPixels * .72f).toInt(), dp(2)).apply { gravity = Gravity.RIGHT; topMargin = dp(5); bottomMargin = dp(9) }
        content.addView(rule, ruleParams)
        val weekday = SimpleDateFormat("EEEE", Locale("he", "IL")).format(Date())
        val civilDate = "$weekday  ${now.format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-yyyy"))}"
        content.addView(label(civilDate, 15, WHITE).apply { gravity = Gravity.RIGHT }, LinearLayout.LayoutParams(-1, -2))
        content.addView(label(now.toHebrewDateString(), 15, GRAY).apply { gravity = Gravity.RIGHT; setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.58f))
        val simRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        val simLabel = simStatus()
        val simIcon = ImageView(this).apply {
            setImageResource(if (simLabel == "הכנס כרטיס SIM") R.drawable.ic_sim_missing else R.drawable.ic_sim_card)
            contentDescription = "כרטיס SIM"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        simRow.addView(simIcon, LinearLayout.LayoutParams(dp(37), dp(32)))
        val simText = label(simLabel, 15, WHITE).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), 0, 0, 0) }
        simRow.addView(simText, LinearLayout.LayoutParams(-2, dp(36)))
        content.addView(simRow, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.72f))
        if ((ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED || (Build.VERSION.SDK_INT >= 31 && BluetoothAdapter.getDefaultAdapter() != null && ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED)) && !permissionPrompted) {
            permissionPrompted = true
            content.post { if (page == Page.HOME) requestPhonePermission() }
        }
    }

    private fun simStatus(): String {
        val tm = getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager ?: return "פרטי SIM אינם זמינים"
        val state = try { tm.simState } catch (_: SecurityException) { return "לא ניתן לקרוא פרטי SIM" } catch (_: Exception) { return "פרטי SIM אינם זמינים" }
        if (state == TelephonyManager.SIM_STATE_ABSENT) return "הכנס כרטיס SIM"
        if (state == TelephonyManager.SIM_STATE_UNKNOWN) return "מצב SIM אינו זמין"
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) != PackageManager.PERMISSION_GRANTED) return "הרשאה נדרשת לשם המפעיל"
        return try { tm.simOperatorName?.takeIf { it.isNotBlank() } ?: "SIM מחובר · מפעיל לא זמין" }
        catch (_: SecurityException) { "שם המפעיל אינו זמין" }
        catch (_: Exception) { "SIM מחובר · מפעיל לא זמין" }
    }

    private fun renderShortcuts(frame: FrameLayout) {
        val viewApps = shortcuts.mapNotNull { id -> apps.find { it.packageName == id } }.take(6)
        if (viewApps.isEmpty()) {
            val empty = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER; setPadding(dp(25), 0, dp(25), 0) }
            empty.addView(label("אין קיצורים נבחרים", 23, YELLOW, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(-1, -2))
            empty.addView(label("פתח אפשרויות ובחר „נהל” כדי לבחור אפליקציות.", 15, WHITE).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
            frame.addView(empty, FrameLayout.LayoutParams(-1, -1))
        } else {
            listPage(frame, "קיצורים", viewApps, selected, false)
        }
    }

    private fun renderApps(frame: FrameLayout) {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), dp(4), dp(10), dp(4)) }
        wrapper.addView(label("יישומים", 16, YELLOW, true).apply { gravity = Gravity.RIGHT }, LinearLayout.LayoutParams(-1, dp(27)))
        cellViews.clear()
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        gridScroll = scroll
        val grid = GridLayout(this).apply {
            columnCount = 3
            rowCount = (apps.size + 2) / 3
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val cellHeight = ((resources.displayMetrics.heightPixels / resources.displayMetrics.density - 27f - 43f - 27f - 20f) / 3f)
            .toInt().coerceIn(84, 124)
        apps.forEachIndexed { index, app ->
            val cell = appCell(app, index == selected, index, (cellHeight * .50f).toInt().coerceIn(36, 56))
            grid.addView(cell, GridLayout.LayoutParams().apply {
                width = 0; height = dp(cellHeight)
                columnSpec = GridLayout.spec(index % 3, 1, 1f)
                rowSpec = GridLayout.spec(index / 3, 1)
                setMargins(dp(2), dp(2), dp(2), dp(2))
            })
        }
        scroll.addView(grid)
        wrapper.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        frame.addView(wrapper, FrameLayout.LayoutParams(-1, -1))
        if (apps.isEmpty()) centeredMessage(frame, "לא נמצאו אפליקציות להפעלה")
        scroll.post { if (selected in 0 until apps.size) scroll.scrollTo(0, grid.getChildAt(selected).top.coerceAtLeast(0)) }
    }

    private fun appCell(app: LaunchableApp, isSelected: Boolean, index: Int, iconDp: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(5), dp(3), dp(4))
            background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(29).toFloat() }
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        box.addView(ImageView(this).apply { setImageDrawable(app.icon); contentDescription = app.label }, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
        box.addView(label(app.label, 12, WHITE).apply { gravity = Gravity.CENTER; maxLines = 2; textAlignment = View.TEXT_ALIGNMENT_CENTER; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        styleCell(box, app, isSelected)
        cellViews.add(box)
        return box
    }

    private fun styleCell(box: LinearLayout, app: LaunchableApp, isSelected: Boolean) {
        (box.background as? android.graphics.drawable.GradientDrawable)?.setColor(if (isSelected) YELLOW else 0xB3141719.toInt())
        (box.getChildAt(1) as? TextView)?.setTextColor(if (isSelected) BLACK else WHITE)
        box.contentDescription = "${app.label}${if (isSelected) ", נבחר" else ""}"
    }

    private fun keepVisible(sv: ScrollView?, v: View) {
        if (sv == null) return
        if (v.top < sv.scrollY) sv.scrollTo(0, v.top)
        else if (v.bottom > sv.scrollY + sv.height) sv.scrollTo(0, v.bottom - sv.height)
    }

    /** Moves the highlight in the app grid without rebuilding the screen. */
    private fun selectApp(newIndex: Int) {
        val old = selected
        if (apps.isEmpty() || newIndex == old || newIndex !in apps.indices) return
        selected = newIndex
        cellViews.getOrNull(old)?.let { styleCell(it, apps[old], false) }
        cellViews.getOrNull(newIndex)?.let { styleCell(it, apps[newIndex], true); keepVisible(gridScroll, it) }
    }

    private fun styleRow(row: LinearLayout, i: Int, selectedIndex: Int) {
        val active = i == selectedIndex
        row.setBackgroundColor(if (active) YELLOW else if (i % 2 == 0) 0xFF101315.toInt() else BLACK)
        (row.getChildAt(1) as? TextView)?.setTextColor(if (active) BLACK else WHITE)
        if (row.childCount > 2) (row.getChildAt(2) as? TextView)?.setTextColor(if (active) BLACK else YELLOW)
    }

    /** Moves the highlight in list screens without rebuilding the screen. */
    private fun selectRow(newIndex: Int) {
        if (listRows.isEmpty()) return
        val old = selected
        val idx = newIndex.coerceIn(0, listRows.lastIndex)
        if (idx == old) return
        selected = idx
        listRows.getOrNull(old)?.let { styleRow(it, old, idx) }
        listRows.getOrNull(idx)?.let { styleRow(it, idx, idx); keepVisible(listScroll, it) }
    }

    private fun listPage(frame: FrameLayout, title: String, items: List<LaunchableApp>, selection: Int, checks: Boolean, staged: Set<String> = emptySet()) {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(7), dp(12), dp(4)) }
        wrapper.addView(label(title, 18, YELLOW, true).apply { gravity = Gravity.RIGHT; setPadding(0, 0, 0, dp(5)) }, LinearLayout.LayoutParams(-1, dp(32)))
        listRows.clear()
        val scroll = ScrollView(this).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
        listScroll = scroll
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        items.forEachIndexed { i, app ->
            val active = staged.contains(app.packageName)
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(7), 0, dp(7), 0)
                setBackgroundColor(if (i == selection) YELLOW else if (i % 2 == 0) 0xFF101315.toInt() else BLACK)
                contentDescription = if (checks) "${app.label}, ${if (active) "מסומן" else "לא מסומן"}" else app.label
            }
            row.addView(ImageView(this).apply { setImageDrawable(app.icon); contentDescription = app.label }, LinearLayout.LayoutParams(dp(34), dp(34)))
            row.addView(label(app.label, 15, if (i == selection) BLACK else WHITE).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), 0, 0, 0); maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(0, -1, 1f))
            if (checks) row.addView(label(if (active) "✓" else "□", 20, if (i == selection) BLACK else YELLOW, true).apply { gravity = Gravity.CENTER }, LinearLayout.LayoutParams(dp(36), -1))
            listRows.add(row)
            list.addView(row, LinearLayout.LayoutParams(-1, dp(if (checks) 48 else 52)).apply { bottomMargin = dp(1) })
        }
        scroll.addView(list)
        wrapper.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val hint = when {
            page == Page.MANAGE -> "↑↓ בחירה · אישור סימון · אפשרויות שמירה"
            page == Page.REORDER -> "אישור לבחירת קיצור · ↑↓ להעברה · אפשרויות שמירה"
            else -> "↑↓ בחירה · אישור לפתיחה"
        }
        wrapper.addView(label(hint, 11, GRAY).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(-1, dp(24)))
        frame.addView(wrapper, FrameLayout.LayoutParams(-1, -1))
        if (items.isEmpty()) centeredMessage(frame, "אין אפליקציות להצגה")
        scroll.post { if (selection in 0 until items.size) scroll.scrollTo(0, list.getChildAt(selection).top) }
    }

    private fun renderManage(frame: FrameLayout) {
        listPage(frame, "נהל קיצורים · עד 6", apps, selected, true, pendingShortcuts)
    }

    private fun renderReorder(frame: FrameLayout) {
        val ordered = shortcuts.mapNotNull { id -> apps.find { it.packageName == id } }
        listPage(frame, "סדר קיצורים", ordered, selected, false)
        if (moving) centeredOverlay(frame, "בחר יעד בעזרת ↑↓\nאישור לשחרור")
    }

    private var moving = false; private var centerLongPressHandled = false

    private fun centeredOverlay(frame: FrameLayout, text: String) {
        val chip = label(text, 14, BLACK, true).apply { gravity = Gravity.CENTER; setPadding(dp(16), dp(10), dp(16), dp(10)); setBackgroundColor(YELLOW) }
        frame.addView(chip, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply { topMargin = dp(4) })
    }

    private fun centeredMessage(frame: FrameLayout, message: String) {
        val text = label(message, 17, GRAY).apply { gravity = Gravity.CENTER }
        frame.addView(text, FrameLayout.LayoutParams(-1, -1))
    }

    private fun softKeys(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(14), 0, dp(14), 0)
        setBackgroundColor(0xFF101315.toInt())
        val left = when (page) {
            Page.HOME -> "קיצורים"
            Page.MANAGE, Page.REORDER -> "שמירה"
            Page.DIAL -> "חייג"
            else -> "אפשרויות"
        }
        val right = if (page == Page.HOME) "אנשי קשר" else "אחורה"
        val leftBox = LinearLayout(this@LauncherActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        if (page == Page.HOME) leftBox.addView(ImageView(this@LauncherActivity).apply { setImageResource(R.drawable.ic_shortcuts); contentDescription = "קיצורים" }, LinearLayout.LayoutParams(dp(29), dp(29)))
        if (page != Page.HOME) leftBox.addView(if (page == Page.MANAGE || page == Page.REORDER || page == Page.DIAL) label(left, 13, WHITE, true).apply { contentDescription = "מקש שמאל: $left" } else ImageView(this@LauncherActivity).apply { setImageResource(R.drawable.ic_options); contentDescription = "אפשרויות"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }, LinearLayout.LayoutParams(dp(34), dp(34)))
        addView(leftBox, LinearLayout.LayoutParams(0, -1, 1f))
        val rightBox = LinearLayout(this@LauncherActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        if (page == Page.HOME) rightBox.addView(ImageView(this@LauncherActivity).apply { setImageResource(R.drawable.ic_contacts); contentDescription = "אנשי קשר" }, LinearLayout.LayoutParams(dp(27), dp(27)))
        if (page != Page.HOME) rightBox.addView(ImageView(this@LauncherActivity).apply { setImageResource(R.drawable.ic_back); contentDescription = "אחורה"; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES }, LinearLayout.LayoutParams(dp(29), dp(29)))
        addView(rightBox, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun label(text: String, size: Int, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size.toFloat()
        setTextColor(color)
        gravity = Gravity.CENTER_VERTICAL
        typeface = if (bold) Typeface.create("sans-serif", Typeface.BOLD) else Typeface.create("sans-serif", Typeface.NORMAL)
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        includeFontPadding = false
    }

    private fun statusGlyph(kind: String): View = NokiaStatusGlyph(this, kind).apply {
        contentDescription = when (kind) { "signal" -> "קליטה סלולרית"; "wifi" -> "רשת אלחוטית"; "bluetooth" -> "Bluetooth"; else -> "סוללה" }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        setPadding(dp(1), 0, dp(2), 0)
    }

    private fun enterManage() {
        returnPage = if (page == Page.SHORTCUTS) Page.SHORTCUTS else Page.APPS
        pendingShortcuts = shortcuts.toMutableSet()
        selected = 0
        page = Page.MANAGE
        render()
    }

    private fun enterReorder() {
        returnPage = if (page == Page.SHORTCUTS) Page.SHORTCUTS else Page.APPS
        selected = 0
        moving = false
        page = Page.REORDER
        render()
    }

    private fun item(label: String, action: () -> Unit): Pair<String, () -> Unit> = label to action

    private fun openMenu(title: String, items: List<Pair<String, () -> Unit>>) {
        menu = MenuState(title, items)
        render()
    }

    private fun showOptions() {
        val first = if (page == Page.SHORTCUTS) "נהל" else "נהל יישומים"
        val second = if (page == Page.SHORTCUTS) "סדר" else "סדר קיצורים"
        openMenu("אפשרויות", listOf(
            item(first) { enterManage() },
            item(second) { enterReorder() },
            item("הגדרות מערכת") { showSystemSettings() }
        ))
    }

    private fun onOff(value: Boolean) = if (value) "פעיל" else "כבוי"

    private fun a11yEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        return enabled.contains("$packageName/") && enabled.contains("KeyAccessibilityService")
    }

    private fun showSystemSettings() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val unavailable = "ההגדרה אינה זמינה במכשיר"
        val items = mutableListOf(
            item("נעל עכשיו") { LockState.setLocked(this, true); LockActivity.show(this) },
            item("שירות מקשים (# והפעלה): ${onOff(a11yEnabled())}") { startQuiet(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS), unavailable) },
            item("גישה למצב שקט: ${onOff(nm.isNotificationPolicyAccessGranted)}") { startQuiet(Intent(Settings.ACTION_NOTIFICATION_POLICY_ACCESS_SETTINGS), unavailable) },
            item("הצגה מעל אפליקציות: ${onOff(Settings.canDrawOverlays(this))}") { startQuiet(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")), unavailable) },
            item("אפליקציית טלפון: ${onOff(isDefaultDialer())}") { requestDialerRole() },
            item("גישה להתראות ולמוזיקה: ${onOff(notificationAccess())}") { startQuiet(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS), unavailable) },
            item("מסך נעילה מותאם: ${onOff(LockState.enabled(this))}") { toggleLockScreen() }
        )
        if (Build.VERSION.SDK_INT >= 33) {
            items.add(item("גישה לקבצים (לרקע): ${onOff(android.os.Environment.isExternalStorageManager())}") {
                startQuiet(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")), unavailable)
            })
        }
        if (Build.VERSION.SDK_INT <= 32) {
            items.add(item("גישה לרקע ולתמונת הנעילה") { requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 42) })
        }
        openMenu("הגדרות מערכת", items)
    }

    private fun toggleLockScreen() {
        val enable = !LockState.enabled(this)
        LockState.setEnabled(this, enable)
        if (enable) ensureLockService() else { stopService(Intent(this, LockService::class.java)); LockState.setLocked(this, false) }
        toast("מסך נעילה מותאם: ${onOff(enable)}")
    }

    private fun menuOverlay(frame: FrameLayout, m: MenuState) {
        val scrim = FrameLayout(this).apply { setBackgroundColor(0xE6000000.toInt()) }
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(10), dp(8), dp(10), dp(8))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(0xFF141719.toInt()); cornerRadius = dp(12).toFloat(); setStroke(dp(2), YELLOW) }
        }
        box.addView(label(m.title, 16, YELLOW, true).apply { gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END }, LinearLayout.LayoutParams(-1, dp(30)))
        val scroll = ScrollView(this).apply { overScrollMode = View.OVER_SCROLL_NEVER; isVerticalScrollBarEnabled = false }
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        m.items.forEachIndexed { i, entry ->
            val active = i == m.index
            list.addView(
                label(entry.first, 15, if (active) BLACK else WHITE).apply {
                    gravity = Gravity.RIGHT or Gravity.CENTER_VERTICAL
                    setPadding(dp(8), 0, dp(8), 0)
                    maxLines = 2
                    setBackgroundColor(if (active) YELLOW else Color.TRANSPARENT)
                },
                LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(2) }
            )
        }
        scroll.addView(list)
        box.addView(scroll, LinearLayout.LayoutParams(-1, -2))
        scrim.addView(box, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply { setMargins(dp(14), dp(4), dp(14), dp(4)) })
        frame.addView(scrim, FrameLayout.LayoutParams(-1, -1))
        scroll.post { list.getChildAt(m.index)?.let { scroll.scrollTo(0, it.top) } }
    }

    private fun menuKey(code: Int): Boolean {
        val m = menu ?: return false
        when (code) {
            KeyEvent.KEYCODE_DPAD_UP -> m.index = (m.index - 1).coerceAtLeast(0)
            KeyEvent.KEYCODE_DPAD_DOWN -> m.index = (m.index + 1).coerceAtMost(m.items.lastIndex)
            KeyEvent.KEYCODE_SOFT_RIGHT -> menu = null
            else -> return false
        }
        render()
        return true
    }

    private fun activateMenu() {
        val m = menu ?: return
        val action = m.items.getOrNull(m.index)?.second ?: return
        menu = null
        render()
        action()
    }

    private fun noAnimation(): Bundle? = ActivityOptions.makeCustomAnimation(this, 0, 0).toBundle()

    private fun startQuiet(intent: Intent, error: String) {
        try { startActivity(intent, noAnimation()) } catch (_: Exception) { toast(error) }
    }

    private fun styleDialog(dialog: AlertDialog) {
        dialog.window?.let {
            it.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
            it.setWindowAnimations(0)
            it.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE)
        }
    }

    private fun saveManage() {
        val ordered = shortcuts.filter { it in pendingShortcuts }
        shortcuts = (ordered + apps.asSequence().map { it.packageName }.filter { it in pendingShortcuts && it !in ordered })
            .take(6).toMutableList()
        saveShortcuts()
        selected = 0
        page = returnPage
        render()
    }

    private fun saveShortcuts() {
        prefs.edit().putString("shortcuts", shortcuts.joinToString("|")).apply()
    }

    private fun openSelected(list: List<LaunchableApp>) {
        val app = list.getOrNull(selected) ?: return
        launchApp(app)
    }

    private fun launchApp(app: LaunchableApp) {
        try {
            val launch = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch == null) { toast("אי אפשר לפתוח את ${app.label}"); return }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch, noAnimation())
        } catch (_: ActivityNotFoundException) { toast("האפליקציה אינה זמינה להפעלה") }
        catch (_: SecurityException) { toast("אין הרשאה לפתוח את האפליקציה") }
        catch (_: Exception) { toast("לא ניתן לפתוח את ${app.label}") }
    }

    private fun showAppActions(app: LaunchableApp) {
        openMenu(app.label, listOf(
            item("פתח") { launchApp(app) },
            item("פרטי אפליקציה") { startQuiet(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${app.packageName}")), "לא ניתן לפתוח את פרטי האפליקציה") },
            item("הסר אפליקציה") { uninstallApp(app) }
        ))
    }

    private fun uninstallApp(app: LaunchableApp) {
        if (app.packageName == packageName) { toast("אי אפשר להסיר את ה־Launcher מתוך עצמו"); return }
        val info = try { packageManager.getApplicationInfo(app.packageName, 0) } catch (_: Exception) { null }
        val isSystem = info != null && (info.flags and ApplicationInfo.FLAG_SYSTEM) != 0 && (info.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
        if (isSystem) { toast("זו אפליקציית מערכת ואי אפשר להסיר אותה"); return }
        startQuiet(Intent(Intent.ACTION_DELETE, Uri.parse("package:${app.packageName}")), "לא ניתן להסיר את ${app.label}")
    }

    private fun openContacts() {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CONTACTS")
        )
        for (intent in intents) try {
            startActivity(intent, noAnimation())
            return
        } catch (_: ActivityNotFoundException) { }
        catch (_: SecurityException) { }
        toast("לא נמצאה אפליקציית אנשי קשר")
    }

    private fun requestPhonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED && (Build.VERSION.SDK_INT < 31 || BluetoothAdapter.getDefaultAdapter() == null || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED)) { render(); return }
        AlertDialog.Builder(this)
            .setTitle("הרשאות לשורת המצב")
            .setMessage("ההרשאות נדרשות להצגת קליטה סלולרית, פרטי SIM ומצב Bluetooth. אפשר להמשיך להשתמש במקשים גם בלי לאשר.")
            .setNegativeButton("לא עכשיו") { _, _ -> render() }
            .setPositiveButton("המשך") { _, _ ->
        requestPermissions(if (Build.VERSION.SDK_INT >= 31 && BluetoothAdapter.getDefaultAdapter() != null) arrayOf(Manifest.permission.READ_PHONE_STATE, Manifest.permission.BLUETOOTH_CONNECT) else arrayOf(Manifest.permission.READ_PHONE_STATE), 41)
            }.show().also { styleDialog(it) }
    }

    @Deprecated("Permission result callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 43) {
            contactsLoadedAt = 0L
            val callGranted = ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED
            val number = pendingCall
            pendingCall = null
            if (page == Page.DIAL) { loadContacts(); dialRows = computeDialRows() }
            if (callGranted && number != null) callNumber(number) else if (::root.isInitialized) render()
            return
        }
        if (requestCode == 41) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) toast("נתוני מצב הרשת יוצגו בשורת הבית")
            else toast("הרשאה נדחתה; החיווי המתאים יוצג כלא זמין")
            render()
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun goBack() {
        if (menu != null) { menu = null; render(); return }
        when (page) {
            Page.HOME -> openContacts()
            Page.SHORTCUTS, Page.APPS -> { page = Page.HOME; selected = 0; render() }
            Page.MANAGE -> { page = returnPage; render() }
            Page.REORDER -> { moving = false; page = returnPage; render() }
            Page.DIAL -> dialBack()
        }
    }

    private fun toggleManage() {
        val app = apps.getOrNull(selected) ?: return
        if (app.packageName in pendingShortcuts) pendingShortcuts.remove(app.packageName)
        else if (pendingShortcuts.size >= 6) toast("אפשר לבחור עד שש אפליקציות")
        else pendingShortcuts.add(app.packageName)
        render()
    }

    private fun moveShortcut(delta: Int) {
        if (!moving || shortcuts.size < 2) return
        val target = (selected + delta).coerceIn(0, shortcuts.lastIndex)
        if (target != selected) {
            val item = shortcuts.removeAt(selected)
            shortcuts.add(target, item)
            selected = target
            saveShortcuts()
        }
        render()
    }

    private fun navigate(keyCode: Int): Boolean {
        when (page) {
            Page.HOME -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> {
                    if (cardFocus >= 0) activateCard() else { page = Page.APPS; selected = 0; render() }
                    return true
                }
                KeyEvent.KEYCODE_SOFT_LEFT -> { page = Page.SHORTCUTS; selected = 0; render(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { moveCardFocus(1); return true }
                KeyEvent.KEYCODE_DPAD_UP -> { moveCardFocus(-1); return true }
                KeyEvent.KEYCODE_DPAD_LEFT -> { if (focusedCard() == CardsView.Kind.MEDIA) NotificationHub.skip(false); return true }
                KeyEvent.KEYCODE_DPAD_RIGHT -> { if (focusedCard() == CardsView.Kind.MEDIA) NotificationHub.skip(true); return true }
                KeyEvent.KEYCODE_SOFT_RIGHT -> { openContacts(); return true }
            }
            Page.DIAL -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { selectRow(selected - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { selectRow(selected + 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_CALL, KeyEvent.KEYCODE_SOFT_LEFT -> {
                    dialRows.getOrNull(selected)?.let { callNumber(it.second) }
                    return true
                }
                KeyEvent.KEYCODE_SOFT_RIGHT -> { dialBack(); return true }
            }
            Page.SHORTCUTS -> {
                val count = shortcuts.size.coerceAtMost(6)
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (count > 0) selectRow(selected - 1); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (count > 0) selectRow(selected + 1); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { openSelected(shortcuts.mapNotNull { id -> apps.find { it.packageName == id } }); return true }
                    KeyEvent.KEYCODE_SOFT_LEFT -> { showOptions(); return true }
                }
            }
            Page.APPS -> {
                val last = apps.lastIndex
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (last >= 0) selectApp((selected - 3).coerceAtLeast(0)); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (last >= 0) selectApp((selected + 3).coerceAtMost(last)); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (last >= 0) selectApp((selected + 1).coerceAtMost(last)); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (last >= 0) selectApp((selected - 1).coerceAtLeast(0)); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { openSelected(apps); return true }
                    KeyEvent.KEYCODE_SOFT_LEFT -> { showOptions(); return true }
                }
            }
            Page.MANAGE -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { selectRow(selected - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { selectRow(selected + 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { toggleManage(); return true }
                KeyEvent.KEYCODE_SOFT_LEFT -> { saveManage(); return true }
            }
            Page.REORDER -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (moving) moveShortcut(-1) else selectRow(selected - 1); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (moving) moveShortcut(1) else selectRow(selected + 1); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { moving = !moving; render(); return true }
                KeyEvent.KEYCODE_SOFT_LEFT -> { saveShortcuts(); moving = false; page = returnPage; render(); return true }
            }
        }
        return false
    }

    // ---------------- dialer ----------------

    private fun isDefaultDialer(): Boolean = try {
        (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).defaultDialerPackage == packageName
    } catch (_: Exception) { false }

    @Suppress("DEPRECATION")
    private fun requestDialerRole() {
        if (isDefaultDialer()) { toast("האפליקציה כבר מוגדרת כאפליקציית הטלפון"); return }
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                val roles = getSystemService(RoleManager::class.java)
                if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_DIALER)) {
                    startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_DIALER), 44)
                    return
                }
            } else {
                startActivityForResult(
                    Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER).putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, packageName), 44)
                return
            }
            toast("המכשיר אינו מאפשר לבחור אפליקציית טלפון")
        } catch (_: Exception) { toast("לא ניתן לבקש הגדרה כאפליקציית טלפון") }
    }

    private fun dialChar(keyCode: Int): Char? = when (keyCode) {
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0' + (keyCode - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_NUMPAD_0..KeyEvent.KEYCODE_NUMPAD_9 -> '0' + (keyCode - KeyEvent.KEYCODE_NUMPAD_0)
        KeyEvent.KEYCODE_STAR -> '*'
        else -> null
    }

    private fun handleDialIntent(i: Intent?): Boolean {
        if (i == null || (i.action != Intent.ACTION_DIAL && i.action != Intent.ACTION_VIEW)) return false
        val data = i.data
        if (data != null && data.scheme != "tel") return false
        dialInput.setLength(0)
        data?.schemeSpecificPart?.let { raw -> dialInput.append(raw.filter { it.isDigit() || it == '*' || it == '#' || it == '+' }) }
        page = Page.DIAL
        return true
    }

    private fun prepareDial() {
        selected = 0
        ensureContactsPermission()
        loadContacts()
        dialRows = computeDialRows()
    }

    private fun ensureContactsPermission() {
        if (contactsPrompted) return
        val missing = listOf(Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE)
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isEmpty()) return
        contactsPrompted = true
        requestPermissions(missing.toTypedArray(), 43)
    }

    private fun dialAppend(c: Char) {
        if (menu != null || (page != Page.HOME && page != Page.DIAL) || dialInput.length >= 30) return
        dialInput.append(c)
        if (page != Page.DIAL) { page = Page.DIAL; prepareDial() } else { selected = 0; dialRows = computeDialRows() }
        render()
    }

    private fun dialPlus() {
        if (dialInput.isNotEmpty() && dialInput.last() == '0') {
            dialInput.setCharAt(dialInput.length - 1, '+')
            selected = 0
            dialRows = computeDialRows()
            render()
        }
    }

    private fun dialBack() {
        if (dialInput.length > 1) {
            dialInput.setLength(dialInput.length - 1)
            selected = 0
            dialRows = computeDialRows()
        } else {
            dialInput.setLength(0)
            page = Page.HOME
            selected = 0
        }
        render()
    }

    private fun clearDial() {
        dialInput.setLength(0)
        dialRows = emptyList()
        page = Page.HOME
        selected = 0
        render()
    }

    private val t9 = HashMap<Char, Char>().apply {
        val groups = mapOf(
            // Hebrew: 3 אבג, 2 דהו, 6 זחט, 5 יכל, 4 מנ, 9 סעפ, 8 צק, 7 רשת (final letters share their key); English as usual
            '2' to "abcדהו", '3' to "defאבג", '4' to "ghiמםנן", '5' to "jklיכךל",
            '6' to "mnoזחט", '7' to "pqrsרשת", '8' to "tuvצץק", '9' to "wxyzסעפף"
        )
        for ((digit, letters) in groups) for (ch in letters) put(ch, digit)
    }

    private fun t9Code(text: String): String {
        val out = StringBuilder()
        for (ch in text.lowercase()) t9[ch]?.let { out.append(it) }
        return out.toString()
    }

    private fun loadContacts() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) { contacts = emptyList(); return }
        if (contacts.isNotEmpty() && System.currentTimeMillis() - contactsLoadedAt < 60_000L) return
        val list = mutableListOf<ContactEntry>()
        val seen = HashSet<String>()
        try {
            contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
                null, null, ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " COLLATE LOCALIZED ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(0)?.trim().orEmpty()
                    val number = c.getString(1)?.trim().orEmpty()
                    if (name.isEmpty() || number.isEmpty()) continue
                    var digits = number.filter { it.isDigit() }
                    if (digits.startsWith("972")) digits = "0" + digits.removePrefix("972")
                    if (!seen.add(name + "|" + digits)) continue
                    val words = name.split(' ', '-', '.').filter { it.isNotEmpty() }
                    list.add(ContactEntry(name, number, digits, words.map { t9Code(it) }.filter { it.isNotEmpty() }, t9Code(name)))
                }
            }
        } catch (_: Exception) { }
        contacts = list
        contactsLoadedAt = System.currentTimeMillis()
    }

    /** First row: the typed number. Then contacts matching by name (T9 keys) or by number. */
    private fun computeDialRows(): List<Pair<String, String>> {
        val input = dialInput.toString()
        if (input.isEmpty()) return emptyList()
        val rows = mutableListOf("חייג $input" to input)
        if (!input.all { it.isDigit() }) return rows
        val byNamePrefix = mutableListOf<ContactEntry>()
        val byNameAny = mutableListOf<ContactEntry>()
        val byNumber = mutableListOf<ContactEntry>()
        for (c in contacts) {
            when {
                c.wordCodes.any { it.startsWith(input) } || c.fullCode.startsWith(input) -> byNamePrefix.add(c)
                c.fullCode.contains(input) -> byNameAny.add(c)
                c.digits.contains(input) -> byNumber.add(c)
            }
        }
        for (c in (byNamePrefix + byNameAny + byNumber).take(30)) rows.add("${c.name}  ${c.number}" to c.number)
        return rows
    }

    private fun callNumber(number: String) {
        if (number.isBlank()) return
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) {
            pendingCall = number
            requestPermissions(arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS), 43)
            return
        }
        try {
            (getSystemService(Context.TELECOM_SERVICE) as TelecomManager).placeCall(Uri.fromParts("tel", number, null), Bundle())
            clearDial()
        } catch (_: SecurityException) { toast("אין הרשאה לחייג") }
        catch (_: Exception) { toast("לא ניתן לחייג") }
    }

    private fun renderDial(frame: FrameLayout) {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(6), dp(12), dp(4)) }
        wrapper.addView(label(dialInput.toString().ifEmpty { " " }, 32, WHITE, true).apply {
            gravity = Gravity.CENTER
            textDirection = View.TEXT_DIRECTION_LTR
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.START
        }, LinearLayout.LayoutParams(-1, dp(56)))
        wrapper.addView(View(this).apply { setBackgroundColor(YELLOW) }, LinearLayout.LayoutParams(-1, dp(2)).apply { bottomMargin = dp(6) })
        listRows.clear()
        val scroll = ScrollView(this).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
        listScroll = scroll
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        dialRows.forEachIndexed { i, entry ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_RTL
                setPadding(dp(7), 0, dp(7), 0)
                setBackgroundColor(if (i == selected) YELLOW else if (i % 2 == 0) 0xFF101315.toInt() else BLACK)
            }
            row.addView(View(this), LinearLayout.LayoutParams(dp(4), 1))
            row.addView(label(entry.first, 15, if (i == selected) BLACK else WHITE).apply {
                gravity = Gravity.CENTER_VERTICAL; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(dp(9), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, -1, 1f))
            listRows.add(row)
            list.addView(row, LinearLayout.LayoutParams(-1, dp(46)).apply { bottomMargin = dp(1) })
        }
        scroll.addView(list)
        wrapper.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val noContacts = ContextCompat.checkSelfPermission(this, Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED
        val hint = if (noContacts) "אשר גישה לאנשי קשר כדי לחפש לפי שם" else "↑↓ בחירה · חייג או אישור להתקשרות · אחורה מוחק"
        wrapper.addView(label(hint, 11, GRAY).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(-1, dp(24)))
        frame.addView(wrapper, FrameLayout.LayoutParams(-1, -1))
        scroll.post { list.getChildAt(selected)?.let { scroll.scrollTo(0, it.top) } }
    }

    private fun focusedCard(): CardsView.Kind? = cardsView?.kinds?.getOrNull(cardFocus)

    private fun moveCardFocus(delta: Int) {
        val cards = cardsView ?: return
        val next = (cardFocus + delta).coerceIn(-1, cards.kinds.size - 1)
        if (next == cardFocus) return
        cardFocus = next
        cards.rebuild(cardFocus)
    }

    private fun refreshCards() {
        if (!::root.isInitialized || page != Page.HOME) return
        val cards = cardsView ?: return
        cards.rebuild(cardFocus)
        cardFocus = cards.focus
    }

    private fun sendPending(pending: PendingIntent?) {
        if (pending == null) { toast("אי אפשר לפתוח את ההתראה"); return }
        try { pending.send(this, 0, null, null, null, null, noAnimation()) } catch (_: Exception) { toast("אי אפשר לפתוח את ההתראה") }
    }

    private fun activateCard() {
        when (focusedCard()) {
            CardsView.Kind.CALL -> startQuiet(Intent(this, CallActivity::class.java), "אי אפשר לפתוח את השיחה")
            CardsView.Kind.CALLS -> sendPending(NotificationHub.calls?.intent)
            CardsView.Kind.MESSAGES -> sendPending(NotificationHub.messages?.intent)
            CardsView.Kind.MEDIA -> NotificationHub.togglePlay()
            null -> { }
        }
    }

    private fun notificationAccess(): Boolean =
        androidx.core.app.NotificationManagerCompat.getEnabledListenerPackages(this).contains(packageName)

    private fun appsForPage(): List<LaunchableApp> = when (page) {
        Page.APPS, Page.MANAGE -> apps
        Page.SHORTCUTS, Page.REORDER -> shortcuts.mapNotNull { id -> apps.find { it.packageName == id } }
        else -> emptyList()
    }

    /** Long press on the center key opens the app menu (open / details / remove). */
    private fun longPressCenter() {
        if (menu != null || centerLongPressHandled) return
        if (page == Page.HOME) {
            // Long press on the song card opens the music app.
            if (cardFocus >= 0) {
                centerLongPressHandled = true
                if (focusedCard() == CardsView.Kind.MEDIA) NotificationHub.openMediaApp(this)
            }
            return
        }
        val app = appsForPage().getOrNull(selected) ?: return
        centerLongPressHandled = true
        showAppActions(app)
    }

    override fun onKeyLongPress(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) { longPressCenter(); return true }
        if (keyCode == KeyEvent.KEYCODE_0 && page == Page.DIAL) { dialPlus(); return true }
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            try { startActivity(Intent(this, PowerMenuActivity::class.java), noAnimation()) } catch (_: Exception) { }
            return true
        }
        return super.onKeyLongPress(keyCode, event)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val repeat = event?.repeatCount ?: 0
        if (keyCode == KeyEvent.KEYCODE_HOME) { goHome(); return true }
        if (keyCode == KeyEvent.KEYCODE_POWER) {
            if (repeat == 0) event?.startTracking()
            return super.onKeyDown(keyCode, event)
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            if (repeat == 0) { centerLongPressHandled = false; event?.startTracking() }
            else if (repeat == 1) longPressCenter()   // fallback for keypads that do not flag long presses
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) { goBack(); return true }
        if (menu == null && (page == Page.HOME || page == Page.DIAL)) {
            if (keyCode == KeyEvent.KEYCODE_POUND) return true      // typed when the key is released
            val c = dialChar(keyCode)
            if (c != null) {
                if (repeat == 0) dialAppend(c)
                else if (repeat == 1 && c == '0') dialPlus()
                return true
            }
        }
        if (menu != null) return if (menuKey(keyCode)) true else super.onKeyDown(keyCode, event)
        return if (navigate(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_POUND && menu == null && (page == Page.HOME || page == Page.DIAL)) {
            dialAppend('#')
            return true
        }
        if (keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER) {
            val wasLongPress = centerLongPressHandled || event?.isCanceled == true
            centerLongPressHandled = false
            if (!wasLongPress) { if (menu != null) activateMenu() else navigate(keyCode) }
            return true
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}

/** Small Android Canvas port of the custom signal, Wi-Fi, Bluetooth and battery marks in NokiaMusic. */
internal class NokiaStatusGlyph(context: android.content.Context, private val kind: String) : View(context) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val sx = width.toFloat(); val sy = height.toFloat()
        if (sx <= 0f || sy <= 0f) return
        stroke.strokeWidth = sx * .085f
        stroke.color = Color.WHITE
        fill.color = Color.WHITE
        when (kind) {
            "signal" -> for (i in 0..3) {
                val bh = sy * (.32f + .2f * i)
                fill.color = if (i < (try { if (Build.VERSION.SDK_INT >= 28) ((context.getSystemService(android.content.Context.TELEPHONY_SERVICE) as? TelephonyManager)?.signalStrength?.level ?: 0) else 0 } catch (_: Exception) { 0 })) Color.WHITE else 0x667F8588
                canvas.drawRoundRect(RectF(sx * (.08f + i * .23f), sy * .92f - bh, sx * (.24f + i * .23f), sy * .92f), sx * .06f, sx * .06f, fill)
            }
            "wifi" -> {
                val cx = sx * .5f; val cy = sy * .88f
                for (k in 0..2) {
                    stroke.color = if (k < (try { val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager; val info = wm?.connectionInfo; if (wm?.isWifiEnabled == true && info != null && info.networkId != -1) { val lvl = WifiManager.calculateSignalLevel(info.rssi, 5); if (lvl >= 4) 3 else if (lvl >= 2) 2 else if (lvl >= 1) 1 else 0 } else 0 } catch (_: Exception) { 0 })) Color.WHITE else 0x667F8588
                    val r = sx * (.26f + .2f * k)
                    canvas.drawArc(RectF(cx-r, cy-r, cx+r, cy+r), -135f, 90f, false, stroke)
                }
                fill.color = if (runCatching { val wm = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? WifiManager; val info = wm?.connectionInfo; wm?.isWifiEnabled == true && info != null && info.networkId != -1 }.getOrDefault(false)) Color.WHITE else 0x667F8588
                canvas.drawCircle(cx, cy, sx * .07f, fill)
            }
            "bluetooth" -> {
                stroke.color = if (runCatching { BluetoothAdapter.getDefaultAdapter()?.isEnabled == true }.getOrDefault(false)) Color.WHITE else 0x667F8588; val p = Path().apply { moveTo(sx*.27f,sy*.30f); lineTo(sx*.73f,sy*.70f); lineTo(sx*.50f,sy*.90f); lineTo(sx*.50f,sy*.10f); lineTo(sx*.73f,sy*.30f); lineTo(sx*.27f,sy*.70f) }
                canvas.drawPath(p, stroke)
            }
            "battery" -> {
                val batteryState=runCatching { context.registerReceiver(null, android.content.IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull(); val batteryLevel=(batteryState?.let { val raw=it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL,-1); val scale=it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE,100); if (raw >= 0 && scale > 0) (raw*100/scale).coerceIn(0,100) else 0 } ?: 0); val charging=batteryState?.let { val s=it.getIntExtra(android.os.BatteryManager.EXTRA_STATUS,-1); s == android.os.BatteryManager.BATTERY_STATUS_CHARGING || s == android.os.BatteryManager.BATTERY_STATUS_FULL } == true; val bw=sx*.84f; val bh=sy*.56f; val top=(sy-bh)/2
                stroke.color=0x99FFFFFF.toInt(); stroke.strokeWidth=sx*.06f
                canvas.drawRoundRect(RectF(0f,top,bw,top+bh),bh*.28f,bh*.28f,stroke)
                fill.color=Color.WHITE
                canvas.drawRoundRect(RectF(sx*.07f,top+sx*.07f,sx*.07f+(bw-sx*.21f)*(batteryLevel/100f),top+bh-sx*.07f),bh*.22f,bh*.22f,fill)
                if (charging) { fill.color=if (batteryLevel >= 60) BLACK else Color.WHITE; val bolt=Path().apply { moveTo(sx*.51f,top+bh*.18f); lineTo(sx*.39f,top+bh*.53f); lineTo(sx*.49f,top+bh*.53f); lineTo(sx*.44f,top+bh*.82f); lineTo(sx*.64f,top+bh*.43f); lineTo(sx*.54f,top+bh*.43f); close() }; canvas.drawPath(bolt,fill) }; fill.color=0x99FFFFFF.toInt()
                canvas.drawRoundRect(RectF(bw+sx*.02f,sy*.4f,bw+sx*.09f,sy*.6f),sx*.03f,sx*.03f,fill)
            }
        }
    }
}

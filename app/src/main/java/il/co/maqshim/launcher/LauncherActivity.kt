package il.co.maqshim.launcher

import android.Manifest
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
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
import androidx.activity.ComponentActivity
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
private enum class Page { HOME, SHORTCUTS, APPS, MANAGE, REORDER }

class LauncherActivity : ComponentActivity() {
    private lateinit var root: LinearLayout
    private val prefs by lazy { getSharedPreferences("launcher", Context.MODE_PRIVATE) }
    private var page = Page.HOME
    private var returnPage = Page.HOME
    private var selected = 0
    private var apps: List<LaunchableApp> = emptyList()
    private var shortcuts: MutableList<String> = mutableListOf()
    private var pendingShortcuts: MutableSet<String> = mutableSetOf()
    private var permissionPrompted = false
    private val handler = Handler(Looper.getMainLooper())
    private val clockTick = object : Runnable {
        override fun run() {
            if (page == Page.HOME) render()
            handler.postDelayed(this, 30_000L)
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
        readApps()
        readShortcuts()
        render()
    }

    override fun onResume() {
        super.onResume()
        handler.removeCallbacks(clockTick)
        handler.post(clockTick)
        readApps()
        readShortcuts()
        if (::root.isInitialized) render()
    }

    override fun onPause() {
        handler.removeCallbacks(clockTick)
        super.onPause()
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
            Page.HOME -> renderHome(body)
            Page.SHORTCUTS -> renderShortcuts(body)
            Page.APPS -> renderApps(body)
            Page.MANAGE -> renderManage(body)
            Page.REORDER -> renderReorder(body)
        }
        root.addView(softKeys(), LinearLayout.LayoutParams(-1, dp(43)))
    }

    private fun statusBar(): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LTR
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(9), 0, dp(9), 0)
        setBackgroundColor(0xFF030405.toInt())
        val brand = label("NOKIA", 11, YELLOW, true).apply { gravity = Gravity.CENTER_VERTICAL }
        addView(brand, LinearLayout.LayoutParams(dp(54), -1))
        addView(View(this@LauncherActivity), LinearLayout.LayoutParams(0, 1, 1f))
        addView(statusGlyph("signal"), LinearLayout.LayoutParams(dp(19), dp(17)))
        addView(statusGlyph("wifi"), LinearLayout.LayoutParams(dp(19), dp(17)))
        addView(statusGlyph("bluetooth"), LinearLayout.LayoutParams(dp(17), dp(17)))
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
        frame.addView(content, FrameLayout.LayoutParams(-1, -1))
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
            setImageResource(if (simLabel == "הכנס כרטיס SIM") R.drawable.sim_missing else R.drawable.sim_card)
            contentDescription = "כרטיס SIM"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        simRow.addView(simIcon, LinearLayout.LayoutParams(dp(37), dp(32)))
        val simText = label(simLabel, 15, WHITE).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(9), 0, 0, 0) }
        simRow.addView(simText, LinearLayout.LayoutParams(-2, dp(36)))
        content.addView(simRow, LinearLayout.LayoutParams(-1, dp(44)))
        content.addView(View(this), LinearLayout.LayoutParams(1, 0, 0.72f))
        if (simLabel == "הרשאה נדרשת לשם המפעיל" && !permissionPrompted) {
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
        val scroll = ScrollView(this).apply { isFillViewport = true; overScrollMode = View.OVER_SCROLL_NEVER }
        val grid = GridLayout(this).apply {
            columnCount = 3
            rowCount = (apps.size + 2) / 3
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        val cellHeight = ((resources.displayMetrics.heightPixels / resources.displayMetrics.density - 27f - 43f - 27f - 20f) / 3f)
            .toInt().coerceIn(80, 116)
        apps.forEachIndexed { index, app ->
            val cell = appCell(app, index == selected, index, (cellHeight * .38f).toInt().coerceIn(28, 43))
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
        scroll.post { if (selected in 0 until apps.size) scroll.smoothScrollTo(0, grid.getChildAt(selected).top.coerceAtLeast(0)) }
    }

    private fun appCell(app: LaunchableApp, isSelected: Boolean, index: Int, iconDp: Int): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(3), dp(5), dp(3), dp(4))
            setBackgroundColor(if (isSelected) YELLOW else 0xFF141719.toInt())
            contentDescription = "${app.label}${if (isSelected) ", נבחר" else ""}"
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        box.addView(ImageView(this).apply { setImageDrawable(app.icon); contentDescription = app.label }, LinearLayout.LayoutParams(dp(iconDp), dp(iconDp)))
        box.addView(label(app.label, 12, if (isSelected) BLACK else WHITE).apply { gravity = Gravity.CENTER; maxLines = 2; textAlignment = View.TEXT_ALIGNMENT_CENTER; ellipsize = android.text.TextUtils.TruncateAt.END; setPadding(0, dp(4), 0, 0) }, LinearLayout.LayoutParams(-1, -2))
        return box
    }

    private fun listPage(frame: FrameLayout, title: String, items: List<LaunchableApp>, selection: Int, checks: Boolean, staged: Set<String> = emptySet()) {
        val wrapper = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(12), dp(7), dp(12), dp(4)) }
        wrapper.addView(label(title, 18, YELLOW, true).apply { gravity = Gravity.RIGHT; setPadding(0, 0, 0, dp(5)) }, LinearLayout.LayoutParams(-1, dp(32)))
        val scroll = ScrollView(this).apply { isFillViewport = false; overScrollMode = View.OVER_SCROLL_NEVER }
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
        scroll.post { if (selection in 0 until items.size) scroll.smoothScrollTo(0, list.getChildAt(selection).top) }
    }

    private fun renderManage(frame: FrameLayout) {
        listPage(frame, "נהל קיצורים · עד 6", apps, selected, true, pendingShortcuts)
    }

    private fun renderReorder(frame: FrameLayout) {
        val ordered = shortcuts.mapNotNull { id -> apps.find { it.packageName == id } }
        listPage(frame, "סדר קיצורים", ordered, selected, false)
        if (moving) centeredOverlay(frame, "בחר יעד בעזרת ↑↓\nאישור לשחרור")
    }

    private var moving = false

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
            Page.HOME -> "▤  קיצורים"
            Page.MANAGE, Page.REORDER -> "✓  שמירה"
            else -> "☰  אפשרויות"
        }
        val right = if (page == Page.HOME) "♙  אנשי קשר" else "‹  אחורה"
        val leftBox = LinearLayout(this@LauncherActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        if (page == Page.HOME) leftBox.addView(ImageView(this@LauncherActivity).apply { setImageResource(R.drawable.shortcuts); contentDescription = "קיצורים" }, LinearLayout.LayoutParams(dp(24), dp(24)))
        leftBox.addView(label(left, 13, WHITE, true).apply { gravity = Gravity.CENTER_VERTICAL; contentDescription = "מקש שמאל: $left" }, LinearLayout.LayoutParams(-2, -1))
        addView(leftBox, LinearLayout.LayoutParams(0, -1, 1f))
        val rightBox = LinearLayout(this@LauncherActivity).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT; layoutDirection = View.LAYOUT_DIRECTION_LTR }
        if (page == Page.HOME) rightBox.addView(ImageView(this@LauncherActivity).apply { setImageResource(R.drawable.contacts); contentDescription = "אנשי קשר" }, LinearLayout.LayoutParams(dp(22), dp(22)))
        rightBox.addView(label(right, 13, WHITE, true).apply { gravity = Gravity.CENTER_VERTICAL or Gravity.RIGHT; contentDescription = right }, LinearLayout.LayoutParams(-2, -1))
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

    private fun showOptions() {
        val actions = if (page == Page.SHORTCUTS) arrayOf("נהל", "סדר") else arrayOf("נהל יישומים", "סדר קיצורים")
        AlertDialog.Builder(this).setItems(actions) { _, which ->
            if (which == 0) enterManage() else enterReorder()
        }.setOnCancelListener { render() }.show().also { it.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL }
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
        try {
            val launch = packageManager.getLaunchIntentForPackage(app.packageName)
            if (launch == null) { toast("אי אפשר לפתוח את ${app.label}"); return }
            launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(launch)
        } catch (_: ActivityNotFoundException) { toast("האפליקציה אינה זמינה להפעלה") }
        catch (_: SecurityException) { toast("אין הרשאה לפתוח את האפליקציה") }
        catch (_: Exception) { toast("לא ניתן לפתוח את ${app.label}") }
    }

    private fun openContacts() {
        val intents = listOf(
            Intent(Intent.ACTION_VIEW, ContactsContract.Contacts.CONTENT_URI),
            Intent(Intent.ACTION_MAIN).addCategory("android.intent.category.APP_CONTACTS")
        )
        for (intent in intents) try {
            startActivity(intent)
            return
        } catch (_: ActivityNotFoundException) { }
        catch (_: SecurityException) { }
        toast("לא נמצאה אפליקציית אנשי קשר")
    }

    private fun requestPhonePermission() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) { render(); return }
        AlertDialog.Builder(this)
            .setTitle("הרשאת פרטי SIM")
            .setMessage("ההרשאה נדרשת רק להצגת שם חברת התקשורת בשורת הבית. אפשר להמשיך להשתמש במקשים גם בלי לאשר.")
            .setNegativeButton("לא עכשיו") { _, _ -> render() }
            .setPositiveButton("המשך") { _, _ ->
        requestPermissions(arrayOf(Manifest.permission.READ_PHONE_STATE), 41)
            }.show().also { it.window?.decorView?.layoutDirection = View.LAYOUT_DIRECTION_RTL }
    }

    @Deprecated("Permission result callback")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 41) {
            if (grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) toast("שם המפעיל יוצג במסך הבית")
            else toast("ההרשאה נדחתה; פרטי המפעיל לא יוצגו")
            render()
        }
    }

    private fun toast(message: String) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    private fun goBack() {
        when (page) {
            Page.HOME -> openContacts()
            Page.SHORTCUTS, Page.APPS -> { page = Page.HOME; selected = 0; render() }
            Page.MANAGE -> { page = returnPage; render() }
            Page.REORDER -> { moving = false; page = returnPage; render() }
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
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_HOME -> { page = Page.APPS; selected = 0; render(); return true }
                KeyEvent.KEYCODE_SOFT_LEFT -> { page = Page.SHORTCUTS; selected = 0; render(); return true }
                KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> return true
                KeyEvent.KEYCODE_SOFT_RIGHT -> { openContacts(); return true }
            }
            Page.SHORTCUTS -> {
                val count = shortcuts.size.coerceAtMost(6)
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (count > 0) selected = (selected - 1).coerceAtLeast(0); render(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (count > 0) selected = (selected + 1).coerceAtMost(count - 1); render(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { openSelected(shortcuts.mapNotNull { id -> apps.find { it.packageName == id } }); return true }
                    KeyEvent.KEYCODE_SOFT_LEFT -> { showOptions(); return true }
                }
            }
            Page.APPS -> {
                val last = apps.lastIndex
                when (keyCode) {
                    KeyEvent.KEYCODE_DPAD_UP -> { if (last >= 0) selected = (selected - 3).coerceAtLeast(0); render(); return true }
                    KeyEvent.KEYCODE_DPAD_DOWN -> { if (last >= 0) selected = (selected + 3).coerceAtMost(last); render(); return true }
                    KeyEvent.KEYCODE_DPAD_LEFT -> { if (last >= 0) selected = (selected + 1).coerceAtMost(last); render(); return true }
                    KeyEvent.KEYCODE_DPAD_RIGHT -> { if (last >= 0) selected = (selected - 1).coerceAtLeast(0); render(); return true }
                    KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { openSelected(apps); return true }
                    KeyEvent.KEYCODE_SOFT_LEFT -> { showOptions(); return true }
                }
            }
            Page.MANAGE -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { selected = (selected - 1).coerceAtLeast(0); render(); return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { selected = (selected + 1).coerceAtMost(apps.lastIndex.coerceAtLeast(0)); render(); return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { toggleManage(); return true }
                KeyEvent.KEYCODE_SOFT_LEFT -> { saveManage(); return true }
            }
            Page.REORDER -> when (keyCode) {
                KeyEvent.KEYCODE_DPAD_UP -> { if (moving) moveShortcut(-1) else { selected = (selected - 1).coerceAtLeast(0); render() }; return true }
                KeyEvent.KEYCODE_DPAD_DOWN -> { if (moving) moveShortcut(1) else { selected = (selected + 1).coerceAtMost(shortcuts.lastIndex.coerceAtLeast(0)); render() }; return true }
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER -> { moving = !moving; render(); return true }
                KeyEvent.KEYCODE_SOFT_LEFT -> { saveShortcuts(); moving = false; page = returnPage; render(); return true }
            }
        }
        return false
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_ESCAPE) { goBack(); return true }
        return if (navigate(keyCode)) true else super.onKeyDown(keyCode, event)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density + .5f).toInt()
}

/** Small Android Canvas port of the custom signal, Wi-Fi, Bluetooth and battery marks in NokiaMusic. */
private class NokiaStatusGlyph(context: android.content.Context, private val kind: String) : View(context) {
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
                fill.color = if (i < 3) Color.WHITE else 0x66FFFFFF
                canvas.drawRoundRect(RectF(sx * (.08f + i * .23f), sy * .92f - bh, sx * (.24f + i * .23f), sy * .92f), sx * .06f, sx * .06f, fill)
            }
            "wifi" -> {
                val cx = sx * .5f; val cy = sy * .88f
                for (k in 0..2) {
                    stroke.color = if (k < 2) Color.WHITE else 0x66FFFFFF
                    val r = sx * (.26f + .2f * k)
                    canvas.drawArc(RectF(cx-r, cy-r, cx+r, cy+r), -135f, 90f, false, stroke)
                }
                fill.color = YELLOW
                canvas.drawCircle(cx, cy, sx * .07f, fill)
            }
            "bluetooth" -> {
                val p = Path().apply { moveTo(sx*.27f,sy*.30f); lineTo(sx*.73f,sy*.70f); lineTo(sx*.50f,sy*.90f); lineTo(sx*.50f,sy*.10f); lineTo(sx*.73f,sy*.30f); lineTo(sx*.27f,sy*.70f) }
                canvas.drawPath(p, stroke)
            }
            "battery" -> {
                val bw=sx*.84f; val bh=sy*.56f; val top=(sy-bh)/2
                stroke.color=0x99FFFFFF.toInt(); stroke.strokeWidth=sx*.06f
                canvas.drawRoundRect(RectF(0f,top,bw,top+bh),bh*.28f,bh*.28f,stroke)
                fill.color=YELLOW
                canvas.drawRoundRect(RectF(sx*.07f,top+sx*.07f,bw-sx*.14f,top+bh-sx*.07f),bh*.22f,bh*.22f,fill)
                fill.color=0x99FFFFFF.toInt()
                canvas.drawRoundRect(RectF(bw+sx*.02f,sy*.4f,bw+sx*.09f,sy*.6f),sx*.03f,sx*.03f,fill)
            }
        }
    }
}

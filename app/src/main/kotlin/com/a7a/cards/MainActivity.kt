package com.a7a.cards

import android.Manifest
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import java.util.UUID
import java.util.regex.Pattern

class MainActivity : ComponentActivity() {
    data class Card(val id: String, val network: String, val number: String, var used: Boolean)

    private val prefs by lazy { getSharedPreferences("cards", MODE_PRIVATE) }
    private val cards = mutableListOf<Card>()
    private lateinit var listContainer: LinearLayout
    private lateinit var searchInput: EditText
    private lateinit var statsText: TextView
    private lateinit var emptyText: TextView
    private var filter = FILTER_ALL
    private val handler = Handler(Looper.getMainLooper())
    private var autoRunning = false
    private var autoCards = emptyList<Card>()
    private var autoIndex = 0
    private var pendingCode: String? = null

    private val networks = listOf("Orange", "Vodafone", "WE", "e& Egypt")
    private val prefixes = mapOf("Orange" to "#102*%s#", "Vodafone" to "*858*%s#", "WE" to "*555*%s#", "e& Egypt" to "*556*%s#")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        loadCards()
        buildUi()
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(Color.rgb(246, 247, 251))
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(18))
            background = gradient(intArrayOf(Color.rgb(83, 54, 180), Color.rgb(116, 74, 214)), dp(22).toFloat())
        }
        val title = text("A7A Cards", 28f, Color.WHITE, Typeface.BOLD).apply { gravity = Gravity.RIGHT }
        val sub = text("إدارة كروت الشحن بسهولة وأمان", 14f, 0xFFE9E3FF.toInt(), Typeface.NORMAL).apply { gravity = Gravity.RIGHT; setPadding(0, dp(4), 0, 0) }
        header.addView(title, lp(-1, -2)); header.addView(sub, lp(-1, -2))
        root.addView(header, lp(-1, dp(118)))

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(16), dp(14), dp(16), dp(10)) }
        val statsCard = MaterialCardView(this).apply { radius = dp(18).toFloat(); cardElevation = dp(2).toFloat(); setCardBackgroundColor(Color.WHITE) }
        val stats = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(8), dp(10), dp(8), dp(10)) }
        statsText = text("", 14f, 0xFF30303A.toInt(), Typeface.BOLD).apply { gravity = Gravity.CENTER; setPadding(dp(4), dp(6), dp(4), dp(6)) }
        stats.addView(statsText, weightLp())
        statsCard.addView(stats); content.addView(statsCard, lp(-1, dp(72)))

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, dp(12), 0, dp(4)) }
        actions.addView(actionButton("＋ إضافة", 0xFF5E35B1.toInt()) { showAddDialog() }, weightLp())
        actions.addView(actionButton("✉ من رسالة", 0xFF00897B.toInt()) { showExtractDialog() }, weightLp())
        actions.addView(actionButton("⌕ بحث", 0xFF3949AB.toInt()) { searchInput.requestFocus() }, weightLp())
        content.addView(actions, lp(-1, dp(56)))

        searchInput = EditText(this).apply {
            hint = "ابحث برقم الكارت أو الشبكة…"; textSize = 14f; isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT; setPadding(dp(14), 0, dp(14), 0)
            background = rounded(0xFFFFFFFF.toInt(), 0xFFE0E0EA.toInt(), dp(14), dp(1))
        }
        searchInput.addTextChangedListener(object : TextWatcher { override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, a: Int) {}; override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) { renderCards() }; override fun afterTextChanged(e: Editable?) {} })
        content.addView(searchInput, lp(-1, dp(50)))

        val filters = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, dp(10), 0, dp(6)) }
        filters.addView(filterButton("الكل", FILTER_ALL) { setFilter(FILTER_ALL) }, weightLp())
        filters.addView(filterButton("متاح", FILTER_UNUSED) { setFilter(FILTER_UNUSED) }, weightLp())
        filters.addView(filterButton("مستخدم", FILTER_USED) { setFilter(FILTER_USED) }, weightLp())
        content.addView(filters, lp(-1, dp(48)))

        val bulk = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        bulk.addView(actionButton("⚡ شحن كل المتاح", 0xFFEF6C00.toInt()) { confirmAutoCharge() }, weightLp())
        bulk.addView(actionButton("🗑 مسح المستخدم", 0xFFC62828.toInt()) { confirmDeleteUsed() }, weightLp())
        content.addView(bulk, lp(-1, dp(54)))

        val scroll = ScrollView(this).apply { isFillViewport = true }
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(0, dp(4), 0, dp(24)) }
        scroll.addView(listContainer)
        content.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val dial = actionButton("⌨ لوحة الاتصال", 0xFF37474F.toInt()) { showDialPad() }
        content.addView(dial, lp(-1, dp(50)))
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        renderCards()
    }

    private fun renderCards() {
        if (!::listContainer.isInitialized) return
        listContainer.removeAllViews()
        val q = searchInput.text.toString().trim().lowercase()
        val filtered = cards.filter { c -> (filter == FILTER_ALL || (filter == FILTER_USED && c.used) || (filter == FILTER_UNUSED && !c.used)) && (q.isBlank() || c.number.contains(q) || c.network.lowercase().contains(q)) }
        statsText.text = "إجمالي ${cards.size}    •    متاح ${cards.count { !it.used }}    •    مستخدم ${cards.count { it.used }}"
        if (filtered.isEmpty()) {
            emptyText = text(if (cards.isEmpty()) "لا توجد كروت حتى الآن\nأضف كارت يدويًا أو الصق رسالة" else "لا توجد نتائج مطابقة", 16f, 0xFF777783.toInt(), Typeface.NORMAL).apply { gravity = Gravity.CENTER; setPadding(dp(10), dp(35), dp(10), dp(35)) }
            listContainer.addView(emptyText, lp(-1, dp(130))); return
        }
        filtered.sortedBy { it.used }.forEach { card -> listContainer.addView(cardView(card), lp(-1, -2).apply { setMargins(0, dp(8), 0, 0) }) }
    }

    private fun cardView(card: Card): View {
        val outer = MaterialCardView(this).apply { radius = dp(18).toFloat(); cardElevation = dp(2).toFloat(); strokeWidth = dp(1); strokeColor = if (card.used) 0xFFE4E4E8.toInt() else 0xFFD9CCFF.toInt(); setCardBackgroundColor(if (card.used) 0xFFFAFAFB.toInt() else Color.WHITE) }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; setPadding(dp(14), dp(12), dp(14), dp(12)) }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL; gravity = Gravity.CENTER_VERTICAL }
        val network = text(card.network, 17f, 0xFF20202A.toInt(), Typeface.BOLD)
        val status = text(if (card.used) "مستخدم" else "متاح", 12f, if (card.used) 0xFF75757F.toInt() else 0xFF2E7D32.toInt(), Typeface.BOLD).apply { gravity = Gravity.CENTER; background = rounded(if (card.used) 0xFFEDEDF0.toInt() else 0xFFE5F5E8.toInt(), Color.TRANSPARENT, dp(20), 0) ; setPadding(dp(12), dp(5), dp(12), dp(5)) }
        top.addView(network, LinearLayout.LayoutParams(0, -2, 1f)); top.addView(status, lp(-2, -2))
        box.addView(top)
        val num = text(formatNumber(card.number), 20f, 0xFF16161C.toInt(), Typeface.BOLD).apply { gravity = Gravity.CENTER; setPadding(0, dp(12), 0, dp(10)) }
        box.addView(num)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; layoutDirection = View.LAYOUT_DIRECTION_RTL }
        row.addView(smallButton("اشحن") { chargeCard(card) }, weightLp())
        row.addView(smallButton("نسخ") { copy(card.number) }, weightLp())
        row.addView(smallButton(if (card.used) "إرجاع" else "تم الشحن") { card.used = !card.used; saveCards(); renderCards() }, weightLp())
        row.addView(smallButton("حذف") { confirmDelete(card) }, weightLp())
        box.addView(row)
        outer.addView(box); return outer
    }

    private fun showAddDialog() {
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(2)) }
        val spinner = Spinner(this); spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, networks)
        val number = EditText(this).apply { hint = "رقم الكارت"; inputType = InputType.TYPE_CLASS_PHONE; isSingleLine = true }
        layout.addView(spinner); layout.addView(number, lp(-1, dp(56)))
        AlertDialog.Builder(this).setTitle("إضافة كارت").setView(layout).setPositiveButton("إضافة") { _, _ -> addCard(spinner.selectedItem.toString(), normalizeDigits(number.text.toString()).filter { it.isDigit() }) }.setNegativeButton("إلغاء", null).show()
    }

    private fun showExtractDialog() {
        val input = EditText(this).apply { hint = "الصق الرسالة التي تحتوي على الكروت…"; minLines = 5; gravity = Gravity.TOP or Gravity.START; inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE }
        AlertDialog.Builder(this).setTitle("إضافة الكروت من رسالة").setMessage("سيتم استخراج كل أرقام الكروت الموجودة في الرسالة.").setView(input).setPositiveButton("استخراج الكل") { _, _ -> val found = extractAllCardNumbers(input.text.toString()); if (found.isEmpty()) Toast.makeText(this, "لم يتم العثور على كروت.", Toast.LENGTH_LONG).show() else showAddMany(found) }.setNegativeButton("إلغاء", null).show()
    }

    private fun showAddMany(numbers: List<String>) {
        val spinner = Spinner(this); spinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, networks)
        AlertDialog.Builder(this).setTitle("تم العثور على ${numbers.size} كرت").setMessage("اختار الشبكة، وسيتم إضافة كل الأرقام دفعة واحدة.").setView(spinner).setPositiveButton("إضافة الكل") { _, _ ->
            var added = 0; var duplicate = 0
            numbers.forEach { n -> if (cards.any { it.number == n }) duplicate++ else { cards.add(Card(UUID.randomUUID().toString(), spinner.selectedItem.toString(), n, false)); added++ } }
            saveCards(); renderCards(); Toast.makeText(this, "تمت إضافة $added كرت" + if (duplicate > 0) " • مكرر $duplicate" else "", Toast.LENGTH_LONG).show()
        }.setNegativeButton("إلغاء", null).show()
    }

    private fun extractAllCardNumbers(text: String): List<String> {
        val normalized = normalizeDigits(text)
        val compact = normalized.replace(Regex("(?<=\\d)[ -]+(?=\\d)"), "")
        val m = Pattern.compile("(?<!\\d)\\d{12,20}(?!\\d)").matcher(compact)
        val result = linkedSetOf<String>()
        while (m.find()) result.add(m.group())
        return result.toList()
    }

    private fun addCard(network: String, number: String) {
        if (number.length !in 12..20) { Toast.makeText(this, "رقم الكارت غير صحيح.", Toast.LENGTH_SHORT).show(); return }
        if (cards.any { it.number == number }) { Toast.makeText(this, "الكارت موجود بالفعل.", Toast.LENGTH_SHORT).show(); return }
        cards.add(Card(UUID.randomUUID().toString(), network, number, false)); saveCards(); renderCards()
    }

    private fun confirmDelete(card: Card) { AlertDialog.Builder(this).setTitle("حذف الكارت؟").setMessage("سيتم حذف ${card.number}").setPositiveButton("حذف") { _, _ -> cards.removeAll { it.id == card.id }; saveCards(); renderCards() }.setNegativeButton("إلغاء", null).show() }
    private fun confirmDeleteUsed() { val count = cards.count { it.used }; if (count == 0) { Toast.makeText(this, "لا توجد كروت مستخدمة.", Toast.LENGTH_SHORT).show(); return }; AlertDialog.Builder(this).setTitle("مسح الكروت المستخدمة؟").setMessage("سيتم حذف $count كرت مستخدم نهائيًا.").setPositiveButton("مسح الكل") { _, _ -> cards.removeAll { it.used }; saveCards(); renderCards(); Toast.makeText(this, "تم مسح $count كرت مستخدم.", Toast.LENGTH_SHORT).show() }.setNegativeButton("إلغاء", null).show() }

    private fun confirmAutoCharge() { if (autoRunning) { Toast.makeText(this, "الشحن التلقائي يعمل بالفعل.", Toast.LENGTH_SHORT).show(); return }; val available = cards.filter { !it.used }; if (available.isEmpty()) { Toast.makeText(this, "لا توجد كروت متاحة للشحن.", Toast.LENGTH_SHORT).show(); return }; AlertDialog.Builder(this).setTitle("شحن كل الكروت المتاحة").setMessage("سيتم شحن ${available.size} كرت، بفاصل 10 ثوانٍ بين كل كرت. هل تريد البدء؟").setPositiveButton("ابدأ") { _, _ -> startAutoCharge() }.setNegativeButton("إلغاء", null).show() }
    private fun startAutoCharge() { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_AUTO); return }; autoCards = cards.filter { !it.used }; autoIndex = 0; autoRunning = true; chargeNextAuto() }
    private fun chargeNextAuto() { if (!autoRunning) return; if (autoIndex >= autoCards.size) { finishAuto(); return }; val card = autoCards[autoIndex]; val code = String.format(prefixes.getValue(card.network), card.number); if (!dialUssd(code)) { stopAuto(false); Toast.makeText(this, "تعذر بدء الشحن عند الكارت ${card.number}", Toast.LENGTH_LONG).show(); return }; card.used = true; saveCards(); renderCards(); autoIndex++; if (autoIndex < autoCards.size) handler.postDelayed({ chargeNextAuto() }, 10_000L) else finishAuto() }
    private fun finishAuto() { autoRunning = false; handler.removeCallbacksAndMessages(null); Toast.makeText(this, "تم الانتهاء من الشحن التلقائي.", Toast.LENGTH_LONG).show() }
    private fun stopAuto(show: Boolean = true) { val was = autoRunning; autoRunning = false; handler.removeCallbacksAndMessages(null); autoCards = emptyList(); autoIndex = 0; if (show && was) Toast.makeText(this, "تم إيقاف الشحن التلقائي.", Toast.LENGTH_SHORT).show() }

    private fun chargeCard(card: Card) { val code = String.format(prefixes.getValue(card.network), card.number); if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { pendingCode = code; ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL); return }; if (dialUssd(code)) { card.used = true; saveCards(); renderCards() } }
    private fun dialUssd(code: String): Boolean = try { startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(code)}"))); true } catch (_: Exception) { Toast.makeText(this, "تعذر فتح الاتصال.", Toast.LENGTH_LONG).show(); false }
    private fun copy(number: String) { val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager; cm.setPrimaryClip(ClipData.newPlainText("card", number)); Toast.makeText(this, "تم نسخ رقم الكارت", Toast.LENGTH_SHORT).show() }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, results: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, results); if (results.firstOrNull() != PackageManager.PERMISSION_GRANTED) return; if (requestCode == REQUEST_CALL) { pendingCode?.let { dialUssd(it) }; pendingCode = null } else if (requestCode == REQUEST_AUTO) startAutoCharge() }

    private fun showDialPad() { val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(4), dp(18), dp(4)) }; val display = EditText(this).apply { inputType = InputType.TYPE_CLASS_PHONE; textSize = 22f; gravity = Gravity.CENTER; isSingleLine = true }; layout.addView(display, lp(-1, dp(58))); arrayOf(arrayOf("1","2","3"),arrayOf("4","5","6"),arrayOf("7","8","9"),arrayOf("*","0","#")).forEach { keys -> val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }; keys.forEach { k -> row.addView(smallButton(k) { display.append(k) }, weightLp()) }; layout.addView(row, lp(-1, dp(56))) }; layout.addView(actionButton("اتصال", 0xFF5E35B1.toInt()) { val code = display.text.toString(); if (code.isNotBlank()) { if (ContextCompat.checkSelfPermission(this, Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) { pendingCode = code; ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CALL_PHONE), REQUEST_CALL) } else dialUssd(code) } }, lp(-1, dp(52))); AlertDialog.Builder(this).setTitle("لوحة الاتصال").setView(layout).setNegativeButton("إغلاق", null).show() }

    private fun setFilter(value: Int) { filter = value; renderCards() }
    private fun filterButton(label: String, value: Int, action: () -> Unit) = actionButton(label, if (filter == value) 0xFF5E35B1.toInt() else 0xFFE4E4EC.toInt(), action).apply { setTextColor(if (filter == value) Color.WHITE else 0xFF33333D.toInt()) }
    private fun actionButton(label: String, color: Int, action: () -> Unit) = Button(this).apply { text = label; textSize = 12f; typeface = Typeface.DEFAULT_BOLD; isAllCaps = false; setTextColor(Color.WHITE); background = rounded(color, Color.TRANSPARENT, dp(13), 0); setOnClickListener { action() } }
    private fun smallButton(label: String, action: () -> Unit) = Button(this).apply { text = label; textSize = 11f; isAllCaps = false; typeface = Typeface.DEFAULT_BOLD; setTextColor(0xFF34343D.toInt()); background = rounded(0xFFF0F0F5.toInt(), Color.TRANSPARENT, dp(11), 0); setOnClickListener { action() } }
    private fun text(value: String, size: Float, color: Int, style: Int) = TextView(this).apply { text = value; textSize = size; setTextColor(color); typeface = Typeface.create(Typeface.DEFAULT, style) }
    private fun rounded(fill: Int, stroke: Int, radius: Int, strokeWidth: Int) = GradientDrawable().apply { setColor(fill); cornerRadius = radius.toFloat(); if (stroke != Color.TRANSPARENT && strokeWidth > 0) setStroke(strokeWidth, stroke) }
    private fun gradient(colors: IntArray, radius: Float) = GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply { cornerRadius = radius }
    private fun formatNumber(n: String): String = n.chunked(4).joinToString(" ")
    private fun normalizeDigits(s: String): String = s.map { when (it) { in '٠'..'٩' -> ('0'.code + (it.code - '٠'.code)).toChar(); in '۰'..'۹' -> ('0'.code + (it.code - '۰'.code)).toChar(); else -> it } }.joinToString("")
    private fun saveCards() { prefs.edit().putString("data", cards.joinToString("\n") { "${it.id}|${it.network}|${it.number}|${it.used}" }).apply() }
    private fun loadCards() { cards.clear(); prefs.getString("data", "")?.lines()?.filter { it.isNotBlank() }?.forEach { p -> val a = p.split("|"); if (a.size == 4) cards.add(Card(a[0], a[1], a[2], a[3].toBoolean())) } }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h)
    private fun weightLp() = LinearLayout.LayoutParams(0, dp(50), 1f)
    override fun onDestroy() { stopAuto(false); super.onDestroy() }

    companion object { const val REQUEST_CALL = 501; const val REQUEST_AUTO = 502; const val FILTER_ALL = 0; const val FILTER_UNUSED = 1; const val FILTER_USED = 2 }
}

package ir.yar.assistant

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.view.View
import android.view.inputmethod.EditorInfo
import android.webkit.MimeTypeMap
import android.widget.*
import androidx.core.content.FileProvider
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.zip.ZipFile
import kotlin.concurrent.thread

class MainActivity : Activity(), RecognitionListener {
    private lateinit var status: TextView
    private lateinit var input: EditText
    private lateinit var mic: Button
    private lateinit var ad: ArrayAdapter<String>
    private val items = ArrayList<String>()
    private val paths = ArrayList<String?>()
    private var model: Model? = null
    private var speech: SpeechService? = null
    private var tts: TextToSpeech? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 96, 32, 32)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
        }
        status = TextView(this).apply { textSize = 18f; text = "در حال آماده‌سازی…"; setPadding(0, 0, 0, 24) }
        input = EditText(this).apply {
            hint = "دستور را بنویس یا با میکروفون بگو"
            imeOptions = EditorInfo.IME_ACTION_SEND
            setSingleLine(true)
            setOnEditorActionListener { _, _, _ -> submit(); true }
        }
        mic = Button(this).apply { text = "🎤 بگو"; setOnClickListener { toggleMic() } }
        val send = Button(this).apply { text = "ارسال"; setOnClickListener { submit() } }
        val row = LinearLayout(this).apply {
            addView(mic, LinearLayout.LayoutParams(0, -2, 1f))
            addView(send, LinearLayout.LayoutParams(0, -2, 1f))
        }
        ad = ArrayAdapter(this, android.R.layout.simple_list_item_1, items)
        val list = ListView(this)
        list.adapter = ad
        list.setOnItemClickListener { _, _, pos, _ -> paths.getOrNull(pos)?.let { openFile(File(it)) } }
        root.addView(status); root.addView(input); root.addView(row)
        root.addView(list, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)

        requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.READ_CONTACTS, Manifest.permission.CALL_PHONE), 1)
        tts = TextToSpeech(this) { s -> if (s == TextToSpeech.SUCCESS) tts?.setLanguage(Locale("fa")) }
        loadModel()
    }

    // ---------- صدا ----------
    private fun loadModel() = thread {
        try {
            val dir = File(filesDir, "model")
            if (!File(dir, "ok").exists()) {
                dir.deleteRecursively()
                copyAssets("model", dir)
                File(dir, "ok").writeText("1")
            }
            model = Model(dir.absolutePath)
            runOnUiThread { status.text = "آماده‌ام. دکمهٔ میکروفون را بزن و دستور بده." }
        } catch (e: Exception) {
            runOnUiThread { status.text = "مدل صدا بارگذاری نشد؛ فعلاً تایپ کن. (${e.message})" }
        }
    }

    private fun copyAssets(path: String, target: File) {
        val kids = assets.list(path)
        if (kids.isNullOrEmpty()) {
            target.parentFile?.mkdirs()
            assets.open(path).use { i -> target.outputStream().use { i.copyTo(it) } }
        } else {
            target.mkdirs()
            for (k in kids) copyAssets("$path/$k", File(target, k))
        }
    }

    private fun toggleMic() {
        if (speech != null) { stopMic(); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 1); return
        }
        val m = model ?: run { reply("مدل صدا هنوز آماده نیست", false); return }
        try {
            speech = SpeechService(Recognizer(m, 16000f), 16000f).also { it.startListening(this) }
            mic.text = "⏹ دارم گوش می‌دهم…"
        } catch (e: Exception) { reply("میکروفون کار نکرد: ${e.message}", false) }
    }

    private fun stopMic() {
        speech?.stop(); speech?.shutdown(); speech = null
        mic.text = "🎤 بگو"
    }

    override fun onPartialResult(hypothesis: String?) {
        val p = JSONObject(hypothesis ?: "{}").optString("partial")
        if (p.isNotBlank()) status.text = p
    }
    override fun onResult(hypothesis: String?) {
        val t = JSONObject(hypothesis ?: "{}").optString("text")
        if (t.isNotBlank()) { stopMic(); input.setText(t); handle(t) }
    }
    override fun onFinalResult(hypothesis: String?) {}
    override fun onError(exception: Exception?) { status.text = "خطا: ${exception?.message}" }
    override fun onTimeout() { stopMic() }

    override fun onDestroy() {
        super.onDestroy()
        speech?.shutdown(); model?.close(); tts?.shutdown()
    }

    private fun submit() {
        val t = input.text.toString()
        if (t.isNotBlank()) handle(t)
    }

    private fun reply(msg: String, speak: Boolean = true) {
        status.text = msg
        if (speak) tts?.speak(msg, TextToSpeech.QUEUE_FLUSH, null, "y")
    }

    // ---------- تشخیص دستور ----------
    private fun handle(raw: String) {
        val t = norm(raw)
        items.clear(); paths.clear(); ad.notifyDataSetChanged()
        fun has(vararg w: String) = w.any { t.contains(it) }
        try {
            when {
                has("چراغ قوه", "فلش") -> setTorch(!has("خاموش"))
                has("آلارم", "الارم", "بیدارم", "زنگ ساعت", "هشدار") -> alarm(t)
                has("تایمر", "زمان سنج", "زمانسنج") -> timer(t)
                has("پیامک", "اس ام اس", "اسمس") -> sms(t)
                Regex("زنگ بزن|تماس بگیر|^زنگ").containsMatchIn(t) -> call(t)
                has("پیدا", "جستجو", "بگرد", "فایل") -> search(t)
                has("ساعت") && has("چنده", "چند") -> {
                    val c = Calendar.getInstance()
                    reply(String.format(Locale.US, "ساعت %02d:%02d است", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
                }
                has("تاریخ", "چندمه", "چه روزی", "روز هفته") -> date()
                has("صدا") -> volume(t)
                has("وای فای", "وایفای") -> { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); reply("تنظیمات وای‌فای باز شد") }
                has("بلوتوث") -> { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); reply("تنظیمات بلوتوث باز شد") }
                has("موسیقی", "آهنگ") -> {
                    startActivity(Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, Intent.CATEGORY_APP_MUSIC))
                    reply("پخش‌کنندهٔ موسیقی باز شد")
                }
                has("یادداشت", "یادم بنداز") -> notes(t)
                calc(t) -> {}
                has("باز کن", "اجرا کن") -> openApp(t)
                else -> reply("متوجه نشدم. مثلاً بگو: زنگ بزن به علی ، چراغ قوه رو روشن کن ، فایل قرارداد رو پیدا کن")
            }
        } catch (e: Exception) {
            reply("خطا: ${e.message}", false)
        }
    }

    // ---------- کارها ----------
    private fun setTorch(on: Boolean) {
        val cm = getSystemService(CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
        if (id == null) { reply("چراغ قوه پیدا نشد"); return }
        cm.setTorchMode(id, on)
        reply(if (on) "چراغ قوه روشن شد" else "چراغ قوه خاموش شد")
    }

    private fun alarm(t: String) {
        val n = wordsToDigits(t)
        val m = Regex("(\\d{1,2})(?:\\s*و\\s*(نیم|ربع|\\d{1,2}))?").find(n)
        if (m == null) { reply("ساعت را هم بگو. مثلاً: آلارم برای ساعت هفت و نیم صبح"); return }
        var h = m.groupValues[1].toInt()
        val min = when (val g = m.groupValues[2]) { "" -> 0; "نیم" -> 30; "ربع" -> 15; else -> g.toInt() }
        if (t.contains("شب") && h in 6..11) h += 12
        else if ((t.contains("عصر") || t.contains("بعدازظهر") || t.contains("بعد از ظهر")) && h in 1..11) h += 12
        if (h == 12 && t.contains("شب")) h = 0
        if (h > 23 || min > 59) { reply("ساعت نامعتبر است"); return }
        startActivity(Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, h); putExtra(AlarmClock.EXTRA_MINUTES, min)
            putExtra(AlarmClock.EXTRA_MESSAGE, "یار"); putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })
        reply(String.format(Locale.US, "آلارم برای ساعت %02d:%02d تنظیم شد", h, min))
    }

    private fun timer(t: String) {
        val n = wordsToDigits(t)
        var secs = 0
        if (t.contains("نیم ساعت")) secs = 1800
        else if (t.contains("ربع ساعت")) secs = 900
        else Regex("(\\d+)\\s*(ثانیه|دقیقه|ساعت)").find(n)?.let {
            val v = it.groupValues[1].toInt()
            secs = v * when (it.groupValues[2]) { "ثانیه" -> 1; "دقیقه" -> 60; else -> 3600 }
        }
        if (secs <= 0) { reply("مدت را هم بگو. مثلاً: تایمر پنج دقیقه"); return }
        startActivity(Intent(AlarmClock.ACTION_SET_TIMER).apply {
            putExtra(AlarmClock.EXTRA_LENGTH, secs); putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        })
        reply("تایمر تنظیم شد")
    }

    private fun findNumber(name: String): String? {
        if (checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.READ_CONTACTS), 2)
            reply("اجازهٔ دسترسی به مخاطبین را بده و دوباره بگو"); return null
        }
        for (n in listOf(name, name.replace('ی', 'ي').replace('ک', 'ك'))) {
            contentResolver.query(ContactsContract.CommonDataKinds.Phone.CONTENT_URI, arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER), ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME + " LIKE ?", arrayOf("%$n%"), null)
                ?.use { if (it.moveToFirst()) return it.getString(0) }
        }
        reply("مخاطبی با نام «$name» پیدا نشد"); return null
    }

    private fun call(t: String) {
        val name = (Regex("(?:با|به)\\s+(.+?)\\s+(?:زنگ بزن|تماس بگیر)").find(t)
            ?: Regex("(?:زنگ بزن|تماس بگیر|زنگ)\\s*(?:به|با)?\\s*(.+)").find(t))?.groupValues?.get(1)?.trim().orEmpty()
        if (name.isBlank()) { reply("به چه کسی؟"); return }
        val num = findNumber(name) ?: return
        val act = if (checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED) Intent.ACTION_CALL else Intent.ACTION_DIAL
        startActivity(Intent(act, Uri.parse("tel:" + Uri.encode(num))))
        reply("در حال تماس با $name")
    }

    private fun sms(t: String) {
        val m = Regex("(?:پیامک|اس ام اس|اسمس)(?:\\s*(?:بفرست|بده|بنویس|ارسال کن))?\\s*(?:به|برای)?\\s*(.+?)(?:\\s+که\\s+(.+))?$").find(t)
        val name = m?.groupValues?.get(1)?.trim().orEmpty()
        val body = m?.groupValues?.get(2).orEmpty()
        if (name.isBlank()) { reply("به چه کسی؟ مثلاً: پیامک بفرست به علی که سلام"); return }
        val num = findNumber(name) ?: return
        startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(num))).putExtra("sms_body", body))
        reply("پیامک برای $name آماده شد؛ دکمهٔ ارسال را بزن")
    }

    private fun volume(t: String) {
        val am = getSystemService(AUDIO_SERVICE) as AudioManager
        val dir = when {
            t.contains("بی صدا") || t.contains("سایلنت") -> AudioManager.ADJUST_MUTE
            t.contains("بلند") || t.contains("زیاد") || t.contains("بیشتر") || t.contains("بالا") -> AudioManager.ADJUST_RAISE
            t.contains("کم") || t.contains("آروم") || t.contains("آهسته") || t.contains("پایین") -> AudioManager.ADJUST_LOWER
            else -> { reply("بلندتر کنم یا کمتر؟"); return }
        }
        repeat(if (dir == AudioManager.ADJUST_MUTE) 1 else 3) { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, dir, AudioManager.FLAG_SHOW_UI) }
        reply("انجام شد")
    }

    private fun date() {
        val c = Calendar.getInstance()
        val (jy, jm, jd) = toJalali(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH))
        val months = arrayOf("فروردین", "اردیبهشت", "خرداد", "تیر", "مرداد", "شهریور", "مهر", "آبان", "آذر", "دی", "بهمن", "اسفند")
        val days = arrayOf("یکشنبه", "دوشنبه", "سه‌شنبه", "چهارشنبه", "پنجشنبه", "جمعه", "شنبه")
        reply("امروز ${days[c.get(Calendar.DAY_OF_WEEK) - 1]} $jd ${months[jm - 1]} $jy است")
    }

    private fun toJalali(gy: Int, gm: Int, gd: Int): Triple<Int, Int, Int> {
        val g = intArrayOf(0, 31, 59, 90, 120, 151, 181, 212, 243, 273, 304, 334)
        val gy2 = if (gm > 2) gy + 1 else gy
        var days = 355666 + 365 * gy + (gy2 + 3) / 4 - (gy2 + 99) / 100 + (gy2 + 399) / 400 + gd + g[gm - 1]
        var jy = -1595 + 33 * (days / 12053)
        days %= 12053
        jy += 4 * (days / 1461)
        days %= 1461
        if (days > 365) { jy += (days - 1) / 365; days = (days - 1) % 365 }
        val jm = if (days < 186) 1 + days / 31 else 7 + (days - 186) / 30
        val jd = 1 + if (days < 186) days % 31 else (days - 186) % 30
        return Triple(jy, jm, jd)
    }

    private fun notes(t: String) {
        val f = File(filesDir, "notes.txt")
        if (t.contains("یادداشت ها") || t.contains("یادداشتها") || t.contains("یادداشتام") || t.contains("یادداشت هام")) {
            val lines = if (f.exists()) f.readLines().reversed() else emptyList()
            items.addAll(lines); paths.addAll(lines.map { null }); ad.notifyDataSetChanged()
            reply(if (lines.isEmpty()) "یادداشتی نداری" else "${lines.size} یادداشت داری", false)
            return
        }
        val body = Regex("(?:یادداشت کن|یادم بنداز|یادداشت)\\s*(?:که)?\\s*(.*)").find(t)?.groupValues?.get(1)?.trim().orEmpty()
        if (body.isBlank()) { reply("چه چیزی را یادداشت کنم؟"); return }
        f.appendText(body + "\n")
        reply("یادداشت شد")
    }

    private fun calc(t: String): Boolean {
        val n = wordsToDigits(t)
        val m = Regex("(\\d+(?:\\.\\d+)?)\\s*(ضرب در|ضربدر|ضرب|×|\\*|به علاوه|بعلاوه|جمع|\\+|منهای|منها|-|تقسیم بر|تقسیم|÷|/)\\s*(\\d+(?:\\.\\d+)?)").find(n) ?: return false
        val a = m.groupValues[1].toDouble(); val b = m.groupValues[3].toDouble(); val op = m.groupValues[2]
        val r = when {
            op.startsWith("ضرب") || op == "×" || op == "*" -> a * b
            op.contains("علاوه") || op == "جمع" || op == "+" -> a + b
            op.startsWith("منها") || op == "-" -> a - b
            else -> { if (b == 0.0) { reply("تقسیم بر صفر ممکن نیست"); return true }; a / b }
        }
        val s = if (r % 1.0 == 0.0) r.toLong().toString() else String.format(Locale.US, "%.4f", r).trimEnd('0')
        reply("جواب: $s")
        return true
    }

    private fun openApp(t: String) {
        val name = t.replace(Regex("\\s*(رو|را)?\\s*(باز کن|اجرا کن)\\s*$"), "").removePrefix("برنامه ").trim()
        if (name.isBlank()) { reply("کدام برنامه؟"); return }
        val alias = mapOf(
            "اینستاگرام" to "instagram", "تلگرام" to "telegram", "واتساپ" to "whatsapp", "واتس اپ" to "whatsapp",
            "کروم" to "chrome", "دوربین" to "camera", "گالری" to "gallery", "تنظیمات" to "settings",
            "ماشین حساب" to "calculator", "مخاطبین" to "contacts", "یوتیوب" to "youtube", "ایتا" to "eitaa",
            "بله" to "bale", "روبیکا" to "rubika", "نقشه" to "maps", "نشان" to "neshan", "بلد" to "balad",
            "اسنپ" to "snapp", "ساعت" to "clock", "مرورگر" to "browser", "فایل ها" to "files", "پیامک" to "messag"
        )
        val key = alias[name] ?: name
        val pm = packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
        val hit = apps.firstOrNull {
            val l = norm(it.loadLabel(pm).toString())
            l.contains(key) || l.contains(name) || it.activityInfo.packageName.lowercase().contains(key)
        }
        val intent = hit?.let { pm.getLaunchIntentForPackage(it.activityInfo.packageName) }
        if (intent == null) { reply("برنامهٔ «$name» پیدا نشد"); return }
        startActivity(intent)
        reply("«$name» باز شد")
    }

    // ---------- جستجوی فایل ----------
    private fun search(t0: String) {
        if (!Environment.isExternalStorageManager()) {
            reply("برای جستجو در فایل‌ها باید اجازهٔ «دسترسی به همهٔ فایل‌ها» را بدهی. صفحهٔ تنظیمات باز شد؛ آن را روشن کن و دوباره دستور بده.")
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
            return
        }
        val t = t0.replace("پی دی اف", "pdf")
        val pics = listOf("jpg", "jpeg", "png", "webp", "heic")
        val vids = listOf("mp4", "mkv", "3gp", "webm")
        val aud = listOf("mp3", "m4a", "wav", "flac")
        val types = mapOf(
            "pdf" to listOf("pdf"), "عکس" to pics, "تصویر" to pics, "ویدیو" to vids, "فیلم" to vids,
            "ورد" to listOf("docx", "doc"), "اکسل" to listOf("xlsx", "xls"), "موسیقی" to aud, "آهنگ" to aud,
            "ویس" to listOf("ogg", "opus", "m4a", "aac", "amr"), "فشرده" to listOf("zip", "rar", "7z"),
            "زیپ" to listOf("zip", "rar", "7z"), "متنی" to listOf("txt")
        )
        val stop = setOf(
            "پیدا", "کن", "کنم", "بکن", "فایل", "فایلی", "فایلها", "که", "توش", "داخلش", "درونش", "محتوا", "نوشته",
            "نوشتم", "بود", "بودم", "بوده", "را", "رو", "من", "بگرد", "جستجو", "ها", "های", "ی", "با", "در", "از",
            "به", "برام", "برای", "لطفا", "امروز", "دیروز", "هفته", "ماه", "این", "پیش", "اخیر", "گذشته", "آخرین",
            "تو", "توی", "داخل", "اسم", "نام", "یه", "یک", "مال", "بده", "نشون", "نشان", "اون", "کجاست", "گوشی"
        )
        val tokens = t.split(" ").filter { it.isNotBlank() }
        val exts = HashSet<String>()
        val keywords = ArrayList<String>()
        for (tok in tokens) {
            val k = types.keys.firstOrNull { tok.startsWith(it) }
            if (k != null) exts.addAll(types[k]!!) else if (tok !in stop) keywords.add(tok)
        }
        val contentMode = listOf("توش", "داخلش", "درونش", "محتوا", "نوشته", "نوشتم").any { t.contains(it) }
        val day = Calendar.getInstance().apply { set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0) }.timeInMillis
        val now = System.currentTimeMillis()
        var since = 0L
        var until = Long.MAX_VALUE
        when {
            "امروز" in tokens -> since = day
            "دیروز" in tokens -> { since = day - 86_400_000L; until = day }
            "هفته" in tokens -> since = now - 7 * 86_400_000L
            "ماه" in tokens -> since = now - 30 * 86_400_000L
        }
        if ((keywords.isEmpty() && (exts.isEmpty() && since == 0L)) || (contentMode && keywords.isEmpty())) {
            reply("چه فایلی را بگردم؟ مثلاً: فایل قرارداد را پیدا کن"); return
        }
        reply("در حال جستجو…", false)
        thread {
            val textExts = setOf("txt", "md", "csv", "json", "html", "xml", "log", "docx")
            val root = Environment.getExternalStorageDirectory()
            val skip = File(root, "Android")
            val found = ArrayList<File>()
            val start = System.currentTimeMillis()
            for (f in root.walkTopDown().onEnter { !it.name.startsWith(".") && it != skip }.onFail { _, _ -> }) {
                if (System.currentTimeMillis() - start > 90_000 || found.size >= 500) break
                if (!f.isFile) continue
                val ext = f.extension.lowercase()
                if (exts.isNotEmpty() && ext !in exts) continue
                val lm = f.lastModified()
                if (lm < since || lm >= until) continue
                if (contentMode) {
                    if (ext !in textExts || f.length() > 3_000_000) continue
                    val txt = try { norm(readText(f, ext)) } catch (e: Exception) { continue }
                    if (keywords.all { txt.contains(it) }) found.add(f)
                } else {
                    val nm = norm(f.name)
                    if (keywords.all { nm.contains(it) }) found.add(f)
                }
            }
            val top = found.sortedByDescending { it.lastModified() }.take(40)
            runOnUiThread {
                items.clear(); paths.clear()
                top.forEach { items.add("📄 ${it.name}\n${it.parent}"); paths.add(it.absolutePath) }
                ad.notifyDataSetChanged()
                reply(if (top.isEmpty()) "چیزی پیدا نشد" else "${top.size} فایل پیدا شد؛ برای باز کردن روی هرکدام بزن", top.isEmpty())
            }
        }
    }

    private fun readText(f: File, ext: String): String {
        if (ext != "docx") return f.readText()
        ZipFile(f).use { z ->
            val e = z.getEntry("word/document.xml") ?: return ""
            return z.getInputStream(e).bufferedReader().readText().replace(Regex("<[^>]+>"), " ")
        }
    }

    private fun openFile(f: File) {
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(f.extension.lowercase()) ?: "*/*"
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (e: Exception) { reply("برنامه‌ای برای باز کردن این فایل پیدا نشد", false) }
    }

    // ---------- ابزارهای متن فارسی ----------
    private fun norm(s: String): String {
        var r = s.replace('ي', 'ی').replace('ك', 'ک').replace('\u200c', ' ')
        for (i in 0..9) r = r.replace("۰۱۲۳۴۵۶۷۸۹"[i], '0' + i).replace("٠١٢٣٤٥٦٧٨٩"[i], '0' + i)
        return r.lowercase().trim().replace(Regex("\\s+"), " ")
    }

    private val nums = mapOf(
        "صفر" to 0, "یک" to 1, "دو" to 2, "سه" to 3, "چهار" to 4, "پنج" to 5, "شش" to 6, "شیش" to 6, "هفت" to 7,
        "هشت" to 8, "نه" to 9, "ده" to 10, "یازده" to 11, "دوازده" to 12, "سیزده" to 13, "چهارده" to 14,
        "پانزده" to 15, "پونزده" to 15, "شانزده" to 16, "شونزده" to 16, "هفده" to 17, "هجده" to 18, "نوزده" to 19,
        "بیست" to 20, "سی" to 30, "چهل" to 40, "پنجاه" to 50, "شصت" to 60, "هفتاد" to 70, "هشتاد" to 80, "نود" to 90,
        "صد" to 100, "دویست" to 200, "سیصد" to 300, "چهارصد" to 400, "پانصد" to 500, "ششصد" to 600,
        "هفتصد" to 700, "هشتصد" to 800, "نهصد" to 900, "هزار" to 1000
    )

    private fun wordsToDigits(t: String): String {
        val tk = t.split(" ").filter { it.isNotBlank() }
        val out = ArrayList<String>()
        var i = 0
        while (i < tk.size) {
            if (!nums.containsKey(tk[i])) { out.add(tk[i]); i++; continue }
            var cur = 0; var total = 0; var j = i; var last = 0
            while (j < tk.size) {
                val w = tk[j]
                if (w == "و" && j > i && j + 1 < tk.size) {
                    val nx = nums[tk[j + 1]]
                    if (nx != null && ((last >= 20 && last % 10 == 0) || last >= 100) && nx < last) { j++; continue } else break
                }
                val v = nums[w] ?: break
                if (v == 1000) { total += (if (cur == 0) 1 else cur) * 1000; cur = 0 }
                else if (v == 100 && cur in 1..9) cur *= 100
                else cur += v
                last = v
                j++
            }
            out.add((total + cur).toString()); i = j
        }
        return out.joinToString(" ")
    }
}

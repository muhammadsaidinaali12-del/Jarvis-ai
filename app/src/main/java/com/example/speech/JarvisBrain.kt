package com.example.speech

import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlin.random.Random

data class JarvisResponse(
    val spokenText: String,
    val displayText: String,
    val executedActionTitle: String? = null,
    val isActionExecuted: Boolean = false
)

class JarvisBrain(private val context: Context) {

    private val tag = "JarvisBrain"

    private val packageManager: PackageManager =
        context.packageManager

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jokes = listOf(
        "Kenapa programmer suka kopi dingin? Karena mereka tidak suka Java yang panas!",
        "Mengapa robot tidak pernah panik? Karena mereka selalu punya program cadangan di memori utama.",
        "Komputer apa yang paling sopan? Komputer yang selalu bilang Permisi, update tersedia.",
        "Kenapa keyboard sering begadang? Karena dia punya dua shift setiap hari.",
        "Apa bedanya internet dan asisten? Kalau internet cari jawaban, kalau saya setia menemani Anda, Tuan."
    )

    private val quotes = listOf(
        "Teknologi terbaik adalah yang menyederhanakan kehidupan manusia. Teruslah berkarya!",
        "Masa depan bukanlah apa yang kita tunggu, melainkan apa yang kita ciptakan hari ini.",
        "Setiap baris kode dan setiap usaha kecil akan membentuk mahakarya besar di masa depan.",
        "Fokus pada proses, nikmati setiap tantangan, dan biarkan hasil membuktikan kualitas Anda."
    )

    /*
     * =========================================================
     * PROCESS COMMAND
     * =========================================================
     */

    suspend fun processCommand(
        input: String
    ): JarvisResponse = withContext(Dispatchers.Default) {

        val indonesianLocale =
            Locale.forLanguageTag("id-ID")

        val query =
            input.trim()

        if (query.isBlank()) {
            return@withContext JarvisResponse(
                spokenText = "Silakan berikan perintah, Tuan.",
                displayText = "Tidak ada perintah."
            )
        }

        val lower =
            query.lowercase(indonesianLocale)

        /*
         * =====================================================
         * 1. DEVICE / APP ACTIONS
         *
         * WAJIB DIPERIKSA SEBELUM GEMINI.
         *
         * Dengan demikian:
         *
         * "buka youtube"
         * "buka instagram"
         * "buka whatsapp"
         *
         * tidak akan dikirim ke Gemini.
         * =====================================================
         */

        handleDeviceActions(
            lower,
            query
        )?.let {
            return@withContext it
        }

        /*
         * =====================================================
         * 2. TIME & DATE
         * =====================================================
         */

        if (
            matchesAny(
                lower,
                "jam berapa",
                "pukul berapa",
                "waktu sekarang",
                "jam skrg",
                "waktu saat ini"
            )
        ) {

            val timeFormat =
                SimpleDateFormat(
                    "HH:mm",
                    indonesianLocale
                )

            val currentTime =
                timeFormat.format(Date())

            val speech =
                "Saat ini pukul $currentTime Waktu Indonesia Barat, Tuan."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        if (
            matchesAny(
                lower,
                "hari apa",
                "tanggal berapa",
                "hari ini",
                "tanggal hari ini",
                "bulan apa",
                "tahun berapa"
            )
        ) {

            val dateFormat =
                SimpleDateFormat(
                    "EEEE, d MMMM yyyy",
                    indonesianLocale
                )

            val currentDate =
                dateFormat.format(Date())

            val speech =
                "Hari ini adalah $currentDate, Tuan."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        /*
         * =====================================================
         * 3. SYSTEM DIAGNOSTIC
         * =====================================================
         */

        if (
            matchesAny(
                lower,
                "status sistem",
                "status jarvis",
                "diagnostik",
                "kondisi sistem",
                "cek sistem"
            )
        ) {

            val speech =
                "Sistem JARVIS V1 online dan berfungsi optimal. Modul pengenal suara bahasa Indonesia aktif, antarmuka siap, semua subsistem beroperasi."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText =
                    "● DIAGNOSTIK JARVIS V1:\n" +
                    "- Status: ONLINE\n" +
                    "- Bahasa: Indonesia (id-ID)\n" +
                    "- Audio Core: AKTIF\n" +
                    "- Integritas: SIAP"
            )
        }

        /*
         * =====================================================
         * 4. IDENTITY
         * =====================================================
         */

        if (
            matchesAny(
                lower,
                "siapa kamu",
                "kamu siapa",
                "nama kamu",
                "tentang kamu",
                "tentang jarvis",
                "siapa anda"
            )
        ) {

            val speech =
                "Saya adalah JARVIS Versi 1, asisten kecerdasan buatan pribadi Anda berbahasa Indonesia. Saya siap mendengarkan dan membantu perintah suara Anda."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        /*
         * =====================================================
         * 5. GREETINGS
         * =====================================================
         */

        if (
            matchesAny(
                lower,
                "halo jarvis",
                "halo",
                "hai jarvis",
                "hai",
                "hello",
                "hei jarvis"
            )
        ) {

            val speech =
                "Halo Tuan! Sistem JARVIS siap mendengarkan perintah Anda. Ada yang bisa saya bantu?"

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        if (matchesAny(lower, "selamat pagi")) {

            val speech =
                "Selamat pagi, Tuan. Semoga hari Anda produktif dan menyenangkan. Sistem siap membantu Anda."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        if (matchesAny(lower, "selamat siang")) {

            val speech =
                "Selamat siang, Tuan. Semoga aktivitas hari ini berjalan lancar. Bagaimana saya dapat membantu?"

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        if (matchesAny(lower, "selamat sore")) {

            val speech =
                "Selamat sore, Tuan. Ada hal yang perlu saya siapkan?"

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        if (matchesAny(lower, "selamat malam")) {

            val speech =
                "Selamat malam, Tuan. Katakan jika Anda memerlukan sesuatu."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        if (
            matchesAny(
                lower,
                "terima kasih",
                "makasih",
                "thanks",
                "terimakasih"
            )
        ) {

            val speech =
                "Sama-sama, Tuan. Senang dapat selalu siap siaga melayani Anda."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        if (
            matchesAny(
                lower,
                "bagaimana kabarmu",
                "apa kabar",
                "gimana kabarmu"
            )
        ) {

            val speech =
                "Semua sirkuit komputasi saya dalam kondisi prima, Tuan. Terima kasih telah bertanya."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = speech
            )
        }

        /*
         * =====================================================
         * 6. ENTERTAINMENT
         * =====================================================
         */

        if (
            matchesAny(
                lower,
                "ceritakan lelucon",
                "lelucon",
                "jokes",
                "humor",
                "lucu",
                "cerita lucu"
            )
        ) {

            val joke =
                jokes.random()

            return@withContext JarvisResponse(
                spokenText = joke,
                displayText = "JARVIS HUMOR:\n$joke"
            )
        }

        if (
            matchesAny(
                lower,
                "motivasi",
                "kata mutiara",
                "semangat",
                "quote"
            )
        ) {

            val quote =
                quotes.random()

            return@withContext JarvisResponse(
                spokenText = quote,
                displayText = "KATA MOTIVASI:\n\"$quote\""
            )
        }

        if (
            matchesAny(
                lower,
                "lempar koin",
                "koin",
                "putar koin"
            )
        ) {

            val side =
                if (Random.nextBoolean()) {
                    "Gambar"
                } else {
                    "Angka"
                }

            val speech =
                "Koin dilempar. Hasilnya adalah $side, Tuan."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText =
                    "🪙 Hasil Lempar Koin: $side"
            )
        }

        if (
            matchesAny(
                lower,
                "lempar dadu",
                "dadu",
                "kocok dadu"
            )
        ) {

            val dice =
                Random.nextInt(1, 7)

            val speech =
                "Dadu berhenti pada angka $dice, Tuan."

            return@withContext JarvisResponse(
                spokenText = speech,
                displayText =
                    "🎲 Hasil Dadu: $dice"
            )
        }

        /*
         * =====================================================
         * 7. MATH
         * =====================================================
         */

        handleMathCalculations(
            lower
        )?.let {
            return@withContext it
        }

        /*
         * =====================================================
         * 8. GEMINI
         * =====================================================
         */

        val aiResponse =
            queryGeminiIfAvailable(query)

        if (aiResponse != null) {

            return@withContext JarvisResponse(
                spokenText = aiResponse,
                displayText = aiResponse
            )
        }

        /*
         * =====================================================
         * 9. FALLBACK
         * =====================================================
         */

        val defaultSpeech =
            "Perintah '$query' telah diterima oleh JARVIS. Saya siap menerima instruksi selanjutnya."

        return@withContext JarvisResponse(
            spokenText = defaultSpeech,
            displayText =
                "Perintah tercatat: \"$query\"\n\n" +
                "JARVIS V1 siap menjalankan perintah suara lainnya."
        )
    }

    /*
     * =========================================================
     * DEVICE + APPLICATION ACTIONS
     * =========================================================
     */

    private fun handleDeviceActions(
        lower: String,
        rawQuery: String
    ): JarvisResponse? {

        try {

            /*
             * =================================================
             * CALCULATOR
             * =================================================
             */

            if (
                matchesAny(
                    lower,
                    "buka kalkulator",
                    "kalkulator",
                    "buka calculator",
                    "calculator"
                )
            ) {

                val intent =
                    Intent().apply {
                        action =
                            Intent.ACTION_MAIN

                        addCategory(
                            Intent.CATEGORY_APP_CALCULATOR
                        )

                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (
                    startActivitySafely(
                        intent
                    )
                ) {

                    val speech =
                        "Membuka kalkulator, Tuan."

                    return JarvisResponse(
                        spokenText = speech,
                        displayText = speech,
                        executedActionTitle =
                            "Aplikasi Kalkulator",
                        isActionExecuted = true
                    )
                }
            }

            /*
             * =================================================
             * CAMERA
             * =================================================
             */

            if (
                matchesAny(
                    lower,
                    "buka kamera",
                    "kamera",
                    "buka camera",
                    "camera"
                )
            ) {

                val intent =
                    Intent(
                        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                    ).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (
                    startActivitySafely(
                        intent
                    )
                ) {

                    val speech =
                        "Membuka kamera, Tuan."

                    return JarvisResponse(
                        spokenText = speech,
                        displayText = speech,
                        executedActionTitle =
                            "Kamera Perangkat",
                        isActionExecuted = true
                    )
                }
            }

            /*
             * =================================================
             * ALARM / CLOCK
             * =================================================
             */

            if (
                matchesAny(
                    lower,
                    "buka alarm",
                    "buka jam",
                    "setel alarm",
                    "alarm",
                    "jam"
                )
            ) {

                val intent =
                    Intent(
                        AlarmClock.ACTION_SHOW_ALARMS
                    ).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (
                    startActivitySafely(
                        intent
                    )
                ) {

                    val speech =
                        "Membuka jam dan alarm, Tuan."

                    return JarvisResponse(
                        spokenText = speech,
                        displayText = speech,
                        executedActionTitle =
                            "Alarm dan Jam",
                        isActionExecuted = true
                    )
                }
            }

            /*
             * =================================================
             * SETTINGS
             * =================================================
             */

            if (
                matchesAny(
                    lower,
                    "buka pengaturan",
                    "pengaturan",
                    "buka setting",
                    "setting",
                    "settings"
                )
            ) {

                val intent =
                    Intent(
                        Settings.ACTION_SETTINGS
                    ).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (
                    startActivitySafely(
                        intent
                    )
                ) {

                    val speech =
                        "Membuka pengaturan perangkat, Tuan."

                    return JarvisResponse(
                        spokenText = speech,
                        displayText = speech,
                        executedActionTitle =
                            "Pengaturan Sistem",
                        isActionExecuted = true
                    )
                }
            }

            /*
             * =================================================
             * SPECIAL WEB APPS
             *
             * YouTube dan Instagram akan dicoba sebagai
             * aplikasi terlebih dahulu.
             *
             * Jika tidak ditemukan, browser dibuka.
             * =================================================
             */

            val appName =
                extractApplicationName(
                    lower
                )

            if (
                appName.isNotBlank()
            ) {

                val normalizedAppName =
                    normalizeApplicationName(
                        appName
                    )

                /*
                 * Coba cari aplikasi yang benar-benar
                 * terpasang di perangkat.
                 */

                val launchResult =
                    launchInstalledApplication(
                        normalizedAppName
                    )

                if (launchResult != null) {

                    return launchResult
                }

                /*
                 * Jika aplikasi populer tidak ditemukan,
                 * buka situs resminya sebagai fallback.
                 */

                val fallbackUrl =
                    getKnownAppWebUrl(
                        normalizedAppName
                    )

                if (fallbackUrl != null) {

                    val browserIntent =
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(fallbackUrl)
                        ).apply {
                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK
                        }

                    if (
                        startActivitySafely(
                            browserIntent
                        )
                    ) {

                        val speech =
                            "Aplikasi $appName tidak ditemukan. Saya membuka versi web-nya, Tuan."

                        return JarvisResponse(
                            spokenText = speech,
                            displayText = speech,
                            executedActionTitle =
                                "Web $appName",
                            isActionExecuted = true
                        )
                    }
                }
            }

            /*
             * =================================================
             * WEB SEARCH
             * =================================================
             */

            if (
                lower.startsWith("cari ") ||
                lower.startsWith("googling ") ||
                lower.startsWith("browsing ")
            ) {

                val searchTerm =
                    rawQuery.replace(
                        Regex(
                            "^(cari|googling|browsing)\\s+",
                            RegexOption.IGNORE_CASE
                        ),
                        ""
                    ).trim()

                if (
                    searchTerm.isNotBlank()
                ) {

                    val intent =
                        Intent(
                            Intent.ACTION_WEB_SEARCH
                        ).apply {

                            putExtra(
                                SearchManager.QUERY,
                                searchTerm
                            )

                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK
                        }

                    if (
                        !startActivitySafely(
                            intent
                        )
                    ) {

                        val browserIntent =
                            Intent(
                                Intent.ACTION_VIEW,
                                Uri.parse(
                                    "https://www.google.com/search?q=${
                                        Uri.encode(searchTerm)
                                    }"
                                )
                            ).apply {
                                flags =
                                    Intent.FLAG_ACTIVITY_NEW_TASK
                            }

                        startActivitySafely(
                            browserIntent
                        )
                    }

                    val speech =
                        "Mencari $searchTerm di web, Tuan."

                    return JarvisResponse(
                        spokenText = speech,
                        displayText = speech,
                        executedActionTitle =
                            "Pencarian Web: $searchTerm",
                        isActionExecuted = true
                    )
                }
            }

        } catch (e: Exception) {

            Log.e(
                tag,
                "Error triggering device/application action",
                e
            )
        }

        return null
    }

    /*
     * =========================================================
     * EXTRACT APPLICATION NAME
     * =========================================================
     *
     * Contoh:
     *
     * "buka youtube"
     * -> "youtube"
     *
     * "tolong buka instagram"
     * -> "instagram"
     *
     * "jalankan whatsapp"
     * -> "whatsapp"
     *
     * "launch chrome"
     * -> "chrome"
     */

    private fun extractApplicationName(
        lower: String
    ): String {

        val patterns =
            listOf(
                "^buka aplikasi\\s+(.+)$",
                "^bukakan aplikasi\\s+(.+)$",
                "^tolong buka aplikasi\\s+(.+)$",
                "^buka\\s+(.+)$",
                "^bukakan\\s+(.+)$",
                "^tolong buka\\s+(.+)$",
                "^jalankan\\s+(.+)$",
                "^jalankan aplikasi\\s+(.+)$",
                "^buka app\\s+(.+)$",
                "^open\\s+(.+)$",
                "^launch\\s+(.+)$",
                "^run\\s+(.+)$"
            )

        for (
            pattern in patterns
        ) {

            val match =
                Regex(pattern)
                    .find(lower)

            if (
                match != null
            ) {

                val result =
                    match.groupValues
                        .getOrNull(1)
                        ?.trim()
                        ?: ""

                if (
                    result.isNotBlank()
                ) {

                    return result
                }
            }
        }

        return ""
    }

    /*
     * =========================================================
     * NORMALIZE APPLICATION NAME
     * =========================================================
     */

    private fun normalizeApplicationName(
        name: String
    ): String {

        return name
            .lowercase(Locale.forLanguageTag("id-ID"))
            .trim()
            .removePrefix("aplikasi ")
            .removePrefix("app ")
            .removeSuffix(" aplikasi")
            .trim()
    }

    /*
     * =========================================================
     * FIND + LAUNCH INSTALLED APPLICATION
     * =========================================================
     *
     * Ini bagian utama fitur "buka semua aplikasi".
     *
     * JARVIS mengambil daftar aplikasi yang memiliki
     * launcher activity, kemudian membandingkan:
     *
     * - label aplikasi
     * - nama package
     *
     * dengan nama yang diucapkan pengguna.
     */

    private fun launchInstalledApplication(
        requestedName: String
    ): JarvisResponse? {

        val normalizedRequest =
            normalizeApplicationName(
                requestedName
            )

        if (
            normalizedRequest.isBlank()
        ) {
            return null
        }

        val launcherIntent =
            Intent(
                Intent.ACTION_MAIN
            ).apply {
                addCategory(
                    Intent.CATEGORY_LAUNCHER
                )
            }

        val activities =
            try {
                packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.MATCH_ALL
                )
            } catch (e: Exception) {

                Log.e(
                    tag,
                    "Gagal mengambil daftar aplikasi",
                    e
                )

                emptyList()
            }

        if (
            activities.isEmpty()
        ) {
            return null
        }

        /*
         * Kandidat terbaik.
         */

        var bestPackageName: String? =
            null

        var bestLabel: String? =
            null

        var bestScore =
            0

        for (
            info in activities
        ) {

            val applicationInfo =
                info.activityInfo.applicationInfo

            val label =
                try {
                    applicationInfo
                        .loadLabel(packageManager)
                        .toString()
                } catch (
                    e: Exception
                ) {
                    ""
                }

            val labelNormalized =
                label
                    .lowercase(
                        Locale.forLanguageTag("id-ID")
                    )
                    .trim()

            val packageName =
                applicationInfo.packageName
                    .lowercase(
                        Locale.forLanguageTag("id-ID")
                    )

            val score =
                calculateApplicationMatchScore(
                    normalizedRequest,
                    labelNormalized,
                    packageName
                )

            if (
                score > bestScore
            ) {

                bestScore =
                    score

                bestPackageName =
                    applicationInfo.packageName

                bestLabel =
                    label
            }
        }

        /*
         * Score minimum mencegah JARVIS membuka aplikasi
         * yang namanya sebenarnya tidak cocok.
         */

        if (
            bestPackageName.isNullOrBlank() ||
            bestLabel.isNullOrBlank() ||
            bestScore < 50
        ) {
            return null
        }

        return try {

            val launchIntent =
                packageManager
                    .getLaunchIntentForPackage(
                        bestPackageName!!
                    )

            if (
                launchIntent == null
            ) {
                null
            } else {

                launchIntent.apply {

                    flags =
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
                }

                context.startActivity(
                    launchIntent
                )

                val speech =
                    "Membuka $bestLabel, Tuan."

                JarvisResponse(
                    spokenText = speech,
                    displayText = speech,
                    executedActionTitle =
                        "Membuka aplikasi: $bestLabel",
                    isActionExecuted = true
                )
            }

        } catch (e: Exception) {

            Log.e(
                tag,
                "Gagal membuka aplikasi $bestPackageName",
                e
            )

            null
        }
    }

    /*
     * =========================================================
     * APPLICATION MATCH SCORE
     * =========================================================
     */

    private fun calculateApplicationMatchScore(
        requestedName: String,
        label: String,
        packageName: String
    ): Int {

        /*
         * Exact label.
         */

        if (
            label == requestedName
        ) {
            return 100
        }

        /*
         * Label dimulai dengan nama yang diminta.
         */

        if (
            label.startsWith(requestedName)
        ) {
            return 90
        }

        /*
         * Nama yang diminta merupakan seluruh label.
         */

        if (
            label.contains(requestedName)
        ) {
            return 80
        }

        /*
         * Package name exact.
         */

        if (
            packageName == requestedName
        ) {
            return 85
        }

        /*
         * Package name mengandung nama aplikasi.
         */

        if (
            packageName.contains(requestedName)
        ) {
            return 70
        }

        /*
         * Pecah menjadi kata-kata.
         */

        val requestedWords =
            requestedName
                .split(
                    Regex("\\s+")
                )
                .filter {
                    it.length >= 2
                }

        if (
            requestedWords.isNotEmpty() &&
            requestedWords.all {
                label.contains(it)
            }
        ) {
            return 65
        }

        return 0
    }

    /*
     * =========================================================
     * KNOWN APP WEB FALLBACK
     * =========================================================
     */

    private fun getKnownAppWebUrl(
        appName: String
    ): String? {

        return when {

            appName.contains("youtube") ->
                "https://www.youtube.com"

            appName.contains("instagram") ->
                "https://www.instagram.com"

            appName.contains("facebook") ->
                "https://www.facebook.com"

            appName.contains("tiktok") ->
                "https://www.tiktok.com"

            appName.contains("whatsapp") ->
                "https://web.whatsapp.com"

            appName.contains("telegram") ->
                "https://web.telegram.org"

            appName.contains("twitter") ||
            appName == "x" ->
                "https://x.com"

            appName.contains("spotify") ->
                "https://open.spotify.com"

            appName.contains("netflix") ->
                "https://www.netflix.com"

            appName.contains("gmail") ->
                "https://mail.google.com"

            appName.contains("google") ->
                "https://www.google.com"

            else ->
                null
        }
    }

    /*
     * =========================================================
     * SAFE START ACTIVITY
     * =========================================================
     */

    private fun startActivitySafely(
        intent: Intent
    ): Boolean {

        return try {

            if (
                !isIntentResolvable(intent)
            ) {
                return false
            }

            context.startActivity(
                intent
            )

            true

        } catch (e: Exception) {

            Log.e(
                tag,
                "Tidak dapat menjalankan Intent: ${intent.action}",
                e
            )

            false
        }
    }

    /*
     * =========================================================
     * INTENT RESOLUTION
     * =========================================================
     */

    private fun isIntentResolvable(
        intent: Intent
    ): Boolean {

        return try {

            packageManager
                .resolveActivity(
                    intent,
                    PackageManager.MATCH_DEFAULT_ONLY
                ) != null

        } catch (
            e: Exception
        ) {

            false
        }
    }

    /*
     * =========================================================
     * MATCH HELPER
     * =========================================================
     */

    private fun matchesAny(
        value: String,
        vararg options: String
    ): Boolean {

        return options.any {
            value == it ||
                    value.contains(it)
        }
    }

    /*
     * =========================================================
     * MATH CALCULATIONS
     * =========================================================
     */

    private fun handleMathCalculations(
        lower: String
    ): JarvisResponse? {

        val mathPattern =
            Regex(
                "(hitung|berapa)?\\s*" +
                        "(\\d+(?:\\.\\d+)?)\\s*" +
                        "(tambah|\\+|kurang|-|kali|\\*|x|bagi|/)\\s*" +
                        "(\\d+(?:\\.\\d+)?)"
            )

        val match =
            mathPattern.find(lower)
                ?: return null

        val num1 =
            match.groupValues[2]
                .toDoubleOrNull()
                ?: return null

        val operator =
            match.groupValues[3]

        val num2 =
            match.groupValues[4]
                .toDoubleOrNull()
                ?: return null

        val (
            opName,
            result
        ) =
            when (operator) {

                "tambah",
                "+" ->
                    "ditambah" to
                            (num1 + num2)

                "kurang",
                "-" ->
                    "dikurang" to
                            (num1 - num2)

                "kali",
                "*",
                "x" ->
                    "dikali" to
                            (num1 * num2)

                "bagi",
                "/" -> {

                    if (
                        num2 == 0.0
                    ) {

                        val speech =
                            "Maaf Tuan, pembagian dengan angka nol tidak terdefinisi dalam matematika."

                        return JarvisResponse(
                            spokenText = speech,
                            displayText = speech
                        )
                    }

                    "dibagi" to
                            (num1 / num2)
                }

                else ->
                    return null
            }

        val formattedResult =
            if (
                result % 1.0 == 0.0
            ) {

                result
                    .toLong()
                    .toString()

            } else {

                String.format(
                    Locale.forLanguageTag("id-ID"),
                    "%.2f",
                    result
                )
            }

        val speech =
            "Hasil dari $num1 $opName $num2 adalah $formattedResult, Tuan."

        val display =
            "KALKULASI MATEMATIKA:\n" +
                    "$num1 $operator $num2 = $formattedResult"

        return JarvisResponse(
            spokenText = speech,
            displayText = display
        )
    }

    /*
     * =========================================================
     * GEMINI
     * =========================================================
     */

    private suspend fun queryGeminiIfAvailable(
        prompt: String
    ): String? = withContext(Dispatchers.IO) {

        val apiKey =
            BuildConfig.GEMINI_API_KEY

        if (
            apiKey.isBlank() ||
            apiKey == "MY_GEMINI_API_KEY"
        ) {
            return@withContext null
        }

        try {

            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

            val systemInstruction =
                "Kamu adalah JARVIS V1, asisten kecerdasan buatan pribadi futuristik berbahasa Indonesia. " +
                        "Berikan jawaban dalam bahasa Indonesia yang ramah, sopan, langsung ke intinya, " +
                        "dan sangat nyaman didengar via Text-to-Speech. " +
                        "Maksimal 2-3 kalimat pendek. " +
                        "Jangan gunakan tanda bintang, format markdown tebal, atau simbol rumit."

            val jsonBody =
                JSONObject().apply {

                    put(
                        "contents",
                        JSONArray().apply {

                            put(
                                JSONObject().apply {

                                    put(
                                        "parts",
                                        JSONArray().apply {

                                            put(
                                                JSONObject().apply {
                                                    put(
                                                        "text",
                                                        prompt
                                                    )
                                                }
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )

                    put(
                        "systemInstruction",
                        JSONObject().apply {

                            put(
                                "parts",
                                JSONArray().apply {

                                    put(
                                        JSONObject().apply {
                                            put(
                                                "text",
                                                systemInstruction
                                            )
                                        }
                                    )
                                }
                            )
                        }
                    )

                    put(
                        "generationConfig",
                        JSONObject().apply {

                            put(
                                "temperature",
                                0.7
                            )

                            put(
                                "maxOutputTokens",
                                150
                            )
                        }
                    )
                }

            val request =
                Request.Builder()
                    .url(url)
                    .post(
                        jsonBody
                            .toString()
                            .toRequestBody(
                                "application/json"
                                    .toMediaType()
                            )
                    )
                    .build()

            val response =
                okHttpClient
                    .newCall(request)
                    .execute()

            response.use {

                if (
                    !it.isSuccessful
                ) {

                    Log.e(
                        tag,
                        "Gemini HTTP ${it.code}: ${it.message}"
                    )

                    return@withContext null
                }

                val body =
                    it.body?.string()
                        ?: return@withContext null

                val json =
                    JSONObject(body)

                val candidates =
                    json.optJSONArray(
                        "candidates"
                    )
                        ?: return@withContext null

                if (
                    candidates.length() == 0
                ) {
                    return@withContext null
                }

                val firstCandidate =
                    candidates.optJSONObject(0)
                        ?: return@withContext null

                val content =
                    firstCandidate
                        .optJSONObject("content")
                        ?: return@withContext null

                val parts =
                    content.optJSONArray(
                        "parts"
                    )
                        ?: return@withContext null

                if (
                    parts.length() == 0
                ) {
                    return@withContext null
                }

                val text =
                    parts
                        .optJSONObject(0)
                        ?.optString(
                            "text",
                            ""
                        )
                        ?.trim()
                        ?: ""

                if (
                    text.isBlank()
                ) {
                    null
                } else {
                    text
                }
            }

        } catch (
            e: Exception
        ) {

            Log.e(
                tag,
                "Gemini request failed",
                e
            )

            null
        }
    }
}
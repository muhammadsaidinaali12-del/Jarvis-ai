package com.example.speech

import android.app.SearchManager
import android.content.Context
import android.content.Intent
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

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val jokes = listOf(
        "Kenapa programmer suka kopi dingin? Karena mereka tidak suka Java yang panas!",
        "Mengapa robot tidak pernah panik? Karena mereka selalu punya program cadangan di memori utama.",
        "Komputer apa yang paling sopan? Komputer yang selalu bilang 'Permisi, update tersedia'.",
        "Kenapa keyboard sering begadang? Karena dia punya dua shift setiap hari.",
        "Apa bedanya internet dan asisten? Kalau internet cari jawaban, kalau saya setia menemani Anda, Tuan."
    )

    private val quotes = listOf(
        "Teknologi terbaik adalah yang menyederhanakan kehidupan manusia. Teruslah berkarya!",
        "Masa depan bukanlah apa yang kita tunggu, melainkan apa yang kita ciptakan hari ini.",
        "Setiap baris kode dan setiap usaha kecil akan membentuk mahakarya besar di masa depan.",
        "Fokus pada proses, nikmati setiap tantangan, dan biarkan hasil membuktikan kualitas Anda."
    )

    suspend fun processCommand(input: String): JarvisResponse = withContext(Dispatchers.Default) {
        val indonesianLocale = Locale.forLanguageTag("id-ID")
        val query = input.trim()
        val lower = query.lowercase(indonesianLocale)

        // 1. Check for Intent / Device Actions
        handleDeviceActions(lower, query)?.let { return@withContext it }

        // 2. Check for Time & Date
        if (matchesAny(lower, "jam berapa", "pukul berapa", "waktu sekarang", "jam skrg", "waktu saat ini")) {
            val timeFormat = SimpleDateFormat("HH:mm", indonesianLocale)
            val currentTime = timeFormat.format(Date())
            val speech = "Saat ini pukul $currentTime Waktu Indonesia Barat, Tuan."
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        if (matchesAny(lower, "hari apa", "tanggal berapa", "hari ini", "tanggal hari ini", "bulan apa", "tahun berapa")) {
            val dateFormat = SimpleDateFormat("EEEE, d MMMM yyyy", indonesianLocale)
            val currentDate = dateFormat.format(Date())
            val speech = "Hari ini adalah $currentDate, Tuan."
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        // 3. System Diagnostic & Identity
        if (matchesAny(lower, "status sistem", "status jarvis", "diagnostik", "kondisi sistem", "cek sistem")) {
            val speech = "Sistem JARVIS V1 online dan berfungsi optimal. Modul pengenal suara bahasa Indonesia aktif, antarmuka siap, semua subsistem beroperasi 100%."
            return@withContext JarvisResponse(
                spokenText = speech,
                displayText = "● DIAGNOSTIK JARVIS V1:\n- Status: ONLINE\n- Bahasa: Indonesia (id-ID)\n- Audio Core: AKTIF\n- Integritas: 100% SIAP"
            )
        }

        if (matchesAny(lower, "siapa kamu", "kamu siapa", "nama kamu", "tentang kamu", "tentang jarvis", "siapa anda")) {
            val speech = "Saya adalah JARVIS Versi 1, asisten kecerdasan buatan pribadi Anda berbahasa Indonesia. Saya siap mendengarkan dan membantu perintah suara Anda."
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        // 4. Greetings
        if (matchesAny(lower, "halo jarvis", "halo", "hai jarvis", "hai", "hello", "hei jarvis")) {
            val speech = "Halo Tuan! Sistem JARVIS siap mendengarkan perintah Anda. Ada yang bisa saya bantu?"
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        if (matchesAny(lower, "selamat pagi")) {
            val speech = "Selamat pagi, Tuan. Semoga hari Anda produktif dan menyenangkan. Sistem siap membantu Anda."
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        if (matchesAny(lower, "selamat siang")) {
            val speech = "Selamat siang, Tuan. Semoga aktivitas hari ini berjalan lancar. Bagaimana saya dapat membantu?"
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        if (matchesAny(lower, "selamat sore")) {
            val speech = "Selamat sore, Tuan. Waktu istirahat mendekat, ada hal yang perlu saya siapkan?"
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        if (matchesAny(lower, "selamat malam")) {
            val speech = "Selamat malam, Tuan. Sistem beralih ke mode malam. Katakan jika Anda memerlukan sesuatu."
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        if (matchesAny(lower, "terima kasih", "makasih", "thanks", "terimakasih")) {
            val speech = "Sama-sama, Tuan. Senang dapat selalu siap siaga melayani Anda."
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        if (matchesAny(lower, "bagaimana kabarmu", "apa kabar", "gimana kabarmu")) {
            val speech = "Semua sirkuit komputasi saya dalam kondisi prima, Tuan. Terima kasih telah bertanya."
            return@withContext JarvisResponse(spokenText = speech, displayText = speech)
        }

        // 5. Entertainment: Jokes, Motivation, Dice, Coin
        if (matchesAny(lower, "ceritakan lelucon", "lelucon", "jokes", "humor", "lucu", "cerita lucu")) {
            val joke = jokes.random()
            return@withContext JarvisResponse(
                spokenText = joke,
                displayText = "JARVIS HUMOR:\n$joke"
            )
        }

        if (matchesAny(lower, "motivasi", "kata mutiara", "semangat", "quote")) {
            val quote = quotes.random()
            return@withContext JarvisResponse(
                spokenText = quote,
                displayText = "KATA MOTIVASI:\n\"$quote\""
            )
        }

        if (matchesAny(lower, "lempar koin", "koin", "putar koin")) {
            val side = if (Random.nextBoolean()) "Gambar" else "Angka"
            val speech = "Koin dilempar... Hasilnya adalah $side, Tuan."
            return@withContext JarvisResponse(spokenText = speech, displayText = "🪙 Hasil Lempar Koin: $side")
        }

        if (matchesAny(lower, "lempar dadu", "dadu", "kocok dadu")) {
            val dice = Random.nextInt(1, 7)
            val speech = "Dadu berhenti pada angka $dice, Tuan."
            return@withContext JarvisResponse(spokenText = speech, displayText = "🎲 Hasil Dadu: $dice")
        }

        // 6. Fast Math Parser
        handleMathCalculations(lower)?.let { return@withContext it }

        // 7. General Knowledge / AI Generation (Gemini or Offline Fallback)
        val aiResponse = queryGeminiIfAvailable(query)
        if (aiResponse != null) {
            return@withContext JarvisResponse(
                spokenText = aiResponse,
                displayText = aiResponse
            )
        }

        // 8. Polite Offline Fallback
        val defaultSpeech = "Perintah '$query' telah diterima oleh JARVIS. Saya siap menerima instruksi selanjutnya."
        return@withContext JarvisResponse(
            spokenText = defaultSpeech,
            displayText = "Perintah tercatat: \"$query\"\n\nJARVIS V1 siap menjalankan perintah suara lainnya."
        )
    }

    private fun handleDeviceActions(lower: String, rawQuery: String): JarvisResponse? {
        try {
            if (matchesAny(lower, "buka kalkulator", "kalkulator", "buka calculator")) {
                val intent = Intent().apply {
                    action = Intent.ACTION_MAIN
                    addCategory(Intent.CATEGORY_APP_CALCULATOR)
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (isIntentResolvable(intent)) {
                    context.startActivity(intent)
                    val speech = "Membuka kalkulator, Tuan."
                    return JarvisResponse(speech, speech, executedActionTitle = "Aplikasi Kalkulator", isActionExecuted = true)
                }
            }

            if (matchesAny(lower, "buka kamera", "kamera", "buka camera")) {
                val intent = Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (isIntentResolvable(intent)) {
                    context.startActivity(intent)
                    val speech = "Membuka kamera, Tuan."
                    return JarvisResponse(speech, speech, executedActionTitle = "Kamera Perangkat", isActionExecuted = true)
                }
            }

            if (matchesAny(lower, "buka alarm", "buka jam", "setel alarm")) {
                val intent = Intent(AlarmClock.ACTION_SHOW_ALARMS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                if (isIntentResolvable(intent)) {
                    context.startActivity(intent)
                    val speech = "Membuka jam dan alarm, Tuan."
                    return JarvisResponse(speech, speech, executedActionTitle = "Alarm & Jam", isActionExecuted = true)
                }
            }

            if (matchesAny(lower, "buka pengaturan", "pengaturan", "buka setting", "setting")) {
                val intent = Intent(Settings.ACTION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
                val speech = "Membuka pengaturan perangkat, Tuan."
                return JarvisResponse(speech, speech, executedActionTitle = "Pengaturan Sistem", isActionExecuted = true)
            }

            if (lower.startsWith("cari ") || lower.startsWith("googling ") || lower.startsWith("browsing ")) {
                val searchTerm = rawQuery.replace(Regex("^(cari|googling|browsing)\\s+", RegexOption.IGNORE_CASE), "").trim()
                if (searchTerm.isNotBlank()) {
                    val intent = Intent(Intent.ACTION_WEB_SEARCH).apply {
                        putExtra(SearchManager.QUERY, searchTerm)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    if (isIntentResolvable(intent)) {
                        context.startActivity(intent)
                    } else {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode(searchTerm)}")).apply {
                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        }
                        context.startActivity(browserIntent)
                    }
                    val speech = "Mencari $searchTerm di web, Tuan."
                    return JarvisResponse(speech, speech, executedActionTitle = "Pencarian Web: $searchTerm", isActionExecuted = true)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Error triggering device action", e)
        }
        return null
    }

    private fun handleMathCalculations(lower: String): JarvisResponse? {
        val mathPattern = Regex("(hitung|berapa)?\\s*(\\d+(?:\\.\\d+)?)\\s*(tambah|\\+|kurang|\\-|kali|\\*|x|bagi|\\/)\\s*(\\d+(?:\\.\\d+)?)")
        val match = mathPattern.find(lower) ?: return null

        val num1 = match.groupValues[2].toDoubleOrNull() ?: return null
        val operator = match.groupValues[3]
        val num2 = match.groupValues[4].toDoubleOrNull() ?: return null

        val (opName, result) = when (operator) {
            "tambah", "+" -> "ditambah" to (num1 + num2)
            "kurang", "-" -> "dikurang" to (num1 - num2)
            "kali", "*", "x" -> "dikali" to (num1 * num2)
            "bagi", "/" -> {
                if (num2 == 0.0) {
                    val speech = "Maaf Tuan, pembagian dengan angka nol tidak terdefinisi dalam matematika."
                    return JarvisResponse(speech, speech)
                }
                "dibagi" to (num1 / num2)
            }
            else -> return null
        }

        val formattedResult = if (result % 1.0 == 0.0) result.toLong().toString() else String.format(Locale.forLanguageTag("id-ID"), "%.2f", result)
        val speech = "Hasil dari $num1 $opName $num2 adalah $formattedResult, Tuan."
        val display = "KALKULASI MATEMATIKA:\n$num1 $operator $num2 = $formattedResult"
        return JarvisResponse(spokenText = speech, displayText = display)
    }

    private suspend fun queryGeminiIfAvailable(prompt: String): String? = withContext(Dispatchers.IO) {
        val apiKey = BuildConfig.GEMINI_API_KEY
        if (apiKey.isBlank() || apiKey == "MY_GEMINI_API_KEY") {
            return@withContext null
        }

        try {
            val url = "https://generativelanguage.googleapis.com/v1beta/models/gemini-3.5-flash:generateContent?key=$apiKey"

            val systemInstruction = "Kamu adalah JARVIS V1, asisten kecerdasan buatan pribadi futuristik berbahasa Indonesia. Berikan jawaban dalam bahasa Indonesia yang ramah, sopan, langsung ke intinya, dan sangat nyaman didengar via Text-to-Speech (maksimal 2-3 kalimat pendek). Jangan gunakan tanda bintang, format markdown tebal, atau simbol rumit."

            val jsonBody = JSONObject().apply {
                put("contents", JSONArray().apply {
                    put(JSONObject().apply {
                        put("parts", JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", prompt)
                            })
                        })
                    })
                })
                put("systemInstruction", JSONObject().apply {
                    put("parts", JSONArray().apply {
                        put(JSONObject().apply {
                            put("text", systemInstruction)
                        })
                    })
                })
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 150)
                })
            }

            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (response.isSuccessful) {
                val respStr = response.body?.string() ?: return@withContext null
                val root = JSONObject(respStr)
                val text = root.optJSONArray("candidates")
                    ?.optJSONObject(0)
                    ?.optJSONObject("content")
                    ?.optJSONArray("parts")
                    ?.optJSONObject(0)
                    ?.optString("text")

                if (!text.isNullOrBlank()) {
                    return@withContext text.trim()
                }
            } else {
                Log.w(tag, "Gemini API failed with code ${response.code}: ${response.message}")
            }
        } catch (e: Exception) {
            Log.w(tag, "Gemini request exception: ${e.message}")
        }
        return@withContext null
    }

    private fun isIntentResolvable(intent: Intent): Boolean {
        return intent.resolveActivity(context.packageManager) != null
    }

    private fun matchesAny(text: String, vararg patterns: String): Boolean {
        return patterns.any { text.contains(it) }
    }
}

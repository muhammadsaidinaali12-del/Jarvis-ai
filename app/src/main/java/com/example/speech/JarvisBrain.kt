package com.example.speech

import android.content.Context
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

class JarvisBrain(
    private val context: Context
) {

    private val tag =
        "JarvisBrain"

    private val actionExecutor =
        JarvisActionExecutor(context)

    private val httpClient =
        OkHttpClient.Builder()
            .connectTimeout(
                15,
                TimeUnit.SECONDS
            )
            .readTimeout(
                30,
                TimeUnit.SECONDS
            )
            .writeTimeout(
                15,
                TimeUnit.SECONDS
            )
            .build()

    private val jokes =
        listOf(
            "Kenapa programmer suka kopi dingin? Karena mereka tidak suka Java yang panas!",
            "Mengapa robot tidak pernah panik? Karena mereka selalu punya program cadangan.",
            "Kenapa keyboard sering begadang? Karena dia punya banyak tombol yang harus dijaga.",
            "Komputer apa yang paling sopan? Komputer yang selalu bilang permisi ketika meminta update."
        )

    private val quotes =
        listOf(
            "Masa depan bukanlah sesuatu yang kita tunggu, tetapi sesuatu yang kita ciptakan.",
            "Setiap langkah kecil tetap membawa Anda lebih dekat kepada tujuan.",
            "Teknologi terbaik adalah teknologi yang membuat hidup manusia menjadi lebih mudah."
        )

    suspend fun processCommand(
        input: String
    ): JarvisResponse =
        withContext(Dispatchers.Default) {

            val query =
                input.trim()

            if (query.isBlank()) {

                return@withContext JarvisResponse(
                    spokenText =
                        "Silakan berikan perintah, Tuan.",
                    displayText =
                        "Tidak ada perintah."
                )
            }

            val lower =
                query.lowercase(
                    Locale.forLanguageTag(
                        "id-ID"
                    )
                )

            /*
             * ==================================================
             * PRIORITAS 1
             * ANDROID ACTION
             *
             * Harus dilakukan sebelum Gemini.
             * ==================================================
             */

            try {

                val action =
                    actionExecutor.execute(
                        query
                    )

                if (action != null) {

                    return@withContext JarvisResponse(
                        spokenText =
                            action.spokenText,

                        displayText =
                            action.displayText,

                        executedActionTitle =
                            action.actionTitle,

                        isActionExecuted =
                            action.success
                    )
                }

            } catch (e: Exception) {

                android.util.Log.e(
                    tag,
                    "Action execution error",
                    e
                )

                return@withContext JarvisResponse(
                    spokenText =
                        "Maaf Tuan, terjadi kesalahan saat menjalankan perintah.",

                    displayText =
                        e.localizedMessage
                            ?: "Action execution error",

                    isActionExecuted =
                        false
                )
            }

            /*
             * ==================================================
             * WAKTU
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "jam berapa",
                    "pukul berapa",
                    "waktu sekarang",
                    "jam sekarang"
                )
            ) {

                val time =
                    SimpleDateFormat(
                        "HH:mm",
                        Locale.forLanguageTag(
                            "id-ID"
                        )
                    ).format(
                        Date()
                    )

                val answer =
                    "Saat ini pukul $time, Tuan."

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText = answer
                )
            }

            /*
             * ==================================================
             * TANGGAL
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "tanggal berapa",
                    "hari apa",
                    "tanggal hari ini",
                    "hari ini"
                )
            ) {

                val date =
                    SimpleDateFormat(
                        "EEEE, d MMMM yyyy",
                        Locale.forLanguageTag(
                            "id-ID"
                        )
                    ).format(
                        Date()
                    )

                val answer =
                    "Hari ini adalah $date, Tuan."

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText = answer
                )
            }

            /*
             * ==================================================
             * IDENTITAS
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "siapa kamu",
                    "kamu siapa",
                    "nama kamu",
                    "siapa anda",
                    "tentang jarvis"
                )
            ) {

                val answer =
                    "Saya adalah JARVIS V1, asisten AI pribadi Anda. Saya siap membantu menjalankan perintah dan memberikan informasi, Tuan."

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText = answer
                )
            }

            /*
             * ==================================================
             * SAPAAN
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "halo",
                    "hai",
                    "hello",
                    "halo jarvis",
                    "hai jarvis"
                )
            ) {

                val answer =
                    "Halo Tuan. JARVIS siap menerima perintah Anda."

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText = answer
                )
            }

            /*
             * ==================================================
             * TERIMA KASIH
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "terima kasih",
                    "terimakasih",
                    "makasih",
                    "thanks"
                )
            ) {

                val answer =
                    "Sama-sama, Tuan."

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText = answer
                )
            }

            /*
             * ==================================================
             * DIAGNOSTIC
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "status sistem",
                    "status jarvis",
                    "cek sistem",
                    "diagnostik"
                )
            ) {

                val answer =
                    "Sistem JARVIS V1 online. Modul suara dan action executor siap digunakan."

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText =
                        "JARVIS V1\n\n" +
                            "Status: ONLINE\n" +
                            "Voice: AKTIF\n" +
                            "Action Executor: AKTIF\n" +
                            "AI Core: SIAP"
                )
            }

            /*
             * ==================================================
             * JOKES
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "jokes",
                    "lelucon",
                    "cerita lucu",
                    "lelucon lucu",
                    "humor"
                )
            ) {

                val answer =
                    jokes.random()

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText =
                        "JARVIS HUMOR\n\n$answer"
                )
            }

            /*
             * ==================================================
             * MOTIVASI
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "motivasi",
                    "kata mutiara",
                    "kata bijak",
                    "semangat"
                )
            ) {

                val answer =
                    quotes.random()

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText =
                        "MOTIVASI\n\n$answer"
                )
            }

            /*
             * ==================================================
             * KOIN
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "lempar koin",
                    "lemparkan koin",
                    "kocok koin"
                )
            ) {

                val result =
                    if (
                        Random.nextBoolean()
                    ) {
                        "Gambar"
                    } else {
                        "Angka"
                    }

                val answer =
                    "Hasil lemparan koin adalah $result, Tuan."

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText =
                        "🪙 $result"
                )
            }

            /*
             * ==================================================
             * DADU
             * ==================================================
             */

            if (
                containsAny(
                    lower,
                    "lempar dadu",
                    "kocok dadu",
                    "lemparkan dadu"
                )
            ) {

                val result =
                    Random.nextInt(
                        1,
                        7
                    )

                val answer =
                    "Dadu menunjukkan angka $result, Tuan."

                return@withContext JarvisResponse(
                    spokenText = answer,
                    displayText =
                        "🎲 $result"
                )
            }

            /*
             * ==================================================
             * MATH
             * ==================================================
             */

            calculateSimpleExpression(
                query
            )?.let { result ->

                return@withContext JarvisResponse(
                    spokenText =
                        "Hasilnya adalah $result, Tuan.",

                    displayText =
                        "Hasil: $result"
                )
            }

            /*
             * ==================================================
             * GEMINI
             * ==================================================
             */

            val gemini =
                askGemini(
                    query
                )

            if (
                gemini != null &&
                gemini.isNotBlank()
            ) {

                return@withContext JarvisResponse(
                    spokenText =
                        gemini,

                    displayText =
                        gemini
                )
            }

            /*
             * ==================================================
             * FALLBACK
             * ==================================================
             */

            val fallback =
                "Saya memahami perintah Anda, Tuan, tetapi saya belum memiliki kemampuan khusus untuk menjalankan perintah tersebut."

            return@withContext JarvisResponse(
                spokenText =
                    fallback,

                displayText =
                    "Perintah belum didukung:\n$query"
            )
        }

    private fun askGemini(
        prompt: String
    ): String? {

        val apiKey =
            try {
                BuildConfig.GEMINI_API_KEY
            } catch (
                _: Throwable
            ) {
                ""
            }

        if (
            apiKey.isBlank() ||
            apiKey == "null"
        ) {
            return null
        }

        return try {

            val url =
                "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.0-flash:generateContent?key=$apiKey"

            val body =
                JSONObject().apply {

                    put(
                        "contents",
                        JSONArray().put(

                            JSONObject().apply {

                                put(
                                    "parts",
                                    JSONArray().put(

                                        JSONObject().put(
                                            "text",
                                            buildPrompt(
                                                prompt
                                            )
                                        )
                                    )
                                )
                            }
                        )
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
                                512
                            )
                        }
                    )
                }

            val request =
                Request.Builder()
                    .url(url)
                    .post(
                        body.toString()
                            .toRequestBody(
                                "application/json"
                                    .toMediaType()
                            )
                    )
                    .build()

            httpClient
                .newCall(request)
                .execute()
                .use { response ->

                    if (
                        !response.isSuccessful
                    ) {
                        return null
                    }

                    val responseBody =
                        response.body
                            ?.string()
                            ?: return null

                    val root =
                        JSONObject(
                            responseBody
                        )

                    val candidates =
                        root.optJSONArray(
                            "candidates"
                        )
                            ?: return null

                    if (
                        candidates.length() == 0
                    ) {
                        return null
                    }

                    val candidate =
                        candidates
                            .optJSONObject(0)
                            ?: return null

                    val content =
                        candidate.optJSONObject(
                            "content"
                        )
                            ?: return null

                    val parts =
                        content.optJSONArray(
                            "parts"
                        )
                            ?: return null

                    if (
                        parts.length() == 0
                    ) {
                        return null
                    }

                    val text =
                        parts
                            .optJSONObject(0)
                            ?.optString(
                                "text",
                                ""
                            )
                            ?.trim()

                    if (
                        text.isNullOrBlank()
                    ) {
                        null
                    } else {
                        text
                    }
                }

        } catch (e: Exception) {

            android.util.Log.e(
                tag,
                "Gemini request failed",
                e
            )

            null
        }
    }

    private fun buildPrompt(
        userPrompt: String
    ): String {

        return """
            Anda adalah JARVIS V1, asisten AI pribadi pengguna.
            
            Gunakan bahasa Indonesia yang natural,
            singkat, sopan, dan bergaya asisten teknologi
            yang tenang dan profesional.
            
            Jangan mengaku telah menjalankan tindakan Android
            jika tindakan tersebut belum benar-benar dijalankan
            oleh Action Executor.
            
            Jawab pertanyaan pengguna secara langsung.
            
            Pengguna:
            $userPrompt
        """.trimIndent()
    }

    private fun calculateSimpleExpression(
        input: String
    ): Double? {

        val cleaned =
            input
                .lowercase(
                    Locale.US
                )
                .replace(
                    "berapa",
                    ""
                )
                .replace(
                    "hasil",
                    ""
                )
                .replace(
                    "hitung",
                    ""
                )
                .replace(
                    "adalah",
                    ""
                )
                .trim()

        val match =
            Regex(
                """(-?\d+(?:[.,]\d+)?)\s*(\+|tambah|minus|-|kali|\*|x|bagi|/)\s*(-?\d+(?:[.,]\d+)?)"""
            ).find(
                cleaned
            )
                ?: return null

        val first =
            match
                .groupValues[1]
                .replace(
                    ",",
                    "."
                )
                .toDoubleOrNull()
                ?: return null

        val operator =
            match.groupValues[2]

        val second =
            match
                .groupValues[3]
                .replace(
                    ",",
                    "."
                )
                .toDoubleOrNull()
                ?: return null

        return when (operator) {

            "+",
            "tambah" ->
                first + second

            "-",
            "minus" ->
                first - second

            "*",
            "x",
            "kali" ->
                first * second

            "/",
            "bagi" -> {

                if (
                    second == 0.0
                ) {
                    null
                } else {
                    first / second
                }
            }

            else ->
                null
        }
    }

    private fun containsAny(
        text: String,
        vararg values: String
    ): Boolean {

        return values.any {
            text.contains(it)
        }
    }
}
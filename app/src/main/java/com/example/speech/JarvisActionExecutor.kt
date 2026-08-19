package com.example.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import java.net.URLEncoder
import java.util.Locale

data class ActionResult(
    val success: Boolean,
    val spokenText: String,
    val displayText: String,
    val actionTitle: String? = null
)

class JarvisActionExecutor(
    private val context: Context
) {

    private val packageManager: PackageManager =
        context.packageManager

    /**
     * Menjalankan perintah Android.
     *
     * Return null berarti:
     * "Ini bukan perintah Android yang saya kenali."
     *
     * Dengan begitu JarvisBrain dapat meneruskannya ke Gemini.
     */
    fun execute(command: String): ActionResult? {

        val original = command.trim()

        if (original.isBlank()) {
            return null
        }

        val lower =
            original.lowercase(Locale.forLanguageTag("id-ID"))

        // -----------------------------------------------------
        // APLIKASI
        // -----------------------------------------------------

        extractOpenApplicationName(lower)?.let { appName ->

            val result =
                launchApplicationByName(appName)

            if (result != null) {
                return result
            }

            /*
             * Untuk aplikasi yang tidak terpasang:
             * khusus layanan web populer, buka website.
             */
            val webUrl =
                webFallbackForApplication(appName)

            if (webUrl != null) {

                val opened =
                    openUrl(webUrl)

                if (opened) {

                    val speech =
                        "Aplikasi $appName tidak ditemukan. Saya membuka versi webnya, Tuan."

                    return ActionResult(
                        success = true,
                        spokenText = speech,
                        displayText = speech,
                        actionTitle = "Web $appName"
                    )
                }
            }

            return ActionResult(
                success = false,
                spokenText = "Saya tidak menemukan aplikasi $appName di perangkat ini, Tuan.",
                displayText = "Aplikasi tidak ditemukan: $appName",
                actionTitle = "Aplikasi tidak ditemukan"
            )
        }

        // -----------------------------------------------------
        // KAMERA
        // -----------------------------------------------------

        if (
            containsAny(
                lower,
                "buka kamera",
                "buka camera",
                "jalankan kamera",
                "jalankan camera"
            )
        ) {

            val intent =
                Intent(
                    MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }

            if (startActivity(intent)) {

                return success(
                    "Membuka kamera, Tuan.",
                    "Kamera",
                    "Kamera perangkat dibuka."
                )
            }

            return failure(
                "Saya tidak dapat membuka kamera, Tuan.",
                "Kamera"
            )
        }

        // -----------------------------------------------------
        // KALKULATOR
        // -----------------------------------------------------

        if (
            containsAny(
                lower,
                "buka kalkulator",
                "buka calculator",
                "jalankan kalkulator",
                "jalankan calculator"
            )
        ) {

            val intent =
                Intent(Intent.ACTION_MAIN).apply {

                    addCategory(
                        Intent.CATEGORY_APP_CALCULATOR
                    )

                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            if (startActivity(intent)) {

                return success(
                    "Membuka kalkulator, Tuan.",
                    "Kalkulator",
                    "Kalkulator dibuka."
                )
            }

            return failure(
                "Kalkulator tidak tersedia di perangkat ini, Tuan.",
                "Kalkulator"
            )
        }

        // -----------------------------------------------------
        // PENGATURAN
        // -----------------------------------------------------

        if (
            containsAny(
                lower,
                "buka pengaturan",
                "buka settings",
                "buka setting",
                "jalankan pengaturan",
                "jalankan settings"
            )
        ) {

            val intent =
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            if (startActivity(intent)) {

                return success(
                    "Membuka pengaturan perangkat, Tuan.",
                    "Pengaturan",
                    "Pengaturan perangkat dibuka."
                )
            }

            return failure(
                "Saya tidak dapat membuka pengaturan, Tuan.",
                "Pengaturan"
            )
        }

        // -----------------------------------------------------
        // WI-FI
        // -----------------------------------------------------

        if (
            containsAny(
                lower,
                "buka wifi",
                "buka wi-fi",
                "buka jaringan wifi",
                "pengaturan wifi",
                "pengaturan wi-fi"
            )
        ) {

            val intent =
                Intent(Settings.ACTION_WIFI_SETTINGS).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            if (startActivity(intent)) {

                return success(
                    "Membuka pengaturan Wi-Fi, Tuan.",
                    "Pengaturan Wi-Fi",
                    "Pengaturan Wi-Fi dibuka."
                )
            }
        }

        // -----------------------------------------------------
        // BLUETOOTH
        // -----------------------------------------------------

        if (
            containsAny(
                lower,
                "buka bluetooth",
                "pengaturan bluetooth",
                "buka pengaturan bluetooth"
            )
        ) {

            val intent =
                Intent(
                    Settings.ACTION_BLUETOOTH_SETTINGS
                ).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            if (startActivity(intent)) {

                return success(
                    "Membuka pengaturan Bluetooth, Tuan.",
                    "Bluetooth",
                    "Pengaturan Bluetooth dibuka."
                )
            }
        }

        // -----------------------------------------------------
        // ALARM
        // -----------------------------------------------------

        if (
            containsAny(
                lower,
                "buka alarm",
                "lihat alarm",
                "buka jam",
                "buka clock"
            )
        ) {

            val intent =
                Intent(
                    AlarmClock.ACTION_SHOW_ALARMS
                ).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK
                    )
                }

            if (startActivity(intent)) {

                return success(
                    "Membuka alarm, Tuan.",
                    "Alarm",
                    "Daftar alarm dibuka."
                )
            }
        }

        // -----------------------------------------------------
        // TELEPON
        // -----------------------------------------------------

        if (
            lower.startsWith("telepon ") ||
            lower.startsWith("telpon ") ||
            lower.startsWith("panggil ")
        ) {

            val number =
                extractAfterPrefix(
                    lower,
                    listOf(
                        "telepon ",
                        "telpon ",
                        "panggil "
                    )
                )

            if (number.isNotBlank()) {

                val cleanedNumber =
                    number
                        .replace(" ", "")
                        .replace("-", "")

                val intent =
                    Intent(
                        Intent.ACTION_DIAL,
                        Uri.parse(
                            "tel:$cleanedNumber"
                        )
                    ).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                if (startActivity(intent)) {

                    return success(
                        "Membuka telepon untuk $number, Tuan.",
                        "Telepon",
                        "Dialer dibuka untuk $number."
                    )
                }
            }
        }

        // -----------------------------------------------------
        // SMS
        //
        // Tidak mengirim otomatis.
        // Android membuka composer agar pengguna
        // dapat memeriksa lalu menekan Kirim.
        // -----------------------------------------------------

        if (
            lower.startsWith("kirim sms ") ||
            lower.startsWith("kirim pesan ")
        ) {

            val payload =
                when {
                    lower.startsWith("kirim sms ") ->
                        original.substring(
                            "kirim sms ".length
                        ).trim()

                    else ->
                        original.substring(
                            "kirim pesan ".length
                        ).trim()

                    }

            val parsed =
                parseMessageCommand(payload)

            if (parsed != null) {

                val number =
                    parsed.first

                val message =
                    parsed.second

                val uri =
                    Uri.parse(
                        "smsto:${Uri.encode(number)}"
                    )

                val intent =
                    Intent(
                        Intent.ACTION_SENDTO,
                        uri
                    ).apply {

                        putExtra(
                            "sms_body",
                            message
                        )

                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                if (startActivity(intent)) {

                    return success(
                        "Saya sudah menyiapkan pesan untuk $number. Silakan tekan Kirim, Tuan.",
                        "SMS",
                        "Composer SMS dibuka."
                    )
                }
            }
        }

        // -----------------------------------------------------
        // WHATSAPP
        // -----------------------------------------------------

        if (
            lower.startsWith("kirim whatsapp ") ||
            lower.startsWith("kirim wa ")
        ) {

            val prefix =
                if (
                    lower.startsWith("kirim whatsapp ")
                ) {
                    "kirim whatsapp "
                } else {
                    "kirim wa "
                }

            val payload =
                original.substring(
                    prefix.length
                ).trim()

            val parsed =
                parseMessageCommand(payload)

            if (parsed != null) {

                val number =
                    parsed.first

                val message =
                    parsed.second

                val encodedMessage =
                    URLEncoder.encode(
                        message,
                        "UTF-8"
                    )

                val uri =
                    Uri.parse(
                        "https://wa.me/${number.replace("+", "")}" +
                            "?text=$encodedMessage"
                    )

                val intent =
                    Intent(
                        Intent.ACTION_VIEW,
                        uri
                    ).apply {
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK
                        )
                    }

                if (startActivity(intent)) {

                    return success(
                        "Saya membuka WhatsApp dengan pesan yang sudah disiapkan, Tuan.",
                        "WhatsApp",
                        "WhatsApp dibuka."
                    )
                }
            }
        }

        // -----------------------------------------------------
        // PENCARIAN GOOGLE
        // -----------------------------------------------------

        if (
            lower.startsWith("cari ") ||
            lower.startsWith("carikan ") ||
            lower.startsWith("search ")
        ) {

            val searchQuery =
                extractAfterPrefix(
                    original,
                    listOf(
                        "cari ",
                        "carikan ",
                        "search "
                    )
                )

            if (searchQuery.isNotBlank()) {

                val encoded =
                    URLEncoder.encode(
                        searchQuery,
                        "UTF-8"
                    )

                val url =
                    "https://www.google.com/search?q=$encoded"

                if (openUrl(url)) {

                    return success(
                        "Mencari $searchQuery, Tuan.",
                        "Pencarian Google",
                        "Google: $searchQuery"
                    )
                }
            }
        }

        // -----------------------------------------------------
        // YOUTUBE SEARCH
        // -----------------------------------------------------

        if (
            lower.startsWith("cari di youtube ") ||
            lower.startsWith("cari youtube ")
        ) {

            val searchQuery =
                when {

                    lower.startsWith(
                        "cari di youtube "
                    ) ->
                        original.substring(
                            "cari di youtube ".length
                        ).trim()

                    else ->
                        original.substring(
                            "cari youtube ".length
                        ).trim()
                }

            if (searchQuery.isNotBlank()) {

                val encoded =
                    URLEncoder.encode(
                        searchQuery,
                        "UTF-8"
                    )

                val url =
                    "https://www.youtube.com/results?search_query=$encoded"

                if (openUrl(url)) {

                    return success(
                        "Mencari $searchQuery di YouTube, Tuan.",
                        "YouTube Search",
                        "YouTube: $searchQuery"
                    )
                }
            }
        }

        return null
    }

    // =========================================================
    // APPLICATION LAUNCHER
    // =========================================================

    private fun launchApplicationByName(
        requestedName: String
    ): ActionResult? {

        val requested =
            normalizeAppName(requestedName)

        if (requested.isBlank()) {
            return null
        }

        val launcherIntent =
            Intent(Intent.ACTION_MAIN).apply {
                addCategory(
                    Intent.CATEGORY_LAUNCHER
                )
            }

        val activities =
            packageManager.queryIntentActivities(
                launcherIntent,
                PackageManager.MATCH_ALL
            )

        if (activities.isEmpty()) {
            return null
        }

        /*
         * Exact match terlebih dahulu.
         */

        val exact =
            activities.firstOrNull { info ->

                val label =
                    info.loadLabel(
                        packageManager
                    )
                        ?.toString()
                        ?.let {
                            normalizeAppName(it)
                        }
                        ?: ""

                label == requested
            }

        val selected =
            exact
                ?: activities.firstOrNull { info ->

                    val label =
                        info.loadLabel(
                            packageManager
                        )
                            ?.toString()
                            ?.let {
                                normalizeAppName(it)
                            }
                            ?: ""

                    label.contains(requested) ||
                        requested.contains(label)
                }

        if (selected == null) {
            return null
        }

        val packageName =
            selected.activityInfo.packageName

        val activityName =
            selected.activityInfo.name

        val intent =
            Intent(Intent.ACTION_MAIN).apply {

                addCategory(
                    Intent.CATEGORY_LAUNCHER
                )

                component =
                    android.content.ComponentName(
                        packageName,
                        activityName
                    )

                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        return if (startActivity(intent)) {

            val actualName =
                selected.loadLabel(
                    packageManager
                )
                    ?.toString()
                    ?: requestedName

            success(
                "Membuka $actualName, Tuan.",
                actualName,
                "$actualName dibuka."
            )

        } else {

            failure(
                "Saya tidak dapat membuka $requestedName, Tuan.",
                requestedName
            )
        }
    }

    // =========================================================
    // APPLICATION NAME EXTRACTION
    // =========================================================

    private fun extractOpenApplicationName(
        lower: String
    ): String? {

        val prefixes =
            listOf(
                "buka ",
                "bukakan ",
                "jalankan ",
                "jalankan aplikasi ",
                "buka aplikasi ",
                "open ",
                "launch "
            )

        for (prefix in prefixes) {

            if (
                lower.startsWith(prefix) &&
                lower.length > prefix.length
            ) {

                val result =
                    lower.substring(
                        prefix.length
                    ).trim()

                if (result.isNotBlank()) {
                    return result
                }
            }
        }

        return null
    }

    // =========================================================
    // APPLICATION ALIASES
    // =========================================================

    private fun normalizeAppName(
        value: String
    ): String {

        var name =
            value
                .lowercase(
                    Locale.forLanguageTag("id-ID")
                )
                .trim()

        val aliases =
            mapOf(
                "yt" to "youtube",
                "youtube app" to "youtube",
                "ig" to "instagram",
                "insta" to "instagram",
                "wa" to "whatsapp",
                "whats app" to "whatsapp",
                "fb" to "facebook",
                "telegram messenger" to "telegram",
                "chrome browser" to "chrome",
                "google chrome" to "chrome",
                "spotify music" to "spotify",
                "tiktok app" to "tiktok"
            )

        name =
            aliases[name] ?: name

        return name
    }

    private fun webFallbackForApplication(
        appName: String
    ): String? {

        return when (
            normalizeAppName(appName)
        ) {

            "youtube" ->
                "https://www.youtube.com"

            "instagram" ->
                "https://www.instagram.com"

            "facebook" ->
                "https://www.facebook.com"

            "tiktok" ->
                "https://www.tiktok.com"

            "telegram" ->
                "https://web.telegram.org"

            "spotify" ->
                "https://open.spotify.com"

            else ->
                null
        }
    }

    // =========================================================
    // MESSAGE PARSER
    // =========================================================

    private fun parseMessageCommand(
        payload: String
    ): Pair<String, String>? {

        /*
         * Format yang didukung:
         *
         * kirim pesan 08123456789 halo
         *
         * atau:
         *
         * kirim pesan 08123456789|halo
         */

        if (payload.isBlank()) {
            return null
        }

        val separatorIndex =
            payload.indexOf('|')

        if (separatorIndex >= 0) {

            val number =
                payload
                    .substring(
                        0,
                        separatorIndex
                    )
                    .trim()

            val message =
                payload
                    .substring(
                        separatorIndex + 1
                    )
                    .trim()

            if (
                number.isNotBlank() &&
                message.isNotBlank()
            ) {
                return number to message
            }
        }

        val parts =
            payload.split(
                Regex("\\s+"),
                limit = 2
            )

        if (parts.size < 2) {
            return null
        }

        val number =
            parts[0].trim()

        val message =
            parts[1].trim()

        if (
            number.isBlank() ||
            message.isBlank()
        ) {
            return null
        }

        return number to message
    }

    // =========================================================
    // HELPERS
    // =========================================================

    private fun containsAny(
        text: String,
        vararg values: String
    ): Boolean {

        return values.any {
            text.contains(it)
        }
    }

    private fun extractAfterPrefix(
        text: String,
        prefixes: List<String>
    ): String {

        val lower =
            text.lowercase(
                Locale.forLanguageTag("id-ID")
            )

        for (prefix in prefixes) {

            if (lower.startsWith(prefix)) {

                return text.substring(
                    prefix.length
                ).trim()
            }
        }

        return ""
    }

    private fun startActivity(
        intent: Intent
    ): Boolean {

        return try {

            intent.addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK
            )

            if (
                intent.resolveActivity(
                    packageManager
                ) == null
            ) {
                false
            } else {

                context.startActivity(intent)
                true
            }

        } catch (
            _: Exception
        ) {
            false
        }
    }

    private fun openUrl(
        url: String
    ): Boolean {

        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
            ).apply {
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK
                )
            }

        return startActivity(intent)
    }

    private fun success(
        spoken: String,
        title: String,
        display: String
    ): ActionResult {

        return ActionResult(
            success = true,
            spokenText = spoken,
            displayText = display,
            actionTitle = title
        )
    }

    private fun failure(
        spoken: String,
        title: String
    ): ActionResult {

        return ActionResult(
            success = false,
            spokenText = spoken,
            displayText = spoken,
            actionTitle = title
        )
    }
}
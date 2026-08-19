package com.example.speech

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import java.util.Locale

data class JarvisActionResult(
    val success: Boolean,
    val spokenText: String,
    val displayText: String = spokenText,
    val actionTitle: String? = null
)

class JarvisActionExecutor(
    private val context: Context
) {

    private val tag = "JarvisActionExecutor"
    private val packageManager: PackageManager =
        context.packageManager

    fun execute(
        command: String
    ): JarvisActionResult? {

        val raw = command.trim()

        if (raw.isBlank()) {
            return null
        }

        val lower =
            raw.lowercase(Locale.forLanguageTag("id-ID"))

        return try {

            // =====================================================
            // OPEN APP
            // =====================================================

            extractOpenApplication(lower)?.let { appName ->

                val result =
                    openApplication(appName)

                if (result != null) {
                    return result
                }

                openKnownWebFallback(appName)?.let {
                    return it
                }
            }

            // =====================================================
            // OPEN CAMERA
            // =====================================================

            if (
                lower == "kamera" ||
                lower.contains("buka kamera") ||
                lower.contains("buka camera")
            ) {

                val intent =
                    Intent(
                        MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA
                    ).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (start(intent)) {
                    return success(
                        "Membuka kamera, Tuan.",
                        "Kamera",
                        "Kamera perangkat dibuka."
                    )
                }
            }

            // =====================================================
            // CALCULATOR
            // =====================================================

            if (
                lower.contains("buka kalkulator") ||
                lower == "kalkulator" ||
                lower.contains("buka calculator")
            ) {

                val intent =
                    Intent(Intent.ACTION_MAIN).apply {

                        addCategory(
                            Intent.CATEGORY_APP_CALCULATOR
                        )

                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (start(intent)) {
                    return success(
                        "Membuka kalkulator, Tuan.",
                        "Kalkulator",
                        "Kalkulator dibuka."
                    )
                }
            }

            // =====================================================
            // SETTINGS
            // =====================================================

            if (
                lower == "pengaturan" ||
                lower == "setting" ||
                lower == "settings" ||
                lower.contains("buka pengaturan")
            ) {

                val intent =
                    Intent(
                        Settings.ACTION_SETTINGS
                    ).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (start(intent)) {
                    return success(
                        "Membuka pengaturan perangkat, Tuan.",
                        "Pengaturan",
                        "Pengaturan Android dibuka."
                    )
                }
            }

            // =====================================================
            // WIFI SETTINGS
            // =====================================================

            if (
                lower.contains("buka wifi") ||
                lower.contains("pengaturan wifi") ||
                lower.contains("setting wifi")
            ) {

                val intent =
                    Intent(
                        Settings.ACTION_WIFI_SETTINGS
                    ).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (start(intent)) {
                    return success(
                        "Membuka pengaturan Wi-Fi, Tuan.",
                        "Wi-Fi",
                        "Pengaturan Wi-Fi dibuka."
                    )
                }
            }

            // =====================================================
            // BLUETOOTH SETTINGS
            // =====================================================

            if (
                lower.contains("buka bluetooth") ||
                lower.contains("pengaturan bluetooth") ||
                lower.contains("setting bluetooth")
            ) {

                val intent =
                    Intent(
                        Settings.ACTION_BLUETOOTH_SETTINGS
                    ).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (start(intent)) {
                    return success(
                        "Membuka pengaturan Bluetooth, Tuan.",
                        "Bluetooth",
                        "Pengaturan Bluetooth dibuka."
                    )
                }
            }

            // =====================================================
            // ALARM
            // =====================================================

            if (
                lower.contains("buka alarm") ||
                lower.contains("buka jam") ||
                lower == "alarm"
            ) {

                val intent =
                    Intent(
                        AlarmClock.ACTION_SHOW_ALARMS
                    ).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (start(intent)) {
                    return success(
                        "Membuka alarm, Tuan.",
                        "Alarm",
                        "Aplikasi alarm dibuka."
                    )
                }
            }

            // =====================================================
            // PHONE / DIAL
            // =====================================================

            extractPhoneNumber(raw)?.let { number ->

                if (
                    lower.contains("telepon") ||
                    lower.contains("telpon") ||
                    lower.contains("hubungi") ||
                    lower.contains("panggil")
                ) {

                    val intent =
                        Intent(
                            Intent.ACTION_DIAL,
                            Uri.parse(
                                "tel:${Uri.encode(number)}"
                            )
                        ).apply {
                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK
                        }

                    if (start(intent)) {
                        return success(
                            "Membuka panggilan untuk $number, Tuan.",
                            "Panggilan",
                            "Dialer dibuka untuk $number."
                        )
                    }
                }
            }

            // =====================================================
            // SMS
            //
            // JARVIS menyiapkan SMS, tetapi tidak mengirim
            // diam-diam tanpa konfirmasi pengguna.
            // =====================================================

            extractSmsCommand(raw)?.let { sms ->

                val uri =
                    Uri.parse(
                        "smsto:${Uri.encode(sms.number)}"
                    )

                val intent =
                    Intent(
                        Intent.ACTION_SENDTO,
                        uri
                    ).apply {

                        putExtra(
                            "sms_body",
                            sms.message
                        )

                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (start(intent)) {

                    return success(
                        "Pesan sudah saya siapkan untuk ${sms.number}. Silakan tekan kirim, Tuan.",
                        "SMS",
                        "SMS disiapkan untuk ${sms.number}."
                    )
                }
            }

            // =====================================================
            // WEB SEARCH
            // =====================================================

            extractSearchQuery(raw)?.let { search ->

                val url =
                    "https://www.google.com/search?q=" +
                            Uri.encode(search)

                val intent =
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse(url)
                    ).apply {
                        flags =
                            Intent.FLAG_ACTIVITY_NEW_TASK
                    }

                if (start(intent)) {
                    return success(
                        "Mencari $search di Google, Tuan.",
                        "Pencarian",
                        "Google dibuka untuk pencarian: $search"
                    )
                }
            }

            // =====================================================
            // OPEN URL
            // =====================================================

            if (
                lower.startsWith("buka situs ") ||
                lower.startsWith("buka website ") ||
                lower.startsWith("buka web ")
            ) {

                val site =
                    raw.substringAfter(" ", "")
                        .substringAfter(" ", "")
                        .trim()

                if (site.isNotBlank()) {

                    val normalizedUrl =
                        if (
                            site.startsWith("http://") ||
                            site.startsWith("https://")
                        ) {
                            site
                        } else {
                            "https://$site"
                        }

                    val intent =
                        Intent(
                            Intent.ACTION_VIEW,
                            Uri.parse(normalizedUrl)
                        ).apply {
                            flags =
                                Intent.FLAG_ACTIVITY_NEW_TASK
                        }

                    if (start(intent)) {
                        return success(
                            "Membuka situs $site, Tuan.",
                            "Website",
                            normalizedUrl
                        )
                    }
                }
            }

        } catch (e: Exception) {

            Log.e(
                tag,
                "Gagal menjalankan command: $raw",
                e
            )
        }

        return null
    }

    // =========================================================
    // APPLICATION NAME
    // =========================================================

    private fun extractOpenApplication(
        command: String
    ): String? {

        val patterns =
            listOf(
                Regex("^buka aplikasi\\s+(.+)$"),
                Regex("^bukakan aplikasi\\s+(.+)$"),
                Regex("^tolong buka aplikasi\\s+(.+)$"),
                Regex("^buka app\\s+(.+)$"),
                Regex("^buka\\s+(.+)$"),
                Regex("^bukakan\\s+(.+)$"),
                Regex("^tolong buka\\s+(.+)$"),
                Regex("^jalankan aplikasi\\s+(.+)$"),
                Regex("^jalankan\\s+(.+)$"),
                Regex("^open\\s+(.+)$"),
                Regex("^launch\\s+(.+)$")
            )

        for (pattern in patterns) {

            val match =
                pattern.find(command)

            if (match != null) {

                val name =
                    match.groupValues
                        .getOrNull(1)
                        ?.trim()
                        ?: ""

                if (
                    name.isNotBlank() &&
                    name != "kamera" &&
                    name != "kalkulator" &&
                    name != "alarm" &&
                    name != "pengaturan"
                ) {
                    return normalizeAppName(name)
                }
            }
        }

        return null
    }

    // =========================================================
    // FIND APPLICATION
    // =========================================================

    private fun openApplication(
        requestedName: String
    ): JarvisActionResult? {

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
            try {

                packageManager.queryIntentActivities(
                    launcherIntent,
                    PackageManager.MATCH_ALL
                )

            } catch (e: Exception) {

                Log.e(
                    tag,
                    "Tidak dapat membaca daftar aplikasi",
                    e
                )

                emptyList()
            }

        var bestPackage: String? = null
        var bestLabel: String? = null
        var bestScore = 0

        for (info in activities) {

            val appInfo =
                info.activityInfo.applicationInfo

            val label =
                try {
                    appInfo
                        .loadLabel(packageManager)
                        .toString()
                } catch (
                    _: Exception
                ) {
                    ""
                }

            val labelNormalized =
                normalizeAppName(label)

            val packageName =
                appInfo.packageName
                    .lowercase(Locale.ROOT)

            val score =
                applicationScore(
                    requested,
                    labelNormalized,
                    packageName
                )

            if (score > bestScore) {

                bestScore = score
                bestPackage = appInfo.packageName
                bestLabel = label
            }
        }

        if (
            bestPackage.isNullOrBlank() ||
            bestLabel.isNullOrBlank() ||
            bestScore < 50
        ) {
            return null
        }

        val launchIntent =
            packageManager
                .getLaunchIntentForPackage(
                    bestPackage!!
                )
                ?: return null

        launchIntent.flags =
            Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED

        if (!start(launchIntent)) {
            return null
        }

        return success(
            "Membuka $bestLabel, Tuan.",
            "Membuka aplikasi: $bestLabel",
            "Aplikasi $bestLabel berhasil dibuka."
        )
    }

    private fun applicationScore(
        requested: String,
        label: String,
        packageName: String
    ): Int {

        if (label == requested) {
            return 100
        }

        if (label.startsWith(requested)) {
            return 90
        }

        if (label.contains(requested)) {
            return 80
        }

        if (packageName == requested) {
            return 85
        }

        if (packageName.contains(requested)) {
            return 70
        }

        val words =
            requested
                .split(Regex("\\s+"))
                .filter {
                    it.length >= 2
                }

        if (
            words.isNotEmpty() &&
            words.all {
                label.contains(it)
            }
        ) {
            return 65
        }

        return 0
    }

    private fun normalizeAppName(
        name: String
    ): String {

        return name
            .lowercase(Locale.ROOT)
            .trim()
            .removePrefix("aplikasi ")
            .removePrefix("app ")
            .removeSuffix(" aplikasi")
            .trim()
            .replace("youtube", "youtube")
            .replace("instagram", "instagram")
            .replace("whatsapp", "whatsapp")
    }

    // =========================================================
    // KNOWN WEB FALLBACK
    // =========================================================

    private fun openKnownWebFallback(
        appName: String
    ): JarvisActionResult? {

        val url =
            when {

                appName.contains("youtube") ->
                    "https://www.youtube.com"

                appName.contains("instagram") ->
                    "https://www.instagram.com"

                appName.contains("facebook") ->
                    "https://www.facebook.com"

                appName.contains("tiktok") ->
                    "https://www.tiktok.com"

                appName.contains("telegram") ->
                    "https://web.telegram.org"

                appName.contains("spotify") ->
                    "https://open.spotify.com"

                appName.contains("netflix") ->
                    "https://www.netflix.com"

                appName.contains("gmail") ->
                    "https://mail.google.com"

                appName == "x" ||
                        appName.contains("twitter") ->
                    "https://x.com"

                appName.contains("google") ->
                    "https://www.google.com"

                else ->
                    null
            }

        if (url == null) {
            return null
        }

        val intent =
            Intent(
                Intent.ACTION_VIEW,
                Uri.parse(url)
            ).apply {
                flags =
                    Intent.FLAG_ACTIVITY_NEW_TASK
            }

        if (!start(intent)) {
            return null
        }

        return success(
            "Aplikasi tidak ditemukan. Saya membuka versi web-nya, Tuan.",
            "Web: $appName",
            url
        )
    }

    // =========================================================
    // SEARCH
    // =========================================================

    private fun extractSearchQuery(
        raw: String
    ): String? {

        val match =
            Regex(
                "^(cari|googling|carikan|search)\\s+(.+)$",
                RegexOption.IGNORE_CASE
            ).find(raw)

        return match
            ?.groupValues
            ?.getOrNull(2)
            ?.trim()
            ?.takeIf {
                it.isNotBlank()
            }
    }

    // =========================================================
    // PHONE
    // =========================================================

    private fun extractPhoneNumber(
        text: String
    ): String? {

        return Regex(
            "(?:\\+62|62|0)\\d{8,13}"
        )
            .fin
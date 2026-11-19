package com.mouzzlii.gateway

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

private const val PREFS_NAME = "gateway_shop_prefs"
private const val KEY_AUTO_REDIRECT = "auto_redirect_after_scan"
private const val KEY_SELECTED_CARRIER = "selected_carrier"
private const val KEY_URL_PREFIX = "target_url_"

enum class Carrier(
    val label: String,
    val packageName: String,
    val defaultWebUrlPrefix: String,
    val preferWeb: Boolean = true
) {
    HERMES(
        label = "Hermes",
        packageName = "de.hermes.myhermes",
        defaultWebUrlPrefix = "https://www.myhermes.at/empfangen/sendungsverfolgung/?waybill="
    ),
    GLS(
        label = "GLS",
        packageName = "com.gls.glsgroup.gls",
        defaultWebUrlPrefix = "https://gls-group.eu/AT/de/paketverfolgung?match="
    ),
    DPD(
        label = "DPD",
        packageName = "de.dpd.dpd",
        defaultWebUrlPrefix = "https://tracking.dpd.de/status/de_AT/parcel/"
    ),
    UPS(
        label = "UPS",
        packageName = "com.ups.mobile",
        defaultWebUrlPrefix = "https://www.ups.com/track?loc=de_AT&tracknum="
    )
}

data class ScanDetectionResult(
    val normalizedCode: String,
    val detectedCarrier: Carrier?,
    val isConfidentMatch: Boolean,
    val reason: String
)

fun normalizeScanInput(rawInput: String): String {
    val cleaned = rawInput
        .replace("\r", "")
        .replace("\n", "")
        .trim()
        .uppercase()

    if (cleaned.isEmpty()) return ""

    val upsMatch = Regex("""1Z[0-9A-Z]{6,20}""").find(cleaned)
    if (upsMatch != null) return upsMatch.value

    val hermesAlphaMatch = Regex("""H[0-9A-Z]{9,22}""").find(cleaned)
    if (hermesAlphaMatch != null) return hermesAlphaMatch.value

    return cleaned.replace(" ", "")
}

fun analyzeScanCode(rawInput: String): ScanDetectionResult {
    val code = normalizeScanInput(rawInput)
    if (code.isEmpty()) {
        return ScanDetectionResult(
            normalizedCode = "",
            detectedCarrier = null,
            isConfidentMatch = false,
            reason = "Kein Code erfasst"
        )
    }

    return when {
        code.startsWith("1Z") && code.length >= 10 -> ScanDetectionResult(
            normalizedCode = code,
            detectedCarrier = Carrier.UPS,
            isConfidentMatch = code.length >= 16,
            reason = "UPS erkannt (1Z...)"
        )

        code.startsWith("H") && code.length >= 10 -> ScanDetectionResult(
            normalizedCode = code,
            detectedCarrier = Carrier.HERMES,
            isConfidentMatch = code.length >= 12,
            reason = "Hermes erkannt (H...)"
        )

        // Typische längere Hermes-Paketscheine / Sendungsnummern (z. B. 14- bis 20-stellig)
        code.length in 14..20 && code.all { it.isDigit() } && !code.startsWith("0034") -> ScanDetectionResult(
            normalizedCode = code,
            detectedCarrier = Carrier.HERMES,
            isConfidentMatch = true,
            reason = "Hermes erkannt (${code.length}-stellige Nummer)"
        )

        code.startsWith("0034") && code.length >= 14 -> ScanDetectionResult(
            normalizedCode = code,
            detectedCarrier = Carrier.DPD,
            isConfidentMatch = code.length >= 14,
            reason = "DPD erkannt (0034...)"
        )

        code.length in 11..13 && code.all { it.isDigit() } -> ScanDetectionResult(
            normalizedCode = code,
            detectedCarrier = Carrier.GLS,
            isConfidentMatch = true,
            reason = "GLS erkannt (${code.length}-stellige Nummer)"
        )

        else -> ScanDetectionResult(
            normalizedCode = code,
            detectedCarrier = null,
            isConfidentMatch = false,
            reason = "Anbieter bitte manuell wählen (${code.length} Zeichen)"
        )
    }
}

fun openCarrierSystem(
    context: Context,
    carrier: Carrier,
    code: String,
    customUrls: Map<Carrier, String>
): String {
    val cleanCode = normalizeScanInput(code)
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText("Tracking", cleanCode))

    val configuredUrl = customUrls[carrier]?.trim().orEmpty()
    val baseUrl = if (configuredUrl.isNotBlank()) configuredUrl else carrier.defaultWebUrlPrefix

    val targetUrl = if (baseUrl.endsWith("=") || baseUrl.endsWith("/")) {
        baseUrl + Uri.encode(cleanCode)
    } else {
        baseUrl
    }

    val launchIntent = if (!carrier.preferWeb) {
        context.packageManager.getLaunchIntentForPackage(carrier.packageName)
    } else {
        null
    }

    return if (launchIntent != null) {
        launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launchIntent)
        "${carrier.label}-App geöffnet – Code kopiert: $cleanCode"
    } else {
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
        "${carrier.label}-System geöffnet – Code kopiert: $cleanCode"
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                GatewayScreen()
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GatewayScreen() {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) }

    val urlStates = remember {
        mutableStateMapOf<Carrier, String>().apply {
            Carrier.entries.forEach { carrier ->
                val saved = prefs.getString(KEY_URL_PREFIX + carrier.name, carrier.defaultWebUrlPrefix)
                put(carrier, saved ?: carrier.defaultWebUrlPrefix)
            }
        }
    }

    var autoRedirectAfterScan by remember {
        mutableStateOf(prefs.getBoolean(KEY_AUTO_REDIRECT, false))
    }
    var selectedCarrier by remember {
        val savedName = prefs.getString(KEY_SELECTED_CARRIER, Carrier.HERMES.name) ?: Carrier.HERMES.name
        mutableStateOf(Carrier.entries.find { it.name == savedName } ?: Carrier.HERMES)
    }
    var showUrlConfig by remember { mutableStateOf(false) }
    var trackingCode by remember { mutableStateOf("") }
    var lastAutoRedirectedCode by remember { mutableStateOf("") }
    var statusMessage by remember { mutableStateOf("Bereit – Code eingeben oder scannen") }

    val analysis = remember(trackingCode) { analyzeScanCode(trackingCode) }

    fun saveSettings() {
        val editor = prefs.edit()
            .putBoolean(KEY_AUTO_REDIRECT, autoRedirectAfterScan)
            .putString(KEY_SELECTED_CARRIER, selectedCarrier.name)
        Carrier.entries.forEach { carrier ->
            editor.putString(KEY_URL_PREFIX + carrier.name, (urlStates[carrier] ?: carrier.defaultWebUrlPrefix).trim())
        }
        editor.apply()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Multi-Carrier Gateway",
            style = MaterialTheme.typography.headlineSmall
        )

        OutlinedTextField(
            value = trackingCode,
            onValueChange = { newValue ->
                trackingCode = newValue
                val currentAnalysis = analyzeScanCode(newValue)
                if (currentAnalysis.detectedCarrier != null) {
                    selectedCarrier = currentAnalysis.detectedCarrier
                }
                statusMessage = currentAnalysis.reason

                if (
                    autoRedirectAfterScan &&
                    currentAnalysis.isConfidentMatch &&
                    currentAnalysis.detectedCarrier != null &&
                    currentAnalysis.normalizedCode.isNotBlank() &&
                    currentAnalysis.normalizedCode != lastAutoRedirectedCode
                ) {
                    lastAutoRedirectedCode = currentAnalysis.normalizedCode
                    saveSettings()
                    statusMessage = openCarrierSystem(
                        context = context,
                        carrier = currentAnalysis.detectedCarrier,
                        code = currentAnalysis.normalizedCode,
                        customUrls = urlStates
                    )
                }
            },
            label = { Text("Sendungsnummer / Scan") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (trackingCode.isNotBlank()) {
            Text(
                text = "Erkannt: ${analysis.detectedCarrier?.label ?: "Keiner"} | ${analysis.reason} | Code: ${analysis.normalizedCode}",
                style = MaterialTheme.typography.bodySmall
            )
        }

        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Carrier.entries.forEach { carrier ->
                FilterChip(
                    selected = selectedCarrier == carrier,
                    onClick = {
                        selectedCarrier = carrier
                        saveSettings()
                        statusMessage = "Anbieter auf ${carrier.label} gesetzt"
                    },
                    label = { Text(carrier.label) }
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Nur bei sicherem Treffer automatisch öffnen")
            Switch(
                checked = autoRedirectAfterScan,
                onCheckedChange = {
                    autoRedirectAfterScan = it
                    saveSettings()
                }
            )
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Button(
                onClick = {
                    if (trackingCode.isBlank()) {
                        statusMessage = "Bitte zuerst eine Sendungsnummer eingeben."
                        return@Button
                    }
                    saveSettings()
                    statusMessage = openCarrierSystem(context, selectedCarrier, trackingCode, urlStates)
                },
                modifier = Modifier.weight(1f)
            ) {
                Text("Zu ${selectedCarrier.label}")
            }

            OutlinedButton(
                onClick = {
                    trackingCode = ""
                    lastAutoRedirectedCode = ""
                    statusMessage = "Eingabe geleert – bereit für nächsten Code"
                }
            ) {
                Text("Leeren")
            }
        }

        TextButton(
            onClick = { showUrlConfig = !showUrlConfig }
        ) {
            Text(if (showUrlConfig) "Ziel-URLs ausblenden" else "Ziel-URLs (Hermes, GLS, DPD, UPS) bearbeiten")
        }

        if (showUrlConfig) {
            Carrier.entries.forEach { carrier ->
                OutlinedTextField(
                    value = urlStates[carrier] ?: "",
                    onValueChange = { urlStates[carrier] = it },
                    label = { Text("${carrier.label}-Ziel-URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Button(
                onClick = {
                    saveSettings()
                    statusMessage = "Alle Ziel-URLs & Einstellungen lokal gespeichert"
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Alle Anbieter-URLs speichern")
            }
        }

        Text(
            text = statusMessage,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

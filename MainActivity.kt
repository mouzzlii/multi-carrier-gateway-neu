package de.gateway.multicarrier

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import de.gateway.multicarrier.ui.GatewayRoot

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val code = extractTrackingNumber(intent)
        setContent {
            GatewayRoot(initialCode = code, onExternalResult = if (code != null && intent.action != Intent.ACTION_MAIN) {
                { record ->
                    setResult(Activity.RESULT_OK, Intent().apply {
                        putExtra(EXTRA_TRACKING_NUMBER, record.code)
                        putExtra(EXTRA_CARRIER, record.carrierId)
                        putExtra(EXTRA_TRACKING_URL, de.gateway.multicarrier.domain.Carrier.fromId(record.carrierId).trackingUrl(record.code))
                    })
                    finish()
                }
            } else null)
        }
    }

    companion object {
        const val EXTRA_TRACKING_NUMBER = "trackingNumber"
        const val ACTION_SCAN = "de.gateway.multicarrier.SCAN"
        const val EXTRA_CARRIER = "carrier"
        const val EXTRA_TRACKING_URL = "trackingUrl"

        private fun extractTrackingNumber(intent: Intent?): String? {
            if (intent == null) return null
            return intent.getStringExtra(EXTRA_TRACKING_NUMBER)
                ?: intent.getStringExtra(Intent.EXTRA_TEXT)
                ?: intent.data?.getQueryParameter(EXTRA_TRACKING_NUMBER)
        }
    }
}

package org.vt.supervisor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Fail-closed safety gate.
 * Configuration edits and financial approvals remain disabled until audited persistence,
 * authorization, reconciliation, and collector synchronization are implemented.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(color = Color(0xFFFFF4E5), modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Text("Village Trust Fund", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text("VT Supervisor", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "NOT APPROVED FOR REAL FINANCIAL ADMINISTRATION",
                            color = Color(0xFF9B1C1C),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text("This build does not persist configuration, receive collector reports, or maintain an auditable master ledger. The apparent Save action in the prototype did not actually save data.")
                        Text("Before supervisor controls can be enabled, the app needs:")
                        Text("• Authenticated supervisor identity and role-based permissions")
                        Text("• Durable, encrypted ledger and versioned configuration history")
                        Text("• Signature, sequence, replay, and full-batch validation for incoming reports")
                        Text("• Cash reconciliation, approvals, reversals, and immutable audit events")
                        Text("• Backup/restore, key management, and tested disaster recovery")
                        Text("• Independent security and accounting tests")
                        Text("Do not use this prototype to manage real member funds.", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

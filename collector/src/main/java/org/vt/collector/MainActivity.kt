package org.vt.collector

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
 * The prototype has no durable ledger or verified receipt/sync path.
 * Do not enable collections until the production controls are implemented and tested.
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
                        Text("VT Collector", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Text(
                            "NOT APPROVED FOR REAL MONEY COLLECTION",
                            color = Color(0xFF9B1C1C),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.ExtraBold
                        )
                        Text("This build is a development prototype. Payment recording is intentionally disabled to prevent false receipts or lost contributions.")
                        Text("Before collection can be enabled, the app needs:")
                        Text("• Encrypted, durable local ledger with database transactions and backups")
                        Text("• Verified member registration and duplicate-payment protection")
                        Text("• Correct installment allocation, including up to six complete advance installments")
                        Text("• Signed, replay-resistant collector/supervisor synchronization")
                        Text("• Reconciliation, reversals, audit history, and tested recovery procedures")
                        Text("• Independent security and accounting tests")
                        Text("Do not accept, record, or rely on payments using this prototype.", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

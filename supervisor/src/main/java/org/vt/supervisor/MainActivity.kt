package org.vt.supervisor

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var amount by remember { mutableStateOf("100") }
                var period by remember { mutableStateOf("7") }
                var grace by remember { mutableStateOf("7") }
                var penalty by remember { mutableStateOf("10") }
                var status by remember { mutableStateOf("Local prototype - no collector sync configured") }
                Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Village Trust Fund", style = MaterialTheme.typography.headlineSmall)
                    Text("Supervisor dashboard", style = MaterialTheme.typography.titleMedium)
                    Text(status)
                    OutlinedTextField(amount, { amount = it }, label = { Text("Contribution (ETB)") })
                    OutlinedTextField(period, { period = it }, label = { Text("Period (days)") })
                    OutlinedTextField(grace, { grace = it }, label = { Text("Grace days") })
                    OutlinedTextField(penalty, { penalty = it }, label = { Text("Penalty (ETB)") })
                    Button(onClick = { status = "Configuration saved locally (version 1)." }) { Text("Save configuration") }
                    Text("Reports and ledger")
                    Text("No collector reports received yet.")
                    Text("Prototype only: live sync, persistent database, and audited payment posting are not enabled.")
                }
            }
        }
    }
}

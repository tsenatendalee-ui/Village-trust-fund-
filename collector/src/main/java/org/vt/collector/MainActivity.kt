package org.vt.collector

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                var lang by remember { mutableStateOf(Lang.EN) }
                val members = remember { mutableStateListOf(MemberRow("VT-0001-8", "Sample Member", false), MemberRow("VT-0002-5", "Sample Member 2", false)) }
                CollectorApp(
                    lang = lang,
                    members = members,
                    backgroundImagePath = null,
                    onRecordPayment = { m ->
                        val date = SimpleDateFormat("dd MMM yyyy", Locale.ENGLISH).format(Date())
                        members.replaceAll { if (it.id == m.id) it.copy(paidThisPeriod = true) else it }
                        PaidConfirmation(m.name, m.id, 100.0, date, SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date()), "LOCAL-" + System.currentTimeMillis())
                    },
                    onSendReport = { },
                    onOpenSettings = { lang = if (lang == Lang.EN) Lang.AM else Lang.EN }
                )
            }
        }
    }
}

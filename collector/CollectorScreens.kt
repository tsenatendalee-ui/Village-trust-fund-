
package org.vt.collector

import android.app.Activity
import android.graphics.BitmapFactory
import android.view.WindowManager
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File

// ---------- Colours (light mint, dark text, 7:1 contrast) ----------
val MintBackground = Color(0xFFEAF7EF)
val CardWhite = Color(0xFFFFFFFF)
val InkDark = Color(0xFF102418)
val PaidGreen = Color(0xFF0B6B2E)
val UnpaidRed = Color(0xFF8A1C1C)

// ---------- Language (collector chooses in Settings; one language at a time) ----------
enum class Lang { AM, EN }

data class Strings(
    val members: String,
    val recordPayment: String,
    val paid: String,
    val notPaid: String,
    val done: String,
    val amount: String,
    val date: String,
    val birr: String,
    val settings: String,
    val sendReport: String,
    val memberLabel: String,
)

val STRINGS_AM = Strings(
    members = "አባላት",
    recordPayment = "ክፍያ መዝግብ",
    paid = "ተከፍሏል",
    notPaid = "አልተከፈለም",
    done = "ተጠናቅቋል",
    amount = "መጠን",
    date = "ቀን",
    birr = "ብር",
    settings = "መቼት",
    sendReport = "ሪፖርት ላክ",
    memberLabel = "የአባል መለያ",
)

val STRINGS_EN = Strings(
    members = "Members",
    recordPayment = "Record payment",
    paid = "PAID",
    notPaid = "NOT PAID",
    done = "Done",
    amount = "Amount",
    date = "Date",
    birr = "birr",
    settings = "Settings",
    sendReport = "Send report",
    memberLabel = "Member ID",
)

fun stringsFor(lang: Lang) = if (lang == Lang.AM) STRINGS_AM else STRINGS_EN

// ---------- Data shown on screen (filled from the local ledger) ----------
data class MemberRow(val id: String, val name: String, val paidThisPeriod: Boolean)

data class PaidConfirmation(
    val memberName: String,
    val memberId: String,
    val amountBirr: Double,
    val ecDate: String,      // already formatted in Ethiopian calendar
    val time: String,
    val code: String,        // short code unique to this payment, matches the report
)

// ---------- Home screen ----------
@Composable
fun HomeScreen(
    lang: Lang,
    members: List<MemberRow>,
    onRecordPayment: (MemberRow) -> PaidConfirmation?,
    onSendReport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val s = stringsFor(lang)
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MintBackground)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(s.members, fontSize = 26.sp, fontWeight = FontWeight.Bold, color = InkDark)
            TextButtonLarge(s.settings, onOpenSettings)
        }
        Spacer(Modifier.height(10.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(members) { m ->
                Card(
                    colors = CardDefaults.cardColors(containerColor = CardWhite),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(m.name, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkDark)
                            Text(m.id, fontSize = 16.sp, color = InkDark)
                        }
                        // status uses a word and colour, never colour alone
                        Text(
                            if (m.paidThisPeriod) s.paid else s.notPaid,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (m.paidThisPeriod) PaidGreen else UnpaidRed,
                        )
                    }
                    if (!m.paidThisPeriod) {
                        Button(
                            onClick = { onRecordPayment(m) },
                            modifier = Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = PaidGreen),
                        ) {
                            Text(s.recordPayment, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CardWhite)
                        }
                        Spacer(Modifier.height(12.dp))
                    }
                }
            }
        }

        Button(
            onClick = onSendReport,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            colors = ButtonDefaults.buttonColors(containerColor = InkDark),
        ) {
            Text(s.sendReport, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = CardWhite)
        }
    }
}

@Composable
private fun TextButtonLarge(label: String, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.height(56.dp),
        colors = ButtonDefaults.buttonColors(containerColor = CardWhite),
    ) {
        Text(label, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = InkDark)
    }
}

// ---------- PAID confirmation screen ----------
@Composable
fun PaidScreen(
    lang: Lang,
    confirmation: PaidConfirmation,
    backgroundImagePath: String?,   // supervisor-set image, stored on the phone
    onDone: () -> Unit,
) {
    val s = stringsFor(lang)
    val context = LocalContext.current
    val activity = context as? Activity

    // Block screenshots and screen recording while this screen is shown.
    DisposableEffect(Unit) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    val background = remember(backgroundImagePath) {
        backgroundImagePath?.let { p ->
            val f = File(p)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(MintBackground)) {
        if (background != null) {
            Image(
                bitmap = background,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top part: check mark and PAID, large and green
            Card(
                colors = CardDefaults.cardColors(containerColor = CardWhite.copy(alpha = 0.94f)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("✓", fontSize = 72.sp, fontWeight = FontWeight.Bold, color = PaidGreen)
                    Text(s.paid, fontSize = 60.sp, fontWeight = FontWeight.Black, color = PaidGreen)
                }
            }

            Spacer(Modifier.height(20.dp))

            // Member name below PAID, bold and dark
            Card(
                colors = CardDefaults.cardColors(containerColor = CardWhite.copy(alpha = 0.94f)),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        confirmation.memberName,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Bold,
                        color = InkDark,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("${s.memberLabel}: ${confirmation.memberId}", fontSize = 18.sp, color = InkDark)
                    Text(
                        "${s.amount}: ${confirmation.amountBirr.toInt()} ${s.birr}",
                        fontSize = 18.sp,
                        color = InkDark,
                    )
                    Text("${s.date}: ${confirmation.ecDate} · ${confirmation.time}", fontSize = 18.sp, color = InkDark)
                    Spacer(Modifier.height(8.dp))
                    Text(confirmation.code, fontSize = 16.sp, color = InkDark)
                }
            }

            Spacer(Modifier.weight(1f))

            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth().height(56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = InkDark),
            ) {
                Text(s.done, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = CardWhite)
            }
        }
    }
}

// ---------- Root: switches between home and PAID ----------
@Composable
fun CollectorApp(
    lang: Lang,
    members: List<MemberRow>,
    backgroundImagePath: String?,
    onRecordPayment: (MemberRow) -> PaidConfirmation?,   // writes the ledger entry first; null if it failed
    onSendReport: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    var confirmation by remember { mutableStateOf<PaidConfirmation?>(null) }

    val current = confirmation
    if (current == null) {
        HomeScreen(
            lang = lang,
            members = members,
            onRecordPayment = { m -> onRecordPayment(m)?.let { confirmation = it } },
            onSendReport = onSendReport,
            onOpenSettings = onOpenSettings,
        )
    } else {
        PaidScreen(lang, current, backgroundImagePath) { confirmation = null }
    }
}

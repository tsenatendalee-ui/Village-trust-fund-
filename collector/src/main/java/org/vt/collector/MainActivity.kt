package org.vt.collector

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Collector(this) }
    }
}

private fun firstCollectionSaturday(today: LocalDate = LocalDate.now()): LocalDate =
    today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY))

private fun weeklyDates(first: LocalDate, count: Int): List<LocalDate> =
    (0 until count).map { first.plusWeeks(it.toLong()) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Collector(ctx: Context) {
    val db = ctx.getSharedPreferences("vt_collector", Context.MODE_PRIVATE)
    var lang by remember { mutableStateOf(db.getString("lang", "") ?: "") }
    var picker by remember { mutableStateOf(lang.isBlank()) }
    var page by remember { mutableStateOf("home") }
    var name by remember { mutableStateOf("") }
    var id by remember { mutableStateOf("") }
    var payId by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf("0") }
    var installments by remember { mutableStateOf("1") }
    var members by remember { mutableStateOf(db.getString("members", "") ?: "") }
    var records by remember { mutableStateOf(db.getString("payments_v2", "[]") ?: "[]") }
    var message by remember { mutableStateOf("") }
    val am = lang == "am"
    fun tr(en: String, amh: String) = if (am) amh else en
    val amount = db.getInt("installment", 100)
    fun savedPayments(): JSONArray = try { JSONArray(records) } catch (_: Exception) { JSONArray() }
    fun paidDatesFor(memberId: String): Set<String> {
        val out = mutableSetOf<String>()
        val arr = savedPayments()
        for (i in 0 until arr.length()) {
            val tx = arr.optJSONObject(i) ?: continue
            if (tx.optString("memberId") == memberId) {
                val dates = tx.optJSONArray("collectionDates") ?: continue
                for (j in 0 until dates.length()) out += dates.optString(j)
            }
        }
        return out
    }

    MaterialTheme {
        Scaffold(
            topBar = {
                TopAppBar(title = { Text("VT Collector") }, actions = {
                    TextButton(onClick = { picker = true }) { Text(tr("Settings", "ቅንብሮች")) }
                })
            },
            bottomBar = {
                NavigationBar {
                    listOf("home" to tr("Home", "መነሻ"), "members" to tr("Members", "አባላት"),
                        "collect" to tr("Collect", "ክፍያ"), "history" to tr("History", "ታሪክ"))
                        .forEach { (p, label) ->
                            NavigationBarItem(selected = page == p, onClick = { page = p }, icon = {}, label = { Text(label) })
                        }
                }
            }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                when (page) {
                    "home" -> {
                        Text(tr("Weekly offline collection", "ሳምንታዊ ክፍያ ስብስብ"), style = MaterialTheme.typography.headlineSmall)
                        Text(tr("Registered members: ", "የተመዘገቡ አባላት፦ ") + (if (members.isBlank()) "0" else members.lines().size.toString()))
                        Text(tr("Payment transactions: ", "የክፍያ ግብይቶች፦ ") + savedPayments().length())
                        Text(tr("Collection day: Saturday. Each member can be allocated only once per Saturday.", "የመሰብሰቢያ ቀን፦ ቅዳሜ። አንድ አባል ለአንድ ቅዳሜ አንድ ጊዜ ብቻ ይከፍላል።"))
                        Text(tr("Records remain on this phone; secure supervisor transfer is not yet available.", "መዝገቦቹ በዚህ ስልክ ብቻ ናቸው።"))
                    }
                    "members" -> {
                        Text(tr("Register member", "አባል መዝግብ"), style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(name, { name = it }, label = { Text(tr("Full name", "ሙሉ ስም")) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(id, { id = it }, label = { Text(tr("Member ID", "የአባል መለያ")) }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val cleanId = id.trim()
                            if (name.isNotBlank() && cleanId.isNotBlank() && members.lines().none { it.substringBefore("|") == cleanId }) {
                                members += (if (members.isBlank()) "" else "\n") + cleanId + "|" + name.trim()
                                db.edit().putString("members", members).commit()
                                name = ""; id = ""; message = tr("Member saved.", "አባሉ ተመዝግቧል።")
                            } else message = tr("Enter a name and a unique member ID.", "ስምና ልዩ የአባል መለያ ያስገቡ።")
                        }) { Text(tr("Save member", "አባል አስቀምጥ")) }
                        Text(members.replace("|", " — "))
                    }
                    "collect" -> {
                        Text(tr("Record weekly installments", "ሳምንታዊ ክፍያ መዝግብ"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("Weekly installment: ETB ", "የሳምንት ክፍያ፦ ብር ") + amount)
                        OutlinedTextField(payId, { payId = it }, label = { Text(tr("Member ID", "የአባል መለያ")) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(offset, { offset = it.filter(Char::isDigit) }, label = { Text(tr("Start offset in weeks (0 = this/next Saturday)", "የመነሻ ሳምንት (0 = ይህ/ቀጣይ ቅዳሜ)")) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(installments, { installments = it.filter(Char::isDigit) }, label = { Text(tr("Number of complete weekly installments (1–26)", "ሙሉ ሳምንታዊ ክፍያ ብዛት (1–26)")) }, modifier = Modifier.fillMaxWidth())
                        val s = offset.toIntOrNull() ?: -1
                        val n = installments.toIntOrNull() ?: 0
                        val first = firstCollectionSaturday().plusWeeks(s.coerceAtLeast(0).toLong())
                        val dates = if (s >= 0 && n in 1..26) weeklyDates(first, n) else emptyList()
                        Text(tr("Collection dates: ", "የክፍያ ቀኖች፦ ") + (dates.firstOrNull()?.toString() ?: "—") + if (dates.size > 1) " … " + dates.last() else "")
                        Text(tr("Total due now: ETB ", "ጠቅላላ ክፍያ፦ ብር ") + (n.coerceAtLeast(0) * amount))
                        Button(onClick = {
                            val member = members.lines().firstOrNull { it.substringBefore("|") == payId.trim() }
                            when {
                                member == null -> message = tr("Member not found.", "አባሉ አልተገኘም።")
                                s < 0 || n !in 1..26 -> message = tr("Choose 1–26 complete weekly installments.", "ከ1 እስከ 26 ሙሉ ሳምንታዊ ክፍያዎችን ይምረጡ።")
                                first.plusWeeks((n - 1).toLong()).isAfter(firstCollectionSaturday().plusMonths(6)) ->
                                    message = tr("Advance payment cannot go beyond six months.", "ቅድሚያ ክፍያ ከ6 ወር በላይ አይፈቀድም።")
                                else -> {
                                    val dateStrings = dates.map { it.toString() }
                                    val alreadyPaid = paidDatesFor(payId.trim())
                                    val conflict = dateStrings.firstOrNull { it in alreadyPaid }
                                    if (conflict != null) {
                                        message = tr("Rejected: this member already has a payment allocated to $conflict.", "ተከልክሏል፦ አባሉ ለ$conflict ቀድሞ ከፍሏል።")
                                    } else {
                                        // Commit one transaction containing all weekly allocations.
                                        // Synchronize this critical section so two rapid taps cannot double-post.
                                        synchronized(db) {
                                            val latest = try { JSONArray(db.getString("payments_v2", "[]")) } catch (_: Exception) { JSONArray() }
                                            val occupied = mutableSetOf<String>()
                                            for (i in 0 until latest.length()) {
                                                val tx = latest.optJSONObject(i) ?: continue
                                                if (tx.optString("memberId") == payId.trim()) {
                                                    val a = tx.optJSONArray("collectionDates") ?: continue
                                                    for (j in 0 until a.length()) occupied += a.optString(j)
                                                }
                                            }
                                            val duplicate = dateStrings.firstOrNull { it in occupied }
                                            if (duplicate != null) {
                                                message = tr("Rejected: $duplicate is already paid.", "ተከልክሏል፦ $duplicate ቀድሞ ተከፍሏል።")
                                            } else {
                                                val allocation = JSONArray()
                                                dateStrings.forEach { allocation.put(it) }
                                                val tx = JSONObject()
                                                    .put("transactionId", UUID.randomUUID().toString())
                                                    .put("memberId", payId.trim())
                                                    .put("memberName", member.substringAfter("|"))
                                                    .put("amountBirr", n * amount)
                                                    .put("installmentAmountBirr", amount)
                                                    .put("installmentCount", n)
                                                    .put("collectionDates", allocation)
                                                    .put("recordedAt", java.time.Instant.now().toString())
                                                latest.put(tx)
                                                val ok = db.edit().putString("payments_v2", latest.toString()).commit()
                                                if (ok) {
                                                    records = latest.toString()
                                                    message = tr("Payment recorded once. $n Saturday installment(s) allocated.", "ክፍያው አንድ ጊዜ ተመዝግቧል። $n ሳምንታዊ ክፍያ(ዎች) ተመድበዋል።")
                                                    payId = ""; installments = "1"; offset = "0"
                                                } else message = tr("Could not save payment. Nothing was confirmed.", "ክፍያው ሊቀመጥ አልቻለም።")
                                            }
                                        }
                                    }
                                }
                            }
                        }) { Text(tr("Save payment", "ክፍያ አስቀምጥ")) }
                        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary)
                        Text(tr("A payment is one transaction. It allocates complete Saturday installments only; duplicate member/date allocations are rejected.", "አንድ ክፍያ አንድ ግብይት ነው። ሙሉ የቅዳሜ ክፍያዎች ብቻ ይመደባሉ፤ ድግግሞሽ ይከለከላል።"))
                    }
                    else -> {
                        Text(tr("Payment history", "የክፍያ ታሪክ"), style = MaterialTheme.typography.titleLarge)
                        val arr = savedPayments()
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items((0 until arr.length()).mapNotNull { arr.optJSONObject(it) }) { tx ->
                                Card(Modifier.fillMaxWidth()) {
                                    Column(Modifier.padding(12.dp)) {
                                        Text(tx.optString("memberName"), style = MaterialTheme.typography.titleMedium)
                                        Text(tr("Member: ", "አባል፦ ") + tx.optString("memberId"))
                                        Text(tr("Transaction: ", "ግብይት፦ ") + tx.optString("transactionId"))
                                        Text(tr("Amount: ETB ", "መጠን፦ ብር ") + tx.optInt("amountBirr"))
                                        val d = tx.optJSONArray("collectionDates") ?: JSONArray()
                                        Text(tr("Saturday dates: ", "የቅዳሜ ቀኖች፦ ") + (0 until d.length()).joinToString(", ") { d.optString(it) })
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if (picker) AlertDialog(
            onDismissRequest = { if (lang.isNotBlank()) picker = false },
            title = { Text(tr("Choose language", "ቋንቋ ይምረጡ")) },
            text = { Column {
                TextButton(onClick = { lang = "en"; db.edit().putString("lang", "en").apply(); picker = false }) { Text("English") }
                TextButton(onClick = { lang = "am"; db.edit().putString("lang", "am").apply(); picker = false }) { Text("አማርኛ") }
            } },
            confirmButton = { TextButton(onClick = { picker = false }) { Text(tr("Close", "ዝጋ")) } }
        )
    }
}

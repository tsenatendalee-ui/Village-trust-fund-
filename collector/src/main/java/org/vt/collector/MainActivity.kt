package org.vt.collector

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONArray
import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.TemporalAdjusters
import java.util.UUID
import java.security.SecureRandom
import java.security.MessageDigest

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

private fun generateMemberId(existingMembers: String): String {
    val used = existingMembers.lines().map { it.substringBefore("|") }.toSet()
    val random = SecureRandom()
    repeat(9000) {
        val candidate = (1000 + random.nextInt(9000)).toString()
        if (candidate !in used) return candidate
    }
    throw IllegalStateException("All four-digit member IDs are in use.")
}

private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private fun isAmharicFullName(value: String): Boolean {
    val parts = value.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
    return parts.size >= 2 && parts.all { part -> part.all { ch -> ch in '\u1200'..'\u137F' } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Collector(ctx: Context) {
    val db = ctx.getSharedPreferences("vt_collector", Context.MODE_PRIVATE)
    var lang by remember { mutableStateOf(db.getString("lang", "") ?: "") }
    var picker by remember { mutableStateOf(lang.isBlank()) }
    var page by remember { mutableStateOf("home") }
    var name by remember { mutableStateOf("") }
    var memberSearch by remember { mutableStateOf("") }
    var payId by remember { mutableStateOf("") }
    var offset by remember { mutableStateOf("0") }
    var installments by remember { mutableStateOf("1") }
    var members by remember { mutableStateOf(db.getString("members", "") ?: "") }
    var records by remember { mutableStateOf(db.getString("payments_v2", "[]") ?: "[]") }
    var message by remember { mutableStateOf("") }
    val am = lang == "am"
    fun tr(en: String, amh: String) = if (am) amh else en
    var amount by remember { mutableIntStateOf(db.getInt("installment", 100)) }
    var transferMessage by remember { mutableStateOf("") }
    var paidReceipt by remember { mutableStateOf<JSONObject?>(null) }
    var pendingExport by remember { mutableStateOf("") }
    val exportReport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(pendingExport.toByteArray(Charsets.UTF_8)) }
            transferMessage = "Report exported. Transfer this JSON file to the Supervisor."
        } catch (e: Exception) { transferMessage = "Export failed: ${e.message}" }
    }
    val importRules = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val raw = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val envelope = JSONObject(raw)
            val payload = envelope.getJSONObject("payload")
            require(envelope.optString("type") == "VT_RULE_UPDATE" && envelope.optInt("schemaVersion") == 1)
            require(sha256(payload.toString()) == envelope.optString("sha256")) { "File integrity check failed." }
            val newAmount = payload.getInt("weeklyInstallment")
            val newGrace = payload.getInt("graceDays")
            val newPenalty = payload.getInt("penaltyBirr")
            require(newAmount > 0 && newGrace >= 0 && newPenalty >= 0)
            val ok = db.edit().putInt("installment", newAmount).putInt("grace", newGrace)
                .putInt("penalty", newPenalty).putInt("rulesVersion", payload.optInt("configVersion", 1)).commit()
            if (ok) { amount = newAmount; transferMessage = "Rules update received and saved. Version " + payload.optInt("configVersion", 1) }
            else transferMessage = "Could not save the rules update."
        } catch (e: Exception) { transferMessage = "Update rejected: ${e.message}" }
    }
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
                        "collect" to tr("Collect", "ክፍያ"), "history" to tr("History", "ታሪክ"), "transfer" to tr("Transfer", "ማስተላለፍ"))
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
                        Text(tr("Records remain on this phone until you export a report.", "ሪፖርት እስኪያወጡ ድረስ መዝገቦቹ በዚህ ስልክ ላይ ይቆያሉ።"))
                    }
                    "members" -> {
                        Text(tr("Register member", "አባል መዝግብ"), style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(name, { name = it }, label = { Text(tr("Full name (Amharic only)", "ሙሉ ስም (በአማርኛ ብቻ)")) }, modifier = Modifier.fillMaxWidth())
                        Text(tr("A unique 4-digit ID is generated automatically after registration.", "ከምዝገባ በኋላ ልዩ ባለ4 አሃዝ መለያ በራስ-ሰር ይፈጠራል።"))
                        Button(onClick = {
                            val cleanName = name.trim().replace(Regex("\\s+"), " ")
                            if (!isAmharicFullName(cleanName)) {
                                message = tr("Enter the member's full name using Amharic/Ethiopic characters (at least two name parts).", "ሙሉ ስምን በአማርኛ ፊደላት ያስገቡ (ቢያንስ ሁለት የስም ክፍሎች)።")
                            } else {
                                try {
                                    val newId = generateMemberId(members)
                                    val updated = members + (if (members.isBlank()) "" else "\n") + newId + "|" + cleanName
                                    if (db.edit().putString("members", updated).commit()) {
                                        members = updated
                                        name = ""
                                        message = tr("Member registered. New ID: ", "አባሉ ተመዝግቧል። አዲስ መለያ፦ ") + newId
                                    } else message = tr("Registration could not be saved.", "ምዝገባው ሊቀመጥ አልቻለም።")
                                } catch (_: Exception) {
                                    message = tr("No unused four-digit IDs are available.", "ነፃ ባለ4 አሃዝ መለያ የለም።")
                                }
                            }
                        }) { Text(tr("Register member and generate ID", "አባል መዝግብና መለያ ፍጠር")) }
                        OutlinedTextField(memberSearch, { memberSearch = it }, label = { Text(tr("Search members by name or 4-digit ID", "አባላትን በስም ወይም በ4 አሃዝ መለያ ፈልግ")) }, modifier = Modifier.fillMaxWidth())
                        val filteredMembers = members.lines().filter { line ->
                            line.isNotBlank() && (line.substringBefore("|").contains(memberSearch.trim(), true) ||
                                line.substringAfter("|", "").contains(memberSearch.trim(), true))
                        }
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(filteredMembers) { line ->
                                Card(Modifier.fillMaxWidth()) {
                                    Text(line.substringAfter("|") + " — " + line.substringBefore("|"), Modifier.padding(12.dp))
                                }
                            }
                        }
                    }
                    "collect" -> {
                        Text(tr("Record weekly installments", "ሳምንታዊ ክፍያ መዝግብ"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("Weekly installment: ETB ", "የሳምንት ክፍያ፦ ብር ") + amount)
                        OutlinedTextField(payId, { payId = it }, label = { Text(tr("Search by member name or 4-digit ID", "በአባል ስም ወይም ባለ4 አሃዝ መለያ ፈልግ")) }, modifier = Modifier.fillMaxWidth())
                        val matches = members.lines().filter { line ->
                            line.isNotBlank() && payId.isNotBlank() &&
                                (line.substringBefore("|").contains(payId.trim(), true) ||
                                    line.substringAfter("|", "").contains(payId.trim(), true))
                        }
                        if (payId.isNotBlank()) {
                            matches.take(6).forEach { line ->
                                TextButton(onClick = { payId = line.substringBefore("|") }) {
                                    Text(line.substringAfter("|") + " — " + line.substringBefore("|"))
                                }
                            }
                        }
                        OutlinedTextField(offset, { offset = it.filter(Char::isDigit) }, label = { Text(tr("Start offset in weeks (0 = this/next Saturday)", "የመነሻ ሳምንት (0 = ይህ/ቀጣይ ቅዳሜ)")) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(installments, { installments = it.filter(Char::isDigit) }, label = { Text(tr("Number of complete weekly installments (1–26)", "ሙሉ ሳምንታዊ ክፍያ ብዛት (1–26)")) }, modifier = Modifier.fillMaxWidth())
                        val s = offset.toIntOrNull() ?: -1
                        val n = installments.toIntOrNull() ?: 0
                        val first = firstCollectionSaturday().plusWeeks(s.coerceAtLeast(0).toLong())
                        val dates = if (s >= 0 && n in 1..26) weeklyDates(first, n) else emptyList()
                        Text(tr("Collection dates: ", "የክፍያ ቀኖች፦ ") + (dates.firstOrNull()?.toString() ?: "—") + if (dates.size > 1) " … " + dates.last() else "")
                        Text(tr("Total due now: ETB ", "ጠቅላላ ክፍያ፦ ብር ") + (n.coerceAtLeast(0) * amount))
                        Button(onClick = {
                            val exactId = members.lines().firstOrNull { it.substringBefore("|") == payId.trim() }
                            val exactNameMatches = members.lines().filter { it.substringAfter("|", "").equals(payId.trim(), true) }
                            val member = exactId ?: exactNameMatches.singleOrNull()
                            when {
                                member == null -> message = if (exactNameMatches.size > 1)
                                    tr("More than one member has that name. Search and select the 4-digit ID.", "ከአንድ በላይ አባል ተመሳሳይ ስም አላቸው። በ4 አሃዝ መለያ ፈልገው ይምረጡ።")
                                    else tr("Member not found. Search by name or ID and select the member.", "አባሉ አልተገኘም። በስም ወይም በመለያ ፈልገው ይምረጡ።")
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
                                                    paidReceipt = tx
                                                    message = ""
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
                    "transfer" -> {
                        Text(tr("Offline transfer", "ከመስመር ውጭ ማስተላለፍ"), style = MaterialTheme.typography.titleLarge)
                        Text("Export the Collector report for the Supervisor, and import rules updates received from the Supervisor.")
                        Button(onClick = {
                            val payload = JSONObject().put("collectorId", "PRIMARY_COLLECTOR")
                                .put("exportedAt", java.time.Instant.now().toString())
                                .put("members", JSONArray(members.lines().filter { it.isNotBlank() }))
                                .put("payments", savedPayments())
                            pendingExport = JSONObject().put("type", "VT_REPORT").put("schemaVersion", 1)
                                .put("payload", payload).put("sha256", sha256(payload.toString())).toString(2)
                            exportReport.launch("VT-Collector-Report-${LocalDate.now()}.json")
                        }, modifier = Modifier.fillMaxWidth()) { Text(tr("Send / Export report", "ሪፖርት ላክ / ወጪ አድርግ")) }
                        Button(onClick = { importRules.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Receive rules update", "የደንብ ማሻሻያ ተቀበል"))
                        }
                        Text("Weekly installment: ETB $amount")
                        Text("Grace days: " + db.getInt("grace", 0))
                        Text("Penalty: ETB " + db.getInt("penalty", 0))
                        if (transferMessage.isNotBlank()) Text(transferMessage, color = MaterialTheme.colorScheme.primary)
                    }
                    "history" -> {
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
        paidReceipt?.let { receipt ->
            AlertDialog(
                onDismissRequest = { /* Receipt must be explicitly acknowledged with Done after showing it to the member. */ },
                title = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
                        Text("✓", fontSize = 64.sp, color = androidx.compose.ui.graphics.Color(0xFF087A35), fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                        Text(tr("PAID", "ተከፍሏል"), fontSize = 52.sp, color = androidx.compose.ui.graphics.Color(0xFF087A35), fontWeight = androidx.compose.ui.text.font.FontWeight.Black)
                    }
                },
                text = {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(receipt.optString("memberName"), fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(receipt.optString("memberId"), fontSize = 22.sp)
                        Text("ETB " + receipt.optInt("amountBirr"), fontSize = 30.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                        Text(receipt.optString("recordedAt").replace("T", " ").take(19), fontSize = 17.sp)
                        Text(tr("Show this PAID confirmation to the member before closing.", "ይህንን የተከፈለ ማረጋገጫ ከመዝጋትዎ በፊት ለአባሉ ያሳዩ።"), fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(tr("Receipt: ", "ደረሰኝ፦ ") + receipt.optString("transactionId").take(8).uppercase(), fontSize = 16.sp)
                    }
                },
                confirmButton = {
                    Button(onClick = { paidReceipt = null; page = "home" }) {
                        Text(tr("Done", "ተጠናቋል"), fontSize = 18.sp)
                    }
                }
            )
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

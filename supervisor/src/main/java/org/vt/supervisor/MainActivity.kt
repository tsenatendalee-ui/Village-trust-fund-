package org.vt.supervisor

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest
import java.time.Instant

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { Supervisor(this) }
    }
}

private fun sha256(value: String): String =
    MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Supervisor(ctx: Context) {
    val db = ctx.getSharedPreferences("vt_supervisor", Context.MODE_PRIVATE)
    var lang by remember { mutableStateOf(db.getString("lang", "") ?: "") }
    var picker by remember { mutableStateOf(lang.isBlank()) }
    var page by remember { mutableStateOf("home") }
    var amount by remember { mutableStateOf(db.getInt("amount", 100).toString()) }
    var grace by remember { mutableStateOf(db.getInt("grace", 5).toString()) }
    var penalty by remember { mutableStateOf(db.getInt("penalty", 0).toString()) }
    var version by remember { mutableIntStateOf(db.getInt("rulesVersion", 1)) }
    var transferMessage by remember { mutableStateOf("") }
    var pendingUpdate by remember { mutableStateOf("") }
    var importedCount by remember { mutableIntStateOf(try { JSONArray(db.getString("importedPayments", "[]")).length() } catch (_: Exception) { 0 }) }
    val am = lang == "am"
    fun tr(en: String, amh: String) = if (am) amh else en

    val sendUpdate = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) try {
            ctx.contentResolver.openOutputStream(uri)?.use { it.write(pendingUpdate.toByteArray(Charsets.UTF_8)) }
            transferMessage = tr("Rules update exported. Send this file to the Collector.", "የደንብ ማሻሻያ ወጪ ተደርጓል። ፋይሉን ለሰብሳቢው ይላኩ።")
        } catch (e: Exception) { transferMessage = "Export failed: ${e.message}" }
    }

    val receiveReport = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) try {
            val raw = ctx.contentResolver.openInputStream(uri)!!.bufferedReader().use { it.readText() }
            val envelope = JSONObject(raw)
            val payload = envelope.getJSONObject("payload")
            require(envelope.optString("type") == "VT_REPORT" && envelope.optInt("schemaVersion") == 1) { "Unsupported report format." }
            require(sha256(payload.toString()) == envelope.optString("sha256")) { "Report integrity check failed." }
            require(payload.optString("collectorId") == "PRIMARY_COLLECTOR") { "Unknown collector." }

            val knownMembers = (db.getString("importedMembers", "") ?: "").lines().filter { it.isNotBlank() }.toMutableSet()
            val memberArray = payload.optJSONArray("members") ?: JSONArray()
            var newMembers = 0
            for (i in 0 until memberArray.length()) {
                val line = memberArray.optString(i)
                if (line.isNotBlank() && knownMembers.add(line.substringBefore("|"))) newMembers++
            }

            val ledger = try { JSONArray(db.getString("importedPayments", "[]")) } catch (_: Exception) { JSONArray() }
            val seenTx = mutableSetOf<String>()
            val paidAllocations = mutableSetOf<String>()
            for (i in 0 until ledger.length()) {
                val tx = ledger.optJSONObject(i) ?: continue
                seenTx += tx.optString("transactionId")
                val ds = tx.optJSONArray("collectionDates") ?: JSONArray()
                for (j in 0 until ds.length()) paidAllocations += tx.optString("memberId") + "|" + ds.optString(j)
            }
            val incoming = payload.optJSONArray("payments") ?: JSONArray()
            var accepted = 0
            var duplicates = 0
            var conflicts = 0
            for (i in 0 until incoming.length()) {
                val tx = incoming.optJSONObject(i) ?: continue
                val id = tx.optString("transactionId")
                val memberId = tx.optString("memberId")
                val dates = tx.optJSONArray("collectionDates") ?: JSONArray()
                if (id.isBlank() || memberId.isBlank() || dates.length() == 0) { conflicts++; continue }
                if (id in seenTx) { duplicates++; continue }
                val keys = (0 until dates.length()).map { memberId + "|" + dates.optString(it) }
                if (keys.toSet().size != keys.size || keys.any { it in paidAllocations }) { conflicts++; continue }
                ledger.put(tx)
                seenTx += id
                paidAllocations.addAll(keys)
                accepted++
            }
            val ok = db.edit()
                .putString("importedMembers", knownMembers.joinToString("\n"))
                .putString("importedPayments", ledger.toString())
                .putString("lastReportAt", Instant.now().toString())
                .commit()
            if (ok) {
                importedCount = ledger.length()
                transferMessage = "Report imported. New members: $newMembers; payments accepted: $accepted; duplicates skipped: $duplicates; conflicts rejected: $conflicts. Total imported payments: $importedCount."
            } else transferMessage = "Import could not be saved. No success was reported."
        } catch (e: Exception) { transferMessage = "Report rejected: ${e.message}" }
    }

    MaterialTheme {
        Scaffold(
            topBar = { TopAppBar(title = { Text("VT Supervisor") }, actions = {
                TextButton(onClick = { picker = true }) { Text(tr("Settings", "ቅንብሮች")) }
            }) },
            bottomBar = { NavigationBar {
                listOf("home" to tr("Home", "መነሻ"), "rules" to tr("Fund rules", "የፈንድ ደንቦች"),
                    "reports" to tr("Reports", "ሪፖርቶች"), "transfer" to tr("Transfer", "ማስተላለፍ"))
                    .forEach { (p, label) -> NavigationBarItem(selected = page == p, onClick = { page = p }, icon = {}, label = { Text(label) }) }
            } }
        ) { pad ->
            Column(Modifier.fillMaxSize().padding(pad).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (page) {
                    "home" -> {
                        Text(tr("Supervisor dashboard", "የኃላፊ ማዕከል"), style = MaterialTheme.typography.headlineSmall)
                        Text(tr("One Collector ↔ one Supervisor. Transfer files offline between the two phones.", "አንድ ሰብሳቢ ↔ አንድ ኃላፊ። ፋይሎችን ከመስመር ውጭ ያስተላልፉ።"))
                        Button(onClick = { page = "rules" }, modifier = Modifier.fillMaxWidth()) { Text(tr("Configure fund", "ፈንድ አዋቅር")) }
                        Button(onClick = { page = "transfer" }, modifier = Modifier.fillMaxWidth()) { Text(tr("Send update / Receive report", "ማሻሻያ ላክ / ሪፖርት ተቀበል")) }
                    }
                    "rules" -> {
                        Text(tr("Weekly fund configuration", "የፈንድ ቅንብር"), style = MaterialTheme.typography.titleLarge)
                        OutlinedTextField(amount, { amount = it.filter(Char::isDigit) }, label = { Text(tr("Weekly installment (ETB)", "ሳምንታዊ ክፍያ (ብር)")) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(grace, { grace = it.filter(Char::isDigit) }, label = { Text(tr("Grace period (days)", "የችሮታ ቀናት")) }, modifier = Modifier.fillMaxWidth())
                        OutlinedTextField(penalty, { penalty = it.filter(Char::isDigit) }, label = { Text(tr("Penalty (ETB)", "ቅጣት (ብር)")) }, modifier = Modifier.fillMaxWidth())
                        Button(onClick = {
                            val a = amount.toIntOrNull(); val g = grace.toIntOrNull(); val p = penalty.toIntOrNull()
                            if (a != null && a > 0 && g != null && g >= 0 && p != null && p >= 0) {
                                version += 1
                                val ok = db.edit().putInt("amount", a).putInt("grace", g).putInt("penalty", p).putInt("rulesVersion", version).commit()
                                transferMessage = if (ok) tr("Rules saved. Send an update file to apply them on the Collector.", "ደንቦች ተቀምጠዋል። በሰብሳቢው ላይ ለመተግበር የማሻሻያ ፋይል ይላኩ።") else "Could not save rules."
                            } else transferMessage = tr("Enter valid non-negative values; installment must be greater than zero.", "ትክክለኛ እሴቶችን ያስገቡ።")
                        }, modifier = Modifier.fillMaxWidth()) { Text(tr("Save rules", "ደንቦችን አስቀምጥ")) }
                        Text("Configuration version: $version")
                        if (transferMessage.isNotBlank()) Text(transferMessage, color = MaterialTheme.colorScheme.primary)
                    }
                    "reports" -> {
                        Text(tr("Imported ledger", "የገቡ መዝገቦች"), style = MaterialTheme.typography.titleLarge)
                        Text(tr("Imported payment transactions: ", "የገቡ የክፍያ ግብይቶች፦ ") + importedCount)
                        Text(tr("Registered member IDs: ", "የተመዘገቡ የአባል መለያዎች፦ ") + (db.getString("importedMembers", "") ?: "").lines().count { it.isNotBlank() })
                        Text(tr("Last report received: ", "የመጨረሻ ሪፖርት የተቀበለበት፦ ") + (db.getString("lastReportAt", "—") ?: "—"))
                        Text(tr("Imported records are stored on this Supervisor phone. Keep independent backups.", "የገቡ መዝገቦች በኃላፊው ስልክ ላይ ይቀመጣሉ።"))
                    }
                    else -> {
                        Text(tr("Offline file transfer", "ከመስመር ውጭ የፋይል ማስተላለፍ"), style = MaterialTheme.typography.titleLarge)
                        Button(onClick = { receiveReport.launch(arrayOf("application/json", "text/*")) }, modifier = Modifier.fillMaxWidth()) {
                            Text(tr("Receive report from Collector", "ከሰብሳቢው ሪፖርት ተቀበል"))
                        }
                        Button(onClick = {
                            val a = amount.toIntOrNull() ?: 0
                            val g = grace.toIntOrNull() ?: 0
                            val p = penalty.toIntOrNull() ?: 0
                            val payload = JSONObject().put("weeklyInstallment", a).put("graceDays", g)
                                .put("penaltyBirr", p).put("configVersion", version)
                                .put("updatedAt", Instant.now().toString())
                            pendingUpdate = JSONObject().put("type", "VT_RULE_UPDATE").put("schemaVersion", 1)
                                .put("payload", payload).put("sha256", sha256(payload.toString())).toString(2)
                            sendUpdate.launch("VT-Rules-Update-v$version.json")
                        }, modifier = Modifier.fillMaxWidth()) { Text(tr("Send rules update to Collector", "የደንብ ማሻሻያ ለሰብሳቢው ላክ")) }
                        Text(tr("Save rules first, then export the update file. The Collector imports that file on its Transfer page.", "መጀመሪያ ደንቦችን ያስቀምጡ፣ ከዚያ የማሻሻያ ፋይሉን ወጪ ያድርጉ።"))
                        if (transferMessage.isNotBlank()) Text(transferMessage, color = MaterialTheme.colorScheme.primary)
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

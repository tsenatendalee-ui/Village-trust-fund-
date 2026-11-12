package org.vt.supervisor
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{Supervisor(this)}}}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Supervisor(ctx:Context){
 val db=ctx.getSharedPreferences("vt_supervisor",Context.MODE_PRIVATE)
 var lang by remember{mutableStateOf(db.getString("lang","")?:"")};var picker by remember{mutableStateOf(lang.isBlank())}
 var page by remember{mutableStateOf("home")};var amount by remember{mutableStateOf(db.getInt("amount",100).toString())};var grace by remember{mutableStateOf(db.getInt("grace",5).toString())};var penalty by remember{mutableStateOf(db.getInt("penalty",0).toString())}
 val am=lang=="am";fun tr(e:String,a:String)=if(am)a else e
 MaterialTheme{Scaffold(topBar={TopAppBar(title={Text("VT Supervisor")},actions={TextButton(onClick={picker=true}){Text(tr("Settings","ቅንብሮች"))}})},bottomBar={NavigationBar{listOf("home" to tr("Home","መነሻ"),"rules" to tr("Fund rules","የፈንድ ደንቦች"),"reports" to tr("Reports","ሪፖርቶች")).forEach{(p,l)->NavigationBarItem(selected=page==p,onClick={page=p},icon={},label={Text(l)})}}}){pad->Column(Modifier.fillMaxSize().padding(pad).padding(18.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
 when(page){
 "home"->{Text(tr("Supervisor dashboard","የኃላፊ ማዕከል"),style=MaterialTheme.typography.headlineSmall);Text(tr("Set fund rules and review local configuration.","የፈንድ ደንቦችን ያዘጋጁ።"));Button(onClick={page="rules"}){Text(tr("Configure fund","ፈንድ አዋቅር"))};Button(onClick={page="reports"}){Text(tr("Reports and status","ሪፖርትና ሁኔታ"))}}
 "rules"->{Text(tr("Weekly fund configuration","የፈንድ ቅንብር"),style=MaterialTheme.typography.titleLarge);OutlinedTextField(amount,{amount=it.filter(Char::isDigit)},label={Text(tr("Weekly installment (ETB)","ሳምንታዊ ክፍያ (ብር)"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(grace,{grace=it.filter(Char::isDigit)},label={Text(tr("Grace period (days)","የችሮታ ቀናት"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(penalty,{penalty=it.filter(Char::isDigit)},label={Text(tr("Penalty (ETB)","ቅጣት (ብር)"))},modifier=Modifier.fillMaxWidth());Button(onClick={val a=amount.toIntOrNull();val g=grace.toIntOrNull();val p=penalty.toIntOrNull();if(a!=null&&a>0&&g!=null&&p!=null){db.edit().putInt("amount",a).putInt("grace",g).putInt("penalty",p).apply()}}){Text(tr("Save rules","ደንቦችን አስቀምጥ"))};Text(tr("Saved locally on this phone.","በዚህ ስልክ ላይ ተቀምጧል።"))}
 else->{Text(tr("Reports / connection status","ሪፖርት / የግንኙነት ሁኔታ"),style=MaterialTheme.typography.titleLarge);Text(tr("Weekly installment: ETB ","ሳምንታዊ ክፍያ፦ ብር ")+amount);Text(tr("Grace days: ","የችሮታ ቀናት፦ ")+grace);Text(tr("Penalty: ETB ","ቅጣት፦ ብር ")+penalty);Text(tr("Collector records are not synchronized to this app yet. Do not treat this as a master ledger or use it to approve real funds.","የሰብሳቢ መዝገቦች ገና አልተመሳሰሉም። ይህን ዋና የገንዘብ መዝገብ አድርገው አይጠቀሙ።"))}
 }
 }}
 if(picker)AlertDialog(onDismissRequest={if(lang.isNotBlank())picker=false},title={Text(tr("Choose language","ቋንቋ ይምረጡ"))},text={Column{TextButton(onClick={lang="en";db.edit().putString("lang","en").apply();picker=false}){Text("English")};TextButton(onClick={lang="am";db.edit().putString("lang","am").apply();picker=false}){Text("አማርኛ")}}},confirmButton={TextButton(onClick={picker=false}){Text(tr("Close","ዝጋ"))}})
 }
}
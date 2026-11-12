package org.vt.collector
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.json.JSONArray
import org.json.JSONObject
import java.time.YearMonth

class MainActivity:ComponentActivity(){override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);setContent{Collector(this)}}}
@OptIn(ExperimentalMaterial3Api::class)
@Composable private fun Collector(ctx:Context){
 val db=ctx.getSharedPreferences("vt_collector",Context.MODE_PRIVATE)
 var lang by remember{mutableStateOf(db.getString("lang","")?:"")};var picker by remember{mutableStateOf(lang.isBlank())}
 var page by remember{mutableStateOf("home")};var name by remember{mutableStateOf("")};var id by remember{mutableStateOf("")}
 var payId by remember{mutableStateOf("")};var start by remember{mutableStateOf("1")};var months by remember{mutableStateOf("1")}
 var members by remember{mutableStateOf(db.getString("members","")?:"")};var records by remember{mutableStateOf(db.getString("payments","")?:"")}
 val am=lang=="am";fun tr(e:String,a:String)=if(am)a else e
 val amount=db.getInt("installment",100)
 MaterialTheme{Scaffold(topBar={TopAppBar(title={Text("VT Collector")},actions={TextButton(onClick={picker=true}){Text(tr("Settings","ቅንብሮች"))}})},bottomBar={NavigationBar{
 listOf("home" to tr("Home","መነሻ"),"members" to tr("Members","አባላት"),"collect" to tr("Collect","ክፍያ"),"history" to tr("History","ታሪክ")).forEach{(p,l)->NavigationBarItem(selected=page==p,onClick={page=p},icon={},label={Text(l)})}
 }}){pad->Column(Modifier.fillMaxSize().padding(pad).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
 when(page){
 "home"->{Text(tr("Offline collection","ከኢንተርኔት ውጭ ክፍያ ስብስብ"),style=MaterialTheme.typography.headlineSmall);Text(tr("Registered members: ","የተመዘገቡ አባላት፦ ")+(if(members.isBlank())"0" else members.split("\n").size.toString()));Text(tr("Saved payment records: ","የተቀመጡ ክፍያዎች፦ ")+(if(records.isBlank())"0" else records.split("\n").size.toString()));Text(tr("Data is stored on this phone only; no secure transfer to Supervisor yet.","መረጃው በዚህ ስልክ ብቻ ይቀመጣል፤ ከኃላፊው ጋር ገና አይገናኝም።"))}
 "members"->{Text(tr("Register member","አባል መዝግብ"),style=MaterialTheme.typography.titleLarge);OutlinedTextField(name,{name=it},label={Text(tr("Full name","ሙሉ ስም"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(id,{id=it},label={Text(tr("Member ID","የአባል መለያ"))},modifier=Modifier.fillMaxWidth());Button(onClick={if(name.isNotBlank()&&id.isNotBlank()&&!members.lines().any{it.startsWith(id.trim()+"|")}){members+=(if(members.isBlank())"" else "\n")+id.trim()+"|"+name.trim();db.edit().putString("members",members).apply();name="";id=""}}){Text(tr("Save member","አባል አስቀምጥ"))};Text(members.replace("|"," — "))}
 "collect"->{Text(tr("Record installments","ወርሃዊ ክፍያ መዝግብ"),style=MaterialTheme.typography.titleLarge);Text(tr("Installment: ETB ","የወር ክፍያ፦ ብር ")+amount);OutlinedTextField(payId,{payId=it},label={Text(tr("Member ID","የአባል መለያ"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(start,{start=it.filter(Char::isDigit)},label={Text(tr("Start offset: 0=current, 1=next month","መነሻ፦ 0=ያለው ወር፣ 1=ቀጣይ"))},modifier=Modifier.fillMaxWidth());OutlinedTextField(months,{months=it.filter(Char::isDigit)},label={Text(tr("Number of complete installments","ሙሉ ወራት ብዛት"))},modifier=Modifier.fillMaxWidth());val s=start.toIntOrNull()?:-1;val n=months.toIntOrNull()?:0;Text(tr("Total ETB ","ጠቅላላ ብር ")+(n*amount));Button(onClick={val member=members.lines().firstOrNull{it.startsWith(payId.trim()+"|")};if(member!=null&&s>=0&&n>0&&s+n-1<=6){val periods=(0 until n).map{YearMonth.now().plusMonths((s+it).toLong()).toString()};val duplicate=periods.any{p->records.lines().any{it.startsWith(payId.trim()+"|")&&it.contains("|"+p+"|")}};if(!duplicate){records+=(if(records.isBlank())"" else "\n")+payId.trim()+"|"+member.substringAfter("|")+"|"+(n*amount)+"|"+periods.joinToString(",")+"|"+java.time.LocalDate.now();db.edit().putString("payments",records).apply();payId="";months="1"}}}){Text(tr("Save payment","ክፍያ አስቀምጥ"))};Text(tr("Only complete installments; future periods limited to six months. Duplicate member/month records are blocked.","ሙሉ ክፍያ ብቻ፤ ቅድሚያ ክፍያ እስከ 6 ወር። ድግግሞሽ ይከለከላል።"))}
 else->{Text(tr("Payment history","የክፍያ ታሪክ"),style=MaterialTheme.typography.titleLarge);Text(records.replace("|","  •  ").replace("\n","\n\n"))}
 }
 }}
 if(picker)AlertDialog(onDismissRequest={if(lang.isNotBlank())picker=false},title={Text(tr("Choose language","ቋንቋ ይምረጡ"))},text={Column{TextButton(onClick={lang="en";db.edit().putString("lang","en").apply();picker=false}){Text("English")};TextButton(onClick={lang="am";db.edit().putString("lang","am").apply();picker=false}){Text("አማርኛ")}}},confirmButton={TextButton(onClick={picker=false}){Text(tr("Close","ዝጋ"))}})
 }
}
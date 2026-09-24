package dev.hamstercage.ui

import androidx.compose.foundation.layout.*
import androidx.activity.compose.BackHandler
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.hamstercage.data.HamsterRepository
import dev.hamstercage.data.AppSnapshot
import dev.hamstercage.domain.*
import dev.hamstercage.location.TrackingHealth
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Composable
fun HamsterApp(repository: HamsterRepository,refreshGeneration: Int,requestForeground: ()->Unit,requestBackground: ()->Unit,openAppSettings: ()->Unit,openLocationSettings: ()->Unit) {
    val scope=rememberCoroutineScope()
    val snackbars=remember { SnackbarHostState() }
    var storageError by remember { mutableStateOf(false) }
    val stream=remember(repository) { repository.state.catch { if(it is CancellationException) throw it;storageError=true } }
    val snapshot by stream.collectAsState(initial=null)
    var destination by rememberSaveable { mutableIntStateOf(0) }
    var now by remember { mutableStateOf(Instant.now()) }
    var officeDialog by remember { mutableStateOf(false) }
    var editedOffice by remember { mutableStateOf<Office?>(null) }
    var editedSession by remember { mutableStateOf<Pair<Session?,LocalDate>?>(null) }
    BackHandler(enabled=destination!=0 && !officeDialog && editedSession==null) { destination=0 }
    var writing by remember { mutableStateOf(false) }
    fun action(success: String?=null,block: suspend ()->Unit) {
        if(writing) return
        writing=true
        scope.launch {
            var message: String?=null
            try { block();message=success }
            catch(e: CancellationException) { throw e }
            catch(e: Exception) { message="Could not save this change. Check the values and try again." }
            finally { writing=false }
            message?.let { snackbars.showSnackbar(it) }
        }
    }
    LaunchedEffect(refreshGeneration) {
        try { repository.refreshHealth() }
        catch(e: CancellationException) { throw e }
        catch(e: Exception) { snackbars.showSnackbar("Could not refresh tracking health. Your saved history is unchanged.") }
    }
    LaunchedEffect(Unit) { while(true) { delay(30_000);now=Instant.now() } }
    Scaffold(
        containerColor=CageStyle.Background,
        snackbarHost={SnackbarHost(snackbars)},
        bottomBar={
            NavigationBar(containerColor=CageStyle.Surface,tonalElevation=0.dp) {
                listOf("Dashboard","History","Offices","Settings").forEachIndexed { i,label ->
                    NavigationBarItem(selected=destination==i,onClick={destination=i},icon={DestinationIcon(i,destination==i)},label={Text(label)},colors=NavigationBarItemDefaults.colors(selectedIconColor=CageStyle.Amber,selectedTextColor=CageStyle.Amber,indicatorColor=CageStyle.Warm,unselectedTextColor=CageStyle.Secondary))
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            val state=snapshot
            when {
                storageError -> ScreenColumn { Notice("Saved data could not be loaded","Close and reopen the app to retry. Existing records have not been reset. Avoid clearing app storage if you want to retain them.") }
                state==null -> Column(Modifier.align(Alignment.Center),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(16.dp)) { CircularProgressIndicator();Text("Opening your local record…") }
                else -> {
                    val input=remember(state,now) { AttendanceInput(state.offices,state.events,state.corrections,state.policy,now,state.historyStartDate,state.unknownDates,state.manualSessions) }
                    val result=remember(input) { AttendanceEngine.derive(input) }
                    val health: @Composable ()->Unit = {
                        TrackingPanel(state.trackingHealth,state.offices.any { it.enabled },requestForeground,requestBackground,openAppSettings,openLocationSettings) {
                            action { repository.requestRegistration(force=true);repository.refreshHealth() }
                        }
                    }
                    when(destination) {
                        0 -> DashboardScreen(input,result,state.trackingHealth.canTrack && state.trackingHealth.registeredOfficeCount>0 && state.trackingHealth.registrationError==null,health,{destination=2},{destination=1})
                        1 -> HistoryScreen(input,result,state.manualEventIds,state.eventEvidence,{session,date -> editedSession=session to date},{date -> action("Day marked reviewed") {repository.confirmDayReviewed(date)}})
                        2 -> OfficesScreen(state.offices,{editedOffice=it;officeDialog=true},health)
                        3 -> SettingsScreen(state.policy,
                            {action("Policy saved") {repository.savePolicy(it)}},
                            {action("Date excluded") {repository.saveExclusion(it)}},
                            {action("Exclusion removed") {repository.removeExclusion(it)}},
                            {date,wfh -> action("WFH label updated") {repository.setWfh(date,wfh)}},
                            {action("Attendance history deleted") {repository.deleteAttendanceHistory(it)}},health)
                    }
                    if(officeDialog) OfficeDialog(editedOffice,{officeDialog=false}) { office ->
                        action("Office saved") { repository.saveOffice(office);officeDialog=false }
                    }
                    editedSession?.let { (session,date) ->
                        SessionDialog(session,date,input,{editedSession=null}) { officeId,start,end,note ->
                            action("Session saved") {
                                if(session==null) repository.addManualSession(officeId,start,end,note)
                                else repository.saveCorrection(Correction(UUID.randomUUID().toString(),session.id,start,end,Instant.now(),note))
                                editedSession=null
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TrackingPanel(health: TrackingHealth,hasEnabledOffice: Boolean,requestForeground: ()->Unit,requestBackground: ()->Unit,openAppSettings: ()->Unit,openLocationSettings: ()->Unit,retry: ()->Unit) {
    Panel {
        Text("Automatic detection",style=MaterialTheme.typography.titleMedium)
        val ready=health.canTrack && health.registeredOfficeCount>0 && health.registrationError==null
        Tag(if(ready) "MONITORING CONFIGURED" else "SETUP NEEDED",true)
        Text(health.message,style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
        when {
            !hasEnabledOffice -> Text("Add and enable an office in Offices to begin.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            !health.fineLocation -> {
                Text("First allow precise location while using the app. This identifies which configured office zone you are in.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                Button(onClick=requestForeground) { Text("Allow precise location") }
                TextButton(onClick=openAppSettings) { Text("Open app permission settings") }
            }
            !health.backgroundLocation -> {
                Text("Next, choose Location → Allow all the time in app permissions. This lets office visits be detected with the app closed. You can revoke it whenever you like.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                Button(onClick=requestBackground) { Text("Allow background detection") }
            }
            !health.locationEnabled -> Button(onClick=openLocationSettings) { Text("Open device location settings") }
            !health.playServicesAvailable -> Text("Automatic detection needs Google Play services. You can still add and correct sessions in History.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
            !ready -> OutlinedButton(onClick=retry) { Text("Retry registration") }
            else -> {
                Text("Detection may arrive minutes late. Force-stopping the app pauses automatic monitoring until you reopen it.",style=MaterialTheme.typography.bodyMedium,color=CageStyle.Secondary)
                TextButton(onClick=retry) { Text("Refresh tracking") }
            }
        }
    }
}

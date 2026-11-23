package app.loocal.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import app.loocal.ui.theme.Appearance
import app.loocal.ui.theme.LoocalTheme
import app.loocal.ui.theme.ink

/** Port of iOS `RootView`: the page above, the destination bar below, toasts on top. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoocalApp(vm: ChatViewModel, requested: String? = null, consumeRequest: () -> Unit = {}, onDarkChange: (Boolean) -> Unit) {
    val state by vm.state.collectAsState()
    var tab by remember { mutableStateOf(Tab.CHAT) }
    var settingsPage by remember { mutableStateOf(SettingsPage.HOME) }
    var addingPersona by remember { mutableStateOf(false) }
    var talking by remember { mutableStateOf(false) }
    var errandTalking by remember { mutableStateOf(false) }
    val micLauncher = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { ok -> if (ok) errandTalking = true }
    val context = androidx.compose.ui.platform.LocalContext.current

    // A temporary chat is always dark, so it's obvious at a glance you're off the record.
    val systemDark = isSystemInDarkTheme()
    val dark = (tab == Tab.CHAT && state.isTemporary) || when (state.appearance) {
        Appearance.SYSTEM -> systemDark
        Appearance.LIGHT -> false
        Appearance.DARK -> true
    }
    androidx.compose.runtime.SideEffect { onDarkChange(dark) }

    // The tab last open in each set, so swiping back returns you where you were.
    var remembered by remember { mutableStateOf(mapOf(0 to Tab.CHAT, 1 to Tab.ERRAND)) }
    fun go(destination: Tab) { remembered = remembered + (destination.space to destination); tab = destination }
    androidx.compose.runtime.LaunchedEffect(requested) {
        when (requested) { "chat" -> { vm.newChat(); go(Tab.CHAT) }; "errand" -> go(Tab.ERRAND); "results" -> go(Tab.RESULTS); "models" -> { settingsPage = SettingsPage.MODELS; go(Tab.SETTINGS) } }
        if (requested != null) consumeRequest()
    }
    // Routines whose time came while Loocal was open run without waiting for a relaunch.
    androidx.compose.runtime.LaunchedEffect(Unit) { while (true) { vm.runDueErrands(); kotlinx.coroutines.delay(30_000) } }
    fun choose(destination: Tab) {
        // The middle destination starts a fresh chat when you're already looking at one.
        if (destination == Tab.CHAT && tab == Tab.CHAT && state.chat.messages.isNotEmpty()) vm.newChat() else go(destination)
    }
    BackHandler(tab != Tab.CHAT && settingsPage == SettingsPage.HOME) { go(if (tab.space == 1 && tab != Tab.ERRAND) Tab.ERRAND else Tab.CHAT) }

    val shift = with(LocalDensity.current) { 28.dp.roundToPx() }
    LoocalTheme(dark) {
        val keyboard = WindowInsets.isImeVisible
        Box(Modifier.fillMaxSize().background(ink.paper)) {
            Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                AnimatedContent(
                    tab, label = "page",
                    // Swiping the page moves through the tabs, left to right across both sets.
                    modifier = Modifier.weight(1f).fillMaxWidth().pointerInput(tab, keyboard, settingsPage) {
                        if (keyboard || settingsPage != SettingsPage.HOME) return@pointerInput
                        var total = 0f
                        detectHorizontalDragGestures(onDragStart = { total = 0f }, onDragEnd = {
                            val next = tab.ordinal + if (total < 0) 1 else -1
                            if (kotlin.math.abs(total) > 70.dp.toPx() && next in Tab.entries.indices) go(Tab.entries[next])
                        }) { _, amount -> total += amount }
                    },
                    // Pages drift in from the side of the tab you tapped.
                    transitionSpec = {
                        val forward = targetState.ordinal >= initialState.ordinal
                        val spring = spring<androidx.compose.ui.unit.IntOffset>(dampingRatio = 0.88f, stiffness = 300f)
                        (slideInHorizontally(spring) { if (forward) shift else -shift } + fadeIn(tween(300))) togetherWith
                            (slideOutHorizontally(spring) { if (forward) -shift else shift } + fadeOut(tween(200)))
                    },
                ) { current ->
                    when (current) {
                        Tab.HISTORY -> HistoryScreen(state, open = { vm.open(it); go(Tab.CHAT) }, delete = vm::delete, togglePin = vm::togglePin)
                        Tab.CHAT -> ChatScreen(state, vm, openPersonas = { addingPersona = true; go(Tab.SETTINGS) }, openConversation = { talking = true })
                        Tab.SETTINGS -> SettingsScreen(state, vm, settingsPage, { settingsPage = it }, addingPersona) { addingPersona = false }
                        Tab.RESULTS -> ResultsScreen(state, vm, openChat = { go(Tab.CHAT) })
                        Tab.ERRAND -> ErrandScreen(state, vm, openPersonas = { addingPersona = true; go(Tab.SETTINGS) }, openVoice = {
                            if (context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED) errandTalking = true
                            else micLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
                        })
                        Tab.ROUTINES -> RoutinesScreen(state, vm, newErrand = { go(Tab.ERRAND) })
                    }
                }
                AnimatedVisibility(
                    !keyboard,
                    enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    // Sits low, like Soma: tucked toward the gesture bar.
                    Box(Modifier.fillMaxWidth().background(ink.paper).padding(top = 6.dp).offset(y = 4.dp), contentAlignment = Alignment.Center) {
                        DestinationBar(tab, remembered, ::choose, flip = { space -> go(remembered[space] ?: Tab.entries[space * 3 + 1]) })
                    }
                }
            }
            AnimatedVisibility(errandTalking, enter = fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.96f), exit = fadeOut()) {
                val source = remember { vm.errandVoice() }
                ErrandVoiceScreen(state, vm, source) { errandTalking = false }
            }
            AnimatedVisibility(talking, enter = fadeIn() + androidx.compose.animation.scaleIn(initialScale = 0.96f), exit = fadeOut()) {
                val conversation = remember { vm.conversation() }
                ConversationScreen(conversation) { talking = false }
            }
            Box(Modifier.align(Alignment.TopCenter)) { Toast(state.error ?: state.notice, vm::dismissError, isError = state.error != null) }
        }
    }
}

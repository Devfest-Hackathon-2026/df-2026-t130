// SPDX-License-Identifier: GPL-3.0-only

package io.github.incallagent.rotep.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipboardManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import io.github.incallagent.agent.live.LiveAuth
import io.github.incallagent.core.policy.DelegationPolicy
import io.github.incallagent.core.policy.PolicyProblem
import io.github.incallagent.core.policy.PolicyValidator
import io.github.incallagent.rotep.calendar.BookingCalendar
import io.github.incallagent.rotep.calendar.bookingCalendar
import io.github.incallagent.rotep.call.CallAudioPath
import io.github.incallagent.rotep.call.CallControl
import io.github.incallagent.rotep.call.CallSession
import io.github.incallagent.rotep.call.CallSoundGuard
import io.github.incallagent.rotep.call.DialCheck
import io.github.incallagent.rotep.call.EmergencyNumbers
import io.github.incallagent.rotep.call.ExecutorScheduler
import io.github.incallagent.rotep.call.HandlerScheduler
import io.github.incallagent.rotep.call.LeftMic
import io.github.incallagent.rotep.call.LiveConnector
import io.github.incallagent.rotep.call.dual.CallBrainPrompt
import io.github.incallagent.rotep.call.dual.ClaudeCallBrain
import io.github.incallagent.rotep.call.dual.DualLiveConnector
import io.github.incallagent.rotep.call.dual.DualSpoken
import io.github.incallagent.rotep.call.dual.OkHttpVoiceSockets
import io.github.incallagent.rotep.call.LocalAudioPath
import io.github.incallagent.rotep.call.ManualCallControl
import io.github.incallagent.rotep.call.NoCallControl
import io.github.incallagent.rotep.call.ReturnToApp
import io.github.incallagent.rotep.call.RotepLive
import io.github.incallagent.rotep.call.ShellAudioPaths
import io.github.incallagent.rotep.call.SpeakingStyle
import io.github.incallagent.rotep.call.SpokenNumbers
import io.github.incallagent.rotep.call.withFlatText
import io.github.incallagent.rotep.call.withSpokenPhrases
import io.github.incallagent.rotep.call.SpokenReading
import io.github.incallagent.rotep.call.TelecomCallControl
import io.github.incallagent.rotep.call.UserCue
import io.github.incallagent.rotep.history.BookingHistory
import io.github.incallagent.rotep.intake.IntakeChat
import io.github.incallagent.rotep.intake.IntakeGrounding
import io.github.incallagent.rotep.intake.IntakeClient
import io.github.incallagent.rotep.intake.IntakeDraft
import io.github.incallagent.rotep.intake.IntakeWebSearch
import io.github.incallagent.rotep.request.PhoneRelay
import io.github.incallagent.rotep.request.PhoneRequestStore
import io.github.incallagent.rotep.request.scheduledCallLost
import io.github.incallagent.rotep.request.PhoneRequestHandler
import io.github.incallagent.rotep.request.callReport
import io.github.incallagent.rotep.request.changedOnPhone
import io.github.incallagent.rotep.practice.PracticePolicy
import io.github.incallagent.rotep.research.ShopLookup
import io.github.incallagent.rotep.schedule.AndroidScheduleAlarms
import io.github.incallagent.rotep.schedule.NotPlacedNote
import io.github.incallagent.rotep.schedule.ScheduleNotices
import io.github.incallagent.rotep.schedule.ScheduleOffer
import io.github.incallagent.rotep.schedule.ScheduleRules
import io.github.incallagent.rotep.schedule.ScheduledCall
import io.github.incallagent.rotep.schedule.ScheduledCallRunner
import io.github.incallagent.rotep.schedule.ScheduledCallStore
import io.github.incallagent.rotep.schedule.ScheduledCalls
import io.github.incallagent.rotep.schedule.scheduleBlocked
import io.github.incallagent.rotep.schedule.scheduledEndNotice
import io.github.incallagent.rotep.service.CallService
import io.github.incallagent.rotep.shell.HelperClient
import io.github.incallagent.rotep.shell.adb.AdbSetup
import io.github.incallagent.rotep.shell.adb.AdbStartOutcome
import io.github.incallagent.rotep.shell.adb.AdbStartPolicy
import io.github.incallagent.rotep.shell.adb.helperProblemForCall
import io.github.incallagent.rotep.ui.FollowUp
import io.github.incallagent.rotep.ui.LookupUi
import io.github.incallagent.rotep.ui.RequestForm
import io.github.incallagent.rotep.ui.followUpForm
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * The app's state across screens and the one running call. Lives as long as the process, so the
 * call keeps going when the screen is closed (CallService keeps the process in front).
 * It only holds the application context, never an Activity.
 */
@SuppressLint("StaticFieldLeak")
object RotepApp {
    private const val REQUEST_OPEN = "ほかの AI からの依頼を開いています。先に断るか、電話を終えてください"
    private const val CHAT_IN_PROGRESS = "AI の返事を待っています。返事が来てから開いてください"
    private const val NUMBER_NOT_KEPT = "電話番号は残していません。入れ直してください"

    enum class Screen { REQUEST, INTAKE, CONFIRM, CALL, RESULT, SETTINGS, HISTORY }

    enum class Stage(val label: String) {
        L0("練習"),
        L2("電話をかける"),
        L3("全自動で電話をかける"),
    }

    /** The call that runs or ran last. [manual] is set in L2. */
    class RunningCall(
        val session: CallSession,
        val stage: Stage,
        val manual: ManualCallControl?,
        val warning: String?,
        /** The speaker, the microphone, the keypad and "電話を切る" of the call screen. */
        val control: CallControl? = null,
        /** The AI's voice needs the phone's microphone on ([Settings.keepMicOn]). */
        val aiNeedsMic: Boolean = false,
        /** The phone plays nothing while the AI has the call ([keepsCallQuiet]). */
        val quietWhileAi: Boolean = false,
    )

    /** A request from another AI that the user opened: [form] as it came, to tell what they changed. */
    private class LinkedRequest(val id: String, val form: RequestForm, val formBefore: RequestForm, val filledByChatBefore: Boolean, val back: Screen)

    private class Pending(
        val policy: DelegationPolicy,
        val key: String,
        /** The thinking model's key (ADR-053); null: this call is Gemini alone. */
        val claudeKey: String?,
        val settings: Settings,
        val stage: Stage,
        val request: LinkedRequest?,
        /** null: not known any more (a call at a chosen time, in a process that started after it was approved). */
        val changedOnPhone: Boolean?,
        val readings: List<SpokenReading>,
        /** Placed at a time the user chose, with nobody at the phone (ADR-040). */
        val scheduled: Boolean = false,
        /** The other AI's request behind a scheduled call: its result goes back to that AI (ADR-042). */
        val requestId: String? = null,
        /** What the confirm screen showed before a scheduled call took it over; back if the call fails to start. */
        val draftBefore: DelegationPolicy? = null,
    )

    private val zone: ZoneId = ZoneId.of("Asia/Tokyo")
    private val main = Handler(Looper.getMainLooper())
    private lateinit var context: Context
    private lateinit var store: SettingsStore

    @Volatile
    private var pending: Pending? = null
    private val returnToApp = ReturnToApp()
    private lateinit var requests: PhoneRequestStore

    /** Between the end of [pending] and the running call: read from the background thread of ADR-049. */
    @Volatile
    private var launching = false

    /** Getting the helper ready for a call that has been approved (ADR-049). Main thread. */
    private val _preparingCall = MutableStateFlow(false)
    val preparingCall: StateFlow<Boolean> = _preparingCall
    private var prepareTicket: AdbSetup.PrepareTicket? = null

    /** The request behind the form and the draft, until its call starts. Main thread. */
    private var linkedRequest: LinkedRequest? = null

    /** Answers another AI over adb; the app need not be open for it. */
    lateinit var requestHandler: PhoneRequestHandler
        private set

    private val _screen = MutableStateFlow(Screen.INTAKE)
    val screen: StateFlow<Screen> = _screen
    private var beforeSettings = Screen.INTAKE
    private var beforeHistory = Screen.INTAKE

    /** The request screen as typed (kept while the process lives). */
    val form = MutableStateFlow(RequestForm())

    private val _lookup = MutableStateFlow<LookupUi>(LookupUi.None)

    /** What was looked up about the shop in [draft], before the call. */
    val lookup: StateFlow<LookupUi> = _lookup

    private val _intakeChat = MutableStateFlow<IntakeChat?>(null)

    /** The chat that fills in the request, while it is open. */
    val intakeChat: StateFlow<IntakeChat?> = _intakeChat

    /** What the chat last put in the form: a form that differs from it was changed by hand. */
    private var lastFromChat: RequestForm? = null

    private val intakeClient by lazy {
        IntakeClient(::claudeKeyForChat, { RotepLog.i("intake", it) }, keyProblem = { store.claudeKeyProblem ?: UserMessages.CLAUDE_KEY_MISSING })
    }
    private val intakeSearch by lazy { IntakeWebSearch(::claudeKeyForChat, { RotepLog.i("intake", it) }) }
    private val warmedUp = AtomicBoolean()

    private val _filledByChat = MutableStateFlow(false)

    /** The form holds what came out of the chat: the confirm screen says so. */
    val filledByChat: StateFlow<Boolean> = _filledByChat

    /** Which look-up is current: an answer for an earlier request is dropped. */
    private val lookupRun = AtomicLong()

    /** The policy built from [form], shown on the confirm screen. Not approved. */
    private val _draft = MutableStateFlow<DelegationPolicy?>(null)
    val draft: StateFlow<DelegationPolicy?> = _draft

    private val _settings = MutableStateFlow(Settings())
    val settings: StateFlow<Settings> = _settings

    /** An error or warning for the top of the screen. */
    val message = MutableStateFlow<String?>(null)

    private val _call = MutableStateFlow<RunningCall?>(null)
    val call: StateFlow<RunningCall?> = _call

    private lateinit var lastResults: LastResultStore
    private val _lastResult = MutableStateFlow<SavedResult?>(null)

    /** The result of the last real call, also from before this run of the app. */
    val lastResult: StateFlow<SavedResult?> = _lastResult

    /** The request from another AI that waits for the user, if any. */
    val incoming: StateFlow<PhoneRequestStore.Incoming?> get() = requests.incoming

    /** A booking made by a call, in the phone's calendar (ADR-039). */
    lateinit var calendar: BookingCalendar
        private set

    private val setUp = OnceDone()

    /** One at a time: the provider that answers over adb can come first, on a binder thread. */
    fun init(c: Context) = setUp.run { setUpApp(c) }

    private fun setUpApp(c: Context) {
        context = c.applicationContext
        store = SettingsStore(context)
        lastResults = LastResultStore(context.noBackupFilesDir) { RotepLog.i("settings", it) }
        _lastResult.value = lastResults.read()
        BookingHistory.init(context)
        calendar = bookingCalendar(context) { RotepLog.i("calendar", it) }
        // A second try, after a failure further down: the relay goes on with the ones it was given
        if (!::requests.isInitialized) {
            requests = PhoneRequestStore(PrefsRequestLedger(context), SystemClock::elapsedRealtime)
            requestHandler = PhoneRequestHandler(requests, handlerPhone)
        }
        PhoneRelay.start(context, requests, requestHandler)
        // A new process has no request waiting: a notification left by the last one leads nowhere
        Alerts.clearRequest(context)
        val lostRequest = requests.expireLeftover()
        _settings.value = store.load()
        // Through the server, the same request is delivered again: nothing is lost then
        if (lostRequest && message.value == null && !PhoneRelay.hasToken) message.value = "ほかの AI からの依頼は、アプリが終了したので消えました。必要なら、もう一度頼んでください"
        val scheduleLog: (String) -> Unit = { RotepLog.i("schedule", it) }
        notPlacedNote = NotPlacedNote.of(context)
        callMark = CallMark.of(context)
        schedules = ScheduledCalls(
            ScheduledCallStore(context.noBackupFilesDir, KeystoreCipher(SCHEDULED_CALL_KEY), scheduleLog),
            AndroidScheduleAlarms(context),
            Instant::now,
            scheduleLog,
        )
        scheduledRunner = ScheduledCallRunner(
            scheduledHost,
            HandlerScheduler(main, onError = { RotepLog.w("schedule", "step failed: ${it.javaClass.simpleName}") }),
            scheduleLog,
        )
        schedules.load(alarmCame = firing != Firing.NONE)?.let {
            if (message.value == null) message.value = it
            requests.scheduledEnded(PhoneRequestStore.Status.NOT_PLACED, it)
        }
        checkSavedKeys()
    }

    /** The key store can be slow, and [init] is often on the main thread. A saved key that cannot be read: asked for again at once. */
    private fun checkSavedKeys() = thread(name = "key-check", isDaemon = true) {
        runCatching {
            store.readApiKey()
            store.readClaudeKey()
        }.onFailure { RotepLog.w("settings", "key check failed: ${it.javaClass.simpleName}") }
        main.post {
            // What could not be read is deleted by now
            _settings.update { it.copy(hasApiKey = store.apiKeySaved, hasClaudeKey = store.claudeKeySaved) }
            if (message.value == null) message.value = store.keyProblem ?: store.claudeKeyProblem
        }
    }

    /** Nothing is sent until the user presses the button: the number may not be a shop's. */
    private fun offerLookup(policy: DelegationPolicy) {
        lookupRun.incrementAndGet()
        _lookup.value = if (ShopLookup.wanted(policy) && _settings.value.hasApiKey) LookupUi.Available else LookupUi.None
    }

    /** "お店を調べる". */
    fun lookUpShop() {
        val policy = _draft.value ?: return
        val run = lookupRun.incrementAndGet()
        _lookup.value = LookupUi.Searching
        thread(name = "shop-lookup", isDaemon = true) {
            // Here and not on the button's thread: the key store can be slow
            val key = store.apiKey()
            if (key == null) {
                val problem = store.keyProblem ?: UserMessages.API_KEY_MISSING
                main.post {
                    if (lookupRun.get() != run) return@post
                    // A key that could not be read is deleted by now
                    _settings.update { it.copy(hasApiKey = store.apiKeySaved) }
                    offerLookup(policy)
                    message.value = problem
                }
                return@thread
            }
            val result = runCatching { ShopLookup(key, { RotepLog.i("lookup", it) }).fetch(policy) }
                .getOrElse {
                    RotepLog.w("lookup", "failed: ${it.javaClass.simpleName}")
                    ShopLookup.Result.Failed("調べられませんでした。少し待ってから、もう一度試してください")
                }
            main.post {
                if (lookupRun.get() != run) return@post
                _lookup.value = when (result) {
                    is ShopLookup.Result.Found -> LookupUi.Found(result.findings)
                    is ShopLookup.Result.Failed -> LookupUi.NotFound(result.reason)
                }
            }
        }
    }

    /** The user has read what was found. */
    fun lookupReviewed() {
        (_lookup.value as? LookupUi.Found)?.let { _lookup.value = it.copy(reviewed = true) }
    }

    /** The user does not want to wait for the look-up: a late answer is dropped. */
    fun skipLookup() {
        lookupRun.incrementAndGet()
        _lookup.value = LookupUi.Available
    }

    /**
     * When the app comes to the front: a call kept quiet whose helper had died leaves its mute
     * behind, and nothing else takes it off. Not during a call of ours, which is quiet on purpose.
     */
    fun restoreCallSoundIfLeft() {
        if (!::context.isInitialized || callRunning()) return
        AdbSetup.restoreIfLeftOn()
        LeftMic.of(context).giveBackIfLeft()
        if (!CallSoundGuard.create(context).restoreIfRemembered()) {
            message.value = "通話の音が消えたままかもしれません。電話中に音量の＋を押すと戻ります"
        }
    }

    fun today(): LocalDate = LocalDate.now(zone)

    // --- requests from another AI ---

    private val handlerPhone = object : PhoneRequestHandler.Phone {
        override fun now(): Instant = Instant.now()

        override fun today(): LocalDate = RotepApp.today()

        override fun callRunning() = RotepApp.callRunning()

        override fun dialProblem(policy: DelegationPolicy) = RotepApp.dialProblem(policy)

        override fun readiness() = RotepApp.readiness()

        override fun practice() = stage() == Stage.L0

        override fun notifyUser() = Alerts.requestArrived(context, PhoneRequestStore.WAITING_MS)

        override fun clearNotice() = Alerts.clearRequest(context)

        override fun scheduleSetupProblem() = scheduleBlocked(schedules.exists, stage(), _settings.value.keepMicOn, false, readiness(), null)
    }

    /**
     * "内容を見る": the request goes through the same form and confirm screen as a typed one. It only
     * ever happens on this tap, so that the screen never changes under the user's finger.
     */
    fun openIncoming() {
        if (callRunning()) return
        val incoming = requests.incoming.value ?: return
        val chat = _intakeChat.value?.ui?.value
        if (chat?.waiting == true) {
            message.value = CHAT_IN_PROGRESS
            return
        }
        Alerts.clearRequest(context)
        val opened = requests.open(incoming.id) ?: run {
            message.value = "依頼の期限が切れました"
            return
        }
        // After a request that ended while open, the form still holds that one: what they typed is older
        linkedRequest = LinkedRequest(incoming.id, opened, linkedRequest?.formBefore ?: form.value, linkedRequest?.filledByChatBefore ?: _filledByChat.value, linkedRequest?.back ?: _screen.value.takeIf { it == Screen.INTAKE || it == Screen.REQUEST } ?: home())
        _filledByChat.value = false
        form.value = opened
        when (val built = opened.toPolicy(UUID.randomUUID().toString(), Instant.now(), today())) {
            is RequestForm.Built.Ok -> toConfirm(built.policy)
            is RequestForm.Built.Errors -> {
                message.value = built.messages.first()
                _draft.value = null
                show(Screen.REQUEST)
            }
        }
    }

    /** When the app comes to the front: nothing else notices that a request expired. */
    fun refreshIncoming() = requests.refresh()

    /** Why the request on the confirm screen can no longer be approved, or null. */
    fun requestProblem(): String? = linkedRequest?.takeUnless { requests.isWaiting(it.id) }?.let { REQUEST_ENDED }

    /** The user replaced the form the request had filled in: it is no longer what they are working on. */
    fun dropOpenedRequest() {
        lastFromChat = null
        _filledByChat.value = false
        val request = linkedRequest ?: return
        requests.decline(request.id)
        linkedRequest = null
    }

    /** "断る". The form goes back to what the user had typed before opening the request. */
    fun declineIncoming() {
        val incoming = requests.incoming.value ?: return
        requests.decline(incoming.id)
        Alerts.clearRequest(context)
        val linked = linkedRequest?.takeIf { it.id == incoming.id } ?: return
        linkedRequest = null
        form.value = linked.formBefore
        _filledByChat.value = linked.filledByChatBefore
        _draft.value = null
        lookupRun.incrementAndGet()
        _lookup.value = LookupUi.None
        if (_screen.value == Screen.CONFIRM) show(linked.back)
        if (beforeSettings == Screen.CONFIRM) beforeSettings = linked.back
    }

    // --- the chat that fills in the request ---

    /** "AI と話して入れる" on the form: back to the conversation, which is kept. */
    fun openIntake() {
        if (callRunning()) return
        if (requests.incoming.value?.opened == true) {
            message.value = REQUEST_OPEN
            return
        }
        ensureIntakeChat()
        message.value = null
        show(Screen.INTAKE)
    }

    /** The first screen makes it. Sends nothing until the user writes something. Main thread. */
    fun ensureIntakeChat() {
        if (_intakeChat.value != null) return
        _intakeChat.value = IntakeChat(
            ::today,
            Instant::now,
            intakeClient,
            { RotepLog.i("intake", it) },
            intakeSearch,
            { stage() == Stage.L0 },
        )
        if (_settings.value.hasClaudeKey && warmedUp.compareAndSet(false, true)) intakeClient.warmUp()
    }

    /** Read for every turn: a key found to be gone also stops the settings from calling it saved. */
    private fun claudeKeyForChat(): String? = when (val read = store.readClaudeKey()) {
        is ApiKeyStore.Read.Key -> read.value
        is ApiKeyStore.Read.NotNow -> null
        else -> {
            _settings.update { it.copy(hasClaudeKey = false) }
            null
        }
    }

    /** Nothing that was said is kept. */
    private fun endIntakeChat() {
        _intakeChat.value?.close()
        _intakeChat.value = null
        lastFromChat = null
        if (message.value == CHAT_IN_PROGRESS) message.value = null
    }

    /** "やり直す": what was said is dropped, and with it what the chat put in the form, unless it was changed by hand. */
    fun restartIntake() {
        if (callRunning()) return
        if (_filledByChat.value && form.value == lastFromChat) form.value = RequestForm()
        _filledByChat.value = false
        endIntakeChat()
        ensureIntakeChat()
    }

    /**
     * The chat replaces the form (nothing typed for an earlier request carries over), but not one
     * changed by hand since. The conversation stays: "直す" on the confirm screen comes back to it.
     */
    fun applyIntake(toConfirm: Boolean) {
        val chat = _intakeChat.value?.ui?.value
        val made = chat?.form?.takeIf { chat.accepted }
        if (made == null) {
            show(Screen.REQUEST)
            return
        }
        // In a practice no number is asked for; this one can never be dialed (DialCheck)
        val filled = if (stage() == Stage.L0 && IntakeGrounding.numberKey(made.phoneNumber) == null) made.copy(phoneNumber = PracticePolicy.PHONE_NUMBER) else made
        if (requests.incoming.value?.opened == true) {
            message.value = REQUEST_OPEN
            show(Screen.REQUEST)
            return
        }
        val before = lastFromChat
        if (before != null && form.value != before) {
            if (filled != before) {
                // Both changed: neither may silently win, the hand may have narrowed and so may the chat
                message.value = "手で直した内容があるので、そのあと会話で変えたことは入っていません。ここで直して「確認へ」を押してください"
                show(Screen.REQUEST)
                return
            }
        } else {
            dropOpenedRequest()
            form.value = filled
            _filledByChat.value = true
            lastFromChat = filled
        }
        if (!toConfirm) {
            show(Screen.REQUEST)
            return
        }
        val current = form.value
        when (val built = current.toPolicy(UUID.randomUUID().toString(), Instant.now(), today())) {
            is RequestForm.Built.Ok -> {
                toConfirm(built.policy)
                // Already read in the conversation, for this shop and these times: not to be confirmed again
                val times = if (current.takesBooking) IntakeDraft.windowsText(current) else emptyList()
                val seen = chat.searches.lastOrNull()?.takeIf { IntakeGrounding.sameText(it.shop, current.businessName) && it.windows == times }
                if (seen != null && _lookup.value == LookupUi.Available) _lookup.value = LookupUi.Found(seen.findings, reviewed = true)
            }
            is RequestForm.Built.Errors -> message.value = built.messages.first()
        }
    }

    // --- screens ---

    fun show(s: Screen) {
        if (s == Screen.SETTINGS && _screen.value != Screen.SETTINGS) beforeSettings = _screen.value
        // Back at the form, a request that ended is over: what they send next is their own
        if (s == Screen.REQUEST && linkedRequest?.let { requests.isWaiting(it.id) } == false) {
            linkedRequest = null
            lastFromChat = null
            message.value = REQUEST_NOW_YOURS
        }
        _screen.value = s
    }

    /** 「電話の記録」: from the first screen or the settings, and back to the one it was opened from. */
    fun openHistory() {
        beforeHistory = historyOpenedFrom(_screen.value, beforeHistory)
        _screen.value = Screen.HISTORY
    }

    fun closeHistory() {
        _screen.value = beforeHistory
    }

    fun closeSettings() {
        _screen.value = beforeSettings
        // The API key may have just been saved
        if (beforeSettings == Screen.CONFIRM && _lookup.value == LookupUi.None) _draft.value?.let(::offerLookup)
    }

    /** Only what the AI can come to say in this call. */
    fun speakable(readings: List<SpokenReading>, p: DelegationPolicy): List<SpokenReading> {
        val said = listOf(p.target.businessName, p.disclosure.reservationName.orEmpty(), p.request.notesForShop.orEmpty()) +
            p.request.preferences.map { it.text } + p.disclosure.mayTell.map { it.value }
        return readings.filter { r -> said.any { r.text in it } }
    }

    /** Where a declined request leaves the user: back at the chat, unless they had typed on the form. */
    private fun home(): Screen = if (_filledByChat.value || form.value == RequestForm()) Screen.INTAKE else Screen.REQUEST

    /** "直す" on the confirm screen: back to where the request was made. */
    fun fixRequest() {
        // While the helper is being started (ADR-049) the way back gives the call up: it must not start behind the person's back
        if (_preparingCall.value) return cancelPreparing()
        show(if (_filledByChat.value) Screen.INTAKE else Screen.REQUEST)
    }

    /** "確認へ" on the form: from here on the form is what counts, not the chat. */
    fun confirmTyped(policy: DelegationPolicy) {
        _filledByChat.value = false
        toConfirm(policy)
    }

    fun toConfirm(policy: DelegationPolicy) {
        _draft.value = policy
        offerLookup(policy)
        message.value = null
        show(Screen.CONFIRM)
    }

    /** After the result: back to a new request (the form keeps what was typed). */
    fun newRequest() {
        if (callRunning()) return
        _call.value = null
        _draft.value = null
        show(if (requests.incoming.value?.opened == true) Screen.REQUEST else Screen.INTAKE)
    }

    /**
     * 「変更」「取り消し」「続き」 on the result screen: the form, filled in by the app (ADR-046). No call
     * is placed from here. The number comes only from the request of this call, kept in memory.
     */
    fun followUp(kind: FollowUp) {
        if (callRunning()) return
        if (requests.incoming.value?.opened == true) {
            newRequest()
            message.value = REQUEST_OPEN
            return
        }
        val running = _call.value
        val kept = _lastResult.value.takeIf { running == null }
        val result = running?.session?.state?.value?.result ?: kept?.result ?: return
        val request = _draft.value.takeIf { running != null && it?.id == result.outcome.policyId }
        val shop = request?.target?.businessName ?: kept?.shopName ?: return newRequest()
        val filled = followUpForm(kind, result, shop, request)
        endIntakeChat()
        _filledByChat.value = false
        form.value = filled
        _call.value = null
        _draft.value = null
        show(Screen.REQUEST)
        message.value = null
        if (filled.phoneNumber.isBlank()) fillNumberFromHistory(filled, result)
    }

    /**
     * A result kept from earlier holds no number; 「予約の控え」 does, if this very call was kept (ADR-045,
     * ADR-046). It only goes into the form: the confirm screen still comes before any call.
     */
    private fun fillNumberFromHistory(filled: RequestForm, result: CallSession.Result) {
        thread(isDaemon = true, name = "follow-up-number") {
            val kept = runCatching { BookingHistory.latestForShop(filled.businessName) }.getOrNull()
            val number = keptNumberOf(kept, result)
            if (number != null) {
                form.update { if (it == filled) it.copy(phoneNumber = number) else it }
            } else if (form.value == filled) {
                message.value = NUMBER_NOT_KEPT
            }
        }
    }

    /** "前回の電話" → "結果を見る". */
    fun showLastResult() {
        if (callRunning() || _lastResult.value == null) return
        _call.value = null
        show(Screen.RESULT)
    }

    /** "この結果を消す". */
    fun deleteLastResult() {
        lastResults.clear()
        calendar.forget()
        _lastResult.value = null
        newRequest()
    }

    // --- settings ---

    fun saveApiKey(key: String) {
        if (key.isBlank()) return
        if (!store.saveApiKey(key)) {
            message.value = UserMessages.API_KEY_NOT_SAVED
            return
        }
        _settings.update { it.copy(hasApiKey = true) }
        clearClipboard()
        RotepLog.i("settings", "API key saved encrypted (${key.trim().length} chars)")
    }

    /** The API key was pasted from the clipboard: clear it, without reading what is there. */
    private fun clearClipboard() {
        try {
            context.getSystemService(ClipboardManager::class.java)?.clearPrimaryClip()
        } catch (t: Throwable) {
            RotepLog.w("settings", "clipboard not cleared: ${t.javaClass.simpleName}")
        }
    }

    fun clearApiKey() {
        store.clearApiKey()
        _settings.update { it.copy(hasApiKey = false) }
        RotepLog.i("settings", "API key cleared")
    }

    /** The notice about a missing or unreadable key is over once there is a new one, or none on purpose. */
    private fun dropClaudeKeyMessage(problem: String?) {
        val shown = message.value ?: return
        if (shown == problem || shown == UserMessages.CLAUDE_KEY_MISSING || shown == UserMessages.API_KEY_NOT_SAVED) message.value = null
    }

    fun saveClaudeKey(key: String) {
        if (key.isBlank()) return
        val problem = store.claudeKeyProblem
        // The Keystore may be making a new key: not on the thread of the tap
        thread(name = "save-key", isDaemon = true) {
            val saved = store.saveClaudeKey(key)
            main.post {
                if (!saved) {
                    message.value = UserMessages.API_KEY_NOT_SAVED
                    return@post
                }
                dropClaudeKeyMessage(problem)
                _settings.update { it.copy(hasClaudeKey = true) }
                warmedUp.set(true)
                intakeClient.warmUp()
                clearClipboard()
                RotepLog.i("settings", "Claude API key saved encrypted (${key.trim().length} chars)")
            }
        }
    }

    fun clearClaudeKey() {
        val problem = store.claudeKeyProblem
        thread(name = "save-key", isDaemon = true) {
            store.clearClaudeKey()
            main.post {
                dropClaudeKeyMessage(problem)
                _settings.update { it.copy(hasClaudeKey = false) }
                RotepLog.i("settings", "Claude API key cleared")
            }
        }
    }

    fun updateSettings(change: (Settings) -> Settings) {
        val s = change(_settings.value).copy(hasApiKey = _settings.value.hasApiKey, hasClaudeKey = _settings.value.hasClaudeKey)
        _settings.value = s
        store.save(s)
        _intakeChat.value?.refresh()
    }

    /** null when a call can start with these settings; otherwise why not (for the confirm screen). */
    fun readiness(): String? {
        val s = _settings.value
        if (callInProgress()) return "通話の途中です"
        // First: no key or helper would help
        callAudioProblem(s, Build.VERSION.SDK_INT)?.let { return it }
        if (!s.hasApiKey) return store.keyProblem ?: UserMessages.API_KEY_MISSING
        dualBrainProblem(s, store.claudeKeyProblem)?.let { return it }
        if (s.audio.shell == null) return null
        val onDemand = AdbSetup.onDemandEnabled
        helperProblemForCall(onDemand, if (onDemand) AdbSetup.onDemandSetupProblem() else null, ShellAudioPaths.factory != null)?.let { return it }
        if (s.control == ControlChoice.AUTO) return TelecomCallControl.readinessProblem(context, appOpAtCallTime = onDemand)
        return null
    }

    /** The automatic control only applies when there is a call: with the phone's own microphone it stays L0. */
    fun stage(): Stage {
        val s = _settings.value
        return when {
            s.audio.shell == null -> Stage.L0
            s.control == ControlChoice.AUTO -> Stage.L3
            else -> Stage.L2
        }
    }

    /**
     * Why [policy]'s number cannot be used, or null. L2 and L3 dial for real ([DialCheck]); every
     * stage refuses what the phone knows as an emergency number. L0 dials nothing, so it goes on
     * when the phone cannot tell.
     */
    fun dialProblem(policy: DelegationPolicy): String? {
        val n = policy.target.phoneNumber
        val dials = stage() != Stage.L0
        if (dials) (DialCheck.check(n, n) as? DialCheck.Result.Refused)?.let { return it.reason }
        return EmergencyNumbers.problem(n, EmergencyNumbers.of(context), dials)
    }

    /** Runtime permissions the call cannot do without. */
    fun requiredPermissions(): List<String> = when (stage()) {
        Stage.L0 -> listOf(Manifest.permission.RECORD_AUDIO)
        Stage.L2 -> listOf(Manifest.permission.CALL_PHONE)
        // READ_PHONE_STATE: TelecomManager.isInCall, so that no call is placed during another call
        Stage.L3 -> listOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_PHONE_STATE)
    }

    /** [requiredPermissions] plus the notification of CallService (the call works without it). */
    fun permissionsToAsk(): List<String> {
        val all = requiredPermissions() + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        return all.filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
    }

    fun missingRequired(): List<String> =
        requiredPermissions().filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    // --- the call ---

    fun callRunning(): Boolean = firing != Firing.NONE || callInProgress()

    /** Without a call at a chosen time that is still getting ready: that one's own checks ask this. */
    private fun callInProgress(): Boolean {
        if (pending != null || launching || _preparingCall.value) return true
        val c = _call.value ?: return false
        return c.session.state.value.phase != CallSession.Phase.ENDED
    }

    /**
     * "承認して電話する": [approved] already carries the approval. Starts CallService first, while
     * the app is in front, and dials only when the service reports back ([serviceStarted]).
     */
    fun startCall(approved: DelegationPolicy) = startCall(approved, prepared = false)

    private fun startCall(approved: DelegationPolicy, prepared: Boolean) {
        if (firing != Firing.NONE) {
            message.value = "時間を指定した電話を、いま始めています"
            return
        }
        readiness()?.let {
            message.value = it
            return
        }
        val problems = PolicyValidator.validate(approved, Instant.now())
        if (!approved.isApproved || problems.isNotEmpty()) {
            message.value = "依頼の内容に問題があります：${problems.firstOrNull()?.message ?: "承認されていません"}"
            return
        }
        dialProblem(approved)?.let {
            message.value = it
            return
        }
        val missing = missingRequired()
        if (missing.isNotEmpty()) {
            message.value = UserMessages.missingPermissions(missing)
            return
        }
        noNetworkProblem(stage(), hasAnyNetwork())?.let {
            message.value = it
            return
        }
        val keys = when (val read = readCallKeys()) {
            is CallKeys.Ready -> read
            CallKeys.NotNow -> {
                // Still saved: the next press reads it again
                message.value = store.keyProblem ?: store.claudeKeyProblem.takeIf { _settings.value.dualBrain }
                return
            }
            // Unreadable since the app started: the settings screen asks for it again
            CallKeys.GeminiGone -> {
                message.value = geminiKeyGone()
                return
            }
            CallKeys.ClaudeGone -> {
                message.value = claudeKeyGone()
                return
            }
        }
        // Before the Pending and its wait for the service: all of the above is asked again afterwards
        if (!prepared && helperNeedsPreparing()) return prepareThenStart(approved)
        val request = linkedRequest
        // Before anything is dialed: a request that was taken back must not turn into a call
        if (request != null && !requests.calling(request.id)) {
            message.value = if (requests.isWaiting(request.id)) REQUEST_NOT_WRITTEN else REQUEST_ENDED
            return
        }
        linkedRequest = null
        val changed = request != null && changedOnPhone(request.form, approved, today())
        val p = Pending(approved, keys.gemini, keys.claude, _settings.value, stage(), request, changed, speakable(form.value.readings, approved))
        pending = p
        _intakeChat.value?.holdStill()
        message.value = null
        val type = when (p.stage) {
            Stage.L0 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
            Stage.L2, Stage.L3 -> ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL
        }
        RotepLog.i("app", "starting ${p.stage} with ${p.settings.audio} voice=${p.settings.voice} slow=${p.settings.slowSpeech}")
        CallService.start(context, type)?.let { serviceFailed(it) }
        // The service reports within a moment; do not wait for ever
        main.postDelayed({ if (pending === p) serviceFailed("CallService から開始の知らせが来ませんでした") }, SERVICE_WAIT_MS)
    }

    /** The Claude key only when the call will use it: with the switch off nothing about it is touched. */
    private fun readCallKeys(): CallKeys = callKeys(store.readApiKey(), if (_settings.value.dualBrain) store.readClaudeKey() else null)

    private fun geminiKeyGone(): String {
        _settings.update { it.copy(hasApiKey = false) }
        return store.keyProblem ?: UserMessages.API_KEY_MISSING
    }

    private fun claudeKeyGone(): String {
        _settings.update { it.copy(hasClaudeKey = false) }
        return store.claudeKeyProblem ?: UserMessages.CLAUDE_KEY_MISSING
    }

    /**
     * With the switch of ADR-049 on, every call goes through the preparation, also with the helper there:
     * the end of the call before this one (which stops that helper) may still be waiting in the same queue.
     */
    private fun helperNeedsPreparing(): Boolean = stage() != Stage.L0 && AdbSetup.onDemandEnabled

    private fun prepareThenStart(approved: DelegationPolicy) {
        _preparingCall.value = true
        message.value = null
        var ticket: AdbSetup.PrepareTicket? = null
        ticket = AdbSetup.prepareForCall(allowCalls = stage() == Stage.L3) { problem ->
            main.post {
                if (prepareTicket !== ticket) return@post // given up
                prepareTicket = null
                _preparingCall.value = false
                if (problem != null) {
                    message.value = problem
                    linkedRequest?.let { requests.couldNotPrepare(it.id, problem) }
                    return@post
                }
                startCall(approved, prepared = true)
                // Refused after all: nothing will end this call, so nothing else would put the switches back
                if (pending == null) AdbSetup.finishAfterCall()
            }
        }
        prepareTicket = ticket
    }

    /** "やめる" while the helper is being started: no call. */
    fun cancelPreparing() {
        // A late tap, when the call has begun already, must not take the helper away from it
        val ticket = prepareTicket ?: return
        ticket.cancel()
        prepareTicket = null
        _preparingCall.value = false
        AdbSetup.finishAfterCall()
    }

    /** From CallService once it is in the foreground, any thread. */
    fun serviceStarted() {
        main.post {
            val p = pending
            when {
                p != null -> launch(p, warning = null)
                firing == Firing.WAITING_FOR_SERVICE -> {
                    firing = Firing.PREPARING
                    limitFiring()
                    scheduledRunner.start()
                }
                firing == Firing.PREPARING -> {}
                // A late report after the start was given up (L2 did not dial): nothing to keep in
                // front. The service has called startForeground by now, so it can be stopped here
                !callRunning() -> CallService.stop(context)
            }
        }
    }

    /** From CallService or [startCall], any thread. L0 goes on with the screen kept open; L2 and L3 do not dial. */
    fun serviceFailed(detail: String) {
        main.post {
            if (firing != Firing.NONE) {
                // Not stopped here: a service that reports late is stopped by its own report
                RotepLog.w("app", "foreground service failed for a scheduled call: $detail")
                firing = Firing.NONE
                if (schedules.exists) scheduledNotPlaced(NOT_READY_FOR_THE_CALL)
                return@post
            }
            val p = pending ?: return@post
            RotepLog.w("app", "foreground service failed for ${p.stage}: $detail")
            when (p.stage) {
                Stage.L0 -> launch(p, warning = "画面を閉じても続けるための準備ができませんでした。会話が終わるまで、この画面を開いたままにしてください")
                Stage.L2, Stage.L3 -> {
                    pending = null
                    callNotStarted(p)
                    message.value = "$NOT_READY_FOR_THE_CALL。少し待ってから、もう一度試してください"
                    AdbSetup.finishAfterCall()
                }
            }
        }
    }

    /** The approved call did not start: its request waits again, and the user can still approve it. */
    private fun callNotStarted(p: Pending) {
        val request = p.request ?: return
        requests.notStarted(request.id)
        linkedRequest = request
    }

    private fun launch(p: Pending, warning: String?) {
        if (pending !== p) return
        launching = true
        pending = null
        val audio: CallAudioPath
        val control: CallControl
        var manual: ManualCallControl? = null
        val mode = p.settings.audio.shell
        try {
            if (mode == null) {
                audio = LocalAudioPath(context, p.settings.loudspeaker, RotepLog.audio)
                control = NoCallControl()
            } else {
                val factory = ShellAudioPaths.factory ?: error(UserMessages.HELPER_NOT_RUNNING)
                audio = factory.create(context, mode, RotepLog.audio, keepsCallQuiet(p.settings))
                if (p.stage == Stage.L3) {
                    control = TelecomCallControl.create(context, p.policy.target.phoneNumber, nobodyThere = p.scheduled) { RotepLog.i("control", it) }
                } else {
                    manual = ManualCallControl(context, p.policy.target.phoneNumber) { RotepLog.i("control", it) }
                    control = manual
                }
            }
        } catch (t: Throwable) {
            launching = false
            CallService.stop(context)
            callNotStarted(p)
            RotepLog.w("app", "not ready for the call: ${t.javaClass.simpleName}")
            message.value = t.message?.takeIf { it == UserMessages.HELPER_NOT_RUNNING } ?: "$NOT_READY_FOR_THE_CALL。少し待ってから、もう一度試してください"
            if (p.scheduled) {
                ScheduleNotices.notPlaced(context, "通話の準備ができませんでした")
                _draft.value = p.draftBefore
            }
            p.requestId?.let { requests.callNotPlaced(it, "通話の準備ができませんでした") }
            AdbSetup.finishAfterCall()
            return
        }
        val inCall = p.stage != Stage.L0
        val scheduler = ExecutorScheduler(onError = { RotepLog.w("call", "step failed: ${it.javaClass.simpleName}") })
        val config = callSessionConfig(p.stage, p.settings, unattended = p.scheduled)
        val style = SpeakingStyle(
            p.settings.voice.voiceName,
            p.settings.slowSpeech,
            p.policy.purpose,
            p.policy.target.kind,
            solo = p.policy.request.partySize?.preferred == 1,
            readings = p.readings + SpokenNumbers.of(p.policy),
            writtenOn = ScheduleRules.writtenOn(p.policy.approval?.approvedAt, today()),
        )
        val claudeKey = p.claudeKey
        val callPolicy = policyForCall(p.policy, dualBrain = claudeKey != null)
        // The call screen shows the limit from here
        if (callPolicy !== p.policy) {
            _draft.update { shown ->
                if (shown?.id == callPolicy.id) shown.copy(conversation = shown.conversation.copy(maxCallSeconds = callPolicy.conversation.maxCallSeconds)) else shown
            }
        }
        val session = CallSession(
            policy = callPolicy,
            audio = audio,
            control = control,
            live = if (claudeKey != null) dualConnector(p, claudeKey, style) else LiveConnector { RotepLive.connect(LiveAuth.ApiKey(p.key), it, style) },
            scheduler = scheduler,
            today = today(),
            config = config,
            listener = object : CallSession.Listener {
                override fun onAlert(reason: CallSession.EndReason, text: String) = Alerts.takeOver(context, inCall)

                // Step by step: a failure of one must not keep the service, the debugging switches or the screen from coming back
                override fun onResult(result: CallSession.Result) {
                    afterCall("report") {
                        (p.request?.id ?: p.requestId)?.let { id ->
                            // Never dialed: not a call that ended
                            if (inCall && !result.dialed) requests.callNotPlaced(id, result.detail)
                            else requests.finished(id, callReport(result, p.changedOnPhone, practice = p.stage == Stage.L0))
                        }
                    }
                    // Before the notice, whose tap may start the app anew. A practice must not push a real call out
                    afterCall("last result") { if (keepsResult(inCall, result)) keepResult(SavedResult(result, p.stage, p.policy.target.businessName)) }
                    afterCall("mark") { if (inCall) callMark.ended() }
                    afterCall("calendar") { if (keepsResult(inCall, result)) calendar.callEnded(result, p.policy, _settings.value.addToCalendar) }
                    afterCall("notice") {
                        if (p.scheduled) {
                            if (!result.dialed) notPlacedNote.write(result.detail)
                            ScheduleNotices.ended(context, scheduledEndNotice(result))
                        } else when (callEndNotice(inCall, result)) {
                            CallEndNotice.URGENT -> Alerts.urgent(context)
                            CallEndNotice.FINISHED -> Alerts.callOver(context)
                            CallEndNotice.NONE -> {}
                        }
                    }
                    // After the notice, which should not wait for this, and before the service stops
                    afterCall("history") {
                        if (keepsInHistory(inCall, result, p.settings.keepBookingHistory, _settings.value.keepBookingHistory)) {
                            BookingHistory.record(p.policy, result, _call.value?.session?.state?.value?.lines.orEmpty())
                        }
                    }
                    main.post {
                        afterCall("form") {
                            // Only a call that was placed uses the request up. A call for a PC AI was never this chat's
                            val request = p.request
                            if (request != null) {
                                // That call was the PC AI's: what the user had is back
                                form.value = request.formBefore
                                _filledByChat.value = request.filledByChatBefore
                            } else if (result.dialed && !p.scheduled) {
                                // A scheduled call's request was tidied away when it was scheduled: what is here now is a newer one
                                _filledByChat.value = false
                                form.update { it.copy(readings = emptyList()) }
                                endIntakeChat()
                            }
                        }
                        afterCall("scheduler") { scheduler.shutdown() }
                        // A call that began in the moment since this one ended needs these, and puts them back at its own end
                        if (!callRunning()) {
                            afterCall("service") { CallService.stop(context) }
                            afterCall("debugging") { AdbSetup.finishAfterCall() }
                            afterCall("return") { returnToApp.stop() }
                            if (_screen.value == Screen.CALL) _screen.value = Screen.RESULT
                        }
                    }
                }
            },
            log = { RotepLog.i("call", it) },
        )
        _call.value = RunningCall(session, p.stage, manual, warning, control, inCall && !config.muteMicDuringAi, keepsCallQuiet(p.settings))
        launching = false
        show(Screen.CALL)
        if (inCall) returnToApp.dialed(SystemClock.elapsedRealtime())
        afterCall("mark") { if (inCall) callMark.placed(p.policy.target.businessName) }
        session.start()
    }

    private inline fun afterCall(what: String, step: () -> Unit) = attempt(what, { RotepLog.w("app", it) }, step)

    /** Claude decides what is said, and Gemini Live hears and speaks (ADR-053). */
    private fun dualConnector(p: Pending, claudeKey: String, style: SpeakingStyle): LiveConnector {
        val spokenPolicy = p.policy.withFlatText().withSpokenPhrases() // As CallSession gives it to the model
        val log: (String) -> Unit = { RotepLog.i("dual", it) }
        val brain = ClaudeCallBrain(claudeKey, CallBrainPrompt.system(spokenPolicy, today(), style), log)
        val spoken = DualSpoken(spokenPolicy.conversation.aiDisclosure, spokenPolicy.conversation.fallbackPhrase, p.settings.voice.voiceName)
        return DualLiveConnector(LiveAuth.ApiKey(p.key), brain, OkHttpVoiceSockets(), spoken, log)
    }

    /** Also when the file could not be written: until the app ends, the result is still there to read. */
    private fun keepResult(saved: SavedResult) {
        // An older result must not come back as the last call after the app restarts
        if (!lastResults.save(saved)) lastResults.clear()
        _lastResult.value = saved
    }

    fun takeOver() {
        _call.value?.session?.takeOver()
    }

    /** 「断って終える」「少し待ってもらう」 (ADR-046). */
    fun sendUserCue(kind: UserCue) {
        _call.value?.session?.userCue(kind)
    }

    /** L2: "相手が出た". */
    fun answered() {
        _call.value?.manual?.answered()
    }

    /** L2: "切った". */
    fun hungUp() {
        _call.value?.manual?.userHungUp()
    }

    /** MainActivity went out of sight: right after dialing, that is the phone app's call screen. */
    fun mainScreenHidden() {
        if (returnToApp.screenHidden(SystemClock.elapsedRealtime())) HelperClient.bringAppToFront()
    }

    fun setCallSpeaker(on: Boolean) {
        _call.value?.control?.setSpeaker(on)
    }

    /** [off]: the phone's own microphone is stopped. */
    fun setCallMicOff(off: Boolean) {
        _call.value?.control?.setMuted(off)
    }

    /** One key of the keypad: 0-9, * or #. */
    fun pressCallKey(key: Char) {
        _call.value?.control?.sendDtmf(key.toString())
    }

    /** "電話を切る". */
    fun hangUpCall() {
        _call.value?.control?.userHangUp()
    }

    /** "電話の画面を開く": the user goes there on purpose, so the app does not come back by itself. */
    fun openPhoneCallScreen() {
        returnToApp.stop()
        HelperClient.showPhoneCallScreen()
    }

    // --- a call at a time the user chose (ADR-040) ---

    private enum class Firing { NONE, WAITING_FOR_SERVICE, PREPARING }

    /** From the alarm until the call is placed or given up. Read before [init]: the alarm can be the first thing a new process does. */
    @Volatile
    private var firing = Firing.NONE

    private lateinit var schedules: ScheduledCalls
    private lateinit var scheduledRunner: ScheduledCallRunner
    private lateinit var notPlacedNote: NotPlacedNote
    private lateinit var callMark: CallMark

    /** The start of the helper for the call that is due, and why it failed (ADR-049). Main thread. */
    private var scheduledTicket: AdbSetup.PrepareTicket? = null
    private var helperSetupProblem: String? = null

    /** Read with the Gemini key just before the call that is due, until it is placed. Main thread. */
    private var scheduledClaudeKey: String? = null

    /** The wait for the service of the call that is due. Main thread. */
    private var firingTimeout: Runnable? = null

    /** Which wait [limitFiring] is for. Main thread. */
    private var firingRun: Any? = null

    /** The call that waits for its time, for the band on the first screen. */
    val scheduled: StateFlow<ScheduledCalls.Info?> get() = schedules.info

    /** "時間を指定して電話する" on the confirm screen for the request now shown. Asks the system: not on every recomposition. */
    fun scheduleOffer(blocker: String?, checkFailed: Boolean, lookupHold: String?): ScheduleOffer {
        val now = Instant.now()
        val blocked = scheduleBlocked(schedules.exists, stage(), _settings.value.keepMicOn, checkFailed, blocker, lookupHold)
        val suggested = suggestedCallAt()
        return ScheduleOffer(blocked, ScheduleRules.today(now), ScheduleRules.firstChoice(now, suggested), suggested)
    }

    /** The time the request now open asks for, or null. A wish only: the user chooses the time and approves it (ADR-042). */
    fun suggestedCallAt(): Instant? = requests.incoming.value?.takeIf { it.opened }?.callAt

    /**
     * "…に電話する" on the confirm screen: [approved] already carries the approval, given on this
     * screen only. Nothing is dialed now, and everything the call will ask is asked now as well.
     */
    fun scheduleCall(approved: DelegationPolicy, at: LocalDateTime) {
        scheduleProblem(approved, at)?.let {
            message.value = it
            return
        }
        // The saved call says which request it is for: what is left in the ledger can never be taken for it
        val saved = linkedRequest?.let { approved.copy(id = it.id) } ?: approved
        schedules.schedule(ScheduledCall(saved, ScheduleRules.instantOf(at), speakable(form.value.readings, approved)))?.let {
            message.value = it
            return
        }
        RotepLog.i("app", "call scheduled")
        val request = linkedRequest
        if (request != null && !requests.scheduled(request.id, ScheduleRules.instantOf(at), changedOnPhone(request.form, approved, today()))) {
            // Taken back in the moment since the checks, or not written down: no call at that time
            schedules.drop()
            message.value = if (requests.isWaiting(request.id)) REQUEST_NOT_WRITTEN else REQUEST_ENDED
            return
        }
        if (request != null) {
            // Approved as a call at this time: its result goes back when the call is over. What the user had is back
            linkedRequest = null
            form.value = request.formBefore
            _filledByChat.value = request.filledByChatBefore
        } else {
            // As after a call: the request is out of the way, and the form keeps what was typed
            _filledByChat.value = false
            form.update { it.copy(readings = emptyList()) }
            endIntakeChat()
        }
        _draft.value = null
        lookupRun.incrementAndGet()
        _lookup.value = LookupUi.None
        message.value = null
        show(request?.back ?: Screen.INTAKE)
    }

    private fun scheduleProblem(approved: DelegationPolicy, at: LocalDateTime): String? {
        val now = Instant.now()
        scheduleBlocked(schedules.exists, stage(), _settings.value.keepMicOn, false, readiness(), null)?.let { return it }
        requestProblem()?.let { return it }
        ScheduleRules.problem(at, now)?.let { return it }
        if (!ScheduleNotices.canNotify(context)) return SCHEDULE_NEEDS_NOTIFICATIONS
        // As of the time of the call: wishes that will be over by then would stop it
        val problems = PolicyValidator.validate(approved, ScheduleRules.instantOf(at))
        if (problems.any { it.code == PolicyProblem.Code.WINDOW_IN_PAST }) return SCHEDULE_AFTER_WINDOWS
        if (!approved.isApproved || problems.isNotEmpty()) return "依頼の内容に問題があります：${problems.firstOrNull()?.message ?: "承認されていません"}"
        dialProblem(approved)?.let { return it }
        val missing = missingRequired()
        return if (missing.isEmpty()) null else UserMessages.missingPermissions(missing)
    }

    /** "取り消す", on the band or on the notice. The alarm that may be on its way finds no file. */
    fun cancelScheduledCall() {
        schedules.cancel()
        requests.scheduledEnded(PhoneRequestStore.Status.CANCELLED, "本人が取り消しました")
        ScheduleNotices.clearSoon(context)
    }

    /**
     * What to tell the person about a call whose alarm is gone after a restart, or null. Called at boot,
     * so it reads only the scheduled call: not the API keys (the keystore can still be busy then).
     */
    fun lostScheduledCallMessage(c: Context): String? {
        val app = c.applicationContext
        val log: (String) -> Unit = { RotepLog.i("schedule", it) }
        val message = ScheduledCalls(
            ScheduledCallStore(app.noBackupFilesDir, KeystoreCipher(SCHEDULED_CALL_KEY), log),
            AndroidScheduleAlarms(app),
            Instant::now,
            log,
        ).tellIfLost(afterRestart = true) ?: return null
        NotPlacedNote.of(app).write(message)
        // The request behind it (if any) is over too. Not through RotepApp.init: that reads the API keys
        PrefsRequestLedger(app).scheduledCallLost()
        return message
    }

    /** When the app comes to the front: a call whose alarm did not come (the phone restarted). */
    fun refreshScheduled() {
        if (firing != Firing.NONE || callRunning()) return
        schedules.tellIfLost()?.let { message.value = it }
        // No saved call any more: whatever still says it waits for its time is over
        if (!schedules.exists) requests.scheduledEnded(PhoneRequestStore.Status.NOT_PLACED, "端末の再起動などで予定が消えたので、電話をかけませんでした")
        // No call of this process is going on, so a mark is from one that died during its call
        if (message.value == null) callMark.takeLeftBehind()?.let { message.value = it }
        // The notification may have been missed
        if (message.value == null) notPlacedNote.take()?.let { message.value = it }
    }

    /** One minute before. It may come late while the phone is asleep: never after the call. */
    fun scheduledCallSoon() {
        if (!schedules.exists) return
        val at = schedules.info.value?.fireAt
        if (at != null && Duration.between(Instant.now(), at) < ScheduleRules.NOTICE_TOO_LATE) return
        ScheduleNotices.soon(context, quiet = callRunning())
    }

    /**
     * The alarm. The foreground service comes before anything else, before [init] too: the exemption
     * from the background limits lasts 10 seconds from the alarm, and the keystore can be slow after
     * Doze. What is left goes on when the service reports ([serviceStarted]).
     */
    fun scheduledCallDue(app: Context) {
        if (firing != Firing.NONE) return
        if (callRunning()) {
            // Its service stays as it is
            init(app)
            if (schedules.exists) scheduledNotPlaced("ほかの通話の途中だったので、電話をかけませんでした")
            return
        }
        firing = Firing.WAITING_FOR_SERVICE
        val failed = CallService.startAwake(app, ServiceInfo.FOREGROUND_SERVICE_TYPE_PHONE_CALL, SCHEDULED_AWAKE_MS)
        if (failed != null) {
            firing = Firing.NONE
            init(app)
            RotepLog.w("app", "foreground service not started: $failed")
            if (schedules.exists) scheduledNotPlaced(NOT_READY_FOR_THE_CALL)
            return
        }
        // The service reports within a moment; do not wait for ever
        val timeout = Runnable {
            firingTimeout = null
            init(app)
            if (firing == Firing.WAITING_FOR_SERVICE) serviceFailed("CallService から開始の知らせが来ませんでした")
        }
        firingTimeout = timeout
        main.postDelayed(timeout, SERVICE_WAIT_MS)
    }

    /** From CallService, as soon as it is in the foreground: what comes after that may take longer than the wait for it. Main thread. */
    fun serviceUp() {
        firingTimeout?.let { main.removeCallbacks(it) }
        firingTimeout = null
    }

    /** Nothing was dialed: the user is told why, and nothing of the call is kept. */
    private fun scheduledNotPlaced(reason: String) {
        schedules.drop()
        requests.scheduledEnded(PhoneRequestStore.Status.NOT_PLACED, reason)
        ScheduleNotices.clearSoon(context)
        notPlacedNote.write(reason)
        ScheduleNotices.notPlaced(context, reason, quiet = callRunning())
    }

    /** The wait is over without a call. The service is up by now, so it can be stopped. */
    private fun endFiring() {
        firing = Firing.NONE
        try {
            if (!callRunning()) CallService.stop(context)
        } finally {
            AdbSetup.finishAfterCall()
        }
    }

    /** Nothing else ends a wait that hangs, and until it ends [callRunning] refuses every call. */
    private fun limitFiring() {
        val thisOne = Any()
        firingRun = thisOne
        main.postDelayed({
            if (firingRun !== thisOne || firing != Firing.PREPARING) return@postDelayed
            RotepLog.w("schedule", "no call after the longest wait")
            scheduledRunner.giveUp()
            try {
                scheduledHost.releaseHelper()
                if (schedules.exists) scheduledNotPlaced(NOT_READY_FOR_THE_CALL)
            } finally {
                endFiring()
            }
        }, ScheduledCallRunner.Config().longestRunMs)
    }

    private val scheduledHost = object : ScheduledCallRunner.Host {
        override fun now(): Instant = Instant.now()

        override fun read() = schedules.read()

        override fun stillScheduled() = schedules.exists

        override fun prepareHelper(): Boolean {
            helperSetupProblem = null
            if (!helperNeedsPreparing()) return false
            var ticket: AdbSetup.PrepareTicket? = null
            ticket = AdbSetup.prepareForCall(allowCalls = true) { problem ->
                main.post { if (scheduledTicket === ticket) helperSetupProblem = problem }
            }
            scheduledTicket = ticket
            return true
        }

        override fun helperProblem() = helperSetupProblem

        override fun releaseHelper() {
            scheduledTicket?.cancel()
            scheduledTicket = null
            AdbSetup.finishAfterCall()
        }

        override fun helperReady() = ShellAudioPaths.factory != null

        override fun networkReady() = networkLooksUsable()

        /** What [startCall] asks, in its words: the policy, the number, the helper, the key is saved, the permissions, no other call. */
        override fun problem(call: ScheduledCall): String? {
            readiness()?.let { return it }
            if (AdbSetup.onDemandEnabled && ShellAudioPaths.factory == null) return AdbStartPolicy.onDemandProblem(AdbStartOutcome.FAILED)
            if (stage() != Stage.L3) return "設定が変わって「全自動で電話をかける」でなくなったので、電話をかけませんでした"
            if (_settings.value.keepMicOn) return "設定の「端末のマイクを止めない」がオンになっていたので、電話をかけませんでした"
            val problems = PolicyValidator.validate(call.policy, Instant.now())
            if (!call.policy.isApproved || problems.isNotEmpty()) return "依頼の内容に問題があります：${problems.firstOrNull()?.message ?: "承認されていません"}"
            dialProblem(call.policy)?.let { return it }
            val missing = missingRequired()
            return if (missing.isEmpty()) null else UserMessages.missingPermissions(missing)
        }

        override fun readKey(): ScheduledCallRunner.KeyRead = when (val read = readCallKeys()) {
            is CallKeys.Ready -> {
                scheduledClaudeKey = read.claude
                ScheduledCallRunner.KeyRead.Key(read.gemini)
            }
            CallKeys.NotNow -> ScheduledCallRunner.KeyRead.NotNow
            CallKeys.GeminiGone -> ScheduledCallRunner.KeyRead.Problem(geminiKeyGone())
            CallKeys.ClaudeGone -> ScheduledCallRunner.KeyRead.Problem(claudeKeyGone())
        }

        override fun dial(call: ScheduledCall, key: String) {
            // The last thing before the call: however the process ends after this, it is not placed again
            if (!schedules.consume()) return stopQuietly()
            val started = requests.scheduledCallStarts(call.policy.id)
            if (started is PhoneRequestStore.ScheduledStart.NotWritten) {
                scheduledNotPlaced("依頼の記録を保存できなかったので、電話をかけませんでした")
                endFiring()
                return
            }
            ScheduleNotices.clearSoon(context)
            firing = Firing.NONE
            // The call and result screens read the shop and the limit from here
            val draftBefore = _draft.value
            _draft.value = call.policy
            val request = started as? PhoneRequestStore.ScheduledStart.Request
            val p = Pending(call.policy, key, scheduledClaudeKey, _settings.value, stage(), null, request?.changedOnPhone, call.readings, scheduled = true, requestId = request?.id, draftBefore = draftBefore)
            scheduledClaudeKey = null
            pending = p
            launch(p, warning = null)
        }

        override fun notPlaced(reason: String) {
            try {
                scheduledNotPlaced(reason)
            } finally {
                endFiring()
            }
        }

        override fun stopQuietly() {
            try {
                ScheduleNotices.clearSoon(context)
            } finally {
                endFiring()
            }
        }
    }

    /** Needs ACCESS_NETWORK_STATE. Without it the system says nothing, and the call goes ahead as it would have. */
    @SuppressLint("MissingPermission")
    private fun networkLooksUsable(): Boolean = try {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val capabilities = manager.activeNetwork?.let { manager.getNetworkCapabilities(it) }
        capabilities != null &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } catch (_: SecurityException) {
        true
    }

    /** false only when the phone has none at all: one that is still being checked must not stop a call. */
    @SuppressLint("MissingPermission")
    private fun hasAnyNetwork(): Boolean = try {
        context.getSystemService(ConnectivityManager::class.java).activeNetwork != null
    } catch (_: SecurityException) {
        true
    }

    /** Opens track 1's diagnostics screen. Returns null, or why it cannot be opened. */
    fun openDiagnostics(activity: Activity): String? {
        val notYet = "診断の画面を開けませんでした（アプリを入れ直してください）"
        // Our own package: look the activity up directly (no package visibility query needed)
        try {
            activity.packageManager.getActivityInfo(ComponentName(activity, DIAGNOSTICS), 0)
        } catch (e: PackageManager.NameNotFoundException) {
            return notYet
        }
        return try {
            activity.startActivity(Intent().setClassName(activity, DIAGNOSTICS))
            null
        } catch (e: ActivityNotFoundException) {
            notYet
        } catch (e: SecurityException) {
            "診断の画面を開けませんでした"
        }
    }

    private const val SERVICE_WAIT_MS = 5_000L
    private const val NOT_READY_FOR_THE_CALL = "通話の準備ができなかったので、電話をかけませんでした"
    private const val REQUEST_ENDED = "ほかの AI が依頼を取り下げたか、期限が切れました。自分の電話としてかけるなら、戻って、内容を確かめてください"
    private const val REQUEST_NOW_YOURS = "ほかの AI からの依頼は終わりました。ここからは自分の電話になります（結果はその AI に返りません）"
    private const val REQUEST_NOT_WRITTEN = "記録を保存できなかったので、電話をかけませんでした。もう一度押してください"
    private const val DIAGNOSTICS = "io.github.incallagent.rotep.diag.DiagnosticsActivity"
    private const val SCHEDULED_CALL_KEY = "rotep_scheduled_call"

    /** The whole of a scheduled call at the longest: ringing, the limit a user can have set, and the end. */
    private const val SCHEDULED_AWAKE_MS = 60 * 60 * 1000L
    private const val SCHEDULE_NEEDS_NOTIFICATIONS = "通知が許可されていないので、予約しませんでした。かける前の知らせと結果が届かないためです。端末の設定で、このアプリの通知を許可してください"
    private const val SCHEDULE_AFTER_WINDOWS = "指定の時刻には、希望の日時の中に過ぎているものがあります。もっと早い時刻にするか、依頼を直してください"
}

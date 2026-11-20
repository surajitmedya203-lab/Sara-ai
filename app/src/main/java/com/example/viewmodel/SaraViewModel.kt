package com.example.viewmodel

import android.app.Application
import android.media.AudioManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.actions.ActionExecutionResult
import com.example.actions.DeviceActionExecutor
import com.example.actions.DeviceTelemetry
import com.example.actions.InstalledAppInfo
import com.example.actions.ParsedSaraIntent
import com.example.actions.SaraCommandParser
import com.example.data.ai.AiRepository
import com.example.data.ai.ChatTurn
import com.example.data.ai.PlaceholderAiRepository
import com.example.data.local.CommandLogEntity
import com.example.data.local.MemoryNoteEntity
import com.example.data.local.PersonalTaskEntity
import com.example.data.local.RoutineEntity
import com.example.data.local.SaraDatabase
import com.example.data.local.SaraPreferences
import com.example.data.local.UserPreferencesRepository
import com.example.voice.SaraSpeechManager
import com.example.voice.SaraTtsManager
import com.example.voice.SpeechUiState
import com.example.voice.VoiceBiometricManager
import com.example.voice.VoiceFingerprint
import com.example.voice.VoiceVerificationResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class SaraMoodState {
    IDLE,
    LISTENING,
    THINKING,
    SPEAKING,
    ACTION_SUCCESS
}

data class ConversationBubble(
    val id: Long = System.nanoTime(),
    val isUser: Boolean,
    val text: String,
    val badge: String? = null,
    val isSampleAi: Boolean = false
)

data class EnrollmentUiState(
    val isCapturing: Boolean = false,
    val samplesCaptured: Int = 0,
    val totalRequired: Int = 3,
    val statusMessage: String = "",
    val lastVerification: VoiceVerificationResult? = null
)

class SaraViewModel(application: Application) : AndroidViewModel(application) {

    private val db = SaraDatabase.getInstance(application)
    private val dao = db.saraDao()
    private val prefsRepo = UserPreferencesRepository(application)
    private val aiRepo: AiRepository = PlaceholderAiRepository()

    val actionExecutor = DeviceActionExecutor(application)
    val speechManager = SaraSpeechManager(application)
    val ttsManager = SaraTtsManager(application)
    val voiceBiometric = VoiceBiometricManager(application)

    val preferences: StateFlow<SaraPreferences> = prefsRepo.preferences.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = SaraPreferences()
    )

    val tasks: StateFlow<List<PersonalTaskEntity>> = dao.observeTasks().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val memories: StateFlow<List<MemoryNoteEntity>> = dao.observeMemories().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val commandLogs: StateFlow<List<CommandLogEntity>> = dao.observeCommandLogs().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val routines: StateFlow<List<RoutineEntity>> = dao.observeRoutines().stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5000),
        initialValue = emptyList()
    )

    val speechState: StateFlow<SpeechUiState> = speechManager.state
    val micRmsLevel: StateFlow<Float> = speechManager.rmsLevel
    val isSpeaking: StateFlow<Boolean> = ttsManager.isSpeaking
    val ttsMouthAmplitude: StateFlow<Float> = ttsManager.mouthAmplitude
    val flashlightOn: StateFlow<Boolean> = actionExecutor.flashlightOn
    val enrollmentAmplitude: StateFlow<Float> = voiceBiometric.liveAmplitude

    private val _moodState = MutableStateFlow(SaraMoodState.IDLE)
    val moodState: StateFlow<SaraMoodState> = _moodState.asStateFlow()

    private val _messages = MutableStateFlow<List<ConversationBubble>>(emptyList())
    val messages: StateFlow<List<ConversationBubble>> = _messages.asStateFlow()

    private val _telemetry = MutableStateFlow(actionExecutor.readTelemetry())
    val telemetry: StateFlow<DeviceTelemetry> = _telemetry.asStateFlow()

    private val _installedApps = MutableStateFlow<List<InstalledAppInfo>>(emptyList())
    val installedApps: StateFlow<List<InstalledAppInfo>> = _installedApps.asStateFlow()

    private val _enrollmentState = MutableStateFlow(EnrollmentUiState())
    val enrollmentState: StateFlow<EnrollmentUiState> = _enrollmentState.asStateFlow()

    private val _needsContactPermissionPrompt = MutableStateFlow(false)
    val needsContactPermissionPrompt: StateFlow<Boolean> = _needsContactPermissionPrompt.asStateFlow()

    private val _lastUsedSampleAi = MutableStateFlow(false)
    val lastUsedSampleAi: StateFlow<Boolean> = _lastUsedSampleAi.asStateFlow()

    private val enrollmentSamples = mutableListOf<VoiceFingerprint>()

    init {
        seedInitialDataIfNeeded()
        refreshDeviceState()
        loadInstalledApps()
        observeSpeakingForMood()

        _messages.value = listOf(
            ConversationBubble(
                isUser = false,
                text = "হ্যালো! আমি সারা, আপনার থ্রিডি পার্সোনাল ভয়েস অ্যাসিস্ট্যান্ট। আমাকে বাংলা বা ইংরেজিতে যেকোনো কমান্ড বলুন — কল, অ্যালার্ম, ফ্ল্যাশলাইট, অ্যাপ ওপেন, কাজ বা মেমোরি!",
                badge = "READY"
            )
        )
    }

    private fun observeSpeakingForMood() {
        viewModelScope.launch {
            isSpeaking.collect { speaking ->
                if (speaking) {
                    _moodState.value = SaraMoodState.SPEAKING
                } else if (_moodState.value == SaraMoodState.SPEAKING) {
                    _moodState.value = SaraMoodState.IDLE
                }
            }
        }
    }

    private fun seedInitialDataIfNeeded() {
        viewModelScope.launch {
            val prefs = prefsRepo.preferences.first()
            if (!prefs.seededDefaults) {
                if (dao.getRoutinesOnce().isEmpty()) {
                    dao.insertRoutine(
                        RoutineEntity(
                            name = "সকালের ব্রিফিং (Morning Brief)",
                            triggerPhrase = "সকালের রুটিন, morning brief, morning routine",
                            stepsSummary = "সময় ও তারিখ বলা • পেন্ডিং কাজের তালিকা পড়া • ভলিউম ৭০% সেট",
                            actionTags = "TIME_BRIEF,TASK_BRIEF,VOLUME_70"
                        )
                    )
                    dao.insertRoutine(
                        RoutineEntity(
                            name = "রাতের স্লিপ মোড (Sleep Mode)",
                            triggerPhrase = "রাতের রুটিন, রাতের মোড, sleep mode, night mode",
                            stepsSummary = "ফ্ল্যাশলাইট বন্ধ • সাইলেন্ট/ভাইব্রেট মোড • পেন্ডিং মেমোরি সামারি",
                            actionTags = "FLASHLIGHT_OFF,RING_VIBRATE"
                        )
                    )
                    dao.insertRoutine(
                        RoutineEntity(
                            name = "ফোকাস মোড (Focus Mode)",
                            triggerPhrase = "কাজের মোড, ফোকাস মোড, focus mode",
                            stepsSummary = "ভলিউম ২০% • ভাইব্রেট মোড • ফোকাস রিমাইন্ডার",
                            actionTags = "VOLUME_20,RING_VIBRATE,TASK_BRIEF"
                        )
                    )
                }
                if (dao.getPendingTasksOnce().isEmpty()) {
                    dao.insertTask(
                        PersonalTaskEntity(
                            title = "আমার ভয়েস প্রোফাইল সারা-কে শেখানো",
                            notes = "Voice ID ট্যাব থেকে ৩ বার ভয়েস স্যাম্পল রেকর্ড করুন",
                            dueLabel = "Today",
                            priority = "HIGH"
                        )
                    )
                }
                if (dao.getAllMemoriesOnce().isEmpty()) {
                    dao.insertMemory(
                        MemoryNoteEntity(
                            keyTopic = "Sara Tip",
                            content = "বলুন 'মনে রাখো...' তাহলে আমি আপনার ব্যক্তিগত যেকোনো তথ্য মনে রাখবো।",
                            category = "Guide"
                        )
                    )
                }
                prefsRepo.markDefaultsSeeded()
            }
        }
    }

    fun refreshDeviceState() {
        _telemetry.value = actionExecutor.readTelemetry()
    }

    fun loadInstalledApps() {
        viewModelScope.launch(Dispatchers.IO) {
            val apps = actionExecutor.getInstalledLauncherApps()
            _installedApps.value = apps
        }
    }

    fun startVoiceListening() {
        ttsManager.stop()
        speechManager.clearError()
        _moodState.value = SaraMoodState.LISTENING
        val lang = preferences.value.languageCode
        speechManager.startListening(
            languageCode = lang,
            onFinalResult = { spoken ->
                processUserCommand(spoken, fromVoice = true)
            },
            onError = { errMsg ->
                _moodState.value = SaraMoodState.IDLE
                appendBubble(
                    ConversationBubble(
                        isUser = false,
                        text = errMsg,
                        badge = "MIC"
                    )
                )
            }
        )
    }

    fun stopVoiceListening() {
        speechManager.stopListening()
        if (_moodState.value == SaraMoodState.LISTENING) {
            _moodState.value = SaraMoodState.IDLE
        }
    }

    fun dismissContactPermissionPrompt() {
        _needsContactPermissionPrompt.value = false
    }

    fun processUserCommand(rawText: String, fromVoice: Boolean = false) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        appendBubble(ConversationBubble(isUser = true, text = text))
        _moodState.value = SaraMoodState.THINKING

        viewModelScope.launch {
            val prefs = prefsRepo.preferences.first()
            val isBengali = prefs.languageCode.startsWith("bn") || text.any { it in '\u0980'..'\u09FF' }
            val parsed = SaraCommandParser.parse(text)

            var replyText: String
            var category: String
            var actionSummary: String
            var status = "EXECUTED"
            var isSampleAi = false

            when (parsed) {
                is ParsedSaraIntent.ToggleFlashlight -> {
                    val res = actionExecutor.setFlashlight(parsed.enable)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.SetVolumePercent -> {
                    val res = actionExecutor.adjustMediaVolumePercent(parsed.percent)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.StepVolume -> {
                    val res = actionExecutor.stepVolume(parsed.increase)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.SetSoundMode -> {
                    val res = actionExecutor.setRingerMode(parsed.ringerMode)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.SetAlarm -> {
                    val res = actionExecutor.setAlarm(parsed.hour, parsed.minute, parsed.label)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.SetTimer -> {
                    val res = actionExecutor.setTimer(parsed.seconds)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.CallTarget -> {
                    val res = actionExecutor.dialNumberOrContact(parsed.target)
                    if (res.requiresContactPermission) _needsContactPermissionPrompt.value = true
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.SendSms -> {
                    val res = actionExecutor.composeSms(parsed.target, parsed.body)
                    if (res.requiresContactPermission) _needsContactPermissionPrompt.value = true
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.OpenSettings -> {
                    val res = actionExecutor.openSettingsScreen(parsed.action, parsed.label)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.OpenCamera -> {
                    val res = actionExecutor.openCamera()
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.OpenCalendar -> {
                    val res = actionExecutor.openCalendarEvent(parsed.title)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.OpenApp -> {
                    val res = actionExecutor.launchAppByName(parsed.appName)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.WebSearch -> {
                    val res = actionExecutor.webSearch(parsed.query)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.MapsNavigate -> {
                    val res = actionExecutor.navigateToPlace(parsed.place)
                    replyText = if (isBengali) res.saraReplyBn else res.saraReplyEn
                    category = res.category
                    actionSummary = res.actionSummary
                }
                is ParsedSaraIntent.AddTask -> {
                    dao.insertTask(PersonalTaskEntity(title = parsed.title))
                    category = "TASK"
                    actionSummary = "Added task: ${parsed.title}"
                    replyText = if (isBengali) {
                        "আপনার ব্যক্তিগত কাজের তালিকায় \"${parsed.title}\" যোগ করেছি।"
                    } else {
                        "Added \"${parsed.title}\" to your personal tasks."
                    }
                }
                is ParsedSaraIntent.ListTasks -> {
                    val pending = dao.getPendingTasksOnce()
                    category = "TASK"
                    actionSummary = "Read ${pending.size} pending tasks"
                    replyText = SaraCommandParser.formatTasksSummary(pending, isBengali)
                }
                is ParsedSaraIntent.SaveMemory -> {
                    dao.insertMemory(
                        MemoryNoteEntity(keyTopic = parsed.topic, content = parsed.content)
                    )
                    category = "MEMORY"
                    actionSummary = "Saved memory: ${parsed.topic}"
                    replyText = if (isBengali) {
                        "ঠিক আছে ${prefs.userName}, আমি মনে রাখলাম: \"${parsed.content}\""
                    } else {
                        "Got it ${prefs.userName}, I'll remember: \"${parsed.content}\""
                    }
                }
                is ParsedSaraIntent.RecallMemory -> {
                    val all = dao.getAllMemoriesOnce()
                    category = "MEMORY"
                    actionSummary = "Recalled personal memories"
                    replyText = SaraCommandParser.formatMemoryRecall(parsed.query, all, isBengali)
                }
                is ParsedSaraIntent.RunRoutine -> {
                    val allRoutines = dao.getRoutinesOnce()
                    val matched = SaraCommandParser.matchRoutine(parsed.query, allRoutines)
                    if (matched != null) {
                        val summary = executeRoutineSteps(matched, isBengali)
                        category = "ROUTINE"
                        actionSummary = "Executed ${matched.name}"
                        replyText = summary
                    } else {
                        category = "ROUTINE"
                        actionSummary = "No routine found"
                        replyText = if (isBengali) "কোনো রুটিন পাওয়া যায়নি।" else "No matching routine found."
                    }
                }
                is ParsedSaraIntent.ReadBattery -> {
                    val tel = actionExecutor.readTelemetry()
                    _telemetry.value = tel
                    category = "DEVICE_INFO"
                    actionSummary = "Battery: ${tel.batteryPercent}%"
                    val chargeNoteBn = if (tel.isCharging) "এবং চার্জ হচ্ছে" else ""
                    val chargeNoteEn = if (tel.isCharging) "and charging" else "on battery"
                    replyText = if (isBengali) {
                        "আপনার ফোনের ব্যাটারি এখন ${tel.batteryPercent} শতাংশ $chargeNoteBn।"
                    } else {
                        "Your battery is at ${tel.batteryPercent}% $chargeNoteEn."
                    }
                }
                is ParsedSaraIntent.ReadTimeDate -> {
                    category = "TIME_DATE"
                    actionSummary = "Checked current time & date"
                    replyText = SaraCommandParser.formatDateTimeReply(isBengali)
                }
                is ParsedSaraIntent.ReadStorageNetwork -> {
                    val tel = actionExecutor.readTelemetry()
                    _telemetry.value = tel
                    category = "DEVICE_INFO"
                    actionSummary = "Storage ${tel.freeStorageGb}, ${tel.networkLabel}"
                    replyText = if (isBengali) {
                        "ফোনে ${tel.freeStorageGb} খালি স্টোরেজ আছে, নেটওয়ার্ক: ${tel.networkLabel}, এবং ব্যাটারি ${tel.batteryPercent}%।"
                    } else {
                        "Free storage: ${tel.freeStorageGb}, Network: ${tel.networkLabel}, Battery: ${tel.batteryPercent}%."
                    }
                }
                is ParsedSaraIntent.CalculateMath -> {
                    val formattedAns = if (parsed.answer % 1.0 == 0.0) {
                        parsed.answer.toLong().toString()
                    } else {
                        "%.2f".format(parsed.answer)
                    }
                    category = "CALCULATOR"
                    actionSummary = "${parsed.expression} = $formattedAns"
                    replyText = if (isBengali) {
                        "${parsed.expression}-এর উত্তর হলো $formattedAns।"
                    } else {
                        "The answer for ${parsed.expression} is $formattedAns."
                    }
                }
                is ParsedSaraIntent.GenerativeAiQuery -> {
                    val history = _messages.value.takeLast(6).map { ChatTurn(it.isUser, it.text) }
                    val aiResult = aiRepo.generateAssistantReply(
                        prompt = parsed.prompt,
                        userName = prefs.userName,
                        languageCode = prefs.languageCode,
                        history = history
                    )
                    replyText = aiResult.replyText
                    isSampleAi = aiResult.isSampleMode
                    status = if (isSampleAi) "SAMPLE_AI" else "EXECUTED"
                    category = "AI_CHAT"
                    actionSummary = if (isSampleAi) "Sample AI reply" else "Gemini AI reply"
                }
            }

            _lastUsedSampleAi.value = isSampleAi
            refreshDeviceState()

            dao.insertCommandLog(
                CommandLogEntity(
                    spokenText = text,
                    intentCategory = category,
                    actionSummary = actionSummary,
                    saraReply = replyText,
                    status = status,
                    voiceVerified = fromVoice && prefs.isVoiceEnrolled,
                    confidenceScore = if (prefs.isVoiceEnrolled) prefs.lastMatchScore else 100
                )
            )

            appendBubble(
                ConversationBubble(
                    isUser = false,
                    text = replyText,
                    badge = category,
                    isSampleAi = isSampleAi
                )
            )

            _moodState.value = SaraMoodState.ACTION_SUCCESS
            if (prefs.speakResponses) {
                ttsManager.speak(replyText, prefs.languageCode)
            } else {
                kotlinx.coroutines.delay(900L)
                if (_moodState.value == SaraMoodState.ACTION_SUCCESS) {
                    _moodState.value = SaraMoodState.IDLE
                }
            }
        }
    }

    private suspend fun executeRoutineSteps(routine: RoutineEntity, isBengali: Boolean): String {
        val tags = routine.actionTags.split(",").map { it.trim() }
        val results = mutableListOf<String>()
        for (tag in tags) {
            when (tag) {
                "TIME_BRIEF" -> results.add(SaraCommandParser.formatDateTimeReply(isBengali))
                "TASK_BRIEF" -> {
                    val pending = dao.getPendingTasksOnce()
                    results.add(SaraCommandParser.formatTasksSummary(pending, isBengali))
                }
                "VOLUME_70" -> {
                    actionExecutor.adjustMediaVolumePercent(70)
                    results.add(if (isBengali) "ভলিউম ৭০% সেট হয়েছে।" else "Volume set to 70%.")
                }
                "VOLUME_20" -> {
                    actionExecutor.adjustMediaVolumePercent(20)
                    results.add(if (isBengali) "ভলিউম ২০% সেট হয়েছে।" else "Volume set to 20%.")
                }
                "FLASHLIGHT_OFF" -> {
                    actionExecutor.setFlashlight(false)
                    results.add(if (isBengali) "ফ্ল্যাশলাইট বন্ধ করা হয়েছে।" else "Flashlight turned off.")
                }
                "RING_VIBRATE" -> {
                    actionExecutor.setRingerMode(AudioManager.RINGER_MODE_VIBRATE)
                    results.add(if (isBengali) "ফোন ভাইব্রেট মোডে দেওয়া হয়েছে।" else "Phone switched to vibrate.")
                }
            }
        }
        dao.markRoutineRun(routine.id, System.currentTimeMillis())
        refreshDeviceState()
        val header = if (isBengali) "${routine.name} সম্পন্ন হয়েছে:" else "${routine.name} completed:"
        return "$header\n${results.joinToString("\n")}"
    }

    // Direct Hub Actions
    fun toggleFlashlightDirect(enable: Boolean) {
        val res = actionExecutor.setFlashlight(enable)
        refreshDeviceState()
        logDirectAction("Flashlight ${if (enable) "ON" else "OFF"}", res)
    }

    fun setVolumeDirect(percent: Int) {
        val res = actionExecutor.adjustMediaVolumePercent(percent)
        refreshDeviceState()
        logDirectAction("Volume $percent%", res)
    }

    fun openSettingsDirect(action: String, label: String) {
        val res = actionExecutor.openSettingsScreen(action, label)
        logDirectAction("Open $label", res)
    }

    fun launchInstalledApp(app: InstalledAppInfo) {
        val res = actionExecutor.launchPackage(app)
        logDirectAction("Open ${app.label}", res)
    }

    fun runRoutineDirect(routine: RoutineEntity) {
        viewModelScope.launch {
            val isBn = preferences.value.languageCode.startsWith("bn")
            val summary = executeRoutineSteps(routine, isBn)
            dao.insertCommandLog(
                CommandLogEntity(
                    spokenText = routine.name,
                    intentCategory = "ROUTINE",
                    actionSummary = "Ran ${routine.name}",
                    saraReply = summary,
                    status = "EXECUTED"
                )
            )
            if (preferences.value.speakResponses) {
                ttsManager.speak(summary, preferences.value.languageCode)
            }
        }
    }

    private fun logDirectAction(trigger: String, res: ActionExecutionResult) {
        viewModelScope.launch {
            dao.insertCommandLog(
                CommandLogEntity(
                    spokenText = trigger,
                    intentCategory = res.category,
                    actionSummary = res.actionSummary,
                    saraReply = res.saraReplyBn,
                    status = if (res.success) "EXECUTED" else "INFO"
                )
            )
        }
    }

    // Personal CRUD
    fun addTask(title: String, priority: String = "NORMAL") {
        if (title.isBlank()) return
        viewModelScope.launch {
            dao.insertTask(PersonalTaskEntity(title = title.trim(), priority = priority))
        }
    }

    fun toggleTaskCompleted(task: PersonalTaskEntity) {
        viewModelScope.launch {
            dao.setTaskCompleted(task.id, !task.isCompleted)
        }
    }

    fun deleteTask(taskId: Long) {
        viewModelScope.launch { dao.deleteTask(taskId) }
    }

    fun addMemory(topic: String, content: String, category: String = "Personal") {
        if (content.isBlank()) return
        viewModelScope.launch {
            dao.insertMemory(
                MemoryNoteEntity(
                    keyTopic = topic.ifBlank { "Note" },
                    content = content.trim(),
                    category = category
                )
            )
        }
    }

    fun deleteMemory(memoryId: Long) {
        viewModelScope.launch { dao.deleteMemory(memoryId) }
    }

    fun clearHistory() {
        viewModelScope.launch { dao.clearCommandLogs() }
    }

    // Voice Biometric Enrollment & Verification
    fun captureEnrollmentSample() {
        if (_enrollmentState.value.isCapturing) return
        viewModelScope.launch {
            _enrollmentState.value = _enrollmentState.value.copy(
                isCapturing = true,
                statusMessage = "বলুন: \"হ্যালো সারা, আমি তোমার মালিক বলছি\""
            )
            val result = voiceBiometric.captureVoiceSample(durationMs = 2600L)
            val fp = result.fingerprint
            if (fp != null && result.hadVoiceActivity) {
                enrollmentSamples.add(fp)
                val count = enrollmentSamples.size
                if (count >= 3) {
                    val averaged = VoiceFingerprint.average(enrollmentSamples)
                    prefsRepo.saveVoiceProfile(averaged.toJson())
                    enrollmentSamples.clear()
                    _enrollmentState.value = EnrollmentUiState(
                        isCapturing = false,
                        samplesCaptured = 3,
                        totalRequired = 3,
                        statusMessage = "ভয়েস প্রিন্ট সংরক্ষিত হয়েছে! সারা এখন আপনার কণ্ঠ চিনতে পারবে।"
                    )
                } else {
                    _enrollmentState.value = _enrollmentState.value.copy(
                        isCapturing = false,
                        samplesCaptured = count,
                        statusMessage = "স্যাম্পল $count/3 সম্পন্ন (${fp.pitchHz.toInt()} Hz)। পরের স্যাম্পল দিন।"
                    )
                }
            } else {
                _enrollmentState.value = _enrollmentState.value.copy(
                    isCapturing = false,
                    statusMessage = result.errorMessage ?: "স্পষ্ট ভয়েস পাওয়া যায়নি, আবার চেষ্টা করুন।"
                )
            }
        }
    }

    fun verifyCurrentSpeaker() {
        if (_enrollmentState.value.isCapturing) return
        viewModelScope.launch {
            val savedJson = preferences.value.voicePrintJson
            val enrolled = VoiceFingerprint.fromJson(savedJson)
            if (enrolled == null) {
                _enrollmentState.value = _enrollmentState.value.copy(
                    statusMessage = "আগে আপনার ভয়েস প্রোফাইল এনরোল করুন।"
                )
                return@launch
            }
            _enrollmentState.value = _enrollmentState.value.copy(
                isCapturing = true,
                statusMessage = "যাচাইয়ের জন্য যেকোনো একটি বাক্য বলুন..."
            )
            val probeRes = voiceBiometric.captureVoiceSample(durationMs = 2400L)
            val probe = probeRes.fingerprint
            if (probe != null) {
                val verification = voiceBiometric.verifyVoice(enrolled, probe)
                prefsRepo.updateLastMatchScore(verification.scorePercent)
                _enrollmentState.value = _enrollmentState.value.copy(
                    isCapturing = false,
                    statusMessage = verification.detail,
                    lastVerification = verification
                )
                val reply = if (verification.isMatch) {
                    "ভয়েস ভেরিফাই হয়েছে! ${verification.scorePercent}% ম্যাচ করেছে, স্বাগতম।"
                } else {
                    "ভয়েস ম্যাচ ${verification.scorePercent}% — কণ্ঠস্বর পুরোপুরি মেলেনি।"
                }
                if (preferences.value.speakResponses) {
                    ttsManager.speak(reply, preferences.value.languageCode)
                }
            } else {
                _enrollmentState.value = _enrollmentState.value.copy(
                    isCapturing = false,
                    statusMessage = probeRes.errorMessage ?: "ভয়েস পাওয়া যায়নি।"
                )
            }
        }
    }

    fun resetVoiceProfile() {
        enrollmentSamples.clear()
        viewModelScope.launch {
            prefsRepo.clearVoiceProfile()
            _enrollmentState.value = EnrollmentUiState(
                statusMessage = "ভয়েস প্রোফাইল মুছে ফেলা হয়েছে।"
            )
        }
    }

    // Settings updates
    fun updateUserName(name: String) = viewModelScope.launch { prefsRepo.setUserName(name) }
    fun updateLanguage(code: String) = viewModelScope.launch { prefsRepo.setLanguageCode(code) }
    fun updateSpeakResponses(enabled: Boolean) = viewModelScope.launch { prefsRepo.setSpeakResponses(enabled) }
    fun updateVoiceLock(enabled: Boolean) = viewModelScope.launch { prefsRepo.setVoiceLockEnabled(enabled) }
    fun updateShaderStyle(id: String) = viewModelScope.launch { prefsRepo.setShaderStyle(id) }
    fun updateDarkTheme(dark: Boolean) = viewModelScope.launch { prefsRepo.setDarkTheme(dark) }

    private fun appendBubble(bubble: ConversationBubble) {
        _messages.value = (_messages.value + bubble).takeLast(30)
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.stopListening()
        ttsManager.shutdown()
    }
}

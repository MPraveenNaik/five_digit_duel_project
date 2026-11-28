package com.digitduel.game

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.text.InputType
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.digitduel.game.databinding.ActivityMainBinding
import com.digitduel.game.databinding.ItemGuessRowBinding
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout

class MainActivity : AppCompatActivity(), BluetoothGameManager.Listener, OnlineRoomManager.Listener {

    private enum class GameMode { BLUETOOTH, ONLINE, OFFLINE }
    private enum class ScreenState { LOBBY, SETUP, GAME }
    private enum class PendingBluetoothAction { NONE, HOST, JOIN }

    private data class DiscoveredDevice(
        val device: BluetoothDevice,
        val displayName: String,
        val address: String,
        val isPaired: Boolean
    )

    private lateinit var binding: ActivityMainBinding
    private lateinit var playerPrefs: PlayerPrefs
    private lateinit var bluetoothManager: BluetoothGameManager
    private lateinit var onlineRoomManager: OnlineRoomManager
    private lateinit var guessAdapter: GuessAdapter

    private val mainHandler = Handler(Looper.getMainLooper())

    // Match & session state
    private var currentScreen = ScreenState.LOBBY
    private var gameMode = GameMode.OFFLINE
    private var isHost = false
    private var opponentName = "Opponent"

    // User-selected lobby rules (4-7 digits, 1/3/5 match series)
    private var selectedDigitCount = GameLogic.NUMBER_LENGTH
    private var selectedSessionMatches = 5

    // Active session rules & scoreboard
    private var activeDigitCount = GameLogic.NUMBER_LENGTH
    private var activeSessionMatches = 5
    private var roundNumber = 1
    private var localSessionWins = 0
    private var opponentSessionWins = 0

    private var mySecret = ""
    private var opponentSecret = ""
    private var secretRevealedPeek = false
    private var localReady = false
    private var remoteReady = false

    private var isGameplayActive = false
    private var isLocalTurn = true
    private var awaitingRemoteFeedback = false
    private var isGameOver = false
    private var secretRevealSent = false
    private var localPlayAgainSent = false
    private var remotePlayAgainReceived = false

    private val myGuesses = mutableListOf<GameLogic.GuessEvaluation>()
    private val opponentGuesses = mutableListOf<GameLogic.GuessEvaluation>()
    private var activeTabIndex = 0 // 0 = Your Guesses, 1 = Opponent's Guesses

    // Bluetooth & Online UI state
    private var pendingBluetoothAction = PendingBluetoothAction.NONE
    private var hostWaitingDialog: AlertDialog? = null
    private var joinDialog: AlertDialog? = null
    private var onlineDialog: AlertDialog? = null
    private var discoveryReceiverRegistered = false
    private val discoveredDevices = mutableListOf<DiscoveredDevice>()
    private var deviceListAdapter: ArrayAdapter<String>? = null
    private var scanStatusView: TextView? = null
    private var scanProgressView: ProgressBar? = null
    private var scanButtonView: MaterialButton? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val allGranted = results.values.all { it }
        if (allGranted) {
            continuePendingBluetoothAction()
        } else {
            pendingBluetoothAction = PendingBluetoothAction.NONE
            Toast.makeText(
                this,
                "Bluetooth permissions are required for multiplayer.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK || bluetoothManager.adapter?.isEnabled == true) {
            continuePendingBluetoothAction()
        } else {
            pendingBluetoothAction = PendingBluetoothAction.NONE
            Toast.makeText(this, "Bluetooth must be enabled to play multiplayer.", Toast.LENGTH_SHORT).show()
        }
    }

    private val discoverableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // Start listening regardless so a paired device can still connect even if discoverable is declined.
        if (currentScreen == ScreenState.LOBBY && bluetoothManager.currentState == BluetoothGameManager.State.IDLE) {
            bluetoothManager.startHosting()
        }
    }

    private val discoveryReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val device: BluetoothDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                    if (device != null) {
                        addOrUpdateDevice(device, isPaired = false)
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    scanProgressView?.visibility = View.GONE
                    scanButtonView?.isEnabled = true
                    scanStatusView?.text = if (discoveredDevices.isEmpty()) {
                        "No nearby Bluetooth devices found."
                    } else {
                        "Tap a device to connect."
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        applyEdgeToEdgeInsets()

        playerPrefs = PlayerPrefs(this)
        bluetoothManager = BluetoothGameManager(this, this)
        onlineRoomManager = OnlineRoomManager(this)
        guessAdapter = GuessAdapter()

        setupLobbyUi()
        setupSecretUi()
        setupGameplayUi()
        setupBackNavigation()

        refreshProfileUi()
        showScreen(ScreenState.LOBBY)
    }

    private fun applyEdgeToEdgeInsets() {
        val basePadding = (16 * resources.displayMetrics.density).toInt()
        ViewCompat.setOnApplyWindowInsetsListener(binding.rootContainer) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(
                basePadding + bars.left,
                basePadding + bars.top,
                basePadding + bars.right,
                basePadding + bars.bottom
            )
            insets
        }
    }

    private fun setupLobbyUi() {
        binding.etPlayerName.setText(playerPrefs.getPlayerName())

        binding.toggleDigitCount.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedDigitCount = when (checkedId) {
                R.id.btnDigits4 -> 4
                R.id.btnDigits5 -> 5
                R.id.btnDigits6 -> 6
                R.id.btnDigits7 -> 7
                else -> GameLogic.NUMBER_LENGTH
            }
            updateRuleChipStyles()
        }

        binding.toggleSessionMatches.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            selectedSessionMatches = when (checkedId) {
                R.id.btnMatches1 -> 1
                R.id.btnMatches3 -> 3
                R.id.btnMatches5 -> 5
                else -> 5
            }
            updateRuleChipStyles()
        }
        updateRuleChipStyles()

        binding.btnSaveName.setOnClickListener {
            val saved = playerPrefs.setPlayerName(binding.etPlayerName.text?.toString().orEmpty())
            binding.etPlayerName.setText(saved)
            hideKeyboard(binding.etPlayerName)
            Toast.makeText(this, "Saved name: $saved", Toast.LENGTH_SHORT).show()
        }

        binding.btnPlayOnline.setOnClickListener {
            savePlayerNameSilently()
            showHostOrJoinDialog(
                title = "Online Match",
                subtitle = "Create a 6-character room code or join a friend's room.",
                hostLabel = "Host (Create Room)",
                joinLabel = "Join (Enter Code)",
                onHost = {
                    bluetoothManager.disconnect()
                    onlineRoomManager.createRoom(playerPrefs.getPlayerName())
                },
                onJoin = {
                    showJoinOnlineRoomDialog()
                }
            )
        }

        binding.btnPlayBluetooth.setOnClickListener {
            savePlayerNameSilently()
            showHostOrJoinDialog(
                title = "Bluetooth Match",
                subtitle = "Host a nearby game or connect to a nearby phone.",
                hostLabel = "Host Nearby",
                joinLabel = "Join Nearby",
                onHost = {
                    onlineRoomManager.disconnect()
                    startBluetoothFlow(PendingBluetoothAction.HOST)
                },
                onJoin = {
                    onlineRoomManager.disconnect()
                    startBluetoothFlow(PendingBluetoothAction.JOIN)
                }
            )
        }

        binding.btnPracticeOffline.setOnClickListener {
            savePlayerNameSilently()
            startOfflinePractice()
        }
    }

    private fun showHostOrJoinDialog(
        title: String,
        subtitle: String,
        hostLabel: String,
        joinLabel: String,
        onHost: () -> Unit,
        onJoin: () -> Unit
    ) {
        onlineDialog?.dismiss()
        val density = resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, (8 * density).toInt(), pad, (4 * density).toInt())
        }

        var dialog: AlertDialog? = null

        val hostBtn = MaterialButton(this).apply {
            text = hostLabel
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.on_accent))
            backgroundTintList = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.accent_primary)
            )
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (50 * density).toInt()
            ).apply {
                bottomMargin = (10 * density).toInt()
            }
            setOnClickListener {
                dialog?.dismiss()
                onHost()
            }
        }

        val joinBtn = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle
        ).apply {
            text = joinLabel
            isAllCaps = false
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            strokeColor = ColorStateList.valueOf(
                ContextCompat.getColor(this@MainActivity, R.color.accent_secondary)
            )
            strokeWidth = (1.5f * density).toInt()
            cornerRadius = (12 * density).toInt()
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (50 * density).toInt()
            )
            setOnClickListener {
                dialog?.dismiss()
                onJoin()
            }
        }

        container.addView(hostBtn)
        container.addView(joinBtn)

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(subtitle)
            .setView(container)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.setOnDismissListener {
            if (onlineDialog == dialog) onlineDialog = null
        }
        onlineDialog = dialog
        dialog.show()
    }

    private fun updateRuleChipStyles() {
        val selectedBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.accent_primary))
        val unselectedBg = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.bg_input))
        val selectedText = ContextCompat.getColor(this, R.color.on_accent)
        val unselectedText = ContextCompat.getColor(this, R.color.text_primary)

        val digitButtons = mapOf(
            4 to binding.btnDigits4,
            5 to binding.btnDigits5,
            6 to binding.btnDigits6,
            7 to binding.btnDigits7
        )
        digitButtons.forEach { (digits, btn) ->
            val active = digits == selectedDigitCount
            btn.backgroundTintList = if (active) selectedBg else unselectedBg
            btn.setTextColor(if (active) selectedText else unselectedText)
        }

        val matchButtons = mapOf(
            1 to binding.btnMatches1,
            3 to binding.btnMatches3,
            5 to binding.btnMatches5
        )
        matchButtons.forEach { (matches, btn) ->
            val active = matches == selectedSessionMatches
            btn.backgroundTintList = if (active) selectedBg else unselectedBg
            btn.setTextColor(if (active) selectedText else unselectedText)
        }
    }

    private fun setupSecretUi() {
        binding.btnLeaveSetup.setOnClickListener {
            leaveMatchToLobby()
        }

        binding.btnLockSecret.setOnClickListener {
            lockLocalSecret()
        }

        binding.etSecretInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) {
                lockLocalSecret()
                true
            } else {
                false
            }
        }
    }

    private fun setupGameplayUi() {
        binding.rvGuesses.layoutManager = LinearLayoutManager(this)
        binding.rvGuesses.adapter = guessAdapter

        binding.btnLeaveGame.setOnClickListener {
            leaveMatchToLobby()
        }

        binding.layoutSecretPeek.setOnClickListener {
            secretRevealedPeek = !secretRevealedPeek
            updateSecretPeekUi()
        }

        binding.btnSubmitGuess.setOnClickListener {
            submitLocalGuess()
        }

        binding.etGuessInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND || actionId == EditorInfo.IME_ACTION_DONE) {
                submitLocalGuess()
                true
            } else {
                false
            }
        }

        binding.tabLayoutHistory.addTab(binding.tabLayoutHistory.newTab())
        binding.tabLayoutHistory.addTab(binding.tabLayoutHistory.newTab())
        binding.tabLayoutHistory.addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab: TabLayout.Tab) {
                activeTabIndex = tab.position
                refreshGuessList()
            }

            override fun onTabUnselected(tab: TabLayout.Tab) {}
            override fun onTabReselected(tab: TabLayout.Tab) {}
        })

        binding.btnPlayAgain.setOnClickListener {
            handlePlayAgainTapped()
        }

        binding.btnBackToLobby.setOnClickListener {
            leaveMatchToLobby()
        }
    }

    private fun setupBackNavigation() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (currentScreen == ScreenState.LOBBY) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                } else {
                    leaveMatchToLobby()
                }
            }
        })
    }

    private fun savePlayerNameSilently() {
        val raw = binding.etPlayerName.text?.toString().orEmpty()
        if (raw.isNotBlank()) {
            val saved = playerPrefs.setPlayerName(raw)
            binding.etPlayerName.setText(saved)
        }
    }

    private fun refreshProfileUi() {
        binding.tvStats.text = getString(
            R.string.stats_format,
            playerPrefs.getGamesPlayed(),
            playerPrefs.getWins()
        )
    }

    private fun showScreen(screen: ScreenState) {
        currentScreen = screen
        binding.viewLobby.visibility = if (screen == ScreenState.LOBBY) View.VISIBLE else View.GONE
        binding.viewSetup.visibility = if (screen == ScreenState.SETUP) View.VISIBLE else View.GONE
        binding.viewGame.visibility = if (screen == ScreenState.GAME) View.VISIBLE else View.GONE
    }

    // --- Bluetooth Permissions & Flows ---

    private fun startBluetoothFlow(action: PendingBluetoothAction) {
        val btAdapter = bluetoothManager.adapter
        if (btAdapter == null) {
            Toast.makeText(
                this,
                "Bluetooth is unavailable on this device. Try Offline Practice!",
                Toast.LENGTH_LONG
            ).show()
            return
        }
        pendingBluetoothAction = action
        val missing = missingBluetoothPermissions(action)
        if (missing.isNotEmpty()) {
            permissionLauncher.launch(missing.toTypedArray())
            return
        }
        continuePendingBluetoothAction()
    }

    private fun missingBluetoothPermissions(action: PendingBluetoothAction): List<String> {
        val required = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            when (action) {
                PendingBluetoothAction.HOST -> listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_ADVERTISE
                )
                PendingBluetoothAction.JOIN -> listOf(
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.BLUETOOTH_SCAN
                )
                PendingBluetoothAction.NONE -> emptyList()
            }
        } else {
            listOf(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return required.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    @SuppressLint("MissingPermission")
    private fun continuePendingBluetoothAction() {
        val action = pendingBluetoothAction
        if (action == PendingBluetoothAction.NONE) return

        val btAdapter = bluetoothManager.adapter ?: return
        if (!btAdapter.isEnabled) {
            val enableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            enableBluetoothLauncher.launch(enableIntent)
            return
        }

        pendingBluetoothAction = PendingBluetoothAction.NONE
        when (action) {
            PendingBluetoothAction.HOST -> launchHostFlow()
            PendingBluetoothAction.JOIN -> openJoinDeviceDialog()
            PendingBluetoothAction.NONE -> Unit
        }
    }

    @SuppressLint("MissingPermission")
    private fun launchHostFlow() {
        val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
            putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        }
        discoverableLauncher.launch(discoverableIntent)
    }

    @SuppressLint("MissingPermission")
    private fun openJoinDeviceDialog() {
        cleanupJoinDiscovery()
        discoveredDevices.clear()

        val btAdapter = bluetoothManager.adapter ?: return
        val paired = try {
            btAdapter.bondedDevices.orEmpty()
        } catch (_: SecurityException) {
            emptySet()
        }
        paired.forEach { device ->
            addOrUpdateDevice(device, isPaired = true)
        }

        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }

        val statusTv = TextView(this).apply {
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
            textSize = 13f
            text = if (discoveredDevices.isEmpty()) {
                "No paired devices found. Tap Scan Nearby."
            } else {
                "Select a paired device or scan nearby:"
            }
        }
        val progress = ProgressBar(this).apply {
            visibility = View.GONE
        }
        val scanBtn = MaterialButton(this).apply {
            text = "Scan Nearby"
            isAllCaps = false
        }
        val listView = ListView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                (260 * density).toInt()
            )
        }

        val labels = discoveredDevices.map { formatDeviceLabel(it) }.toMutableList()
        val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, labels)
        listView.adapter = adapter

        deviceListAdapter = adapter
        scanStatusView = statusTv
        scanProgressView = progress
        scanButtonView = scanBtn

        scanBtn.setOnClickListener {
            startNearbyScan()
        }

        listView.setOnItemClickListener { _, _, position, _ ->
            val selected = discoveredDevices.getOrNull(position) ?: return@setOnItemClickListener
            cleanupJoinDiscovery()
            joinDialog?.dismiss()
            joinDialog = null
            bluetoothManager.connectToDevice(selected.device)
        }

        container.addView(statusTv)
        container.addView(progress)
        container.addView(scanBtn)
        container.addView(listView)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Join Bluetooth Game")
            .setView(container)
            .setNegativeButton("Cancel") { d, _ ->
                cleanupJoinDiscovery()
                d.dismiss()
            }
            .setOnDismissListener {
                cleanupJoinDiscovery()
                joinDialog = null
            }
            .create()

        joinDialog = dialog
        dialog.show()
    }

    @SuppressLint("MissingPermission")
    private fun startNearbyScan() {
        val btAdapter = bluetoothManager.adapter ?: return
        if (!discoveryReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(BluetoothDevice.ACTION_FOUND)
                addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            }
            ContextCompat.registerReceiver(
                this,
                discoveryReceiver,
                filter,
                ContextCompat.RECEIVER_EXPORTED
            )
            discoveryReceiverRegistered = true
        }

        try {
            if (btAdapter.isDiscovering) {
                btAdapter.cancelDiscovery()
            }
            val started = btAdapter.startDiscovery()
            if (started) {
                scanProgressView?.visibility = View.VISIBLE
                scanButtonView?.isEnabled = false
                scanStatusView?.text = "Scanning for nearby Bluetooth devices…"
            } else {
                scanStatusView?.text = "Unable to start discovery."
            }
        } catch (_: SecurityException) {
            scanStatusView?.text = "Scan permission denied."
        }
    }

    @SuppressLint("MissingPermission")
    private fun addOrUpdateDevice(device: BluetoothDevice, isPaired: Boolean) {
        val address = device.address ?: return
        val name = try {
            device.name?.takeIf { it.isNotBlank() } ?: "Unknown Device"
        } catch (_: SecurityException) {
            "Bluetooth Device"
        }
        val existingIdx = discoveredDevices.indexOfFirst { it.address == address }
        if (existingIdx >= 0) {
            val existing = discoveredDevices[existingIdx]
            if (!existing.isPaired && isPaired) {
                discoveredDevices[existingIdx] = existing.copy(displayName = name, isPaired = true)
            }
        } else {
            discoveredDevices.add(
                DiscoveredDevice(
                    device = device,
                    displayName = name,
                    address = address,
                    isPaired = isPaired
                )
            )
        }
        deviceListAdapter?.let { listAdapter ->
            listAdapter.clear()
            listAdapter.addAll(discoveredDevices.map { formatDeviceLabel(it) })
            listAdapter.notifyDataSetChanged()
        }
    }

    private fun formatDeviceLabel(item: DiscoveredDevice): String {
        val tag = if (item.isPaired) "Paired" else "Nearby"
        return "${item.displayName} ($tag)\n${item.address}"
    }

    @SuppressLint("MissingPermission")
    private fun cleanupJoinDiscovery() {
        try {
            val btAdapter = bluetoothManager.adapter
            if (btAdapter?.isDiscovering == true) {
                btAdapter.cancelDiscovery()
            }
        } catch (_: SecurityException) {
        }
        if (discoveryReceiverRegistered) {
            try {
                unregisterReceiver(discoveryReceiver)
            } catch (_: IllegalArgumentException) {
            }
            discoveryReceiverRegistered = false
        }
        deviceListAdapter = null
        scanStatusView = null
        scanProgressView = null
        scanButtonView = null
    }

    // --- Offline Practice & Round Setup ---

    private fun startOfflinePractice() {
        bluetoothManager.disconnect()
        onlineRoomManager.disconnect()
        gameMode = GameMode.OFFLINE
        isHost = true
        opponentName = "Phone Bot"
        activeDigitCount = selectedDigitCount
        activeSessionMatches = selectedSessionMatches
        roundNumber = 1
        localSessionWins = 0
        opponentSessionWins = 0
        prepareRoundSetup()
    }

    private fun sendMultiplayerLine(line: String) {
        when (gameMode) {
            GameMode.BLUETOOTH -> bluetoothManager.sendLine(line)
            GameMode.ONLINE -> onlineRoomManager.sendLine(line)
            GameMode.OFFLINE -> Unit
        }
    }

    private fun applyDigitCountToInputs() {
        val filter = arrayOf<InputFilter>(InputFilter.LengthFilter(activeDigitCount))
        binding.etSecretInput.filters = filter
        binding.etGuessInput.filters = filter
        binding.tvSetupHint.text = getString(R.string.setup_hint_format, activeDigitCount)
        binding.tilGuessInput.hint = getString(R.string.guess_input_hint_format, activeDigitCount)
    }

    private fun sessionLeadText(): String {
        val lead = when {
            localSessionWins > opponentSessionWins -> "You Lead"
            localSessionWins < opponentSessionWins -> "$opponentName Leads"
            else -> "Tied"
        }
        return "Match $roundNumber/$activeSessionMatches • $activeDigitCount Digits • You $localSessionWins–$opponentSessionWins $opponentName ($lead)"
    }

    private fun prepareRoundSetup() {
        mainHandler.removeCallbacksAndMessages(null)
        mySecret = ""
        opponentSecret = ""
        secretRevealedPeek = false
        localReady = false
        remoteReady = false
        isGameplayActive = false
        awaitingRemoteFeedback = false
        isGameOver = false
        secretRevealSent = false
        localPlayAgainSent = false
        remotePlayAgainReceived = false
        myGuesses.clear()
        opponentGuesses.clear()
        activeTabIndex = 0

        applyDigitCountToInputs()
        binding.etSecretInput.setText("")
        binding.etSecretInput.isEnabled = true
        binding.btnLockSecret.isEnabled = true
        binding.tvSecretError.visibility = View.GONE
        binding.layoutWaitingOpponent.visibility = View.GONE

        binding.tvSetupConnection.text = if (gameMode == GameMode.OFFLINE) {
            "Solo Practice vs $opponentName"
        } else {
            "Duel vs $opponentName"
        }
        binding.tvSetupSessionScore.text = sessionLeadText()

        showScreen(ScreenState.SETUP)
    }

    private fun lockLocalSecret() {
        if (localReady) return
        val input = binding.etSecretInput.text?.toString()?.trim().orEmpty()
        when (val result = GameLogic.validateNumber(input, activeDigitCount)) {
            is GameLogic.ValidationResult.Invalid -> {
                binding.tvSecretError.text = result.errorMessage
                binding.tvSecretError.visibility = View.VISIBLE
            }
            is GameLogic.ValidationResult.Valid -> {
                binding.tvSecretError.visibility = View.GONE
                mySecret = input
                localReady = true
                binding.etSecretInput.isEnabled = false
                binding.btnLockSecret.isEnabled = false
                hideKeyboard(binding.etSecretInput)

                if (gameMode == GameMode.OFFLINE) {
                    opponentSecret = GameLogic.generateRandomSecret(activeDigitCount)
                    remoteReady = true
                    startGameplay()
                } else {
                    binding.tvWaitingOpponent.text = getString(R.string.waiting_opponent, opponentName)
                    binding.layoutWaitingOpponent.visibility = View.VISIBLE
                    sendMultiplayerLine("READY")
                    if (remoteReady) {
                        startGameplay()
                    }
                }
            }
        }
    }

    // --- Gameplay ---

    private fun startGameplay() {
        isGameplayActive = true
        isGameOver = false
        awaitingRemoteFeedback = false
        secretRevealSent = false
        localPlayAgainSent = false
        remotePlayAgainReceived = false
        secretRevealedPeek = false

        isLocalTurn = if (gameMode == GameMode.OFFLINE) {
            roundNumber % 2 == 1
        } else {
            if (isHost) roundNumber % 2 == 1 else roundNumber % 2 == 0
        }

        applyDigitCountToInputs()
        binding.etGuessInput.setText("")
        binding.tvGuessError.visibility = View.GONE
        binding.layoutGuessInput.visibility = View.VISIBLE
        binding.layoutGameOver.visibility = View.GONE
        binding.tvPlayAgainStatus.visibility = View.GONE
        binding.btnPlayAgain.isEnabled = true
        binding.btnPlayAgain.text = getString(R.string.play_again)

        updateSecretPeekUi()
        updateTabTitles()
        binding.tabLayoutHistory.getTabAt(0)?.select()
        activeTabIndex = 0
        refreshGuessList()
        updateTurnUi()

        showScreen(ScreenState.GAME)

        if (gameMode == GameMode.OFFLINE && !isLocalTurn) {
            scheduleBotGuess()
        }
    }

    private fun updateSecretPeekUi() {
        binding.tvMySecretPeek.text = if (secretRevealedPeek) {
            getString(R.string.secret_revealed, mySecret)
        } else {
            getString(R.string.secret_masked_format, "\u2022".repeat(activeDigitCount))
        }
    }

    private fun updateTurnUi() {
        binding.tvSessionScoreboard.text = sessionLeadText()
        if (isGameOver) {
            binding.tvTurnBanner.text = "Match $roundNumber Complete"
            binding.tvTurnBanner.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            binding.layoutGuessInput.visibility = View.GONE
            return
        }

        binding.layoutGuessInput.visibility = View.VISIBLE
        val canInput = isLocalTurn && !awaitingRemoteFeedback
        binding.etGuessInput.isEnabled = canInput
        binding.btnSubmitGuess.isEnabled = canInput

        if (awaitingRemoteFeedback) {
            binding.tvTurnBanner.text = "Checking guess with $opponentName…"
            binding.tvTurnBanner.setTextColor(ContextCompat.getColor(this, R.color.warning))
        } else if (isLocalTurn) {
            binding.tvTurnBanner.text = "Your Turn — Crack $opponentName's $activeDigitCount-digit code"
            binding.tvTurnBanner.setTextColor(ContextCompat.getColor(this, R.color.accent_primary))
        } else {
            binding.tvTurnBanner.text = "$opponentName's Turn…"
            binding.tvTurnBanner.setTextColor(ContextCompat.getColor(this, R.color.warning))
        }
    }

    private fun submitLocalGuess() {
        if (!isGameplayActive || isGameOver || !isLocalTurn || awaitingRemoteFeedback) return

        val guess = binding.etGuessInput.text?.toString()?.trim().orEmpty()
        when (val validation = GameLogic.validateNumber(guess, activeDigitCount)) {
            is GameLogic.ValidationResult.Invalid -> {
                binding.tvGuessError.text = validation.errorMessage
                binding.tvGuessError.visibility = View.VISIBLE
                return
            }
            is GameLogic.ValidationResult.Valid -> {
                binding.tvGuessError.visibility = View.GONE
            }
        }

        hideKeyboard(binding.etGuessInput)
        binding.etGuessInput.setText("")

        if (gameMode == GameMode.OFFLINE) {
            val evaluation = GameLogic.evaluateGuess(opponentSecret, guess, activeDigitCount)
            recordMyGuess(evaluation)
            if (evaluation.isWin) {
                finishGame(localWon = true)
            } else {
                isLocalTurn = false
                updateTurnUi()
                scheduleBotGuess()
            }
        } else {
            awaitingRemoteFeedback = true
            updateTurnUi()
            sendMultiplayerLine("GUESS|$guess")
        }
    }

    private fun scheduleBotGuess() {
        mainHandler.postDelayed({
            if (!isGameplayActive || isGameOver || isLocalTurn || gameMode != GameMode.OFFLINE) {
                return@postDelayed
            }
            val botGuess = GameLogic.generateBotGuess(opponentGuesses, activeDigitCount)
            val evaluation = GameLogic.evaluateGuess(mySecret, botGuess, activeDigitCount)
            recordOpponentGuess(evaluation)
            if (evaluation.isWin) {
                finishGame(localWon = false)
            } else {
                isLocalTurn = true
                updateTurnUi()
            }
        }, 650L)
    }

    private fun recordMyGuess(evaluation: GameLogic.GuessEvaluation) {
        myGuesses.add(0, evaluation)
        updateTabTitles()
        if (activeTabIndex != 0) {
            binding.tabLayoutHistory.getTabAt(0)?.select()
        } else {
            refreshGuessList()
        }
    }

    private fun recordOpponentGuess(evaluation: GameLogic.GuessEvaluation) {
        opponentGuesses.add(0, evaluation)
        updateTabTitles()
        if (activeTabIndex == 1) {
            refreshGuessList()
        }
    }

    private fun updateTabTitles() {
        binding.tabLayoutHistory.getTabAt(0)?.text =
            getString(R.string.tab_your_guesses, myGuesses.size)
        binding.tabLayoutHistory.getTabAt(1)?.text =
            getString(R.string.tab_opponent_guesses, opponentName, opponentGuesses.size)
    }

    private fun refreshGuessList() {
        val list = if (activeTabIndex == 0) myGuesses else opponentGuesses
        guessAdapter.submitList(list)
        binding.tvEmptyHistory.visibility = if (list.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun finishGame(localWon: Boolean) {
        if (isGameOver) return
        isGameOver = true
        awaitingRemoteFeedback = false
        mainHandler.removeCallbacksAndMessages(null)

        if (localWon) {
            localSessionWins += 1
        } else {
            opponentSessionWins += 1
        }

        playerPrefs.recordGameResult(localWon)
        refreshProfileUi()

        if (gameMode != GameMode.OFFLINE && !secretRevealSent) {
            secretRevealSent = true
            sendMultiplayerLine("REVEAL|$mySecret")
        }

        binding.layoutGuessInput.visibility = View.GONE
        binding.layoutGameOver.visibility = View.VISIBLE

        if (localWon) {
            binding.tvGameOverTitle.text = "You Won Match $roundNumber in ${myGuesses.size} guesses!"
            binding.tvGameOverTitle.setTextColor(ContextCompat.getColor(this, R.color.success))
        } else {
            binding.tvGameOverTitle.text = "$opponentName Won Match $roundNumber!"
            binding.tvGameOverTitle.setTextColor(ContextCompat.getColor(this, R.color.danger))
        }

        val isSessionFinished = roundNumber >= activeSessionMatches
        if (isSessionFinished) {
            binding.tvSessionLeadSummary.text = when {
                localSessionWins > opponentSessionWins ->
                    "Session Complete: You Win the Series $localSessionWins–$opponentSessionWins!"
                localSessionWins < opponentSessionWins ->
                    "Session Complete: $opponentName Wins the Series $opponentSessionWins–$localSessionWins"
                else ->
                    "Session Complete: Series Tied $localSessionWins–$opponentSessionWins!"
            }
            binding.btnPlayAgain.text = getString(R.string.new_session)
        } else {
            val remaining = activeSessionMatches - roundNumber
            binding.tvSessionLeadSummary.text = when {
                localSessionWins > opponentSessionWins ->
                    "Session Lead: You lead $localSessionWins–$opponentSessionWins ($remaining left)"
                localSessionWins < opponentSessionWins ->
                    "Session Lead: $opponentName leads $opponentSessionWins–$localSessionWins ($remaining left)"
                else ->
                    "Session Lead: Tied $localSessionWins–$opponentSessionWins ($remaining left)"
            }
            binding.btnPlayAgain.text = "Next Match (${roundNumber + 1}/$activeSessionMatches)"
        }

        updateRevealedSecretsLabel()
        updateTurnUi()
    }

    private fun updateRevealedSecretsLabel() {
        val oppDisplay = opponentSecret.ifEmpty { "Waiting…" }
        binding.tvRevealedSecrets.text = "Your Secret: $mySecret  •  $opponentName's Secret: $oppDisplay"
    }

    private fun handlePlayAgainTapped() {
        if (!isGameOver) return
        if (gameMode == GameMode.OFFLINE) {
            advanceSessionRound()
            return
        }
        if (localPlayAgainSent) return
        localPlayAgainSent = true
        binding.btnPlayAgain.isEnabled = false
        binding.btnPlayAgain.text = "Waiting…"
        binding.tvPlayAgainStatus.text = "Waiting for $opponentName to continue…"
        binding.tvPlayAgainStatus.visibility = View.VISIBLE
        sendMultiplayerLine("PLAY_AGAIN")

        if (remotePlayAgainReceived) {
            advanceSessionRound()
        }
    }

    private fun advanceSessionRound() {
        if (roundNumber >= activeSessionMatches) {
            roundNumber = 1
            localSessionWins = 0
            opponentSessionWins = 0
        } else {
            roundNumber += 1
        }
        prepareRoundSetup()
    }

    private fun leaveMatchToLobby() {
        mainHandler.removeCallbacksAndMessages(null)
        isGameplayActive = false
        isGameOver = false
        bluetoothManager.disconnect()
        onlineRoomManager.disconnect()
        hostWaitingDialog?.dismiss()
        hostWaitingDialog = null
        onlineDialog?.dismiss()
        onlineDialog = null
        binding.tvLobbyStatus.visibility = View.GONE
        refreshProfileUi()
        showScreen(ScreenState.LOBBY)
    }

    // --- BluetoothGameManager.Listener ---

    override fun onHostingStarted() {
        hostWaitingDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Hosting Game")
            .setMessage("Waiting for an opponent to connect via Bluetooth…")
            .setCancelable(false)
            .setNegativeButton("Cancel") { d, _ ->
                bluetoothManager.disconnect()
                binding.tvLobbyStatus.visibility = View.GONE
                d.dismiss()
            }
            .create()
        hostWaitingDialog = dialog
        dialog.show()
    }

    override fun onConnecting(deviceName: String) {
        binding.tvLobbyStatus.text = "Connecting to $deviceName…"
        binding.tvLobbyStatus.visibility = View.VISIBLE
    }

    override fun onConnected(deviceName: String, isHost: Boolean) {
        hostWaitingDialog?.dismiss()
        hostWaitingDialog = null
        joinDialog?.dismiss()
        joinDialog = null
        cleanupJoinDiscovery()

        binding.tvLobbyStatus.visibility = View.GONE
        gameMode = GameMode.BLUETOOTH
        this.isHost = isHost
        this.opponentName = deviceName.ifBlank { "Opponent" }
        this.activeDigitCount = selectedDigitCount
        this.activeSessionMatches = selectedSessionMatches
        this.roundNumber = 1
        this.localSessionWins = 0
        this.opponentSessionWins = 0

        bluetoothManager.sendLine(GameLogic.formatHello(playerPrefs.getPlayerName()))
        if (isHost) {
            bluetoothManager.sendLine(GameLogic.formatConfig(activeDigitCount, activeSessionMatches))
        }
        prepareRoundSetup()
    }

    override fun onMessageReceived(line: String) {
        if (gameMode != GameMode.BLUETOOTH) return
        handleRemoteMessage(line)
    }

    // --- OnlineRoomManager Dialogs & Listener ---

    private fun showJoinOnlineRoomDialog() {
        onlineDialog?.dismiss()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val input = EditText(this).apply {
            hint = "Enter 6-character room code"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            filters = arrayOf(InputFilter.LengthFilter(GameLogic.ROOM_CODE_LENGTH))
            gravity = Gravity.CENTER
            textSize = 22f
            letterSpacing = 0.2f
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
            setHintTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        container.addView(input)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Join Online Room")
            .setMessage("Enter the 6-character Room Code shared by your friend.")
            .setView(container)
            .setPositiveButton("Join", null)
            .setNegativeButton("Cancel") { d, _ -> d.dismiss() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val code = GameLogic.normalizeRoomCode(input.text?.toString().orEmpty())
                if (!GameLogic.isValidRoomCode(code)) {
                    input.error = "Enter a 6-character room code."
                    return@setOnClickListener
                }
                hideKeyboard(input)
                dialog.dismiss()
                onlineDialog = null
                bluetoothManager.disconnect()
                onlineRoomManager.joinRoom(code, playerPrefs.getPlayerName())
            }
        }
        dialog.setOnDismissListener {
            if (onlineDialog == dialog) onlineDialog = null
        }
        onlineDialog = dialog
        dialog.show()
    }

    override fun onRoomCreating() {
        onlineDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Creating Room")
            .setMessage("Connecting to online game server…")
            .setCancelable(false)
            .setNegativeButton("Cancel") { d, _ ->
                onlineRoomManager.disconnect()
                d.dismiss()
            }
            .create()
        onlineDialog = dialog
        dialog.show()
    }

    override fun onRoomCreated(roomCode: String) {
        onlineDialog?.dismiss()
        val density = resources.displayMetrics.density
        val pad = (16 * density).toInt()
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(pad, pad / 2, pad, pad / 2)
        }
        val codeTv = TextView(this).apply {
            text = roomCode
            textSize = 30f
            letterSpacing = 0.25f
            gravity = Gravity.CENTER
            setTextIsSelectable(true)
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.accent_primary))
            setPadding(0, (8 * density).toInt(), 0, (12 * density).toInt())
        }
        val infoTv = TextView(this).apply {
            text = "Share this code with your friend and keep this screen open until they join."
            textSize = 13f
            gravity = Gravity.CENTER
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_secondary))
        }
        container.addView(codeTv)
        container.addView(infoTv)

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Room Ready")
            .setView(container)
            .setCancelable(false)
            .setPositiveButton("Share", null)
            .setNeutralButton("Copy Code", null)
            .setNegativeButton("Cancel") { d, _ ->
                onlineRoomManager.disconnect()
                d.dismiss()
            }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener {
                val cm = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                cm?.setPrimaryClip(ClipData.newPlainText("Digit Duel Room Code", roomCode))
                Toast.makeText(this, "Copied room code: $roomCode", Toast.LENGTH_SHORT).show()
            }
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "Join my Digit Duel game! Open the app -> Join Room (Internet) -> Code: $roomCode"
                    )
                }
                startActivity(Intent.createChooser(shareIntent, "Share Room Code"))
            }
        }
        onlineDialog = dialog
        dialog.show()
    }

    override fun onRoomJoining(roomCode: String) {
        onlineDialog?.dismiss()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle("Joining Room $roomCode")
            .setMessage("Connecting to opponent…")
            .setCancelable(false)
            .setNegativeButton("Cancel") { d, _ ->
                onlineRoomManager.disconnect()
                d.dismiss()
            }
            .create()
        onlineDialog = dialog
        dialog.show()
    }

    override fun onRoomConnected(opponentName: String, isHost: Boolean) {
        onlineDialog?.dismiss()
        onlineDialog = null
        binding.tvLobbyStatus.visibility = View.GONE

        gameMode = GameMode.ONLINE
        this.isHost = isHost
        this.opponentName = opponentName.ifBlank { "Opponent" }
        this.activeDigitCount = selectedDigitCount
        this.activeSessionMatches = selectedSessionMatches
        this.roundNumber = 1
        this.localSessionWins = 0
        this.opponentSessionWins = 0

        onlineRoomManager.sendLine(GameLogic.formatHello(playerPrefs.getPlayerName()))
        if (isHost) {
            onlineRoomManager.sendLine(GameLogic.formatConfig(activeDigitCount, activeSessionMatches))
        }
        prepareRoundSetup()
    }

    override fun onRoomMessageReceived(line: String) {
        if (gameMode != GameMode.ONLINE) return
        handleRemoteMessage(line)
    }

    override fun onRoomDisconnected(reason: String) {
        onlineDialog?.dismiss()
        onlineDialog = null
        binding.tvLobbyStatus.visibility = View.GONE
        if (currentScreen != ScreenState.LOBBY && gameMode == GameMode.ONLINE) {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            leaveMatchToLobby()
        }
    }

    override fun onRoomError(message: String) {
        onlineDialog?.dismiss()
        onlineDialog = null
        binding.tvLobbyStatus.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun handleRemoteMessage(line: String) {
        val message = GameLogic.parseProtocolLine(line, expectedDigitCount = activeDigitCount) ?: return
        when (message) {
            is GameLogic.ProtocolMessage.Hello -> {
                opponentName = message.playerName
                if (currentScreen == ScreenState.SETUP) {
                    binding.tvSetupConnection.text = "Duel vs $opponentName"
                    binding.tvSetupSessionScore.text = sessionLeadText()
                    if (localReady) {
                        binding.tvWaitingOpponent.text =
                            getString(R.string.waiting_opponent, opponentName)
                    }
                } else if (currentScreen == ScreenState.GAME) {
                    updateTabTitles()
                    updateTurnUi()
                    if (isGameOver) {
                        updateRevealedSecretsLabel()
                    }
                }
            }
            is GameLogic.ProtocolMessage.Config -> {
                if (!isHost) {
                    activeDigitCount = message.digitCount
                    activeSessionMatches = message.sessionMatches
                    applyDigitCountToInputs()
                    if (currentScreen == ScreenState.SETUP) {
                        binding.tvSetupSessionScore.text = sessionLeadText()
                    } else if (currentScreen == ScreenState.GAME) {
                        updateTurnUi()
                        updateSecretPeekUi()
                    }
                }
            }
            is GameLogic.ProtocolMessage.Ready -> {
                remoteReady = true
                if (currentScreen == ScreenState.SETUP && localReady && !isGameplayActive) {
                    startGameplay()
                }
            }
            is GameLogic.ProtocolMessage.Guess -> {
                if (!isGameplayActive || isGameOver || isLocalTurn) return
                val evaluation = GameLogic.evaluateGuess(mySecret, message.digits, activeDigitCount)
                recordOpponentGuess(evaluation)
                val codes = GameLogic.encodeFeedbackCodes(evaluation.results)
                sendMultiplayerLine("FEEDBACK|${message.digits}|$codes")
                if (evaluation.isWin) {
                    opponentSecret = message.digits
                    finishGame(localWon = false)
                } else {
                    isLocalTurn = true
                    updateTurnUi()
                }
            }
            is GameLogic.ProtocolMessage.Feedback -> {
                if (!isGameplayActive || isGameOver || !awaitingRemoteFeedback) return
                val evaluation = GameLogic.decodeFeedback(message.digits, message.codesCsv, activeDigitCount) ?: return
                awaitingRemoteFeedback = false
                recordMyGuess(evaluation)
                if (evaluation.isWin) {
                    opponentSecret = message.digits
                    finishGame(localWon = true)
                } else {
                    isLocalTurn = false
                    updateTurnUi()
                }
            }
            is GameLogic.ProtocolMessage.Reveal -> {
                opponentSecret = message.digits
                if (isGameOver) {
                    updateRevealedSecretsLabel()
                }
            }
            is GameLogic.ProtocolMessage.PlayAgain -> {
                if (!isGameOver) return
                remotePlayAgainReceived = true
                if (localPlayAgainSent) {
                    advanceSessionRound()
                } else {
                    val promptLabel = if (roundNumber >= activeSessionMatches) "New Session" else "Next Match"
                    binding.tvPlayAgainStatus.text = "$opponentName is ready! Tap $promptLabel."
                    binding.tvPlayAgainStatus.visibility = View.VISIBLE
                }
            }
        }
    }

    override fun onDisconnected(reason: String) {
        hostWaitingDialog?.dismiss()
        hostWaitingDialog = null
        binding.tvLobbyStatus.visibility = View.GONE
        if (currentScreen != ScreenState.LOBBY && gameMode == GameMode.BLUETOOTH) {
            Toast.makeText(this, reason, Toast.LENGTH_LONG).show()
            leaveMatchToLobby()
        }
    }

    override fun onError(message: String) {
        hostWaitingDialog?.dismiss()
        hostWaitingDialog = null
        binding.tvLobbyStatus.visibility = View.GONE
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun hideKeyboard(view: View) {
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        cleanupJoinDiscovery()
        hostWaitingDialog?.dismiss()
        joinDialog?.dismiss()
        onlineDialog?.dismiss()
        bluetoothManager.shutdown()
        onlineRoomManager.shutdown()
    }

    // --- RecyclerView Adapter for Guess History ---

    private class GuessAdapter : RecyclerView.Adapter<GuessAdapter.GuessViewHolder>() {

        private val items = mutableListOf<GameLogic.GuessEvaluation>()

        @SuppressLint("NotifyDataSetChanged")
        fun submitList(newItems: List<GameLogic.GuessEvaluation>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GuessViewHolder {
            val binding = ItemGuessRowBinding.inflate(
                LayoutInflater.from(parent.context),
                parent,
                false
            )
            return GuessViewHolder(binding)
        }

        override fun onBindViewHolder(holder: GuessViewHolder, position: Int) {
            // Newest-first list: top row corresponds to total item count.
            val turnNumber = items.size - position
            holder.bind(items[position], turnNumber)
        }

        override fun getItemCount(): Int = items.size

        class GuessViewHolder(
            private val binding: ItemGuessRowBinding
        ) : RecyclerView.ViewHolder(binding.root) {

            private val digitViews = listOf(
                binding.tvDigit0,
                binding.tvDigit1,
                binding.tvDigit2,
                binding.tvDigit3,
                binding.tvDigit4,
                binding.tvDigit5,
                binding.tvDigit6
            )

            fun bind(item: GameLogic.GuessEvaluation, turnNumber: Int) {
                binding.tvGuessNumber.text = "#$turnNumber"
                binding.tvGuessSummary.text = item.summary
                for (i in digitViews.indices) {
                    val tv = digitViews[i]
                    if (i < item.results.size) {
                        val feedback = item.results[i]
                        tv.visibility = View.VISIBLE
                        tv.text = feedback.digit.toString()
                        val bgRes = when (feedback.status) {
                            GameLogic.DigitStatus.CORRECT_PLACE -> R.drawable.bg_digit_correct
                            GameLogic.DigitStatus.WRONG_PLACE -> R.drawable.bg_digit_wrong_place
                            GameLogic.DigitStatus.DOES_NOT_EXIST -> R.drawable.bg_digit_absent
                        }
                        tv.setBackgroundResource(bgRes)
                    } else {
                        tv.visibility = View.GONE
                    }
                }
            }
        }
    }
}

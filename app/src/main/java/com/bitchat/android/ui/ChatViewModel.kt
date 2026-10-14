package com.bitchat.android.ui

import android.app.Application
import android.util.Log
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bitchat.android.favorites.FavoritesPersistenceService
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import com.bitchat.android.mesh.BluetoothMeshDelegate
import com.bitchat.android.mesh.BluetoothMeshService
import com.bitchat.android.mesh.MeshService
import com.bitchat.android.service.MeshServiceHolder
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.BitchatMessageType
import com.bitchat.android.nostr.NostrIdentityBridge
import com.bitchat.android.protocol.BitchatPacket


import kotlinx.coroutines.launch
import com.bitchat.android.util.NotificationIntervalManager
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.Date
import kotlin.random.Random
import com.bitchat.android.services.VerificationService
import com.bitchat.android.identity.SecureIdentityStateManager
import com.bitchat.android.noise.NoiseSession
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.util.dataFromHexString
import com.bitchat.android.util.hexEncodedString
import java.security.MessageDigest
import android.content.Context
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.bitchat.android.monero.messaging.MoneroMessageHandler
import com.bitchat.android.monero.wallet.WalletSuite
import com.bitchat.android.monero.bluetooth.MoneroChatTransferManager
import com.m2049r.xmrwallet.model.TransactionInfo
import java.io.File
import java.io.FileOutputStream
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Refactored ChatViewModel - Main coordinator for bitchat functionality
 * Delegates specific responsibilities to specialized managers while maintaining 100% iOS compatibility
 */
class ChatViewModel(
    application: Application,
    initialMeshService: BluetoothMeshService,
    initialUnifiedMeshService: MeshService
) : AndroidViewModel(application), BluetoothMeshDelegate {

    // Made var to support mesh service replacement after panic clear
    var meshService: BluetoothMeshService = initialMeshService
        private set
    private var unifiedMeshService: MeshService = initialUnifiedMeshService
    private val mesh: MeshService
        get() = unifiedMeshService
    private val debugManager by lazy { try { com.bitchat.android.ui.debug.DebugSettingsManager.getInstance() } catch (e: Exception) { null } }

    companion object {
        private const val TAG = "ChatViewModel"
    }

    fun sendVoiceNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendVoiceNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendFileNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendFileNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun sendImageNote(toPeerIDOrNull: String?, channelOrNull: String?, filePath: String) {
        mediaSendingManager.sendImageNote(toPeerIDOrNull, channelOrNull, filePath)
    }

    fun getCurrentNpub(): String? {
        return try {
            NostrIdentityBridge
                .getCurrentNostrIdentity(getApplication())
                ?.npub
        } catch (_: Exception) {
            null
        }
    }

    fun buildMyQRString(nickname: String, npub: String?): String {
        return VerificationService.buildMyQRString(nickname, npub) ?: ""
    }

    // MARK: - State management
    private val state = ChatState(
        scope = viewModelScope,
    )

    // Transfer progress tracking
    private val transferMessageMap = mutableMapOf<String, String>()
    private val messageTransferMap = mutableMapOf<String, String>()

    // Specialized managers
    private val dataManager = DataManager(application.applicationContext)
    private val identityManager by lazy { SecureIdentityStateManager(getApplication()) }
    private val messageManager = MessageManager(state)
    private val channelManager = ChannelManager(state, messageManager, dataManager, viewModelScope)

    // Create Noise session delegate for clean dependency injection
    private val noiseSessionDelegate = object : NoiseSessionDelegate {
        override fun hasEstablishedSession(peerID: String): Boolean = hasEstablishedSessionOnAnyLocalTransport(peerID)
        override fun initiateHandshake(peerID: String) = initiateNoiseHandshakeOnBestLocalTransport(peerID)
        override fun getMyPeerID(): String = mesh.myPeerID
    }

    val privateChatManager = PrivateChatManager(state, messageManager, dataManager, noiseSessionDelegate)
    private val commandProcessor = CommandProcessor(state, messageManager, channelManager, privateChatManager)
    private val notificationManager = NotificationManager(
      application.applicationContext,
      NotificationManagerCompat.from(application.applicationContext),
      NotificationIntervalManager()
    )

    private val verificationHandler = VerificationHandler(
        context = application.applicationContext,
        scope = viewModelScope,
        getMeshService = { mesh },
        identityManager = identityManager,
        state = state,
        notificationManager = notificationManager,
        messageManager = messageManager
    )
    val verifiedFingerprints = verificationHandler.verifiedFingerprints

    // ---- Monero state ----
    var moneroChatTransferManager: MoneroChatTransferManager? by mutableStateOf(null)
        private set

    private val _peerMoneroAddresses = mutableStateOf(emptyMap<String, String>())
    val peerMoneroAddresses: State<Map<String, String>> = _peerMoneroAddresses

    private val _transactionHistory = MutableStateFlow<List<TransactionInfo>>(emptyList())
    val transactionHistory: StateFlow<List<TransactionInfo>> = _transactionHistory.asStateFlow()

    private val _isTransactionHistoryLoading = MutableStateFlow(false)
    val isTransactionHistoryLoading: StateFlow<Boolean> = _isTransactionHistoryLoading.asStateFlow()

    private val _transactionHistoryError = MutableStateFlow<String?>(null)
    val transactionHistoryError: StateFlow<String?> = _transactionHistoryError.asStateFlow()

    var walletSuite by mutableStateOf<WalletSuite?>(null)
        private set

    lateinit var moneroMessageHandler: MoneroMessageHandler
        private set

    var isMoneroModeActive by mutableStateOf(false)
    var currentBalance by mutableStateOf("Loading...") // Shows while wallet initializes
    var totalBalance by mutableStateOf("Loading...")
    var walletSeed by mutableStateOf("")
    var walletHeight by mutableStateOf(0L)
    var daemonHeight by mutableStateOf(0L)
    var isSyncing by mutableStateOf(false)
        private set
    var syncProgress by mutableStateOf(0)
        private set
    var isRescanInProgress by mutableStateOf(false)
        private set
    var isRescanQueued by mutableStateOf(false)
        private set
    var walletStatusMessage by mutableStateOf("Initializing wallet...")
        private set
    var isWalletReady by mutableStateOf(false)
        private set

    private val _balanceAtomic = AtomicLong(0L)
    private val _unlockedBalanceAtomic = AtomicLong(0L)

    fun updateCachedBalance(balance: Long) {
        _balanceAtomic.set(balance)
    }

    fun updateCachedUnlockedBalance(unlockedBalance: Long) {
        _unlockedBalanceAtomic.set(unlockedBalance)
    }

    fun getCachedBalance(): Long = _balanceAtomic.get()

    fun getCachedUnlockedBalance(): Long = _unlockedBalanceAtomic.get()

    private val transactionMessages = mutableMapOf<String, BitchatMessage>()

    private val _myWalletAddress = MutableStateFlow<String?>(null)
    val myWalletAddress: StateFlow<String?> = _myWalletAddress.asStateFlow()

    var showDaemonConfigDialog by mutableStateOf(false)
        private set

    var daemonConfigLoading by mutableStateOf(false)
        private set

    private val _isWalletBusy = MutableStateFlow(false)
    val isWalletBusy: StateFlow<Boolean> = _isWalletBusy.asStateFlow()

    fun updateWalletBusyState(isBusy: Boolean) {
        _isWalletBusy.value = isBusy
    }

    fun showDaemonConfigDialog() {
        showDaemonConfigDialog = true
    }

    fun hideDaemonConfigDialog() {
        showDaemonConfigDialog = false
    }

    fun updateMyWalletAddress(address: String) {
        _myWalletAddress.value = address
    }

    fun saveDaemonConfigAndReconnect(config: DaemonConfig) {
        daemonConfigLoading = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val success = saveDaemonConfig(getApplication(), config)
                if (success) {
                    withContext(Dispatchers.Main) {
                        walletSuite?.reloadConfiguration()
                        showDaemonConfigDialog = false
                        daemonConfigLoading = false
                        updateWalletReadyState(false)
                        updateWalletStatusMessage("Connecting with new daemon settings...")
                        initializeWalletSuite(getApplication(), object : WalletSuite.WalletStatusListener {
                            override fun onWalletInitialized(success: Boolean, message: String, address: String?) {
                                updateWalletReadyState(success)
                                if (success) {
                                    updateWalletStatusMessage("Connected to new daemon successfully")
                                    addSystemMessage("✅ Daemon configuration updated and connected")
                                    if (!address.isNullOrEmpty()) {
                                        updateMyWalletAddress(address)
                                    } else {
                                        walletSuite?.getAddress(object : WalletSuite.AddressCallback {
                                            override fun onSuccess(addr: String) { updateMyWalletAddress(addr) }
                                            override fun onError(error: String) { Log.e(TAG, "Failed to get address after daemon change: $error") }
                                        })
                                    }
                                } else {
                                    updateWalletStatusMessage("Failed to connect: $message")
                                    addSystemMessage("❌ Failed to connect to daemon: $message")
                                }
                            }
                            override fun onBalanceUpdated(balance: Long, unlockedBalance: Long) {
                                updateCurrentBalance(
                                    WalletSuite.convertAtomicToXmr(unlockedBalance),
                                    WalletSuite.convertAtomicToXmr(balance)
                                )
                            }
                            override fun onSyncProgress(height: Long, startHeight: Long, targetHeight: Long, percentDone: Double) {
                                val syncing = percentDone < 100.0
                                val progress = percentDone.toInt()
                                walletHeight = height
                                daemonHeight = targetHeight
                                updateSyncState(syncing, progress)
                                if (syncing) updateWalletStatusMessage("Syncing with new daemon: $progress% ($height/$targetHeight)")
                                else updateWalletStatusMessage("Synchronized with new daemon")
                            }
                        })
                    }
                } else {
                    withContext(Dispatchers.Main) {
                        daemonConfigLoading = false
                        addSystemMessage("❌ Failed to save daemon configuration")
                    }
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    daemonConfigLoading = false
                    addSystemMessage("❌ Error saving daemon config: ${e.message}")
                    Log.e(TAG, "Error saving daemon config", e)
                }
            }
        }
    }
    // ---- End Monero state ----


    // Media file sending manager
    private val mediaSendingManager = MediaSendingManager(state, messageManager, channelManager) { mesh }
    
    // Delegate handler for mesh callbacks
    private val meshDelegateHandler = MeshDelegateHandler(
        state = state,
        messageManager = messageManager,
        channelManager = channelManager,
        privateChatManager = privateChatManager,
        notificationManager = notificationManager,
        coroutineScope = viewModelScope,
        onHapticFeedback = { ChatViewModelUtils.triggerHapticFeedback(application.applicationContext) },
        getMyPeerID = { mesh.myPeerID },
        getMeshService = { mesh }
    )
    
    // New Geohash architecture ViewModel (replaces God object service usage in UI path)
    val geohashViewModel = GeohashViewModel(
        application = application,
        state = state,
        messageManager = messageManager,
        privateChatManager = privateChatManager,
        meshDelegateHandler = meshDelegateHandler,
        onMoneroAddressReceived = { peerID, address ->
            updatePeerMoneroAddress(peerID, address)
        },
        dataManager = dataManager,
        notificationManager = notificationManager
    )





    val messages: StateFlow<List<BitchatMessage>> = state.messages
    val connectedPeers: StateFlow<List<String>> = state.connectedPeers
    val nickname: StateFlow<String> = state.nickname
    val isConnected: StateFlow<Boolean> = state.isConnected
    val privateChats: StateFlow<Map<String, List<BitchatMessage>>> = state.privateChats
    val selectedPrivateChatPeer: StateFlow<String?> = state.selectedPrivateChatPeer
    val unreadPrivateMessages: StateFlow<Set<String>> = state.unreadPrivateMessages
    val joinedChannels: StateFlow<Set<String>> = state.joinedChannels
    val currentChannel: StateFlow<String?> = state.currentChannel
    val channelMessages: StateFlow<Map<String, List<BitchatMessage>>> = state.channelMessages
    val unreadChannelMessages: StateFlow<Map<String, Int>> = state.unreadChannelMessages
    val passwordProtectedChannels: StateFlow<Set<String>> = state.passwordProtectedChannels
    val showPasswordPrompt: StateFlow<Boolean> = state.showPasswordPrompt
    val passwordPromptChannel: StateFlow<String?> = state.passwordPromptChannel
    val hasUnreadChannels = state.hasUnreadChannels
    val hasUnreadPrivateMessages = state.hasUnreadPrivateMessages
    val showCommandSuggestions: StateFlow<Boolean> = state.showCommandSuggestions
    val commandSuggestions: StateFlow<List<CommandSuggestion>> = state.commandSuggestions
    val showMentionSuggestions: StateFlow<Boolean> = state.showMentionSuggestions
    val mentionSuggestions: StateFlow<List<String>> = state.mentionSuggestions
    val favoritePeers: StateFlow<Set<String>> = state.favoritePeers
    val peerSessionStates: StateFlow<Map<String, String>> = state.peerSessionStates
    val peerFingerprints: StateFlow<Map<String, String>> = state.peerFingerprints
    val peerNicknames: StateFlow<Map<String, String>> = state.peerNicknames
    val peerRSSI: StateFlow<Map<String, Int>> = state.peerRSSI
    val peerDirect: StateFlow<Map<String, Boolean>> = state.peerDirect
    val showAppInfo: StateFlow<Boolean> = state.showAppInfo
    val showMeshPeerList: StateFlow<Boolean> = state.showMeshPeerList
    val privateChatSheetPeer: StateFlow<String?> = state.privateChatSheetPeer
    val showVerificationSheet: StateFlow<Boolean> = state.showVerificationSheet
    val showSecurityVerificationSheet: StateFlow<Boolean> = state.showSecurityVerificationSheet
    val selectedLocationChannel: StateFlow<com.bitchat.android.geohash.ChannelID?> = state.selectedLocationChannel
    val isTeleported: StateFlow<Boolean> = state.isTeleported
    val geohashPeople: StateFlow<List<GeoPerson>> = state.geohashPeople
    val teleportedGeo: StateFlow<Set<String>> = state.teleportedGeo
    val geohashParticipantCounts: StateFlow<Map<String, Int>> = state.geohashParticipantCounts
    val meshServiceFacade: MeshService
        get() = mesh
    val myPeerID: String
        get() = mesh.myPeerID

    fun getMeshPeerFingerprint(peerID: String): String? = mesh.getPeerFingerprint(peerID)

    fun getMeshPeerInfo(peerID: String): com.bitchat.android.mesh.PeerInfo? = mesh.getPeerInfo(peerID)

    fun initiateMeshHandshake(peerID: String) {
        mesh.initiateNoiseHandshake(peerID)
    }

    init {
        // Note: Mesh service delegate is now set by MainActivity
        loadAndInitialize()
        moneroMessageHandler = createDefaultMoneroHandler()
        // Hydrate UI state from process-wide AppStateStore to survive Activity recreation
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.peers.collect { peers ->
                state.setConnectedPeers(peers)
                state.setIsConnected(peers.isNotEmpty())
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.publicMessages.collect { msgs ->
                // Source of truth is AppStateStore; replace to avoid duplicate keys in LazyColumn
                state.setMessages(msgs)
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.privateMessages.collect { byPeer ->
                // Replace with store snapshot
                state.setPrivateChats(byPeer)
                // Recompute unread set using SeenMessageStore for robustness across Activity recreation
                try {
                    val seen = com.bitchat.android.services.SeenMessageStore.getInstance(getApplication())
                    val myNick = state.getNicknameValue() ?: mesh.myPeerID
                    val unread = mutableSetOf<String>()
                    byPeer.forEach { (peer, list) ->
                        if (list.any { msg -> msg.sender != myNick && !seen.hasRead(msg.id) }) unread.add(peer)
                    }
                    state.setUnreadPrivateMessages(unread)
                } catch (_: Exception) { }
            } } catch (_: Exception) { }
        }
        viewModelScope.launch {
            try { com.bitchat.android.services.AppStateStore.channelMessages.collect { byChannel ->
                // Replace with store snapshot
                state.setChannelMessages(byChannel)
            } } catch (_: Exception) { }
        }
        // Subscribe to BLE transfer progress and reflect in message deliveryStatus
        viewModelScope.launch {
            com.bitchat.android.mesh.TransferProgressManager.events.collect { evt ->
                mediaSendingManager.handleTransferProgressEvent(evt)
            }
        }
        
        // Removed background location notes subscription. Notes now load only when sheet opens.
    }

    fun cancelMediaSend(messageId: String) {
        // Delegate to MediaSendingManager which tracks transfer IDs and cleans up UI state
        mediaSendingManager.cancelMediaSend(messageId)
    }
    
    private fun loadAndInitialize() {
        // Load nickname
        val nickname = dataManager.loadNickname()
        state.setNickname(nickname)
        
        // Load data
        val (joinedChannels, protectedChannels) = channelManager.loadChannelData()
        state.setJoinedChannels(joinedChannels)
        state.setPasswordProtectedChannels(protectedChannels)
        
        // Initialize channel messages
        joinedChannels.forEach { channel ->
            if (!state.getChannelMessagesValue().containsKey(channel)) {
                val updatedChannelMessages = state.getChannelMessagesValue().toMutableMap()
                updatedChannelMessages[channel] = emptyList()
                state.setChannelMessages(updatedChannelMessages)
            }
        }
        
        // Load other data
        dataManager.loadFavorites()
        state.setFavoritePeers(dataManager.favoritePeers.toSet())
        dataManager.loadBlockedUsers()
        dataManager.loadGeohashBlockedUsers()

        // Log all favorites at startup
        dataManager.logAllFavorites()
        logCurrentFavoriteState()
        
        // Initialize session state monitoring
        initializeSessionStateMonitoring()

        // Bridge DebugSettingsManager -> Chat messages when verbose logging is on
        viewModelScope.launch {
            com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().debugMessages.collect { msgs ->
                if (com.bitchat.android.ui.debug.DebugSettingsManager.getInstance().verboseLoggingEnabled.value) {
                    // Only show debug logs in the Mesh chat timeline to avoid leaking into geohash chats
                    val selectedLocation = state.selectedLocationChannel.value
                    if (selectedLocation is com.bitchat.android.geohash.ChannelID.Mesh) {
                        // Append only latest debug message as system message to avoid flooding
                        msgs.lastOrNull()?.let { dm ->
                            messageManager.addSystemMessage(dm.content)
                        }
                    }
                }
            }
        }
        
        // Initialize new geohash architecture
        geohashViewModel.initialize()

        // Initialize favorites persistence service
        com.bitchat.android.favorites.FavoritesPersistenceService.initialize(getApplication())

        // Load verified fingerprints from secure storage
        verificationHandler.loadVerifiedFingerprints()


        // Ensure NostrTransport knows our mesh peer ID for embedded packets
        try {
            val nostrTransport = com.bitchat.android.nostr.NostrTransport.getInstance(getApplication())
            nostrTransport.senderPeerID = mesh.myPeerID
        } catch (_: Exception) { }

        // Note: Mesh service is now started by MainActivity

        // BLE receives are inserted by MessageHandler path; no VoiceNoteBus for Tor in this branch.
    }
    
    override fun onCleared() {
        moneroChatTransferManager?.shutdown()
        super.onCleared()
        // Note: Mesh service lifecycle is now managed by MainActivity
    }
    
    // MARK: - Nickname Management
    
    fun setNickname(newNickname: String) {
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        mesh.sendBroadcastAnnounce()
    }
    
    /**
     * Ensure Nostr DM subscription for a geohash conversation key if known
     * Minimal-change approach: reflectively access GeohashViewModel internals to reuse pipeline
     */
    private fun ensureGeohashDMSubscriptionIfNeeded(convKey: String) {
        try {
            val repoField = GeohashViewModel::class.java.getDeclaredField("repo")
            repoField.isAccessible = true
            val repo = repoField.get(geohashViewModel) as com.bitchat.android.nostr.GeohashRepository
            val gh = repo.getConversationGeohash(convKey)
            if (!gh.isNullOrEmpty()) {
                val subMgrField = GeohashViewModel::class.java.getDeclaredField("subscriptionManager")
                subMgrField.isAccessible = true
                val subMgr = subMgrField.get(geohashViewModel) as com.bitchat.android.nostr.NostrSubscriptionManager
                val identity = com.bitchat.android.nostr.NostrIdentityBridge.deriveIdentity(gh, getApplication())
                val subId = "geo-dm-$gh"
                val currentDmSubField = GeohashViewModel::class.java.getDeclaredField("currentDmSubId")
                currentDmSubField.isAccessible = true
                val currentId = currentDmSubField.get(geohashViewModel) as String?
                if (currentId != subId) {
                    (currentId)?.let { subMgr.unsubscribe(it) }
                    currentDmSubField.set(geohashViewModel, subId)
                    subMgr.subscribeGiftWraps(
                        pubkey = identity.publicKeyHex,
                        sinceMs = System.currentTimeMillis() - 172800000L,
                        id = subId,
                        handler = { event ->
                            val dmHandlerField = GeohashViewModel::class.java.getDeclaredField("dmHandler")
                            dmHandlerField.isAccessible = true
                            val dmHandler = dmHandlerField.get(geohashViewModel) as com.bitchat.android.nostr.NostrDirectMessageHandler
                            dmHandler.onGiftWrap(event, gh, identity)
                        }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "ensureGeohashDMSubscriptionIfNeeded failed: ${e.message}")
        }
    }

    // MARK: - Channel Management (delegated)
    
    fun joinChannel(channel: String, password: String? = null): Boolean {
        return channelManager.joinChannel(channel, password, mesh.myPeerID)
    }
    
    fun switchToChannel(channel: String?) {
        channelManager.switchToChannel(channel)
    }
    
    fun leaveChannel(channel: String) {
        channelManager.leaveChannel(channel)
        mesh.sendMessage("left $channel", emptyList(), null)
    }
    
    // MARK: - Private Chat Management (delegated)
    
    fun startPrivateChat(peerID: String) {
        // For geohash conversation keys, ensure DM subscription is active
        if (peerID.startsWith("nostr_")) {
            ensureGeohashDMSubscriptionIfNeeded(peerID)
        }
        
        val success = privateChatManager.startPrivateChat(peerID, mesh)
        if (success) {
            // Notify notification manager about current private chat
            setCurrentPrivateChatPeer(peerID)
            // Clear notifications for this sender since user is now viewing the chat
            clearNotificationsForSender(peerID)

            // Persistently mark all messages in this conversation as read so Nostr fetches
            // after app restarts won't re-mark them as unread.
            try {
                val seen = com.bitchat.android.services.SeenMessageStore.getInstance(getApplication())
                val chats = state.getPrivateChatsValue()
                val messages = chats[peerID] ?: emptyList()
                messages.forEach { msg ->
                    try { seen.markRead(msg.id) } catch (_: Exception) { }
                }
            } catch (_: Exception) { }
        }
    }
    
    fun endPrivateChat() {
        privateChatManager.endPrivateChat()
        // Notify notification manager that no private chat is active
        setCurrentPrivateChatPeer(null)
        // Clear mesh mention notifications since user is now back in mesh chat
        clearMeshMentionNotifications()
        // Ensure sheet is hidden
        hidePrivateChatSheet()
    }

    // MARK: - Open Latest Unread Private Chat

    fun openLatestUnreadPrivateChat() {
        try {
            val unreadKeys = state.getUnreadPrivateMessagesValue()
            if (unreadKeys.isEmpty()) return

            val me = state.getNicknameValue() ?: mesh.myPeerID
            val chats = state.getPrivateChatsValue()

            // Pick the latest incoming message among unread conversations
            var bestKey: String? = null
            var bestTime: Long = Long.MIN_VALUE

            unreadKeys.forEach { key ->
                val list = chats[key]
                if (!list.isNullOrEmpty()) {
                    // Prefer the latest incoming message (sender != me), fallback to last message
                    val latestIncoming = list.lastOrNull { it.sender != me }
                    val candidateTime = (latestIncoming ?: list.last()).timestamp.time
                    if (candidateTime > bestTime) {
                        bestTime = candidateTime
                        bestKey = key
                    }
                }
            }

            val targetKey = bestKey ?: unreadKeys.firstOrNull() ?: return

            val openPeer: String = if (targetKey.startsWith("nostr_")) {
                // Use the exact conversation key for geohash DMs and ensure DM subscription
                ensureGeohashDMSubscriptionIfNeeded(targetKey)
                targetKey
            } else {
                // Resolve to a canonical mesh peer if needed
                val canonical = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                    selectedPeerID = targetKey,
                    connectedPeers = state.getConnectedPeersValue(),
                    meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                    meshHasPeer = { pid -> mesh.getPeerInfo(pid)?.isConnected == true },
                    nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                    findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
                )
                canonical ?: targetKey
            }

            showPrivateChatSheet(openPeer)
        } catch (e: Exception) {
            Log.w(TAG, "openLatestUnreadPrivateChat failed: ${e.message}")
        }
    }

    // END - Open Latest Unread Private Chat

    
    // MARK: - Message Sending
    
    fun sendMessage(content: String) {
        if (content.isEmpty()) return
        
        // Check for commands
        if (content.startsWith("/")) {
            val selectedLocationForCommand = state.selectedLocationChannel.value
            commandProcessor.processCommand(content, mesh, mesh.myPeerID, { messageContent, mentions, channel ->
                if (selectedLocationForCommand is com.bitchat.android.geohash.ChannelID.Location) {
                    // Route command-generated public messages via Nostr in geohash channels
                    geohashViewModel.sendGeohashMessage(
                        messageContent,
                        selectedLocationForCommand.channel,
                        mesh.myPeerID,
                        state.getNicknameValue()
                    )
                } else {
                    mesh.sendMessage(messageContent, mentions, channel)
                }
            }, this)
            return
        }
        
        val mentions = messageManager.parseMentions(content, mesh.getPeerNicknames().values.toSet(), state.getNicknameValue())
        // REMOVED: Auto-join mentioned channels feature that was incorrectly parsing hashtags from @mentions
        // This was causing messages like "test @jack#1234 test" to auto-join channel "#1234"
        
        var selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        
        if (selectedPeer != null) {
            // If the selected peer is a temporary Nostr alias or a noise-hex identity, resolve to a canonical target
            selectedPeer = com.bitchat.android.services.ConversationAliasResolver.resolveCanonicalPeerID(
                selectedPeerID = selectedPeer,
                connectedPeers = state.getConnectedPeersValue(),
                meshNoiseKeyForPeer = { pid -> mesh.getPeerInfo(pid)?.noisePublicKey },
                meshHasPeer = { pid -> mesh.getPeerInfo(pid)?.isConnected == true },
                nostrPubHexForAlias = { alias -> com.bitchat.android.nostr.GeohashAliasRegistry.get(alias) },
                findNoiseKeyForNostr = { key -> com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNoiseKey(key) }
            ).also { canonical ->
                if (canonical != state.getSelectedPrivateChatPeerValue()) {
                    privateChatManager.startPrivateChat(canonical, mesh)
                    // If we're in the private chat sheet, update its active peer too
                    if (state.getPrivateChatSheetPeerValue() != null) {
                        showPrivateChatSheet(canonical)
                    }
                }
            }
            // Send private message
            val recipientNickname = nicknameForPeer(selectedPeer)
            privateChatManager.sendPrivateMessage(
                content, 
                selectedPeer, 
                recipientNickname,
                state.getNicknameValue(),
                mesh.myPeerID
            ) { messageContent, peerID, recipientNicknameParam, messageId ->
                // Route via MessageRouter (mesh when connected+established, else Nostr)
                val router = com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh)
                router.sendPrivate(messageContent, peerID, recipientNicknameParam, messageId)
            }
        } else {
            // Check if we're in a location channel
            val selectedLocationChannel = state.selectedLocationChannel.value
            if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                // Send to geohash channel via Nostr ephemeral event
                geohashViewModel.sendGeohashMessage(content, selectedLocationChannel.channel, mesh.myPeerID, state.getNicknameValue())
            } else {
                // Send public/channel message via mesh
                val message = BitchatMessage(
                    sender = state.getNicknameValue() ?: mesh.myPeerID,
                    content = content,
                    timestamp = Date(),
                    isRelay = false,
                    senderPeerID = mesh.myPeerID,
                    mentions = if (mentions.isNotEmpty()) mentions else null,
                    channel = currentChannelValue
                )

                if (currentChannelValue != null) {
                    channelManager.addChannelMessage(currentChannelValue, message, mesh.myPeerID)

                    // Check if encrypted channel
                    if (channelManager.hasChannelKey(currentChannelValue)) {
                        channelManager.sendEncryptedChannelMessage(
                            content,
                            mentions,
                            currentChannelValue,
                            state.getNicknameValue(),
                            mesh.myPeerID,
                            onEncryptedPayload = { encryptedData ->
                                mesh.sendMessage(content, mentions, currentChannelValue)
                            },
                            onFallback = {
                                mesh.sendMessage(content, mentions, currentChannelValue)
                            }
                        )
                    } else {
                        mesh.sendMessage(content, mentions, currentChannelValue)
                    }
                } else {
                    messageManager.addMessage(message)
                    mesh.sendMessage(content, mentions, null)
                }
            }
        }
    }

    // MARK: - Utility Functions
    
    fun getPeerIDForNickname(nickname: String): String? {
        return mesh.getPeerNicknames().entries.find { it.value == nickname }?.key
    }
    
    fun toggleFavorite(peerID: String) {
        Log.d("ChatViewModel", "toggleFavorite called for peerID: $peerID")
        privateChatManager.toggleFavorite(peerID)

        // Persist relationship in FavoritesPersistenceService
        try {
            var noiseKey: ByteArray? = null
            var nickname: String = mesh.getPeerNicknames()[peerID] ?: peerID

            // Case 1: Live mesh peer with known info
            val peerInfo = mesh.getPeerInfo(peerID)
            if (peerInfo?.noisePublicKey != null) {
                noiseKey = peerInfo.noisePublicKey
                nickname = peerInfo.nickname
            } else {
                // Case 2: Offline favorite entry using 64-hex noise public key as peerID
                if (peerID.length == 64 && peerID.matches(Regex("^[0-9a-fA-F]+$"))) {
                    try {
                        noiseKey = peerID.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                        // Prefer nickname from favorites store if available
                        val rel = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getFavoriteStatus(noiseKey!!)
                        if (rel != null) nickname = rel.peerNickname
                    } catch (_: Exception) { }
                }
            }

            if (noiseKey != null) {
                // Determine current favorite state from DataManager using fingerprint
                val identityManager = com.bitchat.android.identity.SecureIdentityStateManager(getApplication())
                val fingerprint = identityManager.generateFingerprint(noiseKey!!)
                val isNowFavorite = dataManager.favoritePeers.contains(fingerprint)

                com.bitchat.android.favorites.FavoritesPersistenceService.shared.updateFavoriteStatus(
                    noisePublicKey = noiseKey!!,
                    nickname = nickname,
                    isFavorite = isNowFavorite
                )

                // Send favorite notification via mesh or Nostr with our npub if available
                try {
                    com.bitchat.android.services.MessageRouter
                        .getInstance(getApplication(), mesh)
                        .sendFavoriteNotification(peerID, isNowFavorite)
                } catch (_: Exception) { }
            }
        } catch (_: Exception) { }

        // Log current state after toggle
        logCurrentFavoriteState()
    }
    
    private fun logCurrentFavoriteState() {
        Log.i("ChatViewModel", "=== CURRENT FAVORITE STATE ===")
        Log.i("ChatViewModel", "StateFlow favorite peers: ${favoritePeers.value}")
        Log.i("ChatViewModel", "DataManager favorite peers: ${dataManager.favoritePeers}")
        Log.i("ChatViewModel", "Peer fingerprints: ${privateChatManager.getAllPeerFingerprints()}")
        Log.i("ChatViewModel", "==============================")
    }

    private fun isConnectedOnMesh(peerID: String): Boolean {
        return try {
            mesh.getPeerInfo(peerID)?.isConnected == true
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEstablishedSessionOnMesh(peerID: String): Boolean {
        return try {
            mesh.getPeerInfo(peerID)?.isConnected == true &&
                mesh.hasEstablishedSession(peerID)
        } catch (_: Exception) {
            false
        }
    }

    private fun hasEstablishedSessionOnAnyLocalTransport(peerID: String): Boolean {
        return hasEstablishedSessionOnMesh(peerID)
    }

    private fun initiateNoiseHandshakeOnBestLocalTransport(peerID: String) {
        mesh.initiateNoiseHandshake(peerID)
    }

    private fun nicknameForPeer(peerID: String): String? {
        return state.peerNicknames.value[peerID]
            ?: try { mesh.getPeerNicknames()[peerID] } catch (_: Exception) { null }
    }

    private fun sessionStateForPeer(peerID: String): NoiseSession.NoiseSessionState {
        return try { mesh.getSessionState(peerID) } catch (_: Exception) { NoiseSession.NoiseSessionState.Uninitialized }
    }
    
    /**
     * Initialize session state monitoring for reactive UI updates
     */
    private fun initializeSessionStateMonitoring() {
        viewModelScope.launch {
            while (true) {
                delay(1000) // Check session states every second
                updateReactiveStates()
            }
        }
    }
    
    // Location notes subscription management moved to LocationNotesViewModelExtensions.kt
    
    /**
     * Update reactive states for all connected peers (session states, fingerprints, nicknames, RSSI)
     */
    private fun updateReactiveStates() {
        val currentPeers = state.getConnectedPeersValue()
        
        // Update session states
        val prevStates = state.getPeerSessionStatesValue()
        val sessionStates = currentPeers.associateWith { peerID ->
            sessionStateForPeer(peerID).toString()
        }
        state.setPeerSessionStates(sessionStates)
        // Detect new established sessions and flush router outbox for them and their noiseHex aliases
        sessionStates.forEach { (peerID, newState) ->
            val old = prevStates[peerID]
            if (old != "established" && newState == "established") {
                com.bitchat.android.services.MessageRouter
                    .getInstance(getApplication(), mesh)
                    .onSessionEstablished(peerID)
            }
        }
        // Update fingerprint mappings from centralized manager
        val fingerprints = privateChatManager.getAllPeerFingerprints()
        state.setPeerFingerprints(fingerprints)
        fingerprints.forEach { (peerID, fingerprint) ->
            identityManager.cachePeerFingerprint(peerID, fingerprint)
            val info = try { mesh.getPeerInfo(peerID) } catch (_: Exception) { null }
            val noiseKeyHex = info?.noisePublicKey?.hexEncodedString()
            if (noiseKeyHex != null) {
                identityManager.cachePeerNoiseKey(peerID, noiseKeyHex)
                identityManager.cacheNoiseFingerprint(noiseKeyHex, fingerprint)
            }
            info?.nickname?.takeIf { it.isNotBlank() }?.let { nickname ->
                identityManager.cacheFingerprintNickname(fingerprint, nickname)
            }
        }

        state.setPeerNicknames(mesh.getPeerNicknames())

        state.setPeerRSSI(mesh.getPeerRSSI())

        // Update directness per peer (driven by PeerManager state)
        try {
            val directMap = state.getConnectedPeersValue().associateWith { pid ->
                mesh.getPeerInfo(pid)?.isDirectConnection == true
            }
            state.setPeerDirect(directMap)
        } catch (_: Exception) { }

        // Flush any pending QR verification once a Noise session is established
        currentPeers.forEach { peerID ->
            if (sessionStateForPeer(peerID) is NoiseSession.NoiseSessionState.Established) {
                verificationHandler.sendPendingVerificationIfNeeded(peerID)
            }
        }
    }

    // MARK: - QR Verification
    
    fun isPeerVerified(peerID: String, verifiedFingerprints: Set<String>): Boolean {
        if (peerID.startsWith("nostr_") || peerID.startsWith("nostr:")) return false
        val fingerprint = verificationHandler.getPeerFingerprintForDisplay(peerID)
        return fingerprint != null && verifiedFingerprints.contains(fingerprint)
    }

    fun isNoisePublicKeyVerified(noisePublicKey: ByteArray, verifiedFingerprints: Set<String>): Boolean {
        val fingerprint = verificationHandler.fingerprintFromNoiseBytes(noisePublicKey)
        return verifiedFingerprints.contains(fingerprint)
    }

    fun unverifyFingerprint(peerID: String) {
        verificationHandler.unverifyFingerprint(peerID)
    }

    fun beginQRVerification(qr: VerificationService.VerificationQR): Boolean {
        return verificationHandler.beginQRVerification(qr)
    }

    // MARK: - Debug and Troubleshooting
    
    fun getDebugStatus(): String {
        return mesh.getDebugStatus()
    }
    
    fun setCurrentPrivateChatPeer(peerID: String?) {
        notificationManager.setCurrentPrivateChatPeer(peerID)
    }
    
    fun setCurrentGeohash(geohash: String?) {
        notificationManager.setCurrentGeohash(geohash)
    }

    fun clearNotificationsForSender(peerID: String) {
        notificationManager.clearNotificationsForSender(peerID)
    }
    
    fun clearNotificationsForGeohash(geohash: String) {
        notificationManager.clearNotificationsForGeohash(geohash)
    }

    fun clearMeshMentionNotifications() {
        notificationManager.clearMeshMentionNotifications()
    }

    private var reopenSidebarAfterVerification = false

    fun showVerificationSheet(fromSidebar: Boolean = false) {
        if (fromSidebar) {
            reopenSidebarAfterVerification = true
        }
        state.setShowVerificationSheet(true)
    }

    fun hideVerificationSheet() {
        state.setShowVerificationSheet(false)
        if (reopenSidebarAfterVerification) {
            reopenSidebarAfterVerification = false
            state.setShowMeshPeerList(true)
        }
    }

    fun showSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(true)
    }

    fun hideSecurityVerificationSheet() {
        state.setShowSecurityVerificationSheet(false)
    }

    fun showMeshPeerList() {
        state.setShowMeshPeerList(true)
    }

    fun hideMeshPeerList() {
        state.setShowMeshPeerList(false)
    }

    fun showPrivateChatSheet(peerID: String) {
        state.setPrivateChatSheetPeer(peerID)
    }

    fun hidePrivateChatSheet() {
        state.setPrivateChatSheetPeer(null)
    }

    fun getPeerFingerprintForDisplay(peerID: String): String? {
        return verificationHandler.getPeerFingerprintForDisplay(peerID)
    }

    fun getMyFingerprint(): String {
        return verificationHandler.getMyFingerprint()
    }

    fun resolvePeerDisplayNameForFingerprint(peerID: String): String {
        return verificationHandler.resolvePeerDisplayNameForFingerprint(peerID)
    }

    fun verifyFingerprintValue(fingerprint: String) {
        verificationHandler.verifyFingerprintValue(fingerprint)
    }

    fun unverifyFingerprintValue(fingerprint: String) {
        verificationHandler.unverifyFingerprintValue(fingerprint)
    }

    // MARK: - Command Autocomplete (delegated)
    
    fun updateCommandSuggestions(input: String) {
        commandProcessor.updateCommandSuggestions(input)
    }
    
    fun selectCommandSuggestion(suggestion: CommandSuggestion): String {
        return commandProcessor.selectCommandSuggestion(suggestion)
    }
    
    // MARK: - Mention Autocomplete
    
    fun updateMentionSuggestions(input: String) {
        commandProcessor.updateMentionSuggestions(input, mesh, this)
    }
    
    fun selectMentionSuggestion(nickname: String, currentText: String): String {
        return commandProcessor.selectMentionSuggestion(nickname, currentText)
    }
    
    // MARK: - BluetoothMeshDelegate Implementation (delegated)
    
    override fun didReceiveMessage(message: BitchatMessage) {
        Log.d(TAG, "=== RECEIVED MESSAGE ===")
        Log.d(TAG, "Sender: ${message.sender}")
        Log.d(TAG, "SenderPeerID: ${message.senderPeerID}")
        Log.d(TAG, "Content preview: ${message.content.take(50)}")

        if (message.content.startsWith("[MONERO_ADDRESS]")) {
            val address = message.content.removePrefix("[MONERO_ADDRESS]")
            val senderIdentifier = message.senderPeerID ?: message.sender
            Log.i(TAG, "MONERO ADDRESS DETECTED! Address: $address From: $senderIdentifier")
            updatePeerMoneroAddress(senderIdentifier, address)
            addSystemMessage("${message.sender} shared Monero address")
        } else if (!moneroMessageHandler.processMoneroMessage(message)) {
            meshDelegateHandler.didReceiveMessage(message)
        }
        Log.d(TAG, "=======================")
    }
    
    override fun didUpdatePeerList(peers: List<String>) {
        meshDelegateHandler.didUpdatePeerList(peers)
    }

    override fun didReceiveChannelLeave(channel: String, fromPeer: String) {
        meshDelegateHandler.didReceiveChannelLeave(channel, fromPeer)
    }
    
    override fun didReceiveDeliveryAck(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveDeliveryAck(messageID, recipientPeerID)
    }
    
    override fun didReceiveReadReceipt(messageID: String, recipientPeerID: String) {
        meshDelegateHandler.didReceiveReadReceipt(messageID, recipientPeerID)
    }

    override fun didReceiveVerifyChallenge(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyChallenge(peerID, payload)
    }

    override fun didReceiveVerifyResponse(peerID: String, payload: ByteArray, timestampMs: Long) {
        verificationHandler.didReceiveVerifyResponse(peerID, payload)
    }
    
    override fun decryptChannelMessage(encryptedContent: ByteArray, channel: String): String? {
        return meshDelegateHandler.decryptChannelMessage(encryptedContent, channel)
    }
    
    override fun getNickname(): String? {
        return meshDelegateHandler.getNickname()
    }
    
    override fun isFavorite(peerID: String): Boolean {
        return meshDelegateHandler.isFavorite(peerID)
    }
    
    // registerPeerPublicKey REMOVED - fingerprints now handled centrally in PeerManager
    
    // MARK: - Emergency Clear
    
    fun panicClearAllData() {
        Log.w(TAG, "🚨 PANIC MODE ACTIVATED - Clearing all sensitive data")
        
        // Clear all UI managers
        messageManager.clearAllMessages()
        channelManager.clearAllChannels()
        privateChatManager.clearAllPrivateChats()
        dataManager.clearAllData()
        
        // Clear seen message store
        try {
            com.bitchat.android.services.SeenMessageStore.getInstance(getApplication()).clear()
        } catch (_: Exception) { }
        
        // Clear all mesh service data
        clearAllMeshServiceData()
        
        // Clear all cryptographic data
        clearAllCryptographicData()
        
        // Clear all notifications
        notificationManager.clearAllNotifications()

        // Clear all media files
        com.bitchat.android.features.file.FileUtils.clearAllMedia(getApplication())
        
        // Clear Nostr/geohash state, keys, connections, bookmarks, and reinitialize from scratch
        try {
            // Clear geohash bookmarks too (panic should remove everything)
            try {
                val store = com.bitchat.android.geohash.GeohashBookmarksStore.getInstance(getApplication())
                store.clearAll()
            } catch (_: Exception) { }

            try {
                val locationManager = com.bitchat.android.geohash.LocationChannelManager.getInstance(getApplication())
                locationManager.clearPersistedChannel()
            } catch (_: Exception) { }

            geohashViewModel.panicReset()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to reset Nostr/geohash: ${e.message}")
        }

        // Reset nickname
        val newNickname = "anon${Random.nextInt(1000, 9999)}"
        state.setNickname(newNickname)
        dataManager.saveNickname(newNickname)
        
        // Recreate mesh service with fresh identity
        recreateMeshServiceAfterPanic()

        Log.w(TAG, "🚨 PANIC MODE COMPLETED - New identity: ${mesh.myPeerID}")
    }

    /**
     * Recreate the mesh service with a fresh identity after panic clear.
     * This ensures the new cryptographic keys are used for a new peer ID.
     */
    private fun recreateMeshServiceAfterPanic() {
        val oldPeerID = mesh.myPeerID

        // Clear the holder so getOrCreate() returns a fresh instance
        MeshServiceHolder.clear()

        // Create fresh mesh service with new identity (keys were regenerated in clearAllCryptographicData)
        val freshMeshService = MeshServiceHolder.getOrCreate(getApplication())
        val freshUnifiedMeshService = MeshServiceHolder.getUnifiedOrCreate(getApplication())

        // Replace our reference and set up the new service
        meshService = freshMeshService
        unifiedMeshService = freshUnifiedMeshService
        mesh.delegate = this

        // Restart mesh operations with new identity
        mesh.startServices()
        mesh.sendBroadcastAnnounce()

        Log.d(
            TAG,
            "✅ Mesh service recreated. Old peerID: $oldPeerID, New peerID: ${mesh.myPeerID}"
        )
    }
    
    /**
     * Clear all mesh service related data
     */
    private fun clearAllMeshServiceData() {
        try {
            // Request mesh service to clear all its internal data
            mesh.clearAllInternalData()
            
            Log.d(TAG, "✅ Cleared all mesh service data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing mesh service data: ${e.message}")
        }
    }
    
    /**
     * Clear all cryptographic data including persistent identity
     */
    private fun clearAllCryptographicData() {
        try {
            // Clear encryption service persistent identity (Ed25519 signing keys)
            mesh.clearAllEncryptionData()
            
            // Clear secure identity state (if used)
            try {
                val identityManager = SecureIdentityStateManager(getApplication())
                identityManager.clearIdentityData()
                // Also clear secure values used by FavoritesPersistenceService (favorites + peerID index)
                try {
                    identityManager.clearSecureValues("favorite_relationships", "favorite_peerid_index")
                } catch (_: Exception) { }
                Log.d(TAG, "✅ Cleared secure identity state and secure favorites store")
            } catch (e: Exception) {
                Log.d(TAG, "SecureIdentityStateManager not available or already cleared: ${e.message}")
            }

            // Clear FavoritesPersistenceService persistent relationships
            try {
                FavoritesPersistenceService.shared.clearAllFavorites()
                Log.d(TAG, "✅ Cleared FavoritesPersistenceService relationships")
            } catch (_: Exception) { }
            
            Log.d(TAG, "✅ Cleared all cryptographic data")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error clearing cryptographic data: ${e.message}")
        }
    }

    /**
     * Get participant count for a specific geohash (5-minute activity window)
     */
    fun geohashParticipantCount(geohash: String): Int {
        return geohashViewModel.geohashParticipantCount(geohash)
    }

    /**
     * Begin sampling multiple geohashes for participant activity
     */
    fun beginGeohashSampling(geohashes: List<String>) {
        geohashViewModel.beginGeohashSampling(geohashes)
    }

    /**
     * End geohash sampling
     */
    fun endGeohashSampling() {
        geohashViewModel.endGeohashSampling()
    }

    /**
     * Check if a geohash person is teleported (iOS-compatible)
     */
    fun isPersonTeleported(pubkeyHex: String): Boolean {
        return geohashViewModel.isPersonTeleported(pubkeyHex)
    }

    /**
     * Start geohash DM with pubkey hex (iOS-compatible)
     */
    fun startGeohashDM(pubkeyHex: String) {
        geohashViewModel.startGeohashDM(pubkeyHex) { convKey ->
            showPrivateChatSheet(convKey)
        }
    }

    fun startGeohashDMByNickname(nickname: String) {
        geohashViewModel.startGeohashDMByNickname(nickname) { convKey ->
            showPrivateChatSheet(convKey)
        }
    }

    fun startGeohashDMByShortId(shortId: String) {
        geohashViewModel.startGeohashDMByShortId(shortId) { convKey ->
            showPrivateChatSheet(convKey)
        }
    }

    fun selectLocationChannel(channel: com.bitchat.android.geohash.ChannelID) {
        geohashViewModel.selectLocationChannel(channel)
    }

    /**
     * Block a user in geohash channels by their nickname
     */
    fun blockUserInGeohash(targetNickname: String) {
        geohashViewModel.blockUserInGeohash(targetNickname)
    }

    // MARK: - Navigation Management
    
    fun showAppInfo() {
        state.setShowAppInfo(true)
    }
    
    fun hideAppInfo() {
        state.setShowAppInfo(false)
    }

    /**
     * Handle Android back navigation
     * Returns true if the back press was handled, false if it should be passed to the system
     */
    fun handleBackPressed(): Boolean {
        return when {
            // Close app info dialog
            state.getShowAppInfoValue() -> {
                hideAppInfo()
                true
            }
            // Close password dialog
            state.getShowPasswordPromptValue() -> {
                state.setShowPasswordPrompt(false)
                state.setPasswordPromptChannel(null)
                true
            }
            // Exit private chat
            state.getSelectedPrivateChatPeerValue() != null || state.getPrivateChatSheetPeerValue() != null -> {
                endPrivateChat()
                true
            }
            // Exit channel view
            state.getCurrentChannelValue() != null -> {
                switchToChannel(null)
                true
            }
            // No special navigation state - let system handle (usually exits app)
            else -> false
        }
    }

    // MARK: - iOS-Compatible Color System

    /**
     * Get consistent color for a mesh peer by ID (iOS-compatible)
     */
    fun colorForMeshPeer(peerID: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        // Try to get stable Noise key, fallback to peer ID
        val seed = "noise:${peerID.lowercase()}"
        return colorForPeerSeed(seed, isDark).copy()
    }

    /**
     * Get consistent color for a Nostr pubkey (iOS-compatible)
     */
    fun colorForNostrPubkey(pubkeyHex: String, isDark: Boolean): androidx.compose.ui.graphics.Color {
        return geohashViewModel.colorForNostrPubkey(pubkeyHex, isDark)
    }

    fun addSystemMessage(message: String) {
        messageManager.addSystemMessage(message)
    }

    /**
     * Show a local status/error in the private conversation currently visible
     * to the user. This is intentionally not sent over the network.
     */
    fun addPrivateSystemMessage(peerID: String, message: String) {
        val systemMessage = BitchatMessage(
            sender = "system",
            content = message,
            timestamp = Date(),
            isRelay = false,
            senderPeerID = "system"
        )
        messageManager.addPrivateMessageNoUnread(peerID, systemMessage)
    }

    fun sendDirectMessage(peer: String, message: String): Boolean {
        val recipientNickname = meshService.getPeerNicknames()[peer]
        return privateChatManager.sendPrivateMessage(
            message,
            peer,
            recipientNickname,
            state.getNicknameValue(),
            meshService.myPeerID
        ) { messageContent, peerID, recipientNicknameParam, messageId ->
            val router = com.bitchat.android.services.MessageRouter.getInstance(getApplication(), mesh)
            router.sendPrivate(messageContent, peerID, recipientNicknameParam, messageId)
        }
    }

    // ---- Monero methods ----

    private fun createDefaultMoneroHandler(): MoneroMessageHandler {
        return MoneroMessageHandler(
            onPaymentReceived = { payment ->
                if (!payment.signedTxBlob.isNullOrBlank()) {
                    processIncomingTransactionBlobAutomatically(payment)
                } else {
                    addSystemMessage("💰 Payment received: ${payment.amount} XMR from ${payment.fromUser}")
                }
            },
            onAddressShared = { address, fromUser ->
                addSystemMessage("📍 $fromUser shared Monero address: $address")
                updatePeerMoneroAddress(fromUser, address)
            },
            onPaymentRequested = { request ->
                addSystemMessage("💳 ${request.fromUser} requested ${request.amount} XMR - ${request.reason}")
            },
            onPaymentStatusUpdated = { txId, status ->
                updateTransactionStatus(txId, status)
            },
            onTransactionIdReceived = { txIdMsg ->
                addSystemMessage("🔑 TxID received: ${txIdMsg.txId} for ${txIdMsg.amount} XMR")
                trackTransactionMessage(txIdMsg.txId, BitchatMessage(
                    sender = txIdMsg.fromUser,
                    content = "Transaction ${txIdMsg.txId} received",
                    timestamp = java.util.Date(txIdMsg.timestamp),
                    isRelay = false
                ))
                processIncomingTransactionAutomatically(txIdMsg)
            }
        )
    }

    fun shareMoneroAddressWithPeer(peer: String, address: String) {
        Log.d(TAG, "=== SHARING MONERO ADDRESS === Target: $peer Address: $address")
        val messageContent = "[MONERO_ADDRESS]$address"
        try {
            if (sendDirectMessage(peer, messageContent)) {
                Log.i(TAG, "Successfully sent Monero address to $peer")
                addPrivateSystemMessage(peer, "INFO Shared Monero address")
            } else {
                Log.w(TAG, "Monero address was not queued for $peer")
                addPrivateSystemMessage(peer, "Failed to share address")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send Monero address: ${e.message}", e)
            addPrivateSystemMessage(peer, "Failed to share address")
        }
    }

    fun initializeWalletSuite(context: Context, listener: WalletSuite.WalletStatusListener) {
        val suite = WalletSuite.getInstance(context)
        walletSuite = suite
        moneroChatTransferManager?.shutdown()
        suite.setWalletStatusListener(listener)
        suite.setTransactionListener(object : WalletSuite.TransactionListener {
            override fun onTransactionCreated(txId: String, amount: Long) {
                addSystemMessage("💰 Transaction created: ${WalletSuite.convertAtomicToXmr(amount)} XMR (tx: $txId)")
            }
            override fun onTransactionConfirmed(txId: String) {
                updateTransactionStatus(txId, "confirmed")
                addSystemMessage("✅ Transaction confirmed: $txId")
            }
            override fun onTransactionFailed(txId: String, error: String) {
                updateTransactionStatus(txId, "failed")
                addSystemMessage("❌ Transaction failed: $txId - $error")
            }
            override fun onOutputReceived(amount: Long, txHash: String, isConfirmed: Boolean) {
                val amountXmr = WalletSuite.convertAtomicToXmr(amount)
                val status = if (isConfirmed) "confirmed" else "pending"
                addSystemMessage("💰 Output received: $amountXmr XMR ($status) - tx: $txHash")
            }
        })
        moneroChatTransferManager = MoneroChatTransferManager(
            context = context,
            viewModel = this@ChatViewModel,
            walletSuite = suite,
            messageHandler = moneroMessageHandler,
            sendMessageCallback = { peerID, content -> sendDirectMessage(peerID, content) }
        )
        // Wire signed-blob fallback to the same encrypted private-message
        // transport used by normal chat. This keeps large payload routing and
        // BLE fragmentation in one production path.
        moneroChatTransferManager?.initialize()
        // Create the automatic receive pipeline before wallet initialization;
        // a TxID received during startup is then persisted instead of being
        // dropped while the native wallet is still loading.
        suite.initializeWallet()
    }

    fun updateWalletReadyState(ready: Boolean) {
        isWalletReady = ready
    }

    fun fetchSeed() {
        if (walletSeed.isEmpty() || walletSeed == "••••••••••••••••••••••••••••••••") {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val seed = walletSuite?.getSeed() ?: ""
                    withContext(Dispatchers.Main) {
                        walletSeed = if (seed.isEmpty()) "Seed not available" else seed
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        walletSeed = "Error: ${e.message}"
                    }
                }
            }
        }
    }

    fun updateCurrentBalance(balance: String, total: String = balance) {
        currentBalance = balance
        totalBalance = total
    }
    fun updateSyncState(syncing: Boolean, progress: Int) {
        if (isSyncing && !syncing && progress == 100 && !isRescanQueued) {
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                android.widget.Toast.makeText(getApplication(), "Monero synchronization completed", android.widget.Toast.LENGTH_LONG).show()
            }
        }
        isSyncing = syncing
        syncProgress = progress
    }

    fun startManualSync() {
        val initialProgress = if (daemonHeight > 0) {
            ((walletHeight.toDouble() / daemonHeight) * 100).toInt().coerceIn(0, 100)
        } else 0
        updateSyncState(true, initialProgress)
        walletSuite?.forceManualSync()
        updateWalletStatusMessage("Manual sync started...")
    }

    fun stopSync() {
        walletSuite?.stopSync()
        updateWalletStatusMessage("Sync stopped. Press Play to start.")
        isSyncing = false
    }
    fun updateWalletStatusMessage(message: String) { walletStatusMessage = message }

    fun getBirthdayHeight(): Long {
        return walletSuite?.birthdayHeight ?: 0L
    }

    fun rescanFromBirthdayBlock(customHeight: Long? = null) {
        if (isRescanInProgress) return

        val suite = walletSuite
        if (suite == null) {
            updateWalletStatusMessage("Wallet is not ready yet.")
            return
        }

        val targetHeight = customHeight ?: suite.birthdayHeight
        isRescanInProgress = true
        isRescanQueued = false
        updateSyncState(true, 0)
        updateWalletStatusMessage("Preparing wallet scan...")
        val requestResult = suite.rescanFromHeight(targetHeight, object : WalletSuite.RescanCallback {
            override fun onStarted() {
                isRescanQueued = false
                updateSyncState(true, 0)
                updateWalletStatusMessage("Scanning wallet from Birthday Block...")
            }

            override fun onComplete(newBalance: Long, newUnlockedBalance: Long) {
                isRescanInProgress = false
                isRescanQueued = false
                updateSyncState(false, 0)
                updateWalletStatusMessage("Wallet scan completed.")
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(getApplication(), "Wallet scan completed", android.widget.Toast.LENGTH_LONG).show()
                }
            }

            override fun onError(error: String) {
                isRescanInProgress = false
                isRescanQueued = false
                updateSyncState(false, 0)
                val friendlyError = when {
                    error.contains("closing", ignoreCase = true) -> "Wallet is closing. Try again in a moment."
                    error.contains("reopen", ignoreCase = true) -> "Wallet could not be reopened. Try again in a moment."
                    error.contains("interrupted", ignoreCase = true) -> "Wallet scan was interrupted. You can try again."
                    else -> "Wallet scan could not be completed. Try again in a moment."
                }
                updateWalletStatusMessage(friendlyError)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    android.widget.Toast.makeText(getApplication(), friendlyError, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        })

        when (requestResult) {
            WalletSuite.RescanRequestResult.STARTED -> {
                updateWalletStatusMessage("Scanning wallet from Birthday Block...")
            }
            WalletSuite.RescanRequestResult.QUEUED -> {
                isRescanQueued = true
                updateWalletStatusMessage("Wallet is busy syncing. Scan queued and will start automatically.")
            }
            WalletSuite.RescanRequestResult.REJECTED -> {
                isRescanInProgress = false
                isRescanQueued = false
                updateSyncState(false, 0)
                updateWalletStatusMessage("Wallet scan could not be started. Try again in a moment.")
            }
        }
    }

    fun changeWalletFromSeed(seed: String, birthdayBlock: Long, onComplete: ((Boolean, String) -> Unit)? = null) {
        // INSTANTLY reset UI state so old address, balance, and height are cleared immediately
        updateMyWalletAddress("Generating address...")
        walletSeed = ""
        walletHeight = Math.max(0L, birthdayBlock)
        daemonHeight = 0L
        currentBalance = "Loading..."
        totalBalance = "Loading..."
        updateCachedBalance(0L)
        updateCachedUnlockedBalance(0L)
        isWalletReady = false
        updateSyncState(true, 0)
        updateWalletStatusMessage("Clearing old wallet and initializing new seed...")
        walletSuite?.changeWalletFromSeed(seed, birthdayBlock, object : WalletSuite.WalletStatusListener {
            override fun onWalletInitialized(success: Boolean, message: String, address: String?) {
                isWalletReady = success
                if (success) {
                    walletSeed = seed
                    updateWalletStatusMessage("New wallet set up. Rescan started...")
                    if (!address.isNullOrEmpty()) {
                        updateMyWalletAddress(address)
                    } else {
                        walletSuite?.getAddress(object : WalletSuite.AddressCallback {
                            override fun onSuccess(addr: String) {
                                updateMyWalletAddress(addr)
                            }
                            override fun onError(error: String) {}
                        })
                    }
                } else {
                    updateSyncState(false, 0)
                    updateWalletStatusMessage("Wallet change error: $message")
                }
                onComplete?.invoke(success, message)
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    val toastMsg = if (success) "New wallet restored and rescan started" else "Wallet restore error: $message"
                    android.widget.Toast.makeText(getApplication(), toastMsg, android.widget.Toast.LENGTH_LONG).show()
                }
            }

            override fun onBalanceUpdated(balance: Long, unlocked: Long) {
                updateCachedBalance(balance)
                updateCachedUnlockedBalance(unlocked)
                updateCurrentBalance(WalletSuite.convertAtomicToXmr(unlocked), WalletSuite.convertAtomicToXmr(balance))
            }

            override fun onSyncProgress(height: Long, startHeight: Long, endHeight: Long, percentDone: Double) {
                val syncing = percentDone < 100.0 && (endHeight <= 0 || (endHeight - height) > 3)
                val displayTarget = if (endHeight > 0) Math.max(height, endHeight) else height
                walletHeight = height
                daemonHeight = displayTarget
                val progress = if (!syncing) 100 else percentDone.toInt().coerceIn(0, 99)
                updateSyncState(syncing, progress)
                if (syncing) updateWalletStatusMessage("Syncing wallet: $progress% ($height/$displayTarget)")
                else updateWalletStatusMessage("Synchronized")
            }
        })
    }

    fun addPeerMoneroAddress(peer: String, address: String) {
        _peerMoneroAddresses.value = _peerMoneroAddresses.value + (peer to address)
    }

    fun String.isHex(): Boolean = this.matches(Regex("^[0-9a-fA-F]+$"))

    fun updatePeerMoneroAddress(peerID: String, address: String) {
        Log.d(TAG, "=== UPDATE PEER MONERO ADDRESS === peerID: $peerID address: $address")
        val updatedMap = _peerMoneroAddresses.value.toMutableMap()
        val selectedPeer = state.getSelectedPrivateChatPeerValue()
        if (selectedPeer != null) {
            updatedMap[selectedPeer] = address
            if (peerID.isHex()) updatedMap[peerID] = address
            val nickname = peerNicknames.value[selectedPeer]
            if (nickname != null && nickname.isNotEmpty()) updatedMap[nickname] = address
        } else {
            updatedMap[peerID] = address
        }
        _peerMoneroAddresses.value = updatedMap
        Log.d(TAG, "Updated peerMoneroAddresses size: ${_peerMoneroAddresses.value.size}")
    }

    fun trackTransactionMessage(txId: String, message: BitchatMessage) {
        transactionMessages[txId] = message
    }

    fun addMoneroTransactionMessage(txId: String, amount: String, recipientAddress: String) {
        val content = "Sending $amount XMR... (⏳ pending)"
        val transactionMessage = BitchatMessage(
            id = "tx_$txId",
            sender = "System",
            content = content,
            timestamp = java.util.Date(),
            isRelay = false,
            senderPeerID = "system"
        )
        transactionMessages[txId] = transactionMessage
        val selectedPeer = state.getSelectedPrivateChatPeerValue()
        val currentChannelValue = state.getCurrentChannelValue()
        messageManager.addMessage(transactionMessage)
    }

    fun setTransactionFlowMode(mode: MoneroChatTransferManager.TransactionFlowMode) {
        moneroChatTransferManager?.setTransactionFlowMode(mode)
    }

    private fun processIncomingTransactionAutomatically(
        txIdMessage: MoneroMessageHandler.TransactionIdMessage
    ) {
        viewModelScope.launch {
            repeat(120) {
                val manager = moneroChatTransferManager
                if (manager != null) {
                    manager.handleIncomingTransactionId(
                        txIdMessage,
                        onSuccess = { Log.d(TAG, "Incoming Monero transaction confirmed automatically") },
                        onError = { error ->
                            Log.e(TAG, "Automatic Monero transaction confirmation failed: $error")
                            addSystemMessage("⛔ Could not start automatic transaction confirmation: $error")
                        }
                    )
                    return@launch
                }
                delay(1000L)
            }
            addSystemMessage("⏳ Automatic transaction confirmation is waiting for the wallet service to start")
        }
    }

    private fun processIncomingTransactionBlobAutomatically(
        payment: MoneroMessageHandler.MoneroPaymentMessage
    ) {
        viewModelScope.launch {
            repeat(120) {
                val manager = moneroChatTransferManager
                if (manager != null) {
                    manager.handleIncomingTransactionBlob(
                        payment,
                        onSuccess = {
                            addSystemMessage("✅ Signed transaction imported and submitted automatically")
                        },
                        onError = { error ->
                            addSystemMessage("⛔ Could not import the signed transaction: $error")
                        }
                    )
                    return@launch
                }
                delay(1000L)
            }
            addSystemMessage("⏳ Signed transaction import is waiting for the wallet service to start")
        }
    }

    fun loadTransactionHistory() {
        val suite = walletSuite
        if (suite == null) {
            _isTransactionHistoryLoading.value = false
            _transactionHistoryError.value = "Wallet is not ready"
            return
        }

        _isTransactionHistoryLoading.value = true
        _transactionHistoryError.value = null
        suite.getTransactionHistory(object : WalletSuite.TransactionHistoryCallback {
            override fun onSuccess(transactions: List<TransactionInfo>) {
                _transactionHistory.value = transactions
                _isTransactionHistoryLoading.value = false
            }

            override fun onError(error: String) {
                _isTransactionHistoryLoading.value = false
                _transactionHistoryError.value = error
            }
        })
    }

    fun updateTransactionStatus(txId: String, status: String) {
        Log.d(TAG, "Updating transaction $txId status to: $status")
        val existingMessage = transactionMessages[txId]
        if (existingMessage != null) {
            val statusText = when (status.lowercase()) {
                "confirmed" -> "✅ Transaction $txId confirmed"
                "failed" -> "❌ Transaction $txId failed"
                "pending" -> "⏳ Transaction $txId is pending"
                else -> "ℹ️ Transaction $txId status: $status"
            }
            addSystemMessage(statusText)
        } else {
            addSystemMessage("ℹ️ Transaction $txId: $status")
        }
    }

    // ---- End Monero methods ----

}

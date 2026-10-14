package com.bitchat.android.ui
// [Goose] Bridge file share events to ViewModel via dispatcher is installed in ChatScreen composition

// [Goose] Installing FileShareDispatcher handler in ChatScreen to forward file sends to ViewModel


import androidx.compose.animation.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.IconButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.ui.media.FullScreenImageViewer
import android.util.Log
import androidx.compose.material.icons.automirrored.filled.Send
import com.bitchat.android.monero.wallet.WalletSuite
import com.bitchat.android.monero.messaging.MoneroMessageHandler
import com.bitchat.android.monero.bluetooth.MoneroChatTransferManager
import com.bitchat.android.monero.utils.MoneroValidator
import java.math.BigDecimal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private val moneroAmountInputPattern = Regex(
    """^(?:\d+(?:[.,]\d{1,12})?|[.,]\d{1,12})(?:\s*[xX][mM][rR])?$"""
)
private val moneroSuffixPattern = Regex("""\b[xX][mM][rR]\b""")

/**
 * Parses the amount typed in the chat box without accepting arbitrary text or
 * scientific notation. The XMR suffix is intentionally optional for the
 * manual Monero mode, but is what makes auto-detection explicit and safe.
 */
private fun parseMoneroAmountInput(input: String): String? {
    val trimmed = input.trim()
    if (!moneroAmountInputPattern.matches(trimmed)) return null

    val normalized = trimmed
        .replace(Regex("""\s*[xX][mM][rR]\s*$"""), "")
        .trim()
        .replace(',', '.')

    return runCatching {
        if (BigDecimal(normalized).signum() > 0) normalized else null
    }.getOrNull()
}

private fun containsMoneroSuffix(input: String): Boolean =
    moneroSuffixPattern.containsMatchIn(input)

private fun moneroAmountToAtomic(amount: String): Long? = runCatching {
    BigDecimal(amount).movePointRight(12).longValueExact()
}.getOrNull()

/**
 * Main ChatScreen - REFACTORED to use component-based architecture
 * This is now a coordinator that orchestrates the following UI components:
 * - ChatHeader: App bar, navigation, peer counter
 * - MessageComponents: Message display and formatting
 * - InputComponents: Message input and command suggestions
 * - SidebarComponents: Navigation drawer with channels and people
 * - AboutSheet: App info and password prompts
 * - ChatUIUtils: Utility functions for formatting and colors
 */
@Composable
fun ChatScreen(viewModel: ChatViewModel) {
    val colorScheme = MaterialTheme.colorScheme
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val hasUnreadChannels by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val channelMessages by viewModel.channelMessages.collectAsStateWithLifecycle()
    val showCommandSuggestions by viewModel.showCommandSuggestions.collectAsStateWithLifecycle()
    val commandSuggestions by viewModel.commandSuggestions.collectAsStateWithLifecycle()
    val showMentionSuggestions by viewModel.showMentionSuggestions.collectAsStateWithLifecycle()
    val mentionSuggestions by viewModel.mentionSuggestions.collectAsStateWithLifecycle()
    val showAppInfo by viewModel.showAppInfo.collectAsStateWithLifecycle()
    val showMeshPeerListSheet by viewModel.showMeshPeerList.collectAsStateWithLifecycle()
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()
    val showVerificationSheet by viewModel.showVerificationSheet.collectAsStateWithLifecycle()
    val showSecurityVerificationSheet by viewModel.showSecurityVerificationSheet.collectAsStateWithLifecycle()

    var messageText by remember { mutableStateOf(TextFieldValue("")) }
    var showPasswordPrompt by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var showLocationChannelsSheet by remember { mutableStateOf(false) }

    // ── Monero state ──────────────────────────────────────────────────────
    val walletSuite = viewModel.walletSuite
    val moneroMessageHandler = viewModel.moneroMessageHandler
    val isMoneroModeActive = viewModel.isMoneroModeActive
    val walletStatusMessage = viewModel.walletStatusMessage
    val isWalletReady = viewModel.isWalletReady
    val peerMoneroAddresses by viewModel.peerMoneroAddresses
    val moneroChatTransferManager = viewModel.moneroChatTransferManager
    val myWalletAddress by viewModel.myWalletAddress.collectAsStateWithLifecycle()
    val showDaemonConfigDialog = viewModel.showDaemonConfigDialog
    val daemonConfigLoading = viewModel.daemonConfigLoading
    val currentBalance = viewModel.currentBalance
    val isSyncing = viewModel.isSyncing
    val syncProgress = viewModel.syncProgress
    val transactionHistory by viewModel.transactionHistory.collectAsStateWithLifecycle()
    val isTransactionHistoryLoading by viewModel.isTransactionHistoryLoading.collectAsStateWithLifecycle()
    val transactionHistoryError by viewModel.transactionHistoryError.collectAsStateWithLifecycle()
    var showMoneroConfirmDialog by remember { mutableStateOf(false) }
    var pendingMoneroAmount by remember { mutableStateOf("") }
    var pendingMoneroFeeAtomic by remember { mutableStateOf<Long?>(null) }
    var pendingMoneropeer by remember { mutableStateOf("") }
    var pendingMoneroAddress by remember { mutableStateOf("") }
    var isPreparingMoneroTransaction by remember { mutableStateOf(false) }
    var isMoneroSendInProgress by remember { mutableStateOf(false) }
    var showTransactionHistorySheet by remember { mutableStateOf(false) }
    var showRestoreWalletDialog by remember { mutableStateOf(false) }
    // ── End Monero state ──────────────────────────────────────────────────
    var showLocationNotesSheet by remember { mutableStateOf(false) }
    var showUserSheet by remember { mutableStateOf(false) }
    var selectedUserForSheet by remember { mutableStateOf("") }
    var selectedMessageForSheet by remember { mutableStateOf<BitchatMessage?>(null) }
    var showFullScreenImageViewer by remember { mutableStateOf(false) }
    var viewerImagePaths by remember { mutableStateOf(emptyList<String>()) }
    var initialViewerIndex by remember { mutableStateOf(0) }
    var forceScrollToBottom by remember { mutableStateOf(false) }
    var isScrolledUp by remember { mutableStateOf(false) }

    // Show password dialog when needed
    LaunchedEffect(showPasswordPrompt) {
        showPasswordDialog = showPasswordPrompt
    }

    val isConnected by viewModel.isConnected.collectAsStateWithLifecycle()
    val passwordPromptChannel by viewModel.passwordPromptChannel.collectAsStateWithLifecycle()

    // Get location channel info for timeline switching
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()

    // Determine what messages to show based on current context (unified timelines)
    // Legacy private chat timeline removed - private chats now exclusively use PrivateChatSheet
    val displayMessages = when {
        currentChannel != null -> channelMessages[currentChannel] ?: emptyList()
        else -> {
            val locationChannel = selectedLocationChannel
            if (locationChannel is com.bitchat.android.geohash.ChannelID.Location) {
                val geokey = "geo:${locationChannel.channel.geohash}"
                channelMessages[geokey] ?: emptyList()
            } else {
                messages // Mesh timeline
            }
        }
    }

    // Determine whether to show media buttons (only hide in geohash location chats)
    val showMediaButtons = when {
        currentChannel != null -> true
        else -> selectedLocationChannel !is com.bitchat.android.geohash.ChannelID.Location
    }

    // Use WindowInsets to handle keyboard properly

    // ── Monero transaction sender ─────────────────────────────────────────
    fun sendMoneroTransaction(amount: String, peer: String, toAddress: String, cachedBalance: Long, cachedUnlocked: Long) {
        if (isMoneroSendInProgress) return
        isMoneroSendInProgress = true
        Log.d("ChatScreen", "=== MONERO SEND INITIATED ===")
        Log.d("ChatScreen", "Amount to send: $amount")
        viewModel.addSystemMessage("⏳ Preparing transaction of $amount XMR...")
        val activeWallet = walletSuite
        if (activeWallet == null) {
            isMoneroSendInProgress = false
            viewModel.addSystemMessage("❌ Wallet is not ready")
            return
        }
        activeWallet.sendTransaction(
            toAddress,
            amount,
            cachedBalance,
            cachedUnlocked,
            object : WalletSuite.TransactionCallback {
                override fun onSuccess(txId: String, sentAmount: Long) {
                    isMoneroSendInProgress = false
                    val sentAmountXmr = WalletSuite.convertAtomicToXmr(sentAmount)
                    Log.i("ChatScreen", "=== MONERO SEND SUCCESSFUL === Amount: $sentAmountXmr XMR")
                    viewModel.addSystemMessage("✅ Sent $sentAmountXmr XMR successfully")
                    val txMessage = moneroMessageHandler.createTransactionIdMessage(
                        txId = txId,
                        amount = sentAmountXmr,
                        toAddress = toAddress
                    )
                    viewModel.sendDirectMessage(peer, txMessage)
                }
                override fun onError(error: String) {
                    isMoneroSendInProgress = false
                    Log.e("ChatScreen", "=== MONERO SEND FAILED === $error")
                    viewModel.addSystemMessage("❌ Transaction failed: $error")
                }
            }
        )
    }
    // ── End Monero transaction sender ─────────────────────────────────────

    // ── Wallet initialization ─────────────────────────────────────────────
    val context = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) {
        viewModel.initializeWalletSuite(context, object : WalletSuite.WalletStatusListener {
            override fun onWalletInitialized(success: Boolean, message: String, address: String?) {
                viewModel.updateWalletReadyState(success)
                if (success) {
                    viewModel.updateWalletStatusMessage("Wallet ready")
                    if (!address.isNullOrEmpty()) {
                        viewModel.updateMyWalletAddress(address)
                    } else {
                        viewModel.walletSuite?.getAddress(object : WalletSuite.AddressCallback {
                            override fun onSuccess(addr: String) {
                                viewModel.updateMyWalletAddress(addr)
                            }
                            override fun onError(error: String) {
                                Log.e("ChatScreen", "Failed to get address: $error")
                            }
                        })
                    }
                } else {
                    viewModel.updateWalletStatusMessage("Init failed: $message")
                }
            }
            override fun onBalanceUpdated(balance: Long, unlockedBalance: Long) {
                viewModel.updateCachedBalance(balance)
                viewModel.updateCachedUnlockedBalance(unlockedBalance)
                viewModel.updateCurrentBalance(
                    WalletSuite.convertAtomicToXmr(unlockedBalance),
                    WalletSuite.convertAtomicToXmr(balance)
                )
            }
            override fun onSyncProgress(height: Long, startHeight: Long, targetHeight: Long, percentDone: Double) {
                val syncing = percentDone < 100.0 && (targetHeight <= 0 || (targetHeight - height) > 3)
                val displayTarget = if (targetHeight > 0) Math.max(height, targetHeight) else height
                viewModel.walletHeight = height
                viewModel.daemonHeight = displayTarget
                val progress = if (!syncing) 100 else percentDone.toInt().coerceIn(0, 99)
                viewModel.updateSyncState(syncing, progress)
                if (syncing) viewModel.updateWalletStatusMessage("Syncing: $progress% ($height/$displayTarget)")
                else viewModel.updateWalletStatusMessage("Synchronized")
            }
        })
    }

    // A payment mode belongs to one private conversation. Do not carry it to
    // another peer or to the public/channel composer by accident.
    LaunchedEffect(selectedPrivatePeer) {
        viewModel.isMoneroModeActive = false
    }
    // ── End Wallet initialization ─────────────────────────────────────────
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colorScheme.background) // Extend background to fill entire screen including status bar
    ) {
        val headerHeight = 42.dp
        
        // Main content area that responds to keyboard/window insets
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.ime) // This handles keyboard insets
                .windowInsetsPadding(WindowInsets.navigationBars) // Add bottom padding when keyboard is not expanded
        ) {
            // Header spacer - creates exact space for the floating header (status bar + compact header + monero bar)
            val moneroBarHeight = 40.dp
            Spacer(
                modifier = Modifier
                    .windowInsetsPadding(WindowInsets.statusBars)
                    .height(headerHeight + moneroBarHeight)
            )

            // Messages area - takes up available space, will compress when keyboard appears
            MessagesList(
                messages = displayMessages,
                currentUserNickname = nickname,
                meshService = viewModel.meshServiceFacade,
                modifier = Modifier.weight(1f),
                forceScrollToBottom = forceScrollToBottom,
                onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                onNicknameClick = { fullSenderName ->
                    // Single click - mention user in text input
                    val currentText = messageText.text
                    
                    // Extract base nickname and hash suffix from full sender name
                    val (baseName, hashSuffix) = splitSuffix(fullSenderName)
                    
                    // Check if we're in a geohash channel to include hash suffix
                    val selectedLocationChannel = viewModel.selectedLocationChannel.value
                    val mentionText = if (selectedLocationChannel is com.bitchat.android.geohash.ChannelID.Location && hashSuffix.isNotEmpty()) {
                        // In geohash chat - include the hash suffix from the full display name
                        "@$baseName$hashSuffix"
                    } else {
                        // Regular chat - just the base nickname
                        "@$baseName"
                    }
                    
                    val newText = when {
                        currentText.isEmpty() -> "$mentionText "
                        currentText.endsWith(" ") -> "$currentText$mentionText "
                        else -> "$currentText $mentionText "
                    }
                    
                    messageText = TextFieldValue(
                        text = newText,
                        selection = TextRange(newText.length)
                    )
                },
                onMessageLongPress = { message ->
                    // Message long press - open user action sheet with message context
                    // Extract base nickname from message sender (contains all necessary info)
                    val (baseName, _) = splitSuffix(message.sender)
                    selectedUserForSheet = baseName
                    selectedMessageForSheet = message
                    showUserSheet = true
                },
                onCancelTransfer = { msg ->
                    viewModel.cancelMediaSend(msg.id)
                },
                onImageClick = { currentPath, allImagePaths, initialIndex ->
                    viewerImagePaths = allImagePaths
                    initialViewerIndex = initialIndex
                    showFullScreenImageViewer = true
                }
            )
            // Input area - stays at bottom
        // Bridge file share from lower-level input to ViewModel
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.bitchat.android.ui.events.FileShareDispatcher.setHandler { peer, channel, path ->
            viewModel.sendFileNote(peer, channel, path)
        }
    }

        val canReceiveMonero = selectedPrivatePeer != null &&
            isWalletReady &&
            peerMoneroAddresses.containsKey(selectedPrivatePeer)

    ChatInputSection(
        messageText = messageText,
        onMessageTextChange = { newText: TextFieldValue ->
            messageText = newText
            viewModel.updateCommandSuggestions(newText.text)
            viewModel.updateMentionSuggestions(newText.text)
            // An explicit XMR suffix opts into payment mode automatically once
            // this private chat has a wallet address for the recipient.
            if (canReceiveMonero && parseMoneroAmountInput(newText.text) != null && containsMoneroSuffix(newText.text)) {
                viewModel.isMoneroModeActive = true
            }
        },
        onSend = {
            val trimmedText = messageText.text.trim()
            if (trimmedText.isNotEmpty()) {
                val looksLikeMoneroPayment = containsMoneroSuffix(trimmedText)
                val shouldSendMonero = isMoneroModeActive || looksLikeMoneroPayment

                if (shouldSendMonero) {
                    val receiverMoneroAddress = peerMoneroAddresses[selectedPrivatePeer]
                    val amountText = parseMoneroAmountInput(trimmedText)
                    if (!canReceiveMonero || receiverMoneroAddress == null) {
                        viewModel.addSystemMessage(
                            "❌ Monero can only be sent in a private chat after the recipient shares an address"
                        )
                    } else if (amountText == null) {
                        viewModel.addSystemMessage("❌ Invalid XMR amount")
                    } else if (!MoneroValidator.isValidAddress(receiverMoneroAddress)) {
                        viewModel.addSystemMessage("❌ Recipient address is invalid")
                    } else {
                        val validation = MoneroValidator.validateAmount(amountText)
                        val amountAtomic = moneroAmountToAtomic(amountText)
                        if (!validation.isValid()) {
                            viewModel.addSystemMessage("❌ ${validation.message}")
                        } else if (amountAtomic == null) {
                            viewModel.addSystemMessage("❌ Invalid XMR amount")
                        } else if (isPreparingMoneroTransaction || isMoneroSendInProgress) {
                            viewModel.addSystemMessage("⏳ A Monero transaction is already being prepared")
                        } else if (amountAtomic > viewModel.getCachedUnlockedBalance()) {
                            viewModel.addSystemMessage(
                                "❌ Insufficient unlocked balance. Available: " +
                                    "${WalletSuite.convertAtomicToXmr(viewModel.getCachedUnlockedBalance())} XMR"
                            )
                        } else {
                            pendingMoneroAmount = amountText
                            pendingMoneropeer = selectedPrivatePeer.orEmpty()
                            pendingMoneroAddress = receiverMoneroAddress
                            pendingMoneroFeeAtomic = null
                            isPreparingMoneroTransaction = true
                            viewModel.addSystemMessage("⏳ Estimating Monero network fee...")

                            val activeWallet = walletSuite
                            if (activeWallet == null) {
                                isPreparingMoneroTransaction = false
                                viewModel.addSystemMessage("❌ Wallet is not ready")
                            } else {
                                activeWallet.estimateTransactionFee(
                                    receiverMoneroAddress,
                                    amountText,
                                    object : WalletSuite.FeeEstimateCallback {
                                        override fun onSuccess(feeAtomic: Long) {
                                            isPreparingMoneroTransaction = false
                                            val unlockedBalance = viewModel.getCachedUnlockedBalance()
                                            val totalAtomic = if (Long.MAX_VALUE - amountAtomic < feeAtomic) {
                                                Long.MAX_VALUE
                                            } else {
                                                amountAtomic + feeAtomic
                                            }
                                            if (totalAtomic > unlockedBalance) {
                                                viewModel.addSystemMessage(
                                                    "❌ Insufficient unlocked balance including fee. " +
                                                        "Required: ${WalletSuite.convertAtomicToXmr(totalAtomic)} XMR, " +
                                                        "available: ${WalletSuite.convertAtomicToXmr(unlockedBalance)} XMR"
                                                )
                                            } else {
                                                pendingMoneroFeeAtomic = feeAtomic
                                                showMoneroConfirmDialog = true
                                            }
                                        }

                                        override fun onError(error: String) {
                                            isPreparingMoneroTransaction = false
                                            viewModel.addSystemMessage("❌ Could not estimate network fee: $error")
                                        }
                                    }
                                )
                            }
                        }
                    }
                } else {
                    viewModel.sendMessage(trimmedText)
                    messageText = TextFieldValue("")
                    forceScrollToBottom = !forceScrollToBottom
                }
            }
        },
        onSendVoiceNote = { peer, onionOrChannel, path ->
            viewModel.sendVoiceNote(peer, onionOrChannel, path)
        },
        onSendImageNote = { peer, onionOrChannel, path ->
            viewModel.sendImageNote(peer, onionOrChannel, path)
        },
        onSendFileNote = { peer, onionOrChannel, path ->
            viewModel.sendFileNote(peer, onionOrChannel, path)
        },
        
        showCommandSuggestions = showCommandSuggestions,
        commandSuggestions = commandSuggestions,
        showMentionSuggestions = showMentionSuggestions,
        mentionSuggestions = mentionSuggestions,
        onCommandSuggestionClick = { suggestion: CommandSuggestion ->
                    val commandText = viewModel.selectCommandSuggestion(suggestion)
                    messageText = TextFieldValue(
                        text = commandText,
                        selection = TextRange(commandText.length)
                    )
                },
                onMentionSuggestionClick = { mention: String ->
                    val mentionText = viewModel.selectMentionSuggestion(mention, messageText.text)
                    messageText = TextFieldValue(
                        text = mentionText,
                        selection = TextRange(mentionText.length)
                    )
                },
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                colorScheme = colorScheme,
                        showMediaButtons = showMediaButtons,
                        isMoneroModeActive = isMoneroModeActive,
                        onMoneroAddressShare = {
                            val peer = selectedPrivatePeer
                            val address = myWalletAddress
                            if (peer != null && address != null && isWalletReady) {
                                viewModel.shareMoneroAddressWithPeer(peer, address)
                            } else {
                                viewModel.addSystemMessage("❌ Wallet address is not ready")
                            }
                        },
                        onMoneroModeToggle = {
                    if (selectedPrivatePeer != null && isWalletReady) {
                        viewModel.isMoneroModeActive = !isMoneroModeActive
                    }
                },
                canReceiveMonero = canReceiveMonero,
                isWalletReady = isWalletReady
            )
        }

        // Floating header - positioned absolutely at top, ignores keyboard
        ChatFloatingHeader(
            headerHeight = headerHeight,
            selectedPrivatePeer = null,
            currentChannel = currentChannel,
            nickname = nickname,
            viewModel = viewModel,
            colorScheme = colorScheme,
            onSidebarToggle = { viewModel.showMeshPeerList() },
            onShowAppInfo = { viewModel.showAppInfo() },
            onPanicClear = { viewModel.panicClearAllData() },
            onLocationChannelsClick = { showLocationChannelsSheet = true },
            onLocationNotesClick = { showLocationNotesSheet = true },
            isWalletReady = isWalletReady,
            currentBalance = currentBalance,
            walletStatusMessage = walletStatusMessage,
            isSyncing = isSyncing,
            syncProgress = syncProgress,
        )

        // Divider under header - positioned after status bar + header height
        HorizontalDivider(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsPadding(WindowInsets.statusBars)
                .offset(y = headerHeight)
                .zIndex(1f),
            color = colorScheme.outline.copy(alpha = 0.3f)
        )

        // Scroll-to-bottom floating button
        AnimatedVisibility(
            visible = isScrolledUp,
            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
            exit = slideOutVertically(targetOffsetY = { it / 2 }) + fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 64.dp)
                .zIndex(1.5f)
                .windowInsetsPadding(WindowInsets.navigationBars)
                .windowInsetsPadding(WindowInsets.ime)
        ) {
            Surface(
                shape = CircleShape,
                color = colorScheme.background,
                tonalElevation = 3.dp,
                shadowElevation = 6.dp,
                border = BorderStroke(2.dp, Color(0xFF00C851))
            ) {
                IconButton(onClick = { forceScrollToBottom = !forceScrollToBottom }) {
                    Icon(
                        imageVector = Icons.Filled.ArrowDownward,
                        contentDescription = stringResource(com.bitchat.android.R.string.cd_scroll_to_bottom),
                        tint = Color(0xFF00C851)
                    )
                }
            }
        }
    }

    // Full-screen image viewer - separate from other sheets to allow image browsing without navigation
    if (showFullScreenImageViewer) {
        FullScreenImageViewer(
            imagePaths = viewerImagePaths,
            initialIndex = initialViewerIndex,
            onClose = { showFullScreenImageViewer = false }
        )
    }

    // Dialogs and Sheets

    // ── Monero dialogs ────────────────────────────────────────────────────
    RestoreWalletDialog(
        isVisible = showRestoreWalletDialog,
        // The active wallet's birthday belongs to the wallet being replaced and
        // may be newer than the first transaction in the seed being restored.
        // Default to a full scan unless the user explicitly supplies a height.
        defaultBirthdayHeight = 0L,
        onDismiss = { showRestoreWalletDialog = false },
        onRestore = { seed, birthdayBlock ->
            viewModel.changeWalletFromSeed(seed, birthdayBlock)
            showRestoreWalletDialog = false
        }
    )

    if (showMoneroConfirmDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isMoneroSendInProgress) {
                    showMoneroConfirmDialog = false
                    pendingMoneroFeeAtomic = null
                }
            },
            title = { Text("Confirm Monero Transaction") },
            text = {
                Column {
                    Text("Amount: $pendingMoneroAmount XMR")
                    pendingMoneroFeeAtomic?.let { feeAtomic ->
                        Text("Estimated network fee: ${WalletSuite.convertAtomicToXmr(feeAtomic)} XMR")
                        val amountAtomic = moneroAmountToAtomic(pendingMoneroAmount)
                        if (amountAtomic != null && Long.MAX_VALUE - amountAtomic >= feeAtomic) {
                            Text(
                                "Estimated total: ${WalletSuite.convertAtomicToXmr(amountAtomic + feeAtomic)} XMR",
                                style = androidx.compose.material3.MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                    Text("Send to:")
                    Text(
                        text = if (pendingMoneroAddress.length > 20)
                            "${pendingMoneroAddress.take(10)}...${pendingMoneroAddress.takeLast(10)}"
                        else pendingMoneroAddress,
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !isMoneroSendInProgress,
                    onClick = {
                        val amountAtomic = moneroAmountToAtomic(pendingMoneroAmount)
                        val feeAtomic = pendingMoneroFeeAtomic
                        val unlockedBalance = viewModel.getCachedUnlockedBalance()
                        val totalAtomic = if (amountAtomic != null && feeAtomic != null && Long.MAX_VALUE - amountAtomic >= feeAtomic) {
                            amountAtomic + feeAtomic
                        } else {
                            Long.MAX_VALUE
                        }

                        if (amountAtomic == null || feeAtomic == null) {
                            showMoneroConfirmDialog = false
                            pendingMoneroFeeAtomic = null
                            viewModel.addSystemMessage("❌ Transaction details are no longer valid")
                        } else if (totalAtomic > unlockedBalance) {
                            showMoneroConfirmDialog = false
                            pendingMoneroFeeAtomic = null
                            viewModel.addSystemMessage(
                                "❌ Balance changed. Required: ${WalletSuite.convertAtomicToXmr(totalAtomic)} XMR, " +
                                    "available: ${WalletSuite.convertAtomicToXmr(unlockedBalance)} XMR"
                            )
                        } else {
                            showMoneroConfirmDialog = false
                            pendingMoneroFeeAtomic = null
                            messageText = TextFieldValue("")
                            sendMoneroTransaction(
                                pendingMoneroAmount,
                                pendingMoneropeer,
                                pendingMoneroAddress,
                                viewModel.getCachedBalance(),
                                unlockedBalance
                            )
                        }
                    }
                ) { Text("Send") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isMoneroSendInProgress,
                    onClick = {
                        showMoneroConfirmDialog = false
                        pendingMoneroFeeAtomic = null
                    }
                ) { Text("Cancel") }
            }
        )
    }

    DaemonConfigDialog(
        isVisible = showDaemonConfigDialog,
        isLoading = daemonConfigLoading,
        onDismiss = { viewModel.hideDaemonConfigDialog() },
        onSave = { config -> viewModel.saveDaemonConfigAndReconnect(config) }
    )
    // ── End Monero dialogs ────────────────────────────────────────────────

    ChatDialogs(
        showPasswordDialog = showPasswordDialog,
        passwordPromptChannel = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = { passwordInput = it },
        onPasswordConfirm = {
            if (passwordInput.isNotEmpty()) {
                val success = viewModel.joinChannel(passwordPromptChannel!!, passwordInput)
                if (success) {
                    showPasswordDialog = false
                    passwordInput = ""
                }
            }
        },
        onPasswordDismiss = {
            showPasswordDialog = false
            passwordInput = ""
        },
        showAppInfo = showAppInfo,
        onAppInfoDismiss = { viewModel.hideAppInfo() },
        showLocationChannelsSheet = showLocationChannelsSheet,
        onLocationChannelsSheetDismiss = { showLocationChannelsSheet = false },
        showLocationNotesSheet = showLocationNotesSheet,
        onLocationNotesSheetDismiss = { showLocationNotesSheet = false },
        showUserSheet = showUserSheet,
        onUserSheetDismiss = { 
            showUserSheet = false
            selectedMessageForSheet = null // Reset message when dismissing
        },
        selectedUserForSheet = selectedUserForSheet,
        selectedMessageForSheet = selectedMessageForSheet,
        viewModel = viewModel,
        showVerificationSheet = showVerificationSheet,
        onVerificationSheetDismiss = viewModel::hideVerificationSheet,
        showSecurityVerificationSheet = showSecurityVerificationSheet,
        onSecurityVerificationSheetDismiss = viewModel::hideSecurityVerificationSheet,
        showMeshPeerListSheet = showMeshPeerListSheet,
        onMeshPeerListDismiss = viewModel::hideMeshPeerList,
        onShowRestoreWalletDialog = { showRestoreWalletDialog = true },
        onShowTransactionHistory = {
            showTransactionHistorySheet = true
            viewModel.loadTransactionHistory()
        },
    )

    MoneroTransactionHistorySheet(
        isPresented = showTransactionHistorySheet,
        transactions = transactionHistory,
        isLoading = isTransactionHistoryLoading,
        error = transactionHistoryError,
        onRefresh = viewModel::loadTransactionHistory,
        onDismiss = { showTransactionHistorySheet = false }
    )
}

@Composable
fun ChatInputSection(
    messageText: TextFieldValue,
    onMessageTextChange: (TextFieldValue) -> Unit,
    onSend: () -> Unit,
    onSendVoiceNote: (String?, String?, String) -> Unit,
    onSendImageNote: (String?, String?, String) -> Unit,
    onSendFileNote: (String?, String?, String) -> Unit,
    showCommandSuggestions: Boolean,
    commandSuggestions: List<CommandSuggestion>,
    showMentionSuggestions: Boolean,
    mentionSuggestions: List<String>,
    onCommandSuggestionClick: (CommandSuggestion) -> Unit,
    onMentionSuggestionClick: (String) -> Unit,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    colorScheme: ColorScheme,
    showMediaButtons: Boolean,
    isMoneroModeActive: Boolean = false,
    onMoneroModeToggle: () -> Unit = {},
    onMoneroAddressShare: () -> Unit = {},
    canReceiveMonero: Boolean = false,
    isWalletReady: Boolean = false
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = colorScheme.background
    ) {
        Column {
            HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))
            // Command suggestions box
            if (showCommandSuggestions && commandSuggestions.isNotEmpty()) {
                CommandSuggestionsBox(
                    suggestions = commandSuggestions,
                    onSuggestionClick = onCommandSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
            // Mention suggestions box
            if (showMentionSuggestions && mentionSuggestions.isNotEmpty()) {
                MentionSuggestionsBox(
                    suggestions = mentionSuggestions,
                    onSuggestionClick = onMentionSuggestionClick,
                    modifier = Modifier.fillMaxWidth()
                )
                HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.2f))
            }
            MessageInput(
                value = messageText,
                onValueChange = onMessageTextChange,
                onSend = onSend,
                onSendVoiceNote = onSendVoiceNote,
                onSendImageNote = onSendImageNote,
                onSendFileNote = onSendFileNote,
                selectedPrivatePeer = selectedPrivatePeer,
                currentChannel = currentChannel,
                nickname = nickname,
                showMediaButtons = showMediaButtons,
                isMoneroModeActive = isMoneroModeActive,
                onMoneroModeToggle = onMoneroModeToggle,
                onMoneroAddressShare = onMoneroAddressShare,
                canReceiveMonero = canReceiveMonero,
                isWalletReady = isWalletReady,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatFloatingHeader(
    headerHeight: Dp,
    selectedPrivatePeer: String?,
    currentChannel: String?,
    nickname: String,
    viewModel: ChatViewModel,
    colorScheme: ColorScheme,
    onSidebarToggle: () -> Unit,
    onShowAppInfo: () -> Unit,
    onPanicClear: () -> Unit,
    onLocationChannelsClick: () -> Unit,
    onLocationNotesClick: () -> Unit,
    isWalletReady: Boolean = false,
    currentBalance: String = "0.000000",
    walletStatusMessage: String = "",
    isSyncing: Boolean = false,
    syncProgress: Int = 0
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val locationManager = remember { com.bitchat.android.geohash.LocationChannelManager.getInstance(context) }
    
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .zIndex(1f)
            .windowInsetsPadding(WindowInsets.statusBars), // Extend into status bar area
        color = colorScheme.background // Solid background color extending into status bar
    ) {
        Column {
            TopAppBar(
                title = {
                    ChatHeaderContent(
                        selectedPrivatePeer = selectedPrivatePeer,
                        currentChannel = currentChannel,
                        nickname = nickname,
                        viewModel = viewModel,
                        onBackClick = {
                            when {
                                selectedPrivatePeer != null -> viewModel.endPrivateChat()
                                currentChannel != null -> viewModel.switchToChannel(null)
                            }
                        },
                        onSidebarClick = onSidebarToggle,
                        onTripleClick = onPanicClear,
                        onShowAppInfo = onShowAppInfo,
                        onLocationChannelsClick = onLocationChannelsClick,
                        onLocationNotesClick = {
                            locationManager.refreshChannels()
                            onLocationNotesClick()
                        }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                ),
                modifier = Modifier.height(headerHeight)
            )
            MoneroWalletStatusBar(
                isWalletReady = isWalletReady,
                currentBalance = if (currentBalance != "0.000000" && currentBalance != "0.0" && currentBalance != "0") currentBalance else viewModel.totalBalance,
                walletStatusMessage = walletStatusMessage,
                isSyncing = isSyncing,
                syncProgress = syncProgress,
                onStartSync = { viewModel.startManualSync() },
                onStopSync = { viewModel.stopSync() }
            )
        }
    }
}

@Composable
private fun ChatDialogs(
    showPasswordDialog: Boolean,
    passwordPromptChannel: String?,
    passwordInput: String,
    onPasswordChange: (String) -> Unit,
    onPasswordConfirm: () -> Unit,
    onPasswordDismiss: () -> Unit,
    showAppInfo: Boolean,
    onAppInfoDismiss: () -> Unit,
    showLocationChannelsSheet: Boolean,
    onLocationChannelsSheetDismiss: () -> Unit,
    showLocationNotesSheet: Boolean,
    onLocationNotesSheetDismiss: () -> Unit,
    showUserSheet: Boolean,
    onUserSheetDismiss: () -> Unit,
    selectedUserForSheet: String,
    selectedMessageForSheet: BitchatMessage?,
    viewModel: ChatViewModel,
    showVerificationSheet: Boolean,
    onVerificationSheetDismiss: () -> Unit,
    showSecurityVerificationSheet: Boolean,
    onSecurityVerificationSheetDismiss: () -> Unit,
    showMeshPeerListSheet: Boolean,
    onMeshPeerListDismiss: () -> Unit,
    onShowRestoreWalletDialog: () -> Unit = {},
    onShowTransactionHistory: () -> Unit = {},
) {
    val privateChatSheetPeer by viewModel.privateChatSheetPeer.collectAsStateWithLifecycle()

    // Password dialog
    PasswordPromptDialog(
        show = showPasswordDialog,
        channelName = passwordPromptChannel,
        passwordInput = passwordInput,
        onPasswordChange = onPasswordChange,
        onConfirm = onPasswordConfirm,
        onDismiss = onPasswordDismiss
    )

    // About sheet
    var showDebugSheet by remember { mutableStateOf(false) }
    AboutSheet(
        isPresented = showAppInfo,
        onDismiss = onAppInfoDismiss,
        onShowDebug = { showDebugSheet = true }
    )
    if (showDebugSheet) {
        com.bitchat.android.ui.debug.DebugSettingsSheet(
            isPresented = showDebugSheet,
            onDismiss = { showDebugSheet = false },
            meshService = viewModel.meshService
        )
    }
    
    // Location channels sheet
    if (showLocationChannelsSheet) {
        LocationChannelsSheet(
            isPresented = showLocationChannelsSheet,
            onDismiss = onLocationChannelsSheetDismiss,
            viewModel = viewModel
        )
    }
    
    // Location notes sheet (extracted to separate presenter)
    if (showLocationNotesSheet) {
        LocationNotesSheetPresenter(
            viewModel = viewModel,
            onDismiss = onLocationNotesSheetDismiss
        )
    }
    
    // User action sheet
    if (showUserSheet) {
        ChatUserSheet(
            isPresented = showUserSheet,
            onDismiss = onUserSheetDismiss,
            targetNickname = selectedUserForSheet,
            selectedMessage = selectedMessageForSheet,
            viewModel = viewModel
        )
    }
    // MeshPeerList sheet (network view)
    if (showMeshPeerListSheet){
        MeshPeerListSheet(
            isPresented = showMeshPeerListSheet,
            viewModel = viewModel,
            onDismiss = onMeshPeerListDismiss,
            onShowVerification = {
                onMeshPeerListDismiss()
                viewModel.showVerificationSheet(fromSidebar = true)
            },
            onShowRestoreWalletDialog = {
                onMeshPeerListDismiss()
                onShowRestoreWalletDialog()
            },
            onShowTransactionHistory = {
                onMeshPeerListDismiss()
                onShowTransactionHistory()
            }
        )
    }

    if (showVerificationSheet) {
        VerificationSheet(
            isPresented = showVerificationSheet,
            onDismiss = onVerificationSheetDismiss,
            viewModel = viewModel
        )
    }

    if (showSecurityVerificationSheet) {
        SecurityVerificationSheet(
            isPresented = showSecurityVerificationSheet,
            onDismiss = onSecurityVerificationSheetDismiss,
            viewModel = viewModel
        )
    }

    if (privateChatSheetPeer != null) {
        PrivateChatSheet(
            isPresented = true,
            peerID = privateChatSheetPeer!!,
            viewModel = viewModel,
            onDismiss = {
                viewModel.hidePrivateChatSheet()
                viewModel.endPrivateChat()
            }
        )
    }
}

@Composable
private fun MoneroWalletStatusBar(
    isWalletReady: Boolean,
    currentBalance: String,
    walletStatusMessage: String,
    isSyncing: Boolean,
    syncProgress: Int,
    onStartSync: () -> Unit,
    onStopSync: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = androidx.compose.material3.MaterialTheme.colorScheme.surface,
        tonalElevation = 2.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = if (isSyncing) "Syncing: $syncProgress%" else if (isWalletReady) "XMR: $currentBalance" else walletStatusMessage,
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = if (isSyncing) Color(0xFFFF9500) else if (isWalletReady) Color(0xFF4CAF50) else Color.Gray
                )
            }
            Row {
                IconButton(onClick = if (isSyncing) onStopSync else onStartSync, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isSyncing) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = if (isSyncing) "Stop sync" else "Start sync",
                        modifier = Modifier.size(16.dp)
                    )
                }
            }
        }
    }
}

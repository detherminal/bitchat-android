package com.bitchat.android.ui

import com.bitchat.android.R
import android.util.Log
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bitchat.android.core.ui.component.button.CloseButton
import com.bitchat.android.core.ui.component.sheet.BitchatBottomSheet
import com.bitchat.android.core.ui.component.sheet.BitchatSheetCenterTopBar
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTitle
import com.bitchat.android.core.ui.component.sheet.BitchatSheetTopBar
import com.bitchat.android.geohash.ChannelID
import com.bitchat.android.ui.theme.BASE_FONT_SIZE
import com.bitchat.android.nostr.GeohashAliasRegistry
import com.bitchat.android.nostr.GeohashConversationRegistry
import com.bitchat.android.monero.utils.MoneroValidator
import com.bitchat.android.monero.wallet.WalletSuite
import java.math.BigDecimal


/**
 * Sheet components for ChatScreen
 * Extracted from ChatScreen.kt for better organization
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MeshPeerListSheet(
    isPresented: Boolean,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit,
    onShowVerification: () -> Unit,
    onShowRestoreWalletDialog: () -> Unit = {},
    onShowTransactionHistory: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val colorScheme = MaterialTheme.colorScheme

    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val joinedChannels by viewModel.joinedChannels.collectAsStateWithLifecycle()
    val currentChannel by viewModel.currentChannel.collectAsStateWithLifecycle()
    val selectedPrivatePeer by viewModel.selectedPrivateChatPeer.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val unreadChannelMessages by viewModel.unreadChannelMessages.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val peerRSSI by viewModel.peerRSSI.collectAsStateWithLifecycle()
    val selectedLocationChannel by viewModel.selectedLocationChannel.collectAsStateWithLifecycle()
    val wifiAwareConnected by com.bitchat.android.wifiaware.WifiAwareController.connectedPeers.collectAsStateWithLifecycle()
    val wifiAwarePeerIDs = remember(wifiAwareConnected) { wifiAwareConnected.keys.toSet() }
    // Bottom sheet state
    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    // Scroll state for animated top bar
    val listState = rememberLazyListState()
    val isScrolled by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0
        }
    }
    val topBarAlpha by animateFloatAsState(
        targetValue = if (isScrolled) 0.95f else 0f,
        label = "topBarAlpha"
    )

    if (isPresented) {
        BitchatBottomSheet(
            modifier = modifier,
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f, fill = false).fillMaxWidth()) {
                    LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 64.dp, bottom = 20.dp)
                ) {
                    // Channels section
                    if (joinedChannels.isNotEmpty()) {
                        item(key = "channels_header") {
                            Text(
                                text = stringResource(id = R.string.channels).uppercase(),
                                style = MaterialTheme.typography.labelLarge,
                                color = colorScheme.onSurface.copy(alpha = 0.7f),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .padding(top = 8.dp, bottom = 4.dp)
                            )
                        }

                        items(
                            items = joinedChannels.toList(),
                            key = { "channel_$it" }
                        ) { channel ->
                            val isSelected = channel == currentChannel
                            val unreadCount = unreadChannelMessages[channel] ?: 0

                            ChannelRow(
                                channel = channel,
                                isSelected = isSelected,
                                unreadCount = unreadCount,
                                colorScheme = colorScheme,
                                onChannelClick = {
                                    // Check if this is a DM channel (starts with @)
                                    if (channel.startsWith("@")) {
                                        // Extract peer name and find the peer ID
                                        val peerName = channel.removePrefix("@")
                                        val peerID =
                                            peerNicknames.entries.firstOrNull { it.value == peerName }?.key
                                    if (peerID != null) {
                                            viewModel.showPrivateChatSheet(peerID)
                                            onDismiss()
                                        }
                                    } else {
                                        // Regular channel switch
                                        viewModel.switchToChannel(channel)
                                        onDismiss()
                                    }
                                },
                                onLeaveChannel = {
                                    viewModel.leaveChannel(channel)
                                },
                            )
                        }
                    }

                    // People section - switch between mesh and geohash lists (iOS-compatible)
                    item(key = "people_section") {
                        when (selectedLocationChannel) {
                            is ChannelID.Location -> {
                                GeohashPeopleList(
                                    viewModel = viewModel,
                                    onTapPerson = onDismiss
                                )
                            }
                            else -> {
                                PeopleSection(
                                    modifier = Modifier.padding(top = if (joinedChannels.isNotEmpty()) 16.dp else 0.dp),
                                    connectedPeers = connectedPeers,
                                    peerNicknames = peerNicknames,
                                    peerRSSI = peerRSSI,
                                    nickname = nickname,
                                    colorScheme = colorScheme,
                                    selectedPrivatePeer = selectedPrivatePeer,
                                    wifiAwarePeerIDs = wifiAwarePeerIDs,
                                    viewModel = viewModel,
                                    onPrivateChatStart = { peerID ->
                                        viewModel.showPrivateChatSheet(peerID)
                                        onDismiss()
                                    }
                                )
                            }
                        }
                    }

                    // Monero tools section
                    item(key = "monero_tools") {
                        HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                        MoneroToolsSection(
                            onHistoryClick = {
                                onShowTransactionHistory()
                                onDismiss()
                            },
                            onSettingsClick = {
                                viewModel.showDaemonConfigDialog()
                                onDismiss()
                            }
                        )
                    }
                    // Removed Wallet details from here
                }

                // TopBar (animated)
                BitchatSheetTopBar(
                    title = {
                        BitchatSheetTitle(text = stringResource(id = R.string.your_network))
                    },
                    backgroundAlpha = topBarAlpha,
                    actions = {
                        if (selectedLocationChannel !is ChannelID.Location) {
                            IconButton(
                                onClick = onShowVerification,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Outlined.QrCode,
                                    contentDescription = stringResource(R.string.verify_title),
                                    tint = colorScheme.onSurface.copy(alpha = 0.8f),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    onClose = onDismiss,
                )
            }

            if (viewModel.isWalletReady) {
                HorizontalDivider()
                WalletDetailsSection(
                    viewModel = viewModel,
                    onShowRestoreWalletDialog = onShowRestoreWalletDialog
                )
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}
}

@Composable
private fun ChannelRow(
    channel: String,
    isSelected: Boolean,
    unreadCount: Int,
    colorScheme: ColorScheme,
    onChannelClick: () -> Unit,
    onLeaveChannel: () -> Unit,
) {
    Surface(
        onClick = onChannelClick,
        color = if (isSelected) {
            colorScheme.primaryContainer.copy(alpha = 0.15f)
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Unread badge
                if (unreadCount > 0) {
                    UnreadBadge(
                        count = unreadCount,
                        colorScheme = colorScheme
                    )
                }
                
                Text(
                    text = channel,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontFamily = FontFamily.Monospace,
                        fontSize = BASE_FONT_SIZE.sp
                    ),
                    color = if (isSelected) colorScheme.primary else colorScheme.onSurface,
                    fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Leave channel button
                CloseButton(
                    onClick = onLeaveChannel,
                )
            }
        }
    }
}



@Composable
fun PeopleSection(
    modifier: Modifier  = Modifier,
    connectedPeers: List<String>,
    peerNicknames: Map<String, String>,
    peerRSSI: Map<String, Int>,
    nickname: String,
    colorScheme: ColorScheme,
    selectedPrivatePeer: String?,
    wifiAwarePeerIDs: Set<String> = emptySet(),
    viewModel: ChatViewModel,
    onPrivateChatStart: (String) -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = stringResource(id = R.string.people).uppercase(),
            style = MaterialTheme.typography.labelLarge,
            color = colorScheme.onSurface.copy(alpha = 0.7f),
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(top = 8.dp, bottom = 4.dp)
        )

        if (connectedPeers.isEmpty()) {
            Text(
                text = stringResource(id = R.string.no_one_connected),
                style = MaterialTheme.typography.bodyMedium.copy(
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp
                ),
                color = colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 40.dp, vertical = 12.dp)
            )
        }

        // Observe reactive state for favorites and fingerprints
        val hasUnreadPrivateMessages by viewModel.unreadPrivateMessages.collectAsStateWithLifecycle()
        val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
        val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
        val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()
        val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()

        // Reactive favorite computation for all peers
        val peerFavoriteStates = remember(favoritePeers, peerFingerprints, connectedPeers) {
            connectedPeers.associateWith { peerID ->
                // Reactive favorite computation - same as ChatHeader
                val fingerprint = peerFingerprints[peerID]
                fingerprint != null && favoritePeers.contains(fingerprint)
            }
        }

        val peerVerifiedStates = remember(verifiedFingerprints, peerFingerprints, connectedPeers) {
            connectedPeers.associateWith { peerID ->
                viewModel.isPeerVerified(peerID, verifiedFingerprints)
            }
        }

        // Build mapping of connected peerID -> noise key hex to unify with offline favorites
        val noiseHexByPeerID: Map<String, String> = connectedPeers.associateWith { pid ->
            try {
                viewModel.getMeshPeerInfo(pid)?.noisePublicKey?.joinToString("") { b -> "%02x".format(b) }
            } catch (_: Exception) { null }
        }.filterValues { it != null }.mapValues { it.value!! }

        Log.d("SidebarComponents", "Recomposing with ${favoritePeers.size} favorites, peer states: $peerFavoriteStates")

        // Smart sorting: unread DMs first, then by most recent DM, then favorites, then alphabetical
        val sortedPeers = connectedPeers.sortedWith(
            compareBy<String> { !hasUnreadPrivateMessages.contains(it) } // Unread DM senders first
            .thenByDescending { privateChats[it]?.maxByOrNull { msg -> msg.timestamp }?.timestamp?.time ?: 0L } // Most recent DM (convert Date to Long)
            .thenBy { !(peerFavoriteStates[it] ?: false) } // Favorites first
            .thenBy { (if (it == nickname) "You" else (peerNicknames[it] ?: it)).lowercase() } // Alphabetical
        )
        
        // Build a map of base name counts across all people shown in the list (connected + offline + nostr)
        val hex64Regex = Regex("^[0-9a-fA-F]{64}$")

        // Helper to compute display name used for a given key
        fun computeDisplayNameForPeerId(key: String): String {
            return if (key == nickname) "You" else (peerNicknames[key] ?: (privateChats[key]?.lastOrNull()?.sender ?: key.take(12)))
        }

        val baseNameCounts = mutableMapOf<String, Int>()

        // Connected peers
        sortedPeers.forEach { pid ->
            val dn = computeDisplayNameForPeerId(pid)
            val (b, _) = splitSuffix(dn)
            if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
        }

        // Offline favorites (exclude ones mapped to connected)
        val offlineFavorites = com.bitchat.android.favorites.FavoritesPersistenceService.shared.getOurFavorites()
        offlineFavorites.forEach { fav ->
            val favPeerID = fav.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
            val isMappedToConnected = noiseHexByPeerID.values.any { it.equals(favPeerID, ignoreCase = true) }
            if (!isMappedToConnected) {
                val dn = peerNicknames[favPeerID] ?: fav.peerNickname
                val (b, _) = splitSuffix(dn)
                if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
            }
        }

        // Nostr-only conversations
        val connectedIds = sortedPeers.toSet()
        val appendedOfflineIds = mutableSetOf<String>()
        privateChats.keys
            .filter { key ->
                (key.startsWith("nostr_") || hex64Regex.matches(key)) &&
                        !connectedIds.contains(key) &&
                        !noiseHexByPeerID.values.any { it.equals(key, ignoreCase = true) }
            }
            .forEach { convKey ->
                val dn = peerNicknames[convKey] ?: (privateChats[convKey]?.lastOrNull()?.sender ?: convKey.take(12))
                val (b, _) = splitSuffix(dn)
                if (b != "You") baseNameCounts[b] = (baseNameCounts[b] ?: 0) + 1
            }

        sortedPeers.forEach { peerID ->
            val isFavorite = peerFavoriteStates[peerID] ?: false
            val isVerified = peerVerifiedStates[peerID] ?: false
            // fingerprint and favorite relationship resolution not needed here; UI will show Nostr globe for appended offline favorites below

            val noiseHex = noiseHexByPeerID[peerID]
            val meshUnread = hasUnreadPrivateMessages.contains(peerID)
            val nostrUnread = if (noiseHex != null) hasUnreadPrivateMessages.contains(noiseHex) else false
            val combinedHasUnread = meshUnread || nostrUnread
            val combinedUnreadCount = (
                privateChats[peerID]?.count { msg -> msg.sender != nickname && meshUnread } ?: 0
            ) + (
                if (noiseHex != null) privateChats[noiseHex]?.count { msg -> msg.sender != nickname && nostrUnread } ?: 0 else 0
            )

            val displayName = if (peerID == nickname) "You" else (peerNicknames[peerID] ?: (privateChats[peerID]?.lastOrNull()?.sender ?: peerID.take(12)))
            val (bName, _) = splitSuffix(displayName)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val directMap by viewModel.peerDirect.collectAsStateWithLifecycle()
            val isDirectLive = directMap[peerID] ?: try { viewModel.getMeshPeerInfo(peerID)?.isDirectConnection == true } catch (_: Exception) { false }
            PeerItem(
                peerID = peerID,
                displayName = displayName,
                isDirect = isDirectLive,
                isWifiAware = peerID in wifiAwarePeerIDs,
                isSelected = peerID == selectedPrivatePeer,
                isFavorite = isFavorite,
                isVerified = isVerified,
                hasUnreadDM = combinedHasUnread,
                colorScheme = colorScheme,
                viewModel = viewModel,
                onItemClick = { onPrivateChatStart(peerID) },
                onToggleFavorite = { 
                    Log.d("SidebarComponents", "Sidebar toggle favorite: peerID=$peerID, currentFavorite=$isFavorite")
                    viewModel.toggleFavorite(peerID) 
                },
                unreadCount = if (combinedUnreadCount > 0) combinedUnreadCount else if (combinedHasUnread) 1 else 0,
                showNostrGlobe = false,
                showHashSuffix = showHash
            )
        }

        // Append offline favorites we actively favorite (and not currently connected)
        offlineFavorites.forEach { fav ->
            val favPeerID = fav.peerNoisePublicKey.joinToString("") { b -> "%02x".format(b) }
            // If any connected peer maps to this noise key, skip showing the offline entry
            val isMappedToConnected = noiseHexByPeerID.values.any { it.equals(favPeerID, ignoreCase = true) }
            if (isMappedToConnected) return@forEach

            // Resolve potential Nostr conversation key for this favorite (for unread detection)
            val nostrConvKey: String? = try {
                val npubOrHex = com.bitchat.android.favorites.FavoritesPersistenceService.shared.findNostrPubkey(fav.peerNoisePublicKey)
                if (npubOrHex != null) {
                    val hex = if (npubOrHex.startsWith("npub")) {
                        val (hrp, data) = com.bitchat.android.nostr.Bech32.decode(npubOrHex)
                        if (hrp == "npub") data.joinToString("") { "%02x".format(it) } else null
                    } else {
                        npubOrHex.lowercase()
                    }
                    hex?.let { "nostr_${it.take(16)}" }
                } else null
            } catch (_: Exception) { null }

            val hasUnread = hasUnreadPrivateMessages.contains(favPeerID) || (nostrConvKey != null && hasUnreadPrivateMessages.contains(nostrConvKey))

            // If user clicks an offline favorite and the mapped peer is currently connected under a different ID,
            // open chat with the connected peerID instead of the noise hex for a seamless window
            val mappedConnectedPeerID = noiseHexByPeerID.entries.firstOrNull { it.value.equals(favPeerID, ignoreCase = true) }?.key
            val dn = peerNicknames[favPeerID] ?: fav.peerNickname
            val (bName, _) = splitSuffix(dn)
            val showHash = (baseNameCounts[bName] ?: 0) > 1

            val isVerified = viewModel.isNoisePublicKeyVerified(fav.peerNoisePublicKey, verifiedFingerprints)

            // Compute unreadCount from either noise conversation or Nostr conversation
            val unreadCount = (
                privateChats[favPeerID]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(favPeerID) } ?: 0
            ) + (
                if (nostrConvKey != null) privateChats[nostrConvKey]?.count { msg -> msg.sender != nickname && hasUnreadPrivateMessages.contains(nostrConvKey) } ?: 0 else 0
            )

            PeerItem(
                peerID = favPeerID,
                displayName = dn,
                isDirect = false,
                isSelected = (mappedConnectedPeerID ?: favPeerID) == selectedPrivatePeer,
                isFavorite = true,
                isVerified = isVerified,
                hasUnreadDM = hasUnread,
                colorScheme = colorScheme,
                viewModel = viewModel,
                onItemClick = { onPrivateChatStart(mappedConnectedPeerID ?: favPeerID) },
                onToggleFavorite = { 
                    Log.d("SidebarComponents", "Sidebar toggle favorite (offline): peerID=$favPeerID")
                    viewModel.toggleFavorite(favPeerID)
                },
                unreadCount = if (unreadCount > 0) unreadCount else if (hasUnread) 1 else 0,
                showNostrGlobe = (fav.isMutual && fav.peerNostrPublicKey != null),
                showHashSuffix = showHash
            )
            appendedOfflineIds.add(favPeerID)
        }

        // NOTE: Do NOT append Nostr-only (nostr_*) conversations to the mesh people list.
        // Geohash DMs should appear in the GeohashPeople list for the active geohash, not in mesh offline contacts.
        // We intentionally remove previously-added behavior that mixed geohash DMs into mesh sidebar.
        // If you need to surface non-geohash offline mesh conversations in the future, do it here for 64-hex noise IDs only.
        /*
        val alreadyShownIds = connectedIds + appendedOfflineIds
        privateChats.keys
            .filter { key ->
                // Only include 64-hex noise IDs (mesh identities); exclude any nostr_* aliases
                hex64Regex.matches(key) &&
                !alreadyShownIds.contains(key) &&
                // Skip if this key maps to a connected peer via noiseHex mapping
                !noiseHexByPeerID.values.any { it.equals(key, ignoreCase = true) }
            }
            .sortedBy { key -> privateChats[key]?.lastOrNull()?.timestamp }
            .forEach { convKey ->
                val lastSender = privateChats[convKey]?.lastOrNull()?.sender
                val dn = peerNicknames[convKey] ?: (lastSender ?: convKey.take(12))
                val (bName, _) = splitSuffix(dn)
                val showHash = (baseNameCounts[bName] ?: 0) > 1

                PeerItem(
                    peerID = convKey,
                    displayName = dn,
                    isDirect = false,
                    isSelected = convKey == selectedPrivatePeer,
                    isFavorite = false,
                    hasUnreadDM = hasUnreadPrivateMessages.contains(convKey),
                    colorScheme = colorScheme,
                    viewModel = viewModel,
                    onItemClick = { onPrivateChatStart(convKey) },
                    onToggleFavorite = { viewModel.toggleFavorite(convKey) },
                    unreadCount = privateChats[convKey]?.count { msg ->
                        msg.sender != nickname && hasUnreadPrivateMessages.contains(convKey)
                    } ?: if (hasUnreadPrivateMessages.contains(convKey)) 1 else 0,
                    showNostrGlobe = false,
                    showHashSuffix = showHash
                )
            }
        */
        // End intentional removal
        
    }
}

@Composable
private fun PeerItem(
    peerID: String,
    displayName: String,
    isDirect: Boolean,
    isWifiAware: Boolean = false,
    isSelected: Boolean,
    isFavorite: Boolean,
    isVerified: Boolean,
    hasUnreadDM: Boolean,
    colorScheme: ColorScheme,
    viewModel: ChatViewModel,
    onItemClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    unreadCount: Int = 0,
    showNostrGlobe: Boolean = false,
    showHashSuffix: Boolean = true
) {
    val currentNickname by viewModel.nickname.collectAsStateWithLifecycle()
    // Split display name for hashtag suffix support (iOS-compatible)
    val (baseNameRaw, suffixRaw) = splitSuffix(displayName)
    val baseName = truncateNickname(baseNameRaw)
    val suffix = if (showHashSuffix) suffixRaw else ""
    val isMe = displayName == "You" || peerID == currentNickname

    // Get consistent peer color (iOS-compatible)
    val isDark = colorScheme.background.red + colorScheme.background.green + colorScheme.background.blue < 1.5f
    val assignedColor = viewModel.colorForMeshPeer(peerID, isDark)
    val baseColor = if (isMe) Color(0xFFFF9500) else assignedColor

    Surface(
        onClick = onItemClick,
        color = if (isSelected) {
            colorScheme.primaryContainer.copy(alpha = 0.15f)
        } else {
            Color.Transparent
        },
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Connection/status indicator
                if (hasUnreadDM) {
                    // Show mail icon for unread DMs (iOS orange)
                    Icon(
                        imageVector = Icons.Filled.Email,
                        contentDescription = stringResource(R.string.cd_unread_message),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFFFF9500) // iOS orange
                    )
                } else if (showNostrGlobe) {
                    // Purple globe to indicate Nostr availability
                    Icon(
                        imageVector = Icons.Filled.Public,
                        contentDescription = stringResource(R.string.cd_reachable_via_nostr),
                        modifier = Modifier.size(16.dp),
                        tint = Color(0xFF9C27B0) // Purple
                    )
                } else if (!isDirect && isFavorite) {
                    // Offline favorited user: show outlined circle icon
                    Icon(
                        imageVector = Icons.Outlined.Circle,
                        contentDescription = stringResource(R.string.cd_offline_favorite),
                        modifier = Modifier.size(16.dp),
                        tint = Color.Gray
                    )
                } else {
                    Icon(
                        imageVector = when {
                            isWifiAware -> Icons.Filled.Wifi
                            isDirect -> Icons.Outlined.Bluetooth
                            else -> Icons.Filled.Route
                        },
                        contentDescription = when {
                            isWifiAware -> "Direct Wi-Fi Aware"
                            isDirect -> "Direct Bluetooth"
                            else -> "Routed"
                        },
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }

                // Display name with iOS-style color and hashtag suffix support
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Base name with peer-specific color
                    Text(
                        text = baseName,
                        style = MaterialTheme.typography.bodyMedium.copy(
                            fontFamily = FontFamily.Monospace,
                            fontSize = BASE_FONT_SIZE.sp,
                            fontWeight = if (isMe) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = baseColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    // Hashtag suffix in lighter shade (iOS-style)
                    if (suffix.isNotEmpty()) {
                        Text(
                            text = suffix,
                            style = MaterialTheme.typography.bodyMedium.copy(
                                fontFamily = FontFamily.Monospace,
                                fontSize = BASE_FONT_SIZE.sp
                            ),
                            color = baseColor.copy(alpha = 0.6f)
                        )
                    }

                    if (isWifiAware && hasUnreadDM) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            imageVector = Icons.Filled.Wifi,
                            contentDescription = "Direct Wi-Fi Aware",
                            modifier = Modifier.size(13.dp),
                            tint = colorScheme.onSurface.copy(alpha = 0.8f)
                        )
                    }
                }
            }

            if (isVerified) {
                Spacer(modifier = Modifier.width(4.dp))
                Icon(
                    imageVector = Icons.Filled.Verified,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = Color(0xFF32D74B) // iOS Green
                )
            }

            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Favorite star with proper filled/outlined states
                IconButton(
                    onClick = onToggleFavorite,
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                        contentDescription = if (isFavorite) "Remove from favorites" else "Add to favorites",
                        modifier = Modifier.size(16.dp),
                        tint = if (isFavorite) Color(0xFFFFD700) else Color(0xFF4CAF50)
                    )
                }
            }
        }
    }
}

/**
 * Reusable unread badge component for both channels and private messages
 */
@Composable
private fun UnreadBadge(
    count: Int,
    colorScheme: ColorScheme,
    modifier: Modifier = Modifier
) {
    if (count > 0) {
        Box(
            modifier = modifier
                .background(
                    color = Color(0xFFFFD700), // Yellow color
                    shape = RoundedCornerShape(10.dp)
                )
                .padding(horizontal = 6.dp, vertical = 2.dp)
                .defaultMinSize(minWidth = 18.dp, minHeight = 18.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (count > 99) "99+" else count.toString(),
                style = MaterialTheme.typography.labelSmall.copy(
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                ),
                color = Color.Black // Black text on yellow background
            )
        }
    }
}

/**
 * Convert RSSI value (dBm) to signal strength percentage (0-100)
 * RSSI typically ranges from -30 (excellent) to -100 (very poor)
 * Maps to 0-100 scale where:
 * - 0-32: No signal (0 bars)
 * - 33-65: Weak (1 bar) 
 * - 66-98: Good (2 bars)
 * - 99-100: Excellent (3 bars)
 */
private fun convertRSSIToSignalStrength(rssi: Int?): Int {
    if (rssi == null) return 0
    
    return when {
        rssi >= -40 -> 100  // Excellent signal
        rssi >= -55 -> 85   // Very good signal  
        rssi >= -70 -> 70   // Good signal
        rssi >= -85 -> 50   // Fair signal
        rssi >= -100 -> 25  // Poor signal
        else -> 0           // Very poor or no signal
    }
}

/**
 * Nested Private Chat Sheet - iOS-style nested bottom sheet
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivateChatSheet(
    isPresented: Boolean,
    peerID: String,
    viewModel: ChatViewModel,
    onDismiss: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme
    val privateChats by viewModel.privateChats.collectAsStateWithLifecycle()
    val peerNicknames by viewModel.peerNicknames.collectAsStateWithLifecycle()
    val nickname by viewModel.nickname.collectAsStateWithLifecycle()
    val connectedPeers by viewModel.connectedPeers.collectAsStateWithLifecycle()
    val peerDirectMap by viewModel.peerDirect.collectAsStateWithLifecycle()
    val peerSessionStates by viewModel.peerSessionStates.collectAsStateWithLifecycle()
    val favoritePeers by viewModel.favoritePeers.collectAsStateWithLifecycle()
    val peerFingerprints by viewModel.peerFingerprints.collectAsStateWithLifecycle()

    val verifiedFingerprints by viewModel.verifiedFingerprints.collectAsStateWithLifecycle()
    val wifiAwareConnected by com.bitchat.android.wifiaware.WifiAwareController.connectedPeers.collectAsStateWithLifecycle()
    val isWifiAware = peerID in wifiAwareConnected.keys

    // Start private chat when screen opens
    LaunchedEffect(peerID) {
        viewModel.startPrivateChat(peerID)
    }

    val isNostrPeer = peerID.startsWith("nostr_") || peerID.startsWith("nostr:")

    // Compute display name and title text reactively
    val displayName = peerNicknames[peerID] ?: peerID.take(12)
    val titleText = remember(peerID, peerNicknames) {
        if (isNostrPeer) {
            val gh = GeohashConversationRegistry.get(peerID) ?: "geohash"
            val fullPubkey = GeohashAliasRegistry.get(peerID) ?: ""
            val name = if (fullPubkey.isNotEmpty()) {
                viewModel.geohashViewModel.displayNameForGeohashConversation(fullPubkey, gh)
            } else {
                peerNicknames[peerID] ?: "unknown"
            }
            "#$gh/@$name"
        } else {
            peerNicknames[peerID] ?: peerID.take(12)
        }
    }

    val messages = privateChats[peerID] ?: emptyList()
    val isDirect = peerDirectMap[peerID] == true
    val isConnected = connectedPeers.contains(peerID) || isDirect
    val sessionState = peerSessionStates[peerID]
    val fingerprint = peerFingerprints[peerID]
    val isFavorite = remember(favoritePeers, fingerprint) {
        if (fingerprint != null) favoritePeers.contains(fingerprint) else viewModel.isFavorite(peerID)
    }

    val isVerified = remember(peerID, verifiedFingerprints) {
        viewModel.isPeerVerified(peerID, verifiedFingerprints)
    }

    // The private-chat sheet is the actual Nostr DM composer. Keep its
    // Monero state here as well; the main ChatScreen composer is not used
    // while this sheet is visible.
    val peerMoneroAddresses by viewModel.peerMoneroAddresses
    val isWalletReady = viewModel.isWalletReady
    val myWalletAddress by viewModel.myWalletAddress.collectAsStateWithLifecycle()
    val canReceiveMonero = isWalletReady && peerMoneroAddresses.containsKey(peerID)
    val isMoneroModeActive = viewModel.isMoneroModeActive
    var showMoneroConfirmDialog by remember { mutableStateOf(false) }
    var pendingMoneroAmount by remember { mutableStateOf("") }
    var pendingMoneroFeeAtomic by remember { mutableStateOf<Long?>(null) }
    var pendingMoneroAddress by remember { mutableStateOf("") }
    var isPreparingMoneroTransaction by remember { mutableStateOf(false) }
    var isMoneroSendInProgress by remember { mutableStateOf(false) }

    fun addMoneroStatus(message: String) {
        viewModel.addPrivateSystemMessage(peerID, message)
    }

    fun moneroAmountToAtomic(input: String): Long? = runCatching {
        BigDecimal(input).movePointRight(12).longValueExact()
    }.getOrNull()

    fun parseMoneroAmountInput(input: String): String? {
        val trimmed = input.trim()
        val match = Regex("""^(?:\d+(?:[.,]\d{1,12})?|[.,]\d{1,12})$""").matches(trimmed)
        if (!match) return null
        return runCatching {
            val normalized = trimmed.replace(',', '.')
            if (BigDecimal(normalized).signum() > 0) normalized else null
        }.getOrNull()
    }

    fun prepareMoneroTransaction(rawText: String, onTextConsumed: () -> Unit) {
        val address = peerMoneroAddresses[peerID]
        val amountText = parseMoneroAmountInput(rawText)
        if (!canReceiveMonero || address == null) {
            addMoneroStatus("❌ Monero can only be sent after the recipient shares an address")
            return
        }
        if (amountText == null) {
            addMoneroStatus("❌ Invalid XMR amount")
            return
        }
        if (!MoneroValidator.isValidAddress(address)) {
            addMoneroStatus("❌ Recipient address is invalid")
            return
        }
        val validation = MoneroValidator.validateAmount(amountText)
        val amountAtomic = moneroAmountToAtomic(amountText)
        if (!validation.isValid()) {
            addMoneroStatus("❌ ${validation.message}")
            return
        }
        if (amountAtomic == null) {
            addMoneroStatus("❌ Invalid XMR amount")
            return
        }
        if (isPreparingMoneroTransaction || isMoneroSendInProgress) {
            addMoneroStatus("⏳ A Monero transaction is already being prepared")
            return
        }
        if (amountAtomic > viewModel.getCachedUnlockedBalance()) {
            addMoneroStatus(
                "❌ Insufficient unlocked balance. Available: " +
                    "${WalletSuite.convertAtomicToXmr(viewModel.getCachedUnlockedBalance())} XMR"
            )
            return
        }

        pendingMoneroAmount = amountText
        pendingMoneroAddress = address
        pendingMoneroFeeAtomic = null
        isPreparingMoneroTransaction = true
        onTextConsumed()
        addMoneroStatus("⏳ Estimating Monero network fee...")

        val activeWallet = viewModel.walletSuite
        if (activeWallet == null) {
            isPreparingMoneroTransaction = false
            addMoneroStatus("❌ Wallet is not ready")
            return
        }
        activeWallet.estimateTransactionFee(
            address,
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
                        addMoneroStatus(
                            "❌ Insufficient unlocked balance including fee. Required: " +
                                "${WalletSuite.convertAtomicToXmr(totalAtomic)} XMR, available: " +
                                "${WalletSuite.convertAtomicToXmr(unlockedBalance)} XMR"
                        )
                    } else {
                        pendingMoneroFeeAtomic = feeAtomic
                        showMoneroConfirmDialog = true
                    }
                }

                override fun onError(error: String) {
                    isPreparingMoneroTransaction = false
                    addMoneroStatus("❌ Could not estimate network fee: $error")
                }
            }
        )
    }

    val securityModifier = if (!isNostrPeer) {
        Modifier.clickable { viewModel.showSecurityVerificationSheet() }
    } else {
        Modifier
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true
    )

    if (isPresented) {
        BitchatBottomSheet(
            onDismissRequest = onDismiss,
            sheetState = sheetState,
        ) {
            Box(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier.fillMaxSize()
                ) {
                    Spacer(modifier = Modifier.height(64.dp))

                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))

                    // Messages list
                    var forceScrollToBottom by remember { mutableStateOf(false) }
                    var isScrolledUp by remember { mutableStateOf(false) }

                    MessagesList(
                        messages = messages,
                        currentUserNickname = nickname,
                        meshService = viewModel.meshServiceFacade,
                        modifier = Modifier.weight(1f),
                        forceScrollToBottom = forceScrollToBottom,
                        onScrolledUpChanged = { isUp -> isScrolledUp = isUp },
                        onNicknameClick = { /* handle mention */ },
                        onMessageLongPress = { /* handle long press */ },
                        onCancelTransfer = { msg -> viewModel.cancelMediaSend(msg.id) },
                        onImageClick = { _, _, _ -> /* handle image click */ }
                    )

                    HorizontalDivider(color = colorScheme.outline.copy(alpha = 0.3f))

                    // Input section
                    var messageText by remember {
                        mutableStateOf(
                            androidx.compose.ui.text.input.TextFieldValue(
                                ""
                            )
                        )
                    }

                    ChatInputSection(
                        messageText = messageText,
                        onMessageTextChange = { newText ->
                            messageText = newText
                            viewModel.updateMentionSuggestions(newText.text)
                        },
                        onSend = {
                            val trimmedText = messageText.text.trim()
                            if (trimmedText.isNotEmpty()) {
                                if (isMoneroModeActive) {
                                    prepareMoneroTransaction(trimmedText) {
                                        messageText = TextFieldValue("")
                                    }
                                } else {
                                    viewModel.sendMessage(trimmedText)
                                    messageText = TextFieldValue("")
                                }
                                forceScrollToBottom = !forceScrollToBottom
                            }
                        },
                        onSendVoiceNote = { peer, channel, path ->
                            viewModel.sendVoiceNote(peer, channel, path)
                        },
                        onSendImageNote = { peer, channel, path ->
                            viewModel.sendImageNote(peer, channel, path)
                        },
                        onSendFileNote = { peer, channel, path ->
                            viewModel.sendFileNote(peer, channel, path)
                        },
                        showCommandSuggestions = false,
                        commandSuggestions = emptyList(),
                        showMentionSuggestions = false,
                        mentionSuggestions = emptyList(),
                        onCommandSuggestionClick = { },
                        onMentionSuggestionClick = { },
                        selectedPrivatePeer = peerID,
                        currentChannel = null,
                        nickname = nickname,
                        colorScheme = colorScheme,
                        showMediaButtons = true,
                        isMoneroModeActive = isMoneroModeActive,
                        onMoneroAddressShare = {
                            val address = myWalletAddress
                            if (address != null && isWalletReady) {
                                viewModel.shareMoneroAddressWithPeer(peerID, address)
                            } else {
                                addMoneroStatus("❌ Wallet address is not ready")
                            }
                        },
                        onMoneroModeToggle = {
                            if (isWalletReady && peerID.isNotBlank()) {
                                viewModel.isMoneroModeActive = !isMoneroModeActive
                            }
                        },
                        canReceiveMonero = canReceiveMonero,
                        isWalletReady = isWalletReady
                    )
                }

                // TopBar (fixed at top, iOS-style)
                BitchatSheetCenterTopBar(
                    onClose = onDismiss,
                    modifier = Modifier.align(Alignment.TopCenter),
                    navigationIcon = {
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier
                                .align(Alignment.CenterStart)
                                .padding(start = 16.dp)
                                .size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(R.string.chat_back),
                                tint = colorScheme.onSurface
                            )
                        }
                    },
                    title = {
                        // Center content: connection status + name + encryption
                        Row(
                            modifier = Modifier.align(Alignment.Center),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            when {
                                isNostrPeer -> {
                                    Icon(
                                        imageVector = Icons.Filled.Public,
                                        contentDescription = stringResource(R.string.cd_nostr_reachable),
                                        modifier = Modifier.size(14.dp),
                                        tint = Color(0xFF9C27B0)
                                    )
                                }
                                isWifiAware -> {
                                    Icon(
                                        imageVector = Icons.Filled.Wifi,
                                        contentDescription = "Direct Wi-Fi Aware",
                                        modifier = Modifier.size(14.dp),
                                        tint = colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                isDirect -> {
                                    Icon(
                                        imageVector = Icons.Outlined.Bluetooth,
                                        contentDescription = "Direct Bluetooth",
                                        modifier = Modifier.size(14.dp),
                                        tint = colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                                isConnected -> {
                                    Icon(
                                        imageVector = Icons.Filled.Route,
                                        contentDescription = "Routed",
                                        modifier = Modifier.size(14.dp),
                                        tint = colorScheme.onSurface.copy(alpha = 0.6f)
                                    )
                                }
                            }

                        Text(
                            text = titleText,
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                fontFamily = FontFamily.Monospace
                            ),
                            color = if (isNostrPeer) Color(0xFFFF9500) else colorScheme.onSurface
                        )

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.then(securityModifier)
                            ) {
                                if (!isNostrPeer) {
                                    NoiseSessionIcon(
                                        sessionState = sessionState,
                                        modifier = Modifier.size(14.dp)
                                    )
                                }

                                if (isVerified) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Filled.Verified,
                                        contentDescription = stringResource(R.string.verify_title),
                                        modifier = Modifier.size(14.dp),
                                        tint = Color(0xFF32D74B) // iOS Green
                                    )
                                }
                            }

                            IconButton(
                                onClick = { viewModel.toggleFavorite(peerID) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (isFavorite) Icons.Filled.Star else Icons.Outlined.Star,
                                    contentDescription = if (isFavorite) stringResource(R.string.cd_remove_favorite) else stringResource(R.string.cd_add_favorite),
                                    modifier = Modifier.size(16.dp),
                                    tint = if (isFavorite) Color(0xFFFFD700) else colorScheme.onSurface.copy(alpha = 0.6f)
                                )
                            }
                        }
                    }
                )
            }
        }
    }

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
                            Text("Estimated total: ${WalletSuite.convertAtomicToXmr(amountAtomic + feeAtomic)} XMR")
                        }
                    }
                    Text("Send to:")
                    Text(
                        text = if (pendingMoneroAddress.length > 20) {
                            "${pendingMoneroAddress.take(10)}...${pendingMoneroAddress.takeLast(10)}"
                        } else pendingMoneroAddress,
                        style = MaterialTheme.typography.bodySmall
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
                        } else Long.MAX_VALUE

                        if (amountAtomic == null || feeAtomic == null || totalAtomic > unlockedBalance) {
                            showMoneroConfirmDialog = false
                            pendingMoneroFeeAtomic = null
                            addMoneroStatus("❌ Balance or transaction details changed")
                        } else {
                            showMoneroConfirmDialog = false
                            pendingMoneroFeeAtomic = null
                            isMoneroSendInProgress = true
                            viewModel.isMoneroModeActive = false
                            addMoneroStatus("⏳ Preparing transaction of $pendingMoneroAmount XMR...")
                            val activeWallet = viewModel.walletSuite
                            if (activeWallet == null) {
                                isMoneroSendInProgress = false
                                addMoneroStatus("❌ Wallet is not ready")
                            } else {
                                activeWallet.sendTransaction(
                                    pendingMoneroAddress,
                                    pendingMoneroAmount,
                                    viewModel.getCachedBalance(),
                                    unlockedBalance,
                                    object : WalletSuite.TransactionCallback {
                                        override fun onSuccess(txId: String, sentAmount: Long) {
                                            isMoneroSendInProgress = false
                                            val sentAmountXmr = WalletSuite.convertAtomicToXmr(sentAmount)
                                            addMoneroStatus("✅ Sent $sentAmountXmr XMR successfully")
                                            val txMessage = viewModel.moneroMessageHandler.createTransactionIdMessage(
                                                txId = txId,
                                                amount = sentAmountXmr,
                                                toAddress = pendingMoneroAddress
                                            )
                                            viewModel.sendDirectMessage(peerID, txMessage)
                                        }

                                        override fun onError(error: String) {
                                            isMoneroSendInProgress = false
                                            addMoneroStatus("❌ Transaction failed: $error")
                                        }
                                    }
                                )
                            }
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
}

// ── Monero tools added from SidebarComponents ────────────────────────────

@Composable
private fun MoneroToolsSection(
    onHistoryClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    val colorScheme = MaterialTheme.colorScheme

    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.CurrencyExchange,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "MONERO TOOLS",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSettingsClick() }
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Wallet Settings",
                modifier = Modifier.size(16.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Wallet Settings",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onHistoryClick() }
                .padding(horizontal = 24.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.History,
                contentDescription = "Transaction history",
                modifier = Modifier.size(16.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.7f)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Transaction History",
                style = MaterialTheme.typography.bodyMedium,
                color = colorScheme.onSurface
            )
        }
    }
}

@Composable
private fun WalletDetailsSection(
    viewModel: ChatViewModel,
    onShowRestoreWalletDialog: () -> Unit = {}
) {
    val colorScheme = MaterialTheme.colorScheme
    var isExpanded by remember { mutableStateOf(false) }
    var showSeed by remember { mutableStateOf(false) }
    val rescanActive = viewModel.isRescanInProgress
    val rescanQueued = viewModel.isRescanQueued

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { isExpanded = !isExpanded }
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Info,
                contentDescription = null,
                modifier = Modifier.size(12.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = "WALLET DETAILS",
                style = MaterialTheme.typography.labelSmall,
                color = colorScheme.onSurface.copy(alpha = 0.6f),
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(
                enabled = !rescanActive,
                onClick = { viewModel.rescanFromBirthdayBlock() },
                modifier = Modifier.size(24.dp)
            ) {
                if (rescanActive) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = colorScheme.primary
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Scan wallet from Birthday Block",
                        modifier = Modifier.size(16.dp),
                        tint = colorScheme.onSurface.copy(alpha = 0.6f)
                    )
                }
            }
            Spacer(modifier = Modifier.width(4.dp))
            IconButton(
                onClick = onShowRestoreWalletDialog,
                modifier = Modifier.size(24.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.SwapHoriz,
                    contentDescription = "Change Wallet / Restore",
                    modifier = Modifier.size(16.dp),
                    tint = colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
            Icon(
                imageVector = if (isExpanded) Icons.Default.KeyboardArrowDown else Icons.Default.KeyboardArrowUp,
                contentDescription = if (isExpanded) "Collapse Wallet Details" else "Expand Wallet Details",
                modifier = Modifier.size(18.dp),
                tint = colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }

        if (rescanActive) {
            Row(
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (rescanQueued) Icons.Default.Schedule else Icons.Default.Sync,
                    contentDescription = null,
                    modifier = Modifier.size(14.dp),
                    tint = colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (rescanQueued) {
                        "Wallet is syncing; scan will start automatically"
                    } else {
                        "Scanning wallet from Birthday Block…"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface.copy(alpha = 0.75f)
                )
            }
        }

        if (isExpanded) {
            // Address
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Address", style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurface.copy(alpha = 0.7f))
                Text(
                    text = viewModel.myWalletAddress.collectAsStateWithLifecycle().value ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface
                )
            }

            // Seed
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Seed Phrase", style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurface.copy(alpha = 0.7f), modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        if (!showSeed) viewModel.fetchSeed()
                        showSeed = !showSeed
                    }, modifier = Modifier.size(24.dp)) {
                        Icon(
                            imageVector = if (showSeed) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle Seed Phrase Visibility",
                            modifier = Modifier.size(16.dp),
                            tint = colorScheme.onSurface.copy(alpha = 0.7f)
                        )
                    }
                }
                Text(
                    text = if (showSeed) viewModel.walletSeed else "••••••••••••••••••••••••••••••••",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface
                )
            }

            // Network Height
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Sync Height / Network", style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurface.copy(alpha = 0.7f))
                Text(
                    text = "${viewModel.walletHeight} / ${viewModel.daemonHeight}",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface
                )
            }

            // Balance
            Column(modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)) {
                Text("Balance", style = MaterialTheme.typography.labelSmall, color = colorScheme.onSurface.copy(alpha = 0.7f))
                Text(
                    text = "Total: ${viewModel.totalBalance} XMR",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface
                )
                Text(
                    text = "Unlocked: ${viewModel.currentBalance} XMR",
                    style = MaterialTheme.typography.bodySmall,
                    color = colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }
        }
    }
}

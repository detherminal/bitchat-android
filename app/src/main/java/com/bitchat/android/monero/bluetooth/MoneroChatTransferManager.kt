package com.bitchat.android.monero.bluetooth

import android.content.Context
import android.util.Base64
import android.util.Log
import androidx.lifecycle.viewModelScope
import com.bitchat.android.ui.ChatViewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import com.bitchat.android.monero.wallet.WalletSuite
import com.bitchat.android.monero.messaging.MoneroMessageHandler
import com.bitchat.android.monero.mesh.BitchatMoneroTransfer
import com.bitchat.android.monero.utils.MoneroValidator
import kotlin.coroutines.resume
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.Locale

/**
 * Unified MoneroChatTransferManager
 *
 * Combines:
 * - TxID-based and blob-based flows (legacy + new preferred)
 * - File/message transfer layer via BitchatMoneroTransfer
 * - ChatViewModel + system messages
 * - MoneroMessageHandler integration
 */
class MoneroChatTransferManager(
    private val context: Context,
    private val viewModel: ChatViewModel,
    private val walletSuite: WalletSuite,
    private var messageHandler: MoneroMessageHandler,
    private val sendMessageCallback: (peerID: String, content: String) -> Boolean
) {
    companion object {
        private const val TAG = "com.bitchat.MoneroChatTransferManager"
        private const val PENDING_LOOKUPS_PREFS = "monero_transaction_lookup"
        private const val PENDING_LOOKUPS_KEY = "pending"
    }

    // === Transaction flow modes (from v1) ===
    enum class TransactionFlowMode { TXID_BASED, BLOB_BASED, BOTH }
    private var flowMode: TransactionFlowMode = TransactionFlowMode.TXID_BASED

    // === File transfer layer (from curr) ===
    private val moneroTransfer = BitchatMoneroTransfer(context)
    private var isMoneroTransferInitialized = false

    private data class PendingIncomingLookup(
        val txId: String,
        val fromPeer: String,
        val announcedAmount: String,
        val destinationAddress: String,
        val announcedAt: Long,
        var attempt: Int = 0
    )

    private sealed class LookupResult {
        data class Found(
            val txId: String,
            val amount: Long,
            val confirmations: Long,
            val blockHeight: Long
        ) : LookupResult()

        data class NotFound(val txId: String) : LookupResult()
        data class Failed(val message: String) : LookupResult()
    }

    private val pendingLookups = ConcurrentHashMap<String, PendingIncomingLookup>()
    private val lookupJobs = ConcurrentHashMap<String, Job>()
    private val lookupPreferences = context.applicationContext.getSharedPreferences(
        PENDING_LOOKUPS_PREFS,
        Context.MODE_PRIVATE
    )
    private val lookupPersistenceLock = Any()

    init {
        loadPendingLookups()
        // Resuming here makes a transaction survive an activity/process restart;
        // the lookup loop waits quietly until the wallet becomes ready.
        resumePendingIncomingTransactions()
    }

    fun setTransactionFlowMode(mode: TransactionFlowMode) {
        flowMode = mode
        Log.d(TAG, "Transaction flow mode set to: $mode")
    }

    /** Resume any TxID announcements that were waiting when the app stopped. */
    fun resumePendingIncomingTransactions() {
        pendingLookups.values.forEach { pending ->
            startLookupLoop(pending, onSuccess = {}, onError = {})
        }
    }

    /** Stop active loops before the wallet/manager is replaced. */
    fun shutdown() {
        lookupJobs.values.forEach { it.cancel() }
        lookupJobs.clear()
    }

    // Add method to update message handler
    fun updateMessageHandler(handler: MoneroMessageHandler) {
        this.messageHandler = handler
        Log.d(TAG, "Message handler updated")
    }

    // ---------------- Initialization ----------------

    fun initialize() {
        Log.d(TAG, "Initializing MoneroChatTransferManager, current state=$isMoneroTransferInitialized")
        if (isMoneroTransferInitialized) return

        moneroTransfer.initialize(
            sendMessage = sendMessageCallback,
            callback = object : BitchatMoneroTransfer.MoneroTransferCallback {
                override fun onTransactionReceived(
                    transaction: BitchatMoneroTransfer.MoneroTransaction,
                    fromPeer: String
                ) {
                    handleReceivedTransaction(transaction, fromPeer)
                }
                override fun onTransactionSent(success: Boolean, txHash: String, toPeer: String) {
                    if (success) {
                        viewModel.addSystemMessage("✅ Transaction queued for $toPeer (TxID: $txHash)")
                    } else {
                        viewModel.addSystemMessage("⛔ Failed to send transaction to $toPeer")
                    }
                }
                override fun onTransferError(error: String, txId: String) {
                    viewModel.addSystemMessage("⛔ Transfer error: $error (txId=$txId)")
                }
                override fun onTransferProgress(txId: String, progress: Float) {
                    viewModel.addSystemMessage("📤 File transfer with $txId: $progress%")
                }
                override fun onPeerRequestedTransaction(fromPeer: String, txHash: String) {
                    viewModel.addSystemMessage("ℹ️ Transaction requested by peer: $fromPeer (txHash=$txHash)")
                }
            }
        )
        isMoneroTransferInitialized = true
    }

    // ---------------- Outgoing send ----------------

    fun handleMoneroSend(
        amount: String,
        selectedPrivatePeer: String?,
        canReceiveMonero: Boolean,
        receiverMoneroAddress: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (selectedPrivatePeer == null) {
            onError("No peer selected")
            return
        }
        if (!canReceiveMonero) {
            onError("Peer cannot receive Monero")
            return
        }

        when (flowMode) {
            TransactionFlowMode.TXID_BASED -> sendWithTxIdFlow(amount, selectedPrivatePeer, receiverMoneroAddress, onSuccess, onError)
            TransactionFlowMode.BLOB_BASED -> sendWithBlobFlow(amount, selectedPrivatePeer, receiverMoneroAddress, onSuccess, onError)
            TransactionFlowMode.BOTH -> sendWithTxIdFlow(
                amount, selectedPrivatePeer, receiverMoneroAddress, onSuccess
            ) { error ->
                Log.w(TAG, "TxID flow failed, fallback to blob: $error")
                sendWithBlobFlow(amount, selectedPrivatePeer, receiverMoneroAddress, onSuccess, onError)
            }
        }
    }

    // ---------------- TxID Flow (preferred) ----------------

    private fun sendWithTxIdFlow(
        amount: String,
        selectedPrivatePeer: String,
        receiverMoneroAddress: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (receiverMoneroAddress == null) {
            onError("Receiver address missing")
            return
        }

        val atomicAmount = WalletSuite.convertXmrToAtomic(amount)
        if (atomicAmount <= 0L) {
            onError("Invalid amount. Use up to 12 decimal places.")
            return
        }

        val cachedBalance = walletSuite.getBalanceValue()
        val cachedUnlockedBalance = walletSuite.getUnlockedBalanceValue()
        walletSuite.sendTransaction(receiverMoneroAddress, amount, cachedBalance, cachedUnlockedBalance, object : WalletSuite.TransactionCallback {
            override fun onSuccess(txId: String, amount: Long) {
                val msg = messageHandler.createTransactionIdMessage(
                    txId = txId,
                    amount = WalletSuite.convertAtomicToXmr(amount),
                    toAddress = receiverMoneroAddress
                )
                sendMessageCallback(selectedPrivatePeer, msg)
                onSuccess()
            }
            override fun onError(error: String) = onError(error)
        })
    }

    // ---------------- Blob Flow (legacy + file transfer) ----------------

    private fun sendWithBlobFlow(
        amount: String,
        selectedPrivatePeer: String,
        receiverMoneroAddress: String?,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (receiverMoneroAddress == null) {
            onError("Receiver address missing")
            return
        }
        if (WalletSuite.convertXmrToAtomic(amount) <= 0L) {
            onError("Invalid amount. Use up to 12 decimal places.")
            return
        }

        walletSuite.createTxBlob(receiverMoneroAddress, amount, object : WalletSuite.TxBlobCallback {
            override fun onSuccess(txId: String, base64Blob: String) {
                val transaction = BitchatMoneroTransfer.MoneroTransaction(
                    blob = base64Blob,
                    txHash = txId,
                    fee = null,
                    timestamp = System.currentTimeMillis(),
                    metadata = mapOf("amount" to amount, "destination" to receiverMoneroAddress)
                )
                sendViaMeshMessage(transaction, selectedPrivatePeer, onSuccess, onError)
            }
            override fun onError(error: String) = onError(error)
        })
    }

    private fun sendViaMeshMessage(
        transaction: BitchatMoneroTransfer.MoneroTransaction,
        peer: String,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        if (!isMoneroTransferInitialized) {
            onError("Monero message transport not initialized")
            return
        }
        viewModel.viewModelScope.launch {
            moneroTransfer.sendTransaction(transaction, peer, object : BitchatMoneroTransfer.MoneroTransferCallback {
                override fun onTransactionSent(success: Boolean, txHash: String, toPeer: String) {
                    if (success) onSuccess() else onError("Monero message could not be queued")
                }
                override fun onPeerRequestedTransaction(fromPeer: String, txHash: String) {}
                override fun onTransferError(error: String, txId: String) = onError(error)
                override fun onTransferProgress(txId: String, progress: Float) {}
                override fun onTransactionReceived(transaction: BitchatMoneroTransfer.MoneroTransaction, fromPeer: String) {}
            })
        }
    }

    // ---------------- Incoming Handling ----------------

    private fun handleReceivedTransaction(transaction: BitchatMoneroTransfer.MoneroTransaction, fromPeer: String) {
        moneroTransfer.saveAndSubmitTransaction(transaction, walletSuite,
            onSuccess = { txHash -> viewModel.addSystemMessage("✅ Submitted received tx: $txHash") },
            onError = { err -> viewModel.addSystemMessage("⛔ Failed to handle received tx: $err") })
    }

    fun handleIncomingTransactionId(txIdMessage: MoneroMessageHandler.TransactionIdMessage, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val txId = txIdMessage.txId.trim().lowercase(Locale.US)
        if (!MoneroValidator.isValidTransactionId(txId)) {
            onError("Invalid transaction ID received")
            return
        }

        val key = txId.lowercase()
        val existing = pendingLookups[key]
        if (existing != null) {
            Log.d(TAG, "Ignoring duplicate pending transaction announcement: $txId")
            return
        }

        val pending = PendingIncomingLookup(
            txId = txId,
            fromPeer = txIdMessage.fromUser,
            announcedAmount = txIdMessage.amount,
            destinationAddress = txIdMessage.toAddress,
            announcedAt = txIdMessage.timestamp.takeIf { it > 0L } ?: System.currentTimeMillis()
        )
        pendingLookups[key] = pending
        persistPendingLookups()
        viewModel.addSystemMessage("⏳ Transaction received. Waiting for the wallet to confirm it automatically…")
        startLookupLoop(pending, onSuccess, onError)
    }

    private fun startLookupLoop(
        pending: PendingIncomingLookup,
        onSuccess: () -> Unit,
        onError: (String) -> Unit
    ) {
        val key = pending.txId.lowercase()
        val job = viewModel.viewModelScope.launch(start = CoroutineStart.LAZY) {
            var waitingNoticeShown = false
            while (isActive && pendingLookups.containsKey(key)) {
                if (!walletSuite.isReady()) {
                    delay(1_000L)
                    continue
                }

                when (val result = lookupTransactionOnce(pending)) {
                    is LookupResult.Found -> {
                        pendingLookups.remove(key)
                        lookupJobs.remove(key)
                        persistPendingLookups()

                        val msg = messageHandler.createTransactionFoundMessage(
                            result.txId,
                            WalletSuite.convertAtomicToXmr(result.amount),
                            result.confirmations,
                            result.blockHeight
                        )
                        sendMessageCallback(pending.fromPeer, msg)
                        viewModel.addSystemMessage(
                            "✅ Incoming transaction found: ${WalletSuite.convertAtomicToXmr(result.amount)} XMR " +
                                "(${result.confirmations} confirmations)"
                        )
                        onSuccess()
                        return@launch
                    }

                    is LookupResult.NotFound -> {
                        Log.d(TAG, "Transaction not visible yet: ${result.txId}")
                    }

                    is LookupResult.Failed -> {
                        Log.w(TAG, "Automatic transaction lookup will retry: ${result.message}")
                    }
                }

                pending.attempt += 1
                persistPendingLookups()
                if (!waitingNoticeShown &&
                    System.currentTimeMillis() - pending.announcedAt >= MoneroLookupRetryPolicy.WAITING_NOTICE_AFTER_MS
                ) {
                    waitingNoticeShown = true
                    viewModel.addSystemMessage(
                        "⏳ The transaction is still pending on the wallet. " +
                            "It will keep checking automatically in the background."
                    )
                }
                delay(MoneroLookupRetryPolicy.delayBeforeNextAttempt(pending.attempt - 1))
            }
        }
        if (lookupJobs.putIfAbsent(key, job) != null) {
            job.cancel()
            return
        }
        job.start()
        job.invokeOnCompletion {
            lookupJobs.remove(key, job)
        }
    }

    private suspend fun lookupTransactionOnce(pending: PendingIncomingLookup): LookupResult =
        suspendCancellableCoroutine { continuation ->
            walletSuite.lookupTransactionById(
                pending.txId,
                pending.announcedAmount,
                pending.destinationAddress,
                object : WalletSuite.TransactionLookupCallback {
                    override fun onTransactionFound(txId: String, amount: Long, confirmations: Long, blockHeight: Long) {
                        if (continuation.isActive) {
                            continuation.resume(LookupResult.Found(txId, amount, confirmations, blockHeight))
                        }
                    }

                    override fun onTransactionNotFound(txId: String) {
                        if (continuation.isActive) continuation.resume(LookupResult.NotFound(txId))
                    }

                    override fun onError(error: String) {
                        if (continuation.isActive) continuation.resume(LookupResult.Failed(error))
                    }
                }
            )
        }

    private fun loadPendingLookups() {
        val encoded = lookupPreferences.getStringSet(PENDING_LOOKUPS_KEY, emptySet()).orEmpty()
        encoded.forEach { raw ->
            try {
                val json = JSONObject(raw)
                val txId = json.getString("tx_id").trim().lowercase(Locale.US)
                if (!MoneroValidator.isValidTransactionId(txId)) return@forEach
                val pending = PendingIncomingLookup(
                    txId = txId,
                    fromPeer = json.getString("from_peer"),
                    announcedAmount = json.optString("amount", "0"),
                    destinationAddress = json.optString("to_address", ""),
                    announcedAt = json.optLong("announced_at", System.currentTimeMillis()),
                    attempt = json.optInt("attempt", 0).coerceAtLeast(0)
                )
                pendingLookups[txId.lowercase()] = pending
            } catch (e: Exception) {
                Log.w(TAG, "Dropping malformed persisted transaction lookup", e)
            }
        }
    }

    private fun persistPendingLookups() {
        synchronized(lookupPersistenceLock) {
            // Snapshot and persist under one lock. Multiple lookup coroutines
            // can finish at the same time; without serialization an older
            // StringSet snapshot could overwrite a newer one before restart.
            val encoded = pendingLookups.values.map { pending ->
                JSONObject().apply {
                    put("tx_id", pending.txId.lowercase(Locale.US))
                    put("from_peer", pending.fromPeer)
                    put("amount", pending.announcedAmount)
                    put("to_address", pending.destinationAddress)
                    put("announced_at", pending.announcedAt)
                    put("attempt", pending.attempt)
                }.toString()
            }.toSet()
            lookupPreferences.edit().putStringSet(PENDING_LOOKUPS_KEY, encoded).apply()
        }
    }

    fun handleIncomingTransactionBlob(payment: MoneroMessageHandler.MoneroPaymentMessage, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val blob = payment.signedTxBlob
        if (blob == null) {
            onError("No transaction blob in payment message")
            return
        }
        walletSuite.importSignedTransactionBlob(blob, object : WalletSuite.TransactionImportCallback {
            override fun onSuccess(txId: String) {
                val msg = messageHandler.createPaymentStatusMessage(txId, "Imported and relayed")
                sendMessageCallback(payment.fromUser, msg)
                onSuccess()
            }
            override fun onError(error: String) = onError(error)
        })
    }

}

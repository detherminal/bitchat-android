package com.bitchat.android.monero.wallet;

import android.content.Context;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.util.Log;

import com.m2049r.xmrwallet.data.Node;
import com.m2049r.xmrwallet.model.PendingTransaction;
import com.m2049r.xmrwallet.model.Wallet;
import com.m2049r.xmrwallet.model.WalletListener;
import com.m2049r.xmrwallet.model.WalletManager;
import com.m2049r.xmrwallet.model.TransactionHistory;
import com.m2049r.xmrwallet.model.TransactionInfo;
import com.m2049r.xmrwallet.util.Helper;
import com.m2049r.xmrwallet.model.NetworkType;

import com.bitchat.android.monero.utils.MoneroMnemonic;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;
import java.util.Properties;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

import java.net.HttpURLConnection;
import java.net.URL;

public class WalletSuite {
    private static final String TAG = "WalletSuite";
    private static final String PROPERTIES_FILE = "wallet.properties";

    private static volatile boolean nativeOk = false;
    private static volatile boolean nativeChecked = false;
    private static volatile WalletSuite instance;
    
    // Sync configuration - adapted for slow networks
    private static final long SYNC_TIMEOUT_MS = 7200000; // 120 minutes (2 hours for full rescan)
    private static final long PERIODIC_SYNC_INTERVAL_MS = 30000; // Check for new blocks every 30 seconds
    private static final long RESCAN_PROGRESS_CHECK_INTERVAL_MS = 30000; // 30 seconds
    private static final long RESCAN_COOLDOWN_MS = 600000; // 10 minutes
    private static final int BALANCE_READ_ATTEMPTS = 4;
    private static final long BALANCE_READ_RETRY_DELAY_MS = 1000L;
    
    // Wallet state machine
    private enum WalletState {
        IDLE,           // Not syncing, ready for operations
        SYNCING,        // Normal sync in progress
        SYNC_FINALIZING,// Sync finished native refresh; balance/history are being persisted
        RESCANNING,     // Full rescan in progress - BLOCKS ALL OTHER OPERATIONS
        CLOSING,        // Shutdown in progress
        TRANSACTION,    // Transaction in progress
        LOOKING_UP      // Reading transaction history for automatic TxID handling
    }
    
    private final AtomicReference<WalletState> currentState = new AtomicReference<>(WalletState.IDLE);
    private final AtomicLong lastSyncStartTime = new AtomicLong(0);
    private final AtomicLong lastRescanTime = new AtomicLong(0);
    private final AtomicLong cachedDaemonHeight = new AtomicLong(-1);
    
    private volatile Wallet wallet;
    private boolean autoSyncEnabled = true;
    private final WalletManager walletManager;
    private final Context context;
    private final ExecutorService syncExecutor;
    private final ExecutorService executorService;
    private final ScheduledExecutorService periodicSyncScheduler;
    private final Handler mainHandler;
    private volatile boolean isInitialized = false;
    
    private volatile String walletAddress = null;
    private volatile String cachedSeed = null;
    private volatile long cachedWalletHeight = 0;
    private volatile String currentWalletPath;
    private final AtomicLong balance = new AtomicLong(0L);
    private final AtomicLong unlockedBalance = new AtomicLong(0L);
    
    private volatile WalletStatusListener statusListener;
    private volatile TransactionListener transactionListener;
    private volatile DaemonConfigCallback daemonConfigCallback;
    
    private ScheduledFuture<?> periodicSyncTask;
    private ScheduledFuture<?> currentSyncTimeout;
    
    private RescanCallback rescanCallback = null;
    
    private final Object walletLock = new Object();
    /**
     * Fences native wallet replacement/close against operations using the
     * current wallet. Readers may run concurrently with each other, but a
     * wallet replacement waits until every native operation has finished.
     */
    private final ReentrantReadWriteLock walletLifecycleLock = new ReentrantReadWriteLock(true);
    
    private volatile RescanBalanceCallback rescanBalanceCallback;
    private final AtomicBoolean queuedRescan = new AtomicBoolean(false);
    private final AtomicBoolean walletReplacementRequested = new AtomicBoolean(false);
    private final AtomicLong queuedRescanHeight = new AtomicLong(-1L);
    private volatile RescanCallback queuedRescanCallback;
    
    private volatile boolean transactionInProgress = false;
    private final Object transactionLock = new Object();
    private long transactionStartTime = 0;
    
    // Sync progress tracking
    private volatile long syncStartHeight = 0;
    private volatile long syncEndHeight = 0;
    private volatile long lastProgressUpdateTime = 0;
    
    
    public interface WalletStatusListener {
        void onWalletInitialized(boolean success, String message, String address);
        void onBalanceUpdated(long balance, long unlocked);
        void onSyncProgress(long height, long startHeight, long endHeight, double percentDone);
    }

    public interface TransactionListener {
        void onTransactionCreated(String txId, long amount);
        void onTransactionConfirmed(String txId);
        void onTransactionFailed(String txId, String error);
        void onOutputReceived(long amount, String txHash, boolean isConfirmed);
    }

    public interface AddressCallback {
        void onSuccess(String address);
        void onError(String error);
    }

    public interface BalanceCallback {
        void onSuccess(long balance, long unlocked);
        void onError(String error);
    }

    public interface SyncCallback {
        void onSuccess(String message);
        void onError(String error);
    }

    public interface TxBlobCallback {
        void onSuccess(String txId, String base64Blob);
        void onError(String error);
    }

    public interface FeeEstimateCallback {
        void onSuccess(long feeAtomic);
        void onError(String error);
    }
    
    public interface TransactionImportCallback {
        void onSuccess(String txId);
        void onError(String error);
    }
    
    public interface TransactionHistoryCallback {
        void onSuccess(List<TransactionInfo> transactions);
        void onError(String error);
    }    
    
    public interface TransactionCallback {
        void onSuccess(String txId, long amount);
        void onError(String error);
    }
    
    public interface RescanCallback {
        default void onStarted() {}
        void onComplete(long newBalance, long newUnlockedBalance);
        void onError(String error);
    }

    public enum RescanRequestResult {
        STARTED,
        QUEUED,
        REJECTED
    }

    public interface TransactionLookupCallback {
        void onTransactionFound(String txId, long amount, long confirmations, long blockHeight);
        void onTransactionNotFound(String txId);
        void onError(String error);
    }
    
    public interface RescanBalanceCallback {
        void onBalanceUpdated(long balance, long unlockedBalance);
    }
    
    public static class SyncStatus {
        public final boolean syncing;
        public final long walletHeight;
        public final long daemonHeight;
        public final double percentDone;

        public SyncStatus(boolean syncing, long walletHeight, long daemonHeight, double percentDone) {
            this.syncing = syncing;
            this.walletHeight = walletHeight;
            this.daemonHeight = daemonHeight;
            this.percentDone = percentDone;
        }
    }

    public interface DaemonConfigCallback {
        void onConfigNeeded();
        void onConfigError(String error);
    }
    
    private WalletSuite(Context context) {
        this.context = context.getApplicationContext();
        this.syncExecutor = Executors.newSingleThreadExecutor();
        this.executorService = Executors.newSingleThreadExecutor();
        this.periodicSyncScheduler = Executors.newSingleThreadScheduledExecutor();
        this.mainHandler = new Handler(Looper.getMainLooper());
        this.walletManager = WalletManager.getInstance();
        this.walletManager.setNetworkType(NetworkType.NetworkType_Mainnet);
        loadConfiguration();
        registerShutdownHandler();
    }
    
    public void setRescanBalanceCallback(RescanBalanceCallback callback) {
        this.rescanBalanceCallback = callback;
    }    
    
    public long getBalanceValue() {
        return balance.get();
    }

    public long getUnlockedBalanceValue() {
        return unlockedBalance.get();
    }    

    public String getSeed() {
        return cachedSeed != null ? cachedSeed : "";
    }

    public long getWalletHeight() {
        return cachedWalletHeight;
    }

    public long getDaemonHeight() {
        return getDaemonHeightViaHttp();
    }

    public long getBirthdayHeight() {
        long prefHeight = context.getSharedPreferences("monero_wallet_prefs", Context.MODE_PRIVATE)
                .getLong("birthday_height", -1);
        if (prefHeight >= 0) {
            return prefHeight;
        }
        if (wallet != null) {
            try {
                return wallet.getRestoreHeight();
            } catch (Exception ignored) {}
        }
        return 0;
    }

    public void saveBirthdayHeight(long height) {
        context.getSharedPreferences("monero_wallet_prefs", Context.MODE_PRIVATE)
                .edit()
                .putLong("birthday_height", Math.max(0, height))
                .apply();
    }

    public RescanRequestResult rescanFromHeight(long targetHeight, RescanCallback callback) {
        long height = Math.max(0, targetHeight);
        saveBirthdayHeight(height);

        if (!currentState.compareAndSet(WalletState.IDLE, WalletState.RESCANNING)) {
            WalletState state = currentState.get();
            if (state == WalletState.SYNCING || state == WalletState.SYNC_FINALIZING
                    || state == WalletState.TRANSACTION) {
                if (queuedRescan.compareAndSet(false, true)) {
                    queuedRescanHeight.set(height);
                    queuedRescanCallback = callback;
                    Log.i(TAG, "Rescan queued until wallet is idle (current state: " + state + ")");
                } else {
                    Log.i(TAG, "Rescan request already queued; ignoring duplicate request");
                }
                return RescanRequestResult.QUEUED;
            }

            if (state == WalletState.RESCANNING) {
                Log.i(TAG, "Rescan request ignored because a rescan is already in progress");
                return RescanRequestResult.QUEUED;
            }

            Log.w(TAG, "Cannot trigger rescan - state: " + state);
            if (callback != null) {
                mainHandler.post(() -> callback.onError("Wallet is closing"));
            }
            return RescanRequestResult.REJECTED;
        }

        this.rescanCallback = callback;
        if (callback != null) {
            mainHandler.post(callback::onStarted);
        }

        lastRescanTime.set(System.currentTimeMillis());
        stopPeriodicSync();

        syncExecutor.execute(() -> {
            try {
                String walletPath = (wallet != null) ? wallet.getPath() : currentWalletPath;
                if (walletPath == null || walletPath.isEmpty()) {
                    File dir = context.getDir("wallets", Context.MODE_PRIVATE);
                    walletPath = new File(dir, walletManager.getWalletName()).getAbsolutePath();
                }

                Log.d(TAG, "[1/5] Storing current wallet state...");
                try {
                    if (wallet != null) {
                        wallet.store(walletPath);
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to persist before rescan", e);
                }

                Log.d(TAG, "[2/5] Closing wallet...");
                if (wallet != null) {
                    try { wallet.pauseRefresh(); } catch (Exception ignored) {}
                    wallet.setListener(null);
                    wallet.close();
                    wallet = null;
                }
                Thread.sleep(1000);

                Log.d(TAG, "[3/5] Reopening wallet...");
                wallet = walletManager.openWallet(walletPath);
                if (wallet == null) {
                    Log.e(TAG, "✗ CRITICAL: Failed to reopen wallet - ABORTING");
                    currentState.set(WalletState.IDLE);
                    startPeriodicSync();
                    if (rescanCallback != null) {
                        final RescanCallback cb = rescanCallback;
                        rescanCallback = null;
                        mainHandler.post(() -> cb.onError("Failed to reopen wallet"));
                    }
                    return;
                }

                Log.i(TAG, "Setting restore height to: " + height);
                wallet.setRestoreHeight(height);
                wallet.store(walletPath);

                Log.d(TAG, "[4/5] Reconnecting to daemon...");
                try {
                    Node node = walletManager.createNodeFromConfig();
                    wallet.initJ(node.getAddress(), 0, node.getUsername(), node.getPassword(), node.isSsl(), false, "");
                } catch (Exception e) {
                    Log.w(TAG, "Daemon reconnection error", e);
                }

                Log.d(TAG, "[5/5] Rescanning blockchain from height " + height + "...");
                wallet.rescanBlockchainAsync();

                mainHandler.postDelayed(this::monitorRescanProgress, 5000);

            } catch (Exception e) {
                Log.e(TAG, "✗ Rescan failed", e);
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
                if (rescanCallback != null) {
                    final RescanCallback cb = rescanCallback;
                    rescanCallback = null;
                    mainHandler.post(() -> cb.onError("Rescan failed: " + e.getMessage()));
                }
            }
        });
        return RescanRequestResult.STARTED;
    }

    public void rescanFromBirthdayBlock(RescanCallback callback) {
        long bHeight = getBirthdayHeight();
        Log.i(TAG, "Triggering rescan from birthday block: " + bHeight);
        rescanFromHeight(bHeight, callback);
    }

    private void maybeStartQueuedRescan() {
        if (walletReplacementRequested.get()) {
            return;
        }
        if (!queuedRescan.compareAndSet(true, false)) {
            return;
        }

        long height = queuedRescanHeight.getAndSet(-1L);
        RescanCallback callback = queuedRescanCallback;
        queuedRescanCallback = null;
        if (height < 0) {
            return;
        }

        Log.i(TAG, "Starting queued rescan now that the wallet is idle");
        RescanRequestResult result = rescanFromHeight(height, callback);
        if (result == RescanRequestResult.REJECTED && callback != null) {
            mainHandler.post(() -> callback.onError("Wallet is closing"));
        }
    }

    public void changeWalletFromSeed(String seed, long restoreHeight, WalletStatusListener listener) {
        if (seed == null || seed.trim().isEmpty()) {
            if (listener != null) listener.onWalletInitialized(false, "Seed phrase is empty", null);
            return;
        }

        walletReplacementRequested.set(true);
        queuedRescan.set(false);
        queuedRescanHeight.set(-1L);
        queuedRescanCallback = null;
        stopPeriodicSync();
        this.statusListener = listener;

        // Reset state variables immediately so old wallet data is never served
        balance.set(0);
        unlockedBalance.set(0);
        cachedWalletHeight = Math.max(0, restoreHeight);
        walletAddress = "";
        cachedSeed = "";
        isInitialized = false;

        syncExecutor.execute(() -> {
            walletLifecycleLock.writeLock().lock();
            try {
                if (!waitForReplacementSafeState(30_000L)) {
                    Log.e(TAG, "Cannot replace wallet while a native operation is still active");
                    if (listener != null) {
                        mainHandler.post(() -> listener.onWalletInitialized(false,
                                "Wallet is busy. Please try restoring again after the current operation finishes.", null));
                    }
                    return;
                }

                Log.i(TAG, "=== CHANGING WALLET FROM SEED ===");
                Log.i(TAG, "Target restore height / birthday block: " + restoreHeight);

                if (wallet != null) {
                    try {
                        wallet.pauseRefresh();
                        wallet.setListener(null);
                        wallet.close();
                    } catch (Exception e) {
                        Log.w(TAG, "Error closing existing wallet", e);
                    }
                    wallet = null;
                }

                File dir = new File(context.getFilesDir(), "monero_wallets");
                if (!dir.exists() && !dir.mkdirs()) {
                    if (listener != null) mainHandler.post(() -> listener.onWalletInitialized(false, "Cannot create monero_wallets dir", null));
                    return;
                }

                String walletName = "restored_wallet_" + System.currentTimeMillis();
                String walletPath = new File(dir, walletName).getAbsolutePath();
                currentWalletPath = walletPath;

                context.getSharedPreferences("monero_wallet_prefs", Context.MODE_PRIVATE)
                        .edit()
                        .putString("active_wallet_path", walletPath)
                        .apply();

                Log.i(TAG, "Creating clean restored wallet at path: " + walletPath);

                long validRestoreHeight = Math.max(0, restoreHeight);
                // Always start 100 blocks before the stated birthday to ensure we don't
                // skip the block containing the output. The 100-block buffer is tiny cost.
                long scanStartHeight = validRestoreHeight > 100 ? validRestoreHeight - 100 : 0L;
                saveBirthdayHeight(scanStartHeight);


                MoneroMnemonic.SeedValidationResult validation = MoneroMnemonic.validateAndPrepareSeed(seed);
                if (!validation.isValid) {
                    Log.e(TAG, "✗ Seed validation failed: " + validation.errorMessage);
                    if (listener != null) {
                        mainHandler.post(() -> listener.onWalletInitialized(false, validation.errorMessage, null));
                    }
                    return;
                }

                String normalizedSeed = validation.normalizedSeed;
                int wordCount = normalizedSeed.split(" ").length;
                Log.i(TAG, "Normalized seed word count: " + wordCount);

                // Use the configured network and node for wallet recovery.
                Node restoreNode = walletManager.createNodeFromConfig();
                String daemonAddress = restoreNode.getAddress();
                walletManager.setDaemonAddressJ(daemonAddress);
                Log.i(TAG, "Restore network: " + walletManager.getNetworkType()
                        + ", daemon: " + daemonAddress
                        + ", ssl: " + restoreNode.isSsl());

                String spendKeyHex = MoneroMnemonic.mnemonicToHex(normalizedSeed);
                Log.i(TAG, "Derived secret spend key hex: " + (spendKeyHex.isEmpty() ? "empty" : "len=" + spendKeyHex.length()));

                // Step 1: Try recoveryWallet with 25-word mnemonic seed
                Log.i(TAG, "Step 1: Calling recoveryWallet via 25-word mnemonic seed at restore height: " + scanStartHeight + "...");
                wallet = walletManager.recoveryWallet(walletPath, normalizedSeed, scanStartHeight);

                // Step 2: Fallback to createWalletFromKeys via spendKeyHex if Step 1 returned null
                if (wallet == null) {
                    Log.w(TAG, "Step 1 returned null. Step 2: Calling createWalletFromKeys via spendKeyHex...");
                    if (!spendKeyHex.isEmpty()) {
                        wallet = walletManager.createWalletFromKeys(walletPath, "English", scanStartHeight, "", "", spendKeyHex);
                    }
                }

                if (wallet != null) {
                    // ── 1. Connect to daemon (ONCE – no second initJ later) ────────────────
                    try {
                        wallet.setRestoreHeight(scanStartHeight);
                        wallet.initJ(restoreNode.getAddress(), 0L,
                                restoreNode.getUsername(), restoreNode.getPassword(),
                                restoreNode.isSsl(), false, "");
                        Log.i(TAG, "✓ Restored wallet daemon connected: " + restoreNode.getAddress());
                    } catch (Exception ex) {
                        Log.w(TAG, "Failed initJ for restored wallet daemon node", ex);
                    }

                    try {
                        walletAddress = wallet.getAddress();
                        Log.i(TAG, "✓ Restored Wallet Address: " + walletAddress);
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to get restored wallet address", e);
                    }

                    isInitialized = true;

                    if (listener != null) {
                        final String addr = walletAddress != null ? walletAddress : "";
                        mainHandler.post(() -> listener.onWalletInitialized(true, "Wallet successfully restored – scanning…", addr));
                    }

                    // ── 2. INLINE blocking refresh (same thread, immediately after initJ) ──
                    // Run refresh inline immediately after the first initJ call. A second
                    // initialization can update the native chain height before the historical
                    // scan starts and cause the restored balance to remain zero.
                    Log.i(TAG, "=== INLINE SEED-RESTORE SYNC STARTED ===");
                    currentState.set(WalletState.SYNCING);
                    long syncStart = System.currentTimeMillis();
                    try {
                        // Attach live listener so block height updates continuously in UI
                        final long startScanH = scanStartHeight;
                        syncStartHeight = startScanH;
                        syncEndHeight = getDaemonHeight();
                        WalletListener restoreProgressListener = new WalletListener() {
                            @Override public void moneySent(String txId, long amount) {}
                            @Override public void moneyReceived(String txId, long amount) {}
                            @Override public void unconfirmedMoneyReceived(String txId, long amount) {}
                            @Override public void newBlock(long height) {
                                cachedWalletHeight = height;
                                long currentTime = System.currentTimeMillis();
                                if (currentTime - lastProgressUpdateTime > 500) {
                                    lastProgressUpdateTime = currentTime;
                                    long target = syncEndHeight > startScanH ? syncEndHeight : getDaemonHeight();
                                    double pct = target > startScanH
                                        ? Math.min(99.9, (100.0 * (height - startScanH) / (target - startScanH)))
                                        : 100.0;
                                    if (target - height <= 3) pct = 100.0;
                                    final double finalPct = pct;
                                    final long h = height;
                                    final long t = target;
                                    if (statusListener != null) {
                                        mainHandler.post(() -> statusListener.onSyncProgress(h, startScanH, t, finalPct));
                                    }
                                }
                            }
                            @Override public void updated() {}
                            @Override public void refreshed() {}
                        };
                        wallet.setListener(restoreProgressListener);
                        Log.d(TAG, "Starting inline wallet.refresh() for seed restore with live listener…");
                        boolean ok = wallet.refresh();
                        long elapsed = (System.currentTimeMillis() - syncStart) / 1000;

                        // Force transaction history rebuild before reading balance.
                        // The native wallet2 C++ layer can transiently return 0 from
                        // getBalance() immediately after refresh() because the internal
                        // transfer list hasn't been fully rebuilt yet. Calling
                        // history.refresh() forces it to materialize.
                        try {
                            TransactionHistory history = wallet.getHistory();
                            if (history != null) {
                                history.refresh();
                                java.util.List<com.m2049r.xmrwallet.model.TransactionInfo> txs = history.getAll();
                                int count = txs != null ? txs.size() : 0;
                                Log.d(TAG, "Transaction history refreshed, tx count: " + count);
                                if (txs != null) {
                                    for (com.m2049r.xmrwallet.model.TransactionInfo tx : txs) {
                                        Log.d(TAG, "TX " + tx.hash + " | Amount: " + tx.amount + " | Dir: " + tx.direction + " | Block: " + tx.blockheight);
                                    }
                                }
                            }
                        } catch (Exception histEx) {
                            Log.w(TAG, "History refresh after seed-restore failed", histEx);
                        }

                        // Persist state to disk BEFORE getting balance.
                        // In some monero-core implementations, balance is fully computed
                        // only after the transfer history is materialized and stored.
                        try {
                            boolean stored = wallet.store();
                            Log.d(TAG, "Inline sync wallet stored before balance check: " + stored);
                        } catch (Exception e) {
                            Log.e(TAG, "Exception storing wallet before balance check", e);
                        }

                        long[] stableBalance = readStableBalanceAfterRefresh();
                        long finalBal = stableBalance[0];
                        long finalUnlocked = stableBalance[1];
                        long finalHeight   = wallet.getBlockChainHeight();
                        Log.i(TAG, "✓ Inline seed-restore sync done: ok=" + ok
                                + " time=" + elapsed + "s"
                                + " height=" + finalHeight
                                + " balance=" + finalBal + " atomic");

                        // Store the stable snapshot before notifying the UI. Do not read the
                        // native balance again from a listener callback: wallet2 can briefly
                        // expose zero while rebuilding its transfer cache after refresh().
                        balance.set(finalBal);
                        unlockedBalance.set(finalUnlocked);
                        cachedWalletHeight = finalHeight;
                        syncStartHeight = scanStartHeight;
                        Log.i(TAG, "✓ Balance stored: " + finalBal + " atomic (" + finalUnlocked + " unlocked)");

                        if (listener != null) {
                            final long b = finalBal, u = finalUnlocked;
                            mainHandler.post(() -> listener.onBalanceUpdated(b, u));
                        }

                        // Fetch real daemon height to compute accurate sync progress.
                        // Using finalHeight as endHeight would always yield 100%,
                        // hiding the fact that the wallet may still be behind.
                        long realDaemonHeight = getDaemonHeightViaHttp();
                        if (realDaemonHeight <= 0) realDaemonHeight = finalHeight;
                        syncEndHeight = realDaemonHeight;
                        double realPercent = realDaemonHeight > 0
                                ? Math.min(100.0, (100.0 * finalHeight / realDaemonHeight))
                                : 100.0;
                        Log.i(TAG, "Seed-restore sync progress: height=" + finalHeight
                                + " daemon=" + realDaemonHeight
                                + " percent=" + String.format("%.1f", realPercent));

                        // Notify UI with real progress so height display is accurate
                        if (statusListener != null) {
                            final long h = finalHeight;
                            final long dh = realDaemonHeight;
                            final double pct = realPercent;
                            mainHandler.post(() -> {
                                statusListener.onSyncProgress(h, scanStartHeight, dh, pct);
                                statusListener.onBalanceUpdated(finalBal, finalUnlocked);
                            });
                        }

                        // Persist wallet state
                        try { wallet.store(walletPath); } catch (Exception ignored) {}
                    } catch (Exception ex) {
                        Log.e(TAG, "✗ Inline seed-restore refresh exception", ex);
                    } finally {
                        currentState.set(WalletState.IDLE);
                    }

                    // The restore refresh has completed and the balance snapshot has been
                    // materialized. Resume the normal scheduler; an immediate second refresh
                    // can race the native wallet cache just after restoration.
                    startPeriodicSync();
                } else {
                    String error = "Native wallet recovery failed. Please check seed phrase and height.";
                    Log.e(TAG, "✗ Wallet recovery failed: " + error);
                    if (listener != null) {
                        mainHandler.post(() -> listener.onWalletInitialized(false, "Recovery failed: " + error, null));
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ Exception during wallet recovery from seed", e);
                if (listener != null) {
                    final String err = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    mainHandler.post(() -> listener.onWalletInitialized(false, "Error: " + err, null));
                }
            } finally {
                walletReplacementRequested.set(false);
                walletLifecycleLock.writeLock().unlock();
            }
        });
    }

    public static synchronized WalletSuite getInstance(Context context) {
        if (instance == null) {
            instance = new WalletSuite(context);
            if (!nativeAvailable()) {
                Log.e(TAG, "Failed to load native library monerujo");
            }
        }
        return instance;
    }

    public static synchronized void resetInstance(Context context) {
        if (instance != null) {
            try {
                instance.close();
            } catch (Exception ignored) {
            }
            instance = null;
        }
        instance = new WalletSuite(context);
    }

    public String getCachedAddress() {
        return walletAddress;
    }

    private void registerShutdownHandler() {
        try {
            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                Log.d(TAG, "Shutdown hook triggered");
                closeWalletSync();
            }));
        } catch (Exception e) {
            Log.w(TAG, "Could not register shutdown hook", e);
        }
    }

    private void closeWalletSync() {
        currentState.set(WalletState.CLOSING);

        walletLifecycleLock.writeLock().lock();
        try {
            if (wallet != null && isInitialized) {
                Log.d(TAG, "Closing wallet synchronously");
                
                wallet.setListener(null);
                
                if (currentWalletPath != null) {
                    try {
                        wallet.store(currentWalletPath);
                        Log.d(TAG, "Wallet persisted before close");
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to persist wallet during shutdown", e);
                    }
                }
                
                try { wallet.pauseRefresh(); } catch (Exception ignored) {}
                wallet.close();
                Log.d(TAG, "Wallet closed successfully");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error during wallet closure", e);
        } finally {
            wallet = null;
            isInitialized = false;
            currentWalletPath = null;
            walletLifecycleLock.writeLock().unlock();
        }
    }
    
    public void close() {
        Log.i(TAG, "=== SHUTDOWN INITIATED ===");
        
        stopPeriodicSync();
        
        if (currentSyncTimeout != null) {
            currentSyncTimeout.cancel(false);
        }
        
        closeWalletSync();

        periodicSyncScheduler.shutdown();
        syncExecutor.shutdown();
        
        try {
            if (!syncExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                Log.w(TAG, "Sync executor did not terminate, forcing shutdown");
                syncExecutor.shutdownNow();
            }
            if (!periodicSyncScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                periodicSyncScheduler.shutdownNow();
            }
        } catch (InterruptedException ie) {
            syncExecutor.shutdownNow();
            periodicSyncScheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
        
        Log.i(TAG, "=== SHUTDOWN COMPLETE ===");
    }

    /**
     * Starts periodic sync with a short initial delay (15s) for post-restore follow-up.
     * This ensures the wallet re-checks balance quickly after a seed restore
     * where the inline refresh() may have returned a transient 0 balance.
     */
    private void startPeriodicSyncImmediate() {
        if (!autoSyncEnabled) {
            Log.i(TAG, "Auto sync disabled, skipping periodic sync schedule");
            return;
        }

        if (periodicSyncTask != null && !periodicSyncTask.isDone()) {
            periodicSyncTask.cancel(false);
            periodicSyncTask = null;
        }

        long initialDelayMs = 15000; // 15 seconds
        Log.i(TAG, "=== STARTING FAST FOLLOW-UP SYNC (initial delay: " + (initialDelayMs / 1000) + "s, then every " + (PERIODIC_SYNC_INTERVAL_MS / 1000) + "s) ===");

        periodicSyncTask = periodicSyncScheduler.scheduleWithFixedDelay(() -> {
            if (!isInitialized) {
                Log.d(TAG, "⏭ Skipping periodic sync - wallet not initialized");
                return;
            }
            WalletState state = currentState.get();
            if (state != WalletState.IDLE) {
                Log.d(TAG, "⏭ Skipping periodic sync - state: " + state);
                return;
            }
            if (currentState.get() != WalletState.IDLE) {
                Log.w(TAG, "Skipping follow-up sync check - state: " + currentState.get());
                return;
            }
            Log.i(TAG, "⏰ Post-restore block check triggered");
            performPeriodicSyncIfNeeded();
        }, initialDelayMs, PERIODIC_SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);

        Log.i(TAG, "\u2713 Fast follow-up sync scheduler started");
    }

    /**
     * Starts the periodic sync scheduler
     */
    private void startPeriodicSync() {
        if (!autoSyncEnabled) {
            Log.i(TAG, "Auto sync disabled, skipping periodic sync schedule");
            return;
        }

        if (periodicSyncTask != null && !periodicSyncTask.isDone()) {
            Log.d(TAG, "Periodic sync already scheduled");
            return;
        }

        Log.i(TAG, "=== STARTING PERIODIC BLOCK CHECK (interval: " + (PERIODIC_SYNC_INTERVAL_MS / 1000) + "s) ===");

        periodicSyncTask = periodicSyncScheduler.scheduleWithFixedDelay(() -> {
            if (!isInitialized) {
                Log.d(TAG, "⏭ Skipping periodic sync - wallet not initialized");
                return;
            }
            
            WalletState state = currentState.get();
            if (state != WalletState.IDLE) {
                Log.d(TAG, "⏭ Skipping periodic sync - state: " + state);
                return;
            }
            
            // Check if previous sync is still running
            if (currentState.get() != WalletState.IDLE) {
                Log.w(TAG, "Skipping periodic sync check - state: " + currentState.get());
                return;
            }
            
            Log.i(TAG, "⏰ Periodic block check triggered");
            performPeriodicSyncIfNeeded();
            
        }, PERIODIC_SYNC_INTERVAL_MS, PERIODIC_SYNC_INTERVAL_MS, TimeUnit.MILLISECONDS);

        Log.i(TAG, "✓ Periodic sync scheduler started");
    }

    private void stopPeriodicSync() {
        if (periodicSyncTask != null) {
            periodicSyncTask.cancel(false);
            periodicSyncTask = null;
            Log.i(TAG, "✓ Periodic sync scheduler stopped");
        }
    }

    /**
     * Poll the daemon height cheaply and perform the native wallet refresh only
     * when at least one new block is available. This keeps the 30-second block
     * detection responsive without refreshing the wallet unnecessarily when the
     * chain has not advanced.
     */
    private void performPeriodicSyncIfNeeded() {
        if (!isInitialized || wallet == null) {
            return;
        }

        if (!currentState.compareAndSet(WalletState.IDLE, WalletState.LOOKING_UP)) {
            Log.d(TAG, "Skipping periodic block check - state: " + currentState.get());
            return;
        }

        boolean newBlocksAvailable = false;
        try {
            long daemonHeight = getDaemonHeightViaHttp();
            long walletHeight = wallet.getBlockChainHeight();
            cachedWalletHeight = walletHeight;

            if (daemonHeight <= 0) {
                Log.w(TAG, "Skipping periodic block check: daemon height unavailable");
                return;
            }

            if (walletHeight >= daemonHeight) {
                Log.d(TAG, "No new blocks (wallet=" + walletHeight + ", daemon=" + daemonHeight + ")");
                return;
            }

            Log.i(TAG, "New blocks detected (wallet=" + walletHeight + ", daemon=" + daemonHeight + ")");
            newBlocksAvailable = true;
        } catch (Exception e) {
            Log.w(TAG, "Periodic block check failed; will retry", e);
        } finally {
            currentState.compareAndSet(WalletState.LOOKING_UP, WalletState.IDLE);
        }

        if (newBlocksAvailable) {
            performSync();
        }
    }

    public void stopSync() {
        Log.i(TAG, "User requested to stop sync");
        autoSyncEnabled = false;
        stopPeriodicSync();

        if (wallet != null) {
            try {
                wallet.pauseRefresh();
                Log.i(TAG, "✓ Native wallet refresh paused");
            } catch (Exception e) {
                Log.w(TAG, "Failed to pause native refresh", e);
            }
        }

        // The sync worker owns the state transition. Releasing it here while
        // wallet.refresh() is still on the native stack creates a wallet race.
        if (currentState.get() == WalletState.SYNCING) {
            Log.i(TAG, "Sync stop requested; waiting for the worker to finish safely");
        }
    }

    public void forceManualSync() {
        Log.i(TAG, "User requested manual sync");
        if (currentState.get() == WalletState.IDLE) {
            performSync();
        } else {
            Log.w(TAG, "Cannot force manual sync, current state is: " + currentState.get());
        }
    }

    /**
     * Main sync entry point - uses BLOCKING refresh for reliability
     */
    private void performSync() {
        if (walletReplacementRequested.get()) {
            Log.i(TAG, "Skipping sync because wallet replacement is pending");
            return;
        }
        // State check with atomic compare-and-set
        if (!currentState.compareAndSet(WalletState.IDLE, WalletState.SYNCING)) {
            WalletState state = currentState.get();
            Log.w(TAG, "Cannot start sync - current state: " + state);
            return;
        }
        
        lastSyncStartTime.set(System.currentTimeMillis());
        
        Log.i(TAG, "=== SYNC STARTED ===");
        
        // Schedule timeout as safety net
        mainHandler.post(() -> {
            if (currentSyncTimeout != null) {
                currentSyncTimeout.cancel(false);
            }
            currentSyncTimeout = periodicSyncScheduler.schedule(() -> {
                if (currentState.get() == WalletState.SYNCING) {
                    Log.e(TAG, "🚨 SYNC TIMEOUT - operation hung for " + (SYNC_TIMEOUT_MS / 60000) + " minutes");
                    // Do not release the state here: refresh() may still be using
                    // the native wallet. Ask it to stop and let the worker thread
                    // own the final transition after refresh() returns.
                    try {
                        if (wallet != null) wallet.pauseRefresh();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to pause timed-out wallet refresh", e);
                    }
                }
            }, SYNC_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        });
        
        // Execute sync on dedicated thread - use BLOCKING version for reliability
        syncExecutor.execute(this::executeSyncOperationBlocking);
    }
    
    /**
     * RELIABLE SYNC: Uses blocking refresh() instead of refreshAsync()
     */
    private void executeSyncOperationBlocking() {
        WalletListener syncListener = null;
        
        try {
            // Validate daemon connection. Seed-restore syncs are done inline in
            // changeWalletFromSeed and never reach this path, so this guard is safe.
            if (!validateDaemonConnection()) {
                Log.e(TAG, "Daemon validation failed");
                completeSyncOperation(false);
                return;
            }

            
            // Get sync heights for progress tracking
            final long rawWalletHeight = wallet.getBlockChainHeight();
            long prefBirthday = context.getSharedPreferences("monero_wallet_prefs", Context.MODE_PRIVATE)
                    .getLong("birthday_height", -1);
            final long restoreHeight = prefBirthday >= 0 ? prefBirthday : wallet.getRestoreHeight();

            // If balance is 0 and a valid restore height exists, start sync from restore height
            final long walletHeight;
            if (restoreHeight > 0 && readWalletBalance()[0] == 0) {
                walletHeight = restoreHeight;
            } else if (rawWalletHeight > 1) {
                walletHeight = rawWalletHeight;
            } else {
                walletHeight = restoreHeight > 0 ? restoreHeight : 1;
            }
            final long daemonHeight = getDaemonHeightViaHttp();
            
            syncStartHeight = walletHeight;
            syncEndHeight = daemonHeight;
            
            Log.i(TAG, "Sync range: " + walletHeight + " → " + daemonHeight + " (raw=" + rawWalletHeight + ", restore=" + restoreHeight + ")");
            Log.i(TAG, "Blocks to sync: " + (daemonHeight - walletHeight));
            
            if (statusListener != null) {
                double initialPercent = daemonHeight > 0 ? Math.min(100.0, (100.0 * walletHeight / daemonHeight)) : 0.0;
                mainHandler.post(() -> statusListener.onSyncProgress(walletHeight, walletHeight, daemonHeight, initialPercent));
            }

            // Set up listener for sync progress
            syncListener = new WalletListener() {
                @Override
                public void moneySent(String txId, long amount) {
                    Log.d(TAG, "[SYNC] moneySent: " + txId);
                }

                @Override
                public void moneyReceived(String txId, long amount) {
                    Log.d(TAG, "[SYNC] moneyReceived: " + txId + ", amount: " + amount);
                }

                @Override
                public void unconfirmedMoneyReceived(String txId, long amount) {
                    Log.d(TAG, "[SYNC] unconfirmedMoneyReceived: " + txId);
                }

                @Override
                public void newBlock(long height) {
                    cachedWalletHeight = height;
                    long currentTime = System.currentTimeMillis();
                    if (currentTime - lastProgressUpdateTime > 1000) {
                        lastProgressUpdateTime = currentTime;
                        
                        double percentDone;
                        if (syncEndHeight <= syncStartHeight || height >= syncEndHeight || (syncEndHeight - height) <= 3) {
                            percentDone = 100.0;
                        } else {
                            percentDone = Math.min(99.9, (100.0 * (height - syncStartHeight) / (syncEndHeight - syncStartHeight)));
                        }
                        
                        Log.d(TAG, "[SYNC] Block: " + height + " (" + String.format("%.1f", percentDone) + "%)");
                        
                        if (statusListener != null) {
                            final long currentHeight = height;
                            final double finalPercent = percentDone;
                            mainHandler.post(() -> statusListener.onSyncProgress(currentHeight, syncStartHeight, syncEndHeight, finalPercent));
                        }
                    }
                }
                
                @Override
                public void updated() {
                }

                @Override
                public void refreshed() {
                }
            };

            // Attach live listener before calling wallet.refresh() so newBlock progress callbacks update UI live
            wallet.setListener(syncListener);
            
            long startTime = System.currentTimeMillis();
            
            Log.d(TAG, "Starting BLOCKING wallet.refresh()...");
            
            // CRITICAL: Use BLOCKING refresh for reliability
            boolean refreshSuccess = wallet.refresh();
            
            long duration = System.currentTimeMillis() - startTime;
            long heightAfter = wallet.getBlockChainHeight();
            cachedWalletHeight = heightAfter; // Keep cached height current for getWalletHeight()
            long blocksProcessed = heightAfter - walletHeight;
            
            Log.i(TAG, "✓ Blocking sync completed:");
            Log.i(TAG, "  Duration: " + (duration / 1000) + "s");
            Log.i(TAG, "  Blocks processed: " + blocksProcessed);
            Log.i(TAG, "  Final height: " + heightAfter);
            
            // Final balance update
            try {
                TransactionHistory history = wallet.getHistory();
                if (history != null) {
                    history.refresh();
                    java.util.List<com.m2049r.xmrwallet.model.TransactionInfo> txs = history.getAll();
                    int count = txs != null ? txs.size() : 0;
                    Log.d(TAG, "Transaction history refreshed, tx count: " + count);
                    if (txs != null) {
                        for (com.m2049r.xmrwallet.model.TransactionInfo tx : txs) {
                            Log.d(TAG, "TX " + tx.hash + " | Amount: " + tx.amount + " | Dir: " + tx.direction + " | Block: " + tx.blockheight);
                        }
                    }
                }
            } catch (Exception histEx) {
                Log.w(TAG, "History refresh after background sync failed", histEx);
            }
            updateBalanceFromWallet();

            // Read the native balance before persisting. In this JNI build store()
            // can invalidate wallet2's in-memory transfer cache; persisting first
            // makes a wallet with a real incoming transaction look empty until the
            // next full restore.
            try {
                boolean stored = wallet.store();
                Log.d(TAG, "Background sync wallet stored after balance check: " + stored);
            } catch (Exception e) {
                Log.e(TAG, "Exception storing wallet after balance check", e);
            }

            if (!refreshSuccess) {
                Log.w(TAG, "Blocking sync returned false (network error or timeout)");
            }
            
            completeSyncOperation(refreshSuccess);
            
        } catch (Exception e) {
            Log.e(TAG, "✗ Blocking sync exception", e);
            completeSyncOperation(false);
        } finally {
            // Clean up listener
            if (syncListener != null) {
                wallet.setListener(null);
            }
            // Release the wallet only after native listener cleanup has finished.
            // A new send/history operation must not start in between.
            currentState.compareAndSet(WalletState.SYNC_FINALIZING, WalletState.IDLE);
            maybeStartQueuedRescan();
        }
    }

    /**
     * Updates balance from wallet and notifies listeners
     */
    private void updateBalanceFromWallet() {
        if (wallet == null) return;
        
        try {
            long[] snapshot = readWalletBalance();
            long bal = snapshot[0];
            long unl = snapshot[1];

            balance.set(bal);
            unlockedBalance.set(unl);
            
            Log.d(TAG, "Balance updated - Balance: " + convertAtomicToXmr(bal) +
                  " XMR (" + bal + " atomic), Unlocked: " + convertAtomicToXmr(unl) + " XMR");

            // Notify listeners on main thread
            if (statusListener != null) {
                final long finalBal = bal;
                final long finalUnl = unl;
                mainHandler.post(() -> statusListener.onBalanceUpdated(finalBal, finalUnl));
            }
        } catch (Exception e) {
            Log.w(TAG, "Error updating balance during sync", e);
        }
    }

    /**
     * Reads the balance after a blocking refresh while allowing wallet2's native
     * transfer cache a few moments to settle. A monotonic snapshot prevents a
     * transient zero read from replacing a balance already observed in this
     * restore operation.
     */
    private long[] readStableBalanceAfterRefresh() {
        long bestBalance = balance.get();
        long bestUnlocked = unlockedBalance.get();

        for (int attempt = 1; attempt <= BALANCE_READ_ATTEMPTS; attempt++) {
            try {
                if (wallet == null) break;

                long[] snapshot = readWalletBalance();
                long currentBalance = snapshot[0];
                long currentUnlocked = snapshot[1];
                Log.d(TAG, "Restore balance read " + attempt + "/" + BALANCE_READ_ATTEMPTS
                        + ": balance=" + currentBalance + ", unlocked=" + currentUnlocked);

                if (currentBalance > bestBalance
                        || (currentBalance == bestBalance && currentUnlocked > bestUnlocked)) {
                    bestBalance = currentBalance;
                    bestUnlocked = currentUnlocked;
                }

                if (currentBalance > 0 || (bestBalance > 0 && attempt > 1)) {
                    break;
                }
            } catch (Throwable t) {
                Log.w(TAG, "Restore balance read failed on attempt " + attempt, t);
            }

            if (attempt < BALANCE_READ_ATTEMPTS) {
                try {
                    Thread.sleep(BALANCE_READ_RETRY_DELAY_MS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        return new long[] { bestBalance, bestUnlocked };
    }

    /**
     * Reads both wallet balance APIs. This JNI build can return zero from the
     * account-less overload after reopening a wallet even though the explicit
     * account-0 overload has the correct transfer balance.
     */
    private long[] readWalletBalance() {
        if (wallet == null) return new long[] { 0L, 0L };

        long bal = wallet.getBalance();
        long unl = wallet.getUnlockedBalance();
        if (bal == 0L) {
            long accountBal = wallet.getBalance(wallet.accountIndex);
            long accountUnl = wallet.getUnlockedBalance(wallet.accountIndex);
            Log.d(TAG, "Balance fallback (account " + wallet.accountIndex + "): balance="
                    + accountBal + ", unlocked=" + accountUnl);
            if (accountBal > bal || accountUnl > unl) {
                bal = accountBal;
                unl = accountUnl;
            }
        }
        return new long[] { bal, unl };
    }


    /**
     * Completes sync operation and updates state
     */
    private void completeSyncOperation(boolean success) {
        // Only the sync worker may enter finalization. In particular, the timeout
        // path must not release the state while native refresh() is still running.
        if (!currentState.compareAndSet(WalletState.SYNCING, WalletState.SYNC_FINALIZING)) {
            Log.d(TAG, "Sync already completed or interrupted");
            return;
        }
        
        // Cancel timeout
        if (currentSyncTimeout != null) {
            currentSyncTimeout.cancel(false);
            currentSyncTimeout = null;
        }
        
        long duration = System.currentTimeMillis() - lastSyncStartTime.get();
        Log.i(TAG, "=== SYNC COMPLETED (success=" + success + ", duration=" + (duration / 1000) + "s) ===");
        
        try {
            // The blocking refresh above already owns the native wallet. Do not
            // start a second refreshAsync() here: it can outlive this method and
            // race the next send/history operation. History and balance were
            // materialized before this finalization step.
            if (success) {
                updateBalanceFromWallet();
            }
            
            // Get final sync status and update cached height
            long walletHeight = wallet.getBlockChainHeight();
            cachedWalletHeight = walletHeight; // Keep cached height current for getWalletHeight()
            long daemonHeight = getDaemonHeightViaHttp();
            
            // Always send 100% on sync completion so UI exits syncing state cleanly
            double percent = 100.0;
            long finalDaemonHeight = Math.max(walletHeight, daemonHeight);
            
            Log.i(TAG, "Final sync status:");
            Log.i(TAG, "  Wallet height: " + walletHeight);
            Log.i(TAG, "  Daemon height: " + finalDaemonHeight);
            Log.i(TAG, "  Progress: " + String.format("%.2f%%", percent));
            Log.i(TAG, "  Balance: " + convertAtomicToXmr(balance.get()) + " XMR (" + balance.get() + " atomic)");
            Log.i(TAG, "  Unlocked: " + convertAtomicToXmr(unlockedBalance.get()) + " XMR (" + unlockedBalance.get() + " atomic)");
            
            // Final progress notification with guaranteed 100.0% completion
            if (statusListener != null) {
                mainHandler.post(() -> {
                    statusListener.onSyncProgress(walletHeight, syncStartHeight, finalDaemonHeight, 100.0);
                    statusListener.onBalanceUpdated(balance.get(), unlockedBalance.get());
                });
            }
            
            // Check if rescan needed (only after successful sync to daemon height)
            if (success && percent >= 99.0) {
                checkAndTriggerRescan(walletHeight, daemonHeight);
            }
            
            // Persist wallet state
            persistWallet();
            
        } catch (Exception e) {
            Log.e(TAG, "Error in sync completion", e);
        }
    }
    

    /**
     * Forces wallet to recalculate balance from scratch
     * Call this after major operations like sync completion
     */
    private void forceBalanceRefresh() {
        if (wallet == null) return;
        
        syncExecutor.execute(() -> {
            try {
                Log.d(TAG, "Forcing balance refresh...");
                
                // Store current state
                wallet.store();
                
                // Force wallet to reload transaction cache
                TransactionHistory history = wallet.getHistory();
                if (history != null) {
                    history.refresh();
                    int txCount = history.getAll() != null ? history.getAll().size() : 0;
                    Log.d(TAG, "Transaction count: " + txCount);
                }
                
                // Now get balance - should be accurate
                long[] snapshot = readWalletBalance();
                long bal = snapshot[0];
                long unl = snapshot[1];
                
                balance.set(bal);
                unlockedBalance.set(unl);
                
                Log.i(TAG, "✓ Balance refresh complete: " + convertAtomicToXmr(bal) + " XMR (unlocked: " + convertAtomicToXmr(unl) + ")");
                
                if (statusListener != null) {
                    mainHandler.post(() -> statusListener.onBalanceUpdated(bal, unl));
                }
                
            } catch (Exception e) {
                Log.e(TAG, "Failed to force balance refresh", e);
            }
        });
    }    

    /**
     * Checks if rescan is needed based on sync results
     */
    private void checkAndTriggerRescan(long walletHeight, long daemonHeight) {
        // Check if wallet is significantly behind daemon
        long heightDiff = daemonHeight - walletHeight;
        if (heightDiff > 1000) {
            Log.w(TAG, "⚠️ Large height difference detected: " + heightDiff + " blocks");
            
            // Check if we have transactions but zero balance
            int txCount = getTxCount();
            long currentBalance = balance.get();
            
            if (txCount > 0 && currentBalance == 0) {
                Log.w(TAG, "⚠️ SUSPICIOUS: " + txCount + " transactions but zero balance");
                Log.w(TAG, "This indicates cache corruption - triggering rescan");
                
                // Small delay before rescan to let UI settle
                mainHandler.postDelayed(() -> {
                    Log.i(TAG, "⏰ Auto-triggering rescan due to suspected cache corruption");
                    triggerRescan();
                }, 5000);
            }
        }
    }

    private int getTxCount() {
        try {
            TransactionHistory history = wallet.getHistory();
            if (history == null) return 0;
            history.refresh();
            List<TransactionInfo> allTxs = history.getAll();
            return (allTxs != null) ? allTxs.size() : 0;
        } catch (Exception e) {
            return 0;
        }
    }
    
    /**
     * Triggers a rescan to fix cache corruption issues
     */
    private void triggerRescan() {
        // State transition - CRITICAL: Nothing else can run during rescan
        if (!currentState.compareAndSet(WalletState.IDLE, WalletState.RESCANNING)) {
            Log.w(TAG, "Cannot trigger rescan - state: " + currentState.get());
            if (rescanCallback != null) {
                final RescanCallback callback = rescanCallback;
                rescanCallback = null;
                mainHandler.post(() -> callback.onError("Cannot trigger rescan - wallet busy"));
            }
            return;
        }
        
        lastRescanTime.set(System.currentTimeMillis());
        stopPeriodicSync();
        
        syncExecutor.execute(() -> {
            try {
                String walletPath = wallet.getPath();
                
                // Step 1: Store current wallet state
                Log.d(TAG, "[1/5] Storing current wallet state...");
                try {
                    if (wallet != null && !currentWalletPath.isEmpty()) {
                        wallet.store(currentWalletPath);
                        Log.i(TAG, "✓ Wallet persisted before rescan");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to persist before rescan", e);
                }
               
                // Step 2: Close wallet cleanly
                Log.d(TAG, "[2/5] Closing wallet...");
                try { wallet.pauseRefresh(); } catch (Exception ignored) {}
                wallet.setListener(null);
                wallet.close();
                wallet = null;
                Thread.sleep(1000); // Give JNI time to clean up
                
                // Step 3: Reopen wallet (creates fresh cache)
                Log.d(TAG, "[3/5] Reopening wallet...");
                wallet = walletManager.openWallet(walletPath);
                if (wallet == null) {
                    Log.e(TAG, "✗ CRITICAL: Failed to reopen wallet - ABORTING");
                    currentState.set(WalletState.IDLE);
                    startPeriodicSync();
                    
                    if (rescanCallback != null) {
                        final RescanCallback callback = rescanCallback;
                        rescanCallback = null;
                        mainHandler.post(() -> callback.onError("Failed to reopen wallet"));
                    }
                    return;
                }
                Log.i(TAG, "✓ Wallet reopened with fresh cache");
                
                // Step 4: Reconnect to daemon
                Log.d(TAG, "[4/5] Reconnecting to daemon...");
                try {
                    Node node = walletManager.createNodeFromConfig();
                    long handle = wallet.initJ(
                        node.getAddress(), 0,
                        node.getUsername(), node.getPassword(),
                        node.isSsl(), false, ""
                    );
                    if (handle > 0) {
                        Log.d(TAG, "✓ Daemon connected (handle: " + handle + ")");
                    } else {
                        Log.w(TAG, "Daemon init returned 0 (may still work)");
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Daemon reconnection error (continuing)", e);
                }
                
                // Step 5: Use the new rescanSpent function if available
                Log.d(TAG, "[5/5] Attempting rescanSpent...");
                boolean resyncSuccess = false;
                try {
                    wallet.rescanSpent();
                    resyncSuccess = true;
                    Log.i(TAG, "rescanSpent result: " + resyncSuccess);
                } catch (Throwable t) {
                    Log.w(TAG, "rescanSpent not available, using traditional rescan", t);
                }
                
                if (!resyncSuccess) {
                    // Fallback to traditional rescan
                    Log.d(TAG, "Using traditional rescanBlockchainAsync...");
                    wallet.rescanBlockchainAsync();
                }
                
                // Start progress monitoring
                mainHandler.postDelayed(this::monitorRescanProgress, 5000);
                
            } catch (InterruptedException e) {
                Log.w(TAG, "Rescan interrupted", e);
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
                Thread.currentThread().interrupt();
                
                if (rescanCallback != null) {
                    final RescanCallback callback = rescanCallback;
                    rescanCallback = null;
                    mainHandler.post(() -> callback.onError("Rescan interrupted"));
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ Rescan failed with exception", e);
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
                
                if (rescanCallback != null) {
                    final RescanCallback callback = rescanCallback;
                    rescanCallback = null;
                    final String error = e.getMessage() != null ? e.getMessage() : "Unknown error";
                    mainHandler.post(() -> callback.onError("Rescan failed: " + error));
                }
            }
        });
    }    

    private void monitorRescanProgress() {
        if (currentState.get() != WalletState.RESCANNING || wallet == null) {
            Log.d(TAG, "Rescan monitoring stopped - state changed or wallet null");
            return;
        }
        
        try {
            long height = wallet.getBlockChainHeight();
            long daemonHeight = getDaemonHeightViaHttp();
            long[] snapshot = readWalletBalance();
            long balance = snapshot[0];
            long unlockedBalance = snapshot[1];
            int txCount = getTxCount();
            
            double progress = daemonHeight > 0 ? (height * 100.0 / daemonHeight) : 0;
            
            Log.i(TAG, "=== RESCAN PROGRESS ===");
            Log.i(TAG, "  Height: " + height + " / " + daemonHeight + " (" + String.format("%.1f", progress) + "%)");
            Log.i(TAG, "  Balance: " + convertAtomicToXmr(balance) + " XMR");
            Log.i(TAG, "  Unlocked: " + convertAtomicToXmr(unlockedBalance) + " XMR");
            Log.i(TAG, "  Transactions found: " + txCount);
            
            // Update atomic balances
            this.balance.set(balance);
            this.unlockedBalance.set(unlockedBalance);
            
            // Notify balance callback
            if (rescanBalanceCallback != null) {
                mainHandler.post(() -> rescanBalanceCallback.onBalanceUpdated(balance, unlockedBalance));
            }
            
            // Notify status listener
            if (statusListener != null) {
                final long currentHeight = height;
                final double finalProgress = progress;
                final long finalBalance = balance;
                final long finalUnlocked = unlockedBalance;
                mainHandler.post(() -> {
                    statusListener.onSyncProgress(currentHeight, 0, daemonHeight, finalProgress);
                    statusListener.onBalanceUpdated(finalBalance, finalUnlocked);
                });
            }
            
            // Check if rescan is complete
            if (height >= daemonHeight - 3 || progress >= 99.5) {
                Log.i(TAG, "✓✓✓ RESCAN COMPLETE ✓✓✓");
                
                // Store wallet state
                try {
                    wallet.store();
                    Log.d(TAG, "Wallet persisted after rescan");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to store wallet after rescan", e);
                }
                
                // Transition back to IDLE and restart periodic sync
                currentState.set(WalletState.IDLE);
                startPeriodicSync();
                
                // Notify status listener with 100% complete
                if (statusListener != null) {
                    final long currentHeight = height;
                    final long finalBal = balance;
                    final long finalUnl = unlockedBalance;
                    mainHandler.post(() -> {
                        statusListener.onSyncProgress(currentHeight, 0, daemonHeight, 100.0);
                        statusListener.onBalanceUpdated(finalBal, finalUnl);
                    });
                }

                // Notify rescan callback of completion
                if (rescanCallback != null) {
                    final RescanCallback callback = rescanCallback;
                    rescanCallback = null;
                    final long finalBalance = balance;
                    final long finalUnlockedBalance = unlockedBalance;
                    mainHandler.post(() -> {
                        Log.d(TAG, "Invoking rescan callback with balance: " + convertAtomicToXmr(finalUnlockedBalance) + " XMR");
                        callback.onComplete(finalBalance, finalUnlockedBalance);
                    });
                }
                
                // Clear the rescan balance callback
                rescanBalanceCallback = null;
                
                return; // Stop monitoring
            }
            
            // Continue monitoring
            mainHandler.postDelayed(this::monitorRescanProgress, 5000);
            
        } catch (Exception e) {
            Log.e(TAG, "Error monitoring rescan progress", e);
            currentState.set(WalletState.IDLE);
            startPeriodicSync();
            rescanBalanceCallback = null;
            
            if (rescanCallback != null) {
                final RescanCallback callback = rescanCallback;
                rescanCallback = null;
                final String error = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError("Rescan monitoring failed: " + error));
            }
        }
    }

    /**
     * Persists wallet to disk
     */
    private void persistWallet() {
        if (wallet == null || !isInitialized) {
            return;
        }
        
        if (currentState.get() == WalletState.RESCANNING) {
            Log.d(TAG, "Skipping persist - rescan in progress");
            return;
        }
        
        try {
            String path = wallet.getPath();
            if (path != null && !path.isEmpty()) {
                boolean stored = wallet.store(path);
                if (stored) {
                    Log.d(TAG, "✓ Wallet persisted");
                } else {
                    Log.w(TAG, "Wallet store returned false: " + wallet.getErrorString());
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Wallet persist error", e);
        }
    }
    
    private long getDaemonHeightViaHttp() {
        // FAST PATH 1: Ask native wallet JNI first if available (0ms network delay)
        if (wallet != null) {
            try {
                long nativeHeight = wallet.getDaemonBlockChainHeight();
                if (nativeHeight > 0) {
                    cachedDaemonHeight.set(nativeHeight);
                    return nativeHeight;
                }
            } catch (Exception ignored) {}
        }

        // FAST PATH 2: Fast HTTP GET query with 1.5s timeout
        HttpURLConnection conn = null;
        try {
            String daemonAddress = walletManager.getDaemonAddress();
            int daemonPort = walletManager.getDaemonPort();

            if (daemonAddress == null || daemonAddress.isEmpty()) {
                return cachedDaemonHeight.get();
            }

            boolean useSsl = walletManager.isForceSsl();
            String scheme = useSsl ? "https" : "http";
            String daemonUrl = scheme + "://" + daemonAddress + ":" + daemonPort + "/get_height";
            URL url = new URL(daemonUrl);
            conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);

            if (conn.getResponseCode() == HttpURLConnection.HTTP_OK) {
                StringBuilder response = new StringBuilder();
                try (BufferedReader br = new BufferedReader(
                        new InputStreamReader(conn.getInputStream(), "utf-8"))) {
                    String line;
                    while ((line = br.readLine()) != null) {
                        response.append(line.trim());
                    }
                }

                String jsonResponse = response.toString();
                int heightIndex = jsonResponse.indexOf("\"height\":");
                if (heightIndex != -1) {
                    int startIndex = heightIndex + 9;
                    int endIndex = jsonResponse.indexOf(",", startIndex);
                    if (endIndex == -1) {
                        endIndex = jsonResponse.indexOf("}", startIndex);
                    }
                    if (endIndex != -1) {
                        String heightStr = jsonResponse.substring(startIndex, endIndex).trim();
                        long height = Long.parseLong(heightStr);
                        cachedDaemonHeight.set(height);
                        return height;
                    }
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "Failed to get daemon height via HTTP: " + e.getMessage());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
        return cachedDaemonHeight.get();
    }

    private boolean validateDaemonConnection() {
        try {
            long height = getDaemonHeightViaHttp();
            if (height <= 0) {
                Log.e(TAG, "Daemon height invalid: " + height);
                return false;
            }
            Log.d(TAG, "Daemon height: " + height);
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Daemon validation failed", e);
            return false;
        }
    }

    public Future<Boolean> initializeWallet() {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        syncExecutor.execute(() -> {
            try {
                Log.i(TAG, "=== WALLET INITIALIZATION STARTED ===");
                String walletName = walletManager.getWalletName();
                Log.d(TAG, "Wallet name: " + walletName);

                // Step 1: Check for wallet backup on SD card
                File sdcardDir = new File(Environment.getExternalStorageDirectory(),
                        "Android/data/com.bitchat.droid/files");
                Log.d(TAG, "Checking SD card directory: " + sdcardDir.getAbsolutePath());
                
                File backupFile = new File(sdcardDir, walletName);
                File backupKeysFile = new File(sdcardDir, walletName + ".keys");
                File backupAddressFile = new File(sdcardDir, walletName + ".address.txt");

                // Step 2: Determine target directory - ORIGINAL PATH MAINTAINED
                File dir = new File(context.getFilesDir(), "monero_wallets");
                Log.d(TAG, "Wallets directory: " + dir.getAbsolutePath());
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "CRITICAL: Cannot create monero_wallets directory");
                    notifyWalletInitialized(false, "Cannot create monero_wallets dir");
                    future.complete(false);
                    return;
                }

                String savedPath = context.getSharedPreferences("monero_wallet_prefs", Context.MODE_PRIVATE)
                        .getString("active_wallet_path", null);
                String walletPath;
                if (savedPath != null && new File(savedPath + ".keys").exists()) {
                    walletPath = savedPath;
                    Log.i(TAG, "Using active restored wallet path: " + walletPath);
                } else {
                    walletPath = new File(dir, walletName).getAbsolutePath();
                    Log.i(TAG, "Using default wallet path: " + walletPath);
                }
                currentWalletPath = walletPath;

                // Step 3: Copy wallet from SD card if exists
                if (backupFile.exists()) {
                    Log.i(TAG, "=== RESTORING WALLET FROM SD CARD ===");
                    try {
                        File destWalletFile = new File(walletPath);
                        Files.copy(backupFile.toPath(), destWalletFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        Log.i(TAG, "✓ Main wallet file copied");
                        File bakWalletFile = new File(sdcardDir, walletName + ".bak");
                        Files.move(backupFile.toPath(), bakWalletFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                        Log.i(TAG, "✓ Original wallet file renamed to .bak");
                    } catch (Exception ex) {
                        Log.e(TAG, "✗ Wallet copy/rename failed", ex);
                    }

                    if (backupKeysFile.exists()) {
                        try {
                            File destKeysFile = new File(walletPath + ".keys");
                            Files.copy(backupKeysFile.toPath(), destKeysFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            File bakKeysFile = new File(sdcardDir, walletName + ".keys.bak");
                            Files.move(backupKeysFile.toPath(), bakKeysFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            Log.i(TAG, "✓ Keys file copied and renamed to .bak");
                        } catch (Exception ex) {
                            Log.e(TAG, "✗ Keys copy/rename failed", ex);
                        }
                    }

                    if (backupAddressFile.exists()) {
                        try {
                            File destAddressFile = new File(walletPath + ".address.txt");
                            Files.copy(backupAddressFile.toPath(), destAddressFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            File bakAddrFile = new File(sdcardDir, walletName + ".address.txt.bak");
                            Files.move(backupAddressFile.toPath(), bakAddrFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
                            Log.i(TAG, "✓ Address file copied and renamed to .bak");
                        } catch (Exception ex) {
                            Log.e(TAG, "✗ Address copy/rename failed", ex);
                        }
                    }

                    Log.i(TAG, "=== WALLET RESTORATION COMPLETE ===");
                } else {
                    Log.d(TAG, "No backup found on SD card");
                }

                // Step 4: Open or create wallet
                File keysFile = new File(walletPath + ".keys");
                Log.d(TAG, "Keys file exists: " + keysFile.exists());

                boolean isNewWallet = false;
                boolean exists = keysFile.exists() && keysFile.isFile();
                if (exists) {
                    wallet = walletManager.openWallet(walletPath);
                    if (wallet != null && wallet.getAddress().startsWith("5")) {
                        Log.w(TAG, "Legacy Stagenet wallet detected! Deleting to recreate as Mainnet.");
                        wallet.close();
                        wallet = null;

                        // Delete legacy wallet files
                        File walletFile = new File(walletPath);
                        if (walletFile.exists()) walletFile.delete();
                        if (keysFile.exists()) keysFile.delete();
                        File addrFile = new File(walletPath + ".address.txt");
                        if (addrFile.exists()) addrFile.delete();

                        exists = false;
                    }
                }

                if (!exists) {
                    Log.i(TAG, "=== CREATING NEW MAINNET WALLET ===");
                    wallet = walletManager.createWallet(walletPath);
                    isNewWallet = true;

                    // CRITICAL: Set restore height to current daemon height for new wallets.
                    // A brand new wallet has no transaction history, so scanning from block 1
                    // is completely pointless and would take forever (3.7M+ blocks).
                    if (wallet != null) {
                        try {
                            long currentDaemonHeight = getDaemonHeightViaHttp();
                            if (currentDaemonHeight > 0) {
                                // Subtract a small buffer (10 blocks) just to be safe
                                long restoreHeight = Math.max(1, currentDaemonHeight - 10);
                                wallet.setRestoreHeight(restoreHeight);
                                saveBirthdayHeight(restoreHeight);
                                Log.i(TAG, "New wallet restore height set to: " + restoreHeight + " (daemon: " + currentDaemonHeight + ")");
                            } else {
                                Log.w(TAG, "Could not fetch daemon height for new wallet, will sync from beginning");
                            }
                        } catch (Exception e) {
                            Log.w(TAG, "Failed to set restore height for new wallet", e);
                        }
                    }
                }

                if (wallet == null) {
                    Log.e(TAG, "CRITICAL: Wallet is null after open/create attempt");
                    notifyWalletInitialized(false, "JNI returned null wallet");
                    future.complete(false);
                    return;
                }

                // Caching metadata safely before daemon init
                try {
                    Log.d(TAG, "TRACE: Calling getAddress()");
                    walletAddress = wallet.getAddress();
                    Log.d(TAG, "TRACE: getAddress() SUCCESS");
                    Log.i(TAG, "Wallet address: " + walletAddress);
                } catch (Exception e) {
                    Log.w(TAG, "Metadata fetch failed", e);
                }

                // Step 5: Initialize daemon connection
                Log.d(TAG, "=== INITIALIZING DAEMON CONNECTION ===");
                try {
                    Node node = walletManager.createNodeFromConfig();
                    Log.d(TAG, "Node config: " + node.displayProperties());

                    Log.d(TAG, "TRACE: Calling initJ()");
                    long handle = wallet.initJ(
                            node.getAddress(), 0,
                            node.getUsername(), node.getPassword(),
                            node.isSsl(), false, ""
                    );
                    Log.d(TAG, "TRACE: initJ() SUCCESS, handle: " + handle);
                    if (handle == 0) {
                        Log.w(TAG, "initJ returned 0, applying fallback daemon setup");
                        walletManager.setDaemonAddress(node.getAddress());
                    }

                    // Set restore & refresh height for new wallets or unconfigured wallets
                    long savedBirthday = context.getSharedPreferences("monero_wallet_prefs", Context.MODE_PRIVATE)
                            .getLong("birthday_height", -1);
                    if (savedBirthday >= 0) {
                        try {
                            wallet.setRestoreHeight(savedBirthday);
                            wallet.store(walletPath);
                            Log.i(TAG, "✓ Preserved user restore height: " + savedBirthday);
                        } catch (Exception ex) {
                            Log.w(TAG, "Failed setting saved restore height", ex);
                        }
                    } else if (isNewWallet || wallet.getRestoreHeight() <= 1) {
                        try {
                            long dHeight = wallet.getDaemonBlockChainHeight();
                            if (dHeight <= 0) {
                                dHeight = getDaemonHeightViaHttp();
                            }
                            if (dHeight > 0) {
                                long rHeight = Math.max(1, dHeight - 10);
                                wallet.setRestoreHeight(rHeight);
                                boolean saved = wallet.store(walletPath);
                                Log.i(TAG, "✓ Set new wallet restore height to: " + rHeight + " (saved=" + saved + ")");
                            } else {
                                Log.w(TAG, "Daemon height unavailable after initJ");
                            }
                        } catch (Exception ex) {
                            Log.w(TAG, "Failed setting restore height after initJ", ex);
                        }
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Exception during daemon init", e);
                    walletManager.setDaemonAddress(walletManager.getDaemonAddress());
                }

                // Step 6: Status check
                Log.d(TAG, "TRACE: Calling getStatus()");
                int status = wallet.getStatus();
                Log.d(TAG, "TRACE: getStatus() SUCCESS");
                String statusName = (status < Wallet.Status.values().length)
                        ? Wallet.Status.values()[status].name() : "UNKNOWN";
                Log.d(TAG, "Wallet status: " + statusName + " (" + status + ")");
                if (status != Wallet.Status.Status_Ok.ordinal()) {
                    notifyWalletInitialized(false, "Init failed: " + statusName);
                    future.complete(false);
                    return;
                }

                // Step 7: Check read-only flag
                boolean isReadOnly = false;
                try {
                    Log.d(TAG, "TRACE: Calling isReadOnly()");
                    isReadOnly = wallet.isReadOnly();
                    Log.d(TAG, "TRACE: isReadOnly() SUCCESS");
                } catch (Throwable t) {
                    Log.w(TAG, "Cannot determine read-only state", t);
                }

                // CRITICAL: Set isInitialized BEFORE notifying UI, otherwise UI gets "Wallet not ready"
                isInitialized = true;

                // Fetch spend key safely AFTER initJ and status checks, right before sync
                // Calling getSeed() causes a SIGABRT native crash in this JNI version,
                // so we use the Secret Spend Key instead which is mathematically equivalent and safe to fetch.
                try {
                    Log.d(TAG, "TRACE: Calling getSecretSpendKey() AFTER initJ");
                    String hexKey = wallet.getSecretSpendKey();
                    Log.d(TAG, "TRACE: getSecretSpendKey() SUCCESS");
                    if (hexKey != null && hexKey.length() == 64) {
                        // Convert hex spend key to 25-word mnemonic seed
                        cachedSeed = MoneroMnemonic.hexToMnemonic(hexKey);
                        if (cachedSeed.isEmpty()) {
                            // Fallback to hex if mnemonic conversion fails
                            cachedSeed = hexKey;
                        }
                        Log.d(TAG, "TRACE: Mnemonic seed generated (" + cachedSeed.split(" ").length + " words)");
                    } else {
                        cachedSeed = hexKey != null ? hexKey : "";
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to get spend key", e);
                    cachedSeed = "";
                }

                if (isReadOnly) {
                    Log.w(TAG, "⚠️ Wallet opened in read-only mode");
                    notifyWalletInitialized(true, "Read-only wallet");
                } else {
                    notifyWalletInitialized(true, "Wallet initialized OK");
                }

                // Step 8: (Removed dangerous restore height manipulation)
                try {
                    Log.d(TAG, "TRACE: Calling getBlockChainHeight()");
                    cachedWalletHeight = wallet.getBlockChainHeight();
                    Log.d(TAG, "TRACE: getBlockChainHeight() SUCCESS");

                    Log.d(TAG, "TRACE: Calling getRestoreHeight()");
                    long rh = wallet.getRestoreHeight();
                    Log.d(TAG, "TRACE: getRestoreHeight() SUCCESS");

                    Log.d(TAG, "Height=" + cachedWalletHeight +
                          ", Restore=" + rh);
                } catch (Exception e) {
                    Log.w(TAG, "Failed to get initial wallet height", e);
                }

                // Step 9: Start syncing
                performSync();
                startPeriodicSync();

                future.complete(true);
                Log.i(TAG, "✓ WALLET INITIALIZATION COMPLETE");

            } catch (Exception e) {
                Log.e(TAG, "✗ Exception during wallet init", e);
                notifyWalletInitialized(false, "Error: " + e.getMessage());
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    public void initializeWalletFromSeed(String seed, long restoreHeight, int requestedNetType) {
        syncExecutor.execute(() -> {
            try {
                Log.i(TAG, "=== RESTORING WALLET FROM SEED ===");
                Log.d(TAG, "Restore height: " + restoreHeight);
                
                // USE CONSISTENT PATH with main initializeWallet method
                File dir = context.getDir("wallets", Context.MODE_PRIVATE);
                if (!dir.exists() && !dir.mkdirs()) {
                    Log.e(TAG, "CRITICAL: Cannot create wallets directory");
                    notifyWalletInitialized(false, "Cannot create wallets dir");
                    return;
                }
                
                String walletPath = new File(dir, walletManager.getWalletName()).getAbsolutePath();
                currentWalletPath = walletPath;
                Log.d(TAG, "Wallet path: " + walletPath);
                
                String normalizedSeed = MoneroMnemonic.normalizeAndCompleteSeed(seed);
                wallet = walletManager.recoveryWallet(walletPath, normalizedSeed, restoreHeight);
                Log.d(TAG, "Recovery wallet returned: " + (wallet != null));
                
                if (wallet != null && wallet.getStatus() == Wallet.Status.Status_Ok.ordinal()) {
                    Log.i(TAG, "✓ Wallet restored successfully");
                    
                    setupWallet();
                    isInitialized = true;
                    notifyWalletInitialized(true, "Wallet restored");

                    performSync();
                    startPeriodicSync();
                } else {
                    String error = (wallet != null) ? wallet.getErrorString() : "JNI error";
                    Log.e(TAG, "✗ Wallet restoration failed: " + error);
                    notifyWalletInitialized(false, "Restore failed: " + error);
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ Exception during wallet restoration", e);
                notifyWalletInitialized(false, "Error: " + e.getMessage());
            }
        });
    }

    private void setupWallet() {
        if (wallet == null) return;

        boolean daemonSet = setDaemonFromConfigAndApply();
        if (!daemonSet) {
            Log.e(TAG, "Failed to establish daemon connection during setup");
        }
    }

    public void getAddress(AddressCallback cb) {
        if (!isInitialized || walletAddress == null) {
            cb.onError("Wallet not ready");
            return;
        }
        mainHandler.post(() -> cb.onSuccess(walletAddress));
    }

    public void getBalance(BalanceCallback cb) {
        if (!isInitialized || wallet == null) {
            cb.onError("Wallet not ready");
            return;
        }
        syncExecutor.execute(() -> {
            try {
                long[] snapshot = readWalletBalance();
                long bal = snapshot[0];
                long ubal = snapshot[1];
                mainHandler.post(() -> cb.onSuccess(bal, ubal));
            } catch (Exception e) {
                mainHandler.post(() -> cb.onError(e.getMessage()));
            }
        });
    }

    public static String convertAtomicToXmr(long atomicAmount) {
        return BigDecimal.valueOf(atomicAmount)
                .movePointLeft(12)
                .stripTrailingZeros()
                .toPlainString();
    }

    /**
     * Convert a user-entered XMR amount without going through a double.
     * Monero amounts have exactly twelve decimal places of precision; using
     * BigDecimal here prevents a floating-point rounding error from changing
     * the atomic amount sent to the wallet.
     *
     * @return atomic units, or -1 when the input is invalid or out of range
     */
    public static long convertXmrToAtomic(String amount) {
        if (amount == null || amount.trim().isEmpty()) return -1L;
        try {
            BigDecimal parsed = new BigDecimal(amount.trim());
            if (parsed.signum() <= 0 || parsed.scale() > 12) return -1L;
            return parsed.movePointRight(12).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (Exception e) {
            return -1L;
        }
    }

    public void triggerImmediateSync() {
        Log.d(TAG, "Manual sync triggered");
        performSync();
    }

    private static String bytesToHex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(String.format("%02x", x & 0xff));
        return sb.toString();
    }

    public void reloadConfiguration() {
        loadConfiguration();
        Log.i(TAG, "Config reloaded from: " + getCurrentConfigPath());
        if (wallet != null) setDaemonFromConfigAndApply();
    }

    public static void copyDefaultConfigToExternalStorage(Context ctx) {
        try {
            File dest = new File(ctx.getExternalFilesDir(null), PROPERTIES_FILE);
            if (dest.exists()) {
                Log.i(TAG, "Config already exists: " + dest.getAbsolutePath());
                return;
            }
            try (InputStream is = ctx.getAssets().open(PROPERTIES_FILE);
                 FileOutputStream os = new FileOutputStream(dest)) {
                byte[] buf = new byte[1024];
                int r;
                while ((r = is.read(buf)) > 0) os.write(buf, 0, r);
            }
            Log.i(TAG, "Copied default config to " + dest.getAbsolutePath());
        } catch (IOException e) {
            Log.e(TAG, "Failed to copy default config", e);
        }
    }

    public String getCurrentConfigPath() {
        File external = new File(context.getExternalFilesDir(null), PROPERTIES_FILE);
        if (external.exists()) return external.getAbsolutePath();
        File internal = new File(context.getFilesDir(), PROPERTIES_FILE);
        if (internal.exists()) return internal.getAbsolutePath();
        return "assets/" + PROPERTIES_FILE;
    }

    public void setWalletStatusListener(WalletStatusListener l) {
        this.statusListener = l;
    }

    public void setTransactionListener(TransactionListener l) {
        this.transactionListener = l;
    }
    
    public void setDaemonConfigCallback(DaemonConfigCallback callback) {
        this.daemonConfigCallback = callback;
    }

    private void notifyWalletInitialized(boolean ok, String msg) {
        if (statusListener != null)
            mainHandler.post(() -> statusListener.onWalletInitialized(ok, msg, ok ? walletAddress : null));
    }

    public boolean isReady() {
        return isInitialized && wallet != null;
    }

    public boolean isSyncing() {
        WalletState state = currentState.get();
        return state == WalletState.SYNCING || state == WalletState.SYNC_FINALIZING
                || state == WalletState.RESCANNING;
    }

    public SyncStatus getSyncStatus() {
        if (!isInitialized || wallet == null) {
            return new SyncStatus(false, 0, 0, 0.0);
        }
        try {
            long walletHeight = cachedWalletHeight;
            long daemonHeight = getDaemonHeightViaHttp();
            double percent = daemonHeight > 0 ? (100.0 * walletHeight / daemonHeight) : 0.0;
            return new SyncStatus(isSyncing(), walletHeight, daemonHeight, percent);
        } catch (Exception e) {
            return new SyncStatus(false, 0, 0, 0.0);
        }
    }

    private static boolean nativeAvailable() {
        if (!nativeChecked) {
            synchronized (WalletSuite.class) {
                if (!nativeChecked) {
                    try {
                        System.loadLibrary("monerujo");
                        nativeOk = true;
                    } catch (Throwable e) {
                        nativeOk = false;
                        Log.e(TAG, "Failed to load native library monerujo", e);
                    }
                    nativeChecked = true;
                }
            }
        }
        return nativeOk;
    }

    private void loadConfiguration() {
        Properties props = new Properties();
        boolean loaded = false;
        File external = new File(context.getExternalFilesDir(null), PROPERTIES_FILE);
        File internal = new File(context.getFilesDir(), PROPERTIES_FILE);

        if (external.exists()) {
            try (FileInputStream fis = new FileInputStream(external)) {
                props.load(fis);
                loaded = true;
                Log.i(TAG, "Loaded config from external");
            } catch (IOException e) {
                Log.w(TAG, "Failed to load external config", e);
            }
        }

        if (!loaded && internal.exists()) {
            try (FileInputStream fis = new FileInputStream(internal)) {
                props.load(fis);
                loaded = true;
                Log.i(TAG, "Loaded config from internal");
            } catch (IOException e) {
                Log.w(TAG, "Failed to load internal config", e);
            }
        }

        if (!loaded) {
            try (InputStream is = context.getAssets().open(PROPERTIES_FILE)) {
                props.load(is);
                loaded = true;
                Log.i(TAG, "Loaded config from assets");
            } catch (IOException e) {
                Log.w(TAG, "No config found in assets; using defaults");
            }
        }

        if (props.containsKey("daemon.autosync")) {
            autoSyncEnabled = Boolean.parseBoolean(props.getProperty("daemon.autosync", "true"));
            Log.i(TAG, "Auto sync enabled: " + autoSyncEnabled);
        } else {
            autoSyncEnabled = true; // Default
        }

        walletManager.applyConfiguration(props);
    }

    public boolean setDaemonFromConfigAndApply() {
        try {
            Node node = walletManager.createNodeFromConfig();
            walletManager.setDaemon(node);
            Log.i(TAG, "Daemon set to: " + node.displayProperties());
            return true;
        } catch (Exception e) {
            Log.e(TAG, "Failed to set daemon", e);
            return false;
        }
    }

    /**
     * Send Monero transaction (simplified version for ChatScreen)
     * Required by: ChatScreen.kt and MoneroChatTransferManager.kt
     */
    public void sendTransaction(String destinationAddress, double amountXmr, TransactionCallback callback) {
        Log.i(TAG, "=== SEND TRANSACTION REQUESTED (simplified) ===");
        Log.i(TAG, "Amount: " + amountXmr + " XMR");
        Log.i(TAG, "Destination: " + (destinationAddress != null ? 
            destinationAddress.substring(0, Math.min(20, destinationAddress.length())) : "null"));
        
        // Use cached balances for validation
        long cachedBal = balance.get();
        long cachedUnl = unlockedBalance.get();
        
        sendTransaction(destinationAddress, amountXmr, cachedBal, cachedUnl, callback);
    }

    /**
     * Send Monero transaction with payment ID (for compatibility)
     * Required by: ChatScreen.kt and MoneroChatTransferManager.kt
     */
    public void sendTransaction(String destinationAddress, double amountXmr, String paymentId, TransactionCallback callback) {
        Log.i(TAG, "=== SEND TRANSACTION WITH PAYMENT ID REQUESTED ===");
        Log.i(TAG, "Payment ID: " + (paymentId != null ? paymentId : "null"));
        
        // For now, ignore payment ID and use regular send
        // Payment ID is deprecated in Monero but kept for compatibility
        sendTransaction(destinationAddress, amountXmr, callback);
    }

    /**
     * Estimate the network fee by creating a native pending transaction and
     * reading its fee, without committing or broadcasting it.
     *
     * The standalone native estimateTransactionFee JNI method in the bundled
     * Monero library is not safe on Android: it can release an invalid local
     * jstring reference and abort the whole process. Using the same pending
     * transaction path as the actual send flow avoids that broken JNI entry
     * point and also gives the UI the fee for the exact transaction shape.
     */
    public void estimateTransactionFee(String destinationAddress, double amountXmr, FeeEstimateCallback callback) {
        estimateTransactionFee(
                destinationAddress,
                BigDecimal.valueOf(amountXmr).toPlainString(),
                callback
        );
    }

    /**
     * Exact decimal fee estimate used by the chat confirmation screen. The
     * amount stays a string until conversion to atomic units so the preview
     * and the eventual send cannot disagree at the 12th decimal place.
     */
    public void estimateTransactionFee(String destinationAddress, String amountXmr, FeeEstimateCallback callback) {
        if (callback == null) return;

        executorService.execute(() -> {
            boolean acquiredState = false;
            PendingTransaction pendingTx = null;
            walletLifecycleLock.readLock().lock();
            try {
                if (!isInitialized || wallet == null) {
                    mainHandler.post(() -> callback.onError("Wallet not initialized"));
                    return;
                }

                if (destinationAddress == null || destinationAddress.trim().isEmpty()) {
                    mainHandler.post(() -> callback.onError("Destination address missing"));
                    return;
                }

                if (!currentState.compareAndSet(WalletState.IDLE, WalletState.TRANSACTION)) {
                    mainHandler.post(() -> callback.onError("Wallet busy: " + currentState.get()));
                    return;
                }
                acquiredState = true;

                long atomicAmount = convertXmrToAtomic(amountXmr);
                if (atomicAmount <= 0) {
                    mainHandler.post(() -> callback.onError("Invalid amount"));
                    return;
                }

                pendingTx = wallet.createTransaction(
                    destinationAddress.trim(),
                    "",
                    atomicAmount,
                    15,
                    PendingTransaction.Priority.Priority_Default.getValue(),
                    0
                );

                if (pendingTx == null) {
                    mainHandler.post(() -> callback.onError("Wallet could not prepare the transaction"));
                    return;
                }

                if (pendingTx.getStatus() != PendingTransaction.Status.Status_Ok) {
                    String nativeError = pendingTx.getErrorString();
                    final String error = nativeError != null && !nativeError.trim().isEmpty()
                        ? nativeError
                        : "Wallet could not prepare the transaction";
                    mainHandler.post(() -> callback.onError(error));
                    return;
                }

                long fee = pendingTx.getFee();
                if (fee < 0) {
                    mainHandler.post(() -> callback.onError("Wallet could not read the network fee"));
                    return;
                }

                final long estimatedFee = fee;
                mainHandler.post(() -> callback.onSuccess(estimatedFee));
            } catch (Exception e) {
                Log.e(TAG, "Fee estimation failed", e);
                final String error = e.getMessage() != null ? e.getMessage() : "Unknown fee estimation error";
                mainHandler.post(() -> callback.onError(error));
            } finally {
                if (pendingTx != null) {
                    try {
                        wallet.disposePendingTransaction();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to dispose fee-estimation transaction", e);
                    }
                }
                if (acquiredState) {
                    currentState.compareAndSet(WalletState.TRANSACTION, WalletState.IDLE);
                }
                walletLifecycleLock.readLock().unlock();
            }
        });
    }

    /**
     * Send Monero transaction with explicit balance parameters
     * Required by: MoneroChatTransferManager.kt (TxID flow)
     */
    public void sendTransaction(String destinationAddress, double amountXmr, long cachedBalance, long cachedUnlockedBalance, TransactionCallback callback) {
        sendTransaction(
                destinationAddress,
                BigDecimal.valueOf(amountXmr).toPlainString(),
                cachedBalance,
                cachedUnlockedBalance,
                callback
        );
    }

    /**
     * Exact decimal send overload used by chat input. Keeping the amount as a
     * string until atomic conversion avoids losing the user's 12th decimal.
     */
    public void sendTransaction(String destinationAddress, String amountXmr, long cachedBalance, long cachedUnlockedBalance, TransactionCallback callback) {
        Log.i(TAG, "=== SEND TRANSACTION REQUESTED (with balance params) ===");
        Log.i(TAG, "Amount: " + amountXmr + " XMR");
        Log.i(TAG, "Cached Balance: " + convertAtomicToXmr(cachedBalance) + " XMR");
        Log.i(TAG, "Cached Unlocked: " + convertAtomicToXmr(cachedUnlockedBalance) + " XMR");
        Log.i(TAG, "Destination: " + (destinationAddress != null ? 
            destinationAddress.substring(0, Math.min(20, destinationAddress.length())) : "null"));
        
        if (!isInitialized || wallet == null) {
            mainHandler.post(() -> callback.onError("Wallet not initialized"));
            return;
        }
        
        // Validate state
        WalletState state = currentState.get();
        if (state != WalletState.IDLE) {
            Log.w(TAG, "Cannot send transaction - state: " + state);
            mainHandler.post(() -> callback.onError("Wallet busy: " + state));
            return;
        }
        
        // Convert amount to atomic units
        long atomicAmount = convertXmrToAtomic(amountXmr);
        
        // Validate amount
        if (atomicAmount <= 0) {
            mainHandler.post(() -> callback.onError("Invalid amount"));
            return;
        }
        
        // Validate balance
        if (atomicAmount > cachedUnlockedBalance) {
            String error = String.format("Insufficient balance. Required: %s XMR, Available: %s XMR",
                convertAtomicToXmr(atomicAmount), convertAtomicToXmr(cachedUnlockedBalance));
            mainHandler.post(() -> callback.onError(error));
            return;
        }
        
        // Execute transaction on executor
        executorService.execute(() -> {
            PendingTransaction pendingTx = null;
            boolean syncAfterTransaction = false;
            boolean acquiredState = false;
            walletLifecycleLock.readLock().lock();
            try {
                // Transition to transaction state
                if (!currentState.compareAndSet(WalletState.IDLE, WalletState.TRANSACTION)) {
                    mainHandler.post(() -> callback.onError("Wallet busy: " + currentState.get()));
                    return;
                }
                acquiredState = true;
                
                Log.d(TAG, "Creating transaction...");
                
                // Create transaction with mixin=15 (ring size)
                pendingTx = wallet.createTransaction(
                    destinationAddress,
                    "", // payment ID (deprecated)
                    atomicAmount,
                    15, // mixin
                    PendingTransaction.Priority.Priority_Default.getValue(),
                    0 // subaddress account
                );
                
                if (pendingTx == null) {
                    mainHandler.post(() -> callback.onError("Failed to create transaction"));
                    return;
                }
                
                // Check transaction status
                if (pendingTx.getStatus() != PendingTransaction.Status.Status_Ok) {
                    String error = pendingTx.getErrorString();
                    mainHandler.post(() -> callback.onError("Transaction error: " + error));
                    return;
                }
                
                // Get transaction details
                String txId = pendingTx.getFirstTxId();
                long fee = pendingTx.getFee();
                if (fee < 0 || fee > cachedUnlockedBalance - atomicAmount) {
                    final String error = fee < 0
                            ? "Wallet could not estimate the network fee"
                            : "Insufficient balance for amount plus network fee. Available: "
                                    + convertAtomicToXmr(cachedUnlockedBalance) + " XMR";
                    mainHandler.post(() -> callback.onError(error));
                    return;
                }
                
                Log.i(TAG, "Transaction created:");
                Log.i(TAG, "  TxID: " + txId);
                Log.i(TAG, "  Amount: " + convertAtomicToXmr(atomicAmount) + " XMR");
                Log.i(TAG, "  Fee: " + convertAtomicToXmr(fee) + " XMR");
                
                // Commit transaction
                Log.d(TAG, "Committing transaction...");
                boolean committed = pendingTx.commit("", true);
                
                if (!committed) {
                    String error = pendingTx.getErrorString();
                    mainHandler.post(() -> callback.onError("Failed to commit: " + error));
                    return;
                }

                syncAfterTransaction = true;
                
                Log.i(TAG, "✓ Transaction committed successfully");
                
                // Store wallet state
                try {
                    wallet.store();
                    Log.d(TAG, "Wallet state persisted");
                } catch (Exception e) {
                    Log.w(TAG, "Failed to persist wallet after transaction", e);
                }
                
                // Update balances
                updateBalanceFromWallet();
                
                // Notify success
                final String finalTxId = txId;
                final long finalAmount = atomicAmount;
                mainHandler.post(() -> {
                    callback.onSuccess(finalTxId, finalAmount);
                    if (transactionListener != null) {
                        transactionListener.onTransactionCreated(finalTxId, finalAmount);
                    }
                });
                
            } catch (Exception e) {
                Log.e(TAG, "✗ Transaction exception", e);
                final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError("Transaction failed: " + errorMsg));
            } finally {
                // Clean up pending transaction
                if (pendingTx != null) {
                    try {
                        wallet.disposePendingTransaction();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to dispose pending transaction", e);
                    }
                }
                if (acquiredState) {
                    currentState.compareAndSet(WalletState.TRANSACTION, WalletState.IDLE);
                }
                boolean startSyncAfterUnlock = acquiredState && syncAfterTransaction;
                walletLifecycleLock.readLock().unlock();
                if (startSyncAfterUnlock && !walletReplacementRequested.get()) {
                    // The wallet must leave TRANSACTION state and release the
                    // lifecycle read lock before sync or replacement can start.
                    performSync();
                } else {
                    maybeStartQueuedRescan();
                }
            }
        });
    }

    /**
     * Look up an automatically announced incoming transaction and, when the
     * sender supplied expectations, verify that the wallet entry is actually
     * an incoming payment for the announced amount and destination.
     */
    public void lookupTransactionById(
            String txId,
            String expectedAmountXmr,
            String expectedDestinationAddress,
            TransactionLookupCallback callback) {
        if (callback == null) return;
        if (txId == null || txId.trim().isEmpty()) {
            mainHandler.post(() -> callback.onError("Transaction ID is empty"));
            return;
        }
        final String canonicalTxId = txId.trim().toLowerCase(Locale.US);
        if (!isInitialized || wallet == null) {
            mainHandler.post(() -> callback.onError("Wallet not ready"));
            return;
        }

        // Transaction creation/commit runs on a separate executor. Claim a
        // wallet state before queueing the native refresh so a lookup can
        // never touch the wallet concurrently with a send or rescan.
        if (!currentState.compareAndSet(WalletState.IDLE, WalletState.LOOKING_UP)) {
            mainHandler.post(() -> callback.onError("Wallet busy: " + currentState.get()));
            return;
        }

        syncExecutor.execute(() -> {
            walletLifecycleLock.readLock().lock();
            try {
                Log.i(TAG, "=== LOOKING UP TRANSACTION: " + canonicalTxId + " ===");

                wallet.refresh();

                TransactionHistory history = wallet.getHistory();
                if (history == null) {
                    mainHandler.post(() -> callback.onError("Transaction history unavailable"));
                    return;
                }

                history.refresh();
                List<TransactionInfo> allTxs = history.getAll();

                TransactionInfo txInfo = null;
                if (allTxs != null) {
                    for (TransactionInfo info : allTxs) {
                        if (canonicalTxId.equals(info.hash != null
                                ? info.hash.toLowerCase(Locale.US) : "")
                                && matchesExpectedIncomingTransaction(
                                info, expectedAmountXmr, expectedDestinationAddress)) {
                            txInfo = info;
                            break;
                        }
                    }
                }

                if (txInfo != null) {
                    Log.i(TAG, "✓ Transaction found");
                    long amount = txInfo.amount;
                    long confirmations = txInfo.confirmations;
                    long blockHeight = txInfo.blockheight;

                    if (statusListener != null) {
                        long[] snapshot = readWalletBalance();
                        long bal = snapshot[0];
                        long ubal = snapshot[1];
                        mainHandler.post(() -> statusListener.onBalanceUpdated(bal, ubal));
                    }

                    persistWallet();
                    mainHandler.post(() -> callback.onTransactionFound(canonicalTxId, amount, confirmations, blockHeight));
                } else {
                    Log.w(TAG, "✗ Transaction not found");
                    mainHandler.post(() -> callback.onTransactionNotFound(canonicalTxId));
                }
            } catch (Exception e) {
                Log.e(TAG, "✗ Transaction lookup error", e);
                mainHandler.post(() -> callback.onError("Lookup failed: " + e.getMessage()));
            } finally {
                currentState.compareAndSet(WalletState.LOOKING_UP, WalletState.IDLE);
                walletLifecycleLock.readLock().unlock();
            }
        });
    }

    private boolean matchesExpectedIncomingTransaction(
            TransactionInfo info,
            String expectedAmountXmr,
            String expectedDestinationAddress) {
        if (expectedAmountXmr == null || expectedAmountXmr.trim().isEmpty()) {
            return true;
        }

        long expectedAmount;
        try {
            expectedAmount = convertXmrToAtomic(expectedAmountXmr);
        } catch (Exception e) {
            Log.w(TAG, "Rejecting transaction lookup with invalid announced amount", e);
            return false;
        }
        if (expectedAmount <= 0
                || info.direction != TransactionInfo.Direction.Direction_In
                || info.amount != expectedAmount) {
            return false;
        }

        if (expectedDestinationAddress == null || expectedDestinationAddress.trim().isEmpty()) {
            return true;
        }

        String expected = expectedDestinationAddress.trim();
        if (expected.equals(info.address)) {
            return true;
        }
        if (info.transfers != null) {
            for (com.m2049r.xmrwallet.model.Transfer transfer : info.transfers) {
                if (expected.equals(transfer.address)) return true;
            }
        }

        // Some wallet2 versions do not populate an address for incoming
        // entries. Direction + exact amount remains a useful validation in
        // that case; reject only when the native model exposes a conflicting
        // address.
        return (info.address == null || info.address.trim().isEmpty())
                && (info.transfers == null || info.transfers.isEmpty());
    }

    /**
     * Load the wallet's complete transaction history for the history screen.
     * The callback receives a detached, newest-first snapshot so the UI never
     * reads the native-backed list while it is being refreshed.
     */
    public void getTransactionHistory(TransactionHistoryCallback callback) {
        if (callback == null) return;
        if (!isInitialized || wallet == null) {
            mainHandler.post(() -> callback.onError("Wallet not ready"));
            return;
        }

        syncExecutor.execute(() -> {
            walletLifecycleLock.readLock().lock();
            try {
                // History is a read-only user action. Queue it behind an active
                // sync/finalization instead of surfacing a transient "busy"
                // error when the user opens the history screen immediately
                // after recovery or a payment.
                if (!waitForWalletIdle(30_000L)
                        || !currentState.compareAndSet(WalletState.IDLE, WalletState.LOOKING_UP)) {
                    mainHandler.post(() -> callback.onError("Wallet is busy: " + currentState.get()));
                    return;
                }

                TransactionHistory history = wallet.getHistory();
                if (history == null) {
                    mainHandler.post(() -> callback.onError("Transaction history unavailable"));
                    return;
                }

                history.refreshWithNotes(wallet);
                List<TransactionInfo> nativeTransactions = history.getAll();
                List<TransactionInfo> snapshot = nativeTransactions == null
                        ? new ArrayList<>()
                        : new ArrayList<>(nativeTransactions);
                java.util.Collections.sort(snapshot);

                mainHandler.post(() -> callback.onSuccess(snapshot));
            } catch (Exception e) {
                Log.e(TAG, "✗ Transaction history load error", e);
                String message = e.getMessage() != null
                        ? e.getMessage()
                        : "Unable to load transaction history";
                mainHandler.post(() -> callback.onError(message));
            } finally {
                currentState.compareAndSet(WalletState.LOOKING_UP, WalletState.IDLE);
                walletLifecycleLock.readLock().unlock();
            }
        });
    }

    private boolean waitForWalletIdle(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (currentState.get() != WalletState.IDLE
                && currentState.get() != WalletState.CLOSING
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return currentState.get() == WalletState.IDLE;
    }

    private boolean waitForReplacementSafeState(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (currentState.get() != WalletState.IDLE
                && currentState.get() != WalletState.CLOSING
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return currentState.get() == WalletState.IDLE;
    }

    /**
     * Import and broadcast signed transaction blob
     * Required by: MoneroChatTransferManager.handleIncomingTransactionBlob()
     */
    public void importSignedTransactionBlob(String signedTxBlobBase64, TransactionImportCallback callback) {
        Log.i(TAG, "=== IMPORT SIGNED TX BLOB REQUESTED ===");
        if (callback == null) return;
        
        executorService.execute(() -> {
            boolean acquiredState = false;
            walletLifecycleLock.readLock().lock();
            try {
                if (!isInitialized || wallet == null) {
                    mainHandler.post(() -> callback.onError("Wallet not initialized"));
                    return;
                }
                if (!currentState.compareAndSet(WalletState.IDLE, WalletState.TRANSACTION)) {
                    mainHandler.post(() -> callback.onError("Wallet busy: " + currentState.get()));
                    return;
                }
                acquiredState = true;

                // Decode base64
                byte[] blobBytes = android.util.Base64.decode(signedTxBlobBase64, android.util.Base64.NO_WRAP);
                
                // Convert to hex
                StringBuilder hexString = new StringBuilder();
                for (byte b : blobBytes) {
                    hexString.append(String.format("%02x", b & 0xff));
                }
                String hexBlob = hexString.toString();
                
                Log.d(TAG, "Submitting imported transaction...");
                String txId = wallet.submitTransaction(hexBlob);
                
                if (txId == null || txId.isEmpty()) {
                    String error = wallet.getErrorString();
                    mainHandler.post(() -> callback.onError("Import failed: " + (error != null ? error : "Unknown error")));
                    return;
                }
                
                Log.i(TAG, "✓ Transaction imported and submitted: " + txId);
                
                // Store wallet state
                wallet.store();
                
                // Update balances
                updateBalancesFromWallet();
                
                final String finalTxId = txId;
                mainHandler.post(() -> callback.onSuccess(finalTxId));
                
            } catch (Exception e) {
                Log.e(TAG, "✗ Import tx blob exception", e);
                final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError("Import failed: " + errorMsg));
            } finally {
                if (acquiredState) {
                    currentState.compareAndSet(WalletState.TRANSACTION, WalletState.IDLE);
                }
                walletLifecycleLock.readLock().unlock();
            }
        });
    }

    /**
     * Create transaction blob (unsigned transaction for blob-based flow)
     * Required by: MoneroChatTransferManager.sendWithBlobFlow()
     */
    public void createTxBlob(String to, String amount, TxBlobCallback cb) {
        executorService.execute(() -> {
            PendingTransaction pendingTx = null;
            boolean acquiredState = false;
            walletLifecycleLock.readLock().lock();
            try {
                if (!isInitialized || wallet == null) {
                    mainHandler.post(() -> cb.onError("Wallet not initialized"));
                    return;
                }
                if (!currentState.compareAndSet(WalletState.IDLE, WalletState.TRANSACTION)) {
                    mainHandler.post(() -> cb.onError("Wallet busy: " + currentState.get()));
                    return;
                }
                acquiredState = true;

                long atomic = convertXmrToAtomic(amount);
                if (atomic <= 0) {
                    mainHandler.post(() -> cb.onError("Invalid amount"));
                    return;
                }
                Log.i(TAG, "Using mixin value of: " + 15);
                
                pendingTx = wallet.createTransaction(to, "", atomic, 15, 
                    PendingTransaction.Priority.Priority_Default.getValue(), 0);
                
                if (pendingTx == null) {
                    mainHandler.post(() -> cb.onError("Failed to create transaction"));
                    return;
                }
                
                if (pendingTx.getStatus() != PendingTransaction.Status.Status_Ok) {
                    final String error = pendingTx.getErrorString();
                    mainHandler.post(() -> cb.onError(error));
                    return;
                }
                
                // Save transaction to a temporary file
                String txId = pendingTx.getFirstTxId();
                File tempFile = new File(context.getCacheDir(), txId + ".tx");
                
                boolean saved = pendingTx.commit(tempFile.getAbsolutePath(), true);
                if (!saved) {
                    mainHandler.post(() -> cb.onError("Failed to save transaction"));
                    return;
                }
                
                // Read the file and encode to base64
                byte[] raw = Files.readAllBytes(tempFile.toPath());
                String b64 = Base64.encodeToString(raw, Base64.NO_WRAP);
                
                // Clean up temp file
                tempFile.delete();
                
                mainHandler.post(() -> cb.onSuccess(txId, b64));
                
            } catch (Exception e) {
                Log.e(TAG, "✗ Create tx blob exception", e);
                mainHandler.post(() -> cb.onError(e.getMessage()));
            } finally {
                if (pendingTx != null) {
                    try {
                        wallet.disposePendingTransaction();
                    } catch (Exception e) {
                        Log.w(TAG, "Failed to dispose blob pending transaction", e);
                    }
                }
                if (acquiredState) {
                    currentState.compareAndSet(WalletState.TRANSACTION, WalletState.IDLE);
                }
                walletLifecycleLock.readLock().unlock();
            }
        });
    }
    
    /**
     * CRITICAL: Update cached balances from wallet
     * This method must be called before closing wallet to ensure balances are current
     */
    private void updateBalancesFromWallet() {
        try {
            long[] snapshot = readWalletBalance();
            long bal = snapshot[0];
            long unl = snapshot[1];
            
            balance.set(bal);
            unlockedBalance.set(unl);
            
            Log.d(TAG, "[BALANCE] Updated: balance=" + convertAtomicToXmr(bal)
                    + " XMR (" + bal + " atomic), unlocked=" + convertAtomicToXmr(unl)
                    + " XMR (" + unl + " atomic)");
            
        } catch (Exception e) {
            Log.e(TAG, "[BALANCE] ✗ Failed to update balances", e);
        }
    }    

    /**
     * Submit transaction blob to network
     * Required by: BitchatMoneroTransfer.saveAndSubmitTransaction()
     */
    public void submitTxBlob(byte[] blobBytes, TxBlobCallback callback) {
        Log.i(TAG, "=== SUBMIT TX BLOB REQUESTED ===");
        if (callback == null) return;
        if (blobBytes == null || blobBytes.length == 0) {
            mainHandler.post(() -> callback.onError("Transaction blob is empty"));
            return;
        }
        
        executorService.execute(() -> {
            boolean acquiredState = false;
            File tempBlobFile = null;
            walletLifecycleLock.readLock().lock();
            try {
                if (!isInitialized || wallet == null) {
                    mainHandler.post(() -> callback.onError("Wallet not initialized"));
                    return;
                }
                if (!currentState.compareAndSet(WalletState.IDLE, WalletState.TRANSACTION)) {
                    mainHandler.post(() -> callback.onError("Wallet busy: " + currentState.get()));
                    return;
                }
                acquiredState = true;
                
                // Save blob to temporary file first
                tempBlobFile = new File(context.getCacheDir(), "tx_blob_" + System.currentTimeMillis() + ".tmp");
                Files.write(tempBlobFile.toPath(), blobBytes);
                
                // Create a pending transaction from the blob file
                // Note: This approach may not work directly since we need the proper method to create from blob
                // For now, we'll use an alternative approach
                
                // Convert bytes to hex for wallet.submitTransaction
                StringBuilder hexBuilder = new StringBuilder(blobBytes.length * 2);
                for (byte b : blobBytes) {
                    hexBuilder.append(String.format("%02x", b & 0xff));
                }
                String hexBlob = hexBuilder.toString();

                Log.d(TAG, "Submitting transaction blob (" + blobBytes.length + " bytes)...");
                String txId = wallet.submitTransaction(hexBlob);

                if (txId == null || txId.isEmpty()) {
                    String error = wallet.getErrorString();
                    mainHandler.post(() -> callback.onError("Submit failed: " + (error != null ? error : "Unknown error")));
                    return;
                }

                Log.i(TAG, "✓ Transaction submitted: " + txId);

                // Store wallet state
                wallet.store();
                updateBalancesFromWallet();

                final String finalTxId = txId;
                final String finalBase64 = android.util.Base64.encodeToString(blobBytes, android.util.Base64.NO_WRAP);
                mainHandler.post(() -> callback.onSuccess(finalTxId, finalBase64));

            } catch (Exception e) {
                Log.e(TAG, "✗ Submit tx blob exception", e);
                final String errorMsg = e.getMessage() != null ? e.getMessage() : "Unknown error";
                mainHandler.post(() -> callback.onError("Submit failed: " + errorMsg));
            } finally {
                if (tempBlobFile != null && tempBlobFile.exists() && !tempBlobFile.delete()) {
                    Log.w(TAG, "Failed to delete temporary transaction blob");
                }
                if (acquiredState) {
                    currentState.compareAndSet(WalletState.TRANSACTION, WalletState.IDLE);
                }
                walletLifecycleLock.readLock().unlock();
            }
        });
    }

    /**
     * Get daemon address from wallet manager
     */
    public String getDaemonAddress() {
        return walletManager.getDaemonAddress();
    }

    /**
     * Get daemon port from wallet manager
     */
    public int getDaemonPort() {
        return walletManager.getDaemonPort();
    }

    private void cleanWalletFiles(File dir, String walletName) {
        if (dir == null) return;
        if (walletName != null) {
            String base = new File(dir, walletName).getAbsolutePath();
            String[] suffixes = {"", ".keys", ".address.txt", ".cache", ".old", ".new", ".keys.old", ".keys.new"};
            for (String suffix : suffixes) {
                try {
                    File f = new File(base + suffix);
                    if (f.exists()) {
                        boolean del = f.delete();
                        Log.d(TAG, "Deleted wallet file " + f.getName() + ": " + del);
                    }
                } catch (Exception ignored) {}
            }
        }
        if (dir.exists() && dir.listFiles() != null) {
            for (File f : dir.listFiles()) {
                try {
                    if (f.isDirectory()) {
                        deleteDirRecursive(f);
                    } else {
                        f.delete();
                    }
                } catch (Exception ignored) {}
            }
        }
    }

    private void deleteDirRecursive(File fileOrDir) {
        if (fileOrDir.isDirectory()) {
            File[] children = fileOrDir.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteDirRecursive(child);
                }
            }
        }
        fileOrDir.delete();
    }
}

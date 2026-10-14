package com.bitchat.android.monero

import com.bitchat.android.monero.bluetooth.MoneroLookupRetryPolicy
import com.bitchat.android.monero.wallet.WalletSuite
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MoneroAmountAndLookupPolicyTest {
    @Test
    fun `xmr conversion preserves all twelve atomic decimals`() {
        assertEquals(1L, WalletSuite.convertXmrToAtomic("0.000000000001"))
        assertEquals(123456789012L, WalletSuite.convertXmrToAtomic("0.123456789012"))
        assertEquals(68_340_000L, WalletSuite.convertXmrToAtomic("0.00006834"))
        assertEquals(-1L, WalletSuite.convertXmrToAtomic("0.1234567890123"))
        assertEquals(-1L, WalletSuite.convertXmrToAtomic("not-an-amount"))
        assertEquals("0.000001", WalletSuite.convertAtomicToXmr(1_000_000L))
        assertEquals("0.00006834", WalletSuite.convertAtomicToXmr(68_340_000L))
    }

    @Test
    fun `automatic lookup starts quickly and backs off to a bounded poll`() {
        assertEquals(3_000L, MoneroLookupRetryPolicy.delayBeforeNextAttempt(0))
        assertEquals(10_000L, MoneroLookupRetryPolicy.delayBeforeNextAttempt(1))
        assertEquals(300_000L, MoneroLookupRetryPolicy.delayBeforeNextAttempt(50))
        assertTrue(MoneroLookupRetryPolicy.WAITING_NOTICE_AFTER_MS >= 60_000L)
    }
}

package com.bitchat.android.monero.bluetooth

/**
 * Backoff policy for automatic incoming transaction discovery.
 *
 * A transaction can be announced before the receiver's wallet has refreshed
 * past the block that contains it.  Retrying forever with a fixed short delay
 * would waste battery and put unnecessary pressure on the daemon, while a
 * short fixed retry window creates false "not found" results.  This policy
 * starts quickly and then settles into a lightweight five-minute poll.
 */
object MoneroLookupRetryPolicy {
    private val delaysMs = longArrayOf(
        3_000L,
        10_000L,
        20_000L,
        30_000L,
        60_000L,
        120_000L,
        300_000L
    )

    const val WAITING_NOTICE_AFTER_MS = 120_000L

    fun delayBeforeNextAttempt(attempt: Int): Long {
        val safeAttempt = attempt.coerceAtLeast(0)
        return delaysMs[safeAttempt.coerceAtMost(delaysMs.lastIndex)]
    }
}

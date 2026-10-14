package com.bitchat.android.monero.messaging

import com.bitchat.android.model.BitchatMessage
import com.bitchat.android.model.PrivateMessagePacket
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Date

class MoneroMessageHandlerTest {
    private val txId = "a".repeat(64)

    @Test
    fun `transaction id builder round trips through the incoming parser`() {
        val received = mutableListOf<MoneroMessageHandler.TransactionIdMessage>()
        val handler = handler(onTransactionIdReceived = { received += it })

        val encoded = handler.createTransactionIdMessage(
            txId = txId,
            amount = "0.000123456789",
            toAddress = "4${"A".repeat(94)}",
            blockHeight = 123L
        )

        val json = JSONObject(encoded.removePrefix(MoneroMessageHandler.TYPE_TRANSACTION_ID))
        assertEquals(txId, json.getString("tx_id"))
        assertEquals("0.000123456789", json.getString("amount"))
        assertEquals(123L, json.getLong("block_height"))

        val handled = handler.processMoneroMessage(
            BitchatMessage(
                sender = "Alice",
                senderPeerID = "stable-peer-id",
                content = encoded,
                timestamp = Date(1_700_000_000_000L)
            )
        )

        assertTrue(handled)
        assertEquals(1, received.size)
        assertEquals(txId, received.single().txId)
        assertEquals("stable-peer-id", received.single().fromUser)
        assertEquals("0.000123456789", received.single().amount)
        assertEquals(123L, received.single().blockHeight)
    }

    @Test
    fun `default transaction id announcement fits the legacy private message TLV limit`() {
        val encoded = handler().createTransactionIdMessage(
            txId = txId,
            amount = "0.0000032",
            toAddress = "4${"A".repeat(94)}"
        )

        val contentBytes = encoded.toByteArray(Charsets.UTF_8)
        assertTrue("TxID announcement must fit in one-byte TLV content", contentBytes.size <= 255)
        assertNotNull(
            "Private message encoding must not drop the TxID announcement",
            PrivateMessagePacket(messageID = "message-id", content = encoded).encode()
        )

        val json = JSONObject(encoded.removePrefix(MoneroMessageHandler.TYPE_TRANSACTION_ID))
        assertFalse("Unknown block height should not be serialized as zero", json.has("block_height"))
    }

    @Test
    fun `transaction found message reports amount and confirmations`() {
        val statuses = mutableListOf<Pair<String, String>>()
        val handler = handler(onPaymentStatusUpdated = { id, status -> statuses += id to status })
        val encoded = handler.createTransactionFoundMessage(txId, "0.5", 4L, 99L)

        assertTrue(
            handler.processMoneroMessage(
                BitchatMessage(
                    sender = "Alice",
                    content = encoded,
                    timestamp = Date()
                )
            )
        )

        assertEquals(txId, statuses.single().first)
        assertEquals("Found: 0.5 XMR, 4 confirmations", statuses.single().second)
    }

    @Test
    fun `signed transaction blob is consumed by the automatic payment callback`() {
        val payments = mutableListOf<MoneroMessageHandler.MoneroPaymentMessage>()
        val handler = MoneroMessageHandler(
            onPaymentReceived = { payments += it },
            onAddressShared = { _, _ -> },
            onPaymentRequested = {},
            onPaymentStatusUpdated = { _, _ -> },
            onTransactionIdReceived = {}
        )

        assertTrue(
            handler.processMoneroMessage(
                BitchatMessage(
                    sender = "Alice",
                    senderPeerID = "stable-peer-id",
                    content = "${MoneroMessageHandler.TYPE_TRANSACTION_BLOB}signed-blob",
                    timestamp = Date(1_700_000_000_000L)
                )
            )
        )

        assertEquals("signed-blob", payments.single().signedTxBlob)
        assertEquals("stable-peer-id", payments.single().fromUser)
    }

    @Test
    fun `non-monero messages are left for the normal chat pipeline`() {
        val handler = handler()

        assertFalse(
            handler.processMoneroMessage(
                BitchatMessage(sender = "Alice", content = "hello", timestamp = Date())
            )
        )
    }

    private fun handler(
        onPaymentStatusUpdated: (String, String) -> Unit = { _, _ -> },
        onTransactionIdReceived: (MoneroMessageHandler.TransactionIdMessage) -> Unit = {}
    ) = MoneroMessageHandler(
        onPaymentReceived = {},
        onAddressShared = { _, _ -> },
        onPaymentRequested = {},
        onPaymentStatusUpdated = onPaymentStatusUpdated,
        onTransactionIdReceived = onTransactionIdReceived
    )
}

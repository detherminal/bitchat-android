# Bitchat Monero - Reproduction and Verification Guide

This document describes how to build, reproduce, and verify the Monero flow.

## Scope

The verified default flow is `TXID_BASED`:

1. The sender wallet creates and broadcasts a real Monero transaction.
2. The sender sends the transaction ID to the receiver through a private chat.
3. The receiver automatically searches wallet history without asking the user
   for the TxID.
4. After the receiver finds the transaction, it sends a
   `[MONERO_TX_FOUND]` status message automatically.
5. Both sides can open transaction details from transaction history.

There is no search screen or manual TxID entry. If the receiver cannot find the
transaction immediately, it does not send a false "not found" result. It keeps
retrying with increasing delays and persists the pending record across restarts.

## Build

Build the debug APK with JDK 21 available on `PATH`:

```bash
java -version
./gradlew --no-daemon -Djava.net.preferIPv4Stack=true assembleDebug
```

Expected output:

```text
app/build/outputs/apk/debug/app-universal-debug.apk
```

## Two-device end-to-end test

Install the same debug APK on both physical devices. Use the device serial
number during installation to avoid targeting the wrong phone:

```bash
adb devices
adb -s <SENDER_SERIAL> install -r app/build/outputs/apk/debug/app-universal-debug.apk
adb -s <RECEIVER_SERIAL> install -r app/build/outputs/apk/debug/app-universal-debug.apk
```

### Preparation

1. Open Bitchat on both devices and grant Bluetooth/nearby-device permissions.
2. Confirm that both devices see each other in private chat and can exchange
   messages.
3. Wait for both wallets to connect to the daemon and for the receiver wallet
   to complete at least one sync.
4. Share the receiver Monero address through the private-chat wallet tools.
5. Confirm that the sender stored the correct destination address.

### Send

1. Open Monero Tools in the sender private chat.
2. Select the receiver and enter a valid XMR amount.
3. Wait for the fee/preflight result.
4. Review the amount, estimated fee, and total on the confirmation screen.
5. Confirm the send.

Expected sender behavior:

- A real transaction is created and broadcast.
- A successful-send message and the TxID appear in the chat stream.
- The wallet state returns to `IDLE`.
- The outgoing record appears after transaction history is refreshed.

Expected receiver behavior:

- The TxID message is processed automatically.
- A "waiting for wallet confirmation" state is shown.
- The receiver finds the transaction after it appears in wallet history.
- The receiver sends `[MONERO_TX_FOUND]` back to the sender.
- The amount and confirmation count are displayed.
- Opening the record in Transaction History shows the TxID, amount, net amount,
  fee, block height, confirmations, timestamp, unlock time, address, and
  transfer details.

## Resilience checks

Run each check together with its expected result:

| Check | Expected result |
| --- | --- |
| Open the receiver immediately after the transaction is sent | No false "not found" message is sent; automatic polling continues. |
| Stop and restart the receiver while a lookup is pending | The pending TxID is restored and lookup continues automatically. |
| Deliver the same TxID twice | The second announcement does not create another lookup loop. |
| Request a rescan during a send | The rescan is queued and starts after the transaction finishes. |
| Make a send fail | Wallet state returns to `IDLE` and another attempt is possible. |
| Use an insufficient unlocked balance | The send is blocked before confirmation with a clear fee-inclusive error. |
| Open history while sync is running | The list loads or shows a temporary busy state without crashing. |
| No new block is available | The 30-second check lightly checks daemon height without a full wallet refresh. |

## Automated tests

```bash
./gradlew --no-daemon -Djava.net.preferIPv4Stack=true testDebugUnitTest
```

The unit-test set covers at least:

- TxID message JSON serialize/parse round trips;
- use of the sender peer ID instead of a nickname;
- parsing `[MONERO_TX_FOUND]` amount and confirmation fields;
- connecting signed transaction blobs to the automatic payment callback;
- twelve-decimal-place XMR-to-atomic-unit conversion;
- bounded automatic lookup retry backoff;
- handling of normal chat messages through the Monero pipeline.

## Log verification

Capture separate logs for each device during the physical-device test:

```bash
adb -s <SERIAL> logcat -c
adb -s <SERIAL> logcat -v time | grep -E "WalletSuite|MoneroChatTransferManager|MONERO|Transaction"
```

Use these points when reviewing the test logs:

- transaction commit succeeded;
- automatic receiver lookup started;
- the transaction was found and confirmations were read;
- `[MONERO_TX_FOUND]` was sent and received;
- the history record and detail screen were displayed;
- the failed sync race beginning with `current state: TRANSACTION` did not
  recur after the operation.

## Bluetooth and emulator note

The Android emulator cannot directly use the computer's physical Bluetooth
adapter. Therefore, BLE mesh and Monero end-to-end verification must use two
physical Android devices. An emulator can be used for wallet UI, history,
restore, state, and unit/instrumented checks, but it does not replace the
two-peer BLE verification.

## Blob/offline path

The default transaction path is TxID-based and requires daemon connectivity so that
the receiver can see the transaction on-chain. Small signed transaction blobs
can be imported and submitted automatically through chat; `[XMR_TX_BLOB]` is no
longer shown as raw chat text. The large blob/file-transfer path requires a
separate transport integration and is outside the TxID-based verification flow.

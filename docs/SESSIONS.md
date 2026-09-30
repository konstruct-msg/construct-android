# Sessions — what Android does and what the core does

> **Actualized 2026-09-28**, construct-core `0.22.0+88b7de8`. Replaces `SESSION_INITIALIZATION.md`
> and `SESSTION_LIFECYCLE.md`, which described a protocol that no longer exists (ping/ready,
> SESSION_RESET_INIT, tie-break, heal, END_SESSION). They are in git history if you need to know
> what was there.

**The rule that governs this whole file: the core decides, Android executes.** Every session
decision — whether a message opens a state, which state decrypts, what to do when nothing
decrypts — is taken inside `construct-core` and returned as a typed `CfeAction`. Android fetches,
stores, sends and shows. If you are about to write a session decision in Kotlin, open
`construct-core/src/construct_core.udl` first: it is probably already there.

Decisions behind this file (vault `~/Code/construct-docs/decisions/`):

| Decision | What it says |
|---|---|
| `sessions-renew-by-sending.md` | A session renews by sending; a record keeps previous states; variant B replaced END_SESSION with a decryption error |
| `first-message-opens-without-the-server.md` | A first message opens with the key its sender certificate names — nothing is fetched to receive |
| `first-contact-queue-keyed-by-claimed-device.md` | Messages waiting for a session queue in the core, keyed by device |
| `session-is-one-state-machine.md` | The only phase left is "an init is in flight" (`Opening`), owned by the core |
| `identity-spaces.md` | Everything below the seam is a `CryptoDeviceId`, never an account id |

---

## 1. A session is a ratchet between two devices

A peer is an **account** above the seam (gRPC, Room, contacts) and a **set of devices** below it.
`PeerDeviceRegistry` maps one to the other; `CryptoManager` hands the core only device ids.
Mixing the spaces does not throw — the Double Ratchet AD no longer matches and the message simply
never opens.

## 2. Sending opens, sending renews

- No session with a device → `SessionManager.initSession` fetches that device's bundle and the
  core opens a state (PQXDH). The first message carries the handshake header.
- A session held → the message goes out on the current state.
- **Any** message carrying the handshake header opens a new state beside the one held. The record
  keeps previous states and the core promotes the one that decrypts. Two sides opening at once
  converge by themselves: nothing is announced, confirmed, tie-broken or waited for.
- The core's `Opening` phase is only a lock: two inits in flight would spend two one-time
  prekeys for one state.

## 3. Receiving opens without the server

A sealed first message carries a sender certificate. `ReceivingOpenUseCase` gives the core the
certificate and the server's keys; the core checks the server signature and opens with the key the
certificate names (`CfeAction.OpenReceiving`). Android never fetches the sender's bundle to receive
and never guesses which device wrote.

Messages that arrive before their session is open wait **in the core** (`MessageQueuedPendingInit`);
Android keeps only what the core's answers name by id and cannot carry — the envelope's content
type and timestamp (`HeldEnvelopes`, in memory). Nothing there decides what waits or what drains;
do not grow it into a second queue beside the core's.

### The responder learns who opened — post-quantum

The first flight also names the initiator's ML-KEM-1024 identity key (derived in the core from the
hybrid key, never stored separately). The responder pins it per device, encapsulates to it and mixes
the secret into its first reply; the answer rides on its messages until the initiator, able to
decapsulate, sends on a later chain. Only then is the responder's session `ReceivedProven`
(`decisions/responder-authenticates-initiator-by-kem.md`).

**Consequence for this app: a payload goes to the core whole.** The answer is a field of the wire
payload, and a message rebuilt from components drops it — the initiator then cannot read the
reply. The core exports `encryptToWire` / `decryptWirePayload` and nothing that takes components;
do not add a Kotlin copy of the layout.

## 4. When nothing decrypts — DECRYPTION_ERROR (content type 28)

There is **no END_SESSION** since 2026-09-28. The device that failed answers the writer:

```
reader                                          writer
  │ message on a state it cannot read              │
  │  core → SendDecryptionError(payload)           │
  │ ── sealed type 28 (fixed 191 bytes) ─────────▶ │
  │                                                │ core compares the named ratchet key:
  │                                                │  • current state → SessionRetired
  │                                                │      + ResendMessage(id) once
  │                                                │  • an older state → ResendMessage once
  │                                                │  • unknown → nothing (stale error)
  │ ◀── resent message, opens a new state ──────── │
```

- The payload names the **ratchet key** the unread message was written on, so a stale error
  (redelivered, reordered, about a state already replaced) is recognised exactly and does nothing.
  That is what the old time windows, cooldowns and retry budgets were guessing at — do not rebuild
  any of them.
- The payload is built and sealed by the core (to the writer's identity key). Android sends the
  bytes as they are: `SessionControlUseCase.sendDecryptionError`.
- `SessionRetired.withoutOneTimePrekey` → `SessionManager.openNextWithoutOneTimePrekey`: the reader
  said the prekey the handshake named is gone; the next open fetches the bundle without consuming
  one.
- `ResendMessage(messageId)` names the id **the peer received**. For a sealed copy that is the
  id the server assigned, so `ServerMessageIds` maps it back to ours before
  `SendMessageUseCase.resend`. The map is a Room table kept 30 days, as long as the server keeps
  a queue: the error usually arrives when the reader next comes online, after the sender has
  restarted (in memory until 2026-09-30, and such an error resent nothing). Only text is resent,
  and once — the core answers one error per message; a resend lost to the network is lost (plan
  B10).
- Content type 21 (END_SESSION) from an older build is acknowledged and ignored.

## 5. Local operations send nothing

Manual reset, chat deletion, contact deletion and logout are **local**. `CryptoManager.retireSession`
moves the current state aside so the next send opens a new one; deletion forgets the device. Nothing
is sent to the peer: a message that names no state is exactly what was removed.

## 6. Where the actions are executed

| Action | Executed in |
|---|---|
| everything returned for an incoming message | `MessageProcessor` → `ProcessorEffectsImpl` |
| everything returned for a timer, launch, reconnect or a decryption error received | `CfeTimerBridge` |
| `SaveToSecureStore(slot, data)` | `SessionStateStore` (Room), typed slots |

Both `when`s are exhaustive over `CfeAction`. A new core action does not compile until it is
handled — that is intended; never add an `else ->`.

**Persist before send.** Send and receive persist the state the core returned *before* the RPC,
so a crash cannot leave the peer ahead of our stored ratchet.

## 7. One-time prekeys survive the process

The core holds OTPK privates in memory only. Persist them before upload
(`KeystoreManager.saveOneTimePrekeys`), import them before `setLocalUserId`, re-persist after a
receiving open consumed one, and replace the server pool when nothing was persisted. Android lost
every one on each restart until 2026-09-24.

## 8. What is not done here

- **`CfeAction.OpenSession` is answered by an event** (core 0.21.0). `CfeTimerBridge.answerOpenSession`
  fetches the named device's bundle and sends `SessionBundleFetched` (or `SessionBundleUnavailable`);
  the core reopens inside the event and answers with the save of the record, the messages that
  waited behind the open, and the end of `Opening` — or `OPEN_SESSION_REFUSED`, the held session
  kept. Do not call `reopen_session` for it: that call returns an id and nothing else, which is
  how the record went unsaved and the queue undrained until 2026-09-28. The next ordinary message
  carries the handshake header. The stand check is `IMPLEMENTATION_PLAN.md` A1.
- Android↔iOS SENDER_SYNC between two devices of one account has not been run on the stand.

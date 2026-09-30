# service/

Long-running / background services. Sessions: `docs/SESSIONS.md`.

- `MessagingRuntime` — process-scoped lifecycle: restore CFE sessions, hydrate ACK store, drain pending, start stream + router + processor. Started from `AuthRepository` after register/login/restore. Not a ViewModel.
- `MessagingForegroundService` — hosts the runtime; delivery without GMS.
- `MessageRouter` — stream frames → domain events (dedup, sealed-resolve, control vs message).
- `MessageProcessor` — CFE `handleEvent` for an incoming message → [ProcessorEffects].
- `ProcessorEffectsImpl` — persist, E2E receipts, receiving open, DECRYPTION_ERROR send, retire and resend.
- `CfeTimerBridge` — core timers, launch, reconnect and a decryption error received → the same effects.
- `SessionManager` — initiator `initSession`, account↔device resolution, bundle fetch for sending.
- `HeldEnvelopes` — what the core's queue answers name by id and cannot carry. Not a second queue.
- `ServerMessageIds` — server-assigned id → our id, so a resend finds the message (Room, 30 days).

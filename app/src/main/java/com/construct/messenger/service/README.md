# service/

Long-running / background services.

- `MessagingRuntime` — process-scoped lifecycle: restore CFE sessions, hydrate ACK store, drain pending, start stream + router + processor. Started from `AuthRepository` after register/login/restore. Not a ViewModel.
- `MessageRouter` — stream frames → domain events (dedup, sealed-resolve, control vs message).
- `MessageProcessor` — CFE `handleEvent` → [ProcessorEffects].
- `ProcessorEffectsImpl` — persist to Room, session blobs, local ACK.
- `SessionManager` — initiator `initSession` (prekey fetch + core).

Plan: IMPLEMENTATION_PLAN.md → Phases 5.2, 6, 7. Send / heal / FCM / WebRTC still pending.

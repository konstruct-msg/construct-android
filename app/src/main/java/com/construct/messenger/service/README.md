# service/

Long-running / background services.

- `MessagingRuntime` — process-scoped lifecycle: restore CFE sessions, hydrate ACK store, drain pending, start stream + router + processor. Started from `AuthRepository` after register/login/restore. Not a ViewModel.
- `MessageRouter` — stream frames → domain events (dedup, sealed-resolve, control vs message).
- `MessageProcessor` — CFE `handleEvent` → [ProcessorEffects].
- `ProcessorEffectsImpl` — persist, E2E receipts, heal, END_SESSION, responder init.
- `SessionManager` — initiator `initSession`, responder bundle fetch, GetIdentityKey.

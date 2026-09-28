# domain/usecase/

Use cases: `RegisterUseCase`, `LoginUseCase`, `UploadPreKeysUseCase`, `RotateSignedPreKeyUseCase`,
`SendMessageUseCase` (send and the one resend a DECRYPTION_ERROR asks for), `SendReceiptUseCase`,
`SessionControlUseCase` (sends the core's sealed DECRYPTION_ERROR), `ReceivingOpenUseCase`
(a first contact and a peer's new state both open through it, from the sender certificate).
Still pending: `SetupRecoveryUseCase`, `CallUseCase`.

Plan: `docs/IMPLEMENTATION_PLAN.md` §3 (B4 recovery, C2 calls).

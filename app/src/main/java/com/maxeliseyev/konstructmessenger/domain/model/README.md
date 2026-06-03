# domain/model/

Plain domain models: `User`, `Conversation`, `Message`, `Keys`, `UserIdentity`.

Note: keep `ServerUserId` (UUID-36) and `CryptoDeviceId` (hex-32) distinct — never pass `CryptoDeviceId` into the Rust session layer.

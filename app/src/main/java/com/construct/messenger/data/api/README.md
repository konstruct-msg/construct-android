# data/api/

gRPC services + transport.

- `GrpcClient.kt`, `AuthService.kt`, `KeyService.kt`, `MessagingService.kt`, `MessageStreamService.kt`
- `VeilProxy.kt` — thin wrapper over the Rust VEIL happy-eyeballs coordinator (NOT a Kotlin fallback loop).

## Generated stubs

Proto sources live in `app/src/main/proto/` (vendored from `~/Code/construct-protos`).
gRPC + protobuf stubs are generated at build time by the `com.google.protobuf` Gradle
plugin (configured in `app/build.gradle`) — there is no checked-in `proto/` output dir.
A plain `./gradlew assembleDebug` regenerates them into `app/build/generated/source/proto/`:

- `java/` — protobuf-lite message classes
- `kotlin/` — Kotlin DSL builders
- `grpc/` — grpc-java service stubs
- `grpckt/` — grpc-kotlin coroutine stubs

To refresh after editing the schema: re-copy the `.proto` files from `construct-protos`
into `src/main/proto/`, then rebuild.

Plan: IMPLEMENTATION_PLAN.md → Phases 2.3, 5.

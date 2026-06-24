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

### Gotcha: grpc-kotlin stub class names

`protoc-gen-grpc-kotlin` nests each `XxxCoroutineStub` inside an `object XxxGrpcKt { ... }`
companion-style object — it is **not** a top-level class, even though the file name
(`XxxGrpcKt.kt` or `XxxOuterClassGrpcKt.kt` when there's a java outer-class collision)
suggests otherwise. Import the nested class explicitly:

```kotlin
// Wrong — "Unresolved reference" at compile time:
import shared.proto.services.v1.AuthServiceCoroutineStub

// Right:
import shared.proto.services.v1.AuthServiceGrpcKt.AuthServiceCoroutineStub
```

Verify the real name by grepping the generated source rather than guessing from the
proto/service name:

```bash
grep -rn "CoroutineStub" app/build/generated/source/proto/debug/grpckt/
```

(None of our `.proto` files set `java_package`/`java_multiple_files`, so the java package
is the proto `package` and, since every service name collides with its outer message class,
protoc appends `OuterClass` to the generated file/class name — e.g. `AuthService` service →
`AuthServiceOuterClass.java` (messages) + `AuthServiceOuterClassGrpcKt.kt` (grpc-kotlin), but
the grpc-kotlin object inside is still named `AuthServiceGrpcKt`, not
`AuthServiceOuterClassGrpcKt`.)

Plan: IMPLEMENTATION_PLAN.md → Phases 2.3, 5.

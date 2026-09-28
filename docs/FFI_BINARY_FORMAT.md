# Construct FFI Binary Format (CFE) — Полная Спецификация

> Статус: **реализовано и в проде** (Rust ядро + Swift + Android-биндинги).  
> Версия документа: 3.0  
> Зависимости: `rmp-serde`, `crc32fast`, `serde_bytes` — все уже в `Cargo.toml`.  
> Сверено с реализацией `construct-core/src/cfe/` на **2026-06-05** (ground truth —
> код, не этот документ; при расхождении правь документ).
>
> **С тех пор разошлось (2026-09-28):** запись сессии хранит прежние состояния
> (`CfePreviousStateV1`, с флагом `held_back`) и отметку выведенной сессии
> (`retired: CfeRetiredMarkV1`); очереди лечения (`heal_queue`) и архива сессий больше нет.
> Схемы §4.2 и §4.5 ниже сверяй с `construct-core/src/cfe/types.rs`, они не обновлены.

> [!important] Два разных бинарных рубежа — не путать
> 1. **CFE binary** (этот документ) — формат для **персистентности**:
>    `export_*` / `import_*` (сессии, приватные ключи, OTPK, состояние
>    оркестратора, Kyber-состояние). Из Kotlin/Swift это **непрозрачный blob**:
>    его не собирают и не парсят вручную — кодирование/декодирование целиком
>    внутри Rust.
> 2. **Типизированный UniFFI event-bus** — `handle_event(CfeIncomingEvent) ->
>    Vec<CfeAction>`, `encrypt_message`, `decrypt_message` и т.д. Здесь данные
>    пересекают FFI как **типизированные объекты** (sealed-классы в Kotlin),
>    которые маршалит сам UniFFI. **Это НЕ CFE.** Типы `InboundEvent` (0x10) и
>    `OutboundActions` (0x11) в каталоге ниже — зарезервированы и в живом
>    event-bus сейчас не используются (см. §4.6).

---

## Содержание

1. [Зачем меняем формат](#1-зачем-меняем-формат)
2. [Формат конверта CFE](#2-формат-конверта-cfe)
3. [Каталог типов сообщений](#3-каталог-типов-сообщений)
4. [Полные схемы payload](#4-полные-схемы-payload)
5. [Rust API (cfe.rs)](#5-rust-api-cfrs)
6. [Интеграция со Swift](#6-интеграция-со-swift)
7. [Интеграция с Android/Kotlin](#7-интеграция-с-androidkotlin)
8. [Стратегия миграции](#8-стратегия-миграции)
9. [Обработка ошибок](#9-обработка-ошибок)
10. [Версионирование схем](#10-версионирование-схем)
11. [Безопасность формата](#11-безопасность-формата)
12. [Тестирование](#12-тестирование)
13. [Дорожная карта реализации](#13-дорожная-карта-реализации)

---

## 1. Зачем меняем формат

### Проблемы текущего JSON+base64 подхода

```
PrivateKeysJson сейчас (JSON):
{
  "identity_secret": "Abc123...=",     ← 44 символа для 32 байт (base64 overhead 37%)
  "signing_secret": "Def456...=",
  "signed_prekey_secret": "Ghi789...=",
  "prekey_signature": "JklMno...=",    ← 88 символов для 64 байт
  "suite_id": "1",                     ← число как строка — нет типизации
  "identity_public_check": "Pqr012...=" ← костыль для integrity
}
```

| Проблема | Влияние сейчас | Влияние в будущем |
|---|---|---|
| Нет типа сообщения | Опечатка в ключе = silent failure | MLS/Calls: 10+ типов, не различимы |
| Base64 для байтов | +37% размер, encode/decode overhead | ML-KEM public key: 1184 bytes → 1580 base64 |
| Нет checksum | Порча Keychain → AEAD failure | Никак |
| Нет версии схемы | `#[serde(default)]` на каждом поле | Добавление Kyber ключей ломает старые клиенты |
| String через JNI (Android) | Не реализовано | JNI String → UTF-16 → UTF-8 за каждый вызов |
| Нет сжатия | Неважно сейчас | OpenMLS Welcome: 50-200KB, Commit: 1-50KB |

### Целевое состояние

```
PrivateKeys в CFE (MessagePack + 16-байтный заголовок):
[43 46 01 01 00 00 00 00] ← magic "CF" | ver=1 | type=PRIVATE_KEYS | flags=0
[38 00 00 00]             ← payload length = 56 bytes
[A1 B2 C3 D4]             ← CRC32 payload
[85 ...]                  ← MessagePack map (5 ключей)
  a7 "ik_priv" c4 20 [32 bytes raw]  ← bin8 формат, без base64
  a7 "sk_priv" c4 20 [32 bytes raw]
  a8 "spk_priv" c4 20 [32 bytes raw]
  a9 "spk_sig"  c4 40 [64 bytes raw]
  a8 "suite_id" 01                   ← integer, не строка
```

Экономия: ~40% от PrivateKeys, ~60% от OTPKBundle (нет base64 для 50×64 байт ключей).

---

## 2. Формат конверта CFE

### Байтовая структура

```
Byte offset:  0    1    2    3    4    5    6    7    8-11         12-15        16+
              ┌────┬────┬────┬────┬────┬────┬────┬────┬────────────┬────────────┬────────...
              │ C  │ F  │VER │TYPE│FLAG│ R0 │ R1 │ R2 │  PLEN (LE) │  CRC32(LE) │  PAYLOAD
              └────┴────┴────┴────┴────┴────┴────┴────┴────────────┴────────────┴────────...
                 Magic      Ver  Type Flags     Reserved    u32          u32       MessagePack
              ──────────────────────────────────────────────────────────────────────────────
              Минимальный размер: 16 байт (пустой payload)
              Поле PLEN — u32, но парсер ограничивает payload 256 KiB
              (MAX_PAYLOAD_LEN = 256 * 1024). Больше — ошибка PayloadTooLarge.
```

### Поля заголовка

| Поле | Offset | Size | Описание |
|---|---|---|---|
| `magic` | 0-1 | 2B | `0x43 0x46` ("CF" = ConstructFFI). Константа. |
| `version` | 2 | 1B | Версия формата конверта. Сейчас = `0x01`. |
| `msg_type` | 3 | 1B | Тип содержимого (enum ниже). |
| `flags` | 4 | 1B | Битовые флаги (ниже). |
| `reserved` | 5-7 | 3B | Зарезервировано, должны быть `0x00`. |
| `payload_len` | 8-11 | 4B | Длина payload в байтах, little-endian `u32`. |
| `crc32` | 12-15 | 4B | CRC32 только payload bytes, little-endian `u32`. |
| payload | 16+ | N | MessagePack-сериализованные данные. |

### Flags byte

> [!warning] Флаги определены, но **сейчас не поддержаны ни одного**.
> В коде `SUPPORTED_FLAGS_MASK = 0x00`, поэтому и `encode`, и парсер
> **отклоняют любой ненулевой `flags`** ошибкой `UnsupportedFlags`. Все боевые
> конверты сейчас имеют `flags = 0x00`. Биты ниже — зарезервированный план; пока
> их выставлять нельзя.

```
bit 0  (0x01) – COMPRESSED   (план) payload сжат через zstd — НЕ поддержано
bit 1  (0x02) – ENCRYPTED    (план) envelope encryption — НЕ поддержано
bit 2  (0x04) – CHUNKED      (план) часть многочастного payload — НЕ поддержано
bit 3  (0x08) – SIGNED       (план) Ed25519 подпись payload — НЕ поддержано
bits 4-7      – Reserved, = 0
```

### Условия валидности

`parse_header` отклоняет конверт (точный список из `envelope.rs`, каждый →
конкретный `CfeError`):

- `data.len() < 16` → `TooShort` (если данные похожи на старый JSON — сначала `LegacyJson`).
- `magic != [0x43, 0x46]` → `InvalidMagic` (или `LegacyJson` при детекте JSON).
- `version != 0x01` (строгое равенство, **не** `>`) → `UnsupportedVersion`.
- `msg_type` неизвестен (`from_u8` вернул `None`) → `UnknownType`.
- `flags & !SUPPORTED_FLAGS_MASK != 0`, т.е. любой ненулевой флаг → `UnsupportedFlags`.
- любой из reserved-байтов `[5,6,7] != 0` → `InvalidReservedBytes` (строго, не опционально).
- `payload_len > 256 KiB` → `PayloadTooLarge`.
- `data.len() < 16 + payload_len` → `TruncatedPayload`.
- `CRC32(payload) != stored_crc32` → `ChecksumMismatch`.
- (в `decode_as`) тип конверта ≠ ожидаемому → `TypeMismatch`.

---

## 3. Каталог типов сообщений

Точный список из `construct-core/src/cfe/types.rs` (`from_u8` — единственный
источник истины; неизвестный тег отвергается):

```rust
/// CFE message type registry
/// Значения 0x00 и не перечисленные ниже — неизвестны (UnknownType).
pub enum CfeMessageType {
    // ─── Ключевой материал / состояние (защищённое хранилище) ──────
    PrivateKeys       = 0x01,  // identity_secret + signing_secret + spk_secret (+ old_spks)
    SessionState      = 0x02,  // Double Ratchet session (CfeSessionStateV1)
    OtpkBundle        = 0x03,  // Vec<OtpkRecord> (id + priv + pub) + next_id
    RegistrationBundle= 0x04,  // Публичные ключи для отправки на сервер
    OrchestratorState = 0x05,  // Экспорт Orchestrator: ack-стор, healing, init-locks, archives, prekey_tracker
    SpkRotation       = 0x06,  // Новый SPK после ротации
    AppSettings       = 0x07,  // CfeAppSettingsV1 — настройки приложения
    ContactKeyBundle  = 0x08,  // CfeContactKeyBundleV1 — кэш чужого key-bundle

    // ─── Event/Action протокол ─────────────────────────────────────
    // ⚠️ ЗАРЕЗЕРВИРОВАНО. В живом event-bus НЕ используется — события/действия
    // ходят типизированными UniFFI-объектами CfeIncomingEvent/CfeAction (см. §4.6).
    InboundEvent      = 0x10,
    OutboundActions   = 0x11,

    // ─── Post-Quantum (ML-KEM-768) ──────────────────────────────────
    KyberPrivateKeys  = 0x20,  // Kyber SPK + OTPK private keys
    KyberSessionState = 0x21,  // CfeKyberSessionStateV1 — отложенные PQ-контрибуции + next_otpk_id

    // ─── Звонки ─────────────────────────────────────────────────────
    CallSignal        = 0x30,  // SDP offer/answer, ICE candidate
    CallKeyMaterial   = 0x31,  // DTLS-SRTP key export

    // ─── OpenMLS (групповые чаты) ──────────────────────────────────
    MlsWelcome        = 0x40,  // Welcome message
    MlsCommit         = 0x41,  // Commit message
    MlsProposal       = 0x42,  // Proposal message
    MlsKeyPackage     = 0x43,  // KeyPackage для добавления в группу

    // ─── Утилиты ────────────────────────────────────────────────────
    Generic           = 0x7F,  // Payload без фиксированной схемы (тесты/round-trip)
}
```

> Типы 0x06–0x08, 0x20–0x21, 0x30–0x31, 0x40–0x43 **зарегистрированы** (парсятся
> и используются для соответствующих export/import); это уже не «будущее», как было
> в ранних версиях документа. Полнота payload-схем для Calls/MLS может ещё
> уточняться — но теги стабильны.

### Правила использования типов

| Тип | Хранится в защ. хранилище? | Сжатие? | Шифрование конверта? |
|---|---|---|---|
| PrivateKeys | ✅ Обязательно | Нет (мало данных) | Нет (Keystore/Keychain сам шифрует) |
| SessionState | ✅ Обязательно | Нет | Нет |
| OtpkBundle | ✅ Обязательно | Нет (50 ключей ≈ 3KB) | Нет |
| OrchestratorState | ✅ Обязательно | Нет | Нет |
| KyberSessionState | ✅ Обязательно | Нет | Нет |
| InboundEvent / OutboundActions | ❌ зарезервировано, не используется | — | — |
| MlsWelcome | ✅ / по месту | Нет (флаги пока не поддержаны) | Нет |
| CallSignal | по месту | Нет | Нет |

> Колонка «Сжатие» сейчас всегда «Нет»: флаг COMPRESSED определён, но не
> поддержан (`SUPPORTED_FLAGS_MASK = 0x00`, см. §2).

---

## 4. Полные схемы payload

> Всё описано в MessagePack named-map нотации.
> `bin8` = raw bytes без base64. `uint8` = число 0-255.

### 4.1 PrivateKeys (0x01)

Соответствует текущему `PrivateKeysJson`. Сейчас в Keychain как JSON.

```
CfePrivateKeys {
    "suite_id"    : uint8          // 1 = X25519+ChaCha20, 2 = X25519+AES256, 3 = Kyber Hybrid
    "ik_priv"     : bin (32 bytes) // X25519 identity secret
    "sk_priv"     : bin (32 bytes) // Ed25519 signing secret
    "spk_priv"    : bin (32 bytes) // X25519 signed prekey secret
    "spk_sig"     : bin (64 bytes) // Ed25519 signature over SPK public key
    "spk_id"      : uint32         // SPK key ID (для сервера)
    // Поля ниже — вычислимые, хранятся для быстрой проверки целостности без полного re-derive
    "ik_pub"      : bin (32 bytes) // X25519 identity public (derived from ik_priv)
    "vk_pub"      : bin (32 bytes) // Ed25519 verifying key (derived from sk_priv)
    "spk_pub"     : bin (32 bytes) // X25519 SPK public (derived from spk_priv)
}
// Итого payload: ~9 поля × (имя + значение) ≈ 220 байт
// Против JSON: ~600 байт (base64 + кавычки + ключи)
// Экономия: ~63%
```

### 4.2 SessionState (0x02)

Соответствует `SerializableSession`. Сейчас в Keychain как JSON (в строке "sessions").

```
CfeSessionState {
    "ver"         : uint8          // схема сессии: 1
    "suite_id"    : uint8
    "contact_id"  : str            // UUID строка
    "local_uid"   : str            // UUID строка
    "session_id"  : bin (16 bytes) // деривированный shared session ID (hex → raw bytes)
    "rk"          : bin (32 bytes) // root_key
    "sck"         : bin (32 bytes) // sending_chain_key
    "rck"         : bin (32 bytes) // receiving_chain_key
    "scl"         : uint32         // sending_chain_length
    "rcl"         : uint32         // receiving_chain_length
    "psl"         : uint32         // previous_sending_length
    "dh_priv"     : bin (32 bytes) // dh_ratchet_private (optional, nil если нет)
    "dh_pub"      : bin (32 bytes) // dh_ratchet_public
    "rdh_pub"     : bin (32 bytes) // remote_dh_public (optional)
    "skipped"     : map { bin(32) → bin(32) }  // skipped_message_keys
    "pq_rk1"      : bin (32 bytes) // pre_pq_root_key для PQXDH (optional)
}
// Типичный размер (без skipped): ~420 байт
// JSON эквивалент: ~900 байт
```

### 4.3 OtpkBundle (0x03)

Соответствует `Vec<OtpkRecord>`. Сейчас в Keychain как JSON.

```
CfeOtpkBundle {
    "records" : array of {
        "id"      : uint32          // key_id
        "priv"    : bin (32 bytes)  // private key
        "pub"     : bin (32 bytes)  // public key (предустановлен для быстрого upload)
    }
    "next_id" : uint32              // next_otpk_id counter (!) важно для коллизий
}
// 50 записей: ~50 × (4+32+32) + overhead ≈ 3.5 KB
// JSON: ~50 × (int + 44 + 44) + overhead ≈ 5.5 KB
// Важно: "next_id" решает проблему сброса счётчика после пересоздания KeyManager
```

> **ВНИМАНИЕ**: Добавление `next_id` в схему решает потенциальную проблему:
> при пересоздании `KeyManager` счётчик сбрасывается до 1,000,000.
> Если OTPKs загружены, `import_one_time_prekeys` уже восстанавливает `next_otpk_id`
> из максимального `key_id`, но явное поле надёжнее.

### 4.4 RegistrationBundle (0x04)

Публичные ключи для отправки на сервер. Соответствует `RegistrationBundleJson`.

```
CfeRegistrationBundle {
    "suite_id"    : uint8
    "ik_pub"      : bin (32 bytes)  // identity public
    "spk_pub"     : bin (32 bytes)  // signed prekey public
    "spk_sig"     : bin (64 bytes)  // Ed25519 signature
    "vk_pub"      : bin (32 bytes)  // verifying key (Ed25519 public)
    "spk_id"      : uint32
    "created_at"  : uint64          // unix timestamp ms
}
```

### 4.5 OrchestratorState (0x05)

Полный экспорт всего состояния Orchestrator. Используется для backup/restore.

```
CfeOrchestratorState {
    "schema_ver"  : uint8           // версия схемы всего экспорта
    "user_id"     : str
    "keys"        : CfePrivateKeys  // вложенный, тот же формат что 0x01
    "otpks"       : CfeOtpkBundle   // вложенный, тот же формат что 0x03
    "sessions"    : map { str → CfeSessionState }  // contact_id → session
    "ack_store"   : array of {      // неподтверждённые сообщения
        "id"      : str
        "contact" : str
        "ts"      : uint64
        "attempts": uint8
    }
    "heal_queue"  : array of {      // healing queue entries
        "contact" : str
        "reason"  : uint8
        "ts"      : uint64
    }
    "exported_at" : uint64          // unix timestamp ms
}
```

### 4.6 InboundEvent (0x10) и OutboundActions (0x11) — ЗАРЕЗЕРВИРОВАНО

> [!warning] Это НЕ то, как реально работает event-bus.
> Теги 0x10/0x11 зарезервированы в каталоге, но в коде `construct-core`
> **нигде не используются** (нет ни `encode(InboundEvent, …)`, ни `decode_as`).
> CFE-сериализация событий/действий **не применяется**.

**Как event-bus устроен на самом деле.** Оркестратор экспортирует
типизированный FFI:

```rust
// uniffi_bindings.rs
pub fn handle_event(&self, event: CfeIncomingEvent) -> Result<Vec<CfeAction>, CryptoError>
```

`CfeIncomingEvent` и `CfeAction` — это UDL `[Enum] interface`, т.е. **sealed-классы**
в Kotlin / **enum с ассоциированными значениями** в Swift. Их маршалит сам UniFFI
(своим RustBuffer-кодированием), **а не CFE**. Из Kotlin ты:

1. конструируешь типизированное событие, напр. `CfeIncomingEvent.MessageReceived(...)`;
2. зовёшь `orchestratorCore.handleEvent(event)`;
3. получаешь `List<CfeAction>` (типизированные `SendEncryptedMessage`,
   `MessageDecrypted`, `DuplicateDropped`, `NotifyNewMessage`, …) и исполняешь их.

Никаких CFE-байтов на этом пути нет. Подробный список событий/действий и поток —
в Android-гайде (`construct-android/ANDROID_API_CRYPTO_GUIDE.md`, §2.4 «CFE: общение
с оркестратором») и в `construct-messenger` (`Security/CryptoManager.swift`,
`handleOrchestratorEvent`).

Старые схемы `CfeInboundEvent`/`CfeOutboundActions` (MessagePack-обёртки с
`type`/`contact_id`/`payload`) оставлены ниже **только как историческая заметка** на
случай, если 0x10/0x11 когда-нибудь задействуют для логирования/кросс-процесса —
в текущей реализации их игнорируй.

### 4.7 Будущие типы: Calls и MLS

```
CfeCallSignal {
    "call_id"     : str (UUID)
    "signal_type" : uint8           // 1=offer, 2=answer, 3=ice_candidate, 4=hangup
    "sdp"         : str (optional)
    "ice"         : str (optional)
    "ts"          : uint64
}

CfeMlsWelcome {
    "group_id"    : bin
    "welcome_msg" : bin             // opaque MLS Welcome bytes, FLAGS=COMPRESSED
    "epoch"       : uint64
}
```

---

## 5. Rust API (cfe.rs)

### Структура файла

```
construct-core/src/
└── cfe/
    ├── mod.rs           ← re-exports
    ├── envelope.rs      ← Header + encode/decode
    ├── types.rs         ← CfeMessageType enum + schema structs
    ├── error.rs         ← CfeError enum
    └── compat.rs        ← Legacy JSON migration helpers
```

### envelope.rs

```rust
use crc32fast::Hasher as Crc32Hasher;

pub const CFE_MAGIC: [u8; 2] = [0x43, 0x46]; // "CF"
pub const CFE_VERSION: u8 = 0x01;
pub const CFE_HEADER_LEN: usize = 16;

// Флаги определены, но НИ ОДИН не поддержан: SUPPORTED_FLAGS_MASK == 0x00.
pub const FLAG_COMPRESSED: u8 = 0x01;
pub const FLAG_ENCRYPTED:  u8 = 0x02;
pub const FLAG_CHUNKED:    u8 = 0x04;
pub const FLAG_SIGNED:     u8 = 0x08;
pub const SUPPORTED_FLAGS_MASK: u8 = 0x00;

/// Maximum allowed payload size (256 KiB). Защита от malformed length field.
pub const MAX_PAYLOAD_LEN: usize = 256 * 1024;

pub struct CfeEnvelope {
    pub version:     u8,
    pub msg_type:    CfeMessageType,
    pub flags:       u8,
    pub payload:     Vec<u8>,
}

/// Encode: структура → envelope bytes
pub fn encode<T: Serialize>(msg_type: CfeMessageType, value: &T) -> Result<Vec<u8>, CfeError> {
    encode_with_flags(msg_type, value, 0)
}

pub fn encode_with_flags<T: Serialize>(
    msg_type: CfeMessageType,
    value: &T,
    flags: u8,
) -> Result<Vec<u8>, CfeError> {
    // Любой неподдержанный флаг (а сейчас это ВСЕ) → ошибка. Сжатия здесь нет.
    if flags & !SUPPORTED_FLAGS_MASK != 0 {
        return Err(CfeError::UnsupportedFlags(flags));
    }

    // to_vec_named → MessagePack-MAP с именами полей (важно для кросс-платформы:
    // и Swift, и Kotlin десериализуют по именам, а не по позициям).
    let payload = rmp_serde::to_vec_named(value)
        .map_err(|e| CfeError::SerializeFailed(e.to_string()))?;

    let crc = {
        let mut h = Crc32Hasher::new();
        h.update(&payload);
        h.finalize()
    };

    let mut out = Vec::with_capacity(CFE_HEADER_LEN + payload.len());
    out.extend_from_slice(&CFE_MAGIC);
    out.push(CFE_VERSION);
    out.push(msg_type as u8);
    out.push(flags);
    out.extend_from_slice(&[0u8; 3]); // reserved
    out.extend_from_slice(&(payload.len() as u32).to_le_bytes());
    out.extend_from_slice(&crc.to_le_bytes());
    out.extend_from_slice(&payload);
    Ok(out)
}

/// Decode: bytes → typed структура
pub fn decode<T: DeserializeOwned>(data: &[u8]) -> Result<CfeEnvelope, CfeError> {
    let header = parse_header(data)?;
    // payload уже извлечён и CRC проверен в parse_header
    Ok(header)
}

pub fn decode_as<T: DeserializeOwned>(
    data: &[u8],
    expected_type: CfeMessageType,
) -> Result<T, CfeError> {
    let envelope = parse_header(data)?;
    if envelope.msg_type != expected_type {
        return Err(CfeError::TypeMismatch {
            expected: expected_type,
            got: envelope.msg_type,
        });
    }
    rmp_serde::from_slice(&envelope.payload)
        .map_err(|e| CfeError::DeserializeFailed(e.to_string()))
}

fn parse_header(data: &[u8]) -> Result<CfeEnvelope, CfeError> {
    if data.len() < CFE_HEADER_LEN {
        return Err(CfeError::TooShort { min: CFE_HEADER_LEN, got: data.len() });
    }
    if &data[0..2] != CFE_MAGIC {
        // Проверяем не является ли это legacy JSON (для migration path)
        if data.first() == Some(&b'{') || data.first() == Some(&b'[') {
            return Err(CfeError::LegacyJson);
        }
        return Err(CfeError::InvalidMagic);
    }
    let version = data[2];
    if version != CFE_VERSION {
        return Err(CfeError::UnsupportedVersion(version));
    }
    let msg_type = CfeMessageType::from_u8(data[3])
        .ok_or(CfeError::UnknownType(data[3]))?;
    let flags = data[4];
    // data[5..8] = reserved, проверяем что нули
    if data[5..8] != [0u8; 3] {
        return Err(CfeError::InvalidReservedBytes);
    }
    let payload_len = u32::from_le_bytes(data[8..12].try_into().unwrap()) as usize;
    if payload_len > MAX_PAYLOAD_LEN {
        return Err(CfeError::PayloadTooLarge { max: MAX_PAYLOAD_LEN, got: payload_len });
    }
    let total_len = CFE_HEADER_LEN + payload_len;
    if data.len() < total_len {
        return Err(CfeError::TruncatedPayload {
            expected: payload_len,
            got: data.len() - CFE_HEADER_LEN,
        });
    }
    let stored_crc = u32::from_le_bytes(data[12..16].try_into().unwrap());
    let raw_payload = &data[CFE_HEADER_LEN..total_len];

    // CRC32 check — обнаруживает Keychain corruption
    let mut h = Crc32Hasher::new();
    h.update(raw_payload);
    let actual_crc = h.finalize();
    if stored_crc != actual_crc {
        return Err(CfeError::ChecksumMismatch {
            stored: stored_crc,
            computed: actual_crc,
        });
    }

    // Decompress если нужно
    let payload = if flags & FLAG_COMPRESSED != 0 {
        zstd::decode_all(raw_payload).map_err(|e| CfeError::DecompressFailed(e.to_string()))?
    } else {
        raw_payload.to_vec()
    };

    Ok(CfeEnvelope { version, msg_type, flags, payload })
}
```

### error.rs

```rust
#[derive(Debug, thiserror::Error)]
pub enum CfeError {
    #[error("Data too short: need ≥{min} bytes, got {got}")]
    TooShort { min: usize, got: usize },

    #[error("Invalid magic bytes (expected 0x4346)")]
    InvalidMagic,

    #[error("Legacy JSON detected — use migration path")]
    LegacyJson,

    #[error("Unsupported CFE version {0}")]
    UnsupportedVersion(u8),

    #[error("Unknown message type 0x{0:02X}")]
    UnknownType(u8),

    #[error("CRC32 mismatch: stored=0x{stored:08X}, computed=0x{computed:08X} — data corruption!")]
    ChecksumMismatch { stored: u32, computed: u32 },

    #[error("Payload too large: max={max}, got={got}")]
    PayloadTooLarge { max: usize, got: usize },

    #[error("Truncated payload: expected={expected}, got={got}")]
    TruncatedPayload { expected: usize, got: usize },

    #[error("Invalid reserved bytes (must be 0x000000)")]
    InvalidReservedBytes,

    #[error("Type mismatch: expected {expected:?}, got {got:?}")]
    TypeMismatch { expected: CfeMessageType, got: CfeMessageType },

    #[error("Serialize failed: {0}")]
    SerializeFailed(String),

    #[error("Deserialize failed: {0}")]
    DeserializeFailed(String),

    #[error("Compress failed: {0}")]
    CompressFailed(String),

    #[error("Decompress failed: {0}")]
    DecompressFailed(String),
}
```

### Пример использования в uniffi_bindings.rs

```rust
// БЫЛО:
pub fn export_private_keys_json(&self) -> Result<String, CryptoError> {
    // ... собираем PrivateKeysJson
    serde_json::to_string(&private_keys_json).map_err(|_| CryptoError::SerializationFailed)
}

pub fn import_private_keys_json(&self, json: String) -> Result<(), CryptoError> {
    let keys: PrivateKeysJson = serde_json::from_str(&json)...
}

// СТАЛО:
pub fn export_private_keys(&self) -> Result<Vec<u8>, CryptoError> {
    // ... собираем CfePrivateKeys (новая схема, те же данные)
    cfe::encode(CfeMessageType::PrivateKeys, &private_keys)
        .map_err(|e| CryptoError::SerializationFailed)
}

pub fn import_private_keys(&self, data: Vec<u8>) -> Result<(), CryptoError> {
    let keys: CfePrivateKeys = cfe::decode_as(&data, CfeMessageType::PrivateKeys)
        .or_else(|e| {
            // Fallback: legacy JSON migration
            if matches!(e, CfeError::LegacyJson) {
                let s = std::str::from_utf8(&data)?;
                let legacy: PrivateKeysJson = serde_json::from_str(s)?;
                return Ok(migrate_private_keys(legacy));
            }
            Err(e)
        })?;
    // ... загружаем в KeyManager
}
```

---

## 6. Интеграция со Swift

### UDL изменения

```idl
// construct_core.udl

// БЫЛО:
interface CryptoCore {
    [Throws=CryptoError]
    string export_private_keys_json();
    [Throws=CryptoError]
    void import_private_keys_json(string json);

    [Throws=CryptoError]
    string export_session_json(string contact_id);
    [Throws=CryptoError]
    void import_session_json(string contact_id, string json);

    [Throws=CryptoError]
    string export_one_time_prekeys_json();
    [Throws=CryptoError]
    void import_one_time_prekeys_json(string json);
};

// СТАЛО:
interface CryptoCore {
    [Throws=CryptoError]
    bytes export_private_keys();   // → Swift Data
    [Throws=CryptoError]
    void import_private_keys(bytes data);

    [Throws=CryptoError]
    bytes export_session(string contact_id);
    [Throws=CryptoError]
    void import_session(string contact_id, bytes data);

    [Throws=CryptoError]
    bytes export_one_time_prekeys();
    [Throws=CryptoError]
    void import_one_time_prekeys(bytes data);
};
```

### Swift KeychainManager изменения

```swift
// KeychainManager.swift

// БЫЛО:
func savePrivateKeys(_ json: String) {
    let data = json.data(using: .utf8)!
    // ... save to Keychain
}

func loadPrivateKeys() -> String? {
    guard let data = loadFromKeychain(key: "private_keys") else { return nil }
    return String(data: data, encoding: .utf8)
}

// СТАЛО:
func savePrivateKeys(_ cfeData: Data) {
    // Никаких конвертаций — сохраняем байты напрямую
    saveToKeychain(cfeData, key: "private_keys_v2")
}

func loadPrivateKeys() -> Data? {
    // Пробуем новый формат
    if let data = loadFromKeychain(key: "private_keys_v2") {
        return data
    }
    // Fallback: legacy JSON
    if let legacyData = loadFromKeychain(key: "private_keys") {
        return legacyData  // Rust-сторона сам мигрирует через CfeError::LegacyJson path
    }
    return nil
}
```

### Swift SessionManager изменения

```swift
// SessionManager.swift или CryptoManager.swift

// БЫЛО:
func persistSession(for contactId: String) {
    guard let json = try? core.exportSessionJson(contactId: contactId) else { return }
    keychain.save(json.data(using: .utf8)!, key: "session_\(contactId)")
}

func restoreSession(for contactId: String) {
    guard let data = keychain.load(key: "session_\(contactId)"),
          let json = String(data: data, encoding: .utf8) else { return }
    try? core.importSessionJson(contactId: contactId, json: json)
}

// СТАЛО:
func persistSession(for contactId: String) {
    guard let cfeData = try? core.exportSession(contactId: contactId) else { return }
    keychain.save(cfeData, key: "session_v2_\(contactId)")
}

func restoreSession(for contactId: String) {
    if let data = keychain.load(key: "session_v2_\(contactId)") {
        try? core.importSession(contactId: contactId, data: data)
    } else if let legacyData = keychain.load(key: "session_\(contactId)") {
        // Legacy key — Rust мигрирует автоматически
        try? core.importSession(contactId: contactId, data: legacyData)
    }
}
```

### Swift OtpkReplenishmentService изменения

```swift
// OtpkReplenishmentService.swift

// БЫЛО:
func persistOtpks(core: CryptoCore) {
    guard let json = try? core.exportOneTimePrekeysJson() else { return }
    keychain.save(json.data(using: .utf8)!, key: "otpks")
}

// СТАЛО:
func persistOtpks(core: CryptoCore) {
    guard let cfeData = try? core.exportOneTimePrekeys() else { return }
    keychain.save(cfeData, key: "otpks_v2")
}
```

---

## 7. Интеграция с Android/Kotlin

> Android-биндинги **существуют**: пакет `uniffi.construct_core`, файл
> `construct-android/app/src/main/java/com/construct/messenger/crypto/uniffi/construct_core/construct_core.kt`
> (сгенерирован `build_crypto_lib.sh`, редактировать руками нельзя). Боевые классы
> ядра — `ClassicCryptoCore` (bootstrap до логина) и `OrchestratorCore` (после
> `setLocalUserId`). См. также `construct-android/ANDROID_API_CRYPTO_GUIDE.md`.

### Главное: из Kotlin ты НЕ собираешь CFE вручную

CFE-конверт (magic `CF`, 16-байтовый заголовок, MessagePack-payload, CRC32) целиком
кодируется и декодируется **внутри Rust**. Из Kotlin CFE-данные — это **непрозрачный
blob**, который ты получаешь от `export_*` и кладёшь обратно в `import_*`. Заголовок
и payload **не парсятся** на Kotlin-стороне.

Что реально пересекает FFI и как:

| Назначение | API | Что в Kotlin | CFE? |
|---|---|---|---|
| Персистентность (сессии, ключи, OTPK, состояние) | `exportSession` / `importSession`, `exportPrivateKeys` / `importPrivateKeys`, `exportOneTimePrekeys` / `importOneTimePrekeys`, `exportOrchestratorState` / `importOrchestratorState` | `List<UByte>` (blob) | ✅ CFE-внутри-Rust |
| Сырые ключи | `getIdentityKeyBytes()`, `getSigningKeyBytes()` | `ByteArray` | ❌ просто байты |
| Живые операции / event-bus | `handleEvent(CfeIncomingEvent): List<CfeAction>`, `encryptMessage`, `decryptMessage` | типизированные объекты | ❌ типизированный UniFFI |

> [!warning] CFE-blob'ы приходят как `List<kotlin.UByte>`, **не** `ByteArray`.
> Это артефакт UniFFI для Rust `Vec<u8>` в этих сигнатурах. Прячь конверсию
> `List<UByte> ↔ ByteArray` внутри `CryptoManager`, наружу отдавай `ByteArray`.

```kotlin
// Конверсия blob ↔ ByteArray (держать в одном месте)
fun List<UByte>.toByteArray(): ByteArray = ByteArray(size) { this[it].toByte() }
fun ByteArray.toUByteList(): List<UByte> = map { it.toUByte() }
```

### Поток персистентности (export → Keystore → import)

```kotlin
// CryptoManager.kt (Android) — единственная точка доступа к ядру
class CryptoManager(private val keystore: AndroidKeystore) {
    private var orchestratorCore: OrchestratorCore? = null   // создаётся в setLocalUserId()

    fun persistPrivateKeys() {
        val core = orchestratorCore ?: return
        val cfeBlob: ByteArray = core.exportPrivateKeys().toByteArray()  // CFE-blob из Rust
        keystore.save("private_keys_v2", cfeBlob)                         // храним как есть
    }

    fun restorePrivateKeys(core: OrchestratorCore): Boolean {
        val blob = keystore.load("private_keys_v2")
            ?: keystore.load("private_keys")   // legacy JSON — Rust сам мигрирует (см. §8)
            ?: return false
        core.importPrivateKeys(blob.toUByteList())   // отдаём blob обратно, не парсим
        return true
    }

    fun persistSession(contactId: String) {
        val core = orchestratorCore ?: return
        val cfeBlob: ByteArray = core.exportSession(contactId).toByteArray()
        keystore.save("session_v2_$contactId", cfeBlob)
    }
}
```

Бинарное правило (как на iOS): **никакого JSON/base64 в прикладном коде** для крипто-
данных. Blob ходит как байты; единственный base64 — это внутреннее представление
`EncryptedSharedPreferences` (см. ниже), не часть протокола.

### Поток живых операций (event-bus — НЕ CFE)

Для обработки сообщений ты не трогаешь байты вообще — конструируешь типизированное
событие и исполняешь возвращённые действия:

```kotlin
// Пришло входящее сообщение из MessageStream
val actions: List<CfeAction> = core.handleEvent(
    CfeIncomingEvent.MessageReceived(
        contactId = senderId,
        ephemeralPublicKey = env.ephemeralKey.toUByteList(),
        messageNumber = env.messageNumber,
        content = env.ciphertext.toUByteList(),
        // … поля по сигнатуре биндинга
    )
)
for (action in actions) when (action) {
    is CfeAction.MessageDecrypted   -> showInUi(action)
    is CfeAction.SendReceipt        -> messaging.sendAck(action)
    is CfeAction.DuplicateDropped   -> markProcessed(action)
    is CfeAction.NotifyNewMessage   -> notify(action)
    // … остальные варианты
    else -> Unit
}
```

> Threading: `handleEvent` и любые крипто-вызовы — строго с одного потока
> (крипто-диспетчер / Main) под reentrant-локом. Детали — в
> `ANDROID_API_CRYPTO_GUIDE.md` §2.6.

### Почему `List<UByte>`/`ByteArray`, а не `String`

В JNI `String` проходит `Rust String → UniFFI → JNI → Kotlin String` с UTF-16
конверсией на каждом шаге. Бинарь идёт `Rust Vec<u8> → UniFFI → Kotlin` без
строковой перекодировки — поэтому крипто-данные принципиально бинарные, а не
JSON-строки.

### Android Keystore интеграция

```kotlin
class AndroidKeystore(context: Context) {
    private val prefs = EncryptedSharedPreferences.create(
        context,
        "construct_keystore",
        MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun save(key: String, data: ByteArray) {
        // base64 — лишь способ положить bytes в SharedPreferences (значение уже под AES-256-GCM).
        prefs.edit().putString(key, Base64.encodeToString(data, Base64.NO_WRAP)).apply()
    }

    fun load(key: String): ByteArray? =
        prefs.getString(key, null)?.let { Base64.decode(it, Base64.NO_WRAP) }
}
```

### Когда Kotlin-разработчику всё-таки нужна байтовая структура CFE

Только для **отладки и кросс-платформенных тестов**, не для прода:
- Первые 2 байта сохранённого blob — `0x43 0x46` (`CF`), байт 2 — версия `0x01`,
  байт 3 — `msg_type` (напр. `0x02` для SessionState). Удобно ассертить в тестах.
- Один и тот же логический объект, закодированный Swift и Kotlin, даёт **идентичные
  байты** (обе платформы зовут один Rust + `to_vec_named`). Тестовые векторы из
  §12 переиспользуются как есть.

---

## 8. Стратегия миграции

### Принципы

1. **Zero downtime**: приложение работает с обоими форматами одновременно
2. **Unidirectional**: старый → новый, никогда новый → старый
3. **Lazy migration**: конвертируем при первом чтении, не при установке обновления
4. **Rollback safe**: старый Keychain key остаётся до явного удаления

### Алгоритм миграции при старте

```rust
// В uniffi_bindings.rs, при импорте ключей:

pub fn import_private_keys(&self, data: Vec<u8>) -> Result<(), CryptoError> {
    match cfe::decode_as::<CfePrivateKeys>(&data, CfeMessageType::PrivateKeys) {
        Ok(keys) => {
            // Новый путь — загружаем CFE данные
            self.load_from_cfe_private_keys(keys)
        }
        Err(CfeError::LegacyJson) => {
            // Старый путь — парсим JSON и мигрируем
            let json_str = std::str::from_utf8(&data)
                .map_err(|_| CryptoError::InvalidKeyData)?;
            let legacy: PrivateKeysJson = serde_json::from_str(json_str)
                .map_err(|_| CryptoError::InvalidKeyData)?;

            // Загружаем
            let result = self.load_from_legacy_private_keys(legacy);

            // Сигнализируем Swift что нужно пересохранить в новом формате
            // (через return value или отдельный флаг)
            // Swift: if needsMigration { persistPrivateKeys() }
            result
        }
        Err(CfeError::ChecksumMismatch { .. }) => {
            // Данные повреждены — нужна регистрация заново
            Err(CryptoError::KeyDataCorrupted)
        }
        Err(e) => Err(CryptoError::InvalidKeyData)
    }
}
```

### Swift migration flow

```swift
// AppDelegate или CryptoManager.init():

func initializeCrypto() async {
    // 1. Загружаем ключи (любой формат)
    guard let keysData = keychain.loadPrivateKeys() else {
        await registerNewUser()
        return
    }

    do {
        try core.importPrivateKeys(keysData)

        // 2. Если данные были в старом формате — пересохраняем в CFE
        if keysData.isCfeFormat == false {  // extension на Data, проверяет magic
            let newCfeData = try core.exportPrivateKeys()
            keychain.savePrivateKeys(newCfeData)
            // Старый ключ удалять ПОСЛЕ успешного сохранения нового
            keychain.deleteLegacyPrivateKeys()
            Log.info("✅ Migrated private keys to CFE format", category: "Migration")
        }
    } catch CryptoError.keyDataCorrupted {
        // CRC mismatch — критическая ошибка
        await showCorruptionAlert()
    }
}

// Extension для проверки формата
extension Data {
    var isCfeFormat: Bool {
        count >= 2 && self[0] == 0x43 && self[1] == 0x46
    }
}
```

### Поэтапный план

```
Фаза 0 (сейчас):       JSON + base64 в Keychain
Фаза 1 (миграция):     Читаем оба, пишем CFE
                         - import_private_keys() → JSON fallback
                         - export_private_keys() → CFE всегда
                         - Старые ключи удаляются при первом успешном импорте

Фаза 2 (стабилизация): Только CFE, JSON fallback убираем
                         - Через 2-3 релиза после фазы 1
                         - Пользователи которые не обновились — нужна ре-регистрация

Фаза 3 (Android):      Android пишем сразу в CFE, без JSON
```

---

## 9. Обработка ошибок

### Иерархия ошибок при чтении Keychain

```
Keychain.load() → nil
    → Первый запуск / очищен → Registration flow

Keychain.load() → Data
    → cfe::decode()
        → CfeError::InvalidMagic + data[0] == '{' → LegacyJson → migrate
        → CfeError::InvalidMagic + data[0] != '{' → UnrecoverableCorruption
        → CfeError::ChecksumMismatch               → UnrecoverableCorruption
        → CfeError::UnsupportedVersion             → NeedsAppUpdate
        → CfeError::TypeMismatch                   → Bug (wrong key in Keychain)
        → Ok(CfeEnvelope)
            → rmp_serde::from_slice()
                → DeserializeFailed                → SchemaVersionMismatch → serde(default)
                → Ok(T)                            → ✅ Success
```

### Что делать при `UnrecoverableCorruption`

```swift
// В iOS — Keychain corruption может случиться из-за:
// - Восстановление из старого iCloud backup
// - Ошибки при шифровании Secure Enclave
// - Прямая порча файла (jailbreak, disk error)

func handleCorruption() async {
    // 1. Показать пользователю Alert с объяснением
    // 2. Предложить:
    //    a) Восстановить из более нового backup
    //    b) Начать заново (потеря истории)
    // 3. НЕ пытаться использовать повреждённые ключи

    await showAlert(
        title: "Ошибка безопасности",
        message: "Ключи шифрования повреждены. Для безопасности требуется повторная регистрация.",
        action: "Начать заново"
    )
}
```

---

## 10. Версионирование схем

### Два уровня версионирования

```
1. CFE конверт: заголовочный byte "version" (сейчас 0x01)
   → Меняется только при изменении СТРУКТУРЫ КОНВЕРТА (размер заголовка, порядок полей)
   → Не ожидается изменений

2. Payload схема: поле "ver" внутри MessagePack payload
   → Меняется при изменении полей структуры данных
   → Каждая структура имеет свой счётчик
```

### Правила изменения схемы

```
✅ БЕЗОПАСНО (не требует ver bump):
   - Добавить опциональное поле с default
   - Переименовать поле (через #[serde(alias = "old_name")])

❌ BREAKING (требует ver bump):
   - Удалить поле
   - Изменить тип поля
   - Сделать опциональное поле обязательным

При ver bump:
   payload["ver"] == 1 → старый десериализатор
   payload["ver"] == 2 → новый десериализатор
```

### Пример конкретного bump

```rust
// Добавляем Kyber private key в PrivateKeys (ver 1 → ver 2)

#[derive(Serialize, Deserialize)]
struct CfePrivateKeysV1 {
    #[serde(rename = "ver")]
    schema_version: u8,   // = 1
    suite_id: u8,
    ik_priv: Vec<u8>,
    // ... существующие поля
}

#[derive(Serialize, Deserialize)]
struct CfePrivateKeysV2 {
    #[serde(rename = "ver")]
    schema_version: u8,   // = 2
    suite_id: u8,
    ik_priv: Vec<u8>,
    // ... существующие поля
    kyber_ik_priv: Vec<u8>,   // NEW: ML-KEM-768 private key (2400 bytes)
}

fn load_private_keys(payload: &[u8]) -> Result<LoadedKeys, CfeError> {
    // Читаем только версию
    let versioned: VersionedPayload = rmp_serde::from_slice(payload)?;
    match versioned.ver {
        1 => {
            let v1: CfePrivateKeysV1 = rmp_serde::from_slice(payload)?;
            Ok(migrate_v1_to_current(v1))
        }
        2 => {
            let v2: CfePrivateKeysV2 = rmp_serde::from_slice(payload)?;
            Ok(load_v2(v2))
        }
        v => Err(CfeError::UnknownSchemaVersion { type_: "PrivateKeys", version: v })
    }
}
```

---

## 11. Безопасность формата

### Что CFE защищает

| Угроза | Механизм | Заметки |
|---|---|---|
| Случайная порча Keychain | CRC32 в заголовке | Обнаруживает все однобитные ошибки и большинство многобитных |
| Подмена типа данных | msg_type проверяется при decode_as() | Нельзя прочитать SessionState вместо PrivateKeys |
| Truncated data | payload_len + len check | Ошибка TruncatedPayload до попытки десериализации |
| Replay attacks | Не входит в scope CFE | Replay на уровне DR (Double Ratchet handles this) |
| Confidentiality | Не входит в scope CFE | iOS Keychain / Android Keystore шифруют на уровне ОС |

### Что CFE НЕ защищает

- **Целенаправленная атака**: злоумышленник с доступом к Keychain может пересчитать CRC32 и создать валидный поддельный envelope. CRC — это integrity check, не MAC.
- **Конфиденциальность**: payload в открытом виде. Конфиденциальность обеспечивает Keychain/Keystore.
- **Аутентификация источника**: нет подписи envelope. Это не нужно — данные читаются только самим приложением.

### Примечание о CRC32 vs HMAC

```
CRC32 достаточен для:
  ✅ Случайные ошибки хранения
  ✅ Неполная запись
  ✅ Bit flip на диске / SSD

CRC32 недостаточен для:
  ❌ Намеренная подмена данных атакующим

Для Keychain данных iOS:
  Данные защищены Secure Enclave (AES-256) → злоумышленник не может читать/писать
  → CRC32 достаточен

Если в будущем понадобится HMAC:
  HMAC-SHA256 в flags byte (FLAG_SIGNED) + отдельный 32-байт tag после CRC32
  → Только тогда изменять формат заголовка
```

---

## 12. Тестирование

### Unit тесты в Rust (cfe/tests.rs)

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_encode_decode_roundtrip() {
        let keys = CfePrivateKeys { /* тестовые данные */ };
        let encoded = encode(CfeMessageType::PrivateKeys, &keys).unwrap();

        // Проверяем заголовок
        assert_eq!(&encoded[0..2], b"CF");
        assert_eq!(encoded[2], CFE_VERSION);
        assert_eq!(encoded[3], CfeMessageType::PrivateKeys as u8);

        // Декодируем
        let decoded: CfePrivateKeys = decode_as(&encoded, CfeMessageType::PrivateKeys).unwrap();
        assert_eq!(decoded, keys);
    }

    #[test]
    fn test_crc_corruption_detected() {
        let keys = CfePrivateKeys { /* ... */ };
        let mut encoded = encode(CfeMessageType::PrivateKeys, &keys).unwrap();

        // Портим один байт в payload
        let payload_start = CFE_HEADER_LEN;
        encoded[payload_start] ^= 0xFF;

        let result = decode_as::<CfePrivateKeys>(&encoded, CfeMessageType::PrivateKeys);
        assert!(matches!(result, Err(CfeError::ChecksumMismatch { .. })));
    }

    #[test]
    fn test_type_mismatch_rejected() {
        let session = CfeSessionState { /* ... */ };
        let encoded = encode(CfeMessageType::SessionState, &session).unwrap();

        let result = decode_as::<CfePrivateKeys>(&encoded, CfeMessageType::PrivateKeys);
        assert!(matches!(result, Err(CfeError::TypeMismatch { .. })));
    }

    #[test]
    fn test_legacy_json_detected() {
        let json = b"{\"identity_secret\": \"abc\"}";
        let result = decode_as::<CfePrivateKeys>(json, CfeMessageType::PrivateKeys);
        assert!(matches!(result, Err(CfeError::LegacyJson)));
    }

    #[test]
    fn test_flags_unsupported() {
        // Любой ненулевой флаг сейчас отвергается (SUPPORTED_FLAGS_MASK == 0x00).
        // Когда compression реализуют — этот тест заменят на compressed-roundtrip.
        let big_data = vec![0u8; 10_000];
        let result = encode_with_flags(CfeMessageType::MlsWelcome, &big_data, FLAG_COMPRESSED);
        assert!(matches!(result, Err(CfeError::UnsupportedFlags(FLAG_COMPRESSED))));
    }

    #[test]
    fn test_size_comparison_vs_json() {
        let keys = make_test_private_keys();
        let json_size = serde_json::to_string(&keys).unwrap().len();
        let cfe_size = encode(CfeMessageType::PrivateKeys, &keys).unwrap().len();

        println!("JSON: {} bytes, CFE: {} bytes, savings: {:.0}%",
            json_size, cfe_size,
            (1.0 - cfe_size as f64 / json_size as f64) * 100.0
        );
        assert!(cfe_size < json_size); // CFE должен быть компактнее
    }

    #[test]
    fn test_migration_from_legacy_json() {
        let legacy_json = r#"{"identity_secret":"AAAA...","suite_id":"1",...}"#;
        let result = migrate_from_json(legacy_json.as_bytes());
        assert!(result.is_ok());
    }
}
```

### Integration тест: Swift ↔ Rust round-trip

```swift
// CryptoManagerTests.swift

func testCFEKeyRoundTrip() throws {
    // 1. Генерируем ключи в Rust
    let core = CryptoCore()
    try core.generateRegistrationBundle()

    // 2. Экспортируем в CFE
    let cfeData = try core.exportPrivateKeys()

    // 3. Проверяем magic bytes
    XCTAssertEqual(cfeData[0], 0x43)  // 'C'
    XCTAssertEqual(cfeData[1], 0x46)  // 'F'

    // 4. Создаём новый core, импортируем
    let core2 = CryptoCore()
    try core2.importPrivateKeys(cfeData)

    // 5. Проверяем что ключи совпадают
    let bundle1 = try core.exportRegistrationBundleJson()
    let bundle2 = try core2.exportRegistrationBundleJson()
    XCTAssertEqual(bundle1, bundle2)
}

func testCFEMigrationFromLegacyJSON() throws {
    // Симулируем старый Keychain с JSON
    let legacyJson = """
    {"identity_secret":"Abc123...","signing_secret":"Def456...","suite_id":"1",...}
    """
    let legacyData = legacyJson.data(using: .utf8)!

    let core = CryptoCore()
    // Должно работать без ошибок (legacy path)
    XCTAssertNoThrow(try core.importPrivateKeys([UInt8](legacyData)))
}
```

### Cross-platform тест: Swift CFE == Kotlin CFE

```kotlin
// CryptoManagerTest.kt (Android)

@Test
fun testCFECompatibilityWithSwift() {
    // CFE закодированный на Swift должен декодироваться на Android
    // Тестовые векторы генерируются Rust unit тестами и коммитятся в репозиторий
    val testVectorPath = "src/test/resources/cfe_test_vectors.bin"
    val cfeData = File(testVectorPath).readBytes()

    val core = CryptoCore()
    assertDoesNotThrow { core.importPrivateKeys(cfeData) }
}
```

### Тестовые векторы (commit в репозиторий)

```
tests/cfe_vectors/
├── private_keys_v1.cfe      # CfePrivateKeys с тестовыми ключами
├── session_state_v1.cfe     # CfeSessionState
├── otpk_bundle_v1.cfe       # 10 OtpkRecord
└── README.md                 # Как регенерировать векторы
```

---

## 13. Дорожная карта реализации

### Этап 1: Инфраструктура ✅ ВЫПОЛНЕНО

```
[x] Добавить crc32fast = "1.3" в Cargo.toml
[x] Создать src/cfe/ модуль:
    [x] cfe/envelope.rs   — encode/decode/parse_header, encode_with_flags
    [x] cfe/types.rs      — CfeMessageType enum (0x01–0x43 + 0x7F Generic)
    [x] cfe/error.rs      — CfeError (13 вариантов: TooShort, InvalidMagic, LegacyJson, ...)
    [x] cfe/compat.rs     — legacy JSON migration helpers
    [x] cfe/mod.rs        — re-exports (pub use compat::*, envelope::*, ...)
[x] Написать unit тесты:
    [x] test_encode_decode_roundtrip
    [x] test_crc_corruption_detected
    [x] test_legacy_json_detected
    [x] test_reserved_bytes_validation
    [x] test_truncated_payload_detected
    [x] test_payload_too_large_rejected
[x] cargo test — 168 тестов зелёные
```

### Этап 2: Схемы данных ✅ ВЫПОЛНЕНО

```
[x] Определить CfePrivateKeysV1 (suite_id, ik_priv, sk_priv, spk_priv, spk_sig,
                                   spk_id, ik_pub, vk_pub, spk_pub)
[x] Определить CfeSessionStateV1 (ver, suite_id, contact_id, local_uid,
                                    session_id:ByteBuf[16], rk, sck, rck, scl, rcl,
                                    psl, dh_priv?, dh_pub, rdh_pub?, skipped:Vec,
                                    pq_rk1?)
[x] Определить CfeOtpkBundleV1 (records: Vec<CfeOtpkRecordV1>, next_id ← важно!)
[x] migrate_private_keys_json_str() — с деривацией публичных ключей
[x] migrate_session_json_str()      — через SerializableSession::to_cfe_v1()
[x] migrate_otpk_bundle_json_str()  — next_id = max(key_id) + 1 или 1_000_000
[x] Тесты миграции:
    [x] migrate_private_keys_json_derives_public_checks
    [x] migrate_otpk_bundle_json_sets_next_id
    [x] migrate_session_json_roundtrip_core_fields
```

### Этап 3: uniffi_bindings.rs ✅ ВЫПОЛНЕНО

```
ClassicCryptoCore:
[x] export_private_keys() -> Vec<u8>    (+ JSON fallback в import)
[x] import_private_keys(Vec<u8>)        (JSON fallback через LegacyJson error)
[x] export_session(contact_id) -> Vec<u8>
[x] import_session(contact_id, Vec<u8>) (JSON fallback)
[x] export_one_time_prekeys() -> Vec<u8>
[x] import_one_time_prekeys(Vec<u8>)    (JSON fallback)
[x] UDL (construct_core.udl) — bytes варианты добавлены для ClassicCryptoCore

OrchestratorCore:
[x] export_private_keys() -> Vec<u8>    — orchestrator.rs: export_private_keys_cfe()
[x] export_one_time_prekeys() -> Vec<u8>— orchestrator.rs: export_otpks_cfe()
[x] import_one_time_prekeys(Vec<u8>)    — orchestrator.rs: import_otpks_cfe() с JSON fallback
[x] UDL обновлён для OrchestratorCore
[x] cargo test — 168 тестов зелёные
```

### Этап 4: Swift ✅ ВЫПОЛНЕНО

```
KeychainManager.swift:
[x] savePrivateKeys(_ data: Data) -> Bool   — CFE, тот же ключ "crypto_private_keys_json"
[x] loadPrivateKeysData() -> Data?          — сырые байты, формат определяет Rust
[x] saveOtpks(_ data: Data) -> Bool         — CFE, тот же ключ "crypto_otpks_json"
[x] loadOtpksData() -> Data?
[x] saveSessionData(_ data: Data, for:)     — CFE, тот же ключ "session_<contactId>"
[x] loadSessionData(for:) -> Data?

CryptoManager.swift:
[x] generateRegistrationBundle() → exportPrivateKeys() + savePrivateKeys() (JSON fallback)
[x] persistCoreState()           → exportPrivateKeys() + savePrivateKeys()
[x] reloadCoreFromKeychain()     → loadPrivateKeysData() + createOrchestratorCoreFromKeys()
[x] saveSessionToKeychain()      → exportSession() + saveSessionData()
[x] restoreSession()             → loadSessionData() + importSession()
[x] setLocalUserId()             → loadPrivateKeysData() + createOrchestratorCoreFromKeys()
                                   + loadOtpksData() + importOneTimePrekeys()

OtpkReplenishmentService.swift:
[x] persistOtpks() → exportOneTimePrekeys() + saveOtpks()

CryptoCoreProvider.swift:
[x] init → loadPrivateKeysData() + createCryptoCoreFromKeys()
         + loadOtpksData() + importOneTimePrekeys()

construct_core.udl + Rust:
[x] createOrchestratorCoreFromKeys(keysData: [UInt8], myUserId: String) — CFE/JSON auto-detect
[x] OrchestratorCore.exportPrivateKeys() → Vec<u8> (CFE)
[x] OrchestratorCore.exportOneTimePrekeys() / importOneTimePrekeys() → Vec<u8> (CFE)
[x] build_crypto_lib.sh → clean ✅ (52MB libs)
```

> **Примечание:** Архивы сессий (`SessionArchive`) оставлены в JSON — они хранятся
> в отдельном хранилище и не являются частью критического пути. Миграция архивов
> запланирована на Этап 5 (опционально).

### Этап 5: Интеграционное тестирование

```
[ ] End-to-end тест: регистрация → Keychain save (CFE) → reload → сессия
[ ] Migration тест: загрузить JSON из Keychain → проверить автомиграцию
[ ] Убедиться что magic bytes проверяются (data[0]==0x43 && data[1]==0x46)
[ ] Commit тестовые векторы в tests/cfe_vectors/:
    [ ] private_keys_v1.cfe
    [ ] session_state_v1.cfe
    [ ] otpk_bundle_v1.cfe
[ ] Готово к Android реализации
```

### Этап 6: Android (биндинги готовы)

```
[x] UniFFI генерирует Kotlin биндинги (uniffi.construct_core) — есть в репо
[x] .so собраны под arm64-v8a / armeabi-v7a / x86_64 (build_crypto_lib.sh)
[~] CryptoManager.kt — целевой паттерн описан (§7 + ANDROID_API_CRYPTO_GUIDE.md),
    обёртка ещё пишется (репо на стадии скелета)
[~] AndroidKeystore.save/load(ByteArray) — план; помни про List<UByte>↔ByteArray
[ ] Кросс-платформ тест Swift CFE == Kotlin CFE на общих векторах §12
```

---

## Приложение: Зависимости

### Новые зависимости Cargo.toml

```toml
[dependencies]
crc32fast = "1.3"        # Быстрый CRC32C, нет зависимостей, MIT лицензия
# zstd = "0.13"          # Добавить только когда нужно сжатие (MLS/Calls)
                          # zstd = 200KB, не добавляем до необходимости

# Уже есть:
rmp-serde = "1.1"        # MessagePack сериализация через serde
serde = { version = "1.0", features = ["derive"] }
```

### Размеры зависимостей (справка)

| Crate | Размер | Зависимости | Лицензия |
|---|---|---|---|
| `crc32fast` | ~15KB | нет | MIT/Apache |
| `rmp-serde` | ~50KB | rmp + serde | MIT |
| `zstd` | ~200KB | zstd-sys | MIT |

---

*Документ версия 3.0 (2026-06-05) — CFE реализован и в проде: Rust ядро
(`ClassicCryptoCore` + `OrchestratorCore`), Swift-интеграция, Android UniFFI-биндинги.
Спецификация сверена с `construct-core/src/cfe/` (`envelope.rs`, `types.rs`,
`error.rs`). Ключевые правки v3.0: каталог типов дополнен до фактического
(0x06–0x08, 0x20–0x21, 0x30–0x31, 0x40–0x43); `MAX_PAYLOAD_LEN` = 256 KiB; флаги
определены, но не поддержаны (`SUPPORTED_FLAGS_MASK = 0x00`); условия валидности
приведены к реальным `CfeError`; §4.6 — event-bus идёт типизированным UniFFI, а не
CFE 0x10/0x11; §7 переписан под существующие Android-биндинги (`List<UByte>` vs
`ByteArray`, blob-vs-typed). Ground truth — код, не документ.*

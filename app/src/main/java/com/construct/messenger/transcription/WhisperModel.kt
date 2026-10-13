package com.construct.messenger.transcription

/**
 * The models offered, as iOS offers them (tiny, base, small — multilingual). **Canon:** iOS
 * `WhisperModelManager`, with whisper.cpp's ggml files instead of WhisperKit's Core ML ones.
 *
 * From Hugging Face, `ggerganov/whisper.cpp`, at a pinned revision, each checked against the
 * SHA-256 below before it is used (owner, 2026-10-13): a file that is not this one is deleted,
 * never loaded. Hugging Face learns that this address fetched a speech model — accepted for iOS in
 * `security/konstrukt-unobservability-map.md` (D10); our server learns nothing.
 */
enum class WhisperModel(val fileName: String, val bytes: Long, val sha256: String) {
    TINY("ggml-tiny.bin", 77_691_713, "be07e048e1e599ad46341c8d2a135645097a538221678b7acdd1b1919c6e1b21"),
    BASE("ggml-base.bin", 147_951_465, "60ed5bc3dd14eea856493d334349b405782ddcaf0028d4b5df4088345fba2efe"),
    SMALL("ggml-small.bin", 487_601_967, "1be3a9b2063867b937e64e2ec7483364a79917e157fa98c5d94b5c1fffea987b"),
    ;

    val url: String get() = "https://huggingface.co/ggerganov/whisper.cpp/resolve/$REVISION/$fileName"

    companion object {
        const val REVISION = "5359861c739e955e79d9a303bcbc70fb988958b1"
    }
}

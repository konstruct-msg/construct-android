package com.construct.messenger.util

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.withSign

/**
 * BlurHash (Wolt) decoding: the colour field a photo's sender sends ahead of the photo.
 * **Canon:** iOS `Utilities/BlurHash.swift`, which encodes 4×3 components; this reads any count.
 *
 * Returns ARGB pixels, [width]×[height] row by row, or null for a string that is not a BlurHash —
 * it comes from a peer.
 */
object BlurHash {

    fun decode(hash: String, width: Int, height: Int, punch: Float = 1f): IntArray? {
        if (hash.length < 6 || width <= 0 || height <= 0) return null
        val sizeFlag = decode83(hash, 0, 1) ?: return null
        val nx = sizeFlag % 9 + 1
        val ny = sizeFlag / 9 + 1
        if (hash.length != 4 + 2 * nx * ny) return null
        val quantMax = decode83(hash, 1, 2) ?: return null
        val maxValue = (quantMax + 1) / 166f * punch

        val colors = Array(nx * ny) { i ->
            if (i == 0) {
                val v = decode83(hash, 2, 6) ?: return null
                floatArrayOf(toLinear(v shr 16), toLinear((v shr 8) and 255), toLinear(v and 255))
            } else {
                val v = decode83(hash, 4 + i * 2, 6 + i * 2) ?: return null
                floatArrayOf(
                    signedPow2(((v / (19 * 19)) - 9) / 9f) * maxValue,
                    signedPow2((((v / 19) % 19) - 9) / 9f) * maxValue,
                    signedPow2(((v % 19) - 9) / 9f) * maxValue,
                )
            }
        }

        val cosX = Array(nx) { i -> FloatArray(width) { x -> cos(PI * x * i / width).toFloat() } }
        val cosY = Array(ny) { j -> FloatArray(height) { y -> cos(PI * y * j / height).toFloat() } }
        val out = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                var r = 0f
                var g = 0f
                var b = 0f
                for (j in 0 until ny) {
                    for (i in 0 until nx) {
                        val basis = cosX[i][x] * cosY[j][y]
                        val c = colors[j * nx + i]
                        r += c[0] * basis
                        g += c[1] * basis
                        b += c[2] * basis
                    }
                }
                out[y * width + x] = (0xFF shl 24) or (toSrgb(r) shl 16) or (toSrgb(g) shl 8) or toSrgb(b)
            }
        }
        return out
    }

    private const val CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz#$%*+,-.:;=?@[]^_{|}~"

    private fun decode83(s: String, from: Int, to: Int): Int? {
        var v = 0
        for (k in from until to) {
            val d = CHARS.indexOf(s[k])
            if (d < 0) return null
            v = v * 83 + d
        }
        return v
    }

    private fun signedPow2(v: Float) = v.pow(2).withSign(v)

    private fun toLinear(c: Int): Float {
        val v = c / 255f
        return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f)
    }

    private fun toSrgb(v: Float): Int {
        val c = v.coerceIn(0f, 1f)
        val s = if (c <= 0.0031308f) c * 12.92f else 1.055f * c.pow(1 / 2.4f) - 0.055f
        return (s * 255 + 0.5f).toInt().coerceIn(0, 255)
    }
}

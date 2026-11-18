package com.aijam.companion.engine

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-place radix-2 Cooley-Tukey FFT/IFFT used by both real-time FFT-YIN
 * and on-device offline chromagram/chord/onset analysis.
 */
object Fft {

    fun hannWindow(size: Int): DoubleArray {
        val w = DoubleArray(size)
        for (i in 0 until size) {
            w[i] = 0.5 * (1.0 - cos(2.0 * PI * i / size))
        }
        return w
    }

    fun nextPowerOfTwo(n: Int): Int {
        var v = 1
        while (v < n) v = v shl 1
        return v
    }

    fun transform(real: DoubleArray, imag: DoubleArray, inverse: Boolean = false) {
        val n = real.size
        require(n > 0 && (n and (n - 1)) == 0) { "FFT length must be a power of 2, got $n" }

        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) {
                j = j xor bit
                bit = bit shr 1
            }
            j = j xor bit
            if (i < j) {
                val tr = real[i]; real[i] = real[j]; real[j] = tr
                val ti = imag[i]; imag[i] = imag[j]; imag[j] = ti
            }
        }

        val dir = if (inverse) 1.0 else -1.0
        var len = 2
        while (len <= n) {
            val ang = dir * 2.0 * PI / len
            val wLenR = cos(ang)
            val wLenI = sin(ang)
            val half = len shr 1
            var i = 0
            while (i < n) {
                var wR = 1.0
                var wI = 0.0
                for (k in 0 until half) {
                    val uR = real[i + k]
                    val uI = imag[i + k]
                    val idx = i + k + half
                    val vR = real[idx] * wR - imag[idx] * wI
                    val vI = real[idx] * wI + imag[idx] * wR
                    real[i + k] = uR + vR
                    imag[i + k] = uI + vI
                    real[idx] = uR - vR
                    imag[idx] = uI - vI
                    val nextWR = wR * wLenR - wI * wLenI
                    wI = wR * wLenI + wI * wLenR
                    wR = nextWR
                }
                i += len
            }
            len = len shl 1
        }

        if (inverse) {
            val invN = 1.0 / n
            for (i in 0 until n) {
                real[i] *= invN
                imag[i] *= invN
            }
        }
    }
}

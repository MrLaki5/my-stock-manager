package com.mrlaki5.mystockmanager.ondevice

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.tensorflow.lite.Interpreter
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

/** Ranks a stock vocabulary against a SigLIP 2 image embedding; the index is built by tools/keywords/build_keyword_index.py. */
class KeywordTagger(context: Context, model: File) : AutoCloseable {

    private val index = KeywordIndex.load(context)
    private val breeds = context.assets.open(BREEDS_ASSET).bufferedReader().useLines { lines ->
        lines.map { it.trim() }.filter { it.isNotEmpty() }.toHashSet()
    }
    private val interpreter = Interpreter(model, Interpreter.Options().setNumThreads(THREADS))
    private val input = ByteBuffer.allocateDirect(4 * 3 * SIZE * SIZE).order(ByteOrder.nativeOrder())
    private val output = Array(1) { FloatArray(index.dim) }

    fun tag(imageJpeg: ByteArray): Tags {
        val image = embed(imageJpeg)
        val raw = FloatArray(index.terms.size) { index.dot(it, image) }
        // Calibrated against reference photos, so terms that match almost any photo do not crowd out specific ones.
        val z = FloatArray(raw.size) { (raw[it] - index.mu[it]) / index.sd[it] }
        // Narrow-spread terms can score high calibrated yet be far from the photo, so they must also be close in raw terms.
        val close = raw.indices.sortedByDescending { raw[it] }.take(RAW_TOP)
        val ranked = close.filter { z[it] >= MIN_Z }.sortedByDescending { z[it] }
        val category = index.categories.indices
            .maxBy { (index.categoryDot(it, image) - index.cmu[it]) / index.csd[it] }
        return Tags(
            ranked = ranked.map { index.terms[it] },
            scores = index.terms.indices.associate { index.terms[it] to z[it] },
            category = index.categories[category],
            vocabulary = index.termSet,
            breeds = breeds,
        )
    }

    private fun embed(imageJpeg: ByteArray): FloatArray {
        val bitmap = decode(imageJpeg)
        try {
            val pixels = IntArray(SIZE * SIZE)
            bitmap.getPixels(pixels, 0, SIZE, 0, 0, SIZE, SIZE)
            input.rewind()
            // NCHW, RGB scaled to [-1, 1], as the model was exported.
            for (shift in intArrayOf(16, 8, 0)) {
                for (p in pixels) input.putFloat(((p shr shift) and 0xFF) / 127.5f - 1f)
            }
            input.rewind()
            interpreter.run(input, output)
        } finally {
            bitmap.recycle()
        }
        val v = output[0]
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return FloatArray(v.size) { v[it] / norm }
    }

    // Squashes to 224x224 like the reference preprocessing; halving first keeps the bilinear resize from aliasing.
    private fun decode(imageJpeg: ByteArray): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(imageJpeg, 0, imageJpeg.size, bounds)
        var sample = 1
        while (minOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= SIZE) sample *= 2
        val decoded = BitmapFactory.decodeByteArray(imageJpeg, 0, imageJpeg.size, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: error("Could not decode the image for keywords")
        val scaled = Bitmap.createScaledBitmap(decoded, SIZE, SIZE, true)
        if (scaled !== decoded) decoded.recycle()
        return scaled
    }

    override fun close() = interpreter.close()

    data class Tags(
        val ranked: List<String>,
        val scores: Map<String, Float>,
        val category: String,
        val vocabulary: Set<String>,
        val breeds: Set<String> = emptySet(),
    )

    private companion object {
        const val SIZE = 224
        const val BREEDS_ASSET = "keyword_breeds.txt"
        const val THREADS = 4

        // Tuned on stock-style photos: a raw top-150 term with a calibrated score of 1.5+ is nearly always visible.
        const val RAW_TOP = 150
        const val MIN_Z = 1.5f
    }
}

/** The little-endian index written by tools/keywords/build_keyword_index.py. */
private class KeywordIndex(
    val dim: Int,
    val terms: List<String>,
    private val vectors: ByteArray,
    private val scales: FloatArray,
    val mu: FloatArray,
    val sd: FloatArray,
    val categories: List<String>,
    private val categoryVectors: FloatArray,
    val cmu: FloatArray,
    val csd: FloatArray,
) {
    val termSet: Set<String> = terms.toHashSet()

    fun dot(term: Int, v: FloatArray): Float {
        var sum = 0f
        val base = term * dim
        for (i in 0 until dim) sum += vectors[base + i] * v[i]
        return sum * scales[term]
    }

    fun categoryDot(category: Int, v: FloatArray): Float {
        var sum = 0f
        val base = category * dim
        for (i in 0 until dim) sum += categoryVectors[base + i] * v[i]
        return sum
    }

    companion object {
        private const val MAGIC = 0x5849574B // "KWIX"

        fun load(context: Context): KeywordIndex {
            val bytes = context.assets.open("keyword_index.bin").use { it.readBytes() }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            check(buf.int == MAGIC) { "Not a keyword index" }
            check(buf.int == 1) { "Unsupported keyword index version" }
            val dim = buf.int
            val termCount = buf.int
            val categoryCount = buf.int
            fun strings(n: Int) = List(n) {
                val raw = ByteArray(buf.short.toInt() and 0xFFFF)
                buf.get(raw)
                String(raw, Charsets.UTF_8)
            }
            fun floats(n: Int) = FloatArray(n) { buf.float }
            val terms = strings(termCount)
            val categories = strings(categoryCount)
            val vectors = ByteArray(termCount * dim).also { buf.get(it) }
            return KeywordIndex(
                dim = dim,
                terms = terms,
                vectors = vectors,
                scales = floats(termCount),
                mu = floats(termCount),
                sd = floats(termCount),
                categories = categories,
                categoryVectors = floats(categoryCount * dim),
                cmu = floats(categoryCount),
                csd = floats(categoryCount),
            )
        }
    }
}

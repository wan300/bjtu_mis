package cn.edu.bjtu.mis.data.captcha

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.pytorch.IValue
import org.pytorch.Module
import org.pytorch.Tensor
import java.io.File

class CaptchaSolveException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

data class CaptchaSolveResult(
    val expression: String,
    val answer: String,
)

fun interface CaptchaAnswerSolver {
    fun solve(imageBytes: ByteArray): CaptchaSolveResult
}

class TorchScriptCaptchaSolver(
    private val context: Context,
    private val assetName: String = "bjtu_captcha_crnn.pt",
    private val charset: String = DEFAULT_CHARSET,
) : CaptchaAnswerSolver {
    @Volatile
    private var module: Module? = null

    override fun solve(imageBytes: ByteArray): CaptchaSolveResult {
        val expression = recognize(imageBytes)
        return CaptchaSolveResult(
            expression = expression,
            answer = CaptchaExpression.calculate(expression),
        )
    }

    private fun recognize(imageBytes: ByteArray): String {
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            ?: throw CaptchaSolveException("验证码图片无法读取。")
        val input = preprocess(bitmap)
        val output = runCatching {
            val value = loadModule().forward(IValue.from(input))
            if (value.isTensor) value.toTensor() else value.toTuple().first().toTensor()
        }.getOrElse {
            throw CaptchaSolveException("验证码模型推理失败：${it.message}", it)
        }

        val indices = argmax(output)
        val expression = CaptchaExpression.decodeCtc(indices.take(MAX_DECODE_STEPS), charset)
        if (expression.isBlank()) {
            throw CaptchaSolveException("验证码模型未识别出内容。")
        }
        return expression
    }

    private fun loadModule(): Module {
        module?.let { return it }
        return synchronized(this) {
            module ?: Module.load(copyAssetToFiles().absolutePath).also { module = it }
        }
    }

    private fun copyAssetToFiles(): File {
        val target = File(context.filesDir, assetName)
        context.assets.open(assetName).use { input ->
            val assetSize = input.available().toLong()
            if (target.exists() && target.length() == assetSize) return target
        }
        context.assets.open(assetName).use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return target
    }

    private fun preprocess(bitmap: Bitmap): Tensor {
        val scaled = Bitmap.createScaledBitmap(bitmap, MODEL_WIDTH, MODEL_HEIGHT, true)
        val pixels = IntArray(MODEL_WIDTH * MODEL_HEIGHT)
        scaled.getPixels(pixels, 0, MODEL_WIDTH, 0, 0, MODEL_WIDTH, MODEL_HEIGHT)
        if (scaled !== bitmap) scaled.recycle()

        val data = FloatArray(3 * MODEL_WIDTH * MODEL_HEIGHT)
        val plane = MODEL_WIDTH * MODEL_HEIGHT
        pixels.forEachIndexed { index, color ->
            data[index] = ((color shr 16) and 0xff) / 255f
            data[plane + index] = ((color shr 8) and 0xff) / 255f
            data[2 * plane + index] = (color and 0xff) / 255f
        }
        return Tensor.fromBlob(data, longArrayOf(1L, 3L, MODEL_HEIGHT.toLong(), MODEL_WIDTH.toLong()))
    }

    private fun argmax(tensor: Tensor): List<Int> {
        val shape = tensor.shape()
        if (shape.size != 3) {
            throw CaptchaSolveException("验证码模型输出维度异常：${shape.joinToString("x")}")
        }
        val values = tensor.dataAsFloatArray
        val classes = shape[2].toInt()
        val indices = mutableListOf<Int>()

        if (shape[1] == 1L) {
            val timeSteps = shape[0].toInt()
            for (time in 0 until timeSteps) {
                val base = time * classes
                indices += maxClass(values, base, classes)
            }
        } else if (shape[0] == 1L) {
            val timeSteps = shape[1].toInt()
            for (time in 0 until timeSteps) {
                val base = time * classes
                indices += maxClass(values, base, classes)
            }
        } else {
            throw CaptchaSolveException("验证码模型 batch 维度异常：${shape.joinToString("x")}")
        }
        return indices
    }

    private fun maxClass(values: FloatArray, base: Int, classes: Int): Int {
        var bestIndex = 0
        var bestValue = Float.NEGATIVE_INFINITY
        for (index in 0 until classes) {
            val value = values[base + index]
            if (value > bestValue) {
                bestValue = value
                bestIndex = index
            }
        }
        return bestIndex
    }

    companion object {
        const val DEFAULT_CHARSET = " 0123456789+-*="
        private const val MAX_DECODE_STEPS = 8
        private const val MODEL_WIDTH = 130
        private const val MODEL_HEIGHT = 42
    }
}

object CaptchaExpression {
    private val expressionPattern = Regex("""^(\d+)([+\-*])(\d+)=$""")

    fun decodeCtc(indices: Iterable<Int>, charset: String, blankIndex: Int = 0): String {
        val modelCharset = modelCharset(charset, blankIndex)
        val result = StringBuilder()
        var previous: Int? = null
        for (rawIndex in indices) {
            val index = rawIndex
            if (index == blankIndex) {
                previous = index
                continue
            }
            if (index == previous) continue
            if (index !in modelCharset.indices) {
                throw CaptchaSolveException("验证码识别输出包含未知类别：$index")
            }
            val char = modelCharset[index]
            if (char.isWhitespace()) {
                previous = index
                continue
            }
            result.append(char)
            previous = index
        }
        return result.toString()
    }

    private fun modelCharset(charset: String, blankIndex: Int): String =
        charset
            .let { if ('=' !in it && '/' in it) it.replace('/', '=') else it }
            .let { if (blankIndex == 0 && it.firstOrNull()?.isWhitespace() != true) " $it" else it }

    fun calculate(expression: String): String {
        return calculateOrNull(expression)
            ?: throw CaptchaSolveException("验证码算式格式不匹配：$expression")
    }

    fun calculateOrNull(expression: String): String? {
        val input = expression.filterNot { it.isWhitespace() }
        val match = expressionPattern.matchEntire(input) ?: return null
        val left = match.groupValues[1].toLongOrNull() ?: return null
        val operator = match.groupValues[2]
        val right = match.groupValues[3].toLongOrNull() ?: return null
        val value = when (operator) {
            "+" -> left + right
            "-" -> left - right
            "*" -> left * right
            else -> return null
        }
        return value.toString()
    }
}

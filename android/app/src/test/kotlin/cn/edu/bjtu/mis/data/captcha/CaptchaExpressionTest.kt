package cn.edu.bjtu.mis.data.captcha

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CaptchaExpressionTest {
    @Test
    fun decodeCtcSkipsBlankAndRepeatedClasses() {
        assertEquals("0+1=", CaptchaExpression.decodeCtc(listOf(0, 1, 1, 0, 11, 11, 2, 14), " 0123456789+-*="))
    }

    @Test
    fun decodeCtcAcceptsCharsetWithoutExplicitBlank() {
        assertEquals("01=", CaptchaExpression.decodeCtc(listOf(1, 2, 14), "0123456789+-*="))
    }

    @Test
    fun decodeCtcTreatsLegacySlashClassAsEquals() {
        assertEquals("01=", CaptchaExpression.decodeCtc(listOf(1, 2, 14), "0123456789+-*/"))
    }

    @Test
    fun calculateSupportsExpectedFormats() {
        assertEquals("15", CaptchaExpression.calculate("12+3="))
        assertEquals("56", CaptchaExpression.calculate("8*7="))
        assertEquals("6", CaptchaExpression.calculate("15-9="))
    }

    @Test(expected = CaptchaSolveException::class)
    fun calculateRejectsMissingEquals() {
        CaptchaExpression.calculate("12+3")
    }

    @Test(expected = CaptchaSolveException::class)
    fun calculateRejectsDivision() {
        CaptchaExpression.calculate("12/3=")
    }

    @Test
    fun calculateOrNullReturnsNullForInvalidFormat() {
        assertNull(CaptchaExpression.calculateOrNull("12/3="))
    }
}

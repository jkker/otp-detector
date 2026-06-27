package com.example.otpdetector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class OtpExtractorTest {

    @Test
    fun ignoresDigitsOnlySmsBody() {
        assertNull(OtpExtractor.extractOtp("5707959436", ""))
    }

    @Test
    fun extractsPlaidVerificationCodeFromBodyText() {
        val text =
            "Your Plaid verification code is: 330174. Do NOT share it with anyone. " +
                "Plaid will never call you to ask for this code."

        assertEquals("330174", OtpExtractor.extractOtp(text, ""))
    }

    @Test
    fun extractsMultilineChaseCodeFromBodyText() {
        val text =
            """
            Chase Mobile
            Your code:33670162
            Reply HELP for Help
            Msg & data rates may Apply
            chase.com/mobile
            """.trimIndent()

        assertEquals("33670162", OtpExtractor.extractOtp(text, ""))
    }

    @Test
    fun prefersPrimaryTextOverBigTextFallback() {
        assertEquals("123456", OtpExtractor.extractOtp("Your code is 123456", "Your code is 987654"))
    }

    @Test
    fun fallsBackToBigTextWhenPrimaryTextHasNoOtp() {
        assertEquals("998877", OtpExtractor.extractOtp("New message", "Login code: 998877"))
    }

    @Test
    fun supportsShortPinWithKeyword() {
        assertEquals("4521", OtpExtractor.extractOtp("Your pin is 4521", ""))
    }

    @Test
    fun rejectsShortCodeWithoutOtpContext() {
        assertNull(OtpExtractor.extractOtp("Order #4521 shipped", ""))
    }

    @Test
    fun rejectsIpAddressFragments() {
        assertNull(OtpExtractor.extractOtp("Login from 192.168.1.1", ""))
    }

    @Test
    fun rejectsDashedPhoneNumberFragments() {
        assertNull(OtpExtractor.extractOtp("Call 555-0199", ""))
        assertNull(OtpExtractor.extractOtp("Call 0199-555", ""))
    }

    @Test
    fun rejectsUrlHeavyBigTextWithoutOtpKeywords() {
        assertNull(OtpExtractor.extractOtp("New message", "Visit chase.com/mobile for 33670162"))
    }
}

package com.antisocial.giftcardchecker.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for BalanceResult factory methods and formatting.
 */
class BalanceResultTest {

    @Test
    fun `success factory creates correct result`() {
        val result = BalanceResult.success("25.50", "EUR")

        assertEquals(BalanceStatus.SUCCESS, result.status)
        assertEquals("25.50", result.balance)
        assertEquals("EUR", result.currency)
        assertTrue(result.isSuccess())
        assertNull(result.errorMessage)
    }

    @Test
    fun `invalidCard factory creates correct result`() {
        val message = "Card number is invalid"
        val result = BalanceResult.invalidCard(message)

        assertEquals(BalanceStatus.INVALID_CARD, result.status)
        assertNull(result.balance)
        assertEquals(message, result.errorMessage)
        assertFalse(result.isSuccess())
    }

    @Test
    fun `invalidCard factory uses default message when null`() {
        val result = BalanceResult.invalidCard()

        assertEquals(BalanceStatus.INVALID_CARD, result.status)
        assertEquals("Invalid card number", result.errorMessage)
    }

    @Test
    fun `invalidPin factory creates correct result`() {
        val message = "PIN is incorrect"
        val result = BalanceResult.invalidPin(message)

        assertEquals(BalanceStatus.INVALID_PIN, result.status)
        assertEquals(message, result.errorMessage)
        assertFalse(result.isSuccess())
    }

    @Test
    fun `invalidPin factory uses default message when null`() {
        val result = BalanceResult.invalidPin()

        assertEquals(BalanceStatus.INVALID_PIN, result.status)
        assertEquals("Invalid PIN", result.errorMessage)
    }

    @Test
    fun `networkError factory creates correct result`() {
        val message = "No internet connection"
        val result = BalanceResult.networkError(message)

        assertEquals(BalanceStatus.NETWORK_ERROR, result.status)
        assertEquals(message, result.errorMessage)
        assertFalse(result.isSuccess())
    }

    @Test
    fun `parsingError factory creates correct result`() {
        val message = "Could not parse response"
        val rawResponse = "<html>Raw response here</html>"
        val result = BalanceResult.parsingError(message, rawResponse)

        assertEquals(BalanceStatus.PARSING_ERROR, result.status)
        assertEquals(message, result.errorMessage)
        assertEquals(rawResponse, result.rawResponse)
        assertFalse(result.isSuccess())
    }

    @Test
    fun `websiteChanged factory creates correct result`() {
        val message = "Website structure has changed"
        val result = BalanceResult.websiteChanged(message)

        assertEquals(BalanceStatus.WEBSITE_CHANGED, result.status)
        assertEquals(message, result.errorMessage)
        assertFalse(result.isSuccess())
    }

    @Test
    fun `error factory creates correct result`() {
        val message = "Unknown error occurred"
        val result = BalanceResult.error(message)

        assertEquals(BalanceStatus.UNKNOWN_ERROR, result.status)
        assertEquals(message, result.errorMessage)
        assertFalse(result.isSuccess())
    }

    @Test
    fun `getFormattedBalance formats EUR correctly`() {
        val result = BalanceResult.success("42.99", "EUR")

        assertEquals("42.99 €", result.getFormattedBalance())
    }

    @Test
    fun `getFormattedBalance formats USD correctly`() {
        val result = BalanceResult.success("100.00", "USD")

        assertEquals("100.00 $", result.getFormattedBalance())
    }

    @Test
    fun `getFormattedBalance formats GBP correctly`() {
        val result = BalanceResult.success("75.50", "GBP")

        assertEquals("75.50 £", result.getFormattedBalance())
    }

    @Test
    fun `getFormattedBalance returns N_A for null balance`() {
        val result = BalanceResult.invalidCard()

        assertEquals("N/A", result.getFormattedBalance())
    }

    @Test
    fun `getFormattedBalance uses currency code for unknown currencies`() {
        val result = BalanceResult.success("50.00", "CHF")

        assertEquals("50.00 CHF", result.getFormattedBalance())
    }

    @Test
    fun `getDisplayMessage shows balance for success`() {
        val result = BalanceResult.success("25.50", "EUR")

        val message = result.getDisplayMessage()

        assertTrue(message.contains("Balance"))
        assertTrue(message.contains("25.50"))
        assertTrue(message.contains("€"))
    }

    @Test
    fun `getDisplayMessage shows error message for failures`() {
        val result = BalanceResult.invalidCard("Custom error message")

        val message = result.getDisplayMessage()

        assertEquals("Custom error message", message)
    }

    @Test
    fun `getDisplayMessage truncates long error messages`() {
        val longMessage = "x".repeat(250)
        val result = BalanceResult.error(longMessage)

        val message = result.getDisplayMessage()

        assertTrue(message.length <= 203) // 200 + "..."
        assertTrue(message.endsWith("..."))
    }

    @Test
    fun `getDisplayMessage uses default messages when errorMessage is null`() {
        val testCases = mapOf(
            BalanceStatus.INVALID_CARD to "Invalid card number",
            BalanceStatus.INVALID_PIN to "Invalid PIN",
            BalanceStatus.NETWORK_ERROR to "Network error",
            BalanceStatus.PARSING_ERROR to "Could not read balance",
            BalanceStatus.WEBSITE_CHANGED to "Service temporarily unavailable",
            BalanceStatus.UNKNOWN_ERROR to "An error occurred"
        )

        testCases.forEach { (status, expectedMessage) ->
            val result = BalanceResult(status = status)
            val message = result.getDisplayMessage()
            assertEquals("Failed for status $status", expectedMessage, message)
        }
    }

    @Test
    fun `companion object constant is correct`() {
        assertEquals("extra_balance_result", BalanceResult.EXTRA_BALANCE_RESULT)
    }

    @Test
    fun `result timestamp is set correctly`() {
        val beforeTime = System.currentTimeMillis()
        val result = BalanceResult.success("25.50")
        val afterTime = System.currentTimeMillis()

        assertTrue(result.timestamp >= beforeTime)
        assertTrue(result.timestamp <= afterTime)
    }

    @Test
    fun `isSuccess returns correct values`() {
        assertTrue(BalanceResult.success("10.00").isSuccess())
        assertFalse(BalanceResult.invalidCard().isSuccess())
        assertFalse(BalanceResult.invalidPin().isSuccess())
        assertFalse(BalanceResult.networkError().isSuccess())
        assertFalse(BalanceResult.parsingError().isSuccess())
        assertFalse(BalanceResult.websiteChanged().isSuccess())
        assertFalse(BalanceResult.error().isSuccess())
    }
}

package com.antisocial.giftcardchecker.markets

import com.antisocial.giftcardchecker.model.BalanceStatus
import com.antisocial.giftcardchecker.model.GiftCard
import com.antisocial.giftcardchecker.model.MarketType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for ReweMarket balance parsing and error detection.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class ReweMarketTest {

    private lateinit var market: ReweMarket

    @Before
    fun setup() {
        market = ReweMarket()
    }

    @Test
    fun `parseBalanceResponse extracts balance with Euro symbol`() {
        val response = """
            <html>
                <body>
                    <div class="balance-result">
                        <p>Ihr Guthaben: 25,50 €</p>
                    </div>
                </body>
            </html>
        """.trimIndent()

        val result = market.parseBalanceResponse(response)

        assertTrue(result.isSuccess())
        assertEquals("25.50", result.balance)
        assertEquals("EUR", result.currency)
        assertEquals(BalanceStatus.SUCCESS, result.status)
    }

    @Test
    fun `parseBalanceResponse extracts balance with EUR text`() {
        val response = """
            <html>
                <body>
                    <div>Guthaben: 42,99 EUR</div>
                </body>
            </html>
        """.trimIndent()

        val result = market.parseBalanceResponse(response)

        assertTrue(result.isSuccess())
        assertEquals("42.99", result.balance)
    }

    @Test
    fun `parseBalanceResponse handles German comma decimal`() {
        val response = "Ihr aktuelles Guthaben beträgt 100,00 €"

        val result = market.parseBalanceResponse(response)

        assertTrue(result.isSuccess())
        assertEquals("100.00", result.balance)
    }

    @Test
    fun `parseBalanceResponse prefers labeled balance over product prices`() {
        val response = """
            <div class="product">REWE Geschenkkarte 15 €</div>
            <div class="balance">Ihr Guthaben beträgt 1,00 €</div>
        """.trimIndent()

        val result = market.parseBalanceResponse(response)

        assertTrue(result.isSuccess())
        assertEquals("1.00", result.balance)
    }

    @Test
    fun `parseBalanceResponse ignores prices without balance keywords`() {
        val response = """
            <div>REWE Geschenkkarte 15 €</div>
            <div>Weitere Optionen 25 €</div>
        """.trimIndent()

        val result = market.parseBalanceResponse(response)

        assertEquals(BalanceStatus.PARSING_ERROR, result.status)
    }

    @Test
    fun `parseBalanceResponse extracts balance with Guthaben label`() {
        val response = "Guthaben: 15,75 €"

        val result = market.parseBalanceResponse(response)

        assertTrue(result.isSuccess())
        assertEquals("15.75", result.balance)
    }

    @Test
    fun `parseBalanceResponse returns invalidCard for error responses`() {
        val response = """
            <html>
                <body>
                    <div class="error">Kartennummer oder PIN ungültig</div>
                </body>
            </html>
        """.trimIndent()

        val result = market.parseBalanceResponse(response)

        assertEquals(BalanceStatus.INVALID_CARD, result.status)
        assertNotNull(result.errorMessage)
        assertTrue(result.errorMessage!!.contains("ungültig"))
    }

    @Test
    fun `parseBalanceResponse handles various error messages`() {
        val errorResponses = listOf(
            "Die eingegebene Kartennummer ist nicht gefunden worden",
            "PIN falsch",
            "Ihre Eingabe ist ungültig"
        )

        errorResponses.forEach { response ->
            val result = market.parseBalanceResponse(response)
            assertEquals(BalanceStatus.INVALID_CARD, result.status)
        }
    }

    @Test
    fun `parseBalanceResponse returns parsingError when balance not found`() {
        val response = """
            <html>
                <body>
                    <div>Some random content without balance</div>
                </body>
            </html>
        """.trimIndent()

        val result = market.parseBalanceResponse(response)

        assertEquals(BalanceStatus.PARSING_ERROR, result.status)
        assertNotNull(result.errorMessage)
    }

    @Test
    fun `parseBalanceResponse handles Euro symbol before amount`() {
        val response = "Ihr Guthaben: € 33,25"

        val result = market.parseBalanceResponse(response)

        assertTrue(result.isSuccess())
        assertEquals("33.25", result.balance)
    }

    @Test
    fun `isBalancePageLoaded detects success page`() {
        val html = """
            <html>
                <body>
                    <div>Ihr Guthaben: 50,00 €</div>
                </body>
            </html>
        """.trimIndent()

        assertTrue(market.isBalancePageLoaded(html))
    }

    @Test
    fun `isBalancePageLoaded returns false for non-balance page`() {
        val html = """
            <html>
                <body>
                    <div>Enter your card details</div>
                </body>
            </html>
        """.trimIndent()

        assertFalse(market.isBalancePageLoaded(html))
    }

    @Test
    fun `isErrorPageLoaded detects error page`() {
        val errorHtmls = listOf(
            "<html><body>Kartennummer ungültig</body></html>",
            "<html><body>PIN nicht gefunden</body></html>",
            "<html><body>Ein Fehler ist aufgetreten</body></html>",
            "<html><body>PIN ist falsch</body></html>"
        )

        errorHtmls.forEach { html ->
            assertTrue("Failed for: $html", market.isErrorPageLoaded(html))
        }
    }

    @Test
    fun `isErrorPageLoaded returns false for normal page`() {
        val html = "<html><body>Enter your card number</body></html>"

        assertFalse(market.isErrorPageLoaded(html))
    }

    @Test
    fun `getFormFillScript contains card number and PIN`() {
        val card = GiftCard(
            cardNumber = "1234567890123",
            pin = "5678",
            marketType = MarketType.REWE
        )

        val script = market.getFormFillScript(card)

        assertTrue(script.contains(card.cardNumber))
        assertTrue(script.contains(card.pin))
        assertTrue(script.contains("cardNumber"))
        assertTrue(script.contains("pin"))
    }

    @Test
    fun `getFormSubmitScript contains submit logic`() {
        val script = market.getFormSubmitScript()

        assertTrue(script.contains("submit"))
        assertTrue(script.contains("button"))
        assertTrue(script.contains("click"))
    }

    @Test
    fun `getBalanceExtractionScript checks for error messages`() {
        val script = market.getBalanceExtractionScript()

        assertTrue(script.contains("ungültig"))
        assertTrue(script.contains("nicht gefunden"))
        assertTrue(script.contains("falsch"))
    }

    @Test
    fun `market properties are correctly set`() {
        assertEquals(MarketType.REWE, market.marketType)
        assertEquals("REWE", market.displayName)
        assertEquals("https://kartenwelt.rewe.de/rewe-geschenkkarte.html", market.balanceCheckUrl)
        assertFalse(market.requiresManualEntry)
    }
}

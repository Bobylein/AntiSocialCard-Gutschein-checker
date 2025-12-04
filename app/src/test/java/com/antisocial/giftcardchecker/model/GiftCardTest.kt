package com.antisocial.giftcardchecker.model

import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for GiftCard validation and masking.
 */
class GiftCardTest {

    @Test
    fun `REWE card validation accepts 13 digits`() {
        val card = GiftCard(
            cardNumber = "1234567890123",
            pin = "5678",
            marketType = MarketType.REWE
        )

        assertTrue(card.isValidCardNumber())
    }

    @Test
    fun `REWE card validation rejects non-13 digit numbers`() {
        val invalidCards = listOf(
            GiftCard("123456789012", "5678", MarketType.REWE),  // 12 digits
            GiftCard("12345678901234", "5678", MarketType.REWE), // 14 digits
            GiftCard("", "5678", MarketType.REWE)                // empty
        )

        invalidCards.forEach { card ->
            assertFalse("Card ${card.cardNumber} should be invalid", card.isValidCardNumber())
        }
    }

    @Test
    fun `REWE card validation rejects non-numeric characters`() {
        val card = GiftCard(
            cardNumber = "123456789012A",
            pin = "5678",
            marketType = MarketType.REWE
        )

        assertFalse(card.isValidCardNumber())
    }

    @Test
    fun `ALDI card validation accepts 20 digits`() {
        val card = GiftCard(
            cardNumber = "12345678901234567890",
            pin = "1234",
            marketType = MarketType.ALDI
        )

        assertTrue(card.isValidCardNumber())
    }

    @Test
    fun `ALDI card validation rejects non-20 digit numbers`() {
        val card = GiftCard(
            cardNumber = "1234567890123456789", // 19 digits
            pin = "1234",
            marketType = MarketType.ALDI
        )

        assertFalse(card.isValidCardNumber())
    }

    @Test
    fun `LIDL card validation accepts 20 digits`() {
        val card = GiftCard(
            cardNumber = "98765432109876543210",
            pin = "4321",
            marketType = MarketType.LIDL
        )

        assertTrue(card.isValidCardNumber())
    }

    @Test
    fun `REWE PIN validation accepts 4-8 alphanumeric characters`() {
        val validPins = listOf("1234", "ABCD", "12AB", "1234567", "12345678")

        validPins.forEach { pin ->
            val card = GiftCard("1234567890123", pin, MarketType.REWE)
            assertTrue("PIN $pin should be valid", card.isValidPin())
        }
    }

    @Test
    fun `REWE PIN validation rejects invalid lengths`() {
        val invalidPins = listOf("123", "123456789") // too short, too long

        invalidPins.forEach { pin ->
            val card = GiftCard("1234567890123", pin, MarketType.REWE)
            assertFalse("PIN $pin should be invalid", card.isValidPin())
        }
    }

    @Test
    fun `ALDI PIN validation accepts exactly 4 numeric digits`() {
        val card = GiftCard("12345678901234567890", "1234", MarketType.ALDI)

        assertTrue(card.isValidPin())
    }

    @Test
    fun `ALDI PIN validation rejects non-numeric or wrong length`() {
        val invalidPins = listOf("123", "12345", "ABCD", "12AB")

        invalidPins.forEach { pin ->
            val card = GiftCard("12345678901234567890", pin, MarketType.ALDI)
            assertFalse("PIN $pin should be invalid", card.isValidPin())
        }
    }

    @Test
    fun `LIDL PIN validation accepts exactly 4 numeric digits`() {
        val card = GiftCard("98765432109876543210", "9876", MarketType.LIDL)

        assertTrue(card.isValidPin())
    }

    @Test
    fun `LIDL PIN validation rejects non-numeric or wrong length`() {
        val invalidPins = listOf("987", "98765", "WXYZ", "98WX")

        invalidPins.forEach { pin ->
            val card = GiftCard("98765432109876543210", pin, MarketType.LIDL)
            assertFalse("PIN $pin should be invalid", card.isValidPin())
        }
    }

    @Test
    fun `getMaskedCardNumber masks middle digits correctly`() {
        val card = GiftCard("1234567890123", "5678", MarketType.REWE)

        val masked = card.getMaskedCardNumber()

        assertEquals("1234*****0123", masked)
        assertTrue(masked.startsWith("1234"))
        assertTrue(masked.endsWith("0123"))
    }

    @Test
    fun `getMaskedCardNumber handles short card numbers`() {
        val card = GiftCard("12345678", "5678", MarketType.REWE)

        val masked = card.getMaskedCardNumber()

        // Card numbers <= 8 digits are not masked
        assertEquals("12345678", masked)
    }

    @Test
    fun `getMaskedCardNumber handles 20-digit ALDI card`() {
        val card = GiftCard("12345678901234567890", "1234", MarketType.ALDI)

        val masked = card.getMaskedCardNumber()

        assertEquals("1234************7890", masked)
        assertTrue(masked.startsWith("1234"))
        assertTrue(masked.endsWith("7890"))
        assertEquals(20, masked.length)
    }

    @Test
    fun `companion object constants are correct`() {
        assertEquals("extra_gift_card", GiftCard.EXTRA_GIFT_CARD)
        assertEquals("extra_card_number", GiftCard.EXTRA_CARD_NUMBER)
        assertEquals("extra_pin", GiftCard.EXTRA_PIN)
        assertEquals("extra_market_type", GiftCard.EXTRA_MARKET_TYPE)
    }

    @Test
    fun `card timestamp is set correctly`() {
        val beforeTime = System.currentTimeMillis()
        val card = GiftCard("1234567890123", "5678", MarketType.REWE)
        val afterTime = System.currentTimeMillis()

        assertTrue(card.timestamp >= beforeTime)
        assertTrue(card.timestamp <= afterTime)
    }

    @Test
    fun `card with custom timestamp`() {
        val customTime = 1234567890L
        val card = GiftCard("1234567890123", "5678", MarketType.REWE, customTime)

        assertEquals(customTime, card.timestamp)
    }
}

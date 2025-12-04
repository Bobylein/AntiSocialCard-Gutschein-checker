package com.antisocial.giftcardchecker.markets

import android.graphics.Color
import com.antisocial.giftcardchecker.model.MarketType

/**
 * Market implementation for Lidl gift card balance checking.
 * Uses the tx-gate.com balance check service (same provider as ALDI).
 *
 * Card format:
 * - Card number: 20 digits (last 20 digits of scanned barcode)
 * - PIN: 4 digits
 *
 * All common functionality is provided by TxGateMarket base class.
 */
class LidlMarket : TxGateMarket() {

    override val marketType: MarketType = MarketType.LIDL

    override val displayName: String = "Lidl"

    override val balanceCheckUrl: String = "https://www.lidl.de/c/lidl-geschenkkarten/s10007775"

    override val brandColor: Int = Color.parseColor("#0050AA") // Lidl Blue

    /**
     * tx-gate.com client ID for Lidl
     */
    override val cid: Int = 79

    /**
     * Parent page URL (used for referrer headers)
     */
    override val parentPageUrl: String = "https://www.lidl.de/c/lidl-geschenkkarten/s10007775"

    /**
     * Referrer to use when loading the iframe URL directly
     */
    override val parentPageReferrer: String = "https://www.lidl.de/c/lidl-geschenkkarten/s10007775"
}

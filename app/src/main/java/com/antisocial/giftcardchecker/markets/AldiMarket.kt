package com.antisocial.giftcardchecker.markets

import android.graphics.Color
import com.antisocial.giftcardchecker.model.MarketType

/**
 * Market implementation for ALDI Nord gift card balance checking.
 * Uses the Helaba bank service via tx-gate.com for ALDI gift card balance inquiries.
 *
 * Card format:
 * - Gutschein: 20-digit voucher number
 * - PIN: 4-digit PIN
 *
 * All common functionality is provided by TxGateMarket base class.
 */
class AldiMarket : TxGateMarket() {

    override val marketType: MarketType = MarketType.ALDI

    override val displayName: String = "ALDI Nord"

    override val balanceCheckUrl: String = "https://www.helaba.com/de/aldi/"

    override val brandColor: Int = Color.parseColor("#00529B") // ALDI Blue

    /**
     * tx-gate.com client ID for ALDI
     */
    override val cid: Int = 59

    /**
     * Parent page URL (used for referrer headers)
     */
    override val parentPageUrl: String = "https://www.helaba.com/de/aldi/"

    /**
     * Referrer to use when loading the iframe URL directly
     */
    override val parentPageReferrer: String = "https://www.helaba.com/de/aldi/"
}

package com.antisocial.giftcardchecker.model

/**
 * Sealed class representing the state of the balance check process.
 * Replaces multiple boolean flags with a single, clear state machine.
 */
sealed class BalanceCheckState {
    /**
     * Initial loading state - page is being loaded
     */
    object Loading : BalanceCheckState()

    /**
     * Currently attempting to fill form fields with card data
     * @param attemptNumber The current attempt number (for retry logic)
     */
    data class FillingForm(val attemptNumber: Int = 1) : BalanceCheckState()

    /**
     * Form has been filled, waiting for user to solve CAPTCHA
     */
    object WaitingForCaptcha : BalanceCheckState()

    /**
     * Form has been submitted, waiting for balance result
     */
    object CheckingBalance : BalanceCheckState()

    /**
     * Balance check completed successfully
     * @param result The successful balance result
     */
    data class Success(val result: BalanceResult) : BalanceCheckState()

    /**
     * An error occurred during balance checking
     * @param result The error result
     */
    data class Error(val result: BalanceResult) : BalanceCheckState()

    /**
     * Returns true if this is a terminal state (Success or Error)
     */
    fun isTerminal(): Boolean = this is Success || this is Error

    /**
     * Returns true if form filling is in progress
     */
    fun isFillingForm(): Boolean = this is FillingForm

    /**
     * Returns true if balance check is in progress
     */
    fun isCheckingBalance(): Boolean = this is CheckingBalance

    /**
     * Returns true if waiting for user CAPTCHA input
     */
    fun isWaitingForCaptcha(): Boolean = this is WaitingForCaptcha

    override fun toString(): String = when (this) {
        is Loading -> "Loading"
        is FillingForm -> "FillingForm(attempt=$attemptNumber)"
        is WaitingForCaptcha -> "WaitingForCaptcha"
        is CheckingBalance -> "CheckingBalance"
        is Success -> "Success(${result.getFormattedBalance()})"
        is Error -> "Error(${result.status})"
    }
}

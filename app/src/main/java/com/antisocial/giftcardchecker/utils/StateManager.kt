package com.antisocial.giftcardchecker.utils

import android.util.Log
import com.antisocial.giftcardchecker.model.BalanceCheckState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Manages state transitions for balance checking with validation and logging.
 */
class StateManager(private val tag: String = "StateManager") {

    private val _state = MutableStateFlow<BalanceCheckState>(BalanceCheckState.Loading)
    val state: StateFlow<BalanceCheckState> = _state.asStateFlow()

    /**
     * Get the current state value
     */
    val currentState: BalanceCheckState
        get() = _state.value

    /**
     * Transition to a new state with validation
     */
    fun transitionTo(newState: BalanceCheckState) {
        val currentState = _state.value

        // Validate state transition
        if (!isValidTransition(currentState, newState)) {
            Log.w(tag, "Invalid state transition: $currentState -> $newState (ignored)")
            return
        }

        // Log state change
        Log.d(tag, "State transition: $currentState -> $newState")

        // Update state
        _state.value = newState
    }

    /**
     * Validates whether a state transition is allowed
     */
    private fun isValidTransition(from: BalanceCheckState, to: BalanceCheckState): Boolean {
        // Can't transition out of terminal states
        if (from.isTerminal()) {
            return false
        }

        // Define valid transitions
        return when (from) {
            is BalanceCheckState.Loading -> {
                to is BalanceCheckState.FillingForm ||
                to is BalanceCheckState.Error
            }
            is BalanceCheckState.FillingForm -> {
                to is BalanceCheckState.WaitingForCaptcha ||
                to is BalanceCheckState.FillingForm || // Allow retry with different attempt number
                to is BalanceCheckState.Error
            }
            is BalanceCheckState.WaitingForCaptcha -> {
                to is BalanceCheckState.CheckingBalance ||
                to is BalanceCheckState.Error
            }
            is BalanceCheckState.CheckingBalance -> {
                to is BalanceCheckState.Success ||
                to is BalanceCheckState.Error
            }
            is BalanceCheckState.Success,
            is BalanceCheckState.Error -> {
                // Terminal states - no transitions allowed
                false
            }
        }
    }

    /**
     * Reset to initial loading state (for restarts)
     */
    fun reset() {
        Log.d(tag, "State reset to Loading")
        _state.value = BalanceCheckState.Loading
    }
}

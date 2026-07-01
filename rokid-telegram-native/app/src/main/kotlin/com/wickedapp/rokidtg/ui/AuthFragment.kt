package com.wickedapp.rokidtg.ui

import android.content.Context
import android.os.Bundle
import android.text.InputType
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.wickedapp.rokidtg.MainActivity
import com.wickedapp.rokidtg.R
import com.wickedapp.rokidtg.service.TelegramService
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import org.drinkless.tdlib.TdApi
import timber.log.Timber

/**
 * Drives the TDLib authentication flow on first launch (no seeded session).
 *
 * State → input mapping:
 *   WaitPhoneNumber       → phone (E.164, e.g. +60193450205)
 *   WaitCode              → numeric SMS / Telegram code
 *   WaitPassword          → 2FA password
 *   WaitEmailAddress      → email (modern flow)
 *   WaitEmailCode         → email verification code
 *   WaitRegistration      → first name (single input; last name left blank)
 *   WaitTdlibParameters /
 *   WaitEncryptionKey     → "Initializing..." (input disabled)
 *
 * MainActivity swaps this fragment out for ChatListFragment when state reaches Ready.
 */
class AuthFragment : Fragment() {

    companion object {
        fun newInstance(): AuthFragment = AuthFragment()
    }

    private lateinit var promptView: TextView
    private lateinit var inputView: EditText
    private lateinit var errorView: TextView

    private val svc: TelegramService.LocalBinder?
        get() = (requireActivity() as? MainActivity)?.optionalService()

    override fun onCreateView(i: LayoutInflater, c: ViewGroup?, s: Bundle?): View =
        i.inflate(R.layout.fragment_auth, c, false)

    override fun onViewCreated(view: View, s: Bundle?) {
        promptView = view.findViewById(R.id.auth_prompt)
        inputView  = view.findViewById(R.id.auth_input)
        errorView  = view.findViewById(R.id.auth_error)

        inputView.setOnEditorActionListener { _, _, _ ->
            submitCurrent(); true
        }
        // Hardware ENTER (BT keyboard, adb input keyevent) doesn't always trigger IME action;
        // intercept the raw KeyEvent so submit fires regardless of input method.
        inputView.setOnKeyListener { _, keyCode, event ->
            if (event.action == KeyEvent.ACTION_DOWN &&
                (keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_DPAD_CENTER)) {
                submitCurrent(); true
            } else false
        }

        val binder = svc ?: return
        viewLifecycleOwner.lifecycleScope.launch {
            binder.getAuthStateFlow().filterNotNull().collect { renderState(it) }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            binder.getLastAuthErrorFlow().collect { err -> renderError(err) }
        }
    }

    private fun renderState(state: TdApi.AuthorizationState) {
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber -> configurePhone()
            is TdApi.AuthorizationStateWaitCode        -> configureCode(state)
            is TdApi.AuthorizationStateWaitPassword    -> configurePassword(state)
            is TdApi.AuthorizationStateWaitEmailAddress -> configureEmailAddress()
            is TdApi.AuthorizationStateWaitEmailCode    -> configureEmailCode()
            is TdApi.AuthorizationStateWaitRegistration -> configureRegistration()
            is TdApi.AuthorizationStateWaitTdlibParameters -> configureInitializing()
            // Ready is handled by MainActivity (swaps to ChatListFragment).
            else -> { /* no-op; MainActivity owns transitions out of this fragment */ }
        }
    }

    private fun configurePhone() {
        promptView.text = "Enter your phone number (with country code)"
        inputView.isEnabled = true
        inputView.inputType = InputType.TYPE_CLASS_PHONE
        inputView.hint = "+60193450205"
        clearAndFocus()
    }

    private fun configureCode(state: TdApi.AuthorizationStateWaitCode) {
        val info = state.codeInfo
        val target = (info?.phoneNumber?.takeIf { it.isNotEmpty() } ?: "your phone").let { "+$it".trimEnd('+') }
        promptView.text = "Enter the code sent to $target"
        inputView.isEnabled = true
        inputView.inputType = InputType.TYPE_CLASS_NUMBER
        inputView.hint = "123456"
        clearAndFocus()
    }

    private fun configurePassword(state: TdApi.AuthorizationStateWaitPassword) {
        val hint = state.passwordHint?.takeIf { it.isNotEmpty() }
        promptView.text = if (hint != null) "2FA password (hint: $hint)" else "Enter your 2FA password"
        inputView.isEnabled = true
        inputView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        inputView.hint = "password"
        clearAndFocus()
    }

    private fun configureEmailAddress() {
        promptView.text = "Enter your email address"
        inputView.isEnabled = true
        inputView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        inputView.hint = "you@example.com"
        clearAndFocus()
    }

    private fun configureEmailCode() {
        promptView.text = "Enter the code sent to your email"
        inputView.isEnabled = true
        inputView.inputType = InputType.TYPE_CLASS_NUMBER
        inputView.hint = "123456"
        clearAndFocus()
    }

    private fun configureRegistration() {
        promptView.text = "New account — enter your first name"
        inputView.isEnabled = true
        inputView.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_WORDS
        inputView.hint = "First name"
        clearAndFocus()
    }

    private fun configureInitializing() {
        promptView.text = "Initializing…"
        inputView.isEnabled = false
        inputView.text.clear()
    }

    private fun clearAndFocus() {
        inputView.text.clear()
        inputView.requestFocus()
        // Show soft keyboard for cases without a BT keyboard paired.
        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(inputView, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun renderError(err: String?) {
        if (err.isNullOrEmpty()) {
            errorView.visibility = View.GONE
            errorView.text = ""
        } else {
            errorView.visibility = View.VISIBLE
            errorView.text = err
        }
    }

    private fun submitCurrent() {
        val binder = svc ?: return
        val state = binder.getAuthStateFlow().value ?: return
        val value = inputView.text.toString().trim()
        Timber.tag("Auth").i("submit state=%s len=%d", state.javaClass.simpleName, value.length)
        if (value.isEmpty() && state !is TdApi.AuthorizationStateWaitTdlibParameters) {
            return
        }
        when (state) {
            is TdApi.AuthorizationStateWaitPhoneNumber  -> binder.submitPhoneNumber(value)
            is TdApi.AuthorizationStateWaitCode         -> binder.submitCode(value)
            is TdApi.AuthorizationStateWaitPassword     -> binder.submitPassword(value)
            is TdApi.AuthorizationStateWaitEmailAddress -> binder.submitEmailAddress(value)
            is TdApi.AuthorizationStateWaitEmailCode    -> binder.submitEmailCode(value)
            is TdApi.AuthorizationStateWaitRegistration -> binder.submitRegistration(value, "")
            else -> { /* no input expected */ }
        }
    }
}

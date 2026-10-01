package io.rg2.radio.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import io.rg2.radio.MainActivity
import io.rg2.radio.RadioApp
import kotlin.concurrent.thread

/** Receives io.rg2.radio:/oauth2redirect?code=…&state=… from the browser sign-in. */
class AuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as RadioApp
        val data = intent?.data
        val pending = getSharedPreferences(PENDING_PREFS, MODE_PRIVATE)
        val verifier = pending.getString("pkce_verifier", null)
        val expected = pending.getString("pkce_state", null)
        pending.edit().clear().apply()
        val code = data?.getQueryParameter("code")
        if (code == null || verifier == null || data.getQueryParameter("state") != expected) {
            Toast.makeText(this, "Sign-in failed — try again", Toast.LENGTH_LONG).show()
            finishToMain(); return
        }
        thread {
            val ok = app.container.auth.loginWithCode(code, verifier)
            runOnUiThread {
                if (ok) app.container.authHint.value = null
                Toast.makeText(this, if (ok) "Signed in" else "Sign-in failed — try again", Toast.LENGTH_SHORT).show()
                finishToMain()
            }
        }
    }

    private fun finishToMain() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }

    companion object { const val PENDING_PREFS = "radio-auth-pending" }
}

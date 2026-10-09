package com.example.drivestreamer.ui

import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.example.drivestreamer.R
import com.example.drivestreamer.auth.AuthManager
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.CommonStatusCodes

class LoginActivity : AppCompatActivity() {

    private lateinit var authManager: AuthManager

    private val signInLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        authManager.handleSignInResult(result.data).fold(
            onSuccess = { goToLibrary() },
            onFailure = { e ->
                val message = if (e is ApiException) {
                    "Sign-in failed: code ${e.statusCode} " +
                        "(${CommonStatusCodes.getStatusCodeString(e.statusCode)})"
                } else {
                    "Sign-in failed: ${e.message}"
                }
                Toast.makeText(this, message, Toast.LENGTH_LONG).show()
            }
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        authManager = AuthManager(this)

        // Already signed in from a previous session — skip this screen
        // entirely rather than making the user tap through it every time.
        if (authManager.lastSignedInAccount() != null) {
            goToLibrary()
            return
        }

        setContentView(R.layout.activity_login)
        findViewById<Button>(R.id.signInButton)
            .setOnClickListener { signInLauncher.launch(authManager.signInIntent()) }
    }

    private fun goToLibrary() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}

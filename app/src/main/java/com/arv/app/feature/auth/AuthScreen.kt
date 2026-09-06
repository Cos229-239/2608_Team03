package com.arv.app.feature.auth

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.arv.app.core.di.ServiceLocator
import com.arv.app.core.session.ActiveSession
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException
import com.google.firebase.auth.FirebaseAuthInvalidUserException
import com.google.firebase.auth.FirebaseAuthUserCollisionException
import com.google.firebase.auth.FirebaseAuthWeakPasswordException
import com.google.firebase.FirebaseNetworkException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Screen 00. Who is this.
 *
 * Email and password, nothing else, because a family archive does not need to know
 * anything about a person that their own relatives would not. The account is the key to
 * a family, not a profile; the profile is the Person row onboarding creates next.
 *
 * The sample family stays reachable from here without an account. That is deliberate and
 * it is what the build review runs on. Wiping app data to demo the app would be a design
 * failure, so the demo path cannot be behind a sign-in.
 */
class AuthViewModel : ViewModel() {

    data class State(
        val email: String = "",
        val password: String = "",
        val working: Boolean = false,
        val error: String? = null,
        val authenticated: Boolean = false
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun onEmail(v: String) = _state.update { it.copy(email = v, error = null) }
    fun onPassword(v: String) = _state.update { it.copy(password = v, error = null) }

    fun signIn() = run { auth -> auth.signInWithEmailAndPassword(email(), password()) }
    fun createAccount() = run { auth -> auth.createUserWithEmailAndPassword(email(), password()) }

    private fun email() = _state.value.email.trim()
    private fun password() = _state.value.password

    private fun run(
        call: (FirebaseAuth) -> com.google.android.gms.tasks.Task<com.google.firebase.auth.AuthResult>
    ) {
        val s = _state.value
        if (s.working) return
        if (email().isBlank() || password().isBlank()) {
            _state.update { it.copy(error = "Email and password are both needed.") }
            return
        }
        _state.update { it.copy(working = true, error = null) }

        // Firebase is configured by the google-services plugin from a file that is not
        // committed. A build without that file has no FirebaseApp and getInstance throws.
        // Saying so is better than crashing a teammate's first launch.
        val auth = runCatching { FirebaseAuth.getInstance() }.getOrNull()
        if (auth == null) {
            _state.update {
                it.copy(
                    working = false,
                    error = "Sign-in is not set up on this build yet. The sample family still works."
                )
            }
            return
        }

        call(auth)
            .addOnSuccessListener { result ->
                val user = result.user
                if (user == null) {
                    _state.update { it.copy(working = false, error = "Signed in, but no account came back. Try again.") }
                    return@addOnSuccessListener
                }
                ActiveSession.setAuth(user.uid, user.email)
                _state.update { it.copy(working = false, authenticated = true) }
            }
            .addOnFailureListener { t ->
                _state.update { it.copy(working = false, error = describe(t)) }
            }
    }

    /**
     * Firebase's messages are written for developers. These are written for the person
     * holding the phone, and they say what to do next rather than what went wrong inside.
     */
    private fun describe(t: Throwable): String = when (t) {
        is FirebaseAuthInvalidUserException -> "No account with that email. Create one below."
        is FirebaseAuthInvalidCredentialsException -> "That email or password is not right."
        is FirebaseAuthUserCollisionException -> "There is already an account with that email. Sign in instead."
        is FirebaseAuthWeakPasswordException -> "Password needs at least 6 characters."
        is FirebaseNetworkException -> "No connection. Check the network and try again."
        else -> "Could not sign in. Try again."
    }
}

@Composable
fun AuthScreen(
    onAuthenticated: () -> Unit,
    onSampleFamily: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    if (state.authenticated) {
        LaunchedEffect(Unit) { onAuthenticated() }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(32.dp))
        Text("Arv", style = MaterialTheme.typography.displaySmall)
        Text(
            "The stories your family tells, kept in your family's hands.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))

        OutlinedTextField(
            value = state.email,
            onValueChange = viewModel::onEmail,
            label = { Text("Email") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = state.password,
            onValueChange = viewModel::onPassword,
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            enabled = !state.working,
            modifier = Modifier.fillMaxWidth()
        )

        state.error?.let {
            Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = viewModel::signIn, enabled = !state.working) { Text("Sign in") }
            OutlinedButton(onClick = viewModel::createAccount, enabled = !state.working) {
                Text("Create account")
            }
        }

        if (state.working) {
            CircularProgressIndicator()
        }

        Spacer(Modifier.height(24.dp))
        TextButton(
            onClick = {
                ActiveSession.set(
                    familyId = ServiceLocator.DEMO_FAMILY_ID,
                    userId = ServiceLocator.DEMO_USER_ID,
                    familyName = "Sample family"
                )
                onSampleFamily()
            },
            enabled = !state.working
        ) {
            Text("Try the sample family without an account")
        }
    }
}

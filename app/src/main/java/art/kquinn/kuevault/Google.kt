package art.kquinn.kuevault

import android.accounts.Account
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.tasks.await

/**
 * Signing in to Google accounts with the phone's own Google sign-in. KueVault asks for one thing only: the
 * files it makes itself in each account (Google's "drive.file" permission). It can't see anything else
 * in the account. Every hour Google hands over a fresh pass without asking again.
 */
object Google {
    const val OWN_FILES = "https://www.googleapis.com/auth/drive.file"

    private class Pass(var token: String, var at: Long)

    private val passes = HashMap<String, Pass>()
    private val lock = Mutex()

    private fun request(email: String): AuthorizationRequest =
        AuthorizationRequest.builder()
            .setRequestedScopes(listOf(Scope(OWN_FILES)))
            .setAccount(Account(email, "com.google"))
            .build()

    /** The phone's own list of its Google accounts, with "Add account", for choosing which one to add. */
    fun chooser(): Intent =
        android.accounts.AccountManager.newChooseAccountIntent(null, null, arrayOf("com.google"), null, null, null, null)

    /** The address picked in [chooser]. */
    fun picked(data: Intent?): String? = data?.getStringExtra(android.accounts.AccountManager.KEY_ACCOUNT_NAME)?.takeIf { it.isNotBlank() }

    /** What asking for an account gave: a pass straight away, or Google's own page to show first. */
    sealed class Start {
        data class Ready(val token: String) : Start()
        data class ShowGoogle(val intent: PendingIntent) : Start()
        data class Failed(val message: String) : Start()
    }

    suspend fun start(context: Context, email: String): Start = try {
        val result = Identity.getAuthorizationClient(context).authorize(request(email)).await()
        val pending = result.pendingIntent
        val token = result.accessToken
        when {
            result.hasResolution() && pending != null -> Start.ShowGoogle(pending)
            token != null -> { keep(email, token); Start.Ready(token) }
            else -> Start.Failed("Google gave no sign-in. Try again.")
        }
    } catch (e: ApiException) {
        Start.Failed(explain(e))
    } catch (e: Exception) {
        Start.Failed("Couldn't reach Google. Check the internet and try again." + (e.message?.let { "\nGoogle's words: $it" } ?: ""))
    }

    /** Google's page has closed: what it gave for [email]. */
    fun finish(context: Context, email: String, data: Intent?): Start = try {
        val token = Identity.getAuthorizationClient(context).getAuthorizationResultFromIntent(data).accessToken
        if (token != null) { keep(email, token); Start.Ready(token) } else Start.Failed("Google gave no sign-in. Try again.")
    } catch (e: ApiException) {
        Start.Failed(explain(e))
    }

    fun explain(e: ApiException): String = when (e.statusCode) {
        // DEVELOPER_ERROR: the Google project doesn't list this app yet.
        10 -> "Google doesn't know KueVault yet. The one set-up step in Google Cloud is still needed." + words(e)
        16, 12501 -> "Sign-in was closed before it finished."
        7 -> "Couldn't reach Google. Check the internet and try again."
        else -> "Google said no (code " + e.statusCode + ")." + words(e)
    }

    private fun words(e: ApiException): String {
        val w = (e.status.statusMessage ?: e.message).orEmpty().removePrefix(e.statusCode.toString() + ":").trim()
        return if (w.isEmpty()) "" else "\nGoogle's words: $w"
    }

    private fun keep(email: String, token: String) {
        synchronized(passes) { passes[email] = Pass(token, System.currentTimeMillis()) }
    }

    /** Forgets a pass Google turned down, so the next call asks for a fresh one. */
    fun drop(email: String) {
        synchronized(passes) { passes.remove(email) }
    }

    /** A pass for [email], fresh when the last is near an hour old. Null when Google wants the person asked again. */
    suspend fun token(context: Context, email: String): String? = lock.withLock {
        val now = System.currentTimeMillis()
        synchronized(passes) { passes[email] }?.let { if (now - it.at < 45 * 60_000L) return@withLock it.token }
        val result = runCatching { Identity.getAuthorizationClient(context).authorize(request(email)).await() }.getOrNull() ?: return@withLock null
        if (result.hasResolution()) return@withLock null
        val token = result.accessToken ?: return@withLock null
        keep(email, token)
        token
    }
}

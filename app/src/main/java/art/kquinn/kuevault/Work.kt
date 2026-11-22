package art.kquinn.kuevault

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * What KueVault does with the accounts: how full they are, what is in Documents, putting files in,
 * opening them and binning them. Each call gives the screen plain words for anything that went wrong.
 */
object Work {
    /** What is being done now, for the screen: "Uploading Report.pdf · 42%". Empty when nothing is. */
    var doing by mutableStateOf("")
    /** The last thing that went wrong, until it is closed. */
    var problem by mutableStateOf("")
    /** Accounts Google wants signed in again before they can be used. */
    val signInAgain = mutableStateListOf<String>()

    val documents = mutableStateListOf<Drive.Item>()
    var documentsRead by mutableStateOf(false)

    /** A pass for [email], or null with the account marked to sign in again. */
    private suspend fun pass(context: Context, email: String): String? {
        val token = Google.token(context, email)
        if (token == null) { if (email !in signInAgain) signInAgain += email } else signInAgain.remove(email)
        return token
    }

    /** Asks each account how full it is. */
    suspend fun readSpace(context: Context) {
        for (a in Vault.accounts.toList()) {
            val token = pass(context, a.email) ?: continue
            val quota = withContext(Dispatchers.IO) {
                runCatching { Drive.about(token).second }.onFailure { failed(a.email, it, "reading space") }.getOrNull()
            } ?: continue
            Vault.keepQuota(a.email, quota)
        }
    }

    private fun failed(email: String, e: Throwable, what: String) {
        if (e is Drive.SignedOut) { Google.drop(email); if (email !in signInAgain) signInAgain += email }
        Report.note(what + " failed for " + Vault.hidden(email) + ": " + e.message)
    }

    /** Reads what is in Documents across all its accounts. */
    suspend fun readDocuments(context: Context) {
        val all = ArrayList<Drive.Item>()
        for (a in Vault.forSection(Section.DOCUMENTS)) {
            val token = pass(context, a.email) ?: continue
            withContext(Dispatchers.IO) {
                runCatching { Drive.list(token, a.email, Section.DOCUMENTS) }.onFailure { failed(a.email, it, "reading Documents") }.getOrNull()
            }?.let { all.addAll(it) }
        }
        documents.clear()
        documents.addAll(all.sortedByDescending { it.modifiedMs })
        documentsRead = true
    }

    /** Name, size and kind of a file the person picked on the phone. */
    private fun describe(context: Context, uri: Uri): Triple<String, Long, String> {
        var name = "File"
        var size = -1L
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (c.moveToFirst()) {
                c.getString(0)?.let { name = it }
                if (!c.isNull(1)) size = c.getLong(1)
            }
        }
        return Triple(name, size, context.contentResolver.getType(uri) ?: "application/octet-stream")
    }

    /** Puts files from the phone into Documents, each into the first of its accounts with room. */
    suspend fun upload(context: Context, uris: List<Uri>) {
        if (Vault.forSection(Section.DOCUMENTS).isEmpty()) { problem = "Documents has no account yet. Add one in Accounts first."; return }
        var done = 0
        for (uri in uris) {
            val (name, size, mime) = withContext(Dispatchers.IO) { describe(context, uri) }
            val account = Vault.pick(Section.DOCUMENTS, size.coerceAtLeast(0))
            if (account == null) { problem = "No room left in Documents for $name. Add another account to Documents in Accounts."; break }
            val token = pass(context, account.email) ?: run { problem = "Sign in to " + account.email + " again, in Accounts."; null } ?: break
            doing = "Uploading $name · 0%"
            val item = withContext(Dispatchers.IO) {
                runCatching { Drive.upload(context, token, account.email, Section.DOCUMENTS, uri, name, mime, size) { p -> doing = "Uploading $name · $p%" } }
                    .onFailure { failed(account.email, it, "uploading") }.getOrNull()
            }
            if (item == null) { problem = "$name didn't upload. Check the internet and try again."; break }
            documents.add(0, item)
            Vault.quotas[account.email]?.let { Vault.keepQuota(account.email, it.copy(used = it.used + item.size)) }
            Report.note("uploaded a file of " + Vault.size(item.size) + " to " + Vault.hidden(account.email))
            done++
        }
        doing = ""
        if (done > 0) readSpace(context)
    }

    /** Downloads a file and hands it to the app on the phone that opens that kind of file. */
    suspend fun open(context: Context, item: Drive.Item) {
        val token = pass(context, item.email) ?: run { problem = "Sign in to " + item.email + " again, in Accounts."; return }
        val folder = File(context.cacheDir, "open").apply { mkdirs() }
        val file = File(folder, item.id + "-" + item.name.replace(Regex("[\\\\/:*?\"<>|]"), "_"))
        if (!file.exists() || file.length() != item.size) {
            doing = "Opening " + item.name + " · 0%"
            val ok = withContext(Dispatchers.IO) {
                runCatching { Drive.download(token, item, file) { p -> doing = "Opening " + item.name + " · $p%" } }
                    .onFailure { failed(item.email, it, "downloading") }.isSuccess
            }
            doing = ""
            if (!ok) { problem = item.name + " didn't download. Check the internet and try again."; return }
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(context, context.packageName + ".files", file)
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, item.mime.ifBlank { "*/*" }).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(Intent.createChooser(view, item.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { problem = "No app on this phone opens this kind of file." }
    }

    /** Moves a file to its account's bin, where Google keeps it for 30 days. */
    suspend fun bin(context: Context, item: Drive.Item) {
        val token = pass(context, item.email) ?: run { problem = "Sign in to " + item.email + " again, in Accounts."; return }
        val ok = withContext(Dispatchers.IO) { runCatching { Drive.bin(token, item) }.onFailure { failed(item.email, it, "binning") }.isSuccess }
        if (ok) documents.remove(item) else problem = item.name + " couldn't be moved to the bin. Try again."
    }
}

package art.kquinn.kuevault

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    /** The section a Google account is being connected for (if any), and the account, while the phone's and Google's pages are open. */
    private var addingFor: Section? = null
    private var addingEmail: String? = null

    private val accountChooser = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val email = Google.picked(result.data)
        if (email == null) { addingFor = null; return@registerForActivityResult }
        addingEmail = email
        ask(email)
    }

    private val googlePage = registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { result ->
        val email = addingEmail ?: return@registerForActivityResult
        when (val r = Google.finish(this, email, result.data)) {
            is Google.Start.Ready -> accepted(email, r.token)
            is Google.Start.Failed -> { Work.problem = r.message; Report.note("sign-in failed: " + r.message) }
            is Google.Start.ShowGoogle -> Work.problem = "Sign-in didn't finish. Try again."
        }
    }

    private val filePicker = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) lifecycleScope.launch { Work.upload(this@MainActivity, uris) }
    }

    /** Asks Google for KueVault's one permission on [email]: straight away when it was given before. */
    private fun ask(email: String) {
        lifecycleScope.launch {
            when (val r = Google.start(this@MainActivity, email)) {
                is Google.Start.Ready -> accepted(email, r.token)
                is Google.Start.ShowGoogle -> googlePage.launch(IntentSenderRequest.Builder(r.intent.intentSender).build())
                is Google.Start.Failed -> { Work.problem = r.message; Report.note("sign-in failed: " + r.message) }
            }
        }
    }

    /** Google said yes: the account is a place (or back in use), with its space read, and the section lives there. */
    private fun accepted(email: String, token: String) {
        val section = addingFor
        val again = Vault.place(Place.google(email).id) != null
        addingFor = null
        addingEmail = null
        val place = Vault.connect(Vault.place(Place.google(email).id) ?: Place.google(email))
        Work.signInAgain.remove(place.id)
        if (section != null) Vault.setHome(section, place.id)
        lifecycleScope.launch {
            val about = withContext(Dispatchers.IO) { runCatching { Drive.about(token) }.getOrNull() }
            about?.let { Vault.keepQuota(place.id, it.second) }
            Work.doing = email + (if (again) " is signed in again." else " connected.") + (section?.let { " " + it.title + " lives there now." } ?: "")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Report.init(this)
        Vault.init(this)
        setContent {
            Ui.App(
                Asks(
                    addGoogle = { section -> addingFor = section; runCatching { accountChooser.launch(Google.chooser()) }.onFailure { Work.problem = "This phone has no list of Google accounts to choose from." } },
                    signInAgain = { email -> addingFor = null; addingEmail = email; ask(email) },
                    pickFiles = { filePicker.launch(arrayOf("*/*")) },
                ),
            )
        }
    }

    override fun onResume() {
        super.onResume()
        lifecycleScope.launch(Dispatchers.IO) { Updates.checkAndFetch(applicationContext) }
    }

    /** Leaving KueVault: a downloaded update goes on now, when nothing is being moved. */
    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && Work.doing.isEmpty()) Updates.installIfReady(applicationContext)
    }
}

package nl.giejay.android.tv.immich.auth

import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.InputType
import android.widget.Toast
import androidx.leanback.app.GuidedStepSupportFragment
import androidx.leanback.widget.GuidanceStylist
import androidx.leanback.widget.GuidedAction
import androidx.leanback.widget.GuidedActionEditText
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.MainActivity
import nl.giejay.android.tv.immich.api.ApiClient
import nl.giejay.android.tv.immich.api.ApiClientConfig
import nl.giejay.android.tv.immich.shared.guidedstep.GuidedStepUtil.addAction
import nl.giejay.android.tv.immich.shared.guidedstep.GuidedStepUtil.addCheckedAction
import nl.giejay.android.tv.immich.shared.guidedstep.GuidedStepUtil.addEditableAction
import nl.giejay.android.tv.immich.shared.prefs.API_KEY
import nl.giejay.android.tv.immich.shared.prefs.DEBUG_MODE
import nl.giejay.android.tv.immich.shared.prefs.DISABLE_SSL_VERIFICATION
import nl.giejay.android.tv.immich.shared.prefs.HOST_NAME
import nl.giejay.android.tv.immich.shared.prefs.PreferenceManager
import nl.giejay.android.tv.immich.shared.prefs.SCREENSAVER_ALBUMS
import timber.log.Timber


data class AuthSettings(val hostName: String, val apiKey: String) {
    fun isValid(): Boolean {
        return PreferenceManager.isValid(hostName, apiKey)
    }

    fun normalized(): AuthSettings = copy(hostName = hostName.trim().trimEnd('/'), apiKey = apiKey.trim())
}

class AuthFragmentStep2 : GuidedStepSupportFragment() {
    private val ACTION_NAME = 0L
    private val ACTION_API_KEY = 1L
    private val ACTION_CHECK_CERTS = 2L
    private val ACTION_DEBUG_MODE = 3L
    private val ACTION_CONTINUE = 4L
    private var validating = false

    override fun onCreateGuidance(savedInstanceState: Bundle?): GuidanceStylist.Guidance {
        val icon: Drawable = requireContext().getDrawable(R.drawable.icon)!!
        return GuidanceStylist.Guidance(
            getString(R.string.app_name),
            getString(R.string.login_immich_description),
            "",
            icon
        )
    }

    override fun onCreateActions(actions: MutableList<GuidedAction>, savedInstanceState: Bundle?) {
        addEditableAction(
            actions,
            ACTION_NAME,
            getString(R.string.server_url_hint),
            PreferenceManager.hostName,
            InputType.TYPE_CLASS_TEXT
        )
        addEditableAction(
            actions,
            ACTION_API_KEY,
            getString(R.string.api_key_text),
            PreferenceManager.get(API_KEY),
            InputType.TYPE_CLASS_TEXT
        )
        addCheckedAction(
            actions,
            ACTION_CHECK_CERTS,
            getString(R.string.disable_ssl_verification),
            getString(R.string.disable_ssl_verification_desc),
            PreferenceManager.get(DISABLE_SSL_VERIFICATION)
        )
        addCheckedAction(
            actions,
            ACTION_DEBUG_MODE,
            getString(R.string.debug_mode),
            getString(R.string.debug_mode_desc),
            PreferenceManager.get(DEBUG_MODE)
        )
    }

    override fun onCreateButtonActions(
        actions: MutableList<GuidedAction>,
        savedInstanceState: Bundle?
    ) {
        super.onCreateButtonActions(actions, savedInstanceState)
        addAction(actions, ACTION_CONTINUE, getString(R.string.submit), "")
    }

    override fun onGuidedActionClicked(action: GuidedAction) {
        super.onGuidedActionClicked(action)
        val entry = AuthSettings(getState(ACTION_NAME), getState(ACTION_API_KEY)).normalized()
        Timber.i("Clicked on ${action.title} in step 2, entry valid: ${entry.isValid()}")
        if (action.id == ACTION_CONTINUE) {
            if (entry.isValid()) {
                validateAndSignIn(entry)
            } else if (entry.hostName.isEmpty()) {
                Toast.makeText(activity, getString(R.string.enter_server_url), Toast.LENGTH_SHORT)
                    .show()
            } else if (entry.apiKey.isEmpty()) {
                Toast.makeText(
                    activity,
                    getString(R.string.enter_api_key),
                    Toast.LENGTH_LONG
                ).show()
            } else {
                Toast.makeText(activity, getString(R.string.enter_valid_server_url), Toast.LENGTH_SHORT)
                    .show()
            }
        }
    }

    private fun validateAndSignIn(entry: AuthSettings) {
        if (validating) return
        validating = true
        val disableSsl = findActionById(ACTION_CHECK_CERTS)?.isChecked == true
        val debugMode = findActionById(ACTION_DEBUG_MODE)?.isChecked == true
        Toast.makeText(context, R.string.checking_immich_connection, Toast.LENGTH_SHORT).show()
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                // Validate the entered values before saving them. Response-body logging is disabled here.
                val result = ApiClient.getClient(ApiClientConfig(entry.hostName, entry.apiKey, disableSsl, false))
                    .validateAuthentication()
                currentCoroutineContext().ensureActive()
                val currentEntry = AuthSettings(getState(ACTION_NAME), getState(ACTION_API_KEY)).normalized()
                if (entry != currentEntry || disableSsl != (findActionById(ACTION_CHECK_CERTS)?.isChecked == true)
                    || debugMode != (findActionById(ACTION_DEBUG_MODE)?.isChecked == true)) {
                    Toast.makeText(context, R.string.sign_in_details_changed, Toast.LENGTH_LONG).show()
                    return@launch
                }
                result.fold(
                    { error ->
                        Toast.makeText(context, getString(R.string.sign_in_check_failed, error), Toast.LENGTH_LONG).show()
                    },
                    {
                        if (PreferenceManager.hostName != entry.hostName) {
                            PreferenceManager.save(SCREENSAVER_ALBUMS, emptySet())
                        }
                        PreferenceManager.save(API_KEY, entry.apiKey)
                        PreferenceManager.save(HOST_NAME, entry.hostName)
                        PreferenceManager.save(DISABLE_SSL_VERIFICATION, disableSsl)
                        PreferenceManager.save(DEBUG_MODE, debugMode)
                        val storedMatches = API_KEY.getValue(PreferenceManager.sharedPreference) == entry.apiKey
                        val activeMatches = PreferenceManager.get(API_KEY) == entry.apiKey
                        Timber.i("Authentication storage check: storedMatches=%b, activeMatches=%b", storedMatches, activeMatches)
                        if (!storedMatches || !activeMatches) {
                            Toast.makeText(context, R.string.sign_in_storage_failed, Toast.LENGTH_LONG).show()
                        } else {
                            // Timeline's activity-scoped ViewModel retains its original API client.
                            // A new task disposes all old session models before loading the new login.
                            startActivity(
                                Intent(requireContext(), MainActivity::class.java).addFlags(
                                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                                )
                            )
                        }
                    }
                )
            } finally {
                validating = false
            }
        }
    }

    private fun getState(actionId: Long): String {
        // there really does not seem to be a better way to handle hardware keyboard input or for example: adb shell input text myText
        // https://github.com/giejay/Immich-Android-TV/issues/4
        val position = findActionPositionById(actionId)
        return guidedFieldValue(
            getActionItemView(position)?.findViewById<GuidedActionEditText>(R.id.guidedactions_item_description)?.text,
            findActionById(actionId)?.description
        )
    }
}

package football.persian.footballe.data

import android.content.Context
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.backgroundDataStore by preferencesDataStore(name = "background_settings")

class BackgroundPreferences(private val context: Context) {

    companion object {
        private val BACKGROUND_KEY = stringPreferencesKey("background_choice")
    }

    val backgroundChoice: Flow<String> = context.backgroundDataStore.data
        .map { prefs -> prefs[BACKGROUND_KEY] ?: "bg2" }

    suspend fun setBackground(choice: String) {
        context.backgroundDataStore.edit { prefs ->
            prefs[BACKGROUND_KEY] = choice
        }
    }
}

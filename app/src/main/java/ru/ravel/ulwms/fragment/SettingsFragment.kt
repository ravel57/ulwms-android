package ru.ravel.ulwms.fragment

import android.os.Bundle
import androidx.preference.PreferenceFragmentCompat
import ru.ravel.ulwms.R

class SettingsFragment : PreferenceFragmentCompat() {

	override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
		setPreferencesFromResource(R.xml.settings_screen, rootKey)
	}
}
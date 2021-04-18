package com.gmail.matejpesl1.mimi.activities;

import android.content.Context;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.SeekBar;

import androidx.annotation.NonNull;
import androidx.appcompat.app.ActionBar;
import androidx.appcompat.app.AppCompatActivity;
import androidx.preference.CheckBoxPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SeekBarPreference;

import com.gmail.matejpesl1.mimi.R;
import com.gmail.matejpesl1.mimi.UpdateServiceAlarmManager;
import com.gmail.matejpesl1.mimi.fragments.TimePickerFragment;
import com.gmail.matejpesl1.mimi.utils.RootUtils;
import com.gmail.matejpesl1.mimi.utils.Utils;

import java.util.Date;
import java.util.concurrent.TimeUnit;

public class SettingsActivity extends AppCompatActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings_activity);

        if (savedInstanceState == null) {
            getSupportFragmentManager()
                    .beginTransaction()
                    .replace(R.id.settings, new SettingsFragment())
                    .commit();
        }

        ActionBar actionBar = getSupportActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
            actionBar.setTitle("Nastavení");
        }
    }

    // Otherwise the "back" button in actionBar does nothing.
    @Override public boolean onSupportNavigateUp() {
        onBackPressed();
        return true;
    }

    public static class SettingsFragment extends PreferenceFragmentCompat {
        private Context context;
        private Preference updateTimePref;
        private Preference rootPermissionPref;
        private Preference batteryExceptionPref;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            setPreferencesFromResource(R.xml.root_preferences, rootKey);
            // Initialize.
            updateTimePref = findPreference(getString(R.string.setting_update_time_key));
            rootPermissionPref = findPreference(getString(R.string.setting_root_permission_key));
            batteryExceptionPref = findPreference(getString(R.string.setting_battery_exception_key));

            // Update state.
            updateUpdateTimeSummary();
            if (Utils.hasBatteryException(context))
                batteryExceptionPref.setSummary("Povoleno");

            // Set listeners.
            setOnClickListeners();
        }

        private void setOnClickListeners() {
            updateTimePref.setOnPreferenceClickListener((Preference.OnPreferenceClickListener) preference -> {
                TimePickerFragment picker = new TimePickerFragment(this::handleTimePicked,
                        UpdateServiceAlarmManager.getCurrUpdateCalendar(context));

                picker.show(getParentFragmentManager(), "timePicker");
                return true;
            });

            rootPermissionPref.setOnPreferenceClickListener((Preference.OnPreferenceClickListener) preference -> {
                /*new Thread(() -> {
                    // Checking it also requests it at the same time.
                    if (RootUtils.isRootAvailable())
                        getActivity().runOnUiThread(() -> rootPermissionPref.setSummary("Povoleno"));
                }).start();*/
                RootUtils.askForRoot();
                return true;
            });

            batteryExceptionPref.setOnPreferenceClickListener((Preference.OnPreferenceClickListener) preference -> {
                if (!Utils.hasBatteryException(context))
                    Utils.requestBatteryException(context);
                return true;
            });
        }

        @Override
        public void onAttach(@NonNull Context context) {
            super.onAttach(context);
            this.context = context;
        }

        private void handleTimePicked(int hour, int minute) {
            UpdateServiceAlarmManager.changeUpdateTime(context, minute, hour);
            updateUpdateTimeSummary();
        }

        private void updateUpdateTimeSummary() {
            Date currUpdateDate = UpdateServiceAlarmManager.getCurrUpdateCalendar(context).getTime();
            updateTimePref.setSummary(Utils.dateToDigitalTime(currUpdateDate));
        }
    }
}
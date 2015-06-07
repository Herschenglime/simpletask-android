/**
 * This file is part of Todo.txt Touch, an Android app for managing your todo.txt file (http://todotxt.com).
 *
 * Copyright (c) 2009-2012 Todo.txt contributors (http://todotxt.com)
 * Copyright (c) 2015 Vojtech Kral
 *
 * LICENSE:
 *
 * Todo.txt Touch is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.
 *
 * Todo.txt Touch is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with Todo.txt Touch.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * @author Todo.txt contributors <todotxt@yahoogroups.com>
 * @license http://www.gnu.org/licenses/gpl.html
 * @copyright 2009-2012 Todo.txt contributors (http://todotxt.com)
 * @copyright 2015 Vojtech Kral
 */
package nl.mpcjanssen.simpletask;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.*;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager.NameNotFoundException;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import nl.mpcjanssen.simpletask.util.Util;
import org.jetbrains.annotations.NotNull;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

public class Preferences extends ThemedActivity {
    static TodoApplication m_app ;
    final static String TAG = Preferences.class.getSimpleName();
	public static final int RESULT_LOGOUT = RESULT_FIRST_USER + 1;
	public static final int RESULT_ARCHIVE = RESULT_FIRST_USER + 2;
    public static final int RESULT_RECREATE_ACTIVITY = RESULT_FIRST_USER + 3;
    private LocalBroadcastManager localBroadcastManager;

	private void broadcastIntentAndClose(String intent, int result) {

		Intent broadcastIntent = new Intent(intent);
		localBroadcastManager.sendBroadcast(broadcastIntent);

		// Close preferences screen
		setResult(result);
		finish();
	}

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		// Display the fragment as the main content.

        TodoTxtPrefFragment prefFragment = new TodoTxtPrefFragment();
        localBroadcastManager = LocalBroadcastManager.getInstance(this);

		getFragmentManager().beginTransaction()
				.replace(android.R.id.content, prefFragment)
				.commit();
	}

	public static class TodoTxtPrefFragment extends PreferenceFragment implements
    SharedPreferences.OnSharedPreferenceChangeListener {
        @Override
        public void onCreate(final Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            addPreferencesFromResource(R.xml.preferences);
            PackageInfo packageInfo;
            final Preference versionPref = findPreference("app_version");
            try {
                packageInfo = getActivity().getPackageManager().getPackageInfo(
                        getActivity().getPackageName(), 0);
                versionPref.setTitle("Simpletask " + BuildConfig.FLAVOR + " v" + packageInfo.versionName + " (" + BuildConfig.VERSION_CODE + ")");
            } catch (NameNotFoundException e) {
                e.printStackTrace();
            }
            m_app = (TodoApplication) getActivity().getApplication();
            if (m_app.storeType() != Constants.STORE_DROPBOX) {
                PreferenceCategory dropboxCategory = (PreferenceCategory) findPreference(getString(R.string.dropbox_cat_key));
                getPreferenceScreen().removePreference(dropboxCategory);
            }
            Preference toHide;
            PreferenceCategory aboutCategory = (PreferenceCategory) findPreference("about");
            if (m_app.hasDonated()) {
                toHide = findPreference("donate");
            } else {
                toHide = findPreference("donated");
            }
            aboutCategory.removePreference(toHide);

            if (!TodoApplication.API16) {
                Preference calSyncPref = findPreference(getString(R.string.calendar_sync_screen));
                PreferenceCategory behaviorCategory = (PreferenceCategory) findPreference(getString(R.string.experimental_cat_key));
                behaviorCategory.removePreference(calSyncPref);
            }
        }

        private void sendLog() {
            StringBuilder log = new StringBuilder();
            try {
                Process process = Runtime.getRuntime().exec("logcat -d -v long");
                BufferedReader bufferedReader = new BufferedReader(
                        new InputStreamReader(process.getInputStream()));

                String line;
                while ((line = bufferedReader.readLine()) != null) {
                    log.append(line).append("\n");
                }
            } catch (IOException e) {
                log.append(e.toString());
            }
            Intent shareIntent = new Intent(android.content.Intent.ACTION_SEND);
            shareIntent.setType("text/plain");
            shareIntent.putExtra(android.content.Intent.EXTRA_SUBJECT,
                    "Simpletask Logcat");
            // Create a cache file to pass in EXTRA_STREAM
            try {
                Util.createCachedFile(this.getActivity(),
                        "logcat.txt", log.toString());
                Uri fileUri = Uri.parse("content://" + CachedFileProvider.AUTHORITY + "/"
                        + "logcat.txt");
                shareIntent.putExtra(android.content.Intent.EXTRA_STREAM, fileUri);
            } catch (Exception e) {
                Log.w(TAG, "Failed to create file for sharing");
            }
            startActivity(Intent.createChooser(shareIntent, "Share log"));
        }

        @Override
        public void onResume() {
            super.onResume();
            // Set up a listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().registerOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onPause() {
            super.onPause();
            // Set up a listener whenever a key changes
            getPreferenceScreen().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(this);
        }

        @Override
        public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
            // require restart with UI changes
            if ("theme".equals(key) || "fontsize".equals(key)) {
                getActivity().setResult(RESULT_RECREATE_ACTIVITY);
                getActivity().finish();
            }
        }

        @Override
        public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen,
                @NotNull Preference preference) {

            String key = preference.getKey();
            if (key == null) {
                return false;
            }
            switch (key) {
                case "archive_now":
                    Log.v("PREFERENCES",
                            "Archiving completed items from preferences");
                    m_app.showConfirmationDialog(this.getActivity(), R.string.delete_task_message, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ((Preferences) getActivity()).broadcastIntentAndClose(
                                    Constants.BROADCAST_ACTION_ARCHIVE,
                                    Preferences.RESULT_ARCHIVE);
                        }
                    }, R.string.archive_task_title);
                    break;
                case "logout_dropbox":
                    Log.v("PREFERENCES", "Logging out from Dropbox");
                    m_app.showConfirmationDialog(this.getActivity(), R.string.logout_message, new DialogInterface.OnClickListener() {
                        @Override
                        public void onClick(DialogInterface dialogInterface, int i) {
                            ((Preferences) getActivity()).broadcastIntentAndClose(
                                    Constants.BROADCAST_ACTION_LOGOUT,
                                    Preferences.RESULT_LOGOUT);
                        }
                    }, R.string.dropbox_logout_pref_title);

                    break;
                case "send_log":
                    sendLog();
                    break;
                case "app_version":
                    ClipboardManager clipboard = (ClipboardManager)
                            m_app.getSystemService(Context.CLIPBOARD_SERVICE);
                    Preference versionPref = findPreference("app_version");
                    ClipData clip = ClipData.newPlainText(getString(R.string.version_copied),versionPref.getTitle());
                    clipboard.setPrimaryClip(clip);
                    Util.showToastShort(getActivity(),R.string.version_copied);
                    break;
            }
			return false;
		}
	}
}

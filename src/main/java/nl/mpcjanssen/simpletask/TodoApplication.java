/**
 *
 * Copyright (c) 2009-2012 Todo.txt contributors (http://todotxt.com)
 * Copyright (c) 2013- Mark Janssen
 * Copyright (c) 2015 Vojtech Kral
 *
 * LICENSE:
 *
 * Simpletas is free software: you can redistribute it and/or modify it under the terms of the GNU General Public
 * License as published by the Free Software Foundation, either version 2 of the License, or (at your option) any
 * later version.
 *
 * Simpletask is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without even the implied
 * warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License along with Sinpletask.  If not, see
 * <http://www.gnu.org/licenses/>.
 *
 * @author Todo.txt contributors <todotxt@yahoogroups.com>
 * @license http://www.gnu.org/licenses/gpl.html
 * @copyright 2009-2012 Todo.txt contributors (http://todotxt.com)
 * @copyright 2013- Mark Janssen
 * @copyright 2015 Vojtech Kral
 */
package nl.mpcjanssen.simpletask;

import android.app.*;
import android.appwidget.AppWidgetManager;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.os.AsyncTask;
import android.os.Build;
import android.preference.PreferenceManager;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;
import android.view.Window;
import android.widget.EditText;
import nl.mpcjanssen.simpletask.remote.FileStore;
import nl.mpcjanssen.simpletask.remote.FileStoreInterface;
import nl.mpcjanssen.simpletask.task.TaskCache;
import nl.mpcjanssen.simpletask.util.Util;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;


public class TodoApplication extends Application implements SharedPreferences.OnSharedPreferenceChangeListener {
    private final static String TAG = TodoApplication.class.getSimpleName();
    private static Context m_appContext;
    private static SharedPreferences m_prefs;
    private LocalBroadcastManager localBroadcastManager;
    private ArrayList<File> todoTrail = new ArrayList<File>();

    @Nullable
    private FileStoreInterface mFileStore;
    @Nullable
    private TaskCache m_taskCache;
    private CalendarSync m_calSync;
    private BroadcastReceiver m_broadcastReceiver;

    public static final boolean API16 = android.os.Build.VERSION.SDK_INT >= 16;
    private int m_Theme = -1;

    public static Context getAppContext() {
        return m_appContext;
    }

    public static SharedPreferences getPrefs() {
        return m_prefs;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        TodoApplication.m_appContext = getApplicationContext();
        TodoApplication.m_prefs = PreferenceManager.getDefaultSharedPreferences(getAppContext());
        getTaskCache(null);
        m_calSync = new CalendarSync(this, isSyncDues(), isSyncThresholds());
        localBroadcastManager = LocalBroadcastManager.getInstance(this);
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(Constants.BROADCAST_UPDATE_UI);
        intentFilter.addAction(Constants.BROADCAST_FILE_CHANGED);
        intentFilter.addAction(Constants.BROADCAST_FILE_WRITE_FAILED);
        intentFilter.addAction(Constants.BROADCAST_TASKCACHE_CHANGED);
        m_broadcastReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, @NotNull Intent intent) {
                if (intent.getAction().equals(Constants.BROADCAST_FILE_CHANGED)) {
                    // File change reload task cache
                    try {
                        getFileStore().loadTasksFromFile(getTodoFileName(),getTaskCache(null));
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                } else if (intent.getAction().equals(Constants.BROADCAST_TASKCACHE_CHANGED)) {
                    getFileStore().saveTasksToFile(getTodoFileName(), getTaskCache(null));
                } else if (intent.getAction().equals(Constants.BROADCAST_UPDATE_UI)) {
                    m_calSync.syncLater();
                    updateWidgets();
                } else if (intent.getAction().equals(Constants.BROADCAST_FILE_WRITE_FAILED)) {
                    Util.showToastLong(getApplicationContext(), R.string.write_failed);
                }
            }
        };
        localBroadcastManager.registerReceiver(m_broadcastReceiver, intentFilter);
        prefsChangeListener(this);
    }

    public void prefsChangeListener(SharedPreferences.OnSharedPreferenceChangeListener listener) {
        m_prefs.registerOnSharedPreferenceChangeListener(listener);
    }

    public LocalBroadcastManager getLocalBroadCastManager() {
        return localBroadcastManager;
    }


    public void deauthenticate() {
        if (mFileStore!=null) {
            mFileStore.deauthenticate();
            mFileStore=null;
        }
    }

    public boolean fileStoreCanSync() {
        return mFileStore != null && mFileStore.supportsSync();
    }

    public void sync() {
        if (mFileStore!=null) {
            new AsyncTask<FileStoreInterface, Void, Void>() {

                @Override
                protected Void doInBackground(FileStoreInterface... params) {
                    FileStoreInterface fs = params[0];
                    fs.sync();
                    return null;
                }
            }.execute(mFileStore);
        }
    }

    @Override
    public void onTerminate() {
        Log.v(TAG, "Deregistered receiver");
        m_prefs.unregisterOnSharedPreferenceChangeListener(this);
        if (m_broadcastReceiver!=null) {
            localBroadcastManager.unregisterReceiver(m_broadcastReceiver);
        }
        super.onTerminate();
    }


    public String[] getDefaultSorts () {
        return getResources().getStringArray(R.array.sortKeys);
    }

    public boolean showCompleteCheckbox() {
        return m_prefs.getBoolean(getString(R.string.ui_complete_checkbox), true);
    }

    public boolean showCalendar() {
        return m_prefs.getBoolean(getString(R.string.ui_show_calendarview), false);
    }

    public boolean showHidden() {
        return m_prefs.getBoolean(getString(R.string.show_hidden), false);
    }

    public boolean showEmptyLists() {
        return m_prefs.getBoolean(getString(R.string.show_empty_lists), true);
    }

    public boolean showTxtOnly() {
        return m_prefs.getBoolean(getString(R.string.show_txt_only), false);
    }

    public boolean isSyncDues() {
        return API16 && m_prefs.getBoolean(getString(R.string.calendar_sync_dues), false);
    }

    public boolean isSyncThresholds() {
        return API16 && m_prefs.getBoolean(getString(R.string.calendar_sync_thresholds), false);
    }

    public int getReminderDays() {
        return m_prefs.getInt(getString(R.string.calendar_reminder_days), 1);
    }

    public int getReminderTime() {
        return m_prefs.getInt(getString(R.string.calendar_reminder_time), 720);
    }

    public String getTodoFileName() {
        String name =  m_prefs.getString(getString(R.string.todo_file_key), FileStore.getDefaultPath());
        File todoFile = new File(name);
        try {
            return todoFile.getCanonicalPath();
        } catch (IOException e) {
            return FileStore.getDefaultPath();
        }
    }

    public File getTodoFile() {
        return new File(getTodoFileName());
    }

    public void setTodoFile(String todo) {
        m_prefs.edit().putString(getString(R.string.todo_file_key), todo).apply();
    }

    public boolean isAutoArchive() {
        return m_prefs.getBoolean(getString(R.string.auto_archive_pref_key), false);
    }

    public boolean isBackSaving() {
        return m_prefs.getBoolean(getString(R.string.back_key_saves_key), false);
    }

    public boolean hasPrependDate() {
        return m_prefs.getBoolean(getString(R.string.prepend_date_pref_key), true);
    }

    public boolean hasKeepPrio() {
        return m_prefs.getBoolean(getString(R.string.keep_prio), true);
    }

    public boolean hasShareTaskShowsEdit() {
        return m_prefs.getBoolean(getString(R.string.share_task_show_edit), false);
    }

    public boolean hasCapitalizeTasks() {
        return m_prefs.getBoolean(getString(R.string.capitalize_tasks), false);
    }

    public boolean hasColorDueDates() {
        return m_prefs.getBoolean(getString(R.string.color_due_date_key), true);
    }

    public boolean hasRecurOriginalDates() {
        return m_prefs.getBoolean(getString(R.string.recur_from_original_date), true);
    }

    public boolean hasLandscapeDrawers() {
        return (m_prefs.getBoolean(getString(R.string.ui_drawer_fixed_landscape), false) &&
                getResources().getBoolean(R.bool.is_landscape));
    }

    public void setEditTextHint(@NotNull EditText editText, int resid ) {
        if (m_prefs.getBoolean(getString(R.string.ui_show_edittext_hints), true)) {
            editText.setHint(resid);
        }
    }

    public boolean isAddTagsCloneTags() {
        return m_prefs.getBoolean(getString(R.string.clone_tags_key),false);
    }

    public void setAddTagsCloneTags(boolean bool) {
        m_prefs.edit()
                .putBoolean(getString(R.string.clone_tags_key),bool)
                .apply();
    }

    public boolean isWordWrap() {
        return m_prefs.getBoolean(getString(R.string.word_wrap_key),true);
    }

    public boolean useScript() {
        return m_prefs.getBoolean(getString(R.string.use_rhino),false);
    }

    public boolean showTodoPath() {
        return m_prefs.getBoolean(getString(R.string.show_todo_path),false);
    }


    public boolean backClearsFilter() {
        return m_prefs.getBoolean(getString(R.string.back_clears_filter),false);
    }
    public boolean sortCaseSensitive() {
        return m_prefs.getBoolean(getString(R.string.ui_sort_case_sensitive),true);
    }

    @NotNull
    public String getEol() {
        if( m_prefs.getBoolean(getString(R.string.line_breaks_pref_key),true)) {
            return "\r\n";
        } else {
            return "\n";
        }
    }

    public boolean hasDonated() {
        try {
            getPackageManager().getInstallerPackageName("nl.mpcjanssen.simpletask.donate");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }



    public void setWordWrap(boolean bool) {
        m_prefs.edit()
                .putBoolean(getString(R.string.word_wrap_key), bool)
                .apply();
    }

    @NotNull
    public TaskCache getTaskCache(final Activity act) {
        if (this.m_taskCache!=null) {
            return m_taskCache;
        }

        this.m_taskCache = new TaskCache(this);
        final FileStoreInterface store = getFileStore();
        try {
            store.loadTasksFromFile(getTodoFileName(), m_taskCache);
        } catch (IOException e) {
            e.printStackTrace();
            if (act!=null) {
                store.browseForNewFile(act, getTodoFileName(), new FileStoreInterface.FileSelectedListener() {
                    @Override
                    public void fileSelected(String file) {
                        setTodoFile(file);
                        getTaskCache(act);
                    }
                }, showTxtOnly());
            } else {
                m_taskCache = new TaskCache(this);
            }

        }
        return this.m_taskCache;
    }

    public void fileUpdated() {
        localBroadcastManager.sendBroadcast(new Intent(Constants.BROADCAST_FILE_CHANGED));
    }

    public void updateWidgets() {
        AppWidgetManager mgr = AppWidgetManager.getInstance(getApplicationContext());
        for (int appWidgetId : mgr.getAppWidgetIds(new ComponentName(getApplicationContext(), MyAppWidgetProvider.class))) {
            mgr.notifyAppWidgetViewDataChanged(appWidgetId, R.id.widgetlv);
            Log.v(TAG, "Updating widget: " + appWidgetId);
        }
    }

    private void redrawWidgets(){
        AppWidgetManager appWidgetManager = AppWidgetManager.getInstance(getApplicationContext());
        int[] appWidgetIds = appWidgetManager.getAppWidgetIds(new ComponentName(this, MyAppWidgetProvider.class));
        Log.v(TAG, "Redrawing widgets ");
        if (appWidgetIds.length > 0) {
            new MyAppWidgetProvider().onUpdate(this, appWidgetManager, appWidgetIds);
        }
    }

    private int themeStringToId (String theme) {
        switch (theme) {
            case "android.R.style.Theme_Holo":
                return android.R.style.Theme_Holo;
            case "android.R.style.Theme_Holo_Light_DarkActionBar":
                return android.R.style.Theme_Holo_Light_DarkActionBar;
            case "android.R.style.Theme_Holo_Light":
                return android.R.style.Theme_Holo_Light;
        }
        int currentapiVersion = android.os.Build.VERSION.SDK_INT;
        if ( currentapiVersion >= Build.VERSION_CODES.LOLLIPOP) {
            switch (theme) {
                case "android.R.style.Theme_Material":
                    return android.R.style.Theme_Material;
                case "android.R.style.Theme_Material_Light_DarkActionBar":
                    return android.R.style.Theme_Material_Light_DarkActionBar;
                case "android.R.style.Theme_Material_Light":
                    return android.R.style.Theme_Material_Light;
            }

        }
        return android.R.style.Theme_Holo_Light_DarkActionBar;

    }

    public void reloadTheme() {
        m_Theme = -1;
    }
    public int getActiveTheme() {
        if (m_Theme != -1 ) {
            return m_Theme;
        }
        m_Theme = themeStringToId(getPrefs().getString(getString(R.string.theme_pref_key), ""));
        return m_Theme;

    }

    public void setActionBarStyle(@NotNull Window window) {
        if (getPrefs().getBoolean(getString(R.string.split_actionbar_key), true)) {
            window.setUiOptions(ActivityInfo.UIOPTION_SPLIT_ACTION_BAR_WHEN_NARROW);
        }
    }

    public boolean isDarkTheme() {
        switch (getActiveTheme()) {
            case android.R.style.Theme_Holo:
            case android.R.style.Theme_Material:
                return true;
            default:
                return false;
        }
    }

    public boolean isDarkActionbar() {
        switch (getActiveTheme()) {
            case android.R.style.Theme_Holo:
            case android.R.style.Theme_Holo_Light_DarkActionBar:
            case android.R.style.Theme_Material:
            case android.R.style.Theme_Material_Light_DarkActionBar:
                return true;
            default:
                return false;
        }
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, @NotNull String s) {
        Log.v(TAG, "Preference " + s + " changed");
        if (s.equals(getString(R.string.widget_theme_pref_key)) ||
                s.equals(getString(R.string.widget_extended_pref_key)) ||
                s.equals(getString(R.string.widget_background_transparency)) ||
                s.equals(getString(R.string.widget_header_transparency))) {
            redrawWidgets();
        } else if (s.equals(getString(R.string.line_breaks_pref_key))) {
            if (mFileStore!=null) {
                mFileStore.setEol(getEol());
            }
        } else if (s.equals(getString(R.string.calendar_sync_dues))) {
            m_calSync.setSyncDues(isSyncDues());
        } else if (s.equals(getString(R.string.calendar_sync_thresholds))) {
            m_calSync.setSyncThresholds(isSyncThresholds());
        } else if (s.equals(getString(R.string.calendar_reminder_days)) ||
                   s.equals(getString(R.string.calendar_reminder_time))) {
            m_calSync.syncLater();
        }
    }

    private void loadTodoFile(File newTodo) {
        setTodoFile(newTodo.getPath());
        m_taskCache = null;
        getTaskCache(null);
    }

    public void switchTodoFile(File newTodo) {
        todoTrail.add(getTodoFile());
        loadTodoFile(newTodo);

    }

    public boolean switchPreviousTodoFile() {
        int size = todoTrail.size();
        if (todoTrail.size()==0) {
            return false;
        } else {
            File newTodo = todoTrail.get(size - 1);
            todoTrail.remove(size - 1);
            loadTodoFile(newTodo);
            return true;
        }
    }

    public int getActiveFont() {
        String fontsize =  getPrefs().getString("fontsize", "medium");
        switch (fontsize) {
            case "small":
                return R.style.FontSizeSmall;
            case "large":
                return R.style.FontSizeLarge;
            default:
                return R.style.FontSizeMedium;
        }
    }

    @NotNull
    public FileStoreInterface getFileStore() {
        if (mFileStore==null) {
            mFileStore = new FileStore(this, getEol());
        }
        return mFileStore;
    }

    public void showConfirmationDialog(@NotNull Context cxt, int msgid,
                                              @NotNull DialogInterface.OnClickListener oklistener, int titleid) {
        boolean show = getPrefs().getBoolean(getString(R.string.ui_show_confirmation_dialogs), true);

        AlertDialog.Builder builder = new AlertDialog.Builder(cxt);
        builder.setTitle(titleid);
        builder.setMessage(msgid);
        builder.setPositiveButton(android.R.string.ok, oklistener);
        builder.setNegativeButton(android.R.string.cancel, null);
        builder.setCancelable(true);
        Dialog dialog = builder.create();
        if (show) {
           dialog.show();
        } else {
            oklistener.onClick(dialog , DialogInterface.BUTTON_POSITIVE);
        }
    }

    public boolean isAuthenticated() {
        FileStoreInterface fs = getFileStore();
        return fs.isAuthenticated();
    }

    public void startLogin(LoginScreen loginScreen, int i) {
        getFileStore().startLogin(loginScreen,i);
    }

    public int storeType() {
        return getFileStore().getType();
    }

    public void browseForNewFile(@NotNull final Activity act) {
        FileStoreInterface fileStore = getFileStore();
        fileStore.browseForNewFile(
                act,
                new File(getTodoFileName()).getParent(),
                new FileStoreInterface.FileSelectedListener() {
                    @Override
                    public void fileSelected(String file) {
                        switchTodoFile(new File(file));
                    }
                },
		showTxtOnly());
    }

    @NotNull
    public String getDoneFileName() {
        return new File(getTodoFile().getParentFile(), "done.txt").getAbsolutePath();
    }

    public boolean initialSyncDone() {
        return mFileStore != null && mFileStore.initialSyncDone();
    }
}

package nl.mpcjanssen.simpletask.remote;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Environment;
import android.os.FileObserver;
import android.support.v4.content.LocalBroadcastManager;
import android.util.Log;

import com.google.common.base.Charsets;
import com.google.common.io.Files;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.lang.Override;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.google.common.io.LineProcessor;
import nl.mpcjanssen.simpletask.Constants;
import nl.mpcjanssen.simpletask.task.Task;
import nl.mpcjanssen.simpletask.task.TaskCache;
import nl.mpcjanssen.simpletask.util.ListenerList;
import nl.mpcjanssen.simpletask.util.TaskIo;
import nl.mpcjanssen.simpletask.util.Util;

public class FileStore implements FileStoreInterface {

    private final String TAG = getClass().getSimpleName();
    private final Context mCtx;
    private final LocalBroadcastManager bm;
    private String mEol;
    private FileObserver m_observer;

    public FileStore(Context ctx, String eol) {
        mCtx = ctx;
        mEol = eol;
        m_observer = null;
        this.bm = LocalBroadcastManager.getInstance(ctx);
    }

    @Override
    public boolean isAuthenticated() {
        return true;
    }

    @Override
    public void loadTasksFromFile(final String path, final TaskCache taskCache) {
        bm.sendBroadcast(new Intent(Constants.BROADCAST_SYNC_START));
        taskCache.startLoading();
        new AsyncTask<String, Void, Void> () {
            @Override
            protected Void doInBackground(String... params) {
                try {
                    final int i = 0;
                    TaskIo.loadFromFile(new File(path), new LineProcessor<String>() {
                        @Override
                        public boolean processLine(String s) throws IOException {
                            taskCache.load(new Task(-1, s));
                            return true;
                        }

                        @Override
                        public String getResult() {
                            return null;
                        }

                    });
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return null;
            }
            @Override
            protected void onPostExecute(Void v) {
                startWatching(path);
                taskCache.endLoading();
                bm.sendBroadcast(new Intent(Constants.BROADCAST_SYNC_DONE));
                LocalBroadcastManager.getInstance(mCtx).sendBroadcast(new Intent(Constants.BROADCAST_UPDATE_UI));
            }
        }.execute(path);
    }

    private void notifyFileChanged() {
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(new Intent(Constants.BROADCAST_FILE_CHANGED));
        LocalBroadcastManager.getInstance(mCtx).sendBroadcast(new Intent(Constants.BROADCAST_UPDATE_UI));
    }

    @Override
    public void setEol(String eol) {
        mEol = eol;
    }

    @Override
    public void sync() {
        
    }

    @Override
    public String readFile(String file) {
        try {
            return Files.toString(new File(file), Charsets.UTF_8);
        } catch (IOException e) {
            e.printStackTrace();
            return "";
        }
    }

    @Override
    public boolean supportsSync() {
        return false;
    }

    @Override
    public void startLogin(Activity caller, int i) {

    }

    private void startWatching(final String path) {
        Log.v(TAG,"Observer adding on: " + new File(path).getParentFile().getAbsolutePath());
        final String folder = new File(path).getParentFile().getAbsolutePath();
        final String filename = new File(path).getName();
        m_observer = new FileObserver(folder) {
            @Override
            public void onEvent(int event, String eventPath) {
                if (eventPath!=null && eventPath.equals(filename)) {
                    // Log.v(TAG, "Observer event: " + eventPath + ":" + event);
                    if (event == FileObserver.CLOSE_WRITE ||
                            event == FileObserver.MODIFY ||
                            event == FileObserver.MOVED_TO) {
                        bm.sendBroadcast(new Intent(Constants.BROADCAST_SYNC_START));
                        Log.v(TAG, "Observer " + path + " modified....sync done");
                        bm.sendBroadcast(new Intent(Constants.BROADCAST_SYNC_DONE));
                        bm.sendBroadcast(new Intent(Constants.BROADCAST_FILE_CHANGED));
                    }
                }
            }
        };
        m_observer.startWatching();
    }

    private void stopWatching(String path) {
        if (m_observer!=null) {
            Log.v(TAG,"Observer removing on: " + path);
            m_observer.stopWatching();
            m_observer = null;
        }
    }


    @Override
    public void deauthenticate() {

    }

    @Override
    public void browseForNewFile(Activity act, String path,  FileSelectedListener listener, boolean showTxt) {
        FileDialog dialog = new FileDialog(act, path, showTxt);
        dialog.addFileListener(listener);
        dialog.createFileDialog(act,this);
    }

    @Override
    public void saveTasksToFile(final String path, TaskCache taskCache) {
        bm.sendBroadcast(new Intent(Constants.BROADCAST_SYNC_START));
        stopWatching(path);
       final  ArrayList<String> output = Util.tasksToString(taskCache.getTasks());
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    TaskIo.writeToFile(Util.join(output, mEol), new File(path), false);
                } catch (IOException e) {
                    e.printStackTrace();
                    bm.sendBroadcast(new Intent(Constants.BROADCAST_FILE_WRITE_FAILED));
                }
                bm.sendBroadcast(new Intent(Constants.BROADCAST_SYNC_DONE));
                startWatching(path);
                return null;
            }
        }.execute();
    }

    @Override
    public void appendTaskToFile(final String path, final List<Task> tasks) {
        final int size = tasks.size();
        new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                Log.v(TAG, "Appending " + size + " tasks to "+ path);
                try {
                    TaskIo.writeToFile(mEol + Util.joinTasks(tasks, mEol), new File(path), true);
                } catch (IOException e) {
                    e.printStackTrace();
                    bm.sendBroadcast(new Intent(Constants.BROADCAST_FILE_WRITE_FAILED));
                }

                bm.sendBroadcast(new Intent(Constants.BROADCAST_SYNC_DONE));
                return null;
            }
        }.execute();
    }

    @Override
    public int getType() {
        return Constants.STORE_SDCARD;
    }

    public static String getDefaultPath() {
        return Environment.getExternalStorageDirectory() +"/data/nl.mpcjanssen.simpletask/todo.txt";
    }

    public static class FileDialog {
        private static final String PARENT_DIR = "..";
        private String[] fileList;
        private File currentPath;

        private ListenerList<FileSelectedListener> fileListenerList = new ListenerList<FileSelectedListener>();
        private final Activity activity;
        private boolean txtOnly;

        /**
         * @param activity
         * @param pathName
         */
        public FileDialog(Activity activity, String pathName, boolean txtOnly) {
            this.activity = activity;
            this.txtOnly=txtOnly;
            File path = new File(pathName);
            if (!path.exists() || !path.isDirectory()) path = Environment.getExternalStorageDirectory();
            loadFileList(path);
        }

        /**
         * @return file dialog
         */
        public Dialog createFileDialog(Context ctx, FileStoreInterface fs) {
            Dialog dialog;
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);

            builder.setTitle(currentPath.getPath());

            builder.setItems(fileList, new DialogInterface.OnClickListener() {
                public void onClick(DialogInterface dialog, int which) {
                    String fileChosen = fileList[which];
                    File chosenFile = getChosenFile(fileChosen);
                    if (chosenFile.isDirectory()) {
                        loadFileList(chosenFile);
                        dialog.cancel();
                        dialog.dismiss();
                        showDialog();
                    } else fireFileSelectedEvent(chosenFile);
                }
            });

            dialog = builder.show();
            return dialog;
        }


        public void addFileListener(FileSelectedListener listener) {
            fileListenerList.add(listener);
        }

        /**
         * Show file dialog
         */
        public void showDialog() {
            createFileDialog(null,null).show();
        }

        private void fireFileSelectedEvent(final File file) {
            fileListenerList.fireEvent(new ListenerList.FireHandler<FileSelectedListener>() {
                public void fireEvent(FileSelectedListener listener) {
                    listener.fileSelected(file.toString());
                }
            });
        }

        private void loadFileList(File path) {
            this.currentPath = path;
            List<String> r = new ArrayList<String>();
            if (path.exists()) {
                if (path.getParentFile() != null) r.add(PARENT_DIR);
                FilenameFilter filter = new FilenameFilter() {
                    public boolean accept(File dir, String filename) {
                        File sel = new File(dir, filename);
                        if (!sel.canRead()) return false;
                        else {
                            boolean txtFile = filename.toLowerCase(Locale.getDefault()).endsWith(".txt");
                            return !txtOnly ||  sel.isDirectory() || txtFile;
                        }
                    }
                };
                String[] fileList1 = path.list(filter);
                Collections.addAll(r, fileList1);
            }
            Collections.sort(r);
            fileList = r.toArray(new String[r.size()]);
        }

        private File getChosenFile(String fileChosen) {
            if (fileChosen.equals(PARENT_DIR)) return currentPath.getParentFile();
            else return new File(currentPath, fileChosen);
        }
    }

    @Override
    public boolean initialSyncDone() {
        return true;
    }
}

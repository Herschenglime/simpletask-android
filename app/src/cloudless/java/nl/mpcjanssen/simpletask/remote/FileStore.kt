package nl.mpcjanssen.simpletask.remote

import android.Manifest
import android.content.pm.PackageManager
import android.os.Environment
import android.os.FileObserver
import android.os.Handler
import android.os.Looper
import android.support.v4.content.ContextCompat
import nl.mpcjanssen.simpletask.Logger
import nl.mpcjanssen.simpletask.TodoApplication
import nl.mpcjanssen.simpletask.util.join
import nl.mpcjanssen.simpletask.util.writeToFile
import java.io.File
import java.io.FilenameFilter
import java.util.*
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.reflect.KClass

object FileStore : IFileStore {
    override fun getRemoteVersion(filename: String): String {
        return File(filename).lastModified().toString()
    }

    override val isOnline = true
    private val TAG = "FileStore"
    private val log: Logger
    private var observer: TodoObserver? = null


    init {
        log = Logger
        log.info(TAG, "onCreate")
        log.info(TAG, "Default path: ${getDefaultPath()}")
        observer = null

    }

    override val isAuthenticated: Boolean
        get() {
            val permissionCheck = ContextCompat.checkSelfPermission(TodoApplication.app,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE)
            return permissionCheck == PackageManager.PERMISSION_GRANTED
        }

    override fun loadTasksFromFile(path: String): RemoteContents {
        log.info(TAG, "Loading tasks")
        val file = File(path)
        val lines = file.readLines()
        log.info(TAG, "Read ${lines.size} lines from $path")
        setWatching(path)
        return RemoteContents(file.lastModified().toString(), lines)
    }

    override fun writeFile(file: File, contents: String) {
        log.info(TAG, "Writing file to  ${file.canonicalPath}")
        file.writeText(contents)
    }

    override fun readFile(file: String, fileRead: (String) -> Unit) {
        log.info(TAG, "Reading file: {}" + file)
        val contents: String
        val lines = File(file).readLines()
        contents = join(lines, "\n")
        fileRead(contents)
    }

    override fun loginActivity(): KClass<*>? {
        return LoginScreen::class
    }

    private fun setWatching(path: String) {
        Logger.info(TAG, "Observer: adding folder watcher on ${File(path).parentFile.absolutePath}")
        val obs = observer
        if (obs != null && path == obs.path) {
            Logger.warn(TAG, "Observer: already watching: $path")
            return
        } else if (obs != null) {
            Logger.warn(TAG, "Observer: already watching different path: ${obs.path}")
            obs.ignoreEvents(true)
            obs.stopWatching()
        }
        observer = TodoObserver(path)
        Logger.info(TAG, "Observer: modifying done")
    }

    override fun saveTasksToFile(path: String, lines: List<String>, eol: String): String {
        log.info(TAG, "Saving tasks to file: $path")
        val obs = observer
        obs?.ignoreEvents(true)
        val file = File(path)
        writeToFile(lines, eol, file, false)
        obs?.delayedStartListen(1000)
        return file.lastModified().toString()
    }

    override fun appendTaskToFile(path: String, lines: List<String>, eol: String) {
        log.info(TAG, "Appending ${lines.size} tasks to $path")
        writeToFile(lines, eol, File(path), true)
    }

    override fun logout() {

    }

    override fun getDefaultPath(): String {
        return "${Environment.getExternalStorageDirectory()}/data/nl.mpcjanssen.simpletask/todo.txt"
    }

    override fun loadFileList(path: String, txtOnly: Boolean): List<FileEntry> {
        val result = ArrayList<FileEntry>()
        val file = File(path)

        val filter = FilenameFilter { dir, filename ->
            val sel = File(dir, filename)
            if (!sel.canRead())
                false
            else {
                if (sel.isDirectory) {
                    result.add(FileEntry(sel.name, true))
                } else {
                    !txtOnly || filename.toLowerCase(Locale.getDefault()).endsWith(".txt")
                    result.add(FileEntry(sel.name, false))
                }
            }
        }
        // Run the file applyFilter for side effects
        file.list(filter)
        return result
    }

    class TodoObserver(val path: String) : FileObserver(File(path).parentFile.absolutePath) {
        private val TAG = "FileWatchService"
        private val fileName: String
        private var log = Logger
        private var ignoreEvents: Boolean = false
        private val handler: Handler

        private val delayedEnable = Runnable {
            log.info(TAG, "Observer: Delayed enabling events for: " + path)
            ignoreEvents(false)
        }

        init {
            this.startWatching()
            this.fileName = File(path).name
            log.info(TAG, "Observer: creating observer on: {}")
            this.ignoreEvents = false
            this.handler = Handler(Looper.getMainLooper())

        }

        fun ignoreEvents(ignore: Boolean) {
            log.info(TAG, "Observer: observing events on " + this.path + "? ignoreEvents: " + ignore)
            this.ignoreEvents = ignore
        }

        override fun onEvent(event: Int, eventPath: String?) {
            if (eventPath != null && eventPath == fileName) {
                log.debug(TAG, "Observer event: $path:$event")
                if (event == FileObserver.CLOSE_WRITE ||
                        event == FileObserver.MODIFY ||
                        event == FileObserver.MOVED_TO) {
                    if (ignoreEvents) {
                        log.info(TAG, "Observer: ignored event on: " + path)
                    } else {
                        log.info(TAG, "File changed {}" + path)
                        FileStore.remoteTodoFileChanged()
                    }
                }
            }

        }

        fun delayedStartListen(ms: Int) {
            // Cancel any running timers
            handler.removeCallbacks(delayedEnable)
            // Reschedule
            Logger.info(TAG, "Observer: Adding delayed enabling to todoQueue")
            handler.postDelayed(delayedEnable, ms.toLong())
        }

    }
}

package de.mm20.launcher2.plugin.tasks

import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.database.getIntOrNull
import androidx.core.database.getLongOrNull
import androidx.core.database.getStringOrNull
import de.mm20.launcher2.plugin.config.QueryPluginConfig
import de.mm20.launcher2.plugin.config.StorageStrategy
import de.mm20.launcher2.sdk.PluginState
import de.mm20.launcher2.sdk.base.GetParams
import de.mm20.launcher2.sdk.base.SearchParams
import de.mm20.launcher2.sdk.calendar.CalendarEvent
import de.mm20.launcher2.sdk.calendar.CalendarList
import de.mm20.launcher2.sdk.calendar.CalendarProvider
import de.mm20.launcher2.search.calendar.CalendarListType
import de.mm20.launcher2.search.calendar.CalendarQuery
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

const val PERMISSION_READ_TASKS: String = "org.tasks.permission.READ_TASKS"

class TasksSearchProvider : CalendarProvider(
    config = QueryPluginConfig(
        storageStrategy = StorageStrategy.StoreReference
    ),
) {
    override suspend fun search(query: CalendarQuery, params: SearchParams): List<CalendarEvent> {
        if (!checkTasksPermission()) {
            return emptyList()
        }
        return withContext(Dispatchers.IO) {
            queryTasks(
                selection = buildList {
                    if (query.start != null) add("dueDate >= ${query.start}")
                    if (query.end != null) add("dueDate <= ${query.end}")
                    if (query.excludedCalendars.isNotEmpty()) {
                        add("cdl_id NOT IN (${query.excludedCalendars.joinToString()})")
                    }
                    if (query.query != null) {
                        // Does this smell like SQL injection? Yes, it does. But for some reason,
                        // selectionArgs are not working here. But why would anyone try to jeopardize
                        // their own tasks app?
                        add(
                            "title LIKE '%${
                                query.query?.replace("'", "")?.replace("%", "")
                            }%'"
                        )
                    }
                }.joinToString(" AND ").takeIf { it.isNotEmpty() },
            )
        }
    }

    override suspend fun get(id: String, params: GetParams): CalendarEvent? {
        if (!checkTasksPermission()) return null
        val id = id.toLongOrNull() ?: return null
        return withContext(Dispatchers.IO) {
            queryTasks(
                selection = "_id = $id",
            ).firstOrNull()
        }
    }

    private fun checkTasksPermission(): Boolean {
        return context?.checkSelfPermission(PERMISSION_READ_TASKS) == PackageManager.PERMISSION_GRANTED
    }

    private fun queryTasks(
        selection: String? = null,
        selectionArgs: Array<String>? = arrayOf(),
    ): List<CalendarEvent> {
        val uri = Uri.parse("content://org.tasks/todoagenda")
        val cursor = context!!.contentResolver.query(uri, arrayOf(), selection, selectionArgs, null)

        val results = mutableListOf<CalendarEvent>()

        cursor?.use {
            val idIndex = cursor.getColumnIndex("_id")
            val titleIndex = cursor.getColumnIndex("title")
            val dueIndex = cursor.getColumnIndex("dueDate")
            val completedIndex = cursor.getColumnIndex("completed")
            val notesIndex = cursor.getColumnIndex("notes")
            val colorIndex = cursor.getColumnIndex("cdl_color")
            val calendarNameIndex = cursor.getColumnIndex("cdl_name")

            while (cursor.moveToNext()) {

                val id = cursor.getLongOrNull(idIndex)?.toString() ?: continue
                val dueDate = cursor.getLongOrNull(dueIndex)?.takeIf { it > 0L } ?: continue

                results += CalendarEvent(
                    id = id,
                    title = cursor.getStringOrNull(titleIndex) ?: continue,
                    description = cursor.getStringOrNull(notesIndex),
                    color = cursor.getIntOrNull(colorIndex),
                    calendarName = cursor.getStringOrNull(calendarNameIndex),
                    uri = Uri.parse("content://org.tasks/tasks/$id"),
                    startTime = null,
                    endTime = dueDate,
                    includeTime = dueDate % 60000 > 0, // https://github.com/tasks/tasks/blob/13d4c029e855fd32ec91e4d4ec5f740ec506136e/data/src/commonMain/kotlin/org/tasks/data/entity/Task.kt#L345
                    isCompleted = (cursor.getLongOrNull(completedIndex) ?: 0L) != 0L
                )
            }
        }

        return results
    }

    override suspend fun getCalendarLists(): List<CalendarList> {
        if (!checkTasksPermission()) return emptyList()
        return withContext(Dispatchers.IO) {
            val uri = Uri.parse("content://org.tasks/lists")
            val cursor = context!!.contentResolver.query(uri, arrayOf(), null, arrayOf(), null)
                ?: return@withContext emptyList()
            val results = mutableListOf<CalendarList>()

            val idIndex = cursor.getColumnIndex("cdl_id")
            val nameIndex = cursor.getColumnIndex("cdl_name")
            val colorIndex = cursor.getColumnIndex("cdl_color")

            cursor.use {
                while (cursor.moveToNext()) {
                    results += CalendarList(
                        id = cursor.getLongOrNull(idIndex)?.toString() ?: continue,
                        name = cursor.getStringOrNull(nameIndex) ?: continue,
                        color = cursor.getIntOrNull(colorIndex) ?: 0,
                        contentTypes = listOf(CalendarListType.Tasks),
                    )
                }
            }
            results
        }
    }

    private suspend fun isTasksAppInstalled() : Boolean {
        return withContext(Dispatchers.IO) {
            try {
                context!!.packageManager.getPackageInfo("org.tasks", 0)
            } catch (e: PackageManager.NameNotFoundException) {
                return@withContext false
            }
            return@withContext true
        }
    }

    override suspend fun getPluginState(): PluginState {
        if (!isTasksAppInstalled()) {
            return PluginState.SetupRequired(
                message = context!!.getString(R.string.plugin_state_tasks_app_not_installed),
                setupActivity = Intent(
                    Intent.ACTION_VIEW,
                    Uri.parse("https://tasks.org/")
                )
            )
        }
        if (checkTasksPermission()) {
            return PluginState.Ready()
        }
        return PluginState.SetupRequired(
            message = context!!.getString(R.string.plugin_state_no_permission),
            setupActivity = Intent(context!!, RequestPermissionActivity::class.java)
        )
    }


}
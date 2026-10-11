
package com.openjarvis.automation

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class AutomationManager(private val context: Context) {

    private val dao = AutomationDB
        .getInstance(context)
        .automationDao()

    private val _automationsFlow =
        MutableStateFlow<List<Automation>>(emptyList())

    val automationsFlow: StateFlow<List<Automation>> =
        _automationsFlow

    suspend fun loadAutomations() {
        _automationsFlow.value =
            dao.getAll().map { it.toAutomation() }
    }

    suspend fun createAutomation(
        automation: Automation
    ): String = withContext(Dispatchers.IO) {

        dao.insert(automation.toEntity())

        if (automation.enabled) {
            scheduleAutomation(automation)
        }

        refreshAutomations()
        automation.id
    }

    suspend fun updateAutomation(
        automation: Automation
    ) = withContext(Dispatchers.IO) {

        cancelAutomation(automation.id)
        dao.update(automation.toEntity())

        if (automation.enabled) {
            scheduleAutomation(automation)
        }

        refreshAutomations()
    }

    suspend fun deleteAutomation(
        id: String
    ) = withContext(Dispatchers.IO) {

        cancelAutomation(id)
        dao.delete(id)
        refreshAutomations()
    }

    suspend fun toggleAutomation(
        id: String,
        enabled: Boolean
    ) = withContext(Dispatchers.IO) {

        val existing = dao.getById(id)
            ?: return@withContext

        val updated = existing.toAutomation()
            .copy(enabled = enabled)

        dao.update(updated.toEntity())

        cancelAutomation(id)

        if (enabled) {
            scheduleAutomation(updated)
        }

        refreshAutomations()
    }

    suspend fun runNow(
        id: String
    ) = withContext(Dispatchers.IO) {

        val entity = dao.getById(id)
            ?: return@withContext

        executeAutomation(entity.toAutomation())
        refreshAutomations()
    }

    private suspend fun refreshAutomations() {
        _automationsFlow.value =
            dao.getAll().map { it.toAutomation() }
    }

    private suspend fun scheduleAutomation(
        automation: Automation
    ) {
        if (!automation.enabled) return

        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(false)
            .build()

        val inputData = workDataOf(
            "automation_id" to automation.id,
            "automation_command" to automation.command
        )

        val workManager = WorkManager.getInstance(context)

        when (val schedule = automation.schedule) {

            is AutomationSchedule.Daily -> {
                val request =
                    PeriodicWorkRequestBuilder<AutomationWorker>(
                        24, TimeUnit.HOURS
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(
                            calculateDelay(
                                schedule.hour,
                                schedule.minute
                            ),
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    automation.id,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            }

            is AutomationSchedule.Weekly -> {
                val request =
                    PeriodicWorkRequestBuilder<AutomationWorker>(
                        7, TimeUnit.DAYS
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(
                            calculateWeeklyDelay(
                                schedule.dayOfWeek,
                                schedule.hour,
                                schedule.minute
                            ),
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    automation.id,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            }

            is AutomationSchedule.Interval -> {
                val minimumInterval =
                    maxOf(
                        schedule.intervalMs,
                        15 * 60 * 1000L
                    )

                val request =
                    PeriodicWorkRequestBuilder<AutomationWorker>(
                        minimumInterval,
                        TimeUnit.MILLISECONDS
                    )
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniquePeriodicWork(
                    automation.id,
                    ExistingPeriodicWorkPolicy.REPLACE,
                    request
                )
            }

            is AutomationSchedule.Once -> {
                val delay =
                    schedule.atMs - System.currentTimeMillis()

                if (delay <= 0) return

                val request =
                    OneTimeWorkRequestBuilder<AutomationWorker>()
                        .setConstraints(constraints)
                        .setInputData(inputData)
                        .setInitialDelay(
                            delay,
                            TimeUnit.MILLISECONDS
                        )
                        .addTag(automation.id)
                        .build()

                workManager.enqueueUniqueWork(
                    automation.id,
                    ExistingWorkPolicy.REPLACE,
                    request
                )
            }
        }
    }

    private fun cancelAutomation(id: String) {
        WorkManager.getInstance(context)
            .cancelAllWorkByTag(id)
    }

    private suspend fun executeAutomation(
        automation: Automation
    ) {
        val updated = automation.copy(
            lastRun = System.currentTimeMillis(),
            lastResult = "success",
            runCount = automation.runCount + 1
        )

        dao.update(updated.toEntity())
    }

    private fun calculateDelay(
        targetHour: Int,
        targetMinute: Int
    ): Long {
        val calendar = Calendar.getInstance()

        calendar.set(Calendar.HOUR_OF_DAY, targetHour)
        calendar.set(Calendar.MINUTE, targetMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.DAY_OF_YEAR, 1)
        }

        return calendar.timeInMillis - System.currentTimeMillis()
    }

    private fun calculateWeeklyDelay(
        dayOfWeek: Int,
        targetHour: Int,
        targetMinute: Int
    ): Long {
        val calendar = Calendar.getInstance()

        calendar.set(Calendar.DAY_OF_WEEK, dayOfWeek)
        calendar.set(Calendar.HOUR_OF_DAY, targetHour)
        calendar.set(Calendar.MINUTE, targetMinute)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)

        if (calendar.timeInMillis <= System.currentTimeMillis()) {
            calendar.add(Calendar.WEEK_OF_YEAR, 1)
        }

        return calendar.timeInMillis - System.currentTimeMillis()
    }

    fun parseSchedule(input: String): AutomationSchedule? {
        val lower = input.lowercase()

        val dailyMatch = Regex(
            """every day at (\d{1,2})(?::(\d{2}))?\s*(am|pm)?""",
            RegexOption.IGNORE_CASE
        ).find(lower)

        if (dailyMatch != null) {
            var hour = dailyMatch.groupValues[1].toInt()
            val minute =
                dailyMatch.groupValues[2].toIntOrNull() ?: 0

            val suffix =
                dailyMatch.groupValues[3].lowercase()

            if (hour !in 1..12 && suffix.isNotEmpty()) {
                return null
            }

            if (minute !in 0..59) return null

            if (suffix == "pm" && hour != 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0

            if (hour !in 0..23) return null

            return AutomationSchedule.Daily(hour, minute)
        }

        val intervalMatch = Regex(
            """every (\d+)\s*(minute|hour|day)s?""",
            RegexOption.IGNORE_CASE
        ).find(lower)

        if (intervalMatch != null) {
            val value =
                intervalMatch.groupValues[1].toLongOrNull()
                    ?: return null

            if (value <= 0) return null

            val unit = intervalMatch.groupValues[2]

            val milliseconds = when (unit) {
                "minute" -> value * 60_000L
                "hour" -> value * 3_600_000L
                "day" -> value * 86_400_000L
                else -> return null
            }

            return AutomationSchedule.Interval(milliseconds)
        }

        return null
    }

    private fun Automation.toEntity(): AutomationEntity {
        val type: String
        var hour = 0
        var minute = 0
        var dayOfWeek = 0
        var intervalMs = 0L

        when (val currentSchedule = schedule) {
            is AutomationSchedule.Daily -> {
                type = "daily"
                hour = currentSchedule.hour
                minute = currentSchedule.minute
            }

            is AutomationSchedule.Weekly -> {
                type = "weekly"
                hour = currentSchedule.hour
                minute = currentSchedule.minute
                dayOfWeek = currentSchedule.dayOfWeek
            }

            is AutomationSchedule.Interval -> {
                type = "interval"
                intervalMs = currentSchedule.intervalMs
            }

            is AutomationSchedule.Once -> {
                type = "once"
                // Store the scheduled timestamp in this column
                // for one-time schedules.
                intervalMs = currentSchedule.atMs
            }
        }

        return AutomationEntity(
            id = id,
            name = name,
            command = command,
            scheduleType = type,
            scheduleHour = hour,
            scheduleMinute = minute,
            scheduleDayOfWeek = dayOfWeek,
            scheduleIntervalMs = intervalMs,
            enabled = enabled,
            lastRun = lastRun,
            lastResult = lastResult,
            runCount = runCount
        )
    }

    private fun AutomationEntity.toAutomation(): Automation {
        val convertedSchedule = when (scheduleType.lowercase()) {
            "daily" -> AutomationSchedule.Daily(
                scheduleHour,
                scheduleMinute
            )

            "weekly" -> AutomationSchedule.Weekly(
                scheduleDayOfWeek,
                scheduleHour,
                scheduleMinute
            )

            "interval" -> AutomationSchedule.Interval(
                scheduleIntervalMs
            )

            "once" -> AutomationSchedule.Once(
                scheduleIntervalMs
            )

            else -> AutomationSchedule.Daily(
                scheduleHour,
                scheduleMinute
            )
        }

        return Automation(
            id = id,
            name = name,
            command = command,
            schedule = convertedSchedule,
            enabled = enabled,
            lastRun = lastRun,
            lastResult = lastResult,
            runCount = runCount
        )
    }

    data class Automation(
        val id: String,
        val name: String,
        val command: String,
        val schedule: AutomationSchedule,
        val enabled: Boolean = true,
        val lastRun: Long? = null,
        val lastResult: String? = null,
        val runCount: Int = 0
    )

    sealed class AutomationSchedule {
        data class Daily(
            val hour: Int,
            val minute: Int
        ) : AutomationSchedule()

        data class Weekly(
            val dayOfWeek: Int,
            val hour: Int,
            val minute: Int
        ) : AutomationSchedule()

        data class Interval(
            val intervalMs: Long
        ) : AutomationSchedule()

        data class Once(
            val atMs: Long
        ) : AutomationSchedule()
    }
}

package de.mm20.launcher2.plugin.tasks

import android.app.Activity
import android.os.Bundle

class RequestPermissionActivity: Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestPermissions(
            arrayOf(PERMISSION_READ_TASKS),
            0
        )
        finish()
    }
}
package com.cosmos.screensaver.settings

import android.content.Context
import android.provider.Settings

/** [SystemIdleSetting] backed by the system's `screen_off_timeout`, which needs the Write settings permission to change. */
internal class AndroidIdleSetting(private val context: Context) : SystemIdleSetting {
    override fun canWrite(): Boolean = Settings.System.canWrite(context)

    override fun read(): Int? =
        Settings.System.getString(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)?.toIntOrNull()

    override fun write(millis: Int): Boolean =
        Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, millis)
}

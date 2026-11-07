package dev.aragonite.powersync.client

import android.content.Context
import java.io.IOException

/** Localized owner-facing errors; exception messages never contain credentials. */
class UserError(@JvmField val messageResource: Int, vararg arguments: Any?) :
    IOException("powersync-error-$messageResource") {
    @JvmField val args = arguments

    fun text(context: Context): String = context.getString(messageResource, *args)
}

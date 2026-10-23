package top.niunaijun.blackdex.data

import top.niunaijun.blackbox.BlackDexCore
import top.niunaijun.blackbox.entity.pm.InstallResult

/** Starts a container run. Only the engine monitor may publish a terminal result.
 * There is deliberately no filesystem polling or old-directory success fallback. */
class DexDumpRepository {
    fun start(packageName: String): InstallResult? = BlackDexCore.get().dumpDex(packageName)
}

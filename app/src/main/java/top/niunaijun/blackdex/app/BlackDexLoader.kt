package top.niunaijun.blackdex.app

import android.content.Context
import android.os.Build
import top.niunaijun.blackbox.BlackDexCore
import top.niunaijun.blackbox.app.configuration.ClientConfiguration
import top.niunaijun.blackbox.utils.FileUtils
import top.niunaijun.blackdex.biz.cache.AppSharedPreferenceDelegate
import java.io.File

/**
 *
 * @Description:
 * @Author: wukaicheng
 * @CreateDate: 2021/5/6 23:38
 */
class BlackDexLoader {


    private var mSavePath by AppSharedPreferenceDelegate(App.getContext(), "")

    private var mSaveEnable by AppSharedPreferenceDelegate(App.getContext(), true)

    private var mFixCodeItem by AppSharedPreferenceDelegate(App.getContext(),false)

    private var mHookDump by AppSharedPreferenceDelegate(App.getContext(),true)
    // Separate opt-in: never inherit the legacy hook's default-enabled state.
    private var mHakuApi33HookDump by AppSharedPreferenceDelegate(App.getContext(),false)

    private val mDir = getDexDumpDir(App.getContext())

    fun addLifecycleCallback() {

    }

    fun attachBaseContext(context: Context) {
        BlackDexCore.get().doAttachBaseContext(context, object : ClientConfiguration() {
            override fun getHostPackageName(): String {
                return context.packageName
            }

            override fun getDexDumpDir(): String {
                return mDir
            }

            override fun isFixCodeItem(): Boolean {
                return mFixCodeItem
            }

            override fun isEnableHookDump(): Boolean {
                return isHookDump()
            }
        })
    }

    fun doOnCreate(context: Context) {
        BlackDexCore.get().doCreate()
    }

    fun saveEnable(): Boolean {
        return mSaveEnable
    }

    fun saveEnable(state: Boolean) {
        this.mSaveEnable = state
    }

    fun getSavePath(): String {
        return mSavePath
    }

    fun setSavePath(path: String) {
        this.mSavePath = path
    }

    fun setFixCodeItem(enable:Boolean){
        this.mFixCodeItem = enable
    }

    fun isFixCodeItem():Boolean{
        return this.mFixCodeItem
    }

    fun setHookDump(enable: Boolean){
        if (Build.VERSION.SDK_INT >= 33) mHakuApi33HookDump = enable
        else mHookDump = enable
    }

    fun isHookDump(): Boolean {

        return if (Build.VERSION.SDK_INT >= 33) mHakuApi33HookDump else mHookDump
    }


    companion object {

        val TAG: String = BlackDexLoader::class.java.simpleName

        fun getDexDumpDir(context: Context): String {
            val dump = File(context.filesDir, "haku-dex")
            FileUtils.mkdirs(dump)
            return dump.absolutePath
        }
    }
}

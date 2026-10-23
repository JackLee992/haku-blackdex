package top.niunaijun.blackbox;

import android.app.ActivityManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Process;
import android.os.Build;
import top.niunaijun.blackbox.core.HakuCaptureSession;
import top.niunaijun.blackbox.entity.dump.DumpResult;

import java.io.File;
import java.util.List;

import top.niunaijun.blackbox.app.configuration.ClientConfiguration;
import top.niunaijun.blackbox.core.system.dump.IBDumpMonitor;
import top.niunaijun.blackbox.entity.pm.InstallResult;
import top.niunaijun.blackbox.proxy.ProxyManifest;

/**
 * Created by Milk on 2021/5/22.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BlackDexCore {
    public static final String TAG = "BlackBoxCore";

    private static final BlackDexCore sBlackDexCore = new BlackDexCore();

    public static BlackDexCore get() {
        return sBlackDexCore;
    }

    public void doAttachBaseContext(Context context, ClientConfiguration clientConfiguration) {
        BlackBoxCore.get().doAttachBaseContext(context, clientConfiguration);
    }

    public void doCreate() {
        BlackBoxCore.get().doCreate();
        // uninstall all pckage
        if (Build.VERSION.SDK_INT < 33 && BlackBoxCore.get().isMainProcess()) {
            List<PackageInfo> installedPackages =
                    BlackBoxCore.getBPackageManager().getInstalledPackages(0, BlackBoxCore.USER_ID);
            for (PackageInfo installedPackage : installedPackages) {
                BlackBoxCore.get().uninstallPackage(installedPackage.packageName);
            }
        }
    }

    public InstallResult dumpDex(String packageName) {
        InstallResult installResult = BlackBoxCore.get().installPackage(packageName);
        if (installResult.success) {
            boolean b = launchCapture(installResult.packageName);
            if (!b) {
                BlackBoxCore.get().uninstallPackage(installResult.packageName);
                return null;
            }
            return installResult;
        } else {
            return null;
        }
    }

    public InstallResult dumpDex(File file) {
        InstallResult installResult = BlackBoxCore.get().installPackage(file);
        if (installResult.success) {
            boolean b = launchCapture(installResult.packageName);
            if (!b) {
                BlackBoxCore.get().uninstallPackage(installResult.packageName);
                return null;
            }
            return installResult;
        } else {
            return null;
        }
    }

    public InstallResult dumpDex(Uri file) {
        InstallResult installResult = BlackBoxCore.get().installPackage(file);
        if (installResult.success) {
            boolean b = launchCapture(installResult.packageName);
            if (!b) {
                BlackBoxCore.get().uninstallPackage(installResult.packageName);
                return null;
            }
            return installResult;
        } else {
            return null;
        }
    }

    private boolean launchCapture(String packageName) {
        File directory = null;
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (Build.VERSION.SDK_INT != 33)
                    throw new UnsupportedOperationException("Container capture is qualified for API33 only");
                directory = HakuCaptureSession.begin(packageName);
                HakuCaptureSession.notice(packageName, directory, "Launching target application");
            }
            if (!BlackBoxCore.get().launchApk(packageName))
                throw new IllegalStateException("Target has no launchable activity");
            return true;
        } catch (Throwable error) {
            DumpResult result = new DumpResult();
            result.packageName = packageName;
            if (directory != null) {
                result.dir = directory.getAbsolutePath();
                try { HakuCaptureSession.finish(directory, error.toString()); } catch (Exception ignored) { }
            }
            BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpError(error.toString()));
            return false;
        }
    }

    /** Current attempt, for timeout/report recovery; directory existence is not success. */
    public File getCaptureDirectory(String packageName) throws java.io.IOException {
        return HakuCaptureSession.current(packageName);
    }

    /** Cancel only the caller's exact running attempt; never a replacement session. */
    public boolean cancelCapture(String packageName, String sessionId) throws Exception {
        File directory;
        try { directory = HakuCaptureSession.current(packageName); }
        catch (java.io.IOException missing) { return false; }
        if (!directory.getName().equals(sessionId)) return false;
        String message = "Capture cancelled by user";
        try { HakuCaptureSession.finish(directory, message); }
        catch (java.io.IOException ended) {
            if (!"running".equals(HakuCaptureSession.read(directory).optString("status"))) return false;
            throw ended;
        }
        DumpResult result = new DumpResult();
        result.packageName = packageName;
        result.dir = directory.getAbsolutePath();
        BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpError(message));
        BlackBoxCore.getBActivityManager().stopCaptureProcesses(packageName, BlackBoxCore.USER_ID, sessionId);
        return true;
    }

    public void registerDumpMonitor(IBDumpMonitor monitor) {
        BlackBoxCore.getBDumpManager().registerMonitor(monitor.asBinder());
    }

    public void unregisterDumpMonitor(IBDumpMonitor monitor) {
        BlackBoxCore.getBDumpManager().unregisterMonitor(monitor.asBinder());
    }

    public boolean isRunning() {
        ActivityManager am = (ActivityManager) BlackBoxCore.getContext().getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo info : am.getRunningAppProcesses()) {
            for (int i = 0; i < ProxyManifest.FREE_COUNT; i++) {
                if (info.processName.endsWith("p" + i)) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isExistDexFile(String packageName) {
        File[] files = new File(BlackBoxCore.get().getDexDumpDir(), packageName).listFiles();
        return files != null && files.length > 0;
    }
}

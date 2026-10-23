package top.niunaijun.blackbox.fake.frameworks;

import android.content.Intent;
import android.content.ComponentName;
import android.os.Build;
import top.niunaijun.blackbox.entity.AppConfig;
import android.os.RemoteException;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.core.system.ServiceManager;
import top.niunaijun.blackbox.core.system.am.IBActivityManagerService;

/**
 * Created by Milk on 4/14/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BActivityManager {
    private static final BActivityManager sActivityManager = new BActivityManager();
    private IBActivityManagerService mService;

    public static BActivityManager get() {
        return sActivityManager;
    }

    public void startActivity(Intent intent, int userId) {
        try {
            getService().startActivity(intent, userId);
        } catch (Exception e) {
            if (Build.VERSION.SDK_INT >= 33) throw new IllegalStateException("Container activity launch failed", e);
            e.printStackTrace();
        }
    }

    public AppConfig initProcess(String packageName, String processName, int userId) throws RemoteException {
        return getService().initProcess(packageName, processName, userId);
    }

    public void stopCaptureProcesses(String packageName, int userId, String sessionId) throws RemoteException {
        getService().stopCaptureProcesses(packageName, userId, sessionId);
    }

    public ComponentName startService(Intent intent, String resolvedType, int userId) throws RemoteException {
        return getService().startService(intent, resolvedType, userId);
    }

    public int stopService(Intent intent, String resolvedType, int userId) throws RemoteException {
        return getService().stopService(intent, resolvedType, userId);
    }

    public void stopPackageProcesses(String packageName, int userId) throws RemoteException {
        getService().stopPackageProcesses(packageName, userId);
    }

    private IBActivityManagerService getService() {
        if (mService != null && mService.asBinder().isBinderAlive()) {
            return mService;
        }
        mService = IBActivityManagerService.Stub.asInterface(BlackBoxCore.get().getService(ServiceManager.ACTIVITY_MANAGER));
        return getService();
    }
}

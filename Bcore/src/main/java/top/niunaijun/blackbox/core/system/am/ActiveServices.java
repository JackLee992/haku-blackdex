package top.niunaijun.blackbox.core.system.am;

import android.content.Intent;
import android.content.ComponentName;
import android.os.Binder;
import android.os.RemoteException;
import android.os.Build;
import top.niunaijun.blackbox.core.system.BProcessManager;
import top.niunaijun.blackbox.core.system.ProcessRecord;
import top.niunaijun.blackbox.core.HakuCaptureSession;
import android.content.pm.ResolveInfo;
import android.os.IBinder;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import top.niunaijun.blackbox.core.system.pm.BPackageManagerService;

/**
 * Created by Milk on 4/7/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ActiveServices {
    public static final String TAG = "ActiveServices";

    private final Map<Intent.FilterComparison, RunningServiceRecord> mRunningServiceRecords = new HashMap<>();
    private final Map<IBinder, ConnectedServiceRecord> mConnectedServices = new HashMap<>();

    public ComponentName startService(Intent intent, String resolvedType, int userId) {
        if (Build.VERSION.SDK_INT != 33) return null;
        ResolveInfo resolved = resolveService(intent, resolvedType, userId);
        if (resolved == null || resolved.serviceInfo == null) return null;
        android.content.pm.ServiceInfo info = resolved.serviceInfo;
        ProcessRecord process = BProcessManager.get().startProcessLocked(info.packageName,
                info.processName, userId, -1, Binder.getCallingUid(), Binder.getCallingPid());
        if (process == null) throw new IllegalStateException("Cannot start virtual service process");
        ComponentName component = new ComponentName(info.packageName, info.name);
        Intent target = new Intent(intent).setComponent(component);
        synchronized (mRunningServiceRecords) {
            RunningServiceRecord record = getOrCreateRunningServiceRecord(target);
            record.process = process;
            record.component = component;
        }
        try {
            HakuCaptureSession.service(process.captureDirectory, info, "requested", process.pid, null);
            process.bActivityThread.startVirtualService(target, info);
        } catch (Exception e) {
            try { HakuCaptureSession.service(process.captureDirectory, info, "failed", process.pid, e.toString()); }
            catch (Exception ignored) { }
            throw new IllegalStateException("Cannot dispatch virtual service", e);
        }
        return component;
    }

    public int stopService(Intent intent, String resolvedType, int userId) {
        if (Build.VERSION.SDK_INT == 33) {
            ResolveInfo resolved = resolveService(intent, resolvedType, userId);
            if (resolved == null || resolved.serviceInfo == null) return 0;
            ComponentName component = new ComponentName(resolved.serviceInfo.packageName, resolved.serviceInfo.name);
            RunningServiceRecord record;
            synchronized (mRunningServiceRecords) {
                record = mRunningServiceRecords.remove(new Intent.FilterComparison(new Intent(intent).setComponent(component)));
            }
            if (record == null || record.process == null) return 0;
            try { record.process.bActivityThread.stopVirtualService(component); }
            catch (RemoteException e) { throw new IllegalStateException("Cannot stop virtual service", e); }
            return 1;
        }
        synchronized (mRunningServiceRecords) {
            RunningServiceRecord runningServiceRecord = findRunningServiceRecord(intent);
            if (runningServiceRecord == null) {
                return 0;
            }
            if (runningServiceRecord.mBindCount.get() > 0) {
                Log.d(TAG, "There are also connections");
                return 0;
            }

            runningServiceRecord.mStartId.set(0);
        }
        return 0;
    }

    public void unbindService(IBinder binder, int userId) {
        ConnectedServiceRecord connectedService = mConnectedServices.get(binder);
        if (connectedService == null) {
            return;
        }
        RunningServiceRecord runningServiceRecord = getOrCreateRunningServiceRecord(connectedService.mIntent);
        runningServiceRecord.mConnectedServiceRecord = null;
        runningServiceRecord.mBindCount.decrementAndGet();
        mConnectedServices.remove(binder);
    }

    public void onStartCommand(Intent proxyIntent, int userId) {
    }

    public void onServiceDestroy(Intent proxyIntent, int userId) {
    }

    private RunningServiceRecord getOrCreateRunningServiceRecord(Intent intent) {
        RunningServiceRecord runningServiceRecord = findRunningServiceRecord(intent);
        if (runningServiceRecord == null) {
            runningServiceRecord = new RunningServiceRecord();
            mRunningServiceRecords.put(new Intent.FilterComparison(intent), runningServiceRecord);
        }
        return runningServiceRecord;
    }

    private RunningServiceRecord findRunningServiceRecord(Intent intent) {
        return mRunningServiceRecords.get(new Intent.FilterComparison(intent));
    }

    private ResolveInfo resolveService(Intent intent, String resolvedType, int userId) {
        return BPackageManagerService.get().resolveService(intent, 0, resolvedType, userId);
    }

    private ConnectedServiceRecord findConnectedServiceRecord(Intent intent) {
        RunningServiceRecord runningServiceRecord = mRunningServiceRecords.get(intent);
        if (runningServiceRecord == null)
            return null;
        return runningServiceRecord.mConnectedServiceRecord;
    }

    public static class RunningServiceRecord {
        private ProcessRecord process;
        private ComponentName component;
        // onStartCommand startId
        private AtomicInteger mStartId = new AtomicInteger(1);
        private AtomicInteger mBindCount = new AtomicInteger(0);
        // 正在连接的服务
        private ConnectedServiceRecord mConnectedServiceRecord;

        public int getAndIncrementStartId() {
            return mStartId.getAndIncrement();
        }

        public int decrementBindCountAndGet() {
            return mBindCount.decrementAndGet();
        }

        public int incrementBindCountAndGet() {
            return mBindCount.incrementAndGet();
        }
    }

    public static class ConnectedServiceRecord {
        private IBinder mIBinder;
        private Intent mIntent;
    }
}

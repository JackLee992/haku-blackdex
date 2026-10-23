package top.niunaijun.blackbox.app;

import android.app.Application;
import android.app.Activity;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import java.util.Map;
import java.util.HashMap;
import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.ConditionVariable;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.Log;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import top.niunaijun.blackbox.core.HakuCaptureSession;

import reflection.android.app.ActivityThread;
import reflection.android.app.ContextImpl;
import reflection.android.app.LoadedApk;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.core.VMCore;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.core.IOCore;
import top.niunaijun.blackbox.entity.dump.DumpResult;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.Slog;
import top.niunaijun.blackbox.BlackBoxCore;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class BActivityThread extends IBActivityThread.Stub {
    public static final String TAG = "BActivityThread";

    private static BActivityThread sBActivityThread;
    private AppBindData mBoundApplication;
    private Application mInitialApplication;
    private File mCaptureDirectory;
    private volatile String mCaptureStage = "binding-application";
    private DumpResult mCaptureResult;
    private final Map<ComponentName, StartedService> mStartedServices = new HashMap<>();
    private final AtomicBoolean mCaptureStarted = new AtomicBoolean();
    private final AtomicBoolean mCaptureFinished = new AtomicBoolean();
    private AppConfig mAppConfig;
    private String mProcessCaptureSessionId;
    private final List<ProviderInfo> mProviders = new ArrayList<>();

    public static BActivityThread currentActivityThread() {
        if (sBActivityThread == null) {
            synchronized (BActivityThread.class) {
                if (sBActivityThread == null) {
                    sBActivityThread = new BActivityThread();
                }
            }
        }
        return sBActivityThread;
    }

    public static synchronized AppConfig getAppConfig() {
        return currentActivityThread().mAppConfig;
    }

    public static List<ProviderInfo> getProviders() {
        return currentActivityThread().mProviders;
    }

    public static String getAppProcessName() {
        if (getAppConfig() != null) {
            return getAppConfig().processName;
        } else if (currentActivityThread().mBoundApplication != null) {
            return currentActivityThread().mBoundApplication.processName;
        } else {
            return null;
        }
    }

    public static String getAppPackageName() {
        if (getAppConfig() != null) {
            return getAppConfig().packageName;
        } else if (currentActivityThread().mInitialApplication != null) {
            return currentActivityThread().mInitialApplication.getPackageName();
        } else {
            return null;
        }
    }

    public static Application getApplication() {
        return currentActivityThread().mInitialApplication;
    }

    public static int getAppPid() {
        return getAppConfig() == null ? -1 : getAppConfig().bpid;
    }

    public static int getAppUid() {
        return getAppConfig() == null ? 10000 : getAppConfig().buid;
    }

    public static int getBaseAppUid() {
        return getAppConfig() == null ? 10000 : getAppConfig().baseBUid;
    }

    public static int getUid() {
        return getAppConfig() == null ? -1 : getAppConfig().uid;
    }

    public static int getUserId() {
        return getAppConfig() == null ? 0 : getAppConfig().userId;
    }

    public synchronized void initProcess(AppConfig appConfig) {
        if (this.mAppConfig != null) {
            throw new RuntimeException("reject init process: " + appConfig.processName + ", this process is : " + this.mAppConfig.processName);
        }
        this.mAppConfig = appConfig;
        this.mProcessCaptureSessionId = appConfig.captureSessionId;
    }

    public boolean isInit() {
        return mBoundApplication != null;
    }

    public void bindApplication(final String packageName, final String processName) {
        if (mAppConfig == null) {
            return;
        }
        if (Looper.myLooper() != Looper.getMainLooper()) {
            final ConditionVariable conditionVariable = new ConditionVariable();
            new Handler(Looper.getMainLooper()).post(() -> {
                try { handleBindApplication(packageName, processName); }
                finally { conditionVariable.open(); }
            });
            conditionVariable.block();
        } else {
            handleBindApplication(packageName, processName);
        }
    }

    private synchronized void handleBindApplication(String packageName, String processName) {
        DumpResult result = new DumpResult();
        result.packageName = packageName;
        result.dir = new File(BlackBoxCore.get().getDexDumpDir(), packageName).getAbsolutePath();
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                if (mAppConfig == null || !Objects.equals(packageName, mAppConfig.packageName)
                        || !Objects.equals(processName, mAppConfig.processName))
                    throw new IllegalStateException("Application bind does not match process configuration");
                mCaptureDirectory = HakuCaptureSession.boundSession(packageName, mProcessCaptureSessionId);
                result.dir = mCaptureDirectory.getAbsolutePath();
                if (Objects.equals(packageName, processName)) {
                    mCaptureResult = result;
                    HakuCaptureSession.phase(mCaptureDirectory, "binding-application", null);
                    HakuCaptureSession.notice(packageName, mCaptureDirectory, "Binding target application");
                }
            }
            PackageInfo packageInfo = BlackBoxCore.getBPackageManager().getPackageInfo(packageName, PackageManager.GET_PROVIDERS, BActivityThread.getUserId());
            if (packageInfo == null)
                throw new IllegalStateException("Virtual package metadata unavailable");
            ApplicationInfo applicationInfo = packageInfo.applicationInfo;
            if (packageInfo.providers == null) {
                packageInfo.providers = new ProviderInfo[]{};
            }
            mProviders.addAll(Arrays.asList(packageInfo.providers));

            Object boundApplication = ActivityThread.mBoundApplication.get(BlackBoxCore.mainThread());

            Context packageContext = createPackageContext(applicationInfo);
            if (packageContext == null) throw new IllegalStateException("Target package context unavailable");
            Object loadedApk = ContextImpl.mPackageInfo.get(packageContext);
            LoadedApk.mSecurityViolation.set(loadedApk, false);
            // fix applicationInfo
            LoadedApk.mApplicationInfo.set(loadedApk, applicationInfo);

            // clear dump file
            if (Build.VERSION.SDK_INT < 33)
                FileUtils.deleteDir(new File(BlackBoxCore.get().getDexDumpDir(), packageName));

            // init vmCore
            VMCore.init(Build.VERSION.SDK_INT);
            assert packageContext != null;
            IOCore.get().enableRedirect(packageContext);

            AppBindData bindData = new AppBindData();
            bindData.appInfo = applicationInfo;
            bindData.processName = processName;
            bindData.info = loadedApk;
            bindData.providers = mProviders;

            ActivityThread.AppBindData.instrumentationName.set(boundApplication,
                    new ComponentName(bindData.appInfo.packageName, Instrumentation.class.getName()));
            ActivityThread.AppBindData.appInfo.set(boundApplication, bindData.appInfo);
            ActivityThread.AppBindData.info.set(boundApplication, bindData.info);
            ActivityThread.AppBindData.processName.set(boundApplication, bindData.processName);
            ActivityThread.AppBindData.providers.set(boundApplication, bindData.providers);

            mBoundApplication = bindData;

            Application application;
            BlackBoxCore.get().getAppLifecycleCallback().beforeCreateApplication(packageName, processName, packageContext);
            // Do not swallow construction failures and then dump a fallback loader.
            application = LoadedApk.makeApplication.callWithException(loadedApk, false, null);
            if (application == null) throw new IllegalStateException("Target Application was not created");
            mInitialApplication = application;
            ActivityThread.mInitialApplication.set(BlackBoxCore.mainThread(), application);
            if (Build.VERSION.SDK_INT == 33) {
                if (!mProviders.isEmpty()) {
                    java.lang.reflect.Method install = ActivityThread.REF.getClazz().getDeclaredMethod(
                            "installContentProviders", Context.class, List.class);
                    install.setAccessible(true);
                    install.invoke(BlackBoxCore.mainThread(), application, mProviders);
                }
                Instrumentation instrumentation = ActivityThread.mInstrumentation.get(BlackBoxCore.mainThread());
                if (instrumentation == null) throw new IllegalStateException("Application instrumentation unavailable");
                instrumentation.callApplicationOnCreate(application);
                if (Objects.equals(packageName, processName)) {
                    HakuCaptureSession.phase(mCaptureDirectory, "waiting-for-activity", "applicationOnCreate");
                    HakuCaptureSession.notice(packageName, mCaptureDirectory, "Application started; waiting for target activity");
                }
                // Capture begins only after real Activity.onCreate returns successfully.
            } else if (Objects.equals(packageName, processName)) {
                handleDumpDex(packageName, result, application.getClassLoader());
            }
        } catch (Throwable e) {
            e.printStackTrace();
            mBoundApplication = null;
            if (Build.VERSION.SDK_INT >= 33) {
                captureFailed(e);
            } else {
                mAppConfig = null;
                BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpError(e.toString()));
                BlackBoxCore.get().uninstallPackage(packageName);
            }
        }
    }

    public File getCaptureDirectory() { return mCaptureDirectory; }

    public void captureStage(String stage) {
        mCaptureStage = stage;
        Log.i("HakuCapture", stage);
    }

    private static String describeFailure(Throwable error) {
        StringBuilder message = new StringBuilder();
        java.util.Set<Throwable> seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = error; cause != null && seen.add(cause) && seen.size() <= 12; cause = cause.getCause()) {
            if (message.length() > 0) message.append(" <- ");
            message.append(cause.getClass().getSimpleName()).append(": ").append(cause.getMessage());
        }
        return message.toString();
    }

    public void onTargetActivityCreated(Activity activity) {
        if (Build.VERSION.SDK_INT != 33 || activity.getApplication() != mInitialApplication
                || !Objects.equals(getAppPackageName(), getAppProcessName())) return;
        try {
            HakuCaptureSession.activity(mCaptureDirectory, activity.getClass().getName(), false);
            if (!mCaptureStarted.compareAndSet(false, true)) return;
            HakuCaptureSession.phase(mCaptureDirectory, "observing-loaded-code", "activityOnCreate");
            HakuCaptureSession.notice(getAppPackageName(), mCaptureDirectory, "Activity started; observing loaded code for 2 seconds");
            handleDumpDex(getAppPackageName(), mCaptureResult, mInitialApplication.getClassLoader());
        } catch (Throwable error) {
            captureFailed(error);
        }
    }

    public void onTargetActivityResumed(Activity activity) {
        if (Build.VERSION.SDK_INT != 33 || activity.getApplication() != mInitialApplication
                || !Objects.equals(getAppPackageName(), getAppProcessName())) return;
        try { HakuCaptureSession.activity(mCaptureDirectory, activity.getClass().getName(), true); }
        catch (Exception error) { captureFailed(error); }
    }

    public synchronized void captureFailed(Throwable error) {
        if (Build.VERSION.SDK_INT < 33 || !Objects.equals(getAppPackageName(), getAppProcessName())
                || !mCaptureFinished.compareAndSet(false, true)) return;
        DumpResult result = mCaptureResult;
        if (result == null) {
            result = new DumpResult();
            result.packageName = getAppPackageName();
        }
        Log.e("HakuCapture", "Capture failed after checkpoint " + mCaptureStage, error);
        String message = "Checkpoint " + mCaptureStage + ": " + describeFailure(error);
        if (mCaptureDirectory != null) {
            result.dir = mCaptureDirectory.getAbsolutePath();
            try { HakuCaptureSession.finish(mCaptureDirectory, message); }
            catch (Exception reportError) { message += "; report: " + reportError; }
        }
        BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpError(message));
    }

    private synchronized void captureSucceeded(DumpResult result) throws Exception {
        if (mCaptureFinished.get()) return;
        HakuCaptureSession.finish(mCaptureDirectory, null);
        mCaptureFinished.set(true);
        result.msg = "Loaded standard DEX files validated; see report for coverage";
        BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpSuccess());
    }

    private void handleDumpDex(String packageName, DumpResult result, ClassLoader classLoader) {
        new Thread(() -> {
            try {
                Thread.sleep(Build.VERSION.SDK_INT == 33 ? 2000 : 500);
            } catch (InterruptedException ignored) {
            }
            try {
                captureStage("enter-vmcore-cookie-capture");
                VMCore.cookieDumpDex(classLoader, packageName);
                File[] dexFiles = new File(result.dir).listFiles((dir, name) -> name.endsWith(".dex"));
                if (dexFiles == null || dexFiles.length == 0)
                    throw new IllegalStateException("No validated DEX artifacts produced");
                if (Build.VERSION.SDK_INT == 33) {
                    captureSucceeded(result);
                } else {
                    result.msg = "Loaded standard DEX files validated; see report for coverage";
                    BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpSuccess());
                }
            } catch (Throwable failure) {
                if (Build.VERSION.SDK_INT >= 33) captureFailed(failure);
                else BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpError(
                        failure.getClass().getSimpleName() + ": " + failure.getMessage()));
            } finally {
                if (Build.VERSION.SDK_INT == 33) {
                    try { BlackBoxCore.getBActivityManager().stopCaptureProcesses(packageName, getUserId(), mCaptureDirectory.getName()); }
                    catch (Exception cleanup) { Log.e(TAG, "Unable to stop capture processes", cleanup); }
                } else {
                    mAppConfig = null;
                    BlackBoxCore.get().uninstallPackage(packageName);
                }
            }
        }).start();
    }

    private Context createPackageContext(ApplicationInfo info) {
        try {
            return BlackBoxCore.getContext().createPackageContext(info.packageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public IBinder getActivityThread() {
        return ActivityThread.getApplicationThread.call(BlackBoxCore.mainThread());
    }

    @Override
    public void bindApplication() {
        if (!isInit()) {
            bindApplication(getAppPackageName(), getAppProcessName());
        }
    }

    private static final class StartedService {
        Service service;
        IBinder token = new Binder();
        int lastStartId;
    }

    @Override
    public void startVirtualService(Intent intent, ServiceInfo info) {
        new Handler(Looper.getMainLooper()).post(() -> {
            try {
                if (Build.VERSION.SDK_INT != 33) throw new UnsupportedOperationException("Started-service route requires API33");
                bindApplication();
                if (mInitialApplication == null) throw new IllegalStateException("Service Application unavailable");
                ComponentName component = new ComponentName(info.packageName, info.name);
                StartedService record = mStartedServices.get(component);
                if (record == null) {
                    record = new StartedService();
                    record.service = (Service) mInitialApplication.getClassLoader().loadClass(info.name).getDeclaredConstructor().newInstance();
                    java.lang.reflect.Method attach = Service.class.getDeclaredMethod("attach", Context.class,
                            ActivityThread.REF.getClazz(), String.class, IBinder.class, Application.class, Object.class);
                    attach.setAccessible(true);
                    java.lang.reflect.Method getService = android.app.ActivityManager.class.getDeclaredMethod("getService");
                    getService.setAccessible(true);
                    attach.invoke(record.service, mInitialApplication.getBaseContext(), BlackBoxCore.mainThread(),
                            info.name, record.token, mInitialApplication, getService.invoke(null));
                    mStartedServices.put(component, record);
                    record.service.onCreate();
                    HakuCaptureSession.service(mCaptureDirectory, info, "created", android.os.Process.myPid(), null);
                }
                intent.setExtrasClassLoader(mInitialApplication.getClassLoader());
                record.service.onStartCommand(intent, 0, ++record.lastStartId);
                HakuCaptureSession.service(mCaptureDirectory, info, "started", android.os.Process.myPid(), null);
                Log.i("HakuContainer", "Service started: " + component + " process=" + getAppProcessName());
            } catch (Throwable error) {
                Log.e("HakuContainer", "Virtual service lifecycle failed", error);
                try { HakuCaptureSession.service(mCaptureDirectory, info, "failed", android.os.Process.myPid(), error.toString()); }
                catch (Exception reportError) { Log.e("HakuContainer", "Cannot record service failure", reportError); }
                captureFailed(error);
            }
        });
    }

    @Override
    public void stopVirtualService(ComponentName component) {
        new Handler(Looper.getMainLooper()).post(() -> destroyService(component));
    }

    private void destroyService(ComponentName component) {
        StartedService record = mStartedServices.remove(component);
        if (record != null) record.service.onDestroy();
    }

    public boolean stopVirtualServiceToken(IBinder token, int startId) {
        for (Map.Entry<ComponentName, StartedService> entry : mStartedServices.entrySet()) {
            StartedService record = entry.getValue();
            if (record.token == token && (startId < 0 || startId == record.lastStartId)) {
                ComponentName component = entry.getKey();
                // stopSelf is normally called on the process main thread. Post destruction
                // to preserve callback ordering and avoid mutating the map during iteration.
                new Handler(Looper.getMainLooper()).post(() -> destroyService(component));
                return true;
            }
        }
        return false;
    }

    public static class AppBindData {
        String processName;
        ApplicationInfo appInfo;
        List<ProviderInfo> providers;
        Object info;
    }
}

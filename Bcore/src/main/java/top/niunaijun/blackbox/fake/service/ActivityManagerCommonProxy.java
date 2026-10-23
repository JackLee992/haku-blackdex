package top.niunaijun.blackbox.fake.service;

import java.lang.reflect.Method;
import android.os.Build;
import android.content.Intent;
import android.content.ComponentName;
import android.content.pm.ResolveInfo;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.proxy.ProxyManifest;
import top.niunaijun.blackbox.proxy.record.ProxyActivityRecord;

import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;

/**
 * Created by Milk on 4/21/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class ActivityManagerCommonProxy {
    public static final String TAG = "CommonStub";

    private static Intent targetIntent(Intent original, String resolvedType) throws Exception {
        ResolveInfo resolved = BlackBoxCore.getBPackageManager().resolveActivity(original, 0,
                resolvedType, BActivityThread.getUserId());
        if (resolved == null || resolved.activityInfo == null) return original;
        android.content.pm.ActivityInfo info = resolved.activityInfo;
        if (!info.exported && !info.packageName.equals(BActivityThread.getAppPackageName()))
            throw new SecurityException("Target activity is not exported");
        if (info.launchMode != android.content.pm.ActivityInfo.LAUNCH_MULTIPLE)
            throw new UnsupportedOperationException("Container activity launch mode is not qualified: " + info.launchMode);
        AppConfig process = BlackBoxCore.getBActivityManager().initProcess(info.packageName,
                info.processName, BActivityThread.getUserId());
        if (process == null) throw new IllegalStateException("Target activity process unavailable");
        Intent target = new Intent(original);
        target.setComponent(new ComponentName(info.packageName, info.name));
        Intent shadow = new Intent(original);
        shadow.setComponent(new ComponentName(BlackBoxCore.getHostPkg(), ProxyManifest.getProxyActivity(process.bpid)));
        shadow.setPackage(BlackBoxCore.getHostPkg());
        ProxyActivityRecord.saveStub(shadow, target, info, null, BActivityThread.getUserId());
        return shadow;
    }

    private static Object invokeSystem(Object who, Method method, Object[] args) throws Throwable {
        try { return method.invoke(who, args); }
        catch (java.lang.reflect.InvocationTargetException error) { throw error.getCause(); }
    }

    @ProxyMethod(name = "startActivity")
    public static class StartActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (Build.VERSION.SDK_INT != 33) return 0;
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent) {
                    String resolvedType = i + 1 < args.length && args[i + 1] instanceof String ? (String) args[i + 1] : null;
                    args[i] = targetIntent((Intent) args[i], resolvedType);
                    // Preserve real result token, request code, flags and options.
                    // Only the identity observed by system_server is the actual host.
                    args[1] = BlackBoxCore.getHostPkg();
                    return invokeSystem(who, method, args);
                }
            }
            throw new IllegalStateException("Unknown activity start signature");
        }
    }

    @ProxyMethod(name = "startActivities")
    public static class StartActivities extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (Build.VERSION.SDK_INT != 33) return 0;
            for (int i = 0; i < args.length; i++) {
                if (args[i] instanceof Intent[]) {
                    Intent[] originals = (Intent[]) args[i];
                    String[] types = (String[]) args[i + 1];
                    Intent[] shadows = new Intent[originals.length];
                    for (int n = 0; n < originals.length; n++) shadows[n] = targetIntent(originals[n], types == null ? null : types[n]);
                    args[i] = shadows;
                    args[1] = BlackBoxCore.getHostPkg();
                    return invokeSystem(who, method, args);
                }
            }
            throw new IllegalStateException("Unknown activities start signature");
        }
    }

    @ProxyMethod(name = "activityResumed")
    public static class ActivityResumed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod(name = "activityDestroyed")
    public static class ActivityDestroyed extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }

    @ProxyMethod(name = "finishActivity")
    public static class FinishActivity extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            return method.invoke(who, args);
        }
    }
}

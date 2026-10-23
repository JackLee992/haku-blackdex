package top.niunaijun.blackbox.fake.service;

import android.os.IInterface;
import android.os.Build;
import android.os.Process;
import android.os.storage.StorageVolume;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

import reflection.android.os.ServiceManager;
import reflection.android.os.mount.IMountService;
import reflection.android.os.storage.IStorageManager;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.system.user.BUserHandle;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.MethodHook;
import top.niunaijun.blackbox.fake.hook.ProxyMethod;
import top.niunaijun.blackbox.utils.compat.BuildCompat;

/**
 * Created by Milk on 4/10/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class IStorageManagerProxy extends BinderInvocationStub {

    public IStorageManagerProxy() {
        super(ServiceManager.getService.call("mount"));
    }

    @Override
    protected Object getWho() {
        IInterface mount;
        if (BuildCompat.isOreo()) {
            mount = IStorageManager.Stub.asInterface.call(ServiceManager.getService.call("mount"));
        } else {
            mount = IMountService.Stub.asInterface.call(ServiceManager.getService.call("mount"));
        }
        return mount;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("mount");
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @ProxyMethod(name = "getVolumeList")
    public static class GetVolumeList extends MethodHook {
        @Override
        protected Object hook(Object who, Method method, Object[] args) throws Throwable {
            if (Build.VERSION.SDK_INT == 33) {
                // IStorageManager authenticates callingPackage against the real
                // Binder UID. Keep the target Context identity virtual, but use
                // the host identity for this system-service boundary.
                if (args == null || args.length != 3 || !(args[0] instanceof Integer)
                        || !(args[1] instanceof String) || !(args[2] instanceof Integer)) {
                    throw new IllegalStateException("Unsupported API 33 getVolumeList signature");
                }
                Object[] hostArgs = args.clone();
                // API 33 takes a userId here, unlike older UID-based versions.
                hostArgs[0] = BUserHandle.getUserId(Process.myUid());
                hostArgs[1] = BlackBoxCore.getHostPkg();
                try {
                    return method.invoke(who, hostArgs);
                } catch (InvocationTargetException error) {
                    throw error.getCause();
                }
            }
            if (args == null) {
                StorageVolume[] volumeList = BlackBoxCore.getBStorageManager().getVolumeList(Process.myUid(), null, 0, BActivityThread.getUserId());
                if (volumeList == null) {
                    return method.invoke(who, args);
                }
                return volumeList;
            }
            try {
                int uid = (int) args[0];
                String packageName = (String) args[1];
                int flags = (int) args[2];
                StorageVolume[] volumeList = BlackBoxCore.getBStorageManager().getVolumeList(uid, packageName, flags, BActivityThread.getUserId());
                if (volumeList == null) {
                    return method.invoke(who, args);
                }
            } catch (Throwable t) {
                return method.invoke(who, args);
            }
            return method.invoke(who, args);
        }
    }
}

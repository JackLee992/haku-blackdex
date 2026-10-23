package top.niunaijun.blackbox.fake.service;

import reflection.android.app.IActivityTaskManager;
import android.os.Build;
import reflection.android.util.Singleton;
import reflection.android.os.ServiceManager;
import top.niunaijun.blackbox.fake.hook.BinderInvocationStub;
import top.niunaijun.blackbox.fake.hook.ScanClass;

/**
 * Created by Milk on 3/31/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
@ScanClass(ActivityManagerCommonProxy.class)
public class IActivityTaskManagerProxy extends BinderInvocationStub {
    public static final String TAG = "ActivityTaskManager";

    public IActivityTaskManagerProxy() {
        super(ServiceManager.getService.call("activity_task"));
    }

    @Override
    protected Object getWho() {
        return IActivityTaskManager.Stub.asInterface.call(ServiceManager.getService.call("activity_task"));
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {
        replaceSystemService("activity_task");
        if (Build.VERSION.SDK_INT == 33) {
            try {
                // Instrumentation may have resolved this singleton before hooks ran.
                java.lang.reflect.Field singletonField = Class.forName("android.app.ActivityTaskManager")
                        .getDeclaredField("IActivityTaskManagerSingleton");
                singletonField.setAccessible(true);
                Singleton.mInstance.unwrap().set(singletonField.get(null), proxyInvocation);
            } catch (ReflectiveOperationException error) {
                throw new IllegalStateException("Cannot bind ActivityTaskManager proxy", error);
            }
        }
    }

    @Override
    public boolean isBadEnv() {
        return false;
    }
}

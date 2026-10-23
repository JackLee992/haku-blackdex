package top.niunaijun.blackbox.fake.service.provider;

import android.os.IInterface;
import android.os.Build;
import android.content.AttributionSource;

import java.lang.reflect.Method;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.fake.hook.ClassInvocationStub;

/**
 * Created by Milk on 4/8/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class SettingsProviderStub extends ClassInvocationStub implements VContentProvider {
    private IInterface mBase;

    @Override
    public IInterface wrapper(IInterface contentProviderProxy, String appPkg) {
        mBase = contentProviderProxy;
        injectHook();
        return (IInterface) getProxyInvocation();
    }

    @Override
    protected Object getWho() {
        return mBase;
    }

    @Override
    protected void inject(Object baseInvocation, Object proxyInvocation) {

    }

    @Override
    protected void onBindMethod() {

    }

    @Override
    public boolean isBadEnv() {
        return false;
    }

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        if ("asBinder".equals(method.getName())) {
            return method.invoke(mBase, args);
        }
        if (args != null && args.length > 0) {
            if (Build.VERSION.SDK_INT >= 31 && args[0] instanceof AttributionSource) {
                // IContentProvider uses AttributionSource on modern Android. The
                // system sees the host UID, so forward its actual identity/token.
                // The target's logical Context/package identity stays unchanged.
                args[0] = BlackBoxCore.getContext().getAttributionSource();
            } else if (args[0] instanceof String) {
                args[0] = BlackBoxCore.getHostPkg();
            }
        }
        try {
            return method.invoke(mBase, args);
        } catch (java.lang.reflect.InvocationTargetException error) {
            throw error.getCause();
        }
    }
}

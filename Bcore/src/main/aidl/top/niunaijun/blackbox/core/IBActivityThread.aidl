// IBActivityThread.aidl
package top.niunaijun.blackbox.core;

// Declare any non-default types here with import statements

import android.os.IBinder;
import android.content.ComponentName;
import android.content.Intent;
import java.util.List;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;

interface IBActivityThread {
    IBinder getActivityThread();
    void bindApplication();
    oneway void startVirtualService(in Intent intent, in ServiceInfo info);
    oneway void stopVirtualService(in ComponentName component);
}

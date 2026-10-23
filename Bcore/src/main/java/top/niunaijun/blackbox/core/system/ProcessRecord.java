package top.niunaijun.blackbox.core.system;

import android.content.pm.ApplicationInfo;
import android.os.Binder;
import android.os.ConditionVariable;

import android.os.IInterface;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.Process;
import android.os.Build;
import java.io.File;
import top.niunaijun.blackbox.core.HakuCaptureSession;
import android.text.TextUtils;

import java.util.Arrays;

import top.niunaijun.blackbox.entity.AppConfig;
import top.niunaijun.blackbox.core.IBActivityThread;
import top.niunaijun.blackbox.proxy.ProxyManifest;

public class ProcessRecord extends Binder implements Parcelable {
    public final ApplicationInfo info;
    final public String processName;
    public IBActivityThread bActivityThread;
    public IInterface appThread;
    public int pid;
    public int uid;
    public int buid;
    public int bpid;
    public int callingVUid;
    public int userId;
    public int baseBUid;
    // Server-only session identity: never look up a newer attempt when this PID dies.
    public final File captureDirectory;

    public ConditionVariable initLock = new ConditionVariable();

    public ProcessRecord(ApplicationInfo info, String processName, int buid, int bpid, int callingVUid) {
        this.info = info;
        this.buid = buid;
        this.bpid = bpid;
        this.userId = 0;
        this.callingVUid = callingVUid;
        this.processName = processName;
        File sessionDirectory = null;
        if (Build.VERSION.SDK_INT == 33) {
            try { sessionDirectory = HakuCaptureSession.current(info.packageName); }
            catch (java.io.IOException ignored) { }
        }
        captureDirectory = sessionDirectory;
    }

    public int getCallingBUid() {
        return callingVUid;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ProcessRecord that = (ProcessRecord) o;
        return pid == that.pid &&
                buid == that.buid &&
                bpid == that.bpid &&
                uid == that.uid &&
                userId == that.userId &&
                baseBUid == that.baseBUid &&
                TextUtils.equals(processName, that.processName);
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[]{processName, pid, buid, bpid, uid, pid, userId});
    }

    public String getProviderAuthority() {
        return ProxyManifest.getProxyAuthorities(bpid);
    }

    public AppConfig getClientConfig() {
        AppConfig config = new AppConfig();
        config.packageName = info.packageName;
        config.processName = processName;
        config.bpid = bpid;
        config.buid = buid;
        config.uid = uid;
        config.userId = userId;
        config.token = this;
        config.baseBUid = baseBUid;
        config.captureSessionId = captureDirectory == null ? null : captureDirectory.getName();
        return config;
    }

    public void kill() {
        if (pid > 0) {
            try {
                Process.killProcess(pid);
            } catch (Throwable e) {
                e.printStackTrace();
            }
        }
    }

    public String getPackageName() {
        return info.packageName;
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeParcelable(this.info, flags);
        dest.writeString(this.processName);
        dest.writeInt(this.pid);
        dest.writeInt(this.buid);
        dest.writeInt(this.bpid);
        dest.writeInt(this.uid);
        dest.writeInt(this.callingVUid);
        dest.writeInt(this.userId);
        dest.writeInt(this.baseBUid);
    }

    protected ProcessRecord(Parcel in) {
        // Parcel copies are descriptive records, not server-owned process handles.
        // Client session identity is transported separately through AppConfig.
        this.captureDirectory = null;
        this.info = in.readParcelable(ApplicationInfo.class.getClassLoader());
        this.processName = in.readString();
        this.pid = in.readInt();
        this.buid = in.readInt();
        this.bpid = in.readInt();
        this.uid = in.readInt();
        this.callingVUid = in.readInt();
        this.userId = in.readInt();
        this.baseBUid = in.readInt();
    }

    public static final Creator<ProcessRecord> CREATOR = new Creator<ProcessRecord>() {
        @Override
        public ProcessRecord createFromParcel(Parcel source) {
            return new ProcessRecord(source);
        }

        @Override
        public ProcessRecord[] newArray(int size) {
            return new ProcessRecord[size];
        }
    };
}

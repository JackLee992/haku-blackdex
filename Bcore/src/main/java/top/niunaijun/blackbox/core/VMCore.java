package top.niunaijun.blackbox.core;

import android.util.Log;
import android.os.Build;

import androidx.annotation.Keep;

import java.io.File;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.IOException;
import java.util.zip.ZipFile;
import java.util.zip.ZipEntry;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.ref.Reference;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import dalvik.system.DexFile;
import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.app.BActivityThread;
import top.niunaijun.blackbox.core.env.BEnvironment;
import top.niunaijun.blackbox.entity.dump.DumpResult;
import top.niunaijun.blackbox.utils.DexUtils;
import top.niunaijun.blackbox.utils.FileUtils;
import top.niunaijun.blackbox.utils.compat.DexFileCompat;
import top.niunaijun.jnihook.MethodUtils;

import static top.niunaijun.blackbox.core.env.BEnvironment.EMPTY_JAR;

/**
 * Created by Milk on 4/9/21.
 * * ∧＿∧
 * (`･ω･∥
 * 丶　つ０
 * しーＪ
 * 此处无Bug
 */
public class VMCore {
    public static final String TAG = "VMCoreJava";

    static {
        new File("");
        System.loadLibrary("blackdex");
    }

    public static native void init(int apiLevel);

    public static native void enableIO();

    public static native void addIORule(String targetPath, String relocatePath);

    public static native void hideXposed();

    private static native void cookieDumpDex(long cookie, String dir, boolean fixMethod);

    public static native void hookDumpDex(String dir);

    private static DexFile calibrationDex; // Retain ownership while native calibration reads it.
    public static volatile String hakuHookSetupError;

    public static void cookieDumpDex(ClassLoader classLoader, String packageName) {
        if (Build.VERSION.SDK_INT >= 33) {
            captureAndroid13(classLoader, packageName, BActivityThread.currentActivityThread().getCaptureDirectory());
            return;
        }
        List<Long> cookies = DexFileCompat.getCookies(classLoader);
        File file = new File(BlackBoxCore.get().getDexDumpDir(), packageName);

        DumpResult result = new DumpResult();
        result.dir = file.getAbsolutePath();
        result.packageName = packageName;
        int availableProcessors = Runtime.getRuntime().availableProcessors();
        ExecutorService executorService = Executors.newFixedThreadPool(availableProcessors <= 0 ? 1 : availableProcessors);
        CountDownLatch countDownLatch = new CountDownLatch(cookies.size());
        AtomicInteger atomicInteger = new AtomicInteger(0);

        BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(cookies.size(), atomicInteger.getAndIncrement()));
        for (int i = 0; i < cookies.size(); i++) {
            long cookie = cookies.get(i);
            if (cookie == 0) {
                countDownLatch.countDown();
                BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(cookies.size(), atomicInteger.getAndIncrement()));
                continue;
            }
            FileUtils.mkdirs(file);
            if (atomicInteger.get() == 1) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException ignored) {
                }
            }
            executorService.execute(() -> {
                cookieDumpDex(cookie, file.getAbsolutePath(), BlackBoxCore.get().isFixCodeItem());
                BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(cookies.size(), atomicInteger.getAndIncrement()));
                countDownLatch.countDown();
            });
        }
        try {
            countDownLatch.await();
        } catch (InterruptedException ignored) {
        }
        File[] files = file.listFiles();
        if (files != null) {
            for (File dex : files) {
                if (dex.isFile() && dex.getAbsolutePath().endsWith(".dex")) {
                    DexUtils.fixDex(dex);
                }
            }
        }
    }

    private static void captureAndroid13(ClassLoader classLoader, String packageName, File directory) {
        BActivityThread.currentActivityThread().captureStage("check-capture-configuration");
        if (Build.VERSION.SDK_INT != 33)
            throw new UnsupportedOperationException("Haku native layout is qualified for API 33 only");
        if (BlackBoxCore.get().isFixCodeItem())
            throw new UnsupportedOperationException("Deep ArtMethod recovery is unsupported on Android 13+");
        if (hakuHookSetupError != null)
            throw new IllegalStateException("Requested ART hook failed: " + hakuHookSetupError);
        if (classLoader == null) throw new IllegalStateException("Missing target ClassLoader");
        // The monitor keeps the loader and its DexFile owners reachable throughout
        // synchronous native reads. No cookie escapes into a detached worker.
        synchronized (classLoader) {
            BActivityThread.currentActivityThread().captureStage("enumerate-app-loader-cookies");
            List<DexFile> owners = targetDexFiles(classLoader);
            try {
                List<Long> cookies = new ArrayList<>();
                for (DexFile owner : owners) cookies.addAll(DexFileCompat.getCookies(owner));
                if (cookies.isEmpty()) throw new IllegalStateException("No target DexFile cookies remain after excluding Haku support archives");
                if (directory == null) throw new IllegalStateException("Missing current capture session");
                if (!directory.isDirectory() && !directory.mkdirs())
                    throw new IllegalStateException("Cannot create capture directory");
                DumpResult progress = new DumpResult();
                progress.dir = directory.getAbsolutePath();
                progress.packageName = packageName;
                progress.msg = "Capturing and validating loaded DEX files";
                int completed = 0;
                for (Long cookie : cookies) {
                    if (cookie == null || cookie == 0) throw new IllegalStateException("Invalid DexFile cookie");
                    BActivityThread.currentActivityThread().captureStage("native-cookie-" + (completed + 1) + "-of-" + cookies.size());
                    cookieDumpDex(cookie, directory.getAbsolutePath(), false);
                    BActivityThread.currentActivityThread().captureStage("validated-cookie-" + (completed + 1));
                    BlackBoxCore.getBDumpManager().noticeMonitor(progress.dumpProcess(cookies.size(), ++completed));
                }
                // Original bytes have already passed native validation and readback.
                // DexUtils.fixDex must not rewrite their magic, lengths or checksums.
            } finally {
                // Keep every selected DexFile owner alive even if the application
                // concurrently replaces its loader elements during native reads.
                Reference.reachabilityFence(owners);
            }
        }
    }

    private static List<DexFile> targetDexFiles(ClassLoader classLoader) {
        try {
            Set<String> supportPaths = new HashSet<>();
            supportPaths.add(BEnvironment.JUNIT_JAR.getCanonicalPath());
            supportPaths.add(BEnvironment.VM_JAR.getCanonicalPath());
            supportPaths.add(BEnvironment.EMPTY_JAR.getCanonicalPath());
            List<DexFile> targets = new ArrayList<>();
            for (DexFile dexFile : DexFileCompat.getDexFiles(classLoader)) {
                if (dexFile == null) continue; // Resource-only loader element.
                String source = dexFile.getName();
                // Do not require the source to exist: packed apps may unlink it
                // after loading. Only the exact owned support paths are excluded.
                if (source != null && !source.isEmpty()
                        && supportPaths.contains(new File(source).getCanonicalPath())) continue;
                targets.add(dexFile);
            }
            return targets;
        } catch (IOException error) {
            throw new IllegalStateException("Cannot identify Haku support archive paths", error);
        }
    }

    @Keep
    public static byte[] loadEmptyDexBytes() throws IOException {
        BActivityThread.currentActivityThread().captureStage("native-calibration-read-reference");
        try (ZipFile archive = new ZipFile(EMPTY_JAR)) {
            ZipEntry entry = archive.getEntry("classes.dex");
            if (entry == null) throw new IOException("Calibration classes.dex missing");
            try (InputStream input = archive.getInputStream(entry);
                 ByteArrayOutputStream output = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) {
                    if (output.size() > 64 * 1024 * 1024 - count)
                        throw new IOException("Calibration DEX exceeds limit");
                    output.write(buffer, 0, count);
                }
                return output.toByteArray();
            }
        }
    }

    @Keep
    public static int getCallingUid(int origCallingUid) {
//        if (origCallingUid > 0 && origCallingUid < Process.FIRST_APPLICATION_UID)
//            return origCallingUid;
//        // 非用户应用
//        if (origCallingUid > Process.LAST_APPLICATION_UID)
//            return origCallingUid;
//
//        Log.d(TAG, "origCallingUid: " + origCallingUid + " => " + BClient.getBaseVUid());
//        return BClient.getBaseVUid();
        return origCallingUid;
    }

    @Keep
    public static String redirectPath(String path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static File redirectPath(File path) {
        return IOCore.get().redirectPath(path);
    }

    @Keep
    public static synchronized long[] loadEmptyDex() {
        BActivityThread.currentActivityThread().captureStage("native-calibration-open-reference");
        try {
            if (calibrationDex == null) calibrationDex = new DexFile(EMPTY_JAR);
            List<Long> cookies = DexFileCompat.getCookies(calibrationDex);
            long[] longs = new long[cookies.size()];
            for (int i = 0; i < cookies.size(); i++) {
                longs[i] = cookies.get(i);
            }
            return longs;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new long[]{};
    }

    @Keep
    public static Object findMethod(String className, String methodName, String signature) {
        try {
            className = className.replace("/", ".");
            if (className.startsWith("L")) {
                className = className.substring(1);
            }
            if (className.endsWith(";")) {
                className = className.substring(0, className.length() - 1);
            }
            ClassLoader classLoader = BActivityThread.getApplication().getClassLoader();
            Class<?> aClass = Class.forName(className, false, classLoader);
            if ("<init>".equals(methodName)) {
                Constructor<?>[] constructors = aClass.getDeclaredConstructors();
                for (Constructor<?> constructor : constructors) {
                    String desc = MethodUtils.getDesc(constructor);
                    if (signature.equals(desc)) {
                        return constructor;
                    }
                }
            }

            try {
                Method[] declaredMethods = aClass.getDeclaredMethods();
                for (Method declaredMethod : declaredMethods) {
                    if (declaredMethod.getName().equals(methodName)) {
                        String desc = MethodUtils.getDesc(declaredMethod);
                        if (desc.equals(signature)) {
                            return declaredMethod;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }

            Method[] methods = aClass.getMethods();
            for (Method method : methods) {
                if (method.getName().equals(methodName)) {
                    String desc = MethodUtils.getDesc(method);
                    if (desc.equals(signature)) {
                        return method;
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}

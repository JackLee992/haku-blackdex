package com.jacklee.haku.containerfixture;

import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.os.SystemClock;
import dalvik.system.DexClassLoader;
import java.io.*;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Owned test packer: only its own XOR assets are decoded; no other app is read. */
public final class FixtureApplication extends Application {
    private static final JSONObject STATE = new JSONObject();
    private static final ArrayList<ClassLoader> RETAINED = new ArrayList<>();

    public static String hash(byte[] data) throws Exception {
        StringBuilder hex = new StringBuilder();
        for (byte b : MessageDigest.getInstance("SHA-256").digest(data))
            hex.append(String.format(Locale.ROOT, "%02x", b & 255));
        return hex.toString();
    }

    public static void record(Context context, String name, Object value) {
        synchronized (STATE) {
            try {
                STATE.put(name, value);
                STATE.put("pid", Process.myPid());
                STATE.put("uid", Process.myUid());
                STATE.put("package", context.getPackageName());
                STATE.put("filesDir", context.getFilesDir().getAbsolutePath());
                STATE.put("sourceDir", context.getApplicationInfo().sourceDir);
                STATE.put("lastElapsedMs", SystemClock.elapsedRealtime());
                File directory = new File(context.getFilesDir(), "fixture-records");
                if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("records mkdir");
                File temporary = new File(directory, Process.myPid() + ".json.partial");
                try (FileOutputStream output = new FileOutputStream(temporary)) {
                    output.write(STATE.toString(2).getBytes("UTF-8"));
                    output.getFD().sync();
                }
                if (!temporary.renameTo(new File(directory, Process.myPid() + ".json")))
                    throw new IOException("records rename");
            } catch (Exception failure) {
                android.util.Log.e("HakuContainerFixture", "cannot persist fixture evidence", failure);
            }
        }
    }

    private byte[] payload(String name) throws Exception {
        try (InputStream input = getAssets().open(name);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] block = new byte[8192];
            int n;
            while ((n = input.read(block)) != -1) output.write(block, 0, n);
            byte[] data = output.toByteArray();
            for (int i = 0; i < data.length; i++) data[i] ^= (byte) 0xa7;
            return data;
        }
    }

    private static Object pathList(ClassLoader loader) throws Exception {
        Field field = Class.forName("dalvik.system.BaseDexClassLoader").getDeclaredField("pathList");
        field.setAccessible(true);
        return field.get(loader);
    }

    private static void mergeOwnedElements(ClassLoader app, ClassLoader child) throws Exception {
        Object destination = pathList(app), source = pathList(child);
        Field elements = destination.getClass().getDeclaredField("dexElements");
        elements.setAccessible(true);
        Object old = elements.get(destination), added = elements.get(source);
        int a = Array.getLength(old), b = Array.getLength(added);
        Object joined = Array.newInstance(old.getClass().getComponentType(), a + b);
        System.arraycopy(old, 0, joined, 0, a);
        System.arraycopy(added, 0, joined, a, b);
        elements.set(destination, joined);
    }

    private void preparePayloads() throws Exception {
        String[] names = {"main.xor", "distinct.xor"};
        String[] markers = {"haku_dynamic_dex_ok_v1", "haku_changed_dex_ok_v1"};
        JSONArray cases = new JSONArray();
        for (int i = 0; i < names.length; i++) {
            byte[] bytes = payload(names[i]);
            File file = new File(getFilesDir(), "owned-payload-" + Process.myPid() + "-" + i + ".dex");
            try (FileOutputStream output = new FileOutputStream(file)) {
                output.write(bytes);
                output.getFD().sync();
            }
            DexClassLoader loader = new DexClassLoader(file.getAbsolutePath(),
                    getCodeCacheDir().getAbsolutePath(), null, getClassLoader().getParent());
            RETAINED.add(loader);
            Class<?> controlled = loader.loadClass("fixture.Controlled");
            String actual = (String) controlled.getMethod("marker").invoke(null);
            if (!markers[i].equals(actual)) throw new AssertionError("payload marker " + i);
            // A controlled packer-style app loader: the engine must inspect the
            // real runtime cookie. Clear DEX bytes are absent from the APK.
            mergeOwnedElements(getClassLoader(), loader);
            boolean deleted = file.delete();
            if (!deleted) throw new IOException("payload source removal " + i);
            cases.put(new JSONObject().put("sha256", hash(bytes)).put("bytes", bytes.length)
                    .put("marker", actual).put("sourceDeleted", true)
                    .put("appLoaderElementRegistered", true));
        }
        record(this, "payloads", cases);
        record(this, "payloadsReady", true);
    }

    @Override public void onCreate() {
        super.onCreate();
        record(this, "schema", "haku.container.fixture.v1");
        record(this, "applicationOnCreate", true);
        record(this, "applicationStartedElapsedMs", SystemClock.elapsedRealtime());
        try {
            // Only this owned fixture reads this packaged QA setting. A bounded
            // slow-start variant gives the real Haku Cancel button a stable
            // opportunity to stop an accepted session before Activity launch.
            try (InputStream delay = getAssets().open("startup-delay-ms.txt")) {
                byte[] number = new byte[32];
                int count = delay.read(number);
                int milliseconds = Integer.parseInt(new String(number, 0, count, "UTF-8"));
                if (milliseconds < 0 || milliseconds > 15000) throw new IOException("invalid startup delay");
                record(this, "startupDelayMs", milliseconds);
                if (milliseconds > 0) {
                    record(this, "startupDelayStarted", true);
                    SystemClock.sleep(milliseconds);
                    record(this, "startupDelayCompleted", true);
                }
            }
            preparePayloads();
        } catch (Throwable failure) {
            record(this, "payloadsReady", false);
            record(this, "payloadError", failure.toString());
            android.util.Log.e("HakuContainerFixture", "payload preparation failed", failure);
        }
    }
}

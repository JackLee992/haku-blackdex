package top.niunaijun.blackbox.core;

import android.os.Build;
import android.os.Process;
import android.system.Os;
import android.system.OsConstants;
import android.util.AtomicFile;
import android.content.pm.ServiceInfo;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Locale;
import java.util.UUID;

import top.niunaijun.blackbox.BlackBoxCore;
import top.niunaijun.blackbox.entity.dump.DumpResult;

/** App-private, per-attempt records shared by the host and virtual processes. */
public final class HakuCaptureSession {
    public static final String REPORT = "capture-report.json";
    private HakuCaptureSession() { }

    private static File packageDirectory(String packageName) throws IOException {
        if (packageName == null || !packageName.matches("[A-Za-z0-9_]+(?:\\.[A-Za-z0-9_]+)+"))
            throw new IOException("Invalid capture package name");
        return new File(BlackBoxCore.get().getDexDumpDir(), packageName);
    }

    public static synchronized File begin(String packageName) throws Exception {
        File parent = packageDirectory(packageName);
        File directory = new File(parent, System.currentTimeMillis() + "-" + UUID.randomUUID());
        if (!directory.mkdirs()) throw new IOException("Cannot create capture session");
        JSONObject report = new JSONObject();
        report.put("schemaVersion", 1);
        report.put("sessionId", directory.getName());
        report.put("packageName", packageName);
        report.put("status", "running");
        report.put("phase", "launching");
        report.put("startedAt", System.currentTimeMillis());
        report.put("apiLevel", Build.VERSION.SDK_INT);
        report.put("abi", Build.SUPPORTED_ABIS[0]);
        report.put("observationWindowMs", 2000);
        report.put("captureMode", "main-process-loaded-standard-dex");
        report.put("applicationOnCreate", false);
        report.put("activityOnCreate", false);
        report.put("artifacts", new JSONArray());
        report.put("activities", new JSONArray());
        report.put("services", new JSONArray());
        report.put("serviceScope", "Started services in virtual processes; binding, foreground operation and restart semantics are not qualified.");
        report.put("coverage", "Loaded DEX files of the main application class loader at a snapshot 2000ms after Activity.onCreate. Secondary processes, independent child loaders, late loads and deep method recovery are not covered.");
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, ".session-lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            writeJson(directory, report);
        }
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(parent, ".active-session-lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            AtomicFile active = new AtomicFile(new File(parent, "active-session"));
            write(active, directory.getName().getBytes(StandardCharsets.UTF_8));
        }
        return directory;
    }

    public static synchronized File current(String packageName) throws IOException {
        File parent = packageDirectory(packageName);
        byte[] bytes;
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(parent, ".active-session-lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            bytes = new AtomicFile(new File(parent, "active-session")).readFully();
        }
        String name = new String(bytes, StandardCharsets.UTF_8);
        if (!name.matches("[0-9]+-[a-f0-9-]{36}")) throw new IOException("Invalid capture session pointer");
        File directory = new File(parent, name);
        if (!directory.isDirectory()) throw new IOException("Capture session missing");
        return directory;
    }

    public static synchronized JSONObject read(File directory) throws Exception {
        // AtomicFile.openRead can delete an in-flight .new file. Readers must
        // take the same cross-process lock as writers, not merely read the base.
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, ".session-lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            return readLocked(directory);
        }
    }

    /** Caller holds .session-lock; FileLock must not be acquired recursively. */
    private static JSONObject readLocked(File directory) throws Exception {
        return new JSONObject(new String(new AtomicFile(new File(directory, REPORT)).readFully(), StandardCharsets.UTF_8));
    }

    /** Resolve only the process's original attempt; never follow active-session. */
    public static File boundSession(String packageName, String sessionId) throws Exception {
        if (sessionId == null || !sessionId.matches("[0-9]+-[a-f0-9-]{36}"))
            throw new IOException("Invalid process capture session ID");
        File directory = new File(packageDirectory(packageName), sessionId);
        if (!directory.isDirectory()) throw new IOException("Process capture session missing");
        JSONObject report = read(directory);
        if (!packageName.equals(report.optString("packageName"))
                || !sessionId.equals(report.optString("sessionId")))
            throw new IOException("Process capture session identity mismatch");
        if (!"running".equals(report.optString("status")))
            throw new IOException("Process capture session already ended");
        return directory;
    }

    public static synchronized void phase(File directory, String phase, String lifecycle) throws Exception {
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, ".session-lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
        JSONObject report = readLocked(directory);
        if (!"running".equals(report.getString("status"))) throw new IOException("Capture session already ended");
        report.put("phase", phase);
        report.put("capturePid", Process.myPid());
        if (lifecycle != null) report.put(lifecycle, true);
        writeJson(directory, report);
        }
    }

    public static synchronized void finish(File directory, String error) throws Exception {
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, ".session-lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
        JSONObject report = readLocked(directory);
        if (!"running".equals(report.getString("status"))) throw new IOException("Capture session already ended: " + report.getString("status"));
        JSONArray artifacts = new JSONArray();
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".dex"));
        if (files == null) throw new IOException("Cannot list capture artifacts");
        Arrays.sort(files, Comparator.comparing(File::getName));
        JSONArray invalidArtifacts = new JSONArray();
        for (File file : files) {
            try {
                if (!OsConstants.S_ISREG(Os.lstat(file.getAbsolutePath()).st_mode))
                    throw new IOException("Unexpected capture artifact path");
                String sha = sha256(file);
                if (!file.getName().equals(sha + ".dex")) throw new IOException("Artifact digest differs from filename");
                JSONObject artifact = new JSONObject();
                artifact.put("name", file.getName());
                artifact.put("bytes", file.length());
                artifact.put("sha256", sha);
                artifacts.put(artifact);
            } catch (Exception invalid) {
                if (error == null) throw invalid;
                invalidArtifacts.put(file.getName() + ": " + invalid);
            }
        }
        if (invalidArtifacts.length() > 0) report.put("invalidArtifacts", invalidArtifacts);
        JSONArray services = new JSONArray();
        File[] serviceFiles = new File(directory, "services").listFiles((dir, name) -> name.endsWith(".json"));
        if (serviceFiles != null) {
            Arrays.sort(serviceFiles, Comparator.comparing(File::getName));
            for (File serviceFile : serviceFiles) {
                JSONObject service = new JSONObject(new String(new AtomicFile(serviceFile).readFully(), StandardCharsets.UTF_8));
                services.put(service);
                if (error == null && !"started".equals(service.optString("status")))
                    throw new IOException("Requested service did not complete startup: " + service.optString("className") + " (" + service.optString("status") + ")");
            }
        }
        report.put("services", services);
        if (error == null && (files.length == 0 || !report.optBoolean("applicationOnCreate") || !report.optBoolean("activityOnCreate")))
            throw new IOException("Capture lacks lifecycle or validated artifact evidence");
        report.put("artifacts", artifacts);
        report.put("status", error == null ? "verified" : "failed");
        report.put("phase", error == null ? "complete" : "failed");
        report.put("finishedAt", System.currentTimeMillis());
        report.put("nativeValidation", error == null ? "layout-calibration, original-dex-structure-and-checksums, bundled-parser, atomic-publication-and-byte-readback" : "incomplete");
        if (error != null) report.put("error", error);
        writeJson(directory, report);
        }
    }

    public static synchronized void activity(File directory, String className, boolean resumed) throws Exception {
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, ".session-lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            JSONObject report = readLocked(directory);
            if (!"running".equals(report.optString("status"))) return;
            JSONArray activities = report.optJSONArray("activities");
            if (activities == null) activities = new JSONArray();
            JSONObject activity = null;
            for (int i = 0; i < activities.length(); i++) {
                if (className.equals(activities.getJSONObject(i).optString("className"))) activity = activities.getJSONObject(i);
            }
            if (activity == null) {
                activity = new JSONObject().put("className", className).put("pid", Process.myPid());
                activities.put(activity);
            }
            activity.put(resumed ? "onResume" : "onCreate", true);
            if (resumed) activity.put("resumeCount", activity.optInt("resumeCount") + 1);
            report.put("activities", activities);
            writeJson(directory, report);
        }
    }

    /** Separate per-service evidence; a worker never changes the main lifecycle flags. */
    public static synchronized void service(File directory, ServiceInfo info, String phase, int pid, String error) throws Exception {
        if (directory == null) throw new IOException("Missing service capture session");
        try (RandomAccessFile lockFile = new RandomAccessFile(new File(directory, ".session-lock"), "rw");
             FileLock ignored = lockFile.getChannel().lock()) {
            if (!"running".equals(readLocked(directory).optString("status"))) return;
            File serviceDirectory = new File(directory, "services");
            if (!serviceDirectory.isDirectory() && !serviceDirectory.mkdirs()) throw new IOException("Cannot record service lifecycle");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] key = digest.digest((info.packageName + "/" + info.name + ":" + info.processName).getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : key) hex.append(String.format(Locale.ROOT, "%02x", b & 255));
            AtomicFile file = new AtomicFile(new File(serviceDirectory, hex + ".json"));
            JSONObject service = file.getBaseFile().isFile()
                    ? new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8)) : new JSONObject();
            service.put("className", info.name).put("packageName", info.packageName).put("processName", info.processName);
            service.put("pid", pid).put("updatedAt", System.currentTimeMillis());
            if ("created".equals(phase)) service.put("onCreate", true);
            if ("started".equals(phase)) service.put("onStartCommand", true);
            service.put("status", error != null ? "failed" : "started".equals(phase) ? "started" : "pending");
            if (error != null) service.put("error", error);
            write(file, service.toString(2).getBytes(StandardCharsets.UTF_8));
        }
    }

    public static void notice(String packageName, File directory, String phase) {
        DumpResult result = new DumpResult();
        result.packageName = packageName;
        result.dir = directory.getAbsolutePath();
        result.msg = phase;
        BlackBoxCore.getBDumpManager().noticeMonitor(result.dumpProcess(0, 0));
    }

    public static String sha256(File file) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (FileInputStream input = new FileInputStream(file)) {
            byte[] buffer = new byte[16384];
            int count;
            while ((count = input.read(buffer)) != -1) digest.update(buffer, 0, count);
        }
        StringBuilder result = new StringBuilder();
        for (byte b : digest.digest()) result.append(String.format(Locale.ROOT, "%02x", b & 0xff));
        return result.toString();
    }

    private static void writeJson(File directory, JSONObject report) throws Exception {
        write(new AtomicFile(new File(directory, REPORT)), report.toString(2).getBytes(StandardCharsets.UTF_8));
    }

    private static void write(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream output = file.startWrite();
        try {
            output.write(bytes);
            output.getFD().sync();
            file.finishWrite(output);
        } catch (IOException e) {
            file.failWrite(output);
            throw e;
        }
        // AtomicFile logs rename failure instead of throwing. All callers
        // hold their publication lock, so readback is safe and definitive.
        if (!Arrays.equals(bytes, file.readFully()))
            throw new IOException("Atomic capture record readback differs from write: " + file.getBaseFile());
    }
}

package com.jacklee.haku.artspike;

import android.app.Activity;
import android.os.Build;
import android.os.Bundle;
import android.widget.TextView;
import dalvik.system.DexFile;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import org.json.*;

/** Retains only controlled, app-owned DEX files; this never hooks or reads another application. */
public final class SpikeActivity extends Activity {
  static {
    System.loadLibrary("haku_art_spike");
  }

  private static native String nativeCapture(DexFile file, byte[] expected, String directory);

  private final ArrayList<DexFile> retained = new ArrayList<>();
  private final ArrayList<ClassLoader> retainedLoaders = new ArrayList<>();

  static String hash(byte[] bytes) throws Exception {
    StringBuilder result = new StringBuilder();
    for (byte value : MessageDigest.getInstance("SHA-256").digest(bytes))
      result.append(String.format(Locale.ROOT, "%02x", value & 255));
    return result.toString();
  }

  byte[] asset(String name) throws Exception {
    try (InputStream input = getAssets().open(name);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] chunk = new byte[8192];
      int n;
      while ((n = input.read(chunk)) != -1) output.write(chunk, 0, n);
      byte[] result = output.toByteArray();
      for (int i = 0; i < result.length; i++) result[i] ^= (byte) 0xA7;
      return result;
    }
  }

  JSONObject one(String name, String marker, byte[] bytes, File output) throws Exception {
    File source = new File(getFilesDir(), name + ".dex");
    try (FileOutputStream stream = new FileOutputStream(source)) {
      stream.write(bytes);
      stream.getFD().sync();
    }
    DexFile dex = new DexFile(source.getAbsolutePath());
    retained.add(dex);
    ClassLoader isolated = new ClassLoader(getClassLoader()) {};
    retainedLoaders.add(isolated);
    Class<?> code = dex.loadClass("fixture.Controlled", isolated);
    if (code == null) throw new AssertionError("controlled class unavailable");
    String observed = (String) code.getMethod("marker").invoke(null);
    boolean reflected = marker.equals(observed);
    JSONObject nativeResult = new JSONObject(nativeCapture(dex, bytes, output.getAbsolutePath()));
    nativeResult
        .put("fixture", name)
        .put("expectedSha256", hash(bytes))
        .put("expectedBytes", bytes.length)
        .put("expectedMarker", marker)
        .put("observedMarker", observed)
        .put("reflectionPassed", reflected);
    File artifact = new File(output, hash(bytes) + ".dex");
    nativeResult.put("artifactExists", artifact.isFile());
    // Retain the native diagnosis before attempting any artifact read. A failed
    // native capture is already a failed case, even if a stale file exists.
    if (!nativeResult.optBoolean("passed")) {
      nativeResult.put("javaArtifactReadbackExact", false);
      nativeResult.put("javaArtifactReadbackSkipped", "native capture failed");
      return nativeResult;
    }
    try {
      byte[] readback;
      try (InputStream input = new FileInputStream(artifact);
          ByteArrayOutputStream stream = new ByteArrayOutputStream()) {
        byte[] chunk = new byte[8192];
        int n;
        while ((n = input.read(chunk)) != -1) stream.write(chunk, 0, n);
        readback = stream.toByteArray();
      }
      boolean exact = Arrays.equals(bytes, readback) && hash(bytes).equals(hash(readback));
      nativeResult.put("javaArtifactReadbackExact", exact);
      nativeResult.put("passed", reflected && exact);
    } catch (Throwable failure) {
      nativeResult.put("javaArtifactReadbackExact", false);
      nativeResult.put("javaArtifactReadbackError", failure.toString());
      nativeResult.put("passed", false);
    }
    return nativeResult;
  }

  void run(TextView text) {
    JSONObject report = new JSONObject();
    JSONArray cases = new JSONArray();
    try {
      report
          .put("schema", "haku.art.spike.v1")
          .put("sdk", Build.VERSION.SDK_INT)
          .put("release", Build.VERSION.RELEASE)
          .put("abis", new JSONArray(Arrays.asList(Build.SUPPORTED_ABIS)))
          .put("pid", android.os.Process.myPid())
          .put(
              "scope",
              "Owned DexFile cookie/layout/read/write calibration only; excludes LoadMethod hook"
                  + " and BlackBox container.");
      if (Build.VERSION.SDK_INT != 33 || !android.os.Process.is64Bit())
        throw new UnsupportedOperationException("requires API33 arm64 process");
      byte[] first = asset("main.xor"), second = asset("distinct.xor");
      if (first.length <= 8192 || first.length != second.length || Arrays.equals(first, second))
        throw new AssertionError("fixture size/identity contract");
      File output = new File(getFilesDir(), "captured");
      if (!output.mkdirs() && !output.isDirectory()) throw new IOException("capture_directory");
      cases.put(one("main", "haku_dynamic_dex_ok_v1", first, output));
      cases.put(one("distinct", "haku_changed_dex_ok_v1", second, output));
      boolean passed = true;
      for (int i = 0; i < cases.length(); i++)
        passed &= cases.getJSONObject(i).optBoolean("passed");
      File mainArtifact = new File(output, hash(first) + ".dex"),
          distinctArtifact = new File(output, hash(second) + ".dex");
      boolean separate =
          mainArtifact.isFile()
              && distinctArtifact.isFile()
              && !mainArtifact.equals(distinctArtifact);
      report
          .put("cases", cases)
          .put("equalLengthDistinctArtifacts", separate)
          .put("retainedDexFiles", retained.size())
          .put("passed", passed && separate);
    } catch (Throwable failure) {
      try {
        report.put("cases", cases).put("passed", false).put("error", failure.toString());
      } catch (Exception ignored) {
      }
    }
    try {
      report.put("time", System.currentTimeMillis());
      File target = new File(getFilesDir(), "spike-result.json");
      try (FileOutputStream stream = new FileOutputStream(target)) {
        stream.write(report.toString(2).getBytes("UTF-8"));
        stream.getFD().sync();
      }
    } catch (Exception ignored) {
    }
    final String result = report.toString();
    runOnUiThread(() -> text.setText(result));
  }

  public void onCreate(Bundle state) {
    super.onCreate(state);
    TextView text = new TextView(this);
    text.setText("Running owned ART API33 cookie/layout calibration…");
    text.setPadding(24, 48, 24, 24);
    setContentView(text);
    new Thread(() -> run(text), "haku-art-spike").start();
  }
}

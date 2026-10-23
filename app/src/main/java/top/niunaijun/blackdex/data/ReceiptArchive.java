package top.niunaijun.blackdex.data;

import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.zip.*;

/** Android-free byte verification, shared with host JVM regression tests. */
public final class ReceiptArchive {
    public static final class Entry {
        public final String name, sha256;
        public final long bytes;
        public Entry(String name, long bytes, String sha256) {
            this.name = name; this.bytes = bytes; this.sha256 = sha256;
        }
    }
    private ReceiptArchive() {}
    public static List<Entry> inventory(File directory) throws IOException {
        List<Entry> result = new ArrayList<>();
        collect(directory.getCanonicalFile(), directory.getCanonicalFile(), result);
        Collections.sort(result, (a,b) -> a.name.compareTo(b.name));
        if (result.isEmpty()) throw new IOException("Session has no artifacts");
        return result;
    }
    private static void collect(File root, File directory, List<Entry> result) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Cannot read session directory");
        for (File file : children) {
            // Do not follow links, consume partially published files, or leave the session.
            if (!file.getCanonicalFile().equals(file.getAbsoluteFile())) throw new IOException("Linked session file rejected");
            if (file.isDirectory()) collect(root, file, result);
            else if (file.isFile()) {
                if (file.getName().endsWith(".tmp") || file.getName().endsWith(".partial"))
                    throw new IOException("Session still contains unpublished files");
                String name = file.getCanonicalPath().substring(root.getPath().length() + 1).replace(File.separatorChar, '/');
                result.add(new Entry(name, file.length(), digest(file)));
            }
        }
    }
    public static void create(File session, File destination, List<Entry> entries) throws IOException {
        if (destination.exists()) throw new IOException("Export already exists");
        File temporary = new File(destination.getPath() + ".partial");
        try {
            try (ZipOutputStream zip = new ZipOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
                byte[] buffer = new byte[65536];
                for (Entry entry : entries) {
                    File source = new File(session, entry.name);
                    if (!source.getCanonicalPath().startsWith(session.getCanonicalPath() + File.separator))
                        throw new IOException("Unsafe artifact path");
                    zip.putNextEntry(new ZipEntry(entry.name));
                    try (InputStream in = new BufferedInputStream(new FileInputStream(source))) {
                        int n; while ((n = in.read(buffer)) != -1) zip.write(buffer, 0, n);
                    }
                    zip.closeEntry();
                }
            }
            verify(temporary, entries);
            if (!temporary.renameTo(destination)) throw new IOException("Could not publish verified ZIP");
        } finally { if (temporary.exists()) temporary.delete(); }
    }
    public static void verify(File archive, List<Entry> expected) throws IOException {
        Map<String,Entry> remaining = new HashMap<>();
        for (Entry entry : expected) {
            if (!safeName(entry.name) || remaining.put(entry.name, entry) != null)
                throw new IOException("Unsafe or duplicate receipt entry");
        }
        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> all = zip.entries();
            while (all.hasMoreElements()) {
                ZipEntry item = all.nextElement(); Entry entry = remaining.remove(item.getName());
                if (entry == null || item.isDirectory()) throw new IOException("Unexpected or duplicate ZIP entry");
                MessageDigest digest = sha256(); long bytes = 0; byte[] buffer = new byte[65536];
                try (InputStream in = zip.getInputStream(item)) {
                    int n; while ((n = in.read(buffer)) != -1) { bytes += n; digest.update(buffer, 0, n); }
                }
                if (bytes != entry.bytes || !hex(digest.digest()).equals(entry.sha256))
                    throw new IOException("ZIP readback mismatch: " + entry.name);
            }
        }
        if (!remaining.isEmpty()) throw new IOException("ZIP is missing receipt entries");
    }
    public static boolean safeName(String name) {
        return name != null && !name.isEmpty() && !name.startsWith("/") && !name.contains("\\")
            && !Arrays.asList(name.split("/", -1)).contains("..") && !Arrays.asList(name.split("/", -1)).contains("");
    }
    public static String digest(File file) throws IOException {
        MessageDigest digest = sha256(); byte[] buffer = new byte[65536];
        try (InputStream in = new BufferedInputStream(new FileInputStream(file))) {
            int n; while ((n = in.read(buffer)) != -1) digest.update(buffer, 0, n);
        }
        return hex(digest.digest());
    }
    private static MessageDigest sha256() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (Exception impossible) { throw new AssertionError(impossible); }
    }
    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) out.append(String.format(Locale.US, "%02x", b & 255));
        return out.toString();
    }
}

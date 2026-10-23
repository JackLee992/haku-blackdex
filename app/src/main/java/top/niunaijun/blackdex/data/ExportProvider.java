package top.niunaijun.blackdex.data;

import android.content.*;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.List;

/** A read-only content file provider. Only the two published files receive URI grants. */
public final class ExportProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    private File resolve(Uri uri) throws FileNotFoundException {
        List<String> path = uri.getPathSegments();
        if (path.size() != 2 || !path.get(0).matches("[A-Za-z0-9._-]+") ||
            !(path.get(1).equals("export.zip") || path.get(1).equals("report.json")))
            throw new FileNotFoundException("Unknown export");
        File root = new File(getContext().getFilesDir(), "haku-exports");
        File file = new File(new File(root, path.get(0)), path.get(1));
        try {
            if (!file.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator)
                || !file.getAbsoluteFile().equals(file.getCanonicalFile()) || !file.isFile())
                throw new FileNotFoundException("Export unavailable");
        } catch (IOException e) { throw new FileNotFoundException("Export unavailable"); }
        return file;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new FileNotFoundException("Read-only export");
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public String getType(Uri uri) { return "export.zip".equals(uri.getLastPathSegment()) ? "application/zip" : "application/json"; }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) {
        try {
            File file = resolve(uri);
            String[] columns = projection == null ? new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE} : projection;
            MatrixCursor cursor = new MatrixCursor(columns); Object[] row = new Object[columns.length];
            for (int i=0;i<columns.length;i++) {
                if (OpenableColumns.DISPLAY_NAME.equals(columns[i])) row[i]=file.getName();
                if (OpenableColumns.SIZE.equals(columns[i])) row[i]=file.length();
            }
            cursor.addRow(row); return cursor;
        } catch (FileNotFoundException e) { return null; }
    }
    @Override public Uri insert(Uri u, ContentValues v) { throw new UnsupportedOperationException("Read only"); }
    @Override public int update(Uri u, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException("Read only"); }
    @Override public int delete(Uri u, String s, String[] a) { throw new UnsupportedOperationException("Read only"); }
}

package com.construct.messenger.share;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.IOException;

/** Test APK only: generated canaries, never real files or account data. No target-APK runtime. */
public class ShareFixtureProvider extends ContentProvider {
    @Override public boolean onCreate() { return true; }
    @Override public String getType(Uri uri) {
        String kind = uri.getLastPathSegment();
        return "photo".equals(kind) ? "image/png" : "video".equals(kind) ? "video/mp4" : "pdf".equals(kind) ? "application/pdf" : "application/octet-stream";
    }
    private File source(Uri uri) {
        String kind = uri.getLastPathSegment();
        if (!"bytes".equals(kind) && !"photo".equals(kind) && !"pdf".equals(kind) && !"video".equals(kind)) throw new IllegalArgumentException();
        File out = new File(getContext().getCacheDir(), "share-fixture-" + kind);
        try (FileOutputStream output = new FileOutputStream(out)) {
            if ("photo".equals(kind)) {
                Bitmap bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(0xff3567ab);
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
                bitmap.recycle();
            } else if ("video".equals(kind)) {
                try (InputStream input = getContext().getAssets().open("share-canary.mp4")) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) >= 0) output.write(buffer, 0, count);
                }
            } else if ("pdf".equals(kind)) {
                output.write("%PDF-1.4\nSHARE-CANARY-DOCUMENT\n%%EOF\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } else {
                String raw = uri.getQueryParameter("bytes");
                int size = Math.max(0, Math.min(16 * 1024 * 1024, raw == null ? 4096 : Integer.parseInt(raw)));
                byte[] bytes = new byte[size];
                for (int i = 0; i < size; i++) bytes[i] = (byte) (i % 251);
                output.write(bytes);
            }
        } catch (IOException e) { throw new IllegalStateException(e); }
        return out;
    }
    @Override public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File file = source(uri);
        String kind = uri.getLastPathSegment();
        String extension = "photo".equals(kind) ? "png" : "video".equals(kind) ? "mp4" : "pdf".equals(kind) ? "pdf" : "bin";
        String name = uri.getQueryParameter("name");
        String declared = uri.getQueryParameter("declared");
        MatrixCursor cursor = new MatrixCursor(new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE});
        cursor.addRow(new Object[]{name == null ? "share-canary." + extension : name, declared == null ? file.length() : Long.parseLong(declared)});
        return cursor;
    }
    @Override public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(source(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }
    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
}

package com.onurbaykal.zipzapzop;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileNotFoundException;

// Hands the result picture to the app the player shares to. It serves exactly one file, cache/share/zipzapzop.png,
// read-only, and only to apps the share intent grants. A few lines of our own instead of androidx FileProvider,
// so the local Gradle-free build has it too.
public class ShareProvider extends ContentProvider {
    static final String NAME = "zipzapzop.png";

    static String authority(Context c) { return c.getPackageName() + ".share"; }

    static File file(Context c) {
        File dir = new File(c.getCacheDir(), "share");
        dir.mkdirs();
        return new File(dir, NAME);
    }

    static Uri uri(Context c) { return Uri.parse("content://" + authority(c) + "/" + NAME); }

    private File match(Uri uri) throws FileNotFoundException {
        if (!NAME.equals(uri.getLastPathSegment())) throw new FileNotFoundException(uri.toString());
        File f = file(getContext());
        if (!f.exists()) throw new FileNotFoundException(uri.toString());
        return f;
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) { return "image/png"; }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        if (!"r".equals(mode)) throw new SecurityException("read only");
        return ParcelFileDescriptor.open(match(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    // Some apps ask for the name and size before reading.
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] args, String sort) {
        File f;
        try { f = match(uri); } catch (FileNotFoundException e) { return null; }
        if (projection == null) projection = new String[] { OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE };
        MatrixCursor c = new MatrixCursor(projection, 1);
        Object[] row = new Object[projection.length];
        for (int i = 0; i < projection.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(projection[i])) row[i] = NAME;
            else if (OpenableColumns.SIZE.equals(projection[i])) row[i] = f.length();
        }
        c.addRow(row);
        return c;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
    @Override public int delete(Uri uri, String s, String[] a) { throw new UnsupportedOperationException(); }
    @Override public int update(Uri uri, ContentValues v, String s, String[] a) { throw new UnsupportedOperationException(); }
}

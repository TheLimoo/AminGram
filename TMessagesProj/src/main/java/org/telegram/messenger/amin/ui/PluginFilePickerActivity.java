package org.telegram.messenger.amin.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;

import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.amin.plugins.js.PluginFiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * File-picker trampoline for {@code amin.pickFile} (Platform v2
 * Phase B, §1.6) — the same pattern as GoogleAuthActivity: plugin
 * code runs on the plugin thread with no Activity of its own, so
 * this transparent Activity exists only to host the system
 * document picker (SAF, ACTION_OPEN_DOCUMENT — no broad storage
 * permission is ever requested) and hand the result back through
 * a static pending-callback registry.
 *
 * With copy:true the picked file is copied into the plugin's
 * durable sandbox (the returned path survives restarts); with
 * copy:false it is staged under the cache dir and the returned
 * path is valid for this session. Cancellation or any failure
 * delivers null. Process death while the picker is open loses
 * the pending callback — the documented bound, identical to
 * Google sign-in's: the user simply retries.
 */
public class PluginFilePickerActivity extends Activity {

    public interface PickCallback {
        void onPicked(String pathOrNull);
    }

    private static final int RC_PICK = 4711;
    private static final Map<Integer, Pending> PENDING = new ConcurrentHashMap<>();
    private static final AtomicInteger NEXT_ID = new AtomicInteger(1);

    private static final class Pending {
        String pluginId;
        boolean copy;
        PickCallback callback;
    }

    private int requestId = -1;
    private boolean launched;

    public static void request(Context context, String accept, boolean copy,
                               String pluginId, PickCallback callback) {
        int id = NEXT_ID.getAndIncrement();
        Pending pending = new Pending();
        pending.pluginId = pluginId;
        pending.copy = copy;
        pending.callback = callback;
        PENDING.put(id, pending);
        Intent intent = new Intent(context, PluginFilePickerActivity.class);
        intent.putExtra("amin_req", id);
        intent.putExtra("amin_accept", accept == null || accept.isEmpty() ? "*/*" : accept);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestId = getIntent() != null ? getIntent().getIntExtra("amin_req", -1) : -1;
        if (PENDING.get(requestId) == null) {
            finish();
            return;
        }
        if (savedInstanceState == null && !launched) {
            launched = true;
            String accept = getIntent().getStringExtra("amin_accept");
            Intent pick = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            pick.addCategory(Intent.CATEGORY_OPENABLE);
            pick.setType(accept == null || accept.isEmpty() ? "*/*" : accept);
            try {
                startActivityForResult(pick, RC_PICK);
            } catch (Throwable t) {
                deliver(null);
                finish();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != RC_PICK) {
            return;
        }
        final Pending pending = PENDING.remove(requestId);
        if (pending == null) {
            finish();
            return;
        }
        Uri uri = (resultCode == RESULT_OK && data != null) ? data.getData() : null;
        if (uri == null) {
            pending.callback.onPicked(null);
            finish();
            return;
        }
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Throwable ignored) {
        }
        // Copy off the UI thread; the callback fires when done.
        final Uri picked = uri;
        new Thread(() -> {
            String path = copyPicked(picked, pending);
            pending.callback.onPicked(path);
        }, "AminPluginPickCopy").start();
        finish();
    }

    private void deliver(String path) {
        Pending pending = PENDING.remove(requestId);
        if (pending != null) {
            pending.callback.onPicked(path);
        }
    }

    /** Copies the picked document to its destination and returns
     *  the path, or null on any failure. */
    private String copyPicked(Uri uri, Pending pending) {
        Context context = ApplicationLoader.applicationContext;
        if (context == null) {
            context = this;
        }
        String name = "picked-file";
        long size = -1;
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) {
                    String display = cursor.getString(nameIdx);
                    if (display != null && !display.isEmpty()) {
                        name = display.replaceAll("[\\\\/]", "_");
                    }
                }
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) {
                    size = cursor.getLong(sizeIdx);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        if (name.contains("..") || name.isEmpty()) {
            name = "picked-file";
        }
        InputStream in = null;
        try {
            in = getContentResolver().openInputStream(uri);
            if (in == null) {
                return null;
            }
            if (pending.copy) {
                if (!PluginFiles.validName(name)) {
                    name = "picked-file";
                }
                return PluginFiles.copyInto(context, pending.pluginId, name, in, size);
            }
            // Session staging under the cache dir.
            File dir = new File(context.getCacheDir(), "jsplugins-pick/" + pending.pluginId);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            File out = new File(dir, name);
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(out, false);
                byte[] buffer = new byte[8192];
                int n;
                while ((n = in.read(buffer)) > 0) {
                    fos.write(buffer, 0, n);
                }
                fos.close();
                return out.getAbsolutePath();
            } finally {
                if (fos != null) {
                    try {
                        fos.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        } catch (Throwable t) {
            return null;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}

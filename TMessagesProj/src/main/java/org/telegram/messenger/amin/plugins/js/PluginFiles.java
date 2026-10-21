package org.telegram.messenger.amin.plugins.js;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Sandboxed per-plugin file storage (Platform v2 Phase B, §1.6):
 * the backing helpers for {@code amin.files} and for the file
 * picker's durable copies. Root: {@code filesDir/jsplugins-data/<id>/}.
 * Names are BARE file names — any path separator, ".." or empty
 * name is rejected natively, so a plugin can never escape its
 * sandbox. Per-plugin quota is 50 MB of file content; writes that
 * would exceed it fail cleanly (false), never partially.
 */
public final class PluginFiles {

    public static final long QUOTA_BYTES = 50L * 1024 * 1024;

    private PluginFiles() {
    }

    public static File dir(Context context, String pluginId) {
        File root = new File(context.getFilesDir(), "jsplugins-data");
        File dir = new File(root, pluginId);
        if (!dir.exists()) {
            dir.mkdirs();
        }
        return dir;
    }

    /** True when name is a bare, sandbox-safe file name. */
    public static boolean validName(String name) {
        return name != null && !name.isEmpty() && name.length() <= 128
                && !name.contains("/") && !name.contains("\\")
                && !name.contains("..") && name.indexOf('\0') < 0;
    }

    static File file(Context context, String pluginId, String name) {
        if (!validName(name)) {
            return null;
        }
        return new File(dir(context, pluginId), name);
    }

    /** Total bytes currently stored for the plugin. */
    public static long usedBytes(Context context, String pluginId) {
        long total = 0;
        File[] files = dir(context, pluginId).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    total += f.length();
                }
            }
        }
        return total;
    }

    public static String readText(Context context, String pluginId, String name) {
        File file = file(context, pluginId, name);
        if (file == null || !file.isFile()) {
            return null;
        }
        FileInputStream in = null;
        try {
            in = new FileInputStream(file);
            byte[] data = new byte[(int) Math.min(file.length(), Integer.MAX_VALUE - 8)];
            int read = 0;
            while (read < data.length) {
                int n = in.read(data, read, data.length - read);
                if (n < 0) {
                    break;
                }
                read += n;
            }
            return new String(data, 0, read, "UTF-8");
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

    /** Writes text (UTF-8) under the quota; false on any failure. */
    public static boolean writeText(Context context, String pluginId, String name, String text) {
        File file = file(context, pluginId, name);
        if (file == null || text == null) {
            return false;
        }
        byte[] bytes;
        try {
            bytes = text.getBytes("UTF-8");
        } catch (Throwable t) {
            return false;
        }
        long existing = file.isFile() ? file.length() : 0;
        if (usedBytes(context, pluginId) - existing + bytes.length > QUOTA_BYTES) {
            return false;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, false);
            out.write(bytes);
            out.close();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    public static boolean exists(Context context, String pluginId, String name) {
        File file = file(context, pluginId, name);
        return file != null && file.isFile();
    }

    public static boolean remove(Context context, String pluginId, String name) {
        File file = file(context, pluginId, name);
        return file != null && file.isFile() && file.delete();
    }

    /** [{name, sizeBytes, modifiedAt}] for every stored file. */
    public static List<FileInfo> list(Context context, String pluginId) {
        List<FileInfo> out = new ArrayList<>();
        File[] files = dir(context, pluginId).listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isFile()) {
                    out.add(new FileInfo(f.getName(), f.length(), f.lastModified()));
                }
            }
        }
        out.sort((a, b) -> a.name.compareTo(b.name));
        return out;
    }

    /** Copies a stream's content into the sandbox under name,
     *  honouring the quota; returns the absolute path or null.
     *  Used by the file picker (durable copy) — the caller owns
     *  and closes the stream. */
    public static String copyInto(Context context, String pluginId, String name,
                                  java.io.InputStream in, long declaredSize) {
        File file = file(context, pluginId, name);
        if (file == null || in == null) {
            return null;
        }
        long existing = file.isFile() ? file.length() : 0;
        long room = QUOTA_BYTES - (usedBytes(context, pluginId) - existing);
        if (declaredSize > 0 && declaredSize > room) {
            return null;
        }
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, false);
            byte[] buffer = new byte[8192];
            long total = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                total += n;
                if (total > room) {
                    out.close();
                    file.delete();
                    return null;
                }
                out.write(buffer, 0, n);
            }
            out.close();
            return file.getAbsolutePath();
        } catch (Throwable t) {
            try {
                if (file.exists()) {
                    file.delete();
                }
            } catch (Throwable ignored) {
            }
            return null;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /** Drops the plugin's whole sandbox (true delete). */
    public static void deleteAllForPlugin(Context context, String pluginId) {
        File dir = new File(new File(context.getFilesDir(), "jsplugins-data"), pluginId);
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                f.delete();
            }
        }
        dir.delete();
    }

    public static final class FileInfo {
        public final String name;
        public final long sizeBytes;
        public final long modifiedAt;

        FileInfo(String name, long sizeBytes, long modifiedAt) {
            this.name = name;
            this.sizeBytes = sizeBytes;
            this.modifiedAt = modifiedAt;
        }
    }
}

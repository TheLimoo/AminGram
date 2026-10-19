package org.telegram.messenger.amin.plugins.js;

import android.content.Context;
import android.content.SharedPreferences;
import android.widget.Toast;

import org.mozilla.javascript.Scriptable;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.SendMessagesHelper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * The `amin` object exposed to JS plugins — the ONLY bridge between a
 * plugin and the app, exactly as documented in
 * docs/PLUGIN-MASTER-PROMPT.md:
 *
 *   amin.log(text)                       plugin log (hub → plugin → Log)
 *   amin.toast(text)                     small on-screen popup
 *   amin.storage.get(key, defaultValue)  per-plugin saved values
 *   amin.storage.set(key, value)
 *   amin.send(chatId, text)              send a message AS THE USER
 *   amin.reply(msg, text)                send into the msg's chat
 *   amin.httpGet(url)                    response text, or null (blocking)
 *
 * The Rhino ClassShutter in JsPluginManager whitelists ONLY this class
 * (and its Storage class), so plugin code cannot reach any other Java
 * class. Instances are per-plugin; sends act on the account whose
 * update is currently being dispatched.
 */
public class AminBridge {

    private final String pluginId;
    public final Storage storage;

    public AminBridge(String pluginId) {
        this.pluginId = pluginId;
        this.storage = new Storage(pluginId);
    }

    public void log(Object text) {
        JsPluginManager.appendLog(pluginId, text == null ? "null" : String.valueOf(text));
    }

    public void toast(final Object text) {
        final String value = text == null ? "" : String.valueOf(text);
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Context context = ApplicationLoader.applicationContext;
                if (context != null) {
                    Toast.makeText(context, value, Toast.LENGTH_SHORT).show();
                }
            } catch (Throwable ignored) {
            }
        });
    }

    public void send(Object chatId, Object text) {
        if (chatId == null || text == null) {
            return;
        }
        long peer = toLong(chatId);
        if (peer == 0) {
            return;
        }
        try {
            SendMessagesHelper.getInstance(JsPluginManager.dispatchAccount())
                    .sendMessage(SendMessagesHelper.SendMessageParams.of(String.valueOf(text), peer));
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.send failed: " + t);
        }
    }

    public void reply(Object msg, Object text) {
        if (msg == null || text == null) {
            return;
        }
        long chatId = 0;
        if (msg instanceof Scriptable) {
            Object value = ((Scriptable) msg).get("chatId", (Scriptable) msg);
            if (value != null && value != Scriptable.NOT_FOUND) {
                chatId = toLong(value);
            }
        } else if (msg instanceof Number) {
            chatId = ((Number) msg).longValue();
        }
        if (chatId != 0) {
            send(chatId, text);
        }
    }

    /** Blocking GET on the plugin dispatch thread. Returns the body
     *  text (capped at 1 MB), or null on any failure. */
    public String httpGet(Object urlObject) {
        if (urlObject == null) {
            return null;
        }
        HttpURLConnection connection = null;
        try {
            URL url = new URL(String.valueOf(urlObject));
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(15000);
            connection.setReadTimeout(15000);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("User-Agent",
                    "Mozilla/5.0 (Linux; Android 13) AminGram");
            connection.setRequestProperty("Accept", "*/*");
            int code = connection.getResponseCode();
            if (code < 200 || code >= 400) {
                return null;
            }
            InputStream in = connection.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            int total = 0;
            while ((read = in.read(buffer)) > 0) {
                total += read;
                if (total > 1024 * 1024) {
                    break;
                }
                out.write(buffer, 0, read);
            }
            in.close();
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            JsPluginManager.appendLog(pluginId, "[error] amin.httpGet failed: " + t);
            return null;
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    static long toLong(Object value) {
        if (value instanceof Number) {
            return ((Number) value).longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (Exception e) {
            return 0;
        }
    }

    /** Per-plugin key/value storage, backed by its own
     *  SharedPreferences file ("amingram_jsstore_<id>"). Values are
     *  strings under the hood; get() converts to the type of the
     *  provided default (bool/number/string), as the master prompt
     *  documents string/number/bool values. */
    public static class Storage {

        private final String prefsName;

        public Storage(String pluginId) {
            this.prefsName = JsPluginManager.storagePrefsName(pluginId);
        }

        public Object get(Object key, Object defaultValue) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || key == null) {
                return defaultValue;
            }
            String stored = prefs(context).getString(String.valueOf(key), null);
            if (stored == null) {
                return defaultValue;
            }
            if (defaultValue instanceof Boolean) {
                return Boolean.valueOf(stored);
            }
            if (defaultValue instanceof Number) {
                try {
                    return Double.valueOf(stored);
                } catch (Exception e) {
                    return defaultValue;
                }
            }
            return stored;
        }

        public void set(Object key, Object value) {
            Context context = ApplicationLoader.applicationContext;
            if (context == null || key == null) {
                return;
            }
            SharedPreferences.Editor edit = prefs(context).edit();
            if (value == null) {
                edit.remove(String.valueOf(key));
            } else if (value instanceof Boolean) {
                edit.putString(String.valueOf(key), ((Boolean) value) ? "true" : "false");
            } else if (value instanceof Number) {
                double d = ((Number) value).doubleValue();
                if (d == Math.floor(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                    edit.putString(String.valueOf(key), String.valueOf((long) d));
                } else {
                    edit.putString(String.valueOf(key), String.valueOf(d));
                }
            } else {
                edit.putString(String.valueOf(key), String.valueOf(value));
            }
            edit.apply();
        }

        private SharedPreferences prefs(Context context) {
            return context.getSharedPreferences(prefsName, Context.MODE_PRIVATE);
        }
    }
}

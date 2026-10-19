package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.core.SendWhenOnlineStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class SendWhenOnlineModule implements ModModule {
    @Override public String id() { return "send_when_online"; }
    @Override public String title() { return "Send When Online"; }
    @Override public String description() {
        return "Write a message now and AminGram sends it automatically the moment that person comes online. Works whenever Telegram syncs, even if you are not in the app.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_TOOLS; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        int pending = SendWhenOnlineStore.pendingCount(context);
        return pending > 0 ? pending + " waiting to send" : "Active · nothing queued";
    }
}

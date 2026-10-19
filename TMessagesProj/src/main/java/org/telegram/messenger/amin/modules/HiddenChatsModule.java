package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.core.HiddenChatsStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class HiddenChatsModule implements ModModule {
    @Override public String id() { return "hidden_chats"; }
    @Override public String title() { return "Hidden Chats"; }
    @Override public String description() {
        return "Hide chosen chats from the chat list behind a PIN. Unlock below to see and manage them; locking again (or restarting the app) hides them.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_PRIVACY; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        int n = HiddenChatsStore.getHiddenIds(context).size();
        return (HiddenChatsStore.isUnlocked() ? "Unlocked" : "Locked") + " · " + n + " hidden";
    }
}

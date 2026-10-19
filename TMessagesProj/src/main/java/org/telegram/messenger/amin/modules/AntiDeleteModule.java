package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.core.DeletedStore;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class AntiDeleteModule implements ModModule {
    @Override public String id() { return "anti_delete"; }
    @Override public String title() { return "Anti-Delete"; }
    @Override public String description() {
        return "Keeps a copy of messages you receive. If someone deletes a message for everyone, you can still read it under “Deleted messages” below.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_PRIVACY; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        int kept = VaultStore.getAll(context, DeletedStore.PREFS, DeletedStore.DELETED_KEY).size();
        return kept > 0 ? "Active · " + kept + " deleted kept" : "Active";
    }
}

package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.core.DeletedStore;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

public class EditHistoryModule implements ModModule {
    @Override public String id() { return "edit_history"; }
    @Override public String title() { return "Edit History"; }
    @Override public String description() {
        return "Remembers what a message said before it was edited. When someone edits a message, the old → new text is saved under “Edit history” below.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_PRIVACY; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        int edits = VaultStore.getAll(context, DeletedStore.PREFS, DeletedStore.EDIT_KEY).size();
        return edits > 0 ? "Active · " + edits + " edits recorded" : "Active";
    }
}

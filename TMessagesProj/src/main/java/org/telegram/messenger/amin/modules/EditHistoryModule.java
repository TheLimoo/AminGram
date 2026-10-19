package org.telegram.messenger.amin.modules;

import org.telegram.messenger.amin.plugins.ModModule;

public class EditHistoryModule implements ModModule {
    @Override public String id() { return "edit_history"; }
    @Override public String title() { return "Edit History"; }
    @Override public String description() {
        return "Keep the pre-edit text of edited messages (view in Tools → Edit history). Uses the Anti-Delete snapshot store.";
    }
    @Override public boolean enabledByDefault() { return false; }
}

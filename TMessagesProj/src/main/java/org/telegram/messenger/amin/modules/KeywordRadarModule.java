package org.telegram.messenger.amin.modules;

import android.content.Context;

import org.telegram.messenger.amin.core.MessagePipeline;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;

import java.util.List;

public class KeywordRadarModule implements ModModule {
    @Override public String id() { return "keyword_radar"; }
    @Override public String title() { return "Keyword Radar"; }
    @Override public String description() {
        return "Watches messages for words you choose (for example a name or a topic) and collects every match under “Radar hits” below. Chats are also scanned when you open them.";
    }
    @Override public boolean enabledByDefault() { return false; }
    @Override public int section() { return SECTION_TOOLS; }
    @Override public String status(Context context) {
        if (!PluginRegistry.isEnabled(context, this)) {
            return "";
        }
        int keywords = MessagePipeline.loadKeywords(context).size();
        if (keywords == 0) {
            return "Needs keywords";
        }
        List<String> hits = VaultStore.getAll(context,
                MessagePipeline.RADAR_PREFS, MessagePipeline.RADAR_HITS_KEY);
        if (hits.isEmpty()) {
            return "Active · " + keywords + " keywords · no hits yet";
        }
        String last = hits.get(hits.size() - 1);
        String[] parts = last.split("\\|", 5);
        String kw = parts.length == 5 ? parts[2] : "";
        String snippet = parts.length == 5 ? parts[4] : "";
        if (snippet.length() > 40) {
            snippet = snippet.substring(0, 40) + "…";
        }
        return "Active · " + hits.size() + " hits · last: “" + kw + "” — " + snippet;
    }
}

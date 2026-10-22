package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DialogObject;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.core.DeletedStore;
import org.telegram.messenger.amin.core.VaultStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Store viewer. Two presentations behind the one constructor every
 * caller already uses (Plugins hub, JS plugin pages):
 *
 * - HISTORY mode (v2.0) — only for the DeletedStore lists
 *   (PREFS + DELETED_KEY / EDIT_KEY): a rich, dedicated viewer.
 *   A count header and a filter field (matches message text, chat
 *   title and sender) sit above one rounded CARD per record: a
 *   sender-initial chip, the chat title, a sender/kind + absolute
 *   time meta line, the relative time at the top right, and a
 *   BiDi-safe snippet of up to 3 lines (for edits, "old → new").
 *   Tapping a card opens AminHistoryDetailActivity with the full
 *   text, metadata, Copy / Open chat / Delete-this-record actions;
 *   a "Clear this list" row (with a confirm dialog) closes the
 *   list. Empty and no-match states are designed placeholder
 *   blocks, not a bare "Nothing here yet" row.
 *
 * - GENERIC mode — every other VaultStore list (Keyword Radar
 *   hits, JS plugin logs, …): the original plain TextCell list.
 *   Tap a row to copy it; the last row clears the list. Stage 2
 *   exception: a Keyword Radar hit with a valid message id opens
 *   that chat at the hit message instead of copying. Newest
 *   entries are at the bottom of the store, shown newest-first
 *   here.
 *
 * BiDi: the v1.3.4 formatters wrapped titles in LRM + LTR isolates
 * so an RTL (e.g. Persian) title or body cannot flip a row's visual
 * order. The rich UI keeps the same discipline via {@link #bidi}:
 * every user-controlled string (title, sender, snippet, body) is
 * rendered as LRM + FSI … PDI.
 */
public class AminStoreListActivity extends BaseFragment {

    private static final int KIND_CARD = 0;
    private static final int KIND_CLEAR = 1;

    private final String title;
    private final String prefsName;
    private final String key;
    private final boolean historyMode;

    // Generic-mode state (legacy path, unchanged semantics).
    private List<String> items;
    /** Raw Radar records, position-aligned with {@link #items}
     *  after its display formatting; null for every other list. */
    private List<String> rawItems;
    private StoreAdapter adapter;

    // History-mode state.
    private final List<UiEntry> allEntries = new ArrayList<>();
    private final List<UiEntry> visibleEntries = new ArrayList<>();
    private HistoryAdapter historyAdapter;
    private TextView countHeader;
    private EditTextBoldCursor filterField;
    private View emptyView;
    private TextView emptyTitle;
    private TextView emptySubtitle;
    private String filterQuery = "";

    private RecyclerListView listView;

    public AminStoreListActivity(String title, String prefsName, String key) {
        super();
        this.title = title;
        this.prefsName = prefsName;
        this.key = key;
        this.historyMode = DeletedStore.PREFS.equals(prefsName)
                && (DeletedStore.DELETED_KEY.equals(key) || DeletedStore.EDIT_KEY.equals(key));
    }

    public boolean isHistoryMode() {
        return historyMode;
    }

    // ------------------------------------------------------------------
    // Shared presentation helpers (also used by the detail screen,
    // same package — like AminDialogPickerActivity.dialogName)
    // ------------------------------------------------------------------

    /** LRM + first-strong isolate around user text, the v1.3.4
     *  formatter discipline: an RTL title/body can never flip the
     *  surrounding layout, and LTR text is unaffected. */
    static String bidi(String text) {
        if (text == null) {
            return "";
        }
        return "\u200E\u2066" + text + "\u2069";
    }

    /** "HH:mm · dd MMM yyyy" ("" when the record has no date). */
    static String absoluteTime(long dateSec) {
        if (dateSec <= 0) {
            return "";
        }
        return new SimpleDateFormat("HH:mm · dd MMM yyyy", Locale.US)
                .format(new Date(dateSec * 1000L));
    }

    /** "just now" / "5m ago" / "3h ago" / "2d ago" / "dd MMM". */
    static String relativeTime(long dateSec) {
        if (dateSec <= 0) {
            return "";
        }
        long diffMs = System.currentTimeMillis() - dateSec * 1000L;
        if (diffMs < 60_000L) {
            return "just now";
        }
        if (diffMs < 3_600_000L) {
            return (diffMs / 60_000L) + "m ago";
        }
        if (diffMs < 86_400_000L) {
            return (diffMs / 3_600_000L) + "h ago";
        }
        if (diffMs < 7 * 86_400_000L) {
            return (diffMs / 86_400_000L) + "d ago";
        }
        return new SimpleDateFormat("dd MMM", Locale.US)
                .format(new Date(dateSec * 1000L));
    }

    /** "Photo" / "Document" / … display label for a stored record
     *  kind; "" for plain text (no label needed). */
    static String kindLabel(String kind) {
        if (kind == null || kind.isEmpty() || "text".equals(kind)) {
            return "";
        }
        return Character.toUpperCase(kind.charAt(0)) + kind.substring(1);
    }

    private static GradientDrawable cardDrawable(int fillColor) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadius(AndroidUtilities.dp(22));
        return drawable;
    }

    static int cardFill() {
        return Theme.getColor(Theme.key_windowBackgroundWhite);
    }

    static int accentColor() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
    }

    static int chipFill() {
        return ColorUtils.blendARGB(cardFill(), accentColor(), 0.16f);
    }

    // ------------------------------------------------------------------
    // Fragment lifecycle
    // ------------------------------------------------------------------

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(title);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        View layout = historyMode ? createHistoryView(context) : createLegacyView(context);
        fragmentView = layout;
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Returning from the detail screen (e.g. after deleting the
        // record there) must refresh the cards and the count.
        if (historyMode && getContext() != null && historyAdapter != null) {
            reloadHistory(getContext());
            historyAdapter.notifyDataSetChanged();
        }
    }

    // ------------------------------------------------------------------
    // Generic mode — the original viewer, behaviour preserved
    // ------------------------------------------------------------------

    private boolean isRadarHits() {
        return org.telegram.messenger.amin.core.MessagePipeline.RADAR_PREFS.equals(prefsName)
                && org.telegram.messenger.amin.core.MessagePipeline.RADAR_HITS_KEY.equals(key);
    }

    private void reload(Context context) {
        items = VaultStore.getAll(context, prefsName, key);
        rawItems = isRadarHits() ? new ArrayList<>(items) : null;
        // reverse: newest first (raw Radar records stay aligned)
        for (int i = 0, j = items.size() - 1; i < j; i++, j--) {
            String tmp = items.get(i);
            items.set(i, items.get(j));
            items.set(j, tmp);
            if (rawItems != null) {
                String rawTmp = rawItems.get(i);
                rawItems.set(i, rawItems.get(j));
                rawItems.set(j, rawTmp);
            }
        }
        if (DeletedStore.EDIT_KEY.equals(key)) {
            // Edit records are stored raw (dialogId|msgId|date|old -> new);
            // render them readably (title + time + quoted change).
            for (int i = 0; i < items.size(); i++) {
                items.set(i, DeletedStore.formatEditRecord(currentAccount, items.get(i)));
            }
        } else if (DeletedStore.DELETED_KEY.equals(key)) {
            // Deleted records are raw too (dialogId|msgId|date|kind|text).
            for (int i = 0; i < items.size(); i++) {
                items.set(i, DeletedStore.formatDeletedRecord(currentAccount, items.get(i)));
            }
        } else if (org.telegram.messenger.amin.core.MessagePipeline.RADAR_HITS_KEY.equals(key)) {
            // Radar hits are raw too (dialogId|msgId|keyword|date|snippet).
            for (int i = 0; i < items.size(); i++) {
                items.set(i, org.telegram.messenger.amin.core.MessagePipeline.formatRadarHit(
                        currentAccount, items.get(i)));
            }
        }
    }

    /** Stage 2 Radar deep-link: open the hit's chat scrolled to
     *  the hit message, using upstream's own construction —
     *  {@code ChatActivity.of(dialogId, messageId)} builds a Bundle
     *  with user_id (dialogId >= 0) or chat_id (-dialogId) plus
     *  message_id, exactly like upstream's search/shared-media
     *  jumps; encrypted dialogs mirror DialogsActivity's enc_id
     *  form. The fragment is pinned to this list's account (the
     *  account the Radar titles were resolved against). Returns
     *  false — keeping the legacy copy behaviour — for non-Radar
     *  rows, malformed records, or a missing/invalid message id. */
    private boolean openRadarHit(int position) {
        if (!isRadarHits() || rawItems == null
                || position < 0 || position >= rawItems.size()) {
            return false;
        }
        try {
            String[] parts = rawItems.get(position).split("\\|", 5);
            if (parts.length < 3) {
                return false;
            }
            long dialogId = Long.parseLong(parts[0]);
            long messageId = Long.parseLong(parts[1]);
            if (dialogId == 0 || messageId <= 0 || messageId > Integer.MAX_VALUE) {
                return false;
            }
            BaseFragment chat;
            if (DialogObject.isEncryptedDialog(dialogId)) {
                Bundle args = new Bundle();
                args.putInt("enc_id", DialogObject.getEncryptedChatId(dialogId));
                args.putInt("message_id", (int) messageId);
                chat = new ChatActivity(args);
            } else {
                chat = ChatActivity.of(dialogId, (int) messageId);
            }
            chat.setCurrentAccount(currentAccount);
            presentFragment(chat);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private View createLegacyView(Context context) {
        reload(context);

        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new StoreAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> {
            if (items == null || items.isEmpty()) {
                return;
            }
            if (position < items.size()) {
                if (openRadarHit(position)) {
                    return;
                }
                String value = items.get(position);
                AndroidUtilities.addToClipboard(value);
                BulletinFactory.of(AminStoreListActivity.this)
                        .createSimpleBulletin(R.raw.copy, "Copied to clipboard")
                        .show();
            } else if (position == items.size()) {
                VaultStore.clear(context, prefsName, key);
                reload(context);
                adapter.notifyDataSetChanged();
                BulletinFactory.of(AminStoreListActivity.this)
                        .createSimpleBulletin(R.raw.ic_delete, "List cleared")
                        .show();
            }
        });
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return layout;
    }

    private class StoreAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            if (items == null || items.isEmpty()) {
                return 1; // "empty" row
            }
            return items.size() + 1; // + clear row
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            TextCell cell = new TextCell(parent.getContext());
            cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            TextCell cell = (TextCell) holder.itemView;
            if (items == null || items.isEmpty()) {
                cell.setText("Nothing here yet", false);
                return;
            }
            if (position < items.size()) {
                String value = items.get(position);
                int cap = (DeletedStore.EDIT_KEY.equals(key) || DeletedStore.DELETED_KEY.equals(key)
                        || org.telegram.messenger.amin.core.MessagePipeline.RADAR_HITS_KEY.equals(key)) ? 600 : 140;
                if (value.length() > cap) {
                    value = value.substring(0, cap) + "…";
                }
                cell.setText(value, position < items.size() - 1);
            } else {
                cell.setText("Clear this list", false);
            }
        }
    }

    // ------------------------------------------------------------------
    // History mode — rich Deleted / Edit History viewer
    // ------------------------------------------------------------------

    /** A parsed record plus its resolved display strings and a
     *  pre-built lowercase search blob (text + chat + sender). */
    private static final class UiEntry {
        final DeletedStore.HistoryEntry entry;
        final String chatTitle;
        final String senderName;
        final String searchBlob;

        UiEntry(DeletedStore.HistoryEntry entry, String chatTitle, String senderName) {
            this.entry = entry;
            this.chatTitle = chatTitle != null ? chatTitle : "";
            this.senderName = senderName != null ? senderName : "";
            String blob = this.chatTitle + "\n" + this.senderName + "\n"
                    + entry.snippetText() + "\n" + entry.bodyText() + "\n"
                    + (entry.oldText != null ? entry.oldText : "") + "\n"
                    + (entry.kind != null ? entry.kind : "");
            this.searchBlob = blob.toLowerCase(Locale.ROOT);
        }
    }

    private void reloadHistory(Context context) {
        allEntries.clear();
        List<DeletedStore.HistoryEntry> stored = DeletedStore.getHistoryEntries(context, key);
        for (int i = stored.size() - 1; i >= 0; i--) { // newest first
            DeletedStore.HistoryEntry entry = stored.get(i);
            allEntries.add(new UiEntry(entry,
                    DeletedStore.chatTitle(currentAccount, entry.dialogId),
                    DeletedStore.senderName(currentAccount, entry.dialogId)));
        }
        applyFilter();
    }

    private void applyFilter() {
        visibleEntries.clear();
        String query = filterQuery == null ? "" : filterQuery.trim().toLowerCase(Locale.ROOT);
        for (UiEntry uiEntry : allEntries) {
            if (query.isEmpty() || uiEntry.searchBlob.contains(query)) {
                visibleEntries.add(uiEntry);
            }
        }
        updateHeaderAndEmpty();
    }

    private boolean filtering() {
        return filterQuery != null && !filterQuery.trim().isEmpty();
    }

    private boolean showClearRow() {
        return !filtering() && !allEntries.isEmpty();
    }

    private void updateHeaderAndEmpty() {
        if (countHeader != null) {
            boolean edits = DeletedStore.EDIT_KEY.equals(key);
            if (filtering()) {
                countHeader.setText(visibleEntries.size() + " of " + allEntries.size()
                        + (edits ? " edited messages" : " deleted messages"));
            } else if (allEntries.size() == 1) {
                countHeader.setText(edits ? "1 edited message" : "1 deleted message");
            } else {
                countHeader.setText(allEntries.size()
                        + (edits ? " edited messages" : " deleted messages"));
            }
        }
        if (emptyView != null) {
            boolean empty = visibleEntries.isEmpty();
            emptyView.setVisibility(empty ? View.VISIBLE : View.GONE);
            if (listView != null) {
                listView.setVisibility(empty ? View.GONE : View.VISIBLE);
            }
            if (empty && emptyTitle != null && emptySubtitle != null) {
                if (!allEntries.isEmpty()) {
                    emptyTitle.setText("No matches");
                    emptySubtitle.setText("Nothing matches “" + filterQuery.trim()
                            + "”. Try a different word, chat or name.");
                } else if (DeletedStore.EDIT_KEY.equals(key)) {
                    emptyTitle.setText("No edits recorded yet");
                    emptySubtitle.setText("When someone edits a message AminGram has seen, "
                            + "the before and after text will be kept here.");
                } else {
                    emptyTitle.setText("No deleted messages yet");
                    emptySubtitle.setText("Messages deleted after Anti-Delete was turned on "
                            + "will appear here, with their text kept on this device only.");
                }
            }
        }
    }

    private View createHistoryView(Context context) {
        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        // Header: count line + filter field.
        LinearLayout header = new LinearLayout(context);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                AndroidUtilities.dp(16), AndroidUtilities.dp(4));
        root.addView(header, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        countHeader = new TextView(context);
        countHeader.setTextSize(14);
        countHeader.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        countHeader.setTextColor(accentColor());
        countHeader.setSingleLine(true);
        countHeader.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(countHeader, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        filterField = new EditTextBoldCursor(context);
        filterField.setHint("Filter by text, chat or sender");
        filterField.setSingleLine(true);
        filterField.setTextSize(15);
        filterField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        filterField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        filterField.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(10),
                AndroidUtilities.dp(14), AndroidUtilities.dp(10));
        GradientDrawable filterBg = new GradientDrawable();
        filterBg.setShape(GradientDrawable.RECTANGLE);
        filterBg.setColor(cardFill());
        filterBg.setCornerRadius(AndroidUtilities.dp(12));
        filterBg.setStroke(1, Theme.getColor(Theme.key_divider));
        filterField.setBackground(filterBg);
        LinearLayout.LayoutParams filterParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        filterParams.topMargin = AndroidUtilities.dp(10);
        header.addView(filterField, filterParams);
        filterField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                filterQuery = s != null ? s.toString() : "";
                applyFilter();
                if (historyAdapter != null) {
                    historyAdapter.notifyDataSetChanged();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // Content: the card list, with the designed empty state
        // overlaid in the same area.
        FrameLayout content = new FrameLayout(context);
        root.addView(content, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setClipToPadding(false);
        listView.setPadding(0, AndroidUtilities.dp(2), 0, AndroidUtilities.dp(14));
        historyAdapter = new HistoryAdapter();
        listView.setAdapter(historyAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (position < 0) {
                return;
            }
            if (position < visibleEntries.size()) {
                UiEntry uiEntry = visibleEntries.get(position);
                presentFragment(new AminHistoryDetailActivity(key, uiEntry.entry.raw));
            } else if (showClearRow()) {
                confirmClearHistory(context);
            }
        });
        content.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        emptyView = buildEmptyView(context);
        content.addView(emptyView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        reloadHistory(context);
        return root;
    }

    private View buildEmptyView(Context context) {
        LinearLayout empty = new LinearLayout(context);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER_HORIZONTAL);
        empty.setPadding(AndroidUtilities.dp(40), AndroidUtilities.dp(56),
                AndroidUtilities.dp(40), AndroidUtilities.dp(24));

        FrameLayout badge = new FrameLayout(context);
        GradientDrawable badgeBg = new GradientDrawable();
        badgeBg.setShape(GradientDrawable.OVAL);
        badgeBg.setColor(chipFill());
        badge.setBackground(badgeBg);
        TextView glyph = new TextView(context);
        glyph.setText(DeletedStore.EDIT_KEY.equals(key) ? "✎" : "🗑");
        glyph.setTextSize(30);
        glyph.setGravity(Gravity.CENTER);
        glyph.setTextColor(accentColor());
        badge.addView(glyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        empty.addView(badge, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(76), AndroidUtilities.dp(76)));

        emptyTitle = new TextView(context);
        emptyTitle.setTextSize(17);
        emptyTitle.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        emptyTitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        emptyTitle.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        titleParams.topMargin = AndroidUtilities.dp(18);
        empty.addView(emptyTitle, titleParams);

        emptySubtitle = new TextView(context);
        emptySubtitle.setTextSize(14);
        emptySubtitle.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        emptySubtitle.setGravity(Gravity.CENTER);
        emptySubtitle.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subtitleParams.topMargin = AndroidUtilities.dp(6);
        empty.addView(emptySubtitle, subtitleParams);
        return empty;
    }

    private void confirmClearHistory(Context context) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Clear " + title + "?");
        builder.setMessage("All " + allEntries.size() + " saved records in this list "
                + "are removed from this device. This cannot be undone.");
        builder.setPositiveButton("Clear", (dialog, which) -> {
            VaultStore.clear(context, prefsName, key);
            reloadHistory(context);
            if (historyAdapter != null) {
                historyAdapter.notifyDataSetChanged();
            }
            BulletinFactory.of(AminStoreListActivity.this)
                    .createSimpleBulletin(R.raw.ic_delete, "List cleared")
                    .show();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    /** One history record as a rounded card: initial chip, chat
     *  title + sender/kind · absolute-time meta, relative time at
     *  the top right, and a 3-line BiDi-safe snippet. */
    private static class HistoryCardView extends LinearLayout {
        private final FrameLayout chip;
        private final TextView chipLetter;
        private final TextView titleView;
        private final TextView metaView;
        private final TextView relTimeView;
        private final TextView snippetView;

        HistoryCardView(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12),
                    AndroidUtilities.dp(14), AndroidUtilities.dp(12));

            LinearLayout headerRow = new LinearLayout(context);
            headerRow.setOrientation(HORIZONTAL);
            headerRow.setGravity(Gravity.TOP);
            addView(headerRow, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            chip = new FrameLayout(context);
            headerRow.addView(chip, new LinearLayout.LayoutParams(
                    AndroidUtilities.dp(40), AndroidUtilities.dp(40)));
            chipLetter = new TextView(context);
            chipLetter.setTextSize(17);
            chipLetter.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            chipLetter.setGravity(Gravity.CENTER);
            chip.addView(chipLetter, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            columnParams.leftMargin = AndroidUtilities.dp(12);
            columnParams.rightMargin = AndroidUtilities.dp(8);
            headerRow.addView(column, columnParams);

            titleView = new TextView(context);
            titleView.setTextSize(16);
            titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(titleView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            metaView = new TextView(context);
            metaView.setTextSize(13);
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = AndroidUtilities.dp(2);
            column.addView(metaView, metaParams);

            relTimeView = new TextView(context);
            relTimeView.setTextSize(13);
            relTimeView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            relTimeView.setSingleLine(true);
            headerRow.addView(relTimeView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            snippetView = new TextView(context);
            snippetView.setTextSize(14.5f);
            snippetView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            snippetView.setMaxLines(3);
            snippetView.setEllipsize(TextUtils.TruncateAt.END);
            snippetView.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
            LinearLayout.LayoutParams snippetParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            snippetParams.leftMargin = AndroidUtilities.dp(52);
            snippetParams.topMargin = AndroidUtilities.dp(6);
            addView(snippetView, snippetParams);
        }

        void bind(int account, UiEntry uiEntry) {
            DeletedStore.HistoryEntry entry = uiEntry.entry;
            setBackground(cardDrawable(cardFill()));

            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setColor(chipFill());
            chipBg.setCornerRadius(AndroidUtilities.dp(12));
            chip.setBackground(chipBg);
            String titleText = uiEntry.chatTitle;
            chipLetter.setText(titleText.isEmpty() ? "?"
                    : titleText.substring(0, 1).toUpperCase(Locale.ROOT));
            chipLetter.setTextColor(accentColor());

            titleView.setText(bidi(titleText));

            StringBuilder meta = new StringBuilder();
            if (entry.dialogId > 0) {
                meta.append(bidi(uiEntry.senderName));
            } else if (entry.dialogId != 0) {
                meta.append(AminDialogPickerActivity.dialogKind(account, entry.dialogId));
            }
            String label = entry.edit ? "Edited" : kindLabel(entry.kind);
            if (!label.isEmpty()) {
                if (meta.length() > 0) {
                    meta.append(" · ");
                }
                meta.append(label);
            }
            String absolute = absoluteTime(entry.dateSec);
            if (!absolute.isEmpty()) {
                if (meta.length() > 0) {
                    meta.append(" · ");
                }
                meta.append(absolute);
            }
            metaView.setText(meta.toString());
            relTimeView.setText(relativeTime(entry.dateSec));

            String snippet;
            if (entry.edit) {
                String oldText = entry.oldText != null ? entry.oldText : "";
                if (oldText.isEmpty()) {
                    snippet = entry.bodyText();
                } else {
                    snippet = "\"" + oldText + "\" → \"" + entry.bodyText() + "\"";
                }
            } else {
                snippet = entry.bodyText();
                if (snippet.isEmpty()) {
                    snippet = "[" + (entry.kind != null ? entry.kind : "media") + "]";
                }
            }
            snippetView.setText(bidi(snippet));
        }
    }

    private class HistoryAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return visibleEntries.size() + (showClearRow() ? 1 : 0);
        }

        @Override
        public int getItemViewType(int position) {
            return position < visibleEntries.size() ? KIND_CARD : KIND_CLEAR;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            if (viewType == KIND_CARD) {
                view = new HistoryCardView(parent.getContext());
            } else {
                TextView clear = new TextView(parent.getContext());
                clear.setText("Clear this list");
                clear.setTextSize(15);
                clear.setGravity(Gravity.CENTER);
                clear.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
                clear.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(6));
                view = clear;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            if (position >= visibleEntries.size()) {
                return; // the clear row carries no per-item state
            }
            RecyclerView.LayoutParams lp =
                    (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            lp.leftMargin = AndroidUtilities.dp(12);
            lp.rightMargin = AndroidUtilities.dp(12);
            lp.topMargin = AndroidUtilities.dp(position == 0 ? 8 : 10);
            holder.itemView.setLayoutParams(lp);
            ((HistoryCardView) holder.itemView).bind(currentAccount, visibleEntries.get(position));
        }
    }
}

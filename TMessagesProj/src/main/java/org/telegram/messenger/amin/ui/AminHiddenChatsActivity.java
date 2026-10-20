package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
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
import org.telegram.messenger.R;
import org.telegram.messenger.amin.core.HiddenChatsStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Hidden Chats management page (v1.3.4 UX rework). In v1.3.x the
 * whole flow lived as scattered rows inside the Plugins hub
 * (set PIN / unlock / one row per hidden chat / lock), which Amin
 * found clunky. This dedicated page replaces them — the hub keeps
 * a single "Manage hidden chats" row — and speaks the hub's
 * extera design language: accent section labels, ONE rounded
 * group card per section, hairline-separated rows.
 *
 * Contents follow the store state:
 * - no PIN yet: a PIN card explaining the feature + "Set PIN".
 * - PIN set, locked: an Unlock row (inline PIN prompt).
 * - unlocked: the hidden chats card (name + kind + an Unhide
 *   button per row, plus "Hide a chat…" opening the existing
 *   dialog picker) and a PIN card (Change PIN, Lock now).
 *
 * HiddenChatsStore semantics are untouched: SHA-256+salt PIN,
 * memory-only unlocked flag, the dialogs filter unchanged.
 * Every flow is Throwable-isolated like the rest of the layer.
 */
public class AminHiddenChatsActivity extends BaseFragment {

    private static final int KIND_HEADER = 0;
    private static final int KIND_INFO = 1;
    private static final int KIND_ACTION = 2;
    private static final int KIND_HIDDEN = 3;
    private static final int KIND_FOOTER = 4;

    private static final int SEG_NONE = 0;
    private static final int SEG_ONLY = 1;
    private static final int SEG_FIRST = 2;
    private static final int SEG_MID = 3;
    private static final int SEG_LAST = 4;

    private static final class Row {
        int kind;
        int seg = SEG_NONE;
        String title;
        String value;
        String action;
        long hiddenId;
    }

    private RecyclerListView listView;
    private HiddenAdapter adapter;
    private final List<Row> rows = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Hidden Chats");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        adapter = new HiddenAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        rebuild();
        fragmentView = layout;
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        // Returning from the dialog picker must refresh the list.
        rebuild();
    }

    private void rebuild() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        rows.clear();
        try {
            if (!HiddenChatsStore.hasPin(context)) {
                addHeader("PIN");
                addInfo("Set a PIN to use Hidden Chats. Chats you hide disappear from "
                        + "the chat list while locked and come back when you unlock.");
                addAction("pin_set", "Set PIN", null);
            } else if (!HiddenChatsStore.isUnlocked()) {
                addHeader("Hidden Chats");
                addInfo("Hidden chats are locked. Unlock to see and manage them.");
                addAction("pin_unlock", "Unlock hidden chats", null);
            } else {
                List<Long> hidden = HiddenChatsStore.getHiddenIds(context);
                addHeader(hidden.isEmpty() ? "Hidden chats" : "Hidden chats · " + hidden.size());
                if (hidden.isEmpty()) {
                    addInfo("No chats are hidden yet.");
                } else {
                    for (Long id : hidden) {
                        Row row = new Row();
                        row.kind = KIND_HIDDEN;
                        row.hiddenId = id;
                        rows.add(row);
                    }
                }
                addAction("hide_chat", "Hide a chat…", null);
                addHeader("PIN");
                addAction("pin_change", "Change PIN", null);
                addAction("pin_lock", "Lock now", null);
            }
        } catch (Throwable ignored) {
        }
        Row footer = new Row();
        footer.kind = KIND_FOOTER;
        footer.title = "Unlock lasts until you lock or restart the app. "
                + "Telegram's global search still finds hidden chats.";
        rows.add(footer);
        assignSegments();
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    /** One group card per section: labels and the footer stay
     *  uncarded, everything between two labels joins one card
     *  (same rule as the hub's assignSegments). */
    private void assignSegments() {
        for (Row row : rows) {
            row.seg = SEG_NONE;
        }
        int start = -1;
        for (int i = 0; i <= rows.size(); i++) {
            boolean closes = i == rows.size()
                    || rows.get(i).kind == KIND_HEADER
                    || rows.get(i).kind == KIND_FOOTER;
            if (closes) {
                if (start >= 0) {
                    closeGroup(start, i);
                    start = -1;
                }
            } else if (start < 0) {
                start = i;
            }
        }
    }

    private void closeGroup(int start, int endExclusive) {
        if (endExclusive - start == 1) {
            rows.get(start).seg = SEG_ONLY;
        } else {
            rows.get(start).seg = SEG_FIRST;
            rows.get(endExclusive - 1).seg = SEG_LAST;
            for (int j = start + 1; j < endExclusive - 1; j++) {
                rows.get(j).seg = SEG_MID;
            }
        }
    }

    private void addHeader(String title) {
        Row row = new Row();
        row.kind = KIND_HEADER;
        row.title = title;
        rows.add(row);
    }

    private void addInfo(String text) {
        Row row = new Row();
        row.kind = KIND_INFO;
        row.title = text;
        rows.add(row);
    }

    private void addAction(String actionId, String title, String value) {
        Row row = new Row();
        row.kind = KIND_ACTION;
        row.action = actionId;
        row.title = title;
        row.value = value;
        rows.add(row);
    }

    // ------------------------------------------------------------------
    // Clicks and flows
    // ------------------------------------------------------------------

    private void onRowClick(int position) {
        Context context = getContext();
        if (context == null || position < 0 || position >= rows.size()) {
            return;
        }
        Row row = rows.get(position);
        try {
            if (row.kind == KIND_HIDDEN) {
                unhide(context, row.hiddenId);
            } else if (row.kind == KIND_ACTION) {
                switch (row.action) {
                    case "pin_set":
                        pinSetFlow(context, false);
                        break;
                    case "pin_change":
                        pinChangeFlow(context);
                        break;
                    case "pin_unlock":
                        pinUnlockFlow(context);
                        break;
                    case "pin_lock":
                        HiddenChatsStore.setUnlocked(false);
                        bulletin(R.raw.contact_check, "Hidden chats locked");
                        rebuild();
                        break;
                    case "hide_chat":
                        presentFragment(new AminDialogPickerActivity("Hide a chat", false,
                                (dialogId, name) -> {
                                    Context ctx = getContext();
                                    if (ctx != null) {
                                        HiddenChatsStore.setHidden(ctx, dialogId, true);
                                        bulletin(R.raw.contact_check, "Chat hidden");
                                    }
                                    rebuild();
                                }));
                        break;
                    default:
                        break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void unhide(Context context, long dialogId) {
        try {
            HiddenChatsStore.setHidden(context, dialogId, false);
            bulletin(R.raw.contact_check, "Chat unhidden");
        } catch (Throwable ignored) {
        }
        rebuild();
    }

    private void bulletin(int icon, String text) {
        try {
            BulletinFactory.of(this).createSimpleBulletin(icon, text).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // PIN dialogs (numeric EditText in an AlertDialog, like the hub's
    // v1.3 flows — moved here with the rest of the management UI)
    // ------------------------------------------------------------------

    private EditTextBoldCursor pinField(Context context, String hint) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setHint(hint);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        return editText;
    }

    private FrameLayout fieldContainer(Context context, View field) {
        FrameLayout container = new FrameLayout(context);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8),
                AndroidUtilities.dp(24), 0);
        container.addView(field, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return container;
    }

    /** Set a new PIN (with repeat confirmation). When {@code change}
     *  is true the caller has already verified the current PIN. */
    private void pinSetFlow(Context context, boolean change) {
        if (getParentActivity() == null) {
            return;
        }
        EditTextBoldCursor first = pinField(context, "New PIN (4+ digits)");
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(change ? "Change PIN" : "Set a PIN for Hidden Chats");
        builder.setView(fieldContainer(context, first));
        builder.setPositiveButton("Next", (dialog, which) -> {
            String pin = first.getText().toString().trim();
            if (pin.length() < 4) {
                bulletin(R.raw.error, "PIN must be at least 4 digits");
                return;
            }
            EditTextBoldCursor second = pinField(context, "Repeat the PIN");
            AlertDialog.Builder confirm = new AlertDialog.Builder(getParentActivity());
            confirm.setTitle("Confirm PIN");
            confirm.setView(fieldContainer(context, second));
            confirm.setPositiveButton(change ? "Change PIN" : "Set PIN", (d2, w2) -> {
                if (pin.equals(second.getText().toString().trim())) {
                    HiddenChatsStore.setPin(context, pin);
                    HiddenChatsStore.setUnlocked(true);
                    bulletin(R.raw.contact_check, change ? "PIN changed" : "PIN set — hidden chats unlocked");
                } else {
                    bulletin(R.raw.error, "PINs did not match");
                }
                rebuild();
            });
            confirm.setNegativeButton("Cancel", null);
            showDialog(confirm.create());
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void pinChangeFlow(Context context) {
        if (getParentActivity() == null) {
            return;
        }
        EditTextBoldCursor current = pinField(context, "Current PIN");
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Change PIN");
        builder.setView(fieldContainer(context, current));
        builder.setPositiveButton("Next", (dialog, which) -> {
            if (HiddenChatsStore.checkPin(context, current.getText().toString().trim())) {
                pinSetFlow(context, true);
            } else {
                bulletin(R.raw.error, "Wrong PIN");
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void pinUnlockFlow(Context context) {
        if (getParentActivity() == null) {
            return;
        }
        EditTextBoldCursor field = pinField(context, "PIN");
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Unlock hidden chats");
        builder.setView(fieldContainer(context, field));
        builder.setPositiveButton("Unlock", (dialog, which) -> {
            if (HiddenChatsStore.checkPin(context, field.getText().toString().trim())) {
                HiddenChatsStore.setUnlocked(true);
                bulletin(R.raw.contact_check, "Unlocked");
            } else {
                bulletin(R.raw.error, "Wrong PIN");
            }
            rebuild();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    // ------------------------------------------------------------------
    // Presentation helpers (the hub's extera language, kept local so
    // this page stands on its own)
    // ------------------------------------------------------------------

    private static GradientDrawable segmentDrawable(int seg, int fillColor) {
        float r = AndroidUtilities.dp(22);
        float[] radii;
        switch (seg) {
            case SEG_ONLY:
                radii = new float[]{r, r, r, r, r, r, r, r};
                break;
            case SEG_FIRST:
                radii = new float[]{r, r, r, r, 0, 0, 0, 0};
                break;
            case SEG_LAST:
                radii = new float[]{0, 0, 0, 0, r, r, r, r};
                break;
            default:
                radii = new float[]{0, 0, 0, 0, 0, 0, 0, 0};
                break;
        }
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(fillColor);
        drawable.setCornerRadii(radii);
        return drawable;
    }

    private static int cardFill() {
        return Theme.getColor(Theme.key_windowBackgroundWhite);
    }

    private static int accentColor() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
    }

    private static int chipFill() {
        return ColorUtils.blendARGB(cardFill(), accentColor(), 0.16f);
    }

    /** Accent section label, same as the hub's. */
    private static class SectionLabelView extends TextView {
        SectionLabelView(Context context) {
            super(context);
            setTextSize(14);
            setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            setTextColor(accentColor());
            setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(22),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(2));
        }
    }

    /** Explanatory text inside a group card. */
    private static class InfoView extends TextView {
        InfoView(Context context) {
            super(context);
            setTextSize(13.5f);
            setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            setLineSpacing(AndroidUtilities.dp(2), 1.0f);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(12));
        }

        void bind(String text, int seg) {
            setText(text == null ? "" : text);
            setBackground(segmentDrawable(seg, cardFill()));
        }
    }

    /** One hidden chat: name + kind on the left, an Unhide chip on
     *  the right. The chip is a DIRECT child of the item view and
     *  clickable, so RecyclerListView's item-click suppression
     *  (one-level clickable-child check) keeps chip taps from also
     *  firing the row click; both paths unhide anyway. */
    private static class HiddenChatRowView extends LinearLayout {
        interface Callback {
            void onUnhide(long dialogId);
        }

        private final TextView nameView;
        private final TextView kindView;
        private final TextView unhideChip;
        private long boundId;
        private Callback callback;

        HiddenChatRowView(Context context) {
            super(context);
            setOrientation(HORIZONTAL);
            setGravity(Gravity.CENTER_VERTICAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10),
                    AndroidUtilities.dp(12), AndroidUtilities.dp(10));

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(VERTICAL);
            addView(column, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            nameView = new TextView(context);
            nameView.setTextSize(16);
            nameView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            column.addView(nameView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            kindView = new TextView(context);
            kindView.setTextSize(13);
            kindView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            kindView.setSingleLine(true);
            LinearLayout.LayoutParams kindParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            kindParams.topMargin = AndroidUtilities.dp(2);
            column.addView(kindView, kindParams);

            unhideChip = new TextView(context);
            unhideChip.setText("Unhide");
            unhideChip.setTextSize(13.5f);
            unhideChip.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            unhideChip.setTextColor(accentColor());
            unhideChip.setSingleLine(true);
            unhideChip.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(7),
                    AndroidUtilities.dp(14), AndroidUtilities.dp(7));
            unhideChip.setClickable(true);
            unhideChip.setOnClickListener(v -> {
                if (callback != null) {
                    callback.onUnhide(boundId);
                }
            });
            addView(unhideChip, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        void setCallback(Callback callback) {
            this.callback = callback;
        }

        void bind(int account, long dialogId, int seg) {
            boundId = dialogId;
            nameView.setText(AminDialogPickerActivity.dialogName(account, dialogId));
            kindView.setText(AminDialogPickerActivity.dialogKind(account, dialogId));
            GradientDrawable chipBg = new GradientDrawable();
            chipBg.setShape(GradientDrawable.RECTANGLE);
            chipBg.setColor(chipFill());
            chipBg.setCornerRadius(AndroidUtilities.dp(12));
            unhideChip.setBackground(chipBg);
            setBackground(segmentDrawable(seg, cardFill()));
        }
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private class HiddenAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return rows.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rows.get(position).kind;
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            if (position < 0 || position >= rows.size()) {
                return false;
            }
            int kind = rows.get(position).kind;
            return kind == KIND_ACTION || kind == KIND_HIDDEN;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case KIND_HEADER:
                    view = new SectionLabelView(context);
                    break;
                case KIND_INFO:
                    view = new InfoView(context);
                    break;
                case KIND_HIDDEN: {
                    HiddenChatRowView rowView = new HiddenChatRowView(context);
                    rowView.setCallback(dialogId -> {
                        Context ctx = getContext();
                        if (ctx != null) {
                            unhide(ctx, dialogId);
                        }
                    });
                    view = rowView;
                    break;
                }
                case KIND_FOOTER:
                    view = new TextInfoPrivacyCell(context);
                    break;
                default:
                    view = new TextCell(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Row row = rows.get(position);
            RecyclerView.LayoutParams lp =
                    (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            boolean carded = row.seg != SEG_NONE;
            lp.leftMargin = carded ? AndroidUtilities.dp(12) : 0;
            lp.rightMargin = carded ? AndroidUtilities.dp(12) : 0;
            lp.topMargin = row.seg == SEG_FIRST || row.seg == SEG_ONLY
                    ? AndroidUtilities.dp(10) : 0;
            holder.itemView.setLayoutParams(lp);
            switch (row.kind) {
                case KIND_HEADER:
                    ((SectionLabelView) holder.itemView).setText(row.title);
                    break;
                case KIND_INFO:
                    ((InfoView) holder.itemView).bind(row.title, row.seg);
                    break;
                case KIND_HIDDEN:
                    ((HiddenChatRowView) holder.itemView).bind(currentAccount, row.hiddenId, row.seg);
                    break;
                case KIND_FOOTER:
                    ((TextInfoPrivacyCell) holder.itemView).setText(row.title);
                    break;
                default:
                    TextCell cell = (TextCell) holder.itemView;
                    boolean divider = row.seg == SEG_MID;
                    if (row.value != null) {
                        cell.setTextAndValue(row.title, row.value, divider);
                    } else {
                        cell.setText(row.title, divider);
                    }
                    cell.setBackground(segmentDrawable(row.seg, cardFill()));
                    break;
            }
        }
    }
}

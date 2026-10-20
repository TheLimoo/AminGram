package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.core.DeletedStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;

import java.util.Locale;

/**
 * Detail screen for ONE Deleted / Edit History record (v2.0),
 * opened by tapping a card in AminStoreListActivity's history
 * mode. Constructed with the store key + the record's raw string;
 * the entry is re-parsed via DeletedStore, so Delete acts on the
 * exact stored row.
 *
 * Contents:
 * - a message card: initial chip, chat title, dialog kind +
 *   relative time, then the FULL text — for a deleted message one
 *   neutral block; for an edit, tinted BEFORE (red) and AFTER
 *   (green) blocks with labels, mirroring the hub's status tones.
 *   Body text is selectable.
 * - a Details card: chat, sender, record type, message id and
 *   the message time (absolute + relative). The archival store
 *   keeps the message's own timestamp only — there is no separate
 *   delete/edit moment to show, and a footer note says so.
 * - an Actions card: Copy text (raw text, no BiDi controls),
 *   Open chat (ChatActivity.of — at the message for edits; the
 *   dialog itself for deleted messages, whose message is gone),
 *   and Delete this record (confirm dialog; removes just this
 *   row via DeletedStore.removeRecord and pops back to the list,
 *   which refreshes itself in onResume).
 *
 * BiDi follows the list's discipline (AminStoreListActivity.bidi):
 * titles, sender and bodies render inside LRM + isolates.
 */
public class AminHistoryDetailActivity extends BaseFragment {

    private static final int SEG_ONLY = 1;
    private static final int SEG_FIRST = 2;
    private static final int SEG_MID = 3;
    private static final int SEG_LAST = 4;

    private final String storeKey;
    private final String rawRecord;
    private DeletedStore.HistoryEntry entry;

    public AminHistoryDetailActivity(String storeKey, String rawRecord) {
        super();
        this.storeKey = storeKey;
        this.rawRecord = rawRecord;
    }

    @Override
    public View createView(Context context) {
        entry = DeletedStore.EDIT_KEY.equals(storeKey)
                ? DeletedStore.parseEditRecord(rawRecord)
                : DeletedStore.parseDeletedRecord(rawRecord);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(entry.edit ? "Edited message" : "Deleted message");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scroll = new ScrollView(context);
        scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
        scroll.setFillViewport(true);

        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, AndroidUtilities.dp(6), 0, AndroidUtilities.dp(18));
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        String chatTitle = DeletedStore.chatTitle(currentAccount, entry.dialogId);
        String senderName = DeletedStore.senderName(currentAccount, entry.dialogId);

        content.addView(buildMessageCard(context, chatTitle));
        content.addView(buildDetailsCard(context, chatTitle, senderName));
        content.addView(buildActionsCard(context));

        TextView note = new TextView(context);
        note.setText("Anti-Delete and Edit History save the message's own time — the exact "
                + "moment it was deleted or edited is not stored. Saved texts live only "
                + "on this device.");
        note.setTextSize(13);
        note.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        note.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(12),
                AndroidUtilities.dp(28), 0);
        content.addView(note, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        fragmentView = scroll;
        return scroll;
    }

    // ------------------------------------------------------------------
    // Cards
    // ------------------------------------------------------------------

    private View buildMessageCard(Context context, String chatTitle) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        card.setBackground(segmentDrawable(SEG_ONLY, cardFill()));
        cardMargins(card, true);

        // Header: chip + title + kind/time line.
        LinearLayout headerRow = new LinearLayout(context);
        headerRow.setOrientation(LinearLayout.HORIZONTAL);
        headerRow.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(headerRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout chip = new FrameLayout(context);
        GradientDrawable chipBg = new GradientDrawable();
        chipBg.setShape(GradientDrawable.RECTANGLE);
        chipBg.setColor(AminStoreListActivity.chipFill());
        chipBg.setCornerRadius(AndroidUtilities.dp(12));
        chip.setBackground(chipBg);
        headerRow.addView(chip, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(44), AndroidUtilities.dp(44)));
        TextView chipLetter = new TextView(context);
        chipLetter.setTextSize(18);
        chipLetter.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        chipLetter.setGravity(Gravity.CENTER);
        chipLetter.setText(chatTitle.isEmpty() ? "?"
                : chatTitle.substring(0, 1).toUpperCase(Locale.ROOT));
        chipLetter.setTextColor(AminStoreListActivity.accentColor());
        chip.addView(chipLetter, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams columnParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        columnParams.leftMargin = AndroidUtilities.dp(12);
        headerRow.addView(column, columnParams);

        TextView titleView = new TextView(context);
        titleView.setTextSize(17);
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(AminStoreListActivity.bidi(chatTitle));
        column.addView(titleView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        StringBuilder sub = new StringBuilder();
        if (entry.dialogId != 0) {
            sub.append(AminDialogPickerActivity.dialogKind(currentAccount, entry.dialogId));
        }
        String relative = AminStoreListActivity.relativeTime(entry.dateSec);
        if (!relative.isEmpty()) {
            if (sub.length() > 0) {
                sub.append(" · ");
            }
            sub.append(relative);
        }
        TextView subView = new TextView(context);
        subView.setTextSize(13);
        subView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        subView.setSingleLine(true);
        subView.setText(sub.toString());
        LinearLayout.LayoutParams subParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subParams.topMargin = AndroidUtilities.dp(2);
        column.addView(subView, subParams);

        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        dividerParams.topMargin = AndroidUtilities.dp(12);
        dividerParams.bottomMargin = AndroidUtilities.dp(12);
        card.addView(divider, dividerParams);

        if (entry.edit) {
            int red = Theme.getColor(Theme.key_text_RedRegular);
            int green = Theme.getColor(Theme.key_windowBackgroundWhiteGreenText);
            card.addView(blockLabel(context, "BEFORE", red), new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            card.addView(bodyBlock(context, entry.oldText, red, true),
                    blockParams(AndroidUtilities.dp(6)));
            card.addView(blockLabel(context, "AFTER", green), blockParams(AndroidUtilities.dp(14)));
            card.addView(bodyBlock(context, entry.newText, green, false),
                    blockParams(AndroidUtilities.dp(6)));
        } else {
            String label = AminStoreListActivity.kindLabel(entry.kind);
            card.addView(blockLabel(context, label.isEmpty() ? "MESSAGE" : label.toUpperCase(Locale.ROOT),
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText)),
                    new LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT));
            String body = entry.bodyText();
            if (body.isEmpty()) {
                body = "[" + (entry.kind != null ? entry.kind : "media") + "]";
            }
            TextView bodyView = new TextView(context);
            bodyView.setTextSize(15.5f);
            bodyView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            bodyView.setLineSpacing(AndroidUtilities.dp(3), 1.0f);
            bodyView.setTextIsSelectable(true);
            bodyView.setText(AminStoreListActivity.bidi(body));
            card.addView(bodyView, blockParams(AndroidUtilities.dp(6)));
        }
        return card;
    }

    private TextView blockLabel(Context context, String text, int color) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(12);
        label.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        label.setTextColor(color);
        return label;
    }

    private TextView bodyBlock(Context context, String text, int toneColor, boolean before) {
        TextView block = new TextView(context);
        block.setTextSize(15);
        block.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        block.setLineSpacing(AndroidUtilities.dp(3), 1.0f);
        block.setTextIsSelectable(true);
        block.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10),
                AndroidUtilities.dp(12), AndroidUtilities.dp(10));
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.RECTANGLE);
        bg.setColor(ColorUtils.blendARGB(cardFill(), toneColor, before ? 0.08f : 0.10f));
        bg.setCornerRadius(AndroidUtilities.dp(12));
        block.setBackground(bg);
        String value = text != null ? text : "";
        if (value.isEmpty()) {
            value = before ? "(no previous text was saved)" : "(empty)";
        }
        block.setText(AminStoreListActivity.bidi(value));
        return block;
    }

    private static LinearLayout.LayoutParams blockParams(int topMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.topMargin = topMargin;
        return params;
    }

    private View buildDetailsCard(Context context, String chatTitle, String senderName) {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);

        TextView label = sectionLabel(context, "Details");
        wrap.addView(label, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(segmentDrawable(SEG_ONLY, cardFill()));
        cardMargins(card, false);
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        boolean edits = entry.edit;
        addMetaRow(context, card, "Chat", AminStoreListActivity.bidi(chatTitle), true);
        if (entry.dialogId > 0) {
            addMetaRow(context, card, "Sender", AminStoreListActivity.bidi(senderName), false);
        } else if (entry.dialogId != 0) {
            addMetaRow(context, card, "Chat type",
                    AminDialogPickerActivity.dialogKind(currentAccount, entry.dialogId), false);
        }
        addMetaRow(context, card, "Record",
                edits ? "Edited message" : "Deleted message", false);
        String kind = AminStoreListActivity.kindLabel(entry.kind);
        if (!edits && !kind.isEmpty()) {
            addMetaRow(context, card, "Kind", kind, false);
        }
        if (entry.msgId > 0) {
            addMetaRow(context, card, "Message ID", String.valueOf(entry.msgId), false);
        }
        String absolute = AminStoreListActivity.absoluteTime(entry.dateSec);
        String relative = AminStoreListActivity.relativeTime(entry.dateSec);
        String when = absolute.isEmpty() ? "Unknown"
                : (relative.isEmpty() ? absolute : absolute + " (" + relative + ")");
        addMetaRow(context, card, "Message time", when, false);
        return wrap;
    }

    private void addMetaRow(Context context, LinearLayout card, String name,
                            String value, boolean first) {
        if (!first) {
            View divider = new View(context);
            divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
            LinearLayout.LayoutParams dividerParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 1);
            dividerParams.leftMargin = AndroidUtilities.dp(16);
            card.addView(divider, dividerParams);
        }
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(11),
                AndroidUtilities.dp(16), AndroidUtilities.dp(11));
        // Transparent: the rounded white background lives on the
        // card container, the rows and hairlines stack inside it.
        card.addView(row, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView nameView = new TextView(context);
        nameView.setText(name);
        nameView.setTextSize(14);
        nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        row.addView(nameView, new LinearLayout.LayoutParams(
                AndroidUtilities.dp(104), ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView valueView = new TextView(context);
        valueView.setText(value != null ? value : "");
        valueView.setTextSize(14);
        valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        row.addView(valueView, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
    }

    private View buildActionsCard(Context context) {
        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);

        wrap.addView(sectionLabel(context, "Actions"), new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        cardMargins(card, false);
        wrap.addView(card, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView copy = actionRow(context, "Copy text",
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), SEG_FIRST);
        copy.setOnClickListener(v -> {
            AndroidUtilities.addToClipboard(entry.copyText());
            bulletin(R.raw.copy, "Copied to clipboard");
        });
        card.addView(copy, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(hairline(context));

        TextView open = actionRow(context, "Open chat",
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), SEG_MID);
        open.setOnClickListener(v -> openChat());
        card.addView(open, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        card.addView(hairline(context));

        TextView delete = actionRow(context, "Delete this record",
                Theme.getColor(Theme.key_text_RedRegular), SEG_LAST);
        delete.setOnClickListener(v -> confirmDelete());
        card.addView(delete, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private TextView actionRow(Context context, String text, int color, int seg) {
        TextView row = new TextView(context);
        row.setText(text);
        row.setTextSize(16);
        row.setTextColor(color);
        row.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(13),
                AndroidUtilities.dp(16), AndroidUtilities.dp(13));
        row.setBackground(segmentDrawable(seg, cardFill()));
        row.setClickable(true);
        return row;
    }

    private View hairline(Context context) {
        View divider = new View(context);
        divider.setBackgroundColor(Theme.getColor(Theme.key_divider));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 1);
        params.leftMargin = AndroidUtilities.dp(16);
        divider.setLayoutParams(params);
        return divider;
    }

    private TextView sectionLabel(Context context, String text) {
        TextView label = new TextView(context);
        label.setText(text);
        label.setTextSize(14);
        label.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        label.setTextColor(AminStoreListActivity.accentColor());
        label.setPadding(AndroidUtilities.dp(28), AndroidUtilities.dp(22),
                AndroidUtilities.dp(16), AndroidUtilities.dp(2));
        return label;
    }

    private void cardMargins(View card, boolean first) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = AndroidUtilities.dp(12);
        params.rightMargin = AndroidUtilities.dp(12);
        params.topMargin = AndroidUtilities.dp(first ? 6 : 10);
        card.setLayoutParams(params);
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void openChat() {
        try {
            if (entry == null || entry.dialogId == 0) {
                bulletin(R.raw.error, "This record has no chat");
                return;
            }
            MessagesController controller = MessagesController.getInstance(currentAccount);
            boolean known = entry.dialogId > 0
                    ? controller.getUser(entry.dialogId) != null
                    : controller.getChat(-entry.dialogId) != null;
            if (!known) {
                bulletin(R.raw.error, "This chat is no longer available");
                return;
            }
            // Edits jump to the (still existing) message; a deleted
            // message is gone, so its record just opens the dialog.
            if (entry.edit && entry.msgId > 0) {
                presentFragment(ChatActivity.of(entry.dialogId, entry.msgId));
            } else {
                presentFragment(ChatActivity.of(entry.dialogId));
            }
        } catch (Throwable t) {
            bulletin(R.raw.error, "Could not open the chat");
        }
    }

    private void confirmDelete() {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Delete this record?");
        builder.setMessage("Only this saved copy is removed from this device. "
                + "The chat itself is not touched.");
        builder.setPositiveButton("Delete", (dialog, which) -> {
            Context context = getContext();
            if (context != null && DeletedStore.removeRecord(context, storeKey, rawRecord)) {
                bulletin(R.raw.ic_delete, "Record deleted");
            } else {
                bulletin(R.raw.error, "Record was already gone");
            }
            finishFragment();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void bulletin(int icon, String text) {
        try {
            BulletinFactory.of(this).createSimpleBulletin(icon, text).show();
        } catch (Throwable ignored) {
        }
    }

    // ------------------------------------------------------------------
    // Presentation helpers (the hub's extera language, kept local so
    // this page stands on its own — same pattern as the Google and
    // Hidden Chats pages)
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
}

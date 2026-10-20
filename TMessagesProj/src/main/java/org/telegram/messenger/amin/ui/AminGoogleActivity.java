package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.google.GoogleAuthManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Google Account page (v1.3.5) — the hub's "Google Account" row
 * lands here. Speaks the hub's extera design language (accent
 * section label, one rounded group card, hairline rows), like
 * the Hidden Chats page.
 *
 * Contents:
 * - a status card: "Connected as <email>" (or "Not connected")
 *   plus what the connection is FOR;
 * - Connect (runs the native flow via GoogleAuthActivity and
 *   refreshes on return) or Disconnect (confirm dialog; local
 *   cache cleared, grant revoked with Google best-effort);
 * - a footer with the honest bounds: only plugins declaring the
 *   `google` capability can request tokens; tokens stay on this
 *   device (Keystore-encrypted); Play Services is required;
 *   Google may ask to re-consent periodically for unverified
 *   apps.
 *
 * All flows are Throwable-isolated like the rest of the layer.
 */
public class AminGoogleActivity extends BaseFragment {

    private static final int KIND_HEADER = 0;
    private static final int KIND_INFO = 1;
    private static final int KIND_ACTION = 2;
    private static final int KIND_FOOTER = 3;

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
    }

    private RecyclerListView listView;
    private GoogleAdapter adapter;
    private final List<Row> rows = new ArrayList<>();

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Google Account");
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
        adapter = new GoogleAdapter();
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
        // Returning from the consent flow must refresh the status.
        rebuild();
    }

    private void rebuild() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        rows.clear();
        try {
            boolean connected = GoogleAuthManager.isSignedIn(context);
            String email = GoogleAuthManager.connectedEmail(context);
            addHeader("Google Account");
            if (connected) {
                addInfo("Connected as " + (email != null ? email : "your Google account") + ".\n"
                        + "Plugins that declare the google capability can ask for an access "
                        + "token and call Google services (like Gemini) with your own "
                        + "account — no API key, no paid plan.");
                addAction("disconnect", "Disconnect Google Account", null);
            } else {
                addInfo("Not connected.\n"
                        + "Connect your Google account so AminGram plugins can use Google "
                        + "services (like Gemini) with your own account instead of a paid "
                        + "API key.");
                addAction("connect", "Connect Google Account", null);
            }
        } catch (Throwable ignored) {
        }
        Row footer = new Row();
        footer.kind = KIND_FOOTER;
        footer.title = "Sign-in requests Google Cloud Platform access — a broad Google "
                + "permission (Google will not register the narrower Gemini-only scope "
                + "for this app), so google-capable plugins can call Gemini with your "
                + "account. Tokens are stored only on this device, encrypted with an Android "
                + "Keystore key, and are sent nowhere except to Google. amin.google exists "
                + "only in plugins whose manifest declares the google capability. Sign-in "
                + "needs Google Play Services, and because AminGram is not verified with "
                + "Google, Google may ask you to re-consent every week or so.";
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
            if (row.kind == KIND_ACTION) {
                if ("connect".equals(row.action)) {
                    connect(context);
                } else if ("disconnect".equals(row.action)) {
                    confirmDisconnect(context);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void connect(Context context) {
        GoogleAuthManager.startInteractiveAuth(context, (success, error) -> {
            try {
                if (success) {
                    bulletin(R.raw.contact_check, "Google account connected");
                } else if (error != null) {
                    bulletin(R.raw.error, error);
                }
            } catch (Throwable ignored) {
            }
            rebuild();
        });
    }

    private void confirmDisconnect(Context context) {
        if (getParentActivity() == null) {
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Disconnect Google Account?");
        builder.setMessage("The token stored on this device is removed and access is "
                + "revoked with Google (best effort). Plugins using amin.google stop "
                + "working until you connect again.");
        builder.setPositiveButton("Disconnect", (dialog, which) -> {
            try {
                GoogleAuthManager.disconnect(context);
                bulletin(R.raw.contact_check, "Google account disconnected");
            } catch (Throwable ignored) {
            }
            rebuild();
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
    // this page stands on its own — same pattern as the Hidden
    // Chats page)
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

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private class GoogleAdapter extends RecyclerListView.SelectionAdapter {

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
            return rows.get(position).kind == KIND_ACTION;
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

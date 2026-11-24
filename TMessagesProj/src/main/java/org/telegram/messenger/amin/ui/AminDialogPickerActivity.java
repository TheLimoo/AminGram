package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.messenger.amin.AminHooks;
import org.telegram.messenger.amin.modules.FolderChatQolModule;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Dialog picker used by the Plugins hub and forward flows (AyuForward,
 * Forward as Copy, Hide a chat, Send-When-Online target).
 *
 * Phase B.4 (Folder & Chat QoL): when {@link FolderChatQolModule#foldersInForward}
 * is enabled, renders a horizontal folder tab bar at the top ("All Chats" +
 * every user folder from {@code MessagesController.dialogFilters}) so the user
 * can filter the picker by folder in one tap, plus a pinned "Saved Messages"
 * entry at the top of the list when picking a forward destination.
 */
public class AminDialogPickerActivity extends BaseFragment {

    public interface OnDialogPicked {
        void onDialogPicked(long dialogId, String name);
    }

    private final String title;
    private final boolean usersOnly;
    private final OnDialogPicked listener;
    private final List<TLRPC.Dialog> dialogs = new ArrayList<>();
    private int selectedFilterIndex = -1; // -1 = All chats
    private RecyclerListView.SelectionAdapter listAdapter;
    private LinearLayout folderTabsRow;

    public AminDialogPickerActivity(String title, boolean usersOnly, OnDialogPicked listener) {
        super();
        this.title = title;
        this.usersOnly = usersOnly;
        this.listener = listener;
    }

    public static String dialogName(int account, long dialogId) {
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (dialogId == selfId && selfId != 0) {
            return "Saved Messages";
        }
        MessagesController controller = MessagesController.getInstance(account);
        if (dialogId > 0) {
            TLRPC.User user = controller.getUser(dialogId);
            if (user != null) {
                return UserObject.getUserName(user);
            }
            return "User " + dialogId;
        }
        TLRPC.Chat chat = controller.getChat(-dialogId);
        if (chat != null && chat.title != null) {
            return chat.title;
        }
        return "Chat " + (-dialogId);
    }

    public static String dialogKind(int account, long dialogId) {
        long selfId = UserConfig.getInstance(account).getClientUserId();
        if (dialogId == selfId && selfId != 0) {
            return "Self";
        }
        if (dialogId > 0) {
            TLRPC.User user = MessagesController.getInstance(account).getUser(dialogId);
            return user != null && user.bot ? "Bot" : "User";
        }
        TLRPC.Chat chat = MessagesController.getInstance(account).getChat(-dialogId);
        if (chat != null) {
            return chat.broadcast ? "Channel" : "Group";
        }
        return "Chat";
    }

    private void reloadDialogs() {
        dialogs.clear();
        MessagesController controller = MessagesController.getInstance(currentAccount);
        ArrayList<TLRPC.Dialog> source = null;
        if (selectedFilterIndex >= 0 && controller.dialogFilters != null
                && selectedFilterIndex < controller.dialogFilters.size()) {
            MessagesController.DialogFilter filter = controller.dialogFilters.get(selectedFilterIndex);
            if (filter != null && filter.dialogs != null) {
                source = filter.dialogs;
            }
        }
        if (source == null) {
            source = controller.getDialogs(0);
        }
        source = AminHooks.filterHiddenDialogs(source);
        long selfId = UserConfig.getInstance(currentAccount).getClientUserId();
        boolean hasSelf = false;
        if (source != null) {
            for (TLRPC.Dialog dialog : source) {
                if (dialog == null) {
                    continue;
                }
                if (usersOnly && dialog.id <= 0) {
                    continue;
                }
                if (dialog.id == selfId) {
                    hasSelf = true;
                }
                dialogs.add(dialog);
            }
        }
        if (!usersOnly && selectedFilterIndex == -1 && selfId != 0 && !hasSelf) {
            TLRPC.TL_dialog selfDialog = new TLRPC.TL_dialog();
            selfDialog.id = selfId;
            dialogs.add(0, selfDialog);
        }
        if (listAdapter != null) {
            listAdapter.notifyDataSetChanged();
        }
    }

    private void rebuildFolderTabs(Context context) {
        if (folderTabsRow == null) {
            return;
        }
        folderTabsRow.removeAllViews();
        MessagesController controller = MessagesController.getInstance(currentAccount);
        ArrayList<MessagesController.DialogFilter> filters = controller.dialogFilters;
        addFolderChip(context, "All Chats", -1);
        if (filters != null) {
            for (int i = 0; i < filters.size(); i++) {
                MessagesController.DialogFilter f = filters.get(i);
                if (f == null || f.isDefault()) {
                    continue;
                }
                String label = f.name == null || f.name.isEmpty() ? ("Folder " + (i + 1)) : f.name;
                addFolderChip(context, label, i);
            }
        }
    }

    private void addFolderChip(Context context, String label, int filterIndex) {
        boolean active = (selectedFilterIndex == filterIndex);
        TextView chip = new TextView(context);
        chip.setText(label);
        chip.setSingleLine(true);
        chip.setEllipsize(TextUtils.TruncateAt.END);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        chip.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(6),
                AndroidUtilities.dp(14), AndroidUtilities.dp(6));
        int accent = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4);
        int textColor = active ? 0xFFFFFFFF : Theme.getColor(Theme.key_windowBackgroundWhiteBlackText);
        chip.setTextColor(textColor);
        GradientDrawable bg = new GradientDrawable();
        bg.setCornerRadius(AndroidUtilities.dp(16));
        bg.setColor(active ? accent : Theme.getColor(Theme.key_windowBackgroundGray));
        chip.setBackground(bg);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.rightMargin = AndroidUtilities.dp(8);
        chip.setOnClickListener(v -> {
            selectedFilterIndex = filterIndex;
            rebuildFolderTabs(context);
            reloadDialogs();
        });
        folderTabsRow.addView(chip, lp);
    }

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

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        boolean showFolders = AminHooks.moduleEnabled("folder_chat_qol")
                && FolderChatQolModule.foldersInForward(context);
        MessagesController controller = MessagesController.getInstance(currentAccount);
        if (showFolders && controller.dialogFilters != null && !controller.dialogFilters.isEmpty()) {
            HorizontalScrollView scroll = new HorizontalScrollView(context);
            scroll.setHorizontalScrollBarEnabled(false);
            scroll.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            folderTabsRow = new LinearLayout(context);
            folderTabsRow.setOrientation(LinearLayout.HORIZONTAL);
            folderTabsRow.setGravity(Gravity.CENTER_VERTICAL);
            folderTabsRow.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(8),
                    AndroidUtilities.dp(14), AndroidUtilities.dp(8));
            scroll.addView(folderTabsRow, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            root.addView(scroll, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            rebuildFolderTabs(context);
        }

        reloadDialogs();

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listAdapter = new RecyclerListView.SelectionAdapter() {
            @Override
            public int getItemCount() {
                return Math.max(1, dialogs.size());
            }

            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return !dialogs.isEmpty();
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
                if (dialogs.isEmpty()) {
                    cell.setText("No chats in this folder", false);
                    return;
                }
                TLRPC.Dialog dialog = dialogs.get(position);
                cell.setTextAndValue(dialogName(currentAccount, dialog.id),
                        dialogKind(currentAccount, dialog.id), position < dialogs.size() - 1);
            }
        };
        listView.setAdapter(listAdapter);
        listView.setOnItemClickListener((view, position) -> {
            if (dialogs.isEmpty() || position >= dialogs.size()) {
                return;
            }
            TLRPC.Dialog dialog = dialogs.get(position);
            if (listener != null) {
                listener.onDialogPicked(dialog.id, dialogName(currentAccount, dialog.id));
            }
            finishFragment();
        });
        root.addView(listView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        fragmentView = root;
        return root;
    }
}

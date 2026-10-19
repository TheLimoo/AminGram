package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Components.RecyclerListView;

import java.util.ArrayList;
import java.util.List;

/**
 * Simple dialog picker used by the Plugins hub (hide a chat, choose a
 * Send-When-Online target). Lists the current account's main dialog
 * list with names; optionally restricted to user dialogs.
 */
public class AminDialogPickerActivity extends BaseFragment {

    public interface OnDialogPicked {
        void onDialogPicked(long dialogId, String name);
    }

    private final String title;
    private final boolean usersOnly;
    private final OnDialogPicked listener;
    private final List<TLRPC.Dialog> dialogs = new ArrayList<>();

    public AminDialogPickerActivity(String title, boolean usersOnly, OnDialogPicked listener) {
        super();
        this.title = title;
        this.usersOnly = usersOnly;
        this.listener = listener;
    }

    public static String dialogName(int account, long dialogId) {
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

    @Override
    public View createView(Context context) {
        dialogs.clear();
        ArrayList<TLRPC.Dialog> all = MessagesController.getInstance(currentAccount).getDialogs(0);
        if (all != null) {
            for (TLRPC.Dialog dialog : all) {
                if (dialog == null) {
                    continue;
                }
                if (usersOnly && dialog.id <= 0) {
                    continue;
                }
                dialogs.add(dialog);
            }
        }

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

        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        RecyclerListView listView = new RecyclerListView(context);
        listView.setLayoutManager(new LinearLayoutManager(context, LinearLayoutManager.VERTICAL, false));
        listView.setAdapter(new RecyclerListView.SelectionAdapter() {
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
                    cell.setText("No chats found", false);
                    return;
                }
                TLRPC.Dialog dialog = dialogs.get(position);
                cell.setTextAndValue(dialogName(currentAccount, dialog.id),
                        dialogKind(currentAccount, dialog.id), position < dialogs.size() - 1);
            }
        });
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
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fragmentView = layout;
        return layout;
    }
}

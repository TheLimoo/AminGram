package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;

/**
 * In-app code editor for one JS plugin (v2.2): change a plugin's
 * code and save it in place — no delete-and-reinstall dance.
 *
 * Saving routes through {@link JsPluginManager#install}, the same
 * validated update-in-place path as file import and paste-code:
 * the manifest is parsed, the JavaScript is compile-checked and
 * probe-evaluated, and the SETTINGS schema is validated BEFORE the
 * code file on disk is touched, so a broken edit can never clobber
 * the working plugin — the editor just shows install's readable
 * error and stays open. A successful save preserves the plugin's
 * storage, settings, enabled state and log, hot-reloads the
 * runtime, and (via install) keeps the previous code as a rollback
 * copy the "Restore" action can bring back in one tap.
 */
public class AminPluginEditorActivity extends BaseFragment {

    private static final int MENU_SAVE = 1;
    private static final int MENU_RESTORE = 2;

    private final String pluginId;
    private final String pluginName;
    private String originalSource = "";
    private EditText editText;

    public AminPluginEditorActivity(String pluginId, String pluginName) {
        super();
        this.pluginId = pluginId;
        this.pluginName = pluginName;
    }

    @Override
    public View createView(Context context) {
        String code = JsPluginManager.readCode(context, pluginId);
        originalSource = code != null ? code : "";
        boolean hasPrev = JsPluginManager.readPrevCode(context, pluginId) != null;

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle((pluginName != null ? pluginName : "Plugin") + " — Edit code");
        ActionBarMenu menu = actionBar.createMenu();
        if (hasPrev) {
            menu.addItem(MENU_RESTORE, "Restore");
        }
        menu.addItem(MENU_SAVE, "Save");
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    onBackAction(context);
                } else if (id == MENU_SAVE) {
                    save(context);
                } else if (id == MENU_RESTORE) {
                    confirmRestore(context);
                }
            }
        });

        FrameLayout layout = new FrameLayout(context);
        layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        editText = new EditText(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        editText.setTypeface(Typeface.MONOSPACE);
        editText.setGravity(Gravity.TOP | Gravity.START);
        editText.setInputType(InputType.TYPE_CLASS_TEXT
                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setSingleLine(false);
        editText.setHorizontallyScrolling(false);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        editText.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        int pad = AndroidUtilities.dp(12);
        editText.setPadding(pad, pad, pad, pad);
        editText.setText(originalSource);
        editText.setSelection(0);
        layout.addView(editText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fragmentView = layout;
        return layout;
    }

    private boolean isDirty() {
        return editText != null
                && !editText.getText().toString().equals(originalSource);
    }

    private void onBackAction(Context context) {
        if (!isDirty()) {
            finishFragment();
            return;
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Discard changes?");
        builder.setMessage("Your edits to this plugin's code are not saved yet.");
        builder.setPositiveButton("Discard", (dialog, which) -> finishFragment());
        builder.setNegativeButton("Keep editing", null);
        showDialog(builder.create());
    }

    private void save(Context context) {
        String source = editText.getText().toString();
        if (source.equals(originalSource)) {
            BulletinFactory.of(this)
                    .createSimpleBulletin(R.raw.info, "No changes to save").show();
            return;
        }
        // Validated update-in-place: install returns null on success
        // or a readable error, and the on-disk code is only replaced
        // after every check has passed.
        String error = JsPluginManager.installWithSource(context, source, "editor");
        if (error != null) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Couldn't update the plugin");
            builder.setMessage(error
                    + "\n\nYour current code is unchanged — the plugin on disk is untouched.");
            builder.setPositiveButton("OK", null);
            showDialog(builder.create());
            return;
        }
        BulletinFactory.of(this)
                .createSimpleBulletin(R.raw.contact_check, "Plugin updated").show();
        finishFragment();
    }

    private void confirmRestore(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Restore previous version?");
        builder.setMessage("The code from before the last update replaces the current code. "
                + "Settings and plugin data are kept.");
        builder.setPositiveButton("Restore", (dialog, which) -> {
            String prev = JsPluginManager.readPrevCode(context, pluginId);
            if (prev == null) {
                BulletinFactory.of(AminPluginEditorActivity.this)
                        .createSimpleBulletin(R.raw.info, "No previous version saved").show();
                return;
            }
            String error = JsPluginManager.installWithSource(context, prev, "editor");
            if (error != null) {
                AlertDialog.Builder errorBuilder = new AlertDialog.Builder(getParentActivity());
                errorBuilder.setTitle("Couldn't restore");
                errorBuilder.setMessage(error);
                errorBuilder.setPositiveButton("OK", null);
                showDialog(errorBuilder.create());
                return;
            }
            BulletinFactory.of(AminPluginEditorActivity.this)
                    .createSimpleBulletin(R.raw.contact_check, "Previous version restored").show();
            finishFragment();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }
}

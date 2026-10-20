package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.amin.plugins.js.JsPlugin;
import org.telegram.messenger.amin.plugins.js.JsPluginManager;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.TextInfoPrivacyCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/**
 * One JS plugin's page in the hub: about info, the watchdog status
 * (errors today / suspension + resume), the enable toggle, the
 * settings page generated from the plugin's SETTINGS schema v2
 * (bool / text / number / slider / select / header rows, help
 * subtitles, dependsOn visibility), its log (with an
 * All/Errors/Warnings filter), and delete. Reached from the
 * "Your Plugins" section of the hub.
 *
 * Every settings write flows through {@link #writeSetting}: the raw
 * string lands in the same per-plugin storage amin.storage reads
 * (JsPluginManager.storageSetRaw) and JsPluginManager.onSettingWritten
 * is notified, then the row list is rebuilt so dependsOn dependents
 * appear/disappear immediately.
 */
public class AminJsPluginActivity extends BaseFragment {

    private static final int TYPE_HEADER = 0;
    private static final int TYPE_INFO = 1;
    private static final int TYPE_TOGGLE = 2;
    private static final int TYPE_SETTING_BOOL = 3;
    private static final int TYPE_SETTING_TEXT = 4;
    private static final int TYPE_ACTION = 5;
    private static final int TYPE_SETTING_NUMBER = 6;
    private static final int TYPE_SETTING_SLIDER = 7;
    private static final int TYPE_SETTING_SELECT = 8;
    private static final int TYPE_SETTING_HEADER = 9;
    private static final int TYPE_HELP = 10;
    private static final int TYPE_STATUS_ERRORS = 11;
    private static final int TYPE_STATUS_SUSPENDED = 12;
    private static final int TYPE_RESUME = 13;

    private final String pluginId;
    private JsPlugin plugin;
    private RecyclerListView listView;
    private PluginPageAdapter adapter;

    // Row indices, rebuilt by buildRows().
    private final List<Integer> rowTypes = new ArrayList<>();
    private final List<Object> rowData = new ArrayList<>();
    private int toggleRow = -1;
    private int logRow = -1;
    private int deleteRow = -1;
    private int errorsRow = -1;
    private int suspendedRow = -1;
    private int resumeRow = -1;

    public AminJsPluginActivity(String pluginId) {
        super();
        this.pluginId = pluginId;
    }

    @Override
    public View createView(Context context) {
        plugin = JsPluginManager.get(context, pluginId);
        buildRows(context);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(plugin != null ? plugin.name : "Plugin");
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
        adapter = new PluginPageAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onRowClick(context, view, position));
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        fragmentView = layout;
        return layout;
    }

    private void buildRows(Context context) {
        rowTypes.clear();
        rowData.clear();
        toggleRow = -1;
        logRow = -1;
        deleteRow = -1;
        errorsRow = -1;
        suspendedRow = -1;
        resumeRow = -1;
        if (plugin == null) {
            return;
        }
        addRow(TYPE_HEADER, "About");
        StringBuilder about = new StringBuilder();
        about.append(plugin.description);
        about.append("\n\nVersion ").append(plugin.version)
                .append(" · by ").append(plugin.author);
        about.append("\nCapabilities: ").append(plugin.capabilitiesText());
        StringBuilder hooks = new StringBuilder();
        if (plugin.hasMessage) {
            hooks.append("onMessage ");
        }
        if (plugin.hasCommand) {
            hooks.append("onCommand ");
        }
        if (plugin.hasMenu) {
            hooks.append("onMenuAction ");
        }
        if (hooks.length() > 0) {
            about.append("\nHooks: ").append(hooks.toString().trim());
        }
        addRow(TYPE_INFO, about.toString());

        // Watchdog status (v2.1): suspension first (prominent, with
        // its Resume action), then today's error count linking into
        // the log viewer.
        boolean suspended = JsPluginManager.isSuspended(plugin.id);
        int errorsToday = JsPluginManager.errorsToday(plugin.id);
        if (suspended || errorsToday > 0) {
            addRow(TYPE_HEADER, "Status");
            if (suspended) {
                suspendedRow = addRow(TYPE_STATUS_SUSPENDED, "⏸ Suspended — error storm");
                resumeRow = addRow(TYPE_RESUME, "Resume plugin");
            }
            if (errorsToday > 0) {
                errorsRow = addRow(TYPE_STATUS_ERRORS,
                        "⚠ " + errorsToday + (errorsToday == 1 ? " error" : " errors") + " today");
            }
        }

        toggleRow = addRow(TYPE_TOGGLE, plugin);
        if (!plugin.settings.isEmpty()) {
            addRow(TYPE_HEADER, "Settings");
            for (JsPlugin.SettingDef def : plugin.settings) {
                if (!dependsOnVisible(context, def)) {
                    continue;
                }
                if ("header".equals(def.type)) {
                    addRow(TYPE_SETTING_HEADER, def);
                    continue;
                }
                int type;
                if ("bool".equals(def.type)) {
                    type = TYPE_SETTING_BOOL;
                } else if ("number".equals(def.type)) {
                    type = TYPE_SETTING_NUMBER;
                } else if ("slider".equals(def.type)) {
                    type = TYPE_SETTING_SLIDER;
                } else if ("select".equals(def.type)) {
                    type = TYPE_SETTING_SELECT;
                } else {
                    type = TYPE_SETTING_TEXT;
                }
                addRow(type, def);
                if (def.help != null && !def.help.isEmpty()) {
                    addRow(TYPE_HELP, def.help);
                }
            }
        }
        addRow(TYPE_HEADER, "Plugin");
        logRow = addRow(TYPE_ACTION, "View log");
        deleteRow = addRow(TYPE_ACTION, "Delete plugin");
    }

    private int addRow(int type, Object data) {
        rowTypes.add(type);
        rowData.add(data);
        return rowTypes.size() - 1;
    }

    /** dependsOn gate, evaluated here in buildRows() (and therefore
     *  re-evaluated on every refresh after a write): the row exists
     *  only when the raw stored value of dependsOnKey string-equals
     *  dependsOnEquals. No dependsOnKey → always visible. */
    private boolean dependsOnVisible(Context context, JsPlugin.SettingDef def) {
        if (def.dependsOnKey == null || def.dependsOnKey.isEmpty()) {
            return true;
        }
        String current = JsPluginManager.storageGetRaw(context, plugin.id, def.dependsOnKey);
        if (def.dependsOnEquals == null) {
            return current == null;
        }
        return def.dependsOnEquals.equals(current);
    }

    /** The one write path for every settings row: persist the raw
     *  string, notify the manager (watchdog / live-reload hooks),
     *  then rebuild the rows so dependsOn dependents and the
     *  watchdog status refresh immediately. */
    private void writeSetting(Context context, JsPlugin.SettingDef def, String newValue) {
        String oldValue = JsPluginManager.storageGetRaw(context, plugin.id, def.key);
        if (oldValue == null) {
            oldValue = "bool".equals(def.type)
                    ? (boolValue(context, def) ? "true" : "false")
                    : textValue(context, def);
        }
        JsPluginManager.storageSetRaw(context, plugin.id, def.key, newValue);
        JsPluginManager.onSettingWritten(context, plugin.id, def.key, newValue, oldValue);
        refreshPage(context);
    }

    private void refreshPage(Context context) {
        buildRows(context);
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private void onRowClick(Context context, View view, int position) {
        if (plugin == null || position < 0 || position >= rowTypes.size()) {
            return;
        }
        int type = rowTypes.get(position);
        if (position == toggleRow && type == TYPE_TOGGLE) {
            boolean now = !plugin.enabled;
            JsPluginManager.setEnabled(context, plugin, now);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setTextAndCheck(plugin.name, now, true);
            }
        } else if (position == resumeRow && type == TYPE_RESUME) {
            JsPluginManager.resumePlugin(context, plugin.id);
            BulletinFactory.of(AminJsPluginActivity.this)
                    .createSimpleBulletin(R.raw.contact_check, "Plugin resumed").show();
            refreshPage(context);
        } else if (position == errorsRow && type == TYPE_STATUS_ERRORS) {
            presentFragment(new PluginLogFragment(plugin.id, plugin.name,
                    PluginLogFragment.FILTER_ERRORS));
        } else if (type == TYPE_SETTING_BOOL) {
            JsPlugin.SettingDef def = (JsPlugin.SettingDef) rowData.get(position);
            boolean now = !boolValue(context, def);
            writeSetting(context, def, now ? "true" : "false");
        } else if (type == TYPE_SETTING_TEXT) {
            editTextSetting(context, (JsPlugin.SettingDef) rowData.get(position));
        } else if (type == TYPE_SETTING_NUMBER) {
            editNumberSetting(context, (JsPlugin.SettingDef) rowData.get(position));
        } else if (type == TYPE_SETTING_SELECT) {
            pickSelectSetting(context, (JsPlugin.SettingDef) rowData.get(position));
        } else if (position == logRow) {
            presentFragment(new PluginLogFragment(plugin.id, plugin.name,
                    PluginLogFragment.FILTER_ALL));
        } else if (position == deleteRow) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
            builder.setTitle("Delete " + plugin.name + "?");
            builder.setMessage("The plugin file, its settings and its log are removed.");
            builder.setPositiveButton("Delete", (dialog, which) -> {
                JsPluginManager.delete(context, plugin);
                BulletinFactory.of(AminJsPluginActivity.this)
                        .createSimpleBulletin(R.raw.ic_delete, "Plugin deleted").show();
                finishFragment();
            });
            builder.setNegativeButton("Cancel", null);
            showDialog(builder.create());
        }
    }

    private boolean boolValue(Context context, JsPlugin.SettingDef def) {
        String raw = JsPluginManager.storageGetRaw(context, plugin.id, def.key);
        if (raw != null) {
            return "true".equals(raw);
        }
        return def.def instanceof Boolean && (Boolean) def.def;
    }

    private String textValue(Context context, JsPlugin.SettingDef def) {
        String raw = JsPluginManager.storageGetRaw(context, plugin.id, def.key);
        if (raw != null) {
            return raw;
        }
        return def.def == null ? "" : String.valueOf(def.def);
    }

    // ------------------------------------------------------------------
    // Numeric helpers (number + slider share the canonical form:
    // integers write as "42", decimals as the shortest exact string)
    // ------------------------------------------------------------------

    private static boolean isIntegral(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value)
                && value == Math.rint(value);
    }

    private static String formatNumber(double value) {
        if (isIntegral(value) && Math.abs(value) < 1e15) {
            return Long.toString(Math.round(value));
        }
        return new BigDecimal(value).setScale(10, RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }

    private static double parseNumber(String raw, double fallback) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(raw.trim());
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private double numberValue(Context context, JsPlugin.SettingDef def) {
        double fallback = def.def instanceof Number
                ? ((Number) def.def).doubleValue() : 0;
        String raw = JsPluginManager.storageGetRaw(context, plugin.id, def.key);
        return parseNumber(raw, fallback);
    }

    private static double sliderMin(JsPlugin.SettingDef def) {
        return def.min != null ? def.min : 0;
    }

    private static double sliderMax(JsPlugin.SettingDef def) {
        return def.max != null ? def.max : 100;
    }

    /** Integer-only entry when the schema speaks in whole numbers:
     *  an integral step, or an integral default bounded by integral
     *  min/max. Otherwise the dialog accepts decimals. */
    private static boolean integerOnly(JsPlugin.SettingDef def) {
        if (def.step != null) {
            return isIntegral(def.step);
        }
        if (def.def instanceof Number && !isIntegral(((Number) def.def).doubleValue())) {
            return false;
        }
        if (def.min != null && !isIntegral(def.min)) {
            return false;
        }
        return def.max == null || isIntegral(def.max);
    }

    // ------------------------------------------------------------------
    // Editors
    // ------------------------------------------------------------------

    private void editTextSetting(Context context, JsPlugin.SettingDef def) {
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setText(textValue(context, def));
        editText.setHint(def.title);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        if (def.multiline) {
            editText.setSingleLine(false);
            editText.setInputType(InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
            editText.setMinLines(3);
            editText.setGravity(Gravity.TOP | Gravity.START);
        }
        if (def.maxLength > 0) {
            editText.setFilters(new InputFilter[]{
                    new InputFilter.LengthFilter(def.maxLength)});
        }

        FrameLayout container = new FrameLayout(context);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8),
                AndroidUtilities.dp(24), 0);
        container.addView(editText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(def.title);
        builder.setView(container);
        builder.setPositiveButton("Save", (dialog, which) ->
                writeSetting(context, def, editText.getText().toString()));
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void editNumberSetting(Context context, JsPlugin.SettingDef def) {
        boolean integers = integerOnly(def);
        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setText(formatNumber(numberValue(context, def)));
        editText.setHint(def.title);
        editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        int inputType = InputType.TYPE_CLASS_NUMBER;
        if (!integers) {
            inputType |= InputType.TYPE_NUMBER_FLAG_DECIMAL;
        }
        if ((def.min != null && def.min < 0) || numberValue(context, def) < 0) {
            inputType |= InputType.TYPE_NUMBER_FLAG_SIGNED;
        }
        editText.setInputType(inputType);
        editText.setSingleLine(true);

        FrameLayout container = new FrameLayout(context);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8),
                AndroidUtilities.dp(24), 0);
        container.addView(editText, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(def.title);
        if (def.help != null && !def.help.isEmpty()) {
            builder.setMessage(def.help);
        }
        builder.setView(container);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String entered = editText.getText() == null
                    ? "" : editText.getText().toString().trim();
            double value;
            try {
                value = Double.parseDouble(entered);
            } catch (Exception e) {
                BulletinFactory.of(AminJsPluginActivity.this)
                        .createSimpleBulletin(R.raw.error,
                                "Not a number — setting not changed").show();
                return;
            }
            if (integers) {
                value = Math.rint(value);
            }
            // Step snapping (anchored at min, or 0 when min is unset).
            if (def.step != null && def.step > 0) {
                double anchor = def.min != null ? def.min : 0;
                value = anchor + Math.round((value - anchor) / def.step) * def.step;
            }
            // Range: clamp, and say so (with the range) when it bites.
            if ((def.min != null && value < def.min)
                    || (def.max != null && value > def.max)) {
                double clamped = value;
                if (def.min != null) {
                    clamped = Math.max(clamped, def.min);
                }
                if (def.max != null) {
                    clamped = Math.min(clamped, def.max);
                }
                value = clamped;
                BulletinFactory.of(AminJsPluginActivity.this)
                        .createSimpleBulletin(R.raw.error,
                                "Clamped to the allowed range "
                                        + rangeText(def)).show();
            }
            writeSetting(context, def, formatNumber(value));
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private static String rangeText(JsPlugin.SettingDef def) {
        String lo = def.min != null ? formatNumber(def.min) : "…";
        String hi = def.max != null ? formatNumber(def.max) : "…";
        return lo + " – " + hi;
    }

    private void pickSelectSetting(Context context, JsPlugin.SettingDef def) {
        List<String> values = def.optionValues != null
                ? def.optionValues : new ArrayList<>();
        List<String> labels = def.optionLabels != null && !def.optionLabels.isEmpty()
                ? def.optionLabels : values;
        if (values.isEmpty()) {
            BulletinFactory.of(AminJsPluginActivity.this)
                    .createSimpleBulletin(R.raw.error, "This setting has no options").show();
            return;
        }
        CharSequence[] items = new CharSequence[labels.size()];
        for (int i = 0; i < labels.size(); i++) {
            items[i] = labels.get(i);
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle(def.title);
        builder.setItems(items, (dialog, which) -> {
            if (which >= 0 && which < values.size()) {
                writeSetting(context, def, values.get(which));
            }
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private String selectLabel(Context context, JsPlugin.SettingDef def) {
        String value = textValue(context, def);
        if (def.optionValues != null) {
            int index = def.optionValues.indexOf(value);
            if (index >= 0 && def.optionLabels != null && index < def.optionLabels.size()) {
                return def.optionLabels.get(index);
            }
        }
        return value;
    }

    /** Divider for value rows: suppressed when the next row is this
     *  setting's own help subtitle (the subtitle closes the row). */
    private boolean dividerFor(int position) {
        if (position >= rowTypes.size() - 1) {
            return false;
        }
        return rowTypes.get(position + 1) != TYPE_HELP;
    }

    // ------------------------------------------------------------------
    // Slider row view (self-contained, in the hub's custom-view
    // language: title + gray value on one line, control underneath,
    // white card background, 16dp insets)
    // ------------------------------------------------------------------

    private static class SliderSettingCell extends LinearLayout {
        interface OnRelease {
            void onRelease(JsPlugin.SettingDef def, double value);
        }

        private final TextView titleView;
        private final TextView valueView;
        private final SeekBar seekBar;
        private JsPlugin.SettingDef def;
        private double min;
        private double max;
        private int steps;
        private OnRelease onRelease;

        SliderSettingCell(Context context) {
            super(context);
            setOrientation(VERTICAL);
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(10),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(8));
            setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

            LinearLayout top = new LinearLayout(context);
            top.setOrientation(HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            addView(top, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            titleView = new TextView(context);
            titleView.setTextSize(16);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            titleView.setSingleLine(true);
            titleView.setEllipsize(android.text.TextUtils.TruncateAt.END);
            top.addView(titleView, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            valueView = new TextView(context);
            valueView.setTextSize(15);
            valueView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            valueView.setSingleLine(true);
            top.addView(valueView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            seekBar = new SeekBar(context);
            LinearLayout.LayoutParams seekParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            seekParams.topMargin = AndroidUtilities.dp(2);
            addView(seekBar, seekParams);
            seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
                    valueView.setText(formatNumber(valueForProgress(progress)));
                }

                @Override
                public void onStartTrackingTouch(SeekBar bar) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar bar) {
                    // Writes happen on release only, never per-tick.
                    if (onRelease != null && def != null) {
                        onRelease.onRelease(def, valueForProgress(bar.getProgress()));
                    }
                }
            });
        }

        private double valueForProgress(int progress) {
            if (steps <= 0) {
                return min;
            }
            double value = min + (max - min) * progress / steps;
            if (def != null && def.step != null && def.step > 0) {
                value = min + Math.round((value - min) / def.step) * def.step;
            }
            return Math.max(min, Math.min(max, value));
        }

        void bind(JsPlugin.SettingDef settingDef, double current, OnRelease listener) {
            def = settingDef;
            onRelease = listener;
            min = sliderMin(settingDef);
            max = sliderMax(settingDef);
            if (max < min) {
                max = min;
            }
            if (settingDef.step != null && settingDef.step > 0) {
                steps = (int) Math.max(1, Math.min(10000,
                        Math.round((max - min) / settingDef.step)));
            } else {
                steps = 1000;
            }
            seekBar.setMax(steps);
            double clamped = Math.max(min, Math.min(max, current));
            int progress = steps == 0 ? 0
                    : (int) Math.round((clamped - min) * steps / Math.max(1e-9, max - min));
            seekBar.setProgress(progress);
            titleView.setText(settingDef.title);
            valueView.setText(formatNumber(valueForProgress(progress)));
        }
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private class PluginPageAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return rowTypes.size();
        }

        @Override
        public int getItemViewType(int position) {
            return rowTypes.get(position);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int type = rowTypes.get(holder.getAdapterPosition());
            return type != TYPE_HEADER && type != TYPE_INFO
                    && type != TYPE_HELP && type != TYPE_SETTING_HEADER
                    && type != TYPE_STATUS_SUSPENDED;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case TYPE_HEADER:
                case TYPE_SETTING_HEADER:
                    view = new HeaderCell(context);
                    break;
                case TYPE_INFO:
                    view = new TextInfoPrivacyCell(context);
                    break;
                case TYPE_TOGGLE:
                case TYPE_SETTING_BOOL:
                    view = new TextCheckCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
                case TYPE_HELP: {
                    TextView help = new TextView(context);
                    help.setTextSize(13);
                    help.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    help.setPadding(AndroidUtilities.dp(16), 0,
                            AndroidUtilities.dp(16), AndroidUtilities.dp(10));
                    help.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    view = help;
                    break;
                }
                case TYPE_SETTING_SLIDER:
                    view = new SliderSettingCell(context);
                    break;
                default:
                    view = new TextCell(context);
                    view.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            Context context = holder.itemView.getContext();
            int type = rowTypes.get(position);
            boolean last = position == rowTypes.size() - 1;
            switch (type) {
                case TYPE_HEADER:
                    ((HeaderCell) holder.itemView).setText((String) rowData.get(position));
                    break;
                case TYPE_SETTING_HEADER:
                    ((HeaderCell) holder.itemView).setText(
                            ((JsPlugin.SettingDef) rowData.get(position)).title);
                    break;
                case TYPE_INFO:
                    ((TextInfoPrivacyCell) holder.itemView).setText((String) rowData.get(position));
                    break;
                case TYPE_HELP:
                    ((TextView) holder.itemView).setText((String) rowData.get(position));
                    break;
                case TYPE_TOGGLE:
                    ((TextCheckCell) holder.itemView).setTextAndCheck(
                            plugin.name, plugin.enabled, true);
                    break;
                case TYPE_SETTING_BOOL: {
                    JsPlugin.SettingDef def = (JsPlugin.SettingDef) rowData.get(position);
                    ((TextCheckCell) holder.itemView).setTextAndCheck(
                            def.title, boolValue(context, def), dividerFor(position));
                    break;
                }
                case TYPE_SETTING_TEXT: {
                    JsPlugin.SettingDef def = (JsPlugin.SettingDef) rowData.get(position);
                    String value = textValue(context, def);
                    ((TextCell) holder.itemView).setTextAndValue(
                            def.title, value.isEmpty() ? "Not set" : value, dividerFor(position));
                    break;
                }
                case TYPE_SETTING_NUMBER: {
                    JsPlugin.SettingDef def = (JsPlugin.SettingDef) rowData.get(position);
                    String raw = JsPluginManager.storageGetRaw(context, plugin.id, def.key);
                    String shown = raw == null || raw.isEmpty()
                            ? "Not set" : formatNumber(numberValue(context, def));
                    ((TextCell) holder.itemView).setTextAndValue(
                            def.title, shown, dividerFor(position));
                    break;
                }
                case TYPE_SETTING_SELECT: {
                    JsPlugin.SettingDef def = (JsPlugin.SettingDef) rowData.get(position);
                    String label = selectLabel(context, def);
                    ((TextCell) holder.itemView).setTextAndValue(
                            def.title, label.isEmpty() ? "Not set" : label, dividerFor(position));
                    break;
                }
                case TYPE_SETTING_SLIDER: {
                    JsPlugin.SettingDef def = (JsPlugin.SettingDef) rowData.get(position);
                    ((SliderSettingCell) holder.itemView).bind(def,
                            numberValue(context, def),
                            (d, value) -> writeSetting(context, d, formatNumber(value)));
                    break;
                }
                default:
                    ((TextCell) holder.itemView).setText((String) rowData.get(position), !last);
                    break;
            }
        }
    }

    // ------------------------------------------------------------------
    // Log viewer (v2.1): the plugin's log lines with a filter control
    // cycling All → Errors → Warnings. Lines carry their level as a
    // "[error] " / "[warn] " prefix inside the line text (after the
    // timestamp); the filters match those prefixes. Tap a line to
    // copy it, like the generic store viewer.
    // ------------------------------------------------------------------

    public static class PluginLogFragment extends BaseFragment {

        public static final int FILTER_ALL = 0;
        public static final int FILTER_ERRORS = 1;
        public static final int FILTER_WARNINGS = 2;

        private final String logPluginId;
        private final String logPluginName;
        private int filterMode;
        private final List<String> visibleLines = new ArrayList<>();
        private RecyclerListView logListView;
        private LogAdapter logAdapter;
        private TextCell filterCell;

        public PluginLogFragment(String pluginId, String pluginName, int initialFilter) {
            super();
            this.logPluginId = pluginId;
            this.logPluginName = pluginName;
            this.filterMode = initialFilter;
        }

        @Override
        public View createView(Context context) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_back);
            actionBar.setAllowOverlayTitle(true);
            actionBar.setTitle((logPluginName != null ? logPluginName : "Plugin") + " — log");
            actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
                @Override
                public void onItemClick(int id) {
                    if (id == -1) {
                        finishFragment();
                    }
                }
            });

            LinearLayout layout = new LinearLayout(context);
            layout.setOrientation(LinearLayout.VERTICAL);
            layout.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

            filterCell = new TextCell(context);
            filterCell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            filterCell.setOnClickListener(v -> {
                filterMode = (filterMode + 1) % 3;
                reloadLines(context);
                updateFilterCell();
                if (logAdapter != null) {
                    logAdapter.notifyDataSetChanged();
                }
            });
            layout.addView(filterCell, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            logListView = new RecyclerListView(context);
            logListView.setLayoutManager(new LinearLayoutManager(
                    context, LinearLayoutManager.VERTICAL, false));
            logAdapter = new LogAdapter();
            logListView.setAdapter(logAdapter);
            logListView.setOnItemClickListener((view, position) -> {
                if (position < 0 || position >= visibleLines.size()) {
                    return;
                }
                AndroidUtilities.addToClipboard(visibleLines.get(position));
                BulletinFactory.of(PluginLogFragment.this)
                        .createSimpleBulletin(R.raw.copy, "Copied to clipboard")
                        .show();
            });
            layout.addView(logListView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

            reloadLines(context);
            updateFilterCell();
            fragmentView = layout;
            return layout;
        }

        private void updateFilterCell() {
            if (filterCell != null) {
                filterCell.setTextAndValue("Filter", filterLabel(), false);
            }
        }

        private String filterLabel() {
            switch (filterMode) {
                case FILTER_ERRORS:
                    return "Errors";
                case FILTER_WARNINGS:
                    return "Warnings";
                default:
                    return "All";
            }
        }

        private void reloadLines(Context context) {
            visibleLines.clear();
            List<String> all = JsPluginManager.getLog(context, logPluginId);
            for (int i = all.size() - 1; i >= 0; i--) { // newest first
                String line = all.get(i);
                if (filterMode == FILTER_ERRORS && !line.contains("[error]")) {
                    continue;
                }
                if (filterMode == FILTER_WARNINGS && !line.contains("[warn]")) {
                    continue;
                }
                visibleLines.add(line);
            }
        }

        private class LogAdapter extends RecyclerListView.SelectionAdapter {

            @Override
            public int getItemCount() {
                return Math.max(1, visibleLines.size());
            }

            @Override
            public boolean isEnabled(RecyclerView.ViewHolder holder) {
                return !visibleLines.isEmpty();
            }

            @Override
            public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
                TextCell cell = new TextCell(parent.getContext());
                cell.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                cell.setLayoutParams(new RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT));
                return new RecyclerListView.Holder(cell);
            }

            @Override
            public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
                TextCell cell = (TextCell) holder.itemView;
                if (visibleLines.isEmpty()) {
                    cell.setText(filterMode == FILTER_ALL
                            ? "Nothing here yet" : "No " + filterLabel().toLowerCase() + " in the log",
                            false);
                    return;
                }
                String value = visibleLines.get(position);
                if (value.length() > 300) {
                    value = value.substring(0, 300) + "…";
                }
                cell.setText(value, position < visibleLines.size() - 1);
            }
        }
    }
}

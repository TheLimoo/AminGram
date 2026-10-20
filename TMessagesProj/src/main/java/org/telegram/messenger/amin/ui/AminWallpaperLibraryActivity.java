package org.telegram.messenger.amin.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.MimeTypeMap;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.amin.core.WallpaperLibraryStore;
import org.telegram.messenger.amin.plugins.ModModule;
import org.telegram.messenger.amin.plugins.PluginRegistry;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Live wallpapers library (v2.0): a 2-column grid over
 * WallpaperLibraryStore, in the hub's design language (cards on the
 * gray page). Replaces the hub's old single-file picker for the
 * Video Chat Background module:
 *
 * - Tap a card to APPLY it (and enable the module, so one tap truly
 *   shows it — playback picks the new file up on chat resume through
 *   VideoBackgroundHelper's path-equality release check).
 * - "＋ Add from phone" runs the same picker the hub used
 *   (video/* + image/gif); a successful add is applied immediately,
 *   preserving the old pick-sets-it behaviour.
 * - "Clear / None" (only while something is active) deactivates and
 *   keeps the library; per-item Delete (with confirmation) removes
 *   the file; long-press renames.
 *
 * Thumbnails are generated off the UI thread (first frame via
 * MediaMetadataRetriever for video, BitmapFactory's first frame for
 * GIFs) and cached as JPEGs in
 * cacheDir/amingram_wallpaper_thumbs/&lt;id&gt;.jpg — the store
 * removes the cached thumb when an item is deleted.
 */
public class AminWallpaperLibraryActivity extends BaseFragment {

    private static final int REQUEST_PICK_WALLPAPER = 3921;

    private static final int KIND_ADD = 0;
    private static final int KIND_CLEAR = 1;
    private static final int KIND_CARD = 2;
    private static final int KIND_EMPTY = 3;

    private final List<WallpaperLibraryStore.Entry> items = new ArrayList<>();
    private String activeId;
    private RecyclerListView listView;
    private LibraryAdapter adapter;
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(48);

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Live wallpapers");
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
        GridLayoutManager manager = new GridLayoutManager(context, 2);
        manager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
            @Override
            public int getSpanSize(int position) {
                return kindAt(position) == KIND_CARD ? 1 : 2;
            }
        });
        listView.setLayoutManager(manager);
        adapter = new LibraryAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener((view, position) -> onItemClick(position));
        listView.setOnItemLongClickListener((view, position) -> {
            WallpaperLibraryStore.Entry entry = entryAt(position);
            if (entry == null) {
                return false;
            }
            renameFlow(entry);
            return true;
        });
        layout.addView(listView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        reload();
        fragmentView = layout;
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        reload();
    }

    private void reload() {
        Context context = getContext();
        if (context == null) {
            return;
        }
        try {
            items.clear();
            items.addAll(WallpaperLibraryStore.list(context));
            WallpaperLibraryStore.Entry active = WallpaperLibraryStore.getActive(context);
            activeId = active == null ? null : active.id;
        } catch (Throwable ignored) {
        }
        if (adapter != null) {
            adapter.notifyDataSetChanged();
        }
    }

    private int headerCount() {
        return activeId != null ? 2 : 1;
    }

    private int kindAt(int position) {
        if (position == 0) {
            return KIND_ADD;
        }
        if (activeId != null && position == 1) {
            return KIND_CLEAR;
        }
        if (items.isEmpty()) {
            return KIND_EMPTY;
        }
        return KIND_CARD;
    }

    private WallpaperLibraryStore.Entry entryAt(int position) {
        int index = position - headerCount();
        return index >= 0 && index < items.size() ? items.get(index) : null;
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    private void onItemClick(int position) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        switch (kindAt(position)) {
            case KIND_ADD:
                pickWallpaper();
                break;
            case KIND_CLEAR:
                WallpaperLibraryStore.clearActive(context);
                bulletin(R.raw.ic_delete, "Wallpaper cleared — library kept");
                reload();
                break;
            case KIND_CARD: {
                WallpaperLibraryStore.Entry entry = entryAt(position);
                if (entry != null) {
                    applyEntry(context, entry);
                }
                break;
            }
            default:
                break;
        }
    }

    private void applyEntry(Context context, WallpaperLibraryStore.Entry entry) {
        if (WallpaperLibraryStore.apply(context, entry.id)) {
            enableVideoBackgroundModule(context);
            bulletin(R.raw.contact_check, "Wallpaper applied — reopen a chat");
        } else {
            bulletin(R.raw.error, "That wallpaper file is missing");
        }
        reload();
    }

    /** One tap truly shows the wallpaper: applying also turns the
     *  Video Chat Background module on. */
    private static void enableVideoBackgroundModule(Context context) {
        try {
            for (ModModule module : PluginRegistry.modules()) {
                if ("video_background".equals(module.id())) {
                    if (!PluginRegistry.isEnabled(context, module)) {
                        PluginRegistry.setEnabled(context, module, true);
                    }
                    break;
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private void deleteFlow(WallpaperLibraryStore.Entry entry) {
        Context context = getContext();
        if (context == null || getParentActivity() == null || entry == null) {
            return;
        }
        boolean wasActive = entry.id.equals(activeId);
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Delete wallpaper?");
        builder.setMessage(entry.displayName + (wasActive
                ? "\n\nThis is the active wallpaper — deleting it turns the background off."
                : ""));
        builder.setPositiveButton("Delete", (dialog, which) -> {
            WallpaperLibraryStore.delete(context, entry.id);
            thumbs.remove(entry.id);
            bulletin(R.raw.ic_delete, "Wallpaper deleted");
            reload();
        });
        builder.setNegativeButton("Cancel", null);
        showDialog(builder.create());
    }

    private void renameFlow(WallpaperLibraryStore.Entry entry) {
        Context context = getContext();
        if (context == null || getParentActivity() == null || entry == null) {
            return;
        }
        EditTextBoldCursor field = new EditTextBoldCursor(context);
        field.setHint("Wallpaper name");
        field.setText(entry.displayName);
        field.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        field.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        if (field.getText() != null) {
            field.setSelection(field.getText().length());
        }
        FrameLayout container = new FrameLayout(context);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(8),
                AndroidUtilities.dp(24), 0);
        container.addView(field, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
        builder.setTitle("Rename wallpaper");
        builder.setView(container);
        builder.setPositiveButton("Save", (dialog, which) -> {
            String name = field.getText() == null ? "" : field.getText().toString();
            if (WallpaperLibraryStore.rename(context, entry.id, name)) {
                reload();
            } else {
                bulletin(R.raw.error, "Name can't be empty");
            }
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
    // Picker (identical to the hub's old video-background picker)
    // ------------------------------------------------------------------

    private void pickWallpaper() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.setType("*/*");
            intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"video/*", "image/gif"});
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            startActivityForResult(intent, REQUEST_PICK_WALLPAPER);
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "No file picker available");
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        Context context = getContext();
        if (requestCode != REQUEST_PICK_WALLPAPER || resultCode != Activity.RESULT_OK
                || data == null || data.getData() == null || context == null) {
            return;
        }
        Uri uri = data.getData();
        try {
            InputStream in = context.getContentResolver().openInputStream(uri);
            if (in == null) {
                bulletin(R.raw.error, "Could not read that file");
                return;
            }
            String displayName = queryDisplayName(context, uri);
            if (displayName == null || displayName.isEmpty()) {
                String path = AndroidUtilities.getPath(uri);
                if (path != null) {
                    displayName = new File(path).getName();
                }
            }
            if (displayName == null || displayName.isEmpty()) {
                displayName = "Wallpaper " + (WallpaperLibraryStore.count(context) + 1);
            }
            // The store takes ownership of the stream (always closed).
            WallpaperLibraryStore.Entry entry = WallpaperLibraryStore.addFromStream(
                    context, in, videoExtension(context, uri), displayBase(displayName));
            if (entry == null) {
                bulletin(R.raw.error, "Could not read that file");
                return;
            }
            // Preserve the old pick-sets-it behaviour: apply at once.
            applyEntry(context, entry);
        } catch (Exception e) {
            FileLog.e(e);
            bulletin(R.raw.error, "Something went wrong");
        }
    }

    private static String queryDisplayName(Context context, Uri uri) {
        Cursor cursor = null;
        try {
            cursor = context.getContentResolver().query(uri,
                    new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (index >= 0) {
                    return cursor.getString(index);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (cursor != null) {
                cursor.close();
            }
        }
        return null;
    }

    /** Display names read better without the extension ("beach.mp4"
     *  -> "beach"); the extension itself is kept for the file/ext. */
    private static String displayBase(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    /** Same extension rules the hub's picker used: the resolved
     *  filesystem path's suffix when there is one, else the content
     *  type via MimeTypeMap, else "mp4". */
    private static String videoExtension(Context context, Uri uri) {
        String path = AndroidUtilities.getPath(uri);
        if (path != null) {
            int dot = path.lastIndexOf('.');
            if (dot > 0 && dot > path.lastIndexOf('/')) {
                String ext = path.substring(dot + 1).toLowerCase(Locale.US);
                if (ext.length() <= 5 && ext.matches("[a-z0-9]+")) {
                    return ext;
                }
            }
        }
        try {
            String type = context.getContentResolver().getType(uri);
            if (type != null) {
                String ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(type);
                if (ext != null && !ext.isEmpty()) {
                    return ext.toLowerCase(Locale.US);
                }
            }
        } catch (Exception ignored) {
        }
        return "mp4";
    }

    // ------------------------------------------------------------------
    // Presentation helpers (the hub's card language, kept local)
    // ------------------------------------------------------------------

    private static int cardFill() {
        return Theme.getColor(Theme.key_windowBackgroundWhite);
    }

    private static int accentColor() {
        return Theme.getColor(Theme.key_windowBackgroundWhiteBlueText);
    }

    private static GradientDrawable roundedCard(boolean active) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(cardFill());
        drawable.setCornerRadius(AndroidUtilities.dp(18));
        if (active) {
            drawable.setStroke(AndroidUtilities.dp(2), accentColor());
        }
        return drawable;
    }

    private static String formatSize(long bytes) {
        if (bytes <= 0) {
            return "0 B";
        }
        String[] units = {"B", "KB", "MB", "GB"};
        int unit = 0;
        double value = bytes;
        while (value >= 1024 && unit < units.length - 1) {
            value /= 1024;
            unit++;
        }
        return unit == 0
                ? String.format(Locale.US, "%d %s", bytes, units[unit])
                : String.format(Locale.US, "%.1f %s", value, units[unit]);
    }

    private static String formatDuration(long ms) {
        long seconds = ms / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long rest = seconds % 60;
        return hours > 0
                ? String.format(Locale.US, "%d:%02d:%02d", hours, minutes, rest)
                : String.format(Locale.US, "%d:%02d", minutes, rest);
    }

    /** One wallpaper card: thumbnail, name, duration + size line, an
     *  ACTIVE badge on the active item, and a Delete chip. The chip
     *  is a DIRECT child of the card and clickable, so (as in the
     *  Hidden Chats rows) RecyclerListView's clickable-child
     *  suppression keeps chip taps from also firing the card's
     *  apply click. */
    private static class WallpaperCardView extends FrameLayout {
        interface Callback {
            void onDelete(WallpaperLibraryStore.Entry entry);
        }

        private final ImageView thumbView;
        private final TextView nameView;
        private final TextView metaView;
        private final TextView badgeView;
        private final TextView deleteChip;
        private WallpaperLibraryStore.Entry boundEntry;
        private String boundId;
        private Callback callback;

        WallpaperCardView(Context context) {
            super(context);

            LinearLayout column = new LinearLayout(context);
            column.setOrientation(LinearLayout.VERTICAL);
            column.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(10),
                    AndroidUtilities.dp(10), AndroidUtilities.dp(10));
            addView(column, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

            thumbView = new ImageView(context);
            thumbView.setScaleType(ImageView.ScaleType.CENTER_CROP);
            thumbView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));
            column.addView(thumbView, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(110)));

            nameView = new TextView(context);
            nameView.setTextSize(14.5f);
            nameView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            nameView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
            nameView.setSingleLine(true);
            nameView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            nameParams.topMargin = AndroidUtilities.dp(8);
            column.addView(nameView, nameParams);

            metaView = new TextView(context);
            metaView.setTextSize(12.5f);
            metaView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            metaView.setSingleLine(true);
            metaView.setEllipsize(TextUtils.TruncateAt.END);
            LinearLayout.LayoutParams metaParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            metaParams.topMargin = AndroidUtilities.dp(2);
            // Leave room at the bottom-right for the Delete chip.
            metaParams.bottomMargin = AndroidUtilities.dp(26);
            column.addView(metaView, metaParams);

            badgeView = new TextView(context);
            badgeView.setText("ACTIVE");
            badgeView.setTextSize(11);
            badgeView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            badgeView.setTextColor(0xFFFFFFFF);
            badgeView.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(3),
                    AndroidUtilities.dp(8), AndroidUtilities.dp(3));
            GradientDrawable badgeBg = new GradientDrawable();
            badgeBg.setShape(GradientDrawable.RECTANGLE);
            badgeBg.setColor(accentColor());
            badgeBg.setCornerRadius(AndroidUtilities.dp(8));
            badgeView.setBackground(badgeBg);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.TOP | Gravity.LEFT);
            badgeParams.leftMargin = AndroidUtilities.dp(16);
            badgeParams.topMargin = AndroidUtilities.dp(16);
            addView(badgeView, badgeParams);

            deleteChip = new TextView(context);
            deleteChip.setText("Delete");
            deleteChip.setTextSize(13);
            deleteChip.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            deleteChip.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
            deleteChip.setSingleLine(true);
            deleteChip.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(6),
                    AndroidUtilities.dp(12), AndroidUtilities.dp(6));
            deleteChip.setClickable(true);
            deleteChip.setOnClickListener(v -> {
                if (callback != null && boundEntry != null) {
                    callback.onDelete(boundEntry);
                }
            });
            FrameLayout.LayoutParams chipParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.RIGHT);
            chipParams.rightMargin = AndroidUtilities.dp(8);
            chipParams.bottomMargin = AndroidUtilities.dp(6);
            addView(deleteChip, chipParams);
        }

        void setCallback(Callback callback) {
            this.callback = callback;
        }

        void bind(WallpaperLibraryStore.Entry entry, boolean active, Context context) {
            boundEntry = entry;
            boundId = entry.id;
            nameView.setText(entry.displayName);
            long size = entry.size(context);
            String meta = entry.isGif()
                    ? "GIF · " + formatSize(size)
                    : formatDuration(entry.durationMs(context)) + " · " + formatSize(size);
            metaView.setText(meta);
            badgeView.setVisibility(active ? View.VISIBLE : View.GONE);
            setBackground(roundedCard(active));
            thumbView.setImageBitmap(null);
        }
    }

    /** Full-span header row ("＋ Add from phone" / "Clear / None"). */
    private static class HeaderRowView extends TextView {
        HeaderRowView(Context context) {
            super(context);
            setTextSize(15.5f);
            setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(14),
                    AndroidUtilities.dp(16), AndroidUtilities.dp(14));
        }

        void bind(String text, int textColor) {
            setText(text);
            setTextColor(textColor);
            GradientDrawable bg = new GradientDrawable();
            bg.setShape(GradientDrawable.RECTANGLE);
            bg.setColor(cardFill());
            bg.setCornerRadius(AndroidUtilities.dp(18));
            setBackground(bg);
        }
    }

    // ------------------------------------------------------------------
    // Thumbnails (off the UI thread; JPEG disk cache per item id)
    // ------------------------------------------------------------------

    private void loadThumb(WallpaperCardView card, WallpaperLibraryStore.Entry entry) {
        Context context = getContext();
        if (context == null) {
            return;
        }
        Bitmap cached = thumbs.get(entry.id);
        if (cached != null) {
            card.thumbView.setImageBitmap(cached);
            return;
        }
        final String id = entry.id;
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap bitmap = null;
            try {
                File thumbFile = WallpaperLibraryStore.thumbFile(context, id);
                if (thumbFile.exists() && thumbFile.length() > 0) {
                    bitmap = BitmapFactory.decodeFile(thumbFile.getAbsolutePath());
                }
                if (bitmap == null) {
                    bitmap = entry.isGif()
                            ? gifThumb(entry.file(context))
                            : videoThumb(entry.file(context));
                    if (bitmap != null) {
                        writeThumbCache(thumbFile, bitmap);
                    }
                }
            } catch (Throwable ignored) {
            }
            final Bitmap result = bitmap;
            AndroidUtilities.runOnUIThread(() -> {
                if (result != null) {
                    thumbs.put(id, result);
                    if (id.equals(card.boundId)) {
                        card.thumbView.setImageBitmap(result);
                    }
                }
            });
        });
    }

    private static void writeThumbCache(File thumbFile, Bitmap bitmap) {
        OutputStream out = null;
        try {
            File dir = thumbFile.getParentFile();
            if (dir != null && !dir.exists()) {
                dir.mkdirs();
            }
            out = new FileOutputStream(thumbFile);
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
        } catch (Throwable ignored) {
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static Bitmap scaleToWidth(Bitmap frame) {
        if (frame == null || frame.getWidth() <= 0) {
            return frame;
        }
        int width = 360;
        int height = Math.max(1, (int) (frame.getHeight() * (width / (float) frame.getWidth())));
        Bitmap scaled = Bitmap.createScaledBitmap(frame, width, height, true);
        if (scaled != frame) {
            frame.recycle();
        }
        return scaled;
    }

    private static Bitmap videoThumb(File file) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            if (!file.exists()) {
                return null;
            }
            retriever.setDataSource(file.getAbsolutePath());
            return scaleToWidth(retriever.getFrameAtTime(0));
        } catch (Throwable ignored) {
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Throwable ignored) {
            }
        }
    }

    private static Bitmap gifThumb(File file) {
        try {
            if (!file.exists()) {
                return null;
            }
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            int sample = 1;
            while (options.outWidth / sample > 360 || options.outHeight / sample > 360) {
                sample *= 2;
            }
            options.inSampleSize = sample;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Throwable ignored) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Adapter
    // ------------------------------------------------------------------

    private class LibraryAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return headerCount() + (items.isEmpty() ? 1 : items.size());
        }

        @Override
        public int getItemViewType(int position) {
            return kindAt(position);
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            int position = holder.getAdapterPosition();
            return position >= 0 && kindAt(position) != KIND_EMPTY;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            View view;
            Context context = parent.getContext();
            switch (viewType) {
                case KIND_CARD: {
                    WallpaperCardView card = new WallpaperCardView(context);
                    card.setCallback(entry -> deleteFlow(entry));
                    view = card;
                    break;
                }
                case KIND_EMPTY: {
                    TextView empty = new TextView(context);
                    empty.setText("No wallpapers yet.\nAdd a video or GIF from your phone — "
                            + "it stays in your library, ready to apply with one tap.");
                    empty.setTextSize(14.5f);
                    empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
                    empty.setGravity(Gravity.CENTER);
                    empty.setPadding(AndroidUtilities.dp(32), AndroidUtilities.dp(28),
                            AndroidUtilities.dp(32), AndroidUtilities.dp(28));
                    view = empty;
                    break;
                }
                default:
                    view = new HeaderRowView(context);
                    break;
            }
            view.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return new RecyclerListView.Holder(view);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            int kind = kindAt(position);
            RecyclerView.LayoutParams lp =
                    (RecyclerView.LayoutParams) holder.itemView.getLayoutParams();
            if (kind == KIND_CARD) {
                lp.leftMargin = AndroidUtilities.dp(6);
                lp.rightMargin = AndroidUtilities.dp(6);
                lp.topMargin = AndroidUtilities.dp(6);
                lp.bottomMargin = AndroidUtilities.dp(6);
            } else {
                lp.leftMargin = AndroidUtilities.dp(12);
                lp.rightMargin = AndroidUtilities.dp(12);
                lp.topMargin = AndroidUtilities.dp(10);
                lp.bottomMargin = 0;
            }
            holder.itemView.setLayoutParams(lp);
            switch (kind) {
                case KIND_ADD:
                    ((HeaderRowView) holder.itemView).bind("＋ Add from phone", accentColor());
                    break;
                case KIND_CLEAR:
                    ((HeaderRowView) holder.itemView).bind("Clear / None",
                            Theme.getColor(Theme.key_text_RedRegular));
                    break;
                case KIND_CARD: {
                    WallpaperLibraryStore.Entry entry = entryAt(position);
                    WallpaperCardView card = (WallpaperCardView) holder.itemView;
                    if (entry != null) {
                        Context context = getContext();
                        card.bind(entry, entry.id.equals(activeId),
                                context == null ? holder.itemView.getContext() : context);
                        loadThumb(card, entry);
                    }
                    break;
                }
                default:
                    break;
            }
        }
    }
}

package org.telegram.messenger.amin.ui;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.amin.core.AutoSaverStore;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.RecyclerListView;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Auto-Saver gallery (v1.3): a thumbnail grid of everything Auto-Saver
 * captured — secret-chat TTL media and view-once photos/videos — with
 * the chat name and date on each item. Tap opens the full-screen
 * viewer (save to gallery / share / remove). Long-press removes an
 * entry. Entries saved before v1.3 (log-only, no file path tracked)
 * are shown as text cells explaining exactly that.
 */
public class AminSavedMediaActivity extends BaseFragment {

    private final List<AutoSaverStore.Entry> entries = new ArrayList<>();
    private RecyclerListView listView;
    private GalleryAdapter adapter;
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(90);

    @Override
    public View createView(Context context) {
        reload(context);

        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle("Saved media");
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

        if (entries.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText("Nothing saved yet.\nWhen a self-destruct or view-once photo/video arrives, "
                    + "Auto-Saver keeps a copy here.");
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setTextSize(15);
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(32), 0, AndroidUtilities.dp(32), 0);
            layout.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            listView = new RecyclerListView(context);
            GridLayoutManager manager = new GridLayoutManager(context, 3);
            listView.setLayoutManager(manager);
            adapter = new GalleryAdapter();
            listView.setAdapter(adapter);
            listView.setOnItemClickListener((view, position) -> {
                if (position < 0 || position >= entries.size()) {
                    return;
                }
                AutoSaverStore.Entry entry = entries.get(position);
                presentFragment(new AminPhotoViewActivity(entry.dialogId, entry.id));
            });
            listView.setOnItemLongClickListener((view, position) -> {
                if (position < 0 || position >= entries.size()) {
                    return false;
                }
                AutoSaverStore.Entry entry = entries.get(position);
                AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity());
                builder.setTitle("Remove from saved media?");
                builder.setMessage("The entry is removed from this list. The file in Telegram's cache is not touched.");
                builder.setPositiveButton("Remove", (dialog, which) -> {
                    AutoSaverStore.remove(context, entry);
                    reload(context);
                    adapter.notifyDataSetChanged();
                });
                builder.setNegativeButton("Cancel", null);
                showDialog(builder.create());
                return true;
            });
            layout.addView(listView, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }

        fragmentView = layout;
        return layout;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null && getContext() != null) {
            reload(getContext());
            adapter.notifyDataSetChanged();
        }
    }

    private void reload(Context context) {
        entries.clear();
        List<AutoSaverStore.Entry> all = AutoSaverStore.getEntries(context);
        for (int i = all.size() - 1; i >= 0; i--) {
            entries.add(all.get(i)); // newest first
        }
    }

    private class GalleryAdapter extends RecyclerListView.SelectionAdapter {

        @Override
        public int getItemCount() {
            return entries.size();
        }

        @Override
        public boolean isEnabled(RecyclerView.ViewHolder holder) {
            return true;
        }

        @Override
        public RecyclerView.ViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout cell = new FrameLayout(parent.getContext());
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
            cell.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
            TextView badge = new TextView(parent.getContext());
            badge.setTextColor(0xFFFFFFFF);
            badge.setTextSize(12);
            badge.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(2),
                    AndroidUtilities.dp(6), AndroidUtilities.dp(2));
            badge.setBackgroundColor(0x66000000);
            FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.LEFT);
            cell.addView(badge, badgeParams);
            TextView caption = new TextView(parent.getContext());
            caption.setTextColor(0xFFFFFFFF);
            caption.setTextSize(10);
            caption.setMaxLines(2);
            caption.setPadding(AndroidUtilities.dp(6), AndroidUtilities.dp(2),
                    AndroidUtilities.dp(6), AndroidUtilities.dp(2));
            caption.setBackgroundColor(0x66000000);
            FrameLayout.LayoutParams captionParams = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM | Gravity.RIGHT);
            caption.setGravity(Gravity.RIGHT);
            cell.addView(caption, captionParams);
            int size = AndroidUtilities.displaySize.x / 3;
            cell.setLayoutParams(new RecyclerView.LayoutParams(size, size));
            return new RecyclerListView.Holder(cell);
        }

        @Override
        public void onBindViewHolder(RecyclerView.ViewHolder holder, int position) {
            AutoSaverStore.Entry entry = entries.get(position);
            FrameLayout cell = (FrameLayout) holder.itemView;
            ImageView image = (ImageView) cell.getChildAt(0);
            TextView badge = (TextView) cell.getChildAt(1);
            TextView caption = (TextView) cell.getChildAt(2);
            String key = entry.dialogId + ":" + entry.id;
            cell.setTag(key);
            image.setImageBitmap(null);
            badge.setText(entry.isVideo() ? "▶ video" : "");
            String name = AminDialogPickerActivity.dialogName(currentAccount, entry.dialogId);
            String date = new SimpleDateFormat("MMM d", Locale.getDefault())
                    .format(new Date(entry.date * 1000L));
            caption.setText(name + " · " + date);

            Bitmap cached = thumbs.get(key);
            if (cached != null) {
                image.setImageBitmap(cached);
                return;
            }
            final int account = currentAccount;
            Utilities.globalQueue.postRunnable(() -> {
                File file = AutoSaverStore.resolveFile(getContext(), account, entry);
                Bitmap bitmap = null;
                if (file != null) {
                    bitmap = entry.isVideo() ? videoThumb(file) : imageThumb(file);
                }
                final Bitmap result = bitmap;
                AndroidUtilities.runOnUIThread(() -> {
                    if (result != null) {
                        thumbs.put(key, result);
                    }
                    if (key.equals(cell.getTag())) {
                        if (result != null) {
                            image.setImageBitmap(result);
                        } else {
                            badge.setText("file gone");
                        }
                    }
                });
            });
        }

        private Bitmap imageThumb(File file) {
            try {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), options);
                int sample = 1;
                int target = 360;
                while (options.outWidth / sample > target || options.outHeight / sample > target) {
                    sample *= 2;
                }
                options.inSampleSize = sample;
                options.inJustDecodeBounds = false;
                return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            } catch (Throwable t) {
                return null;
            }
        }

        private Bitmap videoThumb(File file) {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                Bitmap frame = retriever.getFrameAtTime(0);
                if (frame != null) {
                    int width = 360;
                    int height = Math.max(1, (int) (frame.getHeight() * (width / (float) frame.getWidth())));
                    Bitmap scaled = Bitmap.createScaledBitmap(frame, width, height, true);
                    if (scaled != frame) {
                        frame.recycle();
                    }
                    return scaled;
                }
            } catch (Throwable ignored) {
            } finally {
                try {
                    retriever.release();
                } catch (Throwable ignored) {
                }
            }
            return null;
        }
    }
}

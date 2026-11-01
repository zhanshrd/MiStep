package com.jiusis.mistep;

import android.app.Dialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import java.text.Collator;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/** A wallpaper-glass shortcut editor using the same palette and material as MiStep. */
final class QuickAppPicker {
    private static final int WINDOW_TYPE = 2017; // Native SystemUI dialog layer, above StatusBar.
    private static final int BLUE = 0xff347ff4;
    private Dialog dialog;
    private View content;

    void close() {
        Dialog old = dialog;
        dialog = null;
        if (content != null) content.animate().cancel();
        content = null;
        if (old != null) try { old.dismiss(); } catch (RuntimeException ignored) {}
    }

    void show(Context base, List<QuickApp> available, Bitmap wallpaper, int screenWidth,
            int screenHeight, Runnable changed) {
        if (dialog != null || available.isEmpty()) return;
        final int user = QuickAppPins.user();
        final LinkedHashSet<String> chosen;
        try { chosen = new LinkedHashSet<>(QuickAppPins.read(base, user)); }
        catch (Throwable unavailable) {
            Toast.makeText(base, "无法读取快捷应用配置", Toast.LENGTH_SHORT).show();
            return;
        }
        Context windowContext = base.createDisplayContext(base.getDisplay()).createWindowContext(WINDOW_TYPE, null);
        Context context = new ContextThemeWrapper(windowContext, HyperOsStyle.dark(base)
                ? android.R.style.Theme_Material_Dialog_NoActionBar
                : android.R.style.Theme_Material_Light_Dialog_NoActionBar);
        Dialog next = new Dialog(context);
        next.requestWindowFeature(Window.FEATURE_NO_TITLE);
        next.setCanceledOnTouchOutside(true);
        GlassPanel panel = new GlassPanel(context, wallpaper, screenWidth, screenHeight);
        LinearLayout header = new LinearLayout(context);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(label(context, "自定义快捷应用", 21, true), new LinearLayout.LayoutParams(0, dp(context, 42), 1));
        TextView close = label(context, "×", 25, false);
        close.setGravity(Gravity.CENTER);
        close.setContentDescription("关闭，不保存");
        close.setBackground(HyperOsStyle.glassButton(context, 20, false));
        close.setOnClickListener(view -> next.dismiss());
        header.addView(close, new LinearLayout.LayoutParams(dp(context, 40), dp(context, 40)));
        panel.addView(header, new LinearLayout.LayoutParams(-1, dp(context, 46)));
        TextView description = label(context, "按选择顺序，固定在应用栏最前", 12, false);
        description.setTextColor(HyperOsStyle.textSecondary(context));
        panel.addView(description, new LinearLayout.LayoutParams(-1, dp(context, 30)));

        LinearLayout searchBox = new LinearLayout(context);
        searchBox.setGravity(Gravity.CENTER_VERTICAL);
        searchBox.setPadding(dp(context, 14), 0, dp(context, 8), 0);
        searchBox.setBackground(HyperOsStyle.glassPill(context, 16));
        searchBox.addView(new SearchIcon(context), new LinearLayout.LayoutParams(dp(context, 20), dp(context, 20)));
        EditText search = new EditText(context);
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(HyperOsStyle.textPrimary(context));
        search.setHintTextColor(HyperOsStyle.textSecondary(context));
        search.setBackground(null);
        search.setPadding(dp(context, 10), 0, 0, 0);
        search.setHint("搜索应用");
        search.setContentDescription("搜索快捷应用");
        search.setSelectAllOnFocus(true);
        searchBox.addView(search, new LinearLayout.LayoutParams(0, -1, 1));
        panel.addView(searchBox, new LinearLayout.LayoutParams(-1, dp(context, 46)));
        LinearLayout listHeader = new LinearLayout(context);
        listHeader.setGravity(Gravity.CENTER_VERTICAL);
        TextView allTitle = label(context, "全部应用", 12, true);
        allTitle.setTextColor(HyperOsStyle.textSecondary(context));
        listHeader.addView(allTitle, new LinearLayout.LayoutParams(0, -1, 1));
        TextView summary = label(context, "", 12, true);
        summary.setTextColor(BLUE);
        listHeader.addView(summary);
        Runnable count = () -> summary.setText("已选 " + chosen.size() + " 个");
        count.run();
        LinearLayout.LayoutParams listHeaderBounds = new LinearLayout.LayoutParams(-1, dp(context, 38));
        listHeaderBounds.topMargin = dp(context, 6);
        panel.addView(listHeader, listHeaderBounds);

        List<QuickApp> all = new ArrayList<>(available);
        Collator names = Collator.getInstance(Locale.getDefault());
        all.sort((a, b) -> names.compare(a.label, b.label));
        List<QuickApp> rows = new ArrayList<>(all);
        BaseAdapter adapter = new BaseAdapter() {
            public int getCount() { return rows.size(); }
            public Object getItem(int position) { return rows.get(position); }
            public long getItemId(int position) { return position; }
            public View getView(int position, View recycled, ViewGroup parent) {
                AppRow row = recycled instanceof AppRow ? (AppRow) recycled : new AppRow(context);
                QuickApp app = rows.get(position);
                row.bind(app, new ArrayList<>(chosen).indexOf(app.component.flattenToString()) + 1);
                return row;
            }
        };
        FrameLayout listArea = new FrameLayout(context);
        ListView list = new ListView(context);
        list.setAdapter(adapter);
        list.setDivider(new ColorDrawable(Color.TRANSPARENT));
        list.setDividerHeight(dp(context, 5));
        list.setSelector(new ColorDrawable(Color.TRANSPARENT));
        list.setClipToPadding(false);
        list.setPadding(0, 0, 0, dp(context, 5));
        listArea.addView(list, new FrameLayout.LayoutParams(-1, -1));
        TextView empty = label(context, "没有找到应用", 14, false);
        empty.setGravity(Gravity.CENTER);
        empty.setTextColor(HyperOsStyle.textSecondary(context));
        listArea.addView(empty, new FrameLayout.LayoutParams(-1, -1));
        list.setEmptyView(empty);
        panel.addView(listArea, new LinearLayout.LayoutParams(-1, 0, 1));
        list.setOnItemClickListener((parent, row, position, id) -> {
            String key = rows.get(position).component.flattenToString();
            if (!chosen.remove(key)) chosen.add(key);
            adapter.notifyDataSetChanged();
            count.run();
        });
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int before, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase(Locale.ROOT);
                rows.clear();
                for (QuickApp app : all) if (app.label.toLowerCase(Locale.ROOT).contains(query)
                        || app.component.getPackageName().toLowerCase(Locale.ROOT).contains(query)) rows.add(app);
                adapter.notifyDataSetChanged();
                list.setSelection(0);
            }
            public void afterTextChanged(Editable text) {}
        });

        LinearLayout footer = new LinearLayout(context);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        TextView cancel = label(context, "取消", 15, true);
        cancel.setGravity(Gravity.CENTER);
        cancel.setBackground(HyperOsStyle.glassButton(context, 18, false));
        cancel.setOnClickListener(view -> next.dismiss());
        footer.addView(cancel, new LinearLayout.LayoutParams(0, -1, 1));
        TextView done = label(context, "完成", 15, true);
        done.setGravity(Gravity.CENTER);
        done.setTextColor(Color.WHITE);
        done.setBackground(button(context, BLUE, 18));
        LinearLayout.LayoutParams doneBounds = new LinearLayout.LayoutParams(0, -1, 1.5f);
        doneBounds.leftMargin = dp(context, 10);
        footer.addView(done, doneBounds);
        LinearLayout.LayoutParams footerBounds = new LinearLayout.LayoutParams(-1, dp(context, 46));
        footerBounds.topMargin = dp(context, 14);
        panel.addView(footer, footerBounds);
        done.setOnClickListener(view -> {
            if (!QuickAppPins.save(base, user, chosen)) {
                Toast.makeText(base, "保存失败，请重新打开后再试", Toast.LENGTH_SHORT).show();
                return;
            }
            next.dismiss();
            changed.run();
        });
        next.setContentView(panel);
        Window window = next.getWindow();
        if (window == null) return;
        window.setType(WINDOW_TYPE);
        window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
        WindowManager.LayoutParams params = window.getAttributes();
        params.setTitle("MiStepQuickAppPicker");
        params.dimAmount = .28f;
        HostWindowPolicy.configure(params);
        window.setAttributes(params);
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN
                | WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        next.setOnDismissListener(ignored -> {
            if (dialog == next) { dialog = null; content = null; }
            panel.animate().cancel();
        });
        try {
            next.show();
            dialog = next;
            content = panel;
            window.setLayout(Math.min(dp(context, 420), base.getResources().getDisplayMetrics().widthPixels
                    - dp(context, 32)), -2);
            panel.setAlpha(0f);
            panel.setScaleX(.97f);
            panel.setScaleY(.97f);
            panel.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(180L).start();
        } catch (Throwable unavailable) {
            try { next.dismiss(); } catch (RuntimeException ignored) {}
            close();
            Toast.makeText(base, "暂时无法打开应用选择框", Toast.LENGTH_SHORT).show();
            android.util.Log.w("MiStepProbe", "quick app picker unavailable", unavailable);
        }
    }

    private static TextView label(Context context, String text, int size, boolean medium) {
        TextView view = new TextView(context);
        view.setText(text);
        view.setTextSize(size);
        view.setTextColor(HyperOsStyle.textPrimary(context));
        view.setGravity(Gravity.CENTER_VERTICAL);
        if (medium) view.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return view;
    }
    private static Drawable button(Context context, int color, int radius) {
        GradientDrawable fill = new GradientDrawable();
        fill.setColor(color);
        fill.setCornerRadius(dp(context, radius));
        return new RippleDrawable(ColorStateList.valueOf(0x22777777), fill, null);
    }
    private static int dp(Context context, float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private static final class GlassPanel extends LinearLayout {
        private final WallpaperCropDrawable wallpaper;
        private final int desiredHeight;
        private final int[] location = new int[2];
        GlassPanel(Context context, Bitmap bitmap, int width, int height) {
            super(context);
            setOrientation(VERTICAL);
            int padding = dp(context, 20);
            setPadding(padding, padding, padding, padding);
            desiredHeight = Math.min(dp(context, 620), Math.round(context.getResources().getDisplayMetrics().heightPixels * .72f));
            wallpaper = bitmap == null || bitmap.isRecycled() ? null
                    : new WallpaperCropDrawable(bitmap, width, height, 0, 0, HyperOsStyle.topWallpaperTint(context));
            Drawable sheet = HyperOsStyle.glassSheet(context, 28);
            sheet.setAlpha(140);
            setBackground(new LayerDrawable(new Drawable[]{wallpaper == null ? HyperOsStyle.topBackground(context) : wallpaper,
                    HyperOsStyle.topWallpaperWash(context), sheet}));
            setOutlineProvider(new ViewOutlineProvider() {
                public void getOutline(View view, Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(context, 28));
                }
            });
            setClipToOutline(true);
            setFocusableInTouchMode(true);
        }
        @Override protected void onMeasure(int width, int height) {
            int limit = MeasureSpec.getMode(height) == MeasureSpec.UNSPECIFIED ? desiredHeight : MeasureSpec.getSize(height);
            super.onMeasure(width, MeasureSpec.makeMeasureSpec(Math.min(desiredHeight, limit), MeasureSpec.EXACTLY));
        }
        @Override public void draw(Canvas canvas) {
            if (wallpaper == null) { super.draw(canvas); return; }
            getLocationOnScreen(location);
            wallpaper.withScreenOrigin(location[0], location[1], () -> super.draw(canvas));
        }
    }

    private static final class AppRow extends LinearLayout {
        private final ImageView icon;
        private final TextView name;
        private final SelectionMark mark;
        private boolean checked;
        AppRow(Context context) {
            super(context);
            setGravity(Gravity.CENTER_VERTICAL);
            setMinimumHeight(dp(context, 60));
            setPadding(dp(context, 12), dp(context, 8), dp(context, 12), dp(context, 8));
            icon = new ImageView(context);
            icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(icon, new LinearLayout.LayoutParams(dp(context, 36), dp(context, 36)));
            name = label(context, "", 15, true);
            name.setMaxLines(2);
            LinearLayout.LayoutParams labelBounds = new LinearLayout.LayoutParams(0, -2, 1);
            labelBounds.leftMargin = dp(context, 14);
            labelBounds.rightMargin = dp(context, 10);
            addView(name, labelBounds);
            mark = new SelectionMark(context);
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            addView(mark, new LinearLayout.LayoutParams(dp(context, 25), dp(context, 25)));
            setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        }
        void bind(QuickApp app, int order) {
            checked = order > 0;
            Drawable.ConstantState state = app.icon == null ? null : app.icon.getConstantState();
            icon.setImageDrawable(state == null ? app.icon : state.newDrawable().mutate());
            name.setText(app.label);
            mark.order = order;
            mark.invalidate();
            setBackground(button(getContext(), checked ? 0x18347ff4
                    : HyperOsStyle.dark(getContext()) ? 0x16777777 : 0x12777777, 17));
            setContentDescription(app.label + (checked ? "，已固定，第" + order + "位" : "，未固定"));
        }
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info);
            info.setClassName("android.widget.CheckBox");
            info.setCheckable(true);
            info.setChecked(checked);
        }
    }
    private static final class SelectionMark extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        int order;
        SelectionMark(Context context) { super(context); }
        @Override protected void onDraw(Canvas canvas) {
            float center = getWidth() / 2f;
            float radius = center - dp(getContext(), 1.5f);
            paint.setStyle(order > 0 ? Paint.Style.FILL : Paint.Style.STROKE);
            paint.setStrokeWidth(dp(getContext(), 1.4f));
            paint.setColor(order > 0 ? BLUE : HyperOsStyle.textSecondary(getContext()));
            canvas.drawCircle(center, getHeight() / 2f, radius, paint);
            if (order > 0) {
                paint.setStyle(Paint.Style.FILL);
                paint.setColor(Color.WHITE);
                paint.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
                paint.setTextAlign(Paint.Align.CENTER);
                paint.setTextSize(12 * getResources().getDisplayMetrics().scaledDensity);
                canvas.drawText(String.valueOf(order), center, (getHeight() - paint.ascent() - paint.descent()) / 2f, paint);
            }
        }
    }
    private static final class SearchIcon extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        SearchIcon(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO); }
        @Override protected void onDraw(Canvas canvas) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(getContext(), 1.6f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(HyperOsStyle.textSecondary(getContext()));
            float unit = getWidth() / 20f;
            canvas.drawCircle(unit * 8, unit * 8, unit * 5.5f, paint);
            canvas.drawLine(unit * 12, unit * 12, unit * 17, unit * 17, paint);
        }
    }
}

package com.ez.zalopatch.xposed.features;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.AssetManager;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.util.TypedValue;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.graphics.ColorUtils;
import androidx.core.view.ViewCompat;
import androidx.core.widget.TextViewCompat;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.R;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.shape.MaterialShapeDrawable;
import com.google.android.material.shape.ShapeAppearanceModel;

import java.io.InputStream;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

/**
 * Native emoji picker for the reaction bar.
 *
 * <p>Shows a real Material 3 dialog ({@link MaterialAlertDialogBuilder} on
 * {@code Theme.ZaloPatch}) whose choices are the host's own
 * {@code assets/emoji_by_cate.json} entries, filtered to native Unicode emoji. There is no
 * text input and nothing here sends, navigates or mutates the host: the only outbound
 * effect is one {@link Callback#onEmoji} call per selection, and cancel only dismisses.
 *
 * <p>The host process cannot resolve this module's resource ids, so the dialog runs on a
 * minimal themed wrapper that keeps the host Activity as its base (window token, window
 * services) but exposes the module's resources, assets and class loader. The inflater is
 * cloned into that wrapper so the Material layouts and view classes resolve from the
 * module APK.
 */
final class ReactionEmojiDialog {
    interface Callback {
        void onEmoji(String emoji);
    }

    private static final String MODULE_PACKAGE = "com.ez.zalopatch";
    private static final int CONTENT_PADDING_HORIZONTAL_DP = 24;
    private static final int CONTENT_PADDING_BOTTOM_DP = 8;
    private static final int LABEL_PADDING_TOP_DP = 12;
    private static final int LABEL_PADDING_BOTTOM_DP = 4;
    private static final int SCROLL_MIN_HEIGHT_DP = 240;
    private static final int SCROLL_MAX_HEIGHT_DP = 480;
    private static final float SCROLL_SCREEN_FRACTION = 0.5f;
    /** Emoji glyph size relative to the cell box. */
    private static final float GLYPH_CELL_RATIO = 0.56f;
    /** ~12% of the theme's on-primary-container color, as in Material's own state layers. */
    private static final int FAB_RIPPLE_ALPHA = 32;
    /** Minimum touch size, side padding, and label size for the reaction-bar add pill. */
    private static final int ADD_BUTTON_MIN_SIZE_DP = 48;
    private static final int ADD_BUTTON_HORIZONTAL_PADDING_DP = 16;
    private static final int ADD_BUTTON_TEXT_SIZE_SP = 14;
    /** Readable fallback when module resources cannot be loaded in the host process. */
    private static final String ADD_BUTTON_FALLBACK_LABEL = "Emoji";

    private static volatile Context moduleContext;
    private static volatile boolean moduleFallbackActive;
    private static volatile boolean moduleContextUnavailable;
    private static volatile String moduleContextUnavailableReason;

    private ReactionEmojiDialog() {
    }

    /**
     * Shows the picker, or returns {@code false} without showing anything when the host
     * catalogue is missing or malformed, when there is no host Activity to own the dialog,
     * or when this module's resources cannot be used from the host process.
     */
    static boolean show(Context hostContext, Callback callback, Runnable onCancel) {
        return showFailure(hostContext, callback, onCancel) == null;
    }

    /** Null means shown. Failures contain fixed stage codes and class metadata, never payloads. */
    static String showFailure(Context hostContext, Callback callback, Runnable onCancel) {
        Activity host = null;
        String stage = "activity_lookup";
        final String[] reason = {null};
        try {
            if (hostContext == null) return failure(code("host_context_missing", null), null, null);
            if (callback == null) return failure(code("callback_missing", null), hostContext, null);
            host = activityOf(hostContext);
            if (host == null) return failure(code("activity_missing", null), hostContext, null);
            // Read the host's catalogue before wrapping; only the Activity owns the window.
            final List<ReactionEmojiCatalog.Category> categories;
            stage = "catalog_open";
            try (InputStream input = hostContext.getAssets().open(ReactionEmojiCatalog.ASSET_NAME)) {
                stage = "catalog_read";
                categories = ReactionEmojiCatalog.read(input,
                        ReactionEmojiCatalog.language(Locale.getDefault()),
                        (failedStage, cause) -> reason[0] = code(failedStage, cause));
                stage = "catalog_close";
            }
            if (categories.isEmpty()) return failure(reason[0], hostContext, host);
            stage = "module_configuration";
            Context themed = themedContext(host, failedReason -> reason[0] = failedReason);
            if (themed == null) {
                return failure(reason[0], hostContext, host);
            }
            stage = "presentation";
            return present(themed, categories, callback, onCancel) == null
                    ? failure(code("presentation_no_dialog", null), hostContext, host) : null;
        } catch (Throwable throwable) {
            return failure(code(stage, throwable), hostContext, host);
        }
    }

    private static String code(String stage, Throwable cause) {
        return "stage=" + stage + " exception="
                + (cause == null ? "none" : cause.getClass().getName());
    }

    private static String failure(String reason, Context context, Activity activity) {
        return "emoji_picker " + reason + " context=" + className(context)
                + " context_loader=" + loaderClass(context) + " activity=" + className(activity)
                + " finishing=" + (activity == null ? "unknown" : activity.isFinishing())
                + " destroyed=" + (activity == null ? "unknown" : activity.isDestroyed());
    }

    static String describeFailure(String reason, Object listener, boolean nativeExpand) {
        return reason + " entry=" + (nativeExpand ? "native_expand" : "emoji_pill")
                + " listener=" + className(listener) + " listener_loader=" + loaderClass(listener)
                + " dialog_loader=" + loaderClass(ReactionEmojiDialog.class);
    }

    private static String className(Object value) {
        return value == null ? "none" : value.getClass().getName();
    }

    private static String loaderClass(Object value) {
        if (value == null) return "none";
        try {
            ClassLoader loader = (value instanceof Class<?> ? (Class<?>) value : value.getClass())
                    .getClassLoader();
            return loader == null ? "bootstrap" : loader.getClass().getName();
        } catch (Throwable ignored) {
            return "unavailable";
        }
    }

    /**
     * Styles the reaction bar's add button as a Material 3 primary-container text pill, using
     * the module theme's own color roles and shape tokens. The visible label and 48dp touch
     * size are applied even when the module theme is unavailable, so the action never falls
     * back to a bare "+".
     */
    static void styleFab(TextView button, Context hostContext) {
        if (button == null || hostContext == null) {
            return;
        }
        Context themed = null;
        try {
            themed = themedContext(hostContext);
        } catch (Throwable ignored) {
        }
        applyAddButtonLabel(button, hostContext, themed);
        if (themed == null) {
            return;
        }
        try {
            Integer container = MaterialColors.getColorOrNull(
                    themed, com.google.android.material.R.attr.colorPrimaryContainer);
            Integer onContainer = MaterialColors.getColorOrNull(
                    themed, com.google.android.material.R.attr.colorOnPrimaryContainer);
            if (container == null || onContainer == null) {
                return;
            }
            ShapeAppearanceModel shape = ShapeAppearanceModel.builder(
                    themed,
                    com.google.android.material.R.style.ShapeAppearance_Material3_Corner_Full,
                    com.google.android.material.R.style.ShapeAppearance_Material3_Corner_Full)
                    .build();
            MaterialShapeDrawable content = new MaterialShapeDrawable(shape);
            content.setFillColor(ColorStateList.valueOf(container));
            MaterialShapeDrawable mask = new MaterialShapeDrawable(shape);
            mask.setFillColor(ColorStateList.valueOf(android.graphics.Color.WHITE));
            button.setBackground(new RippleDrawable(
                    ColorStateList.valueOf(
                            ColorUtils.setAlphaComponent(onContainer, FAB_RIPPLE_ALPHA)),
                    content, mask));
            button.setTextColor(onContainer);
            if (button.getContentDescription() == null) {
                button.setContentDescription(
                        themed.getString(R.string.zp_emoji_picker_add));
            }
        } catch (Throwable throwable) {
            // Leave the host's colors in place; the label and touch size are already applied.
        }
    }

    /** Applies the localized label and a 48dp-tall, padded click target. */
    private static void applyAddButtonLabel(TextView button, Context hostContext, Context themed) {
        String label = ADD_BUTTON_FALLBACK_LABEL;
        if (themed != null) {
            try {
                label = themed.getString(R.string.zp_emoji_picker_add_label);
            } catch (Throwable ignored) {
            }
        }
        button.setText(label);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, ADD_BUTTON_TEXT_SIZE_SP);
        button.setSingleLine(true);
        button.setGravity(Gravity.CENTER);
        int horizontalPadding = dp(hostContext, ADD_BUTTON_HORIZONTAL_PADDING_DP);
        button.setPadding(horizontalPadding, 0, horizontalPadding, 0);
        button.setMinWidth(dp(hostContext, ADD_BUTTON_MIN_SIZE_DP));
        button.setMinHeight(dp(hostContext, ADD_BUTTON_MIN_SIZE_DP));
    }

    /**
     * Renders the picker on an already themed context and shows it. Package-private, like
     * the class itself, and returns the dialog so the instrumentation test can inspect the
     * real Material dialog and grid; the hook-facing surface stays {@link #show}.
     */
    static AlertDialog present(Context themed, List<ReactionEmojiCatalog.Category> categories,
                               Callback callback, Runnable onCancel) {
        final Selection selection = new Selection(callback, onCancel);

        LinearLayout content = new LinearLayout(themed);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(
                dp(themed, CONTENT_PADDING_HORIZONTAL_DP),
                0,
                dp(themed, CONTENT_PADDING_HORIZONTAL_DP),
                dp(themed, CONTENT_PADDING_BOTTOM_DP));

        final MaxHeightScrollView scroll =
                new MaxHeightScrollView(themed, scrollMaxHeightPx(themed));
        scroll.setClipToPadding(false);
        scroll.addView(content,
                new ScrollView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        final AlertDialog dialog = new MaterialAlertDialogBuilder(themed)
                .setTitle(R.string.zp_emoji_picker_title)
                .setView(scroll)
                .setNegativeButton(R.string.zp_emoji_picker_cancel, null)
                .setCancelable(true)
                .setOnDismissListener(dismissed -> selection.dismissed())
                .create();

        // The dialog's content width is only known after layout, and it can change again
        // (rotation, fold, window resize), so the grid is rebound whenever the width does.
        final GridBinder binder = new GridBinder(content, categories, selection, dialog);
        scroll.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override
            public boolean onPreDraw() {
                int available = scroll.getWidth()
                        - 2 * dp(scroll.getContext(), CONTENT_PADDING_HORIZONTAL_DP);
                if (available > 0) {
                    try {
                        binder.bind(scroll.getContext(), available);
                    } catch (Throwable throwable) {
                        // An unusable grid must not take the host down; the dialog stays empty.
                    }
                }
                return true;
            }
        });

        dialog.show();
        return dialog;
    }

    /** Renders the category grids for one measured content width, once per distinct width. */
    private static final class GridBinder {
        private final LinearLayout content;
        private final List<ReactionEmojiCatalog.Category> categories;
        private final Selection selection;
        private final AlertDialog dialog;
        private int appliedWidthPx;

        GridBinder(LinearLayout content, List<ReactionEmojiCatalog.Category> categories,
                   Selection selection, AlertDialog dialog) {
            this.content = content;
            this.categories = categories;
            this.selection = selection;
            this.dialog = dialog;
        }

        void bind(Context themed, int availableWidthPx) {
            if (availableWidthPx == appliedWidthPx) {
                return;
            }
            appliedWidthPx = availableWidthPx;
            ReactionEmojiGrid.Metrics metrics =
                    ReactionEmojiGrid.measure(availableWidthPx, density(themed));
            content.removeAllViews();
            fill(themed, content, categories, availableWidthPx, metrics, selection, dialog);
        }
    }

    private static void fill(Context themed, LinearLayout content,
                             List<ReactionEmojiCatalog.Category> categories, int availableWidthPx,
                             ReactionEmojiGrid.Metrics metrics, Selection selection,
                             AlertDialog dialog) {
        Drawable.ConstantState cellState = cellBackgroundState(themed);
        Integer labelColor = MaterialColors.getColorOrNull(
                themed, com.google.android.material.R.attr.colorPrimary);

        for (ReactionEmojiCatalog.Category category : categories) {
            if (!category.title.isEmpty()) {
                TextView label = new TextView(themed);
                label.setText(category.title);
                TextViewCompat.setTextAppearance(label,
                        com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
                if (labelColor != null) {
                    label.setTextColor(labelColor);
                }
                label.setPadding(0, dp(themed, LABEL_PADDING_TOP_DP), 0,
                        dp(themed, LABEL_PADDING_BOTTOM_DP));
                ViewCompat.setAccessibilityHeading(label, true);
                content.addView(label, new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            }
            GridLayout grid = new GridLayout(themed);
            grid.setColumnCount(metrics.columns);
            grid.setUseDefaultMargins(false);
            grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
            // Full content width: the cells keep their fixed 48dp width and GridLayout
            // spreads the leftover as even spacing between the columns.
            content.addView(grid, new LinearLayout.LayoutParams(
                    availableWidthPx, ViewGroup.LayoutParams.WRAP_CONTENT));

            for (String emoji : category.emojis) {
                grid.addView(emojiCell(themed, emoji, cellState, metrics, selection, dialog),
                        cellParams(metrics));
            }
        }
    }

    private static TextView emojiCell(Context themed, String emoji, Drawable.ConstantState cellState,
                                      ReactionEmojiGrid.Metrics metrics, Selection selection,
                                      AlertDialog dialog) {
        TextView item = new TextView(themed);
        item.setText(emoji);
        item.setTextSize(TypedValue.COMPLEX_UNIT_PX, metrics.cellWidthPx * GLYPH_CELL_RATIO);
        item.setGravity(Gravity.CENTER);
        item.setIncludeFontPadding(false);
        item.setFocusable(true);
        item.setClickable(true);
        item.setTextIsSelectable(false);
        item.setLongClickable(false);
        item.setContentDescription(emoji);
        if (cellState != null) {
            item.setBackground(cellState.newDrawable(themed.getResources()).mutate());
        }
        item.setOnClickListener(view -> {
            try {
                selection.choose(emoji);
            } finally {
                dialog.dismiss();
            }
        });
        return item;
    }

    private static GridLayout.LayoutParams cellParams(ReactionEmojiGrid.Metrics metrics) {
        GridLayout.LayoutParams params = new GridLayout.LayoutParams(
                GridLayout.spec(GridLayout.UNDEFINED, 1), GridLayout.spec(GridLayout.UNDEFINED, 1));
        params.width = metrics.cellWidthPx;
        params.height = metrics.rowHeightPx;
        return params;
    }

    /** Themed, borderless ripple for one emoji cell, shared by constant state. */
    private static Drawable.ConstantState cellBackgroundState(Context themed) {
        TypedArray attributes = themed.obtainStyledAttributes(
                new int[]{androidx.appcompat.R.attr.selectableItemBackgroundBorderless});
        try {
            Drawable background = attributes.getDrawable(0);
            return background == null ? null : background.getConstantState();
        } finally {
            attributes.recycle();
        }
    }

    private static int scrollMaxHeightPx(Context themed) {
        int screen = themed.getResources().getDisplayMetrics().heightPixels;
        int fraction = Math.round(screen * SCROLL_SCREEN_FRACTION);
        return Math.max(dp(themed, SCROLL_MIN_HEIGHT_DP),
                Math.min(dp(themed, SCROLL_MAX_HEIGHT_DP), fraction));
    }

    /**
     * Resolves module resources from the existing module context or package context first.
     * When package visibility hides the module, it uses the loader-provided APK identity.
     * The configuration is applied per show, never cached for the trusted fallback.
     */
    private static Resources moduleResources(Context host, Consumer<String> failure) {
        Context cached = moduleContext;
        if (cached != null) {
            return configuredResources(cached, host);
        }
        if (moduleContextUnavailable) {
            if (failure != null) failure.accept(moduleContextUnavailableReason + " cached=true");
            return null;
        }
        if (moduleFallbackActive) {
            Resources fallback = loadFallbackResources(host);
            if (fallback != null) {
                return fallback;
            }
            moduleContextUnavailableReason = code("module_resources_fallback", null);
            moduleContextUnavailable = true;
            if (failure != null) {
                failure.accept(moduleContextUnavailableReason + " cached=false");
            }
            return null;
        }
        Context candidate = null;
        try {
            candidate = HookConfig.resolveModuleContextForHooks();
        } catch (Throwable ignored) {
        }
        String unavailable = null;
        if (modulePackageFailure(candidate) != null) {
            candidate = null;
            try {
                candidate = host.createPackageContext(MODULE_PACKAGE,
                        Context.CONTEXT_IGNORE_SECURITY);
            } catch (Throwable cause) {
                unavailable = code("module_context_create", cause);
            }
        }
        String invalid = modulePackageFailure(candidate);
        if (invalid != null) {
            Resources fallback = loadFallbackResources(host);
            if (fallback != null) {
                moduleFallbackActive = true;
                return fallback;
            }
            moduleContextUnavailableReason = unavailable == null ? invalid : unavailable;
            moduleContextUnavailable = true;
            if (failure != null) failure.accept(moduleContextUnavailableReason + " cached=false");
            return null;
        }
        moduleContext = candidate;
        return configuredResources(candidate, host);
    }

    private static Resources configuredResources(Context module, Context host) {
        return module.createConfigurationContext(
                new Configuration(host.getResources().getConfiguration())).getResources();
    }

    private static Resources loadFallbackResources(Context host) {
        try {
            Resources resources = ReactionEmojiResources.load(host);
            return ReactionEmojiResources.valid(resources) ? resources : null;
        } catch (Throwable ignored) {
            return null;
        }
    }

    /**
     * Null only for this module's own context: the package name must match and the module
     * theme id must resolve to the module's own resource name. A plain resource-name lookup
     * is not enough, because an unrelated host resource id can resolve to something else
     * while looking non-null.
     */
    private static String modulePackageFailure(Context candidate) {
        if (candidate == null) return code("module_context_missing", null);
        if (!MODULE_PACKAGE.equals(candidate.getPackageName())) {
            return code("module_package_mismatch", null);
        }
        try {
            String name = candidate.getResources().getResourceName(R.style.Theme_ZaloPatch);
            return (MODULE_PACKAGE + ":style/Theme.ZaloPatch").equals(name)
                    ? null : code("module_resources_mismatch", null);
        } catch (Throwable throwable) {
            return code("module_resources", throwable);
        }
    }

    /**
     * Themed module context: host Activity base (window token and window services), module
     * resources, assets and module code class loader, configured like the host so density,
     * UI mode and rotation follow the host instead of the module's first configuration.
     */
    static Context themedContext(Context host) {
        return themedContext(host, null);
    }

    private static Context themedContext(Context host, Consumer<String> failure) {
        Resources resources = moduleResources(host, failure);
        if (resources == null) {
            return null;
        }
        return new ModuleThemedContext(host, resources, R.style.Theme_ZaloPatch);
    }

    /** Activity behind a possibly wrapped context, or null. */
    static Activity activityOf(Context context) {
        Context current = context;
        while (current instanceof ContextWrapper) {
            if (current instanceof Activity) {
                return (Activity) current;
            }
            current = ((ContextWrapper) current).getBaseContext();
        }
        return null;
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    private static float density(Context context) {
        return context.getResources().getDisplayMetrics().density;
    }

    /** One selection per dialog: a chosen emoji suppresses the later cancel callback. */
    private static final class Selection {
        private final Callback callback;
        private final Runnable onCancel;
        private boolean chosen;
        private boolean cancelled;

        Selection(Callback callback, Runnable onCancel) {
            this.callback = callback;
            this.onCancel = onCancel;
        }

        void choose(String emoji) {
            if (chosen || cancelled) {
                return;
            }
            chosen = true;
            callback.onEmoji(emoji);
        }

        void dismissed() {
            if (chosen || cancelled) {
                return;
            }
            cancelled = true;
            if (onCancel != null) {
                try {
                    onCancel.run();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    /**
     * Host Activity as the base context, module resources/assets/class loader on top, and
     * the module theme built from the module's own resource table. The wrapper never
     * touches the host's theme, activity or back stack; it only borrows its window.
     */
    private static final class ModuleThemedContext extends ContextThemeWrapper {
        private final Resources module;
        private Resources.Theme moduleTheme;
        private LayoutInflater inflater;

        ModuleThemedContext(Context host, Resources module, int themeResId) {
            super(host, themeResId);
            this.module = module;
        }

        @Override
        public Resources getResources() {
            return module;
        }

        @Override
        public AssetManager getAssets() {
            return module.getAssets();
        }

        @Override
        public ClassLoader getClassLoader() {
            // The module package context is created without INCLUDE_CODE, so its own loader
            // is the host/system one. The loader that actually owns the module dex is the one
            // that loaded this class, and it can resolve Material's classes and views.
            return ReactionEmojiDialog.class.getClassLoader();
        }

        @Override
        public Resources.Theme getTheme() {
            Resources.Theme theme = moduleTheme;
            if (theme == null) {
                theme = module.newTheme();
                theme.applyStyle(R.style.Theme_ZaloPatch, true);
                moduleTheme = theme;
            }
            return theme;
        }

        @Override
        public void setTheme(int resId) {
            moduleTheme = null;
            if (resId != 0 && resId != R.style.Theme_ZaloPatch) {
                getTheme().applyStyle(resId, true);
            }
        }

        /**
         * The inherited implementation may snapshot the host's theme into an
         * host-resource theme; this wrapper builds its own theme in {@link #getTheme()}.
         */
        @Override
        protected void onApplyThemeResource(Resources.Theme theme, int resId, boolean first) {
        }

        /**
         * The inherited implementation clones the base inflater into this wrapper, but the
         * clone identity is not part of its contract, so verify it here: the Material
         * layouts must be inflated with the module resources of this wrapper.
         */
        @Override
        public Object getSystemService(String name) {
            if (Context.LAYOUT_INFLATER_SERVICE.equals(name)) {
                LayoutInflater current = inflater;
                if (current == null) {
                    LayoutInflater base = (LayoutInflater) super.getSystemService(name);
                    if (base == null) {
                        return null;
                    }
                    current = base.getContext() == this ? base : base.cloneInContext(this);
                    inflater = current;
                }
                return current;
            }
            return super.getSystemService(name);
        }
    }

    /** Scrollable emoji area capped so a long catalogue cannot outgrow the screen. */
    private static final class MaxHeightScrollView extends ScrollView {
        private final int maxHeightPx;

        MaxHeightScrollView(Context context, int maxHeightPx) {
            super(context);
            this.maxHeightPx = maxHeightPx;
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (maxHeightPx > 0 && getMeasuredHeight() > maxHeightPx) {
                setMeasuredDimension(getMeasuredWidth(), maxHeightPx);
            }
        }
    }
}

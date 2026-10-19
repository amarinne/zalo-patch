package com.ez.zalopatch.xposed.features;

import android.app.Dialog;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Emoji picker for the reaction bar, drawn with Material 3 surface, type and color roles.
 *
 * <p>The host process cannot resolve this module's resources, so the surface is built from
 * code. Colors follow the device light/dark setting and, on Android 12 and newer, the
 * system dynamic palette. Only emoji from the host's own {@code emoji_by_cate.json} are
 * offered, so nothing but native emoji can be picked.
 */
final class ReactionEmojiDialog {
    interface Callback {
        void onEmoji(String emoji);
    }

    private static final String CATALOG_ASSET = "emoji_by_cate.json";
    private static final int COLUMNS = 7;

    private ReactionEmojiDialog() {
    }

    /** Returns false when the host catalog is unavailable and nothing was shown. */
    /** Turns an emoji into the text to display, e.g. with the host app's own emoji images. */
    interface Renderer {
        CharSequence render(String emoji, float sizePx);
    }

    static boolean show(Context context, Callback callback, Runnable onCancel,
                        Renderer renderer) {
        List<String> titles = new ArrayList<>();
        List<List<String>> groups = new ArrayList<>();
        boolean vi = "vi".equals(Locale.getDefault().getLanguage());
        if (!loadCatalog(context, vi ? "vi" : "en", titles, groups)) {
            return false;
        }
        Palette palette = Palette.forContext(context);
        Dialog dialog = new Dialog(context);
        boolean[] picked = new boolean[1];
        dialog.setOnDismissListener(d -> {
            if (!picked[0]) {
                onCancel.run();
            }
        });
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);

        LinearLayout surface = new LinearLayout(context);
        surface.setOrientation(LinearLayout.VERTICAL);
        surface.setPadding(dp(context, 24), dp(context, 24), dp(context, 24), dp(context, 16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(palette.surface);
        background.setCornerRadius(dp(context, 28));
        surface.setBackground(background);

        TextView title = new TextView(context);
        title.setText(vi ? "Chọn emoji" : "Choose an emoji");
        title.setTextColor(palette.onSurface);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        surface.addView(title, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        GridLayout grid = new GridLayout(context);
        grid.setColumnCount(COLUMNS);
        int cell = dp(context, 44);
        for (int g = 0; g < groups.size(); g++) {
            TextView label = new TextView(context);
            label.setText(titles.get(g));
            label.setTextColor(palette.onSurfaceVariant);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            label.setPadding(0, dp(context, 12), 0, dp(context, 4));
            GridLayout.LayoutParams labelParams = new GridLayout.LayoutParams(
                    GridLayout.spec(GridLayout.UNDEFINED, 1),
                    GridLayout.spec(0, COLUMNS));
            labelParams.setGravity(Gravity.FILL_HORIZONTAL);
            grid.addView(label, labelParams);
            for (String emoji : groups.get(g)) {
                TextView item = new TextView(context);
                item.setText(renderer.render(emoji, dp(context, 28)));
                item.setGravity(Gravity.CENTER);
                item.setTextSize(TypedValue.COMPLEX_UNIT_SP, 26);
                item.setBackground(ripple(palette.primary, dp(context, 12)));
                item.setOnClickListener(v -> {
                    picked[0] = true;
                    dialog.dismiss();
                    callback.onEmoji(emoji);
                });
                GridLayout.LayoutParams params = new GridLayout.LayoutParams();
                params.width = cell;
                params.height = cell;
                grid.addView(item, params);
            }
        }
        ScrollView scroll = new ScrollView(context);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(grid);
        LinearLayout.LayoutParams scrollParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(context, 340));
        scrollParams.topMargin = dp(context, 8);
        surface.addView(scroll, scrollParams);

        LinearLayout customRow = new LinearLayout(context);
        customRow.setOrientation(LinearLayout.HORIZONTAL);
        customRow.setGravity(Gravity.CENTER_VERTICAL);
        EditText input = new EditText(context);
        input.setHint(vi ? "Emoji hoặc chữ tùy chỉnh" : "Custom emoji or text");
        input.setHintTextColor(palette.onSurfaceVariant);
        input.setTextColor(palette.onSurface);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPadding(dp(context, 16), 0, dp(context, 16), 0);
        GradientDrawable field = new GradientDrawable();
        field.setColor(Color.TRANSPARENT);
        field.setStroke(dp(context, 1), palette.onSurfaceVariant);
        field.setCornerRadius(dp(context, 28));
        input.setBackground(field);
        customRow.addView(input, new LinearLayout.LayoutParams(0, dp(context, 48), 1f));
        TextView send = new TextView(context);
        send.setText(vi ? "Gửi" : "Send");
        send.setTextColor(palette.surface);
        send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        send.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        send.setGravity(Gravity.CENTER);
        send.setPadding(dp(context, 20), 0, dp(context, 20), 0);
        GradientDrawable sendBg = new GradientDrawable();
        sendBg.setColor(palette.primary);
        sendBg.setCornerRadius(dp(context, 20));
        send.setBackground(sendBg);
        Runnable submit = () -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) {
                return;
            }
            picked[0] = true;
            dialog.dismiss();
            callback.onEmoji(text);
        };
        send.setOnClickListener(v -> submit.run());
        input.setOnEditorActionListener((v, actionId, event) -> {
            submit.run();
            return true;
        });
        LinearLayout.LayoutParams sendParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40));
        sendParams.leftMargin = dp(context, 8);
        customRow.addView(send, sendParams);
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.topMargin = dp(context, 12);
        surface.addView(customRow, rowParams);

        TextView cancel = new TextView(context);
        cancel.setText(vi ? "Hủy" : "Cancel");
        cancel.setTextColor(palette.primary);
        cancel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        cancel.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        cancel.setGravity(Gravity.CENTER);
        cancel.setPadding(dp(context, 12), 0, dp(context, 12), 0);
        cancel.setBackground(ripple(palette.primary, dp(context, 20)));
        cancel.setOnClickListener(v -> dialog.dismiss());
        LinearLayout.LayoutParams cancelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, dp(context, 40));
        cancelParams.gravity = Gravity.END;
        cancelParams.topMargin = dp(context, 8);
        surface.addView(cancel, cancelParams);

        dialog.setContentView(surface);
        dialog.show();
        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int width = Math.min((int) (metrics.widthPixels * 0.92f), dp(context, 400));
            window.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT);
            window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
        return true;
    }

    private static boolean loadCatalog(Context context, String language, List<String> titles,
                                       List<List<String>> groups) {
        try (InputStream in = context.getAssets().open(CATALOG_ASSET);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            JSONArray categories = new JSONArray(out.toString("UTF-8"));
            for (int i = 0; i < categories.length(); i++) {
                JSONObject category = categories.getJSONObject(i);
                JSONArray emojis = category.getJSONArray("emojis");
                List<String> list = new ArrayList<>();
                for (int j = 0; j < emojis.length(); j++) {
                    list.add(emojis.getString(j));
                }
                if (list.isEmpty()) {
                    continue;
                }
                JSONObject name = category.optJSONObject("name");
                titles.add(name == null ? "" : name.optString(language, name.optString("en", "")));
                groups.add(list);
            }
        } catch (Throwable throwable) {
            return false;
        }
        return !groups.isEmpty();
    }

    private static RippleDrawable ripple(int color, int radiusPx) {
        GradientDrawable mask = new GradientDrawable();
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        int pressed = (color & 0x00FFFFFF) | 0x33000000;
        return new RippleDrawable(ColorStateList.valueOf(pressed), null, mask);
    }

    private static int dp(Context context, int value) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                context.getResources().getDisplayMetrics()));
    }

    /** Activity behind a possibly wrapped context, or null. */
    static android.app.Activity activityOf(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof android.app.Activity) {
                return (android.app.Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /** Material 3 color roles for the current device theme. */
    private static final class Palette {
        final int surface;
        final int onSurface;
        final int onSurfaceVariant;
        final int primary;

        private Palette(int surface, int onSurface, int onSurfaceVariant, int primary) {
            this.surface = surface;
            this.onSurface = onSurface;
            this.onSurfaceVariant = onSurfaceVariant;
            this.primary = primary;
        }

        static Palette forContext(Context context) {
            boolean night = (context.getResources().getConfiguration().uiMode
                    & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
            boolean dynamic = Build.VERSION.SDK_INT >= 31;
            if (night) {
                return new Palette(
                        dynamic ? context.getColor(android.R.color.system_neutral1_800) : 0xFF2B2930,
                        dynamic ? context.getColor(android.R.color.system_neutral1_100) : 0xFFE6E0E9,
                        dynamic ? context.getColor(android.R.color.system_neutral2_200) : 0xFFCAC4D0,
                        dynamic ? context.getColor(android.R.color.system_accent1_200) : 0xFFD0BCFF);
            }
            return new Palette(
                    dynamic ? context.getColor(android.R.color.system_neutral1_50) : 0xFFECE6F0,
                    dynamic ? context.getColor(android.R.color.system_neutral1_900) : 0xFF1D1B20,
                    dynamic ? context.getColor(android.R.color.system_neutral2_700) : 0xFF49454F,
                    dynamic ? context.getColor(android.R.color.system_accent1_600) : 0xFF6750A4);
        }
    }

    // Package-private helper kept here so the hook class stays free of view-building code.
    static void styleFab(TextView button, Context context) {
        Palette palette = Palette.forContext(context);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(palette.primary);
        button.setBackground(circle);
        button.setTextColor(palette.surface);
    }
}

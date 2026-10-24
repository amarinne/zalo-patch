package com.ez.zalopatch.xposed.features;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.test.ActivityInstrumentationTestCase2;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.ez.zalopatch.R;
import com.ez.zalopatch.StatusActivity;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.shape.MaterialShapeDrawable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Rendered checks for the native emoji picker: the module-resource wrapper, the real themed
 * Material dialog and its container, the responsive grid at constrained 320dp/360dp widths,
 * day and night rendering, and the selection and cancel contracts. Nothing here sends a
 * reaction or touches host navigation; the callbacks are local collectors.
 *
 * <p>The picker reads Zalo's own {@code assets/emoji_by_cate.json}, so the catalogue-asset
 * test serves a synthetic fixture from this androidTest APK.
 */
public final class ReactionEmojiUiTest extends ActivityInstrumentationTestCase2<StatusActivity> {
    private static final String SMILE = "\uD83D\uDE00";
    private static final String THUMB_SKIN = "\uD83D\uDC4D\uD83C\uDFFB";
    private static final String COPYRIGHT = "\u00A9\uFE0F";
    private static final String KEYCAP_HASH = "#\uFE0F\u20E3";
    private static final String HEART = "\u2764\uFE0F";
    private static final String ENGLAND_FLAG =
            "\uD83C\uDFF4\uDB40\uDC67\uDB40\uDC62\uDB40\uDC65\uDB40\uDC6E\uDB40\uDC67\uDB40\uDC7F";
    private static final List<String> OFFERED =
            Arrays.asList(SMILE, THUMB_SKIN, COPYRIGHT, KEYCAP_HASH, HEART, ENGLAND_FLAG);
    private static final String CATALOG = "[{\"name\":{\"en\":\"Faces\"},\"emojis\":[\""
            + SMILE + "\",\"" + THUMB_SKIN + "\",\"(y)\",\"sticker:1\",\"1" + SMILE + "\",\""
            + COPYRIGHT + "\",\"" + KEYCAP_HASH + "\",\"" + HEART + "\",\"" + ENGLAND_FLAG
            + "\"]}]";

    public ReactionEmojiUiTest() {
        super(StatusActivity.class);
    }

    @Override
    protected void tearDown() throws Exception {
        try {
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        } catch (Throwable ignored) {
        }
        super.tearDown();
    }

    public void testThemedWrapperCarriesModuleResourcesAndHostConfiguration() throws Exception {
        StatusActivity activity = getActivity();
        final Context[] themed = new Context[2];
        getInstrumentation().runOnMainSync(() -> {
            themed[0] = ReactionEmojiDialog.themedContext(configuredHost(activity, false, 320));
            themed[1] = ReactionEmojiDialog.themedContext(configuredHost(activity, true, 360));
        });
        Context light = themed[0];
        Context dark = themed[1];
        assertNotNull("light wrapper", light);
        assertNotNull("dark wrapper", dark);

        // Host configuration, not the module's first configuration, decides width and night.
        Configuration lightConfig = light.getResources().getConfiguration();
        assertEquals(320, lightConfig.screenWidthDp);
        assertEquals(Configuration.UI_MODE_NIGHT_NO,
                lightConfig.uiMode & Configuration.UI_MODE_NIGHT_MASK);
        assertEquals(360, dark.getResources().getConfiguration().screenWidthDp);
        assertEquals(Configuration.UI_MODE_NIGHT_YES,
                dark.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK);

        // Module resources, module code loader, module assets, inflater cloned into the wrapper.
        assertEquals("com.ez.zalopatch:style/Theme.ZaloPatch",
                light.getResources().getResourceName(R.style.Theme_ZaloPatch));
        assertSame(ReactionEmojiDialog.class.getClassLoader(), light.getClassLoader());
        assertNotNull(Class.forName("com.google.android.material.dialog.MaterialAlertDialogBuilder",
                false, light.getClassLoader()));
        light.getAssets().open("dexkit-notices.txt").close();
        LayoutInflater inflater =
                (LayoutInflater) light.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        assertNotNull(inflater);
        assertSame("the inflater must be cloned into the wrapper", light, inflater.getContext());

        // Real Material 3 container role, and night must not reuse the day palette.
        Integer lightContainer = MaterialColors.getColorOrNull(
                light, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        Integer darkContainer = MaterialColors.getColorOrNull(
                dark, com.google.android.material.R.attr.colorSurfaceContainerHigh);
        assertNotNull(lightContainer);
        assertNotNull(darkContainer);
        assertFalse("night must not reuse the day container", lightContainer.equals(darkContainer));
    }

    public void testPickerRendersFilteredEmojiInAThemedMaterialDialog() throws Exception {
        StatusActivity activity = getActivity();
        assertEquals(OFFERED, ReactionEmojiCatalog.parse(CATALOG, "en").get(0).emojis);
        final Context[] themed = new Context[1];
        final List<String> chosen = Collections.synchronizedList(new ArrayList<>());
        final AlertDialog[] dialog = new AlertDialog[1];
        getInstrumentation().runOnMainSync(() -> {
            themed[0] = ReactionEmojiDialog.themedContext(activity);
            dialog[0] = ReactionEmojiDialog.present(themed[0],
                    ReactionEmojiCatalog.parse(CATALOG, "en"), chosen::add, null);
        });
        getInstrumentation().waitForIdleSync();
        assertNotNull(dialog[0]);
        assertTrue(dialog[0].isShowing());
        awaitStableGrid(dialog[0].findViewById(com.google.android.material.R.id.custom));

        // A real Material 3 container: rounded shape drawable filled with the dialog role.
        MaterialShapeDrawable container = containerOf(dialog[0]);
        assertNotNull("the dialog must carry the Material container", container);
        assertNotNull(container.getFillColor());
        assertEquals(MaterialColors.getColorOrNull(
                        themed[0], com.google.android.material.R.attr.colorSurfaceContainerHigh),
                Integer.valueOf(container.getFillColor().getDefaultColor()));
        assertTrue("the container must be rounded",
                container.getShapeAppearanceModel().getTopLeftCornerSize()
                        .getCornerSize(new RectF(0f, 0f, 200f, 200f)) > 0f);

        // Module strings reach the real dialog chrome.
        TextView title = dialog[0].findViewById(com.google.android.material.R.id.alertTitle);
        assertNotNull(title);
        assertEquals(themed[0].getString(R.string.zp_emoji_picker_title), title.getText().toString());
        TextView cancel = dialog[0].findViewById(android.R.id.button2);
        assertNotNull(cancel);
        assertEquals(themed[0].getString(R.string.zp_emoji_picker_cancel), cancel.getText().toString());

        final List<TextView> cells = new ArrayList<>();
        getInstrumentation().runOnMainSync(() ->
                collectCells(dialog[0].findViewById(com.google.android.material.R.id.custom), cells));
        assertEquals("only the filtered catalogue is offered", OFFERED.size(), cells.size());
        for (int i = 0; i < cells.size(); i++) {
            assertEquals(OFFERED.get(i), cells.get(i).getText().toString());
            assertEquals("each choice needs an accessibility label",
                    cells.get(i).getText().toString(), cells.get(i).getContentDescription());
        }

        // One selection per dialog, and a dismissed picker never sends again.
        getInstrumentation().runOnMainSync(() -> cells.get(0).performClick());
        getInstrumentation().waitForIdleSync();
        assertEquals(Collections.singletonList(OFFERED.get(0)), chosen);
        assertFalse(dialog[0].isShowing());
        getInstrumentation().runOnMainSync(() -> cells.get(1).performClick());
        assertEquals("a dismissed picker must not send again", 1, chosen.size());
    }

    public void testGridFitsConstrainedDialogWidthsWithFullTouchTargets() throws Exception {
        StatusActivity activity = getActivity();
        List<ReactionEmojiCatalog.Category> categories = ReactionEmojiCatalog.parse(CATALOG, "en");
        for (int widthDp : new int[]{320, 360}) {
            final AlertDialog[] dialog = new AlertDialog[1];
            getInstrumentation().runOnMainSync(() -> dialog[0] = ReactionEmojiDialog.present(
                    ReactionEmojiDialog.themedContext(activity), categories, emoji -> {
                    }, null));
            getInstrumentation().waitForIdleSync();
            View panel = dialog[0].findViewById(com.google.android.material.R.id.custom);
            awaitStableGrid(panel);
            assertTrue(widthDp + "dp: the panel must be measured before resizing",
                    panel.getWidth() > 0);

            getInstrumentation().runOnMainSync(() -> dialog[0].getWindow().setLayout(
                    dp(activity, widthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
            awaitStableGrid(panel);

            int available = panel.getWidth() - 2 * dp(activity, 24);
            float density = activity.getResources().getDisplayMetrics().density;
            ReactionEmojiGrid.Metrics metrics = ReactionEmojiGrid.measure(available, density);
            assertTrue(widthDp + "dp: the panel must be measured", available > 0);
            assertTrue(widthDp + "dp: the panel must respect the window width",
                    panel.getWidth() <= dp(activity, widthDp));
            assertTrue(widthDp + "dp: cells keep the 48dp touch target",
                    metrics.cellWidthPx >= dp(activity, ReactionEmojiGrid.MIN_TOUCH_TARGET_DP));

            final List<TextView> cells = new ArrayList<>();
            final List<GridLayout> grids = new ArrayList<>();
            getInstrumentation().runOnMainSync(() -> {
                collectCells(panel, cells);
                collectGrids(panel, grids);
            });
            assertEquals(OFFERED.size(), cells.size());
            assertFalse(grids.isEmpty());
            for (GridLayout grid : grids) {
                assertEquals(widthDp + "dp: the grid must fill the content width",
                        available, grid.getWidth());
            }
            int firstRowTop = cells.get(0).getTop();
            int inFirstRow = 0;
            for (TextView cell : cells) {
                assertEquals(widthDp + "dp: cell width", metrics.cellWidthPx,
                        cell.getLayoutParams().width);
                assertEquals(widthDp + "dp: cell height", metrics.rowHeightPx,
                        cell.getLayoutParams().height);
                assertTrue(widthDp + "dp: no cell may leave its grid",
                        cell.getRight() <= ((View) cell.getParent()).getWidth());
                if (cell.getTop() == firstRowTop) {
                    inFirstRow++;
                }
            }
            assertEquals(widthDp + "dp: rendered columns",
                    Math.min(metrics.columns, OFFERED.size()), inFirstRow);

            getInstrumentation().runOnMainSync(() -> dialog[0].cancel());
            getInstrumentation().waitForIdleSync();
        }
        assertFalse(activity.isFinishing());
    }

    public void testNightPickerCancelsLocallyWithoutTouchingTheHost() throws Exception {
        StatusActivity activity = getActivity();
        final CountDownLatch cancelled = new CountDownLatch(1);
        final List<String> chosen = Collections.synchronizedList(new ArrayList<>());
        final AlertDialog[] dialog = new AlertDialog[1];
        final Context[] themed = new Context[1];
        getInstrumentation().runOnMainSync(() -> {
            themed[0] = ReactionEmojiDialog.themedContext(configuredHost(activity, true, 360));
            dialog[0] = ReactionEmojiDialog.present(themed[0],
                    ReactionEmojiCatalog.parse(CATALOG, "en"), chosen::add, cancelled::countDown);
        });
        getInstrumentation().waitForIdleSync();
        MaterialShapeDrawable container = containerOf(dialog[0]);
        assertNotNull(container);
        assertEquals(MaterialColors.getColorOrNull(
                        themed[0], com.google.android.material.R.attr.colorSurfaceContainerHigh),
                Integer.valueOf(container.getFillColor().getDefaultColor()));

        getInstrumentation().runOnMainSync(() -> dialog[0].cancel());
        assertTrue("cancel must report back", cancelled.await(2, TimeUnit.SECONDS));
        assertTrue(chosen.isEmpty());
        assertFalse(dialog[0].isShowing());
        assertFalse("cancel must not navigate or finish the host", activity.isFinishing());
        assertFalse(activity.isDestroyed());
    }

    public void testShowReadsHostCatalogueAndFailsClosedWithoutIt() throws Exception {
        StatusActivity activity = getActivity();
        Context withCatalogue =
                withAssets(activity, getInstrumentation().getContext().getAssets());
        final boolean[] shown = new boolean[1];
        getInstrumentation().runOnMainSync(() -> shown[0] =
                ReactionEmojiDialog.show(withCatalogue, emoji -> {
                }, null));
        assertTrue("the catalogue asset of the given context must open the picker", shown[0]);
        getInstrumentation().waitForIdleSync();

        // Zalo owns the catalogue; without it the picker must stay closed instead of guessing.
        final boolean[] withoutCatalogue = new boolean[1];
        getInstrumentation().runOnMainSync(() -> withoutCatalogue[0] =
                ReactionEmojiDialog.show(activity, emoji -> {
                }, null));
        assertFalse(withoutCatalogue[0]);
        assertFalse(activity.isFinishing());
    }

    public void testStyleFabUsesThemeRolesAndLabelsTheAddButton() throws Exception {
        StatusActivity activity = getActivity();
        Configuration configuration =
                new Configuration(activity.getResources().getConfiguration());
        configuration.fontScale = 1.5f;
        final Context host = activity.createConfigurationContext(configuration);
        final TextView button = new TextView(host);
        final Context[] themed = new Context[1];
        final int constrainedWidth = dp(activity, 200);
        final int[] measured = new int[2];
        getInstrumentation().runOnMainSync(() -> {
            themed[0] = ReactionEmojiDialog.themedContext(host);
            ReactionEmojiDialog.styleFab(button, host);
            button.measure(
                    View.MeasureSpec.makeMeasureSpec(constrainedWidth,
                            View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
            measured[0] = button.getMeasuredWidth();
            measured[1] = button.getMeasuredHeight();
        });
        assertNotNull("themed context", themed[0]);
        assertNotNull("themed background", button.getBackground());
        assertNotNull("the add button needs an accessibility label",
                button.getContentDescription());
        String label = themed[0].getString(R.string.zp_emoji_picker_add_label);
        assertEquals("the add button must show its localized label", label,
                button.getText().toString());
        assertFalse("the add button must not be a bare +",
                "+".contentEquals(button.getText()));
        assertTrue("the label pill keeps the 48dp touch row",
                measured[1] >= dp(activity, 48));
        assertTrue("the label pill fits a constrained picker width",
                measured[0] >= dp(activity, 48) && measured[0] <= constrainedWidth);
        float labelWidth = button.getPaint().measureText(button.getText().toString());
        int horizontalPadding = button.getPaddingLeft() + button.getPaddingRight();
        assertTrue("the label must not be clipped at the increased font scale",
                labelWidth + horizontalPadding <= measured[0]);
        Integer onContainer = MaterialColors.getColorOrNull(
                themed[0], com.google.android.material.R.attr.colorOnPrimaryContainer);
        assertNotNull(onContainer);
        assertEquals(onContainer.intValue(), button.getCurrentTextColor());
    }

    public void testTrustedResourceFallbackShowsOnHostWindowAndTracksConfiguration() throws Exception {
        StatusActivity activity = getActivity();
        Object previousModule = replaceStatic(com.ez.zalopatch.HookConfig.class,
                "moduleContext", new ContextWrapper(activity) {
                    @Override public String getPackageName() { return "fixture.hidden"; }
                });
        Object previousContext = replaceStatic(ReactionEmojiDialog.class, "moduleContext", null);
        Object previousFallback = replaceStatic(ReactionEmojiDialog.class,
                "moduleFallbackActive", false);
        Object previousUnavailable = replaceStatic(ReactionEmojiDialog.class,
                "moduleContextUnavailable", false);
        Object previousReason = replaceStatic(ReactionEmojiDialog.class,
                "moduleContextUnavailableReason", null);
        try {
            ApplicationInfo identity = new ApplicationInfo(activity.getApplicationInfo());
            ReactionEmojiResources.setModuleApplicationInfo(identity);
            final int[] packageAttempts = {0};
            Context hidden = new ContextWrapper(activity) {
                @Override public Context createPackageContext(String name, int flags)
                        throws PackageManager.NameNotFoundException {
                    packageAttempts[0]++;
                    throw new PackageManager.NameNotFoundException("hidden package");
                }
            };
            Context nightHost = configuredHost(hidden, true, 360);
            final Context[] themed = new Context[1];
            getInstrumentation().runOnMainSync(() -> themed[0] =
                    ReactionEmojiDialog.themedContext(nightHost));
            assertNotNull("trusted framework identity must load hidden module resources", themed[0]);
            assertEquals(1, packageAttempts[0]);
            assertEquals("com.ez.zalopatch:style/Theme.ZaloPatch",
                    themed[0].getResources().getResourceName(R.style.Theme_ZaloPatch));
            assertEquals(360, themed[0].getResources().getConfiguration().screenWidthDp);
            assertEquals(Configuration.UI_MODE_NIGHT_YES,
                    themed[0].getResources().getConfiguration().uiMode
                            & Configuration.UI_MODE_NIGHT_MASK);
            assertSame(activity.getSystemService(Context.WINDOW_SERVICE),
                    themed[0].getSystemService(Context.WINDOW_SERVICE));
            assertSame(ReactionEmojiDialog.class.getClassLoader(), themed[0].getClassLoader());

            final AlertDialog[] dialog = new AlertDialog[1];
            getInstrumentation().runOnMainSync(() -> dialog[0] = ReactionEmojiDialog.present(
                    themed[0], ReactionEmojiCatalog.parse(CATALOG, "en"),
                    emoji -> fail("cancel must not select"), () -> { }));
            getInstrumentation().waitForIdleSync();
            assertTrue(dialog[0].isShowing());
            getInstrumentation().runOnMainSync(() -> dialog[0].cancel());
            assertFalse(dialog[0].isShowing());
            assertFalse(activity.isFinishing());
            assertFalse(activity.isDestroyed());

            Context dayHost = configuredHost(hidden, false, 320);
            Context day = ReactionEmojiDialog.themedContext(dayHost);
            assertEquals(320, day.getResources().getConfiguration().screenWidthDp);
            assertEquals(Configuration.UI_MODE_NIGHT_NO,
                    day.getResources().getConfiguration().uiMode
                            & Configuration.UI_MODE_NIGHT_MASK);
        } finally {
            ReactionEmojiResources.setModuleApplicationInfo(null);
            replaceStatic(ReactionEmojiDialog.class, "moduleContext", previousContext);
            replaceStatic(ReactionEmojiDialog.class, "moduleFallbackActive", previousFallback);
            replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailable", previousUnavailable);
            replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailableReason", previousReason);
            replaceStatic(com.ez.zalopatch.HookConfig.class, "moduleContext", previousModule);
        }
    }

    public void testResourceFallbackKeepsPackageContextPriority() {
        StatusActivity activity = getActivity();
        Object previousModule = replaceStatic(com.ez.zalopatch.HookConfig.class,
                "moduleContext", new ContextWrapper(activity) {
                    @Override public String getPackageName() { return "fixture.hidden"; }
                });
        Object previousContext = replaceStatic(ReactionEmojiDialog.class, "moduleContext", null);
        Object previousFallback = replaceStatic(ReactionEmojiDialog.class,
                "moduleFallbackActive", false);
        Object previousUnavailable = replaceStatic(ReactionEmojiDialog.class,
                "moduleContextUnavailable", false);
        try {
            ReactionEmojiResources.setModuleApkPath("relative-invalid.apk");
            final int[] attempts = {0};
            Context visible = new ContextWrapper(activity) {
                @Override public Context createPackageContext(String name, int flags) {
                    attempts[0]++;
                    return activity;
                }
            };
            assertNotNull(ReactionEmojiDialog.themedContext(visible));
            assertEquals("package context remains the first usable path", 1, attempts[0]);
            assertFalse((Boolean) readStatic(ReactionEmojiDialog.class, "moduleFallbackActive"));
        } finally {
            ReactionEmojiResources.setModuleApplicationInfo(null);
            replaceStatic(ReactionEmojiDialog.class, "moduleContext", previousContext);
            replaceStatic(ReactionEmojiDialog.class, "moduleFallbackActive", previousFallback);
            replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailable", previousUnavailable);
            replaceStatic(com.ez.zalopatch.HookConfig.class, "moduleContext", previousModule);
        }
    }

    public void testMissingWrongAndCollidingFallbackIdentityFailsClosed() {
        StatusActivity activity = getActivity();
        Object previousModule = replaceStatic(com.ez.zalopatch.HookConfig.class,
                "moduleContext", new ContextWrapper(activity) {
                    @Override public String getPackageName() { return "fixture.hidden"; }
                });
        Object previousContext = replaceStatic(ReactionEmojiDialog.class, "moduleContext", null);
        Object previousFallback = replaceStatic(ReactionEmojiDialog.class,
                "moduleFallbackActive", false);
        Object previousUnavailable = replaceStatic(ReactionEmojiDialog.class,
                "moduleContextUnavailable", false);
        Object previousReason = replaceStatic(ReactionEmojiDialog.class,
                "moduleContextUnavailableReason", null);
        try {
            final int[] attempts = {0};
            Context hidden = new ContextWrapper(activity) {
                @Override public Context createPackageContext(String name, int flags)
                        throws PackageManager.NameNotFoundException {
                    attempts[0]++;
                    throw new PackageManager.NameNotFoundException("hidden package");
                }
            };
            ReactionEmojiResources.setModuleApplicationInfo(null);
            assertNull(ReactionEmojiDialog.themedContext(hidden));
            String original = (String) readStatic(ReactionEmojiDialog.class,
                    "moduleContextUnavailableReason");
            assertTrue(original.contains("module_context_create"));

            replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailable", false);
            ApplicationInfo wrong = new ApplicationInfo(activity.getApplicationInfo());
            wrong.packageName = "com.zing.zalo";
            ReactionEmojiResources.setModuleApplicationInfo(wrong);
            assertNull(ReactionEmojiDialog.themedContext(hidden));

            replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailable", false);
            ReactionEmojiResources.setModuleApplicationInfo(activity.getApplicationInfo());
            Resources collision = new Resources(activity.getResources().getAssets(),
                    activity.getResources().getDisplayMetrics(),
                    activity.getResources().getConfiguration()) {
                @Override public String getResourceName(int id) {
                    return "com.zing.zalo:style/HostCollision";
                }
            };
            assertFalse("resource identity collision must fail closed",
                    ReactionEmojiResources.valid(collision));
            assertEquals("package failure remains the diagnostic reason", original,
                    readStatic(ReactionEmojiDialog.class, "moduleContextUnavailableReason"));
            assertEquals(2, attempts[0]);
        } finally {
            ReactionEmojiResources.setModuleApplicationInfo(null);
            replaceStatic(ReactionEmojiDialog.class, "moduleContext", previousContext);
            replaceStatic(ReactionEmojiDialog.class, "moduleFallbackActive", previousFallback);
            replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailable", previousUnavailable);
            replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailableReason", previousReason);
            replaceStatic(com.ez.zalopatch.HookConfig.class, "moduleContext", previousModule);
        }
    }

    public void testShowFailureIdentifiesInputActivityAndCatalogueBranches() {
        StatusActivity activity = getActivity();
        getInstrumentation().runOnMainSync(() -> {
            ReactionEmojiDialog.Callback neverSelect = emoji -> fail("diagnostics must not select");
            assertStage("host_context_missing", ReactionEmojiDialog.showFailure(null, neverSelect, null));
            assertStage("callback_missing", ReactionEmojiDialog.showFailure(activity, null, null));
            assertStage("activity_missing", ReactionEmojiDialog.showFailure(
                    activity.getApplicationContext(), neverSelect, null));
            Context brokenWrapper = new ContextWrapper(activity) {
                @Override public Context getBaseContext() { throw new DiagnosticException(); }
                @Override public String toString() { throw new AssertionError("no context toString"); }
            };
            String lookup = ReactionEmojiDialog.showFailure(brokenWrapper, neverSelect, null);
            assertStage("activity_lookup", lookup);
            assertTrue(lookup.contains("exception=" + DiagnosticException.class.getName()));
            assertSafe(lookup);
            assertStage("catalog_open", ReactionEmojiDialog.showFailure(activity, neverSelect, null));
            for (String[] fixture : new String[][]{
                    {"", "catalog_read_empty"}, {"private fixture payload", "catalog_parse"},
                    {"[]", "catalog_empty"}}) {
                try (CatalogueAssets assets = new CatalogueAssets(activity, fixture[0])) {
                    String failure = ReactionEmojiDialog.showFailure(
                            withAssets(activity, assets.assets), neverSelect, null);
                    assertStage(fixture[1], failure);
                    assertTrue(failure.contains("activity=" + activity.getClass().getName()));
                    assertTrue(failure.contains("finishing=false destroyed=false"));
                    assertSafe(failure);
                } catch (Exception failure) {
                    throw new AssertionError(failure);
                }
            }
        });
    }

    public void testShowFailureRetainsModuleResolutionAndNegativeCacheReason() {
        StatusActivity activity = getActivity();
        getInstrumentation().runOnMainSync(() -> {
            Object module = replaceStatic(ReactionEmojiDialog.class, "moduleContext", null);
            Object fallback = replaceStatic(ReactionEmojiDialog.class, "moduleFallbackActive", false);
            Object unavailable = replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailable", false);
            Object reason = replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailableReason", null);
            ReactionEmojiResources.setModuleApplicationInfo(null);
            Object hookModule = replaceStatic(com.ez.zalopatch.HookConfig.class, "moduleContext",
                    new ContextWrapper(activity) {
                        @Override public String getPackageName() { return "fixture.invalid"; }
                    });
            try {
                final int[] attempts = {0};
                android.app.Activity failing = new android.app.Activity() {
                    @Override public AssetManager getAssets() {
                        return getInstrumentation().getContext().getAssets();
                    }
                    @Override public Context createPackageContext(String name, int flags)
                            throws android.content.pm.PackageManager.NameNotFoundException {
                        attempts[0]++;
                        throw new android.content.pm.PackageManager.NameNotFoundException("private fixture payload");
                    }
                };
                String first = ReactionEmojiDialog.showFailure(failing,
                        emoji -> fail("must not select"), null);
                assertStage("module_context_create", first);
                assertTrue(first.contains("exception=android.content.pm.PackageManager$NameNotFoundException"));
                assertTrue(first.contains("cached=false"));
                String cached = ReactionEmojiDialog.showFailure(failing,
                        emoji -> fail("must not select"), null);
                assertStage("module_context_create", cached);
                assertTrue(cached.contains("cached=true"));
                assertEquals("diagnostics must not retry the cached failure", 1, attempts[0]);
                assertSafe(first);
                assertSafe(cached);
                replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailable", false);
                android.app.Activity badResources = new android.app.Activity() {
                    @Override public AssetManager getAssets() {
                        return getInstrumentation().getContext().getAssets();
                    }
                    @Override public Context createPackageContext(String name, int flags) {
                        return new ContextWrapper(activity) {
                            @Override public Resources getResources() { throw new DiagnosticException(); }
                        };
                    }
                };
                String invalidResources = ReactionEmojiDialog.showFailure(badResources,
                        emoji -> fail("must not select"), null);
                assertStage("module_resources", invalidResources);
                assertTrue(invalidResources.contains("exception=" + DiagnosticException.class.getName()));
                assertSafe(invalidResources);
            } finally {
                replaceStatic(ReactionEmojiDialog.class, "moduleContext", module);
                replaceStatic(ReactionEmojiDialog.class, "moduleFallbackActive", fallback);
                replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailable", unavailable);
                replaceStatic(ReactionEmojiDialog.class, "moduleContextUnavailableReason", reason);
                replaceStatic(com.ez.zalopatch.HookConfig.class, "moduleContext", hookModule);
            }
        });
    }

    public void testShowFailureDistinguishesModuleResourcesAndPresentationExceptions() {
        StatusActivity activity = getActivity();
        String[] failures = new String[2];
        getInstrumentation().runOnMainSync(() -> {
            Object module = replaceStatic(ReactionEmojiDialog.class, "moduleContext",
                    new ContextWrapper(activity) {
                        @Override public Context createConfigurationContext(Configuration config) {
                            throw new DiagnosticException();
                        }
                    });
            Context catalogued = withAssets(activity, getInstrumentation().getContext().getAssets());
            try {
                failures[0] = ReactionEmojiDialog.showFailure(catalogued,
                        emoji -> fail("must not select"), null);
                replaceStatic(ReactionEmojiDialog.class, "moduleContext", new ContextWrapper(activity) {
                    @Override public Context createConfigurationContext(Configuration config) {
                        Context configured = activity.createConfigurationContext(config);
                        return new ContextWrapper(configured) {
                            // Configuration succeeds; fail when presentation builds the module theme.
                            @Override public Resources getResources() {
                                Resources base = super.getResources();
                                return new Resources(base.getAssets(), base.getDisplayMetrics(),
                                        base.getConfiguration()) {
                                    @Override public void getValue(int id, android.util.TypedValue outValue,
                                            boolean resolveRefs) {
                                        throw new DiagnosticException();
                                    }
                                };
                            }
                        };
                    }
                });
                failures[1] = ReactionEmojiDialog.showFailure(catalogued,
                        emoji -> fail("must not select"), null);
            } finally {
                replaceStatic(ReactionEmojiDialog.class, "moduleContext", module);
            }
        });
        // Report assertion failures through the runner instead of crashing the main thread.
        assertStage("module_configuration", failures[0]);
        assertStage("presentation", failures[1]);
        for (String failure : failures) {
            assertTrue(failure, failure.contains("exception=" + DiagnosticException.class.getName()));
            assertSafe(failure);
        }
    }

    @android.annotation.TargetApi(29)
    public void testChatFailureReachesSelfCheckAndSurvivesOpeningAndCanceling() throws Exception {
        StatusActivity activity = getActivity();
        List<android.content.ContentValues> rows = new ArrayList<>();
        android.content.ContentProvider provider = new android.content.ContentProvider() {
            @Override public boolean onCreate() { return true; }
            @Override public android.os.Bundle call(String method, String arg, android.os.Bundle extras) {
                if ("record_self_check".equals(method)) {
                    assertEquals("messages.custom_reaction", extras.getString("feature"));
                    rows.add(new android.content.ContentValues(
                            (android.content.ContentValues) extras.getParcelable("values")));
                }
                android.os.Bundle result = new android.os.Bundle();
                result.putInt("recorded", 1);
                return result;
            }
            @Override public android.database.Cursor query(android.net.Uri uri, String[] projection,
                    String selection, String[] args, String sort) { return null; }
            @Override public String getType(android.net.Uri uri) { return null; }
            @Override public android.net.Uri insert(android.net.Uri uri, android.content.ContentValues values) { return null; }
            @Override public int delete(android.net.Uri uri, String selection, String[] args) { return 0; }
            @Override public int update(android.net.Uri uri, android.content.ContentValues values,
                    String selection, String[] args) { return 0; }
        };
        android.content.pm.ProviderInfo info = new android.content.pm.ProviderInfo();
        info.authority = "com.ez.zalopatch.config";
        provider.attachInfo(activity, info);
        android.content.ContentResolver resolver = android.content.ContentResolver.wrap(provider);
        Context isolated = new ContextWrapper(activity) {
            @Override public android.content.ContentResolver getContentResolver() { return resolver; }
            @Override public void sendBroadcast(android.content.Intent intent) {
                fail("self-check fixture must not fall through to a real broadcast");
            }
        };
        java.lang.reflect.Field entriesField = com.ez.zalopatch.xposed.core.SelfCheckRegistry.class
                .getDeclaredField("ENTRIES");
        entriesField.setAccessible(true);
        @SuppressWarnings("unchecked") Map<String, Object> entries = (Map<String, Object>) entriesField.get(null);
        Object previousEntry = entries.remove("messages.custom_reaction");
        java.lang.reflect.Field fallbackField = com.ez.zalopatch.xposed.core.SelfCheckRegistry.class
                .getDeclaredField("FALLBACK_ONLY");
        fallbackField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean fallback =
                (java.util.concurrent.atomic.AtomicBoolean) fallbackField.get(null);
        boolean previousFallback = fallback.getAndSet(false);
        Object hookModule = replaceStatic(com.ez.zalopatch.HookConfig.class, "moduleContext", isolated);
        Object debug = replaceStatic(com.ez.zalopatch.HookConfig.class, "hookDebugEnabled", false);
        try {
            final ChatFeature chat = new ChatFeature(getClass().getClassLoader());
            FixtureListener listener = new FixtureListener() {
                @Override public void react(FixtureReaction reaction, String source) { fail("no native callback"); }
                @Override public void expand(String source) { fail("no native expansion"); }
                @Override public String toString() { throw new AssertionError("no listener toString"); }
            };
            Class<?> bindingType = Class.forName(ChatFeature.class.getName() + "$EmojiBindings");
            java.lang.reflect.Constructor<?> constructor = bindingType.getDeclaredConstructors()[0];
            constructor.setAccessible(true);
            Object bindings = constructor.newInstance(FixtureListener.class,
                    FixturePicker.class.getMethod("getListener"),
                    FixtureListener.class.getMethod("react", FixtureReaction.class, String.class),
                    FixtureReaction.class, new FixtureFactory(),
                    FixtureFactory.class.getMethod("make", int.class, String.class),
                    FixturePicker.class.getMethod("getReactionBarWidth"),
                    FixtureListener.class.getMethod("expand", String.class));
            java.lang.reflect.Field bindingField = ChatFeature.class.getDeclaredField("emojiBindings");
            bindingField.setAccessible(true);
            bindingField.set(chat, bindings);
            java.lang.reflect.Method open = ChatFeature.class.getDeclaredMethod("pickAndSendEmoji",
                    ViewGroup.class, Object.class, boolean.class);
            open.setAccessible(true);
            java.lang.reflect.Field gateField = ChatFeature.class.getDeclaredField("reactionSendGate");
            gateField.setAccessible(true);
            ReactionSendGate gate = (ReactionSendGate) gateField.get(chat);
            for (boolean nativeExpand : new boolean[]{false, true}) {
                getInstrumentation().runOnMainSync(() -> {
                    try {
                        open.invoke(chat, new FixturePicker(activity.getApplicationContext(), listener),
                                nativeExpand ? listener : null, nativeExpand);
                    } catch (Exception failure) { throw new AssertionError(failure); }
                });
                android.content.ContentValues row = rows.get(rows.size() - 1);
                assertEquals("failed", row.getAsString("status"));
                String error = row.getAsString("error");
                assertStage("activity_missing", error);
                assertTrue(error.contains("entry=" + (nativeExpand ? "native_expand" : "emoji_pill")));
                assertTrue(error.contains("listener=" + listener.getClass().getName()));
                assertTrue(error.contains("listener_loader=" + listener.getClass().getClassLoader().getClass().getName()));
                assertEquals(error, gate.failureReason());
                assertSafe(error);
            }
            String retained = gate.failureReason();
            int failedWrites = rows.size();
            getInstrumentation().runOnMainSync(() -> {
                try {
                    open.invoke(chat, new FixturePicker(withAssets(activity,
                            getInstrumentation().getContext().getAssets()), listener), null, false);
                } catch (Exception failure) { throw new AssertionError(failure); }
            });
            getInstrumentation().waitForIdleSync();
            assertEquals("opening must preserve the exact failure", retained, gate.failureReason());
            getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
            getInstrumentation().waitForIdleSync();
            assertEquals(retained, gate.failureReason());
            assertEquals("no success or replacement row on open/cancel", failedWrites, rows.size());
        } finally {
            fallback.set(previousFallback);
            replaceStatic(com.ez.zalopatch.HookConfig.class, "moduleContext", hookModule);
            replaceStatic(com.ez.zalopatch.HookConfig.class, "hookDebugEnabled", debug);
            entries.remove("messages.custom_reaction");
            if (previousEntry != null) entries.put("messages.custom_reaction", previousEntry);
        }
    }

    public interface FixtureListener {
        void react(FixtureReaction reaction, String source);
        void expand(String source);
    }

    public static final class FixtureReaction { }

    public static final class FixtureFactory {
        public FixtureReaction make(int type, String emoji) { throw new AssertionError("no native factory"); }
    }

    public static final class FixturePicker extends android.widget.LinearLayout {
        private final FixtureListener listener;
        FixturePicker(Context context, FixtureListener listener) { super(context); this.listener = listener; }
        public FixtureListener getListener() { return listener; }
        public int getReactionBarWidth() { return 0; }
    }

    private static final class DiagnosticException extends RuntimeException {
        DiagnosticException() { super("private fixture payload"); }
        @Override public String getMessage() { throw new AssertionError("no exception message"); }
        @Override public String toString() { throw new AssertionError("no exception toString"); }
    }

    private static Object replaceStatic(Class<?> owner, String name, Object value) {
        try {
            java.lang.reflect.Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            Object previous = field.get(null);
            field.set(null, value);
            return previous;
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static Object readStatic(Class<?> owner, String name) {
        try {
            java.lang.reflect.Field field = owner.getDeclaredField(name);
            field.setAccessible(true);
            return field.get(null);
        } catch (Exception failure) { throw new AssertionError(failure); }
    }

    private static void assertStage(String expected, String failure) {
        assertNotNull(failure);
        assertTrue("expected " + expected + " in " + failure, failure.contains("stage=" + expected + " "));
    }

    private static void assertSafe(String failure) {
        assertFalse(failure.contains("private fixture"));
        assertFalse(failure.contains("payload"));
        assertFalse(failure.contains("\n"));
        assertFalse(failure.contains("/data/"));
    }

    /** Assets-only disposable APK keeps failure tests on the real fixed-name asset path. */
    private static final class CatalogueAssets implements AutoCloseable {
        final AssetManager assets;
        final java.io.File apk;
        CatalogueAssets(Context context, String catalog) throws Exception {
            apk = java.io.File.createTempFile("reaction-catalog-", ".apk", context.getCacheDir());
            AssetManager opened = null;
            try {
                try (java.util.zip.ZipOutputStream zip = new java.util.zip.ZipOutputStream(
                        new java.io.FileOutputStream(apk))) {
                    zip.putNextEntry(new java.util.zip.ZipEntry("assets/" + ReactionEmojiCatalog.ASSET_NAME));
                    zip.write(catalog.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                    zip.closeEntry();
                }
                java.lang.reflect.Constructor<AssetManager> constructor = AssetManager.class.getDeclaredConstructor();
                constructor.setAccessible(true);
                opened = constructor.newInstance();
                int cookie = (Integer) AssetManager.class.getMethod("addAssetPath", String.class)
                        .invoke(opened, apk.getAbsolutePath());
                assertTrue("fixture asset path must load", cookie != 0);
                assets = opened;
            } catch (Exception | Error failure) {
                if (opened != null) opened.close();
                apk.delete();
                throw failure;
            }
        }
        @Override public void close() {
            try {
                assets.close();
            } finally {
                assertTrue("delete disposable fixture", apk.delete());
            }
        }
    }

    /**
     * Waits until the rendered grid is complete and its width has stopped changing, which is
     * what the dialog does after the first layout and after any later width change.
     */
    private void awaitStableGrid(View panel) throws Exception {
        assertNotNull("the dialog must expose its content panel", panel);
        int lastWidth = -1;
        for (int attempt = 0; attempt < 80; attempt++) {
            final int[] sample = new int[2];
            getInstrumentation().runOnMainSync(() -> {
                List<TextView> cells = new ArrayList<>();
                List<GridLayout> grids = new ArrayList<>();
                collectCells(panel, cells);
                collectGrids(panel, grids);
                sample[0] = cells.size();
                sample[1] = grids.isEmpty() ? -1 : grids.get(0).getWidth();
            });
            if (sample[0] == OFFERED.size() && sample[1] > 0 && sample[1] == lastWidth) {
                return;
            }
            lastWidth = sample[0] == OFFERED.size() ? sample[1] : -1;
            Thread.sleep(15);
        }
        fail("the emoji grid never settled");
    }

    /** The Material shape drawable behind the dialog window, unwrapped from its insets. */
    private static MaterialShapeDrawable containerOf(AlertDialog dialog) {        View decor = dialog.getWindow().getDecorView();
        Drawable background = decor.getBackground();
        if (background instanceof InsetDrawable) {
            background = ((InsetDrawable) background).getDrawable();
        }
        return background instanceof MaterialShapeDrawable
                ? (MaterialShapeDrawable) background : null;
    }

    /**
     * A host carrying the requested configuration while still being bound to the Activity.
     * {@code createConfigurationContext} alone returns a plain context, which loses the
     * Activity's window service and token that {@link ReactionEmojiDialog#present} needs for
     * night and width coverage in a real dialog; delegating services to the Activity keeps
     * both.
     */
    private static Context configuredHost(final Context host, boolean night, int widthDp) {
        Configuration configuration = new Configuration(host.getResources().getConfiguration());
        configuration.screenWidthDp = widthDp;
        configuration.screenHeightDp = 640;
        configuration.uiMode = (configuration.uiMode & ~Configuration.UI_MODE_NIGHT_MASK)
                | (night ? Configuration.UI_MODE_NIGHT_YES : Configuration.UI_MODE_NIGHT_NO);
        final Context configured = host.createConfigurationContext(configuration);
        return new ContextWrapper(host) {
            @Override
            public Resources getResources() {
                return configured.getResources();
            }

            @Override
            public AssetManager getAssets() {
                return configured.getAssets();
            }
        };
    }

    /** Host context whose assets come from the androidTest APK fixture. */
    private static Context withAssets(final Context host, final AssetManager assets) {
        return new ContextWrapper(host) {
            @Override
            public AssetManager getAssets() {
                return assets;
            }

            @Override
            public Resources getResources() {
                return host.getResources();
            }
        };
    }

    private static void collectCells(View view, List<TextView> out) {
        if (view instanceof GridLayout) {
            GridLayout grid = (GridLayout) view;
            for (int i = 0; i < grid.getChildCount(); i++) {
                View child = grid.getChildAt(i);
                if (child instanceof TextView) {
                    out.add((TextView) child);
                }
            }
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectCells(group.getChildAt(i), out);
            }
        }
    }

    private static void collectGrids(View view, List<GridLayout> out) {
        if (view instanceof GridLayout) {
            out.add((GridLayout) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectGrids(group.getChildAt(i), out);
            }
        }
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}

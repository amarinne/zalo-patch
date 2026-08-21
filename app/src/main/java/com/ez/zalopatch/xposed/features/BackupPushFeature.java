package com.ez.zalopatch.xposed.features;

import android.content.Context;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Shortens only Zalo's native scheduled message-backup interval. */
public final class BackupPushFeature extends Feature {
    private static final String FEATURE = "backup.scheduled";
    private static final String INTERVAL_KEY_PREFIX = "SERVER_CONFIG_SYNC_MESSAGE_INTERVAL_";

    private final boolean preflight;
    private final String preflightError;

    public BackupPushFeature(ClassLoader classLoader, boolean preflight, String preflightError) {
        super(classLoader);
        this.preflight = preflight;
        this.preflightError = preflightError;
    }

    @Override
    public String getFeatureName() {
        return "BackupPush";
    }

    @Override
    public void doHook() {
        boolean enabled = HookConfig.isEnabled(Tweaks.KEY_BACKUP_FREQUENT_PUSH);
        if (!preflight) {
            if (enabled) {
                SelfCheckRegistry.markStale(FEATURE, "structural preflight", preflightError);
            } else {
                SelfCheckRegistry.markDisabled(FEATURE,
                        "scheduled backup interval override");
            }
            return;
        }
        SymbolSchema.Active schema = SymbolSchema.activeForHooks(
                HookConfig.resolveModuleContextForHooks());
        String ownerName = schema.string("symbols.backup.interval_reader_class", "");
        String methodName = schema.string("symbols.backup.interval_reader_method", "");
        String target = "source=" + schema.source + " " + ownerName + "#" + methodName;
        try {
            Class<?> owner = XposedHelpers.findClass(ownerName, classLoader);
            Method method = owner.getDeclaredMethod(methodName,
                    long.class, boolean.class, String.class);
            method.setAccessible(true);
            XposedBridge.hookMethod(method, new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    if (!HookConfig.isEnabled(Tweaks.KEY_BACKUP_FREQUENT_PUSH)
                            || param.hasThrowable()
                            || param.args.length < 3
                            || !(param.args[2] instanceof String)
                            || !(param.getResult() instanceof Number)) {
                        return;
                    }
                    String key = (String) param.args[2];
                    if (!key.startsWith(INTERVAL_KEY_PREFIX)
                            || key.length() == INTERVAL_KEY_PREFIX.length()) {
                        return;
                    }
                    long nativeInterval = ((Number) param.getResult()).longValue();
                    int hours = HookConfig.getLevel(Tweaks.KEY_BACKUP_PUSH_INTERVAL);
                    param.setResult(BackupPushDecision.scheduledIntervalMillis(
                            true, hours, nativeInterval));
                    SelfCheckRegistry.incrementHit(FEATURE,
                            ownerName + "#" + methodName,
                            "effective interval " + hours + "h");
                }
            });
            markInstalled(enabled, target);
        } catch (Throwable throwable) {
            SelfCheckRegistry.markStale(FEATURE, target,
                    throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
        }
    }

    private static void markInstalled(boolean enabled, String target) {
        if (!enabled) {
            SelfCheckRegistry.markDisabled(FEATURE, target);
            return;
        }
        Context context = HookConfig.resolveFallbackContextForHooks();
        if (context != null) {
            ZaloPrefsReader.AutoBackup state = ZaloPrefsReader.autoBackup(context);
            if (state == ZaloPrefsReader.AutoBackup.DISABLED) {
                SelfCheckRegistry.markStatus(FEATURE, "disabled", target,
                        "Enable Zalo automatic backup to use the scheduled interval", "");
                return;
            }
            if (state == ZaloPrefsReader.AutoBackup.AMBIGUOUS) {
                SelfCheckRegistry.markStatus(FEATURE, "installed_no_hits", target,
                        "Multiple account preference rows; current account not inferred", "");
                return;
            }
        }
        SelfCheckRegistry.markInstalled(FEATURE, target, 1);
    }
}

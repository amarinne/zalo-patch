package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;

public final class SymbolSchemaHealthFeature extends Feature {
    private static final String FEATURE_SCHEMA = "symbol_schema";

    public SymbolSchemaHealthFeature(ClassLoader classLoader) {
        super(classLoader);
    }

    @Override
    public String getFeatureName() {
        return "SymbolSchemaHealth";
    }

    @Override
    public void doHook() {
        android.content.Context context = HookConfig.resolveFallbackContextForHooks();
        SymbolSchema.Health health = SymbolSchema.health(context);
        SymbolSchema.Active schema = health.schema;
        String target = schema.valid
                ? "bundled schema v" + schema.schemaVersion + "." + schema.schemaRevision
                        + " for Zalo " + schema.minCode
                : "bundled exact-version profiles";
        String error = "failed".equals(health.status) ? schema.validation : "";
        SelfCheckRegistry.markStatus(FEATURE_SCHEMA, health.status, target, health.message, error);
    }
}

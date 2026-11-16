package com.ez.zalopatch.xposed.features;

import com.ez.zalopatch.DexKitRecallFingerprint;
import com.ez.zalopatch.HookConfig;
import com.ez.zalopatch.RecallEventScope;
import com.ez.zalopatch.RecallGuardDecision;
import com.ez.zalopatch.RecallIdentity;
import com.ez.zalopatch.SymbolSchema;
import com.ez.zalopatch.Tweaks;
import com.ez.zalopatch.xposed.core.Feature;
import com.ez.zalopatch.xposed.core.SelfCheckRegistry;
import com.ez.zalopatch.xposed.core.XpHooks;
import com.ez.zalopatch.xposed.core.XpLog;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Keeps the local text of a message another account recalled.
 *
 * <p>The hook sits on the coordinator that runs before the host mutates the live message,
 * empties the row through the writer, and repaints the conversation preview. Skipping the
 * coordinator leaves the stored text untouched; a writer-only veto would run after those
 * mutations and could not restore them.
 *
 * <p>The same coordinator also handles local delete controls. A second resolved hook marks
 * the recall-specific undo handler for the duration of its synchronous call, and the
 * coordinator gate only skips inside that scope. Everything else, including delete
 * controls, groups, media, the account's own messages, and the hard-delete branch that
 * never reaches this coordinator, keeps the native path.
 */
public final class RecallGuardFeature extends Feature {
    /** Anchor-resolution row, independent of whether the tweak is enabled. */
    static final String FEATURE_ROUTE = "recall.route";

    private static final Map<String, Field> WRAPPER_FIELDS = new ConcurrentHashMap<>();
    private static final Map<String, Boolean> WRAPPER_MISSES = new ConcurrentHashMap<>();
    private static final AtomicBoolean READ_FAILURE_LOGGED = new AtomicBoolean();

    private final boolean compatible;
    private final String compatibilityError;

    public RecallGuardFeature(ClassLoader classLoader, boolean compatible,
                              String compatibilityError) {
        super(classLoader);
        this.compatible = compatible;
        this.compatibilityError = compatibilityError == null ? "" : compatibilityError;
    }

    @Override
    public String getFeatureName() {
        return "Keep recalled text";
    }

    /** The runtime shape the coordinator gate needs, resolved once from the composed schema. */
    private static final class Bindings {
        final Method coordinator;
        final Method undoHandler;
        final Method identityJson;
        final Field identity;
        final Field text;
        final Field type;
        final Field sender;
        final Field owner;
        final Field account;

        Bindings(Method coordinator, Method undoHandler, Method identityJson, Field identity,
                 Field text, Field type, Field sender, Field owner, Field account) {
            this.coordinator = coordinator;
            this.undoHandler = undoHandler;
            this.identityJson = identityJson;
            this.identity = identity;
            this.text = text;
            this.type = type;
            this.sender = sender;
            this.owner = owner;
            this.account = account;
        }
    }

    @Override
    public void doHook() {
        String feature = Tweaks.KEY_KEEP_RECALLED_TEXT;
        boolean enabled = HookConfig.isEnabled(feature);
        if (!enabled) SelfCheckRegistry.markDisabled(RecallMarker.FEATURE, "recalled display marker");
        if (!compatible) {
            SelfCheckRegistry.markStale(FEATURE_ROUTE, "recall coordinator",
                    compatibilityError.isEmpty()
                            ? "recall route unresolved" : compatibilityError);
            if (enabled) {
                SelfCheckRegistry.markStale(feature, "recall coordinator", compatibilityError);
            } else {
                SelfCheckRegistry.markDisabled(feature, "recall coordinator");
            }
            return;
        }
        Bindings bindings;
        String target;
        String route;
        try {
            SymbolSchema.Active schema = SymbolSchema.activeForHooks(
                    HookConfig.resolveModuleContextForHooks());
            String prefix = DexKitRecallFingerprint.PREFIX;
            String coordinatorClass = schema.string(prefix + "coordinator_class", "");
            String coordinatorMethod = schema.string(prefix + "coordinator_method", "");
            target = coordinatorClass + "#" + coordinatorMethod + "(Object)";
            route = "undo " + schema.string(prefix + "undo_handler_class", "?")
                    + "#" + schema.string(prefix + "undo_handler_method", "?")
                    + " -> coordinator " + target
                    + " -> writer " + schema.string(prefix + "writer_class", "?")
                    + "#" + schema.string(prefix + "writer_method", "?")
                    + " -> update " + schema.string(prefix + "writer_class", "?")
                    + "#" + schema.string(prefix + "update_method", "?")
                    + "; preview " + coordinatorClass + "#"
                    + schema.string(prefix + "preview_method", "?");
            bindings = bind(schema, coordinatorClass, coordinatorMethod);
        } catch (Throwable failure) {
            SelfCheckRegistry.markStale(FEATURE_ROUTE, "recall coordinator",
                    failure.getClass().getSimpleName() + " " + failure.getMessage());
            if (enabled) {
                SelfCheckRegistry.markStale(feature, "recall coordinator", failure.getMessage());
            } else {
                SelfCheckRegistry.markDisabled(feature, "recall coordinator");
            }
            return;
        }
        SelfCheckRegistry.markStatus(FEATURE_ROUTE, "ok", target, route, "");
        if (!enabled) {
            SelfCheckRegistry.markDisabled(feature, target);
            return;
        }
        List<XpHooks.Handle> markerHooks = new ArrayList<>();
        RecallMarker candidate = null;
        try {
            candidate = new RecallMarker(classLoader, bindings.identity.getDeclaringClass(),
                    SymbolSchema.activeForHooks(HookConfig.resolveModuleContextForHooks()),
                    message -> identityOf(message, bindings), markerHooks);
        } catch (Throwable failure) {
            for (XpHooks.Handle handle : markerHooks) handle.unhook();
            SelfCheckRegistry.markStale(RecallMarker.FEATURE, "recalled display marker",
                    failure.getClass().getSimpleName());
        }
        final RecallMarker marker = candidate;
        XpHooks.Handle scope = null;
        try {
            scope = XpHooks.hookMethod(feature + ".undo", bindings.undoHandler,
                    new XpHooks.Before() {
                        @Override
                        public void before(XpHooks.HookParam param) {
                            RecallEventScope.enterUndo();
                        }
                    },
                    new XpHooks.After() {
                        @Override
                        public void after(XpHooks.HookParam param) {
                            RecallEventScope.leaveUndo();
                        }
                    });
            XpHooks.hookMethod(feature, bindings.coordinator, new XpHooks.Before() {
                @Override
                public void before(XpHooks.HookParam param) {
                    RecallIdentity identity = keptIdentity(param, bindings);
                    if (identity != null) {
                        param.setResult(null);
                        if (marker != null) marker.remember(identity);
                        SelfCheckRegistry.markSuppressed(feature, target,
                                "kept text for a received recall");
                    }
                }
            }, null);
            SelfCheckRegistry.markInstalled(feature, target, 2);
            log("undo scope and coordinator gate installed at " + target);
        } catch (Throwable failure) {
            if (scope != null) scope.unhook();
            for (XpHooks.Handle handle : markerHooks) handle.unhook();
            SelfCheckRegistry.markFailed(feature, target, failure);
        }
    }

    private Bindings bind(SymbolSchema.Active schema, String coordinatorClass,
                          String coordinatorMethod) throws Exception {
        String prefix = DexKitRecallFingerprint.PREFIX;
        Class<?> coordinator = Class.forName(coordinatorClass, false, classLoader);
        Method method = coordinator.getDeclaredMethod(coordinatorMethod, Object.class);
        if (method.getReturnType() != Void.TYPE || Modifier.isStatic(method.getModifiers())) {
            throw new IllegalStateException("coordinator shape changed");
        }
        method.setAccessible(true);
        Class<?> message = Class.forName(schema.string(prefix + "model_class", ""),
                false, classLoader);
        Method undoHandler = Class.forName(
                schema.string(prefix + "undo_handler_class", ""), false, classLoader)
                .getDeclaredMethod(schema.string(prefix + "undo_handler_method", ""), message);
        if (undoHandler.getReturnType() != Void.TYPE
                || Modifier.isStatic(undoHandler.getModifiers())) {
            throw new IllegalStateException("undo handler shape changed");
        }
        undoHandler.setAccessible(true);
        Class<?> id = Class.forName(DexKitRecallFingerprint.MESSAGE_ID, false, classLoader);
        Class<?> writer = Class.forName(schema.string(prefix + "writer_class", ""),
                false, classLoader);
        Class<?> utility = Class.forName("com.zing.zalocore.CoreUtility", false, classLoader);
        Method identityJson = id.getDeclaredMethod(
                schema.string(prefix + "identity_json_method", ""));
        if (identityJson.getReturnType() != String.class
                || Modifier.isStatic(identityJson.getModifiers())) {
            throw new IllegalStateException("identity serialization shape changed");
        }
        identityJson.setAccessible(true);
        return new Bindings(method, undoHandler, identityJson,
                field(message, schema.string(prefix + "identity_field", ""), id),
                field(message, schema.string(prefix + "text_field", ""), String.class),
                field(message, schema.string(prefix + "type_field", ""), Integer.TYPE),
                field(message, schema.string(prefix + "sender_field", ""), String.class),
                field(id, schema.string(prefix + "owner_field", ""), String.class),
                staticField(utility, "i", String.class));
    }

    private static Field field(Class<?> owner, String name, Class<?> type) throws Exception {
        if (name == null || name.isEmpty()) {
            throw new IllegalStateException("recall field missing");
        }
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type || Modifier.isStatic(field.getModifiers())) {
            throw new IllegalStateException("recall field shape changed");
        }
        field.setAccessible(true);
        return field;
    }

    /** The account reader is a static engine field, not a message instance field. */
    private static Field staticField(Class<?> owner, String name, Class<?> type) throws Exception {
        Field field = owner.getDeclaredField(name);
        if (field.getType() != type || !Modifier.isStatic(field.getModifiers())) {
            throw new IllegalStateException("recall account field shape changed");
        }
        field.setAccessible(true);
        return field;
    }

    /**
     * Reads the coordinator argument and the stored message behind it. Any missing,
     * scrambled, or inconsistent read returns null so the native recall path runs.
     */
    private RecallIdentity keptIdentity(XpHooks.HookParam param, Bindings bindings) {
        if (!RecallEventScope.inUndo()) {
            return null;
        }
        try {
            Object argument = param.args != null && param.args.length == 1 ? param.args[0] : null;
            if (argument == null) {
                return null;
            }
            Object message = messageOf(argument, bindings.identity.getDeclaringClass());
            return identityOf(message, bindings);
        } catch (Throwable failure) {
            if (READ_FAILURE_LOGGED.compareAndSet(false, true)) {
                XpLog.i("ZaloPatch: [Keep recalled text] native path kept after "
                        + failure.getClass().getSimpleName());
            }
            return null;
        }
    }

    /** Rendering and suppression use the same exact, account-bound native identity. */
    private static RecallIdentity identityOf(Object message, Bindings bindings) throws Exception {
        if (!bindings.identity.getDeclaringClass().isInstance(message)) return null;
        Object messageId = bindings.identity.get(message);
        if (messageId == null) return null;
        String account = (String) bindings.account.get(null);
        String owner = (String) bindings.owner.get(messageId);
        String sender = (String) bindings.sender.get(message);
        if (!RecallGuardDecision.qualifies(sender, account, owner, bindings.type.getInt(message),
                (String) bindings.text.get(message))) return null;
        Object serialized = bindings.identityJson.invoke(messageId);
        RecallIdentity identity = serialized instanceof String
                ? RecallIdentity.parse(account, (String) serialized) : null;
        return identity != null && identity.owner.equals(owner) && identity.sender.equals(sender)
                ? identity : null;
    }

    /**
     * The coordinator declares {@code Object} and receives a small holder whose only message
     * field is the resolved model. A direct model argument and a single matching holder field
     * are both accepted; anything else is ambiguous and stays native.
     */
    private static Object messageOf(Object argument, Class<?> model) throws Exception {
        if (model.isInstance(argument)) {
            return argument;
        }
        Class<?> holder = argument.getClass();
        String key = holder.getName() + "|" + model.getName();
        if (WRAPPER_MISSES.containsKey(key)) {
            return null;
        }
        Field cached = WRAPPER_FIELDS.get(key);
        if (cached == null) {
            Field selected = null;
            for (Field candidate : holder.getDeclaredFields()) {
                if (Modifier.isStatic(candidate.getModifiers())
                        || !model.isAssignableFrom(candidate.getType())) {
                    continue;
                }
                if (selected != null) {
                    WRAPPER_MISSES.put(key, Boolean.TRUE);
                    return null;
                }
                selected = candidate;
            }
            if (selected == null) {
                WRAPPER_MISSES.put(key, Boolean.TRUE);
                return null;
            }
            selected.setAccessible(true);
            cached = selected;
            WRAPPER_FIELDS.put(key, cached);
        }
        return cached.get(argument);
    }
}

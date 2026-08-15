package com.ez.zalopatch;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

public final class NotificationRuleStore {
    public static final String MIRROR_KEY = "notifications.custom_rules";
    private static final String PREF_KEY = "notifications.custom_rules_json";
    private static final int FORMAT_VERSION = 1;

    public enum Type {
        KEYWORD_BLOCKLIST("keyword_blocklist", R.string.zp_rule_blocked_keywords),
        KEYWORD_EXCEPTIONS("keyword_exceptions", R.string.zp_rule_allowed_keywords),
        ACCOUNT_BLOCKLIST("account_blocklist", R.string.zp_rule_blocked_accounts),
        ACCOUNT_EXCEPTIONS("account_exceptions", R.string.zp_rule_allowed_accounts);

        public final String jsonKey;
        public final int titleRes;

        Type(String jsonKey, int titleRes) {
            this.jsonKey = jsonKey;
            this.titleRes = titleRes;
        }

        public String title(Context context) {
            return context.getString(titleRes);
        }

        public static Type fromName(String value) {
            if (value == null) {
                return null;
            }
            try {
                return valueOf(value);
            } catch (IllegalArgumentException ignored) {
                return null;
            }
        }
    }

    public static final class RuleSet {
        private final List<String> keywordBlocklist;
        private final List<String> keywordExceptions;
        private final List<String> accountBlocklist;
        private final List<String> accountExceptions;

        public RuleSet(
                List<String> keywordBlocklist,
                List<String> keywordExceptions,
                List<String> accountBlocklist,
                List<String> accountExceptions) {
            this.keywordBlocklist = immutable(keywordBlocklist);
            this.keywordExceptions = immutable(keywordExceptions);
            this.accountBlocklist = immutable(accountBlocklist);
            this.accountExceptions = immutable(accountExceptions);
        }

        public List<String> list(Type type) {
            if (type == Type.KEYWORD_BLOCKLIST) {
                return keywordBlocklist;
            }
            if (type == Type.KEYWORD_EXCEPTIONS) {
                return keywordExceptions;
            }
            if (type == Type.ACCOUNT_BLOCKLIST) {
                return accountBlocklist;
            }
            if (type == Type.ACCOUNT_EXCEPTIONS) {
                return accountExceptions;
            }
            return Collections.emptyList();
        }

        public RuleSet with(Type type, List<String> values) {
            return new RuleSet(
                    type == Type.KEYWORD_BLOCKLIST ? values : keywordBlocklist,
                    type == Type.KEYWORD_EXCEPTIONS ? values : keywordExceptions,
                    type == Type.ACCOUNT_BLOCKLIST ? values : accountBlocklist,
                    type == Type.ACCOUNT_EXCEPTIONS ? values : accountExceptions);
        }

        public int total() {
            return keywordBlocklist.size() + keywordExceptions.size()
                    + accountBlocklist.size() + accountExceptions.size();
        }

        public static RuleSet empty() {
            return new RuleSet(null, null, null, null);
        }
    }

    private NotificationRuleStore() {
    }

    public static RuleSet load(Context context) {
        String json = TweakStore.preferences(context).getString(PREF_KEY, "");
        try {
            return decode(json);
        } catch (Exception ignored) {
            return RuleSet.empty();
        }
    }

    public static boolean save(Context context, RuleSet rules) {
        try {
            SharedPreferences.Editor editor = TweakStore.preferences(context).edit();
            put(editor, rules);
            return editor.commit();
        } catch (Exception ignored) {
            return false;
        }
    }

    static void put(SharedPreferences.Editor editor, RuleSet rules) throws Exception {
        editor.putString(PREF_KEY, encode(rules));
    }

    public static String encode(RuleSet rules) throws Exception {
        RuleSet safeRules = rules == null ? RuleSet.empty() : rules;
        JSONObject root = new JSONObject();
        root.put("format_version", FORMAT_VERSION);
        for (Type type : Type.values()) {
            JSONArray values = new JSONArray();
            for (String value : safeRules.list(type)) {
                values.put(value);
            }
            root.put(type.jsonKey, values);
        }
        return root.toString();
    }

    public static RuleSet decode(String json) throws Exception {
        if (json == null || json.trim().isEmpty()) {
            return RuleSet.empty();
        }
        JSONObject root = new JSONObject(json);
        if (root.optInt("format_version", -1) != FORMAT_VERSION) {
            throw new IllegalArgumentException("Unsupported notification rule format");
        }
        return new RuleSet(
                decodeList(root, Type.KEYWORD_BLOCKLIST),
                decodeList(root, Type.KEYWORD_EXCEPTIONS),
                decodeList(root, Type.ACCOUNT_BLOCKLIST),
                decodeList(root, Type.ACCOUNT_EXCEPTIONS));
    }

    public static List<String> sanitize(List<String> values) {
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        if (values != null) {
            for (String value : values) {
                String clean = value == null ? "" : value.trim();
                if (clean.isEmpty()) {
                    continue;
                }
                String identity = identity(clean);
                boolean exists = false;
                for (String current : unique) {
                    if (identity(current).equals(identity)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    unique.add(clean);
                }
            }
        }
        return new ArrayList<>(unique);
    }

    private static String identity(String value) {
        String lower = value == null ? "" : value.toLowerCase(Locale.ROOT);
        String decomposed = Normalizer.normalize(lower, Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('đ', 'd');
    }

    private static List<String> decodeList(JSONObject root, Type type) throws Exception {
        JSONArray array = root.optJSONArray(type.jsonKey);
        ArrayList<String> values = new ArrayList<>();
        if (array != null) {
            for (int i = 0; i < array.length(); i++) {
                Object value = array.get(i);
                if (!(value instanceof String)) {
                    throw new IllegalArgumentException("Expected notification rule string");
                }
                values.add((String) value);
            }
        }
        return sanitize(values);
    }

    private static List<String> immutable(List<String> values) {
        return Collections.unmodifiableList(new ArrayList<>(sanitize(values)));
    }
}

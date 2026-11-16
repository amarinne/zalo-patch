package com.ez.zalopatch;

import org.json.JSONObject;
import org.junit.Test;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public final class InboxRowsPreflightTest {
    public static final class MessagesView { public Object adapter; }
    public static final class ReusedMediaBoxName { }

    @Test public void optionalMediaBoxDriftDoesNotRejectHealthyRows() throws Exception {
        for (String optional : new String[]{"", "missing.Row", ReusedMediaBoxName.class.getName()}) {
            List<String> errors = new ArrayList<>();
            assertTrue(errors.toString(), check(optional, "g", errors));
            assertTrue(errors.toString(), errors.isEmpty());
        }
    }

    @Test public void requiredConversationMarkerStillFailsClosed() throws Exception {
        List<String> errors = new ArrayList<>();
        assertFalse(check(ReusedMediaBoxName.class.getName(), "missing", errors));
        assertTrue(errors.toString(), errors.contains("top_out_field missing missing"));
    }

    private static boolean check(String optional, String marker, List<String> errors) throws Exception {
        JSONObject inbox = new JSONObject()
                .put("message_view_class", MessagesView.class.getName())
                .put("messages_view_adapter_field", "adapter")
                .put("top_out_field", marker).put("top_out_value_field", "a")
                .put("media_box_item_class", optional);
        JSONObject root = new JSONObject().put("symbols", new JSONObject().put("inbox", inbox));
        SymbolSchema.Active schema = new SymbolSchema.Active(root, "", "Test", 1, 1,
                1, 1, "", true, true, 1, 1, "");
        Method check = Class.forName("com.ez.zalopatch.xposed.core.SymbolPreflight")
                .getDeclaredMethod("checkInboxRows", SymbolSchema.Active.class,
                        ClassLoader.class, List.class);
        check.setAccessible(true);
        return (Boolean) check.invoke(null, schema, InboxRowsPreflightTest.class.getClassLoader(), errors);
    }
}

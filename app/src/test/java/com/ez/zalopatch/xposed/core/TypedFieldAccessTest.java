package com.ez.zalopatch.xposed.core;

import java.lang.reflect.Field;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

public final class TypedFieldAccessTest {
    private static final String TYPE = Conversation.class.getName();

    @Test
    public void inheritedBooleanFallsThroughForBothBooleanValues() throws Exception {
        for (boolean value : new boolean[]{false, true}) {
            OctoberRow row = new OctoberRow();
            row.e = value;
            Field stale = BooleanBase.class.getDeclaredField("e");
            assertFalse(TypedFieldAccess.hasType(stale, TYPE));
            assertNull(TypedFieldAccess.read(stale, row, TYPE));
            Field resolved = TypedFieldAccess.resolve(OctoberRow.class, "e", TYPE);
            assertEquals("f", resolved.getName());
            assertSame(row.f, TypedFieldAccess.read(resolved, row, TYPE));
            // Reusing the cached field must still inspect the current object's live value.
            row.f = null;
            assertNull(TypedFieldAccess.read(resolved, row, TYPE));
        }
    }

    @Test
    public void renamedMissingOrUnrelatedMappedFieldUsesUniqueTypedField() throws Exception {
        RenamedRow row = new RenamedRow();
        for (String mapped : new String[]{"e", "missing", "", null}) {
            Field resolved = TypedFieldAccess.resolve(RenamedRow.class, mapped, TYPE);
            assertEquals("renamed", resolved.getName());
            assertSame(row.renamed, TypedFieldAccess.read(resolved, row, TYPE));
        }
        // Even a live Conversation cannot authorize a field declared as Object.
        Field unrelated = RenamedRow.class.getDeclaredField("e");
        assertNull(TypedFieldAccess.read(unrelated, row, TYPE));
    }

    @Test
    public void validMappedFieldAndInheritedFieldRemainUsable() throws Exception {
        OldRow row = new OldRow();
        Field field = TypedFieldAccess.resolve(OldRow.class, "e", TYPE);
        assertEquals("e", field.getName());
        assertSame(row.e, TypedFieldAccess.read(field, row, TYPE));
        assertSame(row.e, TypedFieldAccess.read(
                TypedFieldAccess.resolve(InheritedRow.class, "e", TYPE),
                new InheritedRow(row.e), TYPE));
    }

    @Test
    public void ambiguousFieldsFailClosedEvenWithTypedMappingOrOneLiveValue() {
        assertNull(TypedFieldAccess.uniqueField(AmbiguousRow.class, TYPE));
        assertNull(TypedFieldAccess.resolve(AmbiguousRow.class, "e", TYPE));
        assertNull(TypedFieldAccess.resolve(AmbiguousRow.class, "missing", TYPE));
        assertNull(TypedFieldAccess.resolve(AmbiguousChild.class, "e", TYPE));
    }

    @Test
    public void shadowedUnrelatedNameDoesNotHideUniqueParentConversation() throws Exception {
        ShadowedRow row = new ShadowedRow();
        Field field = TypedFieldAccess.resolve(ShadowedRow.class, "e", TYPE);
        assertSame(OldRow.class, field.getDeclaringClass());
        assertSame(((OldRow) row).e, TypedFieldAccess.read(field, row, TYPE));
    }

    @Test
    public void staticWrongOwnerAndNullValuesCannotBecomeConversations() throws Exception {
        assertNull(TypedFieldAccess.resolve(StaticOnlyRow.class, "e", TYPE));
        StaticMappedRow staticMapped = new StaticMappedRow();
        Field structural = TypedFieldAccess.resolve(StaticMappedRow.class, "e", TYPE);
        assertEquals("f", structural.getName());
        assertSame(staticMapped.f, TypedFieldAccess.read(structural, staticMapped, TYPE));
        Field field = TypedFieldAccess.resolve(OldRow.class, "e", TYPE);
        assertNull(TypedFieldAccess.read(field, new OctoberRow(), TYPE));
        assertNull(TypedFieldAccess.read(field, null, TYPE));
        OldRow row = new OldRow();
        row.e = null;
        assertNull(TypedFieldAccess.read(field, row, TYPE));
        assertNull(TypedFieldAccess.resolve(null, "e", TYPE));
        assertNull(TypedFieldAccess.read(null, row, TYPE));
    }

    @Test
    public void boxedBooleanAlsoFallsThroughAndLiveSubtypeRemainsTyped() throws Exception {
        BoxedBooleanRow row = new BoxedBooleanRow();
        assertNull(TypedFieldAccess.read(BoxedBooleanRow.class.getDeclaredField("e"), row, TYPE));
        Field field = TypedFieldAccess.resolve(BoxedBooleanRow.class, "e", TYPE);
        assertSame(row.f, TypedFieldAccess.read(field, row, TYPE));
    }

    public static class Conversation {}
    public static class BooleanBase { boolean e; }
    public static final class OctoberRow extends BooleanBase { Conversation f = new Conversation(); }
    public static final class RenamedRow {
        Object e = new Conversation();
        Conversation renamed = new Conversation();
    }
    public static class OldRow { Conversation e = new Conversation(); }
    public static final class InheritedRow extends OldRow {
        InheritedRow(Conversation value) { e = value; }
    }
    public static final class AmbiguousRow extends OldRow { Conversation f; }
    public static final class AmbiguousChild extends OldRow { Conversation e; }
    public static final class ShadowedRow extends OldRow { boolean e; }
    public static final class StaticOnlyRow { static Conversation e = new Conversation(); }
    public static final class StaticMappedRow {
        static Conversation e = new Conversation();
        Conversation f = new Conversation();
    }
    public static final class ConversationSubtype extends Conversation {}
    public static final class BoxedBooleanRow {
        Boolean e = Boolean.TRUE;
        Conversation f = new ConversationSubtype();
    }
}

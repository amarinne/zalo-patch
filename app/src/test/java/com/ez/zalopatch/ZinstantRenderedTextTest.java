package com.ez.zalopatch;

import com.zing.zalo.zinstant.zom.node.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class ZinstantRenderedTextTest {
    public static class Tree { private final ZOMDocument shuffled = new ZOMDocument(); }
    public static class AmbiguousTree extends Tree { public ZOMDocument another = new ZOMDocument(); }
    public static class ThrowingText extends ZOMText {
        public ThrowingText() { super("zStyle"); }
        @Override public String getPlainText() { throw new IllegalStateException(); }
    }
    private Tree tree(ZOM root) { Tree tree = new Tree(); tree.shuffled.mZOMRoot = root; return tree; }

    @Test public void readsVisibleCanvasTextFromTypedDocumentWithoutAndroidViews() {
        assertEquals(" zStyle Background and music library", ZinstantRenderedText.read(tree(
                new ZOMContainer(new ZOMText("zStyle"), new ZOMText("Background and music library")))));
    }
    @Test public void skipsHiddenAndTransparentSubtrees() {
        ZOMContainer hidden = new ZOMContainer(new ZOMText("zStyle")); hidden.mVisibility = 1;
        ZOMText relative = new ZOMText("zStyle"); relative.mRelativeVisibility = 1;
        ZOMText transparent = new ZOMText("zStyle"); transparent.mOpacity = 0;
        assertEquals(" Documents", ZinstantRenderedText.read(tree(new ZOMContainer(
                hidden, relative, transparent, new ZOMText("Documents")))));
    }
    @Test public void rejectsAmbiguousDocumentAndUnknownRootShape() {
        assertEquals("", ZinstantRenderedText.read(new AmbiguousTree()));
        assertEquals("", ZinstantRenderedText.read(new Object()));
        assertEquals("", ZinstantRenderedText.read(null));
    }
    @Test public void stopsCyclesWithoutRepeatingText() {
        ZOMContainer node = new ZOMContainer(); node.mChildren = new ZOM[]{node, new ZOMText("Documents")};
        assertEquals(" Documents", ZinstantRenderedText.read(tree(node)));
    }
    @Test public void rejectsOversizeTextEvenAfterAnEarlierMarker() {
        assertEquals("", ZinstantRenderedText.read(tree(new ZOMContainer(
                new ZOMText("zStyle"), new ZOMText("x".repeat(4096))))));
    }
    @Test public void rejectsDeepAndLargeTrees() {
        ZOM node = new ZOMText("zStyle");
        for (int i = 0; i < 14; i++) node = new ZOMContainer(node);
        assertEquals("", ZinstantRenderedText.read(tree(node)));
        ZOM[] nodes = new ZOM[129]; java.util.Arrays.fill(nodes, new ZOMText("zStyle"));
        assertEquals("", ZinstantRenderedText.read(tree(new ZOMContainer(nodes))));
    }
    @Test public void leavesFailedReadsUnavailable() {
        assertEquals("", ZinstantRenderedText.read(tree(new ThrowingText())));
    }
}

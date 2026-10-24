package com.ez.zalopatch.xposed.features;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

/**
 * Responsive rules for the emoji grid. The regression widths are the real inner content
 * widths of the Material dialog after its own padding: 283px and 224px at density 1, where
 * a rounded column count used to produce 47px and 44px cells and break the 48dp touch
 * target.
 */
public final class ReactionEmojiGridTest {
    @Test public void keepsTheFullyDoubledTouchWidthAtTheRegressionWidths() {
        ReactionEmojiGrid.Metrics wide = ReactionEmojiGrid.measure(283, 1f);
        assertEquals(5, wide.columns);
        assertEquals(48, wide.cellWidthPx);
        assertEquals(48, wide.rowHeightPx);

        ReactionEmojiGrid.Metrics narrow = ReactionEmojiGrid.measure(224, 1f);
        assertEquals(4, narrow.columns);
        assertEquals(48, narrow.cellWidthPx);
        assertEquals(48, narrow.rowHeightPx);
    }

    @Test public void neverShrinksTheCellBelowTheTouchTargetWhileItStillFits() {
        for (int width = 48; width <= 720; width++) {
            ReactionEmojiGrid.Metrics metrics = ReactionEmojiGrid.measure(width, 1f);
            assertTrue("columns for " + width, metrics.columns >= 1);
            assertTrue("columns cap for " + width, metrics.columns <= ReactionEmojiGrid.MAX_COLUMNS);
            assertTrue("cell for " + width, metrics.cellWidthPx >= 48);
            assertTrue("cell fits for " + width,
                    metrics.columns * metrics.cellWidthPx <= width);
            assertTrue("row for " + width,
                    metrics.rowHeightPx >= metrics.cellWidthPx);
        }
    }

    @Test public void narrowWidthsMayDropBelowFourColumns() {
        ReactionEmojiGrid.Metrics three = ReactionEmojiGrid.measure(150, 1f);
        assertEquals(3, three.columns);
        assertEquals(48, three.cellWidthPx);

        ReactionEmojiGrid.Metrics two = ReactionEmojiGrid.measure(100, 1f);
        assertEquals(2, two.columns);

        ReactionEmojiGrid.Metrics one = ReactionEmojiGrid.measure(40, 1f);
        assertEquals(1, one.columns);
        assertEquals(40, one.cellWidthPx);
        assertEquals(48, one.rowHeightPx);
    }

    @Test public void capsColumnsOnWideScreensAndScalesWithDensity() {
        assertEquals(ReactionEmojiGrid.MAX_COLUMNS, ReactionEmojiGrid.measure(800, 1f).columns);
        assertEquals(8, ReactionEmojiGrid.measure(800, 2f).columns);
        assertEquals(48, ReactionEmojiGrid.measure(800, 1f).cellWidthPx);

        ReactionEmojiGrid.Metrics highDensity = ReactionEmojiGrid.measure(778, 2.75f);
        assertEquals(5, highDensity.columns);
        assertEquals(132, highDensity.cellWidthPx);
        assertEquals(132, highDensity.rowHeightPx);
    }

    @Test public void unmeasuredWidthFallsBackToTheIdealCell() {
        ReactionEmojiGrid.Metrics unmeasured = ReactionEmojiGrid.measure(0, 2f);
        assertEquals(1, unmeasured.columns);
        assertEquals(96, unmeasured.cellWidthPx);
        assertEquals(96, unmeasured.rowHeightPx);

        ReactionEmojiGrid.Metrics zeroDensity = ReactionEmojiGrid.measure(283, 0f);
        assertEquals(5, zeroDensity.columns);
        assertEquals(48, zeroDensity.cellWidthPx);
    }
}

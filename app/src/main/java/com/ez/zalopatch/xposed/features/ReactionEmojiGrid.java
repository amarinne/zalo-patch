package com.ez.zalopatch.xposed.features;

/**
 * Column and cell sizing for the emoji grid.
 *
 * <p>Pure arithmetic so the responsive rules stay JVM-testable: the cell width never
 * exceeds the Material 3 48dp ideal, the row height keeps the touch target at 48dp when
 * the available width allows it, and the grid stays inside the measured content width at
 * 320dp and 360dp phone widths.
 */
final class ReactionEmojiGrid {
    /** Material 3 ideal cell size, and the minimum touch target both use 48dp. */
    static final int IDEAL_CELL_DP = 48;
    static final int MIN_TOUCH_TARGET_DP = 48;
    /** A narrow width may drop below four columns; a wide one stops at eight. */
    static final int MIN_COLUMNS = 1;
    static final int MAX_COLUMNS = 8;

    static final class Metrics {
        final int columns;
        final int cellWidthPx;
        final int rowHeightPx;

        Metrics(int columns, int cellWidthPx, int rowHeightPx) {
            this.columns = columns;
            this.cellWidthPx = cellWidthPx;
            this.rowHeightPx = rowHeightPx;
        }
    }

    private ReactionEmojiGrid() {
    }

    /**
     * @param availableWidthPx measured content width available to the grid
     * @param density          display density; values below 1 fall back to 1
     */
    static Metrics measure(int availableWidthPx, float density) {
        float safeDensity = density > 0f ? density : 1f;
        int idealCellPx = Math.round(IDEAL_CELL_DP * safeDensity);
        int touchTargetPx = Math.round(MIN_TOUCH_TARGET_DP * safeDensity);
        if (idealCellPx <= 0) {
            return new Metrics(MIN_COLUMNS, 0, 0);
        }
        if (availableWidthPx <= 0) {
            return new Metrics(MIN_COLUMNS, idealCellPx, touchTargetPx);
        }
        // Floor, so every column keeps at least the 48dp touch width instead of shrinking
        // below it; the leftover space becomes even spacing between the columns.
        int columns = availableWidthPx / idealCellPx;
        columns = Math.max(MIN_COLUMNS, Math.min(MAX_COLUMNS, columns));
        int cellWidthPx = Math.min(idealCellPx, availableWidthPx / columns);
        return new Metrics(columns, cellWidthPx, Math.max(cellWidthPx, touchTargetPx));
    }
}

package com.ez.zalopatch;

import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;

/**
 * Minimal wrapping container for status chips. Chips must wrap to the next line instead of
 * stretching to full width, and no extra dependency is needed for that.
 */
public final class ZpFlowLayout extends ViewGroup {
    private final int horizontalGap;
    private final int verticalGap;

    public ZpFlowLayout(Context context) {
        this(context, null);
    }

    public ZpFlowLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
        int chipGap = context.getResources().getDimensionPixelSize(R.dimen.zp_chip_gap);
        horizontalGap = chipGap;
        verticalGap = chipGap;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int available = MeasureSpec.getSize(widthMeasureSpec) - getPaddingStart() - getPaddingEnd();
        int childSpec = MeasureSpec.makeMeasureSpec(Math.max(available, 0), MeasureSpec.AT_MOST);
        int lineWidth = 0;
        int lineHeight = 0;
        int totalHeight = 0;
        int maxWidth = 0;

        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) {
                continue;
            }
            child.measure(childSpec, MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (lineWidth > 0 && lineWidth + horizontalGap + childWidth > available) {
                maxWidth = Math.max(maxWidth, lineWidth);
                totalHeight += lineHeight + verticalGap;
                lineWidth = childWidth;
                lineHeight = childHeight;
            } else {
                lineWidth = lineWidth == 0 ? childWidth : lineWidth + horizontalGap + childWidth;
                lineHeight = Math.max(lineHeight, childHeight);
            }
        }
        maxWidth = Math.max(maxWidth, lineWidth);
        totalHeight += lineHeight;

        setMeasuredDimension(
                resolveSize(maxWidth + getPaddingStart() + getPaddingEnd(), widthMeasureSpec),
                resolveSize(totalHeight + getPaddingTop() + getPaddingBottom(), heightMeasureSpec));
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        int available = right - left - getPaddingStart() - getPaddingEnd();
        int x = getPaddingStart();
        int y = getPaddingTop();
        int lineHeight = 0;

        for (int index = 0; index < getChildCount(); index++) {
            View child = getChildAt(index);
            if (child.getVisibility() == GONE) {
                continue;
            }
            int childWidth = child.getMeasuredWidth();
            int childHeight = child.getMeasuredHeight();
            if (x > getPaddingStart() && x - getPaddingStart() + childWidth > available) {
                x = getPaddingStart();
                y += lineHeight + verticalGap;
                lineHeight = 0;
            }
            child.layout(x, y, x + childWidth, y + childHeight);
            x += childWidth + horizontalGap;
            lineHeight = Math.max(lineHeight, childHeight);
        }
    }
}

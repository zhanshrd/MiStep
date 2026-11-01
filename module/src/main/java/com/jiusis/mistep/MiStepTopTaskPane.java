package com.jiusis.mistep;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/** Same real empty hint as the side cells; occupied tasks have no host overlay. */
final class MiStepTopTaskPane extends FrameLayout {
    private final View emptyHint;
    private boolean choosing=true;
    MiStepTopTaskPane(Context context,View hint) {
        super(context);setClipChildren(true);emptyHint=hint;
        addView(hint,new FrameLayout.LayoutParams(-1,-1));setVisibility(INVISIBLE);
    }
    void message(String text) {emptyHint.setContentDescription(text);}
    void occupied(boolean occupied) {
        choosing=!occupied;emptyHint.setVisibility(occupied?INVISIBLE:VISIBLE);
        if(!occupied)emptyHint.setContentDescription("空白窗口，点按添加主屏应用");
    }
    boolean choosing() {return choosing;}
}

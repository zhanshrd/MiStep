package com.jiusis.mistep;

public final class ControlCenterPagesTest {
    public static void main(String[] args) {
        ControlCenterPages pages=new ControlCenterPages(3);
        pages.begin(200,50,1,300,12,8,false);
        if(pages.move(40,50)!=472 || pages.finish(40,-500,200,false)!=2)throw new AssertionError("third page reachable");
        pages.begin(200,50,2,300,12,8,false);
        if(pages.move(0,50)!=624 || pages.finish(0,-500,200,false)!=2)throw new AssertionError("last page bounded");
        pages.begin(50,50,2,300,12,8,false);
        pages.move(230,50);
        if(pages.finish(230,500,200,false)!=1)throw new AssertionError("return to music");
        pages.begin(50,50,2,300,12,8,false);
        pages.move(60,200);
        if(pages.dragging || pages.finish(60,0,200,false)!=2)throw new AssertionError("vertical content untouched");
        pages.begin(200,50,1,300,12,8,false);pages.move(40,50);
        if(pages.finish(40,-500,200,true)!=1)throw new AssertionError("cancel stays on origin");
        System.out.println("ControlCenterPages: 5 checks passed");
    }
}

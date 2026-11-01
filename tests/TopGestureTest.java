package com.jiusis.mistep;

public final class TopGestureTest {
    static int checks;
    static void ok(boolean value) {checks++;if(!value)throw new AssertionError("check "+checks);}
    static TopTaskSwipe swipe=new TopTaskSwipe();
    static int e(int action,int count,float x,float y,float x1,float y1,long time) {
        return swipe.event(action,count,7,12,x,y,x1,y1,time,1080,622,2);
    }
    static void pair() {e(0,1,300,500,0,0,0);e(5,2,300,500,600,500,40);}
    public static void main(String[] args) {
        e(0,1,300,500,0,0,0);ok(e(2,1,300,100,0,0,100)==0);
        pair();ok(e(2,2,300,480,600,480,70)==0);
        ok(e(2,2,300,440,600,440,100)==TopTaskSwipe.ACCEPT);
        ok(e(2,2,300,100,600,100,200)==TopTaskSwipe.DRAG && swipe.displacement==-400);
        ok(e(2,2,300,300,600,300,300)==TopTaskSwipe.DRAG && swipe.displacement==-200);
        ok(e(6,2,300,300,600,300,310)==TopTaskSwipe.FINISH);
        ok(e(2,1,300,100,0,0,320)==0);
        pair();ok(e(2,2,300,560,600,560,100)==TopTaskSwipe.ACCEPT);
        ok(e(3,2,300,560,600,560,110)==TopTaskSwipe.CANCEL);
        pair();ok(e(2,2,240,500,660,500,100)==0);ok(!swipe.accepted());
        pair();ok(e(2,2,300,440,600,560,100)==0);ok(!swipe.accepted());
        pair();ok(e(2,2,360,440,660,440,100)==0);ok(!swipe.accepted());
        pair();ok(e(5,3,300,440,600,440,80)==0);ok(e(2,2,300,100,600,100,100)==0);
        pair();e(2,2,300,440,600,440,100);ok(e(5,3,300,430,600,430,120)==TopTaskSwipe.CANCEL);
        e(0,1,300,700,0,0,0);e(5,2,300,600,600,500,40);ok(e(2,2,300,100,600,0,100)==0);
        e(0,1,300,500,0,0,0);e(5,2,300,500,600,700,40);ok(e(2,2,300,100,600,300,100)==0);
        e(0,1,300,500,0,0,0);e(5,2,300,500,600,500,400);ok(e(2,2,300,100,600,100,450)==0);
        pair();ok(e(2,2,300,440,600,440,750)==0);
        pair();e(2,2,300,440,600,440,100);
        ok(swipe.event(2,2,12,7,600,300,300,300,130,1080,622,2)==TopTaskSwipe.DRAG);
        ok(swipe.displacement==-200);
        TopRollMotion m=new TopRollMotion();m.reset(false);m.begin(600);m.move(-420,100);
        ok(Math.abs(m.phase-.7f)<.001 && m.destination(false)==1);
        // One face per gesture: a long drag clamps to the adjacent face and never shows a third.
        m.move(-1500,300);ok(m.phase==1f && m.destination(false)==1);
        m.move(600,500);ok(m.phase==-1 && m.destination(false)==-1);
        ok(m.destination(true)==0);
        m.reset(false);m.begin(600);m.move(-3000,100);m.move(-3600,110);
        ok(m.phase==1f && m.destination(false)==1);
        m.reset(true);m.begin(600);m.move(-600,100);ok(m.phase==2 && !TopRollMotion.taskFace(m.destination(false)));
        m.reset(false);m.begin(600);m.move(600,100);ok(m.phase==-1 && TopRollMotion.taskFace(m.destination(false)));
        m.phase=.52f;m.begin(600);m.move(-120,100);ok(Math.abs(m.phase-.72f)<.001);
        ok(TopRollMotion.shouldAnimateBackToMain(false));
        ok(!TopRollMotion.shouldAnimateBackToMain(true));
        for(int n=-100;n<=100;n++)ok(TopRollMotion.taskFace(n)!=TopRollMotion.taskFace(n+1));
        System.out.println("TopGesture: "+checks+" checks passed");
    }
}

package com.jiusis.mistep;

/** Passive two-finger recognition. Only an accepted parallel vertical drag may claim input. */
final class TopTaskSwipe {
    static final int NONE=0, ACCEPT=1, DRAG=2, FINISH=3, CANCEL=4;
    private int first=-1, second=-1;
    private float firstX,firstY,x0,y0,x1,y1,threshold;
    private long downTime,pairTime;
    private boolean eligible,paired,accepted,blocked;
    float displacement;

    void reset() { first=second=-1; eligible=paired=accepted=blocked=false; displacement=0; }
    boolean accepted() { return accepted; }
    private boolean inside(float x,float y,int width,int height) {
        return x>=0 && x<width && y>=0 && y<height;
    }
    int event(int action,int count,int id0,int id1,float a,float b,float c,float d,
              long time,int width,int height,float density) {
        if(action==0) {
            reset(); first=id0;firstX=a;firstY=b;downTime=time;
            threshold=Math.max(1,18*Math.max(.1f,density));
            eligible=count==1 && inside(a,b,width,height); return NONE;
        }
        if(action==3 || count>2) {
            boolean was=accepted;reset();blocked=true;return was?CANCEL:NONE;
        }
        if(action==1 || action==6) {
            boolean was=accepted;accepted=paired=eligible=false;blocked=true;
            return was?FINISH:NONE;
        }
        if(blocked || !eligible)return NONE;
        if(action==5) {
            if(count!=2 || paired || time-downTime>350 || id0!=first
                    || !inside(a,b,width,height) || !inside(c,d,width,height)
                    || Math.hypot(a-firstX,b-firstY)>threshold) {
                eligible=false;return NONE;
            }
            second=id1;x0=a;y0=b;x1=c;y1=d;pairTime=time;paired=true;return NONE;
        }
        if(action!=2)return NONE;
        if(!paired) {
            if(Math.hypot(a-firstX,b-firstY)>threshold)eligible=false;
            return NONE;
        }
        if(count!=2) {boolean was=accepted;reset();blocked=true;return was?CANCEL:NONE;}
        if(id0==second && id1==first) {float x=a,y=b;a=c;b=d;c=x;d=y;}
        else if(id0!=first || id1!=second) {boolean was=accepted;reset();blocked=true;return was?CANCEL:NONE;}
        float dx0=a-x0,dy0=b-y0,dx1=c-x1,dy1=d-y1;
        displacement=(dy0+dy1)/2;
        if(accepted)return DRAG;
        if(time-pairTime>650 || Math.hypot(dx0-dx1,dy0-dy1)>threshold*1.5f) {
            eligible=false;return NONE;
        }
        if(dy0*dy1>0 && Math.min(Math.abs(dy0),Math.abs(dy1))>=threshold
                && Math.abs(displacement)>Math.max(Math.abs(dx0),Math.abs(dx1))*1.5f) {
            accepted=true;return ACCEPT;
        }
        return NONE;
    }
}

package com.jiusis.mistep;

public final class TopTaskStateTest {
    static int checks;
    static void ok(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    public static void main(String[] args) {
        TopTaskState s = new TopTaskState();
        TopTaskState.Key a = new TopTaskState.Key(7,0,"a");
        TopTaskState.Key clone = new TopTaskState.Key(7,999,"a");
        TopTaskState.Key b = new TopTaskState.Key(8,0,"b");
        ok(!a.equals(clone));
        ok(!s.mayAutoRequest(a));
        ok(s.request(a,1,false,true,false,true)==null);
        ok(s.request(a,1,true,false,false,true)==null);
        ok(s.request(a,1,true,true,false,false)==null);
        ok(s.request(a,1,true,true,false,true)==null);
        TopTaskState.Ticket first=s.request(a,1,true,true,true,true);
        ok(first!=null);
        ok(!s.mayAutoRequest(b));
        ok(s.request(b,1,true,true,true,true)==null);
        ok(!s.commit(first,2));
        ok(s.commit(first,1));
        ok(s.owner().equals(a));
        ok(s.request(b,1,true,true,false,true)==null);
        ok(s.request(b,1,true,true,true,true)==null);
        s.release(clone,true); ok(s.owner().equals(a));
        s.release(a,true); ok(s.owner()==null);
        ok(!s.mayAutoRequest(a) && !s.mayAutoRequest(b));
        ok(s.request(a,1,true,true,false,true)==null);
        TopTaskState.Ticket manual=s.request(a,1,true,true,true,false);
        ok(manual!=null); s.cancel(manual); ok(!s.commit(manual,1));
        s.observe(a,false);
        ok(!s.mayAutoRequest(a));
        ok(s.request(a,2,true,true,false,true)==null);
        TopTaskState.Ticket next=s.request(a,2,true,true,true,true);
        ok(next!=null); s.sceneChanged(); ok(!s.commit(next,2));
        TopTaskState.Ticket newer=s.request(b,3,true,true,true,true);
        s.cancel(next); ok(s.current(newer,3)); ok(s.commit(newer,3));
        s.release(b,false);
        ok(s.request(b,4,true,true,false,true)==null);
        ok(s.request(b,4,true,false,true,true)!=null);
        TopTaskGeometry g=new TopTaskGeometry(1080,2460,622,39,2.75f);
        float previousFit=1080f/2460;
        float priorScale=(float)Math.sqrt(previousFit);
        ok(Math.abs(g.scale*2.75f-priorScale*242f/160f)<.00001f);
        ok(g.logicalWidth==Math.round(1080/g.scale) && g.logicalHeight==Math.round(622/g.scale));
        ok(g.left==0 && g.right==1080 && g.top==0 && g.bottom==622);
        ok(g.densityDpi==440 && TopTaskGeometry.DISPLAY_DPI==242);
        ok(Math.abs(g.logicalWidth/2.75f-(1080/priorScale)/(242f/160f))<1f);
        ok(Math.abs(g.logicalHeight/2.75f-(622/priorScale)/(242f/160f))<1f);
        TopTaskGeometry resized=new TopTaskGeometry(1080,2460,541,122,2.75f);
        ok(resized.bottom==541 && Math.abs(resized.logicalHeight*resized.scale-541)<.5f);
        for (int[] display:new int[][]{{1080,2340},{1440,3200},{720,1600}}) {
            MiStepLayout layout=MiStepLayout.of(display[0],display[1]);
            TopTaskGeometry geometry=new TopTaskGeometry(display[0],display[1],layout.topStrip,30,2f);
            ok(geometry.right==display[0] && geometry.bottom==layout.topStrip && layout.fillsScreen());
            // A retained 48dp control and a newly inflated 48dp control use the same
            // native px size. Their projected size must match the selected 242 layout.
            float fit=Math.min(display[0]/(float)Math.max(display[0],display[1]),layout.topStrip/(float)Math.min(display[0],display[1]));
            ok(Math.abs(48*2f*geometry.scale-48*(242f/160f)*(float)Math.sqrt(fit))<.01f);
            ok(Math.abs(geometry.logicalWidth*geometry.scale-display[0])<.5f);
            ok(Math.abs(geometry.logicalHeight*geometry.scale-layout.topStrip)<.5f);
        }
        TopTaskState manualState=new TopTaskState();
        ok(manualState.commit(manualState.request(a,1,true,false,true,true),1));
        manualState.observe(a,false);ok(manualState.owner().equals(a));
        TopTaskGeometry doubled=new TopTaskGeometry(2160,4920,1244,0,2.75f);
        ok(doubled.densityDpi==g.densityDpi);
        TopTaskGeometry shallow=new TopTaskGeometry(1080,2460,200,0,2.75f);
        ok(shallow.densityDpi==440 && Math.abs(shallow.logicalHeight*shallow.scale-200)<.5f);
        ok(shallow.scale<g.scale && shallow.densityDpi==g.densityDpi);
        TopTaskGeometry full=new TopTaskGeometry(1080,2460,2460,0,2.75f);
        ok(full.scale>0 && full.scale<=1f);
        TopTaskGeometry tiny=new TopTaskGeometry(1,1,1,0,2.75f);
        ok(tiny.scale>0f && tiny.logicalWidth>=1 && tiny.logicalHeight>=1 && tiny.densityDpi==440);
        ok(Math.abs(g.logicalWidth*g.scale-g.right)<.5f && Math.abs(g.logicalHeight*g.scale-g.bottom)<.5f);
        System.out.println("TopTaskState: " + checks + " checks passed");
    }
}

"""Exercise the actual shade surface-region crop against the captured modal overreach."""
from pathlib import Path
import os,subprocess,sys
root=Path(__file__).resolve().parents[2];out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
 'android/graphics/Rect.java':'''package android.graphics;
public class Rect {public int left,top,right,bottom;public Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}public int width(){return right-left;}public int height(){return bottom-top;}public boolean isEmpty(){return width()<=0||height()<=0;}}''',
 'android/graphics/Region.java':'''package android.graphics;
public class Region {public enum Op{INTERSECT,DIFFERENCE}final java.awt.geom.Area a;public Region(Rect r){a=new java.awt.geom.Area(new java.awt.Rectangle(r.left,r.top,r.width(),r.height()));}public boolean op(Rect r,Op op){var b=new java.awt.geom.Area(new java.awt.Rectangle(r.left,r.top,r.width(),r.height()));if(op==Op.INTERSECT)a.intersect(b);else a.subtract(b);return !a.isEmpty();}public boolean contains(int x,int y){return a.contains(x,y);}}''',
 'android/view/WindowManager.java':'''package android.view;public interface WindowManager {public static class LayoutParams{public int type=2040;public String packageName="com.android.systemui";public CharSequence title="NotificationShade";public CharSequence getTitle(){return title;}}}''',
 'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers{public static Object getObjectField(Object o,String n){try{var f=o.getClass().getField(n);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}public static float getFloatField(Object o,String n){return ((Number)getObjectField(o,n)).floatValue();}public static Object callMethod(Object o,String n,Object...a){try{return o.getClass().getMethod(n).invoke(o);}catch(Exception e){throw new RuntimeException(e);}}}''',
 'MainPanelTouchRegression.java':'''package com.jiusis.mistep;
import android.graphics.*;import android.view.WindowManager;
public class MainPanelTouchRegression {
 static int checks;static final Object display=new Object();
 public static class Frames{public Rect mFrame=new Rect(0,0,1200,2608);}
 public static class Window{public Object mActivityRecord,dc=display;public float mInvGlobalScale=1;public Frames mWindowFrames=new Frames();public Object getDisplayContent(){return dc;}}
 static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static Region modal(){return new Region(new Rect(-1200,-2608,2400,5216));}
 static void rejects(Window w,WindowManager.LayoutParams p,String why){Region r=modal();check(!MiStepMainPanelTouchRegion.crop(w,display,r,p),why);check(r.contains(-1,-1)&&r.contains(1600,3000),why+" leaves region");}
 public static void main(String[]args){
  Window w=new Window();var p=new WindowManager.LayoutParams();Region r=modal();check(MiStepMainPanelTouchRegion.crop(w,display,r,p),"shade admitted");
  check(!r.contains(-1,-1)&&!r.contains(1200,2607)&&!r.contains(1199,2608),"modal extension removed");check(r.contains(0,0)&&r.contains(1199,2607),"entire panel preserved");
  // Captured scale .74654907, offset 661: physical TOP y300 maps before local y0.
  check(!r.contains((int)(450/.74654907),(int)((300-661)/.74654907)),"recorded upper pinch bypasses shade");
  check(r.contains((int)(450/.74654907),(int)((1000-661)/.74654907)),"main panel still receives touch");
  r=modal();r.op(new Rect(300,400,500,700),Region.Op.DIFFERENCE);MiStepMainPanelTouchRegion.crop(w,display,r,p);check(!r.contains(350,500)&&r.contains(600,500),"client holes preserved");
  w.mWindowFrames.mFrame=new Rect(17,23,2625,1223);r=new Region(new Rect(-2608,-1200,5216,2400));MiStepMainPanelTouchRegion.crop(w,display,r,p);check(r.contains(2607,1199)&&!r.contains(2608,1199)&&!r.contains(1,1200),"rotated frame uses surface origin");
  w=new Window();w.dc=new Object();rejects(w,p,"other display");w=new Window();w.mActivityRecord=new Object();rejects(w,p,"activity window");
  for(float x:new float[]{.5f,2,Float.NaN,Float.POSITIVE_INFINITY}){w=new Window();w.mInvGlobalScale=x;rejects(w,p,"size compat "+x);}
  w=new Window();p.packageName="third.party";rejects(w,p,"package");p.packageName="com.android.systemui";
  for(int type:new int[]{2000,2011,2017,2008,2020,2}){p.type=type;rejects(w,p,"other type "+type);}p.type=2040;
  for(String title:new String[]{"ControlCenter","StatusBar","MiStepHost",""}){p.title=title;rejects(w,p,"other title "+title);}p.title="NotificationShade";
  w.mWindowFrames.mFrame=new Rect(0,0,0,2608);rejects(w,p,"empty frame");
  check(!MiStepMainPanelTouchRegion.crop(null,display,modal(),p),"null window");check(!MiStepMainPanelTouchRegion.crop(w,null,modal(),p),"null display");check(!MiStepMainPanelTouchRegion.crop(w,display,null,p),"null region");check(!MiStepMainPanelTouchRegion.crop(w,display,modal(),null),"null attrs");
  System.out.println("Actual main shade touch-region checks="+checks+" passed");
 }
}'''}
for n,s in files.items():
 f=out/n;f.parent.mkdir(parents=True,exist_ok=True);f.write_text(s,encoding='utf-8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(root/'module/src/main/java/com/jiusis/mistep/MiStepMainPanelTouchRegion.java'),*[str(out/n) for n in files]],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.MainPanelTouchRegression'],check=True)

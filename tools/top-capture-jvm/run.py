"""Replay production composed capture: crop, secure rejection, and buffer ownership."""
from pathlib import Path
import os,subprocess,sys
root=Path(__file__).resolve().parents[2]
source=(root/'module/src/main/java/com/jiusis/mistep/SwapPreview.java').read_text(encoding='utf-8')
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
def member(signature):
    start=source.index(signature);end=source.index('{',start)+1;depth=1
    while depth:
        depth+=(source[end]=='{')-(source[end]=='}');end+=1
    return source[start:end]
files={
'android/graphics/Rect.java':'''package android.graphics;public class Rect {
 public int left,top,right,bottom;public Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}
 public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}public int width(){return right-left;}public int height(){return bottom-top;}}''',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap {
 public enum Config {ARGB_8888}public int w,h;public boolean recycled;public static Bitmap copied;
 public Bitmap(int w,int h){this.w=w;this.h=h;}public int getWidth(){return w;}public int getHeight(){return h;}
 public Bitmap copy(Config c,boolean mutable){copied=new Bitmap(w,h);return copied;}public void recycle(){recycled=true;}}''',
'android/view/SurfaceControl.java':'''package android.view;public class SurfaceControl {}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;public class XposedHelpers {
 public static Class<?>findClass(String n,ClassLoader cl){return n.endsWith("SurfaceControl")?android.view.SurfaceControl.class:n.endsWith("$Builder")?com.jiusis.mistep.TopCaptureRegression.Builder.class:n.endsWith("WindowManagerGlobal")?com.jiusis.mistep.TopCaptureRegression.Global.class:com.jiusis.mistep.TopCaptureRegression.Capture.class;}
 public static Object newInstance(Class<?>c,Object...a){try{return c.getConstructor().newInstance();}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...a){return call(o.getClass(),o,n,a);}
 public static Object callStaticMethod(Class<?>c,String n,Object...a){return call(c,null,n,a);}
 static Object call(Class<?>c,Object o,String n,Object[]a){try{for(var m:c.getMethods())if(m.getName().equals(n)&&m.getParameterCount()==a.length)return m.invoke(o,a);throw new NoSuchMethodException(n);}catch(Exception e){throw new RuntimeException(e);}}}''',
'com/jiusis/mistep/TopCaptureRegression.java':'''package com.jiusis.mistep;
import android.graphics.*;import de.robv.android.xposed.XposedHelpers;
public class TopCaptureRegression {
 static int checks;static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 public static class Builder {static Builder last;Rect crop;float scale;boolean overlays;Object excluded;public Builder(){last=this;}
  public Builder setFrameScale(float v){scale=v;return this;}public Builder setIncludeSystemOverlays(boolean v){overlays=v;return this;}
  public Builder setSourceCrop(Rect r){crop=r;return this;}public Builder build(){return this;}
  public Builder setExcludeLayers(android.view.SurfaceControl[] layers){excluded=layers;return this;}}
 public static class Hardware {int closes;public void close(){closes++;}}
 public static class Shot {Hardware hardware=new Hardware();boolean secure;Bitmap bitmap;
  public Object getHardwareBuffer(){return hardware;}public boolean containsSecureLayers(){return secure;}public Bitmap asBitmap(){return bitmap;}}
 public static class Listener {public Object getBuffer(){return shot;}}
 public static class Capture {public static Listener createSyncCaptureListener(){return new Listener();}}
 public static class Global {public static Global getWindowManagerService(){return new Global();}
  public void captureDisplay(int display,Builder b,Listener l){check(display==0,"default display only");}}
 static Shot shot;static void prepare(int w,int h){shot=new Shot();shot.bitmap=new Bitmap(w,h);}
'''+member('    static Bitmap capture(')+member('    static Bitmap captureRegion(')+member('    static Bitmap captureRegion(ClassLoader cl, int width, int height, Rect crop, Object exclude)')+'''
 public static void main(String[]args)throws Throwable {
  prepare(1080,622);Rect crop=new Rect(0,0,1080,622);Bitmap result=captureRegion(TopCaptureRegression.class.getClassLoader(),1080,2460,crop);
  check(result.getHeight()==622&&result.getWidth()==1080,"only available top content copied");
  check(Builder.last.crop!=crop&&Builder.last.crop.bottom==622&&Builder.last.scale==1&&Builder.last.overlays,"crop copied, no scaling, composed children included");
  check(shot.hardware.closes==1&&shot.bitmap.recycled&&!result.recycled,"hardware/source released, software output retained");
  prepare(1080,2460);result=capture(TopCaptureRegression.class.getClassLoader(),1080,2460);
  check(Builder.last.crop==null&&result.getHeight()==2460&&shot.hardware.closes==1,"existing whole-display contract preserved");
  prepare(1080,622);var excluded=new android.view.SurfaceControl();
  result=captureRegion(TopCaptureRegression.class.getClassLoader(),1080,2460,crop,excluded);
  var layers=(android.view.SurfaceControl[])Builder.last.excluded;
  check(layers.length==1&&layers[0]==excluded&&shot.hardware.closes==1,"only exact transition layer excluded; task layers retained");
  prepare(1080,622);shot.secure=true;boolean rejected=false;
  try{captureRegion(TopCaptureRegression.class.getClassLoader(),1080,2460,crop);}catch(IllegalStateException e){rejected=true;}
  check(rejected&&shot.hardware.closes==1,"secure scene rejected and buffer closed");
  prepare(1080,2460);rejected=false;
  try{captureRegion(TopCaptureRegression.class.getClassLoader(),1080,2460,crop);}catch(IllegalStateException e){rejected=true;}
  check(rejected&&shot.hardware.closes==1&&Bitmap.copied.recycled,"unexpected geometry rejected and copied buffer recycled");
  shot=null;rejected=false;
  try{captureRegion(TopCaptureRegression.class.getClassLoader(),1080,2460,crop);}catch(IllegalStateException e){rejected=true;}
  check(rejected,"missing capture rejected");
  System.out.println("Production top composed capture checks="+checks+" passed (native composition needs device)");
 }
}'''}
paths=[]
for name,text in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(text,encoding='utf-8');paths.append(str(path))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TopCaptureRegression'],check=True)

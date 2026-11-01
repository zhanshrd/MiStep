"""Run actual zoom publication around synchronous orientation callbacks and failure."""
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = Path(sys.argv[2]) if len(sys.argv)>2 else root/'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
source = (src/'HostEntry.java').read_text(encoding='utf8')
start = source.index('    private void applyDisplayZoomLocked(ZoomTarget target, float scale,\n            float offsetY, float offsetX, ZoomCommit commit)')
pos = source.index('{', start)+1; depth = 1
while depth:
    depth += (source[pos]=='{')-(source[pos]=='}'); pos += 1
actual = source[start:pos]
body = r'''package com.jiusis.mistep;
public class ZoomEntryRegression {
 static boolean sZoomed,sFullscreenRotationActive,sZoomFrameApply;static int sNativeLandscapeState;static long topWorkspaceGeneration;static float sZoomScale=1,sZoomOffsetX,sZoomOffset;
 static ZoomTarget sZoomTarget;static Object sDisplayContent;static ZoomCommit sLiveZoomCommit;static int callbacks,nativeSuspensions,refreshes;static boolean failRotation;
 static class Looper {static final Object MAIN=new Object();static Object myLooper(){return MAIN;}}
 static class Handler {Object getLooper(){return Looper.MAIN;}void post(Runnable r){r.run();}}
 static class ZoomTarget {Handler handler=new Handler();Object globalLock=new Object();Display display=new Display();}
 static class Spec {float scale,offsetX,offsetY;}
 static class Rect {int width(){return 1080;}int height(){return 2460;}}
 static class Display {Spec mMagnificationSpec;boolean fail;public void applyMagnificationSpec(Spec s){if(fail)throw new IllegalStateException("apply failed");mMagnificationSpec=s;}public Object getPendingTransaction(){return new Object();}public Rect getBounds(){return new Rect();}public int getRotation(){return 0;}public Object getWindowingLayer(){return null;}}
 static class Zoom {boolean sameTuple(float a,float b,float c){return true;}}
 static class ZoomCommit {long transactionId;float actualScale,actualX,actualY;boolean applied;Zoom zoom=new Zoom();void finish(boolean success,String reason){}}
 static class MiStepWallpaperScale {static void refresh(Object dc){refreshes++;}}
 static class MiStepRecentsInput {static void geometryChanged(Object tx,float s,float y,float x){}}
 static class NativeRawInputBridge {static void update(float s,float y,float x,int w,int h,int r){}}
 static class XposedHelpers {
  static Class<?> findClass(String n,ClassLoader cl){return Spec.class;}
  static void setFloatField(Object o,String n,float v){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);f.setFloat(o,v);}catch(Exception e){throw new RuntimeException(e);}}
  static float getFloatField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.getFloat(o);}catch(Exception e){throw new RuntimeException(e);}}
  static Object getObjectField(Object o,String n){try{var f=o.getClass().getDeclaredField(n);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
  static Object callMethod(Object o,String n,Object...args){for(var m:o.getClass().getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)try{m.setAccessible(true);return m.invoke(o,args);}catch(java.lang.reflect.InvocationTargetException e){throw new RuntimeException(e.getCause());}catch(Exception e){throw new RuntimeException(e);}throw new IllegalStateException(n);}
 }
 void beginFixedMainRotationLocked(Object dc){sFullscreenRotationActive=true;callbacks++;if(!sZoomed)nativeSuspensions++;if(failRotation)throw new IllegalStateException("orientation failure");}
 void endFixedMainRotationLocked(Object dc){sFullscreenRotationActive=false;}
 void placeHostBelowNativeFreeforms(Object d,Object tx){}void restoreExitedMagnificationLocked(ZoomTarget t){}void applyGestureSurfacesLocked(ZoomTarget t,float s,float y,float x){}void log(String s){}
 ACTUAL
 static int checks;static void check(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
 static ZoomTarget reset(){sZoomed=false;sFullscreenRotationActive=false;sNativeLandscapeState=0;topWorkspaceGeneration=0;sZoomScale=1;sZoomOffsetX=sZoomOffset=0;callbacks=nativeSuspensions=refreshes=0;failRotation=false;sZoomTarget=new ZoomTarget();sDisplayContent=sZoomTarget.display;return sZoomTarget;}
 static void apply(ZoomTarget t,float s)throws Throwable{synchronized(t.globalLock){new ZoomEntryRegression().applyDisplayZoomLocked(t,s,s==1?0:622,0,null);}}
 public static void main(String[] args)throws Throwable{
  var t=reset();apply(t,.747f);check(callbacks==1&&nativeSuspensions==0,"entry orientation callback sees incoming workspace instead of native fullscreen");check(sZoomed&&sFullscreenRotationActive&&topWorkspaceGeneration==1,"entry published once");check(t.display.mMagnificationSpec.scale==.747f&&sZoomScale==.747f,"applied state retained");
  apply(t,.747f);check(topWorkspaceGeneration==1&&nativeSuspensions==0,"animation frame keeps same workspace generation");apply(t,1);check(!sZoomed&&!sFullscreenRotationActive&&sZoomScale==1&&refreshes==2,"exit restores normal display state");
  t=reset();t.display.fail=true;try{apply(t,.747f);throw new AssertionError("failure swallowed");}catch(RuntimeException expected){}check(!sZoomed&&!sFullscreenRotationActive&&sNativeLandscapeState==0&&sZoomScale==1,"failed magnification rolls back publication and rotation");
  t=reset();failRotation=true;try{apply(t,.747f);throw new AssertionError("orientation failure swallowed");}catch(RuntimeException expected){}check(!sZoomed&&!sFullscreenRotationActive&&sNativeLandscapeState==0,"synchronous orientation failure rolls back too");
  t=reset();sFullscreenRotationActive=true;t.display.fail=true;try{apply(t,.747f);throw new AssertionError("failure swallowed");}catch(RuntimeException expected){}check(!sZoomed&&sFullscreenRotationActive,"existing rotation ownership survives apply failure");
  t=reset();var other=new ZoomTarget();apply(other,.747f);check(!sZoomed&&callbacks==0,"stale display target has no effects");
  t=reset();try{new ZoomEntryRegression().applyDisplayZoomLocked(t,.747f,622,0,null);throw new AssertionError("missing lock accepted");}catch(IllegalStateException expected){}check(!sZoomed&&callbacks==0,"WM lock remains required");
  System.out.println("actual fullscreen zoom entry checks="+checks+" passed");
 }
}'''.replace('ACTUAL', actual)
target=out/'ZoomEntryRegression.java';target.write_text(body,encoding='utf8')
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),str(target),str(src/'NativeLandscapePolicy.java')],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.ZoomEntryRegression'],check=True)

"""Run the actual center bridge with controllable draw, animation and native callbacks."""
from pathlib import Path
import os, subprocess, sys
root=Path(__file__).resolve().parents[2]
src=root/'module/src/main/java/com/jiusis/mistep'
out=Path(sys.argv[1]);out.mkdir(parents=True,exist_ok=True)
files={
'android/content/Context.java':'''package android.content;public class Context {
 public static final String WINDOW_SERVICE="window";public final android.view.WindowManager windows=new android.view.WindowManager();
 public Object getSystemService(String name){return windows;}public ClassLoader getClassLoader(){return getClass().getClassLoader();}}''',
'android/graphics/Rect.java':'''package android.graphics;public class Rect {
 public int left,top,right,bottom;public Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}
 public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}public int width(){return right-left;}public int height(){return bottom-top;}
 public float exactCenterX(){return (left+right)*.5f;}public float exactCenterY(){return (top+bottom)*.5f;}}''',
'android/graphics/Bitmap.java':'''package android.graphics;public class Bitmap {public enum Config {ARGB_8888}public boolean isRecycled(){return false;}public Bitmap copy(Config c,boolean mutable){return new Bitmap();}}''',
'android/graphics/Paint.java':'''package android.graphics;public class Paint {public static final int ANTI_ALIAS_FLAG=1,FILTER_BITMAP_FLAG=2;public Paint(int f){}}''',
'android/graphics/PixelFormat.java':'''package android.graphics;public class PixelFormat {public static final int TRANSLUCENT=-3;}''',
'android/graphics/Canvas.java':'''package android.graphics;public class Canvas {
 public static int colorDraws,bitmapDraws;public float scale,pivotX,pivotY;public Rect image;
 public int save(){return 1;}public void clipRect(Rect r){}public void restoreToCount(int n){}public void drawColor(int c){colorDraws++;}
 public void scale(float x,float y,float px,float py){scale=x;pivotX=px;pivotY=py;}
 public void drawBitmap(Bitmap b,Object from,Rect r,Paint p){bitmapDraws++;image=r;}}''',
'android/view/SurfaceControl.java':'''package android.view;import android.graphics.Rect;public class SurfaceControl {
 public static class Transaction {
  public static Transaction last;public float sx,sy,x,y;public Rect crop;public int applies,closes;
  public Transaction(){last=this;}
  public Transaction setPosition(Object s,float px,float py){x=px;y=py;return this;}
  public Transaction setMatrix(Object s,float dsdx,float dtdx,float dsdy,float dtdy){sx=dsdx;sy=dtdy;return this;}
  public Transaction setWindowCrop(Object s,Rect r){crop=r;return this;}
  public void apply(){applies++;}public void close(){closes++;}
 }}''',
'android/view/View.java':'''package android.view;import java.util.*;import android.content.Context;import android.graphics.Canvas;
public class View {
 public static final int GONE=8,IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS=4;
 public static final List<Runnable> frames=new ArrayList<>(),timers=new ArrayList<>();
 public interface OnAttachStateChangeListener {void onViewAttachedToWindow(View v);void onViewDetachedFromWindow(View v);}
 public interface Touch {boolean onTouch(View v,Object event);}
 private final Context ctx;public OnAttachStateChangeListener attachment;public int visibility;
 public View(Context c){ctx=c;}public Context getContext(){return ctx;}
 public void setOnTouchListener(Touch t){}public void setImportantForAccessibility(int x){}public void invalidate(){}
 public void setVisibility(int v){visibility=v;}protected void onDraw(Canvas c){}public void draw(Canvas c){onDraw(c);}
 public void postOnAnimation(Runnable r){frames.add(r);}public void postDelayed(Runnable r,long ms){timers.add(r);}
 public void removeCallbacks(Runnable r){timers.remove(r);}public void addOnAttachStateChangeListener(OnAttachStateChangeListener l){attachment=l;}
 public void removeOnAttachStateChangeListener(OnAttachStateChangeListener l){if(attachment==l)attachment=null;}
 public static void drain(){while(!frames.isEmpty())frames.remove(0).run();}
}''',
'android/widget/FrameLayout.java':'''package android.widget;public class FrameLayout extends android.view.View {public FrameLayout(android.content.Context c){super(c);}}''',
'android/view/Gravity.java':'''package android.view;public class Gravity {public static final int TOP=48,START=8388611;}''',
'android/view/WindowManager.java':'''package android.view;public class WindowManager {
 public View view;public LayoutParams params;public int adds,removes;
 public void addView(View v,LayoutParams p){view=v;params=p;adds++;}public void removeViewImmediate(View v){removes++;}
 public static class LayoutParams {
  public static final int TYPE_APPLICATION_OVERLAY=2038,FLAG_NOT_FOCUSABLE=8,FLAG_LAYOUT_IN_SCREEN=256,FLAG_LAYOUT_NO_LIMITS=512,LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS=3;
  public int gravity,windowAnimations,layoutInDisplayCutoutMode;public String title;
  public LayoutParams(int w,int h,int type,int flags,int format){}public void setFitInsetsTypes(int n){}public void setTitle(String s){title=s;}}
}''',
'android/animation/Animator.java':'''package android.animation;public class Animator {}''',
'android/animation/AnimatorListenerAdapter.java':'''package android.animation;public class AnimatorListenerAdapter {public void onAnimationEnd(Animator a){}}''',
'android/animation/ValueAnimator.java':'''package android.animation;import java.util.*;import java.util.function.Consumer;
public class ValueAnimator extends Animator {
 public static final List<ValueAnimator> all=new ArrayList<>();public float value,start,end;public long duration;
 Consumer<ValueAnimator> update;AnimatorListenerAdapter listener;public boolean cancelled;
 public static ValueAnimator ofFloat(float a,float b){ValueAnimator v=new ValueAnimator();v.start=a;v.end=b;all.add(v);return v;}
 public void setDuration(long d){duration=d;}public void setInterpolator(Object x){}
 public void addUpdateListener(Consumer<ValueAnimator> c){update=c;}public void addListener(AnimatorListenerAdapter l){listener=l;}
 public Object getAnimatedValue(){return value;}public void start(){step(0);}public void step(float f){value=start+(end-start)*f;update.accept(this);}
 public void end(){step(1);listener.onAnimationEnd(this);}public void cancel(){cancelled=true;listener.onAnimationEnd(this);}
}''',
'android/view/animation/AccelerateDecelerateInterpolator.java':'''package android.view.animation;public class AccelerateDecelerateInterpolator {}''',
'de/robv/android/xposed/XposedHelpers.java':'''package de.robv.android.xposed;import java.lang.reflect.*;public class XposedHelpers {
 public static final Object surface=new Object();public static Object callMethod(Object o,String n,Object...args){
  if(n.equals("isValid"))return true;
  for(Method m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)
   try{return m.invoke(o,args);}catch(Throwable ignored){}
  return surface;}}''',
'com/jiusis/mistep/HostWindowPolicy.java':'''package com.jiusis.mistep;class HostWindowPolicy {static void configure(Object p){}}''',
'com/jiusis/mistep/HyperOsStyle.java':'''package com.jiusis.mistep;class HyperOsStyle {static int page(Object c){return 0xff101010;}}''',
'com/jiusis/mistep/SwapPreview.java':'''package com.jiusis.mistep;import android.graphics.*;class SwapPreview {
 static int captures;static Object exclusion;static boolean fail;
 static Bitmap captureRegion(ClassLoader c,int w,int h,Rect r)throws Throwable{return captureRegion(c,w,h,r,null);}
 static Bitmap captureRegion(ClassLoader c,int w,int h,Rect r,Object exclude)throws Throwable {
  captures++;exclusion=exclude;if(fail)throw new IllegalStateException("secure/unavailable");return new Bitmap();}}
''',
'com/jiusis/mistep/TopTransitionRegression.java':'''package com.jiusis.mistep;
import android.animation.*;import android.graphics.*;import android.view.*;import android.widget.*;import java.util.concurrent.atomic.AtomicBoolean;
public class TopTransitionRegression {
 static int checks,handoffs,finishes,restores;static AtomicBoolean valid;static FrameLayout host;static android.content.Context ctx;
 static Rect upper=new Rect(0,0,1080,622),main=new Rect(273,622,1080,2460);
 static void check(boolean v,String s){checks++;if(!v)throw new AssertionError(s);}
 static void reset(){ValueAnimator.all.clear();View.frames.clear();View.timers.clear();SwapPreview.fail=false;SwapPreview.captures=0;
  handoffs=finishes=restores=0;Canvas.colorDraws=Canvas.bitmapDraws=0;android.view.SurfaceControl.Transaction.last=null;
  valid=new AtomicBoolean(true);ctx=new android.content.Context();host=new FrameLayout(ctx);}
 static MiStepTopTaskTransition make(boolean intoTop,Runnable transfer)throws Throwable {
  return new MiStepTopTaskTransition(host,1080,2460,intoTop?main:upper,intoTop?upper:main,new Bitmap(),null,valid::get,
   transfer,v->finishes++,s->{});}
 static ValueAnimator shrink(MiStepTopTaskTransition run){run.start();check(ValueAnimator.all.isEmpty(),"no animation before opaque first draw");
  ctx.windows.view.draw(new Canvas());View.drain();check(ValueAnimator.all.size()==1,"one shrink animator");return ValueAnimator.all.get(0);}
 public static void main(String[]args)throws Throwable {
  for(boolean intoTop:new boolean[]{true,false}) {
   reset();var run=make(intoTop,()->handoffs++);var shrink=shrink(run);
   check(handoffs==0,"task ownership unchanged during shrink");shrink.step(.5f);Canvas c=new Canvas();ctx.windows.view.draw(c);
   Rect from=intoTop?main:upper,to=intoTop?upper:main;
   check(c.scale==.5f&&c.pivotX==from.exactCenterX()&&c.pivotY==from.exactCenterY(),"source collapses at its own center in both directions");
   shrink.end();check(handoffs==1&&ValueAnimator.all.size()==1,"handoff exactly once, expansion waits for native callback");
   run.reveal();run.reveal();View.drain();check(SwapPreview.captures==2&&SwapPreview.exclusion==de.robv.android.xposed.XposedHelpers.surface,"destination captured once excluding only transition");
   check(ValueAnimator.all.size()==2,"one expansion despite duplicate completion");var expand=ValueAnimator.all.get(1);expand.step(.5f);
   c=new Canvas();ctx.windows.view.draw(c);check(c.scale==.5f&&c.pivotX==to.exactCenterX()&&c.pivotY==to.exactCenterY(),"destination expands at its own center");
   expand.end();run.close();run.reveal();View.drain();check(ctx.windows.removes==1&&finishes==1&&View.timers.isEmpty(),"one removal, no stale completion or timeout retained");
  }
  reset();var run=make(true,()->handoffs++);run.start();run.close();ctx.windows.view.draw(new Canvas());View.drain();
  check(handoffs==0&&ValueAnimator.all.isEmpty()&&finishes==1,"cancel before first draw never transfers a task");
  reset();run=make(true,()->handoffs++);var shrink=shrink(run);valid.set(false);shrink.step(.5f);shrink.end();
  check(handoffs==0&&ctx.windows.removes==1&&finishes==1,"scene loss mid-shrink cancels without a handoff");
  reset();run=make(true,()->handoffs++);shrink(run).end();View.timers.get(0).run();run.reveal();View.drain();
  check(handoffs==1&&finishes==1&&SwapPreview.captures==1,"timeout frees overlay and late native callback starts nothing");
  reset();run=make(false,()->{throw new IllegalStateException("native refusal");});shrink(run).end();
  check(ctx.windows.removes==1&&finishes==1,"handoff exception frees input overlay");
  reset();run=make(false,()->handoffs++);shrink(run).end();SwapPreview.fail=true;run.reveal();View.drain();
  check(finishes==1&&ctx.windows.removes==1&&ValueAnimator.all.size()==1,"failed destination capture reveals native content without a wrong snapshot");
  reset();run=make(true,()->handoffs++);run.start();host.attachment.onViewDetachedFromWindow(host);View.drain();
  check(finishes==1&&handoffs==0&&ctx.windows.removes==1,"Host detach retires visual bridge");
  reset();run=new MiStepTopTaskTransition(host,1080,2460,upper,main,new Bitmap(),new Bitmap(),valid::get,
   ()->handoffs++,v->finishes++,s->{});shrink(run).end();run.reveal();View.drain();
  check(SwapPreview.captures==1&&handoffs==1,"roll return uses its original task snapshot, not the restored control face");run.close();
  reset();run=new MiStepTopTaskTransition(host,1080,2460,main,main,null,null,true,valid::get,
   ()->handoffs++,v->finishes++,s->{});run.start();ctx.windows.view.draw(new Canvas());View.drain();
  check(handoffs==1&&SwapPreview.captures==0&&Canvas.colorDraws==0&&Canvas.bitmapDraws==0,
   "live center handoff draws neither wallpaper nor a bitmap snapshot");
  Object liveSurface=new Object();run.setLiveSurface(liveSurface);run.reveal();run.reveal();
  check(ValueAnimator.all.size()==1&&android.view.SurfaceControl.Transaction.last.sx==.14f,
   "live app starts at the center scale after native ownership is confirmed");
  var liveExpand=ValueAnimator.all.get(0);liveExpand.step(.5f);
  var tx=android.view.SurfaceControl.Transaction.last;float middle=.14f+.86f*.5f;
  check(Math.abs(tx.sx-middle)<.001f&&Math.abs(tx.x-1080f*(1f-middle)/2f)<.01f
   &&Math.abs(tx.y-2460f*(1f-middle)/2f)<.01f&&tx.applies==1,
   "live task leash expands from the display center through SurfaceControl");
  Canvas liveCanvas=new Canvas();ctx.windows.view.draw(liveCanvas);
  check(Canvas.colorDraws==0&&Canvas.bitmapDraws==0,"live app remains the only rendered image during expansion");
  liveExpand.end();tx=android.view.SurfaceControl.Transaction.last;
  check(tx.sx==1f&&tx.x==0f&&tx.y==0f&&ctx.windows.removes==1&&finishes==1,
   "completed live reveal restores identity transform and removes the touch blocker");
  reset();run=new MiStepTopTaskTransition(host,1080,2460,main,main,null,null,true,valid::get,
   ()->handoffs++,v->finishes++,s->{});run.start();ctx.windows.view.draw(new Canvas());View.drain();
  run.setLiveSurface(new Object());run.reveal();var cancelExpand=ValueAnimator.all.get(0);cancelExpand.step(.4f);run.close();
  check(android.view.SurfaceControl.Transaction.last.sx==1f&&ctx.windows.removes==1&&finishes==1,
   "cancelled live reveal restores the application surface before retiring the overlay");
  reset();run=new MiStepTopTaskTransition(host,1080,2460,main,main,null,null,true,valid::get,
   ()->handoffs++,v->finishes++,s->{});run.start();ctx.windows.view.draw(new Canvas());View.drain();run.reveal();
  check(ctx.windows.removes==1&&finishes==1&&ValueAnimator.all.isEmpty(),"missing live surface skips animation and releases the blocker");
  for(int reason=0;reason<4;reason++) {
   reset();run=new MiStepTopTaskTransition(host,1080,2460,main,main,null,null,true,valid::get,
    ()->handoffs++,v->finishes++,s->{});
   run.setNativeRestore(()->restores++);run.start();ctx.windows.view.draw(new Canvas());View.drain();
   if(reason==0) {run.setLiveSurface(new Object());run.reveal();ValueAnimator.all.get(0).end();}
   else if(reason==1) {run.setLiveSurface(new Object());run.reveal();ValueAnimator.all.get(0).step(.4f);run.close();}
   else if(reason==2) View.timers.get(0).run();
   else run.reveal();
   run.close();
   check(restores==1&&finishes==1,"native visibility policy restored exactly once on completion/cancel/timeout/no-surface "+reason);
   check(Canvas.colorDraws==0&&Canvas.bitmapDraws==0&&SwapPreview.captures==0,"no captured or wallpaper layer on live return "+reason);
  }
  System.out.println("Production upper center transition checks="+checks+" passed (visual timing still needs device)");
 }
}'''
}
paths=[]
for name,text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
paths.append(str(src/'MiStepTopTaskTransition.java'))
paths.append(str(src/'TopTaskReturnPolicy.java'))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TopTransitionRegression'],check=True)

package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** Visual bridge only for main <-> upper task. Native task ownership stays with Host. */
final class MiStepTopTaskTransition {
    private final FrameLayout host;
    private final WindowManager windows;
    private final WindowManager.LayoutParams params;
    private final Face face;
    private final Rect source, destination;
    private final int width, height;
    private final Bitmap wallpaper;
    private final BooleanSupplier current;
    private final Runnable handoff;
    private final Consumer<MiStepTopTaskTransition> finished;
    private final Consumer<String> log;
    private final boolean liveMainReveal;
    private Object liveSurface;
    private Runnable nativeRestore;
    private boolean liveSurfaceAtEnd;
    private Bitmap before, after;
    private ValueAnimator animator;
    private boolean attached, closed, firstDraw, collapsed, revealing;
    private final Runnable timeout=this::close;
    private final View.OnAttachStateChangeListener attachment=new View.OnAttachStateChangeListener() {
        @Override public void onViewAttachedToWindow(View view) {}
        @Override public void onViewDetachedFromWindow(View view) {close();}
    };

    MiStepTopTaskTransition(FrameLayout host,int width,int height,Rect source,Rect destination,
            Bitmap wallpaper,Bitmap sourceSnapshot,BooleanSupplier current,Runnable handoff,
            Consumer<MiStepTopTaskTransition> finished,Consumer<String> log) throws Throwable {
        this(host,width,height,source,destination,wallpaper,sourceSnapshot,false,current,handoff,finished,log);
    }

    MiStepTopTaskTransition(FrameLayout host,int width,int height,Rect source,Rect destination,
            Bitmap wallpaper,Bitmap sourceSnapshot,boolean liveMainReveal,BooleanSupplier current,Runnable handoff,
            Consumer<MiStepTopTaskTransition> finished,Consumer<String> log) throws Throwable {
        this.host=host;this.width=width;this.height=height;
        this.source=new Rect(source);this.destination=new Rect(destination);
        this.wallpaper=wallpaper;this.current=current;this.handoff=handoff;
        this.finished=finished;this.log=log;
        this.liveMainReveal=liveMainReveal;
        before=sourceSnapshot!=null && !sourceSnapshot.isRecycled()
                ?sourceSnapshot.copy(Bitmap.Config.ARGB_8888,false)
                :liveMainReveal?null:SwapPreview.captureRegion(host.getContext().getClassLoader(),width,height,source);
        if(!liveMainReveal && before==null)throw new IllegalStateException("source snapshot unavailable");
        face=new Face();
        windows=(WindowManager)host.getContext().getSystemService(android.content.Context.WINDOW_SERVICE);
        params=new WindowManager.LayoutParams(-1,-1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,PixelFormat.TRANSLUCENT);
        params.gravity=Gravity.TOP|Gravity.START;
        params.windowAnimations=0;
        params.setTitle("MiStepUpperTransition");
        HostWindowPolicy.configure(params);
        params.setFitInsetsTypes(0);
        params.layoutInDisplayCutoutMode=WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        face.setOnTouchListener((view,event)->true);
        face.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    }

    void start() {
        if(closed || attached)return;
        if(!current.getAsBoolean()) {close();return;}
        windows.addView(face,params);attached=true;
        log.accept("upper center start source="+source+" destination="+destination);
        host.addOnAttachStateChangeListener(attachment);
        // A failed/late native handoff must never leave an input-blocking overlay behind.
        host.postDelayed(timeout,5000L);
    }

    void setLiveSurface(Object surface) {
        if(liveMainReveal)liveSurface=surface;
    }

    void setNativeRestore(Runnable restore) {
        if(closed)restore.run();
        else nativeRestore=restore;
    }

    private void shrink() {
        if(liveMainReveal) handoff();
        else animate(1f,0f,160L,this::handoff);
    }

    private void handoff() {
        if(closed || collapsed)return;
        collapsed=true;
        log.accept(liveMainReveal?"upper roll finished; live main reveal handoff":"upper center collapsed; native handoff");
        if(!current.getAsBoolean()) {close();return;}
        try {handoff.run();}
        catch(Throwable error) {log.accept("upper center handoff: "+error);close();}
    }

    /** Called after the existing adoption/return callback confirms task ownership. */
    void reveal() {
        if(closed || !collapsed || revealing)return;
        revealing=true;
        if(liveMainReveal) {
            if(!validLiveSurface()) {
                log.accept("upper live main surface unavailable; skip center animation");
                close();return;
            }
            face.expanding=true;
            animate(0f,1f,180L,()->{
                applyLiveScale(1f);
                log.accept("upper live main surface expanded");
                close();
            });
            return;
        }
        host.postOnAnimation(()->host.postOnAnimation(()->{
            if(closed)return;
            if(!current.getAsBoolean()) {close();return;}
            try {
                Object root=XposedHelpers.callMethod(face,"getViewRootImpl");
                Object surface=XposedHelpers.callMethod(root,"getSurfaceControl");
                if(surface==null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface,"isValid")))
                    throw new IllegalStateException("transition surface unavailable");
                after=SwapPreview.captureRegion(host.getContext().getClassLoader(),width,height,destination,surface);
                log.accept("upper center destination captured excluding transition");
            } catch(Throwable error) {
                // Secure/unsupported capture does not change native task ownership.
                log.accept("upper destination capture unavailable: "+error);
                close();return;
            }
            face.expanding=true;
            animate(0f,1f,180L,()->{log.accept("upper center expanded");close();});
        }));
    }

    private void animate(float from,float to,long duration,Runnable end) {
        if(closed)return;
        ValueAnimator next=ValueAnimator.ofFloat(from,to);
        animator=next;face.scale=from;
        next.setDuration(duration);
        next.setInterpolator(new AccelerateDecelerateInterpolator());
        next.addUpdateListener(value->{
            if(!current.getAsBoolean()) {close();return;}
            float fraction=(Float)value.getAnimatedValue();
            if(liveMainReveal) {
                if(!applyLiveScale(TopTaskReturnPolicy.revealScale(fraction))) {
                    log.accept("upper live main surface transform failed; restoring full frame");
                    close();return;
                }
            } else face.scale=fraction;
            face.invalidate();
        });
        next.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if(closed || animator!=animation)return;
                animator=null;end.run();
            }
        });
        next.start();
    }

    void close() {
        if(closed)return;
        closed=true;
        if(liveMainReveal && !liveSurfaceAtEnd)applyLiveScale(1f);
        Runnable restore=nativeRestore;nativeRestore=null;
        if(restore!=null)try {restore.run();}
        catch(Throwable error) {log.accept("upper live main state restore: "+error);}
        ValueAnimator old=animator;animator=null;
        if(old!=null)old.cancel();
        host.removeCallbacks(timeout);host.removeOnAttachStateChangeListener(attachment);
        if(attached) {
            face.setVisibility(View.GONE);
            try {windows.removeViewImmediate(face);}
            catch(Throwable error) {log.accept("upper transition removal: "+error);}
            attached=false;
        }
        // RenderThread may still retain the last display list; let its bitmaps retire with it.
        before=after=null;
        finished.accept(this);
    }

    private boolean validLiveSurface() {
        try {
            return liveSurface!=null && Boolean.TRUE.equals(XposedHelpers.callMethod(liveSurface,"isValid"));
        } catch(Throwable ignored) {return false;}
    }

    private boolean applyLiveScale(float scale) {
        if(!validLiveSurface())return false;
        Object transaction=null;
        try {
            transaction=Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            float x=TopTaskReturnPolicy.centeredOffset(width,scale);
            float y=TopTaskReturnPolicy.centeredOffset(height,scale);
            XposedHelpers.callMethod(transaction,"setPosition",liveSurface,x,y);
            XposedHelpers.callMethod(transaction,"setMatrix",liveSurface,scale,0f,0f,scale);
            XposedHelpers.callMethod(transaction,"setWindowCrop",liveSurface,new Rect(0,0,width,height));
            XposedHelpers.callMethod(transaction,"apply");
            liveSurfaceAtEnd=scale>=1f;
            return true;
        } catch(Throwable error) {
            log.accept("upper live main surface transaction: "+error);
            return false;
        } finally {
            if(transaction!=null)try {XposedHelpers.callMethod(transaction,"close");}
            catch(Throwable ignored) {}
        }
    }

    private final class Face extends View {
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
        float scale=1f;
        boolean expanding;
        Face() {super(host.getContext());}
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if(!liveMainReveal) {
                background(canvas,source);
                background(canvas,destination);
                Bitmap image=expanding?after:before;
                Rect area=expanding?destination:source;
                if(image!=null && !image.isRecycled() && scale>0f) {
                    int saved=canvas.save();
                    canvas.clipRect(area);
                    canvas.scale(scale,scale,area.exactCenterX(),area.exactCenterY());
                    canvas.drawBitmap(image,null,area,paint);
                    canvas.restoreToCount(saved);
                }
            }
            if(!firstDraw) {
                firstDraw=true;postOnAnimation(()->{if(!closed)shrink();});
            }
        }
        private void background(Canvas canvas,Rect area) {
            int saved=canvas.save();canvas.clipRect(area);
            if(wallpaper!=null && !wallpaper.isRecycled())
                canvas.drawBitmap(wallpaper,null,new Rect(0,0,width,height),paint);
            else canvas.drawColor(HyperOsStyle.page(getContext()));
            canvas.restoreToCount(saved);
        }
    }
}

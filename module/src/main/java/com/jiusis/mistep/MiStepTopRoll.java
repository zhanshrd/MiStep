package com.jiusis.mistep;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import java.util.function.Consumer;

/** Continuous adjacent cuboid faces. Only snapshots rotate; the live task stays flat. */
final class MiStepTopRoll extends View {
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Matrix matrix=new Matrix();
    private final float[] src=new float[8],dst=new float[8];
    private final TopRollMotion motion=new TopRollMotion();
    private final Consumer<Boolean> settled;
    private Bitmap front,back;
    private ValueAnimator animator;
    private boolean dragging,target;

    MiStepTopRoll(Context context,Consumer<Boolean> settled) {
        super(context);this.settled=settled;setVisibility(INVISIBLE);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    boolean rolling() {return animator!=null || dragging;}
    boolean showingTask() {return target;}
    // Valid during the settle callback, before the roll retires its snapshots.
    Bitmap taskSnapshot() {return back;}
    void snapshots(Bitmap front,Bitmap back) {releaseSnapshots();this.front=front;this.back=back;}
    private void stopAnimator() {
        if(animator!=null) {ValueAnimator old=animator;animator=null;old.cancel();}
    }
    void reset(boolean task) {
        stopAnimator();dragging=false;target=task;motion.reset(task);
        setVisibility(INVISIBLE);releaseSnapshots();
    }
    void show(boolean task) {
        int nearest=Math.round(motion.phase);
        int destination=TopRollMotion.taskFace(nearest)==task?nearest:nearest+(task?1:-1);
        animateTo(destination);
    }
    void beginGesture() {
        stopAnimator();dragging=true;motion.begin(getHeight());
        setVisibility(VISIBLE);bringToFront();
    }
    void moveGesture(float dy,long time) {
        if(!dragging)return;
        motion.move(dy,time);invalidate();
    }
    void finishGesture(boolean cancelled) {
        if(!dragging)return;
        dragging=false;animateTo(motion.destination(cancelled));
    }
    private void animateTo(int quarter) {
        stopAnimator();target=TopRollMotion.taskFace(quarter);
        float remaining=Math.abs(motion.phase-quarter);
        ValueAnimator next=ValueAnimator.ofFloat(motion.phase,quarter);
        animator=next;setVisibility(VISIBLE);bringToFront();
        next.setDuration(Math.max(90,Math.min(280,Math.round(260*remaining))));
        next.setInterpolator(new DecelerateInterpolator(1.4f));
        next.addUpdateListener(value->{motion.phase=(float)value.getAnimatedValue();invalidate();});
        next.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator animation) {
                if(animator!=animation)return;
                animator=null;motion.reset(target);settled.accept(target);
                setVisibility(INVISIBLE);releaseSnapshots();
            }
        });
        next.start();
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int save=canvas.save();canvas.clipRect(0,0,getWidth(),getHeight());
        canvas.drawColor(HyperOsStyle.page(getContext()));
        int whole=(int)Math.floor(motion.phase);
        float fraction=motion.phase-whole;
        boolean task=TopRollMotion.taskFace(whole);
        double angle=fraction*Math.PI/2;
        drawFace(canvas,task?back:front,angle,false,fraction);
        drawFace(canvas,task?front:back,angle,true,fraction);
        canvas.restoreToCount(save);
    }
    private void drawFace(Canvas canvas,Bitmap bitmap,double angle,boolean lower,float fraction) {
        if(bitmap==null || bitmap.isRecycled() || (lower && fraction<.0001f))return;
        float w=getWidth(),h=getHeight(),d=h/2,focal=w*3;
        src[0]=src[1]=src[3]=src[6]=0;
        src[2]=src[4]=bitmap.getWidth();src[5]=src[7]=bitmap.getHeight();
        double cos=Math.cos(angle),sin=Math.sin(angle);
        for(int i=0;i<4;i++) {
            float x=(i==0 || i==3)?-w/2:w/2;
            float localY=i<2?-h/2:h/2;
            float y=lower?d:localY,z=lower?-localY:d;
            double rotatedY=y*cos-z*sin,rotatedZ=y*sin+z*cos-d;
            double perspective=focal/(focal-rotatedZ);
            dst[2*i]=(float)(w/2+x*perspective);
            dst[2*i+1]=(float)(h/2+rotatedY*perspective);
        }
        // Perspective turns a face away from the viewer shortly before 90 degrees; drawing it
        // then shows a mirrored sliver of the other page. Cull faces whose top edge projects below the bottom.
        if(dst[1]>=dst[7]-.5f)return;
        matrix.setPolyToPoly(src,0,dst,0,4);paint.setColorFilter(null);paint.setAlpha(255);
        canvas.drawBitmap(bitmap,matrix,paint);
        int saved=canvas.save();canvas.concat(matrix);paint.setColor(0xff000000);
        paint.setAlpha(Math.round(65*(lower?1-fraction:fraction)));
        canvas.drawRect(0,0,bitmap.getWidth(),bitmap.getHeight(),paint);
        canvas.restoreToCount(saved);paint.setAlpha(255);
    }
    private void releaseSnapshots() {
        if(front!=null && !front.isRecycled())front.recycle();
        if(back!=null && back!=front && !back.isRecycled())back.recycle();
        front=back=null;
    }
    @Override protected void onDetachedFromWindow() {reset(target);super.onDetachedFromWindow();}
}

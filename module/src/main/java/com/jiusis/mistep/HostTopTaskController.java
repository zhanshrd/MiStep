package com.jiusis.mistep;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.SystemClock;
import android.view.View;
import android.widget.FrameLayout;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** 顶部横屏任务子系统（v1.0.1 移植）：第五槽 TaskView、双指翻面、中心收展动画、
 *  方向观测与主区焦点偏好。自 MiStepHost 原样搬出；宿主保留 shim。 */
final class HostTopTaskController {
    private final MiStepHost h;

    HostTopTaskController(MiStepHost host) { h = host; }

    MiStepTopTaskPane topTaskPane;
    MiStepTopRoll topRoll;
    final TopTaskState topTaskState = new TopTaskState();
    final java.util.Map<TopTaskState.Key, Boolean> topDirections = new java.util.HashMap<>();
    MiStepHost.Slot topOriginalSlot;
    boolean topFromMain, topFace;
    boolean topSelectionPending;
    long topFaceGeneration;
    MiStepTopTaskTransition topTransition;
    Bitmap retainedTopSnapshot;

    private static final class TopCandidate {
        final MiStepHost.LiveTask task;
        final TopTaskState.Key key;
        final MiStepHost.Slot source;
        final boolean landscape;
        TopCandidate(MiStepHost.LiveTask task, TopTaskState.Key key, MiStepHost.Slot source, boolean landscape) {
            this.task=task; this.key=key; this.source=source; this.landscape=landscape;
        }
    }

    /** parkNext 成功归还顶部槽后的现场复位（宿主 parkNext 调用）。 */
    void onParkSuccessAtTop() {
        topTaskState.sceneChanged(); h.clearSlot(h.slots[MiStepHost.SLOT_TOP]); topFace=false;
        if(topRoll!=null)topRoll.reset(false);
        if(h.topBar!=null) {h.topBar.setVisibility(View.VISIBLE);h.topBar.bringToFront();h.topBar.leaveLandscapePage();}
        if(topTaskPane!=null)topTaskPane.setVisibility(View.INVISIBLE);
    }

    void createTopTaskControls() {
        MiStepHost.Slot upper=h.slots[MiStepHost.SLOT_TOP];upper.hint=h.makeEmptyHint();
        upper.hint.setOnClickListener(v->captureMainIntoTop());
        topTaskPane=new MiStepTopTaskPane(h.ctx,upper.hint);
        h.root.addView(topTaskPane,new FrameLayout.LayoutParams(-1,h.layout.topStrip));
        topRoll=new MiStepTopRoll(h.ctx,this::topRollSettled);
        h.root.addView(topRoll,new FrameLayout.LayoutParams(-1,h.layout.topStrip));
        updateTopTaskGeometry();
        // The upper TaskView was added after the controls; its empty surface hole would
        // otherwise blank them until the first roll settles and reorders the views.
        if(!topFace && h.topBar!=null)h.topBar.bringToFront();
    }

    private void captureMainIntoTop() {
        if(!topReady() || !topFace || h.slots[MiStepHost.SLOT_TOP].occupied || topTaskState.busy() || topTransition!=null)return;
        TopCandidate current=currentTopCandidate();
        if(current==null) {
            android.widget.Toast.makeText(h.ctx,"先在主屏打开应用，再点此格收进来",android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if(!startTopTaskTransition(true,()->claimTopTask(current,true)))claimTopTask(current,true);
    }

    private boolean startTopTaskTransition(boolean intoTop, Runnable transfer) {
        return startTopTaskTransition(intoTop,false,transfer);
    }

    private boolean startLiveMainRevealTransition(Runnable transfer) {
        return startTopTaskTransition(false,true,transfer);
    }

    private boolean startTopTaskTransition(boolean intoTop, boolean liveMainReveal, Runnable transfer) {
        if(h.root==null || topTransition!=null)return false;
        // Returning to main always uses the native app surface, never a captured face.
        if(!intoTop && !liveMainReveal)return false;
        final long epoch=h.sceneEpoch;
        final FrameLayout drawingRoot=h.root;
        int[] upperBounds=h.topGeometry().bounds();
        Rect upper=new Rect(upperBounds[0],upperBounds[1],upperBounds[2],upperBounds[3]);
        Rect mainArea=new Rect(h.layout.mainOffsetX,h.layout.mainOffsetY,
                h.layout.mainOffsetX+Math.round(h.screenW*h.layout.mainScale),
                h.layout.mainOffsetY+Math.round(h.screenH*h.layout.mainScale));
        try {
            Bitmap sourceSnapshot=!liveMainReveal && !intoTop && !topFace && topRoll!=null
                    ?topRoll.taskSnapshot():null;
            topTransition=new MiStepTopTaskTransition(drawingRoot,h.screenW,h.screenH,
                    liveMainReveal?mainArea:intoTop?mainArea:upper,intoTop?upper:mainArea,
                    h.wallpaperBackdrop,sourceSnapshot,liveMainReveal,
                    ()->h.root==drawingRoot && h.sceneEpoch==epoch && h.scene==MiStepHost.ACTIVE
                            && h.screenOn && !h.keyguardLocked && !h.suspendRequested,
                    transfer,closed->{
                        if(topTransition==closed)topTransition=null;
                        h.finishPendingSuspend();
                    },MiStepHost::log);
            topTransition.start();
            return true;
        } catch(Throwable error) {
            cancelTopTaskTransition();
            MiStepHost.log("upper center transition unavailable; keep native handoff: "+error);
            return false;
        }
    }

    private void revealTopTaskTransition() {
        if(topTransition!=null)topTransition.reveal();
    }

    private void cancelTopTaskTransition() {
        if(topTransition!=null)topTransition.close();
    }

    void updateTopTaskGeometry() {
        if(h.layout==null || topTaskPane==null || topRoll==null)return;
        MiStepHost.Slot upper=h.slots[MiStepHost.SLOT_TOP]; upper.rect=h.slotBounds(upper); h.applyRect(upper);
        topTaskPane.setLayoutParams(new FrameLayout.LayoutParams(-1,h.layout.topStrip));
        topRoll.setLayoutParams(new FrameLayout.LayoutParams(-1,h.layout.topStrip));
    }

    private boolean topReady() {
        return h.root!=null && h.scene==MiStepHost.ACTIVE && h.screenOn && !h.keyguardLocked && !h.transferBusy
                && h.promotion==null && h.cellMotion==null && !h.workspaceEntering && topRoll!=null;
    }

    /** Match the current TOP binding, not a package, focused task or ordinary native window. */
    static boolean ownsTopNativeCaption(Object info) {
        MiStepHost h=MiStepHost.current();
        if(h==null || h.root==null || h.scene!=MiStepHost.ACTIVE || info==null)return false;
        MiStepHost.Slot upper=h.slots[MiStepHost.SLOT_TOP];
        if(upper==null || !upper.occupied || upper.taskId<0 || upper.taskToken==null)return false;
        try {
            return XposedHelpers.getIntField(info,"displayId")==0
                    && Integer.valueOf(6).equals(XposedHelpers.callMethod(info,"getWindowingMode"))
                    && XposedHelpers.getIntField(info,"taskId")==upper.taskId
                    && upper.taskToken.equals(XposedHelpers.getObjectField(info,"token"));
        } catch(Throwable ignored) {return false;}
    }

    void resetTopForSuspend() {
        cancelTopTaskTransition();
        releaseRetainedTopSnapshot();
        topFaceGeneration++;
        topTaskState.sceneChanged();
        topSelectionPending=false;
        topFace=false;
        if(topRoll!=null)topRoll.reset(false);
        if(topTaskPane!=null)topTaskPane.setVisibility(View.INVISIBLE);
        if(h.topBar!=null) {h.topBar.setVisibility(View.VISIBLE);h.topBar.bringToFront();h.topBar.leaveLandscapePage();}
    }

    private Bitmap topSnapshot(View view) {
        Bitmap bitmap=Bitmap.createBitmap(h.screenW,h.layout.topStrip,Bitmap.Config.ARGB_8888);
        android.graphics.Canvas canvas=new android.graphics.Canvas(bitmap);
        canvas.drawColor(HyperOsStyle.page(h.ctx));
        view.draw(canvas); return bitmap;
    }

    private void prepareTopSnapshots() {
        ++topFaceGeneration;
        Bitmap front=topSnapshot(h.topBar), back=topSnapshot(topTaskPane);
        MiStepHost.Slot upper=h.slots[MiStepHost.SLOT_TOP];
        if(!topFace && upper.occupied && !upper.parked
                && retainedTopSnapshot!=null && !retainedTopSnapshot.isRecycled()) {
            back.recycle();
            back=retainedTopSnapshot.copy(Bitmap.Config.ARGB_8888,false);
        } else if(topFace && upper.occupied && !upper.parked) {
            try {
                long started=SystemClock.uptimeMillis();
                Bitmap composed=SwapPreview.captureRegion(h.ctx.getClassLoader(),h.screenW,h.screenH,
                        new Rect(0,0,h.screenW,h.layout.topStrip));
                back.recycle();back=composed;
                MiStepHost.log("upper composed snapshot "+back.getWidth()+"x"+back.getHeight()
                        +" elapsed="+(SystemClock.uptimeMillis()-started)+"ms");
            } catch(Throwable unavailable) {MiStepHost.log("upper composed snapshot unavailable: "+unavailable);}
        }
        topRoll.snapshots(front,back);
    }

    static int topGestureHeight() {
        MiStepHost host=MiStepHost.current();
        return host==null ? 0 : host.topCtrl.topGestureHeightNow();
    }

    int topGestureHeightNow() {
        return MiStepHost.hostSceneActive() && topReady() && !h.suspendRequested
                && topTransition==null ? h.layout.topStrip : 0;
    }

    static boolean beginTopGesture() {
        MiStepHost host=MiStepHost.current();
        return host!=null && host.topCtrl.beginTopGestureNow();
    }

    boolean beginTopGestureNow() {
        if(topGestureHeightNow()==0)return false;
        if(!topRoll.rolling())prepareTopSnapshots();
        topSelectionPending=!topFace;
        MiStepClipboard.get(h.ctx).close(); MiStepScanner.closeImmediately();
        topRoll.beginGesture();
        return true;
    }

    static void moveTopGesture(float displacement, long time) {
        MiStepHost host=MiStepHost.current();
        if(host!=null)host.topCtrl.moveTopGestureNow(displacement, time);
    }

    void moveTopGestureNow(float displacement, long time) {
        if(topRoll!=null)topRoll.moveGesture(displacement,time);
    }

    static void endTopGesture(boolean cancelled) {
        MiStepHost host=MiStepHost.current();
        if(host!=null)host.topCtrl.endTopGestureNow(cancelled);
    }

    void endTopGestureNow(boolean cancelled) {
        if(topRoll!=null)topRoll.finishGesture(cancelled);
    }

    void openTopFace(boolean manual) {
        if(!topReady() || topRoll.rolling() || topTransition!=null)return;
        if(topFace) {if(!h.slots[MiStepHost.SLOT_TOP].occupied)showTopCandidates(); return;}
        MiStepClipboard.get(h.ctx).close(); MiStepScanner.closeImmediately();
        topSelectionPending=manual; prepareTopSnapshots(); topRoll.show(true);
    }

    private void topRollSettled(boolean taskFace) {
        if(h.root==null || h.scene!=MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked)return;
        topFace=taskFace; topFaceGeneration++;
        h.topBar.setVisibility(taskFace?View.INVISIBLE:View.VISIBLE);
        // The empty SurfaceView can still clear its old hole while Shell finishes returning.
        // Paint the restored controls after it, including the now full-width adaptive hole.
        if(!taskFace)h.topBar.bringToFront();
        topTaskPane.setVisibility(taskFace?View.VISIBLE:View.INVISIBLE);
        if(!taskFace) {
            h.topBar.leaveLandscapePage();
            MiStepHost.Slot upper=h.slots[MiStepHost.SLOT_TOP];
            if(upper.occupied) {
                Boolean mainOccupied=mainTaskOccupiedBeside(upper.taskId);
                boolean occupiedOrUnknown=mainOccupied==null || mainOccupied;
                if(TopTaskReturnPolicy.keepUpperTask(occupiedOrUnknown)) {
                    retainTopTaskSnapshot();
                    MiStepHost.log("upper task retained in landscape TaskView while main is occupied or unknown task="
                            +upper.taskId);
                } else if(TopTaskReturnPolicy.liveCenterReveal(false,topFromMain,topOriginalSlot==null)) {
                    if(!startLiveMainRevealTransition(
                            ()->releaseTopTask(false,true,null,true)))
                        releaseTopTask(false,true,null);
                } else releaseTopTask(false,true,null);
            }
        } else if(!h.slots[MiStepHost.SLOT_TOP].occupied) {
            showTopCandidates();
        }
        topSelectionPending=false;
        h.updateHint(h.slots[MiStepHost.SLOT_TOP]); h.root.requestLayout();
    }

    private TopCandidate candidate(Object info, MiStepHost.Slot source) {
        MiStepHost.LiveTask task=new MiStepHost.LiveTask(info);
        TopTaskState.Key key=new TopTaskState.Key(task.id,XposedHelpers.getIntField(info,"userId"),task.token);
        Boolean directed=topDirections.get(key);
        boolean landscape=directed!=null?directed:h.configurationOrientationOf(info)==2
                || FullscreenRotationPolicy.requestsLandscape(h.requestedOrientationOf(info));
        return new TopCandidate(task,key,source,landscape);
    }

    private TopCandidate currentTopCandidate() {
        try {
            MiStepHost.LiveTask task=h.findMainTask();
            Object info=task==null?null:h.rootInfo(task);
            return h.ordinaryRoot(info,1) && h.mainTaskVisible(info)?candidate(info,null):null;
        } catch(Throwable unavailable) {MiStepHost.log("upper current candidate unavailable: "+unavailable);return null;}
    }

    private void showTopCandidates() {
        if(topTaskPane==null || !topFace || !topReady())return;
        topTaskPane.occupied(h.slots[MiStepHost.SLOT_TOP].occupied && !h.slots[MiStepHost.SLOT_TOP].parked);
        h.root.requestLayout();
    }

    private Boolean mainTaskOccupiedBeside(int upperTaskId) {
        try {
            MiStepHost.LiveTask mainTask=h.findMainTask();
            return mainTask!=null && mainTask.id!=upperTaskId;
        } catch(Throwable unavailable) {
            MiStepHost.log("upper return cannot confirm main task: "+unavailable);
            return null;
        }
    }

    private void retainTopTaskSnapshot() {
        Bitmap current=topRoll==null?null:topRoll.taskSnapshot();
        if(current==null || current.isRecycled())return;
        Bitmap copy=current.copy(Bitmap.Config.ARGB_8888,false);
        releaseRetainedTopSnapshot();
        retainedTopSnapshot=copy;
    }

    void releaseRetainedTopSnapshot() {
        if(retainedTopSnapshot!=null && !retainedTopSnapshot.isRecycled())
            retainedTopSnapshot.recycle();
        retainedTopSnapshot=null;
    }

    private void replaceTopTask(TopCandidate candidate) {
        if(!topReady())return;
        if(candidate.key.equals(topTaskState.owner())) {topTaskPane.occupied(true);h.root.requestLayout();return;}
        if(h.slots[MiStepHost.SLOT_TOP].occupied)releaseTopTask(false,true,()->claimTopTask(candidate,true));
        else claimTopTask(candidate,true);
    }

    private void claimTopTask(TopCandidate selected, boolean manual) {
        if(!manual || !topReady() || !topFace || h.slots[MiStepHost.SLOT_TOP].occupied) {cancelTopTaskTransition();return;}
        try {
            Object fresh=h.rootInfo(selected.task);
            if(fresh==null || XposedHelpers.getIntField(fresh,"userId")!=selected.key.userId) {cancelTopTaskTransition();return;}
            if(selected.source==null && (!h.ordinaryRoot(fresh,1) || !h.mainTaskVisible(fresh))) {cancelTopTaskTransition();return;}
            if(selected.source!=null && (!h.ordinaryRoot(fresh,6) || !h.controllerOwns(selected.source,selected.task.id,selected.task.token))) {cancelTopTaskTransition();return;}
            TopCandidate checked=candidate(fresh,selected.source);
            TopTaskState.Ticket ticket=topTaskState.request(checked.key,h.sceneEpoch,true,checked.landscape,manual,selected.source==null);
            if(ticket==null) {cancelTopTaskTransition();return;}
            topTaskPane.message("正在接入原任务…");
            if(selected.source==null)adoptTopTask(checked,ticket,true);
            else h.returnToSystem(selected.source,false,()->{
                if(!topTaskState.current(ticket,h.sceneEpoch)) {topTaskState.cancel(ticket);h.finishPendingSuspend();return;}
                h.clearSlot(selected.source);
                adoptTopTask(checked,ticket,false);
            },error->{topTaskState.cancel(ticket);topTaskPane.message("原窗口保持："+error);h.finishPendingSuspend();});
        } catch(Throwable error) {cancelTopTaskTransition();MiStepHost.log("upper admission stopped: "+error);topTaskPane.message("接入未完成，原任务保持");}
    }

    private void adoptTopTask(TopCandidate selected, TopTaskState.Ticket ticket, boolean fromMain) {
        if(!topTaskState.current(ticket,h.sceneEpoch)) {cancelTopTaskTransition();return;}
        h.adoptExisting(h.slots[MiStepHost.SLOT_TOP],selected.task,fromMain,()->{
            if(!topTaskState.commit(ticket,h.sceneEpoch)) {releaseTopTask(false,false,null);return;}
            topOriginalSlot=selected.source;topFromMain=fromMain;
            topTaskPane.occupied(true);
            updateTopTaskGeometry();h.applyFullscreenBounds(h.slots[MiStepHost.SLOT_TOP]);h.applySlotScale(h.slots[MiStepHost.SLOT_TOP]);
            h.publishDesktopSideTasks();
            h.root.requestLayout();
            MiStepHost.log("upper task admitted task="+selected.key.taskId+" user="+selected.key.userId+" original="+(selected.source==null?"main":selected.source.index));
            revealTopTaskTransition();
            preferMainAfterTopChange();
            h.finishPendingSuspend();
        },error->{
            cancelTopTaskTransition();
            topTaskState.cancel(ticket);topTaskPane.message("横屏接入失败，正在归还原任务");
            MiStepHost.log("upper adoption failed: "+error);
            MiStepHost.Slot origin=selected.source;
            if(origin!=null && !origin.occupied && topReady() && !h.suspendRequested)
                h.adoptExisting(origin,selected.task,()->{h.finishPendingSuspend();},failure->{MiStepHost.log("upper original slot restore: "+failure);h.finishPendingSuspend();});
            else h.finishPendingSuspend();
        });
    }

    void releaseTopTask(boolean front, boolean dismissed, Runnable after) {
        releaseTopTask(front,dismissed,after,false);
    }

    private void releaseTopTask(boolean front, boolean dismissed, Runnable after,
            boolean liveMainReveal) {
        final MiStepHost.Slot upper=h.slots[MiStepHost.SLOT_TOP];
        final long binding=upper.taskEpoch;
        final int taskId=upper.taskId;
        final Object token=upper.taskToken;
        // A two-finger return already has the roll animation; only explicit main transfer collapses.
        if(front && topTransition==null && topReady() && h.slots[MiStepHost.SLOT_TOP].occupied
                && !h.slots[MiStepHost.SLOT_TOP].promoting && !h.suspendRequested
                && startLiveMainRevealTransition(()->{
                    if(upper.taskEpoch!=binding || upper.taskId!=taskId || !java.util.Objects.equals(upper.taskToken,token)) {
                        cancelTopTaskTransition();return;
                    }
                    releaseTopTaskNow(front,dismissed,after,true);
                }))return;
        releaseTopTaskNow(front,dismissed,after,liveMainReveal);
    }

    private void releaseTopTaskNow(boolean front, boolean dismissed, Runnable after) {
        releaseTopTaskNow(front,dismissed,after,false);
    }

    private void releaseTopTaskNow(boolean front, boolean dismissed, Runnable after,
            boolean liveMainReveal) {
        MiStepHost.Slot upper=h.slots[MiStepHost.SLOT_TOP];
        if(!upper.occupied) {cancelTopTaskTransition();if(after!=null)after.run();return;}
        if(h.scene!=MiStepHost.ACTIVE || h.promotion!=null || h.transferBusy || upper.promoting) {cancelTopTaskTransition();return;}
        final MiStepHost.LiveTask task=new MiStepHost.LiveTask(upper);
        final TopTaskState.Key key=topTaskState.owner();
        final MiStepHost.Slot origin=topOriginalSlot;
        boolean mainOccupied=false;
        if(front && topFace && topRoll!=null) {
            try {
                MiStepHost.LiveTask mainTask=h.findMainTask();
                mainOccupied=mainTask!=null && mainTask.id!=task.id;
            } catch(Throwable unavailable) { MiStepHost.log("upper return main task unavailable: "+unavailable); }
        }
        final boolean animateBackToMain=TopRollMotion.shouldAnimateBackToMain(mainOccupied);
        boolean returnFront=front;
        try { if(topFromMain && origin==null && h.findMainTask()==null)returnFront=true; } catch(Throwable ignored){}
        final boolean bringingFront=returnFront;
        h.returnToSystem(upper,bringingFront,liveMainReveal,()->{
            topTaskState.release(key,dismissed);h.clearSlot(upper);topOriginalSlot=null;
            Runnable finish=()->{
                if(front && topFace && topRoll!=null) {
                    if(topTransition!=null) {topRoll.reset(false);topRollSettled(false);}
                    else if(animateBackToMain) {prepareTopSnapshots();topRoll.show(false);}
                    else {topRoll.reset(false);topRollSettled(false);}
                }
                revealTopTaskTransition();
                if(after!=null)after.run();h.finishPendingSuspend();
            };
            if(!front && origin!=null && h.isActive(origin) && !origin.occupied && h.scene==MiStepHost.ACTIVE)
                h.adoptExisting(origin,task,finish,error->{MiStepHost.log("upper return original unavailable: "+error);finish.run();});
            else finish.run();
            MiStepHost.log("upper task returned alive="+task.id+" front="+bringingFront+" dismissed="+dismissed);
        },error->{
            cancelTopTaskTransition();
            topTaskPane.message("收回未确认，保留原任务："+error);
            if(!topFace) {topFace=true;h.topBar.setVisibility(View.INVISIBLE);topTaskPane.setVisibility(View.VISIBLE);}
            h.updateHint(upper);h.root.requestLayout();MiStepHost.log("upper return stopped: "+error);h.finishPendingSuspend();
        });
    }

    static void onTopTaskState(Intent intent) {
        MiStepHost host=MiStepHost.current();
        if(host==null)return;
        final int taskId=intent.getIntExtra("task",-1), userId=intent.getIntExtra("user",-1);
        final Object token=intent.getParcelableExtra("token");
        final boolean landscape=intent.getBooleanExtra("landscape",false);
        if(taskId<0 || userId<0 || token==null)return;
        host.topCtrl.topTaskDirection(new TopTaskState.Key(taskId,userId,token),landscape);
    }

    void topTaskDirection(TopTaskState.Key key, boolean landscape) {
        if(h.root==null || !h.supportsTaskUser(key.userId))return;
        topDirections.put(key,landscape);
        if(topDirections.size()>64)topDirections.keySet().removeIf(item->!item.equals(topTaskState.owner()) && !item.equals(key));
        topTaskState.observe(key,landscape);
        // Observe only. A direction change never opens the upper face or claims a task.
    }

    /** Default focus at admission only; later explicit top touches use native focus. */
    private void preferMainAfterTopChange() {
        final long epoch=h.sceneEpoch;
        h.executeOnShell(()->{
            if(epoch!=h.sceneEpoch || h.scene!=MiStepHost.ACTIVE || h.suspendRequested || !h.screenOn || h.keyguardLocked)return;
            try {
                for(Object info:h.rootInfos())if(XposedHelpers.getBooleanField(info,"isFocused")
                        && h.windowModeOf(info)!=1 && !h.isSideToken(XposedHelpers.getObjectField(info,"token")))return;
                MiStepHost.LiveTask target=h.findMainTask();
                if(target==null)target=h.existingHomeTask();
                Object info=h.rootInfo(target);
                if(info==null || h.windowModeOf(info)!=1 || !XposedHelpers.getBooleanField(info,"isVisible"))return;
                Object service=XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityTaskManager"),"getService");
                XposedHelpers.callMethod(service,"setFocusedTask",target.id);
                MiStepHost.log("upper default main focus task="+target.id);
            } catch(Throwable error) {MiStepHost.log("upper main focus unavailable: "+error);}
        });
    }

}

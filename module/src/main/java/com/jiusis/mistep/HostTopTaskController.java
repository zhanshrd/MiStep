package com.jiusis.mistep;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.view.View;
import android.widget.FrameLayout;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** 顶部任务子系统（v1.0.1 移植 + v1.0.2/1.0.3 语义移植）：第五槽 TaskView、双指翻面、
 *  live 翻面逐帧渲染（取代 v1.0.1 的位图快照）、平板顶排（tabletLandscape 工具条 +
 *  直排 TaskView）、live 收集转场（setForceTranslucent 源）、顶部横向滑动归还、
 *  PREPARE_TOP 视口准备广播、方向观测与主区焦点偏好。上游 MiStepHost 对应簇语义搬入；
 *  宿主保留 shim。 */
final class HostTopTaskController {
    private final MiStepHost h;

    HostTopTaskController(MiStepHost host) { h = host; }

    MiStepTopTaskPane topTaskPane;
    MiStepTopRoll topRoll;
    final TopTaskState topTaskState = new TopTaskState();
    final java.util.Map<TopTaskState.Key, Boolean> topDirections = new java.util.HashMap<>();
    Slot topOriginalSlot;
    boolean topFromMain, topFace;
    boolean topSelectionPending;
    long topFaceGeneration;
    MiStepTopTaskTransition topTransition;

    /** 上游为 MiStepHost 私有嵌套类；fork 里宿主字段 tabletCoveredMain 需要可见性，放宽为包私有。 */
    static final class TopCandidate {
        final LiveTask task;
        final TopTaskState.Key key;
        final Slot source;
        final boolean landscape;
        TopCandidate(LiveTask task, TopTaskState.Key key, Slot source, boolean landscape) {
            this.task=task; this.key=key; this.source=source; this.landscape=landscape;
        }
    }

    /** parkNext 成功归还顶部槽后的现场复位（宿主 parkNext 调用）。 */
    void onParkSuccessAtTop() {
        topTaskState.sceneChanged(); h.clearSlot(h.slots[MiStepHost.SLOT_TOP]); topFace=false;
        if(topRoll!=null)topRoll.reset(false);
        if(h.topBar!=null && !h.layout.tabletLandscape) {h.topBar.setVisibility(View.VISIBLE);h.topBar.bringToFront();}
        if(topTaskPane!=null)topTaskPane.setVisibility(View.INVISIBLE);
    }

    void createTopTaskControls() {
        Slot upper=h.slots[MiStepHost.SLOT_TOP];upper.hint=h.makeEmptyHint();
        upper.hint.setOnClickListener(v->captureMainIntoTop());
        topTaskPane=new MiStepTopTaskPane(h.ctx,upper.hint);
        topTaskPane.setAlpha(h.workspaceAlpha);
        FrameLayout taskParent=h.layout.tabletLandscape?h.root:h.topLiveClip;
        taskParent.addView(topTaskPane,new FrameLayout.LayoutParams(-1,h.layout.topStrip));
        topRoll=new MiStepTopRoll(h.ctx,this::topRollSettled,this::renderTopRollFrame);
        h.topLiveClip.addView(topRoll,new FrameLayout.LayoutParams(-1,h.layout.topStrip));
        updateTopTaskGeometry();
        // The upper TaskView was added after the controls; its empty surface hole would
        // otherwise blank them until the first roll settles and reorders the views.
        if(!topFace && h.topBar!=null)h.topBar.bringToFront();
    }

    void configureTabletTools() {
        if (h.root==null || h.topBar==null) return;
        if (h.tabletTools!=null) {h.root.removeView(h.tabletTools);h.tabletTools=null;}
        if (!h.layout.tabletLandscape) {
            h.tabletToolsOpen=false;
            h.topBar.clearTabletHeader();
            h.topBar.setVisibility(topFace?View.INVISIBLE:View.VISIBLE);
            return;
        }
        topFace=true;
        h.tabletTools=h.topBar.createTabletHeader(h.layout.toolHeight,
                ()->setTabletToolsOpen(!h.tabletToolsOpen));
        h.topBar.setTabletToolsExpanded(h.tabletToolsOpen,false);
        h.tabletTools.setAlpha(h.workspaceAlpha);
        FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(-1,h.layout.toolHeight);
        p.topMargin=0;
        h.root.addView(h.tabletTools,p);
        updateTopTaskGeometry();
    }

    void setTabletToolsOpen(boolean open) {
        if (!h.layout.tabletLandscape || h.topBar==null || h.tabletTools==null) return;
        h.tabletToolsOpen=open;
        if (!open) {
            h.topBar.closeQuickAppPicker();
            MiStepClipboard.get(h.ctx).close();
            MiStepScanner.closeImmediately();
        }
        h.topBar.setTabletToolsExpanded(open,true);
        if (open) {h.topLiveClip.bringToFront();h.topBar.bringToFront();}
        h.tabletTools.bringToFront();
        h.root.requestLayout();
    }

    void setTaskCellsCovered(boolean covered) {
        if (h.taskCellsCovered == covered) return;
        h.taskCellsCovered = covered;
        for (int i = 1; i < h.CELL_SLOT_COUNT; i++) h.updateHint(h.slots[i]);
        if (h.root != null) { h.root.requestLayout(); h.root.invalidate(); }
    }

    private void captureMainIntoTop() {
        if(!topReady() || !topFace || h.slots[MiStepHost.SLOT_TOP].occupied || topTaskState.busy() || topTransition!=null)return;
        TopCandidate current=currentTopCandidate();
        if(current==null) {
            android.widget.Toast.makeText(h.ctx,"先在主屏打开应用，再点此格收进来",android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        if(!startTopCollectionTransition(current,()->claimTopTask(current,true)))claimTopTask(current,true);
    }

    private boolean startTopCollectionTransition(TopCandidate selected,Runnable transfer) {
        Slot upper=h.slots[MiStepHost.SLOT_TOP];
        if(h.root==null || topTransition!=null || upper.view==null || upper.controller==null)return false;
        try {
            Object organizer=XposedHelpers.getObjectField(upper.controller,"mTaskOrganizer");
            Object lock=XposedHelpers.getObjectField(organizer,"mLock");
            Object source;
            synchronized(lock) {
                Object appeared=((android.util.SparseArray<?>)XposedHelpers.getObjectField(organizer,"mTasks")).get(selected.task.id);
                if(appeared==null)return false;
                source=XposedHelpers.callMethod(appeared,"getLeash");
                if(!topCollectionSourceCurrent(organizer,selected,source))return false;
            }
            boolean started=startTopTaskTransition(true,false,transfer,source,
                    ()->topCollectionSourceCurrent(organizer,selected,source),upper.view);
            if(started) {
                MiStepTopTaskTransition prepared=topTransition;
                prepared.setNativeRestore(()->setTopCollectionTranslucent(selected,false));
                if(!h.executeOnShell(()->{
                    try {
                        if(topTransition!=prepared || !topCollectionSourceCurrent(organizer,selected,source))
                            throw new IllegalStateException("collection source changed before background sync");
                        Object wct=topCollectionTransaction(selected,true);
                        if(wct==null)throw new IllegalStateException("collection source no longer fullscreen");
                        MiStepSyncPreparation.submit(upper.controller,wct,action->h.main.post(action),()->{
                            if(topTransition==prepared) {
                                if(!prepared.collectionReady())setTopCollectionTranslucent(selected,false);
                            } else if(topTransition==null)setTopCollectionTranslucent(selected,false);
                        },error->{MiStepHost.log("upper background sync failed: "+error);prepared.close();});
                    } catch(Throwable error) {
                        MiStepHost.log("upper background sync unavailable: "+error);h.main.post(prepared::close);
                    }
                }))prepared.close();
            }
            return started;
        } catch(Throwable error) {
            MiStepHost.log("upper live collection unavailable: "+error);return false;
        }
    }

    private boolean setTopCollectionTranslucent(TopCandidate selected,boolean translucent) {
        try {
            Object wct=topCollectionTransaction(selected,translucent);
            return wct!=null && h.applyWct("upper-live-collect-opacity-"+translucent,wct);
        } catch(Throwable error) {
            MiStepHost.log("upper live collection opacity: "+error);return false;
        }
    }

    private Object topCollectionTransaction(TopCandidate selected,boolean translucent) throws Exception {
        Object info=h.rootInfo(selected.task);
        if(!selected.task.matches(info) || !h.ordinaryRoot(info,1)
                || XposedHelpers.getIntField(info,"userId")!=selected.key.userId)return null;
        Object wct=Class.forName("android.window.WindowContainerTransaction")
                .getDeclaredConstructor().newInstance();
        if(translucent) {
            XposedHelpers.callMethod(wct,"reorder",h.homeForCollection(selected.task),true);
            XposedHelpers.callMethod(wct,"reorder",selected.task.token,true);
        }
        XposedHelpers.callMethod(wct,"setForceTranslucent",selected.task.token,translucent);
        return wct;
    }

    private boolean topCollectionSourceCurrent(Object organizer,TopCandidate selected,Object surface) {
        try {
            synchronized(XposedHelpers.getObjectField(organizer,"mLock")) {
                Object appeared=((android.util.SparseArray<?>)XposedHelpers.getObjectField(organizer,"mTasks")).get(selected.task.id);
                if(appeared==null || XposedHelpers.callMethod(appeared,"getLeash")!=surface)return false;
                Object info=XposedHelpers.callMethod(appeared,"getTaskInfo");
                return selected.task.matches(info) && XposedHelpers.getIntField(info,"userId")==selected.key.userId
                        && h.ordinaryRoot(info,1) && h.mainTaskVisible(info);
            }
        } catch(Throwable unavailable) {return false;}
    }

    private boolean startTopTaskTransition(boolean intoTop, Runnable transfer) {
        return startTopTaskTransition(intoTop,false,transfer);
    }

    private boolean startLiveMainRevealTransition(Runnable transfer) {
        return startTopTaskTransition(false,true,transfer);
    }

    private boolean startTopTaskTransition(boolean intoTop, boolean liveMainReveal, Runnable transfer) {
        return startTopTaskTransition(intoTop,liveMainReveal,transfer,null,null,null);
    }

    private boolean startTopTaskTransition(boolean intoTop, boolean liveMainReveal, Runnable transfer,
            Object source,java.util.function.BooleanSupplier sourceCurrent,View destination) {
        if(h.root==null || topTransition!=null)return false;
        if(intoTop ? source==null || sourceCurrent==null || destination==null : !liveMainReveal)return false;
        final long epoch=h.sceneEpoch;
        final FrameLayout drawingRoot=h.root;
        try {
            topTransition=new MiStepTopTaskTransition(drawingRoot,h.screenW,h.screenH,
                    ()->h.root==drawingRoot && h.sceneEpoch==epoch && h.scene==MiStepHost.ACTIVE
                            && h.screenOn && !h.keyguardLocked && !h.suspendRequested,
                    transfer,closed->{
                        if(topTransition==closed)topTransition=null;
                        h.finishPendingSuspend();
                    },MiStepHost::log);
            if(intoTop)topTransition.collect(source,sourceCurrent,destination);
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
        Slot upper=h.slots[MiStepHost.SLOT_TOP];
        FrameLayout taskParent=h.layout.tabletLandscape?h.root:h.topLiveClip;
        reparentTopView(upper.view,taskParent);
        reparentTopView(topTaskPane,taskParent);
        upper.rect=h.slotBounds(upper);h.applyRect(upper);
        if(h.layout.tabletLandscape) {
            topFace=true;
            h.topLiveClip.setLayoutParams(new FrameLayout.LayoutParams(-1,h.layout.topStrip));
            h.topLiveClip.setClipBounds(new Rect(0,0,h.screenW,h.layout.topStrip));
            FrameLayout.LayoutParams p=new FrameLayout.LayoutParams(
                    upper.rect[2]-upper.rect[0],upper.rect[3]-upper.rect[1]);
            p.leftMargin=upper.rect[0];p.topMargin=upper.rect[1];
            topTaskPane.setLayoutParams(p);topTaskPane.setVisibility(View.VISIBLE);
            topTaskPane.setTranslationX(0);topTaskPane.setTranslationY(0);
            topTaskPane.setScaleX(1);topTaskPane.setScaleY(1);
            topRoll.setVisibility(View.GONE);
            upper.view.setScaleX(1);upper.view.setScaleY(1);
            upper.view.setClipBounds(new Rect(0,0,p.width,p.height));
            if(topTransition!=null)topTransition.applyCollectionFrame(upper.view);
            h.updateCellSurfaceVisibility(upper);
            return;
        }
        topRoll.setVisibility(View.VISIBLE);
        h.topLiveClip.setLayoutParams(new FrameLayout.LayoutParams(-1,h.layout.topStrip));
        h.topLiveClip.setClipBounds(new Rect(0,0,h.screenW,h.layout.topStrip));
        topTaskPane.setLayoutParams(new FrameLayout.LayoutParams(-1,h.layout.topStrip));
        topTaskPane.setTranslationX(0);topTaskPane.setTranslationY(0);
        topRoll.setLayoutParams(new FrameLayout.LayoutParams(-1,h.layout.topStrip));
        topRoll.renderFrame();
    }

    private void reparentTopView(View view,FrameLayout parent) {
        if(view==null || view.getParent()==parent)return;
        if(view.getParent() instanceof android.view.ViewGroup)
            ((android.view.ViewGroup)view.getParent()).removeView(view);
        parent.addView(view,view.getLayoutParams());
    }

    private boolean topReady() {
        return h.root!=null && h.scene==MiStepHost.ACTIVE && h.screenOn && !h.keyguardLocked && !h.transferBusy
                && h.promotion==null && h.cellMotion==null && !h.workspaceEntering && topRoll!=null;
    }

    /** Match the current TOP binding, not a package, focused task or ordinary native window. */
    static boolean ownsTopNativeCaption(Object info) {
        MiStepHost h=MiStepHost.current();
        if(h==null || h.root==null || h.scene!=MiStepHost.ACTIVE || info==null)return false;
        Slot upper=h.slots[MiStepHost.SLOT_TOP];
        if(upper==null || !upper.occupied || upper.taskId<0 || upper.taskToken==null)return false;
        try {
            return XposedHelpers.getIntField(info,"displayId")==0
                    && Integer.valueOf(6).equals(XposedHelpers.callMethod(info,"getWindowingMode"))
                    && XposedHelpers.getIntField(info,"taskId")==upper.taskId
                    && upper.taskToken.equals(XposedHelpers.getObjectField(info,"token"));
        } catch(Throwable ignored) {return false;}
    }

    void resetTopForSuspend() {
        h.tabletCoveredMain=null;h.tabletReturnedTop=null;
        cancelTopTaskTransition();
        topFaceGeneration++;
        topTaskState.sceneChanged();
        topSelectionPending=false;
        topFace=false;
        if(topRoll!=null)topRoll.reset(false);
        if(topTaskPane!=null)topTaskPane.setVisibility(View.INVISIBLE);
        h.tabletToolsOpen=false;
        if(h.topBar!=null) {
            if(h.layout.tabletLandscape) h.topBar.setTabletToolsExpanded(false,false);
            else {h.topBar.setVisibility(View.VISIBLE);h.topBar.bringToFront();}
        }
    }

    private void prepareLiveTopRoll() {
        ++topFaceGeneration;
        topTaskPane.occupied(h.slots[MiStepHost.SLOT_TOP].occupied && !h.slots[MiStepHost.SLOT_TOP].parked);
        // Paint the other live face after SurfaceView clears its transparent hole.
        // The projected faces meet at an edge, so this does not cover the live task.
        h.topBar.bringToFront();
    }

    /** SurfaceView's frame transaction carries this transform to the original task surface. */
    private void renderTopRollFrame(TopRollMotion.Frame frame) {
        if(h.root==null || h.topBar==null || topTaskPane==null)return;
        if(h.layout.tabletLandscape) {updateTopTaskGeometry();return;}
        h.topBar.setPivotY(0f);
        h.topBar.setScaleY(Math.max(.0001f,frame.frontScale));
        h.topBar.setTranslationY(frame.frontTop);
        topTaskPane.setPivotY(0f);
        topTaskPane.setScaleY(Math.max(.0001f,frame.taskScale));
        topTaskPane.setTranslationY(frame.taskTop);
        Slot upper=h.slots[MiStepHost.SLOT_TOP];
        if(upper.view!=null) {
            upper.view.setPivotY(0f);
            upper.view.setScaleY(Math.max(.0001f,frame.taskScale));
            upper.view.setTranslationY(frame.taskTop);
            float scale=Math.max(.0001f,frame.taskScale);
            int height=h.layout.topStrip;
            int clipTop=Math.max(0,Math.min(height,(int)Math.floor(-frame.taskTop/scale)));
            int clipBottom=Math.max(clipTop,Math.min(height,
                    (int)Math.ceil((height-frame.taskTop)/scale)));
            upper.view.setClipBounds(new Rect(0,clipTop,h.screenW,clipBottom));
            if(topTransition!=null)topTransition.applyCollectionFrame(upper.view);
        }
        if(h.scene!=MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked)return;
        boolean rolling=topRoll!=null && topRoll.rolling();
        h.topBar.setVisibility(rolling || !topRoll.showingTask()?View.VISIBLE:View.INVISIBLE);
        topTaskPane.setVisibility(rolling || topRoll.showingTask()?View.VISIBLE:View.INVISIBLE);
        h.updateCellSurfaceVisibility(upper);
    }

    static int topGestureHeight() {
        int[] bounds=topGestureBounds();
        return bounds==null?0:bounds[3]-bounds[1];
    }

    static int[] topGestureBounds() {
        MiStepHost h=MiStepHost.current();
        if(h==null || !MiStepHost.hostSceneActive() || h.suspendRequested || h.topCtrl.topTransition!=null)return null;
        boolean ready=h.topCtrl.topReady() || (h.sideDismiss!=null && h.sideDismissSlot==h.slots[MiStepHost.SLOT_TOP]
                && h.sideDismiss.isTracking());
        if(!ready)return null;
        if(h.layout.tabletLandscape) return h.slots[MiStepHost.SLOT_TOP].rect;
        return new int[]{0,0,h.screenW,h.layout.topStrip};
    }

    static int topGestureDirection() {
        MiStepHost h=MiStepHost.current();
        return h!=null && h.layout!=null && h.layout.tabletLandscape ? (h.layout.mainOnRight?-1:1) : 0;
    }

    static void requestTopBack(Object token) {
        MiStepHost h=MiStepHost.current();
        if(h==null || h.layout==null || !h.layout.tabletLandscape || token==null
                || !token.equals(topDismissToken()))return;
        try { MiStepTopBack.request(h.ctx,token); MiStepHost.log("tablet inner-edge back requested"); }
        catch(Throwable error) {MiStepHost.log("tablet inner-edge back unavailable: "+error);}
    }

    private static int topDismissWidth(MiStepHost h) {
        int[] bounds=h.slots[MiStepHost.SLOT_TOP].rect;
        return h.layout.tabletLandscape && bounds!=null ? bounds[2]-bounds[0] : h.screenW;
    }

    static boolean topDismissDirectionAllowed(float dx) {
        return Float.isFinite(dx) && dx!=0;
    }

    /* A visible real task lease remains authoritative throughout the gesture. */
    static Object topDismissToken() {
        MiStepHost h=MiStepHost.current();
        if(h==null || !h.topCtrl.topReady() || h.suspendRequested || h.topCtrl.topTransition!=null
                || !h.topCtrl.topFace || (!h.layout.tabletLandscape && h.topCtrl.topRoll.rolling()) || h.sideDismiss!=null)return null;
        Slot upper=h.slots[MiStepHost.SLOT_TOP];
        TopTaskState.Key owner=h.topCtrl.topTaskState.owner();
        if(!upper.occupied || upper.parked || upper.frozen || upper.promoting || upper.launching
                || upper.closing || upper.view==null || upper.taskToken==null || owner==null
                || owner.taskId!=upper.taskId || !owner.token.equals(upper.taskToken)
                || !h.controllerOwns(upper,upper.taskId,upper.taskToken))return null;
        for(Slot slot:h.slots)if(slot.launching || slot.promoting || slot.closing)return null;
        return upper.taskToken;
    }

    static boolean beginTopDismiss(float dx,Object token) {
        MiStepHost h=MiStepHost.current();
        if(h==null)return false;
        if(token==null || !token.equals(topDismissToken()) || !topDismissDirectionAllowed(dx))return false;
        final Slot upper=h.slots[MiStepHost.SLOT_TOP];
        final long binding=upper.taskEpoch,epoch=h.sceneEpoch;
        final int taskId=upper.taskId;
        final View hostRoot=h.root;
        final TopTaskState.Key key=h.topCtrl.topTaskState.owner();
        final boolean intoMain=h.layout.tabletLandscape && dx*topGestureDirection()<0;
        h.topDismissDx=dx;h.topDismissDirection=Math.signum(dx);
        h.transferBusy=true;upper.promoting=true;
        try {
            final MiStepSideDismiss[] run=new MiStepSideDismiss[1];
            Runnable release=()->{
                run[0].close();
                if(h.sideDismiss==run[0]) {h.sideDismiss=null;h.sideDismissSlot=null;}
                if(h.root==hostRoot && h.sceneEpoch==epoch) {
                    h.updateHint(upper);h.updateTouchRegion(upper);hostRoot.invalidate();
                }
                h.finishPendingSuspend();
            };
            run[0]=new MiStepSideDismiss(hostRoot,upper.view,
                    h.topDismissDirection*(topDismissWidth(h)+HyperOsStyle.dp(h.ctx,8f)),
                    ()->h.root==hostRoot && h.sceneEpoch==epoch && h.scene==MiStepHost.ACTIVE && h.screenOn
                            && !h.keyguardLocked && !h.suspendRequested && h.isActive(upper)
                            && upper.taskEpoch==binding && upper.taskId==taskId && token.equals(upper.taskToken),
                    reachedEdge->{
                        if(!reachedEdge) {
                            if(upper.taskEpoch==binding && upper.taskId==taskId)upper.promoting=upper.frozen;
                            h.transferBusy=false;release.run();return;
                        }
                        final TopCandidate covered=intoMain?h.topCtrl.currentTopCandidate():null;
                        Runnable handback=()->h.returnToSystem(upper,intoMain,
                                intoMain && h.topCtrl.topTransition!=null,()->{
                            h.topCtrl.topTaskState.release(key,true);
                            if(upper.taskEpoch==binding && upper.taskId==taskId)h.clearSlot(upper);
                            h.topCtrl.topOriginalSlot=null;h.topCtrl.topFromMain=false;
                            if(intoMain) {
                                h.tabletReturnedTop=key;
                                h.tabletCoveredMain=covered!=null && !covered.key.equals(key)?covered:null;
                            } else h.topCtrl.preferMainAfterTopChange();
                            MiStepHost.log("upper horizontal swipe handed back task="+taskId+" front="+intoMain);
                            if(intoMain)h.topCtrl.revealTopTaskTransition();
                            release.run();
                        },error->{
                            h.topCtrl.cancelTopTaskTransition();
                            MiStepHost.log("upper horizontal handback stopped; binding retained: "+error);release.run();
                        });
                        // Keep the real task small during native ownership transfer,
                        // then expand that same canonical Surface in the main area.
                        if(!intoMain || !h.topCtrl.startLiveMainRevealTransition(handback))handback.run();
                    },MiStepHost::log);
            h.sideDismiss=run[0];h.sideDismissSlot=upper;
            MiStepClipboard.get(h.ctx).close();MiStepScanner.closeImmediately();
            run[0].follow(dx);
            return true;
        } catch(Throwable error) {
            if(h.sideDismiss!=null && h.sideDismissSlot==upper) {
                h.sideDismiss.close();h.sideDismiss=null;h.sideDismissSlot=null;
            }
            h.transferBusy=false;upper.promoting=upper.frozen;
            MiStepHost.log("upper horizontal preparation stopped: "+error);h.finishPendingSuspend();return false;
        }
    }

    static void moveTopDismiss(float dx) {
        MiStepHost h=MiStepHost.current();
        if(h!=null && h.sideDismiss!=null && h.sideDismissSlot==h.slots[MiStepHost.SLOT_TOP]) {
            h.topDismissDx=dx;h.sideDismiss.follow(dx);
        }
    }

    static void endTopDismiss(boolean cancelled) {
        MiStepHost h=MiStepHost.current();
        if(h==null || h.sideDismiss==null || h.sideDismissSlot!=h.slots[MiStepHost.SLOT_TOP])return;
        boolean commit=!cancelled && Math.signum(h.topDismissDx)==h.topDismissDirection
                && Math.abs(h.topDismissDx)>=Math.max(topDismissWidth(h)*.22f,HyperOsStyle.dp(h.ctx,72f));
        h.sideDismiss.settle(commit);
    }

    static boolean beginTopGesture() {
        MiStepHost h=MiStepHost.current();
        if(h==null || h.layout==null || h.layout.tabletLandscape || topGestureHeight()==0)return false;
        if(!h.topCtrl.topRoll.rolling())h.topCtrl.prepareLiveTopRoll();
        h.topCtrl.topSelectionPending=!h.topCtrl.topFace;
        MiStepClipboard.get(h.ctx).close(); MiStepScanner.closeImmediately();
        h.topCtrl.topRoll.beginGesture();
        return true;
    }

    static void moveTopGesture(float displacement, long time) {
        MiStepHost h=MiStepHost.current();
        if(h!=null && h.topCtrl.topRoll!=null)h.topCtrl.topRoll.moveGesture(displacement,time);
    }

    static void endTopGesture(boolean cancelled) {
        MiStepHost h=MiStepHost.current();
        if(h!=null && h.topCtrl.topRoll!=null)h.topCtrl.topRoll.finishGesture(cancelled);
    }

    void openTopFace(boolean manual) {
        if(!topReady() || topRoll.rolling() || topTransition!=null)return;
        if(topFace) {if(!h.slots[MiStepHost.SLOT_TOP].occupied)showTopCandidates(); return;}
        MiStepClipboard.get(h.ctx).close(); MiStepScanner.closeImmediately();
        topSelectionPending=manual; prepareLiveTopRoll(); topRoll.show(true);
    }

    private void topRollSettled(boolean taskFace) {
        if(h.root==null || h.scene!=MiStepHost.ACTIVE || !h.screenOn || h.keyguardLocked)return;
        if(h.layout.tabletLandscape) {
            topFace=true;topSelectionPending=false;
            // The drawer owns its visibility until its current animation settles.
            updateTopTaskGeometry();h.updateHint(h.slots[MiStepHost.SLOT_TOP]);h.root.requestLayout();return;
        }
        topFace=taskFace; topFaceGeneration++;
        h.topBar.setVisibility(taskFace?View.INVISIBLE:View.VISIBLE);
        // The empty SurfaceView can still clear its old hole while Shell finishes returning.
        // Paint the restored controls after it, including the now full-width adaptive hole.
        if(!taskFace)h.topBar.bringToFront();
        topTaskPane.setVisibility(taskFace?View.VISIBLE:View.INVISIBLE);
        if(!taskFace) {
            Slot upper=h.slots[MiStepHost.SLOT_TOP];
            if(upper.occupied) {
                Boolean mainOccupied=mainTaskOccupiedBeside(upper.taskId);
                boolean occupiedOrUnknown=mainOccupied==null || mainOccupied;
                if(TopTaskReturnPolicy.keepUpperTask(occupiedOrUnknown)) {
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

    private TopCandidate candidate(Object info, Slot source) {
        LiveTask task=new LiveTask(info);
        TopTaskState.Key key=new TopTaskState.Key(task.id,XposedHelpers.getIntField(info,"userId"),task.token);
        Boolean directed=topDirections.get(key);
        boolean landscape=directed!=null?directed:h.configurationOrientationOf(info)==2
                || FullscreenRotationPolicy.requestsLandscape(h.requestedOrientationOf(info));
        return new TopCandidate(task,key,source,landscape);
    }

    private TopCandidate currentTopCandidate() {
        try {
            LiveTask task=h.findMainTask();
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
            LiveTask mainTask=h.findMainTask();
            return mainTask!=null && mainTask.id!=upperTaskId;
        } catch(Throwable unavailable) {
            MiStepHost.log("upper return cannot confirm main task: "+unavailable);
            return null;
        }
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
        prepareTopViewport(selected,()->adoptTopTaskPrepared(selected,ticket,fromMain),
                error->topAdoptionFailed(selected,ticket,error));
    }

    private void prepareTopViewport(TopCandidate selected,Runnable ready,Consumer<String> failure) {
        final java.util.concurrent.atomic.AtomicBoolean completed=new java.util.concurrent.atomic.AtomicBoolean();
        final Runnable timeout=()->{
            if(completed.compareAndSet(false,true))failure.accept("窗口信息准备超时");
        };
        try {
            android.os.Bundle row=new android.os.Bundle();
            row.putBinder("owner",h.desktopSceneOwner);
            row.putBinder("task",(android.os.IBinder)XposedHelpers.callMethod(selected.task.token,"asBinder"));
            row.putInt("taskId",selected.key.taskId);row.putInt("userId",selected.key.userId);
            row.putParcelable("bounds",h.logicalBounds(h.slots[MiStepHost.SLOT_TOP]));
            h.main.postDelayed(timeout,1500L);
            h.ctx.sendOrderedBroadcast(new Intent(MiStepDesktopLaunchBridge.PREPARE_TOP).setPackage("android")
                    .putExtra("state",row),android.Manifest.permission.WRITE_SECURE_SETTINGS,
                    new BroadcastReceiver() {
                        @Override public void onReceive(Context c,Intent intent) {
                            if(!completed.compareAndSet(false,true))return;
                            h.main.removeCallbacks(timeout);
                            if(getResultCode()==-1)ready.run();else failure.accept("窗口信息准备被拒绝");
                        }
                    },h.main,0,null,null);
        } catch(Throwable error) {
            h.main.removeCallbacks(timeout);
            if(completed.compareAndSet(false,true))failure.accept("窗口信息准备失败："+error);
        }
    }

    private void adoptTopTaskPrepared(TopCandidate selected, TopTaskState.Ticket ticket, boolean fromMain) {
        if(!topTaskState.current(ticket,h.sceneEpoch)) {cancelTopTaskTransition();return;}
        h.adoptExisting(h.slots[MiStepHost.SLOT_TOP],selected.task,fromMain,()->{
            if(!topTaskState.commit(ticket,h.sceneEpoch)) {releaseTopTask(false,false,null);return;}
            topOriginalSlot=selected.source;topFromMain=fromMain;
            topTaskPane.occupied(true);
            updateTopTaskGeometry();h.applyFullscreenBounds(h.slots[MiStepHost.SLOT_TOP]);h.applySlotScale(h.slots[MiStepHost.SLOT_TOP]);
            h.publishDesktopSideTasks();
            h.root.requestLayout();
            MiStepHost.log("upper task admitted task="+selected.key.taskId+" user="+selected.key.userId+" original="+(selected.source==null?"main":selected.source.index));
            restoreTabletCoveredMain(selected.key);
            revealTopTaskTransition();
            preferMainAfterTopChange();
            h.finishPendingSuspend();
        },error->topAdoptionFailed(selected,ticket,error));
    }

    private void topAdoptionFailed(TopCandidate selected,TopTaskState.Ticket ticket,String error) {
            cancelTopTaskTransition();
            topTaskState.cancel(ticket);topTaskPane.message("横屏接入失败，正在归还原任务");
            MiStepHost.log("upper adoption failed: "+error);
            Slot origin=selected.source;
            if(origin!=null && !origin.occupied && topReady() && !h.suspendRequested)
                h.adoptExisting(origin,selected.task,()->{h.finishPendingSuspend();},failure->{MiStepHost.log("upper original slot restore: "+failure);h.finishPendingSuspend();});
            else h.finishPendingSuspend();
    }

    private void restoreTabletCoveredMain(TopTaskState.Key collected) {
        final TopCandidate covered=h.tabletCoveredMain;
        boolean returning=collected.equals(h.tabletReturnedTop);
        h.tabletCoveredMain=null;h.tabletReturnedTop=null;
        if(!h.layout.tabletLandscape || !returning || covered==null)return;
        final long epoch=h.sceneEpoch;
        h.executeOnShell(()->{
            if(h.scene!=MiStepHost.ACTIVE || h.sceneEpoch!=epoch || h.suspendRequested
                    || !collected.equals(topTaskState.owner()))return;
            try {
                Object fresh=h.rootInfo(covered.task);
                if(!covered.task.matches(fresh) || !h.ordinaryRoot(fresh,1)
                        || XposedHelpers.getIntField(fresh,"userId")!=covered.key.userId
                        || h.isSideToken(covered.task.token))return;
                Object wct=Class.forName("android.window.WindowContainerTransaction")
                        .getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(wct,"setHidden",covered.task.token,false);
                XposedHelpers.callMethod(wct,"reorder",covered.task.token,true);
                if(h.applyWct("tablet-top-covered-main",wct))
                    MiStepHost.log("tablet covered main restored task="+covered.key.taskId);
            } catch(Throwable unavailable) {MiStepHost.log("tablet covered main no longer available: "+unavailable);}
        });
    }

    void releaseTopTask(boolean front, boolean dismissed, Runnable after) {
        releaseTopTask(front,dismissed,after,false);
    }

    private void releaseTopTask(boolean front, boolean dismissed, Runnable after,
            boolean liveMainReveal) {
        final Slot upper=h.slots[MiStepHost.SLOT_TOP];
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
        Slot upper=h.slots[MiStepHost.SLOT_TOP];
        if(!upper.occupied) {cancelTopTaskTransition();if(after!=null)after.run();return;}
        if(h.scene!=MiStepHost.ACTIVE || h.promotion!=null || h.transferBusy || upper.promoting) {cancelTopTaskTransition();return;}
        final LiveTask task=new LiveTask(upper);
        final TopTaskState.Key key=topTaskState.owner();
        final Slot origin=topOriginalSlot;
        boolean mainOccupied=false;
        if(front && topFace && topRoll!=null) {
            try {
                LiveTask mainTask=h.findMainTask();
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
                    else if(animateBackToMain) {prepareLiveTopRoll();topRoll.show(false);}
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
                LiveTask target=h.findMainTask();
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

package com.jiusis.mistep;

import android.content.Intent;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import java.util.function.Consumer;
import de.robv.android.xposed.XposedHelpers;

/** 工作区生命周期子系统（v1.2 拆分）：suspend/resume/park 链、入场动画、
 *  键盘锁隐私态。自 MiStepHost 原样搬出；宿主保留同名 shim。 */
final class HostLifecycleController {
    private final MiStepHost h;

    HostLifecycleController(MiStepHost host) { h = host; }

    /** 等 TaskView 就绪再启动；宿主刚建时窗口可能还没 attach。 */
    void launchWhenReady(int slot, String pkg, int attempt) {
        if (slot <= MiStepHost.SLOT_MAIN || slot >= MiStepHost.SLOT_COUNT) {
            return;
        }
        if (h.slots[slot].view == null && attempt < 8) {
            h.main.postDelayed(() -> launchWhenReady(slot, pkg, attempt + 1), 400L);
            return;
        }
        h.launchPackage(slot, pkg);
    }

    void toggleVisibility() {
        h.main.post(() -> {
            if (h.scene == MiStepHost.SUSPENDED) resumeSession();
            else if (h.scene == MiStepHost.ACTIVE) suspendSession("exit");
            else if (h.scene == MiStepHost.RESUMING) suspendSession("exit"); // h.suspendSession latches h.suspendRequested during MiStepHost.RESUMING.
            else MiStepHost.log("toggle ignored while MiStepHost.SUSPENDING; exit already in progress");
        });
    }

    /** Transfer live tasks first. Removing a SurfaceView while still bound hides its task. */
    void suspendSession(String reason) {
        if (h.topBar != null) h.topBar.closeQuickAppPicker();
        if (!"native-landscape".equals(reason)) h.landscapeSuspended = false;
        h.statusBar.restore(); // Includes a deferred exit while native ownership is changing.
        MiStepClipboard.get(h.ctx).close();
        if (h.root == null) return;
        if (h.scene == MiStepHost.RESUMING) {
            h.suspendRequested = true;
            if (!h.workspaceEntering) return; // A native readoption still owns its callback.
            h.workspaceEntering = false;
            h.workspaceGeneration++; // Cancel a pending first-frame entry before it can shrink again.
            h.scene = MiStepHost.ACTIVE;
        }
        if (h.scene != MiStepHost.ACTIVE) return;
        if (h.promotion != null || h.transferBusy) {
            h.suspendRequested = true;
            if (h.cellMotion != null) h.cellMotion.finish();
            if (h.sideDismiss != null && h.sideDismiss.isTracking()) h.sideDismiss.settle(false);
            MiStepHost.log("suspend queued after task transfer: " + reason);
            if (h.promotion != null && h.promotion.nativeSwap)
                h.failPromotion(h.promotion, "suspend requested: " + reason);
            return;
        }
        for (Slot slot : h.slots) {
            if (slot.launching) {
                h.suspendRequested = true;
                MiStepHost.log("suspend queued after launch: " + reason);
                return;
            }
        }
        h.scene = MiStepHost.SUSPENDING;
        h.sceneEpoch++;
        h.resetTopForSuspend();
        h.publishDesktopSideTasks();
        h.suspendRequested = false;
        h.closeSwapPreview();
        h.closeCellMovePreview();
        // fullscreen-only 的镜像承载任务仍在主区：退出前先移回，避免下次进入是一块黑屏。
        h.restoreCarrierToMain();
        if (h.mainBackdrop != null) {
            h.mainBackdrop.recycle();
            h.mainBackdrop = null;
        }
        h.hideIme();
        h.workspaceEntering = false;
        final long exitAnimation = ++h.workspaceGeneration;
        if (h.screenOn) h.writeZoomAnimated(false); else h.writeZoom(false);
        h.setZoomState(false);
        MiStepHost.log("suspend begin; retain live task identities: " + reason);
        final long exitEpoch = h.sceneEpoch;
        Runnable returnTasks = () -> {
            if (h.scene == MiStepHost.SUSPENDING && h.sceneEpoch == exitEpoch)
                returnUnboundOwnedCandidates(exitEpoch, () -> parkNext(1));
        };
        if (h.screenOn) playWorkspaceFade(false, exitAnimation, returnTasks);
        else {
            android.animation.ValueAnimator previous = h.workspaceAnimator;
            h.workspaceAnimator = null;
            if (previous != null) previous.cancel();
            setWorkspaceAlpha(0f);
            returnTasks.run();
        }
    }

    /** A pending swap can leave A outside the saved slot; it remains in the recovery ledger. */
    void returnUnboundOwnedCandidates(long epoch, Runnable complete) {
        OwnedTask next = null;
        for (OwnedTask candidate : h.ownedTasks.values()) {
            if (!candidate.recoveryCandidate) continue;
            boolean inSlot = false;
            for (Slot slot : h.slots) if (slot.index > 0 && slot.occupied
                    && candidate.token.equals(slot.taskToken)) { inSlot = true; break; }
            if (!inSlot) { next = candidate; break; }
        }
        if (next == null) { complete.run(); return; }
        returnUnboundOwnedCandidate(next, epoch, 0, complete);
    }

    void returnUnboundOwnedCandidate(OwnedTask candidate, long epoch, int attempt, Runnable complete) {
        if (h.scene != MiStepHost.SUSPENDING || h.sceneEpoch != epoch || h.ownedTasks.get(candidate.token) != candidate) return;
        if (!h.executeOnShell(() -> {
            if (h.scene != MiStepHost.SUSPENDING || h.sceneEpoch != epoch || h.ownedTasks.get(candidate.token) != candidate) return;
            boolean ready = false;
            String error = "owned candidate not yet returned";
            try {
                Object controller = candidate.recoveryController;
                if (controller == null || candidate.userId < 0) throw new IllegalStateException("candidate owner/user absent");
                LiveTask identity = new LiveTask(candidate.id, candidate.token, null);
                Object organizer = XposedHelpers.getObjectField(controller, "mTaskOrganizer");
                Object core = h.rootInfo(identity);
                Object appeared;
                synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                    appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                            organizer, "mTasks")).get(candidate.id);
                    if (appeared != null && !identity.matches(XposedHelpers.callMethod(appeared, "getTaskInfo")))
                        throw new IllegalStateException("candidate canonical token changed");
                }
                if (core == null) {
                    if (appeared != null) throw new IllegalStateException("candidate still in organizer outside h.root list");
                    Object pending = XposedHelpers.getObjectField(controller, "mPendingInfo");
                    if (candidate.token.equals(XposedHelpers.getObjectField(controller, "mTaskToken"))
                            || (pending != null && identity.matches(pending)))
                        throw new IllegalStateException("candidate still referenced by its controller");
                    ready = true; // Exact task is gone from both sources, never infer death from roots alone.
                } else {
                    if (!identity.matches(core) || XposedHelpers.getIntField(core, "userId") != candidate.userId
                            || (!h.ordinaryRoot(core, 1) && !h.ordinaryRoot(core, 6)))
                        throw new IllegalStateException("candidate identity/user/display/role changed");
                    Slot recovery = new Slot(-1);
                    recovery.controller = controller; recovery.taskId = candidate.id;
                    recovery.taskToken = candidate.token;
                    Promotion handback = new Promotion(recovery, new Slot[0]);
                    handback.background = true; handback.candidateRecovery = true;
                    if (attempt == 0 || h.ordinaryRoot(core, 6)) {
                        if (!h.applyWct("return-unbound-owned-candidate", h.promotionController.fullscreenExitTransaction(handback)))
                            throw new IllegalStateException("candidate fullscreen transaction refused");
                    } else {
                        h.promotionController.handoffPromotedTask(handback);
                        ready = handback.handoffComplete;
                    }
                }
            } catch (Throwable failure) { error = failure.toString(); }
            final boolean returned = ready;
            final String detail = error;
            h.main.post(() -> {
                if (h.scene != MiStepHost.SUSPENDING || h.sceneEpoch != epoch || h.ownedTasks.get(candidate.token) != candidate) return;
                if (returned) {
                    h.ownedTasks.remove(candidate.token);
                    MiStepHost.log("unbound owned candidate returned alive task=" + candidate.id);
                    returnUnboundOwnedCandidates(epoch, complete);
                } else if (attempt < 3) {
                    h.main.postDelayed(() -> returnUnboundOwnedCandidate(candidate, epoch, attempt + 1, complete), 350L);
                } else {
                    // Preserve the exact A/B ledger and attached surfaces for a later explicit exit.
                    h.scene = MiStepHost.ACTIVE;
                    if (h.screenOn && !h.keyguardLocked) { setWorkspaceAlpha(1f); h.writeZoom(true); h.setZoomState(true); }
                    MiStepHost.log("suspend stopped; unresolved owned candidate retained task=" + candidate.id + ": " + detail);
                }
            });
        })) {
            h.scene = MiStepHost.ACTIVE;
            if (h.screenOn && !h.keyguardLocked) { setWorkspaceAlpha(1f); h.writeZoom(true); h.setZoomState(true); }
            MiStepHost.log("suspend stopped; owned candidate Shell executor unavailable task=" + candidate.id);
        }
    }

    void parkNext(int index) {
        if (index >= MiStepHost.SLOT_COUNT) {
            releaseWorkspaceBackdrop();
            h.root.setVisibility(View.GONE);
            h.setMirrorLayerVisible(false);
            h.scene = MiStepHost.SUSPENDED;
            h.publishDesktopSideTasks();
            MiStepHost.log("host suspended; all side tasks returned alive: " + h.describeTasks());
            if(DeviceProfile.isTablet(h.ctx))h.scheduleDisplayGeometryRefresh();
            return;
        }
        Slot slot = h.slots[index];
        if (!slot.occupied || slot.parked || slot.taskToken == null) {
            parkNext(index + 1);
            return;
        }
        h.returnToSystem(slot, false, () -> {
            if(index==MiStepHost.SLOT_TOP) {
                h.topCtrl.onParkSuccessAtTop();
            }
            parkNext(index + 1);
        }, error -> {
            // Some peers may already be parked. Restore them before exposing MiStepHost.ACTIVE again.
            h.scene = MiStepHost.RESUMING;
            setWorkspaceAlpha(1f);
            h.writeZoom(true);
            h.setZoomState(true);
            MiStepHost.log("suspend incomplete; restore parked peers before resuming: " + error);
            resumeNext(1, h.sceneEpoch);
        });
    }

    void resumeSession() {
        if (h.scene != MiStepHost.SUSPENDED || !h.screenOn || h.root == null || !MiStepEntryGuard.canOpen(h.ctx)) return;
        h.landscapeSuspended = false;
        h.scene = MiStepHost.RESUMING;
        final long epoch = ++h.sceneEpoch;
        // Read the foreground before showing our overlay or reclaiming any saved task.
        // A desktop launch while suspended is the user's new main-task choice.
        if (!h.executeOnShell(() -> {
            try {
                final java.util.Set<Object> foreground = new java.util.HashSet<>();
                for (Object info : h.rootInfos()) {
                    if (RetainedTaskRestorePolicy.preserveAsMain(h.ordinaryRoot(info, 1),
                            XposedHelpers.getBooleanField(info, "isVisible"),
                            XposedHelpers.getBooleanField(info, "isFocused"))) {
                        foreground.add(XposedHelpers.getObjectField(info, "token"));
                    }
                }
                h.main.post(() -> {
                    if (h.scene != MiStepHost.RESUMING || h.sceneEpoch != epoch) return;
                    for (Slot saved : h.slots) {
                        if (saved.parked && saved.occupied && saved.taskToken != null
                                && !RetainedTaskRestorePolicy.reclaim(
                                        saved.occupied, saved.parked, saved.taskToken, foreground)) {
                            int id = saved.taskId;
                            TaskWindowState state = saved.windowState;
                            saved.windowState = null;
                            h.clearSlot(saved); // Forget the slot; never remove or move the live task.
                            if (state != null) { state.slotIndex = MiStepHost.SLOT_MAIN; state.active = true; }
                            MiStepHost.log("resume kept desktop foreground task=" + id + " in main; old cell released");
                        }
                    }
                    showResumingSession(epoch);
                });
            } catch (Throwable error) {
                h.main.post(() -> {
                    if (h.scene != MiStepHost.RESUMING || h.sceneEpoch != epoch) return;
                    h.scene = MiStepHost.SUSPENDED;
                    MiStepHost.log("resume stopped before reclaiming tasks: " + error);
                });
            }
        })) { h.scene = MiStepHost.SUSPENDED; MiStepHost.log("resume stopped: no Shell queue"); }
    }

    void showResumingSession(long epoch) {
        if (h.scene != MiStepHost.RESUMING || h.sceneEpoch != epoch) return;
        if (h.retainedForLock) { h.keyguardResumeIndex = 1; return; }
        if (!MiStepEntryGuard.canOpen(h.ctx)) { h.scene = MiStepHost.SUSPENDED; return; }
        if (DeviceProfile.isTablet(h.ctx)) h.refreshDisplayGeometry();
        h.consumePendingMainSide();
        setWorkspaceAlpha(0f);
        h.root.setVisibility(View.VISIBLE);
        h.setMirrorLayerVisible(true);
        h.main.postDelayed(h::collapseStatusBarPanels, 220L);
        beginWorkspaceEntry(() -> {
            if (h.scene == MiStepHost.RESUMING && h.sceneEpoch == epoch) resumeNext(1, epoch);
        });
    }

    void resumeNext(int index, long epoch) {
        if (h.scene != MiStepHost.RESUMING || epoch != h.sceneEpoch) return;
        if (h.retainedForLock) { h.keyguardResumeIndex = index; return; }
        if (h.suspendRequested || !h.screenOn) {
            h.scene = MiStepHost.ACTIVE;
            suspendSession("navigation-during-resume");
            return;
        }
        if (index >= MiStepHost.SLOT_COUNT) {
            h.scene = MiStepHost.ACTIVE;
            h.topCtrl.configureTabletTools();
            h.publishDesktopSideTasks();
            h.requestCompaction();
            if (h.topBar != null) h.topBar.applyOptions(h.workspaceOptions);
            h.refreshQuickApps();
            h.reassertSideTaskViews("resume complete");
            MiStepHost.log("host resumed from retained task identities: " + h.describeTasks());
            finishPendingSuspend();
            return;
        }
        Slot slot = h.slots[index];
        if (!slot.occupied || !slot.parked) { resumeNext(index + 1, epoch); return; }
        LiveTask saved = new LiveTask(slot);
        h.adoptExisting(slot, saved, () -> resumeNext(index + 1, epoch), error -> {
            // External task removal is a real empty cell; a failed admission never launches it.
            h.updateHint(slot);
            MiStepHost.log("resume task unavailable; keep existing system task, no relaunch: " + error);
            resumeNext(index + 1, epoch);
        });
    }

    void finishPendingSuspend() {
        if (h.suspendRequested && h.scene == MiStepHost.ACTIVE && h.promotion == null && !h.transferBusy) {
            h.suspendRequested = false;
            suspendSession("deferred");
        }
    }

    /** Wait for the real Host buffer/wallpaper before shrinking the existing main surface. */
    void beginWorkspaceEntry(Runnable ready) {
        final long generation = ++h.workspaceGeneration;
        h.workspaceEntering = true;
        h.zoomAnimUntil = SystemClock.uptimeMillis() + 1000L;
        setWorkspaceAlpha(0f);
        awaitWorkspaceFrame(generation, 0, ready);
    }

    private void prepareWorkspaceBackdrop(long generation, int attempt, Runnable ready) {
        if(generation!=h.workspaceGeneration || h.root==null || h.scene!=MiStepHost.ACTIVE && h.scene!=MiStepHost.RESUMING)return;
        if(h.layout.tabletLandscape && h.wallpaperBackdrop==null && attempt<24) {
            h.main.postDelayed(()->prepareWorkspaceBackdrop(generation,attempt+1,ready),16L);return;
        }
        if (!h.layout.tabletLandscape || h.workspaceBackdrop != null || h.wallpaperBackdrop == null) {
            ready.run(); return;
        }
        final View owner=h.root;
        MiStepWorkspaceBackdrop.acquire(h.ctx, h.main, h.root.getWindowToken(), h.wallpaperBackdrop,
                h.screenW, h.screenH, backdrop -> {
                    if (generation!=h.workspaceGeneration || h.root!=owner || !h.screenOn || h.keyguardLocked
                            || h.scene!=MiStepHost.ACTIVE && h.scene!=MiStepHost.RESUMING) {
                        if(backdrop!=null)backdrop.close();return;
                    }
                    h.workspaceBackdrop=backdrop;
                    if(backdrop!=null) {
                        h.workspaceBackdropOwner=owner;
                        h.workspaceBackdropDetach=new View.OnAttachStateChangeListener() {
                        @Override public void onViewAttachedToWindow(View view) {}
                        @Override public void onViewDetachedFromWindow(View view) {
                            if(h.workspaceBackdrop==backdrop)releaseWorkspaceBackdrop();
                        }
                        };
                        owner.addOnAttachStateChangeListener(h.workspaceBackdropDetach);
                    }
                    ready.run();
                }, MiStepHost::log);
    }

    private void releaseWorkspaceBackdrop() {
        if(h.workspaceBackdropOwner!=null && h.workspaceBackdropDetach!=null)
            h.workspaceBackdropOwner.removeOnAttachStateChangeListener(h.workspaceBackdropDetach);
        h.workspaceBackdropOwner=null;h.workspaceBackdropDetach=null;
        if(h.workspaceBackdrop!=null)h.workspaceBackdrop.close();
        h.workspaceBackdrop=null;
    }

    void awaitWorkspaceFrame(long generation, int attempt, Runnable ready) {
        if (generation != h.workspaceGeneration || h.root == null || !h.screenOn || h.keyguardLocked
                || h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING) return;
        if (h.wallpaperBackdrop == null && attempt < 24) {
            h.main.postDelayed(() -> awaitWorkspaceFrame(generation, attempt + 1, ready), 16L);
            return;
        }
        FrameLayout enteringRoot = h.root;
        Runnable start = () -> h.main.post(() -> {
            if (generation != h.workspaceGeneration || h.root != enteringRoot || !h.screenOn || h.keyguardLocked
                    || h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING) return;
            // WM must see this Host's first buffer before validating its visible
            // window and lending the physical wallpaper parent. Reversing these
            // gates rejected every first entry and exposed an empty surround.
            prepareWorkspaceBackdrop(generation, 0, () -> {
                if (generation != h.workspaceGeneration || h.root != enteringRoot || !h.screenOn || h.keyguardLocked
                        || h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING) return;
                h.writeZoomAnimated(true);
                h.setZoomState(true);
                h.scheduleMainCropClear(900L);
                playWorkspaceFade(true, generation, ready);
            });
        });
        if (h.root.isAttachedToWindow() && h.root.isHardwareAccelerated()) {
            h.root.getViewTreeObserver().registerFrameCommitCallback(start);
            h.root.invalidate();
        } else start.run();
    }

    /** Fade Host controls and real TaskView surface parents; task buffers are never copied. */
    void setWorkspaceAlpha(float value) {
        h.workspaceAlpha = Math.max(0f, Math.min(1f, value));
        if (h.topBar != null) h.topBar.setAlpha(h.workspaceAlpha);
        if (h.tabletTools != null) h.tabletTools.setAlpha(h.workspaceAlpha);
        if (h.topCtrl.topTaskPane != null) h.topCtrl.topTaskPane.setAlpha(h.workspaceAlpha);
        boolean movingBackdrop = h.layout != null && h.layout.tabletLandscape;
        android.graphics.RectF hole = movingBackdrop ? workspaceMainHole() : null;
        updateWorkspaceBacking(h.topStripBacking, hole);
        updateWorkspaceBacking(h.mainGapBacking, hole);
        Object tx = null;
        try {
            for (int i = 1; i < MiStepHost.SLOT_COUNT; i++) {
                updateWorkspaceBacking(h.sideBackingViews[i], hole);
                Slot slot = h.slots[i];
                if (slot == null) continue;
                LiveSwapVisualLease.writeAlpha(slot.hint,
                        h.taskCellsCovered && slot.index < h.CELL_SLOT_COUNT ? 0f : h.workspaceAlpha);
                if (slot.hint != null && slot.hint.getBackground() instanceof WallpaperCropDrawable)
                    ((WallpaperCropDrawable) slot.hint.getBackground()).setMainHole(hole);
                if (slot.hint != null && slot.hint.getBackground()!=null)
                    slot.hint.getBackground().setAlpha(255);
                if (!ownsCellPresentation(slot)) continue;
                boolean visible = cellPresentationVisible(slot);
                if (slot.view != null) slot.view.setAlpha(visible ? 1f : 0f);
                Object surface = h.getSurfaceField(slot.controller, "mSurfaceControl");
                if (surface == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(surface, "isValid"))) continue;
                if (tx == null) tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(tx, "setAlpha", surface, visible ? h.workspaceAlpha : 0f);
            }
            if (tx != null) XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { MiStepHost.log("workspace surface fade: " + error); }
        finally { h.closeSurfaceObject(tx, "close"); }
    }

    android.graphics.RectF workspaceMainHole() {
        float scale = 1f + (h.layout.mainScale - 1f) * h.workspaceAlpha;
        float x = h.layout.mainOffsetX * h.workspaceAlpha, y = h.layout.mainOffsetY * h.workspaceAlpha;
        return new android.graphics.RectF(x, y, x + h.screenW * scale, y + h.screenH * scale);
    }

    /** Keep wallpaper opaque outside the moving main task; never fade to the empty display. */
    void updateWorkspaceBacking(View view, android.graphics.RectF hole) {
        if (view == null) return;
        // Keep the editable cutout wallpaper visible throughout the real-task zoom.
        // The native wallpaper parent can gain its first composited buffer a frame
        // or more after acquisition; hiding this fallback exposed a black surround.
        boolean wallpaper = view.getBackground() instanceof WallpaperCropDrawable;
        // A cold Host still has plain page-color placeholders. They cannot cut
        // out the full-size main task, so keep them hidden until entry fades in.
        view.setAlpha(hole != null && wallpaper ? 1f : h.workspaceAlpha);
        if (wallpaper)
            ((WallpaperCropDrawable) view.getBackground()).setMainHole(hole);
    }

    boolean ownsCellPresentation(Slot slot) {
        // Shell owns the borrowed task parents until the whole handoff finishes.
        // clearSlot/bindTask is only a logical replacement during a native swap;
        // an independent hide here would blank the just-committed real task.
        return !slot.promoting && !slot.launching && h.cellMotion == null
                && !(h.promotion != null && h.promotion.nativeSwap && !h.promotion.cancelled);
    }

    boolean cellPresentationVisible(Slot slot) {
        return !(h.taskCellsCovered && slot.index < h.CELL_SLOT_COUNT)
                && slot.occupied && !slot.parked && slot.taskToken != null
                && h.controllerOwns(slot, slot.taskId, slot.taskToken)
                && (slot.index!=MiStepHost.SLOT_TOP || h.topCtrl.topFace
                        || (h.topCtrl.topRoll!=null && (h.topCtrl.topRoll.rolling() || h.topCtrl.topRoll.showingTask())));
    }

    void playWorkspaceFade(boolean entering, long generation, Runnable complete) {
        android.animation.ValueAnimator previous = h.workspaceAnimator;
        h.workspaceAnimator = null;
        if (previous != null) previous.cancel();
        final float from = entering ? 0f : h.workspaceAlpha;
        android.animation.ValueAnimator animator = android.animation.ValueAnimator.ofFloat(from, entering ? 1f : 0f);
        animator.setDuration(MiStepHost.ZOOM_ANIM_MS);
        animator.setInterpolator(new android.view.animation.PathInterpolator(.34f, .69f, .1f, 1f));
        animator.addUpdateListener(value -> {
            if (h.workspaceAnimator == animator && generation == h.workspaceGeneration)
                setWorkspaceAlpha((Float) value.getAnimatedValue());
        });
        animator.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(android.animation.Animator value) {
                if (h.workspaceAnimator != animator || generation != h.workspaceGeneration) return;
                h.workspaceAnimator = null;
                if (entering) h.workspaceEntering = false;
                setWorkspaceAlpha(entering ? 1f : 0f);
                if (complete != null) complete.run();
            }
        });
        h.workspaceAnimator = animator;
        if (h.layout.tabletLandscape) animator.setStartDelay(Math.max(0L,
                h.workspaceZoomStart - SystemClock.uptimeMillis()));
        animator.start();
        if (h.layout.tabletLandscape && SystemClock.uptimeMillis() > h.workspaceZoomStart)
            animator.setCurrentPlayTime(Math.min(MiStepHost.ZOOM_ANIM_MS,
                    SystemClock.uptimeMillis() - h.workspaceZoomStart));
    }

    /** Hide the overlay window without detaching TaskViews or handing their tasks back. */
    boolean setKeyguardWindowHidden(boolean hidden) {
        if (hidden == h.keyguardWindowHidden) return true;
        if (h.root == null || h.wm == null || h.lp == null) return false;
        float previousAlpha = h.lp.alpha;
        int previousFlags = h.lp.flags;
        if (hidden) {
            h.keyguardWindowAlpha = h.lp.alpha;
            h.keyguardWindowNotTouchable = (h.lp.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0;
            h.lp.alpha = 0f;
            h.lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            h.lp.alpha = h.keyguardWindowAlpha;
            if (!h.keyguardWindowNotTouchable) h.lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        try {
            h.wm.updateViewLayout(h.root, h.lp);
            h.keyguardWindowHidden = hidden;
            h.root.requestLayout();
            return true;
        } catch (Throwable error) {
            h.lp.alpha = previousAlpha;
            h.lp.flags = previousFlags;
            MiStepHost.log("lock window update failed: " + error);
            return false;
        }
    }

    boolean retainWorkspaceForLock() {
        if (h.retainedForLock) {
            h.keyguardLocked = true;
            h.writeZoom(false);
            h.setZoomState(false);
            return setKeyguardWindowHidden(true);
        }
        if (h.root == null || (h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING)) return false;
        h.keyguardLocked = true;
        h.retainedForLock = true;
        h.keyguardResumeIndex = h.scene == MiStepHost.RESUMING && h.workspaceEntering ? 1 : -1;
        h.workspaceEntering = false;
        h.workspaceGeneration++;
        android.animation.ValueAnimator previous = h.workspaceAnimator;
        h.workspaceAnimator = null;
        if (previous != null) previous.cancel();
        if (h.topBar != null) h.topBar.closeQuickAppPicker();
        MiStepClipboard.get(h.ctx).close();
        h.publishDesktopSideTasks();
        h.statusBar.restore();
        h.hideIme();
        h.writeZoom(false);
        h.setZoomState(false);
        if (!setKeyguardWindowHidden(true)) {
            h.retainedForLock = false;
            return false;
        }
        h.setMirrorLayerVisible(false);
        h.retainedWindowPrepared = false;
        final long generation = h.retainedWindowGeneration = System.currentTimeMillis();
        final long epoch = h.sceneEpoch;
        final View owner = h.root;
        MiStepRetainedWindow.request(h.ctx, h.main, h.root.getWindowToken(), generation,
                h.layout.mainScale, h.layout.mainOffsetX, h.layout.mainOffsetY, hidden -> {
            if (!hidden || MiStepHost.sInstance != h || h.root != owner || h.sceneEpoch != epoch
                    || h.retainedWindowGeneration != generation || !h.retainedForLock
                    || !h.keyguardWindowHidden || MiStepEntryGuard.canOpen(h.ctx)) return;
            // WMS has confirmed that this exact window is hidden by native policy.
            // Keep its live buffer drawn at normal alpha behind that hidden parent.
            float oldAlpha = h.lp.alpha;
            h.lp.alpha = h.keyguardWindowAlpha;
            try {
                h.wm.updateViewLayout(h.root, h.lp);
                h.retainedWindowPrepared = true;
                MiStepHost.log("native-hidden retained Host buffer warmed without showing window");
            } catch (Throwable error) { h.lp.alpha = oldAlpha; MiStepHost.log("retained buffer warm stopped: " + error); }
        });
        MiStepHost.log("workspace retained for lock; native lockscreen zoom restored");
        return true;
    }

    void leaveKeyguardPrivacy() {
        if (h.unlockHandoff != null && h.unlockHandoff.active()) return;
        if (!MiStepEntryGuard.canOpen(h.ctx)) return;
        // USER_PRESENT can precede SCREEN_ON. The native guard already confirms
        // an interactive, unlocked display before updating our broadcast cache.
        if (h.retainedForLock) h.screenOn = true;
        if (!h.screenOn) return;
        boolean restore = h.retainedForLock;
        h.keyguardLocked = false;
        if (restore && h.root != null && (h.scene == MiStepHost.ACTIVE || h.scene == MiStepHost.RESUMING)) {
            // The real workspace stayed attached. Restore its steady geometry
            // before revealing it; unlocking is not a new animated entry.
            h.workspaceGeneration++;
            h.workspaceEntering = false;
            h.zoomAnimUntil = 0L;
            setWorkspaceAlpha(1f);
            restoreRetainedWorkspaceSurfaces();
            if (!h.retainedWindowPrepared) h.writeZoom(true);
            // With a warm buffer, WMS restores the real main geometry in the very
            // transaction which natively shows this Host WindowState.
            h.setZoomState(true);
        }
        if (!setKeyguardWindowHidden(false)) {
            if (restore) {
                h.keyguardLocked = true;
                h.writeZoom(false);
                h.setZoomState(false);
            }
            return;
        }
        h.retainedForLock = false;
        h.publishDesktopSideTasks();
        if (restore && h.root != null && (h.scene == MiStepHost.ACTIVE || h.scene == MiStepHost.RESUMING)) {
            final int resumeIndex = h.keyguardResumeIndex;
            h.keyguardResumeIndex = -1;
            h.setMirrorLayerVisible(true);
            if (h.scene == MiStepHost.RESUMING && resumeIndex >= 1) resumeNext(resumeIndex, h.sceneEpoch);
            else if (h.scene == MiStepHost.ACTIVE) h.reassertSideTaskViews("unlock retained workspace");
            MiStepHost.log("unlock restored retained workspace without entry animation: " + h.describeTasks());
        }
        h.updateTopBarState();
        if (h.scene == MiStepHost.ACTIVE && h.screenOn) {
            for (Slot slot : h.slots) {
                if (slot.occupied && !slot.promoting) {
                    h.reapplyScale(slot);
                    h.scheduleSideVisibilityRepair(slot);
                }
            }
        }
        MiStepHost.log("keyguard privacy cleared");
    }

    /** Restore existing task parents before the retained overlay becomes visible. */
    private void restoreRetainedWorkspaceSurfaces() {
        if (!h.retainedForLock || !h.keyguardWindowHidden || h.keyguardLocked || !h.screenOn
                || h.root == null || (h.scene != MiStepHost.ACTIVE && h.scene != MiStepHost.RESUMING)) return;
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
            h.appendWorkspaceSurfaces(tx, java.util.Collections.singletonList(h.sceneEpoch));
            XposedHelpers.callMethod(tx, "apply");
        } catch (Throwable error) { MiStepHost.log("retained unlock surface restore failed: " + error); }
        finally { h.closeSurfaceObject(tx, "close"); }
    }
}

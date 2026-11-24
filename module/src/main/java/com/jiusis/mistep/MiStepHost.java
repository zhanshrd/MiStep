package com.jiusis.mistep;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Region;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.PathInterpolator;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * v1.6.1：核对原生任务/表面绑定，确认脱离后清槽，退出恢复遗留副任务。
 * 宿主不参与显示缩放，保持正常物理坐标；主区画面与触摸均让给下层系统。
 *
 * 调试广播：com.jiusis.mistep.TOGGLE / com.jiusis.mistep.DEBUG(slot,pkg,swap)。
 */
public final class MiStepHost {
    static final String LANDSCAPE_MODE_ACTION = "com.jiusis.mistep.LANDSCAPE_MODE";

    static final String DESKTOP_ICON_LAUNCH_ACTION =
            "com.jiusis.mistep.DESKTOP_ICON_LAUNCH";

    private static final String TAG = "MiStepProbe";
    static final int SLOT_MAIN = 0;
    int CELL_SLOT_COUNT = 1 + MiStepLayout.CELL_COUNT;
    static final int SLOT_TOP = 1 + MiStepLayout.MAX_CELL_COUNT;
    static final int SLOT_COUNT = SLOT_TOP + 1;
    static final String TOP_TASK_STATE_ACTION = "com.jiusis.mistep.TOP_TASK_STATE";
    static final long LAUNCH_TIMEOUT_MS = 4000L;
    static final long SIDE_PROMOTION_TIMEOUT_MS = 3500L;

    static volatile MiStepHost sInstance;

    final Context ctx;
    final Executor executor;
    final Object factory;
    final Object transitions;
    final Handler main = new Handler(Looper.getMainLooper());

    /** 滚动行停稳后会被重新映射（HostSideScrollController.mapSideSlots），故非 final；读 volatile 快照。 */
    volatile Slot[] slots = new Slot[SLOT_COUNT];

    volatile MiStepLayout layout;
    FrameLayout root;
    WindowManager wm;
    WindowManager.LayoutParams lp;
    DisplayManager displayManager;
    DisplayManager.DisplayListener displayListener;
    volatile int displayRotation = -1;
    boolean geometryRefreshQueued;
    int geometryRefreshDeferrals;
    boolean geometryRefreshAfterLive;
    private int lpY = 0;
    boolean zoomed;
    volatile int screenW = 1080;
    volatile int screenH = 2460;
    final Object taskUsersLock = new Object();
    final Object taskWindowStatesLock = new Object();
    final java.util.Map<Integer, TaskWindowState> taskWindowStates =
            new java.util.HashMap<>();
    volatile java.util.Set<Integer> taskUserIds;
    volatile long taskUserIdsReadAt;
    volatile String taskUserIdsReadError;
    volatile boolean screenOn = true;
    Slot launchCurrentSlot;
    Promotion promotion;
    SwapPreview swapPreview;
    Object swapPreviewStatusOwner;
    final MiStepStatusBar statusBar;
    final ZoomTransport zoomTransport;
    final HostMirrorController mirrorController;
    final HostSwapController swapController;
    final HostPromotionController promotionController;
    volatile HomeTaskReturn coveredHome;
    CellMovePreview cellMovePreview;
    Bitmap mainBackdrop;
    View topStripBacking;
    View mainGapBacking;
    Bitmap wallpaperBackdrop;
    boolean wallpaperBackdropRequested;
    BroadcastReceiver wallpaperReceiver;
    BroadcastReceiver desktopIconReceiver;
    private boolean mainBackdropRequested;
    /** mirror 承载时主区显示的桌面/上一主区快照（避免隐藏任务后主区全黑）。 */
    View carrierBackdrop;
    int carrierSlotIndex = -1;
    MiStepTopBar topBar;
    volatile WorkspaceOptions workspaceOptions = WorkspaceOptions.DEFAULT;
    WorkspaceSettingsClient workspaceSettings;
    FrameLayout topLiveClip;
    HostTopTaskController.TopCandidate tabletCoveredMain;
    TopTaskState.Key tabletReturnedTop;
    final HostTopTaskController topCtrl;
    final HostLifecycleController lifecycle;
    final HostAdoptionController adoption;
    final HostStateController stateCtl;
    final HostWindowController winCtl;
    final HostLaunchController launcher;
    final HostTaskEventController taskEvents;
    final HostChromeController chrome;
    final HostCellLayoutController cellLayout;
    final HostSurfaceController surfaceCtl;
    final HostGeometryController geometry;
    final HostDebugController debugCtrl;
    final HostSideScrollController sideScroll;
    private View sideBacking;
    /** 侧栏按格分段的不透明底板；承载格需要把它藏起来让下面的真实任务画面透出来。 */
    final View[] sideBackingViews = new View[SLOT_COUNT];
    boolean mainOnRight;
    View dragGhost;
    boolean dragMoved;
    float dragStartRawX;
    float dragStartRawY;
    Slot dragTarget;
    volatile boolean keyguardLocked;
    volatile boolean retainedForLock;
    boolean retainedWindowPrepared;
    long retainedWindowGeneration;
    volatile boolean keyguardWindowHidden;
    boolean keyguardWindowNotTouchable;
    float keyguardWindowAlpha;
    int keyguardResumeIndex = -1;
    /** v1.0.4：普通退出（exit / homekey）后保留已绑定的副窗，不清槽不归还；再次进入淡入恢复。 */
    volatile boolean retainedOnExit;
    long retainedExitSourceEpoch = -1;
    String pendingSuspendReason;
    MiStepUnlockHandoff unlockHandoff;
    final FinishTransactions<Long> keyguardTransactions = new FinishTransactions<>(16);
    static final int ACTIVE = 0, SUSPENDING = 1, SUSPENDED = 2, RESUMING = 3;
    volatile int scene = ACTIVE;
    long sceneEpoch;
    boolean suspendRequested;
    final MiStepNavigationPolicy.EmptyMainGesture emptyMainGesture =
            new MiStepNavigationPolicy.EmptyMainGesture();
    boolean landscapeSuspended;
    boolean transferBusy;
    boolean overlayRefreshScheduled;
    static boolean boundsHooksReady;
    static boolean legacyTaskInfoCallbacks;
    static boolean surfaceHooksReady;
    Object surfaceObserver;
    volatile boolean traceNextSwap;
    volatile MiStepSwapTrace swapTrace;
    boolean compactPending;
    MiStepCellMotion cellMotion;
    MiStepSideDismiss sideDismiss;
    Slot sideDismissSlot;
    float topDismissDx, topDismissDirection;
    boolean tabletToolsOpen;
    FrameLayout tabletTools;
    boolean taskCellsCovered;
    volatile boolean liveSwapInputBlocked;
    MiStepTransitionInput transitionInput;
    final android.os.IBinder desktopSceneOwner = new android.os.Binder();
    volatile long desktopRegistrationSequence;
    final FinishTransactions<RenderBinding> finishTransactions = new FinishTransactions<>(16);
    final FinishTransactions<Long> workspaceFinishes = new FinishTransactions<>(16);

    final java.util.Map<Object, OwnedTask> ownedTasks = new java.util.HashMap<>();




    final FinishTransactions<NativeObservation> nativeObservations = new FinishTransactions<>(16);
    volatile NativeObservation pendingNativeObservation;

    /** One commit signal or the bounded watchdog starts the same state verification. */
    final class NativeObservation implements Runnable {
        final Object token;
        final Runnable check;
        final java.util.concurrent.atomic.AtomicBoolean consumed = new java.util.concurrent.atomic.AtomicBoolean();
        NativeObservation(Object token, Runnable check) { this.token = token; this.check = check; }
        public void run() {
            if (!consumed.compareAndSet(false, true)) return;
            main.removeCallbacks(this);
            if (pendingNativeObservation == this) pendingNativeObservation = null;
            check.run();
        }
    }

    NativeObservation observeNativeCommit(Object token, Runnable check) { return stateCtl.observeNativeCommit(token, check); }

    void cancelNativeObservation(NativeObservation observation) { stateCtl.cancelNativeObservation(observation); }



    /** Hide only the selected cell's Host drawings; its SurfaceView stays attached. */


    private MiStepHost(Context ctx, Executor executor, Object factory, Object transitions) {
        // Keep the workspace on the physical portrait window configuration while
        // native SystemUI resources follow the main application's environment.
        Display display = ((DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE))
                .getDisplay(Display.DEFAULT_DISPLAY);
        this.ctx = ctx.createDisplayContext(display).createWindowContext(
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
        this.executor = executor;
        this.factory = factory;
        this.transitions = transitions;
        statusBar = new MiStepStatusBar(main, MiStepHost::log);
        zoomTransport = new ZoomTransport(this);
        mirrorController = new HostMirrorController(this);
        swapController = new HostSwapController(this);
        promotionController = new HostPromotionController(this);
        topCtrl = new HostTopTaskController(this);
        lifecycle = new HostLifecycleController(this);
        adoption = new HostAdoptionController(this);
        stateCtl = new HostStateController(this);
        winCtl = new HostWindowController(this);
        launcher = new HostLaunchController(this);
        taskEvents = new HostTaskEventController(this);
        chrome = new HostChromeController(this);
        cellLayout = new HostCellLayoutController(this);
        surfaceCtl = new HostSurfaceController(this);
        geometry = new HostGeometryController(this);
        debugCtrl = new HostDebugController(this);
        sideScroll = new HostSideScrollController(this);
        installLogicalBoundsHooks(ctx.getClassLoader());
        installRetainedSurfaces(ctx.getClassLoader());
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = new Slot(i);
        }
        workspaceSettings = new WorkspaceSettingsClient(this.ctx, main, options -> {
            workspaceOptions = options;
            if (topBar != null) topBar.applyOptions(options);
            requestCompaction();
            if (!options.keepOnLock && retainedForLock) {
                retainedForLock = false;
                suspendSession("lock-retention-disabled");
            }
            log("workspace settings applied hidden=" + options.hiddenButtons + " swap=" + options.swapMode
                    + " keepOnLock=" + options.keepOnLock + " scrollSideTasks=" + options.scrollSideTasks);
        });
    }

    public static void start(Context ctx, Executor executor, Object factory, Object transitions) {
        if (!MiStepEntryGuard.canOpen(ctx)) { log("host start rejected while locked"); return; }
        if (sInstance != null) {
            log("host already started");
            return;
        }
        sInstance = new MiStepHost(ctx, executor, factory, transitions);
        sInstance.startInternal();
    }

    /** 首次触发启动宿主，之后在显示／隐藏间切换。 */
    public static void toggleOrStart(Context ctx, Executor executor, Object factory, Object transitions) {
        if (!MiStepEntryGuard.canOpen(ctx)) { log("host gesture rejected while locked"); return; }
        if (sInstance == null) {
            start(ctx, executor, factory, transitions);
            return;
        }
        sInstance.toggleVisibility();
    }

    /** 调试用：确保宿主已启动，但不切换显示状态。 */
    public static void toggleOrStartIfNeeded(Context ctx, Executor executor, Object factory,
            Object transitions) {
        if (!MiStepEntryGuard.canOpen(ctx)) return;
        if (sInstance == null) {
            start(ctx, executor, factory, transitions);
        }
    }

    /** 亮屏／熄屏通知（HostEntry 的屏幕广播）。 */
    public static void onScreenState(boolean on) {
        MiStepHost h = sInstance;
        if (h == null) {
            return;
        }
        h.main.post(() -> {
            h.screenOn = on;
            h.publishDesktopSideTasks();
            if (!on) {
                MiStepUnlockHandoff pending = h.unlockHandoff;
                h.unlockHandoff = null;
                if (pending != null) pending.cancel();
                h.traceNextSwap = false;
                MiStepSwapTrace trace = h.swapTrace;
                if (trace != null) trace.close();
                h.closeSwapPreview();
                h.statusBar.restore();
                h.closeCellMovePreview();
                if (!h.workspaceOptions.keepOnLock || !h.retainWorkspaceForLock())
                    h.suspendSession("screen-lock");
            } else {
                if (h.retainedForLock) h.leaveKeyguardPrivacy();
                h.updateTopBarState();
            }
        });
    }

    public static void onUserPresent() {
        MiStepHost h = sInstance;
        if (h != null) h.main.post(h::leaveKeyguardPrivacy);
    }

    public static void onNativeWake() {
        MiStepHost h = sInstance;
        if (h == null) return;
        Runnable restore = () -> {
            if (!h.retainedForLock) return;
            h.leaveKeyguardPrivacy();
            if (!h.retainedForLock) log("retained workspace restored from native wake");
        };
        if (Looper.myLooper() == h.main.getLooper()) restore.run();
        else h.main.post(restore);
    }

    static boolean handoffNativeUnlock(Runnable nativeHide) {
        MiStepHost h = sInstance;
        if (h == null || Looper.myLooper() != h.main.getLooper() || !h.retainedForLock
                || !h.keyguardWindowHidden || h.root == null || h.layout == null
                || (h.scene != ACTIVE && h.scene != RESUMING)
                || !MiStepEntryGuard.canOpen(h.ctx) || h.unlockHandoff != null) return false;
        final long epoch = h.sceneEpoch;
        h.screenOn = true;
        MiStepLayout geometry = h.layout;
        MiStepUnlockHandoff handoff = new MiStepUnlockHandoff(h.ctx, h.main,
                geometry.mainScale, geometry.mainOffsetX, geometry.mainOffsetY, ack -> {
            if (h != sInstance || epoch != h.sceneEpoch || !h.retainedForLock
                    || !h.keyguardWindowHidden || !MiStepEntryGuard.canOpen(h.ctx)) {
                h.unlockHandoff.finish(); return;
            }
            h.keyguardLocked = false;
            h.workspaceGeneration++;
            h.workspaceEntering = false;
            h.zoomAnimUntil = 0;
            h.setWorkspaceAlpha(1f);
            Object tx = null;
            try {
                tx = Class.forName("android.view.SurfaceControl$Transaction").getDeclaredConstructor().newInstance();
                XposedHelpers.callMethod(tx, "merge", ack.finishReplay);
                h.appendWorkspaceSurfaces(tx, java.util.Collections.singletonList(epoch));
                XposedHelpers.callMethod(tx, "apply");
                h.setZoomState(true);
                if (!h.setKeyguardWindowHidden(false)) throw new IllegalStateException("Host reveal failed");
                h.root.getViewTreeObserver().registerFrameCommitCallback(() -> h.main.post(() -> {
                    MiStepUnlockHandoff current = h.unlockHandoff;
                    if (current != null && current.active()) {
                        log("authenticated retained Host frame committed before native hide");
                        current.finish();
                    }
                }));
                h.root.invalidate();
            } catch (Throwable error) {
                log("retained unlock handoff stopped: " + error);
                h.unlockHandoff.finish();
            } finally { h.closeSurfaceObject(tx, "close"); }
        }, () -> {
            h.unlockHandoff = null;
            if (MiStepEntryGuard.canOpen(h.ctx)) nativeHide.run();
        });
        h.unlockHandoff = handoff;
        log("authenticated retained unlock waiting for WMS commit and Host frame");
        handoff.start();
        return true;
    }

    public static void onSystemNavigation(String reason) {
        MiStepHost h = sInstance;
        if (h != null) h.main.post(() -> {
            if (h.retainedForLock) return;
            if ("fs_gesture".equals(reason)) {
                h.emptyMainGesture.begin(h.sceneEpoch, SystemClock.uptimeMillis(),
                        h.emptyMainHomeFocused());
                return;
            }
            if ("recentapps".equals(reason)) {
                h.emptyMainGesture.clear();
                return;
            }
            if ("homekey".equals(reason) && h.scene == ACTIVE
                    && h.emptyMainGesture.duplicateHome(h.sceneEpoch, SystemClock.uptimeMillis())) {
                log("native overview follow-up Home retains empty workspace");
                return;
            }
            if ("homekey".equals(reason)
                    && h.emptyMainGesture.consume(h.sceneEpoch, SystemClock.uptimeMillis())
                    && h.emptyMainHomeFocused()) {
                boolean requested = MiStepNativeRecents.show();
                if (requested) h.emptyMainGesture.requested(SystemClock.uptimeMillis());
                log("empty-main upward gesture retains workspace; native overview=" + requested);
                return;
            }
            if (NativeLandscapePolicy.shouldRestoreFromHome(
                    h.landscapeSuspended, h.scene == SUSPENDED, reason)) {
                h.resumeSession();
            } else {
                h.suspendSession(reason);
            }
        });
    }

    private boolean emptyMainHomeFocused() {
        if (scene != ACTIVE || !screenOn || keyguardLocked || suspendRequested
                || promotion != null || transferBusy || topCtrl.topTransition != null) return false;
        try {
            if (findMainTask() != null) return false;
            for (Object info : rootInfos()) {
                if (isHomeRoot(info) && XposedHelpers.getBooleanField(info, "isVisible")
                        && XposedHelpers.getBooleanField(info, "isFocused")) return true;
            }
        } catch (Throwable unavailable) {
            log("empty-main gesture context unavailable: " + unavailable);
        }
        return false;
    }

    /** Native fullscreen uses the existing retained-task suspend/resume lifecycle. */
    public static void onLandscapeMode(boolean fullscreen) {
        MiStepHost h = sInstance;
        if (h == null) return;
        h.main.post(() -> {
            if (h.retainedForLock) return;
            if (fullscreen) {
                if (h.scene == SUSPENDED && h.retainedOnExit) {
                    h.landscapeSuspended = false;
                    return;
                }
                h.landscapeSuspended = true;
                if (h.scene == ACTIVE) h.suspendSession("native-landscape");
            } else {
                h.landscapeSuspended = false;
                if (!(h.scene == SUSPENDED && h.retainedOnExit)) h.resumeSession();
            }
        });
    }

    /** 调试广播：向指定格启动一个包（无人值守验证用）。 */
    public static void debugLaunch(int slot, String pkg) { HostDebugController.debugLaunch(slot, pkg); }

    /** 调试广播：把指定副格与主区交换。 */
    public static void debugSwap(int slot) { HostDebugController.debugSwap(slot); }



    /** Arm passive instrumentation only; it never starts a swap or turns on a POC. */
    public static void debugTraceNextSwap() { HostDebugController.debugTraceNextSwap(); }

    void traceSwap(Promotion ticket) { debugCtrl.traceSwap(ticket); }

    void traceSwapEvent(String event) { debugCtrl.traceSwapEvent(event); }

    /** 调试广播：把主区任务 mirror 成宿主窗口里的实时画面（验证 live-surface 承载）。 */
    public static void debugMirror(int taskId, int x, int y, int w, int h, int holdMs,            int hideMode) { HostDebugController.debugMirror(taskId, x, y, w, h, holdMs, hideMode); }

    void createMirrorLayer() { mirrorController.createMirrorLayer(); }

    void setMirrorLayerVisible(boolean visible) { mirrorController.setMirrorLayerVisible(visible); }

    void startMirrorPoc(int requestedTask, Rect dest, int holdMs, int hideMode) { debugCtrl.startMirrorPoc(requestedTask, dest, holdMs, hideMode); }

    Object shellOrganizer() { return mirrorController.shellOrganizer(); }

    void reassertCarrier() { mirrorController.reassertCarrier(); }

    void restoreCarrierToMain() { mirrorController.restoreCarrierToMain(); }

    void scheduleMainCropClear(long delayMs) { mirrorController.scheduleMainCropClear(delayMs); }

    /** 打开一步时按滑动起始角决定主区在哪边：从左角滑 → 主区在左；从右角滑 → 主区在右。 */
    static volatile int pendingMainSide = -1;

    public static void setPendingMainSide(boolean mainOnRight) { HostStateController.setPendingMainSide(mainOnRight); }

    void consumePendingMainSide() { stateCtl.consumePendingMainSide(); }

    /** 调试广播：把主区任务 leash 从主区尺寸动画到目标槽（view 级交换动画的 POC）。 */
    public static void debugLeashAnim(int slotIndex, int durationMs, String pkg) {
        MiStepHost host = sInstance;
        if (host == null) {
            log("debugLeashAnim: host not started");
            return;
        }
        host.main.post(() -> host.runLeashAnimPoc(slotIndex, Math.max(150, durationMs), pkg));
    }

    volatile int leashAnimFrames;
    /** POC 期间该格的 SF 底板必须停止心跳重建，否则会盖住动画中的真实任务画面。 */
    volatile int leashPocSlot = -1;

    private void runLeashAnimPoc(int slotIndex, long durationMs, String pkg) { debugCtrl.runLeashAnimPoc(slotIndex, durationMs, pkg); }

    private void animateLeashPoc(Object leash, int[] rect, int slotIndex, int taskId,            long durationMs) { debugCtrl.animateLeashPoc(leash, rect, slotIndex, taskId, durationMs); }

    /** POC 期间隐藏目标格的宿主底板，让真实任务画面能在格子里可见（正式交换也会这么做）。 */
    private void hideSlotChromeForPoc(int slotIndex, boolean hide) { debugCtrl.hideSlotChromeForPoc(slotIndex, hide); }

    private void reverseLeashPoc(Object leash, int taskId, int slotIndex) { debugCtrl.reverseLeashPoc(leash, taskId, slotIndex); }

    /**
     * view 级主窗动画：把真实主任务 leash 在桥的洞内从主区矩形缩进目标格。
     * 与 SwapPreview 同时长、同曲线，桥只画侧栏/背景，主窗画面是真实任务表面。
     */
    private void startLiveMainShrink(Promotion ticket) { debugCtrl.startLiveMainShrink(ticket); }

    void applyLeashTransform(Object leash, float x, float y, float sx, float sy) { debugCtrl.applyLeashTransform(leash, x, y, sx, sy); }

    /** Bounds of the visible main pane while the host owns the display zoom. */
    static Rect activeMainBounds() { return HostGeometryController.activeMainBounds(); }

    /** Workspace matrix for a registered native main-task animation, never for a swap. */
    static float[] activeMainTransform() { return HostGeometryController.activeMainTransform(); }

    static Rect nativeFreeformAnimationStart(Object change) { return HostGeometryController.nativeFreeformAnimationStart(change); }

    static float[] activeStatusBarTransform() { return HostGeometryController.activeStatusBarTransform(); }

    static float[] activeIslandMonitorTransform() { return HostGeometryController.activeIslandMonitorTransform(); }

    static boolean hostSceneActive() { return HostGeometryController.hostSceneActive(); }

    static boolean shouldClampAnimationBounds(Object info, Object change) { return HostGeometryController.shouldClampAnimationBounds(info, change); }











    /** Process-wide hooks, restricted to these exact three view/controller identities. */
    static final ThreadLocal<java.util.List<MiStepSideClose>> nativeCloses = new ThreadLocal<>();
    static final ThreadLocal<Object> closeStartTransaction = new ThreadLocal<>();
    static final ThreadLocal<java.util.List<?>> externalChanges = new ThreadLocal<>();
    static final ThreadLocal<Object> closeFinishTransaction = new ThreadLocal<>();
    private static void installLogicalBoundsHooks(ClassLoader cl) { HostGeometryController.installLogicalBoundsHooks(cl); }

    Rect logicalBounds() { return geometry.logicalBounds(); }
    Rect logicalBounds(Slot slot) { return geometry.logicalBounds(slot); }
    Slot slotForObject(Object object) { return geometry.slotForObject(object); }
    TopTaskGeometry topGeometry() { return winCtl.topGeometry(); }
    /** Keep app metrics in the same logical viewport as the hosted surface and input. */
    void applyTopConfiguration(Object wct, Object token, Slot slot) { winCtl.applyTopConfiguration(wct, token, slot); }
    int[] slotBounds(Slot slot) { return winCtl.slotBounds(slot); }

    java.util.Set<Integer> supportedTaskUsers() { return stateCtl.supportedTaskUsers(); }

    boolean supportsTaskUser(int userId) { return winCtl.supportsTaskUser(userId); }

    int currentUserId() { return winCtl.currentUserId(); }

    boolean ownsViewOrController(Object object) { return stateCtl.ownsViewOrController(object); }

    /** External mode1 CHANGE belongs to Launcher, not TaskView's immediate side reparent. */
    boolean leaveExternalChangeToNative(Object change) { return stateCtl.leaveExternalChangeToNative(change); }

    java.util.List<?> rootInfos() throws Exception { return stateCtl.rootInfos(); }

    Object rootInfo(LiveTask task) throws Exception { return stateCtl.rootInfo(task); }

    boolean ordinaryRoot(Object info, int mode) { return stateCtl.ordinaryRoot(info, mode); }

    /** Some associated-profile roots are focused while the ROM reports isVisible=false. */
    boolean mainTaskVisible(Object info) { return stateCtl.mainTaskVisible(info); }

    boolean isSideToken(Object token) { return stateCtl.isSideToken(token); }

    /** 诊断：打印当前所有 root task 的判定字段（findMainTask 漏判用）。 */
    void dumpRoots(String reason) { debugCtrl.dumpRoots(reason); }

    /** 调试广播入口：打印 root 诊断。 */
    public static void debugRoots() { HostDebugController.debugRoots(); }

    LiveTask findMainTask() throws Exception { return launcher.findMainTask(); }

    LiveTask findPackageTask(String pkg) throws Exception { return launcher.findPackageTask(pkg); }

    LiveTask existingHomeTask() throws Exception { return launcher.existingHomeTask(); }

    boolean isHomeRoot(Object info) { return launcher.isHomeRoot(info); }

    /** Reveal only the same Home that this app covered; never send a HOME launch intent. */
    LiveTask existingHomeForCollection(LiveTask task) throws Exception { return launcher.existingHomeForCollection(task); }

    Object homeForCollection(LiveTask task) throws Exception { return launcher.homeForCollection(task); }



    void checkAdoption(Slot slot, LiveTask task, long epoch, int attempt,            Runnable success, Consumer<String> failure) { stateCtl.checkAdoption(slot, task, epoch, attempt, success, failure); }








    private void installRetainedSurfaces(ClassLoader cl) { surfaceCtl.installRetainedSurfaces(cl); }

    void trackRetainedSurfaces(Object info, Object start, Object finish) { surfaceCtl.trackRetainedSurfaces(info, start, finish); }

    /**
     * WindowManager reports this overlay visible throughout fixed-rotation launches,
     * but MIUI can leave its last buffer under the transition until the next redraw.
     * Refresh only around a transition; this does not resize or recreate the host.
     */
    private void scheduleOverlayRefresh() { surfaceCtl.scheduleOverlayRefresh(); }

    private void refreshOverlayAfterTransition() { surfaceCtl.refreshOverlayAfterTransition(); }

    /** Append after native finish operations have merged, before the very same apply. */
    void appendWorkspaceSurfaces(Object tx, java.util.List<Long> epochs) { surfaceCtl.appendWorkspaceSurfaces(tx, epochs); }

    void appendRetainedSurfaces(Object tx, java.util.List<RenderBinding> frames) { surfaceCtl.appendRetainedSurfaces(tx, frames); }


    void setZoomState(boolean on) { stateCtl.setZoomState(on); }

    /**
     * 写缩放触发文件（SystemUI 私有目录，system_server 侧轮询）。
     * 呼出/退出为 300ms 整屏缩放动画（system_server 侧 2.2.4+ 的 anim 命令）；
     * 动画结束后再写一次稳态续租，避免 4 秒租约把进行中的动画取消。
     */
    static final long ZOOM_ANIM_MS = 300L;
    android.animation.ValueAnimator workspaceAnimator;
        long workspaceGeneration;
    volatile float workspaceAlpha = 1f;
    boolean workspaceEntering;




    /** 动画期间宿主不得写普通稳态（会取消动画）；心跳按此时间窗跳过。 */
    volatile long zoomAnimUntil;
    long zoomCommandGeneration;

    private String zoomOnCommand() { return stateCtl.zoomOnCommand(); }

    void writeZoomAnimated(boolean on) { stateCtl.writeZoomAnimated(on); }

    void writeZoom(boolean on) { stateCtl.writeZoom(on); }

    private void writeZoomLine(String command) { stateCtl.writeZoomLine(command); }

    private void writeZoomLine(String command, long issuedAt) { stateCtl.writeZoomLine(command, issuedAt); }

    long workspaceZoomStart;
    MiStepLiveBackdrop workspaceBackdrop;
    View workspaceBackdropOwner;
    View.OnAttachStateChangeListener workspaceBackdropDetach;
    private void startInternal() {
        main.post(() -> {
            if (!MiStepEntryGuard.canOpen(ctx)) {
                if (sInstance == this && root == null) sInstance = null;
                log("queued host start rejected while locked");
                return;
            }
            try {
                registerDesktopIconReceiver();
                buildLayout();
                createSubViews();
                registerWallpaperReceiver();
                main.postDelayed(this::collapseStatusBarPanels, 220L);
        main.postDelayed(this::loadWallpaperBackdrop, 250L);
            } catch (Throwable t) {
                log("startInternal FAILED: " + t);
                XposedBridge.log(t);
            }
        });
    }

    private void buildLayout() { cellLayout.buildLayout(); }

    static MiStepHost current() { return sInstance; }

    static int topGestureHeight() { return HostTopTaskController.topGestureHeight(); }
    static int[] topGestureBounds() { return HostTopTaskController.topGestureBounds(); }
    static int topGestureDirection() { return HostTopTaskController.topGestureDirection(); }
    static void requestTopBack(Object token) { HostTopTaskController.requestTopBack(token); }
    static boolean beginTopDismiss(float dx, Object token) { return HostTopTaskController.beginTopDismiss(dx, token); }
    static void moveTopDismiss(float dx) { HostTopTaskController.moveTopDismiss(dx); }
    static void endTopDismiss(boolean cancelled) { HostTopTaskController.endTopDismiss(cancelled); }
    static boolean beginTopGesture() { return HostTopTaskController.beginTopGesture(); }
    static void moveTopGesture(float displacement, long time) { HostTopTaskController.moveTopGesture(displacement, time); }
    static void endTopGesture(boolean cancelled) { HostTopTaskController.endTopGesture(cancelled); }
    static void onTopTaskState(Intent intent) { HostTopTaskController.onTopTaskState(intent); }
    static boolean ownsTopNativeCaption(Object info) { return HostTopTaskController.ownsTopNativeCaption(info); }
    void resetTopForSuspend() { topCtrl.resetTopForSuspend(); }
    void createTopTaskControls() { topCtrl.createTopTaskControls(); }
    void updateTopTaskGeometry() { topCtrl.updateTopTaskGeometry(); }
    static Object topDismissToken() { return HostTopTaskController.topDismissToken(); }
    void compactCellsAfterCollection() { cellLayout.compactCellsAfterCollection(); }
    void appendHiddenKeyguardSurfaces(Object tx, java.util.List<Long> epochs) { surfaceCtl.appendHiddenKeyguardSurfaces(tx, epochs); }
    private void openTopFace(boolean manual) { topCtrl.openTopFace(manual); }
    void releaseTopTask(boolean front, boolean dismissed, Runnable after) { topCtrl.releaseTopTask(front, dismissed, after); }

    /** BUG-2 韧性：promotion/transfer 卡死时 suspend 会无限排队、cancel 路径存在静默 no-op。
     *  本看门狗独立于 scene 门禁（覆盖 ACTIVE/SUSPENDING/RESUMING 各种卡态）：
     *  >8s 温和取消一次（requestLiveSwapCancellation），>20s 强制 failPromotion 走正规
     *  失败汇总（回滚 WCT/清理槽位/触发 finishPendingSuspend），窗口期归零防连发。 */
    long promotionWatchdogSince;
    boolean promotionWatchdogNudged;

    void startPromotionWatchdog() { stateCtl.startPromotionWatchdog(); }

    private void createSubViews() { taskEvents.createSubViews(); }

    private void createNext(final int index) { taskEvents.createNext(index); }

    boolean onTaskViewCreated(Slot slot, Object taskViewObj, boolean next) { return taskEvents.onTaskViewCreated(slot, taskViewObj, next); }

    TextView makeEmptyHint() { return chrome.makeEmptyHint(); }

    void drawEmptyCellPlus(android.graphics.Canvas canvas, int width, int height,            int color, android.graphics.Paint plus) { chrome.drawEmptyCellPlus(canvas, width, height, color, plus); }

    FrameLayout.LayoutParams hintParams(Slot slot) { return chrome.hintParams(slot); }

    /** 空格子显示"+"提示；有任务时隐藏。 */
    void updateHint(Slot slot) { chrome.updateHint(slot); }

    /** An empty TaskView keeps its last resize background; hide that container, not a screenshot. */
    void updateCellSurfaceVisibility(Slot slot) { cellLayout.updateCellSurfaceVisibility(slot); }

    /** Open the destination hole while its opaque empty hint still covers it.
     * Native preparation owns show/hide; only make its eventual parent nontransparent.
     */
    void prepareCellSurface(Slot slot) { cellLayout.prepareCellSurface(slot); }

    /** A host-owned backing stays inside the cell, below the real task, even for translucent apps. */
    void ensureCellBackground(Slot slot) { cellLayout.ensureCellBackground(slot); }

    void registerDefaultDisplayListener() { stateCtl.registerDefaultDisplayListener(); }

    void readDisplayGeometry() { geometry.readDisplayGeometry(); }

    void scheduleDisplayGeometryRefresh() { geometry.scheduleDisplayGeometryRefresh(); }

    void refreshDisplayGeometry() { geometry.refreshDisplayGeometry(); }

    void refreshQuickApps() { chrome.refreshQuickApps(); }

    void updateTopBarState() { chrome.updateTopBarState(); }





    /** 只取桌面壁纸做高斯模糊底（不使用应用内容），供顶栏/侧栏格子的液态玻璃使用。 */
    void loadWallpaperBackdrop() { chrome.loadWallpaperBackdrop(); }

    void applyWallpaperBackdrops() { chrome.applyWallpaperBackdrops(); }

    /** 角部滑动起手在状态栏区域时 MIUI 通知栏/控制中心会被顺带拉出并盖住主区；打开一步后主动收起。 */
    void collapseStatusBarPanels() { chrome.collapseStatusBarPanels(); }

    /** 换壁纸后自动重算模糊底，避免一步里还是旧壁纸。 */
    private void registerWallpaperReceiver() { chrome.registerWallpaperReceiver(); }

    private void registerDesktopIconReceiver() { stateCtl.registerDesktopIconReceiver(); }


    /** Publish only confirmed bindings; system_server rechecks each native task before use. */
    void publishDesktopSideTasks() { launcher.publishDesktopSideTasks(); }

    void promoteSideTaskForDesktopLaunch(String pkg, int launchUser, ComponentName entry) { launcher.promoteSideTaskForDesktopLaunch(pkg, launchUser, entry); }







    void scheduleSideSurfaceRestore(Slot slot, int taskId, Object token,            long bindingEpoch, long epoch) { launcher.scheduleSideSurfaceRestore(slot, taskId, token, bindingEpoch, epoch); }


    MiStepLayout makeAdaptiveLayout(boolean right) { return cellLayout.makeAdaptiveLayout(right); }


    /** 调试广播入口：打印宿主 View 树（定位残带/残留视图）。 */
    public static void debugDumpViews() { HostDebugController.debugDumpViews(); }

    void dumpViewTree() { debugCtrl.dumpViewTree(); }

    private void dumpViewRecursive(View view, int depth) { debugCtrl.dumpViewRecursive(view, depth); }


    /** 交换确认后把新主任务 leash 完全复位：position/matrix/alpha/crop，避免副格残留变换把画面顶到主区上方。 */
    void resetMainTaskLeash(int taskId) { taskEvents.resetMainTaskLeash(taskId); }

    void launchQuick(QuickApp entry) { launcher.launchQuick(entry); }

    void openSettingsInMain() { launcher.openSettingsInMain(); }

    void beginWindowDrag(MotionEvent event) { winCtl.beginWindowDrag(event); }

    void updateWindowDrag(MotionEvent event) { winCtl.updateWindowDrag(event); }

    void finishWindowDrag(MotionEvent event) { winCtl.finishWindowDrag(event); }

    private void createDragGhost() { winCtl.createDragGhost(); }

    private void setDropHighlight(Slot slot, boolean active) { winCtl.setDropHighlight(slot, active); }

    /** 侧栏第 slotIndex 格的分段底板 top（与相邻格共享边界，保证列无缝）。 */
    int sideSegmentTop(int slotIndex) { return winCtl.sideSegmentTop(slotIndex); }

    /** 侧栏第 slotIndex 格的分段底板高度。 */
    int sideSegmentHeight(int slotIndex) { return winCtl.sideSegmentHeight(slotIndex); }

    private Slot slotAtPoint(float x, float y) { return winCtl.slotAtPoint(x, y); }

    int dp(float value) { return cellLayout.dp(value); }

    void removeCellBackground(Slot slot) { cellLayout.removeCellBackground(slot); }

    private void destroyCellSurface(Object background) { cellLayout.destroyCellSurface(background); }

    void closeSurfaceObject(Object object, String method) { cellLayout.closeSurfaceObject(object, method); }

    int slotIndexOfView(View view) { return winCtl.slotIndexOfView(view); }

    void applyRect(Slot slot) { cellLayout.applyRect(slot); }

    /**
     * Exchange the whole main/side orientation. The official layout keeps the two
     * windows anchored to opposite edges, so the shrinking and growing panes share a
     * moving divider instead of crossing through each other's centre.
     */
    void setMainSide(boolean right) { stateCtl.setMainSide(right); }

    /** Host geometry only. A live flip sends its correlated WMS zoom separately. */
    void applyMainLayout(MiStepLayout target) { cellLayout.applyMainLayout(target); }

    /** Only the top strip / side column receive host input; transitions may cover the display. */
    void installHostTouchRegion() { winCtl.installHostTouchRegion(); }

    /** Side TaskViews return their touch areas to the host for tap/drag arbitration. */
    void updateTouchRegion(Slot slot) { winCtl.updateTouchRegion(slot); }

    // ---------------------------------------------------------------- v0.9 触摸仲裁与定向注入

    /**
     * 触摸规则（官方Mi Step语义 + 演示需要）：
     *   主区由系统处理，宿主不接收其触摸；
     *   副格有任务 → 轻点=与主区交换；滑动取消轻点，始终不操作副任务；
     *   空槽 → 不消费，交给宿主点击处理。
     */
    boolean onSlotTouch(int slotIndex, MotionEvent e) { return swapController.onSlotTouch(slotIndex, e); }

    void recycleBuffered(Slot slot) { swapController.recycleBuffered(slot); }

    private boolean followSideDismiss(Slot slot, MotionEvent e) { return swapController.followSideDismiss(slot, e); }

    private void dismissSideToBackground(Slot slot) { swapController.dismissSideToBackground(slot); }

    /** Keep tap-to-swap, transfer a drag to its real window without reinjecting coordinates. */
    private void transferTouchToTask(Slot slot, long eventTime) { swapController.transferTouchToTask(slot, eventTime); }

    private int uidOf(String pkg) { return winCtl.uidOf(pkg); }

    // ---------------------------------------------------------------- 点击 / 交换

    void adoptExisting(Slot slot, LiveTask task, Runnable success, Consumer<String> failure) { adoption.adoptExisting(slot, task, success, failure); }
    void adoptExisting(Slot slot, LiveTask task, boolean collectFromMain,
            Runnable success, Consumer<String> failure) { adoption.adoptExisting(slot, task, collectFromMain, success, failure); }
    void adoptionFailed(Slot slot, LiveTask task, boolean claimed, String error, Consumer<String> failure) { adoption.adoptionFailed(slot, task, claimed, error, failure); }
    void returnToSystem(Slot slot, boolean front, Runnable success, Consumer<String> failure) { adoption.returnToSystem(slot, front, success, failure); }
    void returnToSystem(Slot slot, boolean front, boolean liveMainReveal,
            Runnable success, Consumer<String> failure) { adoption.returnToSystem(slot, front, liveMainReveal, success, failure); }
    boolean returnTicketCurrent(Promotion ticket) { return adoption.returnTicketCurrent(ticket); }
    boolean absentFromShellAndController(Slot slot, LiveTask task) { return adoption.absentFromShellAndController(slot, task); }
    void checkReturned(Slot slot, Promotion ticket, int attempt, Runnable success, Consumer<String> failure) { adoption.checkReturned(slot, ticket, attempt, success, failure); }
    void promoteExternalLaunch(Slot slot) { adoption.promoteExternalLaunch(slot); }
    void promoteUnsupportedSide(Slot slot) { adoption.promoteUnsupportedSide(slot); }
    void scheduleSideModeResolution(Slot slot, int taskId) { adoption.scheduleSideModeResolution(slot, taskId); }
    void repairSideWindowMode(Slot slot, int taskId) { adoption.repairSideWindowMode(slot, taskId); }
    void releaseNativeFreeformSide(Slot slot, int taskId, Object token) { adoption.releaseNativeFreeformSide(slot, taskId, token); }
    static boolean promoteForNotification(String pkg, int userId, Runnable dispatch) {
        MiStepHost hh = current();
        if (hh == null) return false;
        return hh.adoption.promoteForNotification(pkg, userId, dispatch);
    }
    void resumeNotificationLaunch(Promotion ticket) { adoption.resumeNotificationLaunch(ticket); }
    void queueSidePromotion(Slot slot, boolean desktopLaunch) { adoption.queueSidePromotion(slot, desktopLaunch); }
    void retrySidePromotion(PendingSidePromotion request) { adoption.retrySidePromotion(request); }
    void beginExternalPromotion(PendingSidePromotion request) { adoption.beginExternalPromotion(request); }
    boolean externalPromotionCurrent(Promotion ticket) { return adoption.externalPromotionCurrent(ticket); }
    void afterExternalTransitionIdle(Promotion ticket) { adoption.afterExternalTransitionIdle(ticket); }
    void preannounceDesktopLaunch(android.os.Bundle binding) { adoption.preannounceDesktopLaunch(binding); }
    boolean sideTaskMatchesUser(Slot slot, int user) { return adoption.sideTaskMatchesUser(slot, user); }
    void launchWhenReady(int slot, String pkg, int attempt) { lifecycle.launchWhenReady(slot, pkg, attempt); }
    void toggleVisibility() { lifecycle.toggleVisibility(); }
    void suspendSession(String reason) { lifecycle.suspendSession(reason); }
    void returnUnboundOwnedCandidates(long epoch, Runnable complete) { lifecycle.returnUnboundOwnedCandidates(epoch, complete); }
    void returnUnboundOwnedCandidate(OwnedTask candidate, long epoch, int attempt, Runnable complete) { lifecycle.returnUnboundOwnedCandidate(candidate, epoch, attempt, complete); }
    void parkNext(int index) { lifecycle.parkNext(index); }
    void resumeSession() { lifecycle.resumeSession(); }
    void showResumingSession(long epoch) { lifecycle.showResumingSession(epoch); }
    void resumeNext(int index, long epoch) { lifecycle.resumeNext(index, epoch); }
    void finishPendingSuspend() { lifecycle.finishPendingSuspend(); }
    void beginWorkspaceEntry(Runnable ready) { lifecycle.beginWorkspaceEntry(ready); }
    void awaitWorkspaceFrame(long generation, int attempt, Runnable ready) { lifecycle.awaitWorkspaceFrame(generation, attempt, ready); }
    void setWorkspaceAlpha(float value) { lifecycle.setWorkspaceAlpha(value); }
    void playWorkspaceFade(boolean entering, long generation, Runnable complete) { lifecycle.playWorkspaceFade(entering, generation, complete); }
    boolean retainWorkspaceForLock() { return lifecycle.retainWorkspaceForLock(); }
    boolean hasBoundTasks() { return lifecycle.hasBoundTasks(); }
    boolean setKeyguardWindowHidden(boolean hidden) { return lifecycle.setKeyguardWindowHidden(hidden); }
    void leaveKeyguardPrivacy() { lifecycle.leaveKeyguardPrivacy(); }

    void onSlotTapped(int slotIndex) {
        if(slotIndex==SLOT_TOP) { topCtrl.releaseTopTask(true,true,null); return; }
        swapController.onSlotTapped(slotIndex);
    }

    /** Official empty-cell flow: open an app from Home, then collect that exact task. */
    void captureMainIntoEmptySlot(Slot requestedSlot) { swapController.captureMainIntoEmptySlot(requestedSlot); }

    /** Collect the actual fullscreen task; no captured app or desktop participates. */
    private void startLiveCollect(Slot slot, LiveTask task) { swapController.startLiveCollect(slot, task); }

    private void prepareCollectHandoff(Promotion ticket, Consumer<Boolean> settled) { swapController.prepareCollectHandoff(ticket, settled); }

    void finishLiveCollect(Promotion ticket, MiStepLiveSwap.Result result) { swapController.finishLiveCollect(ticket, result); }

    private void completeLiveCollect(Promotion ticket, MiStepLiveSwap.Result result) { swapController.completeLiveCollect(ticket, result); }

    /** Promote through the existing Shell queue; retain the slot until actual detachment. */
    void swapWithMain(int slotIndex) {
        if(slotIndex==SLOT_TOP) { topCtrl.releaseTopTask(true,true,null); return; }
        swapController.swapWithMain(slotIndex);
    }

    void acquireTransitionInputWindow() { swapController.acquireTransitionInputWindow(); }

    void releaseTransitionInputWindow() { swapController.releaseTransitionInputWindow(); }

    Object liveHostRootSurface() { return swapController.liveHostRootSurface(); }

    Rect liveSideStrip(MiStepLayout geometry) { return swapController.liveSideStrip(geometry); }

    /** Paint only empty Host controls; real app buffers remain the original task surfaces. */
    void drawLiveEmptyHints(android.graphics.Canvas canvas, MiStepLayout original,            MiStepLayout target, int revealEmptySlots) { chrome.drawLiveEmptyHints(canvas, original, target, revealEmptySlots); }

    boolean holdsLiveFlipZoom() { return swapController.holdsLiveFlipZoom(); }

    void finishLiveSwap(Promotion ticket, MiStepLiveSwap.Result result) { swapController.finishLiveSwap(ticket, result); }

    void finishLiveHome(Promotion ticket, MiStepLiveSwap.Result result) { swapController.finishLiveHome(ticket, result); }

    void closeSwapPreview() { promotionController.closeSwapPreview(); }

    void closeCellMovePreview() { promotionController.closeCellMovePreview(); }

    Object fullscreenExitTransaction(Promotion ticket) throws Exception { return promotionController.fullscreenExitTransaction(ticket); }

    /** Retire only our binding, then return its canonical task surface to the existing DA. */
    private void handoffPromotedTask(Promotion ticket) throws Throwable { promotionController.handoffPromotedTask(ticket); }

    /** At most three observations; notification or transaction submission alone is insufficient. */
    private void checkPromotion(Promotion ticket, int attempt) { promotionController.checkPromotion(ticket, attempt); }

    /** The promoted binding is already retired; move the remaining owners into its gap. */
    void completeHomePromotion(Promotion ticket) { promotionController.completeHomePromotion(ticket); }

    /** Backfill can take focus again; make the focus request after that last WCT. */
    void finishPromotionFocus(Promotion ticket, Runnable complete, int attempt) { promotionController.finishPromotionFocus(ticket, complete, attempt); }

    void failPromotion(Promotion ticket, String reason) { promotionController.failPromotion(ticket, reason); }

    void clearSlot(Slot slot) { taskEvents.clearSlot(slot); }

    boolean isActive(Slot slot) { return taskEvents.isActive(slot); }

    boolean canRender(Slot slot) { return taskEvents.canRender(slot); }

    void postForSlot(Slot slot, Consumer<Slot> callback) { taskEvents.postForSlot(slot, callback); }

    /** Reject late callbacks from a task the native controller has already relinquished. */
    boolean controllerOwns(Slot slot, int taskId, Object token) { return taskEvents.controllerOwns(slot, taskId, token); }

    void bindTask(Slot slot, int taskId, Object token) { taskEvents.bindTask(slot, taskId, token); }

    static int windowModeOf(Object info) { return HostWindowController.windowModeOf(info); }

    static int configurationOrientationOf(Object info) { return HostWindowController.configurationOrientationOf(info); }

    static int requestedOrientationOf(Object info) { return HostWindowController.requestedOrientationOf(info); }

    void updateTaskWindowState(int slotIndex, Object info, int taskId, boolean active) { winCtl.updateTaskWindowState(slotIndex, info, taskId, active); }

    void syncSlotWindowState(Slot slot) { winCtl.syncSlotWindowState(slot); }

    /**
     * TaskInfo 的多窗口能力标记：不支持的（例如相机）不是拒绝，而是走 mirror 承载。
     * 普通应用仍按原路线进 TaskView。
     */
    /** 规则：除桌面外都可进副格；系统标志不预拒绝，交给 native adoption 结果。 */
    private boolean launchActivitySupportsSide(Intent launch) { return launcher.launchActivitySupportsSide(launch); }

    private void keepUnsupportedInMain(Slot slot, String pkg, String reason) { launcher.keepUnsupportedInMain(slot, pkg, reason); }

    /**
     * TaskViewTaskController keeps mTaskNotFound after an idle task vanished without a token.
     * Reusing that controller otherwise makes the framework remove the next task immediately.
     */
    void resetIdleTaskView(Slot slot) { taskEvents.resetIdleTaskView(slot); }





    /** 与气泡同原语：IStatusBarService.hideCurrentInputMethodForBubbles(displayId)（BC L776）。 */
    void hideIme() { winCtl.hideIme(); }

    // ---------------------------------------------------------------- 启动 / 关闭任务

    void launchPackage(int slotIndex, String pkg) { launcher.launchPackage(slotIndex, pkg); }

    private void launchComponent(int slotIndex, ComponentName cn) { launcher.launchComponent(slotIndex, cn); }

    private void launchIntoSlot(final int slotIndex, Intent launch, String label) { launcher.launchIntoSlot(slotIndex, launch, label); }

    private void chooseExistingOrLaunch(Slot slot, Intent launch, String label, String pkg) { launcher.chooseExistingOrLaunch(slot, launch, label, pkg); }

    void startSlot(final Slot slot, Intent launch, String label) { taskEvents.startSlot(slot, launch, label); }

    static void callHidden(Object target, String name, Object... args) { HostLaunchController.callHidden(target, name, args); }

    static Method findStartActivity(Class<?> cls) { return HostLaunchController.findStartActivity(cls); }

    // ---------------------------------------------------------------- 任务状态 / 密度 / 可见性

    private Object makeListener(final int slotIndex) { return taskEvents.makeListener(slotIndex); }

    private void onTaskIdKnown(int slotIndex, int taskId) { taskEvents.onTaskIdKnown(slotIndex, taskId); }

    /** 目标机 TaskView 的主要状态回调：taskId / token / 可见性都从这里取。 */
    void onTaskInfo(int slotIndex, Object info) { taskEvents.onTaskInfo(slotIndex, info); }


    private void onTaskVisibility(int slotIndex, int taskId, boolean visible) { taskEvents.onTaskVisibility(slotIndex, taskId, visible); }

    java.util.List<MiStepSideClose> closingTasks(Object info) { return taskEvents.closingTasks(info); }

    private void onTaskRemovalStarted(int slotIndex, int taskId) { taskEvents.onTaskRemovalStarted(slotIndex, taskId); }

    /**
     * v0.9.9：副格是"栈"——某个格子空了，后面的占用格子自动往上排（官方 SideAreaStackView 的紧凑排列）。
     */
    void compactCells() { cellLayout.compactCells(); }

    void requestCompaction() { cellLayout.requestCompaction(); }

    private void finishCellLayout() { cellLayout.finishCellLayout(); }

    private boolean cellMoveCurrent(Slot slot, FrameLayout movingRoot, long epoch, long binding,            int id, Object token, Object controller, View movingView, Object surface) { return cellLayout.cellMoveCurrent(slot, movingRoot, epoch, binding, id, token, controller, movingView, surface); }

    static int compactionHintMask(int initialEmptySlots, int occupiedSlots) { return HostCellLayoutController.compactionHintMask(initialEmptySlots, occupiedSlots); }

    private void animateCellMoves(java.util.List<CellMovePlan> plans, int initialEmptySlots) { cellLayout.animateCellMoves(plans, initialEmptySlots); }

    /** 诊断用：从 RunningTaskInfo 里取 appCompatTaskInfo.topActivityAppBounds（px）。 */
    static String appBoundsOf(Object info) { return HostStaticUtil.appBoundsOf(info); }

    /** An external fullscreen task can be reordered above our roots without killing them. */
    void scheduleSideVisibilityRepair(Slot slot) { taskEvents.scheduleSideVisibilityRepair(slot); }

    /** Idempotently show every live side TaskView after a task-set change. */
    void reassertSideTaskViews(String reason) { taskEvents.reassertSideTaskViews(reason); }

    boolean visibilityRepairCurrent(Slot slot, long epoch, long binding, int id, Object token) { return taskEvents.visibilityRepairCurrent(slot, epoch, binding, id, token, false); }

    String describeTasks() { return winCtl.describeTasks(); }

    boolean executeOnShell(Runnable task) { return winCtl.executeOnShell(task); }

    // ---------------------------------------------------------------- v0.9 等比缩小（自绘矩阵）

    float mainScale() { return winCtl.mainScale(); }

    float cellScale() { return winCtl.cellScale(); }

    /** Only side tasks are hosted; the system handles main-screen scaling. */
    float scaleFor(Slot slot) { return winCtl.scaleFor(slot); }

    /**
     * 把任务内容按 k 等比缩放显示到槽位里（官方 DisplayContentSmtEx.applyZoomedMatrixSmt 的等价做法）。
     * 任务窗口保持满屏（应用按整屏手机排版），只对承载表面做矩阵缩放，视觉就是"整屏缩小"。
     */
    void applySlotScale(Slot slot) { winCtl.applySlotScale(slot); }

    void reapplyScale(Slot slot) { winCtl.reapplyScale(slot); }

    /** 把任务边界改回整屏（0,0,1080,2460），应用即按"整屏手机"排版。 */
    void applyFullscreenBounds(Slot slot) { winCtl.applyFullscreenBounds(slot); }

    /** 统一施加"缩放 + 归位"：leash 需要 setPosition(0,0)，容器表面不能动位置。 */
    private boolean applyScaleTransform(Slot slot, Object sc) { return winCtl.applyScaleTransform(slot, sc); }

    /** A late mode6 finish must never pull a task selected by Launcher back into a cell. */
    boolean mayWriteSideSurface(Slot slot) { return surfaceCtl.mayWriteSideSurface(slot); }

    /** 按字段名取 SurfaceControl（先本类后父类；取不到返回 null）。 */
    static Object getSurfaceField(Object target, String name) { return HostSurfaceController.getSurfaceField(target, name); }

    boolean applyWct(String what, Object wct) { return winCtl.applyWct(what, wct); }

    private Object findWctApplier(Object wct) { return winCtl.findWctApplier(wct); }

    // ---------------------------------------------------------------- v1.0.4：无限小窗 / 退出保留副窗（跨 controller 入口）
    // 侧栏滚动整簇在 HostSideScrollController；退出保留在 HostLifecycleController；
    // 归还路径在 HostAdoptionController。下面只是按需放宽的委托 shim。

    boolean presentsSlot(Slot slot) { return sideScroll.presentsSlot(slot); }
    boolean isInSideViewport(float x, float y) { return sideScroll.isInSideViewport(x, y); }
    boolean trackSideScroll(MotionEvent event) { return sideScroll.trackSideScroll(event); }
    boolean isSideMotionRunning() { return sideScroll.isSideMotionRunning(); }
    void finishSideScroll() { sideScroll.finishSideScroll(); }
    boolean sideRowInPlace(Slot slot) { return sideScroll.sideRowInPlace(slot); }
    void revealSideTaskForDesktop(Slot slot, android.os.Bundle binding) { sideScroll.revealSideTaskForDesktop(slot, binding); }
    void mapSideSlots(int firstRow) { sideScroll.mapSideSlots(firstRow); }
    void layoutSideViewport(boolean all) { sideScroll.layoutSideViewport(all); }
    void observeSideViewportRoot(View view) { sideScroll.observeSideViewportRoot(view); }
    boolean ensureSideViewport() { return sideScroll.ensureSideViewport(); }
    void attachSideViewport(Slot slot, Object surface) { sideScroll.attachSideViewport(slot, surface); }
    boolean clipToSideViewport(Rect rect) { return sideScroll.clipToSideViewport(rect); }
    void applySideRow(Slot slot, float offset, boolean scrolling) { sideScroll.applySideRow(slot, offset, scrolling); }
    boolean compactScrollingSideRows() { return sideScroll.compactScrollingSideRows(); }
    boolean prepareSideRowTap(Slot slot) { return sideScroll.prepareSideRowTap(slot); }
    void updateSideInputViewportBounds() { sideScroll.updateSideInputViewportBounds(); }
    boolean retainedResumeStaging() { return lifecycle.retainedResumeStaging(); }
    void hideReturningTaskSurface(Object surface) { lifecycle.hideReturningTaskSurface(surface); }
    Object currentOrganizedTaskInfo(Slot slot, int taskId, Object token) { return taskEvents.currentOrganizedTaskInfo(slot, taskId, token); }
    void appendSlotViewport(Object tx, Slot slot) { winCtl.appendSlotViewport(tx, slot); }
    void appendTaskViewExitOverrides(Object wct, Promotion ticket) throws Exception { promotionController.appendTaskViewExitOverrides(wct, ticket); }
    void restoreMainOpacity(Promotion ticket, String operation, boolean retainedReturn) { promotionController.restoreMainOpacity(ticket, operation, retainedReturn); }
    void restoreRetainedMainOpacityAfterHandoff(Promotion ticket) { adoption.restoreRetainedMainOpacityAfterHandoff(ticket); }
    void releaseRetainedTaskToMain(Slot slot, boolean nativeAlreadyFullscreen) { adoption.releaseRetainedTaskToMain(slot, nativeAlreadyFullscreen); }
    void finishExternalTopLaunch(Slot upper) { adoption.finishExternalTopLaunch(upper); }
    boolean sideTaskMatchesEntry(Slot slot, int user, ComponentName entry) { return adoption.sideTaskMatchesEntry(slot, user, entry); }
    void scheduleSideVisibilityRepair(Slot slot, boolean warming) { taskEvents.scheduleSideVisibilityRepair(slot, warming); }
    void refreshEmptyHintBackdrop(Slot slot) { chrome.refreshEmptyHintBackdrop(slot); }
    LiveTask findPackageTask(String pkg, ComponentName entry) throws Exception { return launcher.findPackageTask(pkg, entry); }

    static void log(String msg) { HostStaticUtil.log(msg); }
}







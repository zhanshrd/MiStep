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

    private static final String DESKTOP_ICON_LAUNCH_ACTION =
            "com.jiusis.mistep.DESKTOP_ICON_LAUNCH";

    private static final String TAG = "MiStepProbe";
    static final int SLOT_MAIN = 0;
    static final int CELL_SLOT_COUNT = 1 + MiStepLayout.CELL_COUNT;
    static final int SLOT_TOP = CELL_SLOT_COUNT;
    static final int SLOT_COUNT = CELL_SLOT_COUNT + 1;
    static final String TOP_TASK_STATE_ACTION = "com.jiusis.mistep.TOP_TASK_STATE";
    private static final long LAUNCH_TIMEOUT_MS = 4000L;
    static final long SIDE_PROMOTION_TIMEOUT_MS = 3500L;

    static volatile MiStepHost sInstance;

    final Context ctx;
    private final Executor executor;
    private final Object factory;
    final Object transitions;
    final Handler main = new Handler(Looper.getMainLooper());

    final Slot[] slots = new Slot[SLOT_COUNT];

    volatile MiStepLayout layout;
    FrameLayout root;
    WindowManager wm;
    WindowManager.LayoutParams lp;
    DisplayManager displayManager;
    private DisplayManager.DisplayListener displayListener;
    volatile int displayRotation = -1;
    boolean geometryRefreshQueued;
    int geometryRefreshDeferrals;
    boolean geometryRefreshAfterLive;
    private int lpY = 0;
    boolean zoomed;
    volatile int screenW = 1080;
    volatile int screenH = 2460;
    private final Object taskUsersLock = new Object();
    final Object taskWindowStatesLock = new Object();
    final java.util.Map<Integer, TaskWindowState> taskWindowStates =
            new java.util.HashMap<>();
    private volatile java.util.Set<Integer> taskUserIds;
    private volatile long taskUserIdsReadAt;
    private volatile String taskUserIdsReadError;
    volatile boolean screenOn = true;
    private Slot launchCurrentSlot;
    Promotion promotion;
    SwapPreview swapPreview;
    Object swapPreviewStatusOwner;
    final MiStepStatusBar statusBar;
    private final ZoomTransport zoomTransport;
    final HostMirrorController mirrorController;
    final HostSwapController swapController;
    final HostPromotionController promotionController;
    volatile HomeTaskReturn coveredHome;
    CellMovePreview cellMovePreview;
    Bitmap mainBackdrop;
    View topStripBacking;
    Bitmap wallpaperBackdrop;
    boolean wallpaperBackdropRequested;
    BroadcastReceiver wallpaperReceiver;
    private BroadcastReceiver desktopIconReceiver;
    private boolean mainBackdropRequested;
    /** mirror 承载时主区显示的桌面/上一主区快照（避免隐藏任务后主区全黑）。 */
    View carrierBackdrop;
    int carrierSlotIndex = -1;
    MiStepTopBar topBar;
    final HostTopTaskController topCtrl;
    final HostLifecycleController lifecycle;
    final HostAdoptionController adoption;
    final HostChromeController chrome;
    final HostCellLayoutController cellLayout;
    final HostSurfaceController surfaceCtl;
    final HostGeometryController geometry;
    final HostDebugController debugCtrl;
    private View sideBacking;
    /** 侧栏按格分段的不透明底板；承载格需要把它藏起来让下面的真实任务画面透出来。 */
    final View[] sideBackingViews = new View[SLOT_COUNT];
    boolean mainOnRight;
    private View dragGhost;
    private boolean dragMoved;
    private float dragStartRawX;
    private float dragStartRawY;
    private Slot dragTarget;
    View keyguardMask;
    boolean keyguardLocked;
    static final int ACTIVE = 0, SUSPENDING = 1, SUSPENDED = 2, RESUMING = 3;
    volatile int scene = ACTIVE;
    long sceneEpoch;
    boolean suspendRequested;
    boolean landscapeSuspended;
    boolean transferBusy;
    boolean overlayRefreshScheduled;
    static boolean boundsHooksReady;
    static boolean surfaceHooksReady;
    Object surfaceObserver;
    volatile boolean traceNextSwap;
    volatile MiStepSwapTrace swapTrace;
    boolean compactPending;
    MiStepCellMotion cellMotion;
    MiStepSideDismiss sideDismiss;
    Slot sideDismissSlot;
    static final class CellMovePlan {
        final Slot slot;
        final Rect from;
        CellMovePlan(Slot slot, Rect from) { this.slot = slot; this.from = from; }
    }
    volatile boolean liveSwapInputBlocked;
    MiStepTransitionInput transitionInput;
    private final android.os.IBinder desktopSceneOwner = new android.os.Binder();
    private volatile long desktopRegistrationSequence;
    final FinishTransactions<RenderBinding> finishTransactions = new FinishTransactions<>(16);
    final FinishTransactions<Long> workspaceFinishes = new FinishTransactions<>(16);

    final java.util.Map<Object, OwnedTask> ownedTasks = new java.util.HashMap<>();

    static final class Slot implements SlotPositions.Entry {
        int index;
        View view;
        View hint;
        Object controller;
        int taskId = -1;
        Object taskToken;
        int[] rect;
        int windowMode = -1;
        TaskWindowState windowState;
        boolean occupied;
        boolean taskVisible;
        boolean reassertPending;
        long modeGuardUntil;
        int modeRepairAttempts;
        boolean modeAdmissionPending;
        final VisibilityRepair visibilityRepair = new VisibilityRepair();
        // v0.9 缩放渲染：任务按满屏排版，靠矩阵缩到格子里
        Object scaleSurface;
        Object cellBackground;
        Object cellBackgroundParent;
        int cellBackgroundWidth;
        int cellBackgroundHeight;
        boolean scaleOnLeash;
        int scaleTaskId = Integer.MIN_VALUE;
        float scaleApplied = -1f;
        int scaleAttempts;
        int taskUid;
        String pkg;
        // A tap swaps; an outward swipe releases this exact task to the background.
        MotionEvent bufferedDown;
        long touchTaskEpoch, touchSceneEpoch;
        boolean injecting;
        volatile boolean promoting;
        boolean launching;
        int retiredTaskId = -1;
        long taskEpoch;
        int removalCheckTask = -1;
        volatile boolean parked;
        volatile boolean frozen;
        volatile boolean closing;
        volatile boolean nativeReleasePending;
        // fullscreen-only 应用的 mirror 承载：任务仍是满屏前台，画面被移出屏幕，
        // 副本镜像显示在格子里；点击格子把原任务移回主区。
        int carrierTaskId = -1;
        Object carrierLeash;
        Object carrierMirror;
        View carrierTapView;
        boolean carrier;
        PendingSidePromotion pendingSidePromotion;

        Slot(int index) {
            this.index = index;
        }

        public int position() { return index; }
        public void position(int value) { index = value; }
    }

    static final class PendingSidePromotion {
        final Slot slot;
        final int taskId;
        final Object token;
        final long sceneEpoch;
        final long bindingEpoch;
        final long deadline;
        final String pkg;
        boolean desktopLaunch;
        int attempts;

        PendingSidePromotion(Slot slot, long sceneEpoch, boolean desktopLaunch, long deadline) {
            this.slot = slot;
            taskId = slot.taskId;
            token = slot.taskToken;
            this.sceneEpoch = sceneEpoch;
            bindingEpoch = slot.taskEpoch;
            pkg = slot.pkg;
            this.desktopLaunch = desktopLaunch;
            this.deadline = deadline;
        }
    }

    static final class OwnedTask {
        final int id;
        final Object token;
        Object surface;
        Object recoveryController;
        volatile int userId = -1;
        boolean recoveryCandidate;
        OwnedTask(int id, Object token) { this.id = id; this.token = token; }
    }

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

    NativeObservation observeNativeCommit(Object token, Runnable check) {
        NativeObservation observation = new NativeObservation(token, check);
        pendingNativeObservation = observation;
        main.postDelayed(observation, 350L); // Fallback if the target omits the transition/commit callback.
        return observation;
    }

    void cancelNativeObservation(NativeObservation observation) {
        if (observation == null) return;
        observation.consumed.set(true);
        main.removeCallbacks(observation);
        if (pendingNativeObservation == observation) pendingNativeObservation = null;
    }

    static final class Promotion {
        final Slot slot;
        final int taskId;
        final Object token;
        final java.util.List<Object> otherSideTokens = new java.util.ArrayList<>();
        volatile boolean cancelled;
        volatile boolean handoffAttempted;
        volatile boolean handoffComplete;
        boolean background;
        boolean externalLaunch;
        boolean nativeSwap;
        boolean nativeFlip;
        boolean nativeHome;
        boolean nativeCollect;
        Runnable notificationLaunch;
        long liveZoomGuardUntil;
        MiStepLayout previousLayout;
        MiStepLayout targetLayout;
        boolean candidateRecovery;
        boolean liveMainReveal;
        Object liveRevealSurface;
        volatile boolean nativeSubmitted;
        volatile boolean cancelRequested;
        volatile boolean nativeCompleting;
        boolean liveMainFocusPrepared;
        int liveMainUserId = -1;
        PromotionFocusTransfer.Result liveMainFocusResult;
        LiveSwapVisualLease liveVisual;
        MiStepLiveBackdrop liveBackdrop;
        final java.util.List<LiveSwapVisualLease> peerVisuals = new java.util.ArrayList<>();
        final java.util.List<LivePeerBinding> livePeers = new java.util.ArrayList<>();
        OwnedTask previousMainCandidate;
        LiveTask previousMain;
        LiveTask homeBeforeCover;
        LiveTask collectionHome;
        boolean previousMainOnRight;
        boolean targetMainOnRight;
        LiveLiftPreview liveLift;
        /** 交换时主任务（previousMain）的真实 leash：桥上给它挖洞做 view 级缩小动画。 */
        Object mainLeash;
        Bitmap compactSnapshot;
        Rect compactSnapshotRect;
        long sceneEpoch;
        long bindingEpoch;

        Promotion(Slot slot, Slot[] slots) {
            this.slot = slot;
            this.taskId = slot.taskId;
            this.token = slot.taskToken;
            for (Slot other : slots) {
                // A parked/frozen or already hidden cell is not a live peer that can
                // block promotion. Requiring it visible caused spurious rollbacks
                // after the user returned from Home with a stale side binding.
                if (other != slot && other.occupied && !other.parked && !other.frozen
                        && other.taskVisible && other.taskToken != null) {
                    otherSideTokens.add(other.taskToken);
                }
            }
        }
    }

    static final class LivePeerBinding {
        final Slot slot;
        final long epoch;
        final int id;
        final Object token, controller;
        LivePeerBinding(Slot slot) {
            this.slot = slot; epoch = slot.taskEpoch; id = slot.taskId;
            token = slot.taskToken; controller = slot.controller;
        }
    }

    /** Hide only the selected cell's Host drawings; its SurfaceView stays attached. */
    static final class LiveSwapVisualLease {
        private static final class AlphaHold {
            int count;
            float alpha;
            AlphaHold(float alpha) { this.alpha = alpha; }
        }
        private static final java.util.IdentityHashMap<View, AlphaHold> held = new java.util.IdentityHashMap<>();
        final View backing, hint;
        final float backingAlpha, hintAlpha;
        final int backingVisibility, hintVisibility;
        boolean restored;
        LiveSwapVisualLease(View backing, View hint) {
            this.backing = backing; this.hint = hint;
            backingAlpha = backing == null ? 1f : backing.getAlpha();
            hintAlpha = hint == null ? 1f : hint.getAlpha();
            backingVisibility = backing == null ? View.GONE : backing.getVisibility();
            hintVisibility = hint == null ? View.GONE : hint.getVisibility();
            acquire(backing);
            acquire(hint);
        }
        private static void acquire(View view) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold == null) { hold = new AlphaHold(view.getAlpha()); held.put(view, hold); }
            hold.count++; view.setAlpha(0f);
        }
        static void writeAlpha(View view, float alpha) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold != null) hold.alpha = alpha;
            else view.setAlpha(alpha);
        }
        private static void release(View view) {
            if (view == null) return;
            AlphaHold hold = held.get(view);
            if (hold != null && --hold.count == 0) {
                held.remove(view); view.setAlpha(hold.alpha);
            }
        }
        void restore() {
            if (restored) return;
            restored = true;
            // Nested handoffs and late fade callbacks cannot resurrect shared chrome.
            // Restore the latest requested alpha only when the last owner releases it.
            release(backing);
            release(hint);
        }
    }

    static final class LiveTask {
        final int id;
        final Object token;
        final String pkg;
        LiveTask(Object info) {
            id = XposedHelpers.getIntField(info, "taskId");
            token = XposedHelpers.getObjectField(info, "token");
            ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            pkg = base == null ? null : base.getPackageName();
        }
        LiveTask(Slot slot) {
            this.id = slot.taskId;
            this.token = slot.taskToken;
            this.pkg = slot.pkg;
        }
        LiveTask(int id, Object token, String pkg) {
            this.id = id;
            this.token = token;
            this.pkg = pkg;
        }
        boolean matches(Object info) {
            return info != null && id == XposedHelpers.getIntField(info, "taskId")
                    && token != null && token.equals(XposedHelpers.getObjectField(info, "token"));
        }
    }

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
        chrome = new HostChromeController(this);
        cellLayout = new HostCellLayoutController(this);
        surfaceCtl = new HostSurfaceController(this);
        geometry = new HostGeometryController(this);
        debugCtrl = new HostDebugController(this);
        installLogicalBoundsHooks(ctx.getClassLoader());
        installRetainedSurfaces(ctx.getClassLoader());
        for (int i = 0; i < SLOT_COUNT; i++) {
            slots[i] = new Slot(i);
        }
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
                h.traceNextSwap = false;
                MiStepSwapTrace trace = h.swapTrace;
                if (trace != null) trace.close();
                h.closeSwapPreview();
                h.statusBar.restore();
                h.closeCellMovePreview();
                // Locking the device ends Mi Step instead of retaining a magnified lock screen.
                h.suspendSession("screen-lock");
            } else {
                h.updateTopBarState();
            }
        });
    }

    public static void onUserPresent() {
        MiStepHost h = sInstance;
        if (h != null) h.main.post(h::leaveKeyguardPrivacy);
    }

    public static void onSystemNavigation(String reason) {
        MiStepHost h = sInstance;
        if (h != null) h.main.post(() -> {
            if (NativeLandscapePolicy.shouldRestoreFromHome(
                    h.landscapeSuspended, h.scene == SUSPENDED, reason)) {
                h.resumeSession();
            } else {
                h.suspendSession(reason);
            }
        });
    }

    /** Native fullscreen uses the existing retained-task suspend/resume lifecycle. */
    public static void onLandscapeMode(boolean fullscreen) {
        MiStepHost h = sInstance;
        if (h == null) return;
        h.main.post(() -> {
            if (fullscreen) {
                h.landscapeSuspended = true;
                if (h.scene == ACTIVE) h.suspendSession("native-landscape");
            } else {
                h.landscapeSuspended = false;
                h.resumeSession();
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

    public static void setPendingMainSide(boolean mainOnRight) {
        MiStepHost host = sInstance;
        // 过渡期间（RESUMING/SUSPENDING）的滑动不得改写下一次打开的方向：
        // consumePendingMainSide 已在恢复会话时消费，晚到的写入会污染下次打开的朝向。
        // ACTIVE 时热区手势本就在 HotZoneGesture 源头被丢弃，此守卫为防御性冗余；
        // 关闭工作区走顶栏 ✕ / TOGGLE 广播 / Home、Recents / 息屏，没有“再滑一次关闭”。
        if (host != null && host.scene != SUSPENDED) return;
        pendingMainSide = mainOnRight ? 1 : 0;
    }

    void consumePendingMainSide() {
        int pending = pendingMainSide;
        if (pending < 0) return;
        pendingMainSide = -1;
        boolean right = pending == 1;
        if (right != mainOnRight) {
            log("pending main side applied: " + (right ? "right" : "left"));
            setMainSide(right);
        }
    }

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
    static final ThreadLocal<Object> closeFinishTransaction = new ThreadLocal<>();
    private static void installLogicalBoundsHooks(ClassLoader cl) { HostGeometryController.installLogicalBoundsHooks(cl); }

    Rect logicalBounds() { return geometry.logicalBounds(); }
    Rect logicalBounds(Slot slot) { return geometry.logicalBounds(slot); }
    Slot slotForObject(Object object) { return geometry.slotForObject(object); }
TopTaskGeometry topGeometry() {
        return new TopTaskGeometry(screenW,screenH,layout.topStrip,DeviceProfile.statusBarHeightPx(ctx),
                ctx.getResources().getDisplayMetrics().density);
    }
    /** Keep app metrics in the same logical viewport as the hosted surface and input. */
    void applyTopConfiguration(Object wct, Object token, Slot slot) {
        if (slot == null || slot.index != SLOT_TOP) return;
        TopTaskGeometry geometry = topGeometry();
        XposedHelpers.callMethod(wct, "setAppBounds", token, logicalBounds(slot));
        // Inherit native density so retained app Views never mix cached px at two DPIs.
        XposedHelpers.callMethod(wct, "setDensityDpi", token, 0);
        int widthDp = Math.max(1, Math.round(geometry.logicalWidth * 160f / geometry.densityDpi));
        int heightDp = Math.max(1, Math.round(geometry.logicalHeight * 160f / geometry.densityDpi));
        XposedHelpers.callMethod(wct, "setScreenSizeDp", token, widthDp, heightDp);
        XposedHelpers.callMethod(wct, "setSmallestScreenWidthDp", token, Math.min(widthDp, heightDp));
    }
    int[] slotBounds(Slot slot) {
        return slot.index==SLOT_TOP ? topGeometry().bounds() : layout.cellBounds(slot.index-1);
    }

    private java.util.Set<Integer> supportedTaskUsers() {
        long now = SystemClock.uptimeMillis();
        java.util.Set<Integer> cached = taskUserIds;
        if (cached != null && now - taskUserIdsReadAt < 5000L) return cached;
        synchronized (taskUsersLock) {
            cached = taskUserIds;
            now = SystemClock.uptimeMillis();
            if (cached != null && now - taskUserIdsReadAt < 5000L) return cached;
            java.util.Set<Integer> ids = new java.util.HashSet<>();
            int currentUser = currentUserId();
            ids.add(currentUser);
            try {
                UserManager users = ctx.getSystemService(UserManager.class);
                if (users != null) {
                    java.util.List<UserHandle> profiles = users.getUserProfiles();
                    if (profiles != null) {
                        for (UserHandle profile : profiles) {
                            if (profile != null) {
                                ids.add((Integer) XposedHelpers.callMethod(profile, "getIdentifier"));
                            }
                        }
                    }
                }
                taskUserIdsReadError = null;
            } catch (Throwable error) {
                String message = String.valueOf(error);
                if (!message.equals(taskUserIdsReadError)) {
                    log("associated task profiles unavailable; owner only: " + message);
                    taskUserIdsReadError = message;
                }
            }
            taskUserIds = java.util.Collections.unmodifiableSet(ids);
            taskUserIdsReadAt = now;
            log("task profile scope current=" + currentUser + " associated=" + ids);
            return taskUserIds;
        }
    }

    boolean supportsTaskUser(int userId) {
        java.util.Set<Integer> ids = supportedTaskUsers();
        return TaskUserPolicy.allows(userId, currentUserId(), ids);
    }

    int currentUserId() {
        try {
            return (Integer) XposedHelpers.callStaticMethod(
                    Class.forName("android.app.ActivityManager"), "getCurrentUser");
        } catch (Throwable ignored) {
            return (Integer) XposedHelpers.callStaticMethod(UserHandle.class, "getUserId",
                    android.os.Process.myUid());
        }
    }

    boolean ownsViewOrController(Object object) {
        if (object == null) return false;
        for (Slot slot : slots)
            if (slot.index > 0 && !slot.parked && (slot.view == object || slot.controller == object)) return true;
        return false;
    }

    /** External mode1 CHANGE belongs to Launcher, not TaskView's immediate side reparent. */
    boolean leaveExternalChangeToNative(Object change) {
        if (change == null || scene != ACTIVE || !screenOn || keyguardLocked || transferBusy) return false;
        try {
            int mode = (Integer) XposedHelpers.callMethod(change, "getMode");
            if (mode != 6 && mode != 3) return false;
            Object info = XposedHelpers.callMethod(change, "getTaskInfo");
            if (!ordinaryRoot(info, 1)) return false;
            int taskId = XposedHelpers.getIntField(info, "taskId");
            Object token = XposedHelpers.getObjectField(info, "token");
            for (Slot slot : slots) {
                if (slot.index <= 0 || !isActive(slot) || !slot.occupied || slot.parked
                        || slot.frozen || slot.launching || slot.modeAdmissionPending
                        || slot.taskId != taskId || token == null || !token.equals(slot.taskToken)
                        || !controllerOwns(slot, taskId, token)) continue;
                Promotion current = promotion;
                if (current != null && (!current.externalLaunch || current.slot != slot
                        || current.sceneEpoch != sceneEpoch || current.bindingEpoch != slot.taskEpoch))
                    return false;
                Object queue = XposedHelpers.getObjectField(transitions, "mPending");
                if (!(queue instanceof Iterable<?>)) return false;
                for (Object pending : (Iterable<?>) queue)
                    if (XposedHelpers.getObjectField(pending, "mTaskView") == slot.controller) return false;
                Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                    Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                            organizer, "mTasks")).get(taskId);
                    Object canonical = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                    if (!ordinaryRoot(canonical, 1) || !token.equals(XposedHelpers.getObjectField(canonical, "token"))
                            || XposedHelpers.getIntField(canonical, "taskId") != taskId
                            || XposedHelpers.getIntField(canonical, "userId") != XposedHelpers.getIntField(info, "userId"))
                        return false;
                }
                log("external fullscreen change kept native task=" + taskId + " slot=" + slot.index);
                return true;
            }
        } catch (Throwable unavailable) { log("external native change guard unavailable: " + unavailable); }
        return false;
    }

    java.util.List<?> rootInfos() throws Exception {
        Object service = XposedHelpers.callStaticMethod(Class.forName("android.app.ActivityTaskManager"), "getService");
        return (java.util.List<?>) XposedHelpers.callMethod(service, "getAllRootTaskInfos");
    }

    Object rootInfo(LiveTask task) throws Exception {
        for (Object info : rootInfos()) if (task.matches(info)) return info;
        return null;
    }

    boolean ordinaryRoot(Object info, int mode) {
        if (info == null || XposedHelpers.getIntField(info, "displayId") != 0
                || !supportsTaskUser(XposedHelpers.getIntField(info, "userId"))
                || Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"))
                || !Integer.valueOf(mode).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getActivityType"))) return false;
        ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
        return base != null && !"com.miui.home".equals(base.getPackageName());
    }

    /** Some associated-profile roots are focused while the ROM reports isVisible=false. */
    boolean mainTaskVisible(Object info) {
        return info != null && TaskUserPolicy.mayBeMainTask(
                XposedHelpers.getIntField(info, "userId"), currentUserId(), supportedTaskUsers(),
                XposedHelpers.getBooleanField(info, "isVisible"),
                XposedHelpers.getBooleanField(info, "isFocused"), false);
    }

    boolean isSideToken(Object token) {
        for (Slot slot : slots) if (!slot.parked && token != null && token.equals(slot.taskToken)) return true;
        return false;
    }

    /** 诊断：打印当前所有 root task 的判定字段（findMainTask 漏判用）。 */
    void dumpRoots(String reason) { debugCtrl.dumpRoots(reason); }

    /** 调试广播入口：打印 root 诊断。 */
    public static void debugRoots() { HostDebugController.debugRoots(); }

    LiveTask findMainTask() throws Exception {
        // A closing fullscreen app may remain visible while Home has already taken
        // focus. Visibility alone cannot make that departing app a swap participant.
        // If both claim focus the scene is still changing: retain both task bindings.
        LiveTask candidate = null;
        LiveTask focusedCandidate = null;
        boolean focusedHome = false;
        int visibleCandidates = 0;
        java.util.List<?> roots = rootInfos();
        for (Object info : roots) {
            if (isHomeRoot(info) && XposedHelpers.getBooleanField(info, "isVisible")
                    && XposedHelpers.getBooleanField(info, "isFocused")) focusedHome = true;
            if (!ordinaryRoot(info, 1)) continue;
            int userId = XposedHelpers.getIntField(info, "userId");
            boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
            boolean focused = XposedHelpers.getBooleanField(info, "isFocused");
            boolean sideTask = isSideToken(XposedHelpers.getObjectField(info, "token"));
            if (!TaskUserPolicy.mayBeMainTask(userId, currentUserId(),
                    supportedTaskUsers(), visible, focused, sideTask)) continue;
            if (visible) visibleCandidates++;
            LiveTask task = new LiveTask(info);
            if (focused) {
                if (focusedCandidate != null)
                    throw new IllegalStateException("ambiguous focused main roots");
                focusedCandidate = task;
            }
            candidate = task;
            updateTaskWindowState(0, info, task.id, true);
        }
        if (focusedHome && focusedCandidate != null)
            throw new IllegalStateException("Home/app focus is changing; retain task bindings");
        if (focusedHome) return null;
        if (focusedCandidate != null) return focusedCandidate;
        if (visibleCandidates > 1) throw new IllegalStateException("ambiguous visible main roots");
        return candidate; // Home（type=2）不会被当主任务。
    }

    LiveTask findPackageTask(String pkg) throws Exception {
        LiveTask candidate = null;
        for (Object info : rootInfos()) {
            if (!TaskUserPolicy.isCurrentUser(
                    XposedHelpers.getIntField(info, "userId"), currentUserId())) continue;
            ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            if (base == null || !pkg.equals(base.getPackageName())) continue;
            if (!ordinaryRoot(info, 1) || isSideToken(XposedHelpers.getObjectField(info, "token")))
                throw new IllegalStateException("existing package task has unsupported owner/mode");
            if (candidate != null) throw new IllegalStateException("multiple package tasks; no implicit relaunch");
            candidate = new LiveTask(info);
        }
        return candidate;
    }

    LiveTask existingHomeTask() throws Exception {
        LiveTask home = null;
        for (Object info : rootInfos()) {
            if (!isHomeRoot(info)) continue;
            if (home != null) throw new IllegalStateException("ambiguous existing Home roots");
            home = new LiveTask(info);
        }
        if (home == null) throw new IllegalStateException("existing Home root unavailable");
        return home;
    }

    boolean isHomeRoot(Object info) {
        return info != null && Integer.valueOf(2).equals(
                    XposedHelpers.callMethod(info, "getActivityType"))
                && XposedHelpers.getIntField(info, "displayId") == 0
                && XposedHelpers.getIntField(info, "userId") == currentUserId()
                && !Boolean.TRUE.equals(XposedHelpers.callMethod(info, "hasParentTask"));
    }

    /** Reveal only the same Home that this app covered; never send a HOME launch intent. */
    LiveTask existingHomeForCollection(LiveTask task) throws Exception {
        LiveTask home = existingHomeTask();
        HomeTaskReturn saved = coveredHome;
        if (saved != null && saved.covers(task.id, task.token)
                && !saved.restores(home.id, home.token))
            throw new IllegalStateException("covered Home identity changed; keep app in main");
        return home;
    }

    Object homeForCollection(LiveTask task) throws Exception {
        return existingHomeForCollection(task).token;
    }



    void checkAdoption(Slot slot, LiveTask task, long epoch, int attempt,
            Runnable success, Consumer<String> failure) {
        if (!isActive(slot) || epoch != sceneEpoch) {
            adoptionFailed(slot, task, true, "scene changed during adoption", failure);
            return;
        }
        if (!executeOnShell(() -> {
            boolean ready = false;
            String detail;
            try {
                Object core = rootInfo(task);
                ready = ordinaryRoot(core, 6) && XposedHelpers.getBooleanField(core, "isVisible")
                        && controllerOwns(slot, task.id, task.token)
                        && getSurfaceField(slot.controller, "mTaskLeash") != null;
                detail = "core/controller/leash=" + ready;
            } catch (Throwable error) { detail = error.toString(); }
            final boolean confirmed = ready;
            final String result = detail;
            main.post(() -> {
                if (epoch != sceneEpoch) {
                    adoptionFailed(slot, task, true, "stale adoption completion", failure);
                } else if (confirmed) {
                    bindTask(slot, task.id, task.token);
                    slot.pkg = task.pkg;
                    slot.occupied = true;
                    slot.taskVisible = true;
                    slot.launching = false;
                    slot.windowMode = 6; // Confirmed from Core and this controller above.
                    slot.promoting = false;
                    slot.parked = false;
                    transferBusy = false;
                    updateHint(slot);
                    updateTouchRegion(slot);
                    reapplyScale(slot);
                    publishDesktopSideTasks();
                    log("adoption confirmed same task=" + task.id + " slot=" + slot.index);
                    success.run();
                    main.postDelayed(() -> {
                        compactCells();
                        // TaskView insertion can briefly hide sibling surfaces while the
                        // native transition settles. Re-show every already-owned cell
                        // without moving tasks or changing ownership.
                        main.postDelayed(() -> reassertSideTaskViews("adoption-settled"), 420L);
                    }, 120L);
                } else if (attempt < 2) {
                    main.postDelayed(() -> checkAdoption(slot, task, epoch, attempt + 1, success, failure), 500L);
                } else adoptionFailed(slot, task, true, result, failure);
            });
        })) adoptionFailed(slot, task, true, "no Shell queue for confirmation", failure);
    }







    static final class RenderBinding {
        final Slot slot;
        final int id;
        final Object token, leash, parent;
        final long scene, binding;
        RenderBinding(Slot slot, long scene, Object leash, Object parent) {
            this.slot = slot; id = slot.taskId; token = slot.taskToken;
            this.scene = scene; binding = slot.taskEpoch; this.leash = leash; this.parent = parent;
        }
    }

    private void installRetainedSurfaces(ClassLoader cl) { surfaceCtl.installRetainedSurfaces(cl); }

    private void trackRetainedSurfaces(Object info, Object finish) { surfaceCtl.trackRetainedSurfaces(info, finish); }

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


    void setZoomState(boolean on) {
        zoomed = on;
        HostEntry.setHostZoomed(on);
        MiStepStatusBarSampling.geometryChanged();
        HotZoneGesture.raiseAll(ctx);
    }

    /**
     * 写缩放触发文件（SystemUI 私有目录，system_server 侧轮询）。
     * 呼出/退出为 300ms 整屏缩放动画（system_server 侧 2.2.4+ 的 anim 命令）；
     * 动画结束后再写一次稳态续租，避免 4 秒租约把进行中的动画取消。
     */
    static final long ZOOM_ANIM_MS = 300L;
    android.animation.ValueAnimator workspaceAnimator;
        long workspaceGeneration;
    float workspaceAlpha = 1f;
    boolean workspaceEntering;




    /** 动画期间宿主不得写普通稳态（会取消动画）；心跳按此时间窗跳过。 */
    volatile long zoomAnimUntil;
    long zoomCommandGeneration;

    private String zoomOnCommand() { return zoomTransport.zoomOnCommand(); }

    void writeZoomAnimated(boolean on) { zoomTransport.writeZoomAnimated(on); }

    void writeZoom(boolean on) { zoomTransport.writeZoom(on); }

    private void writeZoomLine(String command) { zoomTransport.writeZoomLine(command); }

    private void writeZoomLine(String command, long issuedAt) { zoomTransport.writeZoomLine(command, issuedAt); }
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
    static boolean beginTopGesture() { return HostTopTaskController.beginTopGesture(); }
    static void moveTopGesture(float displacement, long time) { HostTopTaskController.moveTopGesture(displacement, time); }
    static void endTopGesture(boolean cancelled) { HostTopTaskController.endTopGesture(cancelled); }
    static void onTopTaskState(Intent intent) { HostTopTaskController.onTopTaskState(intent); }
    static boolean ownsTopNativeCaption(Object info) { return HostTopTaskController.ownsTopNativeCaption(info); }
    void resetTopForSuspend() { topCtrl.resetTopForSuspend(); }
    private void createTopTaskControls() { topCtrl.createTopTaskControls(); }
    void updateTopTaskGeometry() { topCtrl.updateTopTaskGeometry(); }
    private void openTopFace(boolean manual) { topCtrl.openTopFace(manual); }
    private void releaseTopTask(boolean front, boolean dismissed, Runnable after) { topCtrl.releaseTopTask(front, dismissed, after); }

    /** BUG-2 韧性：promotion/transfer 卡死时 suspend 会无限排队、cancel 路径存在静默 no-op。
     *  本看门狗独立于 scene 门禁（覆盖 ACTIVE/SUSPENDING/RESUMING 各种卡态）：
     *  >8s 温和取消一次（requestLiveSwapCancellation），>20s 强制 failPromotion 走正规
     *  失败汇总（回滚 WCT/清理槽位/触发 finishPendingSuspend），窗口期归零防连发。 */
    private long promotionWatchdogSince;
    private boolean promotionWatchdogNudged;

    void startPromotionWatchdog() {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (root == null || sInstance != MiStepHost.this) {
                    return;
                }
                if (promotion == null) {
                    promotionWatchdogSince = 0L;
                    promotionWatchdogNudged = false;
                } else {
                    long now = android.os.SystemClock.uptimeMillis();
                    if (promotionWatchdogSince == 0L) {
                        promotionWatchdogSince = now;
                    }
                    long stuckMs = now - promotionWatchdogSince;
                    if (stuckMs > 8000L && !promotionWatchdogNudged) {
                        promotionWatchdogNudged = true;
                        log("promotion watchdog: task=" + promotion.taskId + " stuck "
                                + (stuckMs / 1000L) + "s, requesting live-swap cancellation");
                        promotionController.requestLiveSwapCancellation(promotion, "watchdog");
                    }
                    if (stuckMs > 20000L) {
                        log("promotion watchdog: task=" + promotion.taskId + " stuck "
                                + (stuckMs / 1000L) + "s, forcing failPromotion");
                        promotionWatchdogSince = now;
                        promotionWatchdogNudged = false;
                        failPromotion(promotion, "promotion watchdog deadline");
                    }
                }
                main.postDelayed(this, 4000L);
            }
        }, 4000L);
    }

    private void createSubViews() {
        createNext(1);
    }

    private void createNext(final int index) {
        if (index >= SLOT_COUNT) {
            log("three side and one upper TaskView ready; system main region has no TaskView");
            return;
        }
        Consumer<Object> onCreated = new Consumer<Object>() {
            @Override
            public void accept(Object taskViewObj) {
                log("TaskView created slot=" + index + " -> " + taskViewObj);
                main.post(() -> onTaskViewCreated(index, taskViewObj));
            }
        };
        XposedHelpers.callMethod(factory, "create", ctx, executor, onCreated);
    }

    private void onTaskViewCreated(int index, Object taskViewObj) {
        try {
            Slot slot = slots[index];
            View view = (View) taskViewObj;
            slot.view = view;
            slot.controller = XposedHelpers.callMethod(view, "getController");
            slot.rect = slotBounds(slot);

            Object listener = makeListener(index);
            XposedHelpers.callMethod(view, "setListener", executor, listener);

            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1]);
            if (!boundsHooksReady) throw new IllegalStateException("logical bounds hooks unavailable");
            root.addView(view, lp);
            applyRect(slot);
            ensureCellBackground(slot);

            if(index==SLOT_TOP) {
                createTopTaskControls();
                updateHint(slot); updateTouchRegion(slot);
                log("upper landscape TaskView ready");
                createNext(index+1); return;
            }
            slot.hint = makeEmptyHint();
            slot.hint.setOnClickListener(v -> onSlotTapped(slot.index));
            root.addView(slot.hint, hintParams(slot));
            // 修：hint 在 applyRect 之后才创建，之前一直停在 (0,0)（屏幕上左上角那个多余的加号）。
            applyRect(slot);
            updateHint(slot);

            if (workspaceAlpha < 1f) setWorkspaceAlpha(workspaceAlpha);

            view.setOnClickListener(v -> onSlotTapped(slotIndexOfView(v)));
            view.setOnTouchListener((v, e) -> canRender(slot) && scene == ACTIVE && onSlotTouch(slot.index, e));
            updateTouchRegion(slot);
            log("slot " + index + " attached rect=" + java.util.Arrays.toString(slot.rect));
        } catch (Throwable t) {
            log("onTaskViewCreated slot=" + index + " FAILED: " + t);
            XposedBridge.log(t);
        }
        createNext(index + 1);
    }

    TextView makeEmptyHint() { return chrome.makeEmptyHint(); }

    void drawEmptyCellPlus(android.graphics.Canvas canvas, int width, int height,            int color, android.graphics.Paint plus) { chrome.drawEmptyCellPlus(canvas, width, height, color, plus); }

    private FrameLayout.LayoutParams hintParams(Slot slot) { return chrome.hintParams(slot); }

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

    void registerDefaultDisplayListener() {
        if (displayListener != null) return;
        try {
            displayManager = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
            if (displayManager == null) throw new IllegalStateException("DisplayManager unavailable");
            displayListener = new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    if (displayId == Display.DEFAULT_DISPLAY) scheduleDisplayGeometryRefresh();
                }
            };
            displayManager.registerDisplayListener(displayListener, main);
            log("default display geometry listener registered");
        } catch (Throwable error) {
            displayListener = null;
            log("default display geometry listener unavailable: " + error);
        }
    }

    void readDisplayGeometry() { geometry.readDisplayGeometry(); }

    void scheduleDisplayGeometryRefresh() { geometry.scheduleDisplayGeometryRefresh(); }

    private void refreshDisplayGeometry() { geometry.refreshDisplayGeometry(); }

    void refreshQuickApps() { chrome.refreshQuickApps(); }

    void updateTopBarState() { chrome.updateTopBarState(); }





    /** 只取桌面壁纸做高斯模糊底（不使用应用内容），供顶栏/侧栏格子的液态玻璃使用。 */
    void loadWallpaperBackdrop() { chrome.loadWallpaperBackdrop(); }

    void applyWallpaperBackdrops() { chrome.applyWallpaperBackdrops(); }

    /** 角部滑动起手在状态栏区域时 MIUI 通知栏/控制中心会被顺带拉出并盖住主区；打开一步后主动收起。 */
    void collapseStatusBarPanels() { chrome.collapseStatusBarPanels(); }

    /** 换壁纸后自动重算模糊底，避免一步里还是旧壁纸。 */
    private void registerWallpaperReceiver() { chrome.registerWallpaperReceiver(); }

    private void registerDesktopIconReceiver() {
        if (desktopIconReceiver != null) return;
        try {
            desktopIconReceiver = new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    if (intent != null && intent.getBooleanExtra("before", false)) {
                        preannounceDesktopLaunch(intent.getBundleExtra("binding"));
                        return;
                    }
                    String pkg = intent == null ? null : intent.getStringExtra("package");
                    promoteSideTaskForDesktopLaunch(pkg, intent == null ? -1 : intent.getIntExtra("userId", -1));
                }
            };
            IntentFilter filter = new IntentFilter(DESKTOP_ICON_LAUNCH_ACTION);
            ctx.registerReceiver(desktopIconReceiver, filter,
                    "android.permission.MANAGE_ACTIVITY_TASKS", main,
                    Context.RECEIVER_EXPORTED);
            log("desktop icon launch receiver registered");
        } catch (Throwable error) {
            desktopIconReceiver = null;
            log("desktop icon launch receiver failed: " + error);
        }
    }


    /** Publish only confirmed bindings; system_server rechecks each native task before use. */
    void publishDesktopSideTasks() {
        final long sequence = ++desktopRegistrationSequence;
        final long epoch = sceneEpoch;
        final java.util.List<Slot> owners = new java.util.ArrayList<>();
        for (Slot slot : slots) if (slot != null && slot.index > 0 && slot.index < CELL_SLOT_COUNT) owners.add(slot);
        final boolean active = scene == ACTIVE && screenOn && !keyguardLocked && root != null;
        final boolean keepRecents = root != null && scene != SUSPENDED;
        Runnable send = () -> {
            if (sequence != desktopRegistrationSequence || epoch != sceneEpoch) return;
            android.os.Bundle state = new android.os.Bundle();
            java.util.ArrayList<android.os.Bundle> rows = new java.util.ArrayList<>();
            java.util.ArrayList<android.os.Bundle> recents = new java.util.ArrayList<>();
            if (active || keepRecents) for (Slot slot : owners) {
                try {
                    if (!isActive(slot) || !slot.occupied || slot.parked || slot.taskId < 0
                            || slot.taskToken == null || slot.pkg == null
                            || !controllerOwns(slot, slot.taskId, slot.taskToken)) continue;
                    Object organizer = XposedHelpers.getObjectField(slot.controller, "mTaskOrganizer");
                    synchronized (XposedHelpers.getObjectField(organizer, "mLock")) {
                        Object appeared = ((android.util.SparseArray<?>) XposedHelpers.getObjectField(
                                organizer, "mTasks")).get(slot.taskId);
                        Object info = appeared == null ? null : XposedHelpers.callMethod(appeared, "getTaskInfo");
                        if (info == null || !slot.taskToken.equals(XposedHelpers.getObjectField(info, "token"))) continue;
                        android.os.Bundle row = new android.os.Bundle();
                        row.putBinder("task", (android.os.IBinder) XposedHelpers.callMethod(slot.taskToken, "asBinder"));
                        row.putInt("taskId", slot.taskId);
                        row.putInt("userId", XposedHelpers.getIntField(info, "userId"));
                        row.putInt("displayId", XposedHelpers.getIntField(info, "displayId"));
                        row.putString("package", slot.pkg);
                        row.putLong("scene", epoch);
                        row.putLong("binding", slot.taskEpoch);
                        if (keepRecents && scene != SUSPENDED && windowModeOf(info) == 6) recents.add(row);
                        if (active && scene == ACTIVE && screenOn && !keyguardLocked
                                && !slot.frozen && !slot.promoting && !slot.launching
                                && mayWriteSideSurface(slot)) rows.add(row);
                    }
                } catch (Throwable unavailable) { log("desktop binding omitted: " + unavailable); }
            }
            if (sequence != desktopRegistrationSequence || epoch != sceneEpoch) return;
            state.putBinder("owner", desktopSceneOwner);
            Slot upper = slots[SLOT_TOP];
            TopTaskState.Key upperOwner = topCtrl.topTaskState.owner();
            if (active && scene == ACTIVE && screenOn && !keyguardLocked && topCtrl.topFace
                    && upperOwner != null && upperOwner.taskId == upper.taskId
                    && upperOwner.token.equals(upper.taskToken)
                    && isActive(upper) && upper.occupied && !upper.parked
                    && !upper.frozen && !upper.promoting && !upper.launching
                    && upper.taskToken != null && controllerOwns(upper, upper.taskId, upper.taskToken)) {
                TopTaskGeometry geometry = topGeometry();
                android.os.Bundle topInput = new android.os.Bundle();
                topInput.putBinder("task", (android.os.IBinder) XposedHelpers.callMethod(upper.taskToken, "asBinder"));
                topInput.putInt("taskId", upper.taskId);
                topInput.putInt("userId", upperOwner.userId);
                topInput.putInt("displayId", 0);
                topInput.putInt("width", geometry.logicalWidth);
                topInput.putInt("height", geometry.logicalHeight);
                topInput.putFloat("scale", geometry.scale);
                state.putBundle("topInput", topInput);
            }
            state.putLong("sequence", sequence);
            state.putParcelableArrayList("rows", rows);
            state.putParcelableArrayList("recents", recents);
            ctx.sendBroadcast(new Intent(MiStepDesktopLaunchBridge.ACTION).setPackage("android")
                    .putExtra("state", state), android.Manifest.permission.WRITE_SECURE_SETTINGS);
        };
        if (active || keepRecents) executeOnShell(send); else send.run();
    }

    private void promoteSideTaskForDesktopLaunch(String pkg, int launchUser) {
        if (pkg == null || scene != ACTIVE) return;
        if (!supportsTaskUser(launchUser)) return;
        String[] packages = new String[SLOT_COUNT - 1];
        int[] users = new int[SLOT_COUNT - 1];
        boolean[] occupied = new boolean[SLOT_COUNT - 1];
        for (int i = 1; i < SLOT_COUNT; i++) {
            packages[i - 1] = slots[i].pkg;
            occupied[i - 1] = sideTaskMatchesUser(slots[i], launchUser);
            users[i - 1] = launchUser;
        }
        int sideIndex = DesktopIconLaunchPolicy.uniqueSideSlot(pkg, launchUser, packages, users, occupied);
        if (sideIndex < 0) {
            log("desktop launch has no unique side task package=" + pkg);
            return;
        }
        Slot match = slots[sideIndex + 1];
        if(match.index==SLOT_TOP) {topCtrl.releaseTopTask(true,true,null);return;}
        log("desktop icon selected side task package=" + pkg + " slot=" + match.index
                + " task=" + match.taskId + "; promote existing task");
        queueSidePromotion(match, true);
    }







    private void scheduleSideSurfaceRestore(Slot slot, int taskId, Object token,
            long bindingEpoch, long epoch) {
        for (long delay : new long[]{90L, 320L, 800L}) {
            main.postDelayed(() -> {
                if (scene != ACTIVE || sceneEpoch != epoch || !isActive(slot)
                        || slot.taskId != taskId || slot.taskEpoch != bindingEpoch
                        || !java.util.Objects.equals(slot.taskToken, token)
                        || !slot.occupied || slot.promoting || slot.parked
                        || slot.windowMode != 6) return;
                reapplyScale(slot);
            }, delay);
        }
    }


    MiStepLayout makeAdaptiveLayout(boolean right) { return cellLayout.makeAdaptiveLayout(right); }


    /** 调试广播入口：打印宿主 View 树（定位残带/残留视图）。 */
    public static void debugDumpViews() { HostDebugController.debugDumpViews(); }

    void dumpViewTree() { debugCtrl.dumpViewTree(); }

    private void dumpViewRecursive(View view, int depth) { debugCtrl.dumpViewRecursive(view, depth); }


    /** 交换确认后把新主任务 leash 完全复位：position/matrix/alpha/crop，避免副格残留变换把画面顶到主区上方。 */
    void resetMainTaskLeash(int taskId) {
        if (taskId < 0) return;
        try {
            Object organizer = shellOrganizer();
            if (organizer == null) return;
            Object leash = XposedHelpers.callMethod(organizer, "getTaskLeash",
                    new Class<?>[]{int.class}, taskId);
            if (leash == null || !Boolean.TRUE.equals(XposedHelpers.callMethod(leash, "isValid"))) {
                return;
            }
            Object tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            try {
                XposedHelpers.callMethod(tx, "setPosition",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class, float.class}, leash, 0f, 0f);
                XposedHelpers.callMethod(tx, "setMatrix",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class, float.class, float.class, float.class},
                        leash, 1f, 0f, 0f, 1f);
                XposedHelpers.callMethod(tx, "setAlpha",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                float.class}, leash, 1f);
                XposedHelpers.callMethod(tx, "setCrop",
                        new Class<?>[]{Class.forName("android.view.SurfaceControl"),
                                android.graphics.Rect.class}, leash, null);
                XposedHelpers.callMethod(tx, "apply");
            } finally {
                try { XposedHelpers.callMethod(tx, "close"); } catch (Throwable ignored) {}
            }
            log("main task leash reset task=" + taskId);
        } catch (Throwable error) {
            log("reset main task leash failed task=" + taskId + ": " + error);
        }
    }

    void launchQuick(QuickApp entry) {
        if (entry == null || entry.component == null || scene != ACTIVE
                || promotion != null || transferBusy) return;
        for (Slot slot : slots) {
            if (slot.index > SLOT_MAIN && sideTaskMatchesUser(slot, currentUserId()) && entry.component.getPackageName()
                    .equals(slot.pkg)) {
                log("top shortcut promotes existing side task slot=" + slot.index);
                onSlotTapped(slot.index);
                return;
            }
        }
        Intent launch = new Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(entry.component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                        | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        try {
            ctx.startActivity(launch);
            log("top shortcut launch main " + entry.component.flattenToShortString());
            scheduleMainCropClear(1400L);
        } catch (Throwable error) {
            log("top shortcut launch failed: " + error);
            android.widget.Toast.makeText(ctx, "无法打开 " + entry.label,
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    void openSettingsInMain() {
        if (scene != ACTIVE || promotion != null || transferBusy) return;
        for (Slot slot : slots) {
            if (slot.index > SLOT_MAIN && sideTaskMatchesUser(slot, currentUserId())
                    && "com.android.settings".equals(slot.pkg)) {
                onSlotTapped(slot.index);
                return;
            }
        }
        try {
            Intent settings = new Intent(android.provider.Settings.ACTION_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                            | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            ctx.startActivity(settings);
            log("system settings launched in main");
        } catch (Throwable error) {
            log("system settings launch failed: " + error);
            android.widget.Toast.makeText(ctx, "无法打开系统设置",
                    android.widget.Toast.LENGTH_SHORT).show();
        }
    }

    void beginWindowDrag(MotionEvent event) {
        dragMoved = false;
        dragStartRawX = event.getRawX();
        dragStartRawY = event.getRawY();
        dragTarget = null;
    }

    void updateWindowDrag(MotionEvent event) {
        float dx = event.getRawX() - dragStartRawX;
        float dy = event.getRawY() - dragStartRawY;
        if (!dragMoved && dx * dx + dy * dy < dp(10) * dp(10)) return;
        dragMoved = true;
        if (dragGhost == null) createDragGhost();
        if (dragGhost != null) {
            dragGhost.setX(event.getRawX() - dragGhost.getWidth() * 0.5f);
            dragGhost.setY(event.getRawY() - dragGhost.getHeight() * 0.5f);
        }
        Slot target = slotAtPoint(event.getRawX(), event.getRawY());
        if (target != dragTarget) {
            setDropHighlight(dragTarget, false);
            dragTarget = target;
            setDropHighlight(dragTarget, true);
        }
    }

    void finishWindowDrag(MotionEvent event) {
        boolean cancelled = event.getActionMasked() == MotionEvent.ACTION_CANCEL;
        boolean moved = dragMoved;
        Slot target = slotAtPoint(event.getRawX(), event.getRawY());
        setDropHighlight(dragTarget, false);
        if (dragGhost != null) {
            root.removeView(dragGhost);
            dragGhost = null;
        }
        dragTarget = null;
        if (!moved || cancelled) return;
        if (target == null) {
            log("window drag ended outside side cells");
            return;
        }
        if (target.occupied) {
            log("window drag target occupied slot=" + target.index);
            android.widget.Toast.makeText(ctx, "请拖到空白副槽",
                    android.widget.Toast.LENGTH_SHORT).show();
            return;
        }
        log("window drag collect main into slot=" + target.index);
        captureMainIntoEmptySlot(target);
    }

    private void createDragGhost() {
        TextView ghost = new TextView(ctx);
        ghost.setText("当前应用");
        ghost.setTextColor(HyperOsStyle.textPrimary(ctx));
        ghost.setTextSize(android.util.TypedValue.COMPLEX_UNIT_SP, 14f);
        ghost.setTypeface(android.graphics.Typeface.create(
                "sans-serif-medium", android.graphics.Typeface.NORMAL));
        ghost.setGravity(Gravity.CENTER);
        ghost.setAlpha(0.92f);
        ghost.setElevation(1600f);
        ghost.setBackground(HyperOsStyle.glassPill(ctx, 18f, true));
        int width = dp(120), height = dp(56);
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = Math.round(dragStartRawX - width * 0.5f);
        params.topMargin = Math.round(dragStartRawY - height * 0.5f);
        root.addView(ghost, params);
        dragGhost = ghost;
        root.requestLayout();
    }

    private void setDropHighlight(Slot slot, boolean active) {
        if (slot == null || slot.hint == null) return;
        float scale = active ? 1.06f : 1f;
        slot.hint.setScaleX(scale);
        slot.hint.setScaleY(scale);
        LiveSwapVisualLease.writeAlpha(slot.hint, 1f);
    }

    /** 侧栏第 slotIndex 格的分段底板 top（与相邻格共享边界，保证列无缝）。 */
    int sideSegmentTop(int slotIndex) {
        if (slotIndex <= 1 || layout == null) return layout == null ? 0 : layout.topStrip;
        int[] previous = layout.cellBounds(slotIndex - 2);
        return previous[3];
    }

    /** 侧栏第 slotIndex 格的分段底板高度。 */
    int sideSegmentHeight(int slotIndex) {
        if (layout == null) return 1;
        int top = sideSegmentTop(slotIndex);
        int bottom = slotIndex >= CELL_SLOT_COUNT - 1 ? screenH : layout.cellBounds(slotIndex - 1)[3];
        return Math.max(1, bottom - top);
    }

    private Slot slotAtPoint(float x, float y) {
        if (layout == null || y < layout.topStrip) return null;
        for (Slot slot : slots) {
            if (slot.index <= SLOT_MAIN || slot.index >= CELL_SLOT_COUNT || slot.rect == null) continue;
            if (x >= slot.rect[0] && x < slot.rect[2]
                    && y >= slot.rect[1] && y < slot.rect[3]) return slot;
        }
        return null;
    }

    int dp(float value) { return cellLayout.dp(value); }

    void removeCellBackground(Slot slot) { cellLayout.removeCellBackground(slot); }

    private void destroyCellSurface(Object background) { cellLayout.destroyCellSurface(background); }

    void closeSurfaceObject(Object object, String method) { cellLayout.closeSurfaceObject(object, method); }

    private int slotIndexOfView(View view) {
        for (Slot s : slots) {
            if (s.view == view) {
                return s.index;
            }
        }
        return -1;
    }

    void applyRect(Slot slot) { cellLayout.applyRect(slot); }

    /**
     * Exchange the whole main/side orientation. The official layout keeps the two
     * windows anchored to opposite edges, so the shrinking and growing panes share a
     * moving divider instead of crossing through each other's centre.
     */
    void setMainSide(boolean right) {
        if (layout != null && mainOnRight == right) return;
        try {
            applyMainLayout(makeAdaptiveLayout(right));
            if (root != null) writeZoom(true);
        } catch (Throwable error) {
            log("main side switch failed: " + error);
        }
    }

    /** Host geometry only. A live flip sends its correlated WMS zoom separately. */
    void applyMainLayout(MiStepLayout target) { cellLayout.applyMainLayout(target); }

    /** Only the top strip / side column receive host input; transitions may cover the display. */
    void installHostTouchRegion() {
        Class<?> listenerClass = XposedHelpers.findClass(
                "android.view.ViewTreeObserver$OnComputeInternalInsetsListener", ctx.getClassLoader());
        Object listener = Proxy.newProxyInstance(listenerClass.getClassLoader(),
                new Class<?>[]{listenerClass}, (proxy, method, args) -> {
                    if (method.getDeclaringClass() == Object.class) {
                        if ("hashCode".equals(method.getName())) return System.identityHashCode(proxy);
                        if ("equals".equals(method.getName())) return proxy == args[0];
                        return "MiStepHostTouchRegion";
                    }
                    if (!"onComputeInternalInsets".equals(method.getName()) || root == null) {
                        return null;
                    }
                    Object info = args[0];
                    XposedHelpers.callMethod(info, "setTouchableInsets", 3); // TOUCHABLE_INSETS_REGION
                    Region touchable = (Region) XposedHelpers.getObjectField(info, "touchableRegion");
                    touchable.setEmpty();
                    if (swapPreview != null || cellMovePreview != null) {
                        touchable.set(0, 0, screenW, screenH);
                    } else if (liveSwapInputBlocked) {
                        touchable.set(0, 0, screenW, screenH);
                    } else {
                        touchable.set(0, 0, screenW, layout.topStrip);
                        touchable.op(new Rect(layout.cellX, layout.topStrip,
                                        layout.cellX + layout.cellWidth, screenH),
                                Region.Op.UNION);
                        Slot upper=slots[SLOT_TOP];
                        if(topCtrl.topFace && topCtrl.topTaskPane!=null && !topCtrl.topTaskPane.choosing()
                                && topCtrl.topRoll!=null && !topCtrl.topRoll.rolling() && upper.occupied && !upper.parked
                                && !upper.promoting && upper.rect!=null)
                            touchable.op(new Rect(upper.rect[0],upper.rect[1],upper.rect[2],upper.rect[3]),Region.Op.DIFFERENCE);
                    }
                    return null;
                });
        XposedHelpers.callMethod(root.getViewTreeObserver(),
                "addOnComputeInternalInsetsListener", listener);
        log("host input: top/side only; system main passes through");
    }

    /** Side TaskViews return their touch areas to the host for tap/drag arbitration. */
    void updateTouchRegion(Slot slot) {
        try {
            if (slot.view == null) {
                return;
            }
            Method m = slot.view.getClass().getMethod("setObscuredTouchRegion", Region.class);
            // Only side cells exist. Their own scaled contents still use targeted injection.
            Object arg = liveSwapInputBlocked ? new Region(0, 0, screenW, screenH) : slot.index==SLOT_TOP ? null : slot.rect == null ? null
                    : new Region(slot.rect[0], slot.rect[1], slot.rect[2], slot.rect[3]);
            m.invoke(slot.view, arg);
        } catch (Throwable t) {
            log("updateTouchRegion slot=" + slot.index + " failed: " + t);
        }
    }

    // ---------------------------------------------------------------- v0.9 触摸仲裁与定向注入

    /**
     * 触摸规则（官方Mi Step语义 + 演示需要）：
     *   主区由系统处理，宿主不接收其触摸；
     *   副格有任务 → 轻点=与主区交换；滑动取消轻点，始终不操作副任务；
     *   空槽 → 不消费，交给宿主点击处理。
     */
    private boolean onSlotTouch(int slotIndex, MotionEvent e) { return swapController.onSlotTouch(slotIndex, e); }

    void recycleBuffered(Slot slot) { swapController.recycleBuffered(slot); }

    private boolean followSideDismiss(Slot slot, MotionEvent e) { return swapController.followSideDismiss(slot, e); }

    private void dismissSideToBackground(Slot slot) { swapController.dismissSideToBackground(slot); }

    /** Keep tap-to-swap, transfer a drag to its real window without reinjecting coordinates. */
    private void transferTouchToTask(Slot slot, long eventTime) { swapController.transferTouchToTask(slot, eventTime); }

    private int uidOf(String pkg) { return HostStaticUtil.uidOf(ctx, pkg); }

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
    void enterKeyguardPrivacy() { lifecycle.enterKeyguardPrivacy(); }
    void refreshKeyguardPrivacy() { lifecycle.refreshKeyguardPrivacy(); }
    void leaveKeyguardPrivacy() { lifecycle.leaveKeyguardPrivacy(); }

    void onSlotTapped(int slotIndex) {
        if(slotIndex==SLOT_TOP) { topCtrl.releaseTopTask(true,true,null); return; }
        swapController.onSlotTapped(slotIndex);
    }

    /** Official empty-cell flow: open an app from Home, then collect that exact task. */
    private void captureMainIntoEmptySlot(Slot requestedSlot) { swapController.captureMainIntoEmptySlot(requestedSlot); }

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

    private void releaseTransitionInputWindow() { swapController.releaseTransitionInputWindow(); }

    Object liveHostRootSurface() { return swapController.liveHostRootSurface(); }

    Rect liveSideStrip(MiStepLayout geometry) { return swapController.liveSideStrip(geometry); }

    /** Paint only empty Host controls; real app buffers remain the original task surfaces. */
    void drawLiveEmptyHints(android.graphics.Canvas canvas, MiStepLayout original,            MiStepLayout target, int revealEmptySlots) { chrome.drawLiveEmptyHints(canvas, original, target, revealEmptySlots); }

    boolean holdsLiveFlipZoom() { return swapController.holdsLiveFlipZoom(); }

    void finishLiveSwap(Promotion ticket, MiStepLiveSwap.Result result) { swapController.finishLiveSwap(ticket, result); }

    void finishLiveHome(Promotion ticket, MiStepLiveSwap.Result result) { swapController.finishLiveHome(ticket, result); }

    void closeSwapPreview() { promotionController.closeSwapPreview(); }

    void closeCellMovePreview() { promotionController.closeCellMovePreview(); }

    private Object fullscreenExitTransaction(Promotion ticket) throws Exception { return promotionController.fullscreenExitTransaction(ticket); }

    /** Retire only our binding, then return its canonical task surface to the existing DA. */
    private void handoffPromotedTask(Promotion ticket) throws Throwable { promotionController.handoffPromotedTask(ticket); }

    /** At most three observations; notification or transaction submission alone is insufficient. */
    private void checkPromotion(Promotion ticket, int attempt) { promotionController.checkPromotion(ticket, attempt); }

    /** The promoted binding is already retired; move the remaining owners into its gap. */
    void completeHomePromotion(Promotion ticket) { promotionController.completeHomePromotion(ticket); }

    /** Backfill can take focus again; make the focus request after that last WCT. */
    void finishPromotionFocus(Promotion ticket, Runnable complete, int attempt) { promotionController.finishPromotionFocus(ticket, complete, attempt); }

    void failPromotion(Promotion ticket, String reason) { promotionController.failPromotion(ticket, reason); }

    void clearSlot(Slot slot) {
        if(slot.index==SLOT_TOP && topCtrl.topTaskState.owner()!=null) {
            topCtrl.topTaskState.release(topCtrl.topTaskState.owner(),false); topCtrl.topOriginalSlot=null;
        }
        if(slot.index==SLOT_TOP)topCtrl.releaseRetainedTopSnapshot();
        slot.taskEpoch++;
        slot.removalCheckTask = -1;
        slot.retiredTaskId = slot.taskId;
        slot.occupied = false;
        slot.parked = false;
        slot.frozen = false;
        slot.promoting = false;
        slot.taskVisible = false;
        slot.taskId = -1;
        slot.taskToken = null;
        slot.windowMode = -1;
        slot.nativeReleasePending = false;
        slot.pendingSidePromotion = null;
        slot.launching = false;
        slot.injecting = false;
        slot.modeGuardUntil = 0L;
        if (slot.windowState != null) {
            slot.windowState.clear();
            synchronized (taskWindowStatesLock) {
                taskWindowStates.remove(slot.windowState.taskId);
            }
            slot.windowState = null;
        }
        slot.taskUid = 0;
        slot.pkg = null;
        slot.modeAdmissionPending = false;
        slot.modeRepairAttempts = 0;
        slot.scaleSurface = null;
        slot.scaleOnLeash = false;
        slot.scaleTaskId = Integer.MIN_VALUE;
        slot.scaleApplied = -1f;
        slot.scaleAttempts = 0;
        slot.reassertPending = false;
        slot.visibilityRepair.reset();
        recycleBuffered(slot);
        updateHint(slot);
        updateTouchRegion(slot);
        publishDesktopSideTasks();
        requestCompaction();
    }

    boolean isActive(Slot slot) {
        return root != null && sInstance == this && slot.index > 0
                && slot.index < SLOT_COUNT && slots[slot.index] == slot;
    }

    boolean canRender(Slot slot) {
        return isActive(slot) && !slot.parked && screenOn
                && (scene == ACTIVE || scene == RESUMING)
                && root.getVisibility() == View.VISIBLE;
    }

    void postForSlot(Slot slot, Consumer<Slot> callback) {
        main.post(() -> { if (isActive(slot)) callback.accept(slot); });
    }

    /** Reject late callbacks from a task the native controller has already relinquished. */
    boolean controllerOwns(Slot slot, int taskId, Object token) {
        try {
            Object info = XposedHelpers.getObjectField(slot.controller, "mTaskInfo");
            Object current = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
            if (info != null) {
                ComponentName base = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
                if (base != null && "com.miui.home".equals(base.getPackageName())) return false;
            }
            return info != null && current != null
                    && XposedHelpers.getIntField(info, "taskId") == taskId
                    && (token == null || token.equals(current));
        } catch (Throwable t) {
            return false;
        }
    }

    void bindTask(Slot slot, int taskId, Object token) { promotionController.bindTask(slot, taskId, token); }

    static int windowModeOf(Object info) { return HostStaticUtil.windowModeOf(info); }

    static int configurationOrientationOf(Object info) { return HostStaticUtil.configurationOrientationOf(info); }

    static int requestedOrientationOf(Object info) { return HostStaticUtil.requestedOrientationOf(info); }

    private void updateTaskWindowState(int slotIndex, Object info, int taskId, boolean active) {
        if (info == null || taskId < 0) return;
        try {
            int mode = windowModeOf(info);
            int configurationOrientation = configurationOrientationOf(info);
            int requestedOrientation = requestedOrientationOf(info);
            TaskWindowState state;
            synchronized (taskWindowStatesLock) {
                state = taskWindowStates.get(taskId);
                if (state == null) {
                    state = new TaskWindowState(taskId);
                    taskWindowStates.put(taskId, state);
                }
            }
            state.update(slotIndex, configurationOrientation, requestedOrientation,
                    TaskWindowState.UNKNOWN_ROTATION, mode, mode == 1, active);
            if (slotIndex > SLOT_MAIN && slotIndex < SLOT_COUNT) {
                slots[slotIndex].windowState = state;
            }
        } catch (Throwable error) {
            log("task window state update failed task=" + taskId + ": " + error);
        }
    }

    void syncSlotWindowState(Slot slot) {
        if (slot != null && slot.windowState != null) {
            slot.windowState.slotIndex = slot.index;
        }
    }

    /**
     * TaskInfo 的多窗口能力标记：不支持的（例如相机）不是拒绝，而是走 mirror 承载。
     * 普通应用仍按原路线进 TaskView。
     */
    /** 规则：除桌面外都可进副格；系统标志不预拒绝，交给 native adoption 结果。 */
    private boolean launchActivitySupportsSide(Intent launch) {
        return true;
    }

    private void keepUnsupportedInMain(Slot slot, String pkg, String reason) {
        if (slot != null) {
            slot.launching = false;
            updateHint(slot);
            updateTouchRegion(slot);
        }
        log("side launch rejected slot=" + (slot == null ? -1 : slot.index)
                + " pkg=" + pkg + ": " + reason + "; task remains in main");
        android.widget.Toast.makeText(ctx, "该应用不支持副格，已在主区打开",
                android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * TaskViewTaskController keeps mTaskNotFound after an idle task vanished without a token.
     * Reusing that controller otherwise makes the framework remove the next task immediately.
     */
    void resetIdleTaskView(Slot slot) {
        if (slot == null || slot.controller == null) return;
        try {
            MiStepIdleTaskView.reset(slot.controller, text -> log(text + " slot=" + slot.index));
        } catch (Throwable error) {
            log("reset idle TaskView slot=" + (slot == null ? -1 : slot.index) + " failed: " + error);
        }
    }





    /** 与气泡同原语：IStatusBarService.hideCurrentInputMethodForBubbles(displayId)（BC L776）。 */
    void hideIme() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "statusbar");
            Class<?> stub = Class.forName("com.android.internal.statusbar.IStatusBarService$Stub");
            Object svc = stub.getMethod("asInterface", android.os.IBinder.class).invoke(null, binder);
            Method m = null;
            for (Class<?> c = svc.getClass(); c != null && m == null; c = c.getSuperclass()) {
                try {
                    m = c.getDeclaredMethod("hideCurrentInputMethodForBubbles", int.class);
                } catch (NoSuchMethodException ignored) {
                    // 继续往父类找
                }
            }
            if (m == null) {
                log("hideIme: method not found");
                return;
            }
            m.setAccessible(true);
            m.invoke(svc, 0);
            log("hideIme invoked");
        } catch (Throwable t) {
            log("hideIme failed: " + t);
        }
    }

    // ---------------------------------------------------------------- 启动 / 关闭任务

    void launchPackage(int slotIndex, String pkg) {
        if (slotIndex <= SLOT_MAIN || slotIndex >= SLOT_COUNT) {
            log("launchPackage bad slot=" + slotIndex);
            return;
        }
        Intent i = ctx.getPackageManager().getLaunchIntentForPackage(pkg);
        if (i == null && pkg.contains("home")) {
            // 桌面（home）没有普通 LAUNCHER intent，用 CATEGORY_HOME 显式构造。
            i = new Intent(Intent.ACTION_MAIN);
            i.addCategory(Intent.CATEGORY_HOME);
            i.setPackage(pkg);
            log("launchPackage: using CATEGORY_HOME for " + pkg);
        }
        if (i == null) {
            log("launchPackage: no launcher for " + pkg);
            return;
        }
        launchIntoSlot(slotIndex, i, pkg);
    }

    private void launchComponent(int slotIndex, ComponentName cn) {
        Intent i = new Intent(Intent.ACTION_MAIN);
        i.addCategory(Intent.CATEGORY_LAUNCHER);
        i.setComponent(cn);
        launchIntoSlot(slotIndex, i, cn.flattenToShortString());
    }

    private void launchIntoSlot(final int slotIndex, Intent launch, String label) {
        final Slot slot = slots[slotIndex];
        if (!isActive(slot) || slot.view == null || slot.launching || slot.promoting || promotion != null) {
            log("launchIntoSlot " + slotIndex + ": no view");
            return;
        }
        final String pkg = launch.getComponent() != null
                ? launch.getComponent().getPackageName() : launch.getPackage();
        if (pkg == null || "com.miui.home".equals(pkg)) return;
        for (Slot other : slots) {
            if (other != slot && (other.occupied || other.launching) && pkg.equals(other.pkg)) {
                log("launch rejected: package already in side slot=" + other.index);
                return;
            }
        }
        if (!boundsHooksReady || scene != ACTIVE || transferBusy) return;
        Runnable choose = () -> chooseExistingOrLaunch(slot, launch, label, pkg);
        if (slot.occupied) {
            returnToSystem(slot, false, () -> { clearSlot(slot); choose.run(); },
                    error -> log("replacement stopped; previous live task retained: " + error));
        } else choose.run();
    }

    private void chooseExistingOrLaunch(Slot slot, Intent launch, String label, String pkg) {
        if (!isActive(slot) || scene != ACTIVE) return;
        slot.launching = true;
        if (!executeOnShell(() -> {
            try {
                LiveTask existing = findPackageTask(pkg);
                main.post(() -> {
                    if (!isActive(slot) || scene != ACTIVE) { slot.launching = false; return; }
                    if (existing == null) {
                        startSlot(slot, launch, label);
                    } else {
                        slot.launching = false;
                        adoptExisting(slot, existing, this::finishPendingSuspend,
                                error -> {
                                    log("existing task admission failed; no launcher fallback: " + error);
                                    finishPendingSuspend();
                                });
                    }
                });
            } catch (Throwable error) {
                main.post(() -> { slot.launching = false; log("choose task stopped: " + error);
                    finishPendingSuspend(); });
            }
        })) { slot.launching = false; log("no Shell queue for selection"); }
    }

    private void startSlot(final Slot slot, Intent launch, String label) {
        if (!isActive(slot) || scene != ACTIVE || slot.promoting) { slot.launching = false; return; }
        final int index = slot.index;
        resetIdleTaskView(slot);
        prepareCellSurface(slot);
        slot.modeGuardUntil = SystemClock.uptimeMillis() + 2500L;
        slot.modeRepairAttempts = 0;
        slot.modeAdmissionPending = true;
        slot.pkg = launch.getComponent() != null
                ? launch.getComponent().getPackageName() : launch.getPackage();
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        // v0.9：任务一律按"整屏手机"起（1080×2460），完整 UI 不受窗口最小宽限制；
        // 显示时用矩阵缩放塞进格子（官方Mi Step的等比缩小语义）。
        Rect rect = new Rect(0, 0, screenW, screenH);
        PendingIntent pi = PendingIntent.getActivity(ctx, 1000 + index, launch,
                PendingIntent.FLAG_IMMUTABLE);
        ActivityOptions options = ActivityOptions.makeBasic();
        callHidden(options, "setLaunchWindowingMode", 6);          // WINDOWING_MODE_MULTI_WINDOW
        callHidden(options, "setRemoveWithTaskOrganizer", Boolean.FALSE);
        try {
            Method start = findStartActivity(slot.view.getClass());
            if (start == null) {
                slot.launching = false;
                log("startSlot " + index + ": startActivity not found");
                return;
            }
            start.setAccessible(true);
            start.invoke(slot.view, pi, launch, options, rect);
            launchCurrentSlot = slot;
            main.postDelayed(() -> {
                if (launchCurrentSlot == slot) {
                    launchCurrentSlot = null;
                    slot.launching = false;
                    log("launch watchdog fired slot=" + index + " label=" + label);
                    finishPendingSuspend();
                }
            }, LAUNCH_TIMEOUT_MS);
            log("startActivity slot=" + index + " label=" + label
                    + " pkg=" + launch.getPackage() + " rect=" + rect);
        } catch (Throwable t) {
            slot.launching = false;
            log("startSlot " + index + " FAILED: " + t);
            XposedBridge.log(t);
        }
    }

    private static void callHidden(Object target, String name, Object... args) { HostStaticUtil.callHidden(target, name, args); }

    private static Method findStartActivity(Class<?> cls) { return HostStaticUtil.findStartActivity(cls); }

    // ---------------------------------------------------------------- 任务状态 / 密度 / 可见性

    private Object makeListener(final int slotIndex) {
        final Slot owner = slots[slotIndex];
        try {
            Class<?> iface = XposedHelpers.findClass(
                    "com.android.wm.shell.taskview.TaskView$Listener", ctx.getClassLoader());
            return Proxy.newProxyInstance(iface.getClassLoader(), new Class<?>[]{iface},
                    new InvocationHandler() {
                        @Override
                        public Object invoke(Object proxy, Method method, Object[] args) {
                            try {
                                String name = method.getName();
                                if ("onTaskCreated".equals(name) && args != null && args.length >= 1) {
                                    final int taskId = (Integer) args[0];
                                    postForSlot(owner, s -> onTaskIdKnown(s.index, taskId));
                                } else if ("onTaskInfoChanged".equals(name)
                                        && args != null && args.length >= 1) {
                                    final Object info = args[0];
                                    postForSlot(owner, s -> onTaskInfo(s.index, info));
                                } else if ("onTaskVisibilityChanged".equals(name)
                                        && args != null && args.length >= 2) {
                                    final int taskId = (Integer) args[0];
                                    final boolean visible = (Boolean) args[1];
                                    postForSlot(owner, s -> onTaskVisibility(s.index, taskId, visible));
                                } else if ("onTaskRemovalStarted".equals(name)
                                        && args != null && args.length >= 1) {
                                    final int taskId = (Integer) args[0];
                                    postForSlot(owner, s -> onTaskRemovalStarted(s.index, taskId));
                                } else if ("onInitialized".equals(name)) {
                                    log("listener#onInitialized slot=" + owner.index);
                                    postForSlot(owner, s -> { updateHint(s); setWorkspaceAlpha(workspaceAlpha); });
                                } else {
                                    log("listener#" + name + " slot=" + slotIndex);
                                }
                            } catch (Throwable t) {
                                log("listener dispatch failed: " + t);
                            }
                            return null;
                        }
                    });
        } catch (Throwable t) {
            log("makeListener failed: " + t);
            return null;
        }
    }

    private void onTaskIdKnown(int slotIndex, int taskId) {
        Slot slot = slots[slotIndex];
        if (slot.promoting || slot.parked || slot.retiredTaskId == taskId || !controllerOwns(slot, taskId, null)) return;
        Object token = XposedHelpers.getObjectField(slot.controller, "mTaskToken");
        if (token == null || !controllerOwns(slot, taskId, token)) return;
        bindTask(slot, taskId, token);
        slot.occupied = true;
        slot.launching = false;
        if (slot == launchCurrentSlot) {
            launchCurrentSlot = null;
        }
        log("onTaskCreated slot=" + slotIndex + " taskId=" + taskId);
        updateHint(slot);
        updateTouchRegion(slot);
        finishPendingSuspend();
        main.postDelayed(this::compactCells, 120L);
        if (slotIndex > SLOT_MAIN) scheduleSideModeResolution(slot, taskId);
    }

    /** 目标机 TaskView 的主要状态回调：taskId / token / 可见性都从这里取。 */
    private void onTaskInfo(int slotIndex, Object info) {
        Slot slot = slots[slotIndex];
        try {
            int taskId = XposedHelpers.getIntField(info, "taskId");
            if (slot.promoting || slot.parked || slot.retiredTaskId == taskId) return;
            boolean visible = XposedHelpers.getBooleanField(info, "isVisible");
            boolean running = XposedHelpers.getBooleanField(info, "isRunning");
            Object token = XposedHelpers.getObjectField(info, "token");
            int mode = windowModeOf(info);
            if(slotIndex==SLOT_TOP && mode==1 && slot.occupied && taskId==slot.taskId
                    && token!=null && token.equals(slot.taskToken)) {
                topCtrl.releaseTopTask(true,false,null);return;
            }
            if (slotIndex > SLOT_MAIN && mode == 5 && slot.occupied
                    && taskId == slot.taskId && token != null && token.equals(slot.taskToken)) {
                releaseNativeFreeformSide(slot, taskId, token);
                return;
            }
            if (token == null || !controllerOwns(slot, taskId, token)) return;
            boolean surfaceGeometryChanged = slot.taskId != taskId || slot.windowMode != mode;
            boolean changed = slot.taskId != taskId || slot.occupied != running
                    || slot.taskVisible != visible || slot.windowMode != mode;
            bindTask(slot, taskId, token);
            ComponentName actual = (ComponentName) XposedHelpers.getObjectField(info, "baseActivity");
            if (actual != null) slot.pkg = actual.getPackageName();
            slot.occupied = running;
            slot.taskVisible = visible;
            slot.windowMode = mode;
            if (!running && changed) requestCompaction();
            if (slotIndex > SLOT_MAIN && mode == 6) {
                slot.modeAdmissionPending = false;
                slot.modeRepairAttempts = 0;
            }
            updateTaskWindowState(slotIndex, info, taskId, running);
            if (changed) {
                log("taskInfo slot=" + slotIndex + " task=" + taskId
                        + " running=" + running + " visible=" + visible
                        + " mode=" + mode
                        + " appBounds=" + appBoundsOf(info));
                updateHint(slot);
                updateTouchRegion(slot);
            }
            if (running) {
                // Wait for the native leash first. Early bounds writes can interrupt prepareOpen.
                reapplyScale(slot);
            }
            if (running && slotIndex > SLOT_MAIN && mode == 6 && surfaceGeometryChanged) {
                scheduleSideSurfaceRestore(slot, taskId, token, slot.taskEpoch, sceneEpoch);
            }
            if (running && !visible) scheduleSideVisibilityRepair(slot);
            if (running && mode == 1 && slotIndex > SLOT_MAIN
                    && !slot.promoting && !slot.launching && !slot.parked) {
                if (slot.modeAdmissionPending) {
                    if (SystemClock.uptimeMillis() >= slot.modeGuardUntil)
                        repairSideWindowMode(slot, taskId);
                    else scheduleSideModeResolution(slot, taskId);
                } else promoteExternalLaunch(slot);
            } else if (slot.pendingSidePromotion != null
                    && !slot.pendingSidePromotion.desktopLaunch && mode != 1) {
                slot.pendingSidePromotion = null;
            }
            if (changed) publishDesktopSideTasks();
        } catch (Throwable t) {
            log("onTaskInfo slot=" + slotIndex + " failed: " + t);
        }
    }


    private void onTaskVisibility(int slotIndex, int taskId, boolean visible) {
        Slot slot = slots[slotIndex];
        if (slot.taskId != taskId || slot.promoting || slot.parked) return;
        slot.taskVisible = visible;
        log("taskVisibility slot=" + slotIndex + " task=" + taskId + " visible=" + visible);
        if (!visible) scheduleSideVisibilityRepair(slot);
    }

    java.util.List<MiStepSideClose> closingTasks(Object info) {
        java.util.List<MiStepSideClose> result = new java.util.ArrayList<>();
        if (scene != ACTIVE || promotion != null || transferBusy || cellMotion != null || root == null) return result;
        try {
            MiStepLiveSwapSession.ShellBackend backend = new MiStepLiveSwapSession.ShellBackend(
                    XposedHelpers.getObjectField(transitions, "mTransitions"), ctx.getClassLoader());
            backend.assertShellThread();
            for (Object change : (java.util.List<?>) XposedHelpers.callMethod(info, "getChanges")) {
                if ((Integer) XposedHelpers.callMethod(change, "getMode") != 2) continue;
                Object task = XposedHelpers.callMethod(change, "getTaskInfo");
                if (task == null) continue;
                int id = XposedHelpers.getIntField(task, "taskId");
                Object token = XposedHelpers.getObjectField(task, "token");
                for (Slot slot : slots) {
                    if (slot.index == 0 || slot.taskId != id || !java.util.Objects.equals(slot.taskToken, token)
                            || !slot.occupied || slot.promoting || slot.parked || slot.closing || slot.frozen
                            || !controllerOwns(slot, id, token)) continue;
                    Object parent = getSurfaceField(slot.controller, "mSurfaceControl");
                    Object leash = XposedHelpers.callMethod(change, "getLeash");
                    if (parent == null || leash == null || slot.rect == null) continue;
                    long epoch = slot.taskEpoch;
                    FrameLayout closeRoot = root;
                    final MiStepLayout closeGeometry = layout;
                    Rect strip = liveSideStrip(closeGeometry);
                    MiStepLiveBackdrop wallpaper = MiStepLiveBackdrop.create(liveHostRootSurface(), wallpaperBackdrop,
                            screenW, screenH, strip, strip,
                            canvas -> drawLiveEmptyHints(canvas, closeGeometry, closeGeometry,
                                    1 << slot.index), MiStepHost::log);
                    slot.closing = true;
                    result.add(new MiStepSideClose(backend, slot.controller, leash, parent, slot.cellBackground, scaleFor(slot),
                            slot.rect[2] - slot.rect[0], slot.rect[3] - slot.rect[1],
                            () -> isActive(slot) && scene == ACTIVE && slot.taskId == id && slot.taskEpoch == epoch,
                            () -> { slot.closing = false; main.post(() -> {
                                if (!isActive(slot) || slot.taskId != id || slot.taskEpoch != epoch) {
                                    wallpaper.closeAfterFrame(closeRoot);
                                    return;
                                }
                                if (!executeOnShell(() -> {
                                    boolean stillOwned = controllerOwns(slot, id, token);
                                    if (!stillOwned) resetIdleTaskView(slot);
                                    main.post(() -> {
                                        if (isActive(slot) && slot.taskId == id && slot.taskEpoch == epoch) {
                                            if (stillOwned) onTaskRemovalStarted(slot.index, id);
                                            else { clearSlot(slot); compactCells(); }
                                        }
                                        wallpaper.closeAfterFrame(closeRoot);
                                    });
                                })) {
                                    onTaskRemovalStarted(slot.index, id);
                                    wallpaper.closeAfterFrame(closeRoot);
                                }
                            }); }, MiStepHost::log));
                    log("real side close task=" + id + " slot=" + slot.index);
                }
            }
        } catch (Throwable error) {
            for (MiStepSideClose close : result) close.abandon();
            result.clear();
            log("side close admission: " + error);
        }
        return result;
    }

    private void onTaskRemovalStarted(int slotIndex, int taskId) {
        Slot slot = slots[slotIndex];
        log("onTaskRemovalStarted slot=" + slotIndex + " taskId=" + taskId
                + " current=" + slot.taskId);
        if (slot.closing || slot.nativeReleasePending) return;
        if (slot.promoting || slot.parked) {
            log("early removal notification retained for promotion task=" + taskId);
            return;
        }
        if (slot.taskId != taskId || slot.removalCheckTask == taskId) return;
        slot.removalCheckTask = taskId;
        final long expectedEpoch = slot.taskEpoch;
        main.postDelayed(() -> {
            if (!isActive(slot) || slot.promoting || slot.taskId != taskId
                    || slot.taskEpoch != expectedEpoch) return;
            executeOnShell(() -> {
                boolean stillOwned = controllerOwns(slot, taskId, slot.taskToken);
                if (!stillOwned) resetIdleTaskView(slot);
                postForSlot(slot, owner -> {
                    if (owner.taskId != taskId || owner.taskEpoch != expectedEpoch || owner.promoting) return;
                    owner.removalCheckTask = -1;
                    if (stillOwned) {
                        log("removal not detached; retain task=" + taskId);
                        return;
                    }
                    clearSlot(owner);
                    compactCells();
                });
            });
        }, 250L);
    }

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
    private static String appBoundsOf(Object info) { return HostStaticUtil.appBoundsOf(info); }

    /** An external fullscreen task can be reordered above our roots without killing them. */
    void scheduleSideVisibilityRepair(Slot slot) {
        if (!canRender(slot) || scene != ACTIVE || !screenOn || keyguardLocked
                || slot.promoting || slot.launching
                || slot.frozen || !slot.occupied || slot.taskVisible || slot.reassertPending) return;
        final long epoch = sceneEpoch, binding = slot.taskEpoch;
        final int taskId = slot.taskId;
        final Object token = slot.taskToken;
        slot.reassertPending = true;
        main.postDelayed(() -> {
            slot.reassertPending = false;
            if (!visibilityRepairCurrent(slot, epoch, binding, taskId, token)) return;
            for (Slot side : slots) if (side.promoting || side.launching || side.frozen) return;
            executeOnShell(() -> {
                try {
                    if (!visibilityRepairCurrent(slot, epoch, binding, taskId, token)
                            || !controllerOwns(slot, taskId, token)) return;
                    Object foreground = null;
                    java.util.List<?> roots = rootInfos();
                    for (Object info : roots) {
                        if (!XposedHelpers.getBooleanField(info, "isVisible")
                                || !Integer.valueOf(1).equals(XposedHelpers.callMethod(info, "getWindowingMode"))
                                || isSideToken(XposedHelpers.getObjectField(info, "token"))) continue;
                        int type = (Integer) XposedHelpers.callMethod(info, "getActivityType");
                        if (type != 1 && type != 2) continue;
                        if (foreground != null) return; // Ambiguous split/overlay scene: do not guess.
                        foreground = XposedHelpers.getObjectField(info, "token");
                    }
                    if (!slot.visibilityRepair.admit(epoch, foreground)) return;
                    Object wct = Class.forName("android.window.WindowContainerTransaction")
                            .getDeclaredConstructor().newInstance();
                    int count = 0;
                    for (Slot side : slots) {
                        if (!canRender(side) || side.promoting || side.launching || side.frozen
                                || !side.occupied || !controllerOwns(side, side.taskId, side.taskToken)) continue;
                        Object core = null;
                        for (Object info : roots) if (new LiveTask(side).matches(info)) { core = info; break; }
                        if (!ordinaryRoot(core, 6)) continue;
                        XposedHelpers.callMethod(wct, "setHidden", side.taskToken, false);
                        XposedHelpers.callMethod(wct, "setForceTranslucent", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "setAlwaysOnTop", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "setFocusable", side.taskToken, true);
                        XposedHelpers.callMethod(wct, "reorder", side.taskToken, true);
                        count++;
                    }
                    if (count == 0 || !visibilityRepairCurrent(slot, epoch, binding, taskId, token)) return;
                    boolean applied = applyWct("retain-side-order", wct);
                    if (applied) {
                        for (Slot side : slots) {
                            if (canRender(side) && side.occupied && !side.promoting && !side.frozen
                                    && controllerOwns(side, side.taskId, side.taskToken))
                                XposedHelpers.callMethod(transitions, "setTaskViewVisible", side.controller, true);
                        }
                    }
                    log("side order repair kept live tasks=" + count + " foreground=" + foreground
                            + " applied=" + applied + "; no remove/relaunch");
                } catch (Throwable error) {
                    log("side order repair stopped; retain original task: " + error);
                }
            });
        }, 160L);
    }

    /** Idempotently show every live side TaskView after a task-set change. */
    void reassertSideTaskViews(String reason) {
        final long epoch = sceneEpoch;
        if (!executeOnShell(() -> {
            if (!screenOn || (scene != ACTIVE && scene != RESUMING)
                    || root == null || epoch != sceneEpoch) return;
            int count = 0;
            java.util.List<RenderBinding> frames = new java.util.ArrayList<>();
            for (Slot side : slots) {
                if (side.index <= SLOT_MAIN || !canRender(side) || !side.occupied
                        || (side.index==SLOT_TOP && !topCtrl.topFace) || side.parked || side.promoting || side.launching || side.frozen
                        || side.controller == null
                        || !controllerOwns(side, side.taskId, side.taskToken)) continue;
                try {
                    XposedHelpers.callMethod(transitions, "setTaskViewVisible", side.controller, true);
                    Object leash = getSurfaceField(side.controller, "mTaskLeash");
                    Object parent = getSurfaceField(side.controller, "mSurfaceControl");
                    if (leash != null && parent != null && leash != parent)
                        frames.add(new RenderBinding(side, epoch, leash, parent));
                    count++;
                } catch (Throwable error) {
                    log("side reassert failed slot=" + side.index + ": " + error);
                }
            }
            if (!frames.isEmpty()) {
                Object tx = null;
                try {
                    tx = Class.forName("android.view.SurfaceControl$Transaction")
                            .getDeclaredConstructor().newInstance();
                    // setTaskViewVisible is a no-op for an already-visible task.
                    // A rotation finish can still have returned its canonical leash
                    // to the display. Restore the owned parent and geometry together.
                    appendRetainedSurfaces(tx, frames);
                    XposedHelpers.callMethod(tx, "apply");
                } catch (Throwable error) {
                    log("side parent reassert failed reason=" + reason + ": " + error);
                } finally { closeSurfaceObject(tx, "close"); }
            }
            final int visible = count;
            main.post(() -> {
                for (Slot side : slots) {
                    if (side.index > SLOT_MAIN && side.occupied && !side.promoting && !side.parked)
                        reapplyScale(side);
                }
                log("side TaskViews reasserted reason=" + reason + " count=" + visible);
            });
        })) log("side TaskView reassert skipped reason=" + reason + ": no Shell queue");
    }

    private boolean visibilityRepairCurrent(Slot slot, long epoch, long binding, int id, Object token) {
        return canRender(slot) && scene == ACTIVE && !slot.promoting && !slot.launching && !slot.frozen
                && sceneEpoch == epoch && slot.taskEpoch == binding && slot.taskId == id
                && token != null && token.equals(slot.taskToken);
    }

    String describeTasks() {
        StringBuilder sb = new StringBuilder();
        for (Slot s : slots) {
            sb.append(" [").append(s.index).append(':')
                    .append(s.taskId).append(s.occupied ? "" : "(empty)")
                    .append(s.taskVisible ? "/vis" : "/hid");
            if (s.windowState != null) {
                sb.append("/o").append(s.windowState.configurationOrientation)
                        .append("/r").append(s.windowState.resolvedRotation)
                        .append("/m").append(s.windowState.windowingMode)
                        .append(s.windowState.fullscreen ? "/full" : "/windowed");
            }
            sb.append(']');
        }
        return sb.toString();
    }

    boolean executeOnShell(Runnable task) {
        try {
            Object shell = XposedHelpers.getObjectField(transitions, "mShellExecutor");
            if (shell instanceof Executor) {
                ((Executor) shell).execute(task);
                return true;
            }
        } catch (Throwable t) {
            log("Shell executor failed: " + t);
        }
        return false;
    }

    // ---------------------------------------------------------------- v0.9 等比缩小（自绘矩阵）

    float mainScale() {
        return layout.mainScale;
    }

    float cellScale() {
        return layout.cellScale(0);
    }

    /** Only side tasks are hosted; the system handles main-screen scaling. */
    float scaleFor(Slot slot) {
        return slot.index == SLOT_TOP ? topGeometry().scale : layout.cellScale(slot.index - 1);
    }

    /**
     * 把任务内容按 k 等比缩放显示到槽位里（官方 DisplayContentSmtEx.applyZoomedMatrixSmt 的等价做法）。
     * 任务窗口保持满屏（应用按整屏手机排版），只对承载表面做矩阵缩放，视觉就是"整屏缩小"。
     */
    void applySlotScale(Slot slot) {
        if (holdsLiveFlipZoom() || !canRender(slot) || slot.promoting || !slot.occupied || slot.view == null
                || !mayWriteSideSurface(slot)) {
            return;
        }
        float k = scaleFor(slot);
        Object liveLeash = getSurfaceField(slot.controller, "mTaskLeash");
        if (!controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        if (liveLeash != null && slot.scaleSurface == liveLeash
                && slot.scaleTaskId == slot.taskId && Math.abs(slot.scaleApplied - k) < 0.0005f) {
            return;
        }
        // 顺序很重要（真机实测）：先读 leash 做缩放，再改整屏边界 —— 反过来会让
        // TaskViewTransitions 把 mTaskLeash 清空重建，900ms 内都读不到。
        if (liveLeash == null) {
            if (slot.scaleAttempts >= 8) return;
            slot.scaleAttempts++;
            log("applySlotScale slot=" + slot.index + " leash not ready, retry #" + slot.scaleAttempts);
            main.postDelayed(() -> applySlotScale(slot), 200L);
            return;
        }
        slot.scaleAttempts = 0;
        Object sc = liveLeash;
        slot.scaleOnLeash = true;
        if (applyScaleTransform(slot, sc)) {
            slot.scaleSurface = sc;
            slot.scaleTaskId = slot.taskId;
            slot.scaleApplied = k;
            OwnedTask accepted = ownedTasks.get(slot.taskToken);
            if (accepted != null) accepted.surface = sc;
            log("applySlotScale slot=" + slot.index + " task=" + slot.taskId
                    + " k=" + k + " onLeash=" + slot.scaleOnLeash + " surface=" + sc);
        }
        // TaskView 会把任务边界同步成视图大小（真机实测 DeepSeek=[0,622][807,2460]、设置=550
        // 宽右对齐）→ 改回整屏 1080×2460，应用才按"整屏手机"排版。放在缩放之后执行。
        // The opening/bounds guards already enforce logical full-screen sizing.
        // 转场/可见性变化可能覆盖矩阵：在几个延迟点重发。
        main.postDelayed(() -> reapplyScale(slot), 300L);
        main.postDelayed(() -> reapplyScale(slot), 1200L);
    }

    void reapplyScale(Slot slot) {
        if (holdsLiveFlipZoom() || !canRender(slot) || slot.promoting || !slot.occupied || !mayWriteSideSurface(slot)) {
            return;
        }
        if (!controllerOwns(slot, slot.taskId, slot.taskToken)) return;
        Object current = getSurfaceField(slot.controller, "mTaskLeash");
        if (current == null || current != slot.scaleSurface) {
            applySlotScale(slot);
            return;
        }
        if (!applyScaleTransform(slot, slot.scaleSurface)) {
            // 表面可能已失效（任务重启/改边界后转场重建）：清缓存 + 放开去重门槛，
            // 下次 TaskInfo 变化时重新解析并整套重来。
            slot.scaleSurface = null;
            slot.scaleTaskId = Integer.MIN_VALUE;
            slot.scaleApplied = -1f;
        }
    }

    /** 把任务边界改回整屏（0,0,1080,2460），应用即按"整屏手机"排版。 */
    void applyFullscreenBounds(Slot slot) {
        if (holdsLiveFlipZoom() || !canRender(slot) || slot.promoting || slot.taskToken == null
                || !mayWriteSideSurface(slot)) {
            return;
        }
        if (!controllerOwns(slot, slot.taskId, slot.taskToken)
                || getSurfaceField(slot.controller, "mTaskLeash") == null) return;
        try {
            Object wct = Class.forName("android.window.WindowContainerTransaction")
                    .getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(wct, "setForceTranslucent", slot.taskToken, true);
            XposedHelpers.callMethod(wct, "setBounds", slot.taskToken,
                    logicalBounds(slot));
            applyTopConfiguration(wct, slot.taskToken, slot);
            boolean ok = applyWct("fullscreenBounds(slot=" + slot.index + ")", wct);
            log("fullscreenBounds slot=" + slot.index + " task=" + slot.taskId + " ok=" + ok);
        } catch (Throwable t) {
            log("fullscreenBounds slot=" + slot.index + " failed: " + t);
        }
    }

    /** 统一施加"缩放 + 归位"：leash 需要 setPosition(0,0)，容器表面不能动位置。 */
    private boolean applyScaleTransform(Slot slot, Object sc) {
        if (holdsLiveFlipZoom() || !canRender(slot) || slot.promoting || !mayWriteSideSurface(slot)) return false;
        float k = scaleFor(slot);
        Object tx = null;
        try {
            tx = Class.forName("android.view.SurfaceControl$Transaction")
                    .getDeclaredConstructor().newInstance();
            if (slot.scaleOnLeash) {
                XposedHelpers.callMethod(tx, "setPosition", sc, 0f, 0f);
                // v1.1.3：视口也要对齐 —— 只缩不放位置会"比例对但只显示一部分"（真机反馈）。
                try {
                    XposedHelpers.callMethod(tx, "setWindowCrop", sc,
                            logicalBounds(slot));
                } catch (Throwable ignored) {
                    // 裁剪失败也继续，位置+矩阵仍是主体
                }
            }
            XposedHelpers.callMethod(tx, "setMatrix", sc, k, 0f, 0f, k);
            XposedHelpers.callMethod(tx, "apply");
            return true;
        } catch (Throwable t) {
            log("applyScaleTransform slot=" + slot.index + " failed: " + t);
            return false;
        } finally {
            closeSurfaceObject(tx, "close");
        }
    }

    /** A late mode6 finish must never pull a task selected by Launcher back into a cell. */
    boolean mayWriteSideSurface(Slot slot) { return surfaceCtl.mayWriteSideSurface(slot); }

    /** 按字段名取 SurfaceControl（先本类后父类；取不到返回 null）。 */
    static Object getSurfaceField(Object target, String name) { return HostSurfaceController.getSurfaceField(target, name); }

    boolean applyWct(String what, Object wct) {
        Throwable first = null;
        // 路线 A：框架公开类 WindowOrganizer（SystemUI 里可直接 new，真机已验证）。
        try {
            Class<?> c = Class.forName("android.window.WindowOrganizer");
            Object organizer = c.getDeclaredConstructor().newInstance();
            XposedHelpers.callMethod(organizer, "applyTransaction", wct);
            log("wct(" + what + ") via WindowOrganizer");
            return true;
        } catch (Throwable t) {
            first = t;
        }
        // 路线 B：从已捕获的 Shell 对象（transitions / controller）里找能 applyTransaction 的字段。
        try {
            Object applier = findWctApplier(wct);
            if (applier != null) {
                log("wct(" + what + ") via " + applier.getClass().getName());
                return true;
            }
        } catch (Throwable t) {
            log("wct fallback failed: " + t);
        }
        log("wct(" + what + ") FAILED: " + first);
        return false;
    }

    private Object findWctApplier(Object wct) {
        Class<?> wctCls;
        try {
            wctCls = Class.forName("android.window.WindowContainerTransaction");
        } catch (Throwable t) {
            return null;
        }
        Object[] roots = {transitions, slots[0].controller, slots[1].controller,
                slots[2].controller, slots[3].controller, slots[SLOT_TOP].controller};
        for (Object r : roots) {
            if (r == null) {
                continue;
            }
            for (Class<?> c = r.getClass(); c != null && c != Object.class; c = c.getSuperclass()) {
                for (Field f : c.getDeclaredFields()) {
                    try {
                        f.setAccessible(true);
                        Object v = f.get(r);
                        if (v == null || v == wct) {
                            continue;
                        }
                        Method m;
                        try {
                            m = v.getClass().getMethod("applyTransaction", wctCls);
                        } catch (NoSuchMethodException e) {
                            continue;
                        }
                        m.invoke(v, wct);
                        return v;
                    } catch (Throwable ignored) {
                        // 该字段不可读或不可用，继续找
                    }
                }
            }
        }
        return null;
    }

    static void log(String msg) { HostStaticUtil.log(msg); }
}







package com.jiusis.mistep;

import android.os.Handler;

import java.util.function.Consumer;

/** WMS live-zoom 提交请求；超时兜底走 failed（原 HostSwapController 内部类）。 */
final class LiveZoomRequest implements Runnable {
    private final MiStepHost h;
    private final HostSwapController sc;
    final Promotion ticket;
    final LiveSwapZoom.Zoom expected;
    final Consumer<LiveSwapZoom.Ack> completed;
    boolean delivered;

    LiveZoomRequest(MiStepHost host, HostSwapController sc, Promotion ticket,
            LiveSwapZoom.Zoom expected, Consumer<LiveSwapZoom.Ack> completed) {
        this.h = host;
        this.sc = sc;
        this.ticket = ticket;
        this.expected = expected;
        this.completed = completed;
    }

    void complete(LiveSwapZoom.Ack ack) {
        if (delivered) return;
        delivered = true;
        h.main.removeCallbacks(this);
        if (sc.pendingLiveZoom == this) sc.pendingLiveZoom = null;
        completed.accept(ack);
    }

    void failed(String reason) {
        complete(new LiveSwapZoom.Ack(expected.generation, 0L,
                expected.scale, expected.x, expected.y, false, reason));
    }

    @Override public void run() { failed("WMS live zoom commit timed out"); }
}

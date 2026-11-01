package com.jiusis.mistep;

/** One quarter-turn per gesture; only the final visible face determines task ownership. */
final class TopRollMotion {
    float phase, origin, velocity, previous;
    private long previousTime;
    private float distance;
    private int base;
    static boolean shouldAnimateBackToMain(boolean mainOccupied) { return !mainOccupied; }
    void reset(boolean task) {phase=task?1:0;origin=phase;base=Math.round(phase);velocity=0;}
    void begin(float height) {
        origin=phase;base=Math.round(origin);
        distance=Math.max(1,height);previous=0;previousTime=0;velocity=0;
    }
    void move(float dy,long time) {
        if(previousTime>0 && time>previousTime) {
            float measured=-(dy-previous)*1000f/(distance*(time-previousTime));
            velocity=velocity*.35f+Math.max(-5,Math.min(5,measured))*.65f;
        }
        // Clamp to the adjacent faces so a long or slow drag never reveals a third face.
        previous=dy;previousTime=time;
        phase=Math.max(base-1,Math.min(base+1,origin-dy/distance));
    }
    int destination(boolean cancelled) {
        if(cancelled)return base;
        int next=Math.round(phase+Math.max(-.35f,Math.min(.35f,velocity*.08f)));
        return Math.max(base-1,Math.min(base+1,next));
    }
    static boolean taskFace(int quarter) {return (quarter&1)!=0;}
}

"""Replay real retained-parent repair after an already-visible task escaped to DTA."""
import os
from pathlib import Path
import subprocess
import sys

root = Path(__file__).resolve().parents[2]
src = Path(sys.argv[2]) if len(sys.argv)>2 else root / 'module/src/main/java/com/jiusis/mistep'
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
source = (src / 'MiStepHost.java').read_text(encoding='utf8')

def extract(signature):
    start = source.index(signature); pos = source.index('{', start) + 1; depth = 1
    while depth:
        depth += (source[pos] == '{') - (source[pos] == '}'); pos += 1
    return source[start:pos]

actual = '\n'.join(extract(s) for s in (
    '    private static final class RenderBinding {',
    '    private void appendRetainedSurfaces(',
    '    private void reassertSideTaskViews('))
stub = r'''package android.view;
import java.util.*;
public class SurfaceControl {
 public static class Leash {public Object parent,crop;public float x=99,y=99,k=1,alpha;public int layer=99;public boolean shown;}
 public static class Transaction {
  public static final List<Transaction> all=new ArrayList<>();public int applies,closes;public final List<String> ops=new ArrayList<>();final List<Runnable> pending=new ArrayList<>();
  public Transaction(){all.add(this);}public Transaction reparent(Leash l,Object p){ops.add("parent");pending.add(()->l.parent=p);return this;}
  public Transaction setLayer(Leash l,int z){ops.add("layer");pending.add(()->l.layer=z);return this;}
  public Transaction setPosition(Leash l,float x,float y){ops.add("position");pending.add(()->{l.x=x;l.y=y;});return this;}
  public Transaction setWindowCrop(Leash l,Object c){ops.add("crop");pending.add(()->l.crop=c);return this;}
  public Transaction setMatrix(Leash l,float a,float b,float c,float d){ops.add("matrix");pending.add(()->l.k=a);return this;}
  public Transaction setAlpha(Leash l,float a){ops.add("alpha");pending.add(()->l.alpha=a);return this;}
  public Transaction show(Leash l){ops.add("show");pending.add(()->l.shown=true);return this;}
  public void apply(){applies++;pending.forEach(Runnable::run);pending.clear();}public void close(){closes++;}
 }
}'''
body = r'''package com.jiusis.mistep;
import java.util.*;import android.view.SurfaceControl.Leash;import android.view.SurfaceControl.Transaction;
public class SideParentRegression {
 static final int SLOT_MAIN=0,SLOT_TOP=4,ACTIVE=2,RESUMING=1;int scene=ACTIVE;long sceneEpoch=7;boolean screenOn=true,topFace;Object root=new Object();
 static class Controller {Object mTaskLeash,mSurfaceControl;int task;Object token;}
 static class Slot {int index,taskId;long taskEpoch=3;Object taskToken=new Object(),crop=new Object();Controller controller=new Controller();boolean occupied=true,promoting,launching,frozen,parked,render=true,writable=true;float scale=.247f;Slot(int i){index=i;taskId=100+i;controller.task=taskId;controller.token=taskToken;controller.mTaskLeash=new Leash();controller.mSurfaceControl=new Object();}}
 final Slot[] slots={new Slot(0),new Slot(1),new Slot(2),new Slot(3),new Slot(4)};
 final Queue<Runnable> shell=new ArrayDeque<>();static class Main {Queue<Runnable> q=new ArrayDeque<>();void post(Runnable r){q.add(r);}}final Main main=new Main();int scales;boolean queueAvailable=true;
 public static class Transitions {int calls;public void setTaskViewVisible(Controller c,boolean visible){calls++;/* OEM no-op: already visible */}}final Transitions transitions=new Transitions();
 boolean executeOnShell(Runnable r){if(!queueAvailable)return false;shell.add(r);return true;}
 boolean canRender(Slot s){return s.render;}boolean mayWriteSideSurface(Slot s){return s.writable;}
 boolean controllerOwns(Slot s,int id,Object token){return s.controller!=null&&s.controller.task==id&&Objects.equals(s.controller.token,token);}
 Object getSurfaceField(Controller c,String n){return n.equals("mTaskLeash")?c.mTaskLeash:c.mSurfaceControl;}
 Object logicalBounds(Slot s){return s.crop;}float scaleFor(Slot s){return s.scale;}void reapplyScale(Slot s){scales++;}void log(String s){}
 static void closeSurfaceObject(Object o,String m){if(o!=null)XposedHelpers.callMethod(o,m);}
 static class XposedHelpers {static Object callMethod(Object o,String n,Object...args){for(var m:o.getClass().getDeclaredMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)try{m.setAccessible(true);return m.invoke(o,args);}catch(ReflectiveOperationException e){throw new RuntimeException(e);}throw new IllegalStateException(n);}}
 ACTUAL
 static int checks;static void check(boolean x,String m){checks++;if(!x)throw new AssertionError(m);}
 static SideParentRegression fresh(){Transaction.all.clear();return new SideParentRegression();}
 void drain(){while(!shell.isEmpty())shell.remove().run();while(!main.q.isEmpty())main.q.remove().run();}
 static void excluded(java.util.function.Consumer<SideParentRegression> mutate,String label){var h=fresh();for(var s:h.slots)s.render=false;h.slots[3].render=true;mutate.accept(h);h.reassertSideTaskViews(label);h.drain();check(Transaction.all.isEmpty(),label);}
 static void stale(java.util.function.Consumer<SideParentRegression> mutate,String label){var h=fresh();var s=h.slots[3];var frame=new RenderBinding(s,h.sceneEpoch,s.controller.mTaskLeash,s.controller.mSurfaceControl);mutate.accept(h);var tx=new Transaction();h.appendRetainedSurfaces(tx,List.of(frame));check(tx.ops.isEmpty(),label);}
 public static void main(String[] args){
  var h=fresh();Object dta=new Object();var file=h.slots[3];var leash=(Leash)file.controller.mTaskLeash;leash.parent=dta;h.reassertSideTaskViews("resume complete");check(leash.parent==dta,"repair waits for Shell queue");h.drain();
  check(leash.parent==file.controller.mSurfaceControl,"already-visible canonical task returns to exact slot parent");
  check(leash.x==0&&leash.y==0&&leash.k==file.scale&&leash.crop==file.crop,"parent and local geometry restored together");
  check(leash.shown&&leash.alpha==1&&leash.layer==0,"owned task visible at original layer");
  check(Transaction.all.size()==1&&Transaction.all.get(0).applies==1&&Transaction.all.get(0).closes==1,"one applied and closed transaction");
  check(Transaction.all.get(0).ops.size()==21&&h.transitions.calls==3,"three side tasks repaired, main and inactive TOP excluded");
  check(file.taskId==103&&file.taskEpoch==3,"no task relaunch or identity rewrite");
  h=fresh();h.scene=RESUMING;h.topFace=true;h.reassertSideTaskViews("resume");h.drain();check(h.transitions.calls==4,"active TOP and all cells can recover during resume");
  excluded(x->x.slots[3].promoting=true,"promoting");excluded(x->x.slots[3].launching=true,"launching");excluded(x->x.slots[3].frozen=true,"frozen");excluded(x->x.slots[3].parked=true,"parked");excluded(x->x.slots[3].occupied=false,"empty");excluded(x->x.slots[3].controller.task=999,"foreign owner");excluded(x->x.slots[3].controller.token=new Object(),"foreign token");excluded(x->x.screenOn=false,"screen off");excluded(x->x.scene=0,"inactive");excluded(x->x.root=null,"no host");
  h=fresh();h.reassertSideTaskViews("old scene");h.sceneEpoch++;h.drain();check(Transaction.all.isEmpty()&&h.transitions.calls==0,"queued repair cannot cross scene epoch");
  stale(x->x.sceneEpoch++,"stale scene binding");stale(x->x.slots[3].taskEpoch++,"stale task binding");stale(x->x.slots[3].taskId++,"replaced task id");stale(x->x.slots[3].taskToken=new Object(),"replaced token");stale(x->x.slots[3].controller.mTaskLeash=new Leash(),"retired leash");stale(x->x.slots[3].controller.mSurfaceControl=new Object(),"retired parent");stale(x->x.slots[3].writable=false,"surface ownership gate");stale(x->x.slots[3].controller.task=999,"owner changed before append");
  h=fresh();var s=h.slots[3];var f=new RenderBinding(s,h.sceneEpoch,s.controller.mTaskLeash,s.controller.mSurfaceControl);var tx=new Transaction();h.appendRetainedSurfaces(tx,List.of(f,f));check(tx.ops.size()==7,"same slot appended only once");
  System.out.println("actual side parent repair checks="+checks+" passed");
 }
}'''.replace('ACTUAL', actual)
(out/'SurfaceControl.java').write_text(stub, encoding='utf8')
(out/'SideParentRegression.java').write_text(body, encoding='utf8')
jdk = Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'), '-encoding', 'UTF-8', '--release', '17', '-d', str(out), str(out/'SurfaceControl.java'), str(out/'SideParentRegression.java')], check=True)
subprocess.run([str(jdk/'java.exe'), '-cp', str(out), 'com.jiusis.mistep.SideParentRegression'], check=True)

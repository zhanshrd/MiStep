"""Replay real cropped Activity metrics and role changes against the production fix."""
from pathlib import Path
import os, subprocess, sys
root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
files = {
'android/graphics/Rect.java': '''package android.graphics;
public class Rect {public int left,top,right,bottom;public Rect(){}public Rect(int a,int b,int c,int d){left=a;top=b;right=c;bottom=d;}public Rect(Rect r){this(r.left,r.top,r.right,r.bottom);}public int width(){return right-left;}public int height(){return bottom-top;}public boolean equals(Object o){if(!(o instanceof Rect))return false;Rect r=(Rect)o;return left==r.left&&top==r.top&&right==r.right&&bottom==r.bottom;}}
''',
'android/content/res/Configuration.java': '''package android.content.res;
import android.graphics.Rect;public class Configuration {
 public int densityDpi,screenWidthDp,screenHeightDp,smallestScreenWidthDp,orientation,screenLayout,seq;
 public Window windowConfiguration=new Window();public static class Window {Rect bounds=new Rect(),app,max=new Rect();public Rect getBounds(){return bounds;}public Rect getAppBounds(){return app;}public void setAppBounds(Rect r){app=r;}public void setMaxBounds(Rect r){max=r;}public void setBounds(Rect r){bounds=r;}public Rect getMaxBounds(){return max;}}
}
''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public class XposedHelpers {public static Object getObjectField(Object o,String name){try{var f=o.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}public static Object callMethod(Object o,String name,Object...a){try{for(var m:o.getClass().getMethods()){if(m.getName().equals(name)&&m.getParameterCount()==a.length){m.setAccessible(true);return m.invoke(o,a);}}throw new NoSuchMethodException(name);}catch(Exception e){throw new RuntimeException(e);}}}
''',
'com/jiusis/mistep/TopActivityConfigRegression.java': '''package com.jiusis.mistep;
import android.content.res.Configuration;import android.graphics.Rect;
public class TopActivityConfigRegression {
 static int checks;static void check(boolean x,String why){checks++;if(!x)throw new AssertionError(why);}
 public static class Task {int mode=6;boolean translucent=true;Rect bounds=new Rect(0,0,2964,1707);public int getWindowingMode(){return mode;}public boolean isForceTranslucent(){return translucent;}public Rect getBounds(){return bounds;}}
 public static class Activity {Configuration resolved=new Configuration();public Configuration getResolvedOverrideConfiguration(){return resolved;}}
 static Configuration parent(){Configuration c=new Configuration();c.windowConfiguration.setBounds(new Rect(0,0,2964,1707));c.windowConfiguration.setAppBounds(new Rect(0,0,2964,1707));c.densityDpi=440;c.screenWidthDp=1078;c.screenHeightDp=621;c.smallestScreenWidthDp=621;c.orientation=2;c.screenLayout=35;return c;}
 static Activity cropped(){Activity a=new Activity();a.resolved.windowConfiguration.setAppBounds(new Rect(0,122,1080,939));a.resolved.windowConfiguration.setMaxBounds(new Rect(0,0,1080,2460));a.resolved.screenWidthDp=592;a.resolved.screenHeightDp=448;a.resolved.seq=7;return a;}
 public static void main(String[]args){Task t=new Task();Configuration p=parent();Activity a=cropped();
  check(MiStepTopConfiguration.apply(a,t,p),"actual upper activity opted in");
  check(a.resolved.windowConfiguration.getAppBounds().equals(t.bounds),"physical width/legacy insets still clipped the Activity");
  check(a.resolved.windowConfiguration.getMaxBounds().equals(t.bounds),"Display API still sees the full physical phone");
  check(a.resolved.screenWidthDp==1078&&a.resolved.screenHeightDp==621&&a.resolved.densityDpi==440,"Activity resource viewport differs from Task");
  check(a.resolved.smallestScreenWidthDp==621&&a.resolved.orientation==2,"Activity lost selected viewport direction/width");
  check(a.resolved.seq==7&&a.resolved.windowConfiguration.getBounds().width()==0,"fix took ownership of sequence or explicit Activity bounds");
  check(a.resolved.windowConfiguration.getAppBounds()!=t.bounds&&a.resolved.windowConfiguration.getMaxBounds()!=t.bounds,"alias would let Activity mutate Task geometry");
  check(a.resolved.windowConfiguration.getAppBounds()!=p.windowConfiguration.getAppBounds(),"parent and Activity app bounds are aliased");
  Activity dialogPage=cropped();check(MiStepTopConfiguration.apply(dialogPage,t,p)&&dialogPage.resolved.screenWidthDp==1078,"next Activity lost full viewport");
  for(int state=0;state<8;state++){t=new Task();p=parent();a=cropped();switch(state){case 0:t.mode=1;break;case 1:t.mode=5;break;case 2:t.translucent=false;break;case 3:t.bounds=new Rect(0,0,1080,2460);break;case 4:p.windowConfiguration.setAppBounds(new Rect(0,122,1080,939));break;case 5:p.windowConfiguration.setBounds(new Rect(0,0,939,1630));break;case 6:p.screenHeightDp=0;break;case 7:p.densityDpi=0;break;}
   check(!MiStepTopConfiguration.apply(a,t,p),"foreign/incomplete role accepted "+state);check(a.resolved.screenWidthDp==592,"foreign role configuration was changed "+state);}
  t=new Task();p=parent();a=cropped();MiStepTopConfiguration.apply(a,t,p);t.mode=1;
  a.resolved=new Configuration();a.resolved.densityDpi=440;a.resolved.screenWidthDp=393;a.resolved.screenHeightDp=850;a.resolved.windowConfiguration.setMaxBounds(new Rect(0,0,1080,2460));
  check(!MiStepTopConfiguration.apply(a,t,p)&&a.resolved.densityDpi==440&&a.resolved.screenWidthDp==393,"return reintroduced upper metrics");
  check(a.resolved.windowConfiguration.getMaxBounds().equals(new Rect(0,0,1080,2460)),"fullscreen lost native maximum bounds");
  check(!MiStepTopConfiguration.apply(a,null,p)&&!MiStepTopConfiguration.apply(null,t,p)&&!MiStepTopConfiguration.apply(a,t,null),"detached Activity or Task accepted");
  System.out.println("Production upper Activity config/Display metrics/role checks="+checks+" passed");
 }
}
'''
}
paths=[]
for name, text in files.items():
    p=out/name;p.parent.mkdir(parents=True,exist_ok=True);p.write_text(text,encoding='utf-8');paths.append(str(p))
src=root/'module/src/main/java/com/jiusis/mistep'
paths += [str(src/'MiStepTopConfiguration.java'),str(src/'MiStepSideRoleChange.java')]
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.TopActivityConfigRegression'],check=True)

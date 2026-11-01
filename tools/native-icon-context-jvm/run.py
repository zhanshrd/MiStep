"""Run the production icon resource context against themed/stock resource fixtures."""
from pathlib import Path
import os, subprocess, sys

root = Path(__file__).resolve().parents[2]
out = Path(sys.argv[1]); out.mkdir(parents=True, exist_ok=True)
src = root / 'module/src/main/java/com/jiusis/mistep'
files = {
'android/util/DisplayMetrics.java': '''package android.util;
public class DisplayMetrics {public int densityDpi=440; public void setTo(DisplayMetrics d){densityDpi=d.densityDpi;}}''',
'android/content/pm/ApplicationInfo.java': '''package android.content.pm;
public class ApplicationInfo {public String sourceDir;public String[]splitSourceDirs;public int theme=42;public String[]resourceDirs={"/theme/overlay.apk"};}''',
'android/content/res/Configuration.java': '''package android.content.res;
public class Configuration {public int uiMode=1;public Configuration(){}public Configuration(Configuration c){uiMode=c.uiMode;}public boolean equals(Object o){return o instanceof Configuration&&((Configuration)o).uiMode==uiMode;}}''',
'android/content/res/AssetManager.java': '''package android.content.res;
public class AssetManager {public java.util.List<String> paths=new java.util.ArrayList<>();public boolean closed;
 public int addAssetPath(String p){if(p.contains("broken"))return 0;paths.add(p);return paths.size();}public void close(){closed=true;}}''',
'android/graphics/drawable/Drawable.java': '''package android.graphics.drawable;
public class Drawable {public final boolean themed;public int style;public Drawable(boolean t){themed=t;}public Drawable mutate(){return this;}}''',
'android/view/DisplayAdjustments.java': '''package android.view;public class DisplayAdjustments {}''',
'android/content/res/ResourcesImpl.java': '''package android.content.res;public class ResourcesImpl {public ResourcesImpl(AssetManager a,android.util.DisplayMetrics m,Configuration c,android.view.DisplayAdjustments d){}}''',
'android/content/res/MiuiResourcesImpl.java': '''package android.content.res;public class MiuiResourcesImpl extends ResourcesImpl {public MiuiResourcesImpl(AssetManager a,android.util.DisplayMetrics m,Configuration c){super(a,m,c,new android.view.DisplayAdjustments());}}''',
'android/content/res/Resources.java': '''package android.content.res;
import android.util.DisplayMetrics;import android.graphics.drawable.Drawable;
public class Resources {public final AssetManager assets;public final DisplayMetrics metrics;public final Configuration config;
public ResourcesImpl mResourcesImpl;public Resources(AssetManager a,DisplayMetrics m,Configuration c){assets=a;metrics=m;config=c;mResourcesImpl=new MiuiResourcesImpl(a,m,c);} public void setImpl(ResourcesImpl impl){mResourcesImpl=impl;}
public AssetManager getAssets(){return assets;}public Configuration getConfiguration(){return config;}public DisplayMetrics getDisplayMetrics(){return metrics;}
public int getIdentifier(String n,String t,String p){return n.equals("missing")?0:123;}
public Drawable getDrawable(int id,Theme t){Drawable d=new Drawable(mResourcesImpl instanceof MiuiResourcesImpl);d.style=t.applied;return d;}
public Theme newTheme(){return new Theme();}public static class Theme {public int applied;public void applyStyle(int id,boolean force){applied=id;}public void setTo(Theme t){applied=t.applied;}}}''',
'android/content/Context.java': '''package android.content;
import android.content.res.*;import android.content.pm.ApplicationInfo;import android.graphics.drawable.Drawable;
public abstract class Context {public abstract Resources getResources();public abstract AssetManager getAssets();public abstract Resources.Theme getTheme();
public abstract void setTheme(int id);public abstract ApplicationInfo getApplicationInfo();public abstract String getPackageName();
public final Drawable getDrawable(int id){return getResources().getDrawable(id,getTheme());}}''',
'android/content/ContextWrapper.java': '''package android.content;
import android.content.res.*;import android.content.pm.ApplicationInfo;
public class ContextWrapper extends Context {final Context base;public ContextWrapper(Context c){base=c;}
public Resources getResources(){return base.getResources();}public AssetManager getAssets(){return base.getAssets();}public Resources.Theme getTheme(){return base.getTheme();}
public void setTheme(int id){base.setTheme(id);}public ApplicationInfo getApplicationInfo(){return base.getApplicationInfo();}public String getPackageName(){return base.getPackageName();}}''',
'de/robv/android/xposed/XposedBridge.java': '''package de.robv.android.xposed;public class XposedBridge {public static void log(String s){}}''',
'de/robv/android/xposed/XposedHelpers.java': '''package de.robv.android.xposed;
public class XposedHelpers {
 public static Class<?> findClass(String n,ClassLoader l){try{return Class.forName(n);}catch(Exception e){throw new RuntimeException(e);}}
 public static Object newInstance(Class<?>c,Object...args){try{for(var ctor:c.getConstructors())if(ctor.getParameterCount()==args.length)return ctor.newInstance(args);throw new IllegalArgumentException();}catch(Exception e){throw new RuntimeException(e);}}
 public static Object callMethod(Object o,String n,Object...args){try{for(var m:o.getClass().getMethods())if(m.getName().equals(n)&&m.getParameterCount()==args.length)return m.invoke(o,args);throw new IllegalArgumentException();}catch(Exception e){throw new RuntimeException(e);}}
 public static Object getObjectField(Object o,String n){try{return o.getClass().getField(n).get(o);}catch(Exception e){throw new RuntimeException(e);}}
}''',
'com/jiusis/mistep/MiStepOs4Controls.java': '''package com.jiusis.mistep;
final class MiStepOs4Controls {static int failures;static void failed(String msg,Throwable e){failures++;}}''',
'com/jiusis/mistep/NativeIconContextRegression.java': '''package com.jiusis.mistep;
import android.content.*;import android.content.pm.*;import android.content.res.*;import android.util.*;
public class NativeIconContextRegression {
 static int checks;static void check(boolean b,String m){checks++;if(!b)throw new AssertionError(m);}
 static class Themed extends Context {
 final Resources r=new Resources(new AssetManager(),new DisplayMetrics(),new Configuration());final ApplicationInfo app=new ApplicationInfo();final Resources.Theme theme=r.newTheme();
 Themed(String path){app.sourceDir=path;r.assets.paths.add("/theme/overlay.apk");theme.applyStyle(999,true);}
 public Resources getResources(){return r;}public AssetManager getAssets(){return r.assets;}public Resources.Theme getTheme(){return theme;}
 public void setTheme(int id){theme.applyStyle(id,true);}public ApplicationInfo getApplicationInfo(){return app;}public String getPackageName(){return "com.android.systemui";}}
 public static void main(String[]args){
  Themed base=new Themed("/system/SystemUI.apk");base.app.splitSourceDirs=new String[]{"/system/SystemUI_split.apk"};
  Context own=MiStepNativeIconContext.wrap(base);check(own!=base,"independent context");check(own.getResources()!=base.r,"not shared Resources");
  check(own.getResources().mResourcesImpl.getClass()==ResourcesImpl.class,"explicit platform impl despite OEM constructor");check(base.r.mResourcesImpl instanceof MiuiResourcesImpl,"host OEM impl retained");
  check(own.getAssets()!=base.getAssets(),"not shared AssetManager");check(own.getAssets().paths.equals(java.util.List.of("/system/SystemUI.apk","/system/SystemUI_split.apk")),"only base and split APKs");
  check(!own.getDrawable(123).themed,"original native State icon uses unthemed resources");check(base.getDrawable(123).themed,"system panel remains themed");
  check(own.getTheme().applied==42,"original APK theme, not active user theme");check(base.getTheme().applied==999,"base theme untouched");
  check(own.getResources().metrics!=base.r.metrics&&own.getResources().metrics.densityDpi==440,"copied display metrics");
  check(own.getResources().config!=base.r.config,"copied configuration");check(MiStepNativeIconContext.wrap(base)==own,"reuse context for same config");
  check(MiStepNativeIconContext.wrap(own)==own,"idempotent wrapping");
  check(!MiStepNativeIconContext.drawable(base,"missing","wifi").themed,"native drawable fallback names");
  check(MiStepNativeIconContext.drawable(base,"missing")==null,"missing drawable keeps caller native fallback");
  base.r.config.uiMode=2;Context changed=MiStepNativeIconContext.wrap(base);check(changed!=own&&changed.getResources().config.uiMode==2,"configuration invalidates cache");
  check(own.getResources().config.uiMode==1,"old wrapper config not mutated");
  own.setTheme(17);check(own.getTheme().applied==17&&base.getTheme().applied==999,"local theme changes isolated");
  Themed other=new Themed("/system/plugin.apk");check(MiStepNativeIconContext.wrap(other)!=changed,"plugin resources isolated from SystemUI");
  Themed plugin=new Themed("/system/plugin.apk"){public String getPackageName(){return "miui.systemui.plugin";}};
  check(MiStepNativeIconContext.drawable(plugin,"volume").style==123,"original ControlCenter style resolves native slider vector attributes");
  check(MiStepNativeIconContext.wrap(plugin).getTheme().applied==42,"slider style does not change native tile backgrounds");
  check(plugin.theme.applied==999,"stock panel style stays local to icon resources");
  Themed broken=new Themed("/broken.apk");check(MiStepNativeIconContext.wrap(broken)==broken,"unsupported ROM uses native view fallback");check(MiStepOs4Controls.failures==1,"fallback recorded");
  System.out.println("NativeIconContext production checks="+checks+" passed; stock APK decode/visual still needs device");
 }
}'''
}
paths=[]
for name, content in files.items():
    path=out/name;path.parent.mkdir(parents=True,exist_ok=True);path.write_text(content,encoding='utf-8');paths.append(str(path))
paths.append(str(src/'MiStepNativeIconContext.java'))
jdk=Path(os.environ['MISTEP_JAVA_BIN'])
subprocess.run([str(jdk/'javac.exe'),'-encoding','UTF-8','--release','17','-d',str(out),*paths],check=True)
subprocess.run([str(jdk/'java.exe'),'-cp',str(out),'com.jiusis.mistep.NativeIconContextRegression'],check=True)

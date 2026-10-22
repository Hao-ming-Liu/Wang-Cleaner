package com.wangqingli.app;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.graphics.Rect;
import android.os.*;
import android.app.KeyguardManager;
import android.view.accessibility.*;
import com.wangqingli.app.core.*;
import java.util.*;
import java.util.concurrent.*;

/** Event-driven bounded scanning. No polling, gesture injection, network or wake lock. */
public final class GuardService extends AccessibilityService {
    public static volatile boolean connected;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SessionGate gate=new SessionGate();
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private Store store; private String current="",eventPackage="",activity="";
    private boolean pending;
    private List<Store.LocalRule> localRules=Collections.emptyList();
    private final Runnable scan=()->{pending=false;process();};
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener changes=(p,k)->{
        if("rules".equals(k))localRules=store.rules();
        if("enabled".equals(k)){gate.reset();current="";handler.removeCallbacks(scan);pending=false;}
    };
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){gate.reset();current="";handler.removeCallbacks(scan);pending=false;}};
    @Override protected void onServiceConnected(){
        super.onServiceConnected();store=Store.get(this);localRules=store.rules();connected=true;
        store.prefs.registerOnSharedPreferenceChangeListener(changes);
        IntentFilter f=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screenReceiver,f);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        if(store==null||!store.enabled()||e.getPackageName()==null)return;
        String pkg=e.getPackageName().toString();
        if(!screenReady())return;
        eventPackage=pkg;
        if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && e.getClassName()!=null){
            String cls=e.getClassName().toString();
            if(!cls.startsWith("android.widget.")&&!cls.startsWith("android.view."))activity=cls;
        }
        // Coalesce frequent content events without indefinitely delaying the first event.
        if(!pending){pending=true;handler.postDelayed(scan,180);}
    }
    private boolean screenReady(){return ((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()&&!((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();}
    private void process(){
        if(!store.enabled()||!screenReady())return;
        long now=SystemClock.elapsedRealtime();
        if(!gate.canScan(now))return;
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        try{
            String pkg=string(root.getPackageName());
            if(pkg.isEmpty()||!pkg.equals(eventPackage))return;
            String previous=current;
            boolean switched=!pkg.equals(current);
            if(switched){current=pkg;gate.enter(pkg,now);}
            if(switched&&!excluded(pkg)&&store.enabled(previous,"splash")&&store.returnAllowed(previous,pkg)&&gate.canReturn(previous,pkg,now)){
                gate.returned(); // Consume before acting: failures must not create a back loop.
                if(activePackageIs(pkg)&&performGlobalAction(GLOBAL_ACTION_BACK))record(previous,"back","显式来源→目标规则");
                return;
            }
            if(excluded(pkg))return;
            boolean splash=store.enabled(pkg,"splash")&&gate.inSplashWindow(pkg,now)&&!gate.splashHandled();
            boolean prompt=store.enabled(pkg,"prompt");
            if(!splash&&!prompt)return;
            collect(root,nodes); // root is owned separately; children are recycled below.
            Rect screen=new Rect();root.getBoundsInScreen(screen);if(screen.width()<=0||screen.height()<=0)return;
            boolean ad=false,jump=false;Set<String> texts=new HashSet<>();
            for(AccessibilityNodeInfo n:nodes){if(!n.isVisibleToUser()||n.isPassword())continue;String text=string(n.getText()),desc=string(n.getContentDescription());texts.add(text);texts.add(desc);ad|=RuleEngine.isAdEvidence(text)||RuleEngine.isAdEvidence(desc);jump|=RuleEngine.isJumpPrompt(text)||RuleEngine.isJumpPrompt(desc);}
            if(ad)gate.markAd(pkg,now);
            for(AccessibilityNodeInfo n:nodes){
                if(!n.isVisibleToUser()||n.isPassword()||!n.isEnabled())continue;
                Rect bounds=new Rect();n.getBoundsInScreen(bounds);
                boolean small=small(bounds,screen),top=bounds.centerY()<screen.top+screen.height()*0.38;
                AccessibilityNodeInfo click=clickable(n,screen,pkg);if(click==null)continue;
                try{
                    String text=string(n.getText()),desc=string(n.getContentDescription()),id=string(n.getViewIdResourceName());
                    if(prompt&&jump&&(RuleEngine.isCancel(text)||RuleEngine.isCancel(desc))&&small){if(act(click,pkg,"prompt","内置·取消打开提示",now))return;}
                    if(splash&&RuleEngine.isSplashCandidate(text,desc,id,top,small,true,ad)){if(act(click,pkg,"splash","内置·开屏跳过",now))return;}
                    for(Store.LocalRule rule:localRules){
                        if(!rule.enabled||!rule.pkg.equals(pkg)||(!rule.activity.isEmpty()&&!rule.activity.equals(activity)))continue;
                        if(rule.kind.equals("splash")&&!splash||rule.kind.equals("prompt")&&!prompt)continue;
                        if(!small||!rule.context.isEmpty()&&!texts.contains(rule.context))continue;
                        if(!rule.text.isEmpty()&&!rule.text.equals(text)&&!rule.text.equals(desc))continue;
                        if(!rule.viewId.isEmpty()&&!rule.viewId.equals(id))continue;
                        // Imported rules may narrow selectors, but cannot turn arbitrary buttons into actions.
                        if(rule.kind.equals("splash")&&!RuleEngine.isSplashCandidate(text,desc,id,top,small,true,ad))continue;
                        if(rule.kind.equals("prompt")&&(!jump||!(RuleEngine.isCancel(text)||RuleEngine.isCancel(desc))))continue;
                        if(act(click,pkg,rule.kind,rule.id,now))return;
                    }
                }finally{click.recycle();}
            }
        }catch(IllegalStateException|SecurityException e){/* Window became stale: wait for the next real event. */}
        finally{for(AccessibilityNodeInfo n:nodes)if(n!=root)n.recycle();root.recycle();}
    }
    private boolean excluded(String p){return p.equals(getPackageName())||p.equals("com.android.systemui")||p.equals("com.android.settings")||p.contains("permissioncontroller")||p.equals("com.miui.home")||p.equals("com.android.launcher3");}
    private void collect(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> out){
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);int acquired=1;
        while(!q.isEmpty()){AccessibilityNodeInfo n=q.remove();out.add(n);if(n.isPassword())continue;for(int i=0;i<n.getChildCount()&&acquired<180;i++){AccessibilityNodeInfo c=n.getChild(i);if(c!=null){q.add(c);acquired++;}}}
    }
    private static boolean small(Rect b,Rect s){return b.width()>0&&b.height()>0&&b.width()<=s.width()*0.65&&b.height()<=s.height()*0.16&&(long)b.width()*b.height()<(long)s.width()*s.height()/10;}
    private AccessibilityNodeInfo clickable(AccessibilityNodeInfo n,Rect screen,String pkg){
        AccessibilityNodeInfo c=AccessibilityNodeInfo.obtain(n);
        for(int depth=0;depth<3&&c!=null;depth++){
            Rect b=new Rect();c.getBoundsInScreen(b);
            if(!pkg.equals(string(c.getPackageName()))||!small(b,screen)){c.recycle();return null;}
            if(c.isClickable()&&c.isEnabled()&&c.isVisibleToUser())return c;
            AccessibilityNodeInfo parent=c.getParent();c.recycle();c=parent;
        }if(c!=null)c.recycle();return null;
    }
    private boolean act(AccessibilityNodeInfo node,String pkg,String kind,String rule,long now){
        String key=pkg+":"+kind;
        if(!gate.canAct(key,now)||!screenReady()||!activePackageIs(pkg)||!node.refresh()||!node.isVisibleToUser()||!node.isEnabled())return false;
        gate.acted(key,now); // Also throttle rejected operations to avoid hot retry loops.
        if(!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return false;
        if(kind.equals("splash"))gate.markSplashHandled();record(pkg,kind,rule);return true;
    }
    private boolean activePackageIs(String pkg){AccessibilityNodeInfo r=getRootInActiveWindow();if(r==null)return false;try{return pkg.equals(string(r.getPackageName()));}finally{r.recycle();}}
    private void record(String pkg,String kind,String rule){writer.execute(()->{try{store.record(pkg,kind,rule);}catch(android.database.SQLException e){store.prefs.edit().putString("lastError","统计写入失败，请检查剩余空间").apply();}});}
    private static String string(CharSequence s){return s==null?"":s.toString().trim();}
    @Override public void onInterrupt(){gate.reset();current="";handler.removeCallbacks(scan);pending=false;}
    @Override public boolean onUnbind(Intent i){connected=false;return super.onUnbind(i);}
    @Override public void onDestroy(){connected=false;handler.removeCallbacksAndMessages(null);if(store!=null)store.prefs.unregisterOnSharedPreferenceChangeListener(changes);try{unregisterReceiver(screenReceiver);}catch(IllegalArgumentException ignored){}writer.shutdown();super.onDestroy();}
}

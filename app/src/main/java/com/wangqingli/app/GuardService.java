package com.wangqingli.app;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.content.pm.PackageManager;
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
    private Store store; private String current="",eventPackage="",activity="",activityPackage="";
    private int eventWindow=-1;
    private final Map<String,Boolean> activityClasses=new HashMap<>();
    private boolean pending;
    private List<Store.LocalRule> localRules=Collections.emptyList();
    private final Runnable scan=()->{pending=false;process();};
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener changes=(p,k)->{
        if("rules".equals(k))localRules=store.rules();
        if("enabled".equals(k))resetSession();
    };
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){resetSession();}};
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
        // Unselected apps' content updates need no window-tree IPC. Window
        // transitions still run so sessions and explicit return targets track correctly.
        if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                &&!store.enabled(pkg,"splash")&&!store.enabled(pkg,"prompt"))return;
        eventPackage=pkg;
        eventWindow=e.getWindowId();
        if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            if(!pkg.equals(activityPackage)){activity="";activityPackage=pkg;}
            String cls=string(e.getClassName());
            if(isActivity(pkg,cls))activity=cls.startsWith(".")?pkg+cls:cls;
        }
        // Coalesce frequent content events without indefinitely delaying the first event.
        schedule(180);
    }
    private void schedule(long delay){if(!pending){pending=true;handler.postDelayed(scan,delay);}}
    private void resetSession(){gate.reset();current="";eventPackage="";activity="";activityPackage="";eventWindow=-1;handler.removeCallbacks(scan);pending=false;}
    private boolean isActivity(String pkg,String cls){
        if(cls.isEmpty())return false;
        String name=cls.startsWith(".")?pkg+cls:cls,key=pkg+"/"+name;
        Boolean cached=activityClasses.get(key);if(cached!=null)return cached;
        boolean valid;
        try{getPackageManager().getActivityInfo(new ComponentName(pkg,name),0);valid=true;}
        catch(PackageManager.NameNotFoundException|SecurityException e){valid=false;}
        if(activityClasses.size()>=256)activityClasses.clear();activityClasses.put(key,valid);return valid;
    }
    private boolean screenReady(){return ((PowerManager)getSystemService(POWER_SERVICE)).isInteractive()&&!((KeyguardManager)getSystemService(KEYGUARD_SERVICE)).isKeyguardLocked();}
    private void process(){
        if(!store.enabled()||!screenReady())return;
        long now=SystemClock.elapsedRealtime();
        if(!gate.canScan(now)){schedule(gate.remainingScanDelay(now));return;}
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        try{
            String pkg=string(root.getPackageName());
            if(pkg.isEmpty()||!pkg.equals(eventPackage)||(eventWindow!=-1&&root.getWindowId()!=eventWindow))return;
            String previous=current;
            boolean switched=!pkg.equals(current);
            if(switched){current=pkg;gate.enter(pkg,now);if(!pkg.equals(activityPackage)){activity="";activityPackage=pkg;}}
            if(switched&&!excluded(pkg)&&store.enabled(previous,"splash")&&store.returnAllowed(previous,pkg)&&gate.canReturn(previous,pkg,now)){
                gate.returned(); // Consume before acting: failures must not create a back loop.
                if(activeWindowIs(pkg,root.getWindowId())&&performGlobalAction(GLOBAL_ACTION_BACK))record(previous,"back","显式来源→目标规则");
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
            boolean customSplash=hasRules(pkg,"splash"),customPrompt=hasRules(pkg,"prompt");
            for(AccessibilityNodeInfo n:nodes){
                if(!n.isVisibleToUser()||n.isPassword()||!n.isEnabled())continue;
                Rect bounds=new Rect();n.getBoundsInScreen(bounds);
                boolean small=small(bounds,screen),top=bounds.centerY()<screen.top+screen.height()*0.38;
                String text=string(n.getText()),desc=string(n.getContentDescription()),id=string(n.getViewIdResourceName());
                boolean cancel=prompt&&jump&&(RuleEngine.isCancel(text)||RuleEngine.isCancel(desc))&&small;
                boolean skip=splash&&RuleEngine.isSplashCandidate(text,desc,id,top,small,true,ad);
                if(!cancel&&!skip)continue;
                AccessibilityNodeInfo click=clickable(n,screen,pkg);if(click==null)continue;
                try{
                    Store.LocalRule rule=matchingRule(pkg,"prompt",text,desc,id,texts);
                    if(cancel&&(!customPrompt||rule!=null)&&act(n,click,pkg,"prompt",rule,screen,ad,jump,now))return;
                    rule=matchingRule(pkg,"splash",text,desc,id,texts);
                    if(skip&&(!customSplash||rule!=null)&&act(n,click,pkg,"splash",rule,screen,ad,jump,now))return;
                }finally{click.recycle();}
            }
        }catch(IllegalStateException|SecurityException e){/* Window became stale: wait for the next real event. */}
        finally{for(AccessibilityNodeInfo n:nodes)if(n!=root)n.recycle();root.recycle();}
    }
    private boolean hasRules(String pkg,String kind){for(Store.LocalRule r:localRules)if(r.pkg.equals(pkg)&&r.kind.equals(kind))return true;return false;}
    private Store.LocalRule matchingRule(String pkg,String kind,String text,String desc,String id,Set<String> texts){
        for(Store.LocalRule r:localRules){
            if(!r.enabled||!r.pkg.equals(pkg)||!r.kind.equals(kind))continue;
            if(!r.activity.isEmpty()&&(!pkg.equals(activityPackage)||!r.activity.equals(activity)))continue;
            if(!r.context.isEmpty()&&!texts.contains(r.context))continue;
            if(!r.text.isEmpty()&&!r.text.equals(text)&&!r.text.equals(desc))continue;
            if(!r.viewId.isEmpty()&&!r.viewId.equals(id))continue;
            return r;
        }return null;
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
    private boolean act(AccessibilityNodeInfo candidate,AccessibilityNodeInfo node,String pkg,String kind,Store.LocalRule rule,Rect screen,boolean ad,boolean jump,long now){
        String key=pkg+":"+kind;
        int window=candidate.getWindowId();
        if(!gate.canAct(key,now)||!screenReady()||!activeWindowIs(pkg,window))return false;
        String text=string(candidate.getText()),desc=string(candidate.getContentDescription()),id=string(candidate.getViewIdResourceName());
        String targetText=string(node.getText()),targetDesc=string(node.getContentDescription()),targetId=string(node.getViewIdResourceName());
        if(!candidate.refresh()||!node.refresh()||!node.isVisibleToUser()||!node.isEnabled()||!node.isClickable()||node.isPassword()
                ||!candidate.isVisibleToUser()||!candidate.isEnabled()||candidate.isPassword()
                ||candidate.getWindowId()!=window||node.getWindowId()!=window
                ||!pkg.equals(string(candidate.getPackageName()))||!pkg.equals(string(node.getPackageName())))return false;
        // A recycled row/button must not inherit an earlier skip decision.
        if(!text.equals(string(candidate.getText()))||!desc.equals(string(candidate.getContentDescription()))||!id.equals(string(candidate.getViewIdResourceName()))
                ||!targetText.equals(string(node.getText()))||!targetDesc.equals(string(node.getContentDescription()))||!targetId.equals(string(node.getViewIdResourceName())))return false;
        Rect bounds=new Rect(),targetBounds=new Rect();candidate.getBoundsInScreen(bounds);node.getBoundsInScreen(targetBounds);
        if(!small(bounds,screen)||!small(targetBounds,screen))return false;
        boolean top=targetBounds.centerY()<screen.top+screen.height()*0.38;
        if(kind.equals("splash")&&!RuleEngine.isSplashCandidate(text,desc,id,top,true,true,ad))return false;
        if(kind.equals("prompt")&&(!jump||!(RuleEngine.isCancel(text)||RuleEngine.isCancel(desc))))return false;
        if(!activeWindowIs(pkg,window)||!screenReady())return false;
        gate.acted(key,now); // Also throttle rejected operations to avoid hot retry loops.
        if(!node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return false;
        if(kind.equals("splash"))gate.markSplashHandled();record(pkg,kind,rule==null?(kind.equals("splash")?"内置·开屏跳过":"内置·取消打开提示"):rule.id);return true;
    }
    private boolean activeWindowIs(String pkg,int window){AccessibilityNodeInfo r=getRootInActiveWindow();if(r==null)return false;try{return pkg.equals(string(r.getPackageName()))&&r.getWindowId()==window;}finally{r.recycle();}}
    private void record(String pkg,String kind,String rule){writer.execute(()->{try{store.record(pkg,kind,rule);}catch(android.database.SQLException e){store.prefs.edit().putString("lastError","统计写入失败，请检查剩余空间").apply();}});}
    private static String string(CharSequence s){return s==null?"":s.toString().trim();}
    @Override public void onInterrupt(){resetSession();}
    @Override public boolean onUnbind(Intent i){connected=false;return super.onUnbind(i);}
    @Override public void onDestroy(){connected=false;handler.removeCallbacksAndMessages(null);if(store!=null)store.prefs.unregisterOnSharedPreferenceChangeListener(changes);try{unregisterReceiver(screenReceiver);}catch(IllegalArgumentException ignored){}writer.shutdown();super.onDestroy();}
}

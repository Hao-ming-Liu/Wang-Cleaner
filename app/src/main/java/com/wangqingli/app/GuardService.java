package com.wangqingli.app;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Rect;
import android.os.*;
import android.app.KeyguardManager;
import android.provider.Settings;
import android.view.accessibility.*;
import com.wangqingli.app.core.*;
import java.util.*;
import java.util.concurrent.*;

/** Event-driven bounded scanning. No polling, gesture injection, network or wake lock. */
public final class GuardService extends AccessibilityService {
    public static volatile boolean connected;
    public static final ScanDiagnostics diagnostics=new ScanDiagnostics();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final SessionGate gate=new SessionGate();
    private final ExecutorService writer=Executors.newSingleThreadExecutor();
    private Store store; private String current="",eventPackage="",activity="",activityPackage="";
    private int eventWindow=-1;
    private final Map<String,Boolean> activityClasses=new HashMap<>();
    private final Set<String> excludedPackages=new HashSet<>();
    private String inputMethodPackage="";
    private boolean pending,retryAllowed,allowCooldownRetry;
    private long scanDue;
    private List<Store.LocalRule> localRules=Collections.emptyList();
    private final Runnable scan=()->{pending=false;process();};
    private final android.content.SharedPreferences.OnSharedPreferenceChangeListener changes=(p,k)->{
        if(k==null){localRules=store.rules();resetSession();return;}
        if("rules".equals(k)){localRules=store.rules();resetSession();}
        if("enabled".equals(k)||"allApps".equals(k)||k.startsWith("splash:")||k.startsWith("prompt:")||k.startsWith("popup:"))resetSession();
    };
    private final BroadcastReceiver screenReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){resetSession();}};
    private final BroadcastReceiver inputReceiver=new BroadcastReceiver(){@Override public void onReceive(Context c,Intent i){refreshExcludedPackages();resetSession();}};
    @Override protected void onServiceConnected(){
        super.onServiceConnected();store=Store.get(this);localRules=store.rules();refreshExcludedPackages();connected=true;
        store.prefs.registerOnSharedPreferenceChangeListener(changes);
        IntentFilter f=new IntentFilter(Intent.ACTION_SCREEN_OFF);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(screenReceiver,f,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(screenReceiver,f);
        IntentFilter input=new IntentFilter("android.intent.action.INPUT_METHOD_CHANGED");
        if(Build.VERSION.SDK_INT>=33)registerReceiver(inputReceiver,input,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(inputReceiver,input);
    }
    @Override public void onAccessibilityEvent(AccessibilityEvent e){
        if(store==null||e.getPackageName()==null)return;
        String pkg=e.getPackageName().toString();
        note(pkg,ScanDiagnostics.Stage.EVENT);
        if(!store.enabled()){note(pkg,ScanDiagnostics.Stage.DISABLED);return;}
        if(!screenReady())return;
        if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED&&!pkg.equals(current))refreshExcludedPackages();
        // Excluded windows still terminate the previous foreground session. A
        // keyboard overlay is not an app launch; ignore its non-Activity events.
        if(pkg.equals(inputMethodPackage)&&!isActivity(pkg,string(e.getClassName())))return;
        if(excluded(pkg)&&e.getEventType()!=AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){note(pkg,ScanDiagnostics.Stage.EXCLUDED);return;}
        // Unselected apps' content updates need no window-tree IPC. Window
        // transitions still run so sessions and explicit return targets track correctly.
        if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                &&!store.enabled(pkg,"splash")&&!store.enabled(pkg,"prompt")&&!store.enabled(pkg,"popup")){note(pkg,ScanDiagnostics.Stage.DISABLED);return;}
        eventPackage=pkg;
        eventWindow=e.getWindowId();
        if(e.getEventType()==AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED){
            if(!pkg.equals(activityPackage)){activity="";activityPackage=pkg;}
            String cls=string(e.getClassName());
            if(isActivity(pkg,cls))activity=cls.startsWith(".")?pkg+cls:cls;
        }
        // Coalesce frequent content events without indefinitely delaying the first event.
        retryAllowed=true;
        schedule(180);
    }
    private void schedule(long delay){
        long due=SystemClock.uptimeMillis()+Math.max(0,delay);
        if(!pending||due<scanDue){handler.removeCallbacks(scan);pending=true;scanDue=due;handler.postAtTime(scan,due);}
    }
    private void resetSession(){gate.reset();current="";eventPackage="";activity="";activityPackage="";eventWindow=-1;handler.removeCallbacks(scan);pending=false;retryAllowed=false;allowCooldownRetry=false;}
    private void refreshExcludedPackages(){
        excludedPackages.clear();
        Intent home=new Intent(Intent.ACTION_MAIN);home.addCategory(Intent.CATEGORY_HOME);
        ResolveInfo chosen=getPackageManager().resolveActivity(home,PackageManager.MATCH_DEFAULT_ONLY);
        if(chosen!=null&&chosen.activityInfo!=null&&chosen.activityInfo.packageName!=null)excludedPackages.add(chosen.activityInfo.packageName);
        for(ResolveInfo launcher:getPackageManager().queryIntentActivities(home,0))if(launcher.activityInfo!=null)excludedPackages.add(launcher.activityInfo.packageName);
        inputMethodPackage="";
        String ime=Settings.Secure.getString(getContentResolver(),Settings.Secure.DEFAULT_INPUT_METHOD);
        if(ime!=null){ComponentName component=ComponentName.unflattenFromString(ime);if(component!=null){inputMethodPackage=component.getPackageName();excludedPackages.add(inputMethodPackage);}}
    }
    private static void note(String pkg,ScanDiagnostics.Stage stage){diagnostics.note(pkg,stage,SystemClock.elapsedRealtime());}
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
        allowCooldownRetry=retryAllowed;
        retryAllowed=false; // A deferred scan cannot renew itself without a real event.
        note(eventPackage,ScanDiagnostics.Stage.SCAN);
        AccessibilityNodeInfo root=safeRoot();if(root==null){note(eventPackage,ScanDiagnostics.Stage.ROOT_UNAVAILABLE);return;}
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        try{
            String pkg=string(root.getPackageName());
            if(pkg.isEmpty()||!pkg.equals(eventPackage)||(eventWindow!=-1&&root.getWindowId()!=eventWindow)){note(eventPackage,ScanDiagnostics.Stage.WINDOW_MISMATCH);return;}
            String previous=current;
            boolean switched=!pkg.equals(current);
            if(switched){refreshExcludedPackages();current=pkg;gate.enter(pkg,now);if(!pkg.equals(activityPackage)){activity="";activityPackage=pkg;}}
            if(switched&&!excluded(pkg)&&store.enabled(previous,"splash")&&store.returnAllowed(previous,pkg)&&gate.canReturn(previous,pkg,now)){
                gate.returned(); // Consume before acting: failures must not create a back loop.
                if(activeWindowIs(pkg,root.getWindowId())){if(performGlobalAction(GLOBAL_ACTION_BACK)){note(previous,ScanDiagnostics.Stage.RETURN_SUCCESS);record(previous,"back","显式来源→目标规则");}else note(previous,ScanDiagnostics.Stage.RETURN_FAILED);}
                else note(previous,ScanDiagnostics.Stage.STALE_WINDOW);
                return;
            }
            if(excluded(pkg)){note(pkg,ScanDiagnostics.Stage.EXCLUDED);return;}
            boolean splash=store.enabled(pkg,"splash")&&gate.inSplashWindow(pkg,now)&&!gate.splashHandled();
            boolean prompt=store.enabled(pkg,"prompt");
            boolean popup=store.enabled(pkg,"popup");
            if(!splash&&!prompt&&!popup){note(pkg,ScanDiagnostics.Stage.OUTSIDE_WINDOW);return;}
            collect(root,nodes); // root is owned separately; children are recycled below.
            supplementCandidates(root,nodes,splash,popup);
            // Dialog roots describe the dialog, not the full display. Comparing a
            // 48dp button against a short dialog incorrectly rejects legitimate closes.
            android.util.DisplayMetrics display=new android.util.DisplayMetrics();
            ((android.view.WindowManager)getSystemService(WINDOW_SERVICE)).getDefaultDisplay().getRealMetrics(display);
            Rect screen=new Rect(0,0,display.widthPixels,display.heightPixels);
            if(screen.width()<=0||screen.height()<=0){note(pkg,ScanDiagnostics.Stage.NO_CLICK_TARGET);return;}
            boolean ad=false,jump=false;Set<String> texts=new HashSet<>();
            for(AccessibilityNodeInfo n:nodes){if(!n.isVisibleToUser()||n.isPassword())continue;String text=string(n.getText()),desc=string(n.getContentDescription());texts.add(text);texts.add(desc);ad|=RuleEngine.isAdEvidence(text)||RuleEngine.isAdEvidence(desc);jump|=RuleEngine.isJumpPrompt(text)||RuleEngine.isJumpPrompt(desc);}
            if(ad)gate.markAd(pkg,now);
            boolean customSplash=hasRules(pkg,"splash"),customPrompt=hasRules(pkg,"prompt"),customPopup=hasRules(pkg,"popup"),candidateSeen=false;
            for(AccessibilityNodeInfo n:nodes){
                if(!n.isVisibleToUser()||n.isPassword()||!n.isEnabled())continue;
                Rect bounds=new Rect();n.getBoundsInScreen(bounds);
                boolean small=small(bounds,screen),top=bounds.centerY()<screen.top+screen.height()*0.38;
                String text=string(n.getText()),desc=string(n.getContentDescription()),id=string(n.getViewIdResourceName());
                boolean cancel=prompt&&jump&&(RuleEngine.isCancel(text)||RuleEngine.isCancel(desc))&&small;
                boolean skip=splash&&RuleEngine.isSplashCandidate(text,desc,id,top,small,true,ad);
                Store.LocalRule popupRule=popup?matchingRule(pkg,"popup",text,desc,id,texts):null;
                boolean popupLabel=popup&&(RuleEngine.isPopupClose(text)||RuleEngine.isPopupClose(desc))&&small;
                boolean builtInPopup=popupLabel&&!customPopup;
                boolean popupMatch=popup&&small&&(builtInPopup||popupRule!=null);
                if(popup&&customPopup&&popupRule==null&&(popupLabel||hasSelector(pkg,"popup",text,desc,id)))note(pkg,ScanDiagnostics.Stage.RULE_MISMATCH);
                if(!cancel&&!skip&&!popupMatch)continue;
                candidateSeen=true;
                note(pkg,ScanDiagnostics.Stage.CANDIDATE);
                AccessibilityNodeInfo click=clickable(n,screen,pkg);if(click==null){note(pkg,ScanDiagnostics.Stage.NO_CLICK_TARGET);continue;}
                try{
                    Store.LocalRule rule=matchingRule(pkg,"prompt",text,desc,id,texts);
                    if(cancel&&(!customPrompt||rule!=null)&&act(n,click,pkg,"prompt",rule,screen,ad,jump,now))return;
                    if(cancel&&customPrompt&&rule==null)note(pkg,ScanDiagnostics.Stage.RULE_MISMATCH);
                    rule=matchingRule(pkg,"splash",text,desc,id,texts);
                    if(skip&&(!customSplash||rule!=null)&&act(n,click,pkg,"splash",rule,screen,ad,jump,now))return;
                    if(skip&&customSplash&&rule==null)note(pkg,ScanDiagnostics.Stage.RULE_MISMATCH);
                    if(popupMatch&&act(n,click,pkg,"popup",popupRule,screen,ad,jump,now))return;
                }finally{click.recycle();}
            }
            if(!candidateSeen)note(pkg,ScanDiagnostics.Stage.NO_CANDIDATE);
        }catch(IllegalStateException|SecurityException e){note(eventPackage,ScanDiagnostics.Stage.STALE_WINDOW);}
        finally{for(AccessibilityNodeInfo n:nodes)if(n!=root)n.recycle();root.recycle();}
    }
    private boolean hasRules(String pkg,String kind){for(Store.LocalRule r:localRules)if(r.pkg.equals(pkg)&&r.kind.equals(kind))return true;return false;}
    private boolean hasSelector(String pkg,String kind,String text,String desc,String id){
        for(Store.LocalRule r:localRules)if(r.pkg.equals(pkg)&&r.kind.equals(kind)
                &&(r.text.isEmpty()||r.text.equals(text)||r.text.equals(desc))&&(r.viewId.isEmpty()||r.viewId.equals(id)))return true;
        return false;
    }
    private Store.LocalRule matchingRule(String pkg,String kind,String text,String desc,String id,Set<String> texts){
        for(Store.LocalRule r:localRules){
            if(!r.enabled||!r.pkg.equals(pkg)||!r.kind.equals(kind))continue;
            if(kind.equals("popup")&&(r.activity.isEmpty()||r.context.isEmpty()||(r.text.isEmpty()&&r.viewId.isEmpty())))continue;
            if(!r.activity.isEmpty()&&(!pkg.equals(activityPackage)||!r.activity.equals(activity)))continue;
            if(!r.context.isEmpty()&&!texts.contains(r.context))continue;
            if(!r.text.isEmpty()&&!r.text.equals(text)&&!r.text.equals(desc))continue;
            if(!r.viewId.isEmpty()&&!r.viewId.equals(id))continue;
            return r;
        }return null;
    }
    private boolean excluded(String p){return p.equals("android")||p.equals(getPackageName())||p.equals("com.android.systemui")||p.equals("com.android.settings")||p.equals("com.android.permissioncontroller")||p.equals("com.google.android.permissioncontroller")||p.equals("com.android.packageinstaller")||p.equals("com.google.android.packageinstaller")||p.equals("com.miui.packageinstaller")||p.equals("com.miui.securitycenter")||p.equals("com.miui.home")||p.equals("com.android.launcher3")||excludedPackages.contains(p);}
    private void collect(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> out){
        ArrayDeque<AccessibilityNodeInfo> q=new ArrayDeque<>();q.add(root);int acquired=1;
        int probes=0;
        while(!q.isEmpty()){AccessibilityNodeInfo n=q.remove();out.add(n);if(n.isPassword())continue;for(int i=0;i<n.getChildCount()&&acquired<180&&probes<180;i++){probes++;AccessibilityNodeInfo c=n.getChild(i);if(c!=null){q.add(c);acquired++;}}}
    }
    private void supplementCandidates(AccessibilityNodeInfo root,List<AccessibilityNodeInfo> nodes,boolean splash,boolean popup){
        // Four platform text queries at most, retaining at most 32 extra nodes.
        // Full-label matching and all ordinary checks still apply afterwards.
        List<String> queries=new ArrayList<>();
        if(splash){queries.add("跳过");queries.add("skip");}
        if(popup){queries.add("关闭");queries.add("ad");}
        int retained=0;
        for(String query:queries){
            List<AccessibilityNodeInfo> found=root.findAccessibilityNodeInfosByText(query);
            for(AccessibilityNodeInfo n:found){
                if(retained<32&&!nodes.contains(n)){nodes.add(n);retained++;}
                else if(n!=root)n.recycle();
            }
            if(retained>=32)break;
        }
    }
    private static boolean small(Rect b,Rect s){return b.width()>0&&b.height()>0&&s.contains(b)&&b.width()<=s.width()*0.65&&b.height()<=s.height()*0.16&&(long)b.width()*b.height()<(long)s.width()*s.height()/10;}
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
        if(!screenReady()){note(pkg,ScanDiagnostics.Stage.ACTION_REJECTED);return false;}
        if(!gate.canAct(key,now)){
            note(pkg,ScanDiagnostics.Stage.ACTION_REJECTED);
            if(allowCooldownRetry){allowCooldownRetry=false;schedule(gate.remainingActionDelay(key,now));}
            return false;
        }
        if(!activeWindowIs(pkg,window)){note(pkg,ScanDiagnostics.Stage.STALE_WINDOW);return false;}
        String text=string(candidate.getText()),desc=string(candidate.getContentDescription()),id=string(candidate.getViewIdResourceName());
        String targetText=string(node.getText()),targetDesc=string(node.getContentDescription()),targetId=string(node.getViewIdResourceName());
        if(!candidate.refresh()||!node.refresh()||!node.isVisibleToUser()||!node.isEnabled()||!node.isClickable()||node.isPassword()
                ||!candidate.isVisibleToUser()||!candidate.isEnabled()||candidate.isPassword()
                ||candidate.getWindowId()!=window||node.getWindowId()!=window
                ||!pkg.equals(string(candidate.getPackageName()))||!pkg.equals(string(node.getPackageName()))){note(pkg,ScanDiagnostics.Stage.STALE_WINDOW);return false;}
        // A recycled row/button must not inherit an earlier skip decision.
        if(!text.equals(string(candidate.getText()))||!desc.equals(string(candidate.getContentDescription()))||!id.equals(string(candidate.getViewIdResourceName()))
                ||!targetText.equals(string(node.getText()))||!targetDesc.equals(string(node.getContentDescription()))||!targetId.equals(string(node.getViewIdResourceName()))){note(pkg,ScanDiagnostics.Stage.STALE_WINDOW);return false;}
        Rect bounds=new Rect(),targetBounds=new Rect();candidate.getBoundsInScreen(bounds);node.getBoundsInScreen(targetBounds);
        if(!small(bounds,screen)||!small(targetBounds,screen)){note(pkg,ScanDiagnostics.Stage.NO_CLICK_TARGET);return false;}
        boolean top=targetBounds.centerY()<screen.top+screen.height()*0.38;
        if(kind.equals("splash")&&!RuleEngine.isSplashCandidate(text,desc,id,top,true,true,ad)){note(pkg,ScanDiagnostics.Stage.ACTION_REJECTED);return false;}
        if(kind.equals("prompt")&&(!jump||!(RuleEngine.isCancel(text)||RuleEngine.isCancel(desc)))){note(pkg,ScanDiagnostics.Stage.ACTION_REJECTED);return false;}
        if(kind.equals("popup")&&rule==null&&!RuleEngine.isPopupClose(text)&&!RuleEngine.isPopupClose(desc)){note(pkg,ScanDiagnostics.Stage.ACTION_REJECTED);return false;}
        if(rule!=null&&!liveRuleMatches(rule,pkg,window,text,desc,id)){note(pkg,ScanDiagnostics.Stage.RULE_MISMATCH);return false;}
        if(!activeWindowIs(pkg,window)||!screenReady()){note(pkg,ScanDiagnostics.Stage.STALE_WINDOW);return false;}
        gate.acted(key,now); // Also throttle rejected operations to avoid hot retry loops.
        if(!node.performAction(AccessibilityNodeInfo.ACTION_CLICK)){note(pkg,ScanDiagnostics.Stage.CLICK_FAILED);return false;}
        if(kind.equals("splash"))gate.markSplashHandled();
        note(pkg,ScanDiagnostics.Stage.CLICK_SUCCESS);
        record(pkg,kind,rule==null?(kind.equals("splash")?"内置·开屏跳过":kind.equals("prompt")?"内置·取消打开提示":"内置·关闭广告"):rule.id);return true;
    }
    private boolean liveRuleMatches(Store.LocalRule rule,String pkg,int window,String text,String desc,String id){
        if(!rule.enabled||!rule.pkg.equals(pkg)||(!rule.activity.isEmpty()&&(!pkg.equals(activityPackage)||!rule.activity.equals(activity))))return false;
        if(!rule.text.isEmpty()&&!rule.text.equals(text)&&!rule.text.equals(desc))return false;
        if(!rule.viewId.isEmpty()&&!rule.viewId.equals(id))return false;
        if(rule.context.isEmpty())return !rule.kind.equals("popup");
        AccessibilityNodeInfo root=safeRoot();if(root==null)return false;
        List<AccessibilityNodeInfo> nodes=new ArrayList<>();
        try{
            if(root.getWindowId()!=window||!pkg.equals(string(root.getPackageName())))return false;
            collect(root,nodes);
            for(AccessibilityNodeInfo n:nodes)if(n.isVisibleToUser()&&!n.isPassword()
                    &&(rule.context.equals(string(n.getText()))||rule.context.equals(string(n.getContentDescription()))))return true;
            return false;
        }finally{for(AccessibilityNodeInfo n:nodes)if(n!=root)n.recycle();root.recycle();}
    }
    private AccessibilityNodeInfo safeRoot(){try{return getRootInActiveWindow();}catch(IllegalStateException|SecurityException e){return null;}}
    private boolean activeWindowIs(String pkg,int window){AccessibilityNodeInfo r=safeRoot();if(r==null)return false;try{return pkg.equals(string(r.getPackageName()))&&r.getWindowId()==window;}finally{r.recycle();}}
    private void record(String pkg,String kind,String rule){writer.execute(()->{try{store.record(pkg,kind,rule);}catch(android.database.SQLException e){store.prefs.edit().putString("lastError","统计写入失败，请检查剩余空间").apply();}});}
    private static String string(CharSequence s){return s==null?"":s.toString().trim();}
    @Override public void onInterrupt(){resetSession();}
    @Override public boolean onUnbind(Intent i){connected=false;return super.onUnbind(i);}
    @Override public void onDestroy(){connected=false;handler.removeCallbacksAndMessages(null);if(store!=null)store.prefs.unregisterOnSharedPreferenceChangeListener(changes);try{unregisterReceiver(screenReceiver);}catch(IllegalArgumentException ignored){}try{unregisterReceiver(inputReceiver);}catch(IllegalArgumentException ignored){}writer.shutdown();super.onDestroy();}
}

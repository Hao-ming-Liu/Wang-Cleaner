package com.wangqingli.app;

import android.Manifest;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;

public final class MainActivity extends Activity {
    private static final int INK=0xff193d32,GREEN=0xff176b52,MUTED=0xff65766e,BG=0xfff5f6f0;
    private final ExecutorService io=Executors.newSingleThreadExecutor();
    private Store store;private LinearLayout page,root;private int tab=0;private boolean today=false;
    private final List<AppItem> apps=new ArrayList<>();private boolean loading;
    private static class AppItem {String pkg,name;AppItem(String p,String n){pkg=p;name=n;}}
    @Override public void onCreate(Bundle state){super.onCreate(state);store=Store.get(this);if(state!=null)tab=state.getInt("tab",0);render();loadApps();}
    @Override public void onSaveInstanceState(Bundle b){super.onSaveInstanceState(b);b.putInt("tab",tab);}
    @Override public void onResume(){super.onResume();if(store!=null)render();}
    private int dp(float n){return (int)(getResources().getDisplayMetrics().density*n+0.5f);}
    private GradientDrawable shape(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView text(String value,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);if(bold)t.setTypeface(null,Typeface.BOLD);t.setLineSpacing(dp(3),1);return t;}
    private void label(LinearLayout parent,String value,int size,int color,boolean bold){TextView t=text(value,size,color,bold);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(9);parent.addView(t,p);}
    private Button button(String name,Runnable action){Button b=new Button(this);b.setText(name);b.setTextSize(14);b.setTextColor(GREEN);b.setAllCaps(false);b.setOnClickListener(v->action.run());return b;}
    private LinearLayout card(){LinearLayout c=column();c.setPadding(dp(18),dp(18),dp(18),dp(14));c.setBackground(shape(Color.WHITE,20));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.bottomMargin=dp(14);page.addView(c,p);return c;}
    private void toggle(LinearLayout parent,String title,String detail,boolean checked,java.util.function.Consumer<Boolean> change){
        Switch sw=new Switch(this);sw.setText(title);sw.setTextSize(16);sw.setTextColor(INK);sw.setPadding(0,dp(9),0,dp(9));sw.setChecked(checked);sw.setOnCheckedChangeListener((v,b)->change.accept(b));parent.addView(sw,new LinearLayout.LayoutParams(-1,-2));if(!detail.isEmpty())label(parent,detail,12,MUTED,false);
    }
    private void render(){
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(20),dp(10),dp(20),0);
        root.setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(dp(20),insets.getSystemWindowInsetTop()+dp(10),dp(20),insets.getSystemWindowInsetBottom());return insets;});setContentView(root);
        LinearLayout head=column();label(head,"王清理",28,INK,true);label(head,"少一点打扰，多一点清净",13,MUTED,false);root.addView(head);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);page=column();page.setPadding(0,dp(12),0,dp(10));scroll.addView(page);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(tab==0)home();else if(tab==1)applications();else if(tab==2)statistics();else settings();
        LinearLayout nav=new LinearLayout(this);nav.setPadding(0,dp(5),0,dp(5));String[] names={"保护","应用","统计","设置"};
        for(int i=0;i<4;i++){final int t=i;TextView n=text(names[i],15,i==tab?GREEN:MUTED,i==tab);n.setGravity(Gravity.CENTER);n.setPadding(0,dp(14),0,dp(14));if(i==tab)n.setBackground(shape(0xffe2eee3,16));n.setOnClickListener(v->{tab=t;render();});nav.addView(n,new LinearLayout.LayoutParams(0,-2,1));}root.addView(nav);
    }
    private boolean authorized(){AccessibilityManager m=(AccessibilityManager)getSystemService(ACCESSIBILITY_SERVICE);for(AccessibilityServiceInfo info:m.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)){ServiceInfo s=info.getResolveInfo().serviceInfo;if(s.packageName.equals(getPackageName()))return true;}return false;}
    private String status(){if(!store.enabled())return "保护已暂停";if(GuardService.connected)return "无障碍已连接";return authorized()?"已授权 · 等待系统连接":"等待开启无障碍";}
    private void home(){
        LinearLayout hero=card();hero.setBackground(shape(GREEN,22));label(hero,"●  "+status(),18,Color.WHITE,true);label(hero,store.prefs.getBoolean("allApps",false)?"尝试保护符合条件的应用":"只处理你选择的应用",13,0xffd4e7de,false);
        TextView count=text(String.valueOf(store.total(false)),52,Color.WHITE,true);hero.addView(count);label(hero,"累计成功发出的保护操作",12,0xffd4e7de,false);
        LinearLayout c=card();toggle(c,"启用保护","暂停后不再扫描或自动操作，统计仍会保留。",store.enabled(),b->{store.prefs.edit().putBoolean("enabled",b).apply();render();});
        toggle(c,"保护所有应用","默认关闭。开启后，对未单独设置的应用尝试识别开屏、广告弹窗和打开应用提示；应用页可单独关闭。系统授权界面、桌面和输入法等由服务排除。",store.prefs.getBoolean("allApps",false),b->{store.prefs.edit().putBoolean("allApps",b).apply();render();});
        c.addView(button(GuardService.connected?"检查无障碍设置":"开启无障碍服务",this::accessibilityConsent));
        c.addView(button("选择需要保护的应用",()->{tab=1;render();}));
        LinearLayout summary=card();label(summary,"今天已执行  "+store.total(true)+"  次",20,INK,true);label(summary,"开屏跳过 · 关闭广告弹窗 · 取消打开提示 · 跳转后返回",13,MUTED,false);
        label(summary,"次数代表 Android 接受了操作，不代表广告已视觉确认消失。失败操作不计数。",12,MUTED,false);
        LinearLayout limit=card();label(limit,"关于“摇一摇”广告",17,INK,true);label(limit,"可尽快跳过可识别的开屏，并按指定规则在广告跳转后尝试返回。无 root 无障碍不能禁止其他应用读取加速度传感器，也不能通用拦截跳转。",14,MUTED,false);
        if(!store.prefs.getString("lastError","").isEmpty())label(limit,store.prefs.getString("lastError",""),13,0xffa1452c,false);
    }
    private void accessibilityConsent(){new AlertDialog.Builder(this).setTitle("启用前请了解权限用途").setMessage("王清理需要查看保护范围内应用的界面文字和按钮，并代你点击跳过、关闭广告弹窗、取消，或按规则返回。\n\n界面内容只在内存中匹配，不保存或上传；本应用没有联网权限。不读取密码节点。\n\n你可随时暂停保护或在系统设置撤销无障碍。Android 13 及以上可能需要先在应用详情中允许受限制的设置。") .setNegativeButton("暂不开启",null).setPositiveButton("了解，前往设置",(d,w)->open(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))).show();}
    private void loadApps(){if(loading)return;loading=true;io.execute(()->{
        Map<String,String> map=new TreeMap<>();Intent launch=new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        for(ResolveInfo r:getPackageManager().queryIntentActivities(launch,0)){String p=r.activityInfo.packageName;if(!p.equals(getPackageName()))map.put(p,r.loadLabel(getPackageManager()).toString());}
        for(String p:store.packages())if(!map.containsKey(p))map.put(p,appName(p));
        List<AppItem> found=new ArrayList<>();for(Map.Entry<String,String> e:map.entrySet())found.add(new AppItem(e.getKey(),e.getValue()));
        found.sort((a,b)->{int pa=priority(a.pkg),pb=priority(b.pkg);return pa!=pb?Integer.compare(pa,pb):a.name.compareTo(b.name);});
        runOnUiThread(()->{loading=false;if(isDestroyed())return;apps.clear();apps.addAll(found);if(tab==1)render();});
    });}
    private int priority(String p){return p.equals("com.sankuai.meituan")?0:p.equals("com.taobao.taobao")?1:p.equals("tv.danmaku.bili")?2:3;}
    private String appName(String p){try{return getPackageManager().getApplicationLabel(getPackageManager().getApplicationInfo(p,0)).toString();}catch(PackageManager.NameNotFoundException e){return p;}}
    private void applications(){
        label(page,"按应用设置保护",22,INK,true);label(page,"全应用保护开启时，此处的开关可单独关闭应用或类别。优先展示美团、淘宝、哔哩哔哩（若已安装）。规则效果以实机为准。",13,MUTED,false);
        EditText search=new EditText(this);search.setSingleLine(true);search.setHint("搜索应用名称或包名");page.addView(search);
        LinearLayout list=column();page.addView(list);fillApps(list,"");search.addTextChangedListener(new android.text.TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){fillApps(list,s.toString());}public void afterTextChanged(android.text.Editable e){}});
        page.addView(button("添加包名",this::addPackage));page.addView(button("刷新应用列表",this::loadApps));
    }
    private void fillApps(LinearLayout list,String query){list.removeAllViews();int count=0;for(AppItem a:apps){if(!(a.name+" "+a.pkg).toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT)))continue;count++;
        LinearLayout c=column();c.setPadding(dp(16),dp(14),dp(16),dp(8));c.setBackground(shape(Color.WHITE,18));LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);list.addView(c,p);
        label(c,a.name,17,INK,true);label(c,a.pkg,11,MUTED,false);
        toggle(c,"开屏自动跳过","仅进入应用后 12 秒内匹配小型按钮；顶部单字“跳过”可识别。",store.enabled(a.pkg,"splash"),b->store.set(a.pkg,"splash",b));
        toggle(c,"取消打开应用提示","只有明确的跨应用打开提示才会点击取消。",store.enabled(a.pkg,"prompt"),b->store.set(a.pkg,"prompt",b));
        toggle(c,"关闭广告弹窗","识别明确的广告关闭按钮；普通关闭按钮不会作为广告处理。",store.enabled(a.pkg,"popup"),b->store.set(a.pkg,"popup",b));
    }if(count==0)label(list,loading?"正在读取应用列表…":"没有匹配应用；可手工添加包名。",14,MUTED,false);}
    private void addPackage(){EditText input=new EditText(this);input.setHint("例如 com.sankuai.meituan");input.setSingleLine(true);new AlertDialog.Builder(this).setTitle("添加应用包名").setView(input).setNegativeButton("取消",null).setPositiveButton("添加",(d,w)->{String p=input.getText().toString().trim();if(!Store.validPackage(p)){toast("包名格式不正确");return;}store.addPackage(p);loadApps();}).show();}
    private void statistics(){
        label(page,"每一次，都有记录",22,INK,true);LinearLayout filter=new LinearLayout(this);filter.addView(button(today?"查看累计":"查看今天",()->{today=!today;render();}));filter.addView(button("刷新",this::render));page.addView(filter);
        List<Store.Stat> stats=store.stats(today);long max=stats.isEmpty()?1:stats.get(0).total();
        if(stats.isEmpty()){LinearLayout empty=card();label(empty,"还没有保护记录",20,INK,true);label(empty,"在应用页选择 APP 并开启无障碍后，实际成功发出的操作会显示在这里。",14,MUTED,false);}
        for(Store.Stat s:stats){LinearLayout c=card();label(c,appName(s.pkg)+"   "+s.total()+" 次",18,INK,true);label(c,s.pkg,11,MUTED,false);ProgressBar bar=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(100);bar.setProgress((int)(s.total()*100/max));bar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));c.addView(bar,new LinearLayout.LayoutParams(-1,dp(5)));label(c,"跳过 "+s.splash+"  ·  广告弹窗 "+s.popup+"  ·  取消提示 "+s.prompt+"  ·  返回 "+s.back,12,MUTED,false);}
        page.addView(button("导出每日统计 CSV",()->createDocument(102,"text/csv","王清理-统计.csv")));
        LinearLayout history=card();label(history,"最近操作",18,INK,true);for(String[] row:store.history()){String time=new SimpleDateFormat("MM-dd HH:mm:ss",Locale.CHINA).format(new Date(Long.parseLong(row[0])));label(history,time+"  "+appName(row[1])+"\n"+kindName(row[2])+" · "+row[3],12,MUTED,false);}label(history,"最近明细最多保留 300 条；每日累计保留至手动清空。",11,MUTED,false);
    }
    private String kindName(String k){return k.equals("splash")?"开屏跳过":k.equals("prompt")?"取消打开提示":k.equals("popup")?"关闭广告弹窗":"跳转后返回";}
    private void settings(){
        label(page,"运行与规则",22,INK,true);
        LinearLayout status=card();label(status,status(),18,INK,true);label(status,"系统授权："+(authorized()?"已开启":"未开启")+"\n服务连接："+(GuardService.connected?"已连接":"未连接")+"\n电池优化："+(((PowerManager)getSystemService(POWER_SERVICE)).isIgnoringBatteryOptimizations(getPackageName())?"已豁免":"由系统管理"),13,MUTED,false);
        status.addView(button("刷新状态",this::render));status.addView(button("无障碍设置",this::accessibilityConsent));status.addView(button("电池优化设置",()->open(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))));status.addView(button("应用详情 / 后台与自启动",()->open(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,Uri.parse("package:"+getPackageName())))));
        toggle(status,"开机后提醒检查","需要通知权限；提醒不代表服务已恢复。",store.prefs.getBoolean("bootReminder",false),b->{store.prefs.edit().putBoolean("bootReminder",b).apply();if(b&&Build.VERSION.SDK_INT>=33)requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},103);});
        LinearLayout diagnostics=card();label(diagnostics,"两分钟扫描诊断",18,INK,true);
        label(diagnostics,"临时记录包名、事件和拒绝阶段，用来区分没有匹配和操作失败；不记录页面文字或按钮 ID，结束后仅留在内存中。",13,MUTED,false);
        diagnostics.addView(button("开始两分钟诊断",()->{GuardService.diagnostics.start(SystemClock.elapsedRealtime());toast("诊断已开始，约两分钟后自动结束");}));
        diagnostics.addView(button("查看诊断",()->new AlertDialog.Builder(this).setTitle("扫描诊断").setMessage(GuardService.diagnostics.report(SystemClock.elapsedRealtime())).setPositiveButton("关闭",null).show()));
        diagnostics.addView(button("复制诊断",()->{String report=GuardService.diagnostics.report(SystemClock.elapsedRealtime());ClipboardManager clipboard=(ClipboardManager)getSystemService(CLIPBOARD_SERVICE);clipboard.setPrimaryClip(ClipData.newPlainText("王清理扫描诊断",report));toast("诊断已复制");}));
        diagnostics.addView(button("停止诊断",()->{GuardService.diagnostics.stop();toast("诊断已停止，报告仍可查看");}));
        LinearLayout xiaomi=card();label(xiaomi,"小米 / HyperOS 运行建议",17,INK,true);label(xiaomi,"1. 在系统设置中允许王清理自启动和后台运行。\n2. 将王清理的电池策略设为无限制（实际菜单名称随系统变化）。\n3. 在最近任务中锁定王清理。\n4. 重启后打开本页确认“服务已连接”。\n\n清理最近任务与“强行停止”不同。厂商仍可终止服务，强行停止后请手动打开并检查无障碍。",13,MUTED,false);
        LinearLayout rules=card();label(rules,"本地规则",18,INK,true);label(rules,"借鉴 GKD 的按应用匹配思路。使用王清理 JSON v1 格式，不直接兼容 GKD 订阅；导入会替换现有本地规则和返回组合。",13,MUTED,false);
        label(rules,"同一应用和类型存在本地规则时，只执行已开启且匹配的本地规则；即使全部停用，也不会回退内置规则。",12,MUTED,false);
        label(rules,"当前自定义规则："+store.rules().size()+" 条\n返回组合："+store.ruleDocument().optJSONArray("returns").length()+" 条",13,INK,false);
        rules.addView(button("导入 JSON 规则",()->new AlertDialog.Builder(this).setTitle("导入将替换现有规则").setMessage("请选择可信规则。验证失败会保留当前规则；应用保护开关不会自动打开。").setNegativeButton("取消",null).setPositiveButton("选择文件",(d,w)->{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(Intent.CATEGORY_OPENABLE);startActivityForResult(i,101);}).show()));
        rules.addView(button("导出当前规则",()->createDocument(104,"application/json","王清理-规则.json")));
        rules.addView(button("管理广告跳转返回组合",this::returnRules));
        LinearLayout sensor=card();label(sensor,"“摇一摇”的系统限制",17,INK,true);label(sensor,"无障碍不能禁用其他 APP 的加速度计。开发者选项若有 Sensors Off（传感器关闭），它是全局开关，会影响相机、麦克风等；不同系统可能没有该入口。王清理不会自动切换它。",13,MUTED,false);
        LinearLayout data=card();label(data,"本地与透明",17,INK,true);label(data,"无联网权限 · 无截图/OCR · 无后台轮询 · 无唤醒锁\n读取界面仅用于当次匹配，不记录正文。只有包名、动作类别、规则和时间写入本地数据库。\n\n版本 "+versionName()+" · Android 8.0+\n参考项目：github.com/gkd-kit/gkd\n独立实现，非 GKD 官方版本。",13,MUTED,false);
        data.addView(button("清空全部统计",()->new AlertDialog.Builder(this).setTitle("清空统计？").setMessage("将永久删除每日累计和操作明细，应用开关及规则保留。").setNegativeButton("取消",null).setPositiveButton("清空",(d,w)->{store.clearStats();render();}).show()));
    }
    private String versionName(){try{return getPackageManager().getPackageInfo(getPackageName(),0).versionName;}catch(PackageManager.NameNotFoundException e){return "未知";}}
    private void returnRules(){
        LinearLayout layout=column();layout.setPadding(dp(20),dp(8),dp(20),dp(8));
        label(layout,"只有来源开屏前 12 秒、最近 3 秒看到广告证据，并切换至指定目标时才尝试返回一次。可能已短暂打开目标。支付、登录等正常跳转也可能被误判，请谨慎配置。",13,MUTED,false);
        org.json.JSONArray a=store.ruleDocument().optJSONArray("returns");StringBuilder existing=new StringBuilder();for(int i=0;i<a.length();i++){org.json.JSONObject r=a.optJSONObject(i);existing.append(r.optString("source")).append(" → ").append(r.optString("target")).append(r.optBoolean("enabled",false)?" [开启]\n":" [关闭]\n");}label(layout,existing.length()==0?"暂无返回组合":existing.toString(),12,INK,false);
        EditText source=new EditText(this);source.setHint("来源包名，例如 tv.danmaku.bili");source.setSingleLine(true);layout.addView(source);
        EditText target=new EditText(this);target.setHint("跳转目标包名，例如 com.taobao.taobao");target.setSingleLine(true);layout.addView(target);
        ScrollView scroll=new ScrollView(this);scroll.addView(layout);
        new AlertDialog.Builder(this).setTitle("广告跳转后返回").setView(scroll).setNegativeButton("关闭",null).setNeutralButton("清除全部组合",(d,w)->{try{org.json.JSONObject doc=store.ruleDocument();doc.put("returns",new org.json.JSONArray());store.importRules(doc.toString());render();}catch(Exception e){toast("保存失败");}}).setPositiveButton("添加并开启",(d,w)->{try{String s=source.getText().toString().trim(),t=target.getText().toString().trim();store.addReturn(s,t);toast("已添加；请同时开启来源 APP 的开屏保护");render();}catch(Exception e){toast("添加失败："+e.getMessage());}}).show();
    }
    private void createDocument(int code,String mime,String name){try{startActivityForResult(new Intent(Intent.ACTION_CREATE_DOCUMENT).setType(mime).addCategory(Intent.CATEGORY_OPENABLE).putExtra(Intent.EXTRA_TITLE,name),code);}catch(ActivityNotFoundException e){toast("系统没有可用的文件选择器");}}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(result!=RESULT_OK||data==null||data.getData()==null)return;Uri uri=data.getData();io.execute(()->{
        try{
            if(request==101){try(InputStream in=getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){if(in==null)throw new IOException("无法打开文件");byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1){if(out.size()+n>131072)throw new IOException("规则文件超过 128 KB");out.write(buf,0,n);}store.importRules(new String(out.toByteArray(),StandardCharsets.UTF_8));}}
            else if(request==102||request==104){String value=request==102?store.csv():store.ruleDocument().toString(2);try(OutputStream out=getContentResolver().openOutputStream(uri,"wt")){if(out==null)throw new IOException("无法写入文件");out.write(value.getBytes(StandardCharsets.UTF_8));}}
            runOnUiThread(()->{if(!isDestroyed()){toast(request==101?"规则导入成功":"已导出");render();}});
        }catch(Exception e){runOnUiThread(()->toast("操作失败："+e.getMessage()));}
    });}
    private void open(Intent i){try{startActivity(i);}catch(ActivityNotFoundException e){toast("此系统没有该设置入口，请从系统设置中手动查找");}}
    private void toast(String msg){Toast.makeText(this,msg,Toast.LENGTH_LONG).show();}
    @Override public void onDestroy(){io.shutdown();super.onDestroy();}
}

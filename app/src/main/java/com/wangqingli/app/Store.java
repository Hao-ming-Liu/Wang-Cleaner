package com.wangqingli.app;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import com.wangqingli.app.core.RuleEngine;
import org.json.*;
import java.text.SimpleDateFormat;
import java.util.*;

/** Local configuration and bounded action history. No screen content is retained. */
public final class Store extends SQLiteOpenHelper {
    private static Store instance;
    public static synchronized Store get(Context c) {
        if (instance == null) instance = new Store(c.getApplicationContext());
        return instance;
    }
    public final android.content.SharedPreferences prefs;
    private Store(Context c) { super(c, "actions.db", null, 1); prefs=c.getSharedPreferences("settings",0); }
    @Override public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE daily(day TEXT NOT NULL,pkg TEXT NOT NULL,kind TEXT NOT NULL,n INTEGER NOT NULL,PRIMARY KEY(day,pkg,kind))");
        db.execSQL("CREATE TABLE history(id INTEGER PRIMARY KEY AUTOINCREMENT,time INTEGER NOT NULL,pkg TEXT NOT NULL,kind TEXT NOT NULL,rule TEXT NOT NULL)");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int oldV,int newV) { }
    public boolean enabled() { return prefs.getBoolean("enabled", true); }
    public boolean enabled(String pkg,String kind) { return prefs.getBoolean(kind+":"+pkg,false); }
    public void set(String pkg,String kind,boolean value) { prefs.edit().putBoolean(kind+":"+pkg,value).apply(); }
    public Set<String> packages() {
        Set<String> result=new HashSet<>(prefs.getStringSet("manual",Collections.emptySet()));
        for(String key:prefs.getAll().keySet()) if(key.startsWith("splash:")||key.startsWith("prompt:")) result.add(key.substring(key.indexOf(':')+1));
        return result;
    }
    public void addPackage(String pkg) { Set<String> p=packages();p.add(pkg);prefs.edit().putStringSet("manual",p).apply(); }
    public static boolean validPackage(String p) { return p!=null && p.length()<180 && p.matches("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+"); }
    public static String day(long time) { return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date(time)); }
    public synchronized void record(String pkg,String kind,String rule) {
        SQLiteDatabase db=getWritableDatabase();long now=System.currentTimeMillis();
        db.beginTransaction();
        try {
            db.execSQL("INSERT OR IGNORE INTO daily(day,pkg,kind,n) VALUES(?,?,?,0)",new Object[]{day(now),pkg,kind});
            db.execSQL("UPDATE daily SET n=n+1 WHERE day=? AND pkg=? AND kind=?",new Object[]{day(now),pkg,kind});
            db.execSQL("INSERT INTO history(time,pkg,kind,rule) VALUES(?,?,?,?)",new Object[]{now,pkg,kind,rule});
            db.execSQL("DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT 300)");
            db.setTransactionSuccessful();
        } finally {db.endTransaction();}
    }
    public long total(boolean today) {
        try(Cursor c=getReadableDatabase().rawQuery("SELECT COALESCE(SUM(n),0) FROM daily"+(today?" WHERE day=?":""),today?new String[]{day(System.currentTimeMillis())}:null)){return c.moveToFirst()?c.getLong(0):0;}
    }
    public static final class Stat {
        public String pkg; public long splash,prompt,back; public long total(){return splash+prompt+back;}
    }
    public List<Stat> stats(boolean today) {
        List<Stat> out=new ArrayList<>();
        String sql="SELECT pkg,SUM(CASE WHEN kind='splash' THEN n ELSE 0 END),SUM(CASE WHEN kind='prompt' THEN n ELSE 0 END),SUM(CASE WHEN kind='back' THEN n ELSE 0 END) FROM daily"+(today?" WHERE day=?":"")+" GROUP BY pkg ORDER BY SUM(n) DESC";
        try(Cursor c=getReadableDatabase().rawQuery(sql,today?new String[]{day(System.currentTimeMillis())}:null)) {while(c.moveToNext()){Stat s=new Stat();s.pkg=c.getString(0);s.splash=c.getLong(1);s.prompt=c.getLong(2);s.back=c.getLong(3);out.add(s);}}return out;
    }
    public List<String[]> history() {
        List<String[]> out=new ArrayList<>();
        try(Cursor c=getReadableDatabase().rawQuery("SELECT time,pkg,kind,rule FROM history ORDER BY id DESC LIMIT 30",null)){while(c.moveToNext()) out.add(new String[]{c.getString(0),c.getString(1),c.getString(2),c.getString(3)});}return out;
    }
    public synchronized void clearStats() {SQLiteDatabase db=getWritableDatabase();db.beginTransaction();try{db.delete("daily",null,null);db.delete("history",null,null);db.setTransactionSuccessful();}finally{db.endTransaction();}}
    public String csv() {
        StringBuilder b=new StringBuilder("\uFEFFdate,package,action,count\r\n");
        try(Cursor c=getReadableDatabase().rawQuery("SELECT day,pkg,kind,n FROM daily ORDER BY day DESC,pkg",null)){while(c.moveToNext()) b.append(RuleEngine.csvCell(c.getString(0))).append(',').append(RuleEngine.csvCell(c.getString(1))).append(',').append(RuleEngine.csvCell(c.getString(2))).append(',').append(c.getLong(3)).append("\r\n");}return b.toString();
    }
    public JSONObject ruleDocument() {try{return new JSONObject(prefs.getString("rules","{\"version\":1,\"rules\":[],\"returns\":[]}"));}catch(JSONException e){return new JSONObject();}}
    public List<LocalRule> rules() {
        List<LocalRule> out=new ArrayList<>();JSONArray a=ruleDocument().optJSONArray("rules");
        if(a!=null)for(int i=0;i<a.length();i++)out.add(new LocalRule(a.optJSONObject(i)));return out;
    }
    public boolean returnAllowed(String source,String target) {
        JSONArray a=ruleDocument().optJSONArray("returns");
        if(a!=null)for(int i=0;i<a.length();i++){JSONObject r=a.optJSONObject(i);if(r!=null&&r.optBoolean("enabled",false)&&source.equals(r.optString("source"))&&target.equals(r.optString("target")))return true;}return false;
    }
    public synchronized void importRules(String json) throws JSONException {
        if(json.length()>131072)throw new JSONException("文件超过 128 KB");
        JSONObject doc=new JSONObject(json);if(!(doc.opt("version") instanceof Integer)||doc.getInt("version")!=1)throw new JSONException("仅支持整数 version: 1 的王清理规则");
        JSONArray rules=doc.optJSONArray("rules"),returns=doc.optJSONArray("returns");
        if(rules==null||returns==null||rules.length()>100||returns.length()>100)throw new JSONException("需要 rules 和 returns 数组，每类最多 100 条");
        Set<String> ids=new HashSet<>();
        for(int i=0;i<rules.length();i++) {
            JSONObject r=rules.getJSONObject(i);
            validateTypes(r,new String[]{"id","package","kind","text","viewId","activity","context"});
            String id=r.getString("id"),pkg=r.getString("package"),kind=r.getString("kind");
            if(id.isEmpty()||id.length()>80||!ids.add(id)||!validPackage(pkg)||!(kind.equals("splash")||kind.equals("prompt")))throw new JSONException("规则编号、包名或类型无效");
            if(r.optString("text").isEmpty()&&r.optString("viewId").isEmpty())throw new JSONException("必须指定 text 或 viewId");
            for(String k:new String[]{"text","viewId","activity","context"})if(r.optString(k).length()>200)throw new JSONException("规则字段过长");
            if(kind.equals("prompt")&&r.optString("context").isEmpty())throw new JSONException("提示规则必须指定 context");
            if(!r.optString("viewId").isEmpty()&&!r.optString("viewId").startsWith(pkg+":id/"))throw new JSONException("viewId 必须属于指定应用");
        }
        for(int i=0;i<returns.length();i++){JSONObject r=returns.getJSONObject(i);validateTypes(r,new String[]{"source","target"});String s=r.getString("source"),t=r.getString("target");if(!validPackage(s)||!validPackage(t)||s.equals(t)||s.equals("com.wangqingli.app")||t.equals("com.wangqingli.app"))throw new JSONException("返回规则需要两个不同的外部应用包名");}
        String old=prefs.getString("rules",null);
        if(!prefs.edit().putString("rules",doc.toString()).commit()){
            android.content.SharedPreferences.Editor rollback=prefs.edit();
            if(old==null)rollback.remove("rules");else rollback.putString("rules",old);
            boolean restored=rollback.commit();
            throw new JSONException(restored?"保存失败，已恢复原规则":"保存失败，内存已恢复原规则；请检查磁盘空间");
        }
    }
    private static void validateTypes(JSONObject r,String[] strings) throws JSONException {
        for(String key:strings)if(r.has(key)&&!(r.get(key) instanceof String))throw new JSONException(key+" 必须是字符串");
        if(r.has("enabled")&&!(r.get("enabled") instanceof Boolean))throw new JSONException("enabled 必须是 true 或 false");
    }
    public void addReturn(String source,String target) throws JSONException {
        JSONObject d=ruleDocument();JSONArray a=d.getJSONArray("returns");JSONObject r=new JSONObject();r.put("source",source);r.put("target",target);r.put("enabled",true);a.put(r);importRules(d.toString());
    }
    public static final class LocalRule {
        public final String id,pkg,kind,text,viewId,activity,context;public final boolean enabled;
        LocalRule(JSONObject r){id=r.optString("id");pkg=r.optString("package");kind=r.optString("kind");text=r.optString("text");viewId=r.optString("viewId");activity=r.optString("activity");context=r.optString("context");enabled=r.optBoolean("enabled",true);}
    }
}

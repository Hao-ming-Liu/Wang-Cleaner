package com.wangqingli.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.os.Build;

public final class BootReceiver extends BroadcastReceiver {
    // 收到广播回调：开机完成或应用更新后会触发，用于发送"开机检查"提醒通知
    @Override public void onReceive(Context c,Intent intent){
        String action=intent.getAction();if(!Intent.ACTION_BOOT_COMPLETED.equals(action)&&!Intent.ACTION_MY_PACKAGE_REPLACED.equals(action))return;
        // 记录本次开机时间到 SharedPreferences，便于判断最近一次开机
        Store s=Store.get(c);s.prefs.edit().putLong("lastBoot",System.currentTimeMillis()).apply();
        // 仅在用户开启该功能且启用了开机提醒时才继续，否则直接返回
        if(!s.enabled()||!s.prefs.getBoolean("bootReminder",false))return;
        // Android 13+ 需要通知权限，未授权则无法发通知，直接返回
        if(Build.VERSION.SDK_INT>=33&&c.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)return;
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        // 创建低重要性的通知渠道（若已存在会自动忽略重复创建）
        nm.createNotificationChannel(new NotificationChannel("status","开机检查提醒",NotificationManager.IMPORTANCE_LOW));
        // 点击通知后跳转到主页，FLAG_IMMUTABLE 保证 PendingIntent 在 Android 12+ 上的可变性要求
        PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,MainActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        // 发送通知：提示用户检查无障碍服务的实际连接状态
        nm.notify(1,new Notification.Builder(c,"status").setSmallIcon(R.drawable.ic_shield).setContentTitle("王清理 · 开机检查").setContentText("点击查看无障碍实际连接状态；恢复由系统管理").setContentIntent(open).setAutoCancel(true).build());
    }
}
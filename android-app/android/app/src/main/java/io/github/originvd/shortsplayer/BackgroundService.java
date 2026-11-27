package io.github.originvd.shortsplayer;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Log;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * 앱을 나가거나 화면을 꺼도 영상 받기와 재생이 계속되게 붙잡아 두는 서비스.
 * 알림창에 "재생 중 / 받는 중"을 보여 주고, 재생 중이면 [재생·일시정지] [다음] [정지] 버튼을 달아요.
 * 실제 재생과 받기는 앱(웹 화면, ShortsDownloaderPlugin)이 하고, 이 서비스는 앱이 죽지 않게만 해요.
 */
public class BackgroundService extends Service {
    private static final String TAG = "ShortsBg";
    private static final String CHANNEL = "background";
    private static final int NOTIFICATION_ID = 1;

    static final String ACTION_TOGGLE = "toggle";
    static final String ACTION_NEXT = "next";
    static final String ACTION_STOP = "stop";

    /** 알림 버튼을 눌렀을 때 앱에 알려 줄 곳 */
    interface ActionListener {
        void onAction(String action);
    }

    // ---- 지금 상태 (앱이 바꾸고, 서비스가 알림에 보여 줘요) ----
    static volatile boolean playbackActive = false;
    static volatile boolean playing = false;
    static volatile String title = "";
    static volatile boolean downloading = false;
    static volatile int downloadPercent = 0;
    static volatile int downloadQueued = 0;
    static volatile ActionListener listener = null;

    private static BackgroundService instance = null;
    private PowerManager.WakeLock wakeLock;

    /** 상태가 바뀌면 불러요. 필요하면 서비스를 켜고, 필요 없으면 꺼요. */
    static synchronized void refresh(Context ctx) {
        boolean needed = playbackActive || downloading;
        if (needed && instance == null) {
            try {
                ContextCompat.startForegroundService(ctx, new Intent(ctx, BackgroundService.class));
            } catch (Exception e) {
                // 앱이 완전히 뒤에 있을 때는 안드로이드가 새로 켜는 걸 막을 수 있어요
                Log.w(TAG, "could not start service", e);
            }
        } else if (instance != null) {
            if (needed) instance.showNotification();
            else instance.stopSelf();
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "ShortsPlayer:background");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire();
        createChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        showNotification();
        String action = intent != null ? intent.getAction() : null;
        if (action != null && listener != null) listener.onAction(action);
        if (!playbackActive && !downloading) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        synchronized (BackgroundService.class) {
            instance = null;
        }
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        super.onDestroy();
    }

    @Override
    public void onTaskRemoved(Intent rootIntent) {
        // 최근 앱 목록에서 앱을 밀어서 지우면 재생도 같이 끝내요
        playbackActive = false;
        playing = false;
        if (!downloading) stopSelf();
        else showNotification();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "재생과 받기", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("앱을 나가도 재생과 영상 받기가 계속될 때 보여요");
            ch.setShowBadge(false);
            getSystemService(NotificationManager.class).createNotificationChannel(ch);
        }
    }

    private PendingIntent actionIntent(String action, int code) {
        Intent i = new Intent(this, BackgroundService.class).setAction(action);
        return PendingIntent.getService(this, code, i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    void showNotification() {
        Intent open = new Intent(this, MainActivity.class).setFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        String dl = downloading
            ? "영상 받는 중 " + downloadPercent + "%" + (downloadQueued > 0 ? " · " + downloadQueued + "개 기다리는 중" : "")
            : null;
        String head;
        String body;
        if (playbackActive) {
            head = title == null || title.isEmpty() ? "쇼츠 플레이어" : title;
            body = (playing ? "재생 중" : "일시정지") + (dl != null ? " · " + dl : "");
        } else {
            head = "쇼츠 플레이어";
            body = dl != null ? dl : "";
        }

        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(head)
            .setContentText(body)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(playbackActive ? NotificationCompat.CATEGORY_TRANSPORT : NotificationCompat.CATEGORY_PROGRESS)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC);
        if (downloading && !playbackActive) b.setProgress(100, downloadPercent, downloadPercent <= 0);
        if (playbackActive) {
            b.addAction(playing ? android.R.drawable.ic_media_pause : android.R.drawable.ic_media_play,
                playing ? "일시정지" : "재생", actionIntent(ACTION_TOGGLE, 1));
            b.addAction(android.R.drawable.ic_media_next, "다음", actionIntent(ACTION_NEXT, 2));
            b.addAction(android.R.drawable.ic_menu_close_clear_cancel, "정지", actionIntent(ACTION_STOP, 3));
        }

        int type = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (playbackActive) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK;
            if (downloading) type |= ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
            if (type == 0) type = ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC;
        }
        Notification n = b.build();
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type);
        } catch (Exception e) {
            Log.w(TAG, "startForeground failed", e);
            stopSelf();
        }
    }
}

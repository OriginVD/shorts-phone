package io.github.originvd.shortsplayer;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.yausername.ffmpeg.FFmpeg;
import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;
import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.Executors;
import kotlin.Unit;

/**
 * 링크(틱톡, 유튜브 등)로 영상을 받아 주는 기능.
 * 안에서 yt-dlp를 돌려서 앱 전용 임시 폴더에 받고, 파일 위치를 웹 화면에 알려 줘요.
 * 다 받은 파일은 link-done 폴더로 옮기고, 웹 화면이 보관함(IndexedDB)에 넣은 뒤 remove()로 지워요.
 * 앱이 중간에 꺼져도 link-done에 남은 파일은 다음에 켤 때 listDone()으로 찾아서 넣어요.
 *
 * 재생 상태(setPlayback)도 여기서 받아서 BackgroundService에 넘겨요.
 */
@CapacitorPlugin(name = "ShortsDownloader")
public class ShortsDownloaderPlugin extends Plugin {
    private static final String TAG = "ShortsDownloader";
    private static final String PROCESS_ID = "shorts-dl";

    // yt-dlp 준비·업데이트·받기는 모두 이 한 줄에서 차례대로 해요.
    // (업데이트는 yt-dlp 파일을 지우고 새로 복사해서, 받기와 동시에 돌면 파일이 깨져요)
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean ready = false;
    private volatile boolean updateTried = false;
    private String pendingShare = null;
    private final AtomicInteger queued = new AtomicInteger(0);
    // cancel()을 누르면 올라가요. 그 전에 줄 서 있던 것들은 시작하지 않고 취소돼요.
    private final AtomicInteger generation = new AtomicInteger(0);
    private boolean askedNotify = false;

    @Override
    public void load() {
        pendingShare = sharedText(getActivity().getIntent());
        // 링크를 넣기 전에 미리 준비와 업데이트를 해 둬요
        worker.execute(this::prepareTools);
        BackgroundService.listener = action -> {
            JSObject data = new JSObject();
            data.put("action", action);
            notifyListeners("media", data);
        };
    }

    @Override
    protected void handleOnDestroy() {
        BackgroundService.listener = null;
        BackgroundService.playbackActive = false;
        BackgroundService.playing = false;
        BackgroundService.refresh(getContext());
        super.handleOnDestroy();
    }

    /** 안드로이드 13부터는 알림을 띄우려면 한 번 허락을 받아야 해요 (안 받아도 재생/받기는 돼요) */
    private void askNotificationPermission() {
        if (askedNotify || Build.VERSION.SDK_INT < 33) return;
        askedNotify = true;
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            getActivity().runOnUiThread(() ->
                ActivityCompat.requestPermissions(getActivity(), new String[] { Manifest.permission.POST_NOTIFICATIONS }, 7001));
        }
    }

    /** 웹 화면이 재생 상태를 알려 줘요: { active, playing, title } */
    @PluginMethod
    public void setPlayback(PluginCall call) {
        boolean active = Boolean.TRUE.equals(call.getBoolean("active", false));
        boolean wasActive = BackgroundService.playbackActive;
        BackgroundService.playbackActive = active;
        BackgroundService.playing = Boolean.TRUE.equals(call.getBoolean("playing", false));
        BackgroundService.title = call.getString("title", "");
        if (active && !wasActive) askNotificationPermission();
        BackgroundService.refresh(getContext());
        call.resolve();
    }

    private void setDownloadState(boolean on, int percent) {
        BackgroundService.downloading = on;
        BackgroundService.downloadPercent = percent;
        BackgroundService.downloadQueued = Math.max(0, queued.get() - (on ? 1 : 0));
        BackgroundService.refresh(getContext());
    }

    @Override
    protected void handleOnNewIntent(Intent intent) {
        super.handleOnNewIntent(intent);
        String text = sharedText(intent);
        if (text != null) {
            JSObject data = new JSObject();
            data.put("text", text);
            notifyListeners("shared", data, true);
        }
    }

    private static String sharedText(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return null;
        return intent.getStringExtra(Intent.EXTRA_TEXT);
    }

    /** 앱이 다른 앱의 "공유"로 열렸을 때 받은 글을 한 번만 돌려줘요. */
    @PluginMethod
    public void getSharedText(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("text", pendingShare);
        pendingShare = null;
        call.resolve(ret);
    }

    // ---- 아래 셋은 꼭 worker 줄 안에서만 불러요 ----

    private void ensureReady() throws Exception {
        if (ready) return;
        YoutubeDL.getInstance().init(getContext());
        FFmpeg.getInstance().init(getContext());
        ready = true;
    }

    /** 준비하고, 사이트가 바뀌어도 받을 수 있게 앱을 켤 때마다 한 번 yt-dlp 새 버전을 확인해요. */
    private void prepareTools() {
        try {
            ensureReady();
        } catch (Exception e) {
            Log.e(TAG, "init failed", e);
            return;
        }
        if (updateTried) return;
        updateTried = true;
        try {
            YoutubeDL.getInstance().updateYoutubeDL(getContext(), YoutubeDL.UpdateChannel._STABLE);
        } catch (Exception e) {
            Log.w(TAG, "yt-dlp update failed", e);
        }
    }

    /** yt-dlp 파일이 깨졌을 때: 지우고 앱에 들어 있는 원래 yt-dlp로 되돌려요. 다음에 켤 때 다시 업데이트해요. */
    private void resetYtdlp() throws Exception {
        File dir = new File(new File(getContext().getNoBackupFilesDir(), YoutubeDL.baseName), YoutubeDL.ytdlpDirName);
        deleteTree(dir);
        getContext().getSharedPreferences(YoutubeDL.baseName, android.content.Context.MODE_PRIVATE)
            .edit().remove("dlpVersion").remove("dlpVersionName").apply();
        YoutubeDL.getInstance().init_ytdlp(getContext(), dir);
        updateTried = false;
    }

    private static boolean looksBroken(Exception e) {
        String m = String.valueOf(e.getMessage());
        return m.contains("zipimport") || m.contains("ZipImportError") || m.contains("bad local file header")
            || m.contains("BadZipFile") || m.contains("can't find '__main__' module");
    }

    private File workDir() {
        return new File(getContext().getCacheDir(), "link-download");
    }

    private File doneDir() {
        return new File(getContext().getFilesDir(), "link-done");
    }

    private static void deleteTree(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    @PluginMethod
    public void download(PluginCall call) {
        String url = call.getString("url");
        if (url == null || url.trim().isEmpty()) {
            call.reject("링크가 비어 있어요.");
            return;
        }
        final int myGen = generation.get();
        queued.incrementAndGet();
        askNotificationPermission();
        BackgroundService.downloadQueued = Math.max(0, queued.get() - (BackgroundService.downloading ? 1 : 0));
        BackgroundService.refresh(getContext());
        worker.execute(() -> {
            File dir = new File(workDir(), String.valueOf(System.nanoTime()));
            try {
                if (myGen != generation.get()) throw new YoutubeDL.CanceledException();
                setDownloadState(true, 0);
                prepareTools();
                dir.mkdirs();

                YoutubeDLRequest req = new YoutubeDLRequest(url.trim());
                // 이름은 PC에서 받던 것과 같은 모양: "출처_제목 [id].mp4"
                req.addOption("-o", dir.getAbsolutePath() + "/%(extractor_key)s_%(title).80B [%(id)s].%(ext)s");
                // 화질은 최대 1080p (세로 영상도 짧은 쪽 기준), 되도록 폰에서 잘 돌아가는 h264/mp4로
                req.addOption("-f", "bv*+ba/b");
                req.addOption("-S", "res:1080,vcodec:h264,ext:mp4:m4a");
                req.addOption("--merge-output-format", "mp4");
                req.addOption("--no-playlist");
                req.addOption("--no-mtime");
                req.addOption("--no-part");

                YoutubeDLResponse res;
                try {
                    res = runYtdlp(req);
                } catch (YoutubeDL.CanceledException e) {
                    throw e;
                } catch (Exception e) {
                    if (!looksBroken(e)) throw e;
                    // yt-dlp 파일이 깨졌으면 원래 것으로 되돌리고 한 번 더 해 봐요
                    Log.w(TAG, "yt-dlp looks broken, resetting", e);
                    resetYtdlp();
                    deleteTree(dir);
                    dir.mkdirs();
                    res = runYtdlp(req);
                }

                File best = null;
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File f : files) {
                        if (f.isFile() && (best == null || f.length() > best.length())) best = f;
                    }
                }
                if (best == null || best.length() == 0) {
                    call.reject("받은 파일을 찾지 못했어요.\n" + lastLines(res.getErr()));
                    return;
                }
                File done = doneDir();
                done.mkdirs();
                File target = new File(done, best.getName());
                if (target.exists()) target.delete();
                if (!best.renameTo(target)) {
                    target = best; // 옮기지 못하면 그 자리에서 써요
                }
                call.resolve(fileInfo(target));
            } catch (YoutubeDL.CanceledException e) {
                call.reject("취소했어요.", "CANCELED");
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                call.reject("받지 못했어요.\n" + lastLines(String.valueOf(e.getMessage())));
            } finally {
                if (!dir.equals(workDir())) deleteTree(dir);
                queued.decrementAndGet();
                setDownloadState(queued.get() > 0, 0);
            }
        });
    }

    private YoutubeDLResponse runYtdlp(YoutubeDLRequest req) throws Exception {
        final int[] last = { -1 };
        return YoutubeDL.getInstance().execute(req, PROCESS_ID, (progress, eta, line) -> {
            int pct = Math.max(0, Math.min(100, Math.round(progress)));
            JSObject p = new JSObject();
            p.put("percent", progress);
            p.put("eta", eta);
            notifyListeners("progress", p);
            if (pct != last[0]) {
                last[0] = pct;
                setDownloadState(true, pct);
            }
            return Unit.INSTANCE;
        });
    }

    private static JSObject fileInfo(File f) {
        JSObject o = new JSObject();
        o.put("path", f.getAbsolutePath());
        o.put("name", f.getName());
        o.put("size", f.length());
        return o;
    }

    private static String lastLines(String s) {
        if (s == null) return "";
        String[] lines = s.trim().split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) b.append(lines[i]).append('\n');
        return b.toString().trim();
    }

    @PluginMethod
    public void cancel(PluginCall call) {
        generation.incrementAndGet();
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID);
        call.resolve();
    }

    /** 다 받았지만 아직 보관함에 못 넣은 파일들 */
    @PluginMethod
    public void listDone(PluginCall call) {
        JSArray arr = new JSArray();
        File[] files = doneDir().listFiles();
        if (files != null) for (File f : files) if (f.isFile()) arr.put(fileInfo(f));
        JSObject ret = new JSObject();
        ret.put("files", arr);
        call.resolve(ret);
    }

    /** 보관함에 넣은 파일을 지워요 (link-done, link-download 안에 있는 것만) */
    @PluginMethod
    public void remove(PluginCall call) {
        String path = call.getString("path");
        if (path != null) {
            File f = new File(path);
            try {
                String p = f.getCanonicalPath();
                if (p.startsWith(doneDir().getCanonicalPath() + File.separator) || p.startsWith(workDir().getCanonicalPath() + File.separator)) {
                    f.delete();
                }
            } catch (Exception e) {
                Log.w(TAG, "remove failed", e);
            }
        }
        call.resolve();
    }
}

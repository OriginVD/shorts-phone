package io.github.originvd.shortsplayer;

import android.content.Intent;
import android.util.Log;
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
import java.util.concurrent.Executors;
import kotlin.Unit;

/**
 * 링크(틱톡, 유튜브 등)로 영상을 받아 주는 기능.
 * 안에서 yt-dlp를 돌려서 앱 전용 임시 폴더에 받고, 파일 위치를 웹 화면에 알려 줘요.
 * 웹 화면이 그 파일을 보관함(IndexedDB)에 넣은 뒤 cleanup()으로 임시 파일을 지워요.
 */
@CapacitorPlugin(name = "ShortsDownloader")
public class ShortsDownloaderPlugin extends Plugin {
    private static final String TAG = "ShortsDownloader";
    private static final String PROCESS_ID = "shorts-dl";

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService updater = Executors.newSingleThreadExecutor();
    private volatile boolean ready = false;
    private volatile boolean updateTried = false;
    private String pendingShare = null;

    @Override
    public void load() {
        pendingShare = sharedText(getActivity().getIntent());
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

    private synchronized void ensureReady() throws Exception {
        if (ready) return;
        YoutubeDL.getInstance().init(getContext());
        FFmpeg.getInstance().init(getContext());
        ready = true;
    }

    /** 사이트가 바뀌면 옛 yt-dlp로는 못 받을 수 있어서, 앱을 켤 때마다 한 번 새 버전을 확인해요. */
    private void updateInBackground() {
        if (updateTried) return;
        updateTried = true;
        updater.execute(() -> {
            try {
                YoutubeDL.getInstance().updateYoutubeDL(getContext(), YoutubeDL.UpdateChannel._STABLE);
            } catch (Exception e) {
                Log.w(TAG, "yt-dlp update failed", e);
            }
        });
    }

    private File workDir() {
        return new File(getContext().getCacheDir(), "link-download");
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
        worker.execute(() -> {
            try {
                ensureReady();
                updateInBackground();

                File dir = workDir();
                deleteTree(dir);
                dir.mkdirs();

                YoutubeDLRequest req = new YoutubeDLRequest(url.trim());
                // 이름은 PC에서 받던 것과 같은 모양: "출처_제목 [id].mp4"
                req.addOption("-o", dir.getAbsolutePath() + "/%(extractor_key)s_%(title).80B [%(id)s].%(ext)s");
                req.addOption("-f", "bv*[ext=mp4]+ba[ext=m4a]/b[ext=mp4]/bv*+ba/b");
                req.addOption("--merge-output-format", "mp4");
                req.addOption("--no-playlist");
                req.addOption("--no-mtime");
                req.addOption("--no-part");

                YoutubeDLResponse res = YoutubeDL.getInstance().execute(req, PROCESS_ID, (progress, eta, line) -> {
                    JSObject p = new JSObject();
                    p.put("percent", progress);
                    p.put("eta", eta);
                    notifyListeners("progress", p);
                    return Unit.INSTANCE;
                });

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
                JSObject ret = new JSObject();
                ret.put("path", best.getAbsolutePath());
                ret.put("name", best.getName());
                ret.put("size", best.length());
                call.resolve(ret);
            } catch (YoutubeDL.CanceledException e) {
                call.reject("취소했어요.", "CANCELED");
            } catch (Exception e) {
                Log.e(TAG, "download failed", e);
                call.reject("받지 못했어요.\n" + lastLines(String.valueOf(e.getMessage())));
            }
        });
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
        YoutubeDL.getInstance().destroyProcessById(PROCESS_ID);
        call.resolve();
    }

    @PluginMethod
    public void cleanup(PluginCall call) {
        worker.execute(() -> {
            deleteTree(workDir());
            call.resolve();
        });
    }
}

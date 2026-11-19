package io.github.originvd.shortsplayer;

import android.os.Bundle;
import android.view.WindowManager;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 앱 안의 WebView에서는 웹의 화면 꺼짐 방지(wakeLock)가 안 될 수 있어서,
        // 앱이 앞에 떠 있는 동안에는 화면이 꺼지지 않게 해요.
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
}

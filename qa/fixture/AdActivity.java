package io.qingping.fixture;

import android.app.Activity;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A test-only ad page; it contains no advertisements or network traffic. */
public class AdActivity extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40, 100, 40, 40);
        TextView title = new TextView(this);
        title.setText("模拟开屏测试页面");
        if (getIntent().getBooleanExtra("exitPage", false)) title.setText("广告落地页测试");
        title.setTextSize(24);
        box.addView(title);
        Button skip = new Button(this);
        skip.setText(getIntent().getBooleanExtra("ordinary", false) ? "跳过" : "跳过广告");
        skip.setOnClickListener(v -> {
            if (getIntent().getBooleanExtra("noEffect", false)) return;
            title.setText("已进入内容页");
            box.removeView(skip);
        });
        box.addView(skip);
        setContentView(box);
        if (getIntent().getBooleanExtra("lateAd", false)) {
            title.postDelayed(() -> title.setText("稍后广告测试"), getIntent().getLongExtra("adDelayMs", 16000));
        }
    }
}

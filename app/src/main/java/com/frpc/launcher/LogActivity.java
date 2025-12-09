package com.frpc.launcher;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class LogActivity extends AppCompatActivity {
    private TextView tvLog;
    private ScrollView scrollView;
    private Button btnClear;
    private Button btnCopy;
    private BroadcastReceiver outputReceiver;
    private StringBuilder logBuffer = new StringBuilder();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_log);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("运行日志");
        }

        tvLog = findViewById(R.id.tvLog);
        scrollView = findViewById(R.id.scrollView);
        btnClear = findViewById(R.id.btnClear);
        btnCopy = findViewById(R.id.btnCopy);

        btnClear.setOnClickListener(v -> clearLog());
        btnCopy.setOnClickListener(v -> copyLogToClipboard());

        // 注册输出接收器
        outputReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String line = intent.getStringExtra(FrpcService.EXTRA_OUTPUT_LINE);
                if (line != null) {
                    appendLog(line);
                }
            }
        };
        // Android 13+ 需要指定 RECEIVER_NOT_EXPORTED 标志
        // 三参数版本需要 API 26+，所以需要版本检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33+)
            registerReceiver(outputReceiver, new IntentFilter(FrpcService.ACTION_OUTPUT), Context.RECEIVER_NOT_EXPORTED);
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0+ (API 26-32)，使用三参数版本但不设置标志
            registerReceiver(outputReceiver, new IntentFilter(FrpcService.ACTION_OUTPUT), 0);
        } else {
            // Android 7.1 及以下 (API 21-25)，使用两参数版本
            // 这些版本不需要 RECEIVER_NOT_EXPORTED 标志，lint 警告可以忽略
            @SuppressLint("UnspecifiedRegisterReceiverFlag")
            IntentFilter outputFilter = new IntentFilter(FrpcService.ACTION_OUTPUT);
            registerReceiver(outputReceiver, outputFilter);
        }

        // 显示现有日志
        updateLogDisplay();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void appendLog(String line) {
        runOnUiThread(() -> {
            if (logBuffer.length() > 50000) {
                // 限制日志长度，避免内存问题
                int keepLength = 25000;
                logBuffer.delete(0, logBuffer.length() - keepLength);
            }
            logBuffer.append(line).append("\n");
            updateLogDisplay();
        });
    }

    private void updateLogDisplay() {
        if (tvLog != null) {
            tvLog.setText(logBuffer.length() > 0 ? logBuffer.toString() : "暂无日志");
            // 自动滚动到底部
            scrollView.post(() -> scrollView.fullScroll(View.FOCUS_DOWN));
        }
    }

    private void clearLog() {
        logBuffer.setLength(0);
        updateLogDisplay();
        Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show();
    }

    private void copyLogToClipboard() {
        String logText = logBuffer.length() > 0 ? logBuffer.toString() : "暂无日志";
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        ClipData clip = ClipData.newPlainText("Frpc日志", logText);
        clipboard.setPrimaryClip(clip);
        Toast.makeText(this, "日志已复制到剪贴板", Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (outputReceiver != null) {
            unregisterReceiver(outputReceiver);
        }
    }
}


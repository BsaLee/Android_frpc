package com.frpc.launcher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.OutputStream;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class MainActivity extends AppCompatActivity {
    private static final int PERMISSION_REQUEST_CODE = 1001;
    private Button btnStart;
    private Button btnStop;
    private Button btnViewLog;
    private Button btnSettings;
    private Button btnAbout;
    private Button btnCopyConnection;
    private TextView tvRootStatus;
    private TextView tvStatus;
    private TextView tvInfo;
    private TextView tvConnection;
    private LinearLayout llConnection;
    private BroadcastReceiver errorReceiver;
    private BroadcastReceiver startedReceiver;

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        try {
            setContentView(R.layout.activity_main);

            Toolbar toolbar = findViewById(R.id.toolbar);
            if (toolbar != null) {
                setSupportActionBar(toolbar);
            }

            btnStart = findViewById(R.id.btnStart);
            btnStop = findViewById(R.id.btnStop);
            btnViewLog = findViewById(R.id.btnViewLog);
            btnSettings = findViewById(R.id.btnSettings);
            btnAbout = findViewById(R.id.btnAbout);
            btnCopyConnection = findViewById(R.id.btnCopyConnection);
            tvRootStatus = findViewById(R.id.tvRootStatus);
            tvStatus = findViewById(R.id.tvStatus);
            tvInfo = findViewById(R.id.tvInfo);
            tvConnection = findViewById(R.id.tvConnection);
            llConnection = findViewById(R.id.llConnection);

            // 检查必要的视图是否初始化成功
            if (btnStart == null || btnStop == null || tvStatus == null) {
                Log.e("MainActivity", "Failed to initialize views");
                Toast.makeText(this, "界面初始化失败", Toast.LENGTH_LONG).show();
                finish();
                return;
            }

            requestPermissions();
            
            // 检测root权限
            checkRootPermission();

        btnStart.setOnClickListener(v -> startFrpc());
        btnStop.setOnClickListener(v -> stopFrpc());
        btnViewLog.setOnClickListener(v -> {
            Intent intent = new Intent(this, LogActivity.class);
            startActivity(intent);
        });
        btnSettings.setOnClickListener(v -> {
            Intent intent = new Intent(this, SettingsActivity.class);
            startActivity(intent);
        });
        btnAbout.setOnClickListener(v -> {
            Intent intent = new Intent(this, AboutActivity.class);
            startActivity(intent);
        });
        btnCopyConnection.setOnClickListener(v -> copyConnectionToClipboard());

        // 注册错误接收器
        errorReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String errorMsg = intent.getStringExtra(FrpcService.EXTRA_ERROR_MESSAGE);
                if (errorMsg != null) {
                    showErrorDialog(errorMsg);
                    // 错误信息会通过 ACTION_OUTPUT 广播自动发送到 LogActivity
                }
            }
        };
        registerReceiverCompat(errorReceiver, FrpcService.ACTION_ERROR);
        
        // 注册启动成功接收器
        startedReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (FrpcService.ACTION_STARTED.equals(intent.getAction())) {
                    // 延迟一下，确保remotePort已经设置
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        updateUI();
                    }, 500);
                }
            }
        };
        registerReceiverCompat(startedReceiver, FrpcService.ACTION_STARTED);

        updateUI();
        } catch (Exception e) {
            Log.e("MainActivity", "Error in onCreate", e);
            Toast.makeText(this, "应用启动失败: " + e.getMessage(), Toast.LENGTH_LONG).show();
            e.printStackTrace();
            finish();
        }
    }

    private void requestPermissions() {
        // 不需要存储权限，因为使用应用专用目录 (getExternalFilesDir)
        // 应用专用目录不需要任何权限
    }
    
    private void checkRootPermission() {
        // 显示检测中状态
        if (tvRootStatus != null) {
            tvRootStatus.setText("检测Root权限中...");
            tvRootStatus.setTextColor(getResources().getColor(android.R.color.holo_orange_dark));
        }
        Toast.makeText(this, "正在检测Root权限...", Toast.LENGTH_SHORT).show();
        
        new Thread(() -> {
            boolean hasRoot = isRootAvailable();
            runOnUiThread(() -> {
                if (!hasRoot) {
                    Log.w("MainActivity", "Root权限检测失败");
                    if (tvRootStatus != null) {
                        tvRootStatus.setText("❌ 未检测到Root权限");
                        tvRootStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
                    }
                    showRootWarningDialog();
                    Toast.makeText(this, "未检测到Root权限，frpc可能无法正常启动", Toast.LENGTH_LONG).show();
                } else {
                    Log.d("MainActivity", "Root权限检测通过");
                    if (tvRootStatus != null) {
                        tvRootStatus.setText("✓ Root权限已获取");
                        tvRootStatus.setTextColor(getResources().getColor(android.R.color.holo_green_dark));
                    }
                    Toast.makeText(this, "Root权限检测通过", Toast.LENGTH_SHORT).show();
                }
            });
        }).start();
    }
    
    private boolean isRootAvailable() {
        try {
            // 尝试执行su命令
            Process process = Runtime.getRuntime().exec("su");
            OutputStream os = process.getOutputStream();
            os.write("exit\n".getBytes());
            os.flush();
            os.close();
            
            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (Exception e) {
            return false;
        }
    }
    
    private void showRootWarningDialog() {
        new AlertDialog.Builder(this)
                .setTitle("需要Root权限")
                .setMessage("此应用需要Root权限才能正常运行frpc服务。\n\n" +
                        "请确保：\n" +
                        "1. 设备已获取Root权限\n" +
                        "2. 已安装SuperSU、Magisk等Root管理工具\n" +
                        "3. 已授予此应用的Root权限\n\n" +
                        "如果没有Root权限，frpc可能无法正常启动。")
                .setPositiveButton("确定", null)
                .setCancelable(false)
                .show();
    }

    private void startFrpc() {
        Intent intent = new Intent(this, FrpcService.class);
        intent.setAction(FrpcService.ACTION_START);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent);
        } else {
            startService(intent);
        }
        updateUI();
        Toast.makeText(this, "正在启动frpc...", Toast.LENGTH_SHORT).show();
    }

    private void stopFrpc() {
        Intent intent = new Intent(this, FrpcService.class);
        intent.setAction(FrpcService.ACTION_STOP);
        stopService(intent);
        updateUI();
        Toast.makeText(this, "正在停止frpc...", Toast.LENGTH_SHORT).show();
    }

    private void updateUI() {
        try {
            boolean isRunning = FrpcService.isRunning();
            btnStart.setEnabled(!isRunning);
            btnStop.setEnabled(isRunning);
            tvStatus.setText(isRunning ? "运行中" : "已停止");
            tvStatus.setTextColor(isRunning ?
                    getResources().getColor(android.R.color.holo_green_dark) :
                    getResources().getColor(android.R.color.darker_gray));

            if (isRunning) {
                String info = FrpcService.getLastInfo();
                tvInfo.setText(info != null ? info : "frpc正在运行");
                
                // 显示连接信息
                try {
                    String connectionInfo = FrpcService.getConnectionInfo();
                    int remotePort = FrpcService.getRemotePort();
                    Log.d("MainActivity", "Connection info: " + connectionInfo + ", remotePort: " + remotePort);
                    if (connectionInfo != null && !connectionInfo.isEmpty() && remotePort > 0) {
                        tvConnection.setText(connectionInfo);
                        llConnection.setVisibility(View.VISIBLE);
                        Log.d("MainActivity", "Displaying connection: " + connectionInfo);
                    } else {
                        tvConnection.setText("等待连接...");
                        llConnection.setVisibility(View.GONE);
                        Log.d("MainActivity", "Connection info not ready yet");
                    }
                } catch (Exception e) {
                    Log.e("MainActivity", "Error getting connection info", e);
                    tvConnection.setText("获取连接信息失败");
                    llConnection.setVisibility(View.GONE);
                }
            } else {
                tvInfo.setText("");
                tvConnection.setText("");
                llConnection.setVisibility(View.GONE);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error in updateUI", e);
            // 确保至少显示基本状态
            tvStatus.setText("错误");
            tvStatus.setTextColor(getResources().getColor(android.R.color.holo_red_dark));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
        // 检查是否有错误
        String error = FrpcService.getLastError();
        if (error != null) {
            showErrorDialog(error);
            FrpcService.clearError();
        }
    }

    /**
     * 兼容方法：注册 BroadcastReceiver，自动处理不同 Android 版本的 API 差异
     * Android 13+ 需要指定 RECEIVER_NOT_EXPORTED 标志
     * 三参数版本需要 API 26+
     */
    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    private void registerReceiverCompat(BroadcastReceiver receiver, String action) {
        try {
            if (receiver == null || action == null) {
                Log.e("MainActivity", "Invalid receiver or action");
                return;
            }
            IntentFilter filter = new IntentFilter(action);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ (API 33+)
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ (API 26-32)，使用三参数版本但不设置标志
                registerReceiver(receiver, filter, 0);
            } else {
                // Android 7.1 及以下 (API 21-25)，使用两参数版本
                registerReceiver(receiver, filter);
            }
        } catch (Exception e) {
            Log.e("MainActivity", "Error registering receiver for action: " + action, e);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (errorReceiver != null) {
            unregisterReceiver(errorReceiver);
        }
        if (startedReceiver != null) {
            unregisterReceiver(startedReceiver);
        }
    }

    private void showErrorDialog(String message) {
        new AlertDialog.Builder(this)
                .setTitle("启动失败")
                .setMessage(message + "\n\n请确保在构建APK前已运行 prepare_assets.bat 或 prepare_assets.sh 脚本，将frpc文件复制到assets目录。")
                .setPositiveButton("确定", null)
                .show();
    }
    
    private void copyConnectionToClipboard() {
        String connectionInfo = FrpcService.getConnectionInfo();
        if (connectionInfo != null && !connectionInfo.isEmpty()) {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("Frpc连接信息", connectionInfo);
            clipboard.setPrimaryClip(clip);
            Toast.makeText(this, "连接信息已复制: " + connectionInfo, Toast.LENGTH_SHORT).show();
        } else {
            Toast.makeText(this, "暂无连接信息", Toast.LENGTH_SHORT).show();
        }
    }
}


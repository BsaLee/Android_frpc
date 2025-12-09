package com.frpc.launcher;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class SettingsActivity extends AppCompatActivity {
    private static final String PREFS_NAME = "frpc_settings";
    private static final String KEY_SERVER_ADDR = "server_addr";
    private static final String KEY_SERVER_PORT = "server_port";
    private static final String KEY_AUTH_TOKEN = "auth_token";
    private static final String KEY_LOCAL_PORT = "local_port";
    private static final String KEY_RANDOM_PORT_MIN = "random_port_min";
    private static final String KEY_RANDOM_PORT_MAX = "random_port_max";

    private EditText etServerAddr;
    private EditText etServerPort;
    private EditText etAuthToken;
    private EditText etLocalPort;
    private EditText etRandomPortMin;
    private EditText etRandomPortMax;
    private Button btnSave;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("设置");
        }

        prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);

        etServerAddr = findViewById(R.id.etServerAddr);
        etServerPort = findViewById(R.id.etServerPort);
        etAuthToken = findViewById(R.id.etAuthToken);
        etLocalPort = findViewById(R.id.etLocalPort);
        etRandomPortMin = findViewById(R.id.etRandomPortMin);
        etRandomPortMax = findViewById(R.id.etRandomPortMax);
        btnSave = findViewById(R.id.btnSave);

        // 加载保存的设置
        loadSettings();

        btnSave.setOnClickListener(v -> saveSettings());
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void loadSettings() {
        String serverAddr = prefs.getString(KEY_SERVER_ADDR, ConfigConstants.DEFAULT_SERVER_ADDR);
        int serverPort = prefs.getInt(KEY_SERVER_PORT, ConfigConstants.DEFAULT_SERVER_PORT);
        String authToken = prefs.getString(KEY_AUTH_TOKEN, "");
        int localPort = prefs.getInt(KEY_LOCAL_PORT, ConfigConstants.DEFAULT_LOCAL_PORT);
        int randomPortMin = prefs.getInt(KEY_RANDOM_PORT_MIN, ConfigConstants.DEFAULT_RANDOM_PORT_MIN);
        int randomPortMax = prefs.getInt(KEY_RANDOM_PORT_MAX, ConfigConstants.DEFAULT_RANDOM_PORT_MAX);

        etServerAddr.setText(serverAddr);
        etServerPort.setText(String.valueOf(serverPort));
        etAuthToken.setText(authToken);
        etLocalPort.setText(String.valueOf(localPort));
        etRandomPortMin.setText(String.valueOf(randomPortMin));
        etRandomPortMax.setText(String.valueOf(randomPortMax));
    }

    private void saveSettings() {
        String serverAddr = etServerAddr.getText().toString().trim();
        String serverPortStr = etServerPort.getText().toString().trim();
        String authToken = etAuthToken.getText().toString().trim();
        String localPortStr = etLocalPort.getText().toString().trim();
        String randomPortMinStr = etRandomPortMin.getText().toString().trim();
        String randomPortMaxStr = etRandomPortMax.getText().toString().trim();

        // 验证输入
        if (serverAddr.isEmpty()) {
            Toast.makeText(this, "服务器地址不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        int serverPort;
        try {
            serverPort = Integer.parseInt(serverPortStr);
            if (serverPort < 1 || serverPort > 65535) {
                Toast.makeText(this, "服务器端口必须在 1-65535 之间", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "服务器端口必须是数字", Toast.LENGTH_SHORT).show();
            return;
        }

        int localPort;
        try {
            localPort = Integer.parseInt(localPortStr);
            if (localPort < 1 || localPort > 65535) {
                Toast.makeText(this, "本地端口必须在 1-65535 之间", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "本地端口必须是数字", Toast.LENGTH_SHORT).show();
            return;
        }

        int randomPortMin;
        try {
            randomPortMin = Integer.parseInt(randomPortMinStr);
            if (randomPortMin < 1 || randomPortMin > 65535) {
                Toast.makeText(this, "随机端口最小值必须在 1-65535 之间", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "随机端口最小值必须是数字", Toast.LENGTH_SHORT).show();
            return;
        }

        int randomPortMax;
        try {
            randomPortMax = Integer.parseInt(randomPortMaxStr);
            if (randomPortMax < 1 || randomPortMax > 65535) {
                Toast.makeText(this, "随机端口最大值必须在 1-65535 之间", Toast.LENGTH_SHORT).show();
                return;
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "随机端口最大值必须是数字", Toast.LENGTH_SHORT).show();
            return;
        }

        if (randomPortMin >= randomPortMax) {
            Toast.makeText(this, "随机端口最小值必须小于最大值", Toast.LENGTH_SHORT).show();
            return;
        }

        // 保存设置
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_SERVER_ADDR, serverAddr);
        editor.putInt(KEY_SERVER_PORT, serverPort);
        editor.putString(KEY_AUTH_TOKEN, authToken);
        editor.putInt(KEY_LOCAL_PORT, localPort);
        editor.putInt(KEY_RANDOM_PORT_MIN, randomPortMin);
        editor.putInt(KEY_RANDOM_PORT_MAX, randomPortMax);
        editor.apply();

        Toast.makeText(this, "设置已保存", Toast.LENGTH_SHORT).show();
        
        // 如果frpc正在运行，提示需要重启
        if (FrpcService.isRunning()) {
            Toast.makeText(this, "请停止并重新启动frpc以使设置生效", Toast.LENGTH_LONG).show();
        }
    }

    public static String getServerAddr(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_SERVER_ADDR, ConfigConstants.DEFAULT_SERVER_ADDR);
    }

    public static int getServerPort(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_SERVER_PORT, ConfigConstants.DEFAULT_SERVER_PORT);
    }

    public static String getAuthToken(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_AUTH_TOKEN, "");
    }

    public static int getLocalPort(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_LOCAL_PORT, ConfigConstants.DEFAULT_LOCAL_PORT);
    }

    public static int getRandomPortMin(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_RANDOM_PORT_MIN, ConfigConstants.DEFAULT_RANDOM_PORT_MIN);
    }

    public static int getRandomPortMax(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getInt(KEY_RANDOM_PORT_MAX, ConfigConstants.DEFAULT_RANDOM_PORT_MAX);
    }
}


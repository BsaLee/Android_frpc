package com.frpc.launcher;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;

public class AboutActivity extends AppCompatActivity {
    private static final String GITHUB_URL = "https://github.com/bsalee";
    private static final String EMAIL = "luqiao321@gmail.com";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);

        Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
            getSupportActionBar().setTitle("关于软件");
        }

        TextView tvAppName = findViewById(R.id.tvAppName);
        TextView tvVersion = findViewById(R.id.tvVersion);
        TextView tvAuthor = findViewById(R.id.tvAuthor);
        TextView tvEmail = findViewById(R.id.tvEmail);
        TextView tvDescription = findViewById(R.id.tvDescription);
        Button btnGitHub = findViewById(R.id.btnGitHub);
        Button btnEmail = findViewById(R.id.btnEmail);

        // 设置应用信息
        tvAppName.setText("Frpc启动器");
        try {
            android.content.pm.PackageInfo packageInfo = getPackageManager().getPackageInfo(getPackageName(), 0);
            String versionName = packageInfo.versionName != null ? packageInfo.versionName : "1.0";
            int versionCode = packageInfo.versionCode;
            tvVersion.setText("版本: " + versionName + " (Build " + versionCode + ")");
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            tvVersion.setText("版本: 1.0");
        }

        tvAuthor.setText("神仙小分队");
        tvEmail.setText(EMAIL);
        tvDescription.setText("一个用于在 Android 设备上运行 frpc 代理服务的启动器应用。\n\n" +
                "功能特点：\n" +
                "• 一键启动/停止 frpc 服务\n" +
                "• 实时查看运行日志\n" +
                "• 自定义服务器配置\n" +
                "• 显示连接信息\n" +
                "• 支持 Root 权限执行");

        // GitHub 按钮
        btnGitHub.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(GITHUB_URL));
            startActivity(intent);
        });

        // 邮箱按钮
        btnEmail.setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_SENDTO);
            intent.setData(Uri.parse("mailto:" + EMAIL));
            intent.putExtra(Intent.EXTRA_SUBJECT, "关于 Frpc启动器");
            try {
                startActivity(Intent.createChooser(intent, "选择邮箱应用"));
            } catch (Exception e) {
                android.widget.Toast.makeText(this, "未找到邮箱应用", android.widget.Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public boolean onOptionsItemSelected(android.view.MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
}


package com.shuati.app;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {

    private EditText etServerUrl;
    private RadioGroup rgConnectionType;
    private RadioButton rbLocal, rbTailscale, rbCustom;
    private TextView tvHelp;
    private Button btnSave, btnTest;
    private SharedPreferences prefs;

    // 预设的 Tailscale 地址
    private static final String DEFAULT_TAILSCALE_URL = "http://100.122.70.99:8087";
    private static final String DEFAULT_LOCAL_URL = "http://192.168.2.166:8087";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        prefs = getSharedPreferences("shuati", MODE_PRIVATE);

        etServerUrl = findViewById(R.id.et_server_url);
        rgConnectionType = findViewById(R.id.rg_connection_type);
        rbLocal = findViewById(R.id.rb_local);
        rbTailscale = findViewById(R.id.rb_tailscale);
        rbCustom = findViewById(R.id.rb_custom);
        tvHelp = findViewById(R.id.tv_help);
        btnSave = findViewById(R.id.btn_save);
        btnTest = findViewById(R.id.btn_test);

        // 加载已保存的设置
        loadSettings();

        // 连接类型切换
        rgConnectionType.setOnCheckedChangeListener((group, checkedId) -> {
            if (checkedId == R.id.rb_local) {
                etServerUrl.setText(DEFAULT_LOCAL_URL);
                tvHelp.setText("局域网连接：确保手机和服务器在同一 WiFi 下");
            } else if (checkedId == R.id.rb_tailscale) {
                etServerUrl.setText(DEFAULT_TAILSCALE_URL);
                tvHelp.setText("Tailscale 连接：需要手机安装 Tailscale 并加入同一网络\n默认地址: 100.122.70.99:8087");
            } else if (checkedId == R.id.rb_custom) {
                etServerUrl.setText("");
                etServerUrl.setHint("输入自定义地址，如 http://xxx.xxx.xxx.xxx:8087");
                tvHelp.setText("自定义连接：输入完整的服务器地址");
            }
            etServerUrl.requestFocus();
        });

        // 保存按钮
        btnSave.setOnClickListener(v -> {
            String url = etServerUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "请输入服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }

            // 自动添加 http:// 前缀
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
            }

            prefs.edit()
                .putString("server_url", url)
                .putInt("connection_type", rgConnectionType.getCheckedRadioButtonId())
                .apply();

            Toast.makeText(this, "已保存: " + url, Toast.LENGTH_SHORT).show();
            setResult(RESULT_OK);
            finish();
        });

        // 测试连接按钮
        btnTest.setOnClickListener(v -> {
            String url = etServerUrl.getText().toString().trim();
            if (url.isEmpty()) {
                Toast.makeText(this, "请先输入服务器地址", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "测试连接: " + url, Toast.LENGTH_SHORT).show();
            // 实际测试会在保存后通过 WebView 加载来验证
        });
    }

    private void loadSettings() {
        String savedUrl = prefs.getString("server_url", "");
        int connectionType = prefs.getInt("connection_type", R.id.rb_tailscale);

        if (!savedUrl.isEmpty()) {
            etServerUrl.setText(savedUrl);
            rgConnectionType.check(connectionType);

            // 根据类型显示帮助信息
            if (connectionType == R.id.rb_local) {
                tvHelp.setText("局域网连接：确保手机和服务器在同一 WiFi 下");
            } else if (connectionType == R.id.rb_tailscale) {
                tvHelp.setText("Tailscale 连接：需要手机安装 Tailscale 并加入同一网络\n默认地址: 100.122.70.99:8087");
            } else {
                tvHelp.setText("自定义连接：输入完整的服务器地址");
            }
        } else {
            // 默认选中 Tailscale
            rgConnectionType.check(R.id.rb_tailscale);
            etServerUrl.setText(DEFAULT_TAILSCALE_URL);
            tvHelp.setText("Tailscale 连接：需要手机安装 Tailscale 并加入同一网络\n默认地址: 100.122.70.99:8087");
        }
    }
}

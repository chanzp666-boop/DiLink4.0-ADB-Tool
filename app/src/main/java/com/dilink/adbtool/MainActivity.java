package com.dilink.adbtool;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.content.SharedPreferences;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * DiLink 4.0 ADB 跳码助手
 *
 * 原理：
 * 1) 把系统策略服务里的 verifyEnable 置 0；
 * 2) 拉起 com.byd.byddevelopmenttools.VerificationActivity（requestCode=1），
 *    它在 verifyEnable=0 时不会请求扫码，而是直接置 IS_VERIFY=1 并打开
 *    LogControlAndTestToolsActivity（即带 ADB 开关的开发工具页）；
 * 3) 默认 10 秒后把 verifyEnable 恢复为 1，避免长期关闭校验。
 */
public class MainActivity extends Activity {

    private static final String TAG = "AdbBypass";

    private static final String DEV_PKG = "com.byd.byddevelopmenttools";
    private static final String VERIFY_ACT = DEV_PKG + ".VerificationActivity";
    private static final String TEST_TOOLS_ACT = DEV_PKG + ".LogControlAndTestToolsActivity";
    private static final String VERIFY_ACTION = "android.byd.intent.action.VERIFICATION";
    /** VerificationActivity 里读取的 requestCode：1 = 开发工具(LogControlAndTestTools) */
    private static final String EXTRA_REQUEST_CODE = "Development.verify.requestCode";
    private static final int REQUEST_TOOLS = 1;

    private static final long AUTO_RESTORE_DELAY_MS = 10_000L;

    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat mTime = new SimpleDateFormat("HH:mm:ss", Locale.CHINA);

    private TextView mStatus;
    private TextView mHint;
    private TextView mLog;
    private Button mBtnEnter;
    private CheckBox mAutoRestore;

    private Runnable mPendingRestore;
    private SharedPreferences mPrefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mPrefs = getSharedPreferences("state", 0);

        mStatus = findViewById(R.id.tv_status);
        mHint = findViewById(R.id.tv_hint);
        mLog = findViewById(R.id.tv_log);
        mBtnEnter = findViewById(R.id.btn_enter);
        mAutoRestore = findViewById(R.id.cb_auto_restore);

        log("设备：" + android.os.Build.MODEL + " / Android " + android.os.Build.VERSION.RELEASE);

        mBtnEnter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                enterAdbScreen();
            }
        });

        findViewById(R.id.btn_restore).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                restoreVerify(true);
            }
        });

        findViewById(R.id.btn_dial).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openDialer();
            }
        });

        refreshStatus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        checkPendingRestore();
    }

    /** 上次如果没等到自动恢复（进程被杀/直接断电），这次进来补上。 */
    private void checkPendingRestore() {
        if (!mPrefs.getBoolean("pending_restore", false)) {
            return;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                String value = StrategyClient.get(MainActivity.this, StrategyClient.KEY_VERIFY_ENABLE);
                if ("0".equals(value)) {
                    postLog("检测到上次未完成恢复，现在补恢复 verifyEnable=1");
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            restoreVerify(false);
                        }
                    });
                } else if (value != null) {
                    mPrefs.edit().putBoolean("pending_restore", false).apply();
                }
            }
        }).start();
    }

    // ---------------------------------------------------------------- 主流程

    private void enterAdbScreen() {
        cancelPendingRestore();
        mBtnEnter.setEnabled(false);
        mBtnEnter.setText("正在处理…");

        if (isDirectMode()) {
            postLog("检测到新版模式：TestTools 已导出，直接打开，不改 verifyEnable");
            postUi(new Runnable() {
                @Override
                public void run() {
                    launchTestTools();
                }
            });
            return;
        }

        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = StrategyClient.set(StrategyClient.KEY_VERIFY_ENABLE, "0");
                postLog(ok ? "① 已向策略服务提交 verifyEnable=0"
                        : "① 策略服务 binder 不可用，尝试 service call 兜底");

                String value = StrategyClient.get(MainActivity.this, StrategyClient.KEY_VERIFY_ENABLE);
                if (!"0".equals(value) && !ok) {
                    String out = StrategyClient.setViaServiceCmd(StrategyClient.KEY_VERIFY_ENABLE, "0");
                    postLog(out.isEmpty() ? "   service call 未执行成功" : "   service call 已执行");
                    value = StrategyClient.get(MainActivity.this, StrategyClient.KEY_VERIFY_ENABLE);
                }

                postLog("② 回读 verifyEnable = " + (value == null ? "读不到（服务未响应）" : value));

                if ("1".equals(value)) {
                    postUi(new Runnable() {
                        @Override
                        public void run() {
                            resetButton();
                            refreshStatus();
                            showFailDialog("写入被系统拒绝",
                                    "策略服务拒绝了 verifyEnable 的修改。可能是该版本固件对调用方做了限制，"
                                            + "可以先试试「打开拨号暗码」走原流程。");
                        }
                    });
                    return;
                }

                postLog("③ 拉起开发工具验证页…");
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        launchVerification();
                    }
                });
            }
        }).start();
    }

    /**
     * 新版 BydDevelopmentTools 把 TestTools 改成 exported=true（默认值），
     * 旧版是 exported=false。用这个标志判断走“直达”还是“verifyEnable + 验证页”。
     */
    private boolean isDirectMode() {
        try {
            ActivityInfo ai = getPackageManager().getActivityInfo(
                    new ComponentName(DEV_PKG, TEST_TOOLS_ACT), 0);
            return ai.exported;
        } catch (Throwable t) {
            return false;
        }
    }

    private void launchTestTools() {
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(DEV_PKG, TEST_TOOLS_ACT));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            log("④ 已直接打开 TestTools（新版路径）");
        } catch (Throwable t) {
            Log.w(TAG, "direct TestTools failed", t);
            log("   直接打开 TestTools 失败：" + t.getClass().getSimpleName());
            showFailDialog("无法直接打开 TestTools",
                    "系统拒绝了 TestTools 的启动。请把执行日志发给我，我再换路径。");
        } finally {
            resetButton();
        }
    }

    private void launchVerification() {
        boolean started = false;
        try {
            Intent intent = new Intent();
            intent.setComponent(new ComponentName(DEV_PKG, VERIFY_ACT));
            intent.putExtra(EXTRA_REQUEST_CODE, REQUEST_TOOLS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
            startActivity(intent);
            started = true;
        } catch (Throwable t) {
            Log.w(TAG, "explicit start failed", t);
            log("   显式启动失败：" + t.getClass().getSimpleName());
        }

        if (!started) {
            try {
                Intent intent = new Intent(VERIFY_ACTION);
                intent.setPackage(DEV_PKG);
                intent.putExtra(EXTRA_REQUEST_CODE, REQUEST_TOOLS);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(intent);
                started = true;
            } catch (Throwable t) {
                Log.w(TAG, "implicit start failed", t);
                log("   隐式启动失败：" + t.getClass().getSimpleName());
            }
        }

        if (!started) {
            resetButton();
            showFailDialog("无法启动验证页",
                    "系统拒绝了 com.byd.byddevelopmenttools.VerificationActivity 的启动。"
                            + "请把执行日志发给我，我再换一条路径。");
            return;
        }

        log("④ 已启动验证页，正常会在 1 秒内进入 TestTools");
        resetButton();

        if (mAutoRestore.isChecked()) {
            scheduleRestore(AUTO_RESTORE_DELAY_MS);
        } else {
            log("   （已关闭自动恢复，verifyEnable 保持为 0）");
        }

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                String value = StrategyClient.get(MainActivity.this, StrategyClient.KEY_VERIFY_ENABLE);
                log("   当前 verifyEnable = " + (value == null ? "读不到" : value));
                refreshStatus();
            }
        }, 2500L);
    }

    // ---------------------------------------------------------------- 恢复校验

    private void scheduleRestore(long delayMs) {
        cancelPendingRestore();
        mPendingRestore = new Runnable() {
            @Override
            public void run() {
                mPendingRestore = null;
                restoreVerify(false);
            }
        };
        mPrefs.edit().putBoolean("pending_restore", true).apply();
        mHandler.postDelayed(mPendingRestore, delayMs);
        log("⑤ " + (delayMs / 1000) + " 秒后自动恢复二维码校验");
    }

    private void cancelPendingRestore() {
        if (mPendingRestore != null) {
            mHandler.removeCallbacks(mPendingRestore);
            mPendingRestore = null;
            postLog("   已取消上一次待执行的自动恢复");
        }
    }

    private void restoreVerify(final boolean manual) {
        cancelPendingRestore();
        mPrefs.edit().putBoolean("pending_restore", false).apply();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean ok = StrategyClient.set(StrategyClient.KEY_VERIFY_ENABLE, "1");
                if (!ok) {
                    StrategyClient.setViaServiceCmd(StrategyClient.KEY_VERIFY_ENABLE, "1");
                }
                final String value = StrategyClient.get(MainActivity.this, StrategyClient.KEY_VERIFY_ENABLE);
                postLog((manual ? "手动恢复" : "自动恢复") + " verifyEnable=1，回读："
                        + (value == null ? "读不到" : value));
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        refreshStatus();
                        if (manual) {
                            toast("1".equals(value) ? "已恢复二维码校验" : "已提交，回读：" + value);
                        }
                    }
                });
            }
        }).start();
    }

    // ---------------------------------------------------------------- 状态与工具

    /** 验证页组件是否存在（缺失说明不是 DiLink 4.0 的开发工具包）。 */
    private boolean hasVerificationActivity() {
        try {
            getPackageManager().getActivityInfo(new ComponentName(DEV_PKG, VERIFY_ACT), 0);
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private void refreshStatus() {
        new Thread(new Runnable() {
            @Override
            public void run() {
                final boolean direct = isDirectMode();
                final String value = direct ? null : StrategyClient.get(MainActivity.this, StrategyClient.KEY_VERIFY_ENABLE);
                postUi(new Runnable() {
                    @Override
                    public void run() {
                        boolean hasAct = hasVerificationActivity();
                        String actText = hasAct ? "验证页组件正常" : "验证页组件缺失";
                        if (direct) {
                            mStatus.setText("新版模式：可直接进入");
                            mStatus.setTextColor(getColor(R.color.accent));
                            mHint.setText("TestTools 已导出，点按钮直接打开 ADB 页面 · " + actText);
                            return;
                        }
                        if (value == null) {
                            mStatus.setText("读取不到策略服务");
                            mStatus.setTextColor(getColor(R.color.danger));
                            mHint.setText("strategyservice 无响应 · " + actText);
                        } else if ("0".equals(value)) {
                            mStatus.setText("校验已关闭 (0) · 可直进");
                            mStatus.setTextColor(getColor(R.color.accent));
                            mHint.setText(actText + " · 拨暗码也会跳过二维码，直到恢复为 1");
                        } else {
                            mStatus.setText("校验开启 (" + value + ")");
                            mStatus.setTextColor(getColor(R.color.warn));
                            mHint.setText(actText + " · 点上方按钮会自动改 0，再拉起验证页");
                        }
                    }
                });
            }
        }).start();
    }

    private void openDialer() {
        try {
            Intent intent = new Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", "*#91532547#*", null));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
            log("已打开拨号盘并填入暗码");
        } catch (Throwable t) {
            toast("打开拨号盘失败：" + t.getMessage());
        }
    }

    private void showFailDialog(String title, String message) {
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setMessage(message)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void resetButton() {
        mBtnEnter.setEnabled(true);
        mBtnEnter.setText("一键进入 ADB 调试界面");
    }

    private void log(String line) {
        mLog.append("[" + mTime.format(new Date()) + "] " + line + "\n");
        mLog.post(new Runnable() {
            @Override
            public void run() {
                if (mLog.getParent() instanceof android.widget.ScrollView) {
                    ((android.widget.ScrollView) mLog.getParent())
                            .fullScroll(android.view.View.FOCUS_DOWN);
                }
            }
        });
    }

    private void postLog(final String line) {
        postUi(new Runnable() {
            @Override
            public void run() {
                log(line);
            }
        });
    }

    private void postUi(Runnable r) {
        mHandler.post(r);
    }

    private void toast(String text) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
    }
}

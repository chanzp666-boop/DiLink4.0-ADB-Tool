package com.dilink.adbtool;

import android.content.Context;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 访问比亚迪 DiLink 的系统策略服务（strategyservice / CFS）。
 *
 * 开发工具 VerificationActivity 在启动时读取 config 策略里的 verifyEnable：
 *   verifyEnable = "0" -> 跳过二维码授权，直接置 IS_VERIFY=1 并打开 LogControlAndTestToolsActivity
 *   verifyEnable = "1" -> 走 BYDAuthManager 扫码授权
 *
 * 这里用原始 Binder 调用访问策略服务。
 */
public final class StrategyClient {

    private static final String TAG = "StrategyClient";
    private static final String SERVICE = "strategyservice";
    private static final String DESCRIPTOR = "android.strategyservice.IStrategyManagerService";
    private static final String CONFIG = "config";
    private static final int TX_GET = 3;
    private static final int TX_SET = 4;

    public static final String KEY_VERIFY_ENABLE = "verifyEnable";

    private StrategyClient() {
    }

    public static IBinder binder() {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            return (IBinder) getService.invoke(null, SERVICE);
        } catch (Throwable t) {
            Log.w(TAG, "get strategyservice failed", t);
            return null;
        }
    }

    /** 写入一条 config 策略。返回 binder 事务是否被受理。 */
    public static boolean set(String key, String value) {
        IBinder b = binder();
        if (b == null) {
            return false;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(CONFIG);
            data.writeString("{\"" + key + "\":\"" + value + "\"}");
            boolean ok = b.transact(TX_SET, data, reply, 0);
            Log.i(TAG, "set " + key + "=" + value + " -> " + ok);
            return ok;
        } catch (Throwable t) {
            Log.w(TAG, "set failed", t);
            return false;
        } finally {
            data.recycle();
            reply.recycle();
        }
    }

    /**
     * 用 /system/bin/service 再写一次（binder 直调被拦时的兜底）。
     * 返回进程输出，空串表示没跑起来。
     */
    public static String setViaServiceCmd(String key, String value) {
        try {
            String json = "{\"" + key + "\":\"" + value + "\"}";
            Process p = Runtime.getRuntime().exec(new String[]{
                    "/system/bin/service", "call", SERVICE, String.valueOf(TX_SET),
                    "s16", CONFIG, "s16", json});
            StringBuilder sb = new StringBuilder();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                sb.append(line).append('\n');
            }
            r.close();
            p.waitFor();
            return sb.toString().trim();
        } catch (Throwable t) {
            Log.w(TAG, "service call failed", t);
            return "";
        }
    }

    /** 读取一条 config 策略；读不到返回 null。 */
    public static String get(Context context, String key) {
        String reflected = getViaFramework(context, key);
        if (reflected != null) {
            return reflected;
        }
        return getViaBinder(key);
    }

    /**
     * 走框架的 StrategyManager（系统 App 同款路径），反射调用避免编译期依赖隐藏类。
     */
    private static String getViaFramework(Context context, String key) {
        if (context == null) {
            return null;
        }
        try {
            Class<?> c = Class.forName("android.strategyservice.StrategyManager");
            Object manager = c.getConstructor(Context.class).newInstance(context);
            Method getStrategy = c.getMethod("getStrategy", String.class, List.class);
            Object strategy = getStrategy.invoke(manager, CONFIG, Collections.singletonList(key));
            if (strategy == null) {
                return null;
            }
            Object configs = strategy.getClass().getField("configs").get(strategy);
            if (configs instanceof Map) {
                Object value = ((Map<?, ?>) configs).get(key);
                if (value != null) {
                    return String.valueOf(value);
                }
            }
        } catch (Throwable t) {
            Log.i(TAG, "framework read failed: " + t);
        }
        return null;
    }

    /** 原始 Binder 读取；reply 结构存在版本差异，按字节扫描。 */
    private static String getViaBinder(String key) {
        IBinder b = binder();
        if (b == null) {
            return null;
        }
        Parcel data = Parcel.obtain();
        Parcel reply = Parcel.obtain();
        try {
            data.writeInterfaceToken(DESCRIPTOR);
            data.writeString(CONFIG);
            data.writeInt(1);
            data.writeString(key);
            b.transact(TX_GET, data, reply, 0);
            return parseReply(reply.marshall(), key);
        } catch (Throwable t) {
            Log.w(TAG, "binder read failed", t);
        } finally {
            data.recycle();
            reply.recycle();
        }
        return null;
    }

    private static String parseReply(byte[] raw, String key) {
        for (int offset = 0; offset <= 16; offset += 4) {
            String value = parseReplyAt(raw, offset, key, true);
            if (value != null) {
                return value;
            }
            value = parseReplyAt(raw, offset, key, false);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String parseReplyAt(byte[] raw, int offset, String key, boolean withSize) {
        try {
            int[] pos = {offset};
            readStr16(raw, pos);
            int count = withSize ? readInt(raw, pos) : 16;
            if (count < 1 || count > 64) {
                return null;
            }
            for (int i = 0; i < count; i++) {
                String k = readStr16(raw, pos);
                String v = readStr16(raw, pos);
                if (k == null) {
                    return null;
                }
                if (key.equals(k)) {
                    return v;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static int readInt(byte[] b, int[] pos) {
        int i = pos[0];
        if (i + 4 > b.length) {
            throw new IndexOutOfBoundsException();
        }
        int v = (b[i] & 0xFF) | ((b[i + 1] & 0xFF) << 8) | ((b[i + 2] & 0xFF) << 16) | ((b[i + 3] & 0xFF) << 24);
        pos[0] = i + 4;
        return v;
    }

    private static String readStr16(byte[] b, int[] pos) {
        int len = readInt(b, pos);
        if (len < 0 || len > 512) {
            throw new IndexOutOfBoundsException();
        }
        int size = (len + 1) * 2;
        if (pos[0] + size > b.length) {
            throw new IndexOutOfBoundsException();
        }
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int p = pos[0];
            sb.append((char) (((b[p + 1] & 0xFF) << 8) | (b[p] & 0xFF)));
            pos[0] = p + 2;
        }
        pos[0] += 2;
        pos[0] += (4 - (size % 4)) % 4;
        return sb.toString();
    }
}

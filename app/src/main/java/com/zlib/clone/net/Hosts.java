package com.zlib.clone.net;

/**
 * 内置服务器域名与故障切换。
 *
 * 背景：Z-Library 的镜像是动态变化的，且不同镜像的防护策略不同：
 * - z-lib.sk / z-library.sk 对 /eapi/book/* 启用了 DiamWall 反爬（307 跳转 + __diamwall Cookie），
 *   客户端保存并回传该 Cookie 后可正常访问；
 * - 另外几台当前无挑战，直接返回 JSON，放在前面可以更快接入，并把有挑战的镜像作为兜底。
 *
 * 探测顺序即数组顺序；用户在设置里手填的地址始终优先（由调用方决定）。
 */
public final class Hosts {

    /** 2026-11 实测：前 6 个 API 直通，后 5 个为兜底（sk 需要 Cookie 通过反爬）。 */
    public static final String[] BUILT_IN = {
            "https://z-lib.gd/",
            "https://z-lib.by/",
            "https://z-library.im/",
            "https://z-library.website/",
            "https://z-library.qa/",
            "https://z-library.gy/",
            "https://z-lib.sk/",
            "https://z-library.sk/",
            "https://zlib.li/",
            "https://z-lib.fm/",
            "https://101su.ru/"
    };

    /** 故障切换时最多再探几个域名（每个约 4s，避免重试等待过久）。 */
    private static final int MAX_PROBE_ON_SWITCH = 5;

    private Hosts() {
    }

    /**
     * 从内置域名里挑一个可用的服务器（跳过当前正在用的）。
     *
     * @param excludedBase 当前 base（可为 null）
     * @return 可用的规范化 base；全部失败返回 null
     */
    public static String pickNext(String excludedBase) {
        String excludedHost = hostOf(excludedBase);
        int tried = 0;
        for (int i = 0; i < BUILT_IN.length; i++) {
            String candidate = BUILT_IN[i];
            if (hostOf(candidate).equals(excludedHost)) {
                continue;
            }
            if (tried >= MAX_PROBE_ON_SWITCH) {
                break;
            }
            tried++;
            try {
                String base = Api.get().probeHost(candidate);
                if (base != null && base.length() > 0) {
                    return base;
                }
            } catch (Throwable ignored) {
                // 探测失败，继续下一个。
            }
        }
        return null;
    }

    /** 取 URL 的 host（小写），解析失败返回 ""。 */
    public static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        try {
            String host = new java.net.URL(url).getHost();
            return host == null ? "" : host.toLowerCase(java.util.Locale.US);
        } catch (Throwable ignored) {
            return "";
        }
    }
}

package tackshot.ai;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.rpc.BlobAttachment;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.MessageOptions;
import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.SessionConfig;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

/**
 * 官方 Copilot SDK 适配层（D4 用户决策）：com.github:copilot-sdk-java 1.0.11。
 * 客户端懒启动（首次调用才拉起 CLI 子进程），空闲 3 分钟自动 stop 释放内存；
 * 失败映射为中文可读错误（网络/认证/超时/额度）。
 */
final class CopilotSdk {
    private CopilotSdk() {}

    /** 结果回调（SDK 线程调用，UI 侧自行 marshal）。 */
    interface Listener {
        void onDelta(String text);

        void onDone(String fullText, long elapsedMs);

        void onError(String friendlyMsg);
    }

    private static final Object LOCK = new Object();
    private static CopilotClient client;
    private static java.util.Timer idleTimer;
    private static final long IDLE_STOP_MS = 3 * 60 * 1000L;

    /** 模型档位（D2 设置页三档）。 */
    static String modelOfTier(String tier) {
        switch (tier == null ? "fast" : tier) {
            case "balanced":
                return "gpt-4o";
            case "deep":
                return "claude-sonnet-4";
            default:
                return "gpt-4o-mini";
        }
    }

    static String tierLabel(String tier) {
        switch (tier == null ? "fast" : tier) {
            case "balanced":
                return "均衡 · gpt-4o";
            case "deep":
                return "深度 · claude-sonnet-4";
            default:
                return "快速 · gpt-4o-mini";
        }
    }

    private static CopilotClient client(boolean create) throws Exception {
        synchronized (LOCK) {
            if (client == null && create) {
                String token = TokenVault.load();
                if (token == null || token.isEmpty()) throw new IllegalStateException("未配置令牌");
                CopilotClientOptions o = new CopilotClientOptions();
                o.setGitHubToken(token);
                o.setCopilotHome(tackshot.Main.exeDir + java.io.File.separator + "copilot-home");
            client = new CopilotClient(o);
            client.start().get(150, TimeUnit.SECONDS);   // 首次含 CLI 冷启动，45s 不够（实测装好后 ~1.6s，未装则秒败）
            tackshot.Log.write("AI：Copilot 客户端已启动（SDK " + version() + "）");
            }
            scheduleIdleStop();
            return client;
        }
    }

    static String version() {
        return "1.0.11";
    }

    /** 空闲自停：限制 CLI 子进程常驻窗口（性能评估承诺）。 */
    private static void scheduleIdleStop() {
        if (idleTimer != null) idleTimer.cancel();
        idleTimer = new java.util.Timer("ai-idle-stop", true);
        idleTimer.schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                synchronized (LOCK) {
                    if (client != null) {
                        try {
                            client.stop().get(5, TimeUnit.SECONDS);
                        } catch (Throwable ignored) {
                            try {
                                client.forceStop();
                            } catch (Throwable ignored2) {
                            }
                        }
                        try {
                            client.close();
                        } catch (Throwable ignored) {
                        }
                        client = null;
                        tackshot.Log.write("AI：空闲 3 分钟，Copilot 客户端已停止（释放 CLI 子进程）");
                    }
                    if (idleTimer != null) {
                        idleTimer.cancel();
                        idleTimer = null;
                    }
                }
            }
        }, IDLE_STOP_MS);
    }

    static void shutdown() {
        synchronized (LOCK) {
            if (idleTimer != null) {
                idleTimer.cancel();
                idleTimer = null;
            }
            if (client != null) {
                try {
                    client.forceStop();
                } catch (Throwable ignored) {
                }
                try {
                    client.close();
                } catch (Throwable ignored) {
                }
                client = null;
            }
        }
    }

    // ---------------- 测试连接（设置页） ----------------

    /** 返回 null＝成功（附带摘要），非 null＝失败原因（中文）。 */
    static String testConnection() {
        try {
            CopilotClient c = client(true);
            GetAuthStatusResponse auth = c.getAuthStatus().get(20, TimeUnit.SECONDS);
            if (!auth.isAuthenticated())
                return "令牌未通过认证（" + (auth.getStatusMessage() == null || auth.getStatusMessage().isEmpty()
                        ? "Not authenticated" : auth.getStatusMessage())
                        + "）。请确认：①已安装 Copilot CLI（winget install GitHub.Copilot / npm i -g @github/copilot）"
                        + "②PAT 有效且账户有 Copilot 订阅 ③必要时在终端运行一次 copilot 按提示完成登录";
            List<ModelInfo> models = c.listModels().get(20, TimeUnit.SECONDS);
            int vision = 0;
            List<String> names = new ArrayList<>();
            if (models != null)
                for (ModelInfo m : models) {
                    boolean v = m.getCapabilities() != null && m.getCapabilities().getSupports() != null
                            && m.getCapabilities().getSupports().isVision();
                    if (v) {
                        vision++;
                        if (names.size() < 3) names.add(m.getId());
                    }
                }
            if (vision == 0) return "连接成功，但订阅内无可用视觉模型（无法 OCR）";
            return null;   // 摘要从 okSummary 取
        } catch (Throwable t) {
            return friendly(t);
        }
    }

    /** 成功摘要（测试连接用）。 */
    static String okSummary() {
        try {
            CopilotClient c = client(false);
            if (c == null) return "已连接";
            List<ModelInfo> models = c.listModels().get(10, TimeUnit.SECONDS);
            int vision = 0;
            List<String> names = new ArrayList<>();
            if (models != null)
                for (ModelInfo m : models) {
                    boolean v = m.getCapabilities() != null && m.getCapabilities().getSupports() != null
                            && m.getCapabilities().getSupports().isVision();
                    if (v) {
                        vision++;
                        if (names.size() < 3) names.add(m.getId());
                    }
                }
            return "连接成功 · 可用视觉模型 " + vision + " 个（" + String.join("、", names) + "…）";
        } catch (Throwable t) {
            return "连接成功";
        }
    }

    // ---------------- 识别请求（提取/翻译） ----------------

    /** 发起一次请求；返回句柄可用于取消。 */
    static Handle send(BufferedImage image, String prompt, String model, Listener lis) {
        long t0 = System.nanoTime() / 1_000_000L;
        StringBuilder acc = new StringBuilder();
        Handle h = new Handle();
        byte[] jpeg = ImagePrep.toJpegBytes(image);
        if (jpeg == null) {
            lis.onError("图像预处理失败");
            return h;
        }
        CopilotClient c;
        try {
            c = client(true);
        } catch (IllegalStateException e) {
            lis.onError("尚未配置 GitHub 令牌");
            return h;
        } catch (Throwable t) {
            lis.onError(friendly(t));
            return h;
        }
        try {
            SessionConfig sc = new SessionConfig();
            sc.setModel(model);
            sc.setClientName("TackShot");
            CompletableFuture<CopilotSession> sf = c.createSession(sc);
            sf.orTimeout(60, TimeUnit.SECONDS);
            sf.whenComplete((sess, err) -> {
                if (err != null) {
                    lis.onError(friendly(err));
                    return;
                }
                h.session = sess;
                try {
                    sess.on(AssistantMessageDeltaEvent.class, ev -> {
                        try {
                            if (ev != null && ev.getData() != null && ev.getData().deltaContent() != null) {
                                String d = ev.getData().deltaContent();
                                acc.append(d);
                                lis.onDelta(d);
                            }
                        } catch (Throwable ignored) {
                        }
                    });
                } catch (Throwable ignored) {
                }
                BlobAttachment att = new BlobAttachment();
                att.setData(ImagePrep.base64(jpeg));
                att.setMimeType("image/jpeg");
                att.setDisplayName("screenshot.jpg");
                MessageOptions mo = new MessageOptions();
                mo.setPrompt(prompt);
                mo.setAttachments(List.of(att));
                CompletableFuture<String> send = sess.send(mo);
                send.orTimeout(180, TimeUnit.SECONDS);
                send.whenComplete((mid, err2) -> {
                    if (err2 != null) {
                        lis.onError(friendly(err2));
                        return;
                    }
                    String full = acc.toString().trim();
                    if (full.isEmpty()) {
                        lis.onError("模型返回了空结果，请重试或换模型");
                        return;
                    }
                    lis.onDone(full, System.nanoTime() / 1_000_000L - t0);
                });
            });
        } catch (Throwable t) {
            lis.onError(friendly(t));
        }
        return h;
    }

    static final class Handle {
        volatile CopilotSession session;

        void cancel() {
            CopilotSession s = session;
            if (s != null) {
                try {
                    s.abort();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ---------------- 错误映射 ----------------

    static String friendly(Throwable t) {
        Throwable cause = t instanceof CompletionException && t.getCause() != null ? t.getCause() : t;
        String msg = String.valueOf(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        String low = msg.toLowerCase();
        // CLI 子进程秒退（stdio 管道关闭）：本机未装 Copilot CLI 的典型形态（2026-08-24 实测复现）
        if (low.contains("管道") || low.contains("pipe") && low.contains("clos")
                || low.contains("cli exited") || low.contains("cannot run program")
                || low.contains("no such file") && low.contains("copilot"))
            return "未检测到 GitHub Copilot CLI（SDK 依赖本机 copilot 命令）。"
                    + "请先安装：winget install GitHub.Copilot 或 npm install -g @github/copilot（需 Node 22+），"
                    + "安装后重新测试连接";
        if (low.contains("unknownhost") || low.contains("connect") || low.contains("timed out")
                || low.contains("timeout") || low.contains("unreachable") || low.contains("network"))
            return "网络不可达或超时（代理/防火墙？）：" + shorten(msg);
        if (low.contains("401") || low.contains("unauthorized") || low.contains("auth") || low.contains("token"))
            return "令牌无效或已过期（401）——请到设置中重新粘贴";
        if (low.contains("403") || low.contains("forbidden") || low.contains("subscription")
                || low.contains("quota") || low.contains("credit") || low.contains("429"))
            return "无权限或额度不足（403/429）——请检查 Copilot 订阅额度";
        return "AI 请求失败：" + shorten(msg);
    }

    private static String shorten(String s) {
        s = s.replace("\n", " ").trim();
        return s.length() > 160 ? s.substring(0, 160) + "…" : s;
    }
}

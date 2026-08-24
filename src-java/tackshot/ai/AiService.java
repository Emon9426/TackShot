package tackshot.ai;

import java.awt.Rectangle;
import java.awt.image.BufferedImage;

/**
 * AI 功能总入口（FR-8.x P0）：对外仅暴露 JDK 类型（Pin/Capture 为包私有类，不能跨包引用，
 * 调用方自行取图并经 runImage 传入）。
 * 职责：鉴权检查（未配置→引导卡）、发送前确认（D7）、请求生命周期（D5 关窗即取消）、
 * 贴图关闭联动（D1）、令牌与连接测试门面。
 */
public final class AiService {
    private AiService() {}

    public static final String FEAT_EXTRACT = PromptLib.FEAT_EXTRACT;
    public static final String FEAT_TRANSLATE = PromptLib.FEAT_TRANSLATE;

    private static AiResultWindow win;
    private static Object owner;
    private static boolean confirmDismissed = false;
    private static String pendingFeature, pendingTarget;

    /** 配置齐备（设置页开关 + 令牌存在）。 */
    public static boolean ready() {
        return tackshot.Main.cfg.aiEnabled && TokenVault.hasToken();
    }

    // ---------------- 令牌门面（设置页用） ----------------

    public static boolean hasToken() {
        return TokenVault.hasToken();
    }

    public static boolean saveToken(String token) {
        return TokenVault.save(token);
    }

    public static boolean deleteToken() {
        return TokenVault.delete();
    }

    /** 返回 null＝成功；非 null＝失败原因（中文）。 */
    public static String testConnection() {
        return CopilotSdk.testConnection();
    }

    /** 成功摘要（测试连接成功后调用）。 */
    public static String okSummary() {
        return CopilotSdk.okSummary();
    }

    /** SDK 版本（关于页展示）。 */
    public static String sdkVersion() {
        return CopilotSdk.version();
    }

    // ---------------- 入口 ----------------

    /** 统一入口：owner＝发起者标识（贴图/截图会话对象，用于关闭联动）。 */
    public static void runImage(Object ownerRef, BufferedImage img, Rectangle anchor,
                                boolean topmost, String feature, String target) {
        if (img == null) return;
        final BufferedImage image = img;
        start(ownerRef, () -> image, feature, target, anchor, topmost);
    }

    /** 托盘「AI 识别剪贴板」：对剪贴板图片执行默认功能（提取文字）。 */
    public static void runFromClipboard() {
        BufferedImage img = tackshot.Main.clipImage();
        if (img == null) {
            tackshot.Main.balloon("钉图 TackShot", "剪贴板中没有图片");
            return;
        }
        runImage(null, img, null, true, FEAT_EXTRACT, null);
    }

    // ---------------- 核心流程 ----------------

    private static void start(Object ownerRef, AiResultWindow.BufferedImageRef img,
                              String feature, String target, Rectangle anchor, boolean topmost) {
        if (win != null) {
            AiResultWindow old = win;
            win = null;
            old.close();   // 关旧窗（含取消进行中请求，D5）
        }
        owner = ownerRef;
        String tier = tackshot.Main.cfg.aiTier;
        pendingFeature = feature;
        pendingTarget = target == null ? tackshot.Main.cfg.aiTranslateTo : target;

        if (!ready()) {
            showWindow(feature, pendingTarget, tier, img, anchor, topmost).showUnconfigured();
            tackshot.Log.write("AI：未配置（设置页开关或令牌缺失），显示引导卡");
            return;
        }
        // D7 发送前确认（三选：发送 / 不再询问并发送 / 取消）
        if (tackshot.Main.cfg.aiConfirm && !confirmDismissed) {
            String model = CopilotSdk.modelOfTier(tier);
            Object[] opts = {"发送", "不再询问并发送", "取消"};
            int r = javax.swing.JOptionPane.showOptionDialog(tackshot.Main.hidden,
                    "将把这张截图发送至 GitHub（" + model + "）进行识别。\n"
                            + "截图内容会经由 GitHub 及其模型供应商处理，请勿用于涉密内容。",
                    "AI 发送前确认", javax.swing.JOptionPane.DEFAULT_OPTION,
                    javax.swing.JOptionPane.WARNING_MESSAGE, null, opts, opts[0]);
            if (r == 2 || r < 0) return;
            if (r == 1) {
                confirmDismissed = true;
                tackshot.Main.cfg.aiConfirm = false;
                tackshot.Main.cfg.save();
            }
        }
        AiResultWindow w = showWindow(feature, pendingTarget, tier, img, anchor, topmost);
        dispatch(w, img);
    }

    private static AiResultWindow showWindow(String feature, String target, String tier,
                                             AiResultWindow.BufferedImageRef img,
                                             Rectangle anchor, boolean topmost) {
        AiResultWindow w = new AiResultWindow(feature, target, tier, topmost);
        w.setImage(img);
        w.onRetry = () -> {
            if (!ready()) {
                w.showUnconfigured();
                return;
            }
            dispatch(w, img);
        };
        w.onSwitchTier = (t, im) -> {
            tackshot.Main.cfg.aiTier = t;
            tackshot.Main.cfg.save();
            w.setTier(t);
            dispatch(w, im);
        };
        win = w;
        w.dock(anchor);
        w.showLoading();
        w.showWin();
        return w;
    }

    private static void dispatch(AiResultWindow w, AiResultWindow.BufferedImageRef img) {
        BufferedImage image = img.get();
        if (image == null) {
            w.showError("无法获取图像（贴图已关闭？）");
            return;
        }
        String prompt = FEAT_TRANSLATE.equals(pendingFeature)
                ? PromptLib.translatePrompt(pendingTarget)
                : PromptLib.extractPrompt();
        String model = CopilotSdk.modelOfTier(tackshot.Main.cfg.aiTier);
        w.showLoading();
        tackshot.Log.write("AI 请求：" + PromptLib.featureTitle(pendingFeature, pendingTarget)
                + "，模型 " + model + "，图像 " + image.getWidth() + "x" + image.getHeight());
        CopilotSdk.Handle h = CopilotSdk.send(image, prompt, model, new CopilotSdk.Listener() {
            @Override
            public void onDelta(String text) {
                w.appendDelta(text);
            }

            @Override
            public void onDone(String fullText, long elapsedMs) {
                tackshot.Log.write("AI 完成：" + fullText.length() + " 字符，" + elapsedMs + "ms");
                w.showDone(fullText, elapsedMs);
            }

            @Override
            public void onError(String friendlyMsg) {
                tackshot.Log.write("AI 失败：" + friendlyMsg);
                w.showError(friendlyMsg);
            }
        });
        w.setHandle(h);
    }

    /** 贴图关闭联动（Pin.close 调用）：关掉它名下的结果浮窗。 */
    public static void ownerClosed(Object ownerRef) {
        if (win != null && owner == ownerRef) {
            AiResultWindow w = win;
            win = null;
            w.close();
        }
    }

    public static void shutdown() {
        if (win != null) {
            AiResultWindow w = win;
            win = null;
            w.close();
        }
        CopilotSdk.shutdown();
    }

    /** /aitest 视觉验证：直接弹未配置引导卡。 */
    public static void showUnconfiguredCard() {
        final BufferedImage img = new BufferedImage(200, 120, BufferedImage.TYPE_INT_RGB);
        runImage(null, img, null, true, FEAT_EXTRACT, null);
    }
}

import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.ModelInfo;
import java.util.concurrent.TimeUnit;

/** 诊断：假令牌跑 SDK 启动链路——区分 CLI 启动/下载失败与认证失败。 */
public class DiagCli {
    public static void main(String[] args) throws Exception {
        String token = args[0];
        String home = args[1];
        CopilotClientOptions o = new CopilotClientOptions();
        o.setGitHubToken(token);
        o.setCopilotHome(home);
        CopilotClient c = new CopilotClient(o);
        long t0 = System.currentTimeMillis();
        try {
            c.start().get(300, TimeUnit.SECONDS);
            System.out.println("[1] START OK in " + (System.currentTimeMillis() - t0) + "ms");
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.out.println("[1] START FAIL after " + (System.currentTimeMillis() - t0) + "ms: "
                    + cause.getClass().getName() + ": " + cause.getMessage());
            cause.printStackTrace(System.out);
            return;
        }
        try {
            GetAuthStatusResponse auth = c.getAuthStatus().get(30, TimeUnit.SECONDS);
            System.out.println("[2] AUTH authenticated=" + auth.isAuthenticated()
                    + " type=" + auth.getAuthType() + " login=" + auth.getLogin()
                    + " msg=" + auth.getStatusMessage());
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.out.println("[2] AUTH FAIL: " + cause.getClass().getName() + ": " + cause.getMessage());
        }
        try {
            var models = c.listModels().get(30, TimeUnit.SECONDS);
            int n = models == null ? -1 : models.size();
            System.out.println("[3] MODELS n=" + n);
            if (models != null) {
                int vis = 0;
                for (ModelInfo m : models) {
                    boolean v = m.getCapabilities() != null && m.getCapabilities().getSupports() != null
                            && m.getCapabilities().getSupports().isVision();
                    if (v) {
                        vis++;
                        if (vis <= 5) System.out.println("    vision: " + m.getId());
                    }
                }
                System.out.println("[3] vision models=" + vis);
            }
        } catch (Throwable t) {
            Throwable cause = t.getCause() != null ? t.getCause() : t;
            System.out.println("[3] MODELS FAIL: " + cause.getClass().getName() + ": " + cause.getMessage());
        }
        try {
            c.stop().get(8, TimeUnit.SECONDS);
        } catch (Throwable ignored) {
        }
        System.out.println("[4] DONE");
    }
}

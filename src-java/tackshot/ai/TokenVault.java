package tackshot.ai;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.Structure;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.ptr.PointerByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

/**
 * 令牌保险库：GitHub PAT 仅存 Windows 凭据管理器（DPAPI 加密、随用户账户隔离），不落 config-ai.json。
 * jna-platform 未封装 Cred* 系列，此处自声明 advapi32 Unicode 绑定。
 */
final class TokenVault {
    private TokenVault() {}

    private static final String TARGET = "TackShotAI/GitHubPAT";
    private static final int CRED_TYPE_GENERIC = 1;
    private static final int CRED_PERSIST_LOCAL_MACHINE = 2;

    interface Advapi32Cred extends StdCallLibrary {
        Advapi32Cred I = Native.load("advapi32", Advapi32Cred.class, W32APIOptions.UNICODE_OPTIONS);
        boolean CredWriteW(CREDENTIAL cred, int flags);
        boolean CredReadW(String target, int type, int flags, PointerByReference pcred);
        boolean CredDeleteW(String target, int type, int flags);
        void CredFree(Pointer cred);
    }

    @Structure.FieldOrder({"Flags", "Type", "TargetName", "Comment", "LastWritten",
            "CredentialBlobSize", "CredentialBlob", "Persist",
            "AttributeCount", "Attributes", "TargetAlias", "UserName"})
    public static class CREDENTIAL extends Structure {
        public int Flags;
        public int Type;
        public Pointer TargetName;
        public Pointer Comment;
        public WinBase.FILETIME LastWritten;
        public int CredentialBlobSize;
        public Pointer CredentialBlob;
        public int Persist;
        public int AttributeCount;
        public Pointer Attributes;
        public Pointer TargetAlias;
        public Pointer UserName;

        public CREDENTIAL() { super(); }

        public CREDENTIAL(Pointer p) { super(p); read(); }

        @Override
        protected List<String> getFieldOrder() {
            return Arrays.asList("Flags", "Type", "TargetName", "Comment", "LastWritten",
                    "CredentialBlobSize", "CredentialBlob", "Persist",
                    "AttributeCount", "Attributes", "TargetAlias", "UserName");
        }
    }

    private static Pointer wstr(String s) {
        byte[] b = new byte[(s.length() + 1) * 2];
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b[i * 2] = (byte) (c & 0xFF);
            b[i * 2 + 1] = (byte) (c >> 8);
        }
        Pointer m = new com.sun.jna.Memory(b.length);
        m.write(0, b, 0, b.length);
        return m;
    }

    /** 写入（覆盖）令牌。 */
    static boolean save(String token) {
        try {
            byte[] blob = token.getBytes(StandardCharsets.UTF_8);
            Pointer blobPtr = new com.sun.jna.Memory(blob.length);
            blobPtr.write(0, blob, 0, blob.length);
            CREDENTIAL c = new CREDENTIAL();
            c.Flags = 0;
            c.Type = CRED_TYPE_GENERIC;
            c.TargetName = wstr(TARGET);
            c.Comment = wstr("TackShot AI 版 GitHub 访问令牌");
            c.LastWritten = new WinBase.FILETIME();
            c.CredentialBlobSize = blob.length;
            c.CredentialBlob = blobPtr;
            c.Persist = CRED_PERSIST_LOCAL_MACHINE;
            c.AttributeCount = 0;
            c.Attributes = null;
            c.TargetAlias = wstr(TARGET);
            c.UserName = wstr("TackShotAI");
            boolean ok = Advapi32Cred.I.CredWriteW(c, 0);
            if (!ok) tackshot.Log.write("AI 令牌写入凭据管理器失败：GetLastError=" + Native.getLastError());
            return ok;
        } catch (Throwable t) {
            tackshot.Log.write("AI 令牌写入异常：" + t);
            return false;
        }
    }

    /** 读取令牌；无或失败返回 null。 */
    static String load() {
        PointerByReference pref = new PointerByReference();
        try {
            if (!Advapi32Cred.I.CredReadW(TARGET, CRED_TYPE_GENERIC, 0, pref)) return null;
            CREDENTIAL c = new CREDENTIAL(pref.getValue());
            if (c.CredentialBlob == null || c.CredentialBlobSize <= 0 || c.CredentialBlobSize > 8192) return null;
            byte[] blob = c.CredentialBlob.getByteArray(0, c.CredentialBlobSize);
            return new String(blob, StandardCharsets.UTF_8).trim();
        } catch (Throwable t) {
            tackshot.Log.write("AI 令牌读取异常：" + t);
            return null;
        } finally {
            try {
                if (pref.getValue() != null) Advapi32Cred.I.CredFree(pref.getValue());
            } catch (Throwable ignored) {
            }
        }
    }

    static boolean hasToken() {
        String t = load();
        return t != null && !t.isEmpty();
    }

    /** 删除令牌（设置页「清除」）。 */
    static boolean delete() {
        try {
            return Advapi32Cred.I.CredDeleteW(TARGET, CRED_TYPE_GENERIC, 0);
        } catch (Throwable t) {
            return false;
        }
    }
}

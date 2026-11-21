package com.example.android.notepad;

import android.content.SharedPreferences;
import android.test.AndroidTestCase;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

// WebDavClient 与云备份非敏感配置存储的设备测试。
// 验证 HTTPS 目录规范化、PUT 请求头与超时设置、首次创建与覆盖响应、
// 错误分类与密码不泄露以及偏好存储仅保留非敏感字段。
public class WebDavClientTest extends AndroidTestCase {

    // 验证目录 URL 规范化、自动补斜杠、去重文件名，以及拒绝非 HTTPS 和非法格式地址。
    public void testNormalizeAndBuildBackupFileUrl() throws Exception {
        assertEquals(
                "https://dav.jianguoyun.com/dav/NotePad/",
                WebDavClient.normalizeDirectoryUrl("https://dav.jianguoyun.com/dav/NotePad"));
        assertEquals(
                "https://dav.jianguoyun.com/dav/NotePad/",
                WebDavClient.normalizeDirectoryUrl("  https://dav.jianguoyun.com/dav/NotePad/  "));
        assertEquals(
                "https://dav.jianguoyun.com/dav/NotePad/",
                WebDavClient.normalizeDirectoryUrl(
                        "https://dav.jianguoyun.com/dav/NotePad/notepad-backup.json"));
        assertEquals(
                "https://dav.jianguoyun.com/dav/NotePad/notepad-backup.json",
                WebDavClient.buildBackupFileUrl("https://dav.jianguoyun.com/dav/NotePad").toExternalForm());

        assertInvalidUrl("");
        assertInvalidUrl("http://dav.jianguoyun.com/dav/NotePad/");
        assertInvalidUrl("https:///dav/NotePad/");
        assertInvalidUrl("https://dav.jianguoyun.com/dav/NotePad/?token=1");
        assertInvalidUrl("https://dav.jianguoyun.com/dav/NotePad/#section");
    }

    // 验证首次创建（201）、覆盖更新（204/200）及空库（0 条）上传时的请求方法、头部、超时与载荷完整性。
    public void testUploadFirstCreateOverwriteAndEmptySnapshot() throws Exception {
        NoteBackupFormat.NoteRecord sampleNote = new NoteBackupFormat.NoteRecord(
                "云备份测试标题",
                "第一行中文内容\n第二行包含\"引号\"与 English",
                1795250000100L,
                1795250000200L);
        byte[] payload = NoteBackupFormat.encodeToUtf8Bytes(Collections.singletonList(sampleNote));
        URL targetUrl = WebDavClient.buildBackupFileUrl("https://dav.example.com/dav/NotePad/");

        // 1. 首次创建返回 201 Created。
        FakeHttpURLConnection firstConn = new FakeHttpURLConnection(targetUrl, 201, null);
        WebDavClient.UploadResult firstResult = WebDavClient.uploadToUrl(
                targetUrl, "demo@example.com", "demo-app-secret", payload,
                WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS,
                WebDavClient.DEFAULT_READ_TIMEOUT_MS,
                url -> firstConn);

        assertEquals(201, firstResult.httpStatus);
        assertTrue(firstResult.createdNew);
        assertEquals("PUT", firstConn.getRequestMethod());
        assertFalse(firstConn.getInstanceFollowRedirects());
        assertEquals(WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS, firstConn.getConnectTimeout());
        assertEquals(WebDavClient.DEFAULT_READ_TIMEOUT_MS, firstConn.getReadTimeout());
        assertEquals(
                WebDavClient.buildBasicAuthHeader("demo@example.com", "demo-app-secret"),
                firstConn.capturedHeaders.get("Authorization"));
        assertEquals("application/json; charset=utf-8", firstConn.capturedHeaders.get("Content-Type"));
        assertTrue(firstConn.disconnected);
        assertTrue(firstConn.responseStreamClosed);

        // 校验上传的字节可由 T07 解码器完整还原且四字段一致。
        NoteBackupFormat.BackupSnapshot uploaded =
                NoteBackupFormat.decodeFromUtf8Bytes(firstConn.capturedBody.toByteArray());
        assertEquals(1, uploaded.notes.size());
        assertEquals(sampleNote, uploaded.notes.get(0));

        // 2. 覆盖更新返回 204 No Content，且 0 条空快照也能正常上传。
        byte[] emptyPayload = NoteBackupFormat.encodeToUtf8Bytes(Collections.emptyList());
        FakeHttpURLConnection updateConn = new FakeHttpURLConnection(targetUrl, 204, null);
        WebDavClient.UploadResult updateResult = WebDavClient.uploadToUrl(
                targetUrl, "demo@example.com", "demo-app-secret", emptyPayload,
                WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS,
                WebDavClient.DEFAULT_READ_TIMEOUT_MS,
                url -> updateConn);

        assertEquals(204, updateResult.httpStatus);
        assertFalse(updateResult.createdNew);
        assertTrue(updateConn.disconnected);
        NoteBackupFormat.BackupSnapshot emptyUploaded =
                NoteBackupFormat.decodeFromUtf8Bytes(updateConn.capturedBody.toByteArray());
        assertEquals(0, emptyUploaded.notes.size());

        // 3. 部分兼容 WebDAV 服务覆盖时返回 200 OK，同样视作覆盖成功。
        FakeHttpURLConnection okConn = new FakeHttpURLConnection(targetUrl, 200, null);
        WebDavClient.UploadResult okResult = WebDavClient.uploadToUrl(
                targetUrl, "demo@example.com", "demo-app-secret", payload,
                WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS,
                WebDavClient.DEFAULT_READ_TIMEOUT_MS,
                url -> okConn);
        assertEquals(200, okResult.httpStatus);
        assertFalse(okResult.createdNew);
        assertTrue(okConn.disconnected);
    }

    // 验证凭据错误、目录不可用、重定向拒绝、服务端错误与网络超时分类准确，且异常消息不泄露密码。
    public void testUploadErrorClassificationAndNoPasswordLeak() throws Exception {
        URL targetUrl = WebDavClient.buildBackupFileUrl("https://dav.example.com/dav/NotePad/");
        byte[] payload = NoteBackupFormat.encodeToUtf8Bytes(Collections.emptyList());
        String secret = "NEVER-LOG-THIS-APP-PASSWORD";

        // 缺少账号或密码。
        try {
            WebDavClient.uploadBackup(
                    new WebDavClient.Config("https://dav.example.com/dav/NotePad/", "", secret), payload);
            fail("Expected INVALID_CREDENTIALS for empty username");
        } catch (WebDavClient.WebDavException expected) {
            assertEquals(WebDavClient.ErrorCode.INVALID_CREDENTIALS, expected.errorCode);
            assertFalse(expected.getMessage().contains(secret));
        }

        // 401 / 403 -> 凭据错误。
        assertUploadStatusError(targetUrl, payload, secret, 401, WebDavClient.ErrorCode.AUTH_FAILED);
        assertUploadStatusError(targetUrl, payload, secret, 403, WebDavClient.ErrorCode.AUTH_FAILED);

        // 404 / 409 / 302 / 405 -> 目录不可用（含禁用重定向保护）。
        assertUploadStatusError(targetUrl, payload, secret, 404, WebDavClient.ErrorCode.DIRECTORY_UNAVAILABLE);
        assertUploadStatusError(targetUrl, payload, secret, 409, WebDavClient.ErrorCode.DIRECTORY_UNAVAILABLE);
        assertUploadStatusError(targetUrl, payload, secret, 302, WebDavClient.ErrorCode.DIRECTORY_UNAVAILABLE);
        assertUploadStatusError(targetUrl, payload, secret, 405, WebDavClient.ErrorCode.DIRECTORY_UNAVAILABLE);

        // 500 -> 服务端异常。
        assertUploadStatusError(targetUrl, payload, secret, 500, WebDavClient.ErrorCode.SERVER_ERROR);

        // 读取超时 -> 网络失败，且同样释放连接。
        FakeHttpURLConnection timeoutConn =
                new FakeHttpURLConnection(targetUrl, 200, new SocketTimeoutException("Read timed out"));
        try {
            WebDavClient.uploadToUrl(
                    targetUrl, "demo@example.com", secret, payload,
                    WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS,
                    WebDavClient.DEFAULT_READ_TIMEOUT_MS,
                    url -> timeoutConn);
            fail("Expected NETWORK_ERROR on SocketTimeoutException");
        } catch (WebDavClient.WebDavException expected) {
            assertEquals(WebDavClient.ErrorCode.NETWORK_ERROR, expected.errorCode);
            assertFalse(expected.getMessage().contains(secret));
            assertTrue(timeoutConn.disconnected);
        }
    }

    // 验证非敏感配置存储仅写入目录地址与账号，绝不落盘应用密码。
    public void testNonSecretPreferencesOnlySaveUrlAndUsername() {
        SharedPreferences prefs = getContext().getSharedPreferences(
                CloudBackupActivity.PREFS_NAME, android.content.Context.MODE_PRIVATE);
        prefs.edit().clear().commit();

        CloudBackupActivity.saveNonSecretConfig(
                getContext(), " https://dav.jianguoyun.com/dav/NotePad/ ", " demo@example.com ");

        Map<String, ?> all = prefs.getAll();
        assertEquals(2, all.size());
        assertEquals("https://dav.jianguoyun.com/dav/NotePad/", all.get(CloudBackupActivity.KEY_DIRECTORY_URL));
        assertEquals("demo@example.com", all.get(CloudBackupActivity.KEY_USERNAME));
        assertFalse(all.containsKey("password"));

        // 清理测试写入的偏好项，保持测试环境干净。
        prefs.edit().clear().commit();
    }

    // 验证 HTTPS GET 下载成功时的请求方法、头部、禁用重定向、超时、载荷完整与资源释放。
    public void testDownloadBackupSuccessAndHeaders() throws Exception {
        NoteBackupFormat.NoteRecord sampleNote = new NoteBackupFormat.NoteRecord(
                "远端恢复样例",
                "第一行：来自 WebDAV 的正文\n第二行：含中文与 123",
                1795251000100L,
                1795251000200L);
        byte[] serverBytes = NoteBackupFormat.encodeToUtf8Bytes(Collections.singletonList(sampleNote));
        URL targetUrl = WebDavClient.buildBackupFileUrl("https://dav.example.com/dav/NotePad/");

        FakeHttpURLConnection conn = new FakeHttpURLConnection(targetUrl, 200, null, serverBytes);
        byte[] downloaded = WebDavClient.downloadFromUrl(
                targetUrl, "demo@example.com", "demo-app-secret",
                WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS,
                WebDavClient.DEFAULT_READ_TIMEOUT_MS,
                url -> conn);

        assertEquals("GET", conn.getRequestMethod());
        assertFalse(conn.getInstanceFollowRedirects());
        assertEquals(WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS, conn.getConnectTimeout());
        assertEquals(WebDavClient.DEFAULT_READ_TIMEOUT_MS, conn.getReadTimeout());
        assertEquals(
                WebDavClient.buildBasicAuthHeader("demo@example.com", "demo-app-secret"),
                conn.capturedHeaders.get("Authorization"));
        assertEquals("application/json", conn.capturedHeaders.get("Accept"));
        assertTrue(conn.disconnected);
        assertTrue(conn.responseStreamClosed);

        NoteBackupFormat.BackupSnapshot decoded = NoteBackupFormat.decodeFromUtf8Bytes(downloaded);
        assertEquals(1, decoded.notes.size());
        assertEquals(sampleNote, decoded.notes.get(0));
    }

    // 验证下载阶段对 404（远端无文件）、401/403、302/409、500 及网络中断的错误分类与密码不泄露。
    public void testDownloadErrorClassificationAndNoPasswordLeak() throws Exception {
        URL targetUrl = WebDavClient.buildBackupFileUrl("https://dav.example.com/dav/NotePad/");
        String secret = "NEVER-LOG-DOWNLOAD-SECRET";

        assertDownloadStatusError(targetUrl, secret, 404, WebDavClient.ErrorCode.BACKUP_NOT_FOUND);
        assertDownloadStatusError(targetUrl, secret, 401, WebDavClient.ErrorCode.AUTH_FAILED);
        assertDownloadStatusError(targetUrl, secret, 403, WebDavClient.ErrorCode.AUTH_FAILED);
        assertDownloadStatusError(targetUrl, secret, 302, WebDavClient.ErrorCode.DIRECTORY_UNAVAILABLE);
        assertDownloadStatusError(targetUrl, secret, 409, WebDavClient.ErrorCode.DIRECTORY_UNAVAILABLE);
        assertDownloadStatusError(targetUrl, secret, 500, WebDavClient.ErrorCode.SERVER_ERROR);

        // 模拟读取超时中断。
        FakeHttpURLConnection timeoutConn = new FakeHttpURLConnection(
                targetUrl, 200, new SocketTimeoutException("Download timed out"), null);
        try {
            WebDavClient.downloadFromUrl(
                    targetUrl, "demo@example.com", secret,
                    WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS,
                    WebDavClient.DEFAULT_READ_TIMEOUT_MS,
                    url -> timeoutConn);
            fail("Expected NETWORK_ERROR on download timeout");
        } catch (WebDavClient.WebDavException expected) {
            assertEquals(WebDavClient.ErrorCode.NETWORK_ERROR, expected.errorCode);
            assertFalse(expected.getMessage().contains(secret));
            assertTrue(timeoutConn.disconnected);
        }
    }

    // 断言下载收到指定 HTTP 状态码时映射到预期 ErrorCode，且不泄露密码并释放连接。
    private static void assertDownloadStatusError(
            URL targetUrl,
            String secret,
            int httpStatus,
            WebDavClient.ErrorCode expectedCode) {
        FakeHttpURLConnection conn = new FakeHttpURLConnection(targetUrl, httpStatus, null, null);
        try {
            WebDavClient.downloadFromUrl(
                    targetUrl, "demo@example.com", secret,
                    WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS,
                    WebDavClient.DEFAULT_READ_TIMEOUT_MS,
                    url -> conn);
            fail("Expected WebDavException(" + expectedCode + ") for HTTP " + httpStatus);
        } catch (WebDavClient.WebDavException expected) {
            assertEquals(expectedCode, expected.errorCode);
            assertEquals(httpStatus, expected.httpStatus);
            assertFalse(expected.getMessage().contains(secret));
            assertTrue(conn.disconnected);
            assertTrue(conn.responseStreamClosed);
        }
    }

    // 断言非法目录地址抛出 INVALID_URL 异常。
    private static void assertInvalidUrl(String rawUrl) {
        try {
            WebDavClient.normalizeDirectoryUrl(rawUrl);
            fail("Expected INVALID_URL for: " + rawUrl);
        } catch (WebDavClient.WebDavException expected) {
            assertEquals(WebDavClient.ErrorCode.INVALID_URL, expected.errorCode);
        }
    }

    // 断言指定 HTTP 状态码映射到预期 ErrorCode，且异常信息不包含密码并已关闭连接。
    private static void assertUploadStatusError(
            URL targetUrl,
            byte[] payload,
            String secret,
            int httpStatus,
            WebDavClient.ErrorCode expectedCode) {
        FakeHttpURLConnection conn = new FakeHttpURLConnection(targetUrl, httpStatus, null);
        try {
            WebDavClient.uploadToUrl(
                    targetUrl, "demo@example.com", secret, payload,
                    WebDavClient.DEFAULT_CONNECT_TIMEOUT_MS,
                    WebDavClient.DEFAULT_READ_TIMEOUT_MS,
                    url -> conn);
            fail("Expected WebDavException(" + expectedCode + ") for HTTP " + httpStatus);
        } catch (WebDavClient.WebDavException expected) {
            assertEquals(expectedCode, expected.errorCode);
            assertEquals(httpStatus, expected.httpStatus);
            assertFalse(expected.getMessage().contains(secret));
            assertTrue(conn.disconnected);
            assertTrue(conn.responseStreamClosed);
        }
    }

    // 内存模拟 HttpURLConnection，用于在不放宽应用 HTTPS 安全策略的前提下验证真实协议细节与资源关闭。
    private static final class FakeHttpURLConnection extends HttpURLConnection {
        final Map<String, String> capturedHeaders = new HashMap<>();
        final ByteArrayOutputStream capturedBody = new ByteArrayOutputStream();
        private final int statusCode;
        private final IOException responseFailure;
        private final byte[] responseBody;
        boolean disconnected;
        boolean responseStreamClosed;

        // 构造返回指定 HTTP 状态码或模拟网络异常的假连接（用于上传测试）。
        FakeHttpURLConnection(URL url, int statusCode, IOException responseFailure) {
            this(url, statusCode, responseFailure, null);
        }

        // 构造可自定义响应体字节数组的假连接（用于下载与上传测试）。
        FakeHttpURLConnection(URL url, int statusCode, IOException responseFailure, byte[] responseBody) {
            super(url);
            this.statusCode = statusCode;
            this.responseFailure = responseFailure;
            this.responseBody = responseBody;
        }

        // 记录写入的请求头字段。
        @Override
        public void setRequestProperty(String key, String value) {
            capturedHeaders.put(key, value);
        }

        // 返回内存输出流以捕获上传载荷字节。
        @Override
        public OutputStream getOutputStream() {
            return capturedBody;
        }

        // 返回预设的 HTTP 状态码或抛出模拟网络异常。
        @Override
        public int getResponseCode() throws IOException {
            if (responseFailure != null) {
                throw responseFailure;
            }
            return statusCode;
        }

        // 返回可追踪关闭状态的成功响应流，下载场景返回预设字节数组。
        @Override
        public InputStream getInputStream() {
            byte[] bytes = responseBody != null
                    ? responseBody
                    : "ok".getBytes(StandardCharsets.UTF_8);
            return createTrackedStream(bytes);
        }

        // 返回可追踪关闭状态的错误响应流。
        @Override
        public InputStream getErrorStream() {
            return createTrackedStream("error".getBytes(StandardCharsets.UTF_8));
        }

        // 标记连接已被断开释放。
        @Override
        public void disconnect() {
            disconnected = true;
        }

        // 测试假连接不经过代理。
        @Override
        public boolean usingProxy() {
            return false;
        }

        // 内存假连接无需真实握手。
        @Override
        public void connect() {
        }

        // 创建会在 close 时记录标志的响应字节流，用于校验客户端确实关闭了流资源。
        private InputStream createTrackedStream(byte[] bytes) {
            return new ByteArrayInputStream(bytes) {
                @Override
                public void close() throws IOException {
                    responseStreamClosed = true;
                    super.close();
                }
            };
        }
    }
}

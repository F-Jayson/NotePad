package com.example.android.notepad;

import android.util.Base64;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

// WebDAV HTTPS 客户端工具。
// 使用 Android 平台原生 HttpURLConnection / HttpsURLConnection 执行后台传输，
// 强制校验 HTTPS、禁用自动重定向以防止凭据外泄，并统一分类凭据错误、目录不可用与网络失败。
final class WebDavClient {

    // 远端固定维护的整本备份文件名，T08 上传与 T09 下载共用同一路径规则。
    static final String BACKUP_FILE_NAME = "notepad-backup.json";

    // 默认连接超时与读取超时（毫秒），防止弱网或无响应服务端长期挂起后台线程。
    static final int DEFAULT_CONNECT_TIMEOUT_MS = 10000;
    static final int DEFAULT_READ_TIMEOUT_MS = 15000;

    // 上传备份文件使用的 UTF-8 JSON 内容类型。
    private static final String CONTENT_TYPE_JSON_UTF8 = "application/json; charset=utf-8";

    // 打开 HTTP/HTTPS 连接的抽象接口，生产环境直接调用 URL.openConnection，测试可注入假连接验证协议行为。
    interface ConnectionOpener {
        HttpURLConnection open(URL url) throws IOException;
    }

    // 默认生产连接工厂，直接通过系统平台实现打开 HTTPS 连接。
    private static final ConnectionOpener DEFAULT_OPENER = url -> (HttpURLConnection) url.openConnection();

    // 工具类禁止外部实例化。
    private WebDavClient() {
    }

    // WebDAV 连接配置载体；应用密码仅驻留在内存对象中，不得序列化落盘或写入日志。
    static final class Config {
        final String directoryUrl;
        final String username;
        final String password;

        // 构造 WebDAV 连接配置，自动去除目录地址与账号的首尾空白，保留密码原始字符。
        Config(String directoryUrl, String username, String password) {
            this.directoryUrl = directoryUrl == null ? "" : directoryUrl.trim();
            this.username = username == null ? "" : username.trim();
            this.password = password == null ? "" : password;
        }
    }

    // 上传结果载体，记录服务端确认的 HTTP 状态码及是否为首次创建。
    static final class UploadResult {
        final int httpStatus;
        final boolean createdNew;

        // 根据 HTTP 状态码构造上传成功结果（201 表示首次创建，200/204 表示覆盖已有副本）。
        UploadResult(int httpStatus) {
            this.httpStatus = httpStatus;
            this.createdNew = httpStatus == HttpURLConnection.HTTP_CREATED;
        }
    }

    // WebDAV 操作失败的错误分类，便于界面区分地址非法、凭据错误、目录不可用、服务端异常与网络失败。
    enum ErrorCode {
        INVALID_URL,
        INVALID_CREDENTIALS,
        AUTH_FAILED,
        DIRECTORY_UNAVAILABLE,
        SERVER_ERROR,
        NETWORK_ERROR
    }

    // WebDAV 请求异常，封装错误分类与可选的 HTTP 状态码，消息中绝不包含用户密码。
    static final class WebDavException extends Exception {
        final ErrorCode errorCode;
        final int httpStatus;

        // 构造无 HTTP 状态码的客户端校验或网络异常。
        WebDavException(ErrorCode errorCode, String message) {
            this(errorCode, -1, message, null);
        }

        // 构造带底层原因的客户端或网络异常。
        WebDavException(ErrorCode errorCode, String message, Throwable cause) {
            this(errorCode, -1, message, cause);
        }

        // 构造带服务端 HTTP 状态码的响应异常。
        WebDavException(ErrorCode errorCode, int httpStatus, String message) {
            this(errorCode, httpStatus, message, null);
        }

        // 统一初始化错误码、状态码与异常说明。
        WebDavException(ErrorCode errorCode, int httpStatus, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
            this.httpStatus = httpStatus;
        }
    }

    // 校验用户输入的 WebDAV 目录地址并返回规范化后的 HTTPS 目录地址（以斜杠结尾）。
    // 仅允许 https 协议、有效主机名，且拒绝携带查询参数或锚点。
    static String normalizeDirectoryUrl(String rawDirectoryUrl) throws WebDavException {
        URL parsed = parseAndValidateUrl(rawDirectoryUrl, false);
        String text = parsed.toExternalForm();
        String fileSuffix = "/" + BACKUP_FILE_NAME;
        // 若用户直接粘贴了包含固定文件名的完整路径，剥离文件名保留其父目录地址。
        if (text.endsWith(fileSuffix)) {
            text = text.substring(0, text.length() - BACKUP_FILE_NAME.length());
        } else if (!text.endsWith("/")) {
            text = text + "/";
        }
        return text;
    }

    // 基于用户配置的 HTTPS 目录地址拼接远端固定备份文件 URL（.../notepad-backup.json）。
    static URL buildBackupFileUrl(String rawDirectoryUrl) throws WebDavException {
        String normalizedDir = normalizeDirectoryUrl(rawDirectoryUrl);
        try {
            return new URL(normalizedDir + BACKUP_FILE_NAME);
        } catch (MalformedURLException e) {
            throw new WebDavException(ErrorCode.INVALID_URL, "Invalid WebDAV backup URL", e);
        }
    }

    // 通过 HTTPS PUT 将 UTF-8 JSON 备份字节上传到用户配置目录下的 notepad-backup.json。
    // 仅允许 HTTPS 地址；若服务端返回 200、201 或 204 视为成功，否则抛出对应 ErrorCode 的异常。
    static UploadResult uploadBackup(Config config, byte[] utf8Payload) throws WebDavException {
        if (config == null) {
            throw new WebDavException(ErrorCode.INVALID_CREDENTIALS, "WebDAV config must not be null");
        }
        if (utf8Payload == null) {
            throw new IllegalArgumentException("utf8Payload must not be null");
        }
        if (config.username.isEmpty() || config.password.isEmpty()) {
            throw new WebDavException(ErrorCode.INVALID_CREDENTIALS, "Username and password are required");
        }
        URL targetUrl = buildBackupFileUrl(config.directoryUrl);
        return uploadToUrl(targetUrl, config.username, config.password, utf8Payload,
                DEFAULT_CONNECT_TIMEOUT_MS, DEFAULT_READ_TIMEOUT_MS, DEFAULT_OPENER);
    }

    // 向指定 URL 执行 HTTPS PUT 上传的内部入口，支持注入 ConnectionOpener 进行协议与状态码单元测试。
    // 生产入口始终先经过 buildBackupFileUrl 强制校验 HTTPS。
    static UploadResult uploadToUrl(
            URL targetUrl,
            String username,
            String password,
            byte[] utf8Payload,
            int connectTimeoutMs,
            int readTimeoutMs,
            ConnectionOpener opener) throws WebDavException {
        if (targetUrl == null) {
            throw new WebDavException(ErrorCode.INVALID_URL, "Target URL must not be null");
        }
        if (username == null || username.trim().isEmpty() || password == null || password.isEmpty()) {
            throw new WebDavException(ErrorCode.INVALID_CREDENTIALS, "Username and password are required");
        }
        if (utf8Payload == null) {
            throw new IllegalArgumentException("utf8Payload must not be null");
        }
        if (opener == null) {
            throw new IllegalArgumentException("opener must not be null");
        }

        HttpURLConnection connection = null;
        try {
            connection = opener.open(targetUrl);
            // 禁止自动跟随重定向，防止携带 Basic 认证头的请求被转发到其他域名。
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestMethod("PUT");
            connection.setDoOutput(true);
            connection.setUseCaches(false);
            connection.setFixedLengthStreamingMode(utf8Payload.length);
            connection.setRequestProperty("Authorization", buildBasicAuthHeader(username.trim(), password));
            connection.setRequestProperty("Content-Type", CONTENT_TYPE_JSON_UTF8);

            // 将完整 UTF-8 字节流写入请求体并刷新。
            try (OutputStream out = connection.getOutputStream()) {
                out.write(utf8Payload);
                out.flush();
            }

            int status = connection.getResponseCode();
            // 读取并关闭响应流，保证底层连接资源干净回收。
            drainResponseStream(connection, status);
            return evaluateUploadResponse(status);
        } catch (WebDavException e) {
            throw e;
        } catch (IOException e) {
            throw new WebDavException(ErrorCode.NETWORK_ERROR, "Network error during WebDAV upload", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    // 校验 URL 协议、主机名与结构合法性；生产调用强制要求 https，禁止携带查询串或锚点。
    private static URL parseAndValidateUrl(String rawUrl, boolean allowHttp) throws WebDavException {
        if (rawUrl == null || rawUrl.trim().isEmpty()) {
            throw new WebDavException(ErrorCode.INVALID_URL, "WebDAV directory URL is empty");
        }
        String trimmed = rawUrl.trim();
        URL url;
        try {
            url = new URL(trimmed);
            // 通过 URI 二次校验避免包含非法空格或未转义控制字符的地址绕过 URL 解析。
            URI uri = url.toURI();
            if (uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new WebDavException(
                        ErrorCode.INVALID_URL, "WebDAV directory URL must not contain query or fragment");
            }
        } catch (MalformedURLException | URISyntaxException e) {
            throw new WebDavException(ErrorCode.INVALID_URL, "Malformed WebDAV directory URL", e);
        }

        String scheme = url.getProtocol();
        boolean validScheme = "https".equalsIgnoreCase(scheme)
                || (allowHttp && "http".equalsIgnoreCase(scheme));
        if (!validScheme) {
            throw new WebDavException(ErrorCode.INVALID_URL, "WebDAV directory URL must use HTTPS");
        }
        if (url.getHost() == null || url.getHost().trim().isEmpty()) {
            throw new WebDavException(ErrorCode.INVALID_URL, "WebDAV directory URL must contain a host");
        }
        return url;
    }

    // 构造 RFC 7617 HTTP Basic 认证请求头（UTF-8 编码后 Base64，不含换行符）。
    // 返回值仅直接写入请求头，调用方不得输出到日志或持久化存储。
    static String buildBasicAuthHeader(String username, String password) {
        String credentials = username + ":" + password;
        String encoded = Base64.encodeToString(
                credentials.getBytes(StandardCharsets.UTF_8), Base64.NO_WRAP);
        return "Basic " + encoded;
    }

    // 根据 WebDAV PUT 响应码区分成功、凭据失败、目录不可用与其他服务端异常。
    private static UploadResult evaluateUploadResponse(int status) throws WebDavException {
        if (status == HttpURLConnection.HTTP_OK
                || status == HttpURLConnection.HTTP_CREATED
                || status == HttpURLConnection.HTTP_NO_CONTENT) {
            return new UploadResult(status);
        }
        if (status == HttpURLConnection.HTTP_UNAUTHORIZED
                || status == HttpURLConnection.HTTP_FORBIDDEN) {
            throw new WebDavException(
                    ErrorCode.AUTH_FAILED, status, "Authentication failed (HTTP " + status + ")");
        }
        // WebDAV 规范中，父目录不存在常返回 404 或 409；3xx 重定向或 405 也说明目录地址配置有误。
        if (status == HttpURLConnection.HTTP_NOT_FOUND
                || status == HttpURLConnection.HTTP_CONFLICT
                || status == HttpURLConnection.HTTP_BAD_METHOD
                || (status >= 300 && status < 400)) {
            throw new WebDavException(
                    ErrorCode.DIRECTORY_UNAVAILABLE,
                    status,
                    "Remote directory unavailable (HTTP " + status + ")");
        }
        throw new WebDavException(
                ErrorCode.SERVER_ERROR, status, "Unexpected WebDAV response (HTTP " + status + ")");
    }

    // 排空并静默关闭响应流或错误流，避免底层套接字连接泄漏。
    private static void drainResponseStream(HttpURLConnection connection, int status) {
        InputStream stream = null;
        try {
            stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (stream != null) {
                byte[] discard = new byte[1024];
                while (stream.read(discard) != -1) {
                    // 仅排空响应体，无需解析内容。
                }
            }
        } catch (IOException ignored) {
            // 排空阶段异常不影响已获取的 HTTP 状态码判定。
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                }
            }
        }
    }
}

package com.example.android.notepad;

import android.content.ContentResolver;
import android.content.res.AssetFileDescriptor;
import android.net.Uri;
import android.text.TextUtils;

import java.io.Closeable;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;
import java.util.regex.Pattern;

// 单条笔记纯文本导出工具类。
// 复用 NotePadProvider 已提供的 text/plain 管道流输出（UTF-8 编码，标题、空行、正文格式），
// 提供建议文件名清理与向系统文档 URI（SAF）复制流数据的能力。
final class NoteExporter {

    // Provider 与系统文档创建器共同使用的纯文本 MIME 类型。
    static final String MIME_TYPE_TEXT_PLAIN = "text/plain";
    // 导出文件默认使用的文本扩展名。
    private static final String TXT_EXTENSION = ".txt";
    // 字节流复制缓冲区大小（8 KB）。
    private static final int COPY_BUFFER_SIZE = 8192;
    // 建议文件主名的最大长度，避免超长标题在部分存储提供者上创建失败。
    private static final int MAX_BASE_NAME_LENGTH = 80;
    // 匹配常见文件系统不支持的路径分隔符、通配符与控制字符。
    private static final Pattern UNSAFE_FILENAME_CHARS =
            Pattern.compile("[\\\\/:*?\"<>|\\r\\n\\t\\u0000-\\u001F]+");

    // 工具类禁止外部实例化。
    private NoteExporter() {
    }

    // 根据笔记标题生成传给系统文档创建器（ACTION_CREATE_DOCUMENT）的建议文件名。
    // 参数 title 为笔记原始标题（可为空），fallbackBaseName 为清理后为空时的默认主名。
    // 处理顺序：去首尾空白 -> 替换非法字符 -> 去首尾点号空格 -> 空白回退 ->
    // 归一化 .txt 后缀并限制长度，返回可直接作为 EXTRA_TITLE 的安全文件名。
    static String buildSuggestedFileName(String title, String fallbackBaseName) {
        String candidate = title == null ? "" : title.trim();
        // 清理非法文件名字符，避免系统文档提供者拒绝或截断文件名。
        candidate = UNSAFE_FILENAME_CHARS.matcher(candidate).replaceAll("_").trim();
        // 去掉首尾点号与空格，防止生成 "."、".." 或隐藏文件等非法或异常文件名。
        while (candidate.startsWith(".") || candidate.startsWith(" ")) {
            candidate = candidate.substring(1);
        }
        while (candidate.endsWith(".") || candidate.endsWith(" ")) {
            candidate = candidate.substring(0, candidate.length() - 1);
        }

        if (TextUtils.isEmpty(candidate)) {
            candidate = TextUtils.isEmpty(fallbackBaseName) ? "note" : fallbackBaseName.trim();
        }

        // 若标题本身已带 .txt 后缀（不区分大小写），先剥离再统一补回小写 .txt，避免出现 .txt.txt。
        boolean hasTxtExtension = candidate.toLowerCase(Locale.ROOT).endsWith(TXT_EXTENSION);
        String baseName = hasTxtExtension
                ? candidate.substring(0, candidate.length() - TXT_EXTENSION.length()).trim()
                : candidate;
        if (TextUtils.isEmpty(baseName)) {
            baseName = TextUtils.isEmpty(fallbackBaseName) ? "note" : fallbackBaseName.trim();
        }
        // 截断过长的主名，确保与扩展名拼接后符合常见文件系统长度限制。
        if (baseName.length() > MAX_BASE_NAME_LENGTH) {
            baseName = baseName.substring(0, MAX_BASE_NAME_LENGTH).trim();
        }
        return baseName + TXT_EXTENSION;
    }

    // 通过 ContentResolver 将源笔记的 text/plain 管道流完整复制到目标文档 URI。
    // 参数 resolver 为内容解析器，sourceUri 为单条笔记真实 ID URI，targetUri 为用户选定的目标文档 URI。
    // 应在后台线程调用，避免阻塞主线程或与 ContentProvider.openPipeHelper 管道线程竞争；
    // 任一入参为空抛出 IllegalArgumentException，读写或落盘关闭失败抛出 IOException。
    static void exportNoteToUri(ContentResolver resolver, Uri sourceUri, Uri targetUri)
            throws IOException {
        if (resolver == null || sourceUri == null || targetUri == null) {
            throw new IllegalArgumentException("Resolver, sourceUri, and targetUri must not be null");
        }

        AssetFileDescriptor sourceAfd;
        try {
            // 先通过 Provider 已有的流接口打开源笔记；若源笔记不存在或 URI 不支持纯文本流会立刻失败，
            // 从而避免在源端已失效的情况下提前打开或截断目标文档。
            sourceAfd = openNotePlainText(resolver, sourceUri);
        } catch (RuntimeException e) {
            throw new IOException("Failed to open source note " + sourceUri, e);
        }
        if (sourceAfd == null) {
            throw new FileNotFoundException("Unable to open plain text stream for " + sourceUri);
        }

        InputStream input = null;
        OutputStream output = null;
        IOException primaryError = null;
        try {
            // createInputStream() 返回 AutoCloseInputStream，关闭该输入流时会一并释放 sourceAfd。
            input = sourceAfd.createInputStream();
            output = openWritableOutputStream(resolver, targetUri);
            if (output == null) {
                throw new IOException("Unable to open output stream for " + targetUri);
            }

            // 按原始字节流透传 Provider 输出的 UTF-8 文本，保持“标题、空行、正文”与换行原样不变。
            byte[] buffer = new byte[COPY_BUFFER_SIZE];
            int bytesRead;
            while ((bytesRead = input.read(buffer)) != -1) {
                output.write(buffer, 0, bytesRead);
            }
            output.flush();
        } catch (IOException e) {
            primaryError = e;
            throw e;
        } catch (RuntimeException e) {
            // 文档提供者在权限失效或 URI 不可写时可能抛出 SecurityException 等运行时异常，统一转为 IOException。
            IOException wrapped = new IOException("Failed to export note to " + targetUri, e);
            primaryError = wrapped;
            throw wrapped;
        } finally {
            // 先关闭输出流以捕获落盘阶段可能出现的写入异常，再关闭输入流释放全部系统句柄。
            IOException closeOutputError = closeQuietly(output);
            closeQuietly(input);
            if (input == null) {
                // 当 createInputStream 尚未成功返回时，需要直接关闭 AssetFileDescriptor 避免句柄泄漏。
                closeQuietly(sourceAfd);
            }
            // 若主流程无异常但关闭输出流失败（如底层存储写满），仍须向上抛出，避免误报导出成功。
            if (primaryError == null && closeOutputError != null) {
                throw closeOutputError;
            }
        }
    }

    // 检查源 URI 是否支持 text/plain 流并打开对应的只读管道描述符。
    // 当 URI 不支持纯文本流或对应笔记不存在时抛出 FileNotFoundException。
    private static AssetFileDescriptor openNotePlainText(ContentResolver resolver, Uri sourceUri)
            throws FileNotFoundException {
        // 先经 getStreamTypes 核对类型，确保调用链路与 NotePadProvider 的流能力声明保持一致。
        String[] streamTypes = resolver.getStreamTypes(sourceUri, MIME_TYPE_TEXT_PLAIN);
        if (streamTypes == null || streamTypes.length == 0) {
            throw new FileNotFoundException("Plain text stream not supported for " + sourceUri);
        }
        return resolver.openTypedAssetFileDescriptor(sourceUri, streamTypes[0], null);
    }

    // 打开目标文档 URI 的可写输出流；若目标不存在或无法打开写入则抛出 FileNotFoundException。
    private static OutputStream openWritableOutputStream(ContentResolver resolver, Uri targetUri)
            throws FileNotFoundException {
        try {
            // 优先使用截断写模式（"wt"），覆盖同名旧文件时不会残留原文件尾部字节。
            return resolver.openOutputStream(targetUri, "wt");
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            // 兼容仅接受标准 "w" 模式的低版本或第三方文档提供者。
            return resolver.openOutputStream(targetUri, "w");
        }
    }

    // 安全关闭可关闭资源并返回关闭阶段产生的 IOException，避免在 finally 中直接覆盖主异常。
    // 入参为空或关闭成功时返回 null。
    private static IOException closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return null;
        }
        try {
            closeable.close();
            return null;
        } catch (IOException e) {
            return e;
        }
    }
}

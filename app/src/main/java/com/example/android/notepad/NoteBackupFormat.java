package com.example.android.notepad;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

// 整本笔记备份格式编解码与校验工具。
// 负责独立读取全部笔记、按 UTF-8 JSON 格式编码（顶层含 version 与 notes 数组），
// 并为 WebDAV 云备份与云恢复提供统一的解析和严格字段校验，解析过程不修改数据库。
final class NoteBackupFormat {

    // 当前整本备份格式版本号。
    static final int CURRENT_VERSION = 1;

    // 顶层与单条记录使用的 JSON 字段名常量。
    static final String KEY_VERSION = "version";
    static final String KEY_NOTES = "notes";
    static final String KEY_TITLE = "title";
    static final String KEY_NOTE = "note";
    static final String KEY_CREATED = "created";
    static final String KEY_MODIFIED = "modified";

    // 查询整本笔记使用的四列投影，省略本地 _ID 与界面偏好。
    private static final String[] BACKUP_PROJECTION = new String[] {
            NotePad.Notes.COLUMN_NAME_TITLE,
            NotePad.Notes.COLUMN_NAME_NOTE,
            NotePad.Notes.COLUMN_NAME_CREATE_DATE,
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE
    };

    // 工具类禁止外部实例化。
    private NoteBackupFormat() {
    }

    // 单条备份笔记的不可变数据载体，仅保留跨设备同步所需的四个业务字段。
    static final class NoteRecord {
        final String title;
        final String note;
        final long created;
        final long modified;

        // 构造单条备份笔记记录，拒绝空字符串引用或负数时间戳。
        NoteRecord(String title, String note, long created, long modified) {
            if (title == null || note == null) {
                throw new IllegalArgumentException("title and note must not be null");
            }
            if (created < 0L || modified < 0L) {
                throw new IllegalArgumentException("created and modified must be non-negative");
            }
            this.title = title;
            this.note = note;
            this.created = created;
            this.modified = modified;
        }

        // 转换为可供 Provider 插入的 ContentValues，不写入 _ID 以便本机重新分配行号。
        ContentValues toContentValues() {
            ContentValues values = new ContentValues(4);
            values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
            values.put(NotePad.Notes.COLUMN_NAME_NOTE, note);
            values.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, created);
            values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, modified);
            return values;
        }

        // 按标题、正文、创建与修改毫秒值四项完全一致判断等价，供恢复阶段去重与计数匹配使用。
        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof NoteRecord)) {
                return false;
            }
            NoteRecord that = (NoteRecord) o;
            return created == that.created
                    && modified == that.modified
                    && title.equals(that.title)
                    && note.equals(that.note);
        }

        // 与 equals 保持一致，基于四个字段计算哈希值。
        @Override
        public int hashCode() {
            return Objects.hash(title, note, created, modified);
        }
    }

    // 整本备份快照的不可变数据载体，封装格式版本号与按序排列的笔记列表。
    static final class BackupSnapshot {
        final int version;
        final List<NoteRecord> notes;

        // 构造整本备份快照并复制为不可变列表，避免外部修改破坏快照一致性。
        BackupSnapshot(int version, List<NoteRecord> notes) {
            if (notes == null) {
                throw new IllegalArgumentException("notes must not be null");
            }
            this.version = version;
            this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        }
    }

    // 备份格式校验失败的错误分类，便于调用方针对损坏文件、未知版本等场景给出准确反馈。
    enum ErrorCode {
        INVALID_UTF8,
        INVALID_JSON,
        UNSUPPORTED_VERSION,
        INVALID_STRUCTURE,
        INVALID_NOTE_FIELD
    }

    // 表示备份文件编码非法、JSON 损坏、版本不受支持或字段校验未通过的检查型异常。
    static final class BackupFormatException extends Exception {
        final ErrorCode errorCode;

        // 使用明确错误码与说明信息构造异常。
        BackupFormatException(ErrorCode errorCode, String message) {
            super(message);
            this.errorCode = errorCode;
        }

        // 使用明确错误码、说明信息及底层原因异常构造异常。
        BackupFormatException(ErrorCode errorCode, String message, Throwable cause) {
            super(message, cause);
            this.errorCode = errorCode;
        }
    }

    // 独立查询笔记集合 URI 中的全部笔记，不受列表当前搜索筛选条件影响。
    // 空库时返回空列表；若 Provider 查询返回空游标则抛出 IOException。
    static List<NoteRecord> queryAllNotes(ContentResolver resolver) throws IOException {
        if (resolver == null) {
            throw new IllegalArgumentException("resolver must not be null");
        }

        // 直接查询集合 URI 且不传 selection，沿用默认修改时间倒序排列。
        try (Cursor cursor = resolver.query(
                NotePad.Notes.CONTENT_URI,
                BACKUP_PROJECTION,
                null,
                null,
                NotePad.Notes.DEFAULT_SORT_ORDER)) {
            if (cursor == null) {
                throw new IOException("Unable to query notes from provider");
            }

            List<NoteRecord> records = new ArrayList<>(cursor.getCount());
            while (cursor.moveToNext()) {
                // 兼容历史异常空列值：文本列回退为空串，时间列回退为 0 毫秒。
                String title = cursor.isNull(0) ? "" : cursor.getString(0);
                String note = cursor.isNull(1) ? "" : cursor.getString(1);
                long created = cursor.isNull(2) ? 0L : cursor.getLong(2);
                long modified = cursor.isNull(3) ? 0L : cursor.getLong(3);
                if (created < 0L || modified < 0L) {
                    throw new IOException("Note contains negative timestamp");
                }
                records.add(new NoteRecord(title, note, created, modified));
            }
            return Collections.unmodifiableList(records);
        }
    }

    // 读取当前全部笔记并封装为最新版本的整本备份快照。
    static BackupSnapshot createSnapshot(ContentResolver resolver) throws IOException {
        return new BackupSnapshot(CURRENT_VERSION, queryAllNotes(resolver));
    }

    // 将笔记列表按当前版本号编码为 JSON 字符串。
    static String encodeToJson(List<NoteRecord> notes) {
        return encodeToJson(new BackupSnapshot(CURRENT_VERSION, notes));
    }

    // 将整本备份快照编码为标准 JSON 字符串，仅包含顶层 version 与 notes 数组。
    static String encodeToJson(BackupSnapshot snapshot) {
        if (snapshot == null) {
            throw new IllegalArgumentException("snapshot must not be null");
        }
        if (snapshot.version != CURRENT_VERSION) {
            throw new IllegalArgumentException("Unsupported backup version: " + snapshot.version);
        }

        try {
            JSONArray notesArray = new JSONArray();
            for (NoteRecord record : snapshot.notes) {
                if (record == null) {
                    throw new IllegalArgumentException("notes must not contain null elements");
                }
                // 每条记录仅写入四个核心字段，保留原始整数毫秒值，不导出本地 ID 或界面偏好。
                JSONObject item = new JSONObject();
                item.put(KEY_TITLE, record.title);
                item.put(KEY_NOTE, record.note);
                item.put(KEY_CREATED, record.created);
                item.put(KEY_MODIFIED, record.modified);
                notesArray.put(item);
            }

            JSONObject root = new JSONObject();
            root.put(KEY_VERSION, snapshot.version);
            root.put(KEY_NOTES, notesArray);
            return root.toString();
        } catch (JSONException e) {
            // 数值与字符串写入 JSONObject 正常不会失败，若发生异常直接转为状态异常暴露问题。
            throw new IllegalStateException("Failed to encode backup JSON", e);
        }
    }

    // 将笔记列表编码为 UTF-8 字节数组，供网络上传或文件写入直接使用。
    static byte[] encodeToUtf8Bytes(List<NoteRecord> notes) {
        return encodeToJson(notes).getBytes(StandardCharsets.UTF_8);
    }

    // 将整本备份快照编码为 UTF-8 字节数组。
    static byte[] encodeToUtf8Bytes(BackupSnapshot snapshot) {
        return encodeToJson(snapshot).getBytes(StandardCharsets.UTF_8);
    }

    // 按严格 UTF-8 解码字节数组并完成 JSON 结构与字段校验；不会修改本地数据库。
    static BackupSnapshot decodeFromUtf8Bytes(byte[] utf8Bytes) throws BackupFormatException {
        if (utf8Bytes == null || utf8Bytes.length == 0) {
            throw new BackupFormatException(ErrorCode.INVALID_JSON, "Backup payload is empty");
        }

        // 显式开启非法字节报错，拒绝损坏的非 UTF-8 数据而非静默替换乱码字符。
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        String jsonText;
        try {
            jsonText = decoder.decode(ByteBuffer.wrap(utf8Bytes)).toString();
        } catch (CharacterCodingException e) {
            throw new BackupFormatException(ErrorCode.INVALID_UTF8, "Backup payload is not valid UTF-8", e);
        }
        return decodeFromJson(jsonText);
    }

    // 解析并严格校验备份 JSON 字符串，还原全部笔记记录；过程为纯内存操作，不会修改数据库。
    static BackupSnapshot decodeFromJson(String jsonText) throws BackupFormatException {
        if (jsonText == null || jsonText.trim().isEmpty()) {
            throw new BackupFormatException(ErrorCode.INVALID_JSON, "Backup JSON is empty");
        }

        JSONObject root = parseRootObject(jsonText);
        int version = parseAndValidateVersion(root);
        JSONArray notesArray = parseNotesArray(root);

        List<NoteRecord> records = new ArrayList<>(notesArray.length());
        for (int i = 0; i < notesArray.length(); i++) {
            Object rawItem = notesArray.opt(i);
            if (!(rawItem instanceof JSONObject)) {
                throw new BackupFormatException(
                        ErrorCode.INVALID_NOTE_FIELD,
                        "Note element at index " + i + " must be a JSON object");
            }
            JSONObject item = (JSONObject) rawItem;
            String title = requireStringField(item, KEY_TITLE, i);
            String note = requireStringField(item, KEY_NOTE, i);
            long created = requireTimestampField(item, KEY_CREATED, i);
            long modified = requireTimestampField(item, KEY_MODIFIED, i);
            records.add(new NoteRecord(title, note, created, modified));
        }

        return new BackupSnapshot(version, records);
    }

    // 解析顶层 JSON 对象并校验其后不存在多余的非空白尾随字符。
    private static JSONObject parseRootObject(String jsonText) throws BackupFormatException {
        try {
            JSONTokener tokener = new JSONTokener(jsonText);
            Object rootValue = tokener.nextValue();
            if (!(rootValue instanceof JSONObject)) {
                throw new BackupFormatException(
                        ErrorCode.INVALID_JSON, "Backup JSON root must be an object");
            }
            // JSONTokener 默认在读完首个值后停止，需继续检查剩余内容以拒绝尾随损坏数据。
            while (tokener.more()) {
                char trailing = tokener.next();
                if (!Character.isWhitespace(trailing)) {
                    throw new BackupFormatException(
                            ErrorCode.INVALID_JSON, "Unexpected trailing content after JSON root");
                }
            }
            return (JSONObject) rootValue;
        } catch (JSONException e) {
            throw new BackupFormatException(ErrorCode.INVALID_JSON, "Malformed backup JSON", e);
        }
    }

    // 校验顶层 version 字段存在、为精确整数且等于当前支持的版本号。
    private static int parseAndValidateVersion(JSONObject root) throws BackupFormatException {
        if (!root.has(KEY_VERSION)) {
            throw new BackupFormatException(ErrorCode.INVALID_STRUCTURE, "Missing version field");
        }
        // 使用 opt 配合 instanceof 严格区分整数与字符串、浮点数或布尔值，避开 getInt 的隐式强转。
        Object rawVersion = root.opt(KEY_VERSION);
        if (!(rawVersion instanceof Integer) && !(rawVersion instanceof Long)) {
            throw new BackupFormatException(
                    ErrorCode.INVALID_STRUCTURE, "version field must be an integer");
        }
        long versionValue = ((Number) rawVersion).longValue();
        if (versionValue != CURRENT_VERSION) {
            throw new BackupFormatException(
                    ErrorCode.UNSUPPORTED_VERSION, "Unsupported backup version: " + versionValue);
        }
        return (int) versionValue;
    }

    // 校验顶层 notes 字段存在且为 JSON 数组。
    private static JSONArray parseNotesArray(JSONObject root) throws BackupFormatException {
        if (!root.has(KEY_NOTES)) {
            throw new BackupFormatException(ErrorCode.INVALID_STRUCTURE, "Missing notes field");
        }
        Object rawNotes = root.opt(KEY_NOTES);
        if (!(rawNotes instanceof JSONArray)) {
            throw new BackupFormatException(
                    ErrorCode.INVALID_STRUCTURE, "notes field must be a JSON array");
        }
        return (JSONArray) rawNotes;
    }

    // 严格读取单条笔记中的字符串字段，拒绝缺失、null 或非字符串类型（如数字、布尔值）。
    private static String requireStringField(JSONObject item, String key, int index)
            throws BackupFormatException {
        if (!item.has(key)) {
            throw new BackupFormatException(
                    ErrorCode.INVALID_NOTE_FIELD,
                    "Missing '" + key + "' field at note index " + index);
        }
        Object raw = item.opt(key);
        if (!(raw instanceof String)) {
            throw new BackupFormatException(
                    ErrorCode.INVALID_NOTE_FIELD,
                    "Field '" + key + "' must be a string at note index " + index);
        }
        return (String) raw;
    }

    // 严格读取单条笔记中的毫秒时间戳字段，拒绝缺失、非整数类型（如字符串、小数）或负数。
    private static long requireTimestampField(JSONObject item, String key, int index)
            throws BackupFormatException {
        if (!item.has(key)) {
            throw new BackupFormatException(
                    ErrorCode.INVALID_NOTE_FIELD,
                    "Missing '" + key + "' field at note index " + index);
        }
        Object raw = item.opt(key);
        if (!(raw instanceof Integer) && !(raw instanceof Long)) {
            throw new BackupFormatException(
                    ErrorCode.INVALID_NOTE_FIELD,
                    "Field '" + key + "' must be an integer timestamp at note index " + index);
        }
        long value = ((Number) raw).longValue();
        if (value < 0L) {
            throw new BackupFormatException(
                    ErrorCode.INVALID_NOTE_FIELD,
                    "Field '" + key + "' must be non-negative at note index " + index);
        }
        return value;
    }
}

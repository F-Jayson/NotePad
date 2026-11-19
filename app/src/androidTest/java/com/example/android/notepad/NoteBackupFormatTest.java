package com.example.android.notepad;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentResolver;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.List;

// NoteBackupFormat 的设备集成测试。
// 在隔离的 NotePadProvider 上验证整本笔记读出、UTF-8 JSON 编码、解码逐字段对照、
// 空库快照以及多种损坏或非法输入的严格拒绝与数据库无副作用保护。
public class NoteBackupFormatTest extends ProviderTestCase2<NotePadProvider> {

    // 指向被测隔离 Provider 的模拟内容解析器。
    private MockContentResolver mockResolver;

    // 构造绑定 NotePadProvider 与 NotePad.AUTHORITY 的测试用例。
    public NoteBackupFormatTest() {
        super(NotePadProvider.class, NotePad.AUTHORITY);
    }

    // 每个测试方法执行前清空隔离数据库，确保测试数据基线干净。
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mockResolver = getMockContentResolver();
        SQLiteDatabase db = getProvider().getOpenHelperForTest().getWritableDatabase();
        db.delete(NotePad.Notes.TABLE_NAME, null, null);
    }

    // 每个测试方法结束后关闭隔离数据库连接。
    @Override
    protected void tearDown() throws Exception {
        try {
            getProvider().getOpenHelperForTest().close();
        } finally {
            super.tearDown();
        }
    }

    // 验证空库读出、编码与解码链路：空库生成 version=1 且 notes 为空数组的快照。
    public void testEmptyDatabaseRoundTrip() throws Exception {
        NoteBackupFormat.BackupSnapshot snapshot = NoteBackupFormat.createSnapshot(mockResolver);
        assertEquals(NoteBackupFormat.CURRENT_VERSION, snapshot.version);
        assertEquals(0, snapshot.notes.size());

        byte[] encodedBytes = NoteBackupFormat.encodeToUtf8Bytes(snapshot);
        NoteBackupFormat.BackupSnapshot decoded = NoteBackupFormat.decodeFromUtf8Bytes(encodedBytes);
        assertEquals(NoteBackupFormat.CURRENT_VERSION, decoded.version);
        assertEquals(0, decoded.notes.size());
    }

    // 验证同一批含中文、换行、引号、空串及重复项的笔记经过“读出 -> 编码 -> 解码”后四字段完全一致，
    // 且导出的 JSON 仅保留规定字段而不包含本地 _id 或界面偏好。
    public void testProviderReadEncodeDecodeRoundTrip() throws Exception {
        // 准备覆盖中文、多行、双引号/单引号/反斜杠、空字符串以及同名不同正文的多样样本。
        insertNote("中文多行与符号", "第一行：包含\"双引号\"与'单引号'。\n第二行：路径 C:\\notes\\backup.json\n\n第四行：保留空行。",
                1795250000123L, 1795250300456L);
        insertNote("", "", 1795250100000L, 1795250200000L);
        insertNote("同名记录", "正文版本 A", 1795250010000L, 1795250150000L);
        insertNote("同名记录", "正文版本 B", 1795250020000L, 1795250250000L);

        // 1. 从 Provider 独立读出全量笔记并编码为 UTF-8 字节与 JSON 字符串。
        NoteBackupFormat.BackupSnapshot snapshot = NoteBackupFormat.createSnapshot(mockResolver);
        assertEquals(4, snapshot.notes.size());

        String json = NoteBackupFormat.encodeToJson(snapshot);
        byte[] utf8Bytes = NoteBackupFormat.encodeToUtf8Bytes(snapshot);

        // 2. 检查 JSON 结构：顶层仅含 version 与 notes，单条仅含四个约定字段，不含 _id。
        JSONObject root = new JSONObject(json);
        assertEquals(2, root.length());
        assertEquals(1, root.getInt(NoteBackupFormat.KEY_VERSION));
        JSONArray notesArray = root.getJSONArray(NoteBackupFormat.KEY_NOTES);
        assertEquals(4, notesArray.length());
        for (int i = 0; i < notesArray.length(); i++) {
            JSONObject item = notesArray.getJSONObject(i);
            assertEquals(4, item.length());
            assertFalse(item.has(NotePad.Notes._ID));
        }

        // 3. 从 UTF-8 字节流解码并与原始查询结果逐条、逐字段对照。
        NoteBackupFormat.BackupSnapshot decoded = NoteBackupFormat.decodeFromUtf8Bytes(utf8Bytes);
        assertEquals(snapshot.version, decoded.version);
        assertEquals(snapshot.notes.size(), decoded.notes.size());

        List<NoteBackupFormat.NoteRecord> expected = snapshot.notes;
        List<NoteBackupFormat.NoteRecord> actual = decoded.notes;
        for (int i = 0; i < expected.size(); i++) {
            assertEquals(expected.get(i).title, actual.get(i).title);
            assertEquals(expected.get(i).note, actual.get(i).note);
            assertEquals(expected.get(i).created, actual.get(i).created);
            assertEquals(expected.get(i).modified, actual.get(i).modified);
            assertEquals(expected.get(i), actual.get(i));
        }
    }

    // 验证损坏 JSON、非法 UTF-8、未知版本、缺字段与错误字段类型均被拒绝并区分错误码，
    // 且解析失败后不会对数据库产生任何写入或改动。
    public void testRejectInvalidInputsWithoutModifyingDatabase() throws Exception {
        Uri existingUri = insertNote("本地既有笔记", "解析失败时不得修改此记录。",
                1795250001000L, 1795250002000L);

        // 1. 损坏 JSON、空串、顶层数组与尾随垃圾内容。
        assertDecodeFails("", NoteBackupFormat.ErrorCode.INVALID_JSON);
        assertDecodeFails("{broken json", NoteBackupFormat.ErrorCode.INVALID_JSON);
        assertDecodeFails("[]", NoteBackupFormat.ErrorCode.INVALID_JSON);
        assertDecodeFails("{\"version\":1,\"notes\":[]} trailing", NoteBackupFormat.ErrorCode.INVALID_JSON);

        // 2. 非法 UTF-8 字节序列。
        try {
            NoteBackupFormat.decodeFromUtf8Bytes(new byte[] { (byte) 0xFF, (byte) 0xFE, 0x00 });
            fail("Expected BackupFormatException for invalid UTF-8 bytes");
        } catch (NoteBackupFormat.BackupFormatException expected) {
            assertEquals(NoteBackupFormat.ErrorCode.INVALID_UTF8, expected.errorCode);
        }

        // 3. 未知版本号。
        assertDecodeFails("{\"version\":2,\"notes\":[]}", NoteBackupFormat.ErrorCode.UNSUPPORTED_VERSION);
        assertDecodeFails("{\"version\":0,\"notes\":[]}", NoteBackupFormat.ErrorCode.UNSUPPORTED_VERSION);

        // 4. 顶层结构缺失或字段类型错误（含防止 org.json 隐式强转字符串/小数为整数）。
        assertDecodeFails("{\"notes\":[]}", NoteBackupFormat.ErrorCode.INVALID_STRUCTURE);
        assertDecodeFails("{\"version\":\"1\",\"notes\":[]}", NoteBackupFormat.ErrorCode.INVALID_STRUCTURE);
        assertDecodeFails("{\"version\":1.5,\"notes\":[]}", NoteBackupFormat.ErrorCode.INVALID_STRUCTURE);
        assertDecodeFails("{\"version\":1}", NoteBackupFormat.ErrorCode.INVALID_STRUCTURE);
        assertDecodeFails("{\"version\":1,\"notes\":{}}", NoteBackupFormat.ErrorCode.INVALID_STRUCTURE);

        // 5. 单条笔记元素缺失字段或字段类型/时间值非法。
        assertDecodeFails("{\"version\":1,\"notes\":[\"not-an-object\"]}",
                NoteBackupFormat.ErrorCode.INVALID_NOTE_FIELD);
        assertDecodeFails("{\"version\":1,\"notes\":[{\"title\":\"缺正文\",\"created\":1,\"modified\":2}]}",
                NoteBackupFormat.ErrorCode.INVALID_NOTE_FIELD);
        assertDecodeFails("{\"version\":1,\"notes\":[{\"title\":123,\"note\":\"正文\",\"created\":1,\"modified\":2}]}",
                NoteBackupFormat.ErrorCode.INVALID_NOTE_FIELD);
        assertDecodeFails("{\"version\":1,\"notes\":[{\"title\":\"标题\",\"note\":\"正文\",\"created\":\"1000\",\"modified\":2000}]}",
                NoteBackupFormat.ErrorCode.INVALID_NOTE_FIELD);
        assertDecodeFails("{\"version\":1,\"notes\":[{\"title\":\"标题\",\"note\":\"正文\",\"created\":1000.5,\"modified\":2000}]}",
                NoteBackupFormat.ErrorCode.INVALID_NOTE_FIELD);
        assertDecodeFails("{\"version\":1,\"notes\":[{\"title\":\"标题\",\"note\":\"正文\",\"created\":-1,\"modified\":2000}]}",
                NoteBackupFormat.ErrorCode.INVALID_NOTE_FIELD);

        // 核对所有非法解析尝试结束后，数据库仍只有初始的 1 条原样笔记。
        try (Cursor cursor = mockResolver.query(NotePad.Notes.CONTENT_URI, null, null, null, null)) {
            assertNotNull(cursor);
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals("本地既有笔记", cursor.getString(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE)));
            assertEquals("解析失败时不得修改此记录。", cursor.getString(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE)));
            assertEquals(1795250001000L, cursor.getLong(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_CREATE_DATE)));
            assertEquals(1795250002000L, cursor.getLong(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE)));
        }
        assertNotNull(existingUri);
    }

    // 断言指定 JSON 文本解码必然失败且抛出预期的错误码。
    private static void assertDecodeFails(String jsonText, NoteBackupFormat.ErrorCode expectedCode) {
        try {
            NoteBackupFormat.decodeFromJson(jsonText);
            fail("Expected BackupFormatException(" + expectedCode + ") for input: " + jsonText);
        } catch (NoteBackupFormat.BackupFormatException expected) {
            assertEquals(expectedCode, expected.errorCode);
        }
    }

    // 向隔离 Provider 插入一条带固定时间戳的笔记记录。
    private Uri insertNote(String title, String body, long created, long modified) {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, body);
        values.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, created);
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, modified);
        Uri uri = mockResolver.insert(NotePad.Notes.CONTENT_URI, values);
        assertNotNull(uri);
        return uri;
    }
}

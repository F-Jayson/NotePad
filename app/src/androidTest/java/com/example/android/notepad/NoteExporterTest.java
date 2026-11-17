package com.example.android.notepad;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentResolver;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

// NoteExporter 的设备集成测试。
// 在隔离的 NotePadProvider 数据库与临时缓存目录上，验证建议文件名清理、
// 中文多行 UTF-8 导出、同名不同正文区分、覆写截断以及异常场景下源笔记不被破坏。
public class NoteExporterTest extends ProviderTestCase2<NotePadProvider> {

    // 指向被测隔离 Provider 的模拟内容解析器。
    private MockContentResolver mockResolver;
    // 每次测试独立使用的临时输出目录。
    private File tempDir;

    // 构造绑定 NotePadProvider 与 NotePad.AUTHORITY 的测试用例。
    public NoteExporterTest() {
        super(NotePadProvider.class, NotePad.AUTHORITY);
    }

    // 每个测试方法执行前初始化隔离数据库与临时文件输出目录，确保用例间互不干扰。
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mockResolver = getMockContentResolver();
        SQLiteDatabase db = getProvider().getOpenHelperForTest().getWritableDatabase();
        // 隔离测试数据库在不同系统版本上可能被复用，每次测试前清空表数据。
        db.delete(NotePad.Notes.TABLE_NAME, null, null);

        tempDir = new File(getContext().getCacheDir(), "note_exporter_test");
        if (!tempDir.exists()) {
            assertTrue(tempDir.mkdirs());
        }
    }

    // 每个测试方法结束后清理临时导出文件并关闭隔离数据库连接。
    @Override
    protected void tearDown() throws Exception {
        try {
            deleteRecursively(tempDir);
            getProvider().getOpenHelperForTest().close();
        } finally {
            super.tearDown();
        }
    }

    // 验证文件名清理规则：保留中文、过滤非法路径字符、不重复追加 .txt 以及空白与纯点号回退。
    public void testBuildSuggestedFileName() {
        assertEquals("中文会议记录.txt", NoteExporter.buildSuggestedFileName("中文会议记录", "note"));
        assertEquals("周报_2026_第一期.txt",
                NoteExporter.buildSuggestedFileName("周报/2026:第一期", "note"));
        assertEquals("summary.txt", NoteExporter.buildSuggestedFileName("summary.TXT", "note"));
        assertEquals("note.txt", NoteExporter.buildSuggestedFileName("   ", "note"));
        assertEquals("note.txt", NoteExporter.buildSuggestedFileName("...", "note"));
    }

    // 验证包含中文与多行换行的笔记导出后保持“标题、空行、正文”的 UTF-8 格式，且源笔记四字段保持不变。
    public void testExportChineseMultilineNote() throws Exception {
        String title = "中文多行导出样例";
        String body = "第一行：课程实验记录。\n第二行：包含 English 与 123。\n\n第四行：空行之后的正文。";
        Uri noteUri = insertNote(title, body, 1700000001000L, 1700000002000L);

        File outFile = new File(tempDir, "chinese_multiline.txt");
        NoteExporter.exportNoteToUri(mockResolver, noteUri, Uri.fromFile(outFile));

        // 核对输出严格遵循 NotePadProvider.writeDataToPipe 的“标题 + 空行 + 正文”结构。
        String ls = System.lineSeparator();
        String expected = title + ls + ls + body + ls;
        assertEquals(expected, readUtf8File(outFile));

        // 核对导出完成后数据库中的源笔记标题、正文与时间戳未发生改变。
        assertNoteIntact(noteUri, title, body, 1700000001000L, 1700000002000L);
    }

    // 验证两条标题完全相同的笔记按各自单条 URI 导出时，分别输出对应正文且互不混淆。
    public void testExportSameTitleDifferentBody() throws Exception {
        String sharedTitle = "同名导出测试";
        String firstBody = "第一条同名笔记正文\n内容 A";
        String secondBody = "第二条同名笔记正文\n内容 B";

        Uri firstUri = insertNote(sharedTitle, firstBody, 1700000010000L, 1700000011000L);
        Uri secondUri = insertNote(sharedTitle, secondBody, 1700000020000L, 1700000021000L);

        File firstFile = new File(tempDir, "same_title_1.txt");
        File secondFile = new File(tempDir, "same_title_2.txt");

        NoteExporter.exportNoteToUri(mockResolver, firstUri, Uri.fromFile(firstFile));
        NoteExporter.exportNoteToUri(mockResolver, secondUri, Uri.fromFile(secondFile));

        String ls = System.lineSeparator();
        assertEquals(sharedTitle + ls + ls + firstBody + ls, readUtf8File(firstFile));
        assertEquals(sharedTitle + ls + ls + secondBody + ls, readUtf8File(secondFile));
    }

    // 验证覆盖一个原本更长的旧文件时，目标文件被正确截断而不会残留旧尾部内容。
    public void testExportTruncatesExistingTargetFile() throws Exception {
        Uri noteUri = insertNote("短笔记", "短内容", 1700000030000L, 1700000031000L);
        File targetFile = new File(tempDir, "overwrite.txt");
        // 先写入较长的旧数据，模拟覆盖已有文档的场景。
        try (FileOutputStream fos = new FileOutputStream(targetFile)) {
            fos.write("这是一段很长很长的旧文件内容，用于检查覆盖写入后是否存在尾部残留。".getBytes(StandardCharsets.UTF_8));
        }

        NoteExporter.exportNoteToUri(mockResolver, noteUri, Uri.fromFile(targetFile));

        String ls = System.lineSeparator();
        assertEquals("短笔记" + ls + ls + "短内容" + ls, readUtf8File(targetFile));
    }

    // 验证源笔记缺失、URI 类型不支持以及目标不可写时都会抛出 IOException，且不损坏源笔记。
    public void testExportFailurePreservesSourceNote() throws Exception {
        String title = "失败保护样例";
        String body = "导出失败后这条笔记必须保持原样。";
        Uri noteUri = insertNote(title, body, 1700000040000L, 1700000041000L);

        // 场景 1：指向不存在的笔记 ID，应在打开源流阶段直接失败，不创建目标文件。
        Uri missingNoteUri = Uri.withAppendedPath(NotePad.Notes.CONTENT_ID_URI_BASE, "999999");
        File unusedFile = new File(tempDir, "missing_note.txt");
        try {
            NoteExporter.exportNoteToUri(mockResolver, missingNoteUri, Uri.fromFile(unusedFile));
            fail("Expected IOException for missing note URI");
        } catch (IOException expected) {
            assertFalse(unusedFile.exists());
        }

        // 场景 2：集合 URI 不支持单条 text/plain 流，应拒绝导出且不创建目标文件。
        try {
            NoteExporter.exportNoteToUri(mockResolver, NotePad.Notes.CONTENT_URI, Uri.fromFile(unusedFile));
            fail("Expected IOException for collection URI");
        } catch (IOException expected) {
            assertFalse(unusedFile.exists());
        }

        // 场景 3：目标 URI 指向目录，无法作为可写文件打开，应抛出 IOException。
        try {
            NoteExporter.exportNoteToUri(mockResolver, noteUri, Uri.fromFile(tempDir));
            fail("Expected IOException when target URI is unwritable");
        } catch (IOException expected) {
            // 预期失败
        }

        // 核对连续异常后源笔记在数据库中仍保持完整。
        assertNoteIntact(noteUri, title, body, 1700000040000L, 1700000041000L);
    }

    // 向隔离 Provider 插入一条带固定时间戳的测试笔记并返回其单条 URI。
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

    // 断言数据库中指定单条笔记的标题、正文、创建与修改时间均与预期完全一致。
    private void assertNoteIntact(Uri noteUri, String title, String body, long created, long modified) {
        try (Cursor cursor = mockResolver.query(noteUri, null, null, null, null)) {
            assertNotNull(cursor);
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals(title, cursor.getString(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE)));
            assertEquals(body, cursor.getString(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE)));
            assertEquals(created, cursor.getLong(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_CREATE_DATE)));
            assertEquals(modified, cursor.getLong(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE)));
        }
    }

    // 按 UTF-8 编码完整读取文件内容为字符串，用于核对导出结果。
    private static String readUtf8File(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file);
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[4096];
            int len;
            while ((len = in.read(buffer)) != -1) {
                out.write(buffer, 0, len);
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    // 递归删除测试过程中创建的临时文件或目录。
    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteRecursively(child);
            }
        }
        file.delete();
    }
}

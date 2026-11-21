package com.example.android.notepad;

import android.content.ContentUris;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;
import android.os.Bundle;
import android.test.ProviderTestCase2;
import android.test.mock.MockContentResolver;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

// 整本云恢复事务合并逻辑的设备集成测试。
// 在隔离的 NotePadProvider 数据库上验证：补入缺失项并保留本地额外笔记与原 ID、
// 重复恢复幂等（新增 0 条）、同名异文与完全相同的多条记录按数量补齐、空备份与非法文件保护，
// 以及事务中途写入失败时的整批回滚。
public class NoteRestoreTest extends ProviderTestCase2<NotePadProvider> {

    private MockContentResolver mockResolver;
    private SQLiteDatabase db;

    // 构造绑定 NotePadProvider 与 NotePad.AUTHORITY 的测试用例。
    public NoteRestoreTest() {
        super(NotePadProvider.class, NotePad.AUTHORITY);
    }

    // 每个测试方法执行前清空隔离数据库，确保测试数据独立。
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mockResolver = getMockContentResolver();
        db = getProvider().getOpenHelperForTest().getWritableDatabase();
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

    // 验证经典合并场景：备份含 A、B，本地含 B、C；恢复后应有 A、B、C 且保留原时间与本地 ID，
    // 再次恢复同一备份时新增应为 0。
    public void testRestoreMergesMissingNotesAndIsIdempotent() throws Exception {
        NoteBackupFormat.NoteRecord noteA = new NoteBackupFormat.NoteRecord(
                "云端笔记 A", "仅存在于云端备份的正文\n含多行中文。", 1795250001000L, 1795250005000L);
        NoteBackupFormat.NoteRecord noteB = new NoteBackupFormat.NoteRecord(
                "共同笔记 B", "本地与云端都已存在的笔记。", 1795250002000L, 1795250006000L);
        NoteBackupFormat.NoteRecord noteC = new NoteBackupFormat.NoteRecord(
                "本地独有 C", "仅存在于本地设备的笔记。", 1795250003000L, 1795250007000L);

        // 先在本地插入 B 与 C，并记录各自的本机行 ID。
        long localIdB = ContentUris.parseId(insertNote(noteB));
        long localIdC = ContentUris.parseId(insertNote(noteC));

        NoteBackupFormat.BackupSnapshot backup = new NoteBackupFormat.BackupSnapshot(
                NoteBackupFormat.CURRENT_VERSION, Arrays.asList(noteA, noteB));

        // 首次恢复：应仅补入 A，跳过 B。
        NoteBackupFormat.RestoreResult firstResult =
                NoteBackupFormat.restoreMissingNotes(mockResolver, backup);
        assertEquals(1, firstResult.restoredCount);
        assertEquals(1, firstResult.skippedCount);
        assertEquals(2, firstResult.totalBackupCount);

        // 查询全部笔记（默认 modified DESC 排序：C -> B -> A），核对数量、内容与原始时间戳。
        List<NoteBackupFormat.NoteRecord> allAfterFirst = NoteBackupFormat.queryAllNotes(mockResolver);
        assertEquals(3, allAfterFirst.size());
        assertEquals(noteC, allAfterFirst.get(0));
        assertEquals(noteB, allAfterFirst.get(1));
        assertEquals(noteA, allAfterFirst.get(2));

        // 确认本地原有记录 B、C 的行 ID 与内容未被篡改，新增记录 A 获得了独立的本机 ID。
        assertNoteAtId(localIdB, noteB);
        assertNoteAtId(localIdC, noteC);
        try (Cursor cursor = mockResolver.query(
                NotePad.Notes.CONTENT_URI,
                new String[] { NotePad.Notes._ID },
                NotePad.Notes.COLUMN_NAME_TITLE + " = ?",
                new String[] { noteA.title },
                null)) {
            assertNotNull(cursor);
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            long restoredIdA = cursor.getLong(0);
            assertTrue(restoredIdA > 0L);
            assertTrue(restoredIdA != localIdB && restoredIdA != localIdC);
        }

        // 重复恢复同一备份：新增应为 0，总条数仍为 3。
        NoteBackupFormat.RestoreResult secondResult =
                NoteBackupFormat.restoreMissingNotes(mockResolver, backup);
        assertEquals(0, secondResult.restoredCount);
        assertEquals(2, secondResult.skippedCount);
        assertEquals(3, NoteBackupFormat.queryAllNotes(mockResolver).size());
    }

    // 验证同名异文笔记都会被保留，且完全相同的多条记录按缺失数量精准补齐。
    public void testRestoreSameTitleDifferentBodyAndDuplicateIdenticalNotes() throws Exception {
        NoteBackupFormat.NoteRecord sameTitle1 = new NoteBackupFormat.NoteRecord(
                "同名会议", "第一条正文", 1795250010000L, 1795250011000L);
        NoteBackupFormat.NoteRecord sameTitle2 = new NoteBackupFormat.NoteRecord(
                "同名会议", "第二条不同正文", 1795250010000L, 1795250011000L);
        NoteBackupFormat.NoteRecord duplicateNote = new NoteBackupFormat.NoteRecord(
                "完全相同记录", "四字段全部一致", 1795250020000L, 1795250021000L);

        // 本地先有 1 条 sameTitle1 和 1 条 duplicateNote。
        insertNote(sameTitle1);
        insertNote(duplicateNote);

        // 备份包含 sameTitle1、sameTitle2 以及 2 条完全相同的 duplicateNote。
        NoteBackupFormat.BackupSnapshot backup = new NoteBackupFormat.BackupSnapshot(
                NoteBackupFormat.CURRENT_VERSION,
                Arrays.asList(sameTitle1, sameTitle2, duplicateNote, duplicateNote));

        NoteBackupFormat.RestoreResult result =
                NoteBackupFormat.restoreMissingNotes(mockResolver, backup);
        // 应跳过 1 条 sameTitle1 与 1 条 duplicateNote，补入 sameTitle2 与第 2 条 duplicateNote。
        assertEquals(2, result.restoredCount);
        assertEquals(2, result.skippedCount);

        List<NoteBackupFormat.NoteRecord> all = NoteBackupFormat.queryAllNotes(mockResolver);
        assertEquals(4, all.size());
        assertEquals(1, countOccurrences(all, sameTitle1));
        assertEquals(1, countOccurrences(all, sameTitle2));
        assertEquals(2, countOccurrences(all, duplicateNote));

        // 再次导入同一备份，四条均已满足数量，新增必须为 0。
        NoteBackupFormat.RestoreResult repeatResult =
                NoteBackupFormat.restoreMissingNotes(mockResolver, backup);
        assertEquals(0, repeatResult.restoredCount);
        assertEquals(4, repeatResult.skippedCount);
    }

    // 验证空备份与直接传入非法备份 JSON 时不破坏本地已有笔记。
    public void testRestoreEmptyAndInvalidPayloadsKeepLocalIntact() throws Exception {
        NoteBackupFormat.NoteRecord localNote = new NoteBackupFormat.NoteRecord(
                "本地保留笔记", "不可被空备份或无效文件破坏。", 1795250030000L, 1795250031000L);
        long localId = ContentUris.parseId(insertNote(localNote));

        // 1. 空备份恢复应正常完成且新增 0 条。
        NoteBackupFormat.BackupSnapshot emptyBackup = new NoteBackupFormat.BackupSnapshot(
                NoteBackupFormat.CURRENT_VERSION, Collections.emptyList());
        NoteBackupFormat.RestoreResult emptyResult =
                NoteBackupFormat.restoreMissingNotes(mockResolver, emptyBackup);
        assertEquals(0, emptyResult.restoredCount);
        assertEquals(0, emptyResult.skippedCount);
        assertEquals(1, NoteBackupFormat.queryAllNotes(mockResolver).size());
        assertNoteAtId(localId, localNote);

        // 2. 直接向 Provider 传入损坏 JSON 与未知版本，应拒绝写库并保持本地完好。
        assertProviderRejectsRawJson("{corrupt");
        assertProviderRejectsRawJson("{\"version\":99,\"notes\":[]}");
        assertEquals(1, NoteBackupFormat.queryAllNotes(mockResolver).size());
        assertNoteAtId(localId, localNote);
    }

    // 验证单事务写入中途遇到 SQLite 插入失败时整批回滚，已先插入的同批记录一并撤销且本地原数据完好。
    public void testRestoreRollsBackWholeBatchOnMidTransactionFailure() throws Exception {
        NoteBackupFormat.NoteRecord localNote = new NoteBackupFormat.NoteRecord(
                "原有本地笔记", "回滚后必须保持完好。", 1795250040000L, 1795250041000L);
        long localId = ContentUris.parseId(insertNote(localNote));

        NoteBackupFormat.NoteRecord firstNew = new NoteBackupFormat.NoteRecord(
                "先写入的正常笔记", "若事务未回滚会残留此条。", 1795250042000L, 1795250043000L);
        NoteBackupFormat.NoteRecord failingNew = new NoteBackupFormat.NoteRecord(
                "触发回滚的笔记", "由测试触发器模拟写入中断。", 1795250044000L, 1795250045000L);
        NoteBackupFormat.NoteRecord trailingNew = new NoteBackupFormat.NoteRecord(
                "后续笔记", "不应被写入。", 1795250046000L, 1795250047000L);

        // 创建临时 SQLite BEFORE INSERT 触发器，在第 2 条新记录插入时真实触发事务中止。
        db.execSQL("CREATE TEMP TRIGGER fail_mid_restore BEFORE INSERT ON "
                + NotePad.Notes.TABLE_NAME
                + " WHEN NEW." + NotePad.Notes.COLUMN_NAME_TITLE + " = '触发回滚的笔记'"
                + " BEGIN SELECT RAISE(ABORT, 'simulated_mid_tx_failure'); END;");

        try {
            NoteBackupFormat.BackupSnapshot backup = new NoteBackupFormat.BackupSnapshot(
                    NoteBackupFormat.CURRENT_VERSION, Arrays.asList(firstNew, failingNew, trailingNew));
            try {
                NoteBackupFormat.restoreMissingNotes(mockResolver, backup);
                fail("Expected IOException when SQLite aborts mid-transaction");
            } catch (IOException expected) {
                // 预期捕获到事务失败异常。
            }
        } finally {
            db.execSQL("DROP TRIGGER IF EXISTS fail_mid_restore;");
        }

        // 核对第 1 条已插入的“先写入的正常笔记”已随整批回滚撤销，数据库仍只剩原有本地笔记。
        List<NoteBackupFormat.NoteRecord> remaining = NoteBackupFormat.queryAllNotes(mockResolver);
        assertEquals(1, remaining.size());
        assertEquals(localNote, remaining.get(0));
        assertNoteAtId(localId, localNote);
    }

    // 断言 Provider.call 拒绝非法原始备份 JSON。
    private void assertProviderRejectsRawJson(String rawJson) {
        Bundle extras = new Bundle(1);
        extras.putString(NotePadProvider.EXTRA_BACKUP_JSON, rawJson);
        try {
            mockResolver.call(
                    NotePad.Notes.CONTENT_URI,
                    NotePadProvider.METHOD_RESTORE_MISSING_NOTES,
                    null,
                    extras);
            fail("Expected IllegalArgumentException for invalid raw backup JSON: " + rawJson);
        } catch (IllegalArgumentException expected) {
            // 预期拒绝。
        }
    }

    // 向隔离 Provider 插入一条测试笔记并返回单条 URI。
    private Uri insertNote(NoteBackupFormat.NoteRecord record) {
        ContentValues values = record.toContentValues();
        Uri uri = mockResolver.insert(NotePad.Notes.CONTENT_URI, values);
        assertNotNull(uri);
        return uri;
    }

    // 按本机行 ID 断言该记录的四项业务字段完整未变。
    private void assertNoteAtId(long id, NoteBackupFormat.NoteRecord expected) {
        Uri uri = ContentUris.withAppendedId(NotePad.Notes.CONTENT_ID_URI_BASE, id);
        try (Cursor cursor = mockResolver.query(uri, null, null, null, null)) {
            assertNotNull(cursor);
            assertEquals(1, cursor.getCount());
            assertTrue(cursor.moveToFirst());
            assertEquals(expected.title, cursor.getString(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE)));
            assertEquals(expected.note, cursor.getString(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_NOTE)));
            assertEquals(expected.created, cursor.getLong(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_CREATE_DATE)));
            assertEquals(expected.modified, cursor.getLong(cursor.getColumnIndex(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE)));
        }
    }

    // 统计目标记录在列表中按四字段相等出现的次数。
    private static int countOccurrences(
            List<NoteBackupFormat.NoteRecord> list, NoteBackupFormat.NoteRecord target) {
        int count = 0;
        for (NoteBackupFormat.NoteRecord item : list) {
            if (target.equals(item)) {
                count++;
            }
        }
        return count;
    }
}

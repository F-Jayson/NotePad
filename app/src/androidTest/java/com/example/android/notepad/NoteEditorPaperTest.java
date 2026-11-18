package com.example.android.notepad;

import android.app.Instrumentation;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.test.ActivityInstrumentationTestCase2;
import android.text.Editable;
import android.view.Window;
import android.widget.EditText;

/** 用专属样本验证换纸不丢草稿、选区、滚动位置，也不提前写库。 */
public class NoteEditorPaperTest extends ActivityInstrumentationTestCase2<NoteEditor> {
    private Context context;
    private Uri note;
    private boolean previousPaper;

    public NoteEditorPaperTest() {
        super(NoteEditor.class);
    }

    public void testPaperChangeKeepsEditingState() throws Throwable {
        context = getInstrumentation().getTargetContext();
        previousPaper = ThemeHelper.usesWarmPaper(context);
        StringBuilder text = new StringBuilder();
        for (int i = 1; i <= 140; i++) {
            text.append("第").append(i).append("段：记录今天的观察。\n");
        }
        String saved = text.toString();
        long originalTime = 1730000000000L;
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_TITLE, "T05 选区与滚动测试");
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, saved);
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, originalTime);
        note = context.getContentResolver().insert(NotePad.Notes.CONTENT_URI, values);
        setActivityIntent(new Intent(Intent.ACTION_EDIT, note));

        NoteEditor activity = getActivity();
        EditText editor = activity.findViewById(R.id.note);
        int start = saved.indexOf("第100段");
        // 在中后部选择一段文字，并保留一处尚未保存的修改。
        runTestOnUiThread(() -> {
            editor.requestFocus();
            editor.append("未保存草稿");
            editor.setSelection(start, start + 5);
            editor.bringPointIntoView(start + 5);
        });
        getInstrumentation().waitForIdleSync();
        runTestOnUiThread(() -> {
            Editable buffer = editor.getText();
            int scroll = editor.getScrollY();
            // 系统栏和换纸都不应覆盖正文的页边距。
            int margin = editor.getResources().getDimensionPixelSize(R.dimen.np_page_margin);
            assertEquals(margin, editor.getPaddingStart());
            assertEquals(margin, editor.getPaddingEnd());
            assertTrue("长文应已滚动", scroll > 0);
            int choice = previousPaper ? R.id.paper_white : R.id.paper_warm;
            assertTrue(activity.getWindow().performPanelIdentifierAction(Window.FEATURE_OPTIONS_PANEL, choice, 0));
            assertEquals(!previousPaper, ThemeHelper.usesWarmPaper(activity));
            assertSame(buffer, editor.getText());
            assertEquals(saved + "未保存草稿", buffer.toString());
            assertEquals(start, editor.getSelectionStart());
            assertEquals(start + 5, editor.getSelectionEnd());
            assertEquals(scroll, editor.getScrollY());
            assertEquals(margin, editor.getPaddingStart());
            assertEquals(margin, editor.getPaddingEnd());
        });
        // 草稿仅在原有保存流程中写入，换纸时数据库仍是初始值。
        String[] fields = { NotePad.Notes.COLUMN_NAME_NOTE, NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE };
        try (Cursor row = context.getContentResolver().query(note, fields, null, null, null)) {
            assertTrue(row.moveToFirst());
            assertEquals(saved, row.getString(0));
            assertEquals(originalTime, row.getLong(1));
        }
    }

    @Override
    protected void tearDown() throws Exception {
        // 旧测试框架会清空测试对象的字段，先保存清理所需的引用。
        Uri testNote = note;
        Context target = context;
        boolean originalPaper = previousPaper;
        Instrumentation runner = getInstrumentation();
        try {
            super.tearDown();
        } finally {
            // 仅清理本测试创建的 URI，并恢复运行前的全局纸张选择。
            if (testNote != null) {
                runner.waitForIdleSync();
                target.getContentResolver().delete(testNote, null, null);
                ThemeHelper.setWarmPaper(target, originalPaper);
            }
        }
    }
}

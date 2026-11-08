package com.example.android.notepad;

import android.app.Activity;
import android.app.Instrumentation;
import android.app.ListActivity;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.test.InstrumentationTestCase;
import android.view.MenuItem;
import android.view.View;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import java.lang.reflect.Proxy;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * 列表时间只在标题或正文真正变化时更新，并按设备时区显示到分钟。
 * 样本标题都带 {@link #PREFIX}，清理时只删这些行，不碰设备上原有的笔记。
 */
public class NotePadTimestampTest extends InstrumentationTestCase {

    private static final String PREFIX = "T1-timestamp-";
    /** 固定的旧时间，避免和“刚刚修改”落在同一毫秒。 */
    private static final long CREATED = 1600000000000L;
    private static final long MODIFIED = 1700000000000L;

    private Instrumentation mInstr;
    private ContentResolver mResolver;
    private final ArrayList<Uri> mNotes = new ArrayList<>();
    private final ArrayList<Activity> mOpened = new ArrayList<>();
    /** 每个用例第一次启动时清掉上一用例留下的页面。 */
    private boolean mFreshTask;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mInstr = getInstrumentation();
        mResolver = mInstr.getTargetContext().getContentResolver();
        mFreshTask = true;
        deleteSamples();
    }

    @Override
    protected void tearDown() throws Exception {
        for (int i = mOpened.size() - 1; i >= 0; i--) {
            finish(mOpened.get(i));
        }
        deleteSamples();
        super.tearDown();
    }

    /** 新建笔记保留插入时的 created；再次打开并保存不得刷新 modified。 */
    public void testCreateAndResave() throws Exception {
        long before = maxId();
        Activity editor = start(NoteEditor.class, Intent.ACTION_INSERT, NotePad.Notes.CONTENT_URI);
        long id = maxId();
        assertTrue(id > before);
        Uri uri = track(id);

        Row inserted = load(uri);
        waitForText(editor, R.id.note, "");
        setText(editor, R.id.note, PREFIX + "create");
        Thread.sleep(30); // 留出时间，使保存后的 modified 能大于插入时的值
        selectMenu(editor, R.id.menu_save);

        Row saved = load(uri);
        assertEquals(inserted.created, saved.created);
        assertTrue(saved.modified > inserted.modified);
        assertEquals(PREFIX + "create", saved.title);
        assertEquals(PREFIX + "create", saved.note);

        editor = start(NoteEditor.class, Intent.ACTION_EDIT, uri);
        waitForText(editor, R.id.note, PREFIX + "create");
        selectMenu(editor, R.id.menu_save);
        Row again = load(uri);
        assertEquals(saved.created, again.created);
        assertEquals(saved.modified, again.modified);
    }

    /** 只查看不改时间。改正文只更新 modified，标题和 created 保持原样。 */
    public void testViewAndBodyEdit() throws Exception {
        Uri uri = insert(PREFIX + "body", PREFIX + "old", CREATED, MODIFIED);

        Activity editor = start(NoteEditor.class, Intent.ACTION_EDIT, uri);
        waitForText(editor, R.id.note, PREFIX + "old");
        finish(editor);
        assertStamp(uri, CREATED, MODIFIED);

        editor = start(NoteEditor.class, Intent.ACTION_EDIT, uri);
        waitForText(editor, R.id.note, PREFIX + "old");
        setText(editor, R.id.note, PREFIX + "new");
        finish(editor);
        long modified = assertModifiedMoved(uri, CREATED, MODIFIED);
        Row edited = load(uri);
        assertEquals(PREFIX + "body", edited.title);
        assertEquals(PREFIX + "new", edited.note);

        // 点保存会先写一次，退出时 onPause 再写一次；内容未变，时间停住。
        editor = start(NoteEditor.class, Intent.ACTION_EDIT, uri);
        waitForText(editor, R.id.note, PREFIX + "new");
        selectMenu(editor, R.id.menu_save);
        assertStamp(uri, CREATED, modified);
    }

    /** 标题没改不写库。改标题才更新 modified，正文和 created 不动。 */
    public void testTitleEdit() throws Exception {
        Uri uri = insert(PREFIX + "title", PREFIX + "body", CREATED, MODIFIED);

        Activity editor = start(TitleEditor.class, TitleEditor.EDIT_TITLE_ACTION, uri);
        waitForText(editor, R.id.title, PREFIX + "title");
        finish(editor);
        assertStamp(uri, CREATED, MODIFIED);

        editor = start(TitleEditor.class, TitleEditor.EDIT_TITLE_ACTION, uri);
        waitForText(editor, R.id.title, PREFIX + "title");
        setText(editor, R.id.title, PREFIX + "renamed");
        click(editor, R.id.ok);
        Row renamed = load(uri);
        assertEquals(PREFIX + "renamed", renamed.title);
        long modified = assertModifiedMoved(uri, CREATED, MODIFIED);
        assertEquals(PREFIX + "body", renamed.note);

        editor = start(TitleEditor.class, TitleEditor.EDIT_TITLE_ACTION, uri);
        waitForText(editor, R.id.title, PREFIX + "renamed");
        click(editor, R.id.ok);
        assertStamp(uri, CREATED, modified);
    }

    /**
     * 撤销只认这一次打开时的正文。没改就撤销，时间和正文都不变。
     * 同一次编辑里先自动保存再撤销，正文回到打开时的内容，modified 才更新。
     */
    public void testRevert() throws Exception {
        Uri uri = insert(PREFIX + "revert", PREFIX + "body", CREATED, MODIFIED);
        Activity editor = start(NoteEditor.class, Intent.ACTION_EDIT, uri);
        waitForText(editor, R.id.note, PREFIX + "body");
        selectMenu(editor, R.id.menu_revert);
        assertStamp(uri, CREATED, MODIFIED);
        assertEquals(PREFIX + "body", load(uri).note);

        editor = start(NoteEditor.class, Intent.ACTION_EDIT, uri);
        waitForText(editor, R.id.note, PREFIX + "body");
        setText(editor, R.id.note, PREFIX + "edited");
        // 不能关掉编辑页：重新打开后，撤销基准会变成已经保存的新正文。
        finish(start(NotesList.class, Intent.ACTION_MAIN, null));
        waitForText(editor, R.id.note, PREFIX + "edited");
        long editedAt = assertModifiedMoved(uri, CREATED, MODIFIED);

        selectMenu(editor, R.id.menu_revert);
        assertEquals(PREFIX + "body", load(uri).note);
        assertModifiedMoved(uri, CREATED, editedAt);
    }

    /** 列表显示设备时区的 yyyy-MM-dd HH:mm。改正文后按 modified 而不是 created 提前。 */
    public void testListTimeAndOrder() throws Exception {
        long older = System.currentTimeMillis() - 86400000L;
        long newer = System.currentTimeMillis() - 1000L;
        // created 故意比另一条更大。若按创建时间排序，它会排到前面。
        Uri oldNote = insert(PREFIX + "old", PREFIX + "old-body", 9000000000000L, older);
        Uri newNote = insert(PREFIX + "new", PREFIX + "new-body", CREATED, newer);

        ListActivity list = (ListActivity) start(NotesList.class, Intent.ACTION_MAIN, null);
        waitForList(list);
        int newPos = positionOf(list, PREFIX + "new");
        int oldPos = positionOf(list, PREFIX + "old");
        assertTrue(newPos >= 0 && newPos < oldPos);
        String shown = modifiedTextAt(list, newPos);
        assertEquals(format(newer, TimeZone.getDefault()), shown);
        if (TimeZone.getDefault().getOffset(newer) != 0) {
            assertFalse(shown.equals(format(newer, TimeZone.getTimeZone("UTC"))));
        }

        Activity editor = start(NoteEditor.class, Intent.ACTION_EDIT, oldNote);
        waitForText(editor, R.id.note, PREFIX + "old-body");
        setText(editor, R.id.note, PREFIX + "old-edited");
        selectMenu(editor, R.id.menu_save);
        // 列表通过 Cursor 通知刷新，可能比保存返回晚一拍。
        int oldAfter = -1;
        int newAfter = -1;
        for (int i = 0; i < 20; i++) {
            oldAfter = positionOf(list, PREFIX + "old");
            newAfter = positionOf(list, PREFIX + "new");
            if (oldAfter >= 0 && oldAfter < newAfter) {
                break;
            }
            Thread.sleep(50);
        }
        assertTrue(oldAfter >= 0 && oldAfter < newAfter);
        long editedAt = assertModifiedMoved(oldNote, 9000000000000L, newer);
        assertEquals(format(editedAt, TimeZone.getDefault()), modifiedTextAt(list, positionOf(list, PREFIX + "old")));
        assertStamp(newNote, CREATED, newer);
    }

    private Uri insert(String title, String note, long created, long modified) {
        ContentValues values = new ContentValues();
        values.put(NotePad.Notes.COLUMN_NAME_TITLE, title);
        values.put(NotePad.Notes.COLUMN_NAME_NOTE, note);
        values.put(NotePad.Notes.COLUMN_NAME_CREATE_DATE, created);
        values.put(NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, modified);
        Uri uri = mResolver.insert(NotePad.Notes.CONTENT_URI, values);
        assertNotNull(uri);
        mNotes.add(uri);
        return uri;
    }

    private Uri track(long id) {
        Uri uri = ContentUris.withAppendedId(NotePad.Notes.CONTENT_URI, id);
        mNotes.add(uri);
        return uri;
    }

    private void deleteSamples() {
        for (Uri uri : mNotes) {
            mResolver.delete(uri, null, null);
        }
        mNotes.clear();
        mResolver.delete(NotePad.Notes.CONTENT_URI,
                NotePad.Notes.COLUMN_NAME_TITLE + " LIKE ?", new String[] {PREFIX + "%"});
    }

    private long maxId() {
        Cursor cursor = mResolver.query(NotePad.Notes.CONTENT_URI,
                new String[] {NotePad.Notes._ID}, null, null, NotePad.Notes._ID + " DESC");
        assertNotNull(cursor);
        long id = cursor.moveToFirst() ? cursor.getLong(0) : 0;
        cursor.close();
        return id;
    }

    private Row load(Uri uri) {
        Cursor cursor = mResolver.query(uri, new String[] {
                NotePad.Notes.COLUMN_NAME_CREATE_DATE,
                NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
                NotePad.Notes.COLUMN_NAME_TITLE,
                NotePad.Notes.COLUMN_NAME_NOTE
        }, null, null, null);
        assertTrue(cursor != null && cursor.moveToFirst());
        Row row = new Row();
        row.created = cursor.getLong(0);
        row.modified = cursor.getLong(1);
        row.title = cursor.getString(2);
        row.note = cursor.getString(3);
        cursor.close();
        return row;
    }

    private void assertStamp(Uri uri, long created, long modified) {
        Row row = load(uri);
        assertEquals(created, row.created);
        assertEquals(modified, row.modified);
    }

    private long assertModifiedMoved(Uri uri, long created, long previous) {
        Row row = load(uri);
        assertEquals(created, row.created);
        assertTrue(row.modified > previous);
        return row.modified;
    }

    private static String format(long millis, TimeZone zone) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US);
        format.setTimeZone(zone);
        return format.format(new Date(millis));
    }

    private Activity start(Class<?> type, String action, Uri data) {
        Intent intent = new Intent(action, data);
        intent.setClassName("com.example.android.notepad", type.getName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (mFreshTask) {
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
            mFreshTask = false;
        }
        Activity activity = mInstr.startActivitySync(intent);
        mInstr.waitForIdleSync();
        mOpened.add(activity);
        return activity;
    }

    private void waitForText(Activity activity, int viewId, String expected) throws Exception {
        String current = null;
        for (int i = 0; i < 40 && !expected.equals(current); i++) {
            current = textOf(activity, viewId);
            if (!expected.equals(current)) {
                Thread.sleep(50);
            }
        }
        assertEquals(expected, current);
    }

    private String textOf(final Activity activity, final int viewId) {
        final String[] holder = new String[1];
        mInstr.runOnMainSync(() -> {
            EditText view = (EditText) activity.findViewById(viewId);
            holder[0] = view == null || view.getText() == null ? null : view.getText().toString();
        });
        return holder[0];
    }

    private void setText(Activity activity, int viewId, String value) {
        onMain(() -> ((EditText) activity.findViewById(viewId)).setText(value));
    }

    private void click(Activity activity, int viewId) {
        onMain(() -> activity.findViewById(viewId).performClick());
    }

    /**
     * 直接调用菜单处理。MenuItem 没有可用的构造方法，代理只返回被测代码会读的 id。
     */
    private void selectMenu(Activity activity, int itemId) {
        MenuItem item = (MenuItem) Proxy.newProxyInstance(
                MenuItem.class.getClassLoader(),
                new Class<?>[] {MenuItem.class},
                (proxy, method, args) -> {
                    if ("getItemId".equals(method.getName())) {
                        return itemId;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == int.class) return 0;
                    if (type == char.class) return '\0';
                    return null;
                });
        onMain(() -> activity.onOptionsItemSelected(item));
    }

    private void finish(Activity activity) {
        if (activity == null) {
            return;
        }
        onMain(() -> {
            if (!activity.isFinishing()) {
                activity.finish();
            }
        });
    }

    private void onMain(Runnable action) {
        mInstr.runOnMainSync(action);
        mInstr.waitForIdleSync();
    }

    private void waitForList(ListActivity activity) throws Exception {
        for (int i = 0; i < 40 && count(activity) < 2; i++) {
            Thread.sleep(50);
        }
        assertTrue(count(activity) >= 2);
    }

    private int count(ListActivity activity) {
        int[] count = new int[1];
        mInstr.runOnMainSync(() -> count[0] = activity.getListView().getCount());
        return count[0];
    }

    private int positionOf(ListActivity activity, String title) {
        int[] position = new int[] {-1};
        mInstr.runOnMainSync(() -> {
            ListView list = activity.getListView();
            for (int i = 0; i < list.getCount(); i++) {
                Cursor row = (Cursor) list.getItemAtPosition(i);
                if (title.equals(row.getString(row.getColumnIndex(NotePad.Notes.COLUMN_NAME_TITLE)))) {
                    position[0] = i;
                    return;
                }
            }
        });
        return position[0];
    }

    private String modifiedTextAt(ListActivity activity, int position) {
        String[] text = new String[1];
        mInstr.runOnMainSync(() -> {
            ListView list = activity.getListView();
            View row = list.getAdapter().getView(position, null, list);
            text[0] = ((TextView) row.findViewById(R.id.note_modified)).getText().toString();
        });
        return text[0];
    }

    private static final class Row {
        long created;
        long modified;
        String title;
        String note;
    }
}

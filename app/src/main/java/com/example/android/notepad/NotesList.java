/*
 * Copyright (C) 2007 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example.android.notepad;

import com.example.android.notepad.NotePad;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Pattern;

import android.app.ListActivity;
import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.DateUtils;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ContextMenu.ContextMenuInfo;
import android.widget.AdapterView;
import android.widget.ListView;
import android.widget.SearchView;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.widget.Toast;


/**
 * Displays a list of notes. Will display notes from the {@link Uri}
 * provided in the incoming Intent if there is one, otherwise it defaults to displaying the
 * contents of the {@link NotePadProvider}.
 *
 * NOTE: Notice that the provider operations in this Activity are taking place on the UI thread.
 * This is not a good practice. It is only done here to make the code more readable. A real
 * application should use the {@link android.content.AsyncQueryHandler} or
 * {@link android.os.AsyncTask} object to perform operations asynchronously on a separate thread.
 */
public class NotesList extends ListActivity {

    // For logging and debugging
    private static final String TAG = "NotesList";

    /**
     * The columns needed by the cursor adapter
     */
    private static final String[] PROJECTION = new String[] {
            NotePad.Notes._ID, // 0
            NotePad.Notes.COLUMN_NAME_TITLE, // 1
            NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE, // 追加时间列，保留标题列的索引。
    };

    /** The index of the title column */
    private static final int COLUMN_INDEX_TITLE = 1;

    // 使用参数绑定传递搜索词，两个 LIKE 条件共同作用于当前 URI 的结果。
    private static final String SEARCH_SELECTION =
            "(" + NotePad.Notes.COLUMN_NAME_TITLE + " LIKE ? ESCAPE '\\' OR "
                    + NotePad.Notes.COLUMN_NAME_NOTE + " LIKE ? ESCAPE '\\')";
    // 将全角空格等 Unicode 分隔符也视为纯空白输入。
    private static final Pattern BLANK_QUERY = Pattern.compile("[\\s\\p{Z}]*");
    private static final String STATE_SEARCH_QUERY = "searchQuery";
    // 导出单条笔记的系统文件创建器请求码与状态恢复键。
    private static final int REQUEST_CODE_EXPORT_NOTE = 1001;
    private static final String STATE_PENDING_EXPORT_URI = "pendingExportUri";
    // 使用独立的单线程执行器在后台复制纯文本流，避免阻塞界面线程或与 Provider 管道线程争用队列。
    private static final ExecutorService EXPORT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // 适配器持有当前查询游标，关键词在打开编辑器和重建列表时保留。
    private SimpleCursorAdapter adapter;
    private String searchQuery = "";
    // 暂存等待系统文件选择器返回的待导出笔记 URI，避免同名笔记混淆。
    private Uri pendingExportUri;

    /**
     * onCreate is called when Android starts this Activity from scratch.
     */
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // The user does not need to hold down the key to use menu shortcuts.
        setDefaultKeyMode(DEFAULT_KEYS_SHORTCUT);

        /* If no data is given in the Intent that started this Activity, then this Activity
         * was started when the intent filter matched a MAIN action. We should use the default
         * provider URI.
         */
        // Gets the intent that started this Activity.
        Intent intent = getIntent();

        // If there is no data associated with the Intent, sets the data to the default URI, which
        // accesses a list of notes.
        if (intent.getData() == null) {
            intent.setData(NotePad.Notes.CONTENT_URI);
        }

        // 使用 ListActivity 的标准列表与空状态 ID，复用原有行点击入口。
        setContentView(R.layout.notes_list);
        if (savedInstanceState != null) {
            searchQuery = savedInstanceState.getString(STATE_SEARCH_QUERY, "");
            // 若在等待文件选择器返回期间发生配置变更或重建，恢复待导出的笔记 URI。
            String savedExportUri = savedInstanceState.getString(STATE_PENDING_EXPORT_URI);
            if (savedExportUri != null) {
                pendingExportUri = Uri.parse(savedExportUri);
            }
        }

        /*
         * Sets the callback for context menu activation for the ListView. The listener is set
         * to be this Activity. The effect is that context menus are enabled for items in the
         * ListView, and the context menu is handled by a method in NotesList.
         */
        getListView().setOnCreateContextMenuListener(this);

        /*
         * The following two arrays create a "map" between columns in the cursor and view IDs
         * for items in the ListView. Each element in the dataColumns array represents
         * a column name; each element in the viewID array represents the ID of a View.
         * The SimpleCursorAdapter maps them in ascending order to determine where each column
         * value will appear in the ListView.
         */

        // 按标题、修改时间的顺序绑定到列表项中的两个文本控件。
        String[] dataColumns = {
                NotePad.Notes.COLUMN_NAME_TITLE,
                NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE
        };

        // 保留原有标题控件 ID，在标题下方绑定修改时间。
        int[] viewIDs = { android.R.id.text1, R.id.note_modified };

        // 沿用适配器的变更监听，初始游标由 onResume 查询后交给适配器。
        adapter
            = new SimpleCursorAdapter(
                      this,                             // The Context for the ListView
                      R.layout.noteslist_item,          // Points to the XML for a list item
                      null,                             // 先完成列表绑定，再载入查询结果。
                      dataColumns,
                      viewIDs
              );
        // 标题沿用默认绑定，时间按设备本地格式显示。
        adapter.setViewBinder((view, row, columnIndex) -> {
            if (view.getId() != R.id.note_modified) {
                return false;
            }

            TextView modifiedView = (TextView) view;
            // 先判断历史记录中的空值，避免将缺失时间错误显示为 1970 年。
            if (row.isNull(columnIndex)) {
                modifiedView.setText(R.string.note_modified_unknown);
            } else {
                int flags = DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_YEAR
                        | DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_ABBREV_MONTH;
                modifiedView.setText(DateUtils.formatDateTime(this, row.getLong(columnIndex), flags));
            }
            return true;
        });

        // Sets the ListView's adapter to be the cursor adapter that was just created.
        setListAdapter(adapter);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 编辑、新建或改标题后，按当前搜索词重新确定哪些笔记仍然匹配。
        refreshNotes();
    }

    // 保存界面状态：在旋转、切换应用或拉起系统文件选择器导致 Activity 重建时，
    // 持久化当前搜索关键词与尚未完成回调的待导出笔记 URI。
    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 旋转等界面重建后恢复搜索词。
        outState.putString(STATE_SEARCH_QUERY, searchQuery);
        // 同步保存尚未返回结果的待导出笔记 URI，防止 Activity 重建后丢失目标对象。
        if (pendingExportUri != null) {
            outState.putString(STATE_PENDING_EXPORT_URI, pendingExportUri.toString());
        }
    }

    @Override
    protected void onDestroy() {
        // changeCursor 会关闭被替换的游标，销毁列表时以 null 释放最后一个。
        adapter.changeCursor(null);
        super.onDestroy();
    }

    private void refreshNotes() {
        String selection = null;
        String[] selectionArgs = null;
        boolean searching = !BLANK_QUERY.matcher(searchQuery).matches();
        if (searching) {
            // 先转义反斜杠，再转义 LIKE 的两个通配符；单引号由参数绑定处理。
            String keyword = searchQuery.replace("\\", "\\\\")
                    .replace("%", "\\%").replace("_", "\\_");
            String pattern = "%" + keyword + "%";
            selection = SEARCH_SELECTION;
            selectionArgs = new String[] { pattern, pattern };
        }

        // 切换查询时复用原 URI、投影与排序，由适配器关闭旧游标。
        TextView emptyView = findViewById(android.R.id.empty);
        emptyView.setText(searching ? R.string.search_no_results : R.string.notes_empty);
        adapter.changeCursor(getContentResolver().query(getIntent().getData(), PROJECTION,
                selection, selectionArgs, NotePad.Notes.DEFAULT_SORT_ORDER));
    }

    /**
     * Called when the user clicks the device's Menu button the first time for
     * this Activity. Android passes in a Menu object that is populated with items.
     *
     * Sets up a menu that provides the Insert option plus a list of alternative actions for
     * this Activity. Other applications that want to handle notes can "register" themselves in
     * Android by providing an intent filter that includes the category ALTERNATIVE and the
     * mimeTYpe NotePad.Notes.CONTENT_TYPE. If they do this, the code in onCreateOptionsMenu()
     * will add the Activity that contains the intent filter to its list of options. In effect,
     * the menu will offer the user other applications that can handle notes.
     * @param menu A Menu object, to which menu items should be added.
     * @return True, always. The menu should be displayed.
     */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_options_menu, menu);

        // 原生搜索框只负责收集输入，界面重建时先恢复文本，再监听后续变化。
        MenuItem searchItem = menu.findItem(R.id.menu_search);
        SearchView searchView = (SearchView) searchItem.getActionView();
        searchView.setQueryHint(getString(R.string.search_hint));
        if (!searchQuery.isEmpty()) {
            searchItem.expandActionView();
            searchView.setQuery(searchQuery, false);
            searchView.clearFocus();
        }
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                // 输入时已筛选，提交时收起键盘以便点选结果。
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                // 清空或收起搜索框也会收到文本变化，届时恢复全量查询。
                if (!searchQuery.equals(newText)) {
                    searchQuery = newText;
                    refreshNotes();
                }
                return true;
            }
        });

        // Generate any additional actions that can be performed on the
        // overall list.  In a normal install, there are no additional
        // actions found here, but this allows other applications to extend
        // our menu with their own actions.
        Intent intent = new Intent(null, getIntent().getData());
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);

        return super.onCreateOptionsMenu(menu);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);

        // The paste menu item is enabled if there is data on the clipboard.
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);


        MenuItem mPasteItem = menu.findItem(R.id.menu_paste);

        // If the clipboard contains an item, enables the Paste option on the menu.
        if (clipboard.hasPrimaryClip()) {
            mPasteItem.setEnabled(true);
        } else {
            // If the clipboard is empty, disables the menu's Paste option.
            mPasteItem.setEnabled(false);
        }

        // Gets the number of notes currently being displayed.
        final boolean haveItems = getListAdapter().getCount() > 0;

        // If there are any notes in the list (which implies that one of
        // them is selected), then we need to generate the actions that
        // can be performed on the current selection.  This will be a combination
        // of our own specific actions along with any extensions that can be
        // found.
        if (haveItems) {

            // This is the selected item.
            Uri uri = ContentUris.withAppendedId(getIntent().getData(), getSelectedItemId());

            // Creates an array of Intents with one element. This will be used to send an Intent
            // based on the selected menu item.
            Intent[] specifics = new Intent[1];

            // Sets the Intent in the array to be an EDIT action on the URI of the selected note.
            specifics[0] = new Intent(Intent.ACTION_EDIT, uri);

            // Creates an array of menu items with one element. This will contain the EDIT option.
            MenuItem[] items = new MenuItem[1];

            // Creates an Intent with no specific action, using the URI of the selected note.
            Intent intent = new Intent(null, uri);

            /* Adds the category ALTERNATIVE to the Intent, with the note ID URI as its
             * data. This prepares the Intent as a place to group alternative options in the
             * menu.
             */
            intent.addCategory(Intent.CATEGORY_ALTERNATIVE);

            /*
             * Add alternatives to the menu
             */
            menu.addIntentOptions(
                Menu.CATEGORY_ALTERNATIVE,  // Add the Intents as options in the alternatives group.
                Menu.NONE,                  // A unique item ID is not required.
                Menu.NONE,                  // The alternatives don't need to be in order.
                null,                       // The caller's name is not excluded from the group.
                specifics,                  // These specific options must appear first.
                intent,                     // These Intent objects map to the options in specifics.
                Menu.NONE,                  // No flags are required.
                items                       // The menu items generated from the specifics-to-
                                            // Intents mapping
            );
                // If the Edit menu item exists, adds shortcuts for it.
                if (items[0] != null) {

                    // Sets the Edit menu item shortcut to numeric "1", letter "e"
                    items[0].setShortcut('1', 'e');
                }
            } else {
                // If the list is empty, removes any existing alternative actions from the menu
                menu.removeGroup(Menu.CATEGORY_ALTERNATIVE);
            }

        // Displays the menu
        return true;
    }

    /**
     * This method is called when the user selects an option from the menu, but no item
     * in the list is selected. If the option was INSERT, then a new Intent is sent out with action
     * ACTION_INSERT. The data from the incoming Intent is put into the new Intent. In effect,
     * this triggers the NoteEditor activity in the NotePad application.
     *
     * If the item was not INSERT, then most likely it was an alternative option from another
     * application. The parent method is called to process the item.
     * @param item The menu item that was selected by the user
     * @return True, if the INSERT menu item was selected; otherwise, the result of calling
     * the parent method.
     */
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.menu_add) {
            /*
             * Launches a new Activity using an Intent. The intent filter for the Activity
             * has to have action ACTION_INSERT. No category is set, so DEFAULT is assumed.
             * In effect, this starts the NoteEditor Activity in NotePad.
             */
            startActivity(new Intent(Intent.ACTION_INSERT, getIntent().getData()));
            return true;
        } else if (item.getItemId() == R.id.menu_paste) {
            /*
             * Launches a new Activity using an Intent. The intent filter for the Activity
             * has to have action ACTION_PASTE. No category is set, so DEFAULT is assumed.
             * In effect, this starts the NoteEditor Activity in NotePad.
             */
            startActivity(new Intent(Intent.ACTION_PASTE, getIntent().getData()));
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    /**
     * This method is called when the user context-clicks a note in the list. NotesList registers
     * itself as the handler for context menus in its ListView (this is done in onCreate()).
     *
     * The only available options are COPY and DELETE.
     *
     * Context-click is equivalent to long-press.
     *
     * @param menu A ContexMenu object to which items should be added.
     * @param view The View for which the context menu is being constructed.
     * @param menuInfo Data associated with view.
     * @throws ClassCastException
     */
    @Override
    public void onCreateContextMenu(ContextMenu menu, View view, ContextMenuInfo menuInfo) {

        // The data from the menu item.
        AdapterView.AdapterContextMenuInfo info;

        // Tries to get the position of the item in the ListView that was long-pressed.
        try {
            // Casts the incoming data object into the type for AdapterView objects.
            info = (AdapterView.AdapterContextMenuInfo) menuInfo;
        } catch (ClassCastException e) {
            // If the menu object can't be cast, logs an error.
            Log.e(TAG, "bad menuInfo", e);
            return;
        }

        /*
         * Gets the data associated with the item at the selected position. getItem() returns
         * whatever the backing adapter of the ListView has associated with the item. In NotesList,
         * the adapter associated all of the data for a note with its list item. As a result,
         * getItem() returns that data as a Cursor.
         */
        Cursor cursor = (Cursor) getListAdapter().getItem(info.position);

        // If the cursor is empty, then for some reason the adapter can't get the data from the
        // provider, so returns null to the caller.
        if (cursor == null) {
            // For some reason the requested item isn't available, do nothing
            return;
        }

        // Inflate menu from XML resource
        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.list_context_menu, menu);

        // Sets the menu header to be the title of the selected note.
        menu.setHeaderTitle(cursor.getString(COLUMN_INDEX_TITLE));

        // Append to the
        // menu items for any other activities that can do stuff with it
        // as well.  This does a query on the system for any activities that
        // implement the ALTERNATIVE_ACTION for our data, adding a menu item
        // for each one that is found.
        // 改标题等附加操作也使用筛选结果行的完整 ID。
        Intent intent = new Intent(null, ContentUris.withAppendedId(getIntent().getData(), info.id));
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);
    }

    /**
     * This method is called when the user selects an item from the context menu
     * (see onCreateContextMenu()). The only menu items that are actually handled are DELETE and
     * COPY. Anything else is an alternative option, for which default handling should be done.
     *
     * @param item The selected menu item
     * @return True if the menu item was DELETE, and no default processing is need, otherwise false,
     * which triggers the default handling of the item.
     * @throws ClassCastException
     */
    @Override
    public boolean onContextItemSelected(MenuItem item) {
        // The data from the menu item.
        AdapterView.AdapterContextMenuInfo info;

        /*
         * Gets the extra info from the menu item. When an note in the Notes list is long-pressed, a
         * context menu appears. The menu items for the menu automatically get the data
         * associated with the note that was long-pressed. The data comes from the provider that
         * backs the list.
         *
         * The note's data is passed to the context menu creation routine in a ContextMenuInfo
         * object.
         *
         * When one of the context menu items is clicked, the same data is passed, along with the
         * note ID, to onContextItemSelected() via the item parameter.
         */
        try {
            // Casts the data object in the item into the type for AdapterView objects.
            info = (AdapterView.AdapterContextMenuInfo) item.getMenuInfo();
        } catch (ClassCastException e) {

            // If the object can't be cast, logs an error
            Log.e(TAG, "bad menuInfo", e);

            // Triggers default processing of the menu item.
            return false;
        }
        // Appends the selected note's ID to the URI sent with the incoming Intent.
        Uri noteUri = ContentUris.withAppendedId(getIntent().getData(), info.id);

        /*
         * Gets the menu item's ID and compares it to known actions.
         */
        int id = item.getItemId();
        if (id == R.id.context_open) {
            // Launch activity to view/edit the currently selected item
            startActivity(new Intent(Intent.ACTION_EDIT, noteUri));
            return true;
        } else if (id == R.id.context_copy) { //BEGIN_INCLUDE(copy)
            // Gets a handle to the clipboard service.
            ClipboardManager clipboard = (ClipboardManager)
                    getSystemService(Context.CLIPBOARD_SERVICE);

            // Copies the notes URI to the clipboard. In effect, this copies the note itself
            clipboard.setPrimaryClip(ClipData.newUri(   // new clipboard item holding a URI
                    getContentResolver(),               // resolver to retrieve URI info
                    "Note",                             // label for the clip
                    noteUri));                          // the URI

            // Returns to the caller and skips further processing.
            return true;
            //END_INCLUDE(copy)
        } else if (id == R.id.context_export) {
            // 绑定长按行真实 ID 构造的笔记 URI，并交由系统文档创建器选择保存位置。
            startExportNote(noteUri, info.position);
            return true;
        } else if (id == R.id.context_delete) {
            // Deletes the note from the provider by passing in a URI in note ID format.
            // Please see the introductory note about performing provider operations on the
            // UI thread.
            getContentResolver().delete(
                    noteUri,  // The URI of the provider
                    null,     // No where clause is needed, since only a single note ID is being
                    // passed in.
                    null      // No where clause is used, so no where arguments are needed.
            );

            // Returns to the caller and skips further processing.
            return true;
        }
        return super.onContextItemSelected(item);
    }

    /**
     * This method is called when the user clicks a note in the displayed list.
     *
     * This method handles incoming actions of either PICK (get data from the provider) or
     * GET_CONTENT (get or create data). If the incoming action is EDIT, this method sends a
     * new Intent to start NoteEditor.
     * @param l The ListView that contains the clicked item
     * @param v The View of the individual item
     * @param position The position of v in the displayed list
     * @param id The row ID of the clicked item
     */
    @Override
    protected void onListItemClick(ListView l, View v, int position, long id) {

        // Constructs a new URI from the incoming URI and the row ID
        Uri uri = ContentUris.withAppendedId(getIntent().getData(), id);

        // Gets the action from the incoming Intent
        String action = getIntent().getAction();

        // Handles requests for note data
        if (Intent.ACTION_PICK.equals(action) || Intent.ACTION_GET_CONTENT.equals(action)) {

            // Sets the result to return to the component that called this Activity. The
            // result contains the new URI
            setResult(RESULT_OK, new Intent().setData(uri));
        } else {

            // Sends out an Intent to start an Activity that can handle ACTION_EDIT. The
            // Intent's data is the note ID URI. The effect is to call NoteEdit.
            startActivity(new Intent(Intent.ACTION_EDIT, uri));
        }
    }

    // 启动系统文档创建器（ACTION_CREATE_DOCUMENT），让用户自行选择保存目录与文件名。
    // 发起前将基于真实行 ID 构造的 noteUri 存入 pendingExportUri，position 用于读取建议文件名；
    // 这样即使存在同名笔记或等待回调期间界面重建，仍能精确定位源笔记。
    private void startExportNote(Uri noteUri, int position) {
        // 先解析该行笔记标题并清理成合法的 .txt 建议文件名。
        String noteTitle = resolveNoteTitle(noteUri, position);
        String suggestedFileName = NoteExporter.buildSuggestedFileName(
                noteTitle, getString(R.string.export_default_filename));

        // 构造系统文档创建意图，声明可打开的纯文本类型及预填文件名。
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType(NoteExporter.MIME_TYPE_TEXT_PLAIN);
        intent.putExtra(Intent.EXTRA_TITLE, suggestedFileName);

        // 先保存目标笔记身份再跳转，避免回调到达前状态未落定。
        pendingExportUri = noteUri;
        try {
            startActivityForResult(intent, REQUEST_CODE_EXPORT_NOTE);
        } catch (ActivityNotFoundException e) {
            // 极少数精简系统若缺失文档创建器，及时清理暂存状态并提示用户。
            pendingExportUri = null;
            Log.e(TAG, "No activity found to handle ACTION_CREATE_DOCUMENT", e);
            Toast.makeText(this, R.string.export_no_picker, Toast.LENGTH_SHORT).show();
        }
    }

    // 读取指定笔记的标题，供生成默认导出文件名使用。
    // 优先从当前列表游标按 position 直接取值；若游标已关闭或不可用，则按单条 noteUri 补查数据库，
    // 仍查不到时返回 null，由调用方回退至默认文件名。
    private String resolveNoteTitle(Uri noteUri, int position) {
        Object item = getListAdapter().getItem(position);
        if (item instanceof Cursor) {
            Cursor cursor = (Cursor) item;
            if (!cursor.isClosed()) {
                return cursor.getString(COLUMN_INDEX_TITLE);
            }
        }
        // 兜底按单条 URI 精确查询标题列，并由 try-with-resources 确保临时游标关闭。
        try (Cursor cursor = getContentResolver().query(
                noteUri, new String[] { NotePad.Notes.COLUMN_NAME_TITLE }, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                return cursor.getString(0);
            }
        }
        return null;
    }

    // 接收系统文档创建器返回的结果并驱动后台流复制。
    // 区分用户取消、返回参数或源身份异常以及正常选定目标 URI 三种情况；
    // 正常确认后在独立后台线程复制纯文本流，待实际落盘关闭完成后再切回主线程提示成功或失败。
    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE_EXPORT_NOTE) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }

        // 无论结果如何都先消费并清空暂存的源笔记 URI，避免后续操作误用旧对象。
        final Uri sourceUri = pendingExportUri;
        pendingExportUri = null;

        if (resultCode == RESULT_CANCELED) {
            Toast.makeText(this, R.string.export_cancelled, Toast.LENGTH_SHORT).show();
            return;
        }

        final Uri targetUri = data != null ? data.getData() : null;
        if (resultCode != RESULT_OK || sourceUri == null || targetUri == null) {
            Toast.makeText(this, R.string.export_failed, Toast.LENGTH_SHORT).show();
            return;
        }

        // 使用 Application Context 执行后台复制与结果提示，防止导出期间旋转屏幕造成 Activity 泄漏。
        final Context appContext = getApplicationContext();
        EXPORT_EXECUTOR.execute(() -> {
            int messageResId;
            try {
                NoteExporter.exportNoteToUri(appContext.getContentResolver(), sourceUri, targetUri);
                messageResId = R.string.export_success;
            } catch (IOException e) {
                Log.e(TAG, "Failed to export note " + sourceUri + " to " + targetUri, e);
                messageResId = R.string.export_failed;
            }
            // 仅在流复制与关闭输出流全部完成后，切回主线程向用户反馈最终结果。
            final int finalMessageResId = messageResId;
            MAIN_HANDLER.post(() -> Toast.makeText(
                    appContext, finalMessageResId, Toast.LENGTH_SHORT).show());
        });
    }
}

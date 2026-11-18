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

import java.util.Calendar;
import java.util.Locale;
import java.util.regex.Pattern;

import android.app.ListActivity;
import android.content.ClipboardManager;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.res.TypedArray;
import android.database.Cursor;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.TextWatcher;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.ContextMenu;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ContextMenu.ContextMenuInfo;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.SimpleCursorAdapter;
import android.widget.TextView;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;


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
            NotePad.Notes.COLUMN_NAME_NOTE, // T04 追加正文列，用于卡片中的正文摘要。
    };

    /** 行 ID 所在列，用于给卡片分配点缀色。 */
    private static final int COLUMN_INDEX_ID = 0;

    /** The index of the title column */
    private static final int COLUMN_INDEX_TITLE = 1;

    // 使用参数绑定传递搜索词，两个 LIKE 条件共同作用于当前 URI 的结果。
    private static final String SEARCH_SELECTION =
            "(" + NotePad.Notes.COLUMN_NAME_TITLE + " LIKE ? ESCAPE '\\' OR "
                    + NotePad.Notes.COLUMN_NAME_NOTE + " LIKE ? ESCAPE '\\')";
    // 将全角空格等 Unicode 分隔符也视为纯空白输入。
    private static final Pattern BLANK_QUERY = Pattern.compile("[\\s\\p{Z}]*");
    private static final String STATE_SEARCH_QUERY = "searchQuery";

    // 折叠正文中的换行和连续空白，让摘要以一段文字显示。
    private static final Pattern WHITESPACE = Pattern.compile("[\\s\\p{Z}]+");
    // 摘要只处理正文开头的这些字符，避免超长笔记拖慢列表滚动。
    private static final int PREVIEW_SOURCE_LIMIT = 2000;

    // 适配器持有当前查询游标，关键词在打开编辑器和重建列表时保留。
    private SimpleCursorAdapter adapter;
    private String searchQuery = "";

    // T04 新增的界面控件：顶部栏、搜索框、空状态与新建按钮。
    private View header;
    private TextView countView;
    private EditText searchInput;
    private ImageButton searchClear;
    private ImageButton themeToggle;
    private ImageButton moreButton;
    private ImageButton fab;
    private View emptyView;
    private ImageView emptyArt;
    private TextView emptyTitle;
    private TextView emptyDetail;

    // 卡片点缀色与搜索高亮颜色，随深浅模式从资源读取。
    private int[] tagColors;
    private int highlightColor;
    private int markColor;

    // 顶部栏与新建按钮的基础留白，系统栏占用的高度在窗口插入回调中叠加。
    private int headerBaseTop;
    private int listBaseBottom;
    private int fabBaseMargin;
    private int systemBottomInset;

    // 记录上一次滚动位置，判断上滑隐藏、下滑显示新建按钮。
    private int lastFirstVisible;
    private int lastTopOffset;
    private boolean fabHidden;

    // API 33 以上用于“返回键先清除搜索”的回调，旧系统改由 onBackPressed 处理。
    private Object backCallback;

    @Override
    protected void attachBaseContext(Context newBase) {
        super.attachBaseContext(newBase);
        // 按用户选择覆盖深浅色，之后加载的资源会自动选用对应模式。
        ThemeHelper.applyNightMode(this, newBase);
    }

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

        // 按标题、修改时间、正文的顺序绑定到卡片中的三个文本控件。
        String[] dataColumns = {
                NotePad.Notes.COLUMN_NAME_TITLE,
                NotePad.Notes.COLUMN_NAME_MODIFICATION_DATE,
                NotePad.Notes.COLUMN_NAME_NOTE
        };

        // 保留原有标题控件 ID，在标题下方绑定修改时间和正文摘要。
        int[] viewIDs = { android.R.id.text1, R.id.note_modified, R.id.note_preview };

        // 读取卡片点缀色与高亮色，深浅模式各有一套。
        TypedArray tags = getResources().obtainTypedArray(R.array.np_tags);
        tagColors = new int[tags.length()];
        for (int i = 0; i < tagColors.length; i++) {
            tagColors[i] = tags.getColor(i, 0);
        }
        tags.recycle();
        highlightColor = getResources().getColor(R.color.np_highlight);
        markColor = getResources().getColor(R.color.np_search_mark);

        // 沿用适配器的变更监听，初始游标由 onResume 查询后交给适配器。
        adapter
            = new SimpleCursorAdapter(
                      this,                             // The Context for the ListView
                      R.layout.noteslist_item,          // Points to the XML for a list item
                      null,                             // 先完成列表绑定，再载入查询结果。
                      dataColumns,
                      viewIDs
              ) {
            @Override
            public void bindView(View view, Context context, Cursor cursor) {
                super.bindView(view, context, cursor);
                // 按笔记 ID 轮换点缀色，同一条笔记在搜索和重排后颜色不变。
                View dot = view.findViewById(R.id.note_accent);
                long noteId = cursor.getLong(COLUMN_INDEX_ID);
                int color = tagColors[(int) (Math.abs(noteId) % tagColors.length)];
                ((GradientDrawable) dot.getBackground().mutate()).setColor(color);
            }
        };
        // 标题与摘要在搜索时高亮关键词，时间按“今天／昨天／日期”加时分显示。
        adapter.setViewBinder((view, row, columnIndex) -> {
            int viewId = view.getId();
            if (viewId == android.R.id.text1) {
                String title = row.getString(columnIndex);
                ((TextView) view).setText(highlight(title == null ? "" : title));
                return true;
            } else if (viewId == R.id.note_preview) {
                String preview = buildPreview(row.getString(COLUMN_INDEX_TITLE),
                        row.getString(columnIndex));
                // 正文为空或与标题完全重复时隐藏摘要行，卡片随之变矮。
                view.setVisibility(preview.isEmpty() ? View.GONE : View.VISIBLE);
                ((TextView) view).setText(highlight(preview));
                return true;
            } else if (viewId != R.id.note_modified) {
                return false;
            }

            TextView modifiedView = (TextView) view;
            // 先判断历史记录中的空值，避免将缺失时间错误显示为 1970 年。
            if (row.isNull(columnIndex)) {
                modifiedView.setText(R.string.note_modified_unknown);
            } else {
                modifiedView.setText(formatModified(row.getLong(columnIndex)));
            }
            return true;
        });

        // Sets the ListView's adapter to be the cursor adapter that was just created.
        setListAdapter(adapter);

        // 搭建顶部栏、搜索框、新建按钮和滚动效果。
        boolean revealPending = ThemeHelper.hasPendingReveal();
        setupChrome();
        if (revealPending) {
            // 切换外观后由揭示动画承担过渡，不再重复播放入场动画。
            getListView().setLayoutAnimation(null);
            ThemeHelper.playPendingReveal(this);
            themeToggle.setRotation(-120f);
            themeToggle.animate().rotation(0f).setDuration(600)
                    .setInterpolator(new OvershootInterpolator()).start();
        } else if (savedInstanceState == null) {
            playEntrance();
        } else {
            getListView().setLayoutAnimation(null);
        }
    }

    /**
     * 初始化 T04 新增的界面部分：系统栏、窗口插入、顶部栏、搜索框、按钮与滚动监听。
     */
    private void setupChrome() {
        ThemeHelper.setupSystemBars(this, true);

        View root = findViewById(R.id.list_root);
        final ListView listView = getListView();
        header = findViewById(R.id.header);
        countView = findViewById(R.id.header_count);
        searchInput = findViewById(R.id.search_input);
        searchClear = findViewById(R.id.search_clear);
        themeToggle = findViewById(R.id.theme_toggle);
        moreButton = findViewById(R.id.more_button);
        fab = findViewById(R.id.fab_add);
        emptyView = findViewById(android.R.id.empty);
        emptyArt = findViewById(R.id.empty_art);
        emptyTitle = findViewById(R.id.empty_title);
        emptyDetail = findViewById(R.id.empty_detail);

        headerBaseTop = header.getPaddingTop();
        listBaseBottom = listView.getPaddingBottom();
        fabBaseMargin = ((ViewGroup.MarginLayoutParams) fab.getLayoutParams()).bottomMargin;

        // 顶部日期使用固定的中文格式，不随系统语言变化。
        Calendar now = Calendar.getInstance();
        String[] weekdays = getResources().getStringArray(R.array.weekdays);
        ((TextView) findViewById(R.id.header_date)).setText(getString(R.string.header_date,
                now.get(Calendar.MONTH) + 1, now.get(Calendar.DAY_OF_MONTH),
                weekdays[now.get(Calendar.DAY_OF_WEEK) - 1]));

        // 内容延伸到系统栏下方后，按状态栏、导航栏和输入法的实际高度补足留白。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT_WATCH) {
            root.setOnApplyWindowInsetsListener((v, insets) -> {
                header.setPadding(header.getPaddingLeft(),
                        headerBaseTop + insets.getSystemWindowInsetTop(),
                        header.getPaddingRight(), header.getPaddingBottom());
                systemBottomInset = insets.getSystemWindowInsetBottom();
                applyBottomInsets();
                return insets;
            });
        }

        // 外观切换按钮：浅色时显示月亮，深色时显示太阳。
        boolean night = ThemeHelper.isNight(this);
        themeToggle.setImageResource(night ? R.drawable.ic_np_sun : R.drawable.ic_np_moon);
        themeToggle.setContentDescription(getString(
                night ? R.string.action_theme_light : R.string.action_theme_dark));
        themeToggle.setOnClickListener(v -> {
            ThemeHelper.setMode(this, ThemeHelper.isNight(this)
                    ? ThemeHelper.MODE_LIGHT : ThemeHelper.MODE_DARK);
            switchThemeFrom(v);
        });

        moreButton.setOnClickListener(this::showMoreMenu);
        fab.setOnClickListener(v -> createNote());

        // 搜索框：先恢复关键词再监听，避免恢复时重复查询。
        searchInput.setText(searchQuery);
        searchClear.setVisibility(searchQuery.isEmpty() ? View.GONE : View.VISIBLE);
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                // 与 T03 相同：输入即筛选，清空或只含空白时恢复全量列表。
                String newText = s.toString();
                searchClear.setVisibility(newText.isEmpty() ? View.GONE : View.VISIBLE);
                if (!searchQuery.equals(newText)) {
                    searchQuery = newText;
                    refreshNotes();
                }
                updateBackCallback();
            }
        });
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                // 输入时已筛选，提交时收起键盘以便点选结果。
                hideKeyboard();
                return true;
            }
            return false;
        });
        searchInput.setOnFocusChangeListener((v, hasFocus) -> updateBackCallback());
        searchClear.setOnClickListener(v -> searchInput.setText(""));

        listView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                // 开始拖动列表时收起键盘，让出更多可视区域。
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL && searchInput.hasFocus()) {
                    hideKeyboard();
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                    int totalItemCount) {
                onListScrolled(view, firstVisibleItem);
            }
        });
    }

    /** 底部留白 = 基础留白 + 导航栏或输入法高度。 */
    private void applyBottomInsets() {
        ListView listView = getListView();
        listView.setPadding(listView.getPaddingLeft(), listView.getPaddingTop(),
                listView.getPaddingRight(), listBaseBottom + systemBottomInset);
        ViewGroup.MarginLayoutParams params =
                (ViewGroup.MarginLayoutParams) fab.getLayoutParams();
        params.bottomMargin = fabBaseMargin + systemBottomInset;
        fab.setLayoutParams(params);
    }

    /**
     * 滚动时：向下浏览收起新建按钮，回滚时再弹出。
     */
    private void onListScrolled(AbsListView view, int firstVisible) {
        View first = view.getChildAt(0);
        int top = first == null ? 0 : first.getTop();

        // 同一首行内累计超过阈值才切换，避免轻微抖动导致按钮反复出没。
        int slop = dp(8);
        if (firstVisible == lastFirstVisible) {
            int delta = lastTopOffset - top;
            if (Math.abs(delta) < slop) {
                return;
            }
            setFabHidden(delta > 0);
        } else {
            setFabHidden(firstVisible > lastFirstVisible);
        }
        lastFirstVisible = firstVisible;
        lastTopOffset = top;
    }

    private void setFabHidden(boolean hidden) {
        if (fabHidden == hidden) {
            return;
        }
        fabHidden = hidden;
        float distance = fab.getHeight() + fabBaseMargin + systemBottomInset;
        fab.animate().translationY(hidden ? distance : 0f).setDuration(260)
                .setInterpolator(new DecelerateInterpolator(1.5f)).start();
    }

    /** 首次打开时顶部栏下落淡入，新建按钮弹性放大；卡片由布局动画依次浮现。 */
    private void playEntrance() {
        header.setAlpha(0f);
        header.setTranslationY(-dp(16));
        header.animate().alpha(1f).translationY(0f).setDuration(420)
                .setInterpolator(new DecelerateInterpolator(2f)).start();
        fab.setScaleX(0f);
        fab.setScaleY(0f);
        fab.animate().scaleX(1f).scaleY(1f).setStartDelay(260).setDuration(420)
                .setInterpolator(new OvershootInterpolator(2.2f)).start();
    }

    /**
     * 以 anchor 中心为圆心切换外观：先截下旧界面，再重建 Activity，
     * 新界面通过 ThemeHelper 的圆形揭示动画逐渐显露。搜索词随实例状态保留。
     */
    private void switchThemeFrom(View anchor) {
        int[] location = new int[2];
        anchor.getLocationInWindow(location);
        ThemeHelper.captureForReveal(this, location[0] + anchor.getWidth() / 2f,
                location[1] + anchor.getHeight() / 2f);
        recreate();
    }

    /** 顶部“更多”按钮：以弹出菜单展示原有的列表选项菜单。 */
    private void showMoreMenu(View anchor) {
        PopupMenu popup = new PopupMenu(this, anchor);
        Menu menu = popup.getMenu();
        getMenuInflater().inflate(R.menu.list_options_menu, menu);
        addListAlternatives(menu);
        prepareListMenu(menu);
        // 复用 onOptionsItemSelected，未处理的附加操作由菜单项自带的 Intent 启动。
        popup.setOnMenuItemClickListener(this::onOptionsItemSelected);
        popup.show();
    }

    /** 新建入口：右下角按钮和菜单中的“新建笔记”共用。 */
    private void createNote() {
        /*
         * Launches a new Activity using an Intent. The intent filter for the Activity
         * has to have action ACTION_INSERT. No category is set, so DEFAULT is assumed.
         * In effect, this starts the NoteEditor Activity in NotePad.
         */
        startActivity(new Intent(Intent.ACTION_INSERT, getIntent().getData()));
    }

    private void hideKeyboard() {
        InputMethodManager imm =
                (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        searchInput.clearFocus();
    }

    /** 搜索框有内容或处于输入状态时，返回键先清除搜索，与 T03 收起搜索框的行为一致。 */
    private boolean clearSearchIfActive() {
        if (!searchInput.getText().toString().isEmpty() || searchInput.hasFocus()) {
            searchInput.setText("");
            hideKeyboard();
            return true;
        }
        return false;
    }

    @Override
    public void onBackPressed() {
        if (clearSearchIfActive()) {
            return;
        }
        super.onBackPressed();
    }

    /** API 33 以上系统不再调用 onBackPressed，需要按搜索状态注册或注销返回回调。 */
    private void updateBackCallback() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            boolean needed = !searchInput.getText().toString().isEmpty() || searchInput.hasFocus();
            backCallback = BackApi33.update(this, backCallback, needed);
        }
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 编辑、新建或改标题后，按当前搜索词重新确定哪些笔记仍然匹配。
        refreshNotes();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        // 旋转等界面重建后恢复搜索词。
        outState.putString(STATE_SEARCH_QUERY, searchQuery);
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
        adapter.changeCursor(getContentResolver().query(getIntent().getData(), PROJECTION,
                selection, selectionArgs, NotePad.Notes.DEFAULT_SORT_ORDER));

        // 区分“空笔记本”与“搜索无结果”，分别显示不同的插画和说明。
        if (searching) {
            emptyArt.setImageResource(R.drawable.ill_empty_search);
            emptyTitle.setText(R.string.search_no_results);
            emptyDetail.setText(getString(R.string.search_no_results_detail, searchQuery.trim()));
        } else {
            emptyArt.setImageResource(R.drawable.ill_empty_notes);
            emptyTitle.setText(R.string.notes_empty);
            emptyDetail.setText(R.string.notes_empty_detail);
        }
        int count = adapter.getCount();
        countView.setText(getString(searching ? R.string.header_count_search
                : R.string.header_count, count));
        startEmptyFloat(count == 0);
    }

    /** 空状态插画上下轻缓浮动；有笔记时停止动画。 */
    private void startEmptyFloat(boolean empty) {
        emptyArt.animate().cancel();
        if (!empty) {
            emptyArt.setTranslationY(0f);
            return;
        }
        floatEmptyArt(-dp(8));
    }

    private void floatEmptyArt(final float target) {
        emptyArt.animate().translationY(target).setDuration(1600)
                .setInterpolator(new android.view.animation.AccelerateDecelerateInterpolator())
                .withEndAction(() -> {
                    // 列表仍为空且界面可见时继续往返浮动。
                    if (emptyView.isShown()) {
                        floatEmptyArt(target == 0f ? -dp(8) : 0f);
                    }
                }).start();
    }

    /**
     * 生成卡片中的正文摘要：折叠空白，去掉与标题重复的开头；
     * 搜索时若命中位置靠后，从命中处之前几个字开始截取，保证关键词出现在摘要中。
     */
    private String buildPreview(String title, String note) {
        if (note == null) {
            return "";
        }
        String text = note.length() > PREVIEW_SOURCE_LIMIT
                ? note.substring(0, PREVIEW_SOURCE_LIMIT) : note;
        text = WHITESPACE.matcher(text).replaceAll(" ").trim();
        String plainTitle = title == null ? "" : WHITESPACE.matcher(title).replaceAll(" ").trim();
        if (!plainTitle.isEmpty() && text.startsWith(plainTitle)) {
            text = text.substring(plainTitle.length()).trim();
        }
        if (isSearching() && !text.isEmpty()) {
            int index = text.toLowerCase(Locale.ROOT)
                    .indexOf(searchQuery.toLowerCase(Locale.ROOT));
            if (index > 18) {
                text = "…" + text.substring(index - 10);
            }
        }
        return text;
    }

    /** 在文本中高亮所有与搜索词相同（不区分大小写）的片段。 */
    private CharSequence highlight(String text) {
        if (!isSearching() || text.isEmpty()) {
            return text;
        }
        String lower = text.toLowerCase(Locale.ROOT);
        String query = searchQuery.toLowerCase(Locale.ROOT);
        // 个别字符转小写后长度会变化，此时放弃高亮以免下标错位。
        if (lower.length() != text.length() || query.isEmpty()) {
            return text;
        }
        SpannableString spannable = new SpannableString(text);
        int from = 0;
        int index;
        while ((index = lower.indexOf(query, from)) >= 0) {
            int end = index + query.length();
            spannable.setSpan(new BackgroundColorSpan(highlightColor), index, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new ForegroundColorSpan(markColor), index, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            spannable.setSpan(new StyleSpan(Typeface.BOLD), index, end,
                    Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            from = end;
        }
        return spannable;
    }

    private boolean isSearching() {
        return !BLANK_QUERY.matcher(searchQuery).matches();
    }

    /**
     * 修改时间：今天、昨天用相对说法，今年省略年份，更早的显示完整日期，均附 24 小时制时分。
     */
    private String formatModified(long millis) {
        Calendar now = Calendar.getInstance();
        Calendar time = Calendar.getInstance();
        time.setTimeInMillis(millis);
        String clock = String.format(Locale.ROOT, "%02d:%02d",
                time.get(Calendar.HOUR_OF_DAY), time.get(Calendar.MINUTE));
        int month = time.get(Calendar.MONTH) + 1;
        int day = time.get(Calendar.DAY_OF_MONTH);
        if (time.get(Calendar.YEAR) == now.get(Calendar.YEAR)) {
            int dayDiff = now.get(Calendar.DAY_OF_YEAR) - time.get(Calendar.DAY_OF_YEAR);
            if (dayDiff == 0) {
                return getString(R.string.time_today, clock);
            } else if (dayDiff == 1) {
                return getString(R.string.time_yesterday, clock);
            }
            return getString(R.string.time_this_year, month, day, clock);
        }
        return getString(R.string.time_full, time.get(Calendar.YEAR), month, day, clock);
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
        // T04 起搜索改由顶部常驻搜索框承担，此处只保留新建、粘贴等原有菜单项。
        addListAlternatives(menu);

        return super.onCreateOptionsMenu(menu);
    }

    /**
     * 把其他应用为笔记集合注册的附加操作加入菜单，供实体菜单键和“更多”弹出菜单共用。
     */
    private void addListAlternatives(Menu menu) {
        // Generate any additional actions that can be performed on the
        // overall list.  In a normal install, there are no additional
        // actions found here, but this allows other applications to extend
        // our menu with their own actions.
        Intent intent = new Intent(null, getIntent().getData());
        intent.addCategory(Intent.CATEGORY_ALTERNATIVE);
        menu.addIntentOptions(Menu.CATEGORY_ALTERNATIVE, 0, 0,
                new ComponentName(this, NotesList.class), null, intent, 0, null);
    }

    /** 按剪贴板状态启用“粘贴为新笔记”，并勾选当前的外观跟随设置。 */
    private void prepareListMenu(Menu menu) {
        // The paste menu item is enabled if there is data on the clipboard.
        ClipboardManager clipboard = (ClipboardManager)
                getSystemService(Context.CLIPBOARD_SERVICE);
        menu.findItem(R.id.menu_paste).setEnabled(clipboard.hasPrimaryClip());
        menu.findItem(R.id.menu_follow_system).setChecked(
                ThemeHelper.getMode(this) == ThemeHelper.MODE_SYSTEM);
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
        menu.findItem(R.id.menu_follow_system).setChecked(
                ThemeHelper.getMode(this) == ThemeHelper.MODE_SYSTEM);

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
            // 与右下角新建按钮共用同一入口。
            createNote();
            return true;
        } else if (item.getItemId() == R.id.menu_follow_system) {
            // 勾选：交还系统决定；取消：把当前看到的外观固定下来。
            boolean follow = !item.isChecked();
            boolean nightNow = ThemeHelper.isNight(this);
            ThemeHelper.setMode(this, follow ? ThemeHelper.MODE_SYSTEM
                    : (nightNow ? ThemeHelper.MODE_DARK : ThemeHelper.MODE_LIGHT));
            // 只有实际外观会变化时才重建界面。
            if (follow && ThemeHelper.isSystemNight(this) != nightNow) {
                switchThemeFrom(moreButton);
            }
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

    /**
     * 隔离 API 33 才有的 OnBackInvokedDispatcher，避免在旧系统上加载该类。
     */
    private static final class BackApi33 {
        static Object update(final NotesList activity, Object current, boolean needed) {
            OnBackInvokedDispatcher dispatcher = activity.getOnBackInvokedDispatcher();
            if (needed && current == null) {
                OnBackInvokedCallback callback = activity::clearSearchIfActive;
                dispatcher.registerOnBackInvokedCallback(
                        OnBackInvokedDispatcher.PRIORITY_DEFAULT, callback);
                return callback;
            } else if (!needed && current != null) {
                dispatcher.unregisterOnBackInvokedCallback((OnBackInvokedCallback) current);
                return null;
            }
            return current;
        }
    }
}

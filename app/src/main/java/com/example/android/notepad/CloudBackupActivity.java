package com.example.android.notepad;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

// WebDAV 云备份与恢复页面。
// 负责管理 HTTPS 目录地址、账号（可本地记住）与仅限当前进程会话使用的应用密码，
// 并在用户确认后于后台复用 T07 的整本 JSON 快照执行 WebDAV PUT 上传。
public class CloudBackupActivity extends Activity {

    private static final String TAG = "CloudBackupActivity";

    // 本地偏好文件名及仅保存非敏感配置的键；应用密码绝不写入本地存储。
    static final String PREFS_NAME = "cloud_backup_prefs";
    static final String KEY_DIRECTORY_URL = "directory_url";
    static final String KEY_USERNAME = "username";

    // 独立单线程后台执行器与主线程回调句柄，用于数据库快照查询和 HTTPS 传输。
    private static final ExecutorService CLOUD_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // 进程级传输锁，防止界面重复点击或重进页面后同时发起多次冲突的云端传输。
    private static final AtomicBoolean TRANSFER_IN_PROGRESS = new AtomicBoolean(false);
    // 仅驻留当前应用进程内存的应用密码，进程结束即自动清除，不落盘也不进状态 Bundle。
    private static String sSessionPassword = "";

    private EditText urlInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private Button backupButton;
    private ProgressBar progressBar;
    private TextView statusView;

    // 标记当前是否正在准备快照或等待用户确认，避免连点弹出多个确认框。
    private boolean preparingBackup;

    // 初始化页面视图、恢复已记住的目录地址与账号，并绑定备份按钮点击事件。
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_cloud_backup);

        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true);
        }

        urlInput = findViewById(R.id.input_webdav_url);
        usernameInput = findViewById(R.id.input_webdav_username);
        passwordInput = findViewById(R.id.input_webdav_password);
        backupButton = findViewById(R.id.button_cloud_backup);
        progressBar = findViewById(R.id.progress_cloud_backup);
        statusView = findViewById(R.id.text_cloud_status);

        // 仅从偏好读取目录地址与账号；应用密码仅在同一次进程会话内从内存回填。
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        urlInput.setText(prefs.getString(KEY_DIRECTORY_URL, ""));
        usernameInput.setText(prefs.getString(KEY_USERNAME, ""));
        if (!sSessionPassword.isEmpty()) {
            passwordInput.setText(sSessionPassword);
        }

        backupButton.setOnClickListener(v -> onBackupClicked());
        updateTransferUi(TRANSFER_IN_PROGRESS.get());
        if (TRANSFER_IN_PROGRESS.get()) {
            statusView.setText(R.string.cloud_backup_status_in_progress);
        }
    }

    // 页面退到后台时仅同步非敏感配置与内存会话密码，绝不将密码写入磁盘。
    @Override
    protected void onPause() {
        super.onPause();
        saveNonSecretConfig(this, urlInput.getText().toString(), usernameInput.getText().toString());
        sSessionPassword = passwordInput.getText().toString();
    }

    // 响应顶部返回箭头，关闭当前云备份页面回到笔记列表。
    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // 仅持久化目录地址与账号，供下次打开页面复用，不保存应用密码。
    static void saveNonSecretConfig(Context context, String rawUrl, String rawUsername) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        prefs.edit()
                .putString(KEY_DIRECTORY_URL, rawUrl == null ? "" : rawUrl.trim())
                .putString(KEY_USERNAME, rawUsername == null ? "" : rawUsername.trim())
                .apply();
    }

    // 校验输入表单、在后台构建全量笔记快照并弹出包含条数与覆盖说明的确认对话框。
    private void onBackupClicked() {
        if (preparingBackup || TRANSFER_IN_PROGRESS.get()) {
            return;
        }

        String rawUrl = urlInput.getText().toString().trim();
        String username = usernameInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        // 先规范并校验 HTTPS 目录地址，不合法时直接拦截并提示。
        String normalizedDirUrl;
        try {
            normalizedDirUrl = WebDavClient.normalizeDirectoryUrl(rawUrl);
        } catch (WebDavClient.WebDavException e) {
            showStatus(R.string.cloud_backup_error_invalid_url, true);
            urlInput.requestFocus();
            return;
        }
        if (username.isEmpty()) {
            showStatus(R.string.cloud_backup_error_empty_username, true);
            usernameInput.requestFocus();
            return;
        }
        if (password.isEmpty()) {
            showStatus(R.string.cloud_backup_error_empty_password, true);
            passwordInput.requestFocus();
            return;
        }

        // 回写规范化的目录地址，仅记住目录和账号，密码仅留存在当前会话内存。
        urlInput.setText(normalizedDirUrl);
        saveNonSecretConfig(this, normalizedDirUrl, username);
        sSessionPassword = password;

        final WebDavClient.Config config = new WebDavClient.Config(normalizedDirUrl, username, password);
        preparingBackup = true;
        updateTransferUi(true);
        statusView.setText(R.string.cloud_backup_status_preparing);

        // 在后台独立查询整本笔记（不受列表筛选影响），拿到准确条数后再请用户确认上传。
        final Context appContext = getApplicationContext();
        CLOUD_EXECUTOR.execute(() -> {
            NoteBackupFormat.BackupSnapshot snapshot;
            try {
                snapshot = NoteBackupFormat.createSnapshot(appContext.getContentResolver());
            } catch (IOException e) {
                Log.e(TAG, "Failed to read notes for cloud backup", e);
                MAIN_HANDLER.post(() -> {
                    preparingBackup = false;
                    if (!isActivityAlive()) {
                        return;
                    }
                    updateTransferUi(false);
                    showStatus(R.string.cloud_backup_error_read_notes, true);
                });
                return;
            }

            final NoteBackupFormat.BackupSnapshot readySnapshot = snapshot;
            MAIN_HANDLER.post(() -> {
                if (!isActivityAlive()) {
                    preparingBackup = false;
                    return;
                }
                showBackupConfirmDialog(config, readySnapshot);
            });
        });
    }

    // 弹出备份确认对话框，明确告知本次上传条数（含 0 条空库提示）及替换远端旧副本的效果。
    private void showBackupConfirmDialog(
            WebDavClient.Config config, NoteBackupFormat.BackupSnapshot snapshot) {
        int noteCount = snapshot.notes.size();
        String message = noteCount == 0
                ? getString(R.string.cloud_backup_confirm_empty, WebDavClient.BACKUP_FILE_NAME)
                : getString(R.string.cloud_backup_confirm_message, noteCount, WebDavClient.BACKUP_FILE_NAME);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.cloud_backup_confirm_title)
                .setMessage(message)
                .setPositiveButton(R.string.cloud_backup_confirm_positive, (d, which) ->
                        executeUpload(config, snapshot))
                .setNegativeButton(R.string.cloud_backup_confirm_cancel, (d, which) ->
                        handleBackupCancelled())
                .create();
        dialog.setOnCancelListener(d -> handleBackupCancelled());
        dialog.show();
    }

    // 处理用户取消备份确认对话框：恢复界面可交互状态并给出明确取消反馈。
    private void handleBackupCancelled() {
        preparingBackup = false;
        updateTransferUi(false);
        showStatus(R.string.cloud_backup_status_cancelled, true);
    }

    // 在后台线程编码 T07 快照并执行 WebDAV HTTPS PUT 上传，仅在服务端确认后报告成功。
    private void executeUpload(
            WebDavClient.Config config, NoteBackupFormat.BackupSnapshot snapshot) {
        if (!TRANSFER_IN_PROGRESS.compareAndSet(false, true)) {
            preparingBackup = false;
            return;
        }
        preparingBackup = false;

        final int noteCount = snapshot.notes.size();
        updateTransferUi(true);
        statusView.setText(getString(R.string.cloud_backup_status_uploading, noteCount));

        final Context appContext = getApplicationContext();
        CLOUD_EXECUTOR.execute(() -> {
            byte[] payload = NoteBackupFormat.encodeToUtf8Bytes(snapshot);
            WebDavClient.UploadResult uploadResult = null;
            WebDavClient.WebDavException uploadError = null;
            try {
                uploadResult = WebDavClient.uploadBackup(config, payload);
            } catch (WebDavClient.WebDavException e) {
                // 日志仅记录错误分类与状态码，绝不打印账号或应用密码。
                Log.e(TAG, "WebDAV upload failed: " + e.errorCode + ", http=" + e.httpStatus, e);
                uploadError = e;
            } finally {
                TRANSFER_IN_PROGRESS.set(false);
            }

            final WebDavClient.UploadResult finalResult = uploadResult;
            final WebDavClient.WebDavException finalError = uploadError;
            MAIN_HANDLER.post(() -> {
                String message = finalError == null
                        ? buildSuccessMessage(appContext, noteCount, finalResult)
                        : buildErrorMessage(appContext, finalError);
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
                if (!isActivityAlive()) {
                    return;
                }
                updateTransferUi(false);
                statusView.setText(message);
            });
        });
    }

    // 根据服务端返回的首次创建（201）或覆盖更新（200/204）状态组装中文成功提示。
    private static String buildSuccessMessage(
            Context context, int noteCount, WebDavClient.UploadResult result) {
        int resId = result != null && result.createdNew
                ? R.string.cloud_backup_success_created
                : R.string.cloud_backup_success_updated;
        return context.getString(resId, noteCount, WebDavClient.BACKUP_FILE_NAME);
    }

    // 将 WebDAV 异常错误码映射为面向用户的明确中文失败提示。
    private static String buildErrorMessage(Context context, WebDavClient.WebDavException error) {
        switch (error.errorCode) {
            case INVALID_URL:
                return context.getString(R.string.cloud_backup_error_invalid_url);
            case INVALID_CREDENTIALS:
                return context.getString(R.string.cloud_backup_error_empty_password);
            case AUTH_FAILED:
                return context.getString(R.string.cloud_backup_error_auth);
            case DIRECTORY_UNAVAILABLE:
                return context.getString(R.string.cloud_backup_error_directory);
            case NETWORK_ERROR:
                return context.getString(R.string.cloud_backup_error_network);
            case SERVER_ERROR:
            default:
                return context.getString(R.string.cloud_backup_error_server, error.httpStatus);
        }
    }

    // 统一控制进度条显示及输入框、备份按钮的可用状态，防止传输期间修改参数或重复提交。
    private void updateTransferUi(boolean busy) {
        urlInput.setEnabled(!busy);
        usernameInput.setEnabled(!busy);
        passwordInput.setEnabled(!busy);
        backupButton.setEnabled(!busy);
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
    }

    // 更新状态文本并在需要时弹出短提示。
    private void showStatus(int messageResId, boolean showToast) {
        statusView.setText(messageResId);
        if (showToast) {
            Toast.makeText(this, messageResId, Toast.LENGTH_SHORT).show();
        }
    }

    // 判断当前 Activity 是否仍处于可安全更新界面与弹窗的生命周期状态。
    private boolean isActivityAlive() {
        return !isFinishing() && !isDestroyed();
    }
}

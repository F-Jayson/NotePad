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
// 支持手动备份整本 JSON 快照到云端，以及从云端下载、校验并在确认后事务补录缺失笔记。
public class CloudBackupActivity extends Activity {

    private static final String TAG = "CloudBackupActivity";

    // 本地偏好文件名及仅保存非敏感配置的键；应用密码绝不写入本地存储。
    static final String PREFS_NAME = "cloud_backup_prefs";
    static final String KEY_DIRECTORY_URL = "directory_url";
    static final String KEY_USERNAME = "username";

    // 独立单线程后台执行器与主线程回调句柄，用于数据库读写和 HTTPS 传输。
    private static final ExecutorService CLOUD_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN_HANDLER = new Handler(Looper.getMainLooper());

    // 进程级传输锁，防止界面重复点击或重进页面后同时发起多次冲突的云端传输或写库操作。
    private static final AtomicBoolean TRANSFER_IN_PROGRESS = new AtomicBoolean(false);
    // 仅驻留当前应用进程内存的应用密码，进程结束即自动清除，不落盘也不进状态 Bundle。
    private static String sSessionPassword = "";

    private EditText urlInput;
    private EditText usernameInput;
    private EditText passwordInput;
    private Button backupButton;
    private Button restoreButton;
    private ProgressBar progressBar;
    private TextView statusView;

    // 标记当前是否正在准备快照或等待用户确认对话框，避免连点弹出多个确认框。
    private boolean preparingOperation;

    // 初始化页面视图、恢复已记住的目录地址与账号，并绑定备份与恢复按钮点击事件。
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
        restoreButton = findViewById(R.id.button_cloud_restore);
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
        restoreButton.setOnClickListener(v -> onRestoreClicked());
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

    // 响应顶部返回箭头，关闭当前云备份与恢复页面回到笔记列表。
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

    // 统一校验并读取表单中的 HTTPS 目录地址、账号与应用密码；校验不通过时提示并返回 null。
    private WebDavClient.Config readValidatedConfig() {
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
            return null;
        }
        if (username.isEmpty()) {
            showStatus(R.string.cloud_backup_error_empty_username, true);
            usernameInput.requestFocus();
            return null;
        }
        if (password.isEmpty()) {
            showStatus(R.string.cloud_backup_error_empty_password, true);
            passwordInput.requestFocus();
            return null;
        }

        // 回写规范化的目录地址，仅记住目录和账号，密码仅留存在当前会话内存。
        urlInput.setText(normalizedDirUrl);
        saveNonSecretConfig(this, normalizedDirUrl, username);
        sSessionPassword = password;
        return new WebDavClient.Config(normalizedDirUrl, username, password);
    }

    // 响应“备份到云端”按钮：校验表单并在后台构建全量笔记快照，再弹窗确认上传。
    private void onBackupClicked() {
        if (preparingOperation || TRANSFER_IN_PROGRESS.get()) {
            return;
        }
        final WebDavClient.Config config = readValidatedConfig();
        if (config == null) {
            return;
        }

        preparingOperation = true;
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
                    preparingOperation = false;
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
                    preparingOperation = false;
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
                        handleOperationCancelled(R.string.cloud_backup_status_cancelled))
                .create();
        dialog.setOnCancelListener(d ->
                handleOperationCancelled(R.string.cloud_backup_status_cancelled));
        dialog.show();
    }

    // 在后台线程编码 T07 快照并执行 WebDAV HTTPS PUT 上传，仅在服务端确认后报告成功。
    private void executeUpload(
            WebDavClient.Config config, NoteBackupFormat.BackupSnapshot snapshot) {
        if (!TRANSFER_IN_PROGRESS.compareAndSet(false, true)) {
            preparingOperation = false;
            return;
        }
        preparingOperation = false;

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
                        ? buildBackupSuccessMessage(appContext, noteCount, finalResult)
                        : buildBackupErrorMessage(appContext, finalError);
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
                if (!isActivityAlive()) {
                    return;
                }
                updateTransferUi(false);
                statusView.setText(message);
            });
        });
    }

    // 响应“从云端恢复”按钮：下载远端 notepad-backup.json 并用 T07 校验，通过后再弹窗请用户确认写库。
    private void onRestoreClicked() {
        if (preparingOperation || TRANSFER_IN_PROGRESS.get()) {
            return;
        }
        final WebDavClient.Config config = readValidatedConfig();
        if (config == null) {
            return;
        }
        if (!TRANSFER_IN_PROGRESS.compareAndSet(false, true)) {
            return;
        }

        preparingOperation = true;
        updateTransferUi(true);
        statusView.setText(getString(
                R.string.cloud_restore_status_downloading, WebDavClient.BACKUP_FILE_NAME));

        final Context appContext = getApplicationContext();
        CLOUD_EXECUTOR.execute(() -> {
            NoteBackupFormat.BackupSnapshot snapshot = null;
            String errorMessage = null;
            try {
                // 先下载完整字节流，再调用 T07 严格校验版本、数组与四字段类型，全程不触碰数据库。
                byte[] rawBytes = WebDavClient.downloadBackup(config);
                snapshot = NoteBackupFormat.decodeFromUtf8Bytes(rawBytes);
            } catch (WebDavClient.WebDavException e) {
                Log.e(TAG, "WebDAV download failed: " + e.errorCode + ", http=" + e.httpStatus, e);
                errorMessage = buildRestoreDownloadErrorMessage(appContext, e);
            } catch (NoteBackupFormat.BackupFormatException e) {
                Log.e(TAG, "Backup validation failed: " + e.errorCode, e);
                errorMessage = buildRestoreFormatErrorMessage(appContext, e);
            } finally {
                TRANSFER_IN_PROGRESS.set(false);
            }

            final NoteBackupFormat.BackupSnapshot validatedSnapshot = snapshot;
            final String finalErrorMessage = errorMessage;
            MAIN_HANDLER.post(() -> {
                if (finalErrorMessage != null) {
                    preparingOperation = false;
                    Toast.makeText(appContext, finalErrorMessage, Toast.LENGTH_LONG).show();
                    if (!isActivityAlive()) {
                        return;
                    }
                    updateTransferUi(false);
                    statusView.setText(finalErrorMessage);
                    return;
                }

                if (!isActivityAlive()) {
                    preparingOperation = false;
                    return;
                }
                showRestoreConfirmDialog(validatedSnapshot);
            });
        });
    }

    // 弹出恢复确认对话框，展示已校验的备份条数及“保留本地、仅补入缺项”的合并规则。
    private void showRestoreConfirmDialog(NoteBackupFormat.BackupSnapshot snapshot) {
        int backupCount = snapshot.notes.size();
        String message = backupCount == 0
                ? getString(R.string.cloud_restore_confirm_empty)
                : getString(R.string.cloud_restore_confirm_message, backupCount);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(R.string.cloud_restore_confirm_title)
                .setMessage(message)
                .setPositiveButton(R.string.cloud_restore_confirm_positive, (d, which) ->
                        executeRestoreWrite(snapshot))
                .setNegativeButton(R.string.cloud_backup_confirm_cancel, (d, which) ->
                        handleOperationCancelled(R.string.cloud_restore_status_cancelled))
                .create();
        dialog.setOnCancelListener(d ->
                handleOperationCancelled(R.string.cloud_restore_status_cancelled));
        dialog.show();
    }

    // 在后台线程调用 Provider 单事务恢复入口补入缺失笔记，写库失败自动回滚并保留本地原数据。
    private void executeRestoreWrite(NoteBackupFormat.BackupSnapshot snapshot) {
        if (!TRANSFER_IN_PROGRESS.compareAndSet(false, true)) {
            preparingOperation = false;
            return;
        }
        preparingOperation = false;

        updateTransferUi(true);
        statusView.setText(R.string.cloud_restore_status_writing);

        final Context appContext = getApplicationContext();
        CLOUD_EXECUTOR.execute(() -> {
            NoteBackupFormat.RestoreResult restoreResult = null;
            IOException writeError = null;
            try {
                restoreResult = NoteBackupFormat.restoreMissingNotes(
                        appContext.getContentResolver(), snapshot);
            } catch (IOException e) {
                Log.e(TAG, "Failed to restore notes into local database", e);
                writeError = e;
            } finally {
                TRANSFER_IN_PROGRESS.set(false);
            }

            final NoteBackupFormat.RestoreResult finalResult = restoreResult;
            final IOException finalError = writeError;
            MAIN_HANDLER.post(() -> {
                String message = finalError == null
                        ? buildRestoreSuccessMessage(appContext, finalResult)
                        : appContext.getString(R.string.cloud_restore_error_write_failed);
                Toast.makeText(appContext, message, Toast.LENGTH_LONG).show();
                if (!isActivityAlive()) {
                    return;
                }
                updateTransferUi(false);
                statusView.setText(message);
            });
        });
    }

    // 处理用户取消备份或恢复确认对话框：恢复界面可交互状态并给出明确取消反馈。
    private void handleOperationCancelled(int messageResId) {
        preparingOperation = false;
        updateTransferUi(false);
        showStatus(messageResId, true);
    }

    // 根据服务端返回的首次创建（201）或覆盖更新（200/204）状态组装中文备份成功提示。
    private static String buildBackupSuccessMessage(
            Context context, int noteCount, WebDavClient.UploadResult result) {
        int resId = result != null && result.createdNew
                ? R.string.cloud_backup_success_created
                : R.string.cloud_backup_success_updated;
        return context.getString(resId, noteCount, WebDavClient.BACKUP_FILE_NAME);
    }

    // 将备份上传的 WebDAV 异常错误码映射为面向用户的中文失败提示。
    private static String buildBackupErrorMessage(Context context, WebDavClient.WebDavException error) {
        switch (error.errorCode) {
            case INVALID_URL:
                return context.getString(R.string.cloud_backup_error_invalid_url);
            case INVALID_CREDENTIALS:
                return context.getString(R.string.cloud_backup_error_empty_password);
            case AUTH_FAILED:
                return context.getString(R.string.cloud_backup_error_auth);
            case DIRECTORY_UNAVAILABLE:
            case BACKUP_NOT_FOUND:
                return context.getString(R.string.cloud_backup_error_directory);
            case NETWORK_ERROR:
                return context.getString(R.string.cloud_backup_error_network);
            case SERVER_ERROR:
            default:
                return context.getString(R.string.cloud_backup_error_server, error.httpStatus);
        }
    }

    // 将恢复下载的 WebDAV 异常错误码映射为中文失败提示，单独区分远端无备份文件（404）。
    private static String buildRestoreDownloadErrorMessage(
            Context context, WebDavClient.WebDavException error) {
        switch (error.errorCode) {
            case INVALID_URL:
                return context.getString(R.string.cloud_backup_error_invalid_url);
            case INVALID_CREDENTIALS:
                return context.getString(R.string.cloud_backup_error_empty_password);
            case BACKUP_NOT_FOUND:
                return context.getString(
                        R.string.cloud_restore_error_not_found, WebDavClient.BACKUP_FILE_NAME);
            case AUTH_FAILED:
                return context.getString(R.string.cloud_restore_error_auth);
            case DIRECTORY_UNAVAILABLE:
                return context.getString(R.string.cloud_restore_error_directory);
            case NETWORK_ERROR:
                return context.getString(R.string.cloud_restore_error_network);
            case SERVER_ERROR:
            default:
                return context.getString(R.string.cloud_restore_error_server, error.httpStatus);
        }
    }

    // 将 T07 格式校验失败错误码映射为中文恢复提示，区分未知版本与损坏/字段异常。
    private static String buildRestoreFormatErrorMessage(
            Context context, NoteBackupFormat.BackupFormatException error) {
        if (error.errorCode == NoteBackupFormat.ErrorCode.UNSUPPORTED_VERSION) {
            return context.getString(R.string.cloud_restore_error_unsupported_version);
        }
        return context.getString(R.string.cloud_restore_error_invalid_format);
    }

    // 根据事务补录统计组装中文恢复完成提示。
    private static String buildRestoreSuccessMessage(
            Context context, NoteBackupFormat.RestoreResult result) {
        if (result.restoredCount == 0) {
            return context.getString(R.string.cloud_restore_success_none, result.totalBackupCount);
        }
        return context.getString(
                R.string.cloud_restore_success_added, result.restoredCount, result.skippedCount);
    }

    // 统一控制进度条显示及输入框、操作按钮的可用状态，防止传输期间修改参数或重复提交。
    private void updateTransferUi(boolean busy) {
        urlInput.setEnabled(!busy);
        usernameInput.setEnabled(!busy);
        passwordInput.setEnabled(!busy);
        backupButton.setEnabled(!busy);
        restoreButton.setEnabled(!busy);
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

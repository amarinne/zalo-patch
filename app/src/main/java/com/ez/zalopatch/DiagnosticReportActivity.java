package com.ez.zalopatch;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.preference.PreferenceCategory;
import androidx.preference.PreferenceScreen;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.json.JSONObject;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** User-triggered diagnostic report page. */
public final class DiagnosticReportActivity {
    private DiagnosticReportActivity() {
    }

    static boolean requiresGuidedCapture(
            String category, boolean zaloPresent, boolean schemaValid,
            boolean selfCheckPresent, int failed, int stale) {
        if (!"compatibility".equals(category)) return true;
        return !zaloPresent || !schemaValid || !selfCheckPresent || failed > 0 || stale > 0;
    }

    static boolean canUpload(String reportId, String reviewedReportId) {
        return reportId != null && reportId.equals(reviewedReportId);
    }

    public static final class ReportFragment extends ZpPreferenceFragment {
        private static final int REQUEST_EXPORT = 5101;
        private static final String[] CATEGORY_VALUES = new String[]{
                "compatibility", "hook_behavior", "ui_behavior", "notifications",
                "call_recording", "crash_restart", "configuration", "other"
        };
        private static final int[] CATEGORY_LABELS = new int[]{
                R.string.zp_diagnostic_category_compatibility,
                R.string.zp_diagnostic_category_hooks,
                R.string.zp_diagnostic_category_ui,
                R.string.zp_diagnostic_category_notifications,
                R.string.zp_diagnostic_category_calls,
                R.string.zp_diagnostic_category_crash,
                R.string.zp_diagnostic_category_configuration,
                R.string.zp_diagnostic_category_other
        };

        private String category = CATEGORY_VALUES[0];
        private String description = "";
        private String statusMessage = "";
        private String successfulReportId;
        private String successfulJson;
        private String pendingExportJson;
        private String pendingExportName;
        private String reviewedReportId;
        private boolean busy;
        private DiagnosticCaptureManager.Session session;
        private DiagnosticReportFactory.Draft draft;
        private ZpRowPreference reportAction;
        private ZpRowPreference secondaryReportAction;

        @Override
        public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
            if (savedInstanceState != null) {
                category = savedInstanceState.getString("category", CATEGORY_VALUES[0]);
                description = savedInstanceState.getString("description", "");
                statusMessage = savedInstanceState.getString("status", "");
                successfulReportId = savedInstanceState.getString("success_id");
                successfulJson = savedInstanceState.getString("success_json");
                pendingExportJson = savedInstanceState.getString("export_json");
                pendingExportName = savedInstanceState.getString("export_name");
                reviewedReportId = savedInstanceState.getString("reviewed_report_id");
            }
            loadLocalState();
            buildScreen();
        }

        @Override
        public void onResume() {
            super.onResume();
            cleanupExpiredCapture();
        }

        @Override
        public void onSaveInstanceState(Bundle outState) {
            outState.putString("category", category);
            outState.putString("description", description);
            outState.putString("status", statusMessage);
            outState.putString("success_id", successfulReportId);
            outState.putString("success_json", successfulJson);
            outState.putString("export_json", pendingExportJson);
            outState.putString("export_name", pendingExportName);
            outState.putString("reviewed_report_id", reviewedReportId);
            super.onSaveInstanceState(outState);
        }

        @Override
        public void onActivityResult(int requestCode, int resultCode, Intent data) {
            super.onActivityResult(requestCode, resultCode, data);
            if (requestCode != REQUEST_EXPORT) return;
            String json = pendingExportJson;
            pendingExportJson = null;
            pendingExportName = null;
            if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null
                    || json == null) {
                return;
            }
            Uri uri = data.getData();
            Context context = requireContext().getApplicationContext();
            new Thread(() -> {
                boolean saved = false;
                try (OutputStream output = context.getContentResolver()
                        .openOutputStream(uri, "w")) {
                    if (output != null) {
                        output.write(json.getBytes(StandardCharsets.UTF_8));
                        saved = true;
                    }
                } catch (Exception ignored) {
                }
                boolean finalSaved = saved;
                post(() -> Toast.makeText(requireContext(), finalSaved
                                ? R.string.zp_diagnostic_json_saved
                                : R.string.zp_diagnostic_json_save_failed,
                        Toast.LENGTH_SHORT).show());
            }, "diagnostic-json-export").start();
        }

        private StatusActivity host() {
            return (StatusActivity) requireActivity();
        }

        private void loadLocalState() {
            session = DiagnosticCaptureManager.current(requireContext());
            draft = DiagnosticDraftStore.load(requireContext());
            if (session != null) {
                category = session.category;
                description = session.description;
            } else if (draft != null) {
                category = draft.category;
                description = draft.description;
            }
        }

        private void buildScreen() {
            if (!isAdded()) return;
            Context context = requireContext();
            reportAction = null;
            secondaryReportAction = null;
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(context);

            PreferenceCategory problemCategory = PreferenceUi.category(screen,
                    getString(R.string.zp_diagnostic_problem_group));
            ZpSection problem = ZpSection.in(problemCategory);
            ZpRowPreference categoryRow = PreferenceUi.action(context,
                    getString(R.string.zp_diagnostic_category), categoryLabel(category));
            categoryRow.setEnabled(!busy && session == null && successfulReportId == null);
            categoryRow.setOnPreferenceClickListener(preference -> {
                showCategoryDialog();
                return true;
            });
            problem.add(categoryRow);
            ZpDiagnosticDescriptionPreference descriptionField =
                    new ZpDiagnosticDescriptionPreference(context);
            descriptionField.setKey("internal.diagnostic_description");
            descriptionField.value(description);
            descriptionField.inputEnabled(
                    !busy && session == null && draft == null && successfulReportId == null);
            descriptionField.listener(value -> {
                boolean preparedState = draft != null || successfulReportId != null;
                description = value;
                statusMessage = "";
                if (preparedState) {
                    invalidateDraft();
                    buildScreen();
                } else if (reportAction != null) {
                    boolean valid = validDescription();
                    reportAction.setEnabled(valid);
                    if (secondaryReportAction != null) {
                        secondaryReportAction.setEnabled(valid);
                    }
                }
            });
            problem.add(descriptionField);

            if (busy || session != null || draft != null || successfulReportId == null) {
                PreferenceCategory actionCategory = PreferenceUi.category(screen,
                        getString(session != null ? R.string.zp_diagnostic_capture_group
                                : draft != null ? R.string.zp_diagnostic_review_group
                                : requiresGuidedCapture()
                                ? R.string.zp_diagnostic_capture_group
                                : R.string.zp_diagnostic_report_group));
                ZpSection actions = ZpSection.in(actionCategory);
                if (busy) {
                    actions.add(PreferenceUi.info(context,
                            getString(R.string.zp_diagnostic_working), statusMessage));
                } else if (session != null) {
                    actions.add(PreferenceUi.info(context,
                            getString(R.string.zp_diagnostic_capture_active),
                            getString(!session.debugLoggingManaged
                                    ? R.string.zp_diagnostic_metadata_only_capture_active_summary
                                    : "compatibility".equals(session.category)
                                    ? SymbolSchema.active(context).valid
                                    ? R.string.zp_diagnostic_compatibility_mapped_capture_active_summary
                                    : R.string.zp_diagnostic_compatibility_capture_active_summary
                                    : R.string.zp_diagnostic_capture_active_summary)));
                    ZpRowPreference restart = PreferenceUi.action(context,
                            getString(session.debugLoggingManaged
                                    ? R.string.zp_diagnostic_restart_zalo
                                    : R.string.zp_open_zalo_app_info),
                            getString(session.debugLoggingManaged
                                    ? R.string.zp_diagnostic_restart_zalo_summary
                                    : R.string.zp_open_zalo_app_info_summary));
                    restart.setOnPreferenceClickListener(preference -> {
                        if (session.debugLoggingManaged) host().restartZalo();
                        else host().openZaloAppInfo();
                        return true;
                    });
                    actions.add(restart);
                    ZpRowPreference finish = PreferenceUi.action(context,
                            getString(R.string.zp_diagnostic_finish_capture));
                    finish.setOnPreferenceClickListener(preference -> {
                        finishCapture();
                        return true;
                    });
                    actions.add(finish);
                    ZpRowPreference cancel = PreferenceUi.action(context,
                            getString(R.string.zp_diagnostic_cancel_capture));
                    cancel.setOnPreferenceClickListener(preference -> {
                        cancelCapture();
                        return true;
                    });
                    actions.add(cancel);
                } else if (draft == null) {
                    boolean guided = requiresGuidedCapture();
                    if (guided) {
                        actions.add(PreferenceUi.info(context,
                                getString("compatibility".equals(category)
                                        ? compatibilityTitleRes()
                                        : R.string.zp_diagnostic_capture_explanation_title),
                                getString("compatibility".equals(category)
                                        ? compatibilitySummaryRes()
                                        : R.string.zp_diagnostic_capture_explanation)));
                    }
                    reportAction = PreferenceUi.action(context,
                            getString(guided ? R.string.zp_diagnostic_start_capture
                                    : R.string.zp_diagnostic_prepare_report),
                            getString(guided ? R.string.zp_diagnostic_start_capture_summary
                                    : R.string.zp_diagnostic_prepare_report_summary));
                    reportAction.setKey(guided ? "internal.diagnostic_start"
                            : "internal.diagnostic_prepare");
                    reportAction.setEnabled(validDescription());
                    reportAction.setOnPreferenceClickListener(preference -> {
                        if (guided) startCapture();
                        else prepareMetadataReport();
                        return true;
                    });
                    actions.add(reportAction);
                    if (guided) {
                        secondaryReportAction = PreferenceUi.action(context,
                                getString(R.string.zp_diagnostic_prepare_report),
                                getString(R.string.zp_diagnostic_prepare_report_summary));
                        secondaryReportAction.setKey("internal.diagnostic_prepare");
                        secondaryReportAction.setEnabled(validDescription());
                        secondaryReportAction.setOnPreferenceClickListener(preference -> {
                            prepareMetadataReport();
                            return true;
                        });
                        actions.add(secondaryReportAction);
                    }
                } else {
                    actions.add(PreferenceUi.info(context,
                            getString(R.string.zp_diagnostic_setup_title),
                            setupChecklist(draft)));
                    ZpRowPreference preview = PreferenceUi.action(context,
                            getString(R.string.zp_diagnostic_view_json));
                    preview.setOnPreferenceClickListener(preference -> {
                        showJson(draft.json, draft.reportId);
                        return true;
                    });
                    actions.add(preview);
                    ZpRowPreference upload = PreferenceUi.action(context,
                            getString(R.string.zp_diagnostic_upload));
                    upload.setEnabled(DiagnosticReportActivity.canUpload(
                            draft.reportId, reviewedReportId));
                    upload.setOnPreferenceClickListener(preference -> {
                        upload();
                        return true;
                    });
                    ZpRowPreference discard = PreferenceUi.action(context,
                            getString(R.string.zp_diagnostic_discard));
                    discard.destructive(true);
                    discard.setOnPreferenceClickListener(preference -> {
                        DiagnosticDraftStore.clear(requireContext());
                        draft = null;
                        reviewedReportId = null;
                        statusMessage = getString(R.string.zp_diagnostic_discarded);
                        buildScreen();
                        return true;
                    });
                    actions.add(discard);
                    actions.add(upload);
                }
            }

            if (successfulReportId != null || !statusMessage.isEmpty()) {
                PreferenceCategory statusCategory = PreferenceUi.category(screen,
                        getString(R.string.zp_diagnostic_status_group));
                ZpSection status = ZpSection.in(statusCategory);
                if (successfulReportId != null) {
                    status.add(PreferenceUi.info(context,
                            getString(R.string.zp_diagnostic_report_id), successfulReportId));
                    if (successfulJson != null) {
                        ZpRowPreference view = PreferenceUi.action(context,
                                getString(R.string.zp_diagnostic_view_uploaded_json));
                        view.setOnPreferenceClickListener(preference -> {
                            showJson(successfulJson);
                            return true;
                        });
                        status.add(view);
                        ZpRowPreference save = PreferenceUi.action(context,
                                getString(R.string.zp_diagnostic_save_json));
                        save.setOnPreferenceClickListener(preference -> {
                            exportJson(successfulJson, successfulReportId);
                            return true;
                        });
                        status.add(save);
                    }
                    ZpRowPreference copy = PreferenceUi.action(context,
                            getString(R.string.zp_diagnostic_copy_id));
                    copy.setOnPreferenceClickListener(preference -> {
                        copyReportId();
                        return true;
                    });
                    status.add(copy);
                }
                if (!statusMessage.isEmpty()) {
                    status.add(PreferenceUi.info(context,
                            getString(R.string.zp_diagnostic_status), statusMessage));
                }
            }
            setPreferenceScreen(screen);
        }

        private void showCategoryDialog() {
            CharSequence[] labels = new CharSequence[CATEGORY_LABELS.length];
            int checked = 0;
            for (int index = 0; index < labels.length; index++) {
                labels[index] = getString(CATEGORY_LABELS[index]);
                if (CATEGORY_VALUES[index].equals(category)) checked = index;
            }
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.zp_diagnostic_category)
                    .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                        if (!CATEGORY_VALUES[which].equals(category)) {
                            category = CATEGORY_VALUES[which];
                            invalidateDraft();
                        }
                        dialog.dismiss();
                        buildScreen();
                    })
                    .setNegativeButton(R.string.zp_cancel, null)
                    .show();
        }

        private void prepareMetadataReport() {
            Context context = requireContext().getApplicationContext();
            runBusy(() -> {
                DiagnosticReportFactory.Draft prepared = null;
                try {
                    prepared = DiagnosticReportFactory.createMetadataOnly(
                            context, category, description);
                    if (!DiagnosticDraftStore.save(context, prepared)) prepared = null;
                } catch (Exception ignored) {
                    prepared = null;
                }
                DiagnosticReportFactory.Draft finalPrepared = prepared;
                post(() -> {
                    draft = finalPrepared;
                    reviewedReportId = null;
                    statusMessage = getString(finalPrepared == null
                            ? R.string.zp_diagnostic_prepare_failed
                            : R.string.zp_diagnostic_report_prepared);
                    finishBusy();
                });
            });
        }

        private void startCapture() {
            Context context = requireContext().getApplicationContext();
            runBusy(() -> {
                DiagnosticCaptureManager.StartResult result = DiagnosticCaptureManager.start(
                        context, category, description);
                post(() -> {
                    if (result.successful()) {
                        session = result.session;
                        draft = null;
                        reviewedReportId = null;
                        statusMessage = getString(result.session.debugLoggingManaged
                                ? R.string.zp_diagnostic_capture_started
                                : R.string.zp_diagnostic_metadata_only_capture_started);
                    } else {
                        session = DiagnosticCaptureManager.current(context);
                        statusMessage = startFailureMessage(result.failure);
                    }
                    finishBusy();
                });
            });
        }

        private void finishCapture() {
            Context context = requireContext().getApplicationContext();
            runBusy(() -> {
                DiagnosticReportFactory.Draft prepared = null;
                DiagnosticCaptureManager.FinishedCapture finished =
                        DiagnosticCaptureManager.finish(context);
                if (finished != null) {
                    try {
                        prepared = DiagnosticReportFactory.createCaptured(context, finished);
                        if (!DiagnosticDraftStore.save(context, prepared)) prepared = null;
                    } catch (Exception ignored) {
                        prepared = null;
                    }
                }
                DiagnosticReportFactory.Draft finalPrepared = prepared;
                post(() -> {
                    session = DiagnosticCaptureManager.current(context);
                    draft = finalPrepared;
                    reviewedReportId = null;
                    statusMessage = getString(finalPrepared != null
                            ? R.string.zp_diagnostic_capture_ready
                            : session != null
                            ? R.string.zp_diagnostic_restore_failed
                            : R.string.zp_diagnostic_prepare_failed);
                    finishBusy();
                });
            });
        }

        private void cancelCapture() {
            Context context = requireContext().getApplicationContext();
            runBusy(() -> {
                boolean cancelled = DiagnosticCaptureManager.cancel(context);
                post(() -> {
                    session = DiagnosticCaptureManager.current(context);
                    statusMessage = getString(cancelled
                            ? R.string.zp_diagnostic_capture_cancelled
                            : R.string.zp_diagnostic_restore_failed);
                    finishBusy();
                });
            });
        }

        private void upload() {
            DiagnosticReportFactory.Draft expected = draft;
            if (expected == null || !DiagnosticReportActivity.canUpload(
                    expected.reportId, reviewedReportId)) return;
            Context context = requireContext().getApplicationContext();
            runBusy(() -> {
                DiagnosticReportFactory.Draft current = DiagnosticDraftStore.load(context);
                DiagnosticUploader.Result result = current == null
                        || !expected.reportId.equals(current.reportId)
                        ? DiagnosticUploader.Result.failure(DiagnosticUploader.Kind.INVALID_REPORT)
                        : new DiagnosticUploader(BuildConfig.DIAGNOSTIC_INTAKE_URL).upload(current);
                post(() -> {
                    if (result.successful()) {
                        successfulReportId = result.receipt.reportId;
                        successfulJson = expected.json;
                        DiagnosticDraftStore.clear(context);
                        draft = null;
                        reviewedReportId = null;
                        statusMessage = getString(R.string.zp_diagnostic_upload_success);
                    } else {
                        if (current == null) draft = null;
                        statusMessage = uploadFailureMessage(result.failure);
                    }
                    finishBusy();
                });
            });
        }

        private void showJson(String json) {
            showJson(json, null);
        }

        private void showJson(String json, String reviewReportId) {
            TextView text = new TextView(requireContext());
            int padding = Math.round(16 * getResources().getDisplayMetrics().density);
            text.setPadding(padding, padding, padding, padding);
            text.setTypeface(Typeface.MONOSPACE);
            text.setTextIsSelectable(true);
            text.setText(DiagnosticReportFactory.prettyJson(json));
            ScrollView scroll = new ScrollView(requireContext());
            scroll.addView(text);
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.zp_diagnostic_included_json)
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                        if (reviewReportId != null && draft != null
                                && reviewReportId.equals(draft.reportId)) {
                            reviewedReportId = reviewReportId;
                            buildScreen();
                        }
                    })
                    .show();
        }

        private String setupChecklist(DiagnosticReportFactory.Draft report) {
            try {
                JSONObject checks = new JSONObject(report.json)
                        .getJSONObject("productMetadata")
                        .getJSONObject("setupChecks");
                String state = checks.optString("setupState", "warning");
                StringBuilder value = new StringBuilder(getString(
                        "ready".equals(state) ? R.string.zp_diagnostic_setup_ready
                                : "failed".equals(state) ? R.string.zp_diagnostic_setup_failed
                                : R.string.zp_diagnostic_setup_warning));
                value.append('\n').append(checkLine(
                        checks.optBoolean("zaloPackagePresent", false),
                        getString(R.string.zp_diagnostic_check_zalo)));
                value.append('\n').append(checkLine(
                        checks.optBoolean("symbolSchemaValid", false),
                        getString(R.string.zp_diagnostic_check_schema)));
                value.append('\n').append(checkLine(
                        checks.optBoolean("runtimeSelfCheckPresent", false)
                                && checks.optInt("failedCount", 0) == 0
                                && checks.optInt("staleCount", 0) == 0,
                        getString(R.string.zp_diagnostic_check_self_check)));
                value.append('\n').append(checkLine(
                        checks.optBoolean("internetPermissionGranted", false),
                        getString(R.string.zp_diagnostic_check_internet)));
                String root = checks.optString("rootAccessStatus", "not_checked");
                value.append('\n');
                if ("not_checked".equals(root)) {
                    value.append("- ").append(getString(
                            R.string.zp_diagnostic_check_root_not_checked));
                } else {
                    value.append(checkLine("granted".equals(root),
                            getString(R.string.zp_diagnostic_check_root)));
                }
                return value.toString();
            } catch (Exception ignored) {
                return getString(R.string.zp_diagnostic_setup_warning);
            }
        }

        private static String checkLine(boolean passed, String label) {
            return (passed ? "OK: " : "FAILED: ") + label;
        }

        private boolean requiresGuidedCapture() {
            Context context = requireContext();
            boolean zaloPresent;
            try {
                context.getPackageManager().getPackageInfo("com.zing.zalo", 0);
                zaloPresent = true;
            } catch (PackageManager.NameNotFoundException ignored) {
                zaloPresent = false;
            }
            SymbolSchema.Active schema = SymbolSchema.active(context);
            java.util.List<SelfCheckData.Row> rows = SelfCheckData.load(context);
            SelfCheckData.Counts counts = SelfCheckData.counts(rows);
            return DiagnosticReportActivity.requiresGuidedCapture(category, zaloPresent,
                    schema.valid, !rows.isEmpty(), counts.failed, counts.stale);
        }

        private int compatibilityTitleRes() {
            return SymbolSchema.active(requireContext()).valid
                    ? R.string.zp_diagnostic_compatibility_mapped_title
                    : R.string.zp_diagnostic_compatibility_unmapped_title;
        }

        private int compatibilitySummaryRes() {
            return SymbolSchema.active(requireContext()).valid
                    ? R.string.zp_diagnostic_compatibility_mapped_summary
                    : R.string.zp_diagnostic_compatibility_unmapped_summary;
        }

        private void exportJson(String json, String reportId) {
            pendingExportJson = DiagnosticReportFactory.prettyJson(json);
            pendingExportName = "ZaloPatch-" + reportId + ".json";
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT)
                    .addCategory(Intent.CATEGORY_OPENABLE)
                    .setType("application/json")
                    .putExtra(Intent.EXTRA_TITLE, pendingExportName);
            startActivityForResult(intent, REQUEST_EXPORT);
        }

        private void copyReportId() {
            ClipboardManager clipboard = (ClipboardManager) requireContext()
                    .getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard != null && successfulReportId != null) {
                clipboard.setPrimaryClip(ClipData.newPlainText(
                        getString(R.string.zp_diagnostic_clip_label), successfulReportId));
                Toast.makeText(requireContext(), R.string.zp_diagnostic_id_copied,
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void cleanupExpiredCapture() {
            Context context = requireContext().getApplicationContext();
            new Thread(() -> {
                boolean expired = DiagnosticCaptureManager.expireIfNeeded(context);
                post(() -> {
                    loadLocalState();
                    if (expired) statusMessage = getString(R.string.zp_diagnostic_capture_expired);
                    buildScreen();
                });
            }, "diagnostic-capture-cleanup").start();
        }

        private void invalidateDraft() {
            if (draft != null) DiagnosticDraftStore.clear(requireContext());
            draft = null;
            reviewedReportId = null;
            successfulReportId = null;
            successfulJson = null;
            statusMessage = "";
        }

        private boolean validDescription() {
            return DiagnosticReportContract.validDescription(description);
        }

        private String categoryLabel(String value) {
            for (int index = 0; index < CATEGORY_VALUES.length; index++) {
                if (CATEGORY_VALUES[index].equals(value)) return getString(CATEGORY_LABELS[index]);
            }
            return getString(R.string.zp_diagnostic_category_other);
        }

        private String startFailureMessage(DiagnosticCaptureManager.StartFailure failure) {
            if (failure == DiagnosticCaptureManager.StartFailure.ROOT_DENIED) {
                return getString(R.string.zp_diagnostic_root_denied);
            }
            if (failure == DiagnosticCaptureManager.StartFailure.RESTORE_FAILED) {
                return getString(R.string.zp_diagnostic_restore_failed);
            }
            return getString(R.string.zp_diagnostic_capture_start_failed);
        }

        private String uploadFailureMessage(DiagnosticUploader.Kind kind) {
            if (kind == DiagnosticUploader.Kind.REPORT_ID_COLLISION) {
                return getString(R.string.zp_diagnostic_failure_collision);
            }
            if (kind == DiagnosticUploader.Kind.REQUEST_TOO_LARGE) {
                return getString(R.string.zp_diagnostic_failure_too_large);
            }
            if (kind == DiagnosticUploader.Kind.RATE_LIMITED) {
                return getString(R.string.zp_diagnostic_failure_rate_limited);
            }
            if (kind == DiagnosticUploader.Kind.STORAGE_UNAVAILABLE) {
                return getString(R.string.zp_diagnostic_failure_storage);
            }
            if (kind == DiagnosticUploader.Kind.SERVER_ERROR) {
                return getString(R.string.zp_diagnostic_failure_server);
            }
            if (kind == DiagnosticUploader.Kind.REDIRECT_REJECTED) {
                return getString(R.string.zp_diagnostic_failure_redirect);
            }
            if (kind == DiagnosticUploader.Kind.TIMEOUT) {
                return getString(R.string.zp_diagnostic_failure_timeout);
            }
            if (kind == DiagnosticUploader.Kind.NETWORK) {
                return getString(R.string.zp_diagnostic_failure_network);
            }
            if (kind == DiagnosticUploader.Kind.INVALID_RESPONSE) {
                return getString(R.string.zp_diagnostic_failure_receipt);
            }
            return getString(R.string.zp_diagnostic_failure_invalid);
        }

        private void runBusy(Runnable task) {
            busy = true;
            statusMessage = getString(R.string.zp_diagnostic_working);
            buildScreen();
            new Thread(task, "diagnostic-report-work").start();
        }

        private void finishBusy() {
            busy = false;
            buildScreen();
        }

        private void post(Runnable action) {
            if (!isAdded()) return;
            requireActivity().runOnUiThread(() -> {
                if (isAdded()) action.run();
            });
        }
    }
}

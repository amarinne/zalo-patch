package com.ez.zalopatch;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

public final class CallRecordingsActivity {
    private CallRecordingsActivity() {
    }

    public static final class RecordingsFragment extends Fragment {
        private RecordingsAdapter adapter;
        private TextView empty;
        private TextView storage;
        private boolean loading;

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            return inflater.inflate(R.layout.fragment_call_recordings, container, false);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            // Slide the page as one surface, not row by row.
            if (view instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) view).setTransitionGroup(true);
            }
            empty = view.findViewById(R.id.zp_recordings_empty);
            storage = view.findViewById(R.id.zp_recordings_storage);
            RecyclerView list = view.findViewById(R.id.zp_recordings_list);
            list.setLayoutManager(new LinearLayoutManager(requireContext()));
            adapter = new RecordingsAdapter(new RecordingsAdapter.Listener() {
                @Override
                public void onClick(CallRecordingStore.Entry entry) {
                    openRecording(entry);
                }

                @Override
                public void onLongClick(CallRecordingStore.Entry entry) {
                    confirmDelete(entry);
                }
            });
            list.setAdapter(adapter);
            refreshAsync();
        }

        @Override
        public void onResume() {
            super.onResume();
            refreshAsync();
        }

        private void refreshAsync() {
            if (loading || !isAdded()) return;
            loading = true;
            android.content.Context context = requireContext().getApplicationContext();
            new Thread(() -> {
                CallRecordingStore.recover(context);
                List<CallRecordingStore.Entry> entries = CallRecordingStore.list(context);
                long totalSize = 0L;
                for (CallRecordingStore.Entry entry : entries) totalSize += entry.size;
                long finalTotalSize = totalSize;
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    loading = false;
                    if (!isAdded()) return;
                    adapter.submitList(entries);
                    empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                    storage.setText(getString(R.string.zp_call_recordings_storage,
                            entries.size(), formatBytes(finalTotalSize)));
                });
            }, "call-recordings-load").start();
        }

        private void openRecording(CallRecordingStore.Entry entry) {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                    .setDataAndType(entry.uri, "audio/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(intent);
            } catch (ActivityNotFoundException exception) {
                Toast.makeText(requireContext(), R.string.zp_call_recording_open_failed,
                        Toast.LENGTH_SHORT).show();
            }
        }

        private void confirmDelete(CallRecordingStore.Entry entry) {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.zp_call_recording_delete_title)
                    .setMessage(R.string.zp_call_recording_delete_message)
                    .setNegativeButton(R.string.zp_cancel, null)
                    .setPositiveButton(R.string.zp_delete, (dialog, which) -> {
                        if (!CallRecordingStore.delete(requireContext(), entry)) {
                            Toast.makeText(requireContext(),
                                    R.string.zp_call_recording_delete_failed,
                                    Toast.LENGTH_SHORT).show();
                            return;
                        }
                        refreshAsync();
                    })
                    .show();
        }
    }

    private static final class RecordingsAdapter extends
            ListAdapter<CallRecordingStore.Entry, RecordingsAdapter.Holder> {
        interface Listener {
            void onClick(CallRecordingStore.Entry entry);

            void onLongClick(CallRecordingStore.Entry entry);
        }

        private static final DiffUtil.ItemCallback<CallRecordingStore.Entry> DIFF =
                new DiffUtil.ItemCallback<CallRecordingStore.Entry>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull CallRecordingStore.Entry oldItem,
                            @NonNull CallRecordingStore.Entry newItem) {
                        return oldItem.uri.equals(newItem.uri);
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull CallRecordingStore.Entry oldItem,
                            @NonNull CallRecordingStore.Entry newItem) {
                        return oldItem.size == newItem.size
                                && oldItem.durationMs == newItem.durationMs
                                && oldItem.startedAt == newItem.startedAt
                                && oldItem.name.equals(newItem.name);
                    }
                };
        private final Listener listener;

        RecordingsAdapter(Listener listener) {
            super(DIFF);
            this.listener = listener;
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_settings_list, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            CallRecordingStore.Entry entry = getItem(position);
            holder.title.setText(entry.name);
            holder.summary.setText(entrySummary(entry));
            holder.itemView.setOnClickListener(clicked -> listener.onClick(entry));
            holder.itemView.setOnLongClickListener(clicked -> {
                listener.onLongClick(entry);
                return true;
            });
        }

        static final class Holder extends RecyclerView.ViewHolder {
            final TextView title;
            final TextView summary;

            Holder(View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.zp_list_title);
                summary = itemView.findViewById(R.id.zp_list_summary);
            }
        }
    }

    private static String entrySummary(CallRecordingStore.Entry entry) {
        String date = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                .format(new Date(entry.startedAt));
        return date + " · " + formatDuration(entry.durationMs) + " · "
                + formatBytes(entry.size);
    }

    static String formatDuration(long durationMs) {
        long seconds = Math.max(0L, durationMs) / 1000L;
        long hours = seconds / 3600L;
        long minutes = (seconds % 3600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0L) {
            return String.format(Locale.US, "%d:%02d:%02d",
                    hours, minutes, remainingSeconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, remainingSeconds);
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        if (bytes < 1024L * 1024L) {
            return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        }
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}

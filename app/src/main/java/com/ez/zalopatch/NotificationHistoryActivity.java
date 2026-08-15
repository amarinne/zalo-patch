package com.ez.zalopatch;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.ListAdapter;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DateFormat;
import java.util.Date;
import java.util.List;

public final class NotificationHistoryActivity {
    public static final String EXTRA_BUCKET = "bucket";

    private NotificationHistoryActivity() {
    }

    public static final class HistoryFragment extends Fragment {
        private static final String STATE_BUCKET = "notification_history.bucket";
        private static final String ARG_BUCKET = "bucket";
        private NotificationHistoryStore.Bucket bucket = NotificationHistoryStore.Bucket.ALL;
        private HistoryAdapter adapter;
        private TextView storage;
        private TextView empty;
        private MaterialButton export;
        private MaterialButton clear;
        private Chip filter;

        static HistoryFragment forBucket(String initialBucket) {
            HistoryFragment fragment = new HistoryFragment();
            Bundle args = new Bundle();
            args.putString(ARG_BUCKET, initialBucket);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void onCreate(@Nullable Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            if (savedInstanceState != null) {
                bucket = parseBucket(savedInstanceState.getString(STATE_BUCKET));
            } else if (getArguments() != null) {
                bucket = parseBucket(getArguments().getString(ARG_BUCKET));
            }
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            return inflater.inflate(R.layout.fragment_notification_history, container, false);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            // Slide the page as one surface, not row by row.
            if (view instanceof android.view.ViewGroup) {
                ((android.view.ViewGroup) view).setTransitionGroup(true);
            }
            storage = view.findViewById(R.id.zp_history_storage);
            empty = view.findViewById(R.id.zp_history_empty);
            export = view.findViewById(R.id.zp_history_export);
            clear = view.findViewById(R.id.zp_history_clear);
            filter = view.findViewById(R.id.zp_history_filter);
            RecyclerView list = view.findViewById(R.id.zp_history_list);
            list.setLayoutManager(new LinearLayoutManager(requireContext()));
            adapter = new HistoryAdapter(entry -> HistoryDetailSheet.show(
                    getParentFragmentManager(), entry.id));
            list.setAdapter(adapter);
            export.setOnClickListener(clicked ->
                    ((StatusActivity) requireActivity()).exportHistory());
            clear.setOnClickListener(clicked -> confirmClear());
            filter.setOnClickListener(clicked -> showFilterMenu());
            updateFilterLabel();
        }

        @Override
        public void onResume() {
            super.onResume();
            refreshAsync();
        }

        @Override
        public void onSaveInstanceState(@NonNull Bundle outState) {
            outState.putString(STATE_BUCKET, bucket.name());
            super.onSaveInstanceState(outState);
        }

        private void refreshAsync() {
            if (!isAdded()) return;
            android.content.Context context = requireContext().getApplicationContext();
            NotificationHistoryStore.Bucket requested = bucket;
            new Thread(() -> {
                NotificationHistoryStore store = new NotificationHistoryStore(context);
                HistorySnapshot snapshot = new HistorySnapshot(
                        store.latest(100, requested), store.count(), store.storageSummary());
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (!isAdded() || requested != bucket) return;
                    adapter.submitList(snapshot.entries);
                    storage.setText(snapshot.storage);
                    export.setEnabled(snapshot.total > 0);
                    clear.setEnabled(snapshot.total > 0);
                    empty.setVisibility(snapshot.entries.isEmpty() ? View.VISIBLE : View.GONE);
                    empty.setText(requested == NotificationHistoryStore.Bucket.ALL
                            ? R.string.zp_history_empty_all : R.string.zp_history_empty_filtered);
                });
            }, "notification-history-load").start();
        }

        private void confirmClear() {
            new MaterialAlertDialogBuilder(requireContext())
                    .setTitle(R.string.zp_history_clear_title)
                    .setMessage(R.string.zp_history_clear_message)
                    .setNegativeButton(R.string.zp_cancel, null)
                    .setPositiveButton(R.string.zp_clear, (dialog, which) -> {
                        new NotificationHistoryStore(requireContext()).clear();
                        refreshAsync();
                    })
                    .show();
        }

        private void updateFilterLabel() {
            String title = bucketTitle(bucket);
            filter.setText(title);
            filter.setContentDescription(getString(R.string.zp_history_filter_value, title));
        }

        private void showFilterMenu() {
            PopupMenu popup = new PopupMenu(requireContext(), filter);
            NotificationHistoryStore.Bucket[] values = NotificationHistoryStore.Bucket.values();
            for (NotificationHistoryStore.Bucket value : values) {
                popup.getMenu().add(0, value.ordinal(), value.ordinal(), bucketTitle(value));
            }
            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() < 0 || item.getItemId() >= values.length) {
                    return false;
                }
                bucket = values[item.getItemId()];
                updateFilterLabel();
                refreshAsync();
                return true;
            });
            popup.show();
        }

        private String bucketTitle(NotificationHistoryStore.Bucket value) {
            if (value == NotificationHistoryStore.Bucket.MESSAGES) {
                return getString(R.string.zp_history_messages);
            }
            if (value == NotificationHistoryStore.Bucket.SUPPRESSED) {
                return getString(R.string.zp_history_suppressed);
            }
            if (value == NotificationHistoryStore.Bucket.ALLOWED) {
                return getString(R.string.zp_history_allowed);
            }
            return getString(R.string.zp_history_all);
        }

        private static NotificationHistoryStore.Bucket parseBucket(String value) {
            try {
                return NotificationHistoryStore.Bucket.valueOf(value == null ? "ALL" : value);
            } catch (IllegalArgumentException ignored) {
                return NotificationHistoryStore.Bucket.ALL;
            }
        }
    }

    private static final class HistorySnapshot {
        final List<NotificationHistoryStore.Entry> entries;
        final int total;
        final String storage;

        HistorySnapshot(List<NotificationHistoryStore.Entry> entries, int total, String storage) {
            this.entries = entries;
            this.total = total;
            this.storage = storage;
        }
    }

    private static final class HistoryAdapter extends
            ListAdapter<NotificationHistoryStore.Entry, HistoryAdapter.Holder> {
        interface Listener {
            void onClick(NotificationHistoryStore.Entry entry);
        }

        private static final DiffUtil.ItemCallback<NotificationHistoryStore.Entry> DIFF =
                new DiffUtil.ItemCallback<NotificationHistoryStore.Entry>() {
                    @Override
                    public boolean areItemsTheSame(@NonNull NotificationHistoryStore.Entry oldItem,
                            @NonNull NotificationHistoryStore.Entry newItem) {
                        return oldItem.id == newItem.id;
                    }

                    @Override
                    public boolean areContentsTheSame(@NonNull NotificationHistoryStore.Entry oldItem,
                            @NonNull NotificationHistoryStore.Entry newItem) {
                        return oldItem.cancelled == newItem.cancelled
                                && oldItem.promo == newItem.promo
                                && oldItem.title.equals(newItem.title)
                                && oldItem.summary().equals(newItem.summary());
                    }
                };
        private final Listener listener;

        HistoryAdapter(Listener listener) {
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
            NotificationHistoryStore.Entry entry = getItem(position);
            String title = entry.title.isEmpty()
                    ? holder.itemView.getContext().getString(R.string.zp_history_notification)
                    : entry.title;
            holder.title.setText(title);
            String state = entry.cancelled
                    ? holder.itemView.getContext().getString(R.string.zp_history_suppressed_label)
                    : entry.promo
                    ? holder.itemView.getContext().getString(R.string.zp_history_promo_label) : "";
            String time = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                    .format(new Date(entry.postedAt));
            String message = entry.summary();
            holder.summary.setText(message.isEmpty()
                    ? holder.itemView.getContext().getString(
                            R.string.zp_history_row_summary, time, state)
                    : holder.itemView.getContext().getString(
                            R.string.zp_history_row_summary_message, time, state, message));
            holder.itemView.setOnClickListener(clicked -> listener.onClick(entry));
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

    public static final class HistoryDetailSheet extends BottomSheetDialogFragment {
        private static final String ARG_ID = "id";

        static void show(androidx.fragment.app.FragmentManager manager, long id) {
            HistoryDetailSheet sheet = new HistoryDetailSheet();
            Bundle args = new Bundle();
            args.putLong(ARG_ID, id);
            sheet.setArguments(args);
            sheet.show(manager, "notification-history-detail");
        }

        @Nullable
        @Override
        public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                @Nullable Bundle savedInstanceState) {
            return inflater.inflate(R.layout.sheet_notification_history, container, false);
        }

        @Override
        public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            MaterialButton close = view.findViewById(R.id.zp_sheet_close);
            close.setOnClickListener(clicked -> dismiss());
            TextView title = view.findViewById(R.id.zp_sheet_title);
            TextView body = view.findViewById(R.id.zp_sheet_body);
            long id = requireArguments().getLong(ARG_ID, -1L);
            android.content.Context context = requireContext().getApplicationContext();
            new Thread(() -> {
                NotificationHistoryStore.Entry entry = new NotificationHistoryStore(context).find(id);
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                    if (!isAdded()) return;
                    if (entry == null) {
                        title.setText(R.string.zp_history_missing);
                        body.setText("");
                        return;
                    }
                    title.setText(entry.title.isEmpty()
                            ? getString(R.string.zp_history_notification) : entry.title);
                    body.setText(details(entry));
                });
            }, "notification-history-detail").start();
        }

        private String details(NotificationHistoryStore.Entry entry) {
            StringBuilder builder = new StringBuilder();
            append(builder, getString(R.string.zp_detail_time),
                    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.MEDIUM)
                            .format(new Date(entry.postedAt)));
            append(builder, getString(R.string.zp_detail_message), entry.summary());
            append(builder, getString(R.string.zp_detail_result), entry.cancelled
                    ? getString(R.string.zp_history_suppressed_label).trim()
                    : getString(R.string.zp_history_allowed_label));
            append(builder, getString(R.string.zp_detail_channel), entry.channelId);
            append(builder, getString(R.string.zp_detail_template), entry.template);
            append(builder, getString(R.string.zp_detail_metadata), entry.metadata);
            return builder.toString();
        }

        private static void append(StringBuilder builder, String label, String value) {
            if (value == null || value.isEmpty()) return;
            if (builder.length() > 0) builder.append("\n\n");
            builder.append(label).append("\n").append(value);
        }
    }
}

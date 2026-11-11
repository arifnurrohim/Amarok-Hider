package deltazero.amarok.utils;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.Nullable;

public final class OperationJournal {

    private static final String PREFS_NAME = "operation_journal";

    private static final String KEY_STATUS = "status";
    private static final String KEY_HIDE = "hide";
    private static final String KEY_PHASE = "phase";
    private static final String KEY_PROCESSED = "processed";
    private static final String KEY_TOTAL = "total";
    private static final String KEY_STARTED_AT = "startedAt";
    private static final String KEY_UPDATED_AT = "updatedAt";

    private static final String STATUS_RUNNING = "running";
    private static final String STATUS_STALLED = "stalled";
    private static final String STATUS_COMPLETED = "completed";
    private static final String STATUS_CANCELLED = "cancelled";
    private static final String STATUS_FAILED = "failed";
    private static final String STATUS_ACKNOWLEDGED = "acknowledged";

    private OperationJournal() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    public static void start(Context context, boolean hide) {
        long now = System.currentTimeMillis();
        prefs(context).edit()
                .putString(KEY_STATUS, STATUS_RUNNING)
                .putBoolean(KEY_HIDE, hide)
                .putString(KEY_PHASE, "")
                .putInt(KEY_PROCESSED, 0)
                .putInt(KEY_TOTAL, 0)
                .putLong(KEY_STARTED_AT, now)
                .putLong(KEY_UPDATED_AT, now)
                .commit();
    }

    public static void markPhase(Context context, String phase) {
        prefs(context).edit()
                .putString(KEY_PHASE, phase)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .commit();
    }

    public static void update(Context context, int processed, int total) {
        prefs(context).edit()
                .putInt(KEY_PROCESSED, processed)
                .putInt(KEY_TOTAL, total)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .apply();
    }

    public static void markStalled(Context context, int processed, int total) {
        prefs(context).edit()
                .putString(KEY_STATUS, STATUS_STALLED)
                .putInt(KEY_PROCESSED, processed)
                .putInt(KEY_TOTAL, total)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .commit();
    }

    public static void finish(Context context, Status status, int processed, int total) {
        prefs(context).edit()
                .putString(KEY_STATUS, status.value)
                .putInt(KEY_PROCESSED, processed)
                .putInt(KEY_TOTAL, total)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .commit();
    }

    @Nullable
    public static Entry getUnfinished(Context context) {
        SharedPreferences prefs = prefs(context);
        String status = prefs.getString(KEY_STATUS, null);
        if (!STATUS_RUNNING.equals(status) && !STATUS_STALLED.equals(status))
            return null;

        return new Entry(
                prefs.getBoolean(KEY_HIDE, true),
                prefs.getString(KEY_PHASE, ""),
                prefs.getInt(KEY_PROCESSED, 0),
                prefs.getInt(KEY_TOTAL, 0),
                prefs.getLong(KEY_STARTED_AT, 0),
                prefs.getLong(KEY_UPDATED_AT, 0)
        );
    }

    public static void acknowledge(Context context) {
        prefs(context).edit()
                .putString(KEY_STATUS, STATUS_ACKNOWLEDGED)
                .putLong(KEY_UPDATED_AT, System.currentTimeMillis())
                .commit();
    }

    public enum Status {
        COMPLETED(STATUS_COMPLETED),
        CANCELLED(STATUS_CANCELLED),
        FAILED(STATUS_FAILED),
        STALLED(STATUS_STALLED);

        private final String value;

        Status(String value) {
            this.value = value;
        }
    }

    public static final class Entry {
        public final boolean hide;
        public final String phase;
        public final int processed;
        public final int total;
        public final long startedAt;
        public final long updatedAt;

        Entry(boolean hide, String phase, int processed, int total,
              long startedAt, long updatedAt) {
            this.hide = hide;
            this.phase = phase;
            this.processed = processed;
            this.total = total;
            this.startedAt = startedAt;
            this.updatedAt = updatedAt;
        }
    }
}

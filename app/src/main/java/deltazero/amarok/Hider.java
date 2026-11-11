package deltazero.amarok;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import androidx.lifecycle.MutableLiveData;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import deltazero.amarok.filehider.BaseFileHider;
import deltazero.amarok.utils.OperationJournal;
import deltazero.amarok.utils.SecurityUtil;


public final class Hider {

    private static final String TAG = "Hider";
    private static final String PHASE_STARTING = "starting";
    private static final long WATCHDOG_TIMEOUT_MS = 60_000;

    private static final HandlerThread hiderThread = new HandlerThread("HIDER_THREAD");
    private static final Handler threadHandler;
    private static final Handler mainHandler = new Handler(Looper.getMainLooper());
    private static final AtomicReference<Operation> currentOperation = new AtomicReference<>();
    private static final AtomicBoolean pendingForceUnhide = new AtomicBoolean(false);

    public static boolean initialized = false;
    public static MutableLiveData<State> state;
    public static MutableLiveData<Progress> progress;

    public enum State {
        HIDDEN,
        VISIBLE,
        PROCESSING
    }

    static {
        hiderThread.start();
        threadHandler = new Handler(hiderThread.getLooper());
    }

    /**
     * This method should be invoked in {@link AmarokApplication#onCreate()}, after {@link PrefMgr#init(Context)}.
     * Do not invoke this method in static part or before {@link PrefMgr#init(Context)}.
     */
    public static void init() {
        assert PrefMgr.initialized;
        state = new MutableLiveData<>(PrefMgr.getIsHidden() ? State.HIDDEN : State.VISIBLE);
        state.observeForever(newState -> {
            if (newState != State.PROCESSING)
                PrefMgr.setIsHidden(newState == State.HIDDEN);
        });
        progress = new MutableLiveData<>(new Progress(false, PHASE_STARTING, 0, 0, false));
        initialized = true;
    }

    /**
     * NOTE: Calling this method on a background thread
     * does not guarantee that the latest value set will be received.
     */
    public static State getState() {
        return state.getValue();
    }

    public static boolean isProcessing() {
        return currentOperation.get() != null;
    }

    public static boolean hide(Context context) {
        boolean accepted = startOperation(context, true) != null;

        // Avoid password or disguise right after hide
        if (accepted && PrefMgr.getDisableSecurityWhenUnhidden()) {
            SecurityUtil.unlock();
            SecurityUtil.dismissDisguise();
        }
        return accepted;
    }

    public static boolean unhide(Context context) {
        return startOperation(context, false) != null;
    }

    /**
     * Used by WorkManager to keep the process awake until the operation settles.
     *
     * @return true if the operation completed or there was nothing to do.
     */
    public static boolean hideAndWait(Context context, long timeout, TimeUnit unit)
            throws InterruptedException {
        if (getState() == State.HIDDEN)
            return true;

        Operation operation = startOperation(context, true);
        if (operation == null) {
            operation = currentOperation.get();
            if (operation == null)
                return true;
            if (!operation.hide)
                return false;
        }
        return operation.done.await(timeout, unit);
    }

    public static void cancel() {
        Operation operation = currentOperation.get();
        if (operation != null && operation.cancelled.compareAndSet(false, true)) {
            Log.w(TAG, "Cancellation requested.");
            publishProgress(operation);
        }
    }

    public static void forceUnhide(Context context) {
        Operation operation = currentOperation.get();
        if (operation != null) {
            operation.cancelled.set(true);
            pendingForceUnhide.set(true);
            publishProgress(operation);
            return;
        }

        startOperation(context, false);
    }

    private static Operation startOperation(Context context, boolean hide) {
        Context appContext = context.getApplicationContext();
        if (appContext == null)
            appContext = context;

        Operation operation = new Operation(appContext, hide);
        if (!currentOperation.compareAndSet(null, operation)) {
            Log.w(TAG, "Another operation is already running. Ignore the new request.");
            return null;
        }

        state.postValue(State.PROCESSING);
        progress.postValue(new Progress(hide, PHASE_STARTING, 0, 0, false));
        threadHandler.post(() -> runOperation(operation));
        return operation;
    }

    private static void runOperation(Operation operation) {
        Context context = operation.context;
        boolean completed = false;
        boolean failed = false;

        Log.i(TAG, "Process '" + (operation.hide ? "hide" : "unhide") + "' start.");
        OperationJournal.start(context, operation.hide);
        HiderService.start(context);
        scheduleWatchdog(operation);

        try {
            if (operation.hide)
                PrefMgr.setIsHidden(true);

            BaseFileHider fileHider = PrefMgr.getFileHider(context);
            fileHider.setProgressCallback(new BaseFileHider.ProgressCallback() {
                @Override
                public void onProgress(int processed, int total, String phase) {
                    updateProgress(operation, processed, total, phase);
                }

                @Override
                public boolean isCancelled() {
                    return operation.cancelled.get();
                }
            });

            if (operation.hide)
                fileHider.hide(PrefMgr.getHideFilePath());
            else
                fileHider.unhide(PrefMgr.getHideFilePath());

            completed = !operation.cancelled.get() && !operation.stalled;
        } catch (InterruptedException e) {
            Log.w(TAG, "Process '" + (operation.hide ? "hide" : "unhide") + "' interrupted.");
        } catch (Exception e) {
            failed = true;
            Log.e(TAG, "Process '" + (operation.hide ? "hide" : "unhide") + "' failed.", e);
        } finally {
            completeOperation(operation, completed, failed);
        }
    }

    private static void completeOperation(Operation operation, boolean completed, boolean failed) {
        Context context = operation.context;
        if (operation.watchdog != null)
            mainHandler.removeCallbacks(operation.watchdog);

        State finalState;
        OperationJournal.Status journalStatus;

        if (completed) {
            finalState = operation.hide ? State.HIDDEN : State.VISIBLE;
            journalStatus = OperationJournal.Status.COMPLETED;
        } else if (operation.stalled) {
            finalState = State.HIDDEN;
            journalStatus = OperationJournal.Status.STALLED;
        } else if (failed) {
            finalState = State.HIDDEN;
            journalStatus = OperationJournal.Status.FAILED;
        } else {
            finalState = State.HIDDEN;
            journalStatus = OperationJournal.Status.CANCELLED;
        }

        PrefMgr.setIsHidden(finalState == State.HIDDEN);
        state.postValue(finalState);
        OperationJournal.finish(context, journalStatus, operation.processed, operation.total);

        if (completed) {
            if (operation.hide) {
                showToast(context, R.string.hidden_toast);
                QuickHideService.stopService(context);
            } else {
                showToast(context, R.string.unhidden_toast);
                mainHandler.post(() -> QuickHideService.startService(context));
            }
        } else if (failed) {
            showToast(context, R.string.operation_failed);
        }

        operation.done.countDown();
        currentOperation.compareAndSet(operation, null);

        if (pendingForceUnhide.getAndSet(false)) {
            startOperation(context, false);
        } else {
            // Give an immediately following operation a chance to reuse the
            // foreground service instead of racing stopService().
            mainHandler.postDelayed(() -> {
                if (currentOperation.get() == null)
                    HiderService.stop(context);
            }, 1_000);
        }
    }

    private static void updateProgress(Operation operation, int processed, int total, String phase) {
        if (currentOperation.get() != operation)
            return;

        operation.processed = processed;
        operation.total = total;
        operation.phase = phase;
        operation.lastProgressAt = SystemClock.elapsedRealtime();

        if (!phase.equals(operation.lastJournalPhase)) {
            operation.lastJournalPhase = phase;
            OperationJournal.markPhase(operation.context, phase);
        }

        if (processed == 0 || processed == total || processed % 25 == 0)
            OperationJournal.update(operation.context, processed, total);

        publishProgress(operation);
    }

    private static void publishProgress(Operation operation) {
        progress.postValue(new Progress(
                operation.hide,
                operation.phase,
                operation.processed,
                operation.total,
                operation.stalled
        ));
    }

    private static void scheduleWatchdog(Operation operation) {
        operation.lastProgressAt = SystemClock.elapsedRealtime();
        operation.watchdog = new Runnable() {
            @Override
            public void run() {
                if (currentOperation.get() != operation || operation.done.getCount() == 0)
                    return;
                if (operation.cancelled.get())
                    return;

                long inactiveFor = SystemClock.elapsedRealtime() - operation.lastProgressAt;
                if (inactiveFor >= WATCHDOG_TIMEOUT_MS) {
                    operation.stalled = true;
                    operation.cancelled.set(true);
                    Log.w(TAG, "No progress for " + inactiveFor + " ms. Marking operation as stalled.");
                    OperationJournal.markStalled(operation.context, operation.processed, operation.total);
                    publishProgress(operation);
                    return;
                }

                mainHandler.postDelayed(this, WATCHDOG_TIMEOUT_MS - inactiveFor + 1_000);
            }
        };
        mainHandler.postDelayed(operation.watchdog, WATCHDOG_TIMEOUT_MS);
    }

    private static void showToast(Context context, int stringRes) {
        if (PrefMgr.getDisableToasts())
            return;
        mainHandler.post(() -> Toast.makeText(context, stringRes, Toast.LENGTH_SHORT).show());
    }

    public static String getPhaseLabel(Context context, String phase) {
        if (phase == null)
            return context.getString(R.string.processing);

        return switch (phase) {
            case BaseFileHider.PHASE_SCANNING -> context.getString(R.string.operation_phase_scanning);
            case BaseFileHider.PHASE_FILES -> context.getString(R.string.operation_phase_files);
            case BaseFileHider.PHASE_FOLDERS -> context.getString(R.string.operation_phase_folders);
            case BaseFileHider.PHASE_FINALIZING -> context.getString(R.string.operation_phase_finalizing);
            case PHASE_STARTING -> context.getString(R.string.operation_phase_starting);
            default -> context.getString(R.string.processing);
        };
    }

    public static final class Progress {
        public final boolean hiding;
        public final String phase;
        public final int processed;
        public final int total;
        public final boolean stalled;

        Progress(boolean hiding, String phase, int processed, int total, boolean stalled) {
            this.hiding = hiding;
            this.phase = phase;
            this.processed = processed;
            this.total = total;
            this.stalled = stalled;
        }
    }

    private static final class Operation {
        final Context context;
        final boolean hide;
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicBoolean cancelled = new AtomicBoolean(false);

        volatile boolean stalled;
        volatile int processed;
        volatile int total;
        volatile String phase = PHASE_STARTING;
        volatile long lastProgressAt;
        Runnable watchdog;
        String lastJournalPhase;

        Operation(Context context, boolean hide) {
            this.context = context;
            this.hide = hide;
        }
    }
}

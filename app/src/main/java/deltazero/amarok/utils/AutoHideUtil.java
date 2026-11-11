package deltazero.amarok.utils;

import android.content.Context;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.work.ExistingWorkPolicy;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.Worker;
import androidx.work.WorkerParameters;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

import deltazero.amarok.Hider;
import deltazero.amarok.PrefMgr;

public class AutoHideUtil {

    private static final String AUTO_HIDE_WORK_NAME = "deltazero.amarok.AUTO_HIDE_WORK";
    private static final String TAG = "AutoHideUtil";

    public static class AutoHideWorker extends Worker {
        public AutoHideWorker(@NonNull Context context, @NonNull WorkerParameters workerParams) {
            super(context, workerParams);
        }

        @NonNull
        @Override
        public Result doWork() {
            Log.i(TAG, "Auto hide triggered. Start hiding.");
            try {
                boolean completed = Hider.hideAndWait(
                        getApplicationContext(), 9, TimeUnit.MINUTES);
                if (completed)
                    return Result.success();

                Log.w(TAG, "Auto hide did not finish before the worker timeout.");
                return Result.failure();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                Log.w(TAG, "Auto hide worker interrupted.", e);
                return Result.retry();
            }
        }
    }

    public static void setAutoHide(Context context) {
        if (!PrefMgr.getEnableAutoHide()
                || Hider.getState() == Hider.State.HIDDEN
                || Hider.isProcessing())
            return;
        Log.i(TAG, "Auto hide set. Delay: " + PrefMgr.getAutoHideDelay() + " minutes.");
        WorkManager.getInstance(context).enqueueUniqueWork(
                AUTO_HIDE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                new OneTimeWorkRequest.Builder(AutoHideWorker.class)
                        .setInitialDelay(PrefMgr.getAutoHideDelay(), TimeUnit.MINUTES)
                        .build()
        );
    }

    public static void cancelAutoHide(Context context) {
        var workManager = WorkManager.getInstance(context);
        var future = workManager.getWorkInfosForUniqueWork(AUTO_HIDE_WORK_NAME);

        future.addListener(() -> {
            try {
                List<WorkInfo> workInfos = future.get();
                if (workInfos.isEmpty())
                    return;
                if (workInfos.get(0).getState() != WorkInfo.State.RUNNING) {
                    workManager.cancelUniqueWork(AUTO_HIDE_WORK_NAME);
                    Log.i(TAG, "Auto hide cancelled.");
                }
            } catch (ExecutionException | InterruptedException e) {
                Log.w(TAG, "Unable to cancel auto hide.", e);
            }
        }, ContextCompat.getMainExecutor(context));
    }
}

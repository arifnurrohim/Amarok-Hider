package deltazero.amarok.filehider;

import android.content.Context;

import java.util.Set;

public abstract class BaseFileHider {

    public static final String PHASE_SCANNING = "scanning";
    public static final String PHASE_FILES = "files";
    public static final String PHASE_FOLDERS = "folders";
    public static final String PHASE_FINALIZING = "finalizing";

    protected final Context context;

    private ProgressCallback progressCallback = new ProgressCallback() {
        @Override
        public void onProgress(int processed, int total, String phase) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }
    };

    public BaseFileHider(Context context) {
        this.context = context;
    }

    protected abstract void process(Set<String> targetDirs, ProcessMethod method) throws InterruptedException;

    public abstract void tryToActive(ActivationCallbackListener activationCallbackListener);

    public abstract String getName();

    protected enum ProcessMethod {
        HIDE, UNHIDE
    }

    public void hide(Set<String> targetDirs) throws InterruptedException {
        process(targetDirs, ProcessMethod.HIDE);
    }

    public void unhide(Set<String> targetDirs) throws InterruptedException {
        process(targetDirs, ProcessMethod.UNHIDE);
    }

    public void setProgressCallback(ProgressCallback progressCallback) {
        this.progressCallback = progressCallback == null ? new ProgressCallback() {
            @Override
            public void onProgress(int processed, int total, String phase) {
            }

            @Override
            public boolean isCancelled() {
                return false;
            }
        } : progressCallback;
    }

    protected final void reportProgress(int processed, int total, String phase) {
        progressCallback.onProgress(processed, total, phase);
    }

    protected final boolean isCancelled() {
        return progressCallback.isCancelled();
    }

    public interface ProgressCallback {
        void onProgress(int processed, int total, String phase);

        boolean isCancelled();
    }

    public interface ActivationCallbackListener {
        void onActivateCallback(Class<? extends BaseFileHider> appHider, boolean success, int msgResID);
    }
}

package deltazero.amarok.ui;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.hjq.permissions.XXPermissions;

import deltazero.amarok.AmarokActivity;
import deltazero.amarok.Hider;
import deltazero.amarok.PrefMgr;
import deltazero.amarok.R;
import deltazero.amarok.filehider.NoneFileHider;
import deltazero.amarok.ui.settings.SettingsActivity;
import deltazero.amarok.ui.settings.SwitchFileHiderActivity;
import deltazero.amarok.utils.OperationJournal;
import deltazero.amarok.utils.PermissionUtil;
import deltazero.amarok.utils.UpdateUtil;
import nl.dionsegijn.konfetti.xml.KonfettiView;

public class MainActivity extends AmarokActivity {

    public final static String TAG = "Main";
    private ImageView ivStatusImg;
    private TextView tvStatusInfo, tvStatus, tvMoto;
    private MaterialButton btChangeStatus, btSetHideFiles;
    private CircularProgressIndicator piProcessStatus;
    private KonfettiView konfettiView;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Setup activity
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        // Binding views
        ivStatusImg = findViewById(R.id.main_iv_status);
        tvStatus = findViewById(R.id.main_tv_status);
        tvStatusInfo = findViewById(R.id.main_tv_statusinfo);
        tvMoto = findViewById(R.id.main_tv_moto);
        btChangeStatus = findViewById(R.id.main_bt_change_status);
        btSetHideFiles = findViewById(R.id.main_bt_set_hide_files);
        piProcessStatus = findViewById(R.id.main_pi_process_status);
        konfettiView = findViewById(R.id.main_konfetti_view);

        // Init UI
        refreshUi(Hider.getState());

        // Setup observer
        Hider.state.observe(this, this::refreshUi);
        Hider.progress.observe(this, this::refreshProgress);

        // Show welcome dialog
        boolean showWelcome = PrefMgr.getShowWelcome();
        if (showWelcome) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.welcome_title)
                    .setMessage(R.string.welcome_msg)
                    .setPositiveButton(R.string.ok, (dialog, which)
                            -> PermissionUtil.requestStoragePermission(this))
                    .setNegativeButton(R.string.view_github_repo, (dialog, which) -> {
                        startActivity(new Intent(Intent.ACTION_VIEW,
                                Uri.parse("https://github.com/deltazefiro/Amarok-Hider")));
                        PermissionUtil.requestStoragePermission(this);
                    })
                    .setOnCancelListener(dialog -> PermissionUtil.requestStoragePermission(this))
                    .show();
            PrefMgr.setShowWelcome(false);
        } else {
            PermissionUtil.requestStoragePermission(this);
        }

        if (!showWelcome)
            maybeShowRecoveryNotice();

        // Check Hider availability
        PrefMgr.getFileHider(this).tryToActive((fileHiderClass, succeed, msg) -> {
            if (succeed) return;
            PrefMgr.setFileHiderMode(NoneFileHider.class);
            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.filehider_not_ava_title)
                    .setMessage(msg)
                    .setPositiveButton(R.string.switch_file_hider, (dialog, which)
                            -> startActivity(new Intent(this, SwitchFileHiderActivity.class)))
                    .setNegativeButton(getString(R.string.ok), null)
                    .show();
        });

        // Check for updates
        if (PrefMgr.getEnableAutoUpdate()) {
            UpdateUtil.checkAndNotify(this, true);
        }
    }

    public void changeStatus(View view) {
        Hider.State state = Hider.getState();
        if (state == Hider.State.PROCESSING)
            Hider.cancel();
        else if (state == Hider.State.HIDDEN)
            Hider.unhide(this);
        else
            Hider.hide(this);
    }

    public void showMoreSettings(View view) {

        startActivity(new Intent(this, SettingsActivity.class));

    }

    public void setHideFile(View view) {

        if (!XXPermissions.isGranted(this, com.hjq.permissions.Permission.MANAGE_EXTERNAL_STORAGE)) {
            Toast.makeText(this, R.string.storage_permission_denied, Toast.LENGTH_LONG).show();
            return;
        }

        if (Hider.getState() == Hider.State.HIDDEN) {
            Toast.makeText(this, R.string.setting_not_ava_when_hidden, Toast.LENGTH_SHORT).show();
            return;
        }

        startActivity(new Intent(this, SetHideFilesActivity.class));
    }

    public void refreshUi(Hider.State state) {
        tvMoto.setText(R.string.moto);
        switch (state) {
            case HIDDEN -> {
                // Not Processing
                piProcessStatus.hide();
                btChangeStatus.setEnabled(true);
                // Hidden
                ivStatusImg.setImageResource(R.drawable.img_status_hidden);
                ivStatusImg.setImageTintList(getColorStateList(com.google.android.material.R.color.material_on_background_emphasis_high_type));
                btChangeStatus.setText(R.string.unhide);
                btChangeStatus.setIconResource(R.drawable.ic_wolf);
                btSetHideFiles.setEnabled(false);
                tvStatus.setText(getText(R.string.hidden_status));
                tvStatusInfo.setText(getText(R.string.hidden_moto));
            }
            case VISIBLE -> {
                // Not Processing
                piProcessStatus.hide();
                btChangeStatus.setEnabled(true);
                // Visible
                ivStatusImg.setImageResource(R.drawable.img_status_visible);
                ivStatusImg.setImageTintList(null);
                btChangeStatus.setText(R.string.hide);
                btChangeStatus.setIconResource(R.drawable.ic_paw);
                btSetHideFiles.setEnabled(true);
                tvStatus.setText(getText(R.string.visible_status));
                tvStatusInfo.setText(getText(R.string.visible_moto));
            }
            case PROCESSING -> {
                // Processing
                piProcessStatus.show();
                btChangeStatus.setEnabled(true);
                btChangeStatus.setText(R.string.cancel);
                btChangeStatus.setIconResource(R.drawable.cancel_presentation_24dp_1f1f1f_fill0_wght400_grad0_opsz24);
                btSetHideFiles.setEnabled(false);
                tvStatus.setText(R.string.processing);
                refreshProgress(Hider.progress.getValue());
            }
        }
    }

    private void refreshProgress(Hider.Progress progress) {
        if (progress == null || Hider.getState() != Hider.State.PROCESSING)
            return;

        if (progress.stalled) {
            tvStatusInfo.setText(R.string.operation_stalled);
            return;
        }

        String phase = Hider.getPhaseLabel(this, progress.phase);
        if (progress.total > 0) {
            tvStatusInfo.setText(getString(R.string.operation_progress,
                    progress.processed, progress.total, phase));
        } else {
            tvStatusInfo.setText(phase);
        }
    }

    private void maybeShowRecoveryNotice() {
        if (Hider.getState() == Hider.State.PROCESSING)
            return;

        OperationJournal.Entry entry = OperationJournal.getUnfinished(this);
        if (entry == null)
            return;

        String action = getString(entry.hide
                ? R.string.operation_hiding_title
                : R.string.operation_unhiding_title);
        String phase = Hider.getPhaseLabel(this, entry.phase);

        new MaterialAlertDialogBuilder(this)
                .setTitle(R.string.previous_operation_title)
                .setMessage(getString(R.string.previous_operation_message,
                        action, entry.processed, entry.total, phase))
                .setPositiveButton(R.string.previous_operation_unhide, (dialog, which) -> {
                    OperationJournal.acknowledge(this);
                    Hider.unhide(this);
                })
                .setNegativeButton(R.string.previous_operation_dismiss,
                        (dialog, which) -> OperationJournal.acknowledge(this))
                .setCancelable(false)
                .show();
    }

    @Override
    protected void onResume() {
        refreshUi(Hider.getState());
        super.onResume();
    }
}





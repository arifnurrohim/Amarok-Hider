package deltazero.amarok.ui.settings;

import android.os.Bundle;

import com.google.android.material.appbar.MaterialToolbar;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;

import deltazero.amarok.AmarokActivity;
import deltazero.amarok.PrefMgr;
import deltazero.amarok.R;

public class ObfuscateFileHiderSettingsActivity extends AmarokActivity {

    private MaterialSwitch swObfuscateFileHeader, swObfuscateTextFile, swObfuscateTextFileEnhanced;
    private MaterialSwitch swObfuscateFolderName;
    private MaterialToolbar tbToolBar;
    private boolean updatingUi;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_obfuscate_filehider_settings);

        swObfuscateFileHeader = findViewById(R.id.switch_filehider_sw_obfuscate_header);
        swObfuscateTextFile = findViewById(R.id.switch_filehider_sw_obfuscate_text);
        swObfuscateTextFileEnhanced = findViewById(R.id.switch_filehider_sw_obfuscate_text_enhanced);
        swObfuscateFolderName = findViewById(R.id.switch_filehider_sw_obfuscate_folder_name);
        tbToolBar = findViewById(R.id.switch_filehider_tb_toolbar);

        // Init UI
        swObfuscateFileHeader.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingUi) return;
            PrefMgr.setEnableObfuscateFileHeader(isChecked);
            if (!isChecked) {
                PrefMgr.setEnableObfuscateTextFile(false);
                PrefMgr.setEnableObfuscateTextFileEnhanced(false);
            }
            updateUI();
        });
        swObfuscateTextFile.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingUi) return;
            PrefMgr.setEnableObfuscateTextFile(isChecked);
            if (!isChecked) {
                PrefMgr.setEnableObfuscateTextFileEnhanced(false);
            }
            updateUI();
        });
        swObfuscateTextFileEnhanced.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingUi) return;
            PrefMgr.setEnableObfuscateTextFileEnhanced(isChecked);
            updateUI();
        });
        swObfuscateFolderName.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (updatingUi) return;
            if (!isChecked) {
                PrefMgr.setEnableObfuscateFolderName(false);
                updateUI();
                return;
            }

            new MaterialAlertDialogBuilder(this)
                    .setTitle(R.string.obfuscate_folder_name_warning_title)
                    .setMessage(R.string.obfuscate_folder_name_warning_message)
                    .setPositiveButton(R.string.confirm, (dialog, which) -> {
                        PrefMgr.setEnableObfuscateFolderName(true);
                        updateUI();
                    })
                    .setNegativeButton(R.string.cancel, (dialog, which) -> updateUI())
                    .setOnCancelListener(dialog -> updateUI())
                    .show();
        });

        // Enable back button
        tbToolBar.setNavigationOnClickListener(v -> finish());

        // Update UI
        updateUI();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateUI();
    }

    private void updateUI() {
        updatingUi = true;
        swObfuscateFileHeader.setChecked(PrefMgr.getEnableObfuscateFileHeader());
        swObfuscateTextFile.setChecked(PrefMgr.getEnableObfuscateTextFile());
        swObfuscateTextFileEnhanced.setChecked(PrefMgr.getEnableObfuscateTextFileEnhanced());
        swObfuscateFolderName.setChecked(PrefMgr.getEnableObfuscateFolderName());

        swObfuscateTextFile.setEnabled(PrefMgr.getEnableObfuscateFileHeader());
        swObfuscateTextFileEnhanced.setEnabled(PrefMgr.getEnableObfuscateFileHeader()
                && PrefMgr.getEnableObfuscateTextFile());
        updatingUi = false;
    }
}

package dev.lcehub.emerald;

import android.content.Intent;
import android.os.Bundle;
import android.os.Environment;
import androidx.appcompat.app.AppCompatActivity;
import dev.lcehub.emerald.container.Container;
import dev.lcehub.emerald.container.ContainerManager;
import dev.lcehub.emerald.contents.ContentsManager;
import dev.lcehub.emerald.core.AppUtils;
import dev.lcehub.emerald.core.DefaultVersion;
import dev.lcehub.emerald.core.WineInfo;
import dev.lcehub.emerald.xenvironment.ImageFsInstaller;
import dev.lcehub.emerald.core.DownloadProgressDialog;

import java.util.concurrent.Executors;
import org.json.JSONException;
import org.json.JSONObject;

public class LauncherBridgeActivity extends AppCompatActivity {

    public static final String EXTRA_ACTION = "launcher_action";
    public static final String EXTRA_INSTANCE_PATH = "instance_path";
    public static final String EXTRA_ARGS = "extra_args";

    public static final String ACTION_PLAY = "play";
    public static final String ACTION_OPEN = "open";
    public static final String ACTION_SETTINGS = "settings";
    public static final String ACTION_SWITCH_PROTON = "switch_proton";
    public static final String ACTION_INSTALL_DRIVER = "install_driver";
    public static final String ACTION_SET_AUDIO_BACKEND = "set_audio_backend";

    public static final String CONTAINER_NAME = "DiamondEmerald";
    public static final String GAME_EXECUTABLE = "Minecraft.Client.exe";

    private String action;
    private String instancePath;
    private String extraArgs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        action = getIntent().getStringExtra(EXTRA_ACTION);
        instancePath = getIntent().getStringExtra(EXTRA_INSTANCE_PATH);
        extraArgs = getIntent().getStringExtra(EXTRA_ARGS);
        if (action == null || instancePath == null) {
            finish();
            return;
        }

        AppUtils.hideSystemUI(this);
        if (!ImageFsInstaller.installIfNeeded(this, this::ensureContainer)) {
            ensureContainer();
        }
    }

    private void ensureContainer() {
        ContainerManager manager = new ContainerManager(this);
        Container container = null;
        for (Container existing : manager.getContainers()) {
            if (CONTAINER_NAME.equals(existing.getName())) {
                container = existing;
                break;
            }
        }

        if (container != null) {
            updateDrives(container);
            onContainerReady(container);
            return;
        }

        try {
            JSONObject data = new JSONObject();
            data.put("name", CONTAINER_NAME);
            data.put("wineVersion", WineInfo.MAIN_WINE_VERSION.identifier());
            data.put("box64Version", DefaultVersion.WOWBOX64);
            data.put("fexcoreVersion", DefaultVersion.FEXCORE);
            data.put("drives", getDrivesString());
            ContentsManager contentsManager = new ContentsManager(this);
            manager.createContainerAsync(
                data,
                contentsManager,
                this::onContainerCreated
            );
        } catch (JSONException e) {
            AppUtils.showToast(this, "Failed to create container");
            finish();
        }
    }

    private void onContainerCreated(Container container) {
        if (container == null) {
            AppUtils.showToast(this, "Failed to create container");
            finish();
            return;
        }
        onContainerReady(container);
    }

    private void onContainerReady(Container container) {
        if (ACTION_PLAY.equals(action)) {
            Intent intent = new Intent(this, XServerDisplayActivity.class);
            intent.putExtra("container_id", container.id);
            intent.putExtra("exec_path", instancePath + "/" + GAME_EXECUTABLE);
            if (extraArgs != null) intent.putExtra("extra_args", extraArgs);
            startActivity(intent);
        } else if (ACTION_OPEN.equals(action)) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("container_id", container.id);
            intent.putExtra("start_path", instancePath);
            startActivity(intent);
        } else if (ACTION_SETTINGS.equals(action)) {
            Intent intent = new Intent(this, MainActivity.class);
            intent.putExtra("container_settings_id", container.id);
            startActivity(intent);
        } else if (ACTION_SWITCH_PROTON.equals(action)) {
            String wineVersion = null;
            if (extraArgs != null) {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(extraArgs);
                    if (arr.length() > 0) wineVersion = arr.getString(0);
                } catch (JSONException e) {
                    wineVersion = extraArgs;
                }
            }
            if (wineVersion != null && !wineVersion.isEmpty()) {
                ContainerManager manager = new ContainerManager(this);
                manager.removeContainerAsync(container, () -> {
                    try {
                        JSONObject data = new JSONObject();
                        data.put("name", CONTAINER_NAME);
                        data.put("wineVersion", wineVersion);
                        data.put("box64Version", DefaultVersion.WOWBOX64);
                        data.put("fexcoreVersion", DefaultVersion.FEXCORE);
                        data.put("drives", getDrivesString());
                        ContentsManager contentsManager = new ContentsManager(this);
                        manager.createContainerAsync(
                            data,
                            contentsManager,
                            newContainer -> {
                                if (newContainer != null) {
                                    AppUtils.showToast(this, "Switched to " + wineVersion);
                                } else {
                                    AppUtils.showToast(this, "Failed to create container");
                                }
                                finish();
                            }
                        );
                    } catch (JSONException e) {
                        AppUtils.showToast(this, "Failed to switch Proton version");
                        finish();
                    }
                });
            } else {
                finish();
            }
        } else if (ACTION_INSTALL_DRIVER.equals(action)) {
            DownloadProgressDialog dialog = new DownloadProgressDialog(this);
            dialog.show(R.string.installing_wine_files);
            ContentsManager contentsManager = new ContentsManager(this);
            Executors.newSingleThreadExecutor().execute(() -> {
                ImageFsInstaller.installDriversFromAssets(dialog, this);
                runOnUiThread(() -> {
                    dialog.closeOnUiThread();
                    AppUtils.showToast(this, "Drivers installed");
                    finish();
                });
            });
            return;
        } else if (ACTION_SET_AUDIO_BACKEND.equals(action)) {
            String backend = null;
            if (extraArgs != null) {
                try {
                    org.json.JSONArray arr = new org.json.JSONArray(extraArgs);
                    if (arr.length() > 0) backend = arr.getString(0);
                } catch (JSONException e) {
                    backend = extraArgs;
                }
            }
            if (backend != null && !backend.isEmpty()) {
                container.setAudioDriver(backend);
                container.saveData();
                AppUtils.showToast(this, "Audio set to " + backend);
            }
            finish();
        } else {
            finish();
        }
    }

    private void updateDrives(Container container) {
        String drives = getDrivesString();
        if (!drives.equals(container.getDrives())) {
            container.setDrives(drives);
            container.saveData();
        }
    }

    private String getDrivesString() {
        return (
            "D:" +
            Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            ) +
            "E:" +
            instancePath
        );
    }
}

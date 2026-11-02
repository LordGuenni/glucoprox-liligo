package de.heoegbr.bgproxy.ui;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.cardview.widget.CardView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.view.ViewCompat;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

import de.heoegbr.bgproxy.R;
import de.heoegbr.bgproxy.data.ReadingManager;
import de.heoegbr.bgproxy.service.DontDieService;

public class MainActivity extends AppCompatActivity implements ReadingManager.OnNewReadingListener {
    private static final int PERMISSIONS_REQUEST_CODE = 200;

    private TextView mTvBgValue;
    private TextView mTvBgDirection;
    private TextView mTvBgTimestamp;
    private TextView mTvBgDelta;
    private TextView mTvStatusBadge;
    private TextView mTvDeviceIdBadge;
    private CardView mCardPermissionWarning;
    private Button mBtnGrantPermissions;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTvBgValue = findViewById(R.id.tv_bg_value);
        mTvBgDirection = findViewById(R.id.tv_bg_direction);
        mTvBgTimestamp = findViewById(R.id.tv_bg_timestamp);
        mTvBgDelta = findViewById(R.id.tv_bg_delta);
        mTvStatusBadge = findViewById(R.id.tv_status_badge);
        mTvDeviceIdBadge = findViewById(R.id.tv_device_id_badge);
        mCardPermissionWarning = findViewById(R.id.card_permission_warning);
        mBtnGrantPermissions = findViewById(R.id.btn_grant_permissions);

        if (mBtnGrantPermissions != null) {
            mBtnGrantPermissions.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    checkAndRequestPermissions();
                }
            });
        }

        // Load settings fragment
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.preferences_container, new PreferencesFragment())
                .commit();

        ReadingManager.getInstance(this).setListener(this);
        updateDisplay(ReadingManager.getInstance(this).getLatestReading());
        updateStatus();

        checkAndRequestPermissions();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateDisplay(ReadingManager.getInstance(this).getLatestReading());
        updateStatus();
    }

    private void updateStatus() {
        boolean hasPerms = DontDieService.hasBluetoothPermissions(this);
        if (mCardPermissionWarning != null) {
            mCardPermissionWarning.setVisibility(hasPerms ? View.GONE : View.VISIBLE);
        }

        android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
        boolean enabled = prefs.getBoolean("broadcast_en", true);
        int broadcastId = PreferencesFragment.getCleanBroadcastId(this);

        if (mTvDeviceIdBadge != null) {
            mTvDeviceIdBadge.setText(String.format(Locale.getDefault(), "ID: 0x%04X", broadcastId));
        }

        if (mTvStatusBadge != null) {
            if (!hasPerms) {
                mTvStatusBadge.setText("● PERMISSION NEEDED");
                mTvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.glucoseLow));
                ViewCompat.setBackgroundTintList(mTvStatusBadge, ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badgeOrangeBg)));
            } else if (!enabled) {
                mTvStatusBadge.setText("● BROADCAST OFF");
                mTvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badgeGreyText));
                ViewCompat.setBackgroundTintList(mTvStatusBadge, ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badgeGreyBg)));
            } else {
                mTvStatusBadge.setText("● BROADCAST ACTIVE");
                mTvStatusBadge.setTextColor(ContextCompat.getColor(this, R.color.badgeGreenText));
                ViewCompat.setBackgroundTintList(mTvStatusBadge, ColorStateList.valueOf(ContextCompat.getColor(this, R.color.badgeGreenBg)));
            }
        }
    }

    @Override
    public void onNewReading(final ReadingManager.Reading reading) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                updateDisplay(reading);
            }
        });
    }

    private void updateDisplay(ReadingManager.Reading reading) {
        if (reading == null) {
            mTvBgValue.setText("---");
            mTvBgDirection.setText("");
            mTvBgTimestamp.setText("Waiting for xDrip data...");
            if (mTvBgDelta != null) mTvBgDelta.setText("Δ --");
            return;
        }

        double val = reading.getMgDlValue();
        mTvBgValue.setText(String.format(Locale.getDefault(), "%.0f", val));

        // Format direction arrows
        String dir = reading.direction;
        mTvBgDirection.setText(formatDirection(dir));

        // Color coding
        if (val < 70) {
            mTvBgValue.setTextColor(ContextCompat.getColor(this, R.color.glucoseLow));
        } else if (val > 180) {
            mTvBgValue.setTextColor(ContextCompat.getColor(this, R.color.glucoseHigh));
        } else {
            mTvBgValue.setTextColor(ContextCompat.getColor(this, R.color.glucoseNormal));
        }

        // Calculate Delta from history
        if (mTvBgDelta != null) {
            List<ReadingManager.Reading> history = ReadingManager.getInstance(this).getHistory();
            if (history.size() >= 2) {
                double delta = val - history.get(1).getMgDlValue();
                mTvBgDelta.setText(String.format(Locale.getDefault(), "Δ %+.0f", delta));
                if (delta > 0) {
                    mTvBgDelta.setTextColor(ContextCompat.getColor(this, R.color.glucoseHigh));
                } else if (delta < 0) {
                    mTvBgDelta.setTextColor(ContextCompat.getColor(this, R.color.glucoseNormal));
                } else {
                    mTvBgDelta.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
                }
            } else {
                mTvBgDelta.setText("Δ --");
                mTvBgDelta.setTextColor(ContextCompat.getColor(this, R.color.colorTextSecondary));
            }
        }

        SimpleDateFormat sdf = new SimpleDateFormat("HH:mm", Locale.getDefault());
        mTvBgTimestamp.setText("Received at " + sdf.format(new Date(reading.timestamp)));
    }

    private String formatDirection(String slope) {
        if (slope == null) return "";
        switch (slope.toUpperCase()) {
            case "NONE":
            case "FLAT":
                return "→";
            case "FORTYFIVEUP":
            case "SINGLEUP":
                return "↗";
            case "DOUBLEUP":
                return "↑↑";
            case "FORTYFIVEDOWN":
            case "SINGLEDOWN":
                return "↘";
            case "DOUBLEDOWN":
                return "↓↓";
            default:
                return slope;
        }
    }

    private void checkAndRequestPermissions() {
        List<String> needed = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_ADVERTISE);
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.BLUETOOTH_CONNECT);
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                needed.add(Manifest.permission.POST_NOTIFICATIONS);
            }
        }

        if (!needed.isEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toArray(new String[0]), PERMISSIONS_REQUEST_CODE);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSIONS_REQUEST_CODE) {
            updateStatus();
            if (DontDieService.hasBluetoothPermissions(this)) {
                android.content.SharedPreferences prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(this);
                if (prefs.getBoolean("broadcast_en", true)) {
                    Intent serviceIntent = new Intent(this, DontDieService.class);
                    serviceIntent.setAction(DontDieService.ACTION_START);
                    try {
                        ContextCompat.startForegroundService(this, serviceIntent);
                    } catch (Exception ignored) {}
                }
            }
        }
    }

    @Override
    protected void onDestroy() {
        ReadingManager.getInstance(this).setListener(null);
        super.onDestroy();
    }
}

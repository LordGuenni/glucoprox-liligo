package de.lordguenni.glucoprox.ui;

import android.Manifest;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.preference.EditTextPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.SwitchPreferenceCompat;

import de.lordguenni.glucoprox.R;
import de.lordguenni.glucoprox.data.ReadingManager;
import de.lordguenni.glucoprox.service.DontDieService;
import de.lordguenni.glucoprox.util.NightModeHelper;

public class PreferencesFragment extends PreferenceFragmentCompat {
    private static final String TAG = "PreferencesFragment";
    private static final int REQUEST_PERMISSIONS = 1001;

    private final Handler mHandler = new Handler(Looper.getMainLooper());

    public static int getCleanBroadcastId(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String raw = prefs.getString("broadcast_id", "3");
        int id = 3;
        try {
            id = Integer.parseInt(raw);
        } catch (Exception ignored) {}

        if (id <= 0 || id >= 65535) {
            id = 3;
            prefs.edit().putString("broadcast_id", "3").apply();
        }
        return id;
    }

    public static boolean isAesEnabled(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        return prefs.getBoolean("aes_en", true);
    }

    public static byte[] getCleanAesKey(Context context) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(context);
        String raw = prefs.getString("aes_key", "GlucoProxSecret1");
        byte[] key = new byte[16];
        if (raw != null) {
            byte[] rawBytes = raw.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            System.arraycopy(rawBytes, 0, key, 0, Math.min(rawBytes.length, 16));
        }
        // Fill remaining with default if shorter
        if (raw == null || raw.isEmpty()) {
            key = "GlucoProxSecret1".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        }
        return key;
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.preferences, rootKey);

        // Broadcast ID
        EditTextPreference idPref = findPreference("broadcast_id");
        if (idPref != null) {
            idPref.setOnBindEditTextListener(new EditTextPreference.OnBindEditTextListener() {
                @Override
                public void onBindEditText(@NonNull EditText editText) {
                    editText.setInputType(InputType.TYPE_CLASS_NUMBER);
                }
            });

            idPref.setSummaryProvider(new Preference.SummaryProvider<EditTextPreference>() {
                @Override
                public CharSequence provideSummary(EditTextPreference preference) {
                    int id = getCleanBroadcastId(requireContext());
                    return String.format("0x%04X (%d)", id, id);
                }
            });

            idPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    triggerServiceUpdate();
                    return true;
                }
            });
        }

        // Enable / Disable Switch
        SwitchPreferenceCompat switchPref = findPreference("broadcast_en");
        if (switchPref != null) {
            switchPref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    boolean enabled = (Boolean) newValue;
                    if (enabled) {
                        requestRequiredPermissions();
                        startBroadcastService();
                    } else {
                        stopBroadcastService();
                    }
                    return true;
                }
            });
        }

        // Duration, Advertising Speed, TX Power, Auto-Refresh
        Preference.OnPreferenceChangeListener restartListener = new Preference.OnPreferenceChangeListener() {
            @Override
            public boolean onPreferenceChange(Preference preference, Object newValue) {
                triggerServiceUpdate();
                return true;
            }
        };

        Preference durationPref = findPreference("broadcast_duration");
        if (durationPref != null) durationPref.setOnPreferenceChangeListener(restartListener);

        Preference modePref = findPreference("adv_mode");
        if (modePref != null) modePref.setOnPreferenceChangeListener(restartListener);

        Preference powerPref = findPreference("adv_tx_power");
        if (powerPref != null) powerPref.setOnPreferenceChangeListener(restartListener);

        Preference refreshPref = findPreference("auto_refresh_age");
        if (refreshPref != null) refreshPref.setOnPreferenceChangeListener(restartListener);

        Preference aesEnPref = findPreference("aes_en");
        if (aesEnPref != null) aesEnPref.setOnPreferenceChangeListener(restartListener);

        EditTextPreference aesKeyPref = findPreference("aes_key");
        if (aesKeyPref != null) {
            aesKeyPref.setOnPreferenceChangeListener(restartListener);
            aesKeyPref.setSummaryProvider(new Preference.SummaryProvider<EditTextPreference>() {
                @Override
                public CharSequence provideSummary(EditTextPreference preference) {
                    String k = preference.getText();
                    if (k == null || k.isEmpty()) return "Using default key";
                    return "Key: " + k;
                }
            });
        }

        // Night Mode Listeners & Dynamic Status Summary
        Preference nightTypePref = findPreference(NightModeHelper.PREF_NIGHT_MODE_TYPE);
        if (nightTypePref != null) {
            nightTypePref.setOnPreferenceChangeListener(new Preference.OnPreferenceChangeListener() {
                @Override
                public boolean onPreferenceChange(Preference preference, Object newValue) {
                    if (NightModeHelper.TYPE_SUNDOWN.equals(newValue) && !NightModeHelper.hasLocationPermission(requireContext())) {
                        requestPermissions(new String[]{
                                Manifest.permission.ACCESS_COARSE_LOCATION,
                                Manifest.permission.ACCESS_FINE_LOCATION
                        }, 1002);
                    }
                    triggerServiceUpdate();
                    return true;
                }
            });
            nightTypePref.setSummaryProvider(new Preference.SummaryProvider<ListPreference>() {
                @Override
                public CharSequence provideSummary(ListPreference preference) {
                    return NightModeHelper.getStatusSummary(requireContext());
                }
            });
        }

        Preference nightStartPref = findPreference(NightModeHelper.PREF_NIGHT_START);
        if (nightStartPref != null) nightStartPref.setOnPreferenceChangeListener(restartListener);

        Preference nightEndPref = findPreference(NightModeHelper.PREF_NIGHT_END);
        if (nightEndPref != null) nightEndPref.setOnPreferenceChangeListener(restartListener);

        Preference nightLatPref = findPreference(NightModeHelper.PREF_NIGHT_LAT);
        if (nightLatPref != null) nightLatPref.setOnPreferenceChangeListener(restartListener);

        Preference nightLonPref = findPreference(NightModeHelper.PREF_NIGHT_LON);
        if (nightLonPref != null) nightLonPref.setOnPreferenceChangeListener(restartListener);

        // Test Reading Dialog
        Preference testPref = findPreference("test_reading");
        if (testPref != null) {
            testPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    showTestReadingDialog();
                    return true;
                }
            });
        }

        // Clear History
        Preference clearPref = findPreference("clear_history");
        if (clearPref != null) {
            clearPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    showClearHistoryDialog();
                    return true;
                }
            });
        }

        // Permission Check
        Preference permPref = findPreference("permission_check");
        if (permPref != null) {
            permPref.setOnPreferenceClickListener(new Preference.OnPreferenceClickListener() {
                @Override
                public boolean onPreferenceClick(Preference preference) {
                    requestRequiredPermissions();
                    return true;
                }
            });
        }

        updatePermissionStatusSummary();
    }

    @Override
    public void onResume() {
        super.onResume();
        updatePermissionStatusSummary();
    }

    private void updatePermissionStatusSummary() {
        Preference permPref = findPreference("permission_check");
        if (permPref != null && getContext() != null) {
            if (DontDieService.hasBluetoothPermissions(getContext())) {
                permPref.setSummary("All required permissions granted ✓");
            } else {
                permPref.setSummary("Missing Bluetooth permissions! Tap to grant ⚠");
            }
        }
    }

    private void showTestReadingDialog() {
        Context context = requireContext();
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setTitle("Send Test Glucose Reading");

        LinearLayout layout = new LinearLayout(context);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 40, 50, 20);

        TextView labelVal = new TextView(context);
        labelVal.setText("Blood Glucose (mg/dl):");
        layout.addView(labelVal);

        final EditText inputBg = new EditText(context);
        inputBg.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        inputBg.setText("125");
        layout.addView(inputBg);

        TextView labelSlope = new TextView(context);
        labelSlope.setText("Trend Direction:");
        labelSlope.setPadding(0, 20, 0, 0);
        layout.addView(labelSlope);

        final String[] slopes = {"Flat (→)", "SingleUp (↗)", "DoubleUp (↑↑)", "SingleDown (↘)", "DoubleDown (↓↓)", "FortyFiveUp (↗)", "FortyFiveDown (↘)"};
        final String[] slopeKeys = {"Flat", "SingleUp", "DoubleUp", "SingleDown", "DoubleDown", "FortyFiveUp", "FortyFiveDown"};

        final Spinner spinnerSlope = new Spinner(context);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(context, android.R.layout.simple_spinner_dropdown_item, slopes);
        spinnerSlope.setAdapter(adapter);
        layout.addView(spinnerSlope);

        builder.setView(layout);

        builder.setPositiveButton("Broadcast", new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which) {
                String bgStr = inputBg.getText().toString().trim();
                double bg = 120.0;
                try {
                    bg = Double.parseDouble(bgStr);
                } catch (Exception ignored) {}

                String slope = slopeKeys[spinnerSlope.getSelectedItemPosition()];
                long now = System.currentTimeMillis();

                ReadingManager.getInstance(requireContext()).addReading(bg, slope, now);

                if (isServiceRunning() && DontDieService.hasBluetoothPermissions(requireContext())) {
                    Intent intent = new Intent(requireContext(), DontDieService.class);
                    intent.setAction(DontDieService.ACTION_UPDATE_ADVERT);
                    try {
                        ContextCompat.startForegroundService(requireContext(), intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start service for test reading", e);
                    }
                }

                Toast.makeText(requireContext(), String.format(java.util.Locale.getDefault(),
                        "Broadcasted test reading: %.0f mg/dl (%s)", bg, slope), Toast.LENGTH_SHORT).show();
            }
        });

        builder.setNegativeButton("Cancel", null);
        builder.show();
    }

    private void showClearHistoryDialog() {
        Context context = requireContext();
        new AlertDialog.Builder(context)
                .setTitle("Clear Reading History")
                .setMessage("Are you sure you want to remove all saved blood glucose readings?")
                .setPositiveButton("Clear", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ReadingManager.getInstance(requireContext()).clearHistory();
                        Toast.makeText(requireContext(), "Reading history cleared", Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void triggerServiceUpdate() {
        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (isAdded() && isServiceRunning() && DontDieService.hasBluetoothPermissions(requireContext())) {
                    Intent intent = new Intent(requireContext(), DontDieService.class);
                    intent.setAction(DontDieService.ACTION_UPDATE_ADVERT);
                    try {
                        ContextCompat.startForegroundService(requireContext(), intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to update service", e);
                    }
                }
            }
        }, 150);
    }

    private void requestRequiredPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH_ADVERTISE,
                    Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.POST_NOTIFICATIONS
            }, REQUEST_PERMISSIONS);
        } else {
            requestPermissions(new String[]{
                    Manifest.permission.BLUETOOTH,
                    Manifest.permission.BLUETOOTH_ADMIN
            }, REQUEST_PERMISSIONS);
        }
    }

    private boolean isServiceRunning() {
        Context ctx = getContext();
        if (ctx == null) return false;
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        return prefs.getBoolean("broadcast_en", true);
    }

    private void startBroadcastService() {
        Context ctx = requireContext();
        if (!DontDieService.hasBluetoothPermissions(ctx)) {
            requestRequiredPermissions();
            return;
        }
        Intent intent = new Intent(ctx, DontDieService.class);
        intent.setAction(DontDieService.ACTION_START);
        try {
            ContextCompat.startForegroundService(ctx, intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to start service", e);
            Toast.makeText(ctx, "Failed to start service: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void stopBroadcastService() {
        Context ctx = requireContext();
        Intent intent = new Intent(ctx, DontDieService.class);
        intent.setAction(DontDieService.ACTION_STOP);
        ctx.startService(intent);
    }
}


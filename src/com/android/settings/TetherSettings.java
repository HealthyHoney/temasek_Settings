/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings;

import android.app.Activity;
import android.app.Dialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothPan;
import android.bluetooth.BluetoothProfile;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.usb.UsbManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemProperties;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.os.UserHandle;
import android.os.UserManager;
import android.preference.ListPreference;
import android.preference.Preference;
import android.preference.PreferenceScreen;
import android.preference.SwitchPreference;
import android.provider.Settings;
import android.widget.TextView;

import com.android.settings.wifi.WifiApDialog;
import com.android.settings.wifi.WifiApEnabler;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;

/*
 * Displays preferences for Tethering.
 */
public class TetherSettings extends SettingsPreferenceFragment
        implements DialogInterface.OnClickListener, Preference.OnPreferenceChangeListener {
    private static final String TAG = "TetherSettings";

    private static final String USB_TETHER_SETTINGS = "usb_tether_settings";
    private static final String USB_TETHER_NETWORK = "tether_usb_network";
    private static final String ENABLE_WIFI_AP = "enable_wifi_ap";
    private static final String ENABLE_BLUETOOTH_TETHERING = "enable_bluetooth_tethering";
    private static final String TETHER_CHOICE = "TETHER_TYPE";

    private static final int DIALOG_AP_SETTINGS = 1;

    private SwitchPreference mUsbTether;

    private WifiApEnabler mWifiApEnabler;
    private SwitchPreference mEnableWifiAp;
    private ListPreference mUsbNetList;

    private SwitchPreference mBluetoothTether;
    private StorageManager mStorageManager = null;

    private BroadcastReceiver mTetherChangeReceiver;

    private String[] mUsbRegexs;

    private String[] mWifiRegexs;

    private String[] mBluetoothRegexs;
    private AtomicReference<BluetoothPan> mBluetoothPan = new AtomicReference<BluetoothPan>();

    private static final String WIFI_AP_SSID_AND_SECURITY = "wifi_ap_ssid_and_security";
    private static final int CONFIG_SUBTEXT = R.string.wifi_tether_configure_subtext;

    private String[] mSecurityType;
    private Preference mCreateNetwork;

    private WifiApDialog mWifiDialog;
    private WifiManager mWifiManager;
    private WifiConfiguration mWifiConfig = null;
    private UserManager mUm;

    private boolean mUsbConnected;
    private boolean mMassStorageActive;

    private boolean mBluetoothEnableForTether;

    public static final int INVALID             = -1;
    public static final int WIFI_TETHERING      = 0;
    public static final int USB_TETHERING       = 1;
    public static final int BLUETOOTH_TETHERING = 2;

    /* One of INVALID, WIFI_TETHERING, USB_TETHERING or BLUETOOTH_TETHERING */
    private int mTetherChoice = INVALID;

    /* Stores the package name and the class name of the provisioning app */
    private String[] mProvisionApp;
    private static final int PROVISION_REQUEST = 0;

    private boolean mUnavailable;

    @Override
    public void onCreate(Bundle icicle) {
        super.onCreate(icicle);

        if(icicle != null) {
            mTetherChoice = icicle.getInt(TETHER_CHOICE);
        }
        addPreferencesFromResource(R.xml.tether_prefs);

        mUm = (UserManager) getSystemService(Context.USER_SERVICE);

        if (mUm.hasUserRestriction(UserManager.DISALLOW_CONFIG_TETHERING)) {
            mUnavailable = true;
            setPreferenceScreen(new PreferenceScreen(getActivity(), null));
            return;
        }

        mEnableWifiAp =
                (SwitchPreference) findPreference(ENABLE_WIFI_AP);
        Preference wifiApSettings = findPreference(WIFI_AP_SSID_AND_SECURITY);
        mUsbTether = (SwitchPreference) findPreference(USB_TETHER_SETTINGS);
        mBluetoothTether = (SwitchPreference) findPreference(ENABLE_BLUETOOTH_TETHERING);
        mUsbNetList = (ListPreference) findPreference(USB_TETHER_NETWORK);

        final Activity activity = getActivity();
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null) {
            adapter.getProfileProxy(activity.getApplicationContext(), mProfileServiceListener,
                    BluetoothProfile.PAN);
        }

        mStorageManager = (StorageManager)getSystemService(Context.STORAGE_SERVICE);
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        mUsbRegexs = cm.getTetherableUsbRegexs();
        mWifiRegexs = cm.getTetherableWifiRegexs();
        mBluetoothRegexs = cm.getTetherableBluetoothRegexs();

        final boolean usbAvailable = mUsbRegexs.length != 0;
        final boolean wifiAvailable = mWifiRegexs.length != 0;
        final boolean bluetoothAvailable = mBluetoothRegexs.length != 0;

        if (!usbAvailable || Utils.isMonkeyRunning()) {
            getPreferenceScreen().removePreference(mUsbTether);
            getPreferenceScreen().removePreference(mUsbNetList);
        }

        if (wifiAvailable && !Utils.isMonkeyRunning()) {
            mWifiApEnabler = new WifiApEnabler(activity, mEnableWifiAp);
            initWifiTethering();
        } else {
            getPreferenceScreen().removePreference(mEnableWifiAp);
            getPreferenceScreen().removePreference(wifiApSettings);
        }

        if (!bluetoothAvailable) {
            getPreferenceScreen().removePreference(mBluetoothTether);
        } else {
            BluetoothPan pan = mBluetoothPan.get();
            if (isBtPanAllowed() && pan.isTetheringOn()) {
                mBluetoothTether.setChecked(true);
            } else {
                mBluetoothTether.setChecked(false);
            }
        }

        if (mUsbNetList != null) {
            mUsbNetList.setOnPreferenceChangeListener(null);
        }

        mProvisionApp = getResources().getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app);
    }

    @Override
    public void onSaveInstanceState(Bundle savedInstanceState) {
        savedInstanceState.putInt(TETHER_CHOICE, mTetherChoice);
        super.onSaveInstanceState(savedInstanceState);
    }

    private void initWifiTethering() {
        final Activity activity = getActivity();
        mWifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);
        mWifiConfig = mWifiManager.getWifiApConfiguration();
        mSecurityType = getResources().getStringArray(R.array.wifi_ap_security);

        mCreateNetwork = findPreference(WIFI_AP_SSID_AND_SECURITY);

        if (mWifiConfig == null) {
            final String s = activity.getString(
                    com.android.internal.R.string.wifi_tether_configure_ssid_default);
            mCreateNetwork.setSummary(String.format(activity.getString(CONFIG_SUBTEXT),
                    s, mSecurityType[WifiApDialog.OPEN_INDEX]));
        } else {
            int index = WifiApDialog.getSecurityTypeIndex(mWifiConfig);
            mCreateNetwork.setSummary(String.format(activity.getString(CONFIG_SUBTEXT),
                    mWifiConfig.SSID,
                    mSecurityType[index]));
        }
    }

    private BluetoothProfile.ServiceListener mProfileServiceListener =
        new BluetoothProfile.ServiceListener() {
        public void onServiceConnected(int profile, BluetoothProfile proxy) {
            mBluetoothPan.set((BluetoothPan) proxy);
            if (mBluetoothTether != null) {
                boolean enabled = isBtPanAllowed();
                mBluetoothTether.setEnabled(enabled);
                mBluetoothTether.setChecked(enabled && ((BluetoothPan)proxy).isTetheringOn());
            }
        }
        public void onServiceDisconnected(int profile) {
            mBluetoothPan.set(null);
            if (mBluetoothTether != null) {
                mBluetoothTether.setEnabled(false);
            }
        }
    };

    @Override
    public Dialog onCreateDialog(int id) {
        if (id == DIALOG_AP_SETTINGS) {
            final Activity activity = getActivity();
            mWifiDialog = new WifiApDialog(activity, this, mWifiConfig);
            return mWifiDialog;
        }

        return null;
    }

    private class TetherChangeReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context content, Intent intent) {
            String action = intent.getAction();
            if (action.equals(ConnectivityManager.ACTION_TETHER_STATE_CHANGED)) {
                // TODO - this should understand the interface types
                ArrayList<String> available = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_AVAILABLE_TETHER);
                ArrayList<String> active = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_ACTIVE_TETHER);
                ArrayList<String> errored = intent.getStringArrayListExtra(
                        ConnectivityManager.EXTRA_ERRORED_TETHER);
                updateState(available.toArray(new String[available.size()]),
                        active.toArray(new String[active.size()]),
                        errored.toArray(new String[errored.size()]));
            } else if (action.equals(Intent.ACTION_MEDIA_SHARED)) {
                mMassStorageActive = true;
                updateState();
            } else if (action.equals(Intent.ACTION_MEDIA_UNSHARED)) {
                mMassStorageActive = false;
                updateState();
            } else if (action.equals(UsbManager.ACTION_USB_STATE)) {
                mUsbConnected = intent.getBooleanExtra(UsbManager.USB_CONNECTED, false);
                updateState();
            } else if (action.equals(BluetoothAdapter.ACTION_STATE_CHANGED)) {
                if (mBluetoothEnableForTether) {
                    switch (intent
                            .getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                        case BluetoothAdapter.STATE_ON:
                            BluetoothPan bluetoothPan = mBluetoothPan.get();
                            if (isBtPanAllowed()) {
                                bluetoothPan.setBluetoothTethering(true);
                                mBluetoothEnableForTether = false;
                            }
                            break;

                        case BluetoothAdapter.STATE_OFF:
                        case BluetoothAdapter.ERROR:
                            mBluetoothEnableForTether = false;
                            break;

                        default:
                            // ignore transition states
                    }
                }
                updateState();
            }
        }
    }

    @Override
    public void onStart() {
        super.onStart();

        if (mUnavailable) {
            TextView emptyView = (TextView) getView().findViewById(android.R.id.empty);
            getListView().setEmptyView(emptyView);
            if (emptyView != null) {
                emptyView.setText(R.string.tethering_settings_not_available);
            }
            return;
        }

        final Activity activity = getActivity();
        if (mStorageManager != null) {
            final StorageVolume[] volumes = mStorageManager.getVolumeList();
            if (volumes == null) {
                mMassStorageActive = false;
            } else {
                final ArrayList<StorageVolume> physicalVols =
                        mStorageManager.getPhysicalExternalVolume(volumes);
                if (physicalVols.size() == 0) {
                    mMassStorageActive = false;
                } else {
                    mMassStorageActive =
                            Environment.MEDIA_SHARED.equals(physicalVols.get(0).getState());
                }
            }
        } else {
            mMassStorageActive = false;
        }

        mTetherChangeReceiver = new TetherChangeReceiver();
        IntentFilter filter = new IntentFilter(ConnectivityManager.ACTION_TETHER_STATE_CHANGED);
        Intent intent = activity.registerReceiver(mTetherChangeReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_STATE);
        activity.registerReceiver(mTetherChangeReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_SHARED);
        filter.addAction(Intent.ACTION_MEDIA_UNSHARED);
        filter.addDataScheme("file");
        activity.registerReceiver(mTetherChangeReceiver, filter);

        filter = new IntentFilter();
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        activity.registerReceiver(mTetherChangeReceiver, filter);

        if (intent != null) mTetherChangeReceiver.onReceive(activity, intent);
        if (mWifiApEnabler != null) {
            mEnableWifiAp.setOnPreferenceChangeListener(this);
            mWifiApEnabler.resume();
        }

        if (mUsbNetList != null) {
            mUsbNetList.setOnPreferenceChangeListener(this);
        }

        setUsbNetwork(null, null);

        updateState();
    }

    @Override
    public void onStop() {
        super.onStop();

        if (mUnavailable) {
            return;
        }
        getActivity().unregisterReceiver(mTetherChangeReceiver);
        mTetherChangeReceiver = null;
        if (mWifiApEnabler != null) {
            mEnableWifiAp.setOnPreferenceChangeListener(null);
            mWifiApEnabler.pause();
        }

        if (mUsbNetList != null) {
            mUsbNetList.setOnPreferenceChangeListener(null);
        }
    }

    private void updateState() {
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        String[] available = cm.getTetherableIfaces();
        String[] tethered = cm.getTetheredIfaces();
        String[] errored = cm.getTetheringErroredIfaces();
        updateState(available, tethered, errored);
    }

    private void updateState(String[] available, String[] tethered,
            String[] errored) {
        updateUsbState(available, tethered, errored);
        updateBluetoothState(available, tethered, errored);
    }


    private void updateUsbState(String[] available, String[] tethered,
            String[] errored) {
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        boolean usbAvailable = mUsbConnected && !mMassStorageActive;
        int usbError = ConnectivityManager.TETHER_ERROR_NO_ERROR;
        for (String s : available) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) {
                    if (usbError == ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                        usbError = cm.getLastTetherError(s);
                    }
                }
            }
        }
        boolean usbTethered = false;
        for (String s : tethered) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) usbTethered = true;
            }
        }
        boolean usbErrored = false;
        for (String s: errored) {
            for (String regex : mUsbRegexs) {
                if (s.matches(regex)) usbErrored = true;
            }
        }

        if (usbTethered) {
            mUsbTether.setSummary(R.string.usb_tethering_active_subtext);
            mUsbTether.setEnabled(true);
            mUsbTether.setChecked(true);
            mUsbNetList.setEnabled(true);
        } else if (usbAvailable) {
            if (usbError == ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                mUsbTether.setSummary(R.string.usb_tethering_available_subtext);
            } else {
                mUsbTether.setSummary(R.string.usb_tethering_errored_subtext);
            }
            mUsbTether.setEnabled(true);
            mUsbTether.setChecked(false);
            mUsbNetList.setEnabled(true);
        } else if (usbErrored) {
            mUsbTether.setSummary(R.string.usb_tethering_errored_subtext);
            mUsbTether.setEnabled(false);
            mUsbTether.setChecked(false);
            mUsbNetList.setEnabled(false);
        } else if (mMassStorageActive) {
            mUsbTether.setSummary(R.string.usb_tethering_storage_active_subtext);
            mUsbTether.setEnabled(false);
            mUsbTether.setChecked(false);
            mUsbNetList.setEnabled(false);
        } else {
            mUsbTether.setSummary(R.string.usb_tethering_unavailable_subtext);
            mUsbTether.setEnabled(false);
            mUsbTether.setChecked(false);
            mUsbNetList.setEnabled(false);
        }
    }

    private void updateBluetoothState(String[] available, String[] tethered,
            String[] errored) {
        boolean bluetoothErrored = false;
        for (String s: errored) {
            for (String regex : mBluetoothRegexs) {
                if (s.matches(regex)) bluetoothErrored = true;
            }
        }

        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null)
            return;
        int btState = adapter.getState();
        if (btState == BluetoothAdapter.STATE_TURNING_OFF) {
            mBluetoothTether.setEnabled(false);
            mBluetoothTether.setSummary(R.string.bluetooth_turning_off);
        } else if (btState == BluetoothAdapter.STATE_TURNING_ON) {
            mBluetoothTether.setEnabled(false);
            mBluetoothTether.setSummary(R.string.bluetooth_turning_on);
        } else {
            BluetoothPan bluetoothPan = mBluetoothPan.get();
            if (btState == BluetoothAdapter.STATE_ON && isBtPanAllowed() &&
                    bluetoothPan.isTetheringOn()) {
                mBluetoothTether.setChecked(true);
                mBluetoothTether.setEnabled(true);
                int bluetoothTethered = bluetoothPan.getConnectedDevices().size();
                if (bluetoothTethered > 1) {
                    String summary = getString(
                            R.string.bluetooth_tethering_devices_connected_subtext,
                            bluetoothTethered);
                    mBluetoothTether.setSummary(summary);
                } else if (bluetoothTethered == 1) {
                    mBluetoothTether.setSummary(
                            R.string.bluetooth_tethering_device_connected_subtext);
                } else if (bluetoothErrored) {
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_errored_subtext);
                } else {
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_available_subtext);
                }
            } else {
                mBluetoothTether.setEnabled(true);
                mBluetoothTether.setChecked(false);
                mBluetoothTether.setSummary(R.string.bluetooth_tethering_off_subtext);
            }
        }
    }

    private void setUsbNetwork(Preference preference, Object newValue) {
        final Activity activity = getActivity();

        String network = null;
        String summary = null;

        if (newValue == null) {
            /* initializing the preference setting for display only */
            network = Settings.Global.getString(activity.getContentResolver(),
                    Settings.Global.TETHER_USB_NETWORK);
            if ((network == null) || (network.length() == 0)) {
                CharSequence[] values = mUsbNetList.getEntryValues();
                mUsbNetList.setValueIndex(0);
                summary = values[0].toString();
            } else {
                mUsbNetList.setValue(network);
                summary = network;
            }
        } else {
            /* handling a selection by the user to change networks */
            network = newValue.toString();
            summary = network;

            if (mUsbNetList.findIndexOfValue(network) <= 0) {
                network = null;
                mUsbNetList.setValueIndex(0);
                CharSequence[] values = mUsbNetList.getEntryValues();
                summary = values[0].toString();
            }

            android.provider.Settings.Global.putString(activity.getApplicationContext().getContentResolver(),
                    android.provider.Settings.Global.TETHER_USB_NETWORK, network);
        }

        mUsbNetList.setSummary(summary);
    }

    @Override
    public boolean onPreferenceChange(Preference preference, Object value) {
        if (preference == mUsbNetList) {
            setUsbNetwork(preference, value);
        } else {
            boolean enable = (Boolean) value;

            if (enable) {
                startProvisioningIfNecessary(WIFI_TETHERING);
            } else {
                if (isProvisioningNeeded(mProvisionApp)) {
                    TetherService.cancelRecheckAlarmIfNecessary(getActivity(), WIFI_TETHERING);
                }
                mWifiApEnabler.setSoftapEnabled(false);
            }
        }
        return false;
    }

    public static boolean isProvisioningNeededButUnavailable(Context context) {
        String[] provisionApp = context.getResources().getStringArray(
                com.android.internal.R.array.config_mobile_hotspot_provision_app);
        return (isProvisioningNeeded(provisionApp)
                && !isIntentAvailable(context, provisionApp));
    }

    private static boolean isIntentAvailable(Context context, String[] provisionApp) {
        if (provisionApp.length <  2) {
            throw new IllegalArgumentException("provisionApp length should at least be 2");
        }
        final PackageManager packageManager = context.getPackageManager();
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setClassName(provisionApp[0], provisionApp[1]);

        return (packageManager.queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY).size() > 0);
    }


    private static boolean isProvisioningNeeded(String[] provisionApp) {
        if (SystemProperties.getBoolean("net.tethering.noprovisioning", false)
                || provisionApp == null) {
            return false;
        }
        return (provisionApp.length == 2);
    }

    private void startProvisioningIfNecessary(int choice) {
        mTetherChoice = choice;
        if (isProvisioningNeeded(mProvisionApp)) {
            Intent intent = new Intent(Intent.ACTION_MAIN);
            intent.setClassName(mProvisionApp[0], mProvisionApp[1]);
            intent.putExtra(TETHER_CHOICE, mTetherChoice);
            startActivityForResult(intent, PROVISION_REQUEST);
        } else {
            startTethering();
        }
    }

    public void onActivityResult(int requestCode, int resultCode, Intent intent) {
        super.onActivityResult(requestCode, resultCode, intent);
        if (requestCode == PROVISION_REQUEST) {
            if (resultCode == Activity.RESULT_OK) {
                TetherService.scheduleRecheckAlarm(getActivity(), mTetherChoice);
                startTethering();
            } else {
                //BT and USB need switch turned off on failure
                //Wifi tethering is never turned on until afterwards
                switch (mTetherChoice) {
                    case BLUETOOTH_TETHERING:
                        mBluetoothTether.setChecked(false);
                        break;
                    case USB_TETHERING:
                        mUsbTether.setChecked(false);
                        break;
                }
                mTetherChoice = INVALID;
            }
        }
    }

    private void startTethering() {
        switch (mTetherChoice) {
            case WIFI_TETHERING:
                mWifiApEnabler.setSoftapEnabled(true);
                break;
            case BLUETOOTH_TETHERING:
                // turn on Bluetooth first
                BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                if (adapter.getState() == BluetoothAdapter.STATE_OFF) {
                    mBluetoothEnableForTether = true;
                    adapter.enable();
                    mBluetoothTether.setSummary(R.string.bluetooth_turning_on);
                    mBluetoothTether.setEnabled(false);
                } else {
                    BluetoothPan bluetoothPan = mBluetoothPan.get();
                    if (isBtPanAllowed()) bluetoothPan.setBluetoothTethering(true);
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_available_subtext);
                }
                break;
            case USB_TETHERING:
                setUsbTethering(true);
                break;
            default:
                //should not happen
                break;
        }
    }

    private void setUsbTethering(boolean enabled) {
        ConnectivityManager cm =
            (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);
        mUsbTether.setChecked(false);
        if (cm.setUsbTethering(enabled) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
            mUsbTether.setSummary(R.string.usb_tethering_errored_subtext);
            return;
        }
        mUsbTether.setSummary("");
    }

    private boolean isBtPanAllowed() {
        try {
            BluetoothPan btPan = mBluetoothPan.get();
            if (btPan == null) {
                return false;
            }
            btPan.isTetheringOn();
        } catch (NullPointerException ex) {
            // Ignore
            return false;
        }
        return true;
    }

    @Override
    public boolean onPreferenceTreeClick(PreferenceScreen screen, Preference preference) {
        ConnectivityManager cm =
                (ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);

        if (preference == mUsbTether) {
            boolean newState = mUsbTether.isChecked();

            if (newState) {
                startProvisioningIfNecessary(USB_TETHERING);
            } else {
                if (isProvisioningNeeded(mProvisionApp)) {
                    TetherService.cancelRecheckAlarmIfNecessary(getActivity(), USB_TETHERING);
                }
                setUsbTethering(newState);
            }
        } else if (preference == mBluetoothTether) {
            boolean bluetoothTetherState = mBluetoothTether.isChecked();

            if (bluetoothTetherState) {
                startProvisioningIfNecessary(BLUETOOTH_TETHERING);
            } else {
                if (isProvisioningNeeded(mProvisionApp)) {
                    TetherService.cancelRecheckAlarmIfNecessary(getActivity(), BLUETOOTH_TETHERING);
                }
                boolean errored = false;

                String [] tethered = cm.getTetheredIfaces();
                String bluetoothIface = findIface(tethered, mBluetoothRegexs);
                if (bluetoothIface != null &&
                        cm.untether(bluetoothIface) != ConnectivityManager.TETHER_ERROR_NO_ERROR) {
                    errored = true;
                }

                BluetoothPan bluetoothPan = mBluetoothPan.get();
                if (isBtPanAllowed()) bluetoothPan.setBluetoothTethering(false);
                if (errored) {
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_errored_subtext);
                } else {
                    mBluetoothTether.setSummary(R.string.bluetooth_tethering_off_subtext);
                }
            }
        } else if (preference == mCreateNetwork) {
            showDialog(DIALOG_AP_SETTINGS);
        }

        return super.onPreferenceTreeClick(screen, preference);
    }

    private static String findIface(String[] ifaces, String[] regexes) {
        for (String iface : ifaces) {
            for (String regex : regexes) {
                if (iface.matches(regex)) {
                    return iface;
                }
            }
        }
        return null;
    }

    public void onClick(DialogInterface dialogInterface, int button) {
        if (button == DialogInterface.BUTTON_POSITIVE) {
            mWifiConfig = mWifiDialog.getConfig();

            /* always set the tether network */
            setWifiTetherNetwork();

            if (mWifiConfig != null) {

                /**
                 * if soft AP is stopped, bring up
                 * else restart with new config
                 * TODO: update config on a running access point when framework support is added
                 */
                if (mWifiManager.getWifiApState() == WifiManager.WIFI_AP_STATE_ENABLED) {
                    mWifiManager.setWifiApEnabled(null, false);
                    mWifiManager.setWifiApEnabled(mWifiConfig, true);
                } else {
                    mWifiManager.setWifiApConfiguration(mWifiConfig);
                }
                int index = WifiApDialog.getSecurityTypeIndex(mWifiConfig);
                mCreateNetwork.setSummary(String.format(getActivity().getString(CONFIG_SUBTEXT),
                        mWifiConfig.SSID,
                        mSecurityType[index]));
            }
        }
    }

    @Override
    public int getHelpResource() {
        return R.string.help_url_tether;
    }

    /**
     * Checks whether this screen will have anything to show on this device. This is called by
     * the shortcut picker for Settings shortcuts (home screen widget).
     * @param context a context object for getting a system service.
     * @return whether Tether & portable hotspot should be shown in the shortcuts picker.
     */
    public static boolean showInShortcuts(Context context) {
        final ConnectivityManager cm =
                (ConnectivityManager)context.getSystemService(Context.CONNECTIVITY_SERVICE);
        final boolean isSecondaryUser = UserHandle.myUserId() != UserHandle.USER_OWNER;
        return !isSecondaryUser && cm.isTetheringSupported();
    }

    private void setWifiTetherNetwork() {
        final Activity activity = getActivity();
        String network = mWifiDialog.getWifiTetherNetwork();
        android.provider.Settings.Global.putString(activity.getApplicationContext().getContentResolver(),
                android.provider.Settings.Global.TETHER_WIFI_NETWORK, network);
    }
}

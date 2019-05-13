package de.vier_bier.habpanelviewer.reporting;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.os.BatteryManager;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.concurrent.atomic.AtomicBoolean;

import de.vier_bier.habpanelviewer.Constants;
import de.vier_bier.habpanelviewer.R;
import de.vier_bier.habpanelviewer.openhab.IStateUpdateListener;
import de.vier_bier.habpanelviewer.openhab.ServerConnection;
import de.vier_bier.habpanelviewer.status.ApplicationStatus;

/**
 * Monitors battery state and reports to openHAB.
 */
public class BatteryMonitor implements IDeviceMonitor, IStateUpdateListener {
    private static final String TAG = "HPV-BatteryMonitor";

    private final Context mCtx;
    private final ServerConnection mServerConnection;

    private final BroadcastReceiver mBatteryReceiver;
    private boolean mBatteryEnabled;

    private String mBatteryLowItem;
    private String mBatteryChargingItem;
    private String mBatteryLevelItem;

    private boolean mBatteryLow;
    private boolean mBatteryCharging;
    private Integer mBatteryLevel;
    private String mBatteryLowState;
    private String mBatteryChargingState;
    private String mBatteryLevelState;
    private final IntentFilter mIntentFilter;

    private BatteryPollingThread mPollBatteryLevel;

    public BatteryMonitor(Context context, ServerConnection serverConnection) {
        mCtx = context;
        mServerConnection = serverConnection;

        EventBus.getDefault().register(this);

        mBatteryReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                if (Intent.ACTION_BATTERY_LOW.equals(intent.getAction())
                        || Intent.ACTION_BATTERY_OKAY.equals(intent.getAction())) {
                    mBatteryLow = Intent.ACTION_BATTERY_LOW.equals(intent.getAction());
                    mServerConnection.updateState(mBatteryLowItem, mBatteryLow ? "CLOSED" : "OPEN");
                } else {
                    mBatteryCharging = Intent.ACTION_POWER_CONNECTED.equals(intent.getAction());
                    mServerConnection.updateState(mBatteryChargingItem, mBatteryCharging ? "CLOSED" : "OPEN");
                }
            }
        };

        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(Intent.ACTION_POWER_CONNECTED);
        mIntentFilter.addAction(Intent.ACTION_POWER_DISCONNECTED);
        mIntentFilter.addAction(Intent.ACTION_BATTERY_LOW);
        mIntentFilter.addAction(Intent.ACTION_BATTERY_OKAY);
    }

    @Override
    public void disablePreferences(Intent intent) { }

    @Override
    public synchronized void terminate() {
        EventBus.getDefault().unregister(this);
        try {
            mCtx.unregisterReceiver(mBatteryReceiver);
            Log.d(TAG, "unregistering battery receiver...");
        } catch (IllegalArgumentException e) {
            // not registered
        }

        if (mPollBatteryLevel != null) {
            mPollBatteryLevel.stopPolling();
            mPollBatteryLevel = null;
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onMessageEvent(ApplicationStatus status) {
        if (mBatteryEnabled) {
            String state = mCtx.getString(R.string.enabled);
            if (!mBatteryLowItem.isEmpty()) {
                state += "\n" + mCtx.getString(R.string.battLow, mBatteryLow, mBatteryLowItem, mBatteryLowState);
            }
            if (!mBatteryChargingItem.isEmpty()) {
                state += "\n" + mCtx.getString(R.string.battCharging, mBatteryCharging, mBatteryChargingItem, mBatteryChargingState);
            }
            if (!mBatteryLevelItem.isEmpty()) {
                state += "\n" + mCtx.getString(R.string.battLevel, mBatteryLevel, mBatteryLevelItem, mBatteryLevelState);
            }
            status.set(mCtx.getString(R.string.pref_battery), state);
        } else {
            status.set(mCtx.getString(R.string.pref_battery), mCtx.getString(R.string.disabled));
        }
    }

    @Override
    public synchronized void updateFromPreferences(SharedPreferences prefs) {
        if (mBatteryEnabled != prefs.getBoolean(Constants.PREF_BATTERY_ENABLED, false)) {
            mBatteryEnabled = !mBatteryEnabled;

            if (mBatteryEnabled) {
                Log.d(TAG, "registering battery receiver...");
                mCtx.registerReceiver(mBatteryReceiver, mIntentFilter);
            } else {
                Log.d(TAG, "unregistering battery receiver...");
                mCtx.unregisterReceiver(mBatteryReceiver);
            }
        }

        if (!mBatteryEnabled && mPollBatteryLevel != null) {
            mPollBatteryLevel.stopPolling();
            mPollBatteryLevel = null;
        }

        mBatteryLowItem = prefs.getString(Constants.PREF_BATTERY_ITEM, "");
        mBatteryChargingItem = prefs.getString(Constants.PREF_BATTERY_CHARGING_ITEM, "");
        mBatteryLevelItem = prefs.getString(Constants.PREF_BATTERY_LEVEL_ITEM, "");

        mServerConnection.subscribeItems(this, mBatteryLowItem, mBatteryChargingItem, mBatteryLevelItem);

        boolean canPoll = !mBatteryLevelItem.isEmpty()
                || !mBatteryChargingItem.isEmpty() || !mBatteryLowItem.isEmpty();

        if (mBatteryEnabled) {
            if (!canPoll) {
                if (mPollBatteryLevel != null) {
                    mPollBatteryLevel.stopPolling();
                    mPollBatteryLevel = null;
                }
            } else if (mPollBatteryLevel != null) {
                mPollBatteryLevel.pollNow();
            } else {
                mPollBatteryLevel = new BatteryPollingThread();
            }
        }
    }

    @Override
    public void itemUpdated(String name, String value) {
        if (name.equals(mBatteryChargingItem)) {
            mBatteryChargingState = value;
        } else if (name.equals(mBatteryLevelItem)) {
            mBatteryLevelState = value;
        } else if (name.equals(mBatteryLowItem)) {
            mBatteryLowState = value;
        }
    }

    private class BatteryPollingThread extends Thread {
        private final AtomicBoolean fRunning = new AtomicBoolean(true);
        private final AtomicBoolean fPollAll = new AtomicBoolean(true);

        BatteryPollingThread() {
            super("BatteryPollingThread");
            setDaemon(true);
            start();
        }

        void stopPolling() {
            synchronized (fRunning) {
                fRunning.set(false);
                fRunning.notifyAll();
            }
        }

        void pollNow() {
            synchronized (fRunning) {
                fPollAll.set(true);
                fRunning.notifyAll();
            }
        }

        @Override
        public void run() {
            while (fRunning.get()) {
                updateBatteryValues();

                synchronized (fRunning) {
                    try {
                        fRunning.wait("CLOSED".equals(mServerConnection.getState(mBatteryChargingItem)) ? 5000 : 300000);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        }

        private void updateBatteryValues() {
            IntentFilter ifilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
            Intent batteryStatus = null;

            if (!mBatteryLevelItem.isEmpty() || !mBatteryLowItem.isEmpty()) {
                batteryStatus = mCtx.registerReceiver(null, ifilter);

                if (batteryStatus != null) {
                    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    int newBatteryLevelState = (int) ((level / (float) scale) * 100);

                    mServerConnection.updateState(mBatteryLevelItem, String.valueOf(newBatteryLevelState));
                }
            }

            if (fPollAll.getAndSet(false)) {
                if (batteryStatus == null) {
                    batteryStatus = mCtx.registerReceiver(null, ifilter);
                }

                if (batteryStatus != null) {
                    int status = batteryStatus.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                    mBatteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL;
                    int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                    int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
                    mBatteryLevel = (int) ((level / (float) scale) * 100);
                    mBatteryLow = mBatteryLevel < 16;

                    mServerConnection.updateState(mBatteryChargingItem, mBatteryCharging ? "CLOSED" : "OPEN");
                    mServerConnection.updateState(mBatteryLowItem, mBatteryLow ? "CLOSED" : "OPEN");
                }
            }
        }
    }
}

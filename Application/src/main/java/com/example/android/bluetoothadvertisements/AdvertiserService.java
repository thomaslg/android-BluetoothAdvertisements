package com.example.android.bluetoothadvertisements;

import android.annotation.TargetApi;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.bluetooth.le.AdvertiseCallback;
import android.bluetooth.le.AdvertiseData;
import android.bluetooth.le.AdvertiseSettings;
import android.bluetooth.le.BluetoothLeAdvertiser;
import android.content.Context;
import android.content.Intent;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.SystemClock;
import android.util.Log;
import android.widget.Toast;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.concurrent.TimeUnit;

/**
 * Manages BLE Advertising independent of the main app.
 * If the app goes off screen (or gets killed completely) advertising can continue because this
 * Service is maintaining the necessary Callback in memory.
 */
public class AdvertiserService extends Service {

    private static final String TAG = AdvertiserService.class.getSimpleName();

    /**
     * A global variable to let AdvertiserFragment check if the Service is running without needing
     * to start or bind to it.
     * This is the best practice method as defined here:
     * https://groups.google.com/forum/#!topic/android-developers/jEvXMWgbgzE
     */
    public static boolean running = false;

    public static final String ADVERTISING_FAILED =
            "com.example.android.bluetoothadvertisements.advertising_failed";

    public static final String ADVERTISING_FAILED_EXTRA_CODE = "failureCode";

    public static final int ADVERTISING_TIMED_OUT = 6;

    private BluetoothLeAdvertiser mBluetoothLeAdvertiser;

    private AdvertiseCallback mAdvertiseCallback;

    private Handler mHandler;

    private Runnable timeoutRunnable;

    private BluetoothAdapter mBluetoothAdapter;
    private BluetoothServerSocket mBTServerSocket;
    private BluetoothSocket mBTSocket;


    /**
     * Length of time to allow advertising before automatically shutting off. (10 minutes)
     */
    private long TIMEOUT = TimeUnit.MILLISECONDS.convert(10, TimeUnit.MINUTES);

    @Override
    public void onCreate() {
        running = true;
        initialize();
        startAdvertising();
        setTimeout();
        super.onCreate();
    }

    @Override
    public void onDestroy() {
        /**
         * Note that onDestroy is not guaranteed to be called quickly or at all. Services exist at
         * the whim of the system, and onDestroy can be delayed or skipped entirely if memory need
         * is critical.
         */
        running = false;
        stopAdvertising();
        mHandler.removeCallbacks(timeoutRunnable);
        super.onDestroy();
    }

    /**
     * Required for extending service, but this will be a Started Service only, so no need for
     * binding.
     */
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /**
     * Get references to system Bluetooth objects if we don't have them already.
     */
    private void initialize() {
        if (mBluetoothLeAdvertiser == null) {
            BluetoothManager mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
            if (mBluetoothManager != null) {
                BluetoothAdapter mBluetoothAdapter = mBluetoothManager.getAdapter();
                if (mBluetoothAdapter != null) {
                    mBluetoothLeAdvertiser = mBluetoothAdapter.getBluetoothLeAdvertiser();
                } else {
                    Toast.makeText(this, getString(R.string.bt_null), Toast.LENGTH_LONG).show();
                }
            } else {
                Toast.makeText(this, getString(R.string.bt_null), Toast.LENGTH_LONG).show();
            }
        }

        mBluetoothAdapter = ((BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE))
                .getAdapter();
    }

    /**
     * Starts a delayed Runnable that will cause the BLE Advertising to timeout and stop after a
     * set amount of time.
     */
    private void setTimeout(){
        mHandler = new Handler();
        timeoutRunnable = new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "AdvertiserService has reached timeout of "+TIMEOUT+" milliseconds, stopping advertising.");
                sendFailureIntent(ADVERTISING_TIMED_OUT);
                stopSelf();
            }
        };
        mHandler.postDelayed(timeoutRunnable, TIMEOUT);
    }

    /**
     * Starts BLE Advertising.
     */
    private void startAdvertising() {
        Log.d(TAG, "Service: Starting Advertising");

        Log.d(TAG, "TLG ----------- trying to open the BluetoothServerSocket -------");

        if (mAdvertiseCallback == null) {
            AdvertiseSettings settings = buildAdvertiseSettings();
            AdvertiseData data = buildAdvertiseData();
            mAdvertiseCallback = new SampleAdvertiseCallback();

            if (mBluetoothLeAdvertiser != null) {
                mBluetoothLeAdvertiser.startAdvertising(settings, data,
                        mAdvertiseCallback);
            }
        }

        /* Use the Reflection method to access hidden java function
         * into BluetoothAdapter class.
         */
        final Class BTAClass;
        final Method BTAClassMethod;

        try {
            BTAClass = Class.forName(mBluetoothAdapter.getClass().getName());
        } catch (ClassNotFoundException e) {
            Log.e(TAG, "ERROR setting up the Reflection for listenUsingL2capOn");
            return;
        }
        try {
            Class[] cArg = new Class[1];
            cArg[0] = int.class;
            BTAClassMethod = BTAClass.getDeclaredMethod("listenUsingL2capOn", cArg);
        } catch (NoSuchMethodException e) {
            Log.e(TAG, "ERROR setting up the Reflection for listenUsingL2capOn");
            return;
        }

        BTAClassMethod.setAccessible(true);

        try {
            mBTServerSocket = (BluetoothServerSocket) BTAClassMethod.invoke(mBluetoothAdapter, 0x20025);
        } catch (InvocationTargetException e) {
            Log.e(TAG, "ERROR setting up the Reflection for listenUsingL2capOn");
            return;
        } catch (IllegalAccessException e) {
            Log.e(TAG, "ERROR setting up the Reflection for listenUsingL2capOn");
            return;
        }

        new OpenBluetoothSocket().execute();
    }



    private class OpenBluetoothSocket extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... params) {
            try {
                mBTSocket = mBTServerSocket.accept();
            } catch (IOException e) {
                Log.e(TAG, "ERROR opening the BluetoothServerSocket");
            }
            Log.d(TAG, "TLG --------- connection successfully created -----------");
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            Context context = getApplicationContext();
            CharSequence text = "Bluetooth Connected";
            int duration = Toast.LENGTH_SHORT;

            Toast toast = Toast.makeText(context, text, duration);
            toast.show();

            new ReceiveDataBluetooth().execute();
        }
    }

    private class ReceiveDataBluetooth extends AsyncTask<Void, Void, Void> {

        @TargetApi(Build.VERSION_CODES.M)
        @Override
        protected Void doInBackground(Void... params) {
            try {
                byte[] contents = new byte[Constants.BUFFER_SIZE];

                final File sdcard = Environment.getExternalStorageDirectory();
                final File filePcm = new File(sdcard.getAbsolutePath() + Constants.FILE);
                //new File(sdcard.getAbsolutePath() + "/" + Constants.FOLDER + "/output.pcm");

                boolean connected = mBTSocket.isConnected();
                Log.d(TAG, "TLG --------- connected? " + filePcm.canWrite() + " -----------");

                InputStream inputStream = mBTSocket.getInputStream();
                BufferedOutputStream buf = new BufferedOutputStream(new FileOutputStream(filePcm));
                Log.d(TAG, "TLG --------- Stream created -----------");

                int counter = 50;
                int byte_available = 0;

                while (inputStream.available() == 0) {
                    SystemClock.sleep(50);
                }

                while (((byte_available = inputStream.available()) > 0 ) && mBTSocket.isConnected()) {
                    if (byte_available > 0) {
                        Log.d(TAG, "TLG --------- Available to be written to file " + byte_available + " -----------");

                        if (byte_available > Constants.BUFFER_SIZE) {
                            byte_available = Constants.BUFFER_SIZE;
                        }

                        if (inputStream.read(contents, 0, byte_available) < 0) {
                            Log.e(TAG, "TLG ERROR reading the input stream");
                            byte_available = 0;
                        }
                        Log.d(TAG, "TLG --------- Writing to file " + byte_available + " -----------");
                        Log.d(TAG, "TLG --------- Remaining in file " + inputStream.available() + " -----------");

                        buf.write(contents, 0, byte_available);
                    } else {
                        counter = counter - 1;
                        Log.d(TAG, "TLG --------- Skipping "+ counter + " -----------");
                    }
                    Log.d(TAG, "TLG --------- Iteration completed, counter: "+ counter + " -----------");

                    SystemClock.sleep(10);
                }
                Log.d(TAG, "TLG --------- While loop completed -----------");

                inputStream.close();
                buf.close();
/*
                byte buffer[] = new byte[10];
                InputStream inputStream = mBTSocket.getInputStream();
                int maxReceivePacketSize = mBTSocket.getMaxReceivePacketSize();
                Log.d(TAG, "TLG --------- Max received packet size " + maxReceivePacketSize +" -----------");

                inputStream.read(buffer);
                for (int i = 0; i < buffer.length; ++i) {
                    Log.d(TAG, "buffer[" + i + "] = " + buffer[i]);
                }
                */
            } catch (IOException e) {
                Log.e(TAG, "ERROR opening the BluetoothServerSocket");
            }
            Log.d(TAG, "TLG --------- connection successfully created -----------");
            return null;
        }

        @Override
        protected void onPostExecute(Void aVoid) {
            Context context = getApplicationContext();
            CharSequence text = "Data Received";
            int duration = Toast.LENGTH_SHORT;

            Toast toast = Toast.makeText(context, text, duration);
            toast.show();
        }
    }

        /**
     * Stops BLE Advertising.
     */
    private void stopAdvertising() {
        Log.d(TAG, "Service: Stopping Advertising");
        if (mBluetoothLeAdvertiser != null) {
            mBluetoothLeAdvertiser.stopAdvertising(mAdvertiseCallback);
            mAdvertiseCallback = null;
        }
    }

    /**
     * Returns an AdvertiseData object which includes the Service UUID and Device Name.
     */
    private AdvertiseData buildAdvertiseData() {

        /**
         * Note: There is a strict limit of 31 Bytes on packets sent over BLE Advertisements.
         *  This includes everything put into AdvertiseData including UUIDs, device info, &
         *  arbitrary service or manufacturer data.
         *  Attempting to send packets over this limit will result in a failure with error code
         *  AdvertiseCallback.ADVERTISE_FAILED_DATA_TOO_LARGE. Catch this error in the
         *  onStartFailure() method of an AdvertiseCallback implementation.
         */

        AdvertiseData.Builder dataBuilder = new AdvertiseData.Builder();
        dataBuilder.addServiceUuid(Constants.Service_UUID);
        dataBuilder.setIncludeDeviceName(true);

        /* For example - this will cause advertising to fail (exceeds size limit) */
        //String failureData = "asdghkajsghalkxcjhfa;sghtalksjcfhalskfjhasldkjfhdskf";
        //dataBuilder.addServiceData(Constants.Service_UUID, failureData.getBytes());

        return dataBuilder.build();
    }

    /**
     * Returns an AdvertiseSettings object set to use low power (to help preserve battery life)
     * and disable the built-in timeout since this code uses its own timeout runnable.
     */
    private AdvertiseSettings buildAdvertiseSettings() {
        AdvertiseSettings.Builder settingsBuilder = new AdvertiseSettings.Builder();
        settingsBuilder.setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_POWER);
        settingsBuilder.setTimeout(0);
        settingsBuilder.setConnectable(true);
        return settingsBuilder.build();
    }

    /**
     * Custom callback after Advertising succeeds or fails to start. Broadcasts the error code
     * in an Intent to be picked up by AdvertiserFragment and stops this Service.
     */
    private class SampleAdvertiseCallback extends AdvertiseCallback {

        @Override
        public void onStartFailure(int errorCode) {
            super.onStartFailure(errorCode);

            Log.d(TAG, "Advertising failed");
            sendFailureIntent(errorCode);
            stopSelf();

        }

        @Override
        public void onStartSuccess(AdvertiseSettings settingsInEffect) {
            super.onStartSuccess(settingsInEffect);
            Log.d(TAG, "Advertising successfully started");
        }
    }

    /**
     * Builds and sends a broadcast intent indicating Advertising has failed. Includes the error
     * code as an extra. This is intended to be picked up by the {@code AdvertiserFragment}.
     */
    private void sendFailureIntent(int errorCode){
        Intent failureIntent = new Intent();
        failureIntent.setAction(ADVERTISING_FAILED);
        failureIntent.putExtra(ADVERTISING_FAILED_EXTRA_CODE, errorCode);
        sendBroadcast(failureIntent);
    }

}
package co.morbium.superbluetooth;

import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.util.Log;

import static co.morbium.superbluetooth.RCTBluetoothSerialPackage.TAG;

class RCTBluetoothSerialService {
    // Debugging
    private static final boolean D = true;

    // UUIDs
    private static final UUID UUID_SPP = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    // Member fields
    private BluetoothAdapter mAdapter;
    private ConnectThread mConnectThread;
    private ConnectedThread mConnectedThread;
    private RCTBluetoothSerialModule mModule;
    private String mState;

    private static final String STATE_NONE = "none"; 
    private static final String STATE_CONNECTING = "connecting"; 
    private static final String STATE_CONNECTED = "connected";  

    RCTBluetoothSerialService(RCTBluetoothSerialModule module) {
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        mState = STATE_NONE;
        mModule = module;
    }

    synchronized void connect(BluetoothDevice device) {
        if (D) Log.d(TAG, "connect to: " + device);

        if (mState.equals(STATE_CONNECTING)) {
            cancelConnectThread(); 
        }

        cancelConnectedThread(); 

        //Start  to connect with the  device
        mConnectThread = new ConnectThread(device);
        mConnectThread.start();
        setState(STATE_CONNECTING);
    }

    boolean isConnected () {
        return getState().equals(STATE_CONNECTED);
    }

    void write(byte[] out) {
        if (D) Log.d(TAG, "Write in service, state is " + STATE_CONNECTED);
        ConnectedThread r; 

        synchronized (this) {
            if (!isConnected()) return;
            r = mConnectedThread;
        }

        r.write(out); 
    }


    synchronized void stop() {
        if (D) Log.d(TAG, "stop");

        cancelConnectThread();
        cancelConnectedThread();

        setState(STATE_NONE);
    }


    private synchronized String getState() {
        return mState;
    }

    private synchronized void setState(String state) {
        if (D) Log.d(TAG, "setState() " + mState + " -> " + state);
        mState = state;
    }

    private synchronized void connectionSuccess(BluetoothSocket socket, BluetoothDevice device) {
        if (D) Log.d(TAG, "connected");

        cancelConnectThread(); // Cancel any thread attempting to make a connection
        cancelConnectedThread(); // Cancel any thread currently running a connection

        // Start the thread to manage the connection and perform transmissions
        mConnectedThread = new ConnectedThread(socket);
        mConnectedThread.start();

        mModule.onConnectionSuccess("Connected to " + device.getName());
        setState(STATE_CONNECTED);
    }

    private void connectionFailed() {
        mModule.onConnectionFailed("Unable to connect to device"); // Send a failure message
        RCTBluetoothSerialService.this.stop(); // Start the service over to restart listening mode
    }

    private void connectionLost() {
        mModule.onConnectionLost("Device connection was lost");  // Send a failure message
        RCTBluetoothSerialService.this.stop(); // Start the service over to restart listening mode
    }

    private void cancelConnectThread () {
        if (mConnectThread != null) {
            mConnectThread.cancel();
            mConnectThread = null;
        }
    }

    private void cancelConnectedThread () {
        if (mConnectedThread != null) {
            mConnectedThread.cancel();
            mConnectedThread = null;
        }
    }

    private class ConnectThread extends Thread {
        private BluetoothSocket mmSocket;
        private final BluetoothDevice mmDevice;

        ConnectThread(BluetoothDevice device) {
            mmDevice = device;
            BluetoothSocket tmp = null;

            // Get a BluetoothSocket 
            try {
                tmp = device.createRfcommSocketToServiceRecord(UUID_SPP);
            } catch (Exception e) {
                mModule.onError(e);
                Log.e(TAG, "Socket create() failed", e);
            }
            mmSocket = tmp;
        }

        public void run() {
            if (D) Log.d(TAG, "BEGIN mConnectThread");
            setName("ConnectThread");

            mAdapter.cancelDiscovery();

            try {
                if (D) Log.d(TAG,"Connecting to socket...");
                mmSocket.connect();
                if (D) Log.d(TAG,"Connected");
            } catch (Exception e) {
                Log.e(TAG, e.toString());
                mModule.onError(e);

                try {
                    Log.i(TAG,"Trying fallback...");
                    mmSocket = (BluetoothSocket) mmDevice.getClass().getMethod("createRfcommSocket", new Class[] {int.class}).invoke(mmDevice,1);
                    mmSocket.connect();
                    Log.i(TAG,"Connected");
                } catch (Exception e2) {
                    Log.e(TAG, "Couldn't establish a Bluetooth connection.");
                    mModule.onError(e2);
                    try {
                        mmSocket.close();
                    } catch (Exception e3) {
                        Log.e(TAG, "unable to close() socket during connection failure", e3);
                        mModule.onError(e3);
                    }
                    connectionFailed();
                    return;
                }
            }

            synchronized (RCTBluetoothSerialService.this) {
                mConnectThread = null;
            }

            connectionSuccess(mmSocket, mmDevice);  

        }

        void cancel() {
            try {
                mmSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "close() of connect socket failed", e);
                mModule.onError(e);
            }
        }
    }

    private class ConnectedThread extends Thread {
        private final BluetoothSocket mmSocket;
        private final InputStream mmInStream;
        private final OutputStream mmOutStream;

        ConnectedThread(BluetoothSocket socket) {
            if (D) Log.d(TAG, "create ConnectedThread");
            mmSocket = socket;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (Exception e) {
                Log.e(TAG, "temp sockets not created", e);
                mModule.onError(e);
            }

            mmInStream = tmpIn;
            mmOutStream = tmpOut;
        }

        public void run() {
            Log.i(TAG, "BEGIN mConnectedThread");
            byte[] buffer = new byte[1024];
            int bytes;

            while (true) {
                try {
                    bytes = mmInStream.read(buffer); 
                    String data = new String(buffer, 0, bytes, "ISO-8859-1");

                    mModule.onData(data); 
                } catch (Exception e) {
                    Log.e(TAG, "disconnected", e);
                    mModule.onError(e);
                    connectionLost();
                    RCTBluetoothSerialService.this.stop(); 
                    break;
                }
            }
        }

        void write(byte[] buffer) {
            try {
                String str = new String(buffer, "UTF-8");
                if (D) Log.d(TAG, "Write in thread " + str);
                mmOutStream.write(buffer);
            } catch (Exception e) {
                Log.e(TAG, "Exception during write", e);
                mModule.onError(e);
            }
        }

        void cancel() {
            try {
                mmSocket.close();
            } catch (Exception e) {
                Log.e(TAG, "close() of connect socket failed", e);
            }
        }
    }
}

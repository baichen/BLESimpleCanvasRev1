package com.example.baichen.blesimplecanvas;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattDescriptor;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.provider.Settings;
import android.support.design.widget.FloatingActionButton;
import android.support.design.widget.Snackbar;
import android.support.v7.app.AppCompatActivity;
import android.support.v7.widget.Toolbar;
import android.text.method.ScrollingMovementMethod;
import android.view.View;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;

import com.jjoe64.graphview.GraphView;
import com.jjoe64.graphview.series.DataPoint;
import com.jjoe64.graphview.series.LineGraphSeries;
import com.jjoe64.graphview.series.PointsGraphSeries;

import java.util.List;
import java.util.Random;

public class MainActivity extends AppCompatActivity implements View.OnClickListener {
    private TextView display = null;

    // Bluetooth adapters and scan
    private BluetoothManager mBluetoothManager = null;
    private BluetoothAdapter mBluetoothAdapter = null;
    private boolean mScanning = false;
    private Handler mHandler = null;
    private static final long SCAN_PERIOD = 2000;
    // Bluetooth device information
    private BluetoothDevice mBluetoothDevice = null;
    private BluetoothGatt mBluetoothGatt = null;
    private BluetoothGattCharacteristic mBluetoothGattCharacteristicAA71 = null;
    private BluetoothGattCharacteristic mBluetoothGattCharacteristicAA72 = null;

    // Three descriptors are used in Quan's current firmware, with the following functions:
    // 1. Enable/disable remote listening (0x2902)
    // 2. Receive Notifications with data (0x2901)
    // 3. Start Recording command (looks like writing directly to 0xAA72)
    private List<BluetoothGattDescriptor> mBluetoothGattDescriptorsAA71 = null;
    private List<BluetoothGattDescriptor> mBluetoothGattDescriptorsAA72 = null;

    private int mConnectionState = STATE_DISCONNECTED;
    private static final int STATE_DISCONNECTED = 0;
    private static final int STATE_CONNECTING = 1;
    private static final int STATE_CONNECTED = 2;

    // Bluetooth Received Data Logging & Processing
    private long receivedPacketsCount = 0;
    private long receivedBytesCount = 0;
    private int recordPoints = 2000;

    // GraphView Plots
    private GraphView graph = null;
    private static final int MAX_DATA_POINTS = 1000;
    private static final int MAX_POINTS_ON_PLOT = 200;
    private LineGraphSeries<DataPoint> mSeries1;
    private PointsGraphSeries<DataPoint> mSeries2;
    private double graph2LastXValue = 5d;
    private long StartRecordingTimeMillis = 0;
    private long StartAppTimeMillis = 0;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        Toolbar toolbar = (Toolbar) findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        FloatingActionButton fab = (FloatingActionButton) findViewById(R.id.fab);
        fab.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Snackbar.make(view, "Replace with your own action", Snackbar.LENGTH_LONG)
                        .setAction("Action", null).show();
            }
        });

        // custom codes here
        // do not need to change anymore
        display = (TextView) findViewById(R.id.messageBox);
        display.setMovementMethod(new ScrollingMovementMethod());

        SeekBar seekBar = (SeekBar) findViewById( R.id.seekBar );
        final TextView tv = (TextView) findViewById( R.id.labelN );
        seekBar.setProgress(recordPoints);
        tv.setText("\t" + recordPoints);
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                recordPoints = progress;
                tv.setText("\t" + progress);
            }

            public void onStartTrackingTouch(SeekBar seekBar) {
                // TODO Auto-generated method stub
            }

            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });



        final Button btn_start = (Button) findViewById( R.id.btn_start );
        btn_start.setOnClickListener( this );

        // Initialize Bluetooth Adapter
        mBluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        mBluetoothAdapter = mBluetoothManager.getAdapter();
        mHandler = new Handler();
        // Ensures Bluetooth is available on the device and it is enabled. If not,
        // displays a message requesting user permission to enable Bluetooth.
        if (mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            println("Please enable Bluetooth Adapter in settings");
        } else {
            println("Bluetooth Adapter OK!");
        }

        // Initialize GraphView
        graph = (GraphView) findViewById(R.id.graph);
        mSeries1 = new LineGraphSeries<DataPoint>(generateData( MAX_DATA_POINTS ));
        mSeries2 = new PointsGraphSeries<DataPoint>(generateData( MAX_DATA_POINTS ));
        //mSeries1 = new LineGraphSeries<DataPoint>();
        graph.addSeries(mSeries1);
        graph.addSeries(mSeries2);
        mSeries2.setShape(PointsGraphSeries.Shape.POINT);
        mSeries2.setColor(Color.BLUE);
        // ViewPort settings: http://www.android-graphview.org/documentation/learn-about-the-basics
        graph.getViewport().setScalable(true);
        graph.getViewport().setScrollable(true);
        // set manual X bounds
        // use a 4 seconds window for data plot
        graph.getViewport().setXAxisBoundsManual(true);
        graph.getViewport().setMinX(-4000);
        graph.getViewport().setMaxX(0);
        // set manual Y bounds -- assuming 12-bit ADC
        graph.getViewport().setYAxisBoundsManual(true);
        graph.getViewport().setMinY(0);
        graph.getViewport().setMaxY(4096);

        // Get System Time as Reference
        StartAppTimeMillis = System.currentTimeMillis();
    }

    @Override
    public void onClick(View v) {
        switch (v.getId()) {
            case R.id.btn_start:
                println("Start Recording!");
                ScanConnectNotifyRecord();
                break;
        }
    }

    /***********************************************************************************
     *  @BaichenLi
     *
     *  StartRecording Function
     *
     ***********************************************************************************/
    public void ScanConnectNotifyRecord() {
        // 1. Initialize BLE
        // 1.1 assure BLE is enabled
        if(mBluetoothAdapter == null || !mBluetoothAdapter.isEnabled()) {
            println("Please enable Bluetooth Adapter in settings");
            return;
        }
        // 1.2 search for specific device
        if(( mBluetoothDevice == null )||( mConnectionState == STATE_DISCONNECTED )) {
            ScanBLEDevices();
            return;
        }
        // Scan -> connectGatt -> mGattCallback -> onConnectionStateChange() ->
        // -> discoverServices() -> AA71 & AA72 enable notification -> Start Recording

        // Reaches here if BLE interface has been initialized.
        StartRecording( recordPoints );
    }
    /**********************************************************************************
     *  @BaichenLi
     *
     *  When the "SCAN" button is clicked, the program starts to Scan for BLE devices,
     *  with the following ScanBLEDevices() function.
     *  Scanning Period is set by the SCAN_PERIOD final value, in milliseconds (ms).
     *  Currently, SCAN_PERIOD is set to 2sec (2000 ms). After 2 seconds, a Runnable
     *  will be run to stop the scanning process.
     *
     *  The BluetoothAdapter.LeScanCallback callback function is and must be defined.
     *  Give any found BLE devices, this callback will be invoked for further processing.
     *
     *  Rules to keep in mind:
     *   1. As soon as you find the desired device, stop scanning.
     *   2. Never scan on a loop, and set a time limit on your scan.
     *      A device that was previously available may have moved out of range,
     *      and continuing to scan drains the battery.
     *  Reference: https://developer.android.com/guide/topics/connectivity/bluetooth-le.html
     **********************************************************************************/
    private void ScanBLEDevices() {
        if( mScanning ) {
            println("Scanning for BLE devices ...");
            return;
        }

        if( mBluetoothGatt!= null ) {
            mBluetoothGatt.close();
        }

        mHandler.postDelayed(new Runnable() {
            @Override
            public void run() {
                if( mScanning ) {
                    mScanning = false;
                    mBluetoothAdapter.stopLeScan(mLeScanCallback);
                    println("Stop Scanning! No device found.");
                }
            }
        }, SCAN_PERIOD);

        mScanning = true;
        mBluetoothAdapter.startLeScan(mLeScanCallback);
        println("Start Scanning ...");
    }

    // Device scan callback.
    private BluetoothAdapter.LeScanCallback mLeScanCallback = new BluetoothAdapter.LeScanCallback() {
        @Override
        public void onLeScan(final BluetoothDevice device, final int rssi, final byte[] scanRecord) {
            // your implementation here
            println("mLeScanCallback invoked: " + device.getName());
            if( device.getName().toLowerCase().contains("XAmpleBLEPeripheral".toLowerCase()) ) {
                mScanning = false;
                mBluetoothAdapter.stopLeScan(mLeScanCallback);
                mBluetoothDevice = device;
                println("Address:" + device.getAddress() );
                // Connect to device
                println("Connect to device");
                mBluetoothGatt = mBluetoothDevice.connectGatt(getApplicationContext(), false, mGattCallback);
            }
        }
    };

    /**********************************************************************************
     *  @BaichenLi
     *
     *  Callback function to mBluetoothGatt = mBluetoothDevice.connectGatt(this, false, mGattCallback);
     *
     *  *******************************************************************************/
    // There are several useful methods in this class, but the following code will only highlight a few necessary ones.
    // Reference: http://toastdroid.com/2014/09/22/android-bluetooth-low-energy-tutorial/
    private final BluetoothGattCallback mGattCallback = new BluetoothGattCallback() {
        @Override
        public void onCharacteristicChanged(BluetoothGatt gatt, final BluetoothGattCharacteristic characteristic) {
            // this will get called anytime you perform a read or write characteristic operation
            byte[] data = characteristic.getValue();
            receivedPacketsCount++;
            //clearMessageBox();
            print("#P=" + receivedPacketsCount);
            runOnUiThread(new GraphViewUpdater(data) );
        }

        @Override
        public void onConnectionStateChange(final BluetoothGatt gatt, final int status, final int newState) {
            // this will get called when a device connects or disconnects
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                mConnectionState = STATE_CONNECTED;
                print("BLE Device Connected!\nAttempting to start service discovery...");
                mBluetoothGatt.discoverServices();
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                mConnectionState = STATE_DISCONNECTED;
                print("BLE Device Disconnected!");
            }
        }

        @Override
        public void onServicesDiscovered(final BluetoothGatt gatt, final int status) {
            // this will get called after the client initiates a BluetoothGatt.discoverServices() call
            if (status == BluetoothGatt.GATT_SUCCESS) {
                print( "\nonServicesDiscovered GATT_SUCCESS" );
                // Identify Useful Characteristics
                mBluetoothGattCharacteristicAA71 = FindBluetoothGattCharacteristicByUUID("AA71");
                mBluetoothGattCharacteristicAA72 = FindBluetoothGattCharacteristicByUUID("AA72");
                mBluetoothGattDescriptorsAA71 = mBluetoothGattCharacteristicAA71.getDescriptors();
                mBluetoothGattDescriptorsAA72 = mBluetoothGattCharacteristicAA72.getDescriptors();
                for( BluetoothGattDescriptor descriptor : mBluetoothGattDescriptorsAA71 ) {
                    descriptor.setValue( BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE );
                    mBluetoothGatt.writeDescriptor( descriptor );
                }
                for( BluetoothGattDescriptor descriptor : mBluetoothGattDescriptorsAA72 ) {
                    descriptor.setValue( BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE );
                    mBluetoothGatt.writeDescriptor( descriptor );
                }
                // Enable Characteristic Notification
                //mBluetoothGatt.setCharacteristicNotification(mBluetoothGattCharacteristicAA71, true);
                //mBluetoothGatt.setCharacteristicNotification(mBluetoothGattCharacteristicAA72, true);
            } else {
                print( "onServicesDiscovered received: " + status );
            }
        }

        @Override
        public void onCharacteristicWrite(BluetoothGatt gatt, BluetoothGattCharacteristic characteristic, int status) {
        }

        @Override
        public void onDescriptorWrite(BluetoothGatt gatt, BluetoothGattDescriptor descriptor, int status){
            if( status == BluetoothGatt.GATT_SUCCESS ) {
                if( descriptor.getCharacteristic() == mBluetoothGattCharacteristicAA71 ) {
                    mBluetoothGatt.setCharacteristicNotification(mBluetoothGattCharacteristicAA71, true);
                    println("AA71 Enabled");
                    //StartRecording(recordPoints);
                }
                // never gets here
                if( descriptor.getCharacteristic() == mBluetoothGattCharacteristicAA72 ) {
                    mBluetoothGatt.setCharacteristicNotification(mBluetoothGattCharacteristicAA72, true);
                    // println("AA72 Enabled");
                    // assume here's the end of enabling characteristic notifications.
                }
            } else {
                println("Descriptor write status = "+status);
            }
        }

    };
    // assisting function for mGattCallback, probably don't need to modify.
    public BluetoothGattCharacteristic FindBluetoothGattCharacteristicByUUID(String UUID) {
        if( mBluetoothGatt == null ) {
            return null;
        }

        List<BluetoothGattService> services = mBluetoothGatt.getServices();
        for (BluetoothGattService service : services) {
            List<BluetoothGattCharacteristic> characteristics = service.getCharacteristics();
            // Thus far, we have the basic information: device, supported services,
            // and a list of the characteristics for each service.Each has a UUID.
            // There will be something called Client Configuration Descriptor,
            // and by setting some values, we can enable notifications on the remote device.
            for(BluetoothGattCharacteristic characteristic: characteristics) {
                if( characteristic.getUuid().toString().toLowerCase().contains( UUID.toLowerCase() ) ) {
                    println("Found UUID: " + characteristic.getUuid());
                    return characteristic;
                }
                /* Do something to the descriptors as well */
            }
        }
        return null;
    }

    /**********************************************************************************
     *  @BaichenLi
     *
     *  Callback function to mBluetoothGatt = mBluetoothDevice.connectGatt(this, false, mGattCallback);
     *
     *  *******************************************************************************/
    public void StartRecording( int number_of_points ) {
        if( mBluetoothGattCharacteristicAA72 == null) {
            println("Object AA72 Not Found.");
            return;
        }

        byte[] command = new byte[4];
        command[0] = (byte)(number_of_points & 0xff);
        command[1] = (byte)((number_of_points>>8) & 0xff);
        command[2] = 0x02;
        command[3] = 0x30;
        mBluetoothGattCharacteristicAA72.setValue(command);
        if( mBluetoothGatt.writeCharacteristic( mBluetoothGattCharacteristicAA72 ) ) {
            String message = new String("Writing 0x");
            for(int index = 0; index < command.length; index++) {
                message += String.format( "%02X", command[index] );
            }
            message += " to Device";
            println( message );
        } else {
            println("Writing AA72 Init Error." );
        }
        // Log System time.
        StartRecordingTimeMillis = System.currentTimeMillis();
        //mSeries1.resetData(generateData(MAX_DATA_POINTS));
    }

    /**********************************************************************************
     *  @AndroidStudio Auto-generated Fucntions
     *
     *  *******************************************************************************/
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        // Handle action bar item clicks here. The action bar will
        // automatically handle clicks on the Home/Up button, so long
        // as you specify a parent activity in AndroidManifest.xml.
        int id = item.getItemId();

        //noinspection SimplifiableIfStatement
        if (id == R.id.action_settings) {
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    /***********************************************************************************
     *  @BaichenLi
     *
     *  There is a TextView at the top of the screen, I am using it as a widget to
     *  display some text/info. to the users. Inside the main_activity, the code is
     *  very simple: display.setText( text ).
     *  However, it becomes a little bit tricky when using the BluetoothAdapter.
     *  Somehow, the TextView cannot be accessed through mHandler or mLeScanCallback.
     *  To tackle this problem, the following class is defined, to be used in the
     *  consequent print(String) function, which will be run on the UI thread to
     *  update the TextView widget.
     *
     ***********************************************************************************/
    private class TextPrinter implements Runnable {
        private String text = null;
        public TextPrinter(String txt) {
            text = txt;
        }
        @Override
        public void run() {
            if( display.getText().length() >= 150 ) {
                display.setText( text );
            } else {
                display.append(text);
            }
        }
    }
    public void print(String text) {
        runOnUiThread(new TextPrinter(text));
    }
    public void println(String text) {
        runOnUiThread(new TextPrinter(text+"\n"));
    }
    public void clearMessageBox() {
        display.setText("");
    }

    /**********************************************************************************
     * @BaichenLi
     *
     * Generate Random Points for GraphView Initialization & Tests
     *
     * Reference: http://www.android-graphview.org/documentation/realtime-updates
     *********************************************************************************/
    private DataPoint[] generateData(int count) {
        Random mRand = new Random();
        DataPoint[] values = new DataPoint[count];
        for (int i=0; i<count; i++) {
            double x = (i-count)/1000;
            double f = mRand.nextDouble()*0.15+0.3;
            //double y = Math.sin(i*f+2) + mRand.nextDouble()*0.3;
            double y = 0;
            DataPoint v = new DataPoint(x, y);
            values[i] = v;
        }
        return values;
    }
    /***********************************************************************************
     *  @BaichenLi
     *
     * UpdatePlot:
     *  this function will be called whenever it receives a data packet from
     *  the BLE device. We can modify this code to adapt to specific applications.
     *
     *  As mentioned before print(String) and println(String), this module has to
     *  be run in the UI thread. So I created a class here. This is invoked in the
     *  mGattCallback.onCharacteristicChanged(), where received data is presented.
     *
     ***********************************************************************************/
    private double currentXValue = 0;
    private class GraphViewUpdater implements Runnable {
        private byte[] _data_;

        private double recording_time_sec = 0;
        private double recording_time_millis = 0;
        private int packet_id = 0;
        private int packet_data[] = new int[8];
        private double data_average = 0.0;

        public GraphViewUpdater(final byte[] data) {
            _data_ = data;
        }
        @Override
        public void run() {
            // In my tests, data from CC2540 are always 20-Bytes
            if( _data_.length != 20 ) {
                println("Exception: Data Length != 20");
            }
            // parse data from the data packet
            // packet_id = (_data_[0] & 0x000000FF)+((_data_[1] & 0x000000FF)<<8)+((_data_[2] & 0x000000FF)<<16)+((_data_[3] & 0x000000FF)<<24);
            // It appears to me that the following code is the right form.
            // Not sure what is in the first two bytes.
            packet_id = ((_data_[2] & 0x000000FF)<<0)+((_data_[3] & 0x000000FF)<<8);
            data_average = 0.0;
            for(int i=0;i<8;i++) {
                packet_data[ i ] = ((_data_[4+i*2]&0x000000FF) + ((_data_[5+i*2]&0x000000FF)<<8));
                data_average += packet_data[i];
                // Calculate the elapsed time in seconds, relative to the time when START command
                // is sent to the BLE device. Then, append data to mSeries1
                // recording_time_sec = (System.currentTimeMillis()-StartRecordingTimeMillis)/1000;
                // currentXValue ++ ;
                // mSeries1.appendData( new DataPoint(recording_time_sec,(double)packet_data[i]), true, MAX_DATA_POINTS);
                // mSeries1.appendData( new DataPoint(currentXValue,(double)packet_data[i]), true, MAX_DATA_POINTS);
            }
            data_average = data_average / 8;
            print("Pid="+packet_id);
            // Maybe we want to update GraphView less frequently, to avoid program lagging
            // Here, I use the average value & relative system time as 1 sample point
            recording_time_millis = (System.currentTimeMillis()-StartAppTimeMillis);
            recording_time_sec = recording_time_millis/1000;
            // Append the data points to mSeries1 & 2. This will update the graph.
            mSeries1.appendData( new DataPoint(recording_time_millis, data_average), true, MAX_DATA_POINTS);
            mSeries2.appendData( new DataPoint(recording_time_millis, data_average), true, MAX_DATA_POINTS);
            // print some information
            println(" t(s)=" + recording_time_sec);
        }
    }

}

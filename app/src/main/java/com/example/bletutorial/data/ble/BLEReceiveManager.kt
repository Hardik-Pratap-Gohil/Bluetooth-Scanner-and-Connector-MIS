package com.example.bletutorial.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.util.Log
import com.example.bletutorial.data.ConnectionState
import com.example.bletutorial.data.ReceiveManager
import com.example.bletutorial.data.Result
import com.example.bletutorial.util.Resource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject
@SuppressLint("MissingPermission")
class BLEReceiveManager @Inject constructor(
    private val bluetoothAdapter: BluetoothAdapter,
    private val context: Context
) : ReceiveManager{

    private val DEVICE_ADDRESS = "B0:A7:32:2A:AE:9A"
    private val SERVICE_UUID = "eee7aa7e-ef6f-4c28-ac62-2187e74e0b6b"
    private val CHARACTERISTIC_UUID ="eee7aa7e-ef6f-4c28-ac62-2187e74e0b6b"

    override val data: MutableSharedFlow<Resource<Result>> = MutableSharedFlow()

    private val bleScanner by lazy {
        bluetoothAdapter.bluetoothLeScanner
    }

    private val scanSettings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()

    private var gatt: BluetoothGatt? = null

    private var isScanning = false

    private val coroutineScope = CoroutineScope(Dispatchers.Default)

    private val scanCallback = object :ScanCallback(){
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (result.device.address == DEVICE_ADDRESS){
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Connecting to device ..."))
                }
                if (isScanning){
                    result.device.connectGatt(context,false,gattCallback, BluetoothDevice.TRANSPORT_LE) // Try removing the last parameter once
                    isScanning = false
                    bleScanner.stopScan(this)
                    Log.d("ScamReseukt", "Stopped scanning")
                }
            }
        }
    }

    private var currentConnectionAttempt = 1
    private var maximumConnectionAttempt = 5

    private val gattCallback = object : BluetoothGattCallback(){
        override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS){
                if (newState == BluetoothProfile.STATE_CONNECTED){
                    // At this stage the device is connected to the bluetooth
                    coroutineScope.launch {
                        data.emit(Resource.Loading(message = "Discovering services..."))
                    }
                    gatt?.discoverServices()
                    this@BLEReceiveManager.gatt = gatt
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED){
                    coroutineScope.launch {
                        data.emit(Resource.Success(data = Result(ConnectionState.Disconnected)))
                    }
                    gatt?.close()
                }
            } else{
                gatt?.close()
                currentConnectionAttempt += 1
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Attempting to connect $currentConnectionAttempt/$maximumConnectionAttempt"))
                }
                if (currentConnectionAttempt<=maximumConnectionAttempt){
                    startReceiving()
                } else{
                    coroutineScope.launch {
                        data.emit(Resource.Error(errorMessage = "Could not connect to BLE device"))
                    }
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            with(gatt){
                printGattTable()
                coroutineScope.launch {
                    data.emit(Resource.Loading(message = "Adjusting MTU space..."))
                }
                //MTU is the maximum bytes of memory that the sensor can send to the device
                // you can request extra memory
                gatt.requestMtu(517)
            }
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            val characteristic = findCharacteristic(SERVICE_UUID,CHARACTERISTIC_UUID)
            if (characteristic != null){
                Log.d("characteristic", characteristic.toString())
            }
            if (characteristic == null){
                coroutineScope.launch {
                    data.emit(Resource.Error(errorMessage = "Could not find characteristics"))
                }
                return
            }
            enableNotification(characteristic)
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            Log.d("Characteristic changed", "entered onCharacteristicChsnged")
            with(characteristic){
                when(uuid){
                    UUID.fromString(CHARACTERISTIC_UUID) -> {
                        // If you want to retrieve any characteristics
                        val result = Result(
                            ConnectionState.Connected
                        )
                        coroutineScope.launch {
                            data.emit(Resource.Success(data = result))
                        }
                    } else -> Unit
                }
            }
//            sendCharacteristic(gatt, characteristic, "Data to send")
        }
    }

    private fun sendCharacteristic(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, string: String){
        val data = string.toByteArray()
        characteristic?.value = data
        gatt.writeCharacteristic(characteristic)
    }

    private fun enableNotification(characteristic : BluetoothGattCharacteristic){
        Log.d("message", "entered enableNotification")
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        val payload = when {
            characteristic.isIndicatable() -> BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            characteristic.isNotifiable() -> BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            else -> return
        }
        Log.d("payload", "Cleared payload")
        characteristic.getDescriptor(cccdUuid)?.let { cccdDescriptor ->
            if (gatt?.setCharacteristicNotification(characteristic,true) == false){
                Log.d("BLEReceiveManager", "set characteristic notification failed")
                return
            }
            Log.d("random", "Entered arrow function")
            writeDescription(cccdDescriptor,payload)
            Log.d("write", "Finished writing description")

        }
    }

    private fun writeDescription(descriptor: BluetoothGattDescriptor, payload: ByteArray){
        gatt?.let { gatt ->
            descriptor.value = payload
            gatt.writeDescriptor(descriptor)
            Log.d("Writing","Writing desceiption")
        }?: error("Not connected to a BLE device!")
    }
    // Some sensors have different charaacteristic and service uuid
    private fun findCharacteristic(serviceUUID: String, characteristicsUUID: String):BluetoothGattCharacteristic?{
        Log.d("random", "Found characteristics")

        return gatt?.services?.find { service ->
            service.uuid.toString() == serviceUUID
        }?.characteristics?.find { characteristics ->
            characteristics.uuid.toString() == characteristicsUUID
        }

    }

    override fun startReceiving(){

        coroutineScope.launch { data.emit(Resource.Loading(message = "Scanning BLE Devices ...")) }
        isScanning = true
        bleScanner.startScan(null,scanSettings,scanCallback)
    }

    override fun reconnect() {
        Log.d("Reconnect","Reconnecting...")
        gatt?.connect()
    }

    override fun disconnect() {
        gatt?.disconnect()
    }


    override fun closeConnection() {
        bleScanner.stopScan(scanCallback)
        val characteristic = findCharacteristic(SERVICE_UUID, CHARACTERISTIC_UUID)
        if (characteristic != null){
            disconnectCharacteristic(characteristic)
        }
        gatt?.close()
    }

    private fun disconnectCharacteristic(characteristic: BluetoothGattCharacteristic){
        val cccdUuid = UUID.fromString(CCCD_DESCRIPTOR_UUID)
        characteristic.getDescriptor(cccdUuid)?.let {cccdDescriptor ->
            if (gatt?.setCharacteristicNotification(characteristic,false) == false){
                Log.d("BLEReceiveManager", "Set characteristic notification failed")
            }
            writeDescription(cccdDescriptor,BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE)
        }
    }


}
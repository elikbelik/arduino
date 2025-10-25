package com.example.morganaschedualer

// Android Core & Lifecycle
import android.Manifest
import android.annotation.SuppressLint
import android.app.TimePickerDialog
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.ParcelUuid
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.* // Import all filled icons
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
// Permissions
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState
// Coroutines
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
// JSON Parsing
import org.json.JSONArray
import org.json.JSONObject
// UUID
import java.util.*

// --- Log Tag ---
private const val TAG = "MorganaApp"

// --- ESP32 BLE Service and Characteristic UUIDs ---
// !! IMPORTANT: Make sure these EXACTLY match the UUIDs in your ESP32_Scheduler_V4.ino code !!
private val ESP32_SERVICE_UUID: UUID = UUID.fromString("4fafc201-1fb5-459e-8fcc-c5c9c331914b")
private val CHARACTERISTIC_UUID: UUID = UUID.fromString("beb5483e-36e1-4688-b7f5-ea07361b26a8")

// --- Data Model for a Schedule Task ---
data class ScheduleTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String,
    val port: Int,
    val startTime: String,
    val endTime: String,
    val isActive: Boolean,
    val alwaysActive: Boolean
)

// Simple holder for scanned device info
data class BleDevice(
    val name: String?,
    val address: String,
    val device: BluetoothDevice
)

@SuppressLint("MissingPermission") // Permissions are checked via Accompanist
class MainActivity : ComponentActivity() {

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothManager.adapter
    }

    private val bleScanner: BluetoothLeScanner? by lazy {
        bluetoothAdapter?.bluetoothLeScanner
    }

    // Activity result launcher for enabling Bluetooth
    private val enableBluetoothLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            Log.d(TAG, "Bluetooth enabled by user.")
            // You could potentially trigger a scan here if needed
        } else {
            Log.w(TAG, "Bluetooth not enabled by user.")
            // Handle the case where the user refuses to enable Bluetooth
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    // Check if Bluetooth is supported and enabled before showing the main UI
                    BluetoothWrapper(bluetoothAdapter, enableBluetoothLauncher) {
                        RequestBluetoothPermissions { // Handles BLE permissions
                            // Pass BLE control functions down to the app
                            SchedulerAppWithBleControl()
                        }
                    }
                }
            }
        }
    }

    // Make sure to clean up GATT connection on destroy
    override fun onDestroy() {
        super.onDestroy()
        // Accessing the Gatt instance needs careful state management,
        // potentially through a ViewModel or singleton. For simplicity here,
        // we assume it might be accessible via a static or similar pattern,
        // which isn't ideal but demonstrates the cleanup need.
        // A better approach involves a dedicated BLE manager class.
        // bluetoothGatt?.close() // Needs proper access to the Gatt instance
        Log.d(TAG,"Activity Destroyed - Ensure BLE resources are released")

    }
}

// Wrapper Composable to check Bluetooth state
@Composable
fun BluetoothWrapper(
    bluetoothAdapter: BluetoothAdapter?,
    enableBtLauncher: androidx.activity.result.ActivityResultLauncher<android.content.Intent>,
    content: @Composable () -> Unit
) {
    var isBluetoothEnabled by remember { mutableStateOf(bluetoothAdapter?.isEnabled == true) }

    // Effect to observe Bluetooth state changes (simplified)
    // A BroadcastReceiver is the robust way, but this handles initial check + enablement flow
    LaunchedEffect(bluetoothAdapter?.isEnabled) {
        isBluetoothEnabled = bluetoothAdapter?.isEnabled == true
    }

    when {
        bluetoothAdapter == null -> {
            // Device doesn't support Bluetooth
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("This device does not support Bluetooth", color = MaterialTheme.colorScheme.error)
            }
        }
        !isBluetoothEnabled -> {
            // Bluetooth is not enabled
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("Bluetooth is required and is currently disabled.")
                Spacer(modifier = Modifier.height(16.dp))
                Button(onClick = {
                    val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                    enableBtLauncher.launch(enableBtIntent)
                }) {
                    Text("Enable Bluetooth")
                }
            }
        }
        else -> {
            // Bluetooth adapter exists and is enabled, show the permission requester/app content
            content()
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerAppWithBleControl() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // --- BLE State ---
    val bluetoothAdapter = remember { (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter }
    val bleScanner = remember { bluetoothAdapter.bluetoothLeScanner }
    var isScanning by remember { mutableStateOf(false) }
    val scannedDevices = remember { mutableStateListOf<BleDevice>() }
    var connectionState by remember { mutableStateOf("Disconnected") } // Disconnected, Scanning, Connecting, Connected, Error
    var bluetoothGatt by remember { mutableStateOf<BluetoothGatt?>(null) }
    var characteristic by remember { mutableStateOf<BluetoothGattCharacteristic?>(null) }


    // --- App State ---
    val schedules = remember { mutableStateListOf<ScheduleTask>() }
    var showDialog by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<ScheduleTask?>(null) }
    var showDeviceListDialog by remember { mutableStateOf(false) }

    // --- Helper Functions ---
    // Function to enable notifications on the Characteristic
    @Suppress("MissingPermission")
    fun enableNotifications(gatt: BluetoothGatt?) {
        val char = characteristic ?: run {
            Log.e(TAG,"Cannot enable notifications, characteristic is null.")
            gatt?.disconnect()
            return
        }
        val descriptorUuid = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb") // Standard CCCD UUID
        val descriptor = char.getDescriptor(descriptorUuid) ?: run {
            Log.e(TAG, "Client Characteristic Configuration Descriptor (CCCD) not found!")
            gatt?.disconnect()
            return
        }

        // Check characteristic properties
        if (char.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0) {
            Log.e(TAG, "Characteristic does not support notifications.")
            gatt?.disconnect()
            return
        }

        // Enable notification locally
        val notificationSet = gatt?.setCharacteristicNotification(char, true)
        if (notificationSet != true) {
            Log.e(TAG, "Failed to set characteristic notification locally.")
            gatt?.disconnect()
            return
        }

        // Write to the CCCD descriptor to enable notifications on the peripheral
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeResult = gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            Log.d(TAG, "Writing ENABLE_NOTIFICATION_VALUE to CCCD (API 33+), result code: $writeResult")
            if (writeResult != BluetoothStatusCodes.SUCCESS){
                Log.e(TAG,"Failed to write CCCD descriptor, error: $writeResult")
                gatt.disconnect()
            }
        } else {
            // Fallback for older APIs
            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            val writeSuccess = gatt.writeDescriptor(descriptor)
            Log.d(TAG, "Writing ENABLE_NOTIFICATION_VALUE to CCCD (Legacy), success: $writeSuccess")
            if (!writeSuccess){
                Log.e(TAG,"Failed to write CCCD descriptor (Legacy)")
                gatt.disconnect()
            }
        }
        // Result handled in onDescriptorWrite callback
    }

    // Internal function to handle writing commands to the characteristic
    @Suppress("MissingPermission")
    fun sendCommandInternal(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, command: String) {
        Log.i(TAG,"📤 SENDING COMMAND: $command")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val writeResult = gatt.writeCharacteristic(
                characteristic,
                command.toByteArray(Charsets.UTF_8),
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT // Or WRITE_TYPE_NO_RESPONSE if ESP expects it
            )
            Log.d(TAG,"Writing command (API 33+): '$command', result code: $writeResult")
            if (writeResult != BluetoothStatusCodes.SUCCESS){
                Log.e(TAG,"❌ Failed to write characteristic (API 33+), error: $writeResult")
                // Optionally trigger disconnect or retry logic
            } else {
                Log.i(TAG,"✅ Command queued successfully (API 33+)")
            }
        } else {
            characteristic.value = command.toByteArray(Charsets.UTF_8)
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            val writeSuccess = gatt.writeCharacteristic(characteristic)
            Log.d(TAG,"Writing command (Legacy): '$command', success: $writeSuccess")
            if (!writeSuccess){
                Log.e(TAG,"❌ Failed to write characteristic (Legacy)")
                // Optionally trigger disconnect or retry logic
            } else {
                Log.i(TAG,"✅ Command queued successfully (Legacy)")
            }
        }
        // Result handled in onCharacteristicWrite callback
    }

    // Sync time and request initial schedule list after connection and notification setup
    fun syncTimeAndRequestSchedules(gatt: BluetoothGatt?) {
        val g = gatt ?: return // Safety check
        val char = characteristic ?: return

        Log.i(TAG, "🔄 Starting initial sync sequence...")
        scope.launch {
            // 1. Send time sync
            val timeSyncCmd = """{"cmd":"time_sync","time":${System.currentTimeMillis() / 1000}}"""
            Log.i(TAG, "🕐 Step 1/2: Sending Time Sync command...")
            sendCommandInternal(g, char, timeSyncCmd)

            // Wait for the write to complete before sending next command
            Log.d(TAG, "⏳ Waiting 500ms before sending next command...")
            delay(500) // Increased delay to ensure first command completes
            
            // 2. Request schedules
            val getSchedulesCmd = """{"cmd":"get_schedules"}"""
            Log.i(TAG, "📋 Step 2/2: Sending Get Schedules command...")
            sendCommandInternal(g, char, getSchedulesCmd)
            Log.i(TAG, "⏳ Waiting for schedule data from ESP32...")
            // Schedule list will arrive via notification (onCharacteristicChanged)
        }
    }

    // --- Parsing Logic ---
    fun parseAndLoadSchedules(jsonString: String, scheduleList: MutableList<ScheduleTask>) {
        try {
            Log.d(TAG, "🔄 Parsing schedules from JSON...")
            Log.d(TAG, "   JSON length: ${jsonString.length} characters")
            
            // Check if JSON is complete (should start with '[' and end with ']')
            if (!jsonString.startsWith("[") || !jsonString.endsWith("]")) {
                Log.e(TAG, "❌ JSON appears truncated! Start: ${jsonString.startsWith("[")} End: ${jsonString.endsWith("]")}")
                Log.e(TAG, "   This usually means MTU is too small. Check MTU negotiation logs.")
                return
            }
            
            // ESP32 sends the schedules as a direct JSON array
            val jsonArray = JSONArray(jsonString)
            val tempList = mutableListOf<ScheduleTask>()
            for (i in 0 until jsonArray.length()) {
                val taskObject = jsonArray.getJSONObject(i)
                val task = ScheduleTask(
                    id = taskObject.getString("id"),
                    title = taskObject.getString("title"),
                    port = taskObject.getInt("port"),
                    startTime = taskObject.getString("start"),
                    endTime = taskObject.getString("end"),
                    isActive = taskObject.getBoolean("active"),
                    alwaysActive = taskObject.getBoolean("alwaysOn")
                )
                tempList.add(task)
                Log.d(TAG, "   ✅ Parsed task #$i: ${task.title} (Port ${task.port})")
            }
            Log.i(TAG, "✅ Successfully parsed ${tempList.size} schedules from ESP32")
            // Update the Compose state list on the main thread
            scope.launch {
                scheduleList.clear()
                scheduleList.addAll(tempList)
                Log.i(TAG, "✅ UI updated with ${scheduleList.size} tasks")
            }
        } catch (e: org.json.JSONException) {
            Log.e(TAG, "❌ JSON parsing error - data may be truncated due to MTU limits", e)
            Log.e(TAG, "   Received JSON (first 100 chars): ${jsonString.take(100)}")
            Log.e(TAG, "   Full length: ${jsonString.length} chars")
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error parsing schedule JSON", e)
        }
    }

    // --- GATT Callback ---
    val gattCallback = remember {
        object : BluetoothGattCallback() {
            @Suppress("MissingPermission")
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                scope.launch { // Use CoroutineScope to update state on the main thread
                    Log.d(TAG, "onConnectionStateChange: Status $status, NewState $newState")
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        when (newState) {
                            BluetoothProfile.STATE_CONNECTED -> {
                                connectionState = "Negotiating MTU..."
                                bluetoothGatt = gatt
                                // Request larger MTU to handle larger JSON payloads
                                Log.i(TAG, "🔧 Requesting MTU of 517 bytes...")
                                val mtuRequested = gatt?.requestMtu(517) // 512 bytes payload + 5 bytes overhead
                                if (mtuRequested == true) {
                                    Log.d(TAG, "MTU request initiated successfully")
                                } else {
                                    Log.e(TAG, "Failed to initiate MTU request")
                                    // Fall back to service discovery if MTU request fails
                                    delay(600)
                                    gatt?.discoverServices()
                                }
                            }
                            BluetoothProfile.STATE_DISCONNECTED -> {
                                connectionState = "Disconnected"
                                gatt?.close() // Clean up resources
                                bluetoothGatt = null
                                characteristic = null
                                schedules.clear() // Clear data on disconnect
                                Log.d(TAG,"Disconnected from GATT server.")
                            }
                        }
                    } else {
                        Log.e(TAG, "GATT Connection Error: $status")
                        connectionState = "Error"
                        gatt?.close()
                        bluetoothGatt = null
                    }
                }
            }

            // Handle MTU change callback
            @Suppress("MissingPermission")
            override fun onMtuChanged(gatt: BluetoothGatt?, mtu: Int, status: Int) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.i(TAG, "✅ MTU changed successfully to $mtu bytes")
                        connectionState = "Discovering Services..."
                        // Now proceed with service discovery
                        delay(600)
                        gatt?.discoverServices()
                    } else {
                        Log.e(TAG, "❌ MTU change failed with status: $status")
                        connectionState = "Discovering Services..."
                        // Still try service discovery with default MTU
                        delay(600)
                        gatt?.discoverServices()
                    }
                }
            }

            @Suppress("MissingPermission")
            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        Log.d(TAG, "Services Discovered Successfully.")
                        val service = gatt?.getService(ESP32_SERVICE_UUID)
                        if (service == null) {
                            Log.e(TAG, "ESP32 Service not found!")
                            connectionState = "Error: Service Not Found"
                            gatt?.disconnect()
                            return@launch
                        }

                        characteristic = service.getCharacteristic(CHARACTERISTIC_UUID)

                        if (characteristic == null) {
                            Log.e(TAG, "Required Characteristic not found!")
                            connectionState = "Error: Characteristic Missing"
                            gatt?.disconnect()
                            return@launch
                        }

                        Log.d(TAG,"Characteristic found.")
                        // Enable notifications for the characteristic
                        enableNotifications(gatt)


                    } else {
                        Log.w(TAG, "onServicesDiscovered received error: $status")
                        connectionState = "Error: Service Discovery Failed"
                        gatt?.disconnect()
                    }
                }
            }

            // Handle result of characteristic write
            override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: BluetoothGattCharacteristic?, status: Int) {
                if (status == BluetoothGatt.GATT_SUCCESS) {
                    Log.i(TAG, "✅ Characteristic write COMPLETED successfully: ${characteristic?.uuid}")
                } else {
                    Log.e(TAG, "❌ Characteristic write FAILED: ${characteristic?.uuid}, Status: $status")
                }
            }

            // Handle incoming data notifications
            override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
                scope.launch {
                    if (characteristic.uuid == CHARACTERISTIC_UUID) {
                        val receivedJson = String(value, Charsets.UTF_8)
                        Log.i(TAG, "📥 RECEIVED DATA from ESP32:")
                        Log.i(TAG, "   Length: ${receivedJson.length} bytes")
                        Log.i(TAG, "   Content: $receivedJson")
                        parseAndLoadSchedules(receivedJson, schedules)
                    }
                }
            }
            @Deprecated("Used characteristics read")
            override fun onCharacteristicRead(
                gatt: BluetoothGatt,
                characteristic: BluetoothGattCharacteristic,
                value: ByteArray,
                status: Int
            ) {
                // Handle characteristic read if needed (we primarily use notifications)
            }

            // Handle result of setting notification descriptor
            @Suppress("MissingPermission")
            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: BluetoothGattDescriptor?, status: Int) {
                scope.launch {
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        if (descriptor?.characteristic?.uuid == CHARACTERISTIC_UUID) {
                            Log.d(TAG, "Characteristic Notifications Enabled.")
                            // NOW it's safe to sync time and request schedules
                            connectionState = "Connected"
                            syncTimeAndRequestSchedules(gatt)
                        }
                    } else {
                        Log.e(TAG, "Descriptor write failed: ${descriptor?.uuid}, Status: $status")
                        connectionState = "Error: Notification Setup Failed"
                        gatt?.disconnect()
                    }
                }
            }
        }
    }

    // --- Scan Callback ---
    val scanCallback = remember {
        object : ScanCallback() {
            @Suppress("MissingPermission")
            override fun onScanResult(callbackType: Int, result: ScanResult?) {
                result?.device?.let { device ->
                    val bleDevice = BleDevice(
                        name = device.name ?: "Unknown", // Use device.name
                        address = device.address,
                        device = device
                    )
                    // Add device only if it's not already in the list
                    if (scannedDevices.none { it.address == bleDevice.address }) {
                        Log.d(TAG, "Device Found: ${bleDevice.name} (${bleDevice.address})")
                        scannedDevices.add(bleDevice)
                    }
                }
            }

            @Suppress("MissingPermission")
            override fun onBatchScanResults(results: MutableList<ScanResult>?) {
                results?.forEach { result ->
                    result.device?.let { device ->
                        val bleDevice = BleDevice(
                            name = device.name ?: "Unknown",
                            address = device.address,
                            device = device
                        )
                        if (scannedDevices.none { it.address == bleDevice.address }) {
                            Log.d(TAG, "Device Found (Batch): ${bleDevice.name} (${bleDevice.address})")
                            scannedDevices.add(bleDevice)
                        }
                    }
                }
            }


            override fun onScanFailed(errorCode: Int) {
                Log.e(TAG, "BLE Scan Failed: Error Code $errorCode")
                scope.launch {
                    isScanning = false
                    connectionState = "Error: Scan Failed"
                }
            }
        }
    }

    // --- BLE Control Functions ---
    @Suppress("MissingPermission")
    fun stopScan() {
        if (isScanning) {
            Log.d(TAG,"Stopping BLE Scan.")
            isScanning = false
            bleScanner?.stopScan(scanCallback)
            // Optionally hide dialog if scan stops without selection
            // if(showDeviceListDialog && connectionState != "Connecting...") showDeviceListDialog = false
        }
    }

    @Suppress("MissingPermission")
    fun startScan() {
        if (!isScanning) {
            Log.d(TAG,"Starting BLE Scan...")
            scannedDevices.clear() // Clear previous results
            isScanning = true
            connectionState = "Scanning..."
            showDeviceListDialog = true // Show the device list popup

            val scanFilter = ScanFilter.Builder()
                .setServiceUuid(ParcelUuid(ESP32_SERVICE_UUID))
                .build()
            val scanSettings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()

            bleScanner?.startScan(listOf(scanFilter), scanSettings, scanCallback)

            // Stop scan after a timeout
            scope.launch {
                delay(10000) // Scan for 10 seconds
                if (isScanning) {
                    stopScan()
                    // If still scanning and haven't connected, might indicate no device found
                    if(connectionState == "Scanning...") connectionState = "Disconnected"
                }
            }
        }
    }

    @Suppress("MissingPermission")
    fun connectToDevice(device: BluetoothDevice) {
        stopScan() // Stop scanning before connecting
        showDeviceListDialog = false // Hide the list
        connectionState = "Connecting..."
        Log.d(TAG, "Connecting to ${device.address}...")
        // Connect on the main thread for simplicity in this example
        // For production, use a background thread or dedicated service
        bluetoothGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    @Suppress("MissingPermission")
    fun disconnectDevice() {
        Log.d(TAG,"Disconnecting from device...")
        bluetoothGatt?.disconnect()
        // State change handled in gattCallback's onConnectionStateChange
    }

    // The public function used by the UI to send commands
    fun sendCommandToEsp(commandJson: String) {
        val g = bluetoothGatt
        val char = characteristic
        if (g == null || char == null) {
            Log.w(TAG, "Cannot send command: Not connected or characteristic not found.")
            connectionState = "Error" // Update state if trying to send while disconnected
            return
        }
        sendCommandInternal(g, char, commandJson)
    }


    // --- UI Composition ---
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ESP32 Scheduler") }, // Simplified Title
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                actions = {
                    // Show connection state and a disconnect button if connected
                    Text(connectionState, modifier = Modifier.padding(end = 8.dp).align(Alignment.CenterVertically))
                    if (connectionState == "Connected") {
                        IconButton(onClick = { disconnectDevice() }) {
                            Icon(Icons.Default.Close, contentDescription = "Disconnect")
                        }
                    }
                }
            )
        },
        floatingActionButton = {
            if (connectionState == "Connected") {
                FloatingActionButton(onClick = {
                    taskToEdit = null // Ensure we are adding a new task
                    showDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Task")
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            when (connectionState) {
                "Connected" -> {
                    ScheduleListView(
                        schedules = schedules,
                        onToggle = { task ->
                            val updatedTask = task.copy(isActive = !task.isActive)
                            Log.d(TAG, "UI Toggle: $updatedTask")
                            // Update locally immediately for responsiveness
                            val index = schedules.indexOf(task)
                            if (index != -1) schedules[index] = updatedTask
                            // Send update command to ESP32
                            val taskJson = """{"id":"${updatedTask.id}","title":"${updatedTask.title}","port":${updatedTask.port},"start":"${updatedTask.startTime}","end":"${updatedTask.endTime}","active":${updatedTask.isActive},"alwaysOn":${updatedTask.alwaysActive}}"""
                            sendCommandToEsp("""{"cmd":"update","task":$taskJson}""")
                        },
                        onEdit = { task ->
                            taskToEdit = task
                            showDialog = true
                        },
                        onDelete = { task ->
                            Log.d(TAG, "UI Delete: $task")
                            // Update locally immediately
                            schedules.remove(task)
                            // Send delete command to ESP32
                            sendCommandToEsp("""{"cmd":"delete","id":"${task.id}"}""")
                        }
                    )
                }
                "Scanning...", "Connecting...", "Discovering Services..." -> {
                    // Show loading indicator or device list during intermediate states
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center){
                        if(isScanning && showDeviceListDialog) {
                            // Let the dialog handle UI during scan
                        } else {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator()
                                Spacer(modifier = Modifier.height(16.dp))
                                Text(connectionState)
                            }
                        }

                    }
                }

                else -> { // Disconnected, Error states
                    DisconnectedView(
                        onScan = { startScan() },
                        errorMessage = if(connectionState.startsWith("Error")) connectionState else null
                    )
                }
            }

            // Show Edit/Add Dialog
            if (showDialog) {
                TaskEditDialog(
                    task = taskToEdit,
                    onDismiss = { showDialog = false },
                    onSave = { updatedTask ->
                        if (taskToEdit == null) { // Add new task
                            Log.d(TAG, "UI Add: $updatedTask")
                            // Add locally immediately (ESP confirmation comes later)
                            schedules.add(updatedTask)
                            // Send add command
                            val taskJson = """{"id":"${updatedTask.id}","title":"${updatedTask.title}","port":${updatedTask.port},"start":"${updatedTask.startTime}","end":"${updatedTask.endTime}","active":${updatedTask.isActive},"alwaysOn":${updatedTask.alwaysActive}}"""
                            sendCommandToEsp("""{"cmd":"add","task":$taskJson}""")
                        } else { // Edit existing task
                            Log.d(TAG, "UI Update: $updatedTask")
                            // Update locally immediately
                            val index = schedules.indexOfFirst { it.id == updatedTask.id }
                            if (index != -1) schedules[index] = updatedTask
                            // Send update command
                            val taskJson = """{"id":"${updatedTask.id}","title":"${updatedTask.title}","port":${updatedTask.port},"start":"${updatedTask.startTime}","end":"${updatedTask.endTime}","active":${updatedTask.isActive},"alwaysOn":${updatedTask.alwaysActive}}"""
                            sendCommandToEsp("""{"cmd":"update","task":$taskJson}""")
                        }
                        showDialog = false
                    }
                )
            }

            // Show Device List Dialog when scanning
            if (showDeviceListDialog) {
                DeviceListDialog(
                    devices = scannedDevices,
                    onDismiss = {
                        showDeviceListDialog = false
                        stopScan() // Stop scan if dialog is dismissed
                        if(connectionState == "Scanning...") connectionState = "Disconnected" // Reset state if scan cancelled
                    },
                    onDeviceSelected = { bleDevice ->
                        showDeviceListDialog = false // Hide dialog
                        connectToDevice(bleDevice.device) // Attempt connection
                    }
                )
            }
        }
    }

    // Cleanup GATT connection when the Composable leaves the screen
    DisposableEffect(Unit) {
        onDispose {
            Log.d(TAG,"DisposableEffect Cleanup: Disconnecting GATT")
            @Suppress("MissingPermission")
            bluetoothGatt?.disconnect()
            @Suppress("MissingPermission")
            bluetoothGatt?.close()
        }
    }
}


// --- UI Components (Mostly Unchanged, but DisconnectedView modified) ---

@Composable
fun DisconnectedView(onScan: () -> Unit, errorMessage: String?) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (errorMessage != null) {
            Text(errorMessage, color = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.height(16.dp))
        } else {
            Text("Not connected to an ESP32")
            Spacer(modifier = Modifier.height(16.dp))
        }
        Button(onClick = onScan) {
            Text("Scan for Devices")
        }
    }
}

// Dialog to show list of scanned devices
@Composable
fun DeviceListDialog(
    devices: List<BleDevice>,
    onDismiss: () -> Unit,
    onDeviceSelected: (BleDevice) -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select ESP32 Device") },
        text = {
            if (devices.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(100.dp), contentAlignment = Alignment.Center) {
                    Text("Scanning... No devices found yet.")
                }
            } else {
                LazyColumn {
                    items(devices) { device ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onDeviceSelected(device) }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.Phone, contentDescription="BLE Device", modifier=Modifier.size(24.dp))
                            Spacer(Modifier.width(16.dp))
                            Column {
                                Text(device.name ?: "Unknown Device", fontWeight = FontWeight.Bold)
                                Text(device.address, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Divider()
                    }
                }
            }
        },
        confirmButton = {
            // No confirm button needed, selection happens on item click
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel Scan") }
        }
    )
}


@Composable
fun ScheduleListView(
    schedules: List<ScheduleTask>,
    onToggle: (ScheduleTask) -> Unit,
    onEdit: (ScheduleTask) -> Unit,
    onDelete: (ScheduleTask) -> Unit
) {
    if (schedules.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("No tasks configured.")
                Spacer(modifier = Modifier.height(8.dp))
                Text("Press '+' to add one.")
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(schedules, key = { it.id }) { task ->
                TaskCard(task, onToggle, onEdit, onDelete)
            }
        }
    }
}

@Composable
fun TaskCard(
    task: ScheduleTask,
    onToggle: (ScheduleTask) -> Unit,
    onEdit: (ScheduleTask) -> Unit,
    onDelete: (ScheduleTask) -> Unit
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = task.isActive, onCheckedChange = { onToggle(task) })
                Spacer(Modifier.width(16.dp))
                Column(modifier = Modifier.weight(1.0f)) {
                    Text(
                        text = "Port ${task.port}: ${task.title}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    val timeText = if (task.alwaysActive) "Always ON" else "${task.startTime} - ${task.endTime}"
                    Text(
                        text = "Time: $timeText",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                Row { // Keep buttons together
                    IconButton(onClick = { onEdit(task) }) { Icon(Icons.Default.Edit, "Edit") }
                    IconButton(onClick = { onDelete(task) }) { Icon(Icons.Default.Delete, "Delete") }
                }
            }
        }
    }
}


@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditDialog(
    task: ScheduleTask?,
    onDismiss: () -> Unit,
    onSave: (ScheduleTask) -> Unit
) {
    val context = LocalContext.current
    var title by remember { mutableStateOf(task?.title ?: "") }
    var port by remember { mutableStateOf(task?.port?.toString() ?: "") }
    var startTime by remember { mutableStateOf(task?.startTime ?: "09:00") }
    var endTime by remember { mutableStateOf(task?.endTime ?: "17:00") }
    var alwaysActive by remember { mutableStateOf(task?.alwaysActive ?: false) }

    // State to track validation errors
    var isTitleError by remember { mutableStateOf(false) }
    var isPortError by remember { mutableStateOf(false) }


    fun showTimePicker(isStartTime: Boolean) {
        val (h, m) = try {
            val timeToParse = if (isStartTime) startTime else endTime
            if (timeToParse.matches(Regex("\\d{2}:\\d{2}"))) { // Basic format check
                timeToParse.split(":").map { it.toInt() }
            } else {
                Log.w(TAG,"Invalid time format for picker: $timeToParse, using default.")
                listOf(9, 0)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing time for picker: ${if (isStartTime) startTime else endTime}", e)
            listOf(9, 0)
        }
        TimePickerDialog(context, { _, hour, minute ->
            val time = String.format("%02d:%02d", hour, minute)
            if (isStartTime) startTime = time else endTime = time
        }, h, m, true).show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Add Task" else "Edit Task") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it; isTitleError = it.isBlank() }, // Validate on change
                    label = { Text("Task Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = isTitleError // Show error state
                )
                if (isTitleError) {
                    Text("Title cannot be empty", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = port,
                    onValueChange = { newValue ->
                        val filtered = newValue.filter { it.isDigit() }
                        port = filtered
                        val portNum = filtered.toIntOrNull()
                        isPortError = portNum == null || portNum < 0 || portNum > 48 // Validate on change
                    },
                    label = { Text("GPIO Port Number (0-48)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    isError = isPortError // Show error state
                )
                if (isPortError) {
                    Text("Invalid Port (must be 0-48)", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                Spacer(Modifier.height(16.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.clickable { alwaysActive = !alwaysActive }.padding(vertical = 4.dp).fillMaxWidth() // Fill width
                ) {
                    Checkbox(checked = alwaysActive, onCheckedChange = { alwaysActive = it })
                    Text("Always Active", modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showTimePicker(true) },
                        modifier = Modifier.weight(1f),
                        enabled = !alwaysActive
                    ) { Text(startTime) }
                    Text(" to ", modifier = Modifier.padding(horizontal = 8.dp))
                    OutlinedButton(
                        onClick = { showTimePicker(false) },
                        modifier = Modifier.weight(1f),
                        enabled = !alwaysActive
                    ) { Text(endTime) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                // Perform final validation before saving
                val currentPortNum = port.toIntOrNull()
                isTitleError = title.isBlank()
                isPortError = currentPortNum == null || currentPortNum < 0 || currentPortNum > 48

                if (isTitleError || isPortError) {
                    Log.w(TAG, "Save aborted due to validation errors.")
                    return@Button // Stop if validation fails
                }

                // Validation passed, proceed with saving
                onSave(
                    (task ?: ScheduleTask(
                        id = UUID.randomUUID().toString(), title = "", port = 0, startTime = "", endTime = "", isActive = true, alwaysActive = false
                    )).copy(
                        title = title.trim(),
                        port = currentPortNum!!, // Safe non-null assertion after validation
                        startTime = startTime,
                        endTime = endTime,
                        alwaysActive = alwaysActive,
                        isActive = task?.isActive ?: true
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


// ---- Permissions Handling (Using Accompanist) ----
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RequestBluetoothPermissions(content: @Composable () -> Unit) {
    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            listOf(
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_CONNECT
            )
        } else {
            // For Android 11 (API 30) and below, location is needed for scanning
            listOf(
                Manifest.permission.BLUETOOTH,
                Manifest.permission.BLUETOOTH_ADMIN,
                Manifest.permission.ACCESS_FINE_LOCATION // Request Fine for better scan results
            )
        }
    }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = permissionsToRequest
    )

    // Effect to launch permission request when the composable enters the composition
    // and permissions are not yet granted.
    LaunchedEffect(permissionsState) {
        if (!permissionsState.allPermissionsGranted && !permissionsState.shouldShowRationale) {
            // permissionsState.launchMultiplePermissionRequest() // Auto-launch disabled, user clicks button
        }
    }


    if (permissionsState.allPermissionsGranted) {
        content() // Permissions are granted, show the main app UI
    } else {
        // Permissions are not granted, show rationale and request button
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val textToShow = if (permissionsState.shouldShowRationale) {
                // Explain why the app needs the permissions (if user denied previously)
                "The app needs Bluetooth permissions to find and connect to your ESP32 device. Location access might also be required for scanning on older Android versions."
            } else {
                // Initial request message or if user denied with "Don't ask again"
                "Please grant Bluetooth permissions for the app to function."
            }
            Text(textToShow, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text("Request Permissions")
            }
        }
    }
}


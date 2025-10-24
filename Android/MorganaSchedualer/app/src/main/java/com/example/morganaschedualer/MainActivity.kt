package com.example.morganaschedualer

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api // <-- ADD THIS LINE
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
// Import the CORRECT permissions handlers
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

// --- Data Model for a Schedule Task ---
data class ScheduleTask(
    val id: String = UUID.randomUUID().toString(),
    val port: Int,
    val startTime: String,
    val endTime: String,
    val isActive: Boolean
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RequestBluetoothPermissions {
                        // Call the correct app function
                        SchedulerApp()
                    }
                }
            }
        }
    }
}

// Add the OptIn tag for Material 3 components
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SchedulerApp() {
    // In a real app, this state would come from a ViewModel connected to a BLE Manager
    var connectionState by remember { mutableStateOf("Disconnected") }
    val schedules = remember { mutableStateListOf<ScheduleTask>() }
    var showDialog by remember { mutableStateOf(false) }
    var taskToEdit by remember { mutableStateOf<ScheduleTask?>(null) }
    val scope = rememberCoroutineScope() // Correct way to get a coroutine scope in Compose

    // --- MOCK BLE FUNCTIONS (for UI demonstration) ---
    // Replace these with your actual BLE communication logic
    fun syncAndFetchSchedulesFromEsp() {
        // 1. Send time sync command to ESP
        // 2. Send "get_schedules" command
        // 3. Listen for response and parse it
        // 4. Update the 'schedules' list
        println("Simulating fetching data from ESP...")
        connectionState = "Connected"
        // Example response
        schedules.clear()
        schedules.addAll(listOf(
            ScheduleTask(port = 26, startTime = "09:00", endTime = "12:30", isActive = true),
            ScheduleTask(port = 27, startTime = "18:00", endTime = "22:00", isActive = false)
        ))
    }

    fun updateSchedulesOnEsp(updatedList: List<ScheduleTask>) {
        // 1. Convert updatedList to JSON string
        // 2. Send "set_schedules" command with the JSON payload
        println("Simulating sending updated list to ESP...")
        println(updatedList)
    }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ESP32 Task Scheduler") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.primary,
                ),
                actions = {
                    Text(connectionState, modifier = Modifier.padding(end=16.dp))
                }
            )
        },
        floatingActionButton = {
            if (connectionState == "Connected") {
                FloatingActionButton(onClick = {
                    taskToEdit = null
                    showDialog = true
                }) {
                    Icon(Icons.Default.Add, contentDescription = "Add Task")
                }
            }
        }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            if (connectionState != "Connected") {
                DisconnectedView(onConnect = {
                    connectionState = "Connecting..."
                    // Simulate delay for connection
                    scope.launch { // Use the Composable's scope
                        delay(1500) // Import kotlinx.coroutines.delay
                        syncAndFetchSchedulesFromEsp()
                    }
                })
            } else {
                ScheduleListView(
                    schedules = schedules,
                    onToggle = { task ->
                        val index = schedules.indexOf(task)
                        if (index != -1) {
                            schedules[index] = task.copy(isActive = !task.isActive)
                            updateSchedulesOnEsp(schedules.toList())
                        }
                    },
                    onEdit = { task ->
                        taskToEdit = task
                        showDialog = true
                    },
                    onDelete = { task ->
                        schedules.remove(task)
                        updateSchedulesOnEsp(schedules.toList())
                    }
                )
            }

            if (showDialog) {
                TaskEditDialog(
                    task = taskToEdit,
                    onDismiss = { showDialog = false },
                    onSave = { updatedTask ->
                        if (taskToEdit == null) { // Add new
                            schedules.add(updatedTask)
                        } else { // Edit existing
                            val index = schedules.indexOfFirst { it.id == updatedTask.id }
                            if (index != -1) {
                                schedules[index] = updatedTask
                            }
                        }
                        updateSchedulesOnEsp(schedules.toList())
                        showDialog = false
                    }
                )
            }
        }
    }
}

@Composable
fun DisconnectedView(onConnect: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("Not connected to an ESP32")
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onConnect) {
            Text("Connect to Device")
        }
    }
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
            Text("No tasks. Press '+' to add one.")
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
                    Text("Port ${task.port}", fontWeight = FontWeight.Bold, fontSize = 20.sp)
                    Text("Time: ${task.startTime} - ${task.endTime}", style = MaterialTheme.typography.bodyLarge)
                }
                IconButton(onClick = { onEdit(task) }) { Icon(Icons.Default.Edit, "Edit") }
                IconButton(onClick = { onDelete(task) }) { Icon(Icons.Default.Delete, "Delete") }
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
    var port by remember { mutableStateOf(task?.port?.toString() ?: "26") }
    var startTime by remember { mutableStateOf(task?.startTime ?: "09:00") }
    var endTime by remember { mutableStateOf(task?.endTime ?: "17:00") }

    fun showTimePicker(isStartTime: Boolean) {
        val (h, m) = if(isStartTime) startTime.split(":").map { it.toInt() } else endTime.split(":").map { it.toInt() }
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
                OutlinedTextField(value = port, onValueChange = { port = it }, label = { Text("GPIO Port Number") })
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = { showTimePicker(true) }, modifier=Modifier.weight(1f)) { Text(startTime) }
                    Text(" to ", modifier=Modifier.padding(horizontal=8.dp))
                    OutlinedButton(onClick = { showTimePicker(false) }, modifier=Modifier.weight(1f)) { Text(endTime) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val portNum = port.toIntOrNull() ?: 26
                onSave(
                    (task ?: ScheduleTask(port=0, startTime = "", endTime = "", isActive = true)).copy(
                        port = portNum,
                        startTime = startTime,
                        endTime = endTime
                    )
                )
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}


// ---- Permissions Handling (UPDATED) ----
@OptIn(ExperimentalPermissionsApi::class)
@Composable
fun RequestBluetoothPermissions(content: @Composable () -> Unit) {
    // List of permissions to request
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android 12 (API 31) and above
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        // Android 11 (API 30) and below
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
    }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = permissionsToRequest
    )

    if (permissionsState.allPermissionsGranted) {
        content()
    } else {
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val textToShow = if (permissionsState.shouldShowRationale) {
                // If the user has denied the permission but not permanently
                "Bluetooth permissions are required for this app to scan for and connect to your ESP32."
            } else {
                // First time asking or user denied permanently
                "Bluetooth and Location permissions are required for BLE functionality."
            }

            Text(textToShow)
            Spacer(modifier = Modifier.height(8.dp))
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text("Request Permissions")
            }
        }
    }
}

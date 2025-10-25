package com.example.morganaschedualer

import android.Manifest
import android.app.TimePickerDialog
import android.os.Build
import android.os.Bundle
import android.util.Log // Log import
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable // Import for clickable modifier
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.material3.ExperimentalMaterial3Api // Opt-in import
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
// Imports for KeyboardOptions and KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign // Import for Text alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
// Import the CORRECT permissions handlers
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.rememberMultiplePermissionsState

// --- Log Tag ---
private const val TAG = "MorganaApp"

// --- Data Model for a Schedule Task (UPDATED) ---
data class ScheduleTask(
    val id: String = UUID.randomUUID().toString(),
    val title: String, // Added Title
    val port: Int,
    val startTime: String,
    val endTime: String,
    val isActive: Boolean,
    val alwaysActive: Boolean // Added Always Active flag
)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    RequestBluetoothPermissions {
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
    val scope = rememberCoroutineScope()

    // --- MOCK BLE FUNCTIONS (Updated for new data model) ---
    fun syncAndFetchSchedulesFromEsp() {
        Log.d(TAG,"Simulating fetching data from ESP...") // Use Log.d
        connectionState = "Connected"

        // Mock sending time sync and get schedules (using new command protocol structure)
        val timeSyncCmd = """{"cmd":"time_sync","time":${System.currentTimeMillis() / 1000}}"""
        Log.d(TAG, "Mock Send: $timeSyncCmd") // Log mock command
        val getSchedulesCmd = """{"cmd":"get_schedules"}"""
        Log.d(TAG, "Mock Send: $getSchedulesCmd") // Log mock command

        // Mock receiving the list (Updated data structure)
        Log.d(TAG, "Mock receiving schedule list...")
        schedules.clear()
        schedules.addAll(listOf(
            ScheduleTask(id = "uuid-1", title = "Water Pump", port = 26, startTime = "09:00", endTime = "12:30", isActive = true, alwaysActive = false),
            ScheduleTask(id = "uuid-2", title = "Night Light", port = 27, startTime = "18:00", endTime = "22:00", isActive = true, alwaysActive = true),
            ScheduleTask(id = "uuid-3", title = "Fan", port = 25, startTime = "10:00", endTime = "16:00", isActive = false, alwaysActive = false)
        ))
    }

    // Mock function to simulate sending updates (updated structure)
    // In a real app, this would send specific add/update/delete commands
    fun sendCommandToEsp(commandJson: String) {
        Log.d(TAG, "Mock Sending Command: $commandJson")
        // In real app: bleManager.sendCommand(commandJson)
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
                    taskToEdit = null // Clear edit state for adding new task
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
                    scope.launch {
                        delay(1500)
                        syncAndFetchSchedulesFromEsp()
                    }
                })
            } else {
                ScheduleListView(
                    schedules = schedules,
                    onToggle = { task ->
                        val updatedTask = task.copy(isActive = !task.isActive)
                        Log.d(TAG, "Toggling task: $updatedTask")
                        // In mock mode, update locally. Real app waits for ESP response.
                        val index = schedules.indexOf(task)
                        if (index != -1) schedules[index] = updatedTask

                        // Send "update" command (using new protocol format)
                        val taskJson = """{"id":"${updatedTask.id}","title":"${updatedTask.title}","port":${updatedTask.port},"start":"${updatedTask.startTime}","end":"${updatedTask.endTime}","active":${updatedTask.isActive},"alwaysOn":${updatedTask.alwaysActive}}"""
                        sendCommandToEsp("""{"cmd":"update","task":$taskJson}""")
                    },
                    onEdit = { task ->
                        taskToEdit = task // Set the task to be edited
                        showDialog = true
                    },
                    onDelete = { task ->
                        Log.d(TAG, "Deleting task: $task")
                        // In mock mode, remove locally.
                        schedules.remove(task)

                        // Send "delete" command (using new protocol format)
                        sendCommandToEsp("""{"cmd":"delete","id":"${task.id}"}""")
                    }
                )
            }

            // Show Edit/Add Dialog
            if (showDialog) {
                TaskEditDialog(
                    task = taskToEdit, // Pass the task to edit, or null for new task
                    onDismiss = { showDialog = false },
                    onSave = { updatedTask ->
                        if (taskToEdit == null) { // Add new task
                            Log.d(TAG, "Adding new task: $updatedTask")
                            schedules.add(updatedTask) // Add locally in mock mode
                            // Send "add" command
                            val taskJson = """{"id":"${updatedTask.id}","title":"${updatedTask.title}","port":${updatedTask.port},"start":"${updatedTask.startTime}","end":"${updatedTask.endTime}","active":${updatedTask.isActive},"alwaysOn":${updatedTask.alwaysActive}}"""
                            sendCommandToEsp("""{"cmd":"add","task":$taskJson}""")

                        } else { // Edit existing task
                            Log.d(TAG, "Updating task: $updatedTask")
                            val index = schedules.indexOfFirst { it.id == updatedTask.id }
                            if (index != -1) {
                                schedules[index] = updatedTask // Update locally in mock mode
                            }
                            // Send "update" command
                            val taskJson = """{"id":"${updatedTask.id}","title":"${updatedTask.title}","port":${updatedTask.port},"start":"${updatedTask.startTime}","end":"${updatedTask.endTime}","active":${updatedTask.isActive},"alwaysOn":${updatedTask.alwaysActive}}"""
                            sendCommandToEsp("""{"cmd":"update","task":$taskJson}""")
                        }
                        showDialog = false // Close dialog after save
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

// TaskCard UPDATED
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
                    // Display Title
                    Text(
                        text = "Port ${task.port}: ${task.title}",
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                    // Display "Always ON" or time range
                    val timeText = if (task.alwaysActive) "Always ON" else "${task.startTime} - ${task.endTime}"
                    Text(
                        text = "Time: $timeText",
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                IconButton(onClick = { onEdit(task) }) { Icon(Icons.Default.Edit, "Edit") }
                IconButton(onClick = { onDelete(task) }) { Icon(Icons.Default.Delete, "Delete") }
            }
        }
    }
}

// TaskEditDialog UPDATED
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditDialog(
    task: ScheduleTask?, // Null if adding a new task
    onDismiss: () -> Unit,
    onSave: (ScheduleTask) -> Unit
) {
    val context = LocalContext.current
    // State holders for the dialog fields
    var title by remember { mutableStateOf(task?.title ?: "") }
    var port by remember { mutableStateOf(task?.port?.toString() ?: "") } // Start empty for new task port
    var startTime by remember { mutableStateOf(task?.startTime ?: "09:00") }
    var endTime by remember { mutableStateOf(task?.endTime ?: "17:00") }
    var alwaysActive by remember { mutableStateOf(task?.alwaysActive ?: false) }

    // Function to show the Time Picker Dialog
    fun showTimePicker(isStartTime: Boolean) {
        val (h, m) = try { // Add try-catch for safety
            val timeToParse = if (isStartTime) startTime else endTime
            timeToParse.split(":").map { it.toInt() }
        } catch (e: Exception) {
            Log.w(TAG, "Error parsing time for picker: ${if (isStartTime) startTime else endTime}", e)
            listOf(9, 0) // Default to 09:00 if parsing fails
        }
        TimePickerDialog(context, { _, hour, minute ->
            val time = String.format("%02d:%02d", hour, minute)
            if (isStartTime) startTime = time else endTime = time
        }, h, m, true).show() // true for 24-hour format
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (task == null) "Add Task" else "Edit Task") },
        text = {
            Column {
                // Task Title Field
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Task Title") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isError = title.isBlank() // Basic validation feedback
                )
                Spacer(Modifier.height(8.dp))

                // Port Number Field
                OutlinedTextField(
                    value = port,
                    onValueChange = { port = it.filter { char -> char.isDigit() } }, // Only allow digits
                    label = { Text("GPIO Port Number (0-48)") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), // Show number keyboard
                    singleLine = true,
                    isError = port.toIntOrNull() == null || (port.toIntOrNull() ?: -1) < 0 || (port.toIntOrNull() ?: -1) > 48 // Validation
                )
                Spacer(Modifier.height(16.dp))

                // Always Active Checkbox
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    // Make the whole row clickable to toggle the checkbox
                    modifier = Modifier.clickable { alwaysActive = !alwaysActive }.padding(vertical = 4.dp)
                ) {
                    Checkbox(checked = alwaysActive, onCheckedChange = { alwaysActive = it })
                    Text("Always Active", modifier = Modifier.padding(start = 8.dp))
                }
                Spacer(Modifier.height(8.dp))

                // Time Pickers (Disabled if Always Active)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(
                        onClick = { showTimePicker(true) },
                        modifier = Modifier.weight(1f),
                        enabled = !alwaysActive // Disable if alwaysActive is true
                    ) { Text(startTime) }
                    Text(" to ", modifier=Modifier.padding(horizontal=8.dp))
                    OutlinedButton(
                        onClick = { showTimePicker(false) },
                        modifier = Modifier.weight(1f),
                        enabled = !alwaysActive // Disable if alwaysActive is true
                    ) { Text(endTime) }
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val portNum = port.toIntOrNull() ?: -1 // Use -1 to indicate invalid parse

                // --- Validation ---
                if (title.isBlank()) {
                    Log.e(TAG, "Save failed: Title cannot be empty")
                    // In a real app, show a Toast or Snackbar message to the user
                    return@Button // Stop the save
                }
                if (portNum < 0 || portNum > 48) { // Check port range
                    Log.e(TAG, "Save failed: Invalid port number '$port'. Must be 0-48.")
                    // In a real app, show a Toast or Snackbar message to the user
                    return@Button // Stop the save
                }
                // Optional: Add time validation (e.g., end time after start time) if needed

                // Create or update the ScheduleTask object
                val resultTask = (task ?: ScheduleTask( // If task is null, create new one
                    id = UUID.randomUUID().toString(), // Generate new ID
                    title = "", // Placeholder, will be overwritten by copy
                    port = 0, // Placeholder
                    startTime = "", // Placeholder
                    endTime = "", // Placeholder
                    isActive = true, // Default new tasks to active
                    alwaysActive = false // Placeholder
                )).copy(
                    title = title.trim(), // Trim whitespace from title
                    port = portNum,
                    startTime = startTime,
                    endTime = endTime,
                    alwaysActive = alwaysActive
                    // isActive state is preserved from original task, or defaults to true for new
                )
                onSave(resultTask) // Pass the validated and updated/created task back
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
    val permissionsToRequest = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        // Android 12 (API 31) and above
        listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_CONNECT
        )
    } else {
        // Below Android 12
        listOf(
            Manifest.permission.BLUETOOTH,
            Manifest.permission.BLUETOOTH_ADMIN,
            Manifest.permission.ACCESS_FINE_LOCATION // Needed for scanning below Android 12
        )
    }

    val permissionsState = rememberMultiplePermissionsState(
        permissions = permissionsToRequest
    )

    if (permissionsState.allPermissionsGranted) {
        content() // Permissions granted, show the main app content
    } else {
        // Permissions not granted, show the request UI
        Column(
            modifier = Modifier.padding(16.dp).fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            val textToShow = if (permissionsState.shouldShowRationale) {
                // If the user previously denied the permission, explain why it's needed.
                "Bluetooth permissions are crucial for this app to find and connect to your ESP32 device. Please grant the permissions."
            } else {
                // First time asking or user previously denied with "Don't ask again".
                "This app requires Bluetooth permissions to function. Please grant them when prompted."
            }

            Text(textToShow, textAlign = TextAlign.Center) // Center align text
            Spacer(modifier = Modifier.height(16.dp)) // Increased spacing
            Button(onClick = { permissionsState.launchMultiplePermissionRequest() }) {
                Text("Request Permissions")
            }
        }
    }
}


/**
 * ESP32 Scheduler - V5
 *
 * This version adds:
 * - Persistent storage using Preferences library (tasks survive reboots)
 * - Enhanced debugging for all BLE communication
 * - A robust command-based system (add, update, delete, get_schedules, time_sync).
 * - Fixes the WDT (Watchdog Timer) boot loop by moving all logic out of the main loop().
 * - Adds "title" and "alwaysActive" fields to each task.
 * - Adds detailed Serial.print() logs for all actions.
 * - Adds forward declarations to fix compile errors.
 */

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>
#include <ArduinoJson.h>
#include <time.h> // For timekeeping
#include <Preferences.h> // For persistent storage

// --- BLE Configuration ---
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

BLECharacteristic* pCharacteristic;
bool deviceConnected = false;
bool timeIsSet = false;

// --- Persistent Storage ---
Preferences preferences;

// --- Schedule Configuration ---
#define MAX_TASKS 20
struct ScheduleTask {
  char id[37]; // 36 chars for UUID + 1 for null terminator
  char title[31]; // 30 chars for title
  int port;
  char startTime[6]; // "HH:MM"
  char endTime[6];
  bool isActive;
  bool alwaysActive;
};
ScheduleTask schedules[MAX_TASKS];
int taskCount = 0;

// --- Forward Declarations ---
// Fix for "'function' was not declared in this scope" error
void sendSchedulesToApp();
String getLocalTimeISO();
String getCurrentTimeStr();
void checkSchedules();
void saveSchedules();
void loadSchedules();


// --- Server Callback Class ---
class MyServerCallbacks: public BLEServerCallbacks {
  void onConnect(BLEServer* pServer) {
    deviceConnected = true;
    Serial.println("===========================================");
    Serial.println(">>> BLE DEVICE CONNECTED <<<");
    Serial.print(">>> Current taskCount: ");
    Serial.println(taskCount);
    Serial.println(">>> MTU: 517 bytes configured for large transfers");
    Serial.println(">>> Waiting for commands from app...");
    Serial.println("===========================================");
  }

  void onDisconnect(BLEServer* pServer) {
    deviceConnected = false;
    timeIsSet = false; // Require a new time sync on reconnect
    Serial.println("===========================================");
    Serial.println(">>> BLE DEVICE DISCONNECTED <<<");
    Serial.println(">>> Restarting advertising...");
    Serial.println("===========================================");
    BLEDevice::startAdvertising(); // Restart advertising
  }
};

// --- Characteristic Callback Class ---
class MyCallbacks: public BLECharacteristicCallbacks {
  void onWrite(BLECharacteristic* pCharacteristic) {
    String value = pCharacteristic->getValue();
    Serial.println();
    Serial.println("===========================================");
    Serial.println(">>> NEW COMMAND RECEIVED <<<");
    Serial.print(">>> Raw data: ");
    Serial.println(value);
    Serial.print(">>> Data length: ");
    Serial.println(value.length());

    // Parse the command with ArduinoJson
    JsonDocument doc;
    DeserializationError error = deserializeJson(doc, value);

    if (error) {
      Serial.print(">>> ERROR: deserializeJson() failed: ");
      Serial.println(error.c_str());
      Serial.println("===========================================");
      return;
    }

    const char* cmd = doc["cmd"];
    if (!cmd) {
      Serial.println(">>> ERROR: 'cmd' field missing in JSON.");
      Serial.println("===========================================");
      return;
    }
    Serial.print(">>> Command type: ");
    Serial.println(cmd);

    // --- Command Router ---
    if (strcmp(cmd, "time_sync") == 0) {
      // 1. Time Sync Command
      // {"cmd":"time_sync","time":1678886400}
      long unixTime = doc["time"];
      if (unixTime > 0) {
        timeval tv;
        tv.tv_sec = unixTime;
        tv.tv_usec = 0;
        settimeofday(&tv, NULL);
        timeIsSet = true;
        Serial.print("Time successfully synced. Current time: ");
        Serial.println(getLocalTimeISO());
      } else {
        Serial.println("Failed to sync time, invalid timestamp.");
      }

    } else if (strcmp(cmd, "get_schedules") == 0) {
      // 2. Get All Schedules Command
      // {"cmd":"get_schedules"}
      Serial.println(">>> Processing get_schedules command...");
      Serial.print(">>> Current taskCount: ");
      Serial.println(taskCount);
      sendSchedulesToApp();

    } else if (strcmp(cmd, "add") == 0) {
      // 3. Add New Task Command
      // {"cmd":"add","task":{...}}
      if (taskCount >= MAX_TASKS) {
        Serial.print(">>> ERROR: Cannot add task, list is full (");
        Serial.print(MAX_TASKS);
        Serial.println(" tasks maximum)");
        return;
      }
      JsonObject task = doc["task"];
      strlcpy(schedules[taskCount].id, task["id"], 37);
      strlcpy(schedules[taskCount].title, task["title"], 31);
      schedules[taskCount].port = task["port"];
      strlcpy(schedules[taskCount].startTime, task["start"], 6);
      strlcpy(schedules[taskCount].endTime, task["end"], 6);
      schedules[taskCount].isActive = task["active"];
      schedules[taskCount].alwaysActive = task["alwaysOn"];
      
      Serial.print(">>> Adding task #");
      Serial.print(taskCount);
      Serial.print(": '");
      Serial.print(schedules[taskCount].title);
      Serial.print("' on port ");
      Serial.println(schedules[taskCount].port);

      taskCount++;
      saveSchedules(); // Save to flash memory
      sendSchedulesToApp(); // Send updated list back to confirm

    } else if (strcmp(cmd, "update") == 0) {
      // 4. Update Existing Task Command
      // {"cmd":"update","task":{...}}
      JsonObject task = doc["task"];
      const char* id = task["id"];
      bool found = false;
      for (int i = 0; i < taskCount; i++) {
        if (strcmp(schedules[i].id, id) == 0) {
          strlcpy(schedules[i].title, task["title"], 31);
          schedules[i].port = task["port"];
          strlcpy(schedules[i].startTime, task["start"], 6);
          strlcpy(schedules[i].endTime, task["end"], 6);
          schedules[i].isActive = task["active"];
          schedules[i].alwaysActive = task["alwaysOn"];
          
          Serial.print(">>> Updating task #");
          Serial.print(i);
          Serial.print(": '");
          Serial.print(schedules[i].title);
          Serial.print("' - Active: ");
          Serial.println(schedules[i].isActive ? "YES" : "NO");
          
          found = true;
          break;
        }
      }
      if (found) {
        saveSchedules(); // Save to flash memory
        sendSchedulesToApp(); // Send updated list back
      } else {
        Serial.print(">>> ERROR: Could not update task, ID not found: ");
        Serial.println(id);
      }

    } else if (strcmp(cmd, "delete") == 0) {
      // 5. Delete Task Command
      // {"cmd":"delete","id":"uuid-string"}
      const char* id = doc["id"];
      int foundIndex = -1;
      for (int i = 0; i < taskCount; i++) {
        if (strcmp(schedules[i].id, id) == 0) {
          foundIndex = i;
          Serial.print(">>> Deleting task #");
          Serial.print(i);
          Serial.print(": '");
          Serial.print(schedules[i].title);
          Serial.println("'");
          break;
        }
      }

      if (foundIndex != -1) {
        // Shift all subsequent tasks down
        for (int i = foundIndex; i < taskCount - 1; i++) {
          schedules[i] = schedules[i + 1];
        }
        taskCount--;
        saveSchedules(); // Save to flash memory
        sendSchedulesToApp(); // Send updated list back
        Serial.print(">>> Task deleted. New taskCount: ");
        Serial.println(taskCount);
      } else {
        Serial.print(">>> ERROR: Could not delete task, ID not found: ");
        Serial.println(id);
      }

    } else {
      Serial.print(">>> ERROR: Unknown command received: ");
      Serial.println(cmd);
    }

    Serial.println("===========================================");
  }
};

// --- Helper Functions ---

String getLocalTimeISO() {
  struct tm timeinfo;
  if (!getLocalTime(&timeinfo)) {
    return "Time not set";
  }
  char timeStr[20];
  strftime(timeStr, sizeof(timeStr), "%Y-%m-%d %H:%M:%S", &timeinfo);
  return String(timeStr);
}

// Gets current time as "HH:MM"
String getCurrentTimeStr() {
  struct tm timeinfo;
  if (!getLocalTime(&timeinfo)) {
    return "00:00";
  }
  char timeStr[6];
  strftime(timeStr, sizeof(timeStr), "%H:%M", &timeinfo);
  return String(timeStr);
}

// Sends the full list of schedules to the app
void sendSchedulesToApp() {
  if (!deviceConnected) {
    Serial.println(">>> ERROR: Send failed - Device not connected.");
    return;
  }

  Serial.println(">>> Preparing to send schedules to app...");
  Serial.print(">>> Building JSON array with ");
  Serial.print(taskCount);
  Serial.println(" tasks");

  JsonDocument doc;
  JsonArray tasks = doc.to<JsonArray>();
  for (int i = 0; i < taskCount; i++) {
    JsonObject task = tasks.add<JsonObject>();
    task["id"] = schedules[i].id;
    task["title"] = schedules[i].title;
    task["port"] = schedules[i].port;
    task["start"] = schedules[i].startTime;
    task["end"] = schedules[i].endTime;
    task["active"] = schedules[i].isActive;
    task["alwaysOn"] = schedules[i].alwaysActive;
    
    Serial.print(">>>   Task #");
    Serial.print(i);
    Serial.print(": ");
    Serial.print(schedules[i].title);
    Serial.print(" (Port ");
    Serial.print(schedules[i].port);
    Serial.println(")");
  }

  String output;
  serializeJson(doc, output);

  Serial.println(">>> JSON payload to send:");
  Serial.println(output);
  Serial.print(">>> Payload size: ");
  Serial.print(output.length());
  Serial.println(" bytes");

  // Send the JSON string to the app
  pCharacteristic->setValue(output.c_str());
  pCharacteristic->notify();
  
  Serial.println(">>> Schedule list sent via BLE notification");
}

// Save all schedules to flash memory (persistent storage)
void saveSchedules() {
  Serial.println(">>> Saving schedules to flash memory...");
  preferences.begin("schedules", false); // Open in read-write mode
  
  // Save the task count
  preferences.putInt("taskCount", taskCount);
  Serial.print(">>> Saving taskCount: ");
  Serial.println(taskCount);
  
  // Save each task as a blob
  for (int i = 0; i < taskCount; i++) {
    char key[10];
    sprintf(key, "task_%d", i);
    preferences.putBytes(key, &schedules[i], sizeof(ScheduleTask));
    Serial.print(">>>   Saved task #");
    Serial.print(i);
    Serial.print(": ");
    Serial.println(schedules[i].title);
  }
  
  preferences.end();
  Serial.println(">>> Schedules saved successfully!");
}

// Load all schedules from flash memory
void loadSchedules() {
  Serial.println(">>> Loading schedules from flash memory...");
  preferences.begin("schedules", true); // Open in read-only mode
  
  // Load the task count
  taskCount = preferences.getInt("taskCount", 0); // Default to 0 if not found
  Serial.print(">>> Loaded taskCount: ");
  Serial.println(taskCount);
  
  // Load each task
  for (int i = 0; i < taskCount; i++) {
    char key[10];
    sprintf(key, "task_%d", i);
    size_t bytesRead = preferences.getBytes(key, &schedules[i], sizeof(ScheduleTask));
    
    if (bytesRead == sizeof(ScheduleTask)) {
      Serial.print(">>>   Loaded task #");
      Serial.print(i);
      Serial.print(": ");
      Serial.print(schedules[i].title);
      Serial.print(" (Port ");
      Serial.print(schedules[i].port);
      Serial.print(", ");
      Serial.print(schedules[i].startTime);
      Serial.print(" - ");
      Serial.print(schedules[i].endTime);
      Serial.println(")");
    } else {
      Serial.print(">>> ERROR: Failed to load task #");
      Serial.println(i);
    }
  }
  
  preferences.end();
  Serial.println(">>> Schedules loaded successfully!");
}

// This function checks and applies the schedules
void checkSchedules() {
  if (!timeIsSet) {
    // Don't do anything if time hasn't been synced
    return;
  }

  String currentTime = getCurrentTimeStr();
  // Serial.print("Checking schedules... Current time: "); // Uncomment for very noisy logging
  // Serial.println(currentTime);

  for (int i = 0; i < taskCount; i++) {
    ScheduleTask task = schedules[i];

    // Configure the pin as an output
    // Note: Do not use input-only pins (34-39) or system pins (6-11)!
    pinMode(task.port, OUTPUT);

    if (!task.isActive) {
      // Task is disabled, turn it off
      if (digitalRead(task.port) == HIGH) { // Only log if state is changing
        Serial.print("Task '");
        Serial.print(task.title);
        Serial.print("' (Port ");
        Serial.print(task.port);
        Serial.println(") is inactive. Turning OFF.");
        digitalWrite(task.port, LOW);
      }
      continue;
    }

    if (task.alwaysActive) {
      // Task is set to "Always Active", turn it on
      if (digitalRead(task.port) == LOW) { // Only log if state is changing
        Serial.print("Task '");
        Serial.print(task.title);
        Serial.print("' (Port ");
        Serial.print(task.port);
        Serial.println(") is 'Always Active'. Turning ON.");
        digitalWrite(task.port, HIGH);
      }
      continue;
    }

    // Standard time-based logic
    bool shouldBeOn = false;
    if (strcmp(task.startTime, task.endTime) < 0) {
      // Normal case (e.g., 09:00 - 17:00)
      shouldBeOn = (currentTime >= task.startTime && currentTime < task.endTime);
    } else {
      // Overnight case (e.g., 22:00 - 06:00)
      shouldBeOn = (currentTime >= task.startTime || currentTime < task.endTime);
    }

    if (shouldBeOn) {
      if (digitalRead(task.port) == LOW) { // Only log if state is changing
        Serial.print("Task '");
        Serial.print(task.title);
        Serial.print("' (Port ");
        Serial.print(task.port);
        Serial.println(") is ACTIVE. Turning ON.");
        digitalWrite(task.port, HIGH);
      }
    } else {
      if (digitalRead(task.port) == HIGH) { // Only log if state is changing
        Serial.print("Task '");
        Serial.print(task.title);
        Serial.print("' (Port ");
        Serial.print(task.port);
        Serial.println(") is INACTIVE. Turning OFF.");
        digitalWrite(task.port, LOW);
      }
    }
  }
}

// --- Arduino Setup ---
void setup() {
  Serial.begin(115200);
  Serial.println();
  Serial.println("===========================================");
  Serial.println(">>> Starting ESP32 Scheduler V5 <<<");
  Serial.println("===========================================");

  // Load saved schedules from flash memory
  loadSchedules();
  Serial.println();

  // Initialize BLE
  Serial.println(">>> Initializing BLE...");
  BLEDevice::init("ESP32 Scheduler");
  
  // Set MTU size (must be done before creating server)
  BLEDevice::setMTU(517); // Set maximum MTU to match Android request
  Serial.println(">>> MTU set to 517 bytes for large data transfers");
  
  BLEServer *pServer = BLEDevice::createServer();
  pServer->setCallbacks(new MyServerCallbacks());
  BLEService *pService = pServer->createService(SERVICE_UUID);

  pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_READ |
                      BLECharacteristic::PROPERTY_WRITE |
                      BLECharacteristic::PROPERTY_NOTIFY
                    );

  pCharacteristic->addDescriptor(new BLE2902());
  pCharacteristic->setCallbacks(new MyCallbacks());

  // Set an initial value
  pCharacteristic->setValue("Hello! Awaiting command...");
  pService->start();

  // Start advertising
  Serial.println(">>> Starting BLE advertising...");
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  pAdvertising->setScanResponse(true);
  pAdvertising->setMinPreferred(0x06); // functions that help with iPhone connections
  pAdvertising->setMinPreferred(0x12);
  BLEDevice::startAdvertising();

  Serial.println("===========================================");
  Serial.println(">>> BLE Server Started Successfully! <<<");
  Serial.print(">>> Device Name: ESP32 Scheduler");
  Serial.println();
  Serial.print(">>> Loaded Tasks: ");
  Serial.println(taskCount);
  Serial.println(">>> Waiting for client connection...");
  Serial.println("===========================================");
}

// --- Arduino Loop ---
void loop() {
  // The main loop is now non-blocking!
  // This loop runs thousands of times per second.
  
  if (deviceConnected && timeIsSet) {
    // Only check schedules if a device is connected and time is synced
    checkSchedules();
  }

  // A small delay is CRITICAL to prevent the watchdog timer from
  // resetting the chip, especially when no device is connected.
  // This allows background BLE tasks to run.
  delay(100);
}


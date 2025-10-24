/*
  ESP32 Port Scheduler Firmware - V2 (Offline, Multi-Task)
  - Stores a list of schedules.
  - Syncs time and schedule list with a phone over BLE.
  - The ESP32 is the single source of truth for the schedule list.
*/

#include <BLEDevice.h>
#include <BLEUtils.h>
#include <BLEServer.h>
#include <ArduinoJson.h>
#include "time.h"
#include <BLE2902.h>

// --- Configuration ---
#define SERVICE_UUID        "4fafc201-1fb5-459e-8fcc-c5c9c331914b"
#define CHARACTERISTIC_UUID "beb5483e-36e1-4688-b7f5-ea07361b26a8"

#define MAX_SCHEDULES 20
#define JSON_DOC_SIZE 1024 // Increased size for full schedule list

// Data structure for a single schedule task
struct Schedule {
  int port;
  bool active;
  int startMinutes;
  int endMinutes;
};

// The global list of all schedules
Schedule scheduleList[MAX_SCHEDULES];
int scheduleCount = 0;
bool isTimeSet = false;

BLECharacteristic *pCharacteristic;

// --- Helper Functions ---
void serializeSchedulesToJson(char* buffer, size_t size) {
  StaticJsonDocument<JSON_DOC_SIZE> doc;
  JsonArray schedules = doc.createNestedArray("schedules");
  
  for (int i = 0; i < scheduleCount; i++) {
    JsonObject s = schedules.createNestedObject();
    s["port"] = scheduleList[i].port;
    s["active"] = scheduleList[i].active;
    
    char startTime[6], endTime[6];
    sprintf(startTime, "%02d:%02d", scheduleList[i].startMinutes / 60, scheduleList[i].startMinutes % 60);
    sprintf(endTime, "%02d:%02d", scheduleList[i].endMinutes / 60, scheduleList[i].endMinutes % 60);
    s["start"] = startTime;
    s["end"] = endTime;
  }
  
  serializeJson(doc, buffer, size);
}


// --- BLE Callbacks ---
class MyCallbacks: public BLECharacteristicCallbacks {
    void onWrite(BLECharacteristic *pCharacteristic) {
      String value = pCharacteristic->getValue();
      if (value.length() == 0) return;

      Serial.print("Received Command: ");
      Serial.println(value.c_str());

      StaticJsonDocument<JSON_DOC_SIZE> doc;
      deserializeJson(doc, value);

      // --- Time Sync Command ---
      if (doc.containsKey("time")) {
        timeval tv;
        tv.tv_sec = doc["time"];
        settimeofday(&tv, NULL);
        isTimeSet = true;
        Serial.println("Time has been synchronized.");
      }
      
      // --- Command Router ---
      const char* cmd = doc["cmd"];
      if (!cmd) return;

      // --- Get Schedules Command ---
      if (strcmp(cmd, "get_schedules") == 0) {
        char buffer[JSON_DOC_SIZE];
        serializeSchedulesToJson(buffer, sizeof(buffer));
        pCharacteristic->setValue(buffer);
        pCharacteristic->notify(); // Notify the client the value has changed
        Serial.println("Sent schedule list to phone.");
        Serial.println(buffer);
      }
      
      // --- Set Schedules Command ---
      else if (strcmp(cmd, "set_schedules") == 0) {
        JsonArray data = doc["data"];
        scheduleCount = 0; // Clear the old list
        
        for (JsonObject item : data) {
          if (scheduleCount >= MAX_SCHEDULES) break;
          
          scheduleList[scheduleCount].port = item["port"];
          scheduleList[scheduleCount].active = item["active"];
          
          const char* start_str = item["start"];
          const char* end_str = item["end"];
          int startH, startM, endH, endM;
          sscanf(start_str, "%d:%d", &startH, &startM);
          sscanf(end_str, "%d:%d", &endH, &endM);
          
          scheduleList[scheduleCount].startMinutes = startH * 60 + startM;
          scheduleList[scheduleCount].endMinutes = endH * 60 + endM;
          
          scheduleCount++;
        }
        Serial.printf("Received and saved %d schedules.\n", scheduleCount);
      }
    }
};


void setup() {
  Serial.begin(115200);
  Serial.println("Starting ESP32 Scheduler V2...");

  // Initialize all possible ports you might use
  pinMode(25, OUTPUT); pinMode(26, OUTPUT); pinMode(27, OUTPUT); pinMode(32, OUTPUT);
  digitalWrite(25, LOW); digitalWrite(26, LOW); digitalWrite(27, LOW); digitalWrite(32, LOW);

  // Initialize BLE
  BLEDevice::init("ESP32 Scheduler V2");
  BLEServer *pServer = BLEDevice::createServer();
  BLEService *pService = pServer->createService(SERVICE_UUID);
  
  pCharacteristic = pService->createCharacteristic(
                      CHARACTERISTIC_UUID,
                      BLECharacteristic::PROPERTY_READ |
                      BLECharacteristic::PROPERTY_WRITE |
                      BLECharacteristic::PROPERTY_NOTIFY
                    );
  pCharacteristic->addDescriptor(new BLE2902());
  pCharacteristic->setCallbacks(new MyCallbacks());
  pService->start();
  
  BLEAdvertising *pAdvertising = BLEDevice::getAdvertising();
  pAdvertising->addServiceUUID(SERVICE_UUID);
  BLEDevice::startAdvertising();
  Serial.println("BLE Server started.");
}

void checkSchedules() {
  if (!isTimeSet) return;

  struct tm timeinfo;
  getLocalTime(&timeinfo);
  int currentMinutes = timeinfo.tm_hour * 60 + timeinfo.tm_min;

  // Logic to determine which ports should be on
  bool portState[34] = {false}; // Keep track of final port states

  for (int i = 0; i < scheduleCount; i++) {
    Schedule s = scheduleList[i];
    if (!s.active) continue;

    bool shouldBeOn = false;
    if (s.startMinutes > s.endMinutes) { // Overnight
      if (currentMinutes >= s.startMinutes || currentMinutes < s.endMinutes) {
        shouldBeOn = true;
      }
    } else { // Same-day
      if (currentMinutes >= s.startMinutes && currentMinutes < s.endMinutes) {
        shouldBeOn = true;
      }
    }

    if (shouldBeOn) {
      portState[s.port] = true;
    }
  }

  // Apply the final states to the GPIOs
  // This correctly handles multiple schedules for the same port
  for (int i=0; i<34; ++i) {
    digitalWrite(i, portState[i]);
  }
}

void loop() {
  checkSchedules();
  delay(5000);
}


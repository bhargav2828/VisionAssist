
# Vision Assist

**An AI-powered assistive application designed to help visually impaired people understand their surroundings through voice interaction and real-time image analysis.**

## About the Project

Vision Assist is an Android application that uses a smartphone camera, object detection, and a vision-language AI model to describe the user's surroundings.

The application captures images using the phone camera and sends them to a Python backend running on a computer over a local Wi-Fi network. The backend analyzes the image and returns a description that the Android application communicates to the user through text-to-speech.

## Key Features

- **Voice Interaction:** Interact with the application using voice commands.
- **Camera Capture:** Capture images using the Android device's camera.
- **Object Detection:** Detect objects in captured images using YOLO.
- **AI Image Understanding:** Generate contextual descriptions using Qwen2.5-VL.
- **Speech Output:** Communicate image descriptions through Android Text-to-Speech.
- **Local Network Communication:** Connect the Android application to the Python backend over Wi-Fi.

## Project Architecture

The project consists of two main components:

### 1. Android Application

Located in `android/VisionAssist/`.

Technologies:
- Kotlin
- Jetpack Compose
- CameraX
- OkHttp
- Android Speech Recognition
- Android Text-to-Speech

The Android application captures images, communicates with the backend, receives descriptions, and presents the results through speech.

### 2. Python Backend

Located in the repository root.

Technologies:
- Python
- FastAPI
- Ultralytics YOLO
- Ollama
- Qwen2.5-VL
- Pillow
- Zeroconf

The backend receives images from the Android application, performs image processing and object detection, and uses the vision-language model to generate contextual descriptions.

## How It Works

1. The user interacts with the Android application using voice commands.
2. The smartphone camera captures an image.
3. The image is sent to the Python backend over the local Wi-Fi network.
4. The backend processes the image using YOLO object detection.
5. The image and detection information are passed to the Qwen2.5-VL model through Ollama.
6. The generated description is returned to the Android application.
7. Android Text-to-Speech communicates the result to the user.

## Repository Structure

```text
VisionAssist/
│
├── android/
│   └── VisionAssist/
│       ├── app/
│       ├── gradle/
│       ├── build.gradle.kts
│       ├── settings.gradle.kts
│       └── ...
│
├── server.py
├── vision_pipeline.py
├── object_detection.py
├── spatial_reasoning.py
├── capture_image.py
├── main.py
├── requirements.txt
├── README.md
└── .gitignore
```

## Requirements

- Python installed on the backend computer
- Android Studio
- An Android device
- Ollama installed on the backend computer
- Qwen2.5-VL model (`qwen2.5vl:3b`)
- YOLO model weights required by the backend
- Android device and backend computer connected to the same Wi-Fi network

## Backend Setup

### 1. Clone the Repository

```bash
git clone https://github.com/bhargav2828/VisionAssist.git
cd VisionAssist
```

### 2. Create a Virtual Environment

```bash
python -m venv venv
```

Activate it on Windows:

```powershell
venv\Scripts\activate
```

### 3. Install Dependencies

```bash
pip install -r requirements.txt
```

### 4. Set Up Ollama

Install Ollama and download the required vision-language model:

```bash
ollama pull qwen2.5vl:3b
```

Make sure Ollama is running before starting the backend.

### 5. Start the Backend Server

```bash
python -m uvicorn server:app --host 0.0.0.0 --port 8000
```

The backend runs on port `8000`.

Health endpoint:

```text
http://127.0.0.1:8000/health
```

## Android Application Setup

1. Open Android Studio.
2. Select **Open**.
3. Navigate to `android/VisionAssist/`.
4. Allow Gradle synchronization to complete.
5. Connect an Android device or start an emulator.
6. Run the application.

Make sure the Android device and the computer running the backend are connected to the same local Wi-Fi network.

## Technologies Used

| Component | Technology |
|---|---|
| Android Development | Kotlin |
| User Interface | Jetpack Compose |
| Camera | CameraX |
| Backend | Python, FastAPI |
| Object Detection | YOLO |
| Vision-Language Model | Qwen2.5-VL |
| AI Model Runtime | Ollama |
| Image Processing | Pillow |
| Network Communication | OkHttp |
| Speech Output | Android Text-to-Speech |

## Future Improvements

- Improve object detection and scene understanding.
- Enhance voice interaction and response speed.
- Improve spatial awareness and contextual descriptions.
- Expand accessibility features.
- Improve communication and reliability between the Android application and backend.

## Disclaimer

Vision Assist is an assistive technology project intended to support visually impaired users in understanding their surroundings.

It is not a certified navigation, mobility, or obstacle-avoidance system. Users should continue to rely on established mobility aids and safety practices.

## Author

**Vision Assist Project**

GitHub Repository: https://github.com/bhargav2828/VisionAssist

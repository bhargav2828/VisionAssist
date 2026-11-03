
from pathlib import Path
from time import perf_counter
import io

import ollama
from PIL import Image
from ultralytics import YOLO


QWEN_MODEL = "qwen2.5vl:3b"
YOLO_MODEL = "yolo26n.pt"

CONFIDENCE_THRESHOLD = 0.35
MAX_IMAGE_SIZE = 1280

# Load YOLO once and reuse it for future images.
_yolo_model = None


def get_yolo_model():
    global _yolo_model

    if _yolo_model is None:
        print("Loading YOLO model...")
        _yolo_model = YOLO(YOLO_MODEL)

    return _yolo_model


def prepare_image(image_path):
    """Prepare a smaller image for Qwen."""

    with Image.open(image_path) as image:
        image = image.convert("RGB")
        original_size = image.size

        image.thumbnail(
            (MAX_IMAGE_SIZE, MAX_IMAGE_SIZE),
            Image.Resampling.LANCZOS
        )

        buffer = io.BytesIO()

        image.save(
            buffer,
            format="JPEG",
            quality=85
        )

        return buffer.getvalue(), original_size, image.size


def detect_objects(image_path):
    """Detect objects and calculate their image-relative positions."""

    model = get_yolo_model()

    with Image.open(image_path) as image:
        width, height = image.size

    results = model.predict(
        source=str(image_path),
        conf=CONFIDENCE_THRESHOLD,
        imgsz=640,
        verbose=False
    )

    detections = []

    for result in results:
        for box in result.boxes:

            x1, y1, x2, y2 = box.xyxy[0].tolist()

            center_x = (x1 + x2) / 2
            center_y = (y1 + y2) / 2

            horizontal = (
                "left" if center_x < width / 3
                else "right" if center_x > 2 * width / 3
                else "center"
            )

            vertical = (
                "upper" if center_y < height / 3
                else "lower" if center_y > 2 * height / 3
                else "middle"
            )

            class_id = int(box.cls[0])

            detections.append({
                "object": result.names[class_id],
                "confidence": round(float(box.conf[0]), 2),
                "position_in_image": f"{vertical}-{horizontal}"
            })

    return detections


def describe_image(image_bytes, detections):
    """
    Use one Qwen request to understand the image
    and carefully combine YOLO detections.
    """

    if detections:
        detection_text = "\n".join(
            f"- {item['object']}: "
            f"confidence {item['confidence']}, "
            f"image position {item['position_in_image']}"
            for item in detections
        )
    else:
        detection_text = "YOLO did not detect any objects."

    prompt = f"""
You are Vision Assist, an accessibility assistant
for blind and visually impaired people.

Describe the attached image clearly and naturally.

YOLO OBJECT DETECTIONS:
{detection_text}

IMPORTANT INSTRUCTIONS:

1. First describe the overall environment.
2. Identify important people, objects and activities.
3. Identify common objects accurately, such as laptops,
   phones, tables, chairs, beds and doors.
4. Use YOLO detections only when they are visually
   supported by the image.
5. If YOLO identifies something incorrectly, IGNORE it.
6. Do not invent objects just because YOLO detected them.
7. Give useful image-relative directions:
   left, center, right, foreground and background.
8. Mention the position of important objects when clear.
9. Explain relationships between objects and people.
10. Avoid repeating the same information.
11. Use short, natural sentences suitable for speech.
12. Do not give walking instructions or claim that
    a route is safe.

Remember:
Left, center and right refer to positions in the IMAGE,
not the person's actual body-relative directions.

Return only the final spoken description.
"""

    response = ollama.chat(
        model=QWEN_MODEL,
        messages=[
            {
                "role": "user",
                "content": prompt,
                "images": [image_bytes]
            }
        ],
        options={
            "num_ctx": 4096,
            "num_predict": 180,
            "temperature": 0.2
        }
    )

    return response["message"]["content"].strip()


def run_pipeline(image_path):
    """Run Vision Assist image analysis."""

    path = Path(image_path).expanduser()

    if not path.is_file():
        raise FileNotFoundError(f"Image not found: {path}")

    print("\n" + "=" * 55)
    print("VISION ASSIST - AI ANALYSIS")
    print("=" * 55)

    total_start = perf_counter()

    print("\nSTEP 1: Preparing image...")

    image_bytes, original_size, resized_size = prepare_image(path)

    print(f"Original image: {original_size}")
    print(f"Qwen image: {resized_size}")

    print("\nSTEP 2: Running YOLO...")

    yolo_start = perf_counter()

    detections = detect_objects(path)

    yolo_time = perf_counter() - yolo_start

    print(f"YOLO completed in {yolo_time:.1f} seconds.")
    print(f"Objects detected: {len(detections)}")

    for item in detections:
        print(
            f"  {item['object']} | "
            f"confidence: {item['confidence']} | "
            f"position: {item['position_in_image']}"
        )

    print("\nSTEP 3: Running Qwen scene understanding...")

    qwen_start = perf_counter()

    final_narration = describe_image(
        image_bytes,
        detections
    )

    qwen_time = perf_counter() - qwen_start

    total_time = perf_counter() - total_start

    print("\n" + "=" * 55)
    print("VISION ASSIST - FINAL NARRATION")
    print("=" * 55)

    print("\n" + final_narration)

    print("\n" + "=" * 55)
    print(f"YOLO time: {yolo_time:.1f} seconds")
    print(f"Qwen time: {qwen_time:.1f} seconds")
    print(f"Total time: {total_time:.1f} seconds")
    print("=" * 55)

    return final_narration


def main():

    print("VISION ASSIST - MULTIMODAL AI")

    while True:

        try:
            image_path = input(
                "\nEnter image path or type 'exit': "
            ).strip().strip('"')

            if image_path.lower() == "exit":
                print("Vision Assist closed.")
                break

            if not image_path:
                continue

            try:
                run_pipeline(image_path)

            except ollama.ResponseError as error:
                print(f"Ollama error: {error}")

            except Exception as error:
                print(f"Analysis error: {error}")

        except (KeyboardInterrupt, EOFError):
            print("\nVision Assist stopped.")
            break


if __name__ == "__main__":
    main()

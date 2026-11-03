
from pathlib import Path

from ultralytics import YOLO


MODEL_NAME = "yolo26n.pt"


def detect_objects(image_path):
    """Detect common objects in an image using YOLO."""
    path = Path(image_path).expanduser()

    if not path.is_file():
        raise FileNotFoundError(f"Image not found: {path}")

    model = YOLO(MODEL_NAME)
    results = model.predict(source=str(path), verbose=False)

    detections = []

    for result in results:
        for box in result.boxes:
            class_id = int(box.cls[0])
            confidence = float(box.conf[0])
            x1, y1, x2, y2 = box.xyxy[0].tolist()

            detections.append({
                "label": result.names[class_id],
                "confidence": round(confidence, 3),
                "box": [
                    round(x1),
                    round(y1),
                    round(x2),
                    round(y2),
                ],
            })

    return detections


if __name__ == "__main__":
    image_path = input("Enter the full path to an image: ").strip().strip('"')

    try:
        detections = detect_objects(image_path)

        print("\n" + "=" * 45)
        print("VISION ASSIST - OBJECT DETECTION")
        print("=" * 45)

        if not detections:
            print("No objects detected.")
        else:
            for item in detections:
                print(
                    f"{item['label']} | "
                    f"confidence: {item['confidence']:.1%} | "
                    f"box: {item['box']}"
                )

        print(f"\nTotal detections: {len(detections)}")

    except (FileNotFoundError, OSError, ValueError) as error:
        print(f"Could not process image: {error}")

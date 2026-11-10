
from pathlib import Path
from collections import Counter

from ultralytics import YOLO


MODEL_NAME = "yolo26n.pt"
CONFIDENCE_THRESHOLD = 0.20
IMAGE_SIZE = 640

_model = None


def get_model():
    global _model

    if _model is None:
        print("Loading YOLO object detection model...")
        _model = YOLO(MODEL_NAME)

    return _model


def detect_objects(image_path):
    """Detect supported objects and return their locations and counts."""

    path = Path(image_path).expanduser()

    if not path.is_file():
        raise FileNotFoundError(f"Image not found: {path}")

    model = get_model()

    results = model.predict(
        source=str(path),
        conf=CONFIDENCE_THRESHOLD,
        imgsz=IMAGE_SIZE,
        verbose=False
    )

    detections = []

    for result in results:
        width, height = result.orig_shape[1], result.orig_shape[0]

        for box in result.boxes:
            class_id = int(box.cls[0])
            confidence = float(box.conf[0])

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

            detections.append({
                "label": result.names[class_id],
                "confidence": round(confidence, 3),
                "box": [
                    round(x1),
                    round(y1),
                    round(x2),
                    round(y2)
                ],
                "position": f"{vertical}-{horizontal}",
                "center": [
                    round(center_x),
                    round(center_y)
                ]
            })

    return detections


def group_detections(detections):
    """Group detected objects by class and calculate their counts."""

    counts = Counter(
        item["label"] for item in detections
    )

    grouped = []

    for label, count in sorted(counts.items()):
        matching = [
            item for item in detections
            if item["label"] == label
        ]

        grouped.append({
            "label": label,
            "count": count,
            "average_confidence": round(
                sum(item["confidence"] for item in matching) / count,
                3
            ),
            "positions": [
                item["position"] for item in matching
            ]
        })

    return grouped


def format_detection_summary(detections):
    """Create a readable summary for the visual-language model."""

    if not detections:
        return "No objects were detected by YOLO."

    grouped = group_detections(detections)

    lines = []

    for item in grouped:
        lines.append(
            f"- {item['label']}: {item['count']} detected; "
            f"average confidence {item['average_confidence']}; "
            f"positions: {', '.join(item['positions'])}"
        )

    return "\n".join(lines)


if __name__ == "__main__":

    image_path = input(
        "Enter the full path to an image: "
    ).strip().strip('"')

    try:
        detections = detect_objects(image_path)
        grouped = group_detections(detections)

        print("\n" + "=" * 50)
        print("VISION ASSIST - OBJECT DETECTION")
        print("=" * 50)

        if not detections:
            print("No objects detected.")

        else:
            for item in grouped:
                print(
                    f"{item['label']}: {item['count']} "
                    f"(average confidence: "
                    f"{item['average_confidence']:.1%})"
                )

            print("\nIndividual detections:")

            for item in detections:
                print(
                    f"{item['label']} | "
                    f"{item['confidence']:.1%} | "
                    f"{item['position']} | "
                    f"{item['box']}"
                )

        print(f"\nTotal detections: {len(detections)}")

    except Exception as error:
        print(f"Could not process image: {error}")

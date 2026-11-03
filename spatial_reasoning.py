
def get_image_position(box, image_width, image_height):
    """Estimate an object's position within the image."""
    x1, y1, x2, y2 = box

    center_x = (x1 + x2) / 2
    center_y = (y1 + y2) / 2

    horizontal = (
        "left" if center_x < image_width / 3
        else "right" if center_x > 2 * image_width / 3
        else "center"
    )

    vertical = (
        "upper" if center_y < image_height / 3
        else "lower" if center_y > 2 * image_height / 3
        else "middle"
    )

    return f"{vertical}-{horizontal}"


def describe_detections(detections, image_width, image_height):
    """Turn YOLO detections into readable image-relative positions."""
    descriptions = []

    for item in detections:
        position = get_image_position(
            item["box"], image_width, image_height
        )

        descriptions.append({
            "label": item["label"],
            "confidence": item["confidence"],
            "position": position,
        })

    return descriptions


if __name__ == "__main__":
    sample_detections = [
        {"label": "bed", "confidence": 0.85, "box": [0, 500, 400, 850]},
        {"label": "person", "confidence": 0.91, "box": [500, 200, 750, 600]},
        {"label": "bottle", "confidence": 0.78, "box": [800, 300, 900, 450]},
    ]

    results = describe_detections(sample_detections, 1152, 864)

    print("VISION ASSIST - SPATIAL REASONING")
    for item in results:
        print(
            f"{item['label']}: {item['position']} "
            f"(confidence: {item['confidence']:.1%})"
        )


from capture_image import load_image


def main():
    print("=" * 45)
    print("           VISION ASSIST")
    print("     Visual Description Assistant")
    print("=" * 45)

    image_path = input("\nEnter the full path to a photo: ").strip().strip('"')

    try:
        image = load_image(image_path)

        print("\nImage loaded successfully!")
        print(f"Width: {image.width} pixels")
        print(f"Height: {image.height} pixels")
        print("\nReady for AI scene analysis!")
        
    except (FileNotFoundError, OSError) as error:
        print(f"\nCould not load image: {error}")


if __name__ == "__main__":
    main()

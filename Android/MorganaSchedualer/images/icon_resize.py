from PIL import Image
import os

# 1. הגדר את שם הקובץ המקורי
original_image_path = '/Users/elad/code/playground/Arduino/Android/MorganaSchedualer/images/logo.png'
main_dir = os.path.dirname(original_image_path)

# 2. הגדר את גדלי האייקונים הרצויים (בפיקסלים)
# אלו גדלים נפוצים עבור אנדרואיד (xxxhdpi, xxhdpi, xhdpi, hdpi, mdpi)
icon_sizes = {
    'mipmap-xxxhdpi': (192, 192),
    'mipmap-xxhdpi': (144, 144),
    'mipmap-xhdpi': (96, 96),
    'mipmap-hdpi': (72, 72),
    'mipmap-mdpi': (48, 48)
}

# 3. בדוק אם קובץ המקור קיים
if not os.path.exists(original_image_path):
    print(f"שגיאה: הקובץ '{original_image_path}' לא נמצא.")
    print("אנא שמור את הלוגו בתיקייה זו בשם 'morgana_logo.png'.")
else:
    # 4. פתח את התמונה המקורית
    with Image.open(original_image_path) as img:
        print(f"מעבד את התמונה: {original_image_path}")

        # 5. עבור בלולאה על כל הגדלים וצור גרסאות מוקטנות
        for folder_name, size in icon_sizes.items():
            # צור תיקייה אם היא לא קיימת
            if not os.path.exists(folder_name):
                os.makedirs(folder_name)

            # שנה את גודל התמונה באיכות גבוהה
            resized_img = img.resize(size, Image.Resampling.LANCZOS)

            # 6. שמור את הקובץ המוקטן בתוך התיקייה המתאימה
            icon_dir = os.path.join(main_dir, "../app/src/main/res", folder_name)
            if not os.path.exists(icon_dir):
                os.makedirs(icon_dir)
            output_filename = os.path.join(icon_dir, 'ic_launcher.png')
            resized_img.save(output_filename)

            print(f"נוצר אייקון: {output_filename} (בגודל {size[0]}x{size[1]})")

    print("\nהסתיים בהצלחה!")
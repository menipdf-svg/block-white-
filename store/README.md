# קטלוג החנות

הקטלוג הוא רק רשימת קישורים. אין כאן קבצי APK. כל רשומה ב-`catalog.json`:

```json
{
  "apps": [
    {
      "pkg": "org.fdroid.fdroid",
      "name": "F-Droid",
      "desc": "חנות קוד פתוח",
      "version": "1.20",
      "versionCode": 1020000,
      "url": "https://f-droid.org/F-Droid.apk",
      "cert": "<SHA-256 של תעודת החתימה>"
    },
    {
      "pkg": "com.example.app",
      "name": "אפליקציה מהחנות",
      "link": "https://play.google.com/store/apps/details?id=com.example.app"
    }
  ]
}
```

- **url** – קישור הורדה ישיר. האפליקציה מורידה ומתקינה בשקט. חובה לצרף הוכחה:
  - `cert` (מומלץ לקישורים שהקובץ בהם מתעדכן): SHA-256 של חתימת האפליקציה. הקובץ מתקבל רק אם החתימה תואמת.
  - `sha256`: SHA-256 של קובץ ה-APK עצמו. הקובץ נחסם אם הוא משתנה.
- **link** – קישור לדף האפליקציה (Google Play, F-Droid…). ההתקנה נעשית שם, והחבילה מותרת במצב "רק מהחנות".
- `versionCode` משמש לזיהוי עדכון.

חתימת אפליקציה מותקנת אפשר לקבל עם `apksigner verify --print-certs app.apk`
(השורה "certificate SHA-256 digest").

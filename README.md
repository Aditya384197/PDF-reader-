# Aditya Read Aloud

हल्का Android PDF Reader + Text-to-Speech पुस्तक-वाचन ऐप। PDF आपकी डिवाइस की निजी app storage में कॉपी होती है और ऐप खुद किसी cloud/server को PDF upload नहीं करता।

## मुख्य सुविधाएँ

- PDF चुनकर स्थायी लाइब्रेरी में जोड़ना
- किताब के कवर/नाम और पढ़े गए पृष्ठ की प्रगति दिखाना
- Android `PdfRenderer` से PDF पृष्ठ दिखाना
- pinch-to-zoom वाला सरल reader
- PDF text extraction के लिए PdfBox-Android
- text-less/scanned pages के लिए bundled ML Kit OCR (Latin + Devanagari)
- Android Text-to-Speech से लगातार पृष्ठ-दर-पृष्ठ वाचन
- पृष्ठ समाप्त होने पर “अगला पृष्ठ / Next page” संकेत
- 0.50x–2.00x गति नियंत्रण
- आवाज़ चयन
- auto language: Hindi/Devanagari या English/Latin
- live text panel में TTS range callback मिलने पर वर्तमान शब्द का highlight
- foreground playback service, इसलिए ऐप को background में भेजने पर वाचन जारी रह सकता है
- notification से पिछला/अगला/रोकें/चलाएँ/बंद नियंत्रण
- last page, speed और भाषा सेटिंग स्थायी रूप से सेव
- कोई `INTERNET` permission नहीं

## महत्वपूर्ण व्यवहार

लाइव शब्द highlight Android के चुने हुए TTS engine पर निर्भर है। `UtteranceProgressListener.onRangeStart()` तभी callback देता है जब speech engine timing/range information उपलब्ध कराए। ऐसे engine में audio चलेगा, लेकिन exact word highlight अपडेट नहीं हो पाएगा।

“मानवीय/पुरुष आवाज़” का वास्तविक timbre Android में उपलब्ध TTS engine और installed voice package पर निर्भर करता है; ऐप offline voice को प्राथमिकता देता है और उपलब्ध voices दिखाता है।

## GitHub Actions

`.github/workflows/build-apk.yml` पहले से शामिल है। GitHub में push या **Actions → Build Aditya Read Aloud APK → Run workflow** के बाद debug APK artifact के रूप में मिलेगा।

Build stack:

- Android Gradle Plugin 9.0.1
- Gradle 9.1.0
- Java 17
- Android compile SDK 36.1
- Jetpack Compose BOM 2026.09.00

## GitHub में डालने का तरीका

इस ZIP को extract करके उसकी सभी files/folders repository के root में रखें और commit/push करें। Workflow पहले से `.github/workflows/build-apk.yml` में है।

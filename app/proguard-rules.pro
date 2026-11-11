# Room, WorkManager and Compose ship their own consumer rules.
# SAX parser classes are looked up reflectively by SAXParserFactory on some devices.
-keep class org.apache.harmony.xml.** { *; }
-dontwarn javax.xml.**

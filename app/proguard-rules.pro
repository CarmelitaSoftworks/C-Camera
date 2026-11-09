# Los métodos nativos se resuelven por nombre desde C++ (JNI).
-keepclasseswithmembernames class * { native <methods>; }
-keep class com.carmelita.ccamera.processing.NativeEngine { *; }

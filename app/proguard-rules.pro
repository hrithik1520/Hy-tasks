# JNI: libhyllm looks these up by name (app/src/main/cpp/jni_bridge.cpp). Renaming or stripping
# them would crash the app, so keep them if code shrinking (R8) is ever turned on.
-keep class com.hy.assistant.llm.LlamaNative { native <methods>; }
-keep interface com.hy.assistant.llm.TokenCallback { *; }
-keepclassmembers class * implements com.hy.assistant.llm.TokenCallback { public boolean onBytes(byte[]); }

# KueTV code shrinking (v1, 26 Nov 2026). Purpose: a smaller app that the TV prepares faster after each update
# (the Fire TV home screen showed for 15-20 s while it did). Names are kept as they are, and everything
# KueTV's own code and the parts that are reached by name (VLC, the VPN engine, torrents, Gson) are kept whole;
# only the unused parts of the other libraries go.
-dontobfuscate
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod,SourceFile,LineNumberTable

# KueTV's own code: Gson reads and writes its classes by their field names.
-keep class art.kquinn.kuetv.** { *; }

# Parts that native code or the system reach by name.
-keep class org.videolan.** { *; }
-keep class de.blinkt.openvpn.** { *; }
-keep class org.libtorrent4j.** { *; }
-keep class org.spongycastle.** { *; }
-keep class org.bouncycastle.** { *; }
-keep class com.google.gson.** { *; }
-keepclassmembers,allowobfuscation class * { @com.google.gson.annotations.SerializedName <fields>; }

# Classes the libraries only mention, never needed on the TV.
-dontwarn **

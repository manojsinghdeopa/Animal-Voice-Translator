# Keep application entry point
-keep class com.yantraman.animalsoundmaker.** { *; }

# Keep annotations
-keepattributes *Annotation*

# Keep MainActivity and other activities (prevents UI crashes)
-keep class * extends android.app.Activity { *; }

# Google Play Services and Firebase (if used)
-keep class com.google.** { *; }
-dontwarn com.google.**

# gRPC (required for Google Cloud APIs)
-keep class io.grpc.** { *; }
-dontwarn io.grpc.**

# ML Kit and Text-to-Speech
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**
-keep class com.google.cloud.texttospeech.** { *; }
-dontwarn com.google.cloud.texttospeech.**

# Prevent stripping Parcelable implementations
-keep class * implements android.os.Parcelable { *; }
-keep class * implements java.io.Serializable { *; }

# Keep lambda expressions (prevents crashes in Kotlin)
-keepclassmembers class * {
    *** lambda*(...);
}

# Keep Retrofit/Gson (if used in networking)
-keep class com.google.gson.** { *; }
-dontwarn com.google.gson.**

# Keep required R8 rules for proper optimization
-keepattributes SourceFile,LineNumberTable

# Prevent obfuscation of enums
-keepclassmembers enum * { *; }

# Ensure reflection works properly
-keepclassmembers class * {
    @android.webkit.JavascriptInterface <methods>;
}

# Avoid issues with dynamic class loading
-keep class * {
    public <init>(...);
}

# This is generated automatically by the Android Gradle plugin.
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean
-dontwarn javax.naming.InvalidNameException
-dontwarn javax.naming.NamingException
-dontwarn javax.naming.directory.Attribute
-dontwarn javax.naming.directory.Attributes
-dontwarn javax.naming.ldap.LdapName
-dontwarn javax.naming.ldap.Rdn
-dontwarn javax.servlet.ServletContextListener
-dontwarn org.apache.avalon.framework.logger.Logger
-dontwarn org.apache.log.Hierarchy
-dontwarn org.apache.log.Logger
-dontwarn org.apache.log4j.Level
-dontwarn org.apache.log4j.Logger
-dontwarn org.apache.log4j.Priority
-dontwarn org.ietf.jgss.GSSContext
-dontwarn org.ietf.jgss.GSSCredential
-dontwarn org.ietf.jgss.GSSException
-dontwarn org.ietf.jgss.GSSManager
-dontwarn org.ietf.jgss.GSSName
-dontwarn org.ietf.jgss.Oid


# Optimize code further
-optimizations !code/simplification/arithmetic

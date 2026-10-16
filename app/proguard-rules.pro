# svn-space ProGuard rules
-keepattributes *Annotation*
-keepclassmembers class * {
    @androidx.annotation.Keep *;
}

# AppAuth
-keep class net.openid.appauth.** { *; }

# Keep our models
-keep class space.svn.svnspace.data.model.** { *; }

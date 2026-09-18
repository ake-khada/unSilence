# Error Prone's source-analysis annotation mentions a javac-only enum. Android
# never evaluates that annotation; this exception belongs only to the test APK.
-dontwarn javax.lang.model.element.Modifier

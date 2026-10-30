# Instrumentation shares the target app's Kotlin runtime. The separately
# compiled AndroidX runner calls these methods; app-only R8 cannot see them.
# Applied only with -PvalidationBuildType=release, never to shipping builds.
-keep class kotlin.** { *; }
-keep class kotlinx.coroutines.** { *; }
-keep class androidx.tracing.** { *; }
# Inline serialization calls in the test APK link the target's runtime too.
-keep class kotlinx.serialization.** { *; }

# Test-created WorkerParameters links this otherwise unused AndroidX helper.
-keep class androidx.work.WorkerParameters$RuntimeExtras { *; }
-keep,allowobfuscation class androidx.work.WorkerParameters { *; }
-keep,allowobfuscation interface androidx.work.impl.utils.taskexecutor.TaskExecutor { *; }
-keep,allowobfuscation interface androidx.work.impl.utils.taskexecutor.SerialExecutor { *; }
-keep,allowobfuscation interface androidx.work.ProgressUpdater { *; }
-keep,allowobfuscation interface androidx.work.ForegroundUpdater { *; }

# These two app entry points are called by the separately compiled fixture.
# Keep only this test linkage; normal shipping optimization is unchanged.
-keep,allowobfuscation class com.unsilence.app.ui.feed.FeedSavedStateKt {
    public static java.lang.String encodeFeedSession(com.unsilence.app.ui.feed.SavedFeedSession);
    public static com.unsilence.app.ui.feed.SavedFeedSession decodeFeedSession(java.lang.String, java.lang.String);
}

# Explicit cross-APK call boundaries used by ReleasePersistenceTest. Retain
# their descriptors against inlining/return-type specialization; app classes
# and these members may still be obfuscated. None of these rules ships.
-keep,allowobfuscation class com.unsilence.app.data.relay.NostrJsonKt {
    public static kotlinx.serialization.json.Json getNostrJson();
}
-keep,allowobfuscation class com.unsilence.app.data.relay.NostrEventDtoKt {
    public static com.unsilence.app.data.memory.NostrEvent toNostrEvent(...);
}
-keep,allowobfuscation class com.unsilence.app.data.wallet.NwcResponseAuthenticatorKt {
    public static com.unsilence.app.data.wallet.AuthenticatedNwcResponse authenticateNwcResponse(...);
}
-keepclassmembers,allowobfuscation class com.unsilence.app.data.wallet.NwcResponseExpectation {
    public <init>(...);
}
-keepclassmembers,allowobfuscation class com.unsilence.app.data.wallet.AuthenticatedNwcResponse {
    public kotlinx.serialization.json.JsonObject getPayload();
}
-keepclassmembers,allowobfuscation class com.unsilence.app.UnsilenceApp {
    public androidx.hilt.work.HiltWorkerFactory getWorkerFactory();
    public com.unsilence.app.data.relay.Subscription getSubscription();
}
-keepclassmembers,allowobfuscation class androidx.hilt.work.HiltWorkerFactory {
    public androidx.work.ListenableWorker createWorker(...);
}
-keep,allowobfuscation interface com.unsilence.app.data.relay.TimelineEventLoader { *; }
-keepclassmembers,allowobfuscation class com.unsilence.app.data.relay.TimelineEventResolution {
    public <init>(...);
}
-keepclassmembers,allowobfuscation class com.unsilence.app.data.relay.TimelineService {
    public <init>(...);
}
-keepclassmembers,allowobfuscation class com.unsilence.app.data.memory.MemoryEventStore {
    public <init>(...);
    public void setOwnPubkey(java.lang.String);
    public boolean insert(com.unsilence.app.data.memory.NostrEvent);
    public com.unsilence.app.data.memory.NostrEvent getNostrEvent(java.lang.String);
    *** saveSnapshotBinary*(...);
    *** restoreSnapshotBinary(...);
}
-keepclassmembers,allowobfuscation class com.unsilence.app.data.memory.NostrEvent {
    public java.lang.String getId();
    public java.lang.String getContent();
    public java.lang.String getSig();
    public java.util.List getTags();
}
